import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn, spawnSync } from 'node:child_process';
import { createServer } from 'node:http';
import { Readable } from 'node:stream';
import { Miniflare, Log, LogLevel } from 'miniflare';

// Import and execute the shipping Python serializer/runtime, rather than duplicating
// their event dictionaries in JS. Only the native Phone and voice ports are fake.
const pythonFixture = String.raw`
import asyncio, json, sys, tempfile, uuid
sys.path.insert(0, sys.argv[1])
from agent import Agent, Store
from runtime import MacExecutor

class SyntheticProcessExit(BaseException): pass

async def main(data):
    command, scenario = data['command'], data['scenario']
    call_id = str(uuid.uuid4())
    actions = []
    executor = MacExecutor.__new__(MacExecutor)
    executor.config = {'max_call_seconds': 1}
    executor.active = None
    executor.stop = asyncio.Event()
    async def ready(): return {'ready': True}
    executor.readiness = ready
    async def voice(payload, helper, stop, emit, **kwargs):
        actions.append('synthetic_voice')
        await emit('ai_active')
        return {'outcome': 'needs_review', 'summary': 'Synthetic cross-language fixture; no actual call or model.',
                'transcript': [{'role': 'assistant', 'text': 'Synthetic only.'}]}
    executor.voice_runner = voice
    with tempfile.TemporaryDirectory(prefix='flashphone-mac-wire-') as directory:
        store = Store(directory)
        agent = Agent({'server': 'https://synthetic.invalid', 'device_id': command['device_id']}, store, executor=executor)
        class Native:
            helper = '/synthetic/never-executed-native-helper'
            status_reads = 0
            async def request(self, payload):
                action = payload['action']
                actions.append(action)
                if action == 'prepare':
                    if scenario == 'cancel':
                        # A real cancel command arrives while native preparation is
                        # still pending; its public expected_call_id must be null.
                        await agent.command(data['cancel_command'])
                    return {'ok': True, 'call_id': call_id, 'call_issued': False}
                if action == 'dial': return {'ok': True, 'call_id': call_id, 'call_issued': True}
                if action == 'status':
                    self.status_reads += 1
                    if scenario == 'restart' and self.status_reads > 1:
                        # Simulate process loss without a graceful final event.
                        raise SyntheticProcessExit()
                    phase = 'active' if self.status_reads == 1 else 'ended'
                    return {'ok': True, 'call_id': call_id, 'phase': phase, 'carrier_ended': phase == 'ended'}
                return {'ok': True, 'call_id': call_id}
        executor.native = Native()
        await agent.command(command)
        try:
            await agent.running
        except SyntheticProcessExit:
            pass
        before_restart = None
        if scenario == 'restart':
            before_restart = store.pending()
            store.close()
            store = Store(directory)
            store.recover()
        if scenario == 'complete':
            with store.db:
                # An archive snapshot may carry the prior ended flag; the shipping
                # serializer must normalize it instead of issuing a second end.
                store.event(command['task_id'], 'recording_ready', carrier_ended=True,
                            result={'recording': {'status': 'synthetic_metadata_only'}})
        pending = store.pending()
        serialized = [json.dumps(event, separators=(',', ':')) for event in pending]
        # Exercise the actual exact-ACK spool deletion, not JS-owned counters.
        for event in pending: store.ack(event['task_id'], event['sequence'])
        pending_after_ack = store.pending()
        store.close()
        print(json.dumps({'wire': serialized, 'actions': actions, 'before_restart': before_restart,
                          'pending_after_ack': pending_after_ack}))

asyncio.run(main(json.load(sys.stdin)))
`;

