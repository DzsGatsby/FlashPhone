"""Local native control/media port. No replay, guessed carrier state or implicit hangup."""
import asyncio
import contextlib
import hashlib
import json
import time
from pathlib import Path

class NativeController:
    def __init__(self, helper):
        path=Path(helper).expanduser()
        if not path.is_absolute() or not path.is_file() or path.is_symlink():
            raise ValueError('native_helper_absolute_regular_path_required')
        self.helper=str(path); self.process=None; self.lock=asyncio.Lock()
    async def request(self,payload):
        async with self.lock:
            if self.process is None:
                self.process=await asyncio.create_subprocess_exec(self.helper,'control-server',
                    stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL,limit=16385)
            encoded=json.dumps(payload,separators=(',',':')).encode()+b'\n'
            if len(encoded)>16384:raise ValueError('native_request_too_large')
            try:
                self.process.stdin.write(encoded);await self.process.stdin.drain()
                line=await asyncio.wait_for(self.process.stdout.readline(),5)
                result=json.loads(line)
                if not isinstance(result,dict):raise ValueError('invalid_native_response')
                return result
            except BaseException:
                await self.close();raise  # Never reconnect/reissue a possibly accepted UI action.
    async def close(self):
        process,self.process=self.process,None
        if process and process.returncode is None:
            with contextlib.suppress(ProcessLookupError):process.terminate()
            try:await asyncio.wait_for(process.wait(),2)
            except asyncio.TimeoutError:
                with contextlib.suppress(ProcessLookupError):process.kill()
                await process.wait()

