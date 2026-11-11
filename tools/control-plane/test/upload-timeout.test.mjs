import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { Miniflare, Log, LogLevel } from 'miniflare';
test('slow recording deadline clears reservation and R2 object before a same-task retry',async()=>{
  const key='synthetic-upload-deadline-test-key',digest=createHash('sha256').update(key).digest('hex');
  const script=readFileSync(new URL('../worker.js',import.meta.url),'utf8').replace('const UPLOAD_TIMEOUT_MS = 120000;','const UPLOAD_TIMEOUT_MS = 75;');
  const mf=new Miniflare({script,modules:true,compatibilityDate:process.env.TEST_COMPATIBILITY_DATE||'2026-11-11',compatibilityFlags:['nodejs_compat'],bindings:{ROOT_KEY_HASH:digest},durableObjects:{OWNERS:{className:'OwnerControl',useSQLite:true}},r2Buckets:['RECORDINGS'],log:new Log(LogLevel.ERROR)});
  let ws;
  const req=async(path,body,auth=key,extra={})=>mf.dispatchFetch('https://timeout.test'+path,{method:body===undefined?'GET':'POST',headers:{Authorization:'Bearer '+auth,'CF-Connecting-IP':'192.0.2.'+Math.floor(Math.random()*200),'Content-Type':'application/json',...extra},body:body instanceof ReadableStream||body instanceof Uint8Array?body:body===undefined?undefined:JSON.stringify(body),duplex:'half'});
  const data=async(path,body,auth)=>{const response=await req(path,body,auth);assert.ok(response.status<300);return response.json();};
  try{
    const link=await data('/api/device-links',{}),device=await data('/api/device-enroll',{code:link.code,name:'合成超时设备',platform:'android',capabilities:['calls.dial','recording']});
    const upgraded=await mf.dispatchFetch('https://timeout.test/api/device/socket',{headers:{Authorization:'Bearer '+device.device_token,Upgrade:'websocket'}});ws=upgraded.webSocket;ws.accept();
    const hello=new Promise(resolve=>ws.addEventListener('message',e=>{if(JSON.parse(e.data).type==='hello_ack')resolve();}));
    ws.send(JSON.stringify({v:1,type:'hello',capabilities:['calls.dial','recording'],status:{ready:true}}));await hello;
    const created=await data('/api/tasks',{device_id:device.device_id,action:'dial',number:'+8613800000000',task:{goal:'合成测试，不拨真实手机'},idempotency_key:crypto.randomUUID()});
    const bytes=new Uint8Array(48);bytes.set(new TextEncoder().encode('RIFF'),0);bytes.set(new TextEncoder().encode('WAVE'),8);
    let slowController;
    const slow=new ReadableStream({start(controller){slowController=controller;controller.enqueue(bytes.subarray(0,12));}});
    const response=await req('/api/tasks/'+created.task.id+'/recording',slow,device.device_token,{'Content-Type':'audio/wav','Content-Length':'48'});
    assert.equal(response.status,504);assert.equal((await response.json()).error.code,'upload_timeout');slowController.close();
    assert.equal((await req('/api/tasks/'+created.task.id+'/recording')).status,404);
    const retry=await req('/api/tasks/'+created.task.id+'/recording',bytes,device.device_token,{'Content-Type':'audio/wav','Content-Length':'48'});
    assert.equal(retry.status,201);assert.equal((await retry.json()).recording.bytes,48);
    const bucket=await mf.getR2Bucket('RECORDINGS');assert.equal((await bucket.list()).objects.length,1);
  }finally{ws?.close();await mf.dispose();}
});
