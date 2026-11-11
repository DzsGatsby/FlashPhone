#!/usr/bin/env python3
"""Flash phone adapter for Grok Bot's existing execution-on-this-Mac route."""
import argparse
import json
import os
from pathlib import Path
import socket
import socketserver
import stat
import sys
import threading

from flashphone import FlashClient, ClientError, MAX_RESPONSE

SOCKET = Path.home() / '.grokbot/flash-phone/bridge.sock'
MAX_REQUEST = 131_072
TOOLS = frozenset('flashphone_' + x for x in (
    'devices', 'call_task', 'task_status', 'wait_task', 'cancel_task', 'hangup_task', 'recording'))


def dispatch(client, request):
    if not isinstance(request, dict):
        raise ClientError('Request must be an object')
    operation = request.get('operation')
    if operation == 'devices':
        return client.tool('flashphone_devices', {})
    if operation == 'tools':
        return client.rpc({'jsonrpc': '2.0', 'id': 'tools', 'method': 'tools/list'})
    if operation != 'call' or request.get('name') not in TOOLS:
        raise ClientError('Unsupported Flash phone operation')
    arguments = request.get('arguments', {})
    if not isinstance(arguments, dict):
        raise ClientError('Tool arguments must be an object')
    return client.tool(request['name'], arguments)


class Handler(socketserver.StreamRequestHandler):
    slots = threading.BoundedSemaphore(4)

    def handle(self):
        self.request.settimeout(10)
        acquired = self.slots.acquire(blocking=False)
        try:
            if not acquired:
                raise ClientError('Flash phone bridge is busy')
            raw = self.rfile.readline(MAX_REQUEST + 1)
            if len(raw) > MAX_REQUEST or not raw.endswith(b'\n'):
                raise ClientError('Incomplete or oversized request')
            result = dispatch(self.server.client, json.loads(raw))
            reply = {'result': result}
        except ClientError as e:
            reply = {'error': str(e)}
        except Exception:
            # Transport details may contain configuration; never copy them to the bot.
            reply = {'error': 'Flash phone request failed; query the existing task before any new dial'}
        finally:
            if acquired:
                self.slots.release()
        encoded = json.dumps(reply, ensure_ascii=False).encode() + b'\n'
        if len(encoded) > MAX_RESPONSE:
            encoded = b'{"error":"Flash phone response exceeds limit"}\n'
        try:
            self.wfile.write(encoded)
        except OSError:
            pass


class Server(socketserver.ThreadingUnixStreamServer):
    daemon_threads = True


def check_directory(directory):
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_mode & 0o077 or info.st_uid != os.getuid():
        raise ClientError('Bridge directory must be private and owned by this user')


def serve():
    os.umask(0o077)
    SOCKET.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    check_directory(SOCKET.parent)
    if SOCKET.exists() or SOCKET.is_symlink():
        info = SOCKET.lstat()
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
            raise ClientError('Refusing to replace a non-socket bridge path')
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as probe:
            probe.settimeout(1)
            try:
                probe.connect(str(SOCKET))
            except ConnectionRefusedError:
                SOCKET.unlink()
            else:
                raise ClientError('Flash phone bridge is already running')
    client = FlashClient.from_file(Path.home() / 'Library/Application Support/Flash phone/controller.json')
    with Server(str(SOCKET), Handler) as server:
        os.chmod(SOCKET, 0o700)
        server.client = client
        server.serve_forever()


def invoke(request):
    check_directory(SOCKET.parent)
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(45)
        connection.connect(str(SOCKET))
        encoded = json.dumps(request, ensure_ascii=False).encode() + b'\n'
        if len(encoded) > MAX_REQUEST:
            raise ClientError('Request exceeds limit')
        connection.sendall(encoded)
        with connection.makefile('rb') as stream:
            raw = stream.readline(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE or not raw.endswith(b'\n'):
                raise ClientError('Incomplete or oversized response')
            reply = json.loads(raw)
    if reply.get('error'):
        raise ClientError(reply['error'])
    return reply['result']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='operation', required=True)
    commands.add_parser('serve', help=argparse.SUPPRESS)
    commands.add_parser('devices')
    commands.add_parser('tools')
    call = commands.add_parser('call')
    call.add_argument('name', choices=sorted(TOOLS))
    call.add_argument('arguments', nargs='?', default='{}')
    args = parser.parse_args()
    try:
        if args.operation == 'serve':
            serve()
            return 0
        request = {'operation': args.operation}
        if args.operation == 'call':
            request.update(name=args.name, arguments=json.loads(args.arguments))
        result = invoke(request)
        print(json.dumps(result, ensure_ascii=False))
        return 1 if result.get('isError') else 0
    except (ClientError, OSError, ValueError):
        print('Flash phone bridge unavailable or request invalid; do not redial an uncertain task', file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
