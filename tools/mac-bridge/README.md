# Flash phone Mac / iPhone bridge prototype

This directory is independent of the Android build. It contains an outbound WSS device agent,
private persistent command/event spool, and a compiled Core Audio / scoped Phone AX helper.
**The current build deliberately reports `requires_setup` and does not advertise `calls.dial`.**
The observed keypad can be prepared safely, but carrier ACTIVE/end ownership is not yet verified.
A virtual loopback passing is not proof of a real telephone conversation.

## Build and offline verification

Requires macOS26+ with Phone.app, Apple Command Line Tools, Python3.10+ and websockets15/16.
The underlying process-tap API itself is available from macOS14.2; Phone.app control is a separate requirement.

```sh
python3 -m venv tools/mac-bridge/.venv
tools/mac-bridge/.venv/bin/pip install -r tools/mac-bridge/requirements.txt
./tools/mac-bridge/build.sh /absolute/private/work/mac-bridge-build
python3 -m unittest discover -s tools/mac-bridge/tests -v
/absolute/private/work/mac-bridge-build/FlashMacBridge.app/Contents/MacOS/FlashMacBridge selftest
/absolute/private/work/mac-bridge-build/FlashMacBridge.app/Contents/MacOS/FlashMacBridge capabilities
```

`build.sh` creates a local ad-hoc signed app bundle, not a system service. It does not install
anything or change device defaults. A deployment should use a stable signing identity and bundle
path; replacing an ad-hoc executable may invalidate its TCC grants. No developer key is bundled.
The local Python tests use only temporary directories and localhost WebSockets, never a provider.

For an installed daemon, use the dedicated environment at
`~/Library/Application Support/Flash phone/venv` and install **all** of `requirements.txt`, including
`python-socks[asyncio]>=2.7,<3`. An existing system `import websockets` is not a deployment check:
websockets15/16 automatically uses the Mac's configured proxy, and an existing SOCKS route needs
python-socks. Without it, connection fails before WSS authentication. Keep the existing proxy/PF
boundary; do not work around this by setting `proxy=None` or disabling TLS. Set the LaunchAgent's
interpreter to that venv's `bin/python` (or `bin/python3`) and verify its `sys.prefix` and imports of
both `websockets` and `python_socks`. The controller-only stdlib CLI is a separate process and does
not replace these device-daemon dependencies.

The explicitly opt-in, finite `virtual-loopback 2` command writes a 997 Hz synthetic tone only to
**Filmage Audio Device**, and reads that same stand-alone virtual device. It rejects physical,
aggregate, missing or ambiguous devices. It does not open a physical microphone, select a default
input/output, or touch BlackHole / the existing SonoBus LaunchAgent. The device is shared, not
claimed exclusively: other applications using Filmage may observe the synthetic tone. Do not run
this probe during another audio task. It reports only scalar counts/RMS/tone correlation, no PCM.

The observed local 2-second probe passed at 44100 Hz (88576 input/output frames, target amplitude
0.08, measured 0.07908). This proves the virtual device loopback, **not Phone routing or caller audio**.

## Native media contract

Commands are independent bounded processes; no command changes global audio routes.

- `capabilities`: JSON device names/channel counts/rates and no-prompt Accessibility trust. It never
  claims an untested system audio permission or a successful carrier call.
- `rx-phone SECONDS` (max600): creates an unmuted private process tap restricted to audio-process
  objects with bundle ID `com.apple.mobilephone`, then a private input aggregate. It does not fall
  back to a global tap, hardware microphone or unrelated helper process. Missing Phone audio process
  fails. Phone may actually emit audio through another OS process; this remains an explicit failure,
  not permission to capture everything. No file is written. Stdout must be a continuously drained pipe.
- RX stdout is binary **little endian**: 16-byte header `u32 magic=0x31414346` (`FCA1`),
  `u32 sample_rate`, `u32 channels`, `u32 payload_bytes<=496`, then interleaved Float32LE samples.
  Only native interleaved float32 tap formats are accepted. The reader must retain incomplete frames
  between reads, validate rate/channels/alignment, downmix and continuously resample to model 16k PCM16.
  Each packet is at most Darwin `PIPE_BUF=512`; nonblocking short/failed writes fail the RX process.
  No endless drop queue or backpressure on a phone stream is permitted.
- `tx SECONDS` (max600): stdin is native **mono Float32LE**, at Filmage's rate reported by
  `capabilities` (`output_sample_rate`, currently44100). Samples are duplicated to the device output
  channels. Resample model24k PCM16 continuously before writing. Queue is bounded to0.5s; overflow
  fails instead of silently dropping speech. EOF drains for at most0.6s; absent input emits silence.
  Stdout is final scalar JSON, stderr fixed errors only. Successful device render is not proof of
  remote audibility. Interruption must stop/discard old producer data and terminate/recreate this TX
  process; there is no unsafe mixed audio/control byte protocol or unacknowledged flush signal.
