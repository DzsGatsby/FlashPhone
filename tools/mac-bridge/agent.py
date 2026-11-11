#!/usr/bin/env python3
"""Outbound-only Flash phone device agent. No call replay and no audio/secret logging."""
import argparse
import asyncio
import contextlib
import hashlib
import json
import os
from pathlib import Path
import random
import signal
import sqlite3
import stat
import sys
import time
import urllib.parse
import urllib.request
import uuid

TERMINAL = frozenset(('ended', 'failed', 'interrupted', 'canceled'))
MAX_MESSAGE = 16384

def now_ms():
    return int(time.time() * 1000)

def private_dir(path):
    path = Path(path).expanduser()
    if path.is_symlink():
        raise ValueError('state_directory_symlink')
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.stat().st_uid != os.getuid() or stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError('state_directory_must_be_private')
    return path

def read_private(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        s = os.fstat(fd)
        if not stat.S_ISREG(s.st_mode) or s.st_uid != os.getuid() or s.st_mode & 0o077 or s.st_size > 65536:
            raise ValueError('private_file_permissions_or_size')
        with os.fdopen(fd, 'r', closefd=False) as f:
            return json.load(f)
    finally:
        os.close(fd)

def atomic_private(path, value):
    path = Path(path)
    temp = path.with_name('.' + path.name + '.' + uuid.uuid4().hex)
    fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'w', closefd=False) as f:
            json.dump(value, f, separators=(',', ':'))
            f.flush()
            os.fsync(fd)
        os.replace(temp, path)
    finally:
        os.close(fd)
        with contextlib.suppress(FileNotFoundError): temp.unlink()

def base_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
        raise ValueError('https_origin_required')
    return url.rstrip('/')

