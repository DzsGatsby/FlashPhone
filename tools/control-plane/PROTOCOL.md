# Flash phone device control protocol v1

Personal owner account, many controller sessions and many execution devices. HTTPS/WSS only.
Cloudflare owner Durable Object + private optional R2 recordings. No cloud audio relay/inference.
Bootstrap root key is random 256-bit, stored privately on Mac; only its SHA256 is a Worker secret.
Never log bearer tokens, enrollment codes, phone numbers, task text or audio. No public signup.

## Auth and controller endpoints
- POST /api/login {key} -> HttpOnly Secure SameSite cookie, controller info. Bearer owner key also works.
- POST /api/session-links {label} authenticated -> one-use short code, 5 minute expiry.
- POST /api/session-links/claim {code,label} -> controller_token + cookie; only token hashes persisted.
- POST /api/device-links {} authenticated -> one-use enrollment code, 5 minute expiry.
- POST /api/device-enroll {code,name,platform,capabilities} -> device_id, device_token, account label.
  platforms android | iphone-mac. Device tokens cannot create controller sessions or other devices.
  Codes rate limited 5 attempts/IP/5min + global cap, invalidate after success; max32 devices.
- GET /api/devices authenticated -> {devices:[{id,name,platform,online,capabilities,status,last_seen}]}.
- DELETE /api/devices/:id authenticated -> revoke token and close connection, no automatic hangup.
- POST /api/tasks {device_id,action:"dial",number,sim_id?,task:{goal,questions,context,constraints},idempotency_key,expires_in_seconds?}
  -> {task:{id,device_id,phase,...}}. Require explicit goal, valid normal phone, max expiry120s.
  Reject offline/not-capable devices (do not queue calls for later). Same key+payload returns same task;
  different payload under same key is409. Max one nonterminal call task per device.
- GET /api/tasks?limit=50 and GET /api/tasks/:id authenticated -> task + ordered events.
- POST /api/tasks/:id/cancel authenticated -> cancel queued/preparing call or stop AI/handoff;
  cancel is NOT an implicit hangup. A new call must never be targeted by an old task command.
- POST /api/tasks/:id/hangup authenticated -> explicit hangup of that task's current call only.
- POST /api/tasks/:id/recording using bound device auth, Content-Type audio/wav, bytes bounded40MiB
  -> private R2 recording metadata, cannot attach to a different device's task.
- GET /api/tasks/:id/recording controller auth -> stream private recording, no public bearer-in-query URL.
- GET /api/device/socket device Bearer auth -> websocket; native clients use Authorization header.

## Device socket
Client first sends:
{"v":1,"type":"hello","capabilities":["calls.dial","calls.cancel","calls.hangup","recording"],"status":{"ready":false,"reason":"..."}}
Server sends:
{"v":1,"type":"command","command":{"command_id":"uuid","task_id":"uuid","device_id":"uuid","action":"dial|cancel|hangup","issued_at":0,"expires_at":0,"expected_call_id":null,"payload":{"number":"...","sim_id":null,"task":{"goal":"...","questions":[],"context":"","constraints":""}}}}
Times are unix milliseconds. Commands max16KiB. Lease checks local opt-in and strict expiration.
Persist command/task dedupe BEFORE dialing; reconnect never redials. Changed payload with same ID fails.
After process restart, unfinished previously issued calls are interrupted/unknown, NEVER replayed.
Device event:
{"v":1,"type":"event","task_id":"uuid","sequence":1,"phase":"preparing","carrier_ended":false,"ai_active":false,"work_id":null,"call_id":null,"result":null}
Phases: accepted, preparing, dialing, ringing, active, ai_active, handed_off, ended, failed,
interrupted, canceled, recording_ready. Per-task sequence strictly monotonic, duplicate ignored.
result may contain summary, outcome, transcript:[{role,text}], recording:{...}, reason.
Task owner/device IDs and call_id binding must match. received DIALING result is NOT task completion.
AI leaving while carrier call is active => handed_off, not ended. Real carrier end => ended.
Pre-dial failure/cancel is terminal with no call issued. Unknown end => interrupted, carrier_ended=false.
recording_ready merges metadata without reverting the task's already terminal state.
Native WebSocket protocol ping every60sec; no high-frequency SQL heartbeat writes. Bounded exponential
reconnect 2..60sec with jitter; same persisted device identity, bounded unsent event spool.

## MCP and CLI
POST /mcp Bearer controller/root key, stateless Streamable HTTP JSON RPC.
Tools flashphone_devices, flashphone_call_task, flashphone_task_status, flashphone_wait_task,
flashphone_cancel_task, flashphone_hangup_task, flashphone_recording.
wait <=25sec, returns latest state and timeout vs terminal accurately. Result includes task_id.
MCP must never describe dial acceptance as successful conversation. All capabilities share same tasks
with the responsive web controller. Token errors return401 without disclosing account/device state.

## Bounds
Owner32 devices, one active call task/device, max200 retained tasks, max128 bounded events/task,
max64KiB result/transcript; recordings private max40MiB/file and max50 files/1GiB total per owner.
No background call retry; no schedules/cron; no durable outgoing replay after device restart.
Bootstrap, all sessions/tokens, mutation and protocol routes fail closed when not configured.