- `tap-probe`: creates then destroys a private Phone process tap without starting I/O or reading any
  audio. This can surface real TCC errors/prompts; `tap_created=true` alone is **not capture permission
  or Phone RX verification**. This command was not run in the initial virtual-only verification.

No process-tap probe currently promotes device readiness automatically. System capture and virtual
input may need actual TCC grants in Privacy & Security. `NSAudioCaptureUsageDescription` and
`NSMicrophoneUsageDescription` are in the bundle. A reported AX trust=true for one responsible
process is not a transferable entitlement for a future daemon/app. No automation grant is requested.

## Realtime media implementation

`voice.py` exports `async run_voice(task, native_helper, stop, emit, credential_file=None,
max_seconds=30)`. `task` is the command payload `{number, task:{goal,questions,context,constraints}}`.
The caller MUST positively bind the same ACTIVE carrier call before invoking this function.
No provider connection is established during ringing. It never dials, answers, ends or retries a call.

Credentials default to `~/Library/Application Support/Flash phone/voice.json`, a0600 regular JSON
file with `api_key` and optional `speaker`. The file is read only locally; the broker never receives
it. Model endpoint is fixed to the official Volcengine3.0 JSON WSS endpoint, model1.2.6.1; no redirects,
secret logs, paid smoke test, enrollment or provider call is performed by offline tests.

The pump first opens scoped RX and requires a valid native frame, drains/discards setup audio while
connecting, waits for `session.created`, then sends unmute and the greeting. RX is continuously
resampled to16kPCM16 and sent in640-byte/20ms blocks. Provider24k output is byte-bounded to4s and
resampled/paced into native Filmage20ms writes. In-flight bytes and response identity are tracked;
interruption retires the old epoch, clears pending output and terminates/recreates TX so stale pipe
bytes cannot be replayed. A delayed old-response cancel cannot cut a replacement response.

The entire setup+media interval is bounded1..600s. Stop cancels connection/ack waits as well as media;
RX/TX children, websocket and workers are closed in bounded finally cleanup. Any capture/provider/
output failure yields a fixed sanitized reason and leaves the carrier call for handoff. The return
value includes bounded final transcript, RX/pipe counters, reason and optional private WAV path.

The local stereo16kPCM16 WAV preserves observation-time gaps, channel0 captured Phone audio and
channel1 AI data successfully written to the TX pipe. **It is not AudioTrack acceptance or proof that
the remote party heard the speech.** It is stored in a0700 local directory with0600 files, max50 files/
1GiB and600s per call, with no automatic upload. This Mac prototype is not claiming application-level
recording encryption. Local FakeWS tests use a synthetic native process and test-only credential file,
never the real local credential or a paid provider.

## Phone control contract

`control-server` accepts one JSON object per stdin line, replies one JSON object per line, and owns
its in-memory single preparation lease. All paths are fixed executable arguments, never shell text.

- `{"action":"capabilities"}`: read-only status, currently `control_verified=false`.
- `prepare`: requires `task_id` UUID, strict ASCII `number` and unix-ms `expires_at`<=120s ahead.
  It requires Phone already running and AX trusted. It uses only the observed main window identifier
  `windowSceneWindow` → toolbar button Description`Keypad` → uniqueAXPopover → unique settableAXTextField.
  It enters the immutable number, compares normalized digits, and checks only that popover's button
  Identifier`Accept`/Description`Call`. It does **not press Call**. It returns a fresh `call_id` and
  `call_issued=false`. Locale/UI/tree mismatches fail closed, without using Recents or coordinates.
- `cancel`: requires that exact `task_id`/`expected_call_id`; clears only an unchanged, owned number
  draft, never an unrelated call. It does not hang up a carrier call.
- `dial` / `hangup`: fail closed until actual ACTIVE/end selectors and ownership are verified.
  No guessed End/Accept selectors, `tel:` launch or uncontrolled AppleScript fallback is implemented.

No controller prepare/dial/cancel action was executed by the implementation agent; root CUA separately
validated the keypad/readback with a synthetic number and canceled it. The native controller itself
still needs a root-controlled UI validation before being considered production control.

## Enrollment, daemon and storage

The backend protocol is [../control-plane/PROTOCOL.md](../control-plane/PROTOCOL.md).
Root/operator supplies a five-minute single-use enrollment code in a **0600 JSON file** `{"code":"..."}`.
Never put an owner bootstrap key or reusable token in CLI arguments, launchd plist, repository, logs
or a browser query string. No credential value is included in examples.

```sh
python3 tools/mac-bridge/agent.py enroll \
  --config "$HOME/Library/Application Support/Flash phone/mac-device.json" \
  --code-file /absolute/private/enroll.json \
  --server https://flash-phone.dzsgatsby.workers.dev --name 'Mac iPhone bridge'
python3 tools/mac-bridge/agent.py run \
  --config "$HOME/Library/Application Support/Flash phone/mac-device.json" \
  --state "$HOME/Library/Application Support/Flash phone/mac-bridge-state"
```