const pythonUploadFixture = String.raw`
import asyncio, base64, hashlib, json, os, pathlib, struct, sys, tempfile, uuid, wave
sys.path.insert(0, sys.argv[1])
from agent import RecordingUploads, Store

async def main(data):
    with tempfile.TemporaryDirectory(prefix='flashphone-mac-upload-') as directory:
        store = Store(directory)
        # Restore the shipping runtime's already-ACKed task snapshot into a fresh
        # private local store; the controller has already persisted these events.
        with store.db:
            for event in data['events']:
                store.event(event['task_id'], event['phase'], call_id=event['call_id'],
                            work_id=event['work_id'], carrier_ended=event['carrier_ended'], result=event['result'])
        for event in store.pending(): store.ack(event['task_id'], event['sequence'])
        last = data['events'][-1]
        recordings = pathlib.Path(directory) / 'recordings'
        recordings.mkdir(mode=0o700)
        source = recordings / ('call-' + uuid.uuid4().hex + '.wav')
        fd = os.open(source, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as output:
            with wave.open(output, 'wb') as wav:
                wav.setparams((2, 2, 16000, 0, 'NONE', 'not compressed'))
                wav.writeframes(b''.join(struct.pack('<hh', sample * 7, -sample * 3) for sample in range(256)))
        original = source.read_bytes()
        uploads = RecordingUploads(store, data['origin'], data['device_token'], recordings, allow_loopback=True)
        uploads.RETRY_DELAYS = (0, 0)
        try:
            await uploads.register(source, last['task_id'], last['call_id'])
            await uploads.set_connected(True)
            await asyncio.wait_for(uploads.worker, timeout=12)
        finally:
            await uploads.set_connected(False)
        row = store.db.execute('SELECT status,attempts,digest,bytes FROM recording_uploads WHERE task=?', (last['task_id'],)).fetchone()
        wire = [json.dumps(event, separators=(',', ':')) for event in store.pending()]
        snapshot = store.db.execute('SELECT phase,call_id,work_id FROM tasks WHERE id=?', (last['task_id'],)).fetchone()
        store.close()
        print(json.dumps({'wire': wire, 'upload': {'status': row[0], 'attempts': row[1], 'sha256': row[2], 'bytes': row[3]},
                          'source_base64': base64.b64encode(original).decode(), 'source_sha256': hashlib.sha256(original).hexdigest(),
                          'snapshot': {'phase': snapshot[0], 'call_id': snapshot[1], 'work_id': snapshot[2]}}))

asyncio.run(main(json.load(sys.stdin)))
`;

const rootKey = 'synthetic-mac-wire-root-' + randomUUID();
const rootHash = createHash('sha256').update(rootKey).digest('hex');
const persist = mkdtempSync(join(tmpdir(), 'flashphone-mac-worker-'));
const macDirectory = fileURLToPath(new URL('../../mac-bridge/', import.meta.url));
let mf, ip = 0;
const sockets = [];
before(() => {
  mf = new Miniflare({
    script: readFileSync(new URL('../worker.js', import.meta.url), 'utf8'), modules: true,
    compatibilityDate: process.env.TEST_COMPATIBILITY_DATE || '2026-11-11', compatibilityFlags: ['nodejs_compat'],
    bindings: { ROOT_KEY_HASH: rootHash },
    durableObjects: { OWNERS: { className: 'OwnerControl', useSQLite: true } },
    durableObjectsPersist: join(persist, 'do'), r2Buckets: ['RECORDINGS'], r2Persist: join(persist, 'r2'), log: new Log(LogLevel.ERROR),
  });
});
after(async () => {
  for (const ws of sockets) ws.close();
  await mf.dispose();
  rmSync(persist, { recursive: true, force: true });
});

async function request(path, { body, auth = rootKey, headers = {} } = {}) {
  return mf.dispatchFetch('https://mac-wire.test' + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'CF-Connecting-IP': '192.0.2.' + ++ip, ...(auth ? { Authorization: 'Bearer ' + auth } : {}), 'Content-Type': 'application/json', ...headers },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}
