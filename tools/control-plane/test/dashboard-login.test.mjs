import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
const html=readFileSync(new URL('../dashboard.html',import.meta.url),'utf8');
const script=html.match(/<script>([\s\S]*)<\/script>/)[1];
const takeCode=script.slice(script.indexOf('function takeFragmentLoginCode(){'),script.indexOf('const $='));
const start=script.slice(script.indexOf('async function startDashboard(){'));
async function run(fragment,{reject=false}={}){
  const events=[],elements={loginerror:{textContent:''},loginbutton:{disabled:false}};
  const context=vm.createContext({URLSearchParams,location:{hash:fragment,pathname:'/',search:''},history:{state:null,replaceState(state,title,url){events.push(['strip',url]);context.location.hash='';}},JSON,
    $:id=>elements[id],api:async(path,options)=>{events.push(['api',path,options?JSON.parse(options.body):null]);if(reject)throw Error('授权码无效或已到期');},signedIn:async()=>events.push(['signedIn'])});
  vm.runInContext(takeCode,context);
  await vm.runInContext(start.replace('startDashboard();','startDashboard();'),context);
  return{events,elements,remaining:vm.runInContext('fragmentLogin.code',context)};
}
test('fragment is stripped before claim and exchanged once for the existing cookie login flow',async()=>{
  const result=await run('#login_code=ABC234YZ');
  assert.deepEqual(result.events,[['strip','/'],['api','/api/session-links/claim',{code:'ABC234YZ',label:'Mac控制台'}],['signedIn']]);
  assert.equal(result.remaining,null);assert.equal(result.elements.loginbutton.disabled,false);
  assert.doesNotMatch(script,/localStorage|sessionStorage|controller_token/);
});
test('failed or invalid fragment is removed, never echoed, and leaves manual login available',async()=>{
  const failed=await run('#login_code=ABC234YZ',{reject:true});
  assert.equal(failed.remaining,null);assert.equal(failed.elements.loginerror.textContent,'授权码无效或已到期');assert.equal(failed.elements.loginbutton.disabled,false);
  for(const hash of ['#login_code=too-long-synthetic-token','#login_code=ABC234YZ&login_code=XYZ234AB','#login_code=']){
    const result=await run(hash);assert.deepEqual(result.events,[['strip','/']]);assert.ok(result.elements.loginerror.textContent);assert.equal(result.remaining,null);
  }
});
test('ordinary visits retain normal existing-cookie restoration without claiming anything',async()=>{
  const result=await run('');await Promise.resolve();assert.deepEqual(result.events,[['api','/api/me',null],['signedIn']]);
});