The config parent/state directory must be owned by this user and0700; credential files0600, regular,
not symlinks. Enrollment never overwrites an existing identity. Device config contains server,
device_id, device_token; optional `native_helper` is an absolute local helper binary path. Owner/API
model credentials are separate local-only files, not sent to the task broker. No enrollment or external
connection was executed by the implementation agent. No owner token is generated by this program.

- HTTPS enrollment / WSS only in production, standard TLS validation, **no redirects** that could leak
  Authorization. Bearer sent only to the fixed configured origin. No listener exposed on the Mac.
- Device hello advertises no telephone capability until real control/audio/media readiness is true.
  Root-controlled verification can distinguish setup readiness from `real_call_verified=false`;
  a full real call is an independent acceptance test, not faked by a synthetic loopback.
- Validate device/task/command UUID, action and strict TTL before executing. Dial number/brief fields
  are bounded; command max16KiB. Persist digest tombstone **and accepted event atomically before any
  execution**. Same command replay is ignored; changed same-ID payload rejected; new-ID same-task
  does not dial again. Async execution does not block reception of cancel/hangup commands.
- Restart marks previous unfinished tasks interrupted/unknown and never replays a call. Socket reconnect
  resends only unacknowledged events. The server ACK is exactly `{v:1,type:"ack",task_id,sequence}`;
  hello_ack must name this device. Revocation401/403/4003 exits; no infinite revoked-token retries.
- Private SQLite DELETE-journal/FULL-sync stores task result archive + monotonic sequence + bounded
  spool. At2048 unacknowledged events it fails closed; never discards undelivered results to make room.
  Up to200 acknowledged terminal task results remain. Dedupe tombstones are retained separately;
  max8192 commands then requires local maintenance/re-enrollment, never auto-evicts old IDs for replay.
- Outbound reconnect backoff2..60s with jitter, protocol ping60s. No high-frequency cloud heartbeat,
  background dialing retry, schedule, general shell endpoint or remote arbitrary UI commands.
- `launch-agent.plist.in` is a template only. Root/operator substitutes absolute paths and installs
  only after permissions/setup. It has no credential arguments and is **not installed by build/test**.
  `KeepAlive = {SuccessfulExit:false}` restarts abnormal/nonzero exits after launchd's throttle;
  it deliberately does not restart a normal exit after device revocation or graceful termination.
  This differs from an unconditional KeepAlive=true. A running daemon's ordinary WSS outages are
  handled inside its bounded reconnect loop; the Apple account block remains an online blocked
  status, never a reason to retry a telephone call.

## Remaining acceptance gates

ACTIVE/end AX binding and native controller UI validation; Phone tap permission and scoped process
selection on a real call; Phone microphone=Filmage and remote TX audibility; per-task ACTIVE/control integration of the media pump,
real interruption, timeout/handoff and final result; signed app TCC identity; private local account enrollment
and service install. This prototype must reject remote call tasks while these are incomplete.

## Native-control integration gate

`runtime.py` already wires `prepare → dial → status(active) → media-route → run_voice →
restore_route → handed_off → status(ended) → release`. Every control request carries this task's
immutable local `call_id`. It publishes `ended` only after a positive carrier-ended observation;
cancel stops AI and restores the route without an implicit hangup. Provider/preflight errors do not
create a second dial attempt. Native `status/media-route/restore_route/release` must be implemented
and validated against the actual Phone call subtree before the gate can open; they currently fail
closed. A random local lease ID is not being presented as an Apple call UUID.

The device's private config can eventually add `enabled: true`, `native_helper`, `voice_credential_file`,
`max_call_seconds` and `audio_setup_file`. The private setup receipt requires the current helper SHA256,
actual `virtual_loopback_verified`, `tap_io_verified`, `phone_route_verified` booleans and a future
`expires_at` (unix milliseconds, at most24h). A setup receipt is produced from actual local checks,
not from remote task arguments. `real_call_verified` is separate. Without validated native control,
route restoration, fresh audio setup evidence and local model credentials, no call capability is
advertised. A requested SIM is rejected until real line selection is implemented; it is never silently
mapped to the default iPhone line.

## Observed Apple setup block (2026-11-11)

Root's explicitly bounded CUA test did not connect: Phone displayed “iPhone Calls Not Available”
with the account-requirement alert. ACTIVE was never confirmed and **zero real model sessions**
were created. A second click produced a second failed Recents entry; root stopped all further
attempts and restored Phone Microphone to Use System Setting. No ACTIVE/end selectors were
obtained. These facts do not verify real Phone RX, remote TX or automated call control.

Private device config `setup_reason: "iphone_account_requirement"` makes both daemon hello and
local executor report blocked before any native/control/model attempt, while the WSS device
remains online with no telephone capabilities. The owner must personally check iPhone iCloud/
FaceTime and Mac Apple Account sign-in. The software does not read account identities, sign out,
switch accounts, silently clear this block or retry a call. A new explicit authorization is required
for any later real call test. Removing the block alone does not bypass the remaining control/audio
readiness checks. All localhost FakeWS/synthetic tests are independent of this external setup block.