class MacExecutor:
    def __init__(self,config,voice_runner=None):
        self.config=config;self.native=NativeController(config['native_helper'])
        self.active=None;self.stop=asyncio.Event();self._preparing_task_id=None;self._pre_canceled_task_id=None
        if voice_runner is None:
            from voice import run_voice
            voice_runner=run_voice
        self.voice_runner=voice_runner
    def set_recording_exporter(self,callback):self.recording_exporter=callback
    async def readiness(self):
        if getattr(self,'config',{}).get('setup_reason')=='iphone_account_requirement':
            from agent import Readiness
            return Readiness('iphone_account_requirement').status()
        control=await self.native.request({'action':'capabilities'})
        verified=all(control.get(key) is True for key in ('control_verified','active_selector_verified',
            'hangup_selector_verified','route_lease_verified','accessibility_trusted'))
        if not verified:
            return {'ready':False,'reason':'requires_setup','real_call_verified':False,
                    'message':'Mac 桥接已连接，AI 通话接管尚未就绪。',
                    'remediation':'正在等待本机通话状态绑定及双向声音验证；普通电话拨通不会自动启动 AI。',
                    'control_handler':bool(control.get('control_handler')),'control_verified':False,
                    'phone_rx_verified':False,'phone_tx_verified':False}
        from agent import read_private
        try:
            if self.config.get('enabled') is not True:raise ValueError()
            proof=read_private(self.config['audio_setup_file'])
            helper_hash=hashlib.sha256(Path(self.native.helper).read_bytes()).hexdigest()
            if proof.get('helper_sha256')!=helper_hash or not all(proof.get(k) is True for k in
                ('virtual_loopback_verified','tap_io_verified','phone_route_verified')):raise ValueError()
            if type(proof.get('expires_at')) is not int or not time.time()*1000<proof['expires_at']<=time.time()*1000+86400000:raise ValueError()
            path=self.config.get('voice_credential_file',str(Path.home()/'Library/Application Support/Flash phone/voice.json'))
            credentials=read_private(path)
            key=credentials.get('api_key')
            if not isinstance(key,str) or not key or len(key)>1024 or any(ord(x)<33 or ord(x)>126 for x in key):raise ValueError()
        except (ValueError,KeyError,OSError,TypeError):
            return {'ready':False,'reason':'requires_setup_local_audio_and_model','real_call_verified':False,'control_verified':True}
        return {'ready':True,'reason':'local_setup_verified','real_call_verified':proof.get('real_call_verified') is True,
                'control_verified':True,'phone_rx_verified':True,'phone_tx_verified':True}
    async def _control(self,action,**fields):
        current=self.active
        if current is None:raise ValueError('no_owned_call')
        response=await self.native.request(dict(action=action,task_id=current['task_id'],
            expected_call_id=current['call_id'],**fields))
        if not response.get('ok'):raise ValueError('native_control_rejected')
        if response.get('call_id') not in (None,current['call_id']):raise ValueError('native_call_binding_changed')
        return response
    async def _wait_end(self,emit,deadline):
        # Keep ownership after AI handoff; only positive carrier end permits ended.
        while time.monotonic()<deadline:
            status=await self._control('status')
            if status.get('phase')=='ended' and status.get('carrier_ended') is True:
                await emit('ended',call_id=self.active['call_id'],carrier_ended=True);return
            if status.get('phase') not in ('dialing','ringing','active','ending'):
                await emit('interrupted',call_id=self.active['call_id'],reason='carrier_state_unknown');return
            await asyncio.sleep(0.5)
        await emit('interrupted',call_id=self.active['call_id'],reason='carrier_observation_limit_no_hangup')
    async def dial(self,command,emit):
        if command.get('payload',{}).get('sim_id') is not None:
            await emit('failed',reason='requires_setup_sim_selection');return
        status=await self.readiness()
        if not status['ready']:
            await emit('failed',reason=status['reason']);return
        if self.active is not None:
            await emit('failed',reason='device_busy');return
        remaining=(command['expires_at']-time.time()*1000)/1000
        if remaining<=0:
            await emit('failed',reason='call_lease_expired');return
        carrier_deadline=time.monotonic()+remaining
        self.stop=asyncio.Event();issued=False
        self._preparing_task_id=command['task_id']
        if getattr(self,'_pre_canceled_task_id',None)==command['task_id']:self.stop.set()
        try:
            if self.stop.is_set():
                await emit('canceled',reason='pre_dial_canceled');return
            await emit('preparing')
            prepared=await self.native.request(dict(action='prepare',task_id=command['task_id'],
                number=command['payload']['number'],expires_at=command['expires_at']))
            if not prepared.get('ok') or not isinstance(prepared.get('call_id'),str):
                await emit('failed',reason='native_preparation_failed');return
            self.active={'task_id':command['task_id'],'call_id':prepared['call_id'],'issued':False}
            await emit('preparing')
            if self.stop.is_set() or time.monotonic()>=carrier_deadline:
                await self._control('cancel');await emit('canceled',reason='pre_dial_canceled');return
            # An exception after dispatch is unknown: never claim no call was issued or retry it.
            response=await self._control('dial')
            issued=response.get('call_issued') is True
            if not issued:
                await emit('failed',reason='native_dial_not_issued');return
            self.active['issued']=True
            await emit('dialing',call_id=self.active['call_id'])
            while True:
                if self.stop.is_set():
                    await self._control('restore_route')
                    await emit('handed_off',call_id=self.active['call_id'],reason='owner_canceled_ai_before_active')
                    await self._wait_end(emit,time.monotonic()+600);return
                if time.monotonic()>=carrier_deadline:
                    await self._control('restore_route')
                    await emit('handed_off',call_id=self.active['call_id'],reason='carrier_connect_lease_expired_no_hangup')
                    await self._wait_end(emit,time.monotonic()+600);return
                observed=await self._control('status')
                if observed.get('phase')=='active':break
                if observed.get('phase')=='ended' and observed.get('carrier_ended') is True:
                    await emit('ended',call_id=self.active['call_id'],carrier_ended=True);return
                if observed.get('phase') not in ('dialing','ringing'):raise ValueError('carrier_state_unknown')
                await asyncio.sleep(0.1)
            await emit('active',call_id=self.active['call_id'])
            await self._control('media-route')
            async def media_emit(phase,**fields):await emit(phase,call_id=self.active['call_id'],**fields)
            result=await self.voice_runner(command['payload'],self.native.helper,self.stop,media_emit,
                credential_file=self.config.get('voice_credential_file'),max_seconds=max(1,min(600,int(self.config.get('max_call_seconds',600)))))
            # Local absolute paths never become broker output. Recording upload is a separate device-auth flow.
            path=result.pop('recording_path',None)
            await self._control('restore_route')
            await emit('handed_off',call_id=self.active['call_id'],result=result)
            exporter=getattr(self,'recording_exporter',None)
            if path and exporter:
                # A private internal callback, never a task event, receives the generated file path.
                await exporter(path,self.active['task_id'],self.active['call_id'])
            await self._wait_end(emit,time.monotonic()+600)
        except asyncio.CancelledError:
            self.stop.set();raise
        except Exception:
            self.stop.set()
            await emit('interrupted',call_id=self.active['call_id'] if self.active and self.active.get('issued') else None,reason='native_control_or_media_failed_no_replay')
        finally:
            # Route restoration is scoped to this exact lease; it can never hang up a call.
            if self.active:
                with contextlib.suppress(Exception):await self._control('release')
            self.active=None;self._preparing_task_id=None
            if getattr(self,'_pre_canceled_task_id',None)==command['task_id']:self._pre_canceled_task_id=None
    async def cancel(self,command,emit):
        task=command['task_id'];expected=command.get('expected_call_id')
        if self.active is None:
            pending=getattr(self,'_preparing_task_id',None)
            if expected is not None or (pending is not None and pending!=task):raise ValueError('wrong_pending_task')
            # Also covers an accepted task canceled before its async dial worker gets CPU time.
            self._pre_canceled_task_id=task;self.stop.set();return
        if task!=self.active['task_id']:raise ValueError('wrong_active_task')
        if self.active.get('issued'):
            if expected!=self.active['call_id']:raise ValueError('wrong_active_call')
        elif expected is not None:raise ValueError('pre_dial_has_no_published_call')
        self._pre_canceled_task_id=task;self.stop.set()  # No implicit carrier hangup.
    async def hangup(self,command,emit):
        if self.active is None or not self.active.get('issued') or command['task_id']!=self.active['task_id'] or command.get('expected_call_id')!=self.active.get('call_id'):
            raise ValueError('no_matching_carrier_call')
        self.stop.set();await self._control('hangup')
        # status watcher, not the button press, proves carrier end.
    async def close(self):
        self.stop.set();await self.native.close()
