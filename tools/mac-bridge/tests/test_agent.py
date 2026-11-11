import asyncio
import copy
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('bridge_agent', ROOT / 'agent.py')
agent = importlib.util.module_from_spec(spec); spec.loader.exec_module(agent)

def uid(): return str(uuid.uuid4())

def command(device, clock=10000, **fields):
    c = dict(command_id=uid(), task_id=uid(), device_id=device, action='dial', issued_at=clock - 1,
             expires_at=clock + 1000, expected_call_id=None,
             payload=dict(number='+12025550123', sim_id=None,
                          task=dict(goal='Ask the opening hours', questions=['What time?'], context='', constraints='No purchases')))
    c.update(fields); return c

class StoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); os.chmod(self.temp.name, 0o700)
        self.store = agent.Store(self.temp.name); self.device = uid()
    def tearDown(self): self.store.close(); self.temp.cleanup()
    def test_permissions(self):
        self.assertEqual(stat.S_IMODE((Path(self.temp.name) / 'state.sqlite3').stat().st_mode), 0o600)
    def test_dedupe_and_changed_payload(self):
        c = command(self.device)
        self.assertTrue(self.store.accept(c)); self.assertFalse(self.store.accept(c))
        d = copy.deepcopy(c); d['payload']['task']['goal'] = 'Changed'
        with self.assertRaisesRegex(ValueError, 'conflict'): self.store.accept(d)
        self.assertEqual(len(self.store.pending()), 1)
    def test_new_command_id_same_task_does_not_repeat(self):
        c = command(self.device); self.store.accept(c)
        c['command_id'] = uid(); self.assertFalse(self.store.accept(c))
        self.assertEqual(len(self.store.pending()), 1)
    def test_acceptance_and_tombstone_atomic(self):
        c = command(self.device); self.store.accept(c)
        self.store.close(); self.store = agent.Store(self.temp.name)
        self.assertFalse(self.store.accept(c)); self.store.recover()
        events = self.store.pending()
        self.assertEqual([e['phase'] for e in events], ['accepted', 'interrupted'])
        self.assertFalse(events[-1]['carrier_ended'])
    def test_recovery_only_once_and_no_replay(self):
        c = command(self.device); self.store.accept(c)
        self.store.recover(); self.store.recover()
        self.assertEqual([e['phase'] for e in self.store.pending()], ['accepted', 'interrupted'])
    def test_ack_exact_and_future_does_not_delete(self):
        c = command(self.device); self.store.accept(c)
        self.store.ack(c['task_id'], 99); self.assertEqual(len(self.store.pending()), 1)
        self.store.ack(c['task_id'], 1); self.store.ack(c['task_id'], 1)
        self.assertEqual(self.store.pending(), [])
        with self.store.db: self.store.event(c['task_id'], 'failed', reason='requires_setup')
        self.assertEqual(self.store.pending()[0]['sequence'], 2)
    def test_no_sensitive_payload_in_result_spool(self):
        c = command(self.device); self.store.accept(c)
        body = json.dumps(self.store.pending())
        self.assertNotIn(c['payload']['number'], body)
        self.assertNotIn(c['payload']['task']['goal'], body)
    def test_symlink_and_world_readable_config_refused(self):
        path = Path(self.temp.name) / 'identity.json'
        path.write_text('{}'); os.chmod(path, 0o644)
        with self.assertRaises(ValueError): agent.read_private(path)
        os.chmod(path, 0o600); link = path.with_name('link'); link.symlink_to(path)
        with self.assertRaises(OSError): agent.read_private(link)
    def test_atomic_private_identity(self):
        path = Path(self.temp.name) / 'identity.json'; agent.atomic_private(path, {'secret':'test-only'})
        self.assertEqual(agent.read_private(path), {'secret':'test-only'})
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
    def test_readiness_never_claims_synthetic_is_call(self):
        r = agent.Readiness(); self.assertFalse(r.status()['ready']); self.assertEqual(r.capabilities(), [])