class Store:
    def __init__(self, directory):
        self.directory = private_dir(directory)
        path = self.directory / 'state.sqlite3'
        if path.exists(): read_mode = path.stat().st_mode
        else: read_mode = 0o600
        if path.is_symlink() or read_mode & 0o077: raise ValueError('database_must_be_private')
        old = os.umask(0o077)
        try: self.db = sqlite3.connect(path)
        finally: os.umask(old)
        self.db.executescript('''
          PRAGMA journal_mode=DELETE;
          PRAGMA synchronous=FULL;
          CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY, digest TEXT NOT NULL, task TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY, phase TEXT NOT NULL, seq INTEGER NOT NULL DEFAULT 0, call_id TEXT, result TEXT);
          CREATE TABLE IF NOT EXISTS events(task TEXT NOT NULL, seq INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(task,seq));
        ''')
        if 'result' not in {row[1] for row in self.db.execute('PRAGMA table_info(tasks)')}:
            with self.db: self.db.execute('ALTER TABLE tasks ADD COLUMN result TEXT')

    def event(self, task, phase, call_id=None, reason=None, carrier_ended=False, result=None):
        # The caller owns the transaction; these entries survive until positive server ACK.
        self.db.execute('INSERT OR IGNORE INTO tasks(id,phase) VALUES(?,?)', (task, phase))
        current = self.db.execute('SELECT seq,call_id,phase,result FROM tasks WHERE id=?', (task,)).fetchone()
        if current[1] is not None and call_id is not None and current[1] != call_id:
            raise ValueError('call_ownership_changed')
        call_id = call_id if call_id is not None else current[1]
        seq = current[0] + 1
        event = dict(v=1, type='event', task_id=task, sequence=seq, phase=phase,
                     carrier_ended=carrier_ended, ai_active=phase == 'ai_active', work_id=None,
                     call_id=call_id, result=result or ({'reason': reason} if reason else None))
        encoded = json.dumps(event, separators=(',', ':'))
        if len(encoded.encode()) > 65536: raise ValueError('result_too_large')
        if self.db.execute('SELECT count(*) FROM events').fetchone()[0] >= 2048:
            raise ValueError('unacknowledged_spool_full')
        archived = json.loads(current[3]) if current[3] else {}
        if event['result'] is not None: archived.update(event['result'])
        archive_body = json.dumps(archived, separators=(',', ':')) if archived else None
        if archive_body and len(archive_body.encode()) > 65536: raise ValueError('archive_result_too_large')
        self.db.execute('UPDATE tasks SET phase=?,seq=?,call_id=?,result=? WHERE id=?', (current[2] if phase == 'recording_ready' else phase, seq, call_id, archive_body, task))
        self.db.execute('INSERT INTO events VALUES(?,?,?)', (task, seq, encoded))
        return event

    def recover(self):
        with self.db:
            rows = self.db.execute('SELECT id,phase FROM tasks').fetchall()
            for task, phase in rows:
                if phase not in TERMINAL:
                    self.event(task, 'interrupted', reason='agent_restarted_no_replay')

    def accept(self, command):
        digest = hashlib.sha256(json.dumps(command, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        with self.db:
            row = self.db.execute('SELECT digest FROM commands WHERE id=?', (command['command_id'],)).fetchone()
            if row:
                if row[0] != digest: raise ValueError('command_id_payload_conflict')
                return False
            if self.db.execute('SELECT count(*) FROM commands').fetchone()[0] >= 8192:
                # Do not evict dedupe tombstones and accidentally allow an old command to run again.
                raise ValueError('dedupe_full_requires_local_maintenance')
            seen_task = self.db.execute('SELECT 1 FROM commands WHERE task=? LIMIT 1', (command['task_id'],)).fetchone()
            self.db.execute('INSERT INTO commands VALUES(?,?,?)', (command['command_id'], digest, command['task_id']))
            if command['action'] == 'dial':
                if seen_task: return False
                self.event(command['task_id'], 'accepted')
        return True

    def pending(self):
        return [json.loads(r[0]) for r in self.db.execute('SELECT body FROM events ORDER BY task,seq')]

    def ack(self, task, seq):
        if not isinstance(task, str) or type(seq) is not int or seq < 1: return
        with self.db:
            # ACK exactly one event; never allow a speculative high ACK to delete an unsent future event.
            self.db.execute('DELETE FROM events WHERE task=? AND seq=?', (task, seq))
            excess = self.db.execute('SELECT count(*) FROM tasks').fetchone()[0] - 200
            if excess > 0:
                rows = self.db.execute("SELECT id FROM tasks WHERE phase IN ('ended','failed','interrupted','canceled') AND NOT EXISTS (SELECT 1 FROM events WHERE events.task=tasks.id) ORDER BY rowid LIMIT ?", (excess,)).fetchall()
                self.db.executemany('DELETE FROM tasks WHERE id=?', rows)

    def close(self): self.db.close()

def validate_command(command, device_id, clock=now_ms):
    if not isinstance(command, dict): raise ValueError('invalid_command')
    for key in ('command_id', 'task_id', 'device_id'):
        value = command.get(key)
        if not isinstance(value, str) or str(uuid.UUID(value)) != value: raise ValueError('invalid_identifier')
    if command['device_id'] != device_id: raise ValueError('wrong_device')
    if command.get('action') not in ('dial', 'cancel', 'hangup'): raise ValueError('invalid_action')
    expected = command.get('expected_call_id')
    if expected is not None:
        if not isinstance(expected, str) or str(uuid.UUID(expected)) != expected: raise ValueError('invalid_call_binding')
    issued, expires = command.get('issued_at'), command.get('expires_at')
    now = clock()
    if type(issued) is not int or type(expires) is not int or expires <= now or issued > now + 5000 or expires <= issued or expires - issued > 120000:
        raise ValueError('expired_or_invalid_lease')
    if command['action'] == 'dial':
        p = command.get('payload')
        if not isinstance(p, dict): raise ValueError('invalid_payload')
        number, brief = p.get('number'), p.get('task')
        sim = p.get('sim_id')
        if sim is not None and (not isinstance(sim, str) or not sim or len(sim) > 256): raise ValueError('invalid_sim_id')
        if not isinstance(number, str) or not (3 <= len(number) <= 24) or not number.lstrip('+').isascii() or not number.lstrip('+').isdigit() or '+' in number[1:]:
            raise ValueError('invalid_number')
        if not isinstance(brief, dict) or set(brief) - {'goal', 'questions', 'context', 'constraints'}: raise ValueError('invalid_task')
        limits = {'goal': 1000, 'context': 4000, 'constraints': 2000}
        for key, limit in limits.items():
            value = brief.get(key, '')
            if not isinstance(value, str) or len(value) > limit or (key == 'goal' and not value.strip()): raise ValueError('invalid_task')
        q = brief.get('questions', [])
        if not isinstance(q, list) or len(q) > 20 or any(not isinstance(x, str) or not x.strip() or len(x) > 500 for x in q): raise ValueError('invalid_task')
    return command

class Readiness:
    """No synthetic loopback or user-editable flag is proof of a carrier call. Fail closed."""
    def __init__(self, setup_reason=None):
        self.setup_reason=setup_reason
    def status(self):
        if self.setup_reason=='iphone_account_requirement':
            return {'ready':False,'blocked':True,'reason':'iphone_account_requirement','real_call_verified':False,
                    'message':'iPhone 通话暂不可用：Apple 账号条件未满足。',
                    'remediation':'请机主核对 iPhone 的 iCloud、FaceTime 和 Mac 电话应用使用的 Apple 账号；完成前不会自动切换账号或重拨。',
                    'control_verified':False,'phone_rx_verified':False,'phone_tx_verified':False}
        return {'ready': False, 'reason': 'requires_setup', 'control_verified': False,
                'phone_rx_verified': False, 'phone_tx_verified': False,
                'real_call_verified': False,
                'detail': 'Local control, scoped RX, virtual TX and realtime setup must pass; real-call acceptance is separate.'}
    def capabilities(self): return []

def secure_connect(*args, **kwargs):
    import websockets
    class NoRedirectConnect(websockets.connect):
        def process_redirect(self, error):
            # websockets otherwise retains Authorization across cross-origin redirects.
            return error
    return NoRedirectConnect(*args, **kwargs)

class Agent:
    def __init__(self, config, store, readiness=None, executor=None):
        self.config, self.store = config, store
        self.readiness = readiness or Readiness(config.get('setup_reason'))
        self.executor = executor
        self.running = None
        self.device_id = config['device_id']
        uuid.UUID(self.device_id)
        base_url(config['server'])

    async def command(self, command):
        validate_command(command, self.device_id)
        if not self.store.accept(command): return
        task = command['task_id']
        row = self.store.db.execute('SELECT phase,call_id FROM tasks WHERE id=?', (task,)).fetchone()
        async def emit(phase, **fields):
            with self.store.db: self.store.event(task, phase, **fields)
        if command['action'] == 'dial':
            if self.running is not None and not self.running.done():
                await emit('failed', reason='device_busy'); return
            if self.executor is None:
                await emit('failed', reason='requires_setup'); return
            async def execute():
                try:
                    validate_command(command, self.device_id)
                    await self.executor.dial(command, emit)
                except asyncio.CancelledError:
                    await emit('interrupted', reason='device_agent_stopped_no_replay'); raise
                except Exception:
                    # Carrier state is unknown unless the adapter positively proves an ended call.
                    await emit('interrupted', reason='local_execution_failed')
            self.running = asyncio.create_task(execute())
        elif row and row[0] not in TERMINAL:
            expected = command.get('expected_call_id')
            if expected != row[1] or (command['action'] == 'hangup' and row[1] is None):
                raise ValueError('wrong_active_call_binding')
            if self.executor is None:
                await emit('interrupted', reason='no_bound_carrier_call'); return
            if command['action'] == 'cancel': await self.executor.cancel(command, emit)
            else: await self.executor.hangup(command, emit)

    async def connected(self, ws):
        hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=15))
        if not isinstance(hello, dict) or hello.get('v') != 1 or hello.get('type') != 'hello_ack' or hello.get('device_id') != self.device_id or hello.get('protocol') != 1:
            raise ValueError('invalid_device_handshake')
        sent = set()
        while True:
            for event in self.store.pending():
                key = (event['task_id'], event['sequence'])
                if key not in sent:
                    await ws.send(json.dumps(event)); sent.add(key)
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=0.5)
            except asyncio.TimeoutError:
                continue
            message = json.loads(raw)
            if not isinstance(message, dict) or message.get('v') != 1: raise ValueError('invalid_envelope')
            if message.get('type') == 'ack':
                self.store.ack(message.get('task_id'), message.get('sequence'))
            elif message.get('type') == 'command':
                try: await self.command(message.get('command'))
                except (ValueError, TypeError, KeyError):
                    print('Rejected invalid device command.', file=sys.stderr)

    async def run(self):
        import websockets
        self.store.recover()
        delay = 2
        while True:
            try:
                server = base_url(self.config['server'])
                uri = 'wss' + server[5:] + '/api/device/socket'
                # Native device credential goes only to this configured HTTPS/WSS origin.
                async with secure_connect(uri, additional_headers={'Authorization': 'Bearer ' + self.config['device_token']},
                                              user_agent_header='FlashPhoneMacBridge/1.0',
                                              max_size=MAX_MESSAGE, ping_interval=60, ping_timeout=30,
                                              open_timeout=15, close_timeout=5) as ws:
                    delay = 2
                    status = await self.executor.readiness() if self.executor else self.readiness.status()
                    capabilities = ['calls.dial', 'calls.cancel', 'calls.hangup'] if status.get('ready') else []
                    await ws.send(json.dumps(dict(v=1, type='hello', capabilities=capabilities, status=status)))
                    await self.connected(ws)
            except asyncio.CancelledError: raise
            except Exception as error:
                status = getattr(getattr(error, 'response', None), 'status_code', None)
                close = getattr(error, 'rcvd', None)
                if status in (401, 403) or getattr(close, 'code', None) == 4003:
                    print('Device authorization revoked; stopped without replay.', file=sys.stderr)
                    return
                print('Device transport unavailable; reconnecting with bounded backoff.', file=sys.stderr)
                await asyncio.sleep(delay + random.uniform(0, delay / 4))
                delay = min(60, delay * 2)

