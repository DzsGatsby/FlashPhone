import asyncio
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
import uuid
import wave
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('upload_agent',ROOT/'agent.py')
agent=importlib.util.module_from_spec(spec);spec.loader.exec_module(agent)
TOKEN='synthetic-device-token-no-real-credential'

class UploadTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp=tempfile.TemporaryDirectory();self.directory=Path(self.temp.name)
        self.recordings=self.directory/'mac-recordings';self.recordings.mkdir(mode=0o700)
        self.state=self.directory/'state';self.state.mkdir(mode=0o700)
        self.store=agent.Store(self.state);self.task=str(uuid.uuid4());self.call=str(uuid.uuid4())
        with self.store.db:
            self.store.event(self.task,'active',call_id=self.call)
            self.store.event(self.task,'ended',carrier_ended=True)
        self.work=self.store.pending()[-1]['work_id']
        self.path=self.recordings/('call-'+uuid.uuid4().hex+'.wav')
        fd=os.open(self.path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'wb') as raw:
            with wave.open(raw,'wb') as wav:
                wav.setnchannels(2);wav.setsampwidth(2);wav.setframerate(16000);wav.writeframes(b'\0\0\1\0'*800)
        self.posts=[];self.saved={};self.response_mode='ok';self.entered=threading.Event();self.release=threading.Event()
        outer=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_POST(self):
                assert self.headers['Authorization']=='Bearer '+TOKEN
                assert self.headers['User-Agent']=='FlashPhoneMacBridge/1.0'
                assert self.headers['Content-Type']=='audio/wav'
                outer.entered.set()
                if outer.response_mode=='hold':outer.release.wait(3)
                body=self.rfile.read(int(self.headers['Content-Length']))
                if len(body)!=int(self.headers['Content-Length']):return
                outer.posts.append((self.path,body,self.headers['X-Recording-SHA256']))
                digest=hashlib.sha256(body).hexdigest()
                assert self.path=='/api/tasks/'+outer.task+'/recording'
                assert digest==self.headers['X-Recording-SHA256']
                outer.saved.setdefault(digest,body)
                if outer.response_mode=='drop_once' and len(outer.posts)==1:
                    self.close_connection=True;return
                if outer.response_mode=='fail':
                    self.send_response(503);self.end_headers();return
                result={'recording':{'task_id':outer.task,'bytes':len(body),'sha256':digest}}
                if outer.response_mode=='bad_digest':result['recording']['sha256']='0'*64
                if outer.response_mode=='revoked':
                    self.send_response(401);self.end_headers();return
                response=json.dumps(result).encode()
                try:
                    self.send_response(201);self.send_header('Content-Length',str(len(response)));self.end_headers();self.wfile.write(response)
                except OSError:pass
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.origin='http://127.0.0.1:'+str(self.server.server_port)
        self.uploads=self.make_uploader()
    def make_uploader(self):
        result=agent.RecordingUploads(self.store,self.origin,TOKEN,self.recordings,allow_loopback=True)
        result.RETRY_DELAYS=(0.02,0.02);return result
    async def asyncTearDown(self):
        self.release.set();await self.uploads.set_connected(False)
        await asyncio.to_thread(self.server.shutdown);self.server.server_close();await asyncio.to_thread(self.thread.join)
        self.store.close();self.temp.cleanup()
    async def finish(self):
        await self.uploads.set_connected(True)
        await asyncio.wait_for(self.uploads.worker,3)
    def row(self):
        return self.store.db.execute('SELECT attempts,status FROM recording_uploads WHERE task=?',(self.task,)).fetchone()
    async def test_new_synthetic_wav_uploads_private_bound_ready_after_ended(self):
        await self.uploads.register(self.path,self.task,self.call);await self.finish()
        self.assertEqual(self.row(),(1,'uploaded'));self.assertEqual(len(self.posts),1)
        ready=self.store.pending()[-1]
        self.assertEqual((ready['phase'],ready['work_id'],ready['call_id']),('recording_ready',self.work,self.call))
        self.assertFalse(ready['carrier_ended']);self.assertNotIn(str(self.path),json.dumps(self.store.pending()))
        phase=self.store.db.execute('SELECT phase FROM tasks WHERE id=?',(self.task,)).fetchone()[0]
        self.assertEqual(phase,'ended')
    async def test_response_lost_retry_keeps_same_digest_and_one_object(self):
        self.response_mode='drop_once'
        await self.uploads.register(self.path,self.task,self.call);await self.finish()
        self.assertEqual(self.row(),(2,'uploaded'));self.assertEqual(len(self.posts),2)
        self.assertEqual(len(self.saved),1);self.assertEqual(self.posts[0][2],self.posts[1][2])
    async def test_three_attempt_budget_persists_across_restart(self):
        self.response_mode='fail';self.uploads.RETRY_DELAYS=(30,30)
        await self.uploads.register(self.path,self.task,self.call)
        await self.uploads.set_connected(True)
        for _ in range(200):
            if self.row()==(1,'pending'):break
            await asyncio.sleep(0.005)
        self.assertEqual(self.row(),(1,'pending'))
        await self.uploads.set_connected(False);self.store.close();self.store=agent.Store(self.state)
        self.uploads=self.make_uploader();await self.finish()
        self.assertEqual(self.row(),(3,'failed'));self.assertEqual(len(self.posts),3)
        await self.uploads.set_connected(False);self.store.close();self.store=agent.Store(self.state)
        self.uploads=self.make_uploader();await self.finish()
        self.assertEqual(len(self.posts),3)
    async def test_wrong_response_hash_is_not_published_ready(self):
        self.response_mode='bad_digest'
        await self.uploads.register(self.path,self.task,self.call);await self.finish()
        self.assertEqual(self.row(),(1,'failed'))
        self.assertNotEqual(self.store.pending()[-1]['phase'],'recording_ready')
    async def test_symlink_outside_directory_and_other_call_never_upload(self):
        link=self.recordings/('call-'+uuid.uuid4().hex+'.wav');link.symlink_to(self.path)
        for path,call in ((link,self.call),(self.directory/self.path.name,self.call),(self.path,str(uuid.uuid4()))):
            with self.assertRaises(agent.RecordingError):await self.uploads.register(path,self.task,call)
        self.assertEqual(self.posts,[])
    async def test_mutated_source_is_not_reopened_as_a_new_recording(self):
        await self.uploads.register(self.path,self.task,self.call)
        self.path.write_bytes(b'changed');self.path.chmod(0o600)
        await self.finish();self.assertEqual(self.row(),(1,'failed'));self.assertEqual(self.posts,[])
    async def test_disconnect_and_revoke_cancel_inflight_without_new_attempt(self):
        self.response_mode='hold'
        await self.uploads.register(self.path,self.task,self.call);await self.uploads.set_connected(True)
        await asyncio.to_thread(self.entered.wait,2)
        before=time.monotonic();await self.uploads.set_connected(False,revoked=True)
        self.assertLess(time.monotonic()-before,2)
        self.release.set();await self.uploads.set_connected(True)
        self.assertEqual(self.row(),(1,'canceled'));self.assertTrue(self.uploads.revoked)
        self.assertFalse(any(e['phase']=='recording_ready' for e in self.store.pending()))
    async def test_600_seconds_stereo16k_stays_below_upload_limit(self):
        self.assertLess(44+600*16000*2*2,agent.RecordingUploads.MAX_BYTES)



    async def test_http_revocation_stops_future_upload_and_registration(self):
        self.response_mode='revoked'
        await self.uploads.register(self.path,self.task,self.call);await self.finish()
        self.assertTrue(self.uploads.revoked);self.assertEqual(self.row(),(1,'failed'))
        await self.uploads.set_connected(True)
        self.assertEqual(len(self.posts),1)
        with self.assertRaises(agent.RecordingError):await self.uploads.register(self.path,self.task,self.call)
    async def test_source_cannot_be_attached_to_another_call_task(self):
        await self.uploads.register(self.path,self.task,self.call)
        other_task=str(uuid.uuid4());other_call=str(uuid.uuid4())
        with self.store.db:self.store.event(other_task,'active',call_id=other_call)
        with self.assertRaisesRegex(agent.RecordingError,'already_bound'):
            await self.uploads.register(self.path,other_task,other_call)
        self.assertEqual(self.posts,[])
    async def test_fifo_and_world_readable_file_are_rejected_before_reading(self):
        fifo=self.recordings/('call-'+uuid.uuid4().hex+'.wav');os.mkfifo(fifo,0o600)
        with self.assertRaises(agent.RecordingError):await asyncio.wait_for(self.uploads.register(fifo,self.task,self.call),1)
        self.path.chmod(0o644)
        with self.assertRaises(agent.RecordingError):await self.uploads.register(self.path,self.task,self.call)
        self.assertEqual(self.posts,[])



    async def test_request_wall_deadline_exhausts_last_attempt_without_ready(self):
        self.response_mode='hold';self.uploads.REQUEST_TIMEOUT=0.03
        await self.uploads.register(self.path,self.task,self.call)
        with self.store.db:self.store.db.execute('UPDATE recording_uploads SET attempts=2 WHERE task=?',(self.task,))
        await self.finish()
        self.assertEqual(self.row(),(3,'failed'))
        reason=self.store.db.execute('SELECT reason FROM recording_uploads WHERE task=?',(self.task,)).fetchone()[0]
        self.assertEqual(reason,'recording_upload_timeout')
        self.assertFalse(any(e['phase']=='recording_ready' for e in self.store.pending()))

if __name__=='__main__':unittest.main()
