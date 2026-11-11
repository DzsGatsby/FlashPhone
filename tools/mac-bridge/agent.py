#!/usr/bin/env python3
"""Outbound-only Flash phone device agent. No call replay and no audio/secret logging."""
import argparse
import http.client
import asyncio
import contextlib
import hashlib
import json
import os
from pathlib import Path
import random
import signal
import sqlite3
import socket
import ssl
import threading
import re
import wave
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
          CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY, phase TEXT NOT NULL, seq INTEGER NOT NULL DEFAULT 0, call_id TEXT, result TEXT, work_id TEXT);
          CREATE TABLE IF NOT EXISTS events(task TEXT NOT NULL, seq INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(task,seq));
          CREATE TABLE IF NOT EXISTS recording_uploads(task TEXT PRIMARY KEY,call_id TEXT NOT NULL,work_id TEXT NOT NULL,
            name TEXT NOT NULL,bytes INTEGER NOT NULL,digest TEXT NOT NULL,dev INTEGER NOT NULL,ino INTEGER NOT NULL,mtime INTEGER NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'pending',reason TEXT);
          CREATE UNIQUE INDEX IF NOT EXISTS recording_source_once ON recording_uploads(name);
        ''')
        columns={row[1] for row in self.db.execute('PRAGMA table_info(tasks)')}
        with self.db:
            if 'result' not in columns:self.db.execute('ALTER TABLE tasks ADD COLUMN result TEXT')
            if 'work_id' not in columns:self.db.execute('ALTER TABLE tasks ADD COLUMN work_id TEXT')
            for task,work,call in self.db.execute('SELECT id,work_id,call_id FROM tasks').fetchall():
                pending=self.db.execute('SELECT seq,body FROM events WHERE task=? ORDER BY seq',(task,)).fetchall()
                bodies=[(seq,json.loads(body)) for seq,body in pending]
                candidates={body['work_id'] for _,body in bodies if body.get('work_id')}
                if len(candidates)>1 or (work and candidates and work not in candidates):raise ValueError('persisted_work_binding_conflict')
                stable=work or next(iter(candidates),None) or str(uuid.uuid4())
                self.db.execute('UPDATE tasks SET work_id=? WHERE id=?',(stable,task))
                for seq,body in bodies:
                    dirty=False
                    if body.get('work_id') is None:body['work_id']=stable;dirty=True
                    if body.get('phase')=='recording_ready':
                        if body.get('carrier_ended') is not False:body['carrier_ended']=False;dirty=True
                        if body.get('call_id') is None and call is not None:body['call_id']=call;dirty=True
                    if dirty:self.db.execute('UPDATE events SET body=? WHERE task=? AND seq=?',(json.dumps(body,separators=(',',':')),task,seq))

    def event(self, task, phase, call_id=None, reason=None, carrier_ended=False, result=None, work_id=None):
        # The caller owns the transaction; these entries survive until positive server ACK.
        self.db.execute('INSERT OR IGNORE INTO tasks(id,phase) VALUES(?,?)', (task, phase))
        current = self.db.execute('SELECT seq,call_id,phase,result,work_id FROM tasks WHERE id=?', (task,)).fetchone()
        if current[1] is not None and call_id is not None and current[1] != call_id:
            raise ValueError('call_ownership_changed')
        call_id = call_id if call_id is not None else current[1]
        if work_id is not None and (not isinstance(work_id,str) or not work_id or len(work_id)>256):raise ValueError('invalid_work_binding')
        if current[4] and work_id is not None and current[4]!=work_id:raise ValueError('work_ownership_changed')
        work_id=current[4] or work_id or str(uuid.uuid4())
        # A recording attachment never reasserts a carrier-end transition.
        if phase=='recording_ready':carrier_ended=False
        seq = current[0] + 1
        event = dict(v=1, type='event', task_id=task, sequence=seq, phase=phase,
                     carrier_ended=carrier_ended, ai_active=phase == 'ai_active', work_id=work_id,
                     call_id=call_id, result=result or ({'reason': reason} if reason else None))
        encoded = json.dumps(event, separators=(',', ':'))
        if len(encoded.encode()) > 65536: raise ValueError('result_too_large')
        if self.db.execute('SELECT count(*) FROM events').fetchone()[0] >= 2048:
            raise ValueError('unacknowledged_spool_full')
        archived = json.loads(current[3]) if current[3] else {}
        if event['result'] is not None: archived.update(event['result'])
        archive_body = json.dumps(archived, separators=(',', ':')) if archived else None
        if archive_body and len(archive_body.encode()) > 65536: raise ValueError('archive_result_too_large')
        self.db.execute('UPDATE tasks SET phase=?,seq=?,call_id=?,result=?,work_id=? WHERE id=?', (current[2] if phase == 'recording_ready' else phase, seq, call_id, archive_body, work_id, task))
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

class RecordingError(Exception):
    def __init__(self,reason,retryable=False):
        super().__init__(reason);self.reason=reason;self.retryable=retryable

class UploadCancellation:
    def __init__(self):self.event=threading.Event();self.lock=threading.Lock();self.connection=None;self.socket=None
    def check(self):
        if self.event.is_set():raise RecordingError('recording_upload_canceled',True)
    def connection_factory(self,kind):
        def make(host,**kwargs):
            self.check();connection=kind(host,**kwargs)
            with self.lock:self.connection=connection
            original=connection.connect
            def connect():
                original()
                with self.lock:self.socket=connection.sock
                self.check()
            connection.connect=connect
            self.check();return connection
        return make
    def cancel(self):
        self.event.set()
        with self.lock:
            connection=self.connection
            live_socket=self.socket or (connection.sock if connection else None)
            if live_socket:
                with contextlib.suppress(OSError):live_socket.shutdown(socket.SHUT_RDWR)
            if connection:connection.close()

class RecordingUploads:
    REQUEST_TIMEOUT=125
    RETRY_DELAYS=(2,4)
    MAX_BYTES=40*1024*1024
    def __init__(self,store,origin,token,directory,allow_loopback=False):
        parsed=urllib.parse.urlsplit(origin)
        local=allow_loopback and parsed.scheme=='http' and parsed.hostname in ('127.0.0.1','localhost','::1')
        if local:
            if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('','/'):
                raise ValueError('invalid_test_origin')
            self.origin=origin.rstrip('/')
        else:self.origin=base_url(origin)
        self.store=store;self.token=token;self.directory=Path(directory).expanduser()
        self.connected=False;self.revoked=False;self.worker=None;self.active_cancel=None
    def _open_source(self,name):
        if not re.fullmatch(r'call-[0-9a-f]{32}\.wav',name):raise RecordingError('recording_source_not_allowed')
        directory_fd=None;fd=None
        try:
            directory_fd=os.open(self.directory,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            directory_stat=os.fstat(directory_fd)
            if directory_stat.st_uid!=os.getuid() or directory_stat.st_mode&0o077:raise RecordingError('recording_directory_not_private')
            fd=os.open(name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=directory_fd)
            info=os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or info.st_mode&0o077 or info.st_nlink!=1:
                raise RecordingError('recording_source_not_private')
            if not 44<=info.st_size<=self.MAX_BYTES:raise RecordingError('recording_size_invalid')
            result=os.fdopen(fd,'rb');fd=None
            return result,info
        except OSError:raise RecordingError('recording_source_not_allowed') from None
        finally:
            if fd is not None:os.close(fd)
            if directory_fd is not None:os.close(directory_fd)
    def _inspect(self,path):
        path=Path(path).expanduser()
        if not path.is_absolute() or path.parent!=self.directory:raise RecordingError('recording_source_not_allowed')
        source,info=self._open_source(path.name)
        with source:
            try:
                with wave.open(source,'rb') as wav:
                    if (wav.getnchannels(),wav.getsampwidth(),wav.getframerate(),wav.getcomptype())!=(2,2,16000,'NONE') or not 0<wav.getnframes()<=600*16000:
                        raise RecordingError('recording_format_invalid')
                    expected=wav.getnframes()*4
                    if expected+44!=info.st_size:raise RecordingError('recording_wav_length_invalid')
                source.seek(0);digest=hashlib.sha256()
                for chunk in iter(lambda:source.read(65536),b''):digest.update(chunk)
                after=os.fstat(source.fileno())
                if (after.st_size,after.st_mtime_ns)!=(info.st_size,info.st_mtime_ns):raise RecordingError('recording_source_changed')
            except (wave.Error,EOFError):raise RecordingError('recording_format_invalid') from None
        return dict(name=path.name,bytes=info.st_size,digest=digest.hexdigest(),dev=info.st_dev,ino=info.st_ino,mtime=info.st_mtime_ns)
    async def register(self,path,task_id,call_id):
        # Only a current voice export enters this method. Never scan/import the recordings directory.
        if self.revoked:raise RecordingError('recording_authorization_revoked')
        try:valid_task=isinstance(task_id,str) and str(uuid.UUID(task_id))==task_id
        except ValueError:valid_task=False
        if not valid_task:raise RecordingError('recording_task_binding_mismatch')
        task=self.store.db.execute('SELECT call_id,work_id FROM tasks WHERE id=?',(task_id,)).fetchone()
        if not task or task[0]!=call_id or not task[1]:raise RecordingError('recording_task_binding_mismatch')
        candidate=Path(path).expanduser()
        bound=self.store.db.execute('SELECT task FROM recording_uploads WHERE name=?',(candidate.name,)).fetchone()
        if bound and bound[0]!=task_id:raise RecordingError('recording_source_already_bound')
        inspected=await asyncio.to_thread(self._inspect,path)
        with self.store.db:
            previous=self.store.db.execute('SELECT digest,bytes FROM recording_uploads WHERE task=?',(task_id,)).fetchone()
            if previous:
                if previous!=(inspected['digest'],inspected['bytes']):raise RecordingError('recording_already_registered')
            else:
                self.store.db.execute('INSERT INTO recording_uploads(task,call_id,work_id,name,bytes,digest,dev,ino,mtime) VALUES(?,?,?,?,?,?,?,?,?)',
                    (task_id,call_id,task[1],inspected['name'],inspected['bytes'],inspected['digest'],inspected['dev'],inspected['ino'],inspected['mtime']))
        self._start();return {'status':'queued'}
    def _start(self):
        if self.connected and not self.revoked and (self.worker is None or self.worker.done()):self.worker=asyncio.create_task(self._drain())
    async def set_connected(self,connected,revoked=False):
        self.connected=connected;self.revoked=self.revoked or revoked
        if connected and not self.revoked:self._start();return
        if self.active_cancel:self.active_cancel.cancel()
        if self.worker and not self.worker.done():
            self.worker.cancel();await asyncio.gather(self.worker,return_exceptions=True)
        if revoked:
            with self.store.db:self.store.db.execute("UPDATE recording_uploads SET status='canceled',reason='recording_authorization_revoked' WHERE status IN ('pending','uploading')")
    def _post(self,record,cancellation):
        source,info=self._open_source(record['name'])
        with source:
            if (info.st_dev,info.st_ino,info.st_mtime_ns,info.st_size)!=(record['dev'],record['ino'],record['mtime'],record['bytes']):raise RecordingError('recording_source_changed')
            digest=hashlib.sha256()
            for chunk in iter(lambda:source.read(65536),b''):
                cancellation.check();digest.update(chunk)
            if digest.hexdigest()!=record['digest']:raise RecordingError('recording_source_changed')
            source.seek(0)
            class Body:
                def __init__(self):self.bytes=0;self.digest=hashlib.sha256()
                def read(self,amount):
                    cancellation.check();data=source.read(amount);self.bytes+=len(data);self.digest.update(data);return data
            body_stream=Body()
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self,*args,**kwargs):raise RecordingError('recording_redirect_refused')
            class HTTP(urllib.request.HTTPHandler):
                def http_open(self,request):return self.do_open(cancellation.connection_factory(http.client.HTTPConnection),request)
            class HTTPS(urllib.request.HTTPSHandler):
                def https_open(self,request):return self.do_open(cancellation.connection_factory(http.client.HTTPSConnection),request,context=ssl.create_default_context())
            request=urllib.request.Request(self.origin+'/api/tasks/'+record['task']+'/recording',data=body_stream,method='POST',headers={
                'Authorization':'Bearer '+self.token,'User-Agent':'FlashPhoneMacBridge/1.0','Content-Type':'audio/wav',
                'Content-Length':str(record['bytes']),'X-Recording-SHA256':record['digest']})
            try:
                cancellation.check()
                with urllib.request.build_opener(NoRedirect,HTTP(),HTTPS()).open(request,timeout=20) as response:
                    data=response.read(65537)
                    if len(data)>65536:raise RecordingError('recording_response_invalid')
                    body=json.loads(data)
                cancellation.check()
            except urllib.error.HTTPError as error:
                status=error.code;error.close()
                if status in (401,403):raise RecordingError('recording_authorization_revoked') from None
                raise RecordingError('recording_http_rejected',status in (408,429) or status>=500) from None
            except RecordingError:raise
            except (OSError,urllib.error.URLError,TimeoutError):raise RecordingError('recording_transport_failed',True) from None
            except (ValueError,TypeError):raise RecordingError('recording_response_invalid') from None
            if body_stream.bytes!=record['bytes'] or body_stream.digest.hexdigest()!=record['digest']:raise RecordingError('recording_source_changed')
            metadata=body.get('recording') if isinstance(body,dict) else None
            if not isinstance(metadata,dict) or metadata.get('task_id')!=record['task'] or metadata.get('bytes')!=record['bytes'] or metadata.get('sha256')!=record['digest']:
                raise RecordingError('recording_response_mismatch')
            return True
    def _publish(self,task,call,work,success,reason=None):
        current=self.store.db.execute('SELECT phase,call_id,work_id FROM tasks WHERE id=?',(task,)).fetchone()
        if not current or current[1]!=call or current[2]!=work:raise RecordingError('recording_task_binding_mismatch')
        phase='recording_ready' if success else current[0]
        return self.store.event(task,phase,call_id=call,work_id=work,carrier_ended=(not success and phase=='ended'),
            result={'recording_upload':{'status':'uploaded' if success else 'failed',**({'reason':reason} if reason else {})}})
    async def _drain(self):
        while self.connected and not self.revoked:
            exhausted=self.store.db.execute("SELECT task,call_id,work_id FROM recording_uploads WHERE status IN ('pending','uploading') AND attempts>=3").fetchall()
            for task,call,work in exhausted:
                with self.store.db:
                    self._publish(task,call,work,False,'recording_retry_budget_exhausted')
                    self.store.db.execute("UPDATE recording_uploads SET status='failed',reason='recording_retry_budget_exhausted' WHERE task=?",(task,))
            row=self.store.db.execute("SELECT task,call_id,work_id,name,bytes,digest,dev,ino,mtime,attempts FROM recording_uploads WHERE status IN ('pending','uploading') AND attempts<3 ORDER BY rowid LIMIT 1").fetchone()
            if not row:return
            record=dict(zip(('task','call_id','work_id','name','bytes','digest','dev','ino','mtime','attempts'),row))
            with self.store.db:
                current=self.store.db.execute('SELECT call_id,work_id FROM tasks WHERE id=?',(record['task'],)).fetchone()
                if current!=(record['call_id'],record['work_id']):
                    self.store.db.execute("UPDATE recording_uploads SET status='failed',reason='recording_task_binding_mismatch' WHERE task=?",(record['task'],));continue
                self.store.db.execute("UPDATE recording_uploads SET attempts=attempts+1,status='uploading' WHERE task=?",(record['task'],))
            cancellation=UploadCancellation();self.active_cancel=cancellation
            job=asyncio.create_task(asyncio.to_thread(self._post,record,cancellation))
            try:
                done,_=await asyncio.wait({job},timeout=self.REQUEST_TIMEOUT)
                if not done:
                    cancellation.cancel()
                    with contextlib.suppress(Exception,asyncio.CancelledError):await asyncio.wait_for(job,22)
                    raise RecordingError('recording_upload_timeout',True)
                await job
                with self.store.db:
                    self._publish(record['task'],record['call_id'],record['work_id'],True)
                    self.store.db.execute("UPDATE recording_uploads SET status='uploaded',reason=NULL WHERE task=?",(record['task'],))
            except asyncio.CancelledError:
                cancellation.cancel()
                with contextlib.suppress(Exception,asyncio.CancelledError):await asyncio.wait_for(job,22)
                with self.store.db:self.store.db.execute("UPDATE recording_uploads SET status='pending',reason='recording_upload_interrupted' WHERE task=? AND status='uploading'",(record['task'],))
                raise
            except Exception as error:
                failure=error if isinstance(error,RecordingError) else RecordingError('recording_upload_failed',True)
                retry=failure.retryable and record['attempts']+1<3 and not self.revoked
                with self.store.db:
                    self.store.db.execute('UPDATE recording_uploads SET status=?,reason=? WHERE task=?',('pending' if retry else 'failed',failure.reason,record['task']))
                    if not retry:self._publish(record['task'],record['call_id'],record['work_id'],False,failure.reason)
                if failure.reason=='recording_authorization_revoked':self.revoked=True;self.connected=False;return
                if retry:await asyncio.sleep(self.RETRY_DELAYS[min(record['attempts'],1)])
            finally:self.active_cancel=None

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
        self.uploads=RecordingUploads(store,config['server'],config['device_token'],Path.home()/'Library/Application Support/Flash phone/mac-recordings') if config.get('device_token') else None
        if executor and hasattr(executor,'set_recording_exporter'):executor.set_recording_exporter(self._recording_exported)
        self.device_id = config['device_id']
        uuid.UUID(self.device_id)
        base_url(config['server'])

    async def _recording_exported(self,path,task_id,call_id):
        if not self.uploads:return {'status':'unavailable','reason':'recording_uploader_not_configured'}
        try:return await self.uploads.register(path,task_id,call_id)
        except Exception as exception:
            error=exception if isinstance(exception,RecordingError) else RecordingError('recording_source_unavailable')
            with contextlib.suppress(RecordingError):
                row=self.store.db.execute('SELECT work_id FROM tasks WHERE id=?',(task_id,)).fetchone()
                if row:
                    with self.store.db:self.uploads._publish(task_id,call_id,row[0],False,error.reason)
            return {'status':'failed','reason':error.reason}

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
        if self.uploads:await self.uploads.set_connected(True)
        try:
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
        finally:
            if self.uploads:await self.uploads.set_connected(False)

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
                    capabilities = ['calls.dial', 'calls.cancel', 'calls.hangup']+(['recording'] if self.uploads else []) if status.get('ready') else []
                    await ws.send(json.dumps(dict(v=1, type='hello', capabilities=capabilities, status=status)))
                    await self.connected(ws)
            except asyncio.CancelledError: raise
            except Exception as error:
                status = getattr(getattr(error, 'response', None), 'status_code', None)
                close = getattr(error, 'rcvd', None)
                if status in (401, 403) or getattr(close, 'code', None) == 4003:
                    if self.uploads:await self.uploads.set_connected(False,revoked=True)
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
            if instance.uploads:await instance.uploads.set_connected(False)
            if executor: await executor.close()
    try: asyncio.run(run())
    finally: store.close()

if __name__ == '__main__':
    try: main()
    except (KeyboardInterrupt, asyncio.CancelledError): pass
    except Exception:
        print('Bridge failed safely; inspect local configuration and permissions.', file=sys.stderr)
        sys.exit(1)