def enroll(config_path, code_path, server, name):
    path = Path(config_path).expanduser()
    private_dir(path.parent)
    if path.exists(): raise ValueError('refusing_existing_device_identity')
    code = read_private(code_path).get('code')
    if not isinstance(code, str) or not code: raise ValueError('enrollment_code_missing')
    server = base_url(server)
    request = urllib.request.Request(server + '/api/device-enroll',
        data=json.dumps(dict(code=code, name=name, platform='iphone-mac', capabilities=[])).encode(),
        headers={'Content-Type': 'application/json', 'User-Agent':'FlashPhoneMacBridge/1.0'}, method='POST')
    # No HTTP redirect may forward an enrollment credential to another endpoint.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs): return None
    with urllib.request.build_opener(NoRedirect).open(request, timeout=15) as response:
        body = json.loads(response.read(65537))
    uuid.UUID(body['device_id'])
    token = body['device_token']
    if not isinstance(token, str) or len(token) < 16 or '\n' in token: raise ValueError('invalid_device_token')
    atomic_private(path, dict(server=server, device_id=body['device_id'], device_token=token))
    print('Device identity saved privately; telephone capability remains requires_setup.')

def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    run = sub.add_parser('run'); run.add_argument('--config', required=True); run.add_argument('--state', required=True)
    e = sub.add_parser('enroll'); e.add_argument('--config', required=True); e.add_argument('--code-file', required=True); e.add_argument('--server', required=True); e.add_argument('--name', default='Mac iPhone bridge')
    sub.add_parser('readiness')
    args = parser.parse_args()
    if args.command == 'readiness': print(json.dumps(Readiness().status())); return
    if args.command == 'enroll': enroll(args.config, args.code_file, args.server, args.name); return
    store = Store(args.state)
    async def run():
        config = read_private(args.config)
        executor = None
        if config.get('native_helper') and config.get('setup_reason') != 'iphone_account_requirement':
            from runtime import MacExecutor
            executor = MacExecutor(config)
        instance = Agent(config, store, executor=executor)
        current=asyncio.current_task()
        loop=asyncio.get_running_loop()
        loop.add_signal_handler(signal.SIGTERM,current.cancel)
        try: await instance.run()
        finally:
            loop.remove_signal_handler(signal.SIGTERM)
            if instance.running:
                instance.running.cancel()
                with contextlib.suppress(asyncio.CancelledError): await instance.running
            if executor: await executor.close()
    try: asyncio.run(run())
    finally: store.close()

if __name__ == '__main__':
    try: main()
    except (KeyboardInterrupt, asyncio.CancelledError): pass
    except Exception:
        print('Bridge failed safely; inspect local configuration and permissions.', file=sys.stderr)
        sys.exit(1)