class ValidationTest(unittest.TestCase):
    def setUp(self): self.device = uid(); self.c = command(self.device)
    def test_valid(self): self.assertEqual(agent.validate_command(self.c, self.device, lambda:10000), self.c)
    def test_expiry_and_future_and_long_lease(self):
        for changes in ({'expires_at':10000}, {'issued_at':15001}, {'expires_at':200000}, {'expires_at':True}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                agent.validate_command(dict(self.c, **changes), self.device, lambda:10000)
    def test_other_device_and_malformed_id(self):
        for changes in ({'device_id':uid()}, {'task_id':'../bad'}):
            with self.assertRaises(ValueError): agent.validate_command(dict(self.c, **changes), self.device, lambda:10000)
    def test_bad_payload(self):
        for patch in ({'number':'911; rm -rf'}, {'task':{'goal':''}}, {'task':{'goal':'X','questions':[3]}}, {'task':{'goal':'X','unknown':'X'}}):
            d = copy.deepcopy(self.c); d['payload'].update(patch)
            with self.assertRaises(ValueError): agent.validate_command(d, self.device, lambda:10000)
    def test_secure_origin_only(self):
        for origin in ('http://example.com', 'https://secret@example.com', 'https://example.com/api', 'https://example.com/?token=X'):
            with self.assertRaises(ValueError): agent.base_url(origin)

class TransportTest(unittest.IsolatedAsyncioTestCase):
    async def test_real_websocket_ack_and_reconnect_does_not_reexecute(self):
        # Local WebSocket transport only; no provider, no telephone, no external network.
        import websockets
        with tempfile.TemporaryDirectory() as temp:
            os.chmod(temp, 0o700); store = agent.Store(temp); device = uid()
            bridge = agent.Agent(dict(device_id=device, server='https://example.invalid', device_token='test-only'), store)
            c = command(device, clock=agent.now_ms())
            async def server(ws):
                await ws.send(json.dumps(dict(v=1,type='hello_ack',device_id=device,protocol=1)))
                await ws.send(json.dumps(dict(v=1,type='command',command=c)))
                seen = [json.loads(await ws.recv()), json.loads(await ws.recv())]
                self.assertEqual([e['phase'] for e in seen], ['accepted', 'failed'])
                for event in seen:
                    await ws.send(json.dumps(dict(v=1,type='ack',task_id=event['task_id'],sequence=event['sequence'])))
                await asyncio.sleep(0.02)
            async with websockets.serve(server, '127.0.0.1', 0) as listener:
                port = listener.sockets[0].getsockname()[1]
                async with websockets.connect(f'ws://127.0.0.1:{port}', proxy=None) as ws:
                    with self.assertRaises(websockets.exceptions.ConnectionClosedOK): await bridge.connected(ws)
            self.assertEqual(store.pending(), [])
            await bridge.command(c)
            self.assertEqual(store.pending(), [])
            store.close()


class RedirectTest(unittest.IsolatedAsyncioTestCase):
    async def test_bearer_never_follows_redirect(self):
        import websockets
        from websockets.http11 import Response
        from websockets.datastructures import Headers
        reached = []
        async def destination(ws): reached.append(True)
        async with websockets.serve(destination, '127.0.0.1', 0) as second:
            port = second.sockets[0].getsockname()[1]
            def redirect(connection, request):
                return Response(302, 'Found', Headers({'Location':f'ws://127.0.0.1:{port}'}), b'')
            async with websockets.serve(destination, '127.0.0.1', 0, process_request=redirect) as first:
                origin = first.sockets[0].getsockname()[1]
                with self.assertRaises(websockets.exceptions.InvalidStatus):
                    async with agent.secure_connect(f'ws://127.0.0.1:{origin}', additional_headers={'Authorization':'Bearer local-test'}, proxy=None):
                        self.fail('redirect unexpectedly accepted')
        self.assertEqual(reached, [])

class DispatchTest(unittest.IsolatedAsyncioTestCase):
    async def test_dispatch_async_and_cancel_never_hangs_up(self):
        class Executor:
            def __init__(self): self.started=asyncio.Event(); self.canceled=asyncio.Event(); self.hangups=0
            async def dial(self, command, emit):
                self.started.set(); await self.canceled.wait(); await emit('handed_off', reason='owner_canceled_ai')
            async def cancel(self, command, emit): self.canceled.set()
            async def hangup(self, command, emit): self.hangups += 1
        with tempfile.TemporaryDirectory() as temp:
            os.chmod(temp, 0o700); store=agent.Store(temp); device=uid(); executor=Executor()
            bridge=agent.Agent(dict(device_id=device,server='https://example.invalid'),store,executor=executor)
            c=command(device,clock=agent.now_ms()); await bridge.command(c)
            await asyncio.wait_for(executor.started.wait(),1)
            cancel=command(device,clock=agent.now_ms(),task_id=c['task_id'],action='cancel')
            await bridge.command(cancel); await bridge.running
            self.assertEqual(executor.hangups,0)
            self.assertEqual(store.pending()[-1]['phase'],'handed_off')
            store.close()
    async def test_busy_second_task_does_not_start(self):
        class Executor:
            def __init__(self): self.started=asyncio.Event(); self.calls=0
            async def dial(self, command, emit): self.calls+=1; self.started.set(); await asyncio.Future()
        with tempfile.TemporaryDirectory() as temp:
            os.chmod(temp,0o700); store=agent.Store(temp); device=uid(); executor=Executor()
            bridge=agent.Agent(dict(device_id=device,server='https://example.invalid'),store,executor=executor)
            await bridge.command(command(device,clock=agent.now_ms())); await executor.started.wait()
            await bridge.command(command(device,clock=agent.now_ms()))
            self.assertEqual(executor.calls,1)
            self.assertEqual([e['result']['reason'] for e in store.pending() if e['phase']=='failed'],['device_busy'])
            bridge.running.cancel()
            with self.assertRaises(asyncio.CancelledError): await bridge.running
            self.assertEqual(store.pending()[0]['phase'],'accepted'); store.close()

class ArchiveTest(unittest.TestCase):
    setUp = StoreTest.setUp
    tearDown = StoreTest.tearDown
    def test_binding_preserved_and_cannot_change(self):
        c=command(self.device); self.store.accept(c); call=uid()
        with self.store.db:
            self.store.event(c['task_id'],'active',call_id=call)
            self.store.event(c['task_id'],'ai_active')
        self.assertEqual(self.store.pending()[-1]['call_id'],call)
        with self.assertRaisesRegex(ValueError,'ownership'):
            with self.store.db: self.store.event(c['task_id'],'active',call_id=uid())
    def test_recording_metadata_does_not_revive_terminal_task(self):
        c=command(self.device); self.store.accept(c)
        with self.store.db:
            self.store.event(c['task_id'],'ended',carrier_ended=True)
            self.store.event(c['task_id'],'recording_ready',result={'recording':{'bytes':44}})
        phase=self.store.db.execute('SELECT phase FROM tasks WHERE id=?',(c['task_id'],)).fetchone()[0]
        self.assertEqual(phase,'ended')
    def test_ack_keeps_private_final_result_archive(self):
        c=command(self.device);self.store.accept(c)
        with self.store.db: self.store.event(c['task_id'],'failed',result={'summary':'test-only outcome','reason':'requires_setup'})
        for event in self.store.pending():self.store.ack(event['task_id'],event['sequence'])
        self.assertEqual(self.store.pending(),[])
        saved=json.loads(self.store.db.execute('SELECT result FROM tasks WHERE id=?',(c['task_id'],)).fetchone()[0])
        self.assertEqual(saved['summary'],'test-only outcome')




class WorkWireTest(unittest.TestCase):
    setUp=StoreTest.setUp
    tearDown=StoreTest.tearDown
    def test_work_stable_and_recording_ready_does_not_reassert_end(self):
        c=command(self.device);self.store.accept(c);call=uid()
        with self.store.db:
            self.store.event(c['task_id'],'dialing',call_id=call)
            self.store.event(c['task_id'],'active')
            self.store.event(c['task_id'],'handed_off',result={'transcript':[{'role':'assistant','text':'synthetic'}]})
            self.store.event(c['task_id'],'ended',carrier_ended=True)
            self.store.event(c['task_id'],'recording_ready',carrier_ended=True,result={'recording':{'bytes':44}})
        wire=self.store.pending();work=wire[0]['work_id']
        self.assertIsNotNone(work);self.assertEqual(len({e['work_id'] for e in wire}),1)
        self.assertTrue(wire[-2]['carrier_ended']);self.assertFalse(wire[-1]['carrier_ended'])
        self.assertEqual(wire[-1]['call_id'],call)
        row=self.store.db.execute('SELECT phase,result FROM tasks WHERE id=?',(c['task_id'],)).fetchone()
        self.assertEqual(row[0],'ended');self.assertIn('transcript',json.loads(row[1]))
    def test_reopen_recover_keeps_original_call_and_work_binding(self):
        c=command(self.device);self.store.accept(c);call=uid()
        with self.store.db:self.store.event(c['task_id'],'active',call_id=call)
        work=self.store.pending()[-1]['work_id']
        self.store.close();self.store=agent.Store(self.temp.name);self.store.recover()
        event=self.store.pending()[-1]
        self.assertEqual((event['phase'],event['call_id'],event['work_id']),('interrupted',call,work))
        self.assertFalse(event['carrier_ended'])
    def test_changed_work_binding_is_rejected(self):
        c=command(self.device);self.store.accept(c)
        original=self.store.pending()[0]['work_id']
        with self.assertRaisesRegex(ValueError,'work_ownership'):
            with self.store.db:self.store.event(c['task_id'],'preparing',work_id=uid())
        self.assertEqual(self.store.pending()[0]['work_id'],original)
    def test_legacy_spool_backfills_one_work_and_sanitizes_recording_end(self):
        c=command(self.device);self.store.accept(c);call=uid()
        with self.store.db:
            self.store.event(c['task_id'],'ended',call_id=call,carrier_ended=True)
            event=self.store.event(c['task_id'],'recording_ready')
            event['work_id']=None;event['call_id']=None;event['carrier_ended']=True
            self.store.db.execute('UPDATE tasks SET work_id=NULL WHERE id=?',(c['task_id'],))
            for seq,body in self.store.db.execute('SELECT seq,body FROM events WHERE task=?',(c['task_id'],)).fetchall():
                body=json.loads(body);body['work_id']=None
                if body['phase']=='recording_ready':body=event
                self.store.db.execute('UPDATE events SET body=? WHERE task=? AND seq=?',(json.dumps(body),c['task_id'],seq))
            self.store.db.execute('ALTER TABLE tasks DROP COLUMN work_id')
        self.store.close();self.store=agent.Store(self.temp.name)
        wire=self.store.pending();work=wire[0]['work_id']
        self.assertTrue(work);self.assertEqual({e['work_id'] for e in wire},{work})
        self.assertEqual(wire[-1]['call_id'],call);self.assertFalse(wire[-1]['carrier_ended'])
        self.store.close();self.store=agent.Store(self.temp.name)
        self.assertEqual(self.store.pending()[-1]['work_id'],work)

if __name__ == '__main__': unittest.main()
