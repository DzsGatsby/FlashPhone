'use strict';
/* ========== icons ========== */
const P='<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z"/>';
const I={
home:'<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M10 21v-6h4v6"/>',
list:'<path d="M8 6h13M8 12h13M8 18h13"/><circle cx="3.5" cy="6" r="1"/><circle cx="3.5" cy="12" r="1"/><circle cx="3.5" cy="18" r="1"/>',
plus:'<path d="M12 5v14M5 12h14"/>',
shield:'<path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z"/><path d="m9 12 2 2 4-4"/>',
gear:'<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
back:'<path d="m15 18-6-6 6-6"/>',chev:'<path d="m9 18 6-6-6-6"/>',down:'<path d="m6 9 6 6 6-6"/>',
phone:P,phoneIn:P+'<path d="M16 2v6h6M22 2l-6 6"/>',phoneOut:P+'<path d="M16 2h6v6M16 8l6-6"/>',
phoneOff:'<path d="M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91"/><path d="M23 1 1 23"/>',
check:'<path d="M20 6 9 17l-5-5"/>',x:'<path d="M18 6 6 18M6 6l12 12"/>',
search:'<circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/>',
play:'<path d="M8 5v14l11-7z" fill="currentColor"/>',pause:'<path d="M7 5h3v14H7zM14 5h3v14h-3z" fill="currentColor"/>',
lock:'<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
wifi:'<path d="M5 12.55a11 11 0 0 1 14 0M1.42 9a16 16 0 0 1 21.16 0M8.53 16.11a6 6 0 0 1 6.95 0"/><circle cx="12" cy="20" r="1"/>',
key:'<circle cx="7.5" cy="15.5" r="4.5"/><path d="m10.7 12.3 9.3-9.3M17 6l3 3M14 9l2 2"/>',
sim:'<path d="M6 2h9l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"/><rect x="8" y="11" width="8" height="7" rx="1"/>',
bell:'<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>',
battery:'<rect x="2" y="7" width="16" height="10" rx="2"/><path d="M22 11v2M6 11v2"/>',
info:'<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>',
more:'<circle cx="12" cy="5" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="12" cy="19" r="1.3"/>',
trash:'<path d="M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6"/>',
copy:'<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/>',
edit:'<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
user:'<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
userX:'<circle cx="9" cy="8" r="4"/><path d="M2 21a7 7 0 0 1 14 0M17 8l5 5M22 8l-5 5"/>',
users:'<circle cx="9" cy="8" r="4"/><path d="M2 21a7 7 0 0 1 14 0"/><path d="M16 3.1a4 4 0 0 1 0 7.8M22 21a7 7 0 0 0-4-6.3"/>',
bolt:'<path d="M13 2 3 14h9l-1 8 10-12h-9z"/>',
clock:'<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
moon:'<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/>',
mic:'<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0M12 17v5"/>',
micOff:'<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0M12 17v5M2 2l20 20"/>',
speaker:'<path d="M11 5 6 9H2v6h4l5 4z"/><path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14"/>',
hand:'<path d="M18 11V6a2 2 0 0 0-4 0v5M14 10V4a2 2 0 0 0-4 0v6M10 10.5V6a2 2 0 0 0-4 0v8"/><path d="M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15"/>',
message:'<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
book:'<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5z"/><path d="M4 19.5V21h16"/>',
download:'<path d="M12 3v12M7 10l5 5 5-5M5 21h14"/>',
refresh:'<path d="M21 12a9 9 0 1 1-3-6.7L21 8"/><path d="M21 3v5h-5"/>',
alert:'<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/>',
sparkle:'<path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"/><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"/>',
code:'<path d="m16 18 6-6-6-6M8 6l-6 6 6 6"/>',
wave:'<path d="M2 12h2M6 8v8M10 4v16M14 7v10M18 10v4M22 12h0"/>',
target:'<circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/>',
pauseC:'<circle cx="12" cy="12" r="10"/><path d="M10 9v6M14 9v6"/>',
playC:'<circle cx="12" cy="12" r="10"/><path d="m10 8 6 4-6 4z"/>',
contact:'<rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="12" cy="10" r="3"/><path d="M7 18a5 5 0 0 1 10 0"/>',
signal:'<path d="M2 20h2v-3H2zM7 20h2v-6H7zM12 20h2v-10h-2zM17 20h2V5h-2z" fill="currentColor" stroke="none"/>',
arrowDL:'<path d="M17 7 7 17M7 8v9h9"/>',arrowUR:'<path d="M7 17 17 7M8 7h9v9"/>',
grid:'<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
file:'<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h5"/>',
};
const ic=(n,c='')=>`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="${c}" aria-hidden="true">${I[n]||''}</svg>`;
const esc=s=>String(s==null?'':s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt=s=>{s=Math.max(0,Math.round(s));return Math.floor(s/60)+':'+String(s%60).padStart(2,'0')};
const $=(s,r=document)=>r.querySelector(s);
const $$=(s,r=document)=>Array.from(r.querySelectorAll(s));

/* ========== mock data ========== */
const SIMS=[{id:1,name:'SIM 1 · 中国移动',num:'138 **** 2051'},{id:2,name:'SIM 2 · 中国联通',num:'156 **** 7302'}];
const T=(t,who,text)=>({t,who,text});
const CALLS=[
{id:'c1',dir:'out',name:'鼎泰丰（来福士店）',number:'021 6386 3299',day:'今天',time:'14:20',dur:52,status:'open',rec:true,outcome:['已确认','ok'],sim:1,
 summary:'周五 18:30 有 4 人桌，不接受预订需现场取号，建议提前 30 分钟到店；人均约 ¥180。',
 brief:{goal:'确认周五晚 6:30 是否有 4 人位，并记下价格。',questions:['周五 18:30 还有 4 人位吗？','可以预订还是只能现场取号？','人均大概多少钱？'],context:'4 位大人，不需要儿童椅。',constraints:'不要付定金，不要留身份证号。'},
 tr:[T(0,'sys','已接通 · AI 开始通话'),T(2,'ai','您好，我想咨询一下周五晚上的座位。'),T(6,'caller','您好，鼎泰丰。请问几位？'),T(9,'ai','4 位大人，周五晚上 6 点半还有位置吗？'),T(15,'caller','6 点半有 4 人桌，不过我们不接受预订，需要现场取号。'),T(24,'ai','明白。那建议提前多久到店取号？'),T(29,'caller','提前半小时左右吧，周五人比较多。'),T(35,'ai','好的。再请问人均大概多少钱？'),T(40,'caller','人均 180 左右。'),T(44,'ai','好的，谢谢您，我们周五过去，再见！'),T(48,'caller','好的，再见。')]},
{id:'c2',dir:'in',name:'快递员',number:'186 2033 4471',day:'今天',time:'11:05',dur:41,status:'open',rec:true,outcome:['已留言','info'],rule:'允许名单 · 命中交代「快递外卖」',
 summary:'包裹已放 3 号楼丰巢柜，取件码 5821；对方无需回电。',
 tr:[T(0,'sys','来电 · 等待 3 秒后 AI 接听'),T(3,'ai','您好，机主现在不方便接听，我是他的助理，请问有什么事？'),T(8,'caller','你好，顺丰的，家里没人，包裹放哪？'),T(14,'ai','麻烦放 3 号楼的丰巢柜就可以，放好后能告诉我取件码吗？'),T(21,'caller','行，放好了，取件码 5821。'),T(28,'ai','收到，5821。还需要机主回电吗？'),T(32,'caller','不用了。'),T(35,'ai','好的，辛苦您，再见。')]},
{id:'c3',dir:'in',name:'未知号码',number:'021 5108 7730',day:'今天',time:'09:12',dur:23,status:'done',rec:true,outcome:['推销 · 已婉拒','gray'],rule:'接听所有来电',
 summary:'对方推销贷款业务，AI 已礼貌婉拒并结束通话。',
 tr:[T(0,'sys','来电 · AI 接听'),T(2,'ai','您好，请问哪位？'),T(5,'caller','您好，这边是金融服务中心，现在有一笔低息额度……'),T(12,'ai','谢谢，机主暂时不需要，请不要再来电，再见。')]},
{id:'c4',dir:'out',name:'物业服务中心',number:'021 5466 1200',day:'昨天',time:'18:40',dur:132,status:'done',rec:true,outcome:['已确认','ok'],sim:1,
 summary:'明天 9:00–12:00 安排维修师傅上门检查卫生间漏水，上门前会先打电话。',
 brief:{goal:'预约师傅上门检查卫生间漏水。',questions:['最早什么时候能上门？','上门前会提前联系吗？'],context:'7 栋 1802，卫生间吊顶渗水。',constraints:'不同意任何收费项目，先问清再说。'},
 tr:[T(0,'sys','已接通 · AI 开始通话'),T(3,'ai','您好，7 栋 1802 卫生间吊顶在渗水，想预约师傅上门看看。'),T(11,'caller','好的，明天上午可以吗？'),T(15,'ai','可以。具体几点？上门前会先联系吗？'),T(20,'caller','9 点到 12 点之间，到之前师傅会打电话。'),T(27,'ai','好的，谢谢，明天见。')]},
{id:'c5',dir:'in',name:'138 0013 2210',number:'138 0013 2210',day:'昨天',time:'13:30',dur:58,status:'open',rec:false,partial:false,outcome:['需回电','warn'],rule:'接听所有来电',
 summary:'对方是孩子班主任王老师，希望你今天下班前回电，事情不急。',
 tr:[T(0,'sys','来电 · AI 接听'),T(2,'ai','您好，机主现在不方便接听，请问有什么事？'),T(7,'caller','我是王老师，想跟家长聊一下孩子最近的情况，不急，今天能回个电话就行。'),T(18,'ai','好的王老师，我会转告他今天下班前给您回电。')]},
{id:'c6',dir:'out',name:'中国移动客服',number:'10086',day:'11月9日',time:'16:02',dur:151,status:'done',rec:true,taken:true,outcome:['已接管','info'],sim:1,
 summary:'AI 问清套餐变更规则（次月生效、无违约金），你在 2:31 接管并完成办理。',
 brief:{goal:'了解从 128 元套餐降到 58 元套餐的规则。',questions:['降档什么时候生效？','有没有违约金？'],context:'',constraints:'不要直接办理，确认规则后由我决定。'},
 tr:[T(0,'sys','已接通 · AI 开始通话'),T(4,'ai','您好，我想了解一下 128 元套餐改成 58 元的规则。'),T(30,'caller','降档是次月生效，合约期外没有违约金。'),T(42,'ai','明白，谢谢。机主想确认后再办理。'),T(151,'sys','你已接管通话 · 录音已停止')]},
];
const S={
  theme:'system',tab:'home',
  auto:true,pausedUntil:null,scope:'list',delay:3,maxSec:180,
  allow:['186 2033 4471','021 5466 1200','400 811 1111','139 1708 6620'],
  bypass:['138 1234 5678','137 6611 0099'],
  greeting:'',instructions:'你是机主的电话助理。语气礼貌简洁，先问清来意，记下关键信息；不替机主做任何承诺。',
  voice:'默认标准音色',
  playbooks:[
    {id:'p1',title:'快递外卖',when:'快递员或外卖骑手来电',reply:'问清物品放在哪里、是否需要回电；不透露门禁密码。',expiry:'长期有效',on:true},
    {id:'p2',title:'业务询问',when:'客户询问商品、服务或预约',reply:'问清需求、数量、期望时间和联系方式，记下留言；不报价、不承诺。',expiry:'长期有效',on:true},
    {id:'p3',title:'今天下午开会',when:'14:00–17:00 的所有来电',reply:'告知机主在开会，17 点后回电，问清事由。',expiry:'今天有效',on:false},
  ],
  ready:{perm:true,notif:true,helper:true,cred:true,audio:true,standby:true,battery:false},
  recovery:true,defaultSim:1,
  calls:JSON.parse(JSON.stringify(CALLS)),
  callsLoaded:false,filter:'all',q:'',
  openGroups:{device:true},
  update:{state:'idle',pct:0},
  draft:null,live:null,player:null,detailSeg:'tr',
};
const READY_ITEMS=[
  ['perm','电话权限','读取通话状态、拨打电话','phone'],
  ['notif','电话通知','锁屏时显示 AI 通话通知','bell'],
  ['helper','音频助手','无线配对 · 让 AI 能听能说','wifi'],
  ['cred','火山实时语音凭据','已加密保存在本机','key'],
  ['audio','双向通话实测','双方都能听见，接管正常','check'],
  ['standby','后台待命','锁屏和后台时可接听','clock'],
  ['battery','电池优化豁免','建议开启，避免后台被系统停止','battery'],
];
const CRITICAL=['perm','notif','helper','cred','audio'];
const readyCount=()=>READY_ITEMS.filter(r=>S.ready[r[0]]).length;
const coreReady=()=>CRITICAL.every(k=>S.ready[k]);
const openCount=()=>S.calls.filter(c=>c.status==='open').length;
const isPaused=()=>S.pausedUntil&&S.pausedUntil>Date.now();
const hhmm=d=>String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0');

/* ========== router ========== */
const SCREENS={};
const stackEl=()=>$('#stack');
let stack=[];
const topE=()=>stack[stack.length-1];
function paint(e){
  if(!e)return;
  const def=SCREENS[e.name];
  const sc=e.el.querySelector('.scroll');const y=sc?sc.scrollTop:0;
  e.el.className='screen '+(def.cls||'')+(def.tab?' has-tabbar':'')+(e.extra||'');
  e.el.innerHTML=def.render(e.params||{},e);
  const sc2=e.el.querySelector('.scroll');if(sc2)sc2.scrollTop=y;
  def.after&&def.after(e);
}
function rerender(){paint(topE())}
function mount(name,params,anim){
  const el=document.createElement('div');
  const e={name,params:params||{},el,extra:' fresh'};setTimeout(()=>{e.extra='';el.classList.remove('fresh')},900);
  stackEl().appendChild(el);stack.push(e);paint(e);
  if(anim){const m=SCREENS[name].mode||'push';el.classList.add('enter');if(m==='modal')el.classList.add('modal');if(m==='fade')el.classList.add('fade');
    el.addEventListener('animationend',()=>el.classList.remove('enter','modal','fade'),{once:true});}
  chrome();return e;
}
function push(name,params){
  const prev=topE();const m=SCREENS[name].mode||'push';
  if(prev&&m==='push')prev.el.classList.add('under');
  closeOverlay();
  return mount(name,params,true);
}
function pop(){
  if(stack.length<=1)return;
  const e=stack.pop();const def=SCREENS[e.name];def.leave&&def.leave(e);
  const m=def.mode||'push';
  e.el.classList.add('leave');if(m==='modal')e.el.classList.add('modal');
  if(m==='fade'){e.el.style.transition='opacity .2s';e.el.style.opacity='0';}
  setTimeout(()=>e.el.remove(),280);
  const t=topE();t.el.classList.remove('under');paint(t);chrome();
}
function replaceTop(name,params){
  const e=stack.pop();const def=SCREENS[e.name];def.leave&&def.leave(e);
  setTimeout(()=>e.el.remove(),300);
  mount(name,params,true);
}
function switchTab(t){
  closeOverlay();
  stack.forEach(e=>{const d=SCREENS[e.name];d.leave&&d.leave(e);e.el.remove()});stack=[];
  S.tab=t;mount(t,{},false);
  const sc=topE().el.querySelector('.scroll');if(sc)sc.scrollTop=0;
}
function chrome(){
  const e=topE();const def=SCREENS[e.name];
  $('#tabbar').classList.toggle('hidden',!def.tab);
  $$('#tabbar .tab').forEach(b=>b.classList.toggle('active',b.dataset.tab===S.tab));
  const n=openCount();const dot=$('#tabbar [data-tab="calls"] .dot');
  dot.textContent=n;dot.style.display=n?'':'none';
  $('.statusbar').classList.toggle('light',!!def.dark);
}

const legacyCallItem=c=>`<button class="crow" data-act="openCall" data-id="${c.id}">${avatar(c.name,{dir:c.dir,taken:c.taken})}<span class="cmain"><span class="ctop"><span class="cname">${esc(c.name)}</span><span class="ctime num">${c.time}</span></span><span class="csum">${esc(c.summary)}</span><span class="cmeta">${chip(c.outcome[0],c.outcome[1])}${c.rec?chip('录音','gray','wave'):''}${c.status==='open'?'<span class="opendot">待处理</span>':''}</span></span></button>`;
/* ========== overlay: sheet / dialog / toast ========== */
let overlayClose=null;
function closeOverlay(){if(overlayClose){const f=overlayClose;overlayClose=null;f()}}
function openSheet({title='',desc='',body='',id=''}){
  closeOverlay();
  const root=$('#overlay');
  root.innerHTML=`<div class="backdrop" data-act="closeSheet"></div><div class="sheet" role="dialog" aria-modal="true" data-sheet="${id}"><div class="grab"></div>${title?`<h3>${title}</h3>`:''}${desc?`<p class="sd">${desc}</p>`:''}<div class="sbody">${body}</div></div>`;
  requestAnimationFrame(()=>requestAnimationFrame(()=>{$('.backdrop',root)&&$('.backdrop',root).classList.add('show');$('.sheet',root)&&$('.sheet',root).classList.add('show')}));
  overlayClose=()=>{const b=$('.backdrop',root),s=$('.sheet',root);b&&b.classList.remove('show');s&&s.classList.remove('show');setTimeout(()=>{if(!overlayClose)root.innerHTML=''},280)};
}
function sheetBody(html){const b=$('#overlay .sbody');if(b)b.innerHTML=html}
function confirmDialog({title,body,ok='确定',danger=false}){
  return new Promise(res=>{
    closeOverlay();
    const root=$('#overlay');
    root.innerHTML=`<div class="backdrop"></div><div class="dialog" role="alertdialog" aria-modal="true"><h3>${title}</h3><p>${body}</p><div class="btn-row"><button class="btn ghost" data-r="0">取消</button><button class="btn ${danger?'danger':'primary'}" data-r="1">${ok}</button></div></div>`;
    requestAnimationFrame(()=>requestAnimationFrame(()=>{$('.backdrop',root).classList.add('show');$('.dialog',root).classList.add('show')}));
    const done=v=>{overlayClose=null;$('.backdrop',root).classList.remove('show');$('.dialog',root).classList.remove('show');setTimeout(()=>{if(!overlayClose)root.innerHTML=''},250);res(v)};
    overlayClose=()=>done(false);
    $$('[data-r]',root).forEach(b=>b.onclick=()=>done(b.dataset.r==='1'));
    $('.backdrop',root).onclick=()=>done(false);
  });
}
let toastT=null;
function toast(msg,icon='check'){
  const t=$('#toast');t.innerHTML=(icon?ic(icon):'')+'<span>'+esc(msg)+'</span>';t.classList.add('show');
  clearTimeout(toastT);toastT=setTimeout(()=>t.classList.remove('show'),1800);
}

/* ========== theme ========== */
const mq=window.matchMedia('(prefers-color-scheme: dark)');
function applyTheme(){
  const dark=S.theme==='dark'||(S.theme==='system'&&mq.matches);
  document.documentElement.dataset.theme=dark?'dark':'light';
}
mq.addEventListener&&mq.addEventListener('change',applyTheme);

/* ========== shared bits ========== */
const navbar=(title,{right='',back='back',act='pop'}={})=>`<div class="navbar"><button class="iconbtn" data-act="${act}" aria-label="返回">${ic(back)}</button><div class="title">${title}</div>${right||'<span class="spacer"></span>'}</div>`;
const sw=(on,act,label,extra='')=>`<button class="switch ${on?'on':''}" role="switch" aria-checked="${on}" aria-label="${esc(label)}" data-act="${act}" ${extra}></button>`;
const row=({icon,tone='',t,d='',v='',act='',chev=true,right='',attrs=''})=>`<${act?'button':'div'} class="row ${icon?'':'noicon'}" ${act?`data-act="${act}"`:''} ${attrs}>${icon?`<span class="ic ${tone}">${ic(icon)}</span>`:''}<span class="main"><div class="t">${t}</div>${d?`<div class="d">${d}</div>`:''}</span>${v?`<span class="v">${v}</span>`:''}${right}${act&&chev&&!right?ic('chev','chev'):''}</${act?'button':'div'}>`;
const chip=(t,tone='',icon='')=>`<span class="chip ${tone}">${icon?ic(icon):''}${t}</span>`;
function heroState(){
  if(!coreReady())return{cls:'off',title:'AI 代接未就绪',sub:`还差 ${CRITICAL.filter(k=>!S.ready[k]).length} 项必需配置，完成后即可开启`,icon:'alert'};
  if(!S.auto)return{cls:'off',title:'AI 代接已关闭',sub:'来电由系统电话正常处理，也可在响铃时手动让 AI 接听',icon:'phoneOff'};
  if(isPaused())return{cls:'paused',title:'自动接听已暂停',sub:`${hhmm(new Date(S.pausedUntil))} 自动恢复 · 期间来电照常响铃`,icon:'pauseC'};
  return{cls:'',title:'AI 代接 · 待命中',sub:`符合规则的来电响铃 ${S.delay} 秒后由 AI 接听`,icon:'shield'};
}
const AVG=[['#8A7BFF','#4F46E5'],['#4FB8FF','#2563EB'],['#FF8AC2','#D9387E'],['#FFC25C','#E8681A'],['#4FE0A6','#0B9A68'],['#B794FF','#7C3AED'],['#4FE3D2','#0E7C96']];
function avatar(name,{lg=false,dir='',taken=false}={}){
  const unknown=!name||/^[\d\s+]+$/.test(name)||name==='未知号码';
  let h=0;for(const ch of String(name))h=(h*31+ch.charCodeAt(0))>>>0;const g=AVG[h%AVG.length];
  const bg=unknown?'linear-gradient(135deg,#A3A1B8,#6E6C86)':`linear-gradient(135deg,${g[0]},${g[1]})`;
  const badge=dir?`<span class="badge ${taken?'take':dir}">${ic(taken?'hand':dir==='in'?'arrowDL':'arrowUR')}</span>`:'';
  return `<span class="av ${lg?'lg':''}" style="background:${bg}">${unknown?ic('user'):esc(String(name).replace(/[（(].*$/,'').slice(0,1))}${badge}</span>`;
}
const ILLO={
 calls:`<svg class="illo" viewBox="0 0 140 110" fill="none"><defs><linearGradient id="ig1" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8A7BFF"/><stop offset="1" stop-color="#5146E8"/></linearGradient></defs><ellipse cx="70" cy="100" rx="44" ry="5" fill="currentColor" opacity=".08"/><rect x="40" y="14" width="60" height="80" rx="14" fill="url(#ig1)"/><rect x="47" y="24" width="46" height="8" rx="4" fill="#fff" opacity=".9"/><rect x="47" y="38" width="34" height="6" rx="3" fill="#fff" opacity=".5"/><rect x="47" y="52" width="40" height="6" rx="3" fill="#fff" opacity=".5"/><rect x="47" y="66" width="28" height="6" rx="3" fill="#fff" opacity=".5"/><circle cx="104" cy="22" r="10" fill="#F2A93B"/><path d="M104 16v6l4 2" stroke="#fff" stroke-width="2.4" stroke-linecap="round"/><path d="M22 40l3 6 6 3-6 3-3 6-3-6-6-3 6-3z" fill="#F2A93B" opacity=".8"/><circle cx="118" cy="70" r="3" fill="#8A7BFF" opacity=".6"/></svg>`,
 search:`<svg class="illo" viewBox="0 0 140 110" fill="none"><defs><linearGradient id="ig2" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8A7BFF"/><stop offset="1" stop-color="#5146E8"/></linearGradient></defs><ellipse cx="70" cy="100" rx="40" ry="5" fill="currentColor" opacity=".08"/><circle cx="62" cy="50" r="28" fill="url(#ig2)" opacity=".15"/><circle cx="62" cy="50" r="22" stroke="url(#ig2)" stroke-width="7"/><path d="M79 67l18 18" stroke="url(#ig2)" stroke-width="9" stroke-linecap="round"/><path d="M54 46h16M54 55h10" stroke="#5146E8" stroke-width="4" stroke-linecap="round" opacity=".5"/><path d="M110 24l2 5 5 2-5 2-2 5-2-5-5-2 5-2z" fill="#F2A93B"/></svg>`,
 done:`<svg class="illo" viewBox="0 0 140 110" fill="none"><defs><linearGradient id="ig3" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#4FE0A6"/><stop offset="1" stop-color="#0B9A68"/></linearGradient></defs><ellipse cx="70" cy="100" rx="36" ry="5" fill="currentColor" opacity=".08"/><circle cx="70" cy="52" r="34" fill="url(#ig3)" opacity=".16"/><circle cx="70" cy="52" r="24" fill="url(#ig3)"/><path d="M59 52l8 8 15-16" stroke="#fff" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/><path d="M110 22l2 5 5 2-5 2-2 5-2-5-5-2 5-2z" fill="#F2A93B"/><circle cx="26" cy="36" r="4" fill="#4FE0A6" opacity=".6"/></svg>`,
 book:`<svg class="illo" viewBox="0 0 140 110" fill="none"><defs><linearGradient id="ig4" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFC25C"/><stop offset="1" stop-color="#E8681A"/></linearGradient></defs><ellipse cx="70" cy="100" rx="40" ry="5" fill="currentColor" opacity=".08"/><rect x="36" y="18" width="68" height="74" rx="12" fill="url(#ig4)"/><rect x="46" y="30" width="40" height="6" rx="3" fill="#fff" opacity=".9"/><rect x="46" y="44" width="48" height="5" rx="2.5" fill="#fff" opacity=".55"/><rect x="46" y="56" width="30" height="5" rx="2.5" fill="#fff" opacity=".55"/><circle cx="102" cy="78" r="13" fill="#5B57F5"/><path d="M102 72v12M96 78h12" stroke="#fff" stroke-width="3" stroke-linecap="round"/></svg>`,
 people:`<svg class="illo" viewBox="0 0 140 110" fill="none"><ellipse cx="70" cy="100" rx="40" ry="5" fill="currentColor" opacity=".08"/><circle cx="56" cy="42" r="16" fill="#8A7BFF"/><path d="M28 90a28 28 0 0156 0z" fill="#8A7BFF"/><circle cx="90" cy="48" r="13" fill="#4FB8FF"/><path d="M68 92a22 22 0 0144 0z" fill="#4FB8FF" opacity=".9"/><path d="M110 20l2 5 5 2-5 2-2 5-2-5-5-2 5-2z" fill="#F2A93B"/></svg>`,
};
const illo=k=>ILLO[k]||'';
function callIcon(c){
  if(c.taken)return`<span class="ic info">${ic('hand')}</span>`;
  return c.dir==='in'?`<span class="ic info">${ic('phoneIn')}</span>`:`<span class="ic">${ic('phoneOut')}</span>`;
}

/* ========== CALLS ========== */
const FILTERS=[['all','全部'],['open','待处理'],['done','已处理'],['rec','有录音'],['in','来电'],['out','去电']];
function filteredCalls(){
  const q=S.q.trim().toLowerCase();
  return S.calls.filter(c=>{
    if(S.filter==='open'&&c.status!=='open')return false;
    if(S.filter==='done'&&c.status!=='done')return false;
    if(S.filter==='rec'&&!c.rec)return false;
    if(S.filter==='in'&&c.dir!=='in')return false;
    if(S.filter==='out'&&c.dir!=='out')return false;
    if(q&&!(c.name+c.number+c.summary+c.tr.map(l=>l.text).join('')).toLowerCase().includes(q.replace(/\s/g,''))&&!(c.name+c.number+c.summary).toLowerCase().includes(q))return false;
    return true;
  });
}
function callsListHTML(){
  if(!S.callsLoaded)return `<div class="date-h"><span class="sk" style="display:inline-block;width:40px;height:12px"></span></div><div class="card">${[0,1,2,3].map(i=>`<div class="crow"><span class="sk" style="width:46px;height:46px;border-radius:50%;flex-shrink:0"></span><span class="cmain"><span style="display:flex;gap:10px"><span class="sk" style="height:15px;width:${40+i*7}%"></span><span class="sk" style="height:12px;width:34px;margin-left:auto"></span></span><span class="sk" style="display:block;height:12px;width:92%;margin-top:10px"></span><span class="sk" style="display:block;height:12px;width:60%;margin-top:6px"></span><span style="display:flex;gap:6px;margin-top:10px"><span class="sk" style="height:20px;width:54px;border-radius:10px"></span><span class="sk" style="height:20px;width:44px;border-radius:10px"></span></span></span></div>`).join('')}</div>`;
  if(!S.calls.length)return `<div class="empty">${illo('calls')}<h4>还没有 AI 通话记录</h4><p>AI 接听或拨出的电话，结束后会在这里显示原文、任务和录音</p><button class="btn primary sm" data-act="newTask">${ic('plus')}委派一次外呼</button></div>`;
  const list=filteredCalls();
  if(!list.length)return `<div class="empty">${illo('search')}<h4>没有符合条件的记录</h4><p>换个关键词，或清除筛选条件</p><button class="btn ghost sm" data-act="clearFilter">清除筛选</button></div>`;
  const days=[...new Set(list.map(c=>c.day))];
  return days.map(d=>`<div class="date-h">${d}</div><div class="card">${list.filter(c=>c.day===d).map(callItem).join('')}</div>`).join('');
}
/* ========== RULES ========== */
SCREENS.rules={tab:true,render(){
  const paused=isPaused();
  return `<div class="topbar"><div style="flex:1"><div class="sub">来电由 AI 接听的规则</div><h1>接听</h1></div></div>
  <div class="scroll">
    <div class="card">
      ${row({icon:'shield',tone:S.auto?'ok':'gray',t:'自动接听来电',d:!coreReady()?'完成就绪检查后可开启':S.auto?(paused?`已暂停 · ${hhmm(new Date(S.pausedUntil))} 自动恢复`:`待命中 · 响铃 ${S.delay} 秒后接听`):'已关闭 · 可在响铃时手动让 AI 接听',right:coreReady()?sw(S.auto,'toggleAuto','自动接听来电'):`<button class="btn sm secondary" data-act="openWizard">去配置</button>`})}
      ${S.auto&&coreReady()?`<div style="padding:0 14px 14px;display:flex;gap:8px">${paused?`<button class="btn sm secondary" style="flex:1" data-act="resumeAuto">恢复自动接听</button>`:`<button class="pill-btn" style="flex:1" data-act="pause" data-min="30">${ic('clock')}暂停 30 分钟</button><button class="pill-btn" style="flex:1" data-act="pause" data-min="120">${ic('clock')}暂停 2 小时</button>`}</div>`:''}
    </div>

    <div class="section-h"><h2>接听范围</h2></div>
    <button class="opt ${S.scope==='list'?'on':''}" data-act="scope" data-v="list"><span class="radio"></span><span class="main"><div style="font-weight:600">仅接听名单内号码</div><div class="mini">名单外的来电正常响铃，由你决定</div></span>${chip(S.allow.length+' 个','brand')}</button>
    <button class="opt ${S.scope==='all'?'on':''}" data-act="scope" data-v="all"><span class="radio"></span><span class="main"><div style="font-weight:600">接听所有来电</div><div class="mini">包含名单外号码及隐藏号码</div></span></button>

    <div class="card" style="margin-top:12px">
      ${row({icon:'users',t:'允许自动接听的号码',d:S.allow.length?S.allow.slice(0,2).join('、')+(S.allow.length>2?' 等':''):'名单为空',v:S.allow.length+' 个',act:'numbers',attrs:'data-kind="allow"'})}
      ${row({icon:'userX',tone:'warn',t:'始终由本人接听',d:'即使接听所有来电，AI 也不会接这些号码',v:S.bypass.length+' 个',act:'numbers',attrs:'data-kind="bypass"'})}
      <div class="row" style="flex-wrap:wrap"><span class="ic gray">${ic('clock')}</span><span class="main"><div class="t">接听等待</div><div class="d">给你留出自己接听的时间</div></span><span class="v num" style="font-weight:700;color:var(--text)" id="delayV">${S.delay} 秒</span>
      <input class="slider" type="range" min="0" max="15" value="${S.delay}" data-act-input="delay" aria-label="接听等待秒数" style="margin-left:48px"></div>
    </div>

    <div class="section-h"><h2>AI 开场白与声音</h2></div>
    <div class="card">
      ${row({icon:'message',t:'开场白与通话说明',d:S.greeting?`“${esc(S.greeting)}”`:'空白：接通后先听对方开口，不主动自报家门',act:'persona'})}
      ${row({icon:'speaker',t:'音色',v:esc(S.voice),act:'voiceSheet'})}
      ${row({icon:'clock',t:'AI 最长通话',d:'超时后 AI 礼貌结束通话',v:S.maxSec+' 秒',act:'maxSecSheet'})}
    </div>

    <div class="section-h" id="playbooks"><h2>接听交代 · 生效 ${S.playbooks.filter(p=>p.on).length}/${S.playbooks.length}</h2><a data-act="editPlaybook" style="gap:4px">${ic('plus').replace('<svg','<svg style="width:15px;height:15px"')}新增</a></div>
    ${S.playbooks.length?`<div class="card">${S.playbooks.map(p=>`<div class="row"><button class="main" style="text-align:left;min-height:44px" data-act="editPlaybook" data-id="${p.id}"><div class="t">${esc(p.title)} ${p.expiry!=='长期有效'?chip(p.expiry,'flash'):''}</div><div class="d">${esc(p.when)} → ${esc(p.reply)}</div></button>${sw(p.on,'togglePlaybook','启用 '+p.title,`data-id="${p.id}"`)}</div>`).join('')}</div>`
      :`<div class="card"><div class="empty" style="padding:24px 20px">${illo('book')}<h4>还没有接听交代</h4><p>写下某类来电该怎么回、要问清什么，修改从下一通电话生效</p><button class="btn sm primary" data-act="editPlaybook">新增交代</button></div></div>`}
    <div class="card" style="margin-top:12px">${row({icon:'sparkle',tone:'flash',t:'与 Codex 商量',d:'用对话把复杂情况整理成交代',act:'codex',right:chip('Beta','brand')})}</div>
  </div>`;
},after(e){if(e.params.scrollTo){const t=$('#'+e.params.scrollTo,e.el);if(t)setTimeout(()=>t.scrollIntoView({behavior:'smooth',block:'start'}),60);e.params.scrollTo=null}}};

/* ========== SETTINGS ========== */
function group(id,icon,tone,title,sum,inner){
  const open=!!S.openGroups[id];
  return `<section class="group ${open?'open':''}" id="g-${id}"><button class="group-h" data-act="toggleGroup" data-g="${id}" aria-expanded="${open}"><span class="ic ${tone}" style="width:36px;height:36px;border-radius:10px;display:flex;align-items:center;justify-content:center">${ic(icon).replace('<svg','<svg style="width:20px;height:20px"')}</span><span class="main" style="flex:1"><div style="font-weight:650">${title}</div><div class="mini">${sum}</div></span>${ic('down','gchev')}</button><div class="group-b"><div><div class="inner">${inner}</div></div></div></section>`;
}
/* ========== CALL DETAIL ========== */
const findCall=id=>S.calls.find(c=>c.id===id);
function trHTML(c){
  if(!c.tr.length)return `<div class="empty">这通电话没有保存文字记录。</div>`;
  const who={ai:'AI',caller:'对方',me:'你'};
  return c.tr.map(l=>l.who==='sys'?`<div class="sysline" style="color:var(--text-3)">${esc(l.text)}</div>`:`<button class="tline" style="width:100%;text-align:left" data-act="seekLine" data-id="${c.id}" data-t="${l.t}"><span class="ts num">${fmt(l.t)}</span><span><div class="who ${l.who}">${who[l.who]}</div><div class="tx">${esc(l.text)}</div></span></button>`).join('');
}
function briefHTML(c){
  if(c.dir==='in')return `<div class="card">${row({icon:'shield',tone:'info',t:'接听依据',d:esc(c.rule||'接听规则'),chev:false})}${row({icon:'message',tone:'gray',t:'开场白',d:S.greeting?esc(S.greeting):'空白：接通后先听对方开口',chev:false})}</div>`;
  const b=c.brief;
  return `<div class="card"><div class="review-block"><div class="k">希望办成什么</div><div class="val">${esc(b.goal)}</div></div>
  <div class="review-block"><div class="k">必须问清什么</div>${b.questions.length?`<ol>${b.questions.map(q=>`<li>${esc(q)}</li>`).join('')}</ol>`:'<div class="val mini">未填写</div>'}</div>
  <div class="review-block"><div class="k">补充情况</div><div class="val">${esc(b.context)||'<span class="mini">未填写</span>'}</div></div>
  <div class="review-block"><div class="k">不能承诺或执行的事</div><div class="val">${esc(b.constraints)||'<span class="mini">未填写</span>'}</div></div></div>`;
}
function stopPlayer(){if(S.player){clearInterval(S.player.timer);S.player.playing=false}}
/* ========== COMPOSE (new task) ========== */
const TEMPLATES=[
 ['询问营业与预约','核实今天是否正常营业、能否预约，不直接预订或付款。',['今天营业到几点？','现在过去需要排队吗？','可以电话预约吗？']],
 ['确认快递存放','确认包裹的具体存放位置，不透露门禁密码。',['东西具体放在哪个位置了？','需要我回电吗？']],
 ['预约上门维修','预约师傅上门检查，先问清时间和费用。',['最早什么时候能上门？','上门检查收费吗？']],
 ['咨询价格','问清价格和包含内容，不做任何承诺。',['基础价格是多少？','有没有额外费用？']],
];
const newDraft=(o={})=>Object.assign({step:0,number:'',sim:S.defaultSim||0,goal:'',questions:[''],context:'',constraints:'',mode:'now',err:{}},o);
const digits=s=>s.replace(/\D/g,'');
const STEP_NAMES=['号码与 SIM','目标','问题与边界','核对'];
function validateStep(){
  const d=S.draft;d.err={};
  if(d.step===0){const n=digits(d.number);if(n.length<7||n.length>15)d.err.number='请填写 7–15 位普通电话号码';if(!d.sim)d.err.sim='请选择这次使用的 SIM'}
  if(d.step===1){if(!d.goal.trim())d.err.goal='请写下这次要办成什么（1–400 字）'}
  return !Object.keys(d.err).length;
}

/* ========== LIVE CALL ========== */
const ANSWERS=['可以的，没问题。','建议提前半小时到，周五人比较多。','人均大概 180 元左右。','不需要额外费用。','最早明天上午 9 点。','我们营业到晚上 10 点。'];
function buildScript(L){
  const s=[];
  if(L.mode==='in'){
    s.push(['ai','您好，机主现在不方便接听，我是他的助理，请问有什么事？'],['caller','你好，顺丰快递，家里没人，包裹放哪儿？'],['ai','麻烦放 3 号楼的丰巢柜，放好后能告诉我取件码吗？',0],['caller','行，放好了，取件码 5821。',0],['ai','收到，5821。还需要机主回电吗？'],['caller','不用了。',1],['ai','好的，辛苦您，再见。']);
    return s;
  }
  s.push(['ai','您好，打扰一下，我想咨询点事情。'],['caller','您好，请说。']);
  L.questions.forEach((q,i)=>{s.push(['ai',q.text]);s.push(['caller',ANSWERS[i%ANSWERS.length],i])});
  if(!L.questions.length){s.push(['ai',L.goal]);s.push(['caller','可以的，没问题。'])}
  s.push(['ai','好的，都清楚了，谢谢您，再见！'],['caller','好，再见。']);
  return s;
}
function startLive(o){
  const L=S.live=Object.assign({mode:'out',phase:o.mode==='in'?'connected':'dialing',sec:0,lines:[],idx:0,taken:false,muted:false,speaker:false,typing:null,ended:false,timers:[]},o);
  L.script=buildScript(L);
  L.lines.push({who:'sys',text:L.mode==='in'?`来电 · 响铃 ${S.delay} 秒后 AI 接听`:'开始拨号',t:0});
  const tick=setInterval(()=>{
    if(L.ended)return;
    if(L.phase==='connected'){L.sec++;$$('[data-live-timer]').forEach(n=>n.textContent=fmt(L.sec))}
  },1000);
  L.tick=tick;
  const later=(ms,f)=>{const t=setTimeout(()=>{if(!L.ended)f()},ms);L.timers.push(t)};
  const step=()=>{
    if(L.taken||L.ended)return;
    if(L.idx>=L.script.length){later(1200,()=>endLive('done'));return}
    const [who,text,qi]=L.script[L.idx];
    L.typing=who;liveRefresh();
    later(900+Math.min(1400,text.length*35),()=>{
      L.typing=null;L.lines.push({who,text,t:L.sec});
      if(qi!=null&&L.questions[qi])L.questions[qi].done=true;
      L.idx++;liveRefresh();later(500,step);
    });
  };
  if(L.mode==='out'){
    later(1500,()=>{L.phase='ringing';liveRefresh()});
    later(3200,()=>{L.phase='connected';L.lines.push({who:'sys',text:'已接通 · AI 开始模拟对话',t:0});liveRefresh();later(400,step)});
  }else{L.lines.push({who:'sys',text:'AI 已模拟接听',t:0});later(300,step)}
}
function liveRefresh(){const e=topE();if(e&&e.name==='live')paint(e);else if(e&&e.name==='home')paint(e)}
function endLive(reason){
  const L=S.live;if(!L||L.ended)return;
  L.ended=true;clearInterval(L.tick);L.timers.forEach(t=>clearTimeout(t));L.typing=null;
  const id='c'+Date.now();
  const done=L.questions.filter(q=>q.done).length;
  const rec={id,dir:L.mode,name:L.name,number:L.number,day:'今天',time:hhmm(new Date()),dur:Math.max(L.sec,8),status:'open',rec:true,taken:L.taken,sim:L.sim,
    outcome:L.taken?['已接管','info']:L.mode==='in'?['已留言','info']:(done===L.questions.length?['已问清','ok']:['部分问清','warn']),
    rule:L.mode==='in'?'允许名单 · 命中交代「快递外卖」':undefined,
    summary:L.mode==='in'?'包裹已放 3 号楼丰巢柜，取件码 5821；对方无需回电。':(L.taken?`AI 完成开场后，你在 ${fmt(L.takenAt||L.sec)} 接管通话。`:`${done}/${L.questions.length} 个问题已问清：`+L.lines.filter(l=>l.who==='caller').slice(1).map(l=>l.text.replace(/。$/,'')).join('；')+'。'),
    brief:L.mode==='out'?{goal:L.goal,questions:L.questions.map(q=>q.text),context:L.context||'',constraints:L.constraints||''}:undefined,
    tr:L.lines.map((l,i)=>({t:l.t!=null?l.t:(i?L.sec:0),who:l.who,text:l.text}))};
  rec.tr.forEach((l,i)=>{if(i&&l.t<rec.tr[i-1].t)l.t=rec.tr[i-1].t});
  S.calls.unshift(rec);L.recordId=id;
  const e=topE();
  if(e&&e.name==='live'){paint(e);
    openSheet({title:reason==='hangup'?'通话已挂断':'通话已结束',desc:`时长 ${fmt(L.sec)} · 已保存模拟原文 · 未生成录音`,body:`${L.mode==='out'?`<div class="card pad" style="margin-bottom:14px"><div class="mini">问清进度</div><div style="font-size:22px;font-weight:750" class="num">${done} / ${L.questions.length}</div><div class="progress" style="margin-top:6px"><i style="width:${L.questions.length?done/L.questions.length*100:100}%"></i></div></div>`:''}<button class="btn primary" data-act="liveToDetail">查看通话详情</button><div style="height:8px"></div><button class="btn ghost" data-act="liveDone">完成</button>`});
  }else{toast('通话已结束，已保存到记录');chrome();if(e)paint(e)}
}
SCREENS.live={mode:'fade',cls:'live',dark:true,render(){
  const L=S.live;if(!L)return '';
  const phaseTxt=L.ended?'通话已结束':L.phase==='dialing'?'正在拨号…':L.phase==='ringing'?'对方响铃中…':L.taken?'你正在通话':'AI 通话中';
  const done=L.questions.filter(q=>q.done).length;
  const who={ai:'AI',caller:'对方',me:'你'};
  const speaking=L.typing==='ai';
  return `<div class="navbar"><button class="iconbtn" data-act="minimizeLive" aria-label="最小化">${ic('down')}</button><div class="title" style="font-size:14px;color:#A5B4FC">${L.mode==='in'?'AI 代接来电':'AI 外呼任务'}</div><span class="spacer"></span></div>
  <div class="live-head"><div class="live-avatar ${speaking?'speaking':''}" style="background:${(()=>{let h=0;for(const ch of L.name)h=(h*31+ch.charCodeAt(0))>>>0;const g=AVG[h%AVG.length];return /^[\d\s]+$/.test(L.name)?'linear-gradient(135deg,#A3A1B8,#6E6C86)':`linear-gradient(135deg,${g[0]},${g[1]})`})()}">${/^[\d\s]+$/.test(L.name)?ic('user'):esc(L.name.slice(0,1))}</div><div class="live-name">${esc(L.name)}</div><div class="live-sub num">${phaseTxt}${L.phase==='connected'?` · <span data-live-timer>${fmt(L.sec)}</span>`:''}</div>
  <div class="live-chips">${L.taken?chip('模拟接管','warn'):L.phase==='connected'&&!L.ended?'<span class="chip ok"><span class="pulse"></span>通话演示</span>':''}${chip(L.sim?'SIM '+L.sim:'SIM 1')}${chip('AI 最长 '+S.maxSec+' 秒')}</div>
  <div class="vviz ${L.ended||L.taken?'':L.typing||'idle'}" aria-hidden="true">${Array.from({length:30},(_,i)=>`<i style="--h:${(.3+.7*Math.abs(Math.sin(i*2.1)*Math.cos(i*.6))).toFixed(2)};animation-delay:${(-i*.07).toFixed(2)}s"></i>`).join('')}</div>
  <div class="vlab">${L.ended?'':L.taken?'AI 已退出':L.typing==='ai'?'AI 正在说话':L.typing==='caller'?'对方正在说话':L.phase==='connected'?'正在聆听':''}</div></div>
  ${L.mode==='out'?`<div class="goal-card"><div class="gt"><span>本次目标</span><span class="num">问清 ${done}/${L.questions.length}</span></div><div class="gg clamp">${esc(L.goal)}</div><div class="gprog"><i style="width:${L.questions.length?done/L.questions.length*100:0}%"></i></div>${(L.goalOpen?L.questions:L.questions.filter(q=>!q.done).slice(0,1)).map(q=>`<div class="goal-q ${q.done?'done':''}"><span class="ck">${q.done?ic('check'):''}</span>${L.goalOpen?'':'<span class="nextq">下一个</span>'}${esc(q.text)}</div>`).join('')||`<div class="goal-q done"><span class="ck">${ic('check')}</span>全部问题已问清</div>`}<button class="goal-tog" data-act="goalToggle">${L.goalOpen?'收起':'查看全部 '+L.questions.length+' 个问题'}</button></div>`
   :`<div class="goal-card"><div class="gt"><span>接听依据</span></div><div class="gg" style="margin:4px 0 0">允许名单 · 命中交代「快递外卖」</div></div>`}
  <div class="transcript" id="liveTr" aria-live="polite">${L.lines.map(l=>l.who==='sys'?`<div class="sysline">${esc(l.text)}</div>`:`<div class="bubble ${l.who}"><div class="who">${who[l.who]}</div>${esc(l.text)}</div>`).join('')}
  ${L.typing?`<div class="bubble ${L.typing}" style="width:64px"><span class="typing"><i></i><i></i><i></i></span></div>`:''}
  ${L.phase!=='connected'&&!L.ended?`<div class="sysline"><span class="typing"><i></i><i></i><i></i></span></div>`:''}</div>
  <div class="live-ctrl">
    ${L.ended?'':L.taken?`<div class="takeover done">${ic('hand')}你已接管 · AI 已退出</div>`:`<button class="takeover" data-act="takeover" ${L.phase!=='connected'?'disabled style="opacity:.5"':''}>${ic('hand')}人工接管</button>`}
    <div class="ctrl-row">
      <button class="cbtn ${L.muted?'on':''}" data-act="liveMute" aria-pressed="${L.muted}"><span class="cc">${ic(L.muted?'micOff':'mic')}</span>静音</button>
      <button class="cbtn ${L.speaker?'on':''}" data-act="liveSpeaker" aria-pressed="${L.speaker}"><span class="cc">${ic('speaker')}</span>免提</button>
      <button class="cbtn end" data-act="hangup" ${L.ended?'disabled':''}><span class="cc">${ic('phoneOff')}</span>挂断</button>
    </div>
  </div>`;
},after(e){const t=$('#liveTr',e.el);if(t)t.scrollTop=t.scrollHeight}};

/* ========== INCOMING ========== */
SCREENS.incoming={mode:'fade',cls:'incoming',dark:true,render(){
  const I=S.incoming;const autoOn=S.auto&&coreReady()&&!isPaused();
  return `<div style="padding:90px 24px 0;text-align:center">
    <div class="chip" style="background:rgba(255,255,255,.12);color:#C7D2FE;margin-bottom:24px">${ic('phoneIn')}来电 · SIM 1</div>
    <div class="ring-anim">${ic('phone')}</div>
    <div style="font-size:28px;font-weight:750;margin-top:26px">快递员</div><div class="num" style="color:#A5B4FC">186 2033 4471</div>
    <div class="chips" style="justify-content:center;margin-top:14px"><span class="chip" style="background:rgba(34,197,94,.18);color:#86EFAC">${ic('check')}在允许名单</span><span class="chip" style="background:rgba(245,158,11,.2);color:#FCD34D">交代「快递外卖」</span></div>
  </div>
  <div style="text-align:center">${I.connecting?`<div style="color:#C7D2FE"><span class="spinner"></span><div style="margin-top:8px">正在连接 AI，来电仍在响铃</div></div>`:autoOn&&!I.cancelled?`<div class="countdown">${I.cd}</div><div style="color:#C7D2FE">秒后 AI 自动接听</div>`:`<div style="color:#C7D2FE">${I.cancelled?'已取消 AI 接听，来电仍在响铃':'自动接听未开启'}</div>`}</div>
  <div style="padding:0 24px 44px;display:flex;flex-direction:column;gap:12px">
    ${autoOn&&!I.cancelled?`<button class="btn" style="background:rgba(255,255,255,.12);color:#fff" data-act="cancelAiAnswer">取消 AI 接听</button>`:`<button class="btn" style="background:#4F46E5;color:#fff" data-act="aiAnswerNow">${ic('sparkle')}让 AI 接听</button>`}
    <div class="btn-row"><button class="btn" style="background:#EF4444;color:#fff" data-act="declineIncoming">${ic('phoneOff')}拒接</button><button class="btn" style="background:#22C55E;color:#04210f" data-act="selfAnswer">${ic('phone')}我来接</button></div>
  </div>`;
},leave(){if(S.incoming)clearInterval(S.incoming.timer)}};
function incomingConnect(){
  const I=S.incoming;clearInterval(I.timer);I.connecting=true;rerender();
  setTimeout(()=>{if(topE().name!=='incoming')return;S.incoming=null;
    const e=stack.pop();e.el.remove();
    startLive({mode:'in',name:'快递员',number:'186 2033 4471',sim:1,questions:[{text:'问清放在哪里',done:false},{text:'是否需要回电',done:false}],goal:''});
    mount('live',{},true);},1100);
}

/* ========== NUMBERS ========== */
SCREENS.numbers={render(p){
  const list=S[p.kind];const allow=p.kind==='allow';
  return navbar(allow?'允许自动接听的号码':'始终由本人接听')+`<div class="scroll">
    <p class="mini" style="margin:4px 4px 12px">${allow?'名单内号码来电时，AI 在等待时间后接听。最多 100 个。':'这些号码仍会正常响铃，由你本人接听。即使开启「接听所有来电」，AI 也不会自动接听。'}</p>
    <div style="display:flex;gap:8px"><input class="input num" id="numIn" type="tel" placeholder="输入号码" aria-label="新号码"><button class="btn primary" style="width:auto;padding:0 18px" data-act="addNum" data-kind="${p.kind}">添加</button></div>
    <div class="hint err" id="numErr" style="font-size:12px;color:var(--danger);margin:6px 2px 0"></div>
    <div class="section-h"><h2>${list.length} 个号码</h2></div>
    ${list.length?`<div class="card">${list.map((n,i)=>`<div class="row"><span class="ic ${allow?'':'warn'}">${ic(allow?'user':'userX')}</span><span class="main"><div class="t num">${esc(n)}</div></span><button class="iconbtn" style="color:var(--danger)" data-act="delNum" data-kind="${p.kind}" data-i="${i}" aria-label="删除 ${esc(n)}">${ic('trash')}</button></div>`).join('')}</div>`
    :`<div class="empty">${illo('people')}<h4>尚未添加号码</h4><p>${allow?'名单为空时，未开启「接听所有来电」就不会自动接听':'把重要的人加进来，AI 永远不会替你接'}</p></div>`}
  </div>`;
}};

/* ========== PERSONA ========== */
SCREENS.persona={render(){
  return navbar('开场白与通话说明')+`<div class="scroll">
    <div class="field" style="margin-top:6px"><label for="pg">开场白</label><textarea id="pg" class="textarea" placeholder="留空则接通后先听对方开口" data-bind-p="greeting" maxlength="200">${esc(S.greeting)}</textarea><div class="hint"><span>按填写的内容播报；留空则等对方先开口</span><span class="num" data-cnt-p="greeting">${S.greeting.length} / 200</span></div></div>
    <div class="label">常用开场白</div><div class="chips" style="margin-bottom:18px">${['您好，我是机主的助理，请问有什么事？','您好，机主现在不方便接听，我可以帮您转达。'].map(g=>`<button class="pill-btn" data-act="pickGreeting" data-v="${esc(g)}">${esc(g.slice(0,12))}…</button>`).join('')}</div>
    <div class="field"><label for="pi">通话目的与说明</label><textarea id="pi" class="textarea" style="min-height:150px" data-bind-p="instructions" maxlength="1000">${esc(S.instructions)}</textarea><div class="hint"><span>对所有 AI 通话生效；单次外呼的任务单优先</span><span class="num" data-cnt-p="instructions">${S.instructions.length} / 1000</span></div></div>
  </div><div class="footer"><button class="btn primary" data-act="savePersona">保存</button></div>`;
}};

/* ========== ACTIONS ========== */
const A={
  pop:()=>pop(),
  tab:el=>{switchTab(el.dataset.tab);if(el.dataset.scroll){topE().params.scrollTo=el.dataset.scroll;paint(topE())}},
  closeSheet:()=>closeOverlay(),
  noop:()=>toast('演示原型：此处会跳转到系统设置','info'),
  goAbout:()=>{S.openGroups.about=true;switchTab('settings');topE().params.scrollTo='g-about';paint(topE())},
  goDevice:()=>{S.openGroups.device=true;switchTab('settings')},
  toggleAuto:()=>{S.auto=!S.auto;S.pausedUntil=null;S.ready.standby=S.auto;toast(S.auto?'已开启自动接听，后台待命已启动':'已关闭自动接听，后台待命已撤销',S.auto?'check':'info');rerender();},
  pauseSheet:()=>openSheet({title:'临时暂停自动接听',desc:'暂停期间来电照常响铃，由你决定是否接听。',body:`<button class="menu-item" data-act="pause" data-min="30">${ic('clock')}暂停 30 分钟</button><button class="menu-item" data-act="pause" data-min="120">${ic('clock')}暂停 2 小时</button><button class="menu-item" data-act="pause" data-min="600">${ic('moon')}暂停 10 小时</button>`}),
  pause:el=>{S.pausedUntil=Date.now()+(+el.dataset.min)*60000;closeOverlay();toast('已暂停，'+hhmm(new Date(S.pausedUntil))+' 自动恢复','info');rerender()},
  resumeAuto:()=>{S.pausedUntil=null;toast('已恢复自动接听');rerender()},
  fixReady:el=>{const k=el.dataset.k;
    if(k==='battery'){confirmDialog({title:'豁免电池优化？',body:'将打开系统电池设置。允许后，后台待命不容易被系统停止。',ok:'打开设置'}).then(ok=>{if(ok){S.ready.battery=true;toast('已豁免电池优化');paint(topE())}});return}
    if(k==='standby'){S.ready.standby=true;toast('后台待命已启动');rerender();return}
    openWizard({step:{perm:0,notif:0,helper:1,cred:2,audio:3}[k]||0})},
  openWizard:el=>openWizard({step:el&&el.dataset.step?+el.dataset.step:0}),
  closeWizard:()=>{pop()},
  wzGrant:el=>{S.wz[el.dataset.k]=true;rerender()},
  wzNext:()=>{S.wz.step++;rerender()},wzPrev:()=>{S.wz.step--;rerender()},
  wzPair:()=>{const w=S.wz;w.code=$$('[data-code]').map(i=>i.value);if(w.code.join('').replace(/\D/g,'').length<6){w.pairErr='请输入完整的 6 位配对码';rerender();return}
    w.pairErr='';w.pairing=true;rerender();setTimeout(()=>{w.pairing=false;w.paired=true;rerender();toast('音频助手已连接')},1400)},
  wzVerify:()=>{const w=S.wz;w.key=($('#wzKey')||{}).value||w.key;w.cred='checking';rerender();
    setTimeout(()=>{w.cred=(w.key.length>=8&&!/bad/i.test(w.key))?'ok':'err';rerender()},1200)},
  wzTestCall:()=>{S.wz.tested=true;toast('请用另一台手机拨打本机号码','info')},
  wzCheck:el=>{const i=+el.dataset.i;S.wz.test[i]=!S.wz.test[i];rerender()},
  wzEnable:()=>{S.wz.enable=!S.wz.enable;rerender()},
  wzFinish:()=>{Object.assign(S.ready,{perm:true,notif:true,helper:true,cred:true,audio:true});S.auto=S.wz.enable;S.ready.standby=S.wz.enable;pop();toast(S.auto?'配置完成，AI 代接已开启':'配置完成')},
  newTask:()=>{S.draft=newDraft();push('compose')},
  closeCompose:()=>{const d=S.draft;if(d&&(d.number||d.goal)){confirmDialog({title:'放弃这个草稿？',body:'已填写的号码和任务内容不会保存。',ok:'放弃',danger:true}).then(ok=>{if(ok)pop()})}else pop()},
  clearDraft:()=>{S.draft=newDraft();rerender()},
  nextStep:()=>{if(validateStep()){S.draft.step++;}rerender()},
  prevStep:()=>{S.draft.step--;S.draft.err={};rerender()},
  goStep:el=>{S.draft.step=+el.dataset.s;rerender()},
  pickSim:el=>{S.draft.sim=+el.dataset.v;S.draft.err.sim=null;rerender()},
  pickNum:el=>{S.draft.number=el.dataset.n;S.draft.err={};rerender()},
  contacts:()=>openSheet({title:'从通讯录选择',body:[['物业服务中心','021 5466 1200'],['鼎泰丰（来福士店）','021 6386 3299'],['社区诊所','021 6211 3080'],['王老师','138 0013 2210']].map(([n,num])=>`<button class="menu-item" data-act="pickContact" data-n="${num}">${ic('user')}<span style="flex:1">${n}</span><span class="mini num">${num}</span></button>`).join('')}),
  pickContact:el=>{S.draft.number=el.dataset.n;S.draft.err={};closeOverlay();rerender()},
  tpl:el=>{const t=TEMPLATES[+el.dataset.i];S.draft.goal=t[1];S.draft.questions=t[2].slice();S.draft.err={};rerender();toast('已填入「'+t[0]+'」，可继续修改')},
  addQ:()=>{S.draft.questions.push('');rerender();const ins=$$('[data-bind="q"]');ins[ins.length-1]&&ins[ins.length-1].focus()},
  delQ:el=>{S.draft.questions.splice(+el.dataset.i,1);if(!S.draft.questions.length)S.draft.questions=[''];rerender()},
  dialMode:el=>{S.draft.mode=el.dataset.v;rerender()},
  confirmDial:()=>{const d=S.draft;const go=()=>{const name=(S.calls.find(c=>c.number===d.number)||{}).name||d.number;
      startLive({mode:'out',name,number:d.number,sim:d.sim,goal:d.goal,context:d.context,constraints:d.constraints,questions:d.questions.filter(q=>q.trim()).map(t=>({text:t.trim(),done:false}))});
      S.draft=null;replaceTop('live')};
    if(d.mode==='locked'){let n=45;openSheet({title:'请在 45 秒内锁屏',desc:'锁屏后 Flash phone 会按本次号码、SIM 和任务尝试拨出一次。',body:`<div style="text-align:center;padding:10px 0 18px"><div class="countdown num" id="lockCd" style="color:var(--brand)">45</div><div class="mini">秒</div></div><button class="btn primary" data-act="simLock">${ic('lock')}模拟锁屏（演示）</button><div style="height:8px"></div><button class="btn ghost" data-act="cancelLock">取消</button>`});
      S.lockTimer=setInterval(()=>{n--;const c=$('#lockCd');if(c)c.textContent=n;if(n<=0){clearInterval(S.lockTimer);closeOverlay();toast('未在 45 秒内锁屏，已取消拨号','info')}},1000);S.lockGo=go;return}
    go()},
  simLock:()=>{clearInterval(S.lockTimer);closeOverlay();S.lockGo&&S.lockGo()},
  cancelLock:()=>{clearInterval(S.lockTimer);closeOverlay();toast('已取消锁屏拨号','info')},
  takeover:()=>{const L=S.live;if(!L||L.phase!=='connected')return;L.taken=true;L.takenAt=L.sec;L.typing=null;L.timers.forEach(t=>clearTimeout(t));
    L.lines.push({who:'sys',text:'你已接管通话 · AI 已退出，录音已停止',t:L.sec});rerender();toast('已模拟接管，未操作真实电话','hand')},
  goalToggle:()=>{S.live.goalOpen=!S.live.goalOpen;rerender()},
  liveMute:()=>{S.live.muted=!S.live.muted;rerender()},
  liveSpeaker:()=>{S.live.speaker=!S.live.speaker;rerender()},
  hangup:()=>endLive('hangup'),
  minimizeLive:()=>{if(S.live&&S.live.ended){A.liveDone();return}pop()},
  resumeLive:()=>{if(S.live&&!S.live.ended)push('live')},
  liveToDetail:()=>{const id=S.live.recordId;closeOverlay();S.live=null;S.detailSeg='tr';replaceTop('detail',{id})},
  liveDone:()=>{closeOverlay();S.live=null;pop()},
  simIncoming:()=>{if(S.live&&!S.live.ended){toast('当前有通话，结束后继续待命','info');return}
    S.incoming={cd:S.delay,cancelled:false,connecting:false};push('incoming');
    const autoOn=S.auto&&coreReady()&&!isPaused();
    if(autoOn){S.incoming.timer=setInterval(()=>{const I=S.incoming;if(!I||I.cancelled)return;I.cd--;if(I.cd<=0)incomingConnect();else rerender()},1000)}},
  cancelAiAnswer:()=>{const I=S.incoming;clearInterval(I.timer);I.cancelled=true;rerender();toast('已取消 AI 接听，来电仍在响铃','info')},
  aiAnswerNow:()=>incomingConnect(),
  declineIncoming:()=>{S.incoming=null;pop();toast('已拒接','info')},
  selfAnswer:()=>{S.incoming=null;pop();toast('已模拟转交本人接听','phone')},
  openCall:el=>{S.detailSeg='tr';push('detail',{id:el.dataset.id})},
  detailSeg:el=>{S.detailSeg=el.dataset.v;rerender()},
  toggleDone:el=>{const c=findCall(el.dataset.id);c.status=c.status==='open'?'done':'open';toast(c.status==='done'?'已标为已处理':'已标为待处理');rerender();chrome()},
  reuse:el=>{const c=findCall(el.dataset.id);
    const go=()=>{S.draft=newDraft({number:c.number,sim:c.sim||S.defaultSim,goal:c.brief.goal,questions:c.brief.questions.slice(),context:c.brief.context,constraints:c.brief.constraints,step:3});push('compose');toast('已用这份交代新建草稿，此操作不会拨号','info')};
    go()},
  callBack:el=>{const c=findCall(el.dataset.id);S.draft=newDraft({number:c.number,goal:'回电给'+c.name+'，',step:1});push('compose')},
  detailMore:el=>{const id=el.dataset.id;openSheet({body:`<button class="menu-item" data-act="copyTr" data-id="${id}">${ic('copy')}复制通话原文</button><button class="menu-item" data-act="shareRec">${ic('download')}导出录音（本机）</button><button class="menu-item danger" data-act="delCall" data-id="${id}">${ic('trash')}删除记录</button>`})},
  copyTr:()=>{closeOverlay();toast('已复制通话原文')},
  shareRec:()=>{closeOverlay();toast('演示原型：此处会调起系统分享','info')},
  delCall:el=>{const id=el.dataset.id;confirmDialog({title:'删除这条通话记录？',body:'将从本机删除这条记录、文字原文及加密录音，删除后无法恢复。',ok:'删除',danger:true}).then(ok=>{if(!ok)return;S.calls=S.calls.filter(c=>c.id!==id);stopPlayer();pop();toast('记录已删除')})},
  playToggle:el=>{const c=findCall(el.dataset.id);let p=S.player;if(!p||p.id!==c.id){stopPlayer();p=S.player={id:c.id,pos:0,playing:false,speed:1}}
    p.playing=!p.playing;clearInterval(p.timer);
    if(p.playing){if(p.pos>=c.dur)p.pos=0;p.timer=setInterval(()=>{p.pos+=.25*p.speed;if(p.pos>=c.dur){p.pos=c.dur;p.playing=false;clearInterval(p.timer)}updatePlayerDOM()},250)}
    updatePlayerDOM()},
  speed:el=>{const p=S.player||(S.player={id:topE().params.id,pos:0,playing:false,speed:1});p.speed=p.speed===1?1.5:p.speed===1.5?2:1;el.textContent=p.speed+'×'},
  seek:(el,ev)=>{const c=findCall(el.dataset.id);const r=el.getBoundingClientRect();let p=S.player;if(!p||p.id!==c.id){stopPlayer();p=S.player={id:c.id,pos:0,playing:false,speed:1}}
    p.pos=Math.max(0,Math.min(1,(ev.clientX-r.left)/r.width))*c.dur;updatePlayerDOM()},
  seekLine:el=>{const c=findCall(el.dataset.id);if(!c.rec)return;let p=S.player;if(!p||p.id!==c.id){stopPlayer();p=S.player={id:c.id,pos:0,playing:false,speed:1}}p.pos=+el.dataset.t;updatePlayerDOM()},
  setFilter:el=>{S.filter=el.dataset.f;rerender()},
  clearFilter:()=>{S.filter='all';S.q='';rerender()},
  clearQ:()=>{S.q='';rerender()},
  callsMore:()=>openSheet({body:`<button class="menu-item" data-act="markAllDone">${ic('check')}全部标为已处理</button><button class="menu-item danger" data-act="clearAll">${ic('trash')}清空全部记录</button>`}),
  markAllDone:()=>{S.calls.forEach(c=>c.status='done');closeOverlay();toast('已全部标为已处理');rerender();chrome()},
  clearAll:()=>confirmDialog({title:'清空全部通话记录？',body:'将从本机永久删除全部 AI 通话记录、任务快照及关联的加密录音文件，无法恢复。',ok:'清空',danger:true}).then(ok=>{if(ok){S.calls=[];toast('已清空全部通话记录');rerender();chrome()}}),
  scope:el=>{S.scope=el.dataset.v;rerender()},
  numbers:el=>push('numbers',{kind:el.dataset.kind}),
  addNum:el=>{const k=el.dataset.kind;const v=$('#numIn').value.trim();const n=digits(v);const err=$('#numErr');
    if(n.length<7||n.length>15){err.textContent='请填写 7–15 位有效号码';return}
    if(S[k].some(x=>digits(x)===n)){err.textContent='这个号码已在名单中';return}
    if(S[k].length>=100){err.textContent='最多可设置 100 个号码';return}
    S[k].unshift(v);rerender();toast('已添加')},
  delNum:el=>{S[el.dataset.kind].splice(+el.dataset.i,1);rerender();toast('已移除')},
  persona:()=>push('persona'),
  pickGreeting:el=>{S.greeting=el.dataset.v;rerender()},
  savePersona:()=>{S.greeting=$('#pg').value.trim();S.instructions=$('#pi').value.trim();pop();toast('已保存，从下一通电话生效')},
  voiceSheet:()=>openSheet({title:'音色',desc:'复刻音色需在火山控制台已有正式槽位。',body:['默认标准音色','复刻音色：我的声音'].map(v=>`<button class="opt ${S.voice===v?'on':''}" data-act="pickVoice" data-v="${v}"><span class="radio"></span><span class="main">${v}</span></button>`).join('')}),
  pickVoice:el=>{S.voice=el.dataset.v;closeOverlay();rerender();toast('已保存音色')},
  maxSecSheet:()=>openSheet({title:'AI 最长通话',desc:'15–600 秒。超时后 AI 礼貌结束通话。',body:`<div style="text-align:center;font-size:34px;font-weight:800" class="num" id="msV">${S.maxSec} 秒</div><input class="slider" type="range" min="15" max="600" step="15" value="${S.maxSec}" data-act-input="maxSec" aria-label="AI 最长秒数"><div style="height:12px"></div><button class="btn primary" data-act="closeAndRender">完成</button>`}),
  closeAndRender:()=>{closeOverlay();rerender()},
  simSheet:()=>openSheet({title:'默认拨号 SIM',body:SIMS.map(s=>`<button class="opt ${S.defaultSim===s.id?'on':''}" data-act="pickDefSim" data-v="${s.id}"><span class="radio"></span><span class="main"><div style="font-weight:600">${s.name}</div><div class="mini num">${s.num}</div></span></button>`).join('')+`<button class="opt ${!S.defaultSim?'on':''}" data-act="pickDefSim" data-v="0"><span class="radio"></span><span class="main">不指定，每次选择</span></button>`}),
  pickDefSim:el=>{S.defaultSim=+el.dataset.v;closeOverlay();rerender();toast('已保存')},
  credSheet:()=>openSheet({title:'实时语音凭据',desc:'火山实时语音 3.0 · 凭据加密保存在本机',body:`<div class="field"><label for="ck">API Key</label><input id="ck" class="input" type="password" value="sk-demo-3f9a" autocomplete="off"></div><div class="notice" style="margin-bottom:14px">${ic('info')}<span>需具备实时语音权限；方舟聊天 Key 不能用。保存时不联网，首次通话时验证。</span></div><button class="btn primary" data-act="saveCred">保存</button>`}),
  saveCred:()=>{S.ready.cred=true;closeOverlay();toast('已更新示例状态（未保存真实凭据）');rerender()},
  toggleRecovery:()=>{S.recovery=!S.recovery;rerender()},
  toggleGroup:el=>{const g=el.dataset.g;S.openGroups[g]=!S.openGroups[g];const sec=$('#g-'+g);if(sec){sec.classList.toggle('open',S.openGroups[g]);el.setAttribute('aria-expanded',S.openGroups[g])}},
  theme:el=>{S.theme=el.dataset.v;applyTheme();rerender()},
  checkUpdate:()=>{S.update.state='checking';rerender();setTimeout(()=>{S.update.state='available';if(topE().name==='settings')rerender()},1300)},
  downloadUpdate:()=>{const u=S.update;u.state='downloading';u.pct=0;rerender();const t=setInterval(()=>{u.pct=Math.min(100,u.pct+7+Math.round(Math.random()*9));if(u.pct>=100){clearInterval(t);u.state='ready'}if(topE().name==='settings')rerender()},300)},
  installUpdate:()=>{toast('已打开 Android 安装确认（演示）','info');S.update.state='latest';rerender()},
  changelog:()=>openSheet({title:'更新记录',body:[['界面预览','11月8日','锁屏后拨出；通话录音双声道回放'],['早期原型','11月2日','接听交代支持有效期；修复自动恢复'],['早期原型','10月27日','全新外呼任务单与核对页']].map(([v,d,t])=>`<div class="row noicon"><span class="main"><div class="t">${v} <span class="mini">· ${d}</span></div><div class="d">${t}</div></span></div>`).join('')}),
  feedback:()=>openSheet({title:'反馈问题',desc:'遇到问题、有想法，选一个方便的地方告诉作者',body:`<button class="menu-item" data-act="repo">${ic('code')}去 GitHub 提问题</button><button class="menu-item" data-act="copyDiag">${ic('copy')}复制诊断信息<span class="mini" style="margin-left:auto">不含 Key 和通话内容</span></button>`}),
  copyDiag:()=>{closeOverlay();toast('诊断信息已复制')},
  repo:()=>{closeOverlay();toast('演示原型：将打开 GitHub','info')},
  license:()=>openSheet({title:'Apache-2.0 许可证',body:'<p class="mini">Flash phone 是基于 wangzan101 和贡献者开发的 Mika 构建的独立项目。完整许可证文本与第三方声明见源码仓库的 LICENSE 与 licenses/ 目录。</p>'}),
  editPlaybook:el=>{const p=el&&el.dataset.id?S.playbooks.find(x=>x.id===el.dataset.id):null;
    openSheet({title:p?'编辑交代':'新增交代',desc:'修改从下一通电话生效',body:`${p?'':`<div class="label">从常见场景开始</div><div class="chips" style="margin-bottom:14px"><button class="pill-btn" data-act="pbPreset" data-i="0">快递外卖</button><button class="pill-btn" data-act="pbPreset" data-i="1">业务询问</button></div>`}
    <div class="field"><label for="pbT">标题</label><input id="pbT" class="input" value="${esc(p?p.title:'')}" placeholder="例如：开会期间"></div>
    <div class="field"><label for="pbW">适用场景</label><input id="pbW" class="input" value="${esc(p?p.when:'')}" placeholder="什么样的来电"></div>
    <div class="field"><label for="pbR">处理方式</label><textarea id="pbR" class="textarea" placeholder="AI 可以怎么回、还要问清什么">${esc(p?p.reply:'')}</textarea></div>
    <div class="label">有效期</div><div class="seg" style="margin-bottom:16px" id="pbE">${['长期有效','今天有效','两小时'].map(x=>`<button class="${(p?p.expiry:'长期有效')===x?'on':''}" data-act="pbExp" data-v="${x}">${x}</button>`).join('')}</div>
    <div class="btn-row">${p?`<button class="btn ghost" style="color:var(--danger)" data-act="pbDel" data-id="${p.id}">删除</button>`:''}<button class="btn primary" data-act="pbSave" data-id="${p?p.id:''}">保存交代</button></div>`})},
  pbPreset:el=>{const pr=[['快递外卖','快递员或外卖骑手来电','问清物品放在哪里、是否需要回电；不透露门禁密码。'],['业务询问','客户询问商品、服务或预约','问清需求、数量、期望时间和联系方式，记下留言；不报价、不承诺。']][+el.dataset.i];$('#pbT').value=pr[0];$('#pbW').value=pr[1];$('#pbR').value=pr[2]},
  pbExp:el=>{$$('#pbE button').forEach(b=>b.classList.toggle('on',b===el))},
  pbSave:el=>{const t=$('#pbT').value.trim(),w=$('#pbW').value.trim(),r=$('#pbR').value.trim();const ex=($('#pbE .on')||{}).dataset.v||'长期有效';
    if(!t||!r){toast('请填写标题和处理方式','alert');return}
    const id=el.dataset.id;if(id){Object.assign(S.playbooks.find(p=>p.id===id),{title:t,when:w,reply:r,expiry:ex})}else S.playbooks.push({id:'p'+Date.now(),title:t,when:w||'所有来电',reply:r,expiry:ex,on:true});
    closeOverlay();rerender();toast('已保存到本机')},
  pbDel:el=>{const id=el.dataset.id;confirmDialog({title:'删除这条交代？',body:'后续电话将不再使用。',ok:'删除',danger:true}).then(ok=>{if(ok){S.playbooks=S.playbooks.filter(p=>p.id!==id);rerender();toast('已删除')}})},
  togglePlaybook:el=>{const p=S.playbooks.find(x=>x.id===el.dataset.id);p.on=!p.on;rerender()},
  codex:()=>toast('演示原型：Codex 商量页需接入后端','info'),
};
function openWizard({step=0}={}){
  S.wz={step,perm:S.ready.perm,notif:S.ready.notif,paired:step>1||S.ready.helper&&step>0,pairing:false,pairErr:'',code:[],key:step>2?'sk-demo-3f9a':'',cred:step>2?'ok':'',test:[false,false,false],tested:false,enable:true};
  if(step===0){S.wz.perm=false;S.wz.notif=false;S.wz.paired=false;S.wz.cred=''}
  push('wizard');
}

/* ========== events ========== */
document.addEventListener('click',ev=>{
  const el=ev.target.closest('[data-act]');if(!el||el.disabled)return;
  const f=A[el.dataset.act];if(f){ev.preventDefault();f(el,ev)}
});
document.addEventListener('input',ev=>{
  const t=ev.target;
  if(t.dataset.bind&&S.draft){
    if(t.dataset.bind==='q')S.draft.questions[+t.dataset.i]=t.value;else S.draft[t.dataset.bind]=t.value;
    const c=$(`[data-cnt="${t.dataset.bind}"]`);if(c)c.textContent=t.value.length+' / '+t.maxLength;
    if(S.draft.err[t.dataset.bind]){S.draft.err[t.dataset.bind]=null;t.classList.remove('err')}
  }
  if(t.dataset.bindWz&&S.wz){S.wz[t.dataset.bindWz]=t.value;if(S.wz.cred==='err'){S.wz.cred=''}}
  if(t.dataset.bindP){const c=$(`[data-cnt-p="${t.dataset.bindP}"]`);if(c)c.textContent=t.value.length+' / '+t.maxLength}
  if(t.dataset.code!=null){t.value=t.value.replace(/\D/g,'').slice(0,1);if(t.value&&t.nextElementSibling)t.nextElementSibling.focus()}
  const a=t.dataset.actInput;
  if(a==='search'){S.q=t.value;const l=$('#callsList');if(l)l.innerHTML=callsListHTML()}
  if(a==='delay'){S.delay=+t.value;const v=$('#delayV');if(v)v.textContent=S.delay+' 秒'}
  if(a==='maxSec'){S.maxSec=+t.value;const v=$('#msV');if(v)v.textContent=S.maxSec+' 秒'}
});
document.addEventListener('keydown',ev=>{if(ev.key==='Escape'){if(overlayClose)closeOverlay();else if(stack.length>1)pop()}});
$$('#tabbar [data-tab]').forEach(b=>b.addEventListener('click',()=>switchTab(b.dataset.tab)));
$('#fab').addEventListener('click',()=>A.newTask());

/* clock */
function clock(){const n=$('#sbTime');if(n)n.textContent=hhmm(new Date())}
setInterval(clock,10000);clock();

let playerHTML;
function updatePlayerDOM(){}
