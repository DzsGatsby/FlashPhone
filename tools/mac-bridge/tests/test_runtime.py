import asyncio
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('local_mac_runtime', ROOT / 'runtime.py')
runtime = importlib.util.module_from_spec(spec); spec.loader.exec_module(runtime)

class RuntimeTest(unittest.IsolatedAsyncioTestCase):
    async def test_unverified_control_never_starts_media_or_dial(self):
        executor = runtime.MacExecutor.__new__(runtime.MacExecutor)
        class Native:
            def __init__(self): self.actions=[]
            async def request(self, payload):
                self.actions.append(payload['action'])
                return {'control_verified':False,'control_handler':True,'accessibility_trusted':True}
        executor.native=Native()
        emitted=[]
        async def emit(phase, **fields): emitted.append((phase,fields))
        await executor.dial({},emit)
        self.assertEqual(executor.native.actions,['capabilities'])
        self.assertEqual(emitted,[('failed',{'reason':'requires_setup'})])
        status=await executor.readiness()
        self.assertIn('AI',status['message'])
        self.assertNotIn('账号',status['message'])
        self.assertFalse(status['control_verified'])
    async def test_wrong_call_cannot_cancel_or_hangup(self):
        executor = runtime.MacExecutor.__new__(runtime.MacExecutor)
        executor.active={'task_id':'new-task','call_id':'new-call'}; executor.stop=asyncio.Event()
        async def emit(*args,**kwargs): self.fail('must not emit wrong-call action')
        for action in (executor.cancel,executor.hangup):
            with self.assertRaises(ValueError):
                await action({'task_id':'old-task','expected_call_id':'old-call'},emit)
        self.assertFalse(executor.stop.is_set())
    async def test_local_readonly_native_rpc_when_requested(self):
        helper=os.getenv('FLASH_MAC_NATIVE_TEST_HELPER')
        if not helper: self.skipTest('Set explicit compiled helper path for local read-only integration.')
        controller=runtime.NativeController(helper)
        try:
            result=await controller.request({'action':'capabilities'})
            self.assertFalse(result['ready']); self.assertTrue(result['control_handler'])
            bad=await controller.request({'action':'prepare','task_id':'not-a-uuid'})
            self.assertFalse(bad['ok']); self.assertEqual(bad['reason'],'invalid_task_id')
        finally: await controller.close()


class OrderedRuntimeTest(unittest.IsolatedAsyncioTestCase):
    async def test_cloud_only_after_bound_active_and_routes_restored_before_handoff(self):
        import time,uuid
        actions=[]; emitted=[];exports=[];call=str(uuid.uuid4());statuses=iter(('ringing','active','ended'))
        class Native:
            helper='/synthetic/native'
            async def request(self,payload):
                action=payload['action'];actions.append(action)
                if action=='prepare':return {'ok':True,'call_id':call,'call_issued':False}
                if action=='dial':return {'ok':True,'call_id':call,'call_issued':True}
                if action=='status':
                    phase=next(statuses);actions.append(phase)
                    return {'ok':True,'call_id':call,'phase':phase,'carrier_ended':phase=='ended'}
                return {'ok':True,'call_id':call}
        executor=runtime.MacExecutor.__new__(runtime.MacExecutor);executor.active=None
        executor.native=Native();executor.config={'max_call_seconds':1};executor.stop=asyncio.Event()
        async def ready():return {'ready':True}
        executor.readiness=ready
        async def media(payload,helper,stop,emit,**kwargs):
            actions.append('cloud');await emit('ai_active')
            return {'reason':'test_complete','transcript':[],'recording_path':'/private/path/never/export.wav'}
        executor.voice_runner=media
        async def exporter(path,task_id,call_id):
            exports.append((path,task_id,call_id));return {'status':'queued'}
        executor.set_recording_exporter(exporter)
        async def emit(phase,**fields):emitted.append((phase,fields))
        await executor.dial({'task_id':str(uuid.uuid4()),'expires_at':int(time.time()*1000)+10000,
            'payload':{'number':'+12025550123','task':{'goal':'test'}}},emit)
        self.assertLess(actions.index('active'),actions.index('cloud'))
        self.assertLess(actions.index('media-route'),actions.index('cloud'))
        self.assertLess(actions.index('restore_route'),actions.index('ended'))
        self.assertEqual(emitted[-1][0],'ended');self.assertTrue(emitted[-1][1]['carrier_ended'])
        self.assertTrue(all('call_id' not in fields for phase,fields in emitted if phase=='preparing'))
        self.assertEqual(next(fields['call_id'] for phase,fields in emitted if phase=='dialing'),call)
        self.assertNotIn('never/export',json.dumps(emitted));self.assertIsNone(executor.active)
        self.assertEqual(len(exports),1);self.assertEqual(exports[0][0],'/private/path/never/export.wav');self.assertEqual(exports[0][2],call)


