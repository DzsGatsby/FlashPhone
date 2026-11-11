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
        actions=[]; emitted=[];call=str(uuid.uuid4());statuses=iter(('ringing','active','ended'))
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
        async def emit(phase,**fields):emitted.append((phase,fields))
        await executor.dial({'task_id':str(uuid.uuid4()),'expires_at':int(time.time()*1000)+10000,
            'payload':{'number':'+12025550123','task':{'goal':'test'}}},emit)
        self.assertLess(actions.index('active'),actions.index('cloud'))
        self.assertLess(actions.index('media-route'),actions.index('cloud'))
        self.assertLess(actions.index('restore_route'),actions.index('ended'))
        self.assertEqual(emitted[-1][0],'ended');self.assertTrue(emitted[-1][1]['carrier_ended'])
        self.assertNotIn('never/export',json.dumps(emitted));self.assertIsNone(executor.active)


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

if __name__=='__main__': unittest.main()