async function value(path, options) {
  const response = await request(path, options), body = await response.json();
  assert.ok(response.status < 300, JSON.stringify(body));
  return body;
}
async function setup() {
  const link = await value('/api/device-links', { body: {} });
  const device = await value('/api/device-enroll', { auth: null, body: {
    code: link.code, name: 'Synthetic Mac wire integration', platform: 'iphone-mac',
    capabilities: ['calls.dial', 'calls.cancel', 'calls.hangup', 'recording'],
  } });
  const response = await request('/api/device/socket', { auth: device.device_token, headers: { Upgrade: 'websocket' } });
  assert.equal(response.status, 101);
  const ws = response.webSocket;
  ws.accept(); sockets.push(ws);
  const queue = [], waiters = [];
  ws.addEventListener('message', event => {
    const message = JSON.parse(event.data), waiting = waiters.shift();
    if (waiting) waiting(message); else queue.push(message);
  });
  const next = () => queue.length ? Promise.resolve(queue.shift()) : new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error('Missing Worker WebSocket response')), 3000);
    waiters.push(message => { clearTimeout(timer); resolve(message); });
  });
  ws.send(JSON.stringify({ v: 1, type: 'hello', capabilities: ['calls.dial', 'calls.cancel', 'calls.hangup', 'recording'], status: { ready: true, synthetic: true } }));
  assert.equal((await next()).device_id, device.device_id);
  const { task } = await value('/api/tasks', { body: {
    device_id: device.device_id, action: 'dial', number: '+12025550123', idempotency_key: randomUUID(),
    task: { goal: 'Synthetic integration only; never dial or use a model.', questions: [], context: '', constraints: 'No actual calls.' },
  } });
  const command = (await next()).command;
  assert.equal(command.task_id, task.id);
  assert.equal(command.action, 'dial');
  return { task, command, ws, next, device };
}
function generate(command, scenario, cancelCommand) {
  const result = spawnSync('python3', ['-B', '-c', pythonFixture, macDirectory], {
    input: JSON.stringify({ command, scenario, cancel_command: cancelCommand }), encoding: 'utf8',
    timeout: 10000, maxBuffer: 1024 * 1024, env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' },
  });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
  const fixture = JSON.parse(result.stdout);
  assert.deepEqual(fixture.pending_after_ack, []);
  fixture.events = fixture.wire.map(wire => JSON.parse(wire));
  return fixture;
}
async function replay(channel, fixture, inspect = () => {}) {
  for (let index = 0; index < fixture.wire.length; index++) {
    // Forward Python's exact serialized bytes, without repairing any fields in JS.
    channel.ws.send(fixture.wire[index]);
    const event = fixture.events[index], ack = await channel.next();
    assert.deepEqual(ack, { v: 1, type: 'ack', task_id: event.task_id, sequence: event.sequence }, 'Worker rejected Mac event: ' + JSON.stringify({ event, response: ack }));
    const { task } = await value('/api/tasks/' + channel.task.id);
    assert.equal(task.sequence, event.sequence);
    assert.equal(task.work_id, event.work_id);
    assert.equal(task.call_id, event.call_id);
    await inspect(task, event);
  }
}
function assertWorkBinding(events) {
  assert.match(events[0].work_id, /^[0-9a-f-]{36}$/);
  assert.equal(new Set(events.map(event => event.work_id)).size, 1);
}

test('shipping Mac Store/Runtime wire reaches SQLite through WS: handoff stays live, end and late recording are ACKed', async () => {
  const channel = await setup(), fixture = generate(channel.command, 'complete');
  assertWorkBinding(fixture.events);
  const phases = fixture.events.map(event => event.phase);
  for (const phase of ['accepted', 'preparing', 'dialing', 'active', 'ai_active', 'handed_off', 'ended', 'recording_ready']) assert.ok(phases.includes(phase), phase);
  assert.ok(phases.indexOf('preparing') < phases.indexOf('dialing'));
  assert.ok(phases.indexOf('dialing') < phases.indexOf('active'));
  const beforeDial = fixture.events.slice(0, phases.indexOf('dialing'));
  assert.ok(beforeDial.every(event => event.call_id === null), 'native prepare lease must stay private before actual dial');
  const issued = fixture.events.slice(phases.indexOf('dialing'));
  assert.match(issued[0].call_id, /^[0-9a-f-]{36}$/);
  assert.equal(new Set(issued.map(event => event.call_id)).size, 1);
  assert.equal(fixture.events.at(-1).carrier_ended, false, 'archive event cannot assert a second carrier end');
  await replay(channel, fixture, async (task, event) => {
    if (event.phase === 'handed_off') {
      assert.equal(task.terminal, false); assert.equal(task.carrier_ended, false);
      const rpc = await value('/mcp', { body: { jsonrpc: '2.0', id: 1, method: 'tools/call', params: {
        name: 'flashphone_wait_task', arguments: { task_id: task.id, timeout_seconds: 0 },
      } } });
      assert.equal(rpc.result.structuredContent.terminal, false);
      assert.equal(rpc.result.structuredContent.timed_out, true);
    }
    if (event.phase === 'ended' || event.phase === 'recording_ready') {
      assert.equal(task.phase, 'ended'); assert.equal(task.terminal, true); assert.equal(task.carrier_ended, true);
      assert.equal(task.result.outcome, 'needs_review');
      assert.equal(task.result.transcript[0].text, 'Synthetic only.');
    }
  });
  assert.equal(fixture.actions.filter(action => action === 'dial').length, 1);
  assert.equal(fixture.actions.filter(action => action === 'synthetic_voice').length, 1);
});