class BlockedAccountTest(unittest.IsolatedAsyncioTestCase):
    async def test_observed_apple_account_block_precedes_native_or_cloud_attempts(self):
        executor=runtime.MacExecutor.__new__(runtime.MacExecutor)
        executor.config={'setup_reason':'iphone_account_requirement'}
        class Native:
            async def request(self,payload):raise AssertionError('blocked setup must not touch Phone')
        executor.native=Native()
        status=await executor.readiness()
        self.assertFalse(status['ready']);self.assertTrue(status['blocked'])
        self.assertEqual(status['reason'],'iphone_account_requirement')
        events=[]
        async def emit(phase,**fields):events.append((phase,fields))
        await executor.dial({'payload':{'number':'+12025550123'}},emit)
        self.assertEqual(events,[('failed',{'reason':'iphone_account_requirement'})])




class PreDialCancelWireTest(unittest.IsolatedAsyncioTestCase):
    async def scenario(self, during_prepare):
        import sys,time,uuid
        sys.path.insert(0,str(ROOT))
        import agent
        device=str(uuid.uuid4());task=str(uuid.uuid4());private_lease=str(uuid.uuid4())
        entered=asyncio.Event();finish_prepare=asyncio.Event();actions=[]
        class Native:
            helper='/synthetic/native'
            async def request(self,payload):
                action=payload['action'];actions.append(action)
                if action=='prepare':
                    entered.set();await finish_prepare.wait()
                    return {'ok':True,'call_id':private_lease,'call_issued':False}
                return {'ok':True,'call_id':private_lease}
        executor=runtime.MacExecutor.__new__(runtime.MacExecutor)
        executor.native=Native();executor.active=None;executor.stop=asyncio.Event();executor.config={}
        async def ready():return {'ready':True}
        async def voice(*args,**kwargs):self.fail('pre-dial cancel must never start a model')
        executor.readiness=ready;executor.voice_runner=voice
        def command(action):
            return dict(command_id=str(uuid.uuid4()),task_id=task,device_id=device,action=action,
                issued_at=int(time.time()*1000),expires_at=int(time.time()*1000)+30000,
                expected_call_id=None,payload={'number':'+12025550123','task':{'goal':'synthetic'}})
        with tempfile.TemporaryDirectory() as directory:
            os.chmod(directory,0o700);store=agent.Store(directory)
            bridge=agent.Agent({'device_id':device,'server':'https://example.invalid'},store,executor=executor)
            try:
                await bridge.command(command('dial'))
                if during_prepare:await asyncio.wait_for(entered.wait(),1)
                await bridge.command(command('cancel'))
                finish_prepare.set();await asyncio.wait_for(bridge.running,1)
                wire=store.pending()
                self.assertEqual(wire[-1]['phase'],'canceled')
                self.assertTrue(all(e['call_id'] is None for e in wire))
                self.assertEqual(len({e['work_id'] for e in wire}),1)
                self.assertTrue(wire[-1]['work_id'])
                self.assertNotIn('dial',actions);self.assertNotIn('hangup',actions)
                if during_prepare:self.assertIn('cancel',actions)
                else:self.assertEqual(actions,[])
            finally:
                if bridge.running and not bridge.running.done():
                    bridge.running.cancel();await asyncio.gather(bridge.running,return_exceptions=True)
                store.close()
    async def test_cancel_during_await_prepare_never_publishes_a_carrier_id(self):
        await self.scenario(True)
    async def test_cancel_before_worker_starts_survives_new_stop_event(self):
        await self.scenario(False)

if __name__=='__main__': unittest.main()
