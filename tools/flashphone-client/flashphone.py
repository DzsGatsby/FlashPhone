#!/usr/bin/env python3
"""Flash phone HTTPS client and newline-JSON MCP stdio adapter. No dependencies."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

DEFAULT_CONFIG = Path.home() / 'Library/Application Support/Flash phone/controller.json'
MAX_RESPONSE = 4 * 1024 * 1024

class ClientError(Exception):
    pass

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ClientError('Redirect refused: controller credentials stay on the configured origin')

class FlashClient:
    def __init__(self, origin, token, allow_loopback=False):
        parsed = urllib.parse.urlsplit(origin)
        local = parsed.hostname in ('127.0.0.1', 'localhost', '::1')
        if parsed.scheme != 'https' and not (allow_loopback and local and parsed.scheme == 'http'):
            raise ClientError('An HTTPS controller origin is required')
        if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
            raise ClientError('The controller URL must be an origin without credentials, path or query')
        if not isinstance(token, str) or len(token) < 32 or len(token) > 512 or any(c.isspace() for c in token):
            raise ClientError('Invalid controller token')
        self.origin, self.token = origin.rstrip('/'), token
        self.opener = urllib.request.build_opener(NoRedirect())

    @classmethod
    def from_file(cls, path):
        flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0)
        try:
            fd = os.open(path, flags)
            with os.fdopen(fd, 'r') as f:
                info = os.fstat(f.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
                    raise ClientError('Controller configuration must be a private regular file (chmod 600)')
                if hasattr(os, 'getuid') and info.st_uid != os.getuid():
                    raise ClientError('Controller configuration belongs to another user')
                raw = f.read(16_385)
                if len(raw) > 16_384:
                    raise ClientError('Controller configuration is too large')
                config = json.loads(raw)
            return cls(config['origin'], config['token'])
        except (OSError, ValueError, KeyError) as e:
            raise ClientError('Controller configuration is missing or invalid') from e

    def request(self, path, body=None, method=None, timeout=35):
        if not path.startswith('/') or path.startswith('//') or '\\' in path:
            raise ClientError('Invalid API path')
        encoded = None if body is None else json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode()
        if encoded is not None and len(encoded) > 131_072:
            raise ClientError('Request exceeds the control protocol limit')
        req = urllib.request.Request(self.origin + path, data=encoded, method=method or ('POST' if encoded else 'GET'),
            headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json',
                     'User-Agent': 'FlashPhoneClient/1.0', 'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-06-18'})
        try:
            with self.opener.open(req, timeout=timeout) as response:
                data = response.read(MAX_RESPONSE + 1)
                if len(data) > MAX_RESPONSE:
                    raise ClientError('Control response is too large')
                return json.loads(data) if data else None
        except urllib.error.HTTPError as e:
            code = e.code
            e.close()
            raise ClientError('Controller HTTP ' + str(code)) from e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            raise ClientError('Connection interrupted; outcome is uncertain. Query the existing task before any new dial.') from e
        except ValueError as e:
            raise ClientError('Controller returned invalid JSON') from e

    def rpc(self, message):
        # Never retry an RPC here: it may have already created a phone call.
        return self.request('/mcp', message)

    def tool(self, name, arguments):
        result = self.rpc({'jsonrpc': '2.0', 'id': str(uuid.uuid4()), 'method': 'tools/call',
                           'params': {'name': name, 'arguments': arguments}})
        if not isinstance(result, dict):
            raise ClientError('Missing MCP response')
        if result.get('error'):
            raise ClientError('MCP error: ' + str(result['error'].get('message', 'request failed'))[:240])
        return result.get('result')

    def recording(self, task_id, destination):
        if not valid_id(task_id):
            raise ClientError('Invalid task id')
        destination = Path(destination)
        if destination.exists():
            raise ClientError('Recording destination already exists')
        metadata = self.tool('flashphone_recording', {'task_id': task_id})
        if metadata.get('isError'):
            raise ClientError('Recording is not available for this task')
        recording = (metadata.get('structuredContent') or {}).get('recording')
        if not isinstance(recording, dict):
            raise ClientError('Recording is not ready')
        expected_bytes, expected_sha = recording.get('bytes'), recording.get('sha256')
        if not isinstance(expected_bytes, int) or not 0 < expected_bytes <= 40 * 1024 * 1024 or not isinstance(expected_sha, str) or len(expected_sha) != 64:
            raise ClientError('Invalid recording metadata')
        temporary = None
        try:
            req = urllib.request.Request(self.origin + '/api/tasks/' + task_id + '/recording',
                headers={'Authorization': 'Bearer ' + self.token, 'User-Agent': 'FlashPhoneClient/1.0'})
            digest, total = hashlib.sha256(), 0
            fd, private = tempfile.mkstemp(prefix='.flashphone-', suffix='.download', dir=destination.parent)
            temporary = Path(private)
            with os.fdopen(fd, 'wb') as output, self.opener.open(req, timeout=35) as response:
                os.chmod(temporary, 0o600)
                if response.headers.get('Content-Length') != str(expected_bytes):
                    raise ClientError('Recording length does not match its metadata')
                while block := response.read(65_536):
                    total += len(block)
                    if total > 40 * 1024 * 1024:
                        raise ClientError('Recording exceeds 40 MiB')
                    digest.update(block)
                    output.write(block)
            if total != expected_bytes or digest.hexdigest() != expected_sha:
                raise ClientError('Recording is incomplete or its checksum does not match')
            os.link(temporary, destination)  # Fails rather than replacing a concurrent user's file.
            temporary.unlink()
            return {'file': str(destination), 'bytes': total, 'sha256': digest.hexdigest()}
        except Exception:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
            raise


def valid_id(value):
    try:
        return str(uuid.UUID(value)) == value.lower()
    except (ValueError, AttributeError):
        return False


def serve_stdio(client):
    while raw := sys.stdin.buffer.readline(131_073):
        message = None
        try:
            if len(raw) > 131_072:
                while raw and not raw.endswith(b'\n'):
                    raw = sys.stdin.buffer.readline(131_073)
                raise ClientError('MCP input is too large')
            message = json.loads(raw)
            if not isinstance(message, dict):
                raise ClientError('MCP input must be an object')
            response = client.rpc(message)
            if 'id' in message and response is not None:
                print(json.dumps(response, ensure_ascii=False, separators=(',', ':')), flush=True)
        except Exception as e:
            text = str(e) if isinstance(e, ClientError) else 'Invalid MCP input'
            if isinstance(message, dict) and 'id' in message:
                print(json.dumps({'jsonrpc': '2.0', 'id': message['id'], 'error': {'code': -32000, 'message': text}}), flush=True)
            else:
                print('Flash phone MCP: ' + text, file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('mcp')
    commands.add_parser('devices')
    commands.add_parser('tools')
    commands.add_parser('open-dashboard')
    call = commands.add_parser('call')
    call.add_argument('--device', required=True)
    call.add_argument('--number', required=True)
    call.add_argument('--goal', required=True)
    call.add_argument('--question', action='append', default=[])
    call.add_argument('--context', default='')
    call.add_argument('--constraints', default='')
    call.add_argument('--sim')
    call.add_argument('--idempotency-key', required=True, help='Stable key for this user-confirmed call intention')
    for name in ['status', 'wait', 'cancel', 'hangup', 'recording']:
        sub = commands.add_parser(name)
        sub.add_argument('task_id')
        if name == 'wait':
            sub.add_argument('--seconds', type=int, default=25, choices=range(1, 26), metavar='1..25')
        if name == 'recording':
            sub.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        client = FlashClient.from_file(args.config)
        if args.command == 'mcp':
            return serve_stdio(client)
        if args.command == 'open-dashboard':
            link = client.request('/api/session-links', {'label': 'Mac控制台'})
            code = link.get('code', '')
            if not isinstance(code, str) or len(code) != 8 or not code.isalnum():
                raise ClientError('Invalid one-use login code')
            subprocess.run(['/usr/bin/open', client.origin + '/#login_code=' + urllib.parse.quote(code, safe='')],
                check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            result = {'opened': True, 'origin': client.origin}
        elif args.command == 'tools':
            result = client.rpc({'jsonrpc': '2.0', 'id': 'tools', 'method': 'tools/list'})
        elif args.command == 'devices':
            result = client.tool('flashphone_devices', {})
        elif args.command == 'call':
            arguments = {'device_id': args.device, 'number': args.number,
                'task': {'goal': args.goal, 'questions': args.question, 'context': args.context, 'constraints': args.constraints},
                'idempotency_key': args.idempotency_key}
            if args.sim:
                arguments['sim_id'] = args.sim
            result = client.tool('flashphone_call_task', arguments)
        elif args.command == 'recording':
            result = client.recording(args.task_id, args.output)
        else:
            name = {'status': 'flashphone_task_status', 'wait': 'flashphone_wait_task',
                    'cancel': 'flashphone_cancel_task', 'hangup': 'flashphone_hangup_task'}[args.command]
            arguments = {'task_id': args.task_id}
            if args.command == 'wait':
                arguments['timeout_seconds'] = args.seconds
            result = client.tool(name, arguments)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        if isinstance(result, dict) and (result.get('isError') or result.get('error')):
            return 1
    except ClientError as e:
        print(json.dumps({'error': str(e)}, ensure_ascii=False), file=sys.stderr)
        return 2
    except (urllib.error.HTTPError, OSError) as e:
        print(json.dumps({'error': 'Recording download failed; no file was replaced'}), file=sys.stderr)
        return 2
    return 0

if __name__ == '__main__':
    sys.exit(main() or 0)
