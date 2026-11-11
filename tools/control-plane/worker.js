import { DurableObject } from 'cloudflare:workers';
const DASHBOARD = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Flash phone · 个人控制台</title><style>\n:root{color-scheme:light;--ink:#17202e;--muted:#657080;--line:#e1e5ea;--bg:#f5f7fa;--blue:#245ac7;--green:#167550;--red:#bd3343;--amber:#9b6512}*{box-sizing:border-box}body{margin:0;font:14px/1.55 system-ui,-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;color:var(--ink);background:var(--bg)}button,input,select,textarea{font:inherit}button,a,input,textarea,select{outline-offset:3px}button{cursor:pointer;border:1px solid var(--line);border-radius:7px;background:white;padding:8px 13px;color:var(--ink);min-height:38px}button:hover{border-color:#90a5c8;background:#f6f9ff}button:disabled{opacity:.45;cursor:not-allowed}.primary{background:var(--blue);border-color:var(--blue);color:white}.primary:hover{background:#174eaf;color:white}.danger{color:var(--red)}.quiet{background:transparent;border-color:transparent}.badge{display:inline-flex;align-items:center;gap:5px;padding:3px 8px;border-radius:5px;background:#eef1f5;font-size:12px;white-space:nowrap}.badge.good{color:var(--green);background:#e6f4ed}.badge.warn{color:var(--amber);background:#fff2d9}.badge.bad{color:var(--red);background:#ffe9ed}.dot{width:7px;height:7px;border-radius:50%;background:currentColor;display:inline-block}.muted{color:var(--muted)}.small{font-size:12px}.mono{font-family:ui-monospace,SFMono-Regular,monospace}.shell{min-height:100vh;display:grid;grid-template-columns:210px minmax(0,1fr)}aside{position:sticky;top:0;height:100vh;border-right:1px solid var(--line);background:#fff;padding:25px 16px;display:flex;flex-direction:column;gap:24px}.brand{display:flex;gap:10px;align-items:center;font-size:18px;font-weight:700}.mark{background:#1c2a43;color:#fff;border-radius:9px;width:34px;height:34px;display:grid;place-items:center}.eyebrow{font-size:11px;letter-spacing:1.5px;color:var(--muted)}nav{display:grid;gap:5px}nav button{text-align:left;border:0;background:none;padding:10px 12px}nav button[aria-current=page]{background:#eaf0fc;color:var(--blue);font-weight:650}nav b{display:inline-flex;width:25px;font-weight:500}.sidefoot{margin-top:auto;border-top:1px solid var(--line);padding-top:15px;display:grid;gap:8px}.workspace{min-width:0}header{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:22px 30px;background:#fff;border-bottom:1px solid var(--line);position:sticky;top:0;z-index:2}h1{font-size:21px;line-height:1.25;margin:3px 0}h2{font-size:16px;margin:0}h3{font-size:14px;margin:0}p{margin:8px 0}.toolbar{display:flex;gap:8px;align-items:center;flex-wrap:wrap}main{padding:25px 30px;max-width:1560px;margin:auto}.page{display:none}.page.visible{display:block}.grid{display:grid;gap:18px}.metrics{grid-template-columns:repeat(4,1fr);margin-bottom:20px}.metric{padding:17px 20px;border:1px solid var(--line);background:#fff;border-radius:8px}.metric strong{font-size:27px;display:block;margin-top:3px;font-weight:650}.panel{border:1px solid var(--line);border-radius:9px;background:white;min-width:0;overflow:hidden}.panelhead{padding:16px 19px;border-bottom:1px solid var(--line);display:flex;align-items:center;justify-content:space-between;gap:12px}.body{padding:19px}.two{grid-template-columns:minmax(0,1.5fr) minmax(280px,1fr)}.formcols{grid-template-columns:1fr 1fr}.field{display:flex;flex-direction:column;gap:6px;margin-bottom:14px}.field label{font-weight:600;font-size:13px}.field input,.field select,.field textarea,.filters input,.filters select{width:100%;padding:9px 11px;border:1px solid #cdd5df;border-radius:6px;background:white;min-height:40px;color:var(--ink)}textarea{resize:vertical;min-height:85px}.field .help{font-size:12px;color:var(--muted)}.hint{padding:12px 14px;background:#f2f5fa;border-radius:6px;color:#4f607a;font-size:13px}.notice{border-left:3px solid var(--blue);padding:10px 14px;background:#eef4ff;font-size:13px;margin-bottom:18px}.filters{display:flex;gap:10px;padding:14px 18px;border-bottom:1px solid var(--line)}.filters input{max-width:320px}.filters select{max-width:180px}table{border-collapse:collapse;width:100%}th,td{text-align:left;padding:12px 18px;border-bottom:1px solid #edf0f3;vertical-align:middle}th{font-size:11px;color:var(--muted);font-weight:600;letter-spacing:.5px;background:#fafbfd}.taskrow{cursor:pointer}.taskrow:hover{background:#f4f7fd}.taskrow.selected{background:#eef3ff}.truncate{max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.empty{padding:34px 20px;text-align:center;color:var(--muted)}.device{padding:16px 19px;border-bottom:1px solid var(--line);display:flex;gap:12px;align-items:flex-start}.device:last-child{border:0}.deviceicon{width:35px;height:38px;border-radius:7px;background:#eef2f8;display:grid;place-items:center;font-size:18px}.grow{flex:1;min-width:0}.device p{font-size:12px;color:var(--muted);margin:4px 0}.device .toolbar{margin-top:6px}.rowtitle{display:flex;justify-content:space-between;gap:10px}.sectiongap{margin-top:20px}#message{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);z-index:20;padding:12px 18px;background:#213047;color:white;border-radius:7px;max-width:90vw;display:none;box-shadow:0 3px 14px #12213c22}dialog{border:1px solid var(--line);border-radius:10px;padding:0;max-width:min(560px,95vw);width:100%;box-shadow:0 12px 40px #0f1d3933}dialog::backdrop{background:#14213866}.dialoghead{padding:18px 22px;display:flex;justify-content:space-between;align-items:center;border-bottom:1px solid var(--line)}.dialogbody{padding:20px 22px}.code{font-size:32px;font-weight:650;letter-spacing:5px;padding:20px 10px;text-align:center;background:#edf3ff;border-radius:7px;margin:15px 0}.drawer{position:fixed;inset:0 0 0 auto;width:min(600px,100vw);background:white;z-index:8;border-left:1px solid var(--line);box-shadow:-7px 0 25px #11203712;display:none;flex-direction:column}.drawer.open{display:flex}.drawerhead{padding:20px 22px;border-bottom:1px solid var(--line);display:flex;justify-content:space-between;align-items:center}.drawercontent{padding:20px 22px;overflow-y:auto;flex:1}.draweractions{padding:14px 22px;border-top:1px solid var(--line);display:flex;gap:8px;flex-wrap:wrap;background:#fff}.kv{display:grid;grid-template-columns:85px 1fr;gap:8px;margin:17px 0}.kv dt{color:var(--muted)}.kv dd{margin:0;overflow-wrap:anywhere}.timeline{border-left:2px solid var(--line);margin:14px 5px;padding-left:17px}.event{position:relative;padding:0 0 16px}.event:before{content:'';position:absolute;width:8px;height:8px;border-radius:50%;background:#9bacc6;left:-22px;top:6px}.transcript{display:grid;gap:10px}.line{padding:10px 12px;border:1px solid var(--line);border-radius:6px}.line b{font-size:11px;display:block;color:var(--muted);margin-bottom:4px}audio{width:100%;margin:8px 0}details{margin:14px 0}summary{cursor:pointer;font-weight:600}.loginpage{min-height:100vh;display:grid;place-items:center;padding:20px;background:#f0f3f8}.logincard{max-width:460px;width:100%;background:#fff;border:1px solid var(--line);border-radius:12px;padding:32px}.loginpage .brand{margin-bottom:27px}.loginpage h1{font-size:24px;margin-bottom:8px}.tabs{display:flex;gap:5px;background:#f1f4f8;border-radius:7px;padding:4px;margin:23px 0}.tabs button{flex:1;border:0;background:none}.tabs button.active{background:white;box-shadow:0 1px 3px #14253f12}.loginerror{color:var(--red);font-size:13px;min-height:24px}.sectiontitle{font-size:12px;text-transform:uppercase;letter-spacing:1px;color:var(--muted);margin:20px 0 10px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.7 ui-monospace,monospace;background:#f3f5f8;padding:12px;border-radius:7px}.hidden{display:none!important}@media(max-width:1000px){.shell{grid-template-columns:170px 1fr}main{padding:20px}.two{grid-template-columns:1fr}.metrics{gap:10px}.metric{padding:12px}.metric strong{font-size:23px}header{padding:18px 20px}aside{padding:22px 10px}.wideonly{display:none}}@media(max-width:640px){.shell{display:block}aside{height:auto;position:sticky;top:0;z-index:4;padding:10px 14px;border:0;border-bottom:1px solid var(--line);gap:9px}.brand{font-size:16px}.mark{width:27px;height:27px}.eyebrow,.sidefoot{display:none}nav{display:flex;overflow:auto;gap:2px}nav button{font-size:12px;white-space:nowrap;padding:8px 9px;min-height:34px}nav b{display:none}header{position:static;padding:16px}h1{font-size:19px}main{padding:15px}.metrics{grid-template-columns:1fr 1fr;gap:8px}.metric strong{font-size:23px}.formcols{grid-template-columns:1fr;gap:0}.body{padding:16px}.panelhead{padding:14px 16px}th,td{padding:11px 12px}.truncate{max-width:165px}.mobilehide{display:none}.filters{padding:12px;gap:7px}.drawerhead,.drawercontent{padding:16px}.kv{grid-template-columns:70px 1fr}.logincard{padding:25px}}\n</style></head><body>\n<section id=\"login\" class=\"loginpage\"><form id=\"loginform\" class=\"logincard\"><div class=\"brand\"><span class=\"mark\">↗</span> Flash phone <span class=\"badge\">个人控制台</span></div><h1>电话任务，随处接续。</h1><p class=\"muted\">连接自己的设备，交代本次目标，核对真实进度与通话记录。</p><div class=\"tabs\"><button type=\"button\" class=\"active\" data-login=\"code\">跨设备短码</button><button type=\"button\" data-login=\"key\">主密钥登录</button></div><div class=\"field\"><label id=\"loginlabel\" for=\"credential\">一次性登录短码</label><input id=\"credential\" autocomplete=\"off\" placeholder=\"输入 8 位授权码\" maxlength=\"512\" required><span class=\"help\" id=\"loginhelp\">在已登录的控制台「设备与授权」生成，5 分钟有效。</span></div><div class=\"field\" id=\"labelField\"><label for=\"controllerLabel\">这台控制设备的名称</label><input id=\"controllerLabel\" placeholder=\"例如：随身手机\" maxlength=\"80\" value=\"网页控制台\"></div><p id=\"loginerror\" class=\"loginerror\" role=\"alert\"></p><button class=\"primary\" style=\"width:100%\" id=\"loginbutton\">安全登录</button><p class=\"small muted\" style=\"margin-top:17px\">仅限机主授权 · 无公开注册 · 录音与通话数据不公开</p></form></section>\n<div id=\"app\" class=\"shell hidden\"><aside><div class=\"brand\"><span class=\"mark\">↗</span> Flash phone</div><div class=\"eyebrow\">PERSONAL WORKSPACE</div><nav aria-label=\"主导航\"><button data-page=\"overview\" aria-current=\"page\"><b>◫</b> 工作总览</button><button data-page=\"compose\"><b>↗</b> 发起通话</button><button data-page=\"history\"><b>≡</b> 通话记录</button><button data-page=\"devices\"><b>⌘</b> 设备与授权</button></nav><div class=\"sidefoot\"><span class=\"badge good\"><span class=\"dot\"></span> 私人工作区</span><span class=\"small muted\" id=\"controllerName\"></span><button class=\"quiet\" id=\"logout\">退出本机登录</button></div></aside><div class=\"workspace\"><header><div><div class=\"eyebrow\" id=\"headerEyebrow\">PERSONAL WORKSPACE</div><h1 id=\"pageTitle\">工作总览</h1></div><div class=\"toolbar\"><span class=\"muted small\" id=\"freshness\">正在读取…</span><button id=\"refresh\" aria-label=\"刷新真实设备状态\">↻ 刷新</button><button class=\"primary\" id=\"quickCall\">＋ 新通话</button></div></header><main>\n<section id=\"overview\" class=\"page visible\"><div class=\"grid metrics\"><div class=\"metric\"><span class=\"muted small\">在线设备</span><strong id=\"onlineCount\">—</strong></div><div class=\"metric\"><span class=\"muted small\">未结束任务</span><strong id=\"activeCount\">—</strong></div><div class=\"metric\"><span class=\"muted small\">真实通话结束</span><strong id=\"endedCount\">—</strong></div><div class=\"metric\"><span class=\"muted small\">可核听录音</span><strong id=\"recordingCount\">—</strong></div></div><div class=\"grid two\"><div class=\"panel\"><div class=\"panelhead\"><h2>最近电话任务</h2><button class=\"quiet\" data-go=\"history\">查看全部 →</button></div><div id=\"recentTasks\"></div></div><div class=\"panel\"><div class=\"panelhead\"><h2>执行设备</h2><button class=\"quiet\" data-go=\"devices\">管理 →</button></div><div id=\"overviewDevices\"></div></div></div><div class=\"notice sectiongap\">指令受理、正在拨号、AI 接入和系统电话结束分别显示。AI 退出后，系统电话可能仍在继续；控制台不会自动重拨。</div></section>\n<section id=\"compose\" class=\"page\"><div class=\"grid two\"><form class=\"panel\" id=\"callform\"><div class=\"panelhead\"><div><h2>交代这一次通话</h2><span class=\"muted small\">草稿留在当前页面，不会自动发起</span></div><span class=\"badge\">单次任务</span></div><div class=\"body\"><div class=\"grid formcols\"><div class=\"field\"><label for=\"deviceSelect\">执行设备</label><select id=\"deviceSelect\" required><option value=\"\">请选择已就绪设备</option></select></div><div class=\"field\"><label for=\"simSelect\">拨号线路</label><select id=\"simSelect\"><option value=\"\">设备默认线路</option></select></div></div><div id=\"deviceHelp\" class=\"hint\" style=\"margin-bottom:16px\">仅显示设备真实报告的能力和就绪状态。</div><div class=\"field\"><label for=\"phoneNumber\">拨打号码</label><input type=\"tel\" id=\"phoneNumber\" placeholder=\"完整普通电话号码\" required maxlength=\"32\" autocomplete=\"off\"></div><div class=\"field\"><label for=\"callGoal\">本次目标 <span class=\"muted\">必填</span></label><textarea id=\"callGoal\" placeholder=\"希望这通电话了解什么、完成什么？\" required maxlength=\"2000\"></textarea></div><div class=\"field\"><label for=\"callQuestions\">必须问到的问题</label><textarea id=\"callQuestions\" placeholder=\"每行一个问题\" maxlength=\"9000\"></textarea></div><details><summary>背景与不能承诺的事项</summary><div class=\"field\" style=\"margin-top:12px\"><label for=\"callContext\">必要背景</label><textarea id=\"callContext\" maxlength=\"4000\" placeholder=\"只提供这通电话需要知道的信息\"></textarea></div><div class=\"field\"><label for=\"callConstraints\">边界和限制</label><textarea id=\"callConstraints\" maxlength=\"2000\" placeholder=\"例如：不能付款、不能确认订单，只了解信息\"></textarea></div></details><div class=\"toolbar\"><button type=\"submit\" class=\"primary\" id=\"reviewCall\">核对并发起 →</button><button type=\"button\" class=\"quiet\" id=\"clearDraft\">清空草稿</button></div></div></form><div class=\"grid\" style=\"align-content:start\"><div class=\"panel\"><div class=\"panelhead\"><h2>本次执行边界</h2></div><div class=\"body\"><p>① 机主确认这次号码、设备和交代。</p><p>② 在线设备在本机核验授权与电话状态。</p><p>③ 控制台跟进真实回调与文字结果。</p><p>④ 系统电话真实结束后，查看可用录音。</p><div class=\"hint sectiongap\">设备离线或未就绪时直接拒绝，指令最多 120 秒有效，不保存成稍后自动拨出的队列。</div></div></div><div class=\"panel\"><div class=\"panelhead\"><h2>正在进行</h2></div><div id=\"composeActive\"></div></div></div></div></section>\n<section id=\"history\" class=\"page\"><div class=\"panel\"><div class=\"panelhead\"><div><h2>通话任务与核听</h2><span class=\"small muted\">号码、交代、事件与结果保持在同一任务中</span></div><span class=\"badge\" id=\"historyTotal\">0 条</span></div><div class=\"filters\"><input id=\"search\" aria-label=\"搜索号码或目标\" placeholder=\"搜索号码、目标、结果\"><select id=\"phaseFilter\" aria-label=\"筛选阶段\"><option value=\"all\">全部阶段</option><option value=\"ongoing\">尚未结束</option><option value=\"ended\">系统已结束</option><option value=\"review\">需要核查</option><option value=\"recording\">有录音</option></select><select id=\"historyDevice\" aria-label=\"筛选执行设备\"><option value=\"\">全部设备</option></select></div><div id=\"historyTasks\"></div></div></section>\n<section id=\"devices\" class=\"page\"><div class=\"grid two\"><div class=\"panel\"><div class=\"panelhead\"><div><h2>自己的执行设备</h2><span class=\"muted small\">授权隔离；每台设备只能更新自己的任务</span></div><button class=\"primary\" id=\"newDevice\">＋ 注册设备</button></div><div id=\"deviceList\"></div></div><div class=\"grid\" style=\"align-content:start\"><div class=\"panel\"><div class=\"panelhead\"><h2>跨设备控制</h2><button id=\"newSession\">生成登录短码</button></div><div class=\"body\"><p class=\"small muted\">手机、电脑浏览器可共享这一个私人工作区。短码仅能使用一次，5 分钟后失效。</p><div id=\"sessions\"></div></div></div><div class=\"panel\"><div class=\"panelhead\"><h2>MCP 接入</h2></div><div class=\"body\"><p class=\"small muted\">同一任务可从控制台或支持 Streamable HTTP 的智能体查看与操作。身份令牌由机主私下配置。</p><pre id=\"mcpUrl\"></pre><span class=\"badge\">等待最多 25 秒 · 受理不是完成</span></div></div></div></div></section>\n</main></div></div>\n<div id=\"drawer\" class=\"drawer\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"detailTitle\"><div class=\"drawerhead\"><div><span class=\"eyebrow\">TASK DETAIL</span><h2 id=\"detailTitle\">本次通话</h2></div><div class=\"toolbar\"><button id=\"prevTask\" aria-label=\"上一条任务\">←</button><button id=\"nextTask\" aria-label=\"下一条任务\">→</button><button id=\"closeDrawer\" aria-label=\"关闭任务详情\">✕</button></div></div><div class=\"drawercontent\" id=\"taskDetail\"></div><div class=\"draweractions\" id=\"taskActions\"></div></div>\n<dialog id=\"confirmDialog\"><div class=\"dialoghead\"><h2>确认这一次拨出</h2><button data-close=\"confirmDialog\" aria-label=\"关闭\">✕</button></div><div class=\"dialogbody\"><div id=\"confirmation\"></div><div class=\"notice\">将立即交给所选在线设备。相同提交不会重复拨号；设备仍会核验本机通话权限与状态。</div><div class=\"toolbar\"><button class=\"primary\" id=\"sendCall\">确认并发起通话</button><button data-close=\"confirmDialog\">返回修改</button></div></div></dialog>\n<dialog id=\"codeDialog\"><div class=\"dialoghead\"><h2 id=\"codeTitle\">授权短码</h2><button data-close=\"codeDialog\" aria-label=\"关闭\">✕</button></div><div class=\"dialogbody\"><p id=\"codeHelp\" class=\"muted\"></p><div id=\"codeValue\" class=\"code mono\"></div><p id=\"codeExpiry\" class=\"small muted\"></p><div class=\"toolbar\"><button id=\"copyCode\">复制短码</button><button data-close=\"codeDialog\">完成</button></div></div></dialog>\n<div id=\"message\" role=\"status\"></div>\n<script>\n'use strict';\n// Consume the local-only fragment before any API request; never retain it in history or storage.\nfunction takeFragmentLoginCode(){\n  const fragment=new URLSearchParams(location.hash.slice(1));\n  if(!fragment.has('login_code'))return {present:false,code:null};\n  history.replaceState(history.state,'',location.pathname+location.search);\n  const codes=fragment.getAll('login_code'),code=(codes[0]||'').toUpperCase();\n  return {present:true,code:codes.length===1&&/^[2-9A-HJ-NP-Z]{8}$/.test(code)?code:null};\n}\nconst fragmentLogin=takeFragmentLoginCode();\nconst $=id=>document.getElementById(id), state={devices:[],tasks:[],sessions:[],page:'overview',selected:null,loginMode:'code',pending:null,busy:false,refreshing:false}, phases={accepted:'已受理 · 等待设备',preparing:'设备准备中',dialing:'正在拨号',ringing:'对方响铃中',active:'系统电话已接通',ai_active:'AI 正在通话',handed_off:'AI 已交还系统',ended:'系统电话已结束',failed:'执行失败',interrupted:'执行状态待确认',canceled:'拨号前已取消'};\nlet refreshTimer, toastTimer;\nfunction el(tag,attrs={},children=[]){const n=document.createElement(tag);for(const[k,v]of Object.entries(attrs)){if(k==='text')n.textContent=v;else if(k==='class')n.className=v;else if(k==='click')n.addEventListener('click',v);else n.setAttribute(k,v);}for(const c of children)n.append(c);return n;}\nfunction empty(text){return el('div',{class:'empty',text});}\nfunction toast(text){$('message').textContent=text;$('message').style.display='block';clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('message').style.display='none',4500);}\nasync function api(path,options={}){const response=await fetch(path,{credentials:'same-origin',...options,headers:{'Content-Type':'application/json',...options.headers}});let body;try{body=await response.json();}catch{body={};}if(!response.ok){if(response.status===401&&!path.includes('claim')&&path!='/api/login')signedOut();throw Error(body.error?.message||'请求未完成，请稍后重试');}return body;}\nfunction signedOut(){clearTimeout(refreshTimer);$('app').classList.add('hidden');$('login').classList.remove('hidden');closeDrawer();}\nasync function signedIn(){const me=await api('/api/me');$('controllerName').textContent=me.controller.label;$('login').classList.add('hidden');$('app').classList.remove('hidden');$('credential').value='';$('mcpUrl').textContent=location.origin+'/mcp';await refresh();}\nfunction phaseBadge(t){return el('span',{class:'badge '+(t.phase==='ended'?'good':t.phase==='ai_active'?'good':['failed','interrupted'].includes(t.phase)?'warn':''),text:phases[t.phase]||t.phase});}\nfunction time(ms){return ms?new Date(ms).toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}):'—';}\nfunction deviceName(id){return state.devices.find(d=>d.id===id)?.name||'已撤销设备';}\nfunction go(page){state.page=page;document.querySelectorAll('.page').forEach(n=>n.classList.toggle('visible',n.id===page));document.querySelectorAll('[data-page]').forEach(n=>n.setAttribute('aria-current',n.dataset.page===page?'page':'false'));$('pageTitle').textContent={overview:'工作总览',compose:'发起通话',history:'通话记录',devices:'设备与授权'}[page];}\nfunction taskTable(tasks){if(!tasks.length)return empty('这里还没有电话任务。准备好设备后，可发起第一通。');const table=el('table'),head=el('tr');['任务 / 目标','进度','时间'].forEach((h,i)=>head.append(el('th',{text:h,class:i===2?'mobilehide':''})));table.append(el('thead',{},[head]));const body=el('tbody');for(const t of tasks){const row=el('tr',{class:'taskrow'+(state.selected===t.id?' selected':''),tabindex:'0',click:()=>openTask(t.id)});row.addEventListener('keydown',e=>{if(e.key==='Enter')openTask(t.id)});row.append(el('td',{},[el('div',{class:'truncate',text:t.task.goal}),el('div',{class:'small muted',text:t.number+' · '+deviceName(t.device_id)})]),el('td',{},[phaseBadge(t),...(t.recording?[el('div',{class:'small muted',text:'● 可核听录音'})]:[])]),el('td',{class:'mobilehide small muted',text:time(t.created_at)}));body.append(row);}table.append(body);return table;}\nfunction deviceRows(devices,manage=false){if(!devices.length)return empty('尚未注册执行设备。注册后将显示真实连接状态。');const box=el('div');for(const d of devices){const desc=d.status?.message||d.status?.reason||(d.status?.ready?'电话功能已就绪':'等待本机准备'),right=el('div',{class:'grow'},[el('div',{class:'rowtitle'},[el('h3',{text:d.name}),el('span',{class:'badge '+(d.online?'good':''),text:d.online?'在线':'离线'})]),el('p',{text:(d.platform==='android'?'Android 电话设备':'Mac / iPhone 桥接')+' · '+desc}),el('div',{class:'toolbar'},d.capabilities.map(c=>el('span',{class:'badge',text:{'calls.dial':'拨号','calls.cancel':'接管','calls.hangup':'挂断',recording:'录音'}[c]||c})))]);if(manage)right.append(el('p',{class:'mono small muted',text:d.id}),el('button',{class:'quiet danger',text:'撤销这台设备',click:()=>revokeDevice(d)}));box.append(el('div',{class:'device'},[el('span',{class:'deviceicon',text:d.platform==='android'?'▯':'⌘'}),right]));}return box;}\nfunction render(){const ongoing=state.tasks.filter(t=>!t.terminal);$('onlineCount').textContent=state.devices.filter(d=>d.online).length+' / '+state.devices.length;$('activeCount').textContent=ongoing.length;$('endedCount').textContent=state.tasks.filter(t=>t.carrier_ended).length;$('recordingCount').textContent=state.tasks.filter(t=>t.recording).length;$('recentTasks').replaceChildren(taskTable(state.tasks.slice(0,8)));$('overviewDevices').replaceChildren(deviceRows(state.devices));$('deviceList').replaceChildren(deviceRows(state.devices,true));$('composeActive').replaceChildren(taskTable(ongoing.slice(0,5)));const previous=$('deviceSelect').value;$('deviceSelect').replaceChildren(el('option',{value:'',text:'请选择执行设备'}),...state.devices.map(d=>el('option',{value:d.id,text:d.name+(d.online?(d.status?.ready?' · 就绪':' · 未就绪'):' · 离线')})));$('deviceSelect').value=previous;updateDeviceHelp();const chosen=$('historyDevice').value;$('historyDevice').replaceChildren(el('option',{value:'',text:'全部设备'}),...state.devices.map(d=>el('option',{value:d.id,text:d.name})));$('historyDevice').value=chosen;renderHistory();renderSessions();}\nfunction renderHistory(){const search=$('search').value.toLowerCase(),filter=$('phaseFilter').value,device=$('historyDevice').value;const matches=state.tasks.filter(t=>(!device||t.device_id===device)&&(!search||[t.number,t.task.goal,JSON.stringify(t.result)].join(' ').toLowerCase().includes(search))&&(filter==='all'||filter==='ongoing'&&!t.terminal||filter==='ended'&&t.carrier_ended||filter==='recording'&&t.recording||filter==='review'&&['interrupted','failed','handed_off'].includes(t.phase)));$('historyTotal').textContent=matches.length+' / '+state.tasks.length+' 条';$('historyTasks').replaceChildren(taskTable(matches));}\nfunction updateDeviceHelp(){const d=state.devices.find(d=>d.id===$('deviceSelect').value),previous=$('simSelect').value;$('simSelect').replaceChildren(el('option',{value:'',text:'设备默认线路'}),...(d?.status?.sims||[]).map(s=>el('option',{value:s.id,text:s.label+(s.default?' · 默认':'')})));$('simSelect').value=previous;if($('simSelect').selectedIndex<0)$('simSelect').value='';$('deviceHelp').textContent=d?(d.online?'在线':'离线')+' · '+(d.status?.message||(d.status?.ready?'本机报告已就绪':d.status?.reason||'本机未报告已就绪')):'仅显示设备真实报告的能力和就绪状态。';}\nfunction renderSessions(){$('sessions').replaceChildren(...state.sessions.map(s=>el('div',{class:'device',style:'padding:12px 0'},[el('div',{class:'grow'},[el('h3',{text:s.label}),el('span',{class:'small muted',text:'到期 '+time(s.expires)})]),el('button',{class:'quiet danger',text:'撤销',click:async()=>{try{await api('/api/sessions/'+s.id,{method:'DELETE'});await refresh();toast('该控制会话已撤销');}catch(e){toast(e.message)}}})])));}\nasync function refresh(){if(state.refreshing)return;state.refreshing=true;try{const[d,t,s]=await Promise.all([api('/api/devices'),api('/api/tasks?limit=200'),api('/api/sessions')]);state.devices=d.devices;state.tasks=t.tasks;state.sessions=s.sessions;render();$('freshness').textContent='更新于 '+new Date().toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',second:'2-digit'});if(state.selected)await loadTask(state.selected);}catch(e){$('freshness').textContent='刷新未完成';toast(e.message);}finally{state.refreshing=false;clearTimeout(refreshTimer);if(!document.hidden&&!$('app').classList.contains('hidden'))refreshTimer=setTimeout(refresh,state.tasks.some(t=>!t.terminal)?5000:20000);}}\nasync function openTask(id){state.selected=id;$('drawer').classList.add('open');await loadTask(id);}\nasync function loadTask(id){try{const data=await api('/api/tasks/'+id);if(state.selected!==id)return;renderDetail(data.task);}catch(e){toast(e.message)}}\nfunction closeDrawer(){const audio=$('taskDetail').querySelector('audio');if(audio){audio.pause();audio.removeAttribute('src');audio.load();}$('drawer').classList.remove('open');state.selected=null;}\nfunction detailBlock(title,value){return el('div',{},[el('h3',{class:'sectiontitle',text:title}),el('p',{text:value||'未提供'})]);}\nfunction renderDetail(t){const content=$('taskDetail'),signature=JSON.stringify(t);if(content.dataset.signature===signature)return;const oldAudio=content.querySelector('audio'),same=content.dataset.id===t.id,scroll=content.scrollTop,expanded=new Set([...content.querySelectorAll('details[open]')].map(n=>n.querySelector('summary')?.textContent));if(oldAudio&&!same)oldAudio.pause();content.dataset.signature=signature;content.dataset.id=t.id;$('detailTitle').textContent=t.number;const kv=el('dl',{class:'kv'});for(const[k,v]of [['执行设备',deviceName(t.device_id)],['发起时间',time(t.created_at)],['系统电话',t.carrier_ended?'已确认结束':t.call_id?'尚未确认结束':'尚未绑定'],['AI 状态',t.ai_active?'正在接入':'未报告正在接入'],['任务标识',t.id]])kv.append(el('dt',{text:k}),el('dd',{text:v,class:k==='任务标识'?'mono small':''}));const nodes=[phaseBadge(t),kv,detailBlock('本次目标',t.task.goal)];if(t.task.questions?.length)nodes.push(detailBlock('必须问到',t.task.questions.join('\\n')));if(t.task.context||t.task.constraints){const details=el('details',{},[el('summary',{text:'背景与边界'}),detailBlock('背景',t.task.context),detailBlock('不能承诺',t.task.constraints)]);nodes.push(details);}if(t.result)nodes.push(detailBlock('设备返回结果',t.result.summary||t.result.reason||t.result.outcome||'等待进一步核查'));if(t.phase==='handed_off')nodes.push(el('div',{class:'notice',text:'AI 已停止处理，系统电话可能仍在继续。需要挂断时请使用本任务的明确挂断操作。'}));if(t.recording){const audio=same&&oldAudio?.getAttribute('src')===t.recording.url?oldAudio:el('audio',{controls:'',preload:'none',src:t.recording.url});if(audio!==oldAudio)audio.addEventListener('error',()=>toast('录音暂时无法播放，请刷新状态后重试'));nodes.push(detailBlock('录音核听','本机采集的对方声音与设备接受的AI音频；不能单独证明远端实际听到。'),audio);}else nodes.push(el('p',{class:'small muted',text:'设备尚未上传可核听录音；不能据此认定对方听到AI回答。'}));if(t.result?.transcript?.length)nodes.push(el('details',{},[el('summary',{text:'对话原文 · '+t.result.transcript.length+' 条'}),el('div',{class:'transcript sectiongap'},t.result.transcript.map(l=>el('div',{class:'line'},[el('b',{text:l.role}),el('span',{text:l.text})])))]));nodes.push(el('h3',{class:'sectiontitle',text:'真实执行事件'}),el('div',{class:'timeline'},(t.events||[]).map(e=>el('div',{class:'event'},[el('h3',{text:phases[e.phase]||e.phase}),el('span',{class:'small muted',text:'#'+e.sequence+(e.carrier_ended?' · 系统确认挂断':'')})]))));content.replaceChildren(...nodes);if(same){content.scrollTop=scroll;content.querySelectorAll('details').forEach(n=>{n.open=expanded.has(n.querySelector('summary')?.textContent)})}const actions=[el('button',{text:'复用为新草稿',click:()=>reuse(t)})];if(!t.carrier_ended&&!['ended','failed','canceled'].includes(t.phase)){actions.push(el('button',{text:'停止 AI / 交还系统',click:()=>control(t,'cancel')}));if(t.call_id)actions.push(el('button',{class:'danger',text:'明确挂断此电话',click:()=>control(t,'hangup')}));}$('taskActions').replaceChildren(...actions);}\nasync function control(t,action){if(action==='hangup'&&!confirm('明确挂断本任务对应的 '+t.number+'？这会结束系统电话。'))return;try{await api('/api/tasks/'+t.id+'/'+action,{method:'POST',body:'{}'});toast(action==='hangup'?'已发送本任务挂断指令，等待设备确认':'已发送停止AI指令，不会隐式挂断系统电话');await refresh();}catch(e){toast(e.message)}}\nfunction reuse(t){$('deviceSelect').value=t.device_id;updateDeviceHelp();$('simSelect').value=t.sim_id||'';$('phoneNumber').value=t.number;$('callGoal').value=t.task.goal;$('callQuestions').value=(t.task.questions||[]).join('\\n');$('callContext').value=t.task.context||'';$('callConstraints').value=t.task.constraints||'';state.pending=null;closeDrawer();go('compose');toast('已创建草稿，尚未拨号');}\nasync function revokeDevice(d){if(!confirm('撤销「'+d.name+'」的设备授权？不会自动挂断已在进行的系统电话。'))return;try{await api('/api/devices/'+d.id,{method:'DELETE'});await refresh();toast('设备授权已撤销，未自动挂断系统电话');}catch(e){toast(e.message)}}\nasync function newCode(kind){try{const data=await api(kind==='device'?'/api/device-links':'/api/session-links',{method:'POST',body:JSON.stringify(kind==='device'?{}:{label:'跨设备登录'})});$('codeTitle').textContent=kind==='device'?'注册执行设备':'跨设备控制登录';$('codeHelp').textContent=kind==='device'?'在 Flash phone 手机或 Mac 桥接端输入此授权码。它只授予该设备执行自己的任务。':'在另一台手机或电脑打开本控制台，输入此码完成登录。';$('codeValue').textContent=data.code;$('codeExpiry').textContent='有效至 '+time(data.expires_at)+' · 使用一次后立即失效';$('codeDialog').showModal();}catch(e){toast(e.message)}}\n$('loginform').addEventListener('submit',async e=>{e.preventDefault();$('loginerror').textContent='';$('loginbutton').disabled=true;try{await api(state.loginMode==='key'?'/api/login':'/api/session-links/claim',{method:'POST',body:JSON.stringify(state.loginMode==='key'?{key:$('credential').value}:{code:$('credential').value,label:$('controllerLabel').value})});await signedIn();}catch(e){$('loginerror').textContent=e.message;}finally{$('loginbutton').disabled=false;}});\ndocument.querySelectorAll('[data-login]').forEach(b=>b.addEventListener('click',()=>{state.loginMode=b.dataset.login;document.querySelectorAll('[data-login]').forEach(n=>n.classList.toggle('active',n===b));$('loginlabel').textContent=state.loginMode==='key'?'机主主密钥':'一次性登录短码';$('credential').type=state.loginMode==='key'?'password':'text';$('credential').value='';$('credential').placeholder=state.loginMode==='key'?'输入机主私下保存的主密钥':'输入 8 位授权码';$('loginhelp').textContent=state.loginMode==='key'?'主密钥仅用于建立安全会话，不存入浏览器本地存储。':'在已登录的控制台生成，5分钟有效。';$('labelField').classList.toggle('hidden',state.loginMode==='key');}));\ndocument.querySelectorAll('[data-page]').forEach(b=>b.addEventListener('click',()=>go(b.dataset.page)));document.querySelectorAll('[data-go]').forEach(b=>b.addEventListener('click',()=>go(b.dataset.go)));document.querySelectorAll('[data-close]').forEach(b=>b.addEventListener('click',()=>$(b.dataset.close).close()));$('quickCall').onclick=()=>go('compose');$('refresh').onclick=refresh;$('deviceSelect').onchange=updateDeviceHelp;for(const id of ['search','phaseFilter','historyDevice'])$(id).addEventListener(id==='search'?'input':'change',renderHistory);$('logout').onclick=async()=>{try{await api('/api/logout',{method:'POST',body:'{}'});signedOut();}catch(e){toast(e.message)}};$('newDevice').onclick=()=>newCode('device');$('newSession').onclick=()=>newCode('controller');$('copyCode').onclick=()=>navigator.clipboard.writeText($('codeValue').textContent).then(()=>toast('短码已复制')).catch(()=>toast('请直接选中短码复制'));$('closeDrawer').onclick=closeDrawer;for(const[id,offset]of [['prevTask',-1],['nextTask',1]])$(id).onclick=()=>{const index=state.tasks.findIndex(t=>t.id===state.selected)+offset;if(state.tasks[index])openTask(state.tasks[index].id)};\n$('clearDraft').onclick=()=>{if(confirm('清空当前草稿？')){$('callform').reset();state.pending=null;updateDeviceHelp();}};\n$('callform').addEventListener('submit',e=>{e.preventDefault();const d=state.devices.find(d=>d.id===$('deviceSelect').value);if(!d?.online||!d.status?.ready||!d.capabilities.includes('calls.dial'))return toast('所选设备未在线就绪，未创建拨号任务');const payload={device_id:d.id,action:'dial',number:$('phoneNumber').value,sim_id:$('simSelect').value||null,task:{goal:$('callGoal').value,questions:$('callQuestions').value.split('\\n').map(v=>v.trim()).filter(Boolean),context:$('callContext').value,constraints:$('callConstraints').value},expires_in_seconds:120};if(!state.pending||JSON.stringify(state.pending.payload)!==JSON.stringify(payload))state.pending={payload,key:crypto.randomUUID()};$('confirmation').replaceChildren(detailBlock('执行设备',d.name),detailBlock('拨打号码',payload.number),detailBlock('本次目标',payload.task.goal),...(payload.task.questions.length?[detailBlock('必须问到',payload.task.questions.join('\\n'))]:[]),...(payload.task.constraints?[detailBlock('不能承诺',payload.task.constraints)]:[]));$('confirmDialog').showModal();});\n$('sendCall').onclick=async()=>{if(state.busy||!state.pending)return;state.busy=true;$('sendCall').disabled=true;try{const result=await api('/api/tasks',{method:'POST',body:JSON.stringify({...state.pending.payload,idempotency_key:state.pending.key})});$('confirmDialog').close();state.pending=null;await refresh();await openTask(result.task.id);toast('指令已受理，正在等待设备真实进度');}catch(e){toast(e.message);}finally{state.busy=false;$('sendCall').disabled=false;}};\ndocument.addEventListener('visibilitychange',()=>{clearTimeout(refreshTimer);if(document.hidden){const audio=$('taskDetail').querySelector('audio');if(audio)audio.pause();}else if(!$('app').classList.contains('hidden'))refresh();});document.addEventListener('keydown',e=>{if(e.key==='Escape')closeDrawer()});\nasync function startDashboard(){\n  if(!fragmentLogin.present){api('/api/me').then(signedIn).catch(()=>{});return;}\n  if(!fragmentLogin.code){$('loginerror').textContent='登录短码格式无效，请从 Mac 控制台重新打开';return;}\n  $('loginbutton').disabled=true;\n  try{\n    await api('/api/session-links/claim',{method:'POST',body:JSON.stringify({code:fragmentLogin.code,label:'Mac控制台'})});\n    fragmentLogin.code=null;\n    await signedIn();\n  }catch(error){$('loginerror').textContent=error.message;}\n  finally{fragmentLogin.code=null;$('loginbutton').disabled=false;}\n}\nstartDashboard();\n</script></body></html>\n";
const MAX_JSON = 16 * 1024, MAX_RESULT = 64 * 1024, MAX_AUDIO = 40 * 1024 * 1024;
const UPLOAD_TIMEOUT_MS = 120000;
const TERMINAL = new Set(['ended', 'failed', 'interrupted', 'canceled']);
const PHASES = new Set(['accepted', 'preparing', 'dialing', 'ringing', 'active', 'ai_active', 'handed_off', ...TERMINAL, 'recording_ready']);
const CAPS = new Set(['calls.dial', 'calls.cancel', 'calls.hangup', 'recording']);
const encoder = new TextEncoder();
const ACCOUNT = Object.freeze({ id: 'personal', label: '我的 Flash phone' });
class Problem extends Error { constructor(status, code, message) { super(message); this.status = status; this.code = code; } }
const fail = (status, code, message) => { throw new Problem(status, code, message); };
const json = (value, status = 200, headers = {}) => Response.json(value, { status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...headers } });
const now = () => Date.now();
const id = () => crypto.randomUUID();
const token = () => Array.from(crypto.getRandomValues(new Uint8Array(32)), n => n.toString(16).padStart(2, '0')).join('');
const sha = async value => new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value)));
const hex = bytes => Array.from(bytes, n => n.toString(16).padStart(2, '0')).join('');
const hash = async value => hex(await sha(value));
const decode = value => value == null ? null : JSON.parse(value);
const text = (value, max, required = false) => {
  if (typeof value !== 'string' || value.length > max || (required && !value.trim())) fail(400, 'invalid_input', '输入格式或长度不符合要求');
  return value.trim();
};
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const bounded = (value, min, max, fallback) => value == null ? fallback : Number.isInteger(value) && value >= min && value <= max ? value : fail(400, 'invalid_input', '数值超出范围');
const caps = value => Array.isArray(value) && value.length <= 8 && value.every(x => CAPS.has(x)) ? [...new Set(value)] : fail(400, 'invalid_capabilities', '设备能力格式无效');
const cookie = (value, age = 2592000) => `__Host-fp_session=${value}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${age}`;
function canonical(value) {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(k => JSON.stringify(k) + ':' + canonical(value[k])).join(',') + '}';
  return JSON.stringify(value);
}
async function readJson(request, limit = MAX_JSON) {
  if (!request.headers.get('Content-Type')?.startsWith('application/json')) fail(415, 'json_required', '需要 JSON 请求');
  const bytes = await readBounded(request.body, limit);
  let value; try { value = JSON.parse(new TextDecoder().decode(bytes)); } catch { fail(400, 'invalid_json', 'JSON 格式无效'); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) fail(400, 'invalid_json', '需要 JSON 对象');
  return value;
}
async function readBounded(body, max) {
  if (!body) return new Uint8Array();
  const reader = body.getReader(), parts = []; let size = 0;
  try {
    while (true) { const { done, value } = await reader.read(); if (done) break; size += value.length;
      if (size > max) { await reader.cancel(); fail(413, 'body_too_large', '内容超出大小限制'); } parts.push(value); }
  } finally { reader.releaseLock(); }
  const result = new Uint8Array(size); let offset = 0; for (const part of parts) { result.set(part, offset); offset += part.length; } return result;
}
function errorResponse(error) {
  if (!(error instanceof Problem)) console.error(JSON.stringify({ event: 'request_failed', category: 'internal' }));
  return json({ error: { code: error instanceof Problem ? error.code : 'internal_error', message: error instanceof Problem ? error.message : '请求暂未完成，请稍后重试' } }, error instanceof Problem ? error.status : 500,
    error.status === 401 ? { 'WWW-Authenticate': 'Bearer realm="FlashPhone"' } : error.status === 429 ? { 'Retry-After': '60' } : {});
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/' && request.method === 'GET') return new Response(DASHBOARD, { headers: {
      'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
      'Content-Security-Policy': "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'", 'Referrer-Policy': 'no-referrer' } });
    if (!/^https:$/.test(url.protocol)) return errorResponse(new Problem(400, 'https_required', '需要 HTTPS 连接'));
    if (!env.OWNERS || !/^[a-f0-9]{64}$/i.test(env.ROOT_KEY_HASH ?? '')) return errorResponse(new Problem(503, 'not_configured', '控制台尚未配置'));
    const origin = request.headers.get('Origin');
    if (origin && origin !== url.origin) return errorResponse(new Problem(403, 'origin_rejected', '不接受跨站请求'));
    if (!url.pathname.startsWith('/api/') && url.pathname !== '/mcp') return errorResponse(new Problem(404, 'not_found', '入口不存在'));
    // One coordination atom per personal owner. No public account selection or registration.
    return env.OWNERS.getByName('personal-owner:v1').fetch(request);
  },
};

