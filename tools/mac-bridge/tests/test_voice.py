import asyncio
import base64
import importlib.util
import json
import math
import os
from pathlib import Path
import struct
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch
import wave

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
spec=importlib.util.spec_from_file_location('mac_voice',ROOT/'voice.py')
voice=importlib.util.module_from_spec(spec); spec.loader.exec_module(voice)

class PureVoiceTest(unittest.TestCase):
    def test_continuous_resampling_matches_arbitrary_packet_boundaries(self):
        samples=[math.sin(i*0.03) for i in range(44100)]
        whole=voice.Resampler(44100,16000).feed(samples)
        streaming=voice.Resampler(44100,16000); actual=[]
        for i in range(0,len(samples),113): actual.extend(streaming.feed(samples[i:i+113]))
        self.assertEqual(actual,whole); self.assertEqual(len(actual),16000)
        self.assertEqual(len(streaming.samples),1)
    def test_queue_is_byte_bounded_and_interrupt_retires_old_epoch(self):
        q=voice.OutputQueue()
        for _ in range(4): q.put(bytes(48000))
        with self.assertRaises(voice.VoiceFailure): q.put(b'\0\0')
        q.clear(); self.assertEqual(q.bytes,0); self.assertEqual(q.epoch,1)
    def test_obsolete_cancel_does_not_interrupt_new_response(self):
        gate=voice.ResponseGate(); gate.start('old'); gate.interrupt(); gate.start('new')
        self.assertFalse(gate.cancel_applies('old')); self.assertTrue(gate.audio('new'))
        self.assertFalse(gate.audio('old'))
    def test_interruption_waits_for_start_not_old_anonymous_pcm(self):
        gate=voice.ResponseGate(); gate.start('a'); gate.interrupt()
        self.assertFalse(gate.audio(None)); gate.start('b'); self.assertTrue(gate.audio('b'))
    def test_private_recording_stereo_timeline_and_source_limit(self):
        with tempfile.TemporaryDirectory() as temp:
            os.chmod(temp,0o700); rec=voice.Recorder(1)
            rec.add(0,[0.5]*160,0.01); rec.add(1,[-0.5]*160,0.02)
            path=rec.save(temp); self.assertEqual(os.stat(path).st_mode & 0o777,0o600)
            with wave.open(path,'rb') as w:
                self.assertEqual((w.getnchannels(),w.getframerate(),w.getnframes()),(2,16000,320))
                values=struct.unpack('<'+str(320*2)+'h',w.readframes(320))
            self.assertGreater(values[0],0); self.assertEqual(values[1],0)
            self.assertEqual(values[320],0); self.assertLess(values[321],0)
    def test_protocol_fixed_model_and_no_root_device_key_in_schema(self):
        body=voice.session_create('owner task','voice')
        self.assertEqual(body['session']['model'],'1.2.6.1')
        self.assertEqual(body['session']['audio']['output']['format'],{'type':'pcm_s16le','rate':24000})
        self.assertNotIn('credentials',body)

FAKE_NATIVE='''#!/usr/bin/env python3
import json,sys,time,struct
if sys.argv[1]=='capabilities':
 print(json.dumps({'devices':[{'name':'Filmage Audio Device','output_sample_rate':44100}]}))
elif sys.argv[1]=='rx-phone':
 end=time.monotonic()+float(sys.argv[2])
 samples=struct.pack('<80f',*([0.1]*80))
 packet=struct.pack('<IIII',0x31414346,16000,1,len(samples))+samples
 try:
  while time.monotonic()<end:
   sys.stdout.buffer.write(packet);sys.stdout.buffer.flush();time.sleep(0.005)
 except BrokenPipeError: pass
elif sys.argv[1]=='tx':
 while sys.stdin.buffer.read(4096):pass
'''