test('Mac cancel during native prepare uses no public call_id, never dials, and persists canceled without Worker 400', async () => {
  const channel = await setup();
  await value('/api/tasks/' + channel.task.id + '/cancel', { body: {} });
  const cancel = (await channel.next()).command;
  assert.equal(cancel.action, 'cancel'); assert.equal(cancel.expected_call_id, null);
  const fixture = generate(channel.command, 'cancel', cancel);
  assertWorkBinding(fixture.events);
  assert.equal(fixture.events.at(-1).phase, 'canceled');
  assert.ok(fixture.events.every(event => event.call_id === null));
  assert.ok(!fixture.actions.includes('dial')); assert.ok(!fixture.actions.includes('synthetic_voice'));
  await replay(channel, fixture, (task, event) => {
    if (event.phase === 'canceled') {
      assert.equal(task.phase, 'canceled'); assert.equal(task.terminal, true); assert.equal(task.carrier_ended, false);
    }
  });
});

test('reopening the Mac SQLite spool recovers interrupted with original work/call binding and no dial replay', async () => {
  const channel = await setup(), fixture = generate(channel.command, 'restart');
  assertWorkBinding(fixture.events);
  assert.equal(fixture.before_restart.at(-1).phase, 'handed_off');
  const recovered = fixture.events.at(-1);
  assert.equal(recovered.phase, 'interrupted'); assert.equal(recovered.carrier_ended, false);
  assert.equal(recovered.work_id, fixture.before_restart.at(-1).work_id);
  assert.equal(recovered.call_id, fixture.before_restart.at(-1).call_id);
  assert.equal(recovered.result.reason, 'agent_restarted_no_replay');
  assert.equal(fixture.actions.filter(action => action === 'dial').length, 1);
  await replay(channel, fixture, (task, event) => {
    if (event.phase === 'interrupted') {
      assert.equal(task.phase, 'interrupted'); assert.equal(task.terminal, true); assert.equal(task.carrier_ended, false);
      assert.equal(task.result.reason, 'agent_restarted_no_replay');
    }
  });
});

