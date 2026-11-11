import { DurableObject } from 'cloudflare:workers';
const DASHBOARD = '__DASHBOARD_HTML__';
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