class PumpTest(unittest.IsolatedAsyncioTestCase):
    async def test_actual_local_websocket_and_native_pipe_pump_no_paid_provider(self):
        import websockets
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp); os.chmod(temp,0o700)
            helper=directory/'native.py'; helper.write_text(FAKE_NATIVE); helper.chmod(0o700)
            credentials=directory/'voice.json'; credentials.write_text(json.dumps({'api_key':'LOCAL-TEST-ONLY','speaker':'fake'})); credentials.chmod(0o600)
            stop=asyncio.Event(); observed=[]; emitted=[]
            async def server(ws):
                self.assertEqual(ws.request.headers['X-Api-Key'],'LOCAL-TEST-ONLY')
                created=json.loads(await ws.recv()); observed.append(created['type'])
                self.assertEqual(created['session']['model'],'1.2.6.1')
                await ws.send(json.dumps({'type':'session.created'}))
                while True:
                    frame=json.loads(await ws.recv()); observed.append(frame['type'])
                    if frame['type']=='input_audio_buffer.append':
                        self.assertEqual(len(base64.b64decode(frame['audio'])),640); break
                for frame in [
                    {'type':'conversation.item.input_audio_transcription.started'},
                    {'type':'conversation.item.input_audio_transcription.completed','transcript':'几点开门'},
                    {'type':'response.output_audio.started','response_id':'turn1'},
                    {'type':'response.output_text.delta','delta':'九点开门'},
                    {'type':'response.output_text.done'},
                    {'type':'response.output_audio.delta','response_id':'turn1','delta':base64.b64encode(struct.pack('<2400h',*([1000]*2400))).decode()},
                    {'type':'response.output_audio.done','response_id':'turn1'},
                ]: await ws.send(json.dumps(frame))
                await asyncio.sleep(0.4); stop.set()
                try:
                    async for raw in ws:
                        if json.loads(raw).get('type')=='session.close':
                            await ws.send(json.dumps({'type':'session.closed'})); break
                except websockets.exceptions.ConnectionClosed: pass
            async def emit(phase,**fields): emitted.append(phase)
            save=voice.Recorder.save
            async with websockets.serve(server,'127.0.0.1',0) as listener:
                port=listener.sockets[0].getsockname()[1]
                with patch.object(voice,'ENDPOINT',f'ws://127.0.0.1:{port}'), patch.object(voice.Recorder,'save',lambda self,_:save(self,directory)):
                    result=await asyncio.wait_for(voice.run_voice({'task':{'goal':'Ask opening hours'}},helper,stop,emit,credentials,max_seconds=3),8)
            self.assertIn('speech_text_buffer.commit',observed); self.assertIn('input_audio_unmute.commit',observed)
            self.assertEqual(emitted,['ai_active']); self.assertEqual(result['reason'],'owner_stopped_ai')
            self.assertGreater(result['audio_report']['rx_pcm_bytes'],0)
            self.assertGreater(result['audio_report']['tx_pipe_bytes'],0)
            self.assertEqual(result['transcript'],[{'role':'user','text':'几点开门'},{'role':'assistant','text':'九点开门'}])
            self.assertFalse(result['carrier_ended']); self.assertFalse(result['tx_remote_audible_verified'])
            self.assertTrue(Path(result['recording_path']).is_file())
    async def test_explicit_stop_before_start_opens_no_audio_or_cloud(self):
        stop=asyncio.Event();stop.set()
        with tempfile.TemporaryDirectory() as temp:
            os.chmod(temp,0o700); path=Path(temp)/'voice.json';path.write_text('{"api_key":"LOCAL-TEST"}');path.chmod(0o600)
            async def emit(*args,**kwargs): self.fail('no AI session may start')
            with patch.object(voice,'secure_connect',side_effect=AssertionError('must not connect')):
                result=await voice.run_voice({'task':{'goal':'test'}},'/does/not/exist',stop,emit,path,1)
            self.assertEqual(result['reason'],'owner_stopped_ai')
            self.assertIsNone(result['recording_path'])


class CancelConnectionTest(unittest.IsolatedAsyncioTestCase):
    async def test_stop_during_provider_ack_wait_closes_all_children_promptly(self):
        import websockets,time
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(temp);os.chmod(temp,0o700)
            helper=directory/'native.py';helper.write_text(FAKE_NATIVE);helper.chmod(0o700)
            credentials=directory/'voice.json';credentials.write_text('{"api_key":"LOCAL-TEST"}');credentials.chmod(0o600)
            stop=asyncio.Event();create_received=asyncio.Event()
            async def server(ws):
                await ws.recv();create_received.set();await ws.wait_closed()
            async def emit(*args,**kwargs): self.fail('session never became ready')
            save=voice.Recorder.save
            async with websockets.serve(server,'127.0.0.1',0) as listener:
                port=listener.sockets[0].getsockname()[1]
                with patch.object(voice,'ENDPOINT',f'ws://127.0.0.1:{port}'),patch.object(voice.Recorder,'save',lambda self,_:save(self,directory)):
                    run=asyncio.create_task(voice.run_voice({'task':{'goal':'test'}},helper,stop,emit,credentials,10))
                    await asyncio.wait_for(create_received.wait(),6)
                    before=time.monotonic();stop.set()
                    result=await asyncio.wait_for(run,3)
                    self.assertLess(time.monotonic()-before,2)
            self.assertEqual(result['reason'],'owner_stopped_ai');self.assertFalse(result['carrier_ended'])

class ChildCleanupTest(unittest.IsolatedAsyncioTestCase):
    async def test_full_unconsumed_pipe_cannot_hang_cleanup(self):
        import time
        process=await asyncio.create_subprocess_exec(sys.executable,'-c',
            'import os\nwhile True: os.write(1,b"x"*4096)',stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,limit=1024)
        await asyncio.sleep(0.05);before=time.monotonic()
        await asyncio.wait_for(voice.terminate(process),3)
        self.assertIsNotNone(process.returncode);self.assertLess(time.monotonic()-before,2.5)

if __name__=='__main__': unittest.main()