test('shipping Python recording uploader streams authenticated WAV to Worker/R2, retries lost response idempotently, and publishes a bound late archive', async () => {
  const channel = await setup(), baseline = generate(channel.command, 'upload_base');
  await replay(channel, baseline);
  assert.equal(baseline.events.at(-1).phase, 'ended');
  const route = '/api/tasks/' + channel.task.id + '/recording', requests = [], serverErrors = [];
  // The production handler requires HTTPS. This loopback-only HTTP ingress passes
  // the actual Python request/body to dispatchFetch with that trusted test origin;
  // auth, digest, streaming upload, SQLite and R2 handlers are unmodified.
  const server = createServer(async (incoming, outgoing) => {
    try {
      assert.equal(incoming.url, route); assert.equal(incoming.method, 'POST');
      const headers = new Headers();
      for (const name of ['authorization', 'content-type', 'content-length', 'x-recording-sha256', 'user-agent']) {
        if (incoming.headers[name]) headers.set(name, incoming.headers[name]);
      }
      headers.set('CF-Connecting-IP', '192.0.2.' + ++ip);
      const response = await mf.dispatchFetch('https://mac-wire.test' + route, {
        method: 'POST', headers, body: Readable.toWeb(incoming), duplex: 'half',
      });
      const bytes = new Uint8Array(await response.arrayBuffer());
      requests.push({ status: response.status, authenticated: headers.get('authorization') === 'Bearer ' + channel.device.device_token,
        digest: headers.get('X-Recording-SHA256'), bytes: Number(headers.get('Content-Length')) });
      if (requests.length === 1) {
        // R2 has committed, but the uploader never sees the success response.
        // Its real persisted retry must return the same metadata and one object.
        outgoing.destroy(); return;
      }
      outgoing.writeHead(response.status, Object.fromEntries(response.headers)); outgoing.end(bytes);
    } catch (error) {
      serverErrors.push(error); outgoing.writeHead(500); outgoing.end('Synthetic ingress failed');
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let uploaded;
  try {
    const result = await new Promise((resolve, reject) => {
      // spawnSync would block the local HTTP ingress needed by the real uploader.
      const child = spawn('python3', ['-B', '-c', pythonUploadFixture, macDirectory], {
        env: { ...process.env, PYTHONDONTWRITEBYTECODE: '1' }, stdio: ['pipe', 'pipe', 'pipe'],
      });
      let stdout = '', stderr = '';
      const timer = setTimeout(() => { child.kill('SIGKILL'); reject(Error('Python recording upload timed out')); }, 15000);
      child.stdout.on('data', chunk => { stdout += chunk; }); child.stderr.on('data', chunk => { stderr += chunk; });
      child.on('error', error => { clearTimeout(timer); reject(error); });
      child.on('close', code => {
        clearTimeout(timer);
        if (code !== 0) reject(Error('Python recording uploader failed: ' + stderr));
        else { try { resolve(JSON.parse(stdout)); } catch (error) { reject(error); } }
      });
      child.stdin.end(JSON.stringify({ events: baseline.events, device_token: channel.device.device_token,
        origin: 'http://127.0.0.1:' + server.address().port }));
    });
    assert.deepEqual(serverErrors, []);
    assert.deepEqual(requests.map(request => request.status), [201, 200]);
    assert.ok(requests.every(request => request.authenticated));
    assert.equal(result.upload.status, 'uploaded'); assert.equal(result.upload.attempts, 2);
    assert.ok(requests.every(request => request.digest === result.source_sha256 && request.bytes === result.upload.bytes));
    assert.equal(result.upload.sha256, result.source_sha256);
    assert.equal(result.snapshot.phase, 'ended');
    assert.equal(result.snapshot.call_id, baseline.events.at(-1).call_id);
    assert.equal(result.snapshot.work_id, baseline.events.at(-1).work_id);
    uploaded = { ...result, events: result.wire.map(wire => JSON.parse(wire)) };
  } finally {
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
  const original = Buffer.from(uploaded.source_base64, 'base64');
  const beforeArchive = (await value('/api/tasks/' + channel.task.id)).task;
  assert.equal(beforeArchive.phase, 'ended'); assert.equal(beforeArchive.terminal, true);
  assert.equal(beforeArchive.recording.bytes, original.length);
  assert.equal(beforeArchive.recording.sha256, createHash('sha256').update(original).digest('hex'));
  assert.equal(beforeArchive.recording.url, route);
  assert.equal((await request(route, { auth: null })).status, 401);
  assert.equal((await request(route, { auth: channel.device.device_token })).status, 403);
  const full = await request(route);
  assert.equal(full.status, 200); assert.deepEqual(Buffer.from(await full.arrayBuffer()), original);
  const partial = await request(route, { headers: { Range: 'bytes=0-43' } });
  assert.equal(partial.status, 206); assert.equal(partial.headers.get('Content-Range'), 'bytes 0-43/' + original.length);
  assert.deepEqual(Buffer.from(await partial.arrayBuffer()), original.subarray(0, 44));
  assert.equal((await (await mf.getR2Bucket('RECORDINGS')).list()).objects.length, 1);
  assert.equal(uploaded.events.length, 1);
  const archived = uploaded.events[0];
  assert.equal(archived.phase, 'recording_ready'); assert.equal(archived.carrier_ended, false);
  assert.equal(archived.call_id, beforeArchive.call_id); assert.equal(archived.work_id, beforeArchive.work_id);
  assert.equal(archived.result.recording_upload.status, 'uploaded');
  assert.ok(!uploaded.wire[0].includes('flashphone-mac-upload-'), 'wire must never disclose the private recording path');
  await replay(channel, uploaded, task => {
    assert.equal(task.phase, 'ended'); assert.equal(task.terminal, true); assert.equal(task.carrier_ended, true);
    assert.equal(task.result.recording_upload.status, 'uploaded');
    assert.equal(task.result.transcript[0].text, 'Synthetic only.');
    assert.equal(task.recording.sha256, uploaded.source_sha256);
  });
});