export class OwnerControl extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    ctx.blockConcurrencyWhile(async () => {
      this.sql.exec(`CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,hash TEXT UNIQUE NOT NULL,label TEXT NOT NULL,kind TEXT NOT NULL,device_id TEXT,expires INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS links(hash TEXT PRIMARY KEY,kind TEXT NOT NULL,label TEXT NOT NULL,expires INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,name TEXT NOT NULL,platform TEXT NOT NULL,capabilities TEXT NOT NULL,status TEXT NOT NULL,last_seen INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,device_id TEXT NOT NULL,phase TEXT NOT NULL,number TEXT NOT NULL,sim_id TEXT,brief TEXT NOT NULL,idem TEXT UNIQUE NOT NULL,payload_hash TEXT NOT NULL,created INTEGER NOT NULL,expires INTEGER NOT NULL,updated INTEGER NOT NULL,sequence INTEGER NOT NULL DEFAULT 0,call_id TEXT,work_id TEXT,carrier_ended INTEGER NOT NULL DEFAULT 0,ai_active INTEGER NOT NULL DEFAULT 0,result TEXT);
        CREATE TABLE IF NOT EXISTS dedupe_keys(idem TEXT PRIMARY KEY,payload_hash TEXT NOT NULL,task_id TEXT NOT NULL,created INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS task_device ON tasks(device_id,created);
        CREATE TABLE IF NOT EXISTS events(task_id TEXT NOT NULL,sequence INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(task_id,sequence));
        CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY,task_id TEXT NOT NULL,action TEXT NOT NULL,json TEXT NOT NULL,created INTEGER NOT NULL,expires INTEGER NOT NULL,sent INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS recordings(task_id TEXT PRIMARY KEY,key TEXT NOT NULL,bytes INTEGER NOT NULL,created INTEGER NOT NULL,uploading INTEGER NOT NULL DEFAULT 0,digest TEXT);
        CREATE TABLE IF NOT EXISTS rates(key TEXT PRIMARY KEY,count INTEGER NOT NULL,expires INTEGER NOT NULL);`);
    });
  }
  rows(sql, ...args) { return this.sql.exec(sql, ...args).toArray(); }
  row(sql, ...args) { return this.rows(sql, ...args)[0]; }
  rate(key, max, windowMs) {
    const t = now(), row = this.row('SELECT count,expires FROM rates WHERE key=?', key);
    if (row?.expires > t && row.count >= max) fail(429, 'rate_limited', '操作过于频繁，请稍后再试');
    this.sql.exec('INSERT INTO rates(key,count,expires) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET count=excluded.count,expires=excluded.expires', key, row?.expires > t ? row.count + 1 : 1, row?.expires > t ? row.expires : t + windowMs);
    this.sql.exec('DELETE FROM rates WHERE expires<?', t);
  }
  async publicRate(request) {
    // Never persist the client's raw IP. A bounded global gate also caps attacker-chosen IP rows.
    this.rate('public-global', 100, 300000);
    this.rate('public:' + await hash(request.headers.get('CF-Connecting-IP') || 'unknown'), 5, 300000);
  }
  async auth(request) {
    const bearer = request.headers.get('Authorization');
    const raw = bearer?.startsWith('Bearer ') ? bearer.slice(7) : request.headers.get('Cookie')?.match(/(?:^|;\s*)__Host-fp_session=([a-f0-9]{64})/)?.[1];
    if (!raw || raw.length > 512) fail(401, 'unauthorized', '身份验证失败');
    const digest = await sha(raw), root = Uint8Array.from(this.env.ROOT_KEY_HASH.match(/../g), n => parseInt(n, 16));
    if (crypto.subtle.timingSafeEqual(digest, root)) return { id: 'root', kind: 'controller' };
    const session = this.row('SELECT * FROM sessions WHERE hash=? AND revoked=0 AND expires>?', hex(digest), now());
    if (!session) fail(401, 'unauthorized', '身份验证失败');
    return session;
  }
  controller(actor) { if (actor.kind !== 'controller') fail(403, 'scope_denied', '当前身份无权访问此入口'); }
  device(actor, task) { if (actor.kind !== 'device' || actor.device_id !== task.device_id) fail(403, 'scope_denied', '当前设备无权访问此任务'); }
  async issueSession(label, kind = 'controller', deviceId = null) {
    const value = token(), digest = await hash(value), sid = id(), expires = now() + (kind === 'device' ? 365 : 30) * 86400000;
    const count = this.row('SELECT count(*) AS n FROM sessions WHERE kind=? AND revoked=0 AND expires>?', kind, now()).n;
    if (count >= (kind === 'device' ? 32 : 64)) fail(409, 'session_limit', '已达到授权数量上限，请先撤销旧授权');
    this.sql.exec('INSERT INTO sessions(id,hash,label,kind,device_id,expires) VALUES(?,?,?,?,?,?)', sid, digest, label, kind, deviceId, expires);
    await this.schedule();
    return { value, id: sid, expires };
  }
  async createLink(kind, label) {
    const alphabet = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ', bytes = crypto.getRandomValues(new Uint8Array(8));
    const code = Array.from(bytes, n => alphabet[n & 31]).join(''), digest = await hash(code), expires = now() + 300000;
    if (this.row('SELECT count(*) AS n FROM links WHERE expires>?', now()).n >= 16) fail(409, 'link_limit', '未使用的授权码过多，请等待到期');
    this.sql.exec('INSERT INTO links(hash,kind,label,expires) VALUES(?,?,?,?)', digest, kind, label, expires);
    await this.schedule();
    return { code, expires_at: expires };
  }
  async claimLink(code, kind) {
    const digest = await hash(text(code, 16, true).toUpperCase().replace(/[\s-]/g, ''));
    const link = this.row('SELECT * FROM links WHERE hash=? AND kind=? AND expires>?', digest, kind, now());
    if (!link) fail(401, 'invalid_code', '授权码无效或已到期');
    this.sql.exec('DELETE FROM links WHERE hash=?', digest);
    return link;
  }
  async fetch(request) { try { return await this.route(request); } catch (error) { return errorResponse(error); } }
  async route(request) {
    const url = new URL(request.url), path = url.pathname, method = request.method;
    if (!/^[a-f0-9]{64}$/i.test(this.env.ROOT_KEY_HASH ?? '')) fail(503, 'not_configured', '控制台尚未配置');
    if (method === 'POST' && ['/api/login', '/api/session-links/claim', '/api/device-enroll'].includes(path)) {
      await this.publicRate(request); const body = await readJson(request);
      if (path === '/api/login') {
        const key = text(body.key, 512, true);
        const digest = await sha(key), root = Uint8Array.from(this.env.ROOT_KEY_HASH.match(/../g), n => parseInt(n, 16));
        if (!crypto.subtle.timingSafeEqual(digest, root)) fail(401, 'unauthorized', '身份验证失败');
        const session = await this.issueSession('主控登录');
        return json({ account: ACCOUNT, controller: { id: session.id, label: '主控登录' } }, 200, { 'Set-Cookie': cookie(session.value) });
      }
      const kind = path === '/api/device-enroll' ? 'device' : 'controller';
      const label = text(body.label ?? body.name ?? '新控制设备', 80, true);
      let platform, capabilities;
      if (kind === 'device') { platform = body.platform; if (!['android', 'iphone-mac'].includes(platform)) fail(400, 'invalid_platform', '设备平台无效'); capabilities = caps(body.capabilities); }
      // No await between one-use code validation and deletion. Session generation follows consumption.
      await this.claimLink(body.code, kind);
      const deviceId = kind === 'device' ? id() : null;
      const session = await this.issueSession(label, kind, deviceId);
      if (kind === 'device') {
        this.sql.exec('INSERT INTO devices(id,name,platform,capabilities,status,last_seen) VALUES(?,?,?,?,?,?)', deviceId, label, platform, JSON.stringify(capabilities), JSON.stringify({ ready: false, reason: '设备尚未连接' }), now());
        return json({ device_id: deviceId, device_token: session.value, account: ACCOUNT, account_label: ACCOUNT.label }, 201);
      }
      return json({ controller_token: session.value, controller: { id: session.id, label }, account: ACCOUNT }, 201, { 'Set-Cookie': cookie(session.value) });
    }
    const actor = await this.auth(request);
    this.rate('auth:' + actor.id, actor.kind === 'device' ? 240 : 120, 60000);
    if (path === '/api/device/socket' && method === 'GET') return this.openSocket(request, actor);
    const recording = path.match(/^\/api\/tasks\/([\w-]+)\/recording$/);
    if (recording) {
      const task = this.getTask(recording[1]);
      if (method === 'POST') { this.device(actor, task); return this.upload(request, actor, task); }
      this.controller(actor);
      if (method === 'GET' || method === 'HEAD') return this.download(request, task);
    }
    this.controller(actor);
    if (path === '/mcp') return this.mcp(request, actor);
    if (path === '/api/me' && method === 'GET') return json({ account: ACCOUNT, controller: { id: actor.id, label: actor.label ?? '主密钥' }, limits: { devices: 32, tasks: 200, recording_bytes: MAX_AUDIO } });
    if (path === '/api/logout' && method === 'POST') { if (actor.id !== 'root') this.sql.exec('UPDATE sessions SET revoked=1 WHERE id=?', actor.id); return json({ ok: true }, 200, { 'Set-Cookie': cookie('', 0) }); }
    if (path === '/api/session-links' && method === 'POST') { const body = await readJson(request); return json(await this.createLink('controller', text(body.label ?? '跨设备登录', 80, true)), 201); }
    if (path === '/api/device-links' && method === 'POST') { await readJson(request); return json(await this.createLink('device', '设备注册'), 201); }
    if (path === '/api/sessions' && method === 'GET') return json({ sessions: this.rows("SELECT id,label,expires FROM sessions WHERE kind='controller' AND revoked=0 AND expires>? ORDER BY expires DESC", now()) });
    const sessionPath = path.match(/^\/api\/sessions\/([\w-]+)$/);
    if (sessionPath && method === 'DELETE') { this.sql.exec("UPDATE sessions SET revoked=1 WHERE id=? AND kind='controller'", sessionPath[1]); return json({ ok: true }); }
    if (path === '/api/devices' && method === 'GET') return json({ devices: this.devices() });
    const devicePath = path.match(/^\/api\/devices\/([\w-]+)$/);
    if (devicePath && method === 'DELETE') {
      this.sql.exec('UPDATE devices SET revoked=1 WHERE id=?', devicePath[1]);
      this.sql.exec('UPDATE sessions SET revoked=1 WHERE device_id=?', devicePath[1]);
      for (const socket of this.ctx.getWebSockets(devicePath[1])) socket.close(4003, 'device_revoked');
      this.interruptDevice(devicePath[1], '设备授权已撤销，未自动挂断系统电话');
      return json({ ok: true, carrier_hangup_requested: false });
    }
    if (path === '/api/tasks' && method === 'POST') return json({ task: await this.createTask(await readJson(request)) }, 201);
    if (path === '/api/tasks' && method === 'GET') return json({ tasks: this.listTasks(bounded(Number(url.searchParams.get('limit') ?? 50), 1, 200, 50)) });
    const taskPath = path.match(/^\/api\/tasks\/([\w-]+)(?:\/(cancel|hangup))?$/);
    if (taskPath) { const task = this.getTask(taskPath[1]);
      if (!taskPath[2] && method === 'GET') return json({ task: this.taskView(task, true) });
      if (taskPath[2] && method === 'POST') return json({ task: this.controlTask(task, taskPath[2]) });
    }
    fail(404, 'not_found', '入口不存在');
  }
  devices() { return this.rows('SELECT * FROM devices WHERE revoked=0 ORDER BY name,id').map(d => ({ id: d.id, name: d.name, platform: d.platform, capabilities: decode(d.capabilities), status: decode(d.status), last_seen: d.last_seen, account_id: ACCOUNT.id, online: this.deviceSockets(d.id).length > 0 })); }
  deviceSockets(deviceId) { return this.ctx.getWebSockets(deviceId).filter(socket => { const a = socket.deserializeAttachment(); return a?.hello && a.expires > now() && socket.readyState === 1; }); }
  getTask(taskId) { const task = this.row('SELECT * FROM tasks WHERE id=?', taskId); if (!task) fail(404, 'task_not_found', '任务不存在或已清理'); return task; }
  taskView(task, withEvents = false) {
    const recording = this.row('SELECT bytes,created,digest FROM recordings WHERE task_id=? AND uploading=0', task.id);
    const view = { account_id: ACCOUNT.id, id: task.id, task_id: task.id, device_id: task.device_id, action: 'dial', phase: task.phase, number: task.number, sim_id: task.sim_id, task: decode(task.brief), created_at: task.created, expires_at: task.expires, updated_at: task.updated, sequence: task.sequence, call_id: task.call_id, work_id: task.work_id, carrier_ended: !!task.carrier_ended, ai_active: !!task.ai_active, terminal: TERMINAL.has(task.phase), result: decode(task.result), recording: recording ? { bytes: recording.bytes, sha256: recording.digest, created_at: recording.created, url: `/api/tasks/${task.id}/recording` } : null };
    if (withEvents) view.events = this.rows('SELECT json FROM events WHERE task_id=? ORDER BY sequence', task.id).map(row => decode(row.json));
    return view;
  }
  listTasks(limit = 50) { return this.rows('SELECT * FROM tasks ORDER BY created DESC,id DESC LIMIT ?', limit).map(t => this.taskView(t)); }
  normalizeTask(body) {
    if (body.action !== 'dial' || !uuid(body.device_id)) fail(400, 'invalid_task', '需要有效设备和拨号操作');
    const number = text(body.number, 32, true).replace(/[\s()-]/g, '');
    if (!/^\+?[0-9]{7,15}$/.test(number) || /^(?:\+?86)?(?:110|112|119|120|122|911)$/.test(number)) fail(400, 'invalid_number', '仅支持普通完整电话号码');
    const brief = body.task;
    if (!brief || typeof brief !== 'object' || !Array.isArray(brief.questions ?? []) || (brief.questions?.length ?? 0) > 20) fail(400, 'invalid_task', '请填写本次通话目标和问题');
    const task = { goal: text(brief.goal, 2000, true), questions: (brief.questions ?? []).map(q => text(q, 500, true)), context: text(brief.context ?? '', 4000), constraints: text(brief.constraints ?? '', 2000) };
    return { device_id: body.device_id, action: 'dial', number, sim_id: body.sim_id == null ? null : text(body.sim_id, 256, true), task,
      idempotency_key: text(body.idempotency_key, 128, true), expires_in_seconds: bounded(body.expires_in_seconds, 5, 120, 120) };
  }
  async createTask(body) {
    const normalized = this.normalizeTask(body), payloadHash = await hash(canonical(normalized)), time = now();
    const consumed = this.row('SELECT * FROM dedupe_keys WHERE idem=?', normalized.idempotency_key);
    const repeated = this.row('SELECT * FROM tasks WHERE idem=?', normalized.idempotency_key);
    if (consumed && !repeated) { if (consumed.payload_hash !== payloadHash) fail(409, 'idempotency_conflict', '同一幂等标识对应了不同内容'); fail(410, 'task_retired', '该指令已消费且记录已清理，不会重复拨号'); }
    if (repeated) { if (repeated.payload_hash !== payloadHash) fail(409, 'idempotency_conflict', '同一幂等标识对应了不同内容'); return this.taskView(repeated, true); }
    const device = this.row('SELECT * FROM devices WHERE id=? AND revoked=0', normalized.device_id), sockets = this.deviceSockets(normalized.device_id);
    if (!device || !sockets.length) fail(409, 'device_offline', '设备未在线，未排队拨号');
    if (!decode(device.capabilities).includes('calls.dial')) fail(409, 'capability_missing', '设备不支持远程拨号');
    if (decode(device.status)?.ready !== true) fail(409, 'device_not_ready', '设备尚未就绪，未执行拨号');
    if (this.row("SELECT id FROM tasks WHERE device_id=? AND phase NOT IN ('ended','failed','interrupted','canceled')", device.id)) fail(409, 'device_busy', '设备已有未结束电话任务');
    if (this.row('SELECT count(*) AS n FROM dedupe_keys').n >= 10000) fail(409, 'dedupe_limit', '已达到安全幂等凭据保留上限，未创建新拨号');
    if (this.row('SELECT count(*) AS n FROM tasks').n >= 200) fail(409, 'task_limit', '任务保留数量已满，请等待清理或删除旧记录');
    const taskId = id(), command = { command_id: id(), task_id: taskId, device_id: device.id, action: 'dial', issued_at: time, expires_at: time + normalized.expires_in_seconds * 1000, expected_call_id: null, payload: { number: normalized.number, sim_id: normalized.sim_id, task: normalized.task } };
    this.ctx.storage.transactionSync(() => {
      this.sql.exec('INSERT INTO tasks(id,device_id,phase,number,sim_id,brief,idem,payload_hash,created,expires,updated) VALUES(?,?,?,?,?,?,?,?,?,?,?)', taskId, device.id, 'accepted', normalized.number, normalized.sim_id, JSON.stringify(normalized.task), normalized.idempotency_key, payloadHash, time, command.expires_at, time);
      this.sql.exec('INSERT INTO dedupe_keys(idem,payload_hash,task_id,created) VALUES(?,?,?,?)', normalized.idempotency_key, payloadHash, taskId, time);
      this.storeCommand(command);
    });
    this.sendCommand(command, sockets[0]);
    await this.schedule();
    return this.taskView(this.getTask(taskId), true);
  }
  storeCommand(command) { const payload = JSON.stringify(command); if (encoder.encode(payload).length > MAX_JSON - 128) fail(413, 'command_too_large', '本次通话交代过长'); this.sql.exec('INSERT INTO commands(id,task_id,action,json,created,expires) VALUES(?,?,?,?,?,?)', command.command_id, command.task_id, command.action, payload, command.issued_at, command.expires_at); }
  sendCommand(command, socket) {
    // Persist BEFORE send. A crash may lose an attempt, but must never cause an automatic redial.
    this.sql.exec('UPDATE commands SET sent=1 WHERE id=?', command.command_id);
    try { socket.send(JSON.stringify({ v: 1, type: 'command', command })); }
    catch { const task = this.getTask(command.task_id); if (command.action === 'dial') this.interrupt(task, '指令传输未确认，未自动重试'); }
  }
  controlTask(task, action) {
    if (task.carrier_ended || ['ended', 'failed', 'canceled'].includes(task.phase)) fail(409, 'task_finished', '该任务已结束，未操作其他电话');
    const socket = this.deviceSockets(task.device_id)[0]; if (!socket) fail(409, 'device_offline', '设备未在线，未排队操作');
    const device = this.row('SELECT capabilities FROM devices WHERE id=? AND revoked=0', task.device_id);
    if (!device || !decode(device.capabilities).includes(`calls.${action}`)) fail(409, 'capability_missing', '设备不支持此操作');
    if (action === 'hangup' && !task.call_id) fail(409, 'call_not_bound', '尚未确认该任务对应的系统电话，未挂断');
    const latest = this.row('SELECT created FROM commands WHERE task_id=? AND action=? ORDER BY created DESC LIMIT 1', task.id, action);
    if (latest && now() - latest.created < 5000) fail(429, 'command_pending', '操作已发送，正在等待设备确认');
    const command = { command_id: id(), task_id: task.id, device_id: task.device_id, action, issued_at: now(), expires_at: now() + 30000, expected_call_id: task.call_id, payload: {} };
    this.storeCommand(command); this.sendCommand(command, socket);
    return { ...this.taskView(this.getTask(task.id), true), command_accepted: true, command_id: command.command_id };
  }
  openSocket(request, actor) {
    if (actor.kind !== 'device') fail(403, 'scope_denied', '需要设备身份');
    if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') fail(426, 'websocket_required', '需要 WebSocket 连接');
    const device = this.row('SELECT * FROM devices WHERE id=? AND revoked=0', actor.device_id); if (!device) fail(401, 'unauthorized', '身份验证失败');
    for (const old of this.ctx.getWebSockets(device.id)) old.close(4001, 'connection_replaced');
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [device.id]);
    server.serializeAttachment({ device_id: device.id, session_id: actor.id, hello: false, opened_at: now(), expires: actor.expires });
    // Native protocol ping/pong is handled by the runtime without application heartbeat SQL.
    return new Response(null, { status: 101, webSocket: client });
  }
  async webSocketMessage(socket, message) {
    try {
      const attachment = socket.deserializeAttachment();
      const session = attachment && this.row('SELECT id FROM sessions WHERE id=? AND revoked=0 AND expires>?', attachment.session_id, now());
      if (!session) { socket.close(4003, 'device_revoked'); return; }
      if (typeof message !== 'string' || encoder.encode(message).length > MAX_RESULT + MAX_JSON) fail(413, 'event_too_large', '设备消息过长');
      this.rate('socket:' + attachment.device_id, 120, 60000);
      let event; try { event = JSON.parse(message); } catch { fail(400, 'invalid_event', '设备消息格式无效'); }
      if (event.v !== 1) fail(400, 'protocol_version', '设备协议版本不兼容');
      if (event.type === 'hello') {
        const capabilities = caps(event.capabilities);
        if (!event.status || typeof event.status.ready !== 'boolean' || encoder.encode(JSON.stringify(event.status)).length > 4096) fail(400, 'invalid_status', '设备状态无效');
        attachment.hello = true; socket.serializeAttachment(attachment);
        this.sql.exec('UPDATE devices SET capabilities=?,status=?,last_seen=? WHERE id=? AND revoked=0', JSON.stringify(capabilities), JSON.stringify(event.status), now(), attachment.device_id);
        socket.send(JSON.stringify({ v: 1, type: 'hello_ack', device_id: attachment.device_id, server_time: now(), protocol: 1 }));
        return;
      }
      if (!attachment.hello) fail(400, 'hello_required', '请先发送设备状态');
      if (event.type !== 'event') fail(400, 'invalid_event', '设备消息类型无效');
      this.acceptEvent(attachment.device_id, event);
      socket.send(JSON.stringify({ v: 1, type: 'ack', task_id: event.task_id, sequence: event.sequence }));
      await this.schedule();
    } catch (error) {
      socket.send(JSON.stringify({ v: 1, type: 'error', error: { code: error instanceof Problem ? error.code : 'internal_error', message: error instanceof Problem ? error.message : '设备消息暂未处理' } }));
      if (!(error instanceof Problem)) console.error(JSON.stringify({ event: 'device_event_failed', category: 'internal' }));
    }
  }
  acceptEvent(deviceId, event) {
    const task = this.getTask(event.task_id);
    if (task.device_id !== deviceId) fail(403, 'scope_denied', '当前设备无权更新此任务');
    if (!Number.isSafeInteger(event.sequence) || event.sequence < 1 || !PHASES.has(event.phase)) fail(400, 'invalid_event', '事件序号或阶段无效');
    if (event.sequence <= task.sequence) return;
    if (typeof event.carrier_ended !== 'boolean' || typeof event.ai_active !== 'boolean') fail(400, 'invalid_event', '需要真实通话状态');
    const callId = event.call_id == null ? null : text(event.call_id, 256, true), workId = event.work_id == null ? null : text(event.work_id, 256, true);
    if (task.call_id && callId !== task.call_id) fail(409, 'call_binding_changed', '事件未绑定该任务的原系统电话');
    if (task.work_id && workId !== task.work_id) fail(409, 'work_binding_changed', '事件未绑定该任务的原执行上下文');
    if (event.carrier_ended && (event.phase !== 'ended' || !callId)) fail(400, 'invalid_end', '真实挂断必须提供绑定的系统电话');
    if (event.phase === 'ended' && (!event.carrier_ended || !callId)) fail(400, 'invalid_end', 'AI 退出不能标记为系统电话结束');
    if (['dialing', 'ringing', 'active', 'ai_active', 'handed_off'].includes(event.phase) && !workId) fail(400, 'missing_work_binding', '需要执行上下文标识');
    if (['active', 'ai_active', 'handed_off'].includes(event.phase) && !callId) fail(400, 'missing_call_binding', '需要绑定系统电话');
    if (event.phase === 'canceled' && task.call_id) fail(400, 'invalid_cancel', '已经绑定电话时请报告人工接管或真实结束');
    if (event.result != null && (typeof event.result !== 'object' || Array.isArray(event.result))) fail(400, 'invalid_result', '结果必须是对象');
    const result = event.result == null ? null : JSON.stringify(event.result);
    if (result && encoder.encode(result).length > MAX_RESULT) fail(413, 'result_too_large', '结果或原文超过限制');
    // Recording upload is the source of truth for playable metadata; device-supplied URLs are never used.
    if (event.result?.recording) delete event.result.recording;
    const sameTerminal = TERMINAL.has(task.phase) && event.phase !== 'ended';
    const phase = event.phase === 'recording_ready' || sameTerminal || task.phase === 'ended' ? task.phase : event.phase;
    const mergedResult = event.result == null ? task.result : JSON.stringify({ ...(decode(task.result) ?? {}), ...event.result });
    if (mergedResult && encoder.encode(mergedResult).length > MAX_RESULT) fail(413, 'result_too_large', '累计结果超过限制');
    this.ctx.storage.transactionSync(() => {
      this.sql.exec('UPDATE tasks SET phase=?,updated=?,sequence=?,call_id=?,work_id=?,carrier_ended=?,ai_active=?,result=? WHERE id=?', phase, now(), event.sequence, task.call_id || callId, task.work_id || workId, task.carrier_ended || Number(event.carrier_ended), TERMINAL.has(phase) || phase === 'handed_off' ? 0 : Number(event.ai_active), mergedResult, task.id);
      this.sql.exec('INSERT INTO events(task_id,sequence,json) VALUES(?,?,?)', task.id, event.sequence, JSON.stringify(event));
      this.sql.exec('DELETE FROM events WHERE task_id=? AND sequence NOT IN (SELECT sequence FROM events WHERE task_id=? ORDER BY sequence DESC LIMIT 128)', task.id, task.id);
      this.sql.exec('UPDATE devices SET last_seen=? WHERE id=?', now(), deviceId);
    });
  }
  async webSocketClose(socket) { this.socketLost(socket); }
  async webSocketError(socket) { this.socketLost(socket); }
  socketLost(socket) {
    const a = socket.deserializeAttachment();
    if (a && !this.deviceSockets(a.device_id).filter(ws => ws !== socket).length) {
      this.sql.exec('UPDATE devices SET last_seen=? WHERE id=?', now(), a.device_id);
      // Preserve actual call state through a transient disconnect. No automatic command replay.
      this.ctx.waitUntil(this.schedule());
    }
  }
  interrupt(task, reason) { this.sql.exec("UPDATE tasks SET phase='interrupted',ai_active=0,updated=?,result=? WHERE id=? AND phase NOT IN ('ended','failed','interrupted','canceled')", now(), JSON.stringify({ reason, outcome: 'unknown' }), task.id); }
  interruptDevice(deviceId, reason) { for (const task of this.rows("SELECT * FROM tasks WHERE device_id=? AND phase NOT IN ('ended','failed','interrupted','canceled')", deviceId)) this.interrupt(task, reason); }
  async upload(request, actor, task) {
    if (!this.env.RECORDINGS) fail(503, 'recordings_unavailable', '录音存储尚未配置');
    if (request.headers.get('Content-Type')?.split(';')[0] !== 'audio/wav') fail(415, 'wav_required', '需要 WAV 录音');
    const size = Number(request.headers.get('Content-Length'));
    if (!Number.isSafeInteger(size) || size < 44 || size > MAX_AUDIO) fail(413, 'recording_size', '录音必须在 44 字节至 40 MiB 之间');
    const requestedDigest = request.headers.get('X-Recording-SHA256')?.toLowerCase();
    if (requestedDigest && !/^[a-f0-9]{64}$/.test(requestedDigest)) fail(400, 'invalid_digest', '录音摘要格式无效');
    const prior = this.row('SELECT * FROM recordings WHERE task_id=?', task.id);
    if (prior?.uploading) fail(409, 'upload_in_progress', '该录音正在上传');
    if (prior) {
      if (requestedDigest && prior.digest === requestedDigest && prior.bytes === size) return json({ recording: { task_id: task.id, bytes: size, sha256: prior.digest, url: `/api/tasks/${task.id}/recording` } });
      fail(409, 'recording_exists', '该任务已有录音，未覆盖原始证据；重试请带相同 SHA256');
    }
    const total = this.row('SELECT count(*) AS n,COALESCE(sum(bytes),0) AS bytes FROM recordings');
    if (this.row('SELECT count(*) AS n FROM recordings WHERE uploading=1').n >= 2) fail(429, 'upload_limit', '同时上传的录音过多，请稍后重试');
    if (total.n >= 50 || total.bytes + size > 1024 * 1024 * 1024) fail(409, 'recording_quota', '已达到私人录音保留上限');
    const key = `personal/${task.id}/${id()}.wav`;
    this.sql.exec('INSERT INTO recordings(task_id,key,bytes,created,uploading) VALUES(?,?,?,?,1)', task.id, key, size, now());
    await this.schedule();
    try {
      // Twelve signature bytes only. FixedLengthStream preserves R2's native known-length contract.
      const fixed = new FixedLengthStream(size), digester = new crypto.DigestStream('SHA-256');
      const reader = request.body.getReader(), writer = fixed.writable.getWriter(), digestWriter = digester.getWriter();
      let stopped = false, rejectStopped;
      const canceled = new Promise((_, reject) => { rejectStopped = reject; });
      const stop = error => {
        if (stopped) return;
        stopped = true;
        rejectStopped(error);
        // Never wait for backpressure before canceling the source and both streaming consumers.
        reader.cancel(error).catch(() => {});
        writer.abort(error).catch(() => {});
        digestWriter.abort(error).catch(() => {});
      };
      const timer = setTimeout(() => stop(new Problem(504, 'upload_timeout', '录音上传超过两分钟，已取消并清理')), UPLOAD_TIMEOUT_MS);
      let digest;
      try {
        const pump = (async () => {
          let received = 0, prefix = new Uint8Array();
          try {
            while (true) {
              const { done, value } = await Promise.race([reader.read(), canceled]); if (done) break;
              received += value.length; if (received > size) fail(413, 'recording_size', '录音长度超过声明');
              if (prefix.length < 12) {
                const needed = Math.min(12 - prefix.length, value.length), next = new Uint8Array(prefix.length + needed);
                next.set(prefix); next.set(value.subarray(0, needed), prefix.length); prefix = next;
                if (prefix.length === 12 && (new TextDecoder().decode(prefix.subarray(0, 4)) !== 'RIFF' || new TextDecoder().decode(prefix.subarray(8, 12)) !== 'WAVE')) fail(400, 'invalid_wav', 'WAV 录音格式无效');
              }
              await Promise.race([Promise.all([writer.write(value), digestWriter.write(value)]), canceled]);
            }
            if (received !== size || prefix.length < 12) fail(400, 'invalid_wav', 'WAV 录音不完整');
            await Promise.race([Promise.all([writer.close(), digestWriter.close()]), canceled]);
          } catch (error) { stop(error); throw error; }
          finally { reader.releaseLock(); }
        })();
        const put = this.env.RECORDINGS.put(key, fixed.readable, { httpMetadata: { contentType: 'audio/wav' } }).catch(error => { stop(error); throw error; });
        const results = await Promise.race([Promise.all([pump, put, digester.digest]), canceled]);
        digest = hex(new Uint8Array(results[2]));
      } finally { clearTimeout(timer); }
      if (requestedDigest && requestedDigest !== digest) fail(400, 'digest_mismatch', '录音摘要不匹配，未保存');
      const valid = this.row('SELECT id FROM sessions WHERE id=? AND revoked=0 AND expires>?', actor.id, now());
      if (!valid) fail(401, 'unauthorized', '身份验证失败');
      const reservation = this.row('SELECT key,uploading FROM recordings WHERE task_id=?', task.id);
      if (reservation?.key !== key || reservation.uploading !== 1) fail(409, 'upload_reservation_lost', '录音上传凭据已失效，未提交结果');
      this.sql.exec('UPDATE recordings SET uploading=0,digest=? WHERE task_id=? AND key=?', digest, task.id, key);
      await this.schedule(); return json({ recording: { task_id: task.id, bytes: size, sha256: digest, url: `/api/tasks/${task.id}/recording` } }, 201);
    } catch (error) {
      await this.env.RECORDINGS.delete(key);
      this.sql.exec('DELETE FROM recordings WHERE task_id=? AND key=?', task.id, key);
      throw error;
    }
  }

  async download(request, task) {
    const record = this.row('SELECT * FROM recordings WHERE task_id=? AND uploading=0', task.id);
    if (!record || !this.env.RECORDINGS) fail(404, 'recording_missing', '此任务还没有可播放录音');
    const ranged = request.headers.has('Range');
    const object = await this.env.RECORDINGS.get(record.key, ranged ? { range: request.headers } : undefined);
    if (!object) fail(404, 'recording_missing', '录音已清理');
    const headers = { 'Content-Type': 'audio/wav', 'Cache-Control': 'private, no-store', 'X-Content-Type-Options': 'nosniff', 'Accept-Ranges': 'bytes', ETag: object.httpEtag };
    if (ranged && object.range) { headers['Content-Range'] = `bytes ${object.range.offset}-${object.range.offset + object.range.length - 1}/${object.size}`; headers['Content-Length'] = String(object.range.length); }
    else headers['Content-Length'] = String(object.size);
    return new Response(request.method === 'HEAD' ? null : object.body, { status: ranged && object.range ? 206 : 200, headers });
  }
  async schedule() {
    const candidates = this.rows('SELECT min(expires) AS due FROM links UNION ALL SELECT min(expires) AS due FROM sessions WHERE revoked=0 UNION ALL SELECT min(expires) AS due FROM tasks WHERE phase IN (\'accepted\',\'preparing\',\'dialing\',\'ringing\') UNION ALL SELECT min(created)+2592000000 AS due FROM tasks UNION ALL SELECT min(created)+600000 AS due FROM recordings WHERE uploading=1');
    const times = candidates.map(r => r.due).filter(t => t != null && t > now());
    const pending = this.row("SELECT count(*) AS n FROM tasks WHERE phase NOT IN ('ended','failed','interrupted','canceled')").n;
    if (pending) times.push(now() + 120000);
    if (!times.length) return this.ctx.storage.deleteAlarm();
    const due = Math.max(now() + 1000, Math.min(...times)), previous = await this.ctx.storage.getAlarm();
    if (previous == null || due < previous || previous <= now()) await this.ctx.storage.setAlarm(due);
  }
  async alarm() {
    const time = now();
    this.sql.exec('DELETE FROM links WHERE expires<=?', time);
    this.sql.exec('DELETE FROM sessions WHERE expires<=? OR revoked=1', time);
    this.sql.exec('DELETE FROM rates WHERE expires<=?', time);
    this.sql.exec('DELETE FROM commands WHERE expires<?', time - 86400000);
    for (const task of this.rows("SELECT * FROM tasks WHERE phase NOT IN ('ended','failed','interrupted','canceled')")) {
      if (['accepted', 'preparing', 'dialing', 'ringing'].includes(task.phase) && task.expires <= time || (!this.deviceSockets(task.device_id).length && task.updated < time - 120000)) this.interrupt(task, '设备执行状态未确认，未自动重拨或挂断');
    }
    for (const upload of this.rows('SELECT task_id,key FROM recordings WHERE uploading=1 AND created<?', time - 600000)) {
      if (this.env.RECORDINGS) await this.env.RECORDINGS.delete(upload.key);
      this.sql.exec('DELETE FROM recordings WHERE task_id=? AND key=?', upload.task_id, upload.key);
    }
    const stale = this.rows("SELECT * FROM tasks WHERE phase IN ('ended','failed','interrupted','canceled') AND (created<? OR id IN(SELECT id FROM tasks ORDER BY created DESC LIMIT -1 OFFSET 180)) AND id NOT IN(SELECT task_id FROM recordings WHERE uploading=1)", time - 2592000000);
    for (const task of stale) {
      const record = this.row('SELECT key FROM recordings WHERE task_id=?', task.id);
      if (record && this.env.RECORDINGS) await this.env.RECORDINGS.delete(record.key);
      this.ctx.storage.transactionSync(() => { this.sql.exec('DELETE FROM recordings WHERE task_id=?', task.id); this.sql.exec('DELETE FROM events WHERE task_id=?', task.id); this.sql.exec('DELETE FROM commands WHERE task_id=?', task.id); this.sql.exec('DELETE FROM tasks WHERE id=?', task.id); });
    }
    await this.schedule();
  }
  async mcp(request, actor) {
    if (request.method === 'GET') return new Response(null, { status: 405, headers: { Allow: 'POST', 'Cache-Control': 'no-store' } });
    if (request.method !== 'POST') fail(405, 'method_not_allowed', '仅接受 JSON-RPC POST');
    const message = await readJson(request), rpcId = message.id ?? null;
    const rpc = (result, error) => json({ jsonrpc: '2.0', id: rpcId, ...(error ? { error } : { result }) });
    if (message.jsonrpc !== '2.0' || typeof message.method !== 'string') return rpc(null, { code: -32600, message: 'Invalid Request' });
    if (message.method === 'notifications/initialized') return new Response(null, { status: 202 });
    if (message.method === 'initialize') return rpc({ protocolVersion: ['2025-11-25', '2025-06-18', '2025-03-26'].includes(message.params?.protocolVersion) ? message.params.protocolVersion : '2025-11-25', capabilities: { tools: { listChanged: false } }, serverInfo: { name: 'flashphone-personal-control', version: '1.0.0' }, instructions: '拨号受理不是通话完成。handed_off表示系统电话可能仍在进行；只有carrier_ended=true才是真实挂断。' });
    if (message.method === 'ping') return rpc({});
    if (message.method === 'tools/list') return rpc({ tools: toolDefinitions() });
    if (message.method !== 'tools/call') return rpc(null, { code: -32601, message: 'Method not found' });
    const name = message.params?.name, args = message.params?.arguments ?? {};
    try {
      let result;
      if (name === 'flashphone_devices') result = { devices: this.devices() };
      else if (name === 'flashphone_call_task') result = { task: await this.createTask({ ...args, action: 'dial' }), notice: '已受理指令；以设备后续事件确认拨号、AI接入和真实结束' };
      else if (['flashphone_task_status', 'flashphone_wait_task'].includes(name)) {
        const deadline = now() + bounded(args.timeout_seconds, 0, 25, name === 'flashphone_wait_task' ? 20 : 0) * 1000;
        let task = this.getTask(args.task_id);
        while (name === 'flashphone_wait_task' && !TERMINAL.has(task.phase) && now() < deadline) {
          await new Promise(resolve => setTimeout(resolve, Math.min(1000, deadline - now())));
          if (actor.id !== 'root' && !this.row('SELECT id FROM sessions WHERE id=? AND revoked=0 AND expires>?', actor.id, now())) fail(401, 'unauthorized', '身份验证失败');
          task = this.getTask(args.task_id);
        }
        result = { task: this.taskView(task, true), terminal: TERMINAL.has(task.phase), timed_out: !TERMINAL.has(task.phase) && name === 'flashphone_wait_task' };
      } else if (name === 'flashphone_cancel_task' || name === 'flashphone_hangup_task') result = { task: this.controlTask(this.getTask(args.task_id), name === 'flashphone_cancel_task' ? 'cancel' : 'hangup'), notice: '仅发送本任务操作，等待设备确认' };
      else if (name === 'flashphone_recording') { const task = this.getTask(args.task_id); result = { task_id: task.id, recording: this.taskView(task).recording, authentication: 'controller Bearer or secure session cookie required; no public recording URL' }; }
      else return rpc(null, { code: -32602, message: 'Unknown tool' });
      return rpc({ content: [{ type: 'text', text: JSON.stringify(result) }], structuredContent: result, isError: false });
    } catch (error) { if (error instanceof Problem) return rpc({ content: [{ type: 'text', text: JSON.stringify({ error: { code: error.code, message: error.message } }) }], isError: true }); throw error; }
  }
}
function toolDefinitions() {
  const taskId = { type: 'object', properties: { task_id: { type: 'string', description: '任务ID，所有操作严格绑定此任务' } }, required: ['task_id'], additionalProperties: false };
  const descriptions = { flashphone_devices: '列出真实在线设备、能力与就绪状态', flashphone_call_task: '按明确号码和本次交代创建外呼。受理不代表拨号或对话完成，幂等标识必填', flashphone_task_status: '读取任务与真实设备事件', flashphone_wait_task: '最多等待25秒；handed_off不等于真实挂断，准确返回timeout与terminal', flashphone_cancel_task: '取消本任务或停止AI交还系统，不隐式挂断', flashphone_hangup_task: '明确挂断本任务绑定的当前系统电话', flashphone_recording: '读取本任务受保护录音元数据，需要controller认证下载' };
  return Object.entries(descriptions).map(([name, description]) => ({ name, description, inputSchema: name === 'flashphone_devices' ? { type: 'object', properties: {}, additionalProperties: false } : name === 'flashphone_call_task' ? { type: 'object', properties: { device_id: { type: 'string' }, number: { type: 'string' }, sim_id: { type: 'string' }, task: { type: 'object', properties: { goal: { type: 'string' }, questions: { type: 'array', items: { type: 'string' } }, context: { type: 'string' }, constraints: { type: 'string' } }, required: ['goal'] }, idempotency_key: { type: 'string' }, expires_in_seconds: { type: 'integer', minimum: 5, maximum: 120 } }, required: ['device_id', 'number', 'task', 'idempotency_key'], additionalProperties: false } : name === 'flashphone_wait_task' ? { ...taskId, properties: { ...taskId.properties, timeout_seconds: { type: 'integer', minimum: 0, maximum: 25 } } } : taskId, annotations: { readOnlyHint: !['flashphone_call_task', 'flashphone_cancel_task', 'flashphone_hangup_task'].includes(name), destructiveHint: name === 'flashphone_hangup_task' } }));
}
