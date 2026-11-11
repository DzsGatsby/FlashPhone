import Foundation
import CoreAudio
import AudioToolbox
import ApplicationServices
import Darwin

struct BridgeError: Error { let message: String }
func checked(_ status: OSStatus, _ operation: String) throws {
    if status != noErr { throw BridgeError(message: "\(operation): OSStatus \(status)") }
}
func address(_ selector: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
    AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
}
func scalar<T>(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector, _ initial: T, _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) throws -> T {
    var a = address(selector, scope), value = initial, size = UInt32(MemoryLayout<T>.size)
    try withUnsafeMutableBytes(of: &value) { raw in
        try checked(AudioObjectGetPropertyData(id, &a, 0, nil, &size, raw.baseAddress!), "read property")
    }
    return value
}
func text(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) throws -> String {
    var a = address(selector), value: Unmanaged<CFString>?, size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
    try checked(AudioObjectGetPropertyData(id, &a, 0, nil, &size, &value), "read name")
    guard let value else { throw BridgeError(message: "missing_name") }
    return value.takeRetainedValue() as String
}
func objects(_ selector: AudioObjectPropertySelector) throws -> [AudioObjectID] {
    var a = address(selector), size: UInt32 = 0
    let system = AudioObjectID(kAudioObjectSystemObject)
    try checked(AudioObjectGetPropertyDataSize(system, &a, 0, nil, &size), "list size")
    var values = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
    try checked(AudioObjectGetPropertyData(system, &a, 0, nil, &size, &values), "list objects")
    return values
}
func channels(_ device: AudioObjectID, _ scope: AudioObjectPropertyScope) throws -> Int {
    var a = address(kAudioDevicePropertyStreamConfiguration, scope), size: UInt32 = 0
    try checked(AudioObjectGetPropertyDataSize(device, &a, 0, nil, &size), "stream size")
    let raw = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioBufferList>.alignment)
    defer { raw.deallocate() }
    try checked(AudioObjectGetPropertyData(device, &a, 0, nil, &size, raw), "stream channels")
    return UnsafeMutableAudioBufferListPointer(raw.assumingMemoryBound(to: AudioBufferList.self)).reduce(0) { $0 + Int($1.mNumberChannels) }
}
func format(_ device: AudioObjectID, _ scope: AudioObjectPropertyScope) throws -> AudioStreamBasicDescription {
    let f = try scalar(device, kAudioDevicePropertyStreamFormat, AudioStreamBasicDescription(), scope)
    guard f.mFormatID == kAudioFormatLinearPCM, f.mFormatFlags & kAudioFormatFlagIsFloat != 0,
          f.mBitsPerChannel == 32, f.mSampleRate > 0 else { throw BridgeError(message: "requires_float32_device_format") }
    return f
}
func filmage() throws -> AudioObjectID {
    let found = try objects(kAudioHardwarePropertyDevices).filter { (try? text($0, kAudioObjectPropertyName)) == "Filmage Audio Device" }
    guard found.count == 1 else { throw BridgeError(message: "requires_unique_dedicated_Filmage_device") }
    let id = found[0]
    let transport = try scalar(id, kAudioDevicePropertyTransportType, UInt32(0))
    guard transport == kAudioDeviceTransportTypeVirtual,
          try channels(id, kAudioObjectPropertyScopeInput) == 2,
          try channels(id, kAudioObjectPropertyScopeOutput) == 2 else {
        throw BridgeError(message: "physical_or_aggregate_device_refused")
    }
    return id
}
func json(_ value: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys])
    FileHandle.standardOutput.write(data); FileHandle.standardOutput.write(Data([10]))
}

// Short lock-protected queue, bounded to 0.5 seconds. No allocation/file/network operation in render.
final class FloatRing {
    let lock = NSLock()
    var data: [Float]
    init(capacity: Int = 48000) { data = [Float](repeating: 0, count: max(1, capacity)) }
    var read = 0, write = 0, count = 0
    var overflows = 0
    func append(_ samples: [Float]) {
        lock.lock(); defer { lock.unlock() }
        for v in samples {
            if count == data.count { overflows += 1; continue }
            data[write] = v.isFinite ? max(-1, min(1, v)) : 0
            write = (write + 1) % data.count; count += 1
        }
    }
    func nextLocked() -> Float {
        if count == 0 { return 0 }
        let v = data[read]; read = (read + 1) % data.count; count -= 1; return v
    }
}

func virtualLoopback(seconds: Double) throws {
    // Only this exact stand-alone virtual device. Neither defaults nor hardware microphones are touched.
    let device = try filmage()
    let input = try format(device, kAudioObjectPropertyScopeInput)
    let output = try format(device, kAudioObjectPropertyScopeOutput)
    guard input.mSampleRate == output.mSampleRate else { throw BridgeError(message: "loopback_rate_mismatch") }
    let lock = NSLock()
    var sent: UInt64 = 0, received: UInt64 = 0
    var sumSquared = 0.0, re = 0.0, im = 0.0
    var proc: AudioDeviceIOProcID?
    try checked(AudioDeviceCreateIOProcIDWithBlock(&proc, device, nil) { _, inData, _, outData, _ in
        lock.lock(); defer { lock.unlock() }
        let inputs = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: inData))
        for b in inputs {
            guard let pointer = b.mData else { continue }
            let p = pointer.assumingMemoryBound(to: Float.self)
            let n = Int(b.mDataByteSize) / 4, c = max(1, Int(b.mNumberChannels))
            for i in stride(from: 0, to: n, by: c) {
                let x = Double(p[i]), phase = 2 * Double.pi * 997 * Double(received) / input.mSampleRate
                sumSquared += x * x; re += x * cos(phase); im += x * sin(phase); received += 1
            }
            break
        }
        let outputs = UnsafeMutableAudioBufferListPointer(outData)
        for b in outputs {
            guard let pointer = b.mData else { continue }
            let p = pointer.assumingMemoryBound(to: Float.self)
            let c = max(1, Int(b.mNumberChannels)), frames = Int(b.mDataByteSize) / 4 / c
            for frame in 0..<frames {
                let value = Float(0.08 * sin(2 * Double.pi * 997 * Double(sent + UInt64(frame)) / output.mSampleRate))
                for channel in 0..<c { p[frame * c + channel] = value }
            }
        }
        if let b = outputs.first { sent += UInt64(Int(b.mDataByteSize) / 4 / max(1, Int(b.mNumberChannels))) }
    }, "create virtual loopback")
    guard let proc else { throw BridgeError(message: "missing_io_proc") }
    defer { AudioDeviceDestroyIOProcID(device, proc) }
    try checked(AudioDeviceStart(device, proc), "start virtual loopback")
    Thread.sleep(forTimeInterval: min(5, max(0.5, seconds)))
    AudioDeviceStop(device, proc)
    lock.lock(); defer { lock.unlock() }
    let rms = sqrt(sumSquared / Double(max(1, received)))
    let amplitude = 2 * sqrt(re * re + im * im) / Double(max(1, received))
    json(["command": "virtual-loopback", "device": "Filmage Audio Device", "physical_microphone_opened": false,
          "defaults_changed": false, "sample_rate": input.mSampleRate, "captured_frames": received,
          "rendered_frames": sent, "rms": rms, "tone_amplitude": amplitude,
          "passed": received > 1000 && amplitude > 0.02 && amplitude < 0.12,
          "phone_rx_verified": false, "phone_tx_verified": false])
}

func transmit(seconds: Double) throws {
    let device = try filmage(), f = try format(device, kAudioObjectPropertyScopeOutput)
    // Native-rate mono Float32LE stdin. Every output channel receives the same sample.
    let ring = FloatRing(capacity: Int(f.mSampleRate / 2))
    var proc: AudioDeviceIOProcID?
    try checked(AudioDeviceCreateIOProcIDWithBlock(&proc, device, nil) { _, _, _, outData, _ in
        ring.lock.lock(); defer { ring.lock.unlock() }
        let outputs = UnsafeMutableAudioBufferListPointer(outData)
        let frames = outputs.map { Int($0.mDataByteSize) / 4 / max(1, Int($0.mNumberChannels)) }.min() ?? 0
        for frame in 0..<frames {
            let value = ring.nextLocked()
            for b in outputs {
                guard let pointer = b.mData else { continue }
                let p = pointer.assumingMemoryBound(to: Float.self), c = Int(b.mNumberChannels)
                for channel in 0..<c { p[frame * c + channel] = value }
            }
        }
    }, "create TX")
    guard let proc else { throw BridgeError(message: "missing_io_proc") }
    defer { AudioDeviceDestroyIOProcID(device, proc) }
    try checked(AudioDeviceStart(device, proc), "start TX")
    defer { AudioDeviceStop(device, proc) }
    let oldFlags = fcntl(STDIN_FILENO, F_GETFL)
    guard oldFlags >= 0, fcntl(STDIN_FILENO, F_SETFL, oldFlags | O_NONBLOCK) == 0 else { throw BridgeError(message: "stdin_nonblocking_failed") }
    defer { _ = fcntl(STDIN_FILENO, F_SETFL, oldFlags) }
    let deadline = Date().addingTimeInterval(min(600, max(0.1, seconds)))
    var bytes = [UInt8](repeating: 0, count: 4096), carry = Data()
    while Date() < deadline {
        let n = Darwin.read(STDIN_FILENO, &bytes, bytes.count)
        if n == 0 {
            let drainDeadline = Date().addingTimeInterval(0.6)
            while Date() < drainDeadline {
                ring.lock.lock(); let empty = ring.count == 0; ring.lock.unlock()
                if empty { break }
                Thread.sleep(forTimeInterval: 0.005)
            }
            break
        }
        if n < 0 {
            if errno != EAGAIN && errno != EINTR { throw BridgeError(message: "TX_input_failed") }
            Thread.sleep(forTimeInterval: 0.005); continue
        }
        carry.append(contentsOf: bytes.prefix(n))
        let aligned = carry.count / 4 * 4
        let samples = carry.prefix(aligned).withUnsafeBytes { raw in
            (0..<(aligned / 4)).map { raw.loadUnaligned(fromByteOffset: $0 * 4, as: Float.self) }
        }
        ring.append(samples); carry.removeFirst(aligned)
        ring.lock.lock(); let overflowed = ring.overflows > 0; ring.lock.unlock()
        if overflowed { throw BridgeError(message: "TX_consumer_backpressure") }
    }
    ring.lock.lock(); let overflow = ring.overflows; ring.lock.unlock()
    json(["command": "tx", "sample_rate": f.mSampleRate, "overflows": overflow, "remote_audible_verified": false])
}

@available(macOS 14.2, *)
func capture(seconds: Double) throws {
    let candidates = try objects(kAudioHardwarePropertyProcessObjectList).filter {
        (try? text($0, kAudioProcessPropertyBundleID)) == "com.apple.mobilephone"
    }
    guard !candidates.isEmpty else { throw BridgeError(message: "Phone_audio_process_not_available") }
    let description = CATapDescription(stereoMixdownOfProcesses: candidates)
    description.name = "Flash phone scoped RX"
    description.isPrivate = true
    description.muteBehavior = .unmuted
    var tap: AudioObjectID = 0
    try checked(AudioHardwareCreateProcessTap(description, &tap), "create Phone process tap")
    defer { AudioHardwareDestroyProcessTap(tap) }
    let f = try scalar(tap, kAudioTapPropertyFormat, AudioStreamBasicDescription())
    guard f.mFormatID == kAudioFormatLinearPCM, f.mFormatFlags & kAudioFormatFlagIsFloat != 0,
          f.mBitsPerChannel == 32, f.mFormatFlags & kAudioFormatFlagIsNonInterleaved == 0 else { throw BridgeError(message: "unsupported_tap_format") }
    let uid = try text(tap, kAudioTapPropertyUID)
    let settings: [String: Any] = [kAudioAggregateDeviceNameKey: "Flash phone private RX", kAudioAggregateDeviceUIDKey: UUID().uuidString,
        kAudioAggregateDeviceIsPrivateKey: true, kAudioAggregateDeviceTapAutoStartKey: true,
        kAudioAggregateDeviceTapListKey: [[kAudioSubTapUIDKey: uid, kAudioSubTapDriftCompensationKey: true]]]
    var aggregate: AudioObjectID = 0
    try checked(AudioHardwareCreateAggregateDevice(settings as CFDictionary, &aggregate), "create private tap aggregate")
    defer { AudioHardwareDestroyAggregateDevice(aggregate) }
    // Nonblocking stdout: framed F32 buffers. Dropped frames terminate capture rather than corrupt framing.
    let flags = fcntl(STDOUT_FILENO, F_GETFL)
    guard flags >= 0, fcntl(STDOUT_FILENO, F_SETFL, flags | O_NONBLOCK) == 0 else { throw BridgeError(message: "stdout_nonblocking_failed") }
    defer { _ = fcntl(STDOUT_FILENO, F_SETFL, flags) }
    let lock = NSLock(); var failed = false
    var proc: AudioDeviceIOProcID?
    try checked(AudioDeviceCreateIOProcIDWithBlock(&proc, aggregate, nil) { _, input, _, _, _ in
        lock.lock(); defer { lock.unlock() }
        if failed { return }
        for b in UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: input)) {
            guard let p = b.mData else { continue }
            // <= PIPE_BUF (512 on Darwin) atomic blocks: header[magic u32,rate u32,channels u32,len u32], LE.
            let n = Int(b.mDataByteSize), alignment = max(4, Int(b.mNumberChannels) * 4)
            let chunk = (496 / alignment) * alignment
            var offset = 0
            while offset < n {
                let length = min(chunk, n - offset)
                var header: [UInt32] = [0x31414346, UInt32(f.mSampleRate), b.mNumberChannels, UInt32(length)]
                var packet = Data(bytes: &header, count: 16)
                packet.append(p.advanced(by: offset).assumingMemoryBound(to: UInt8.self), count: length)
                let written = packet.withUnsafeBytes { Darwin.write(STDOUT_FILENO, $0.baseAddress, $0.count) }
                if written != packet.count { failed = true; return }
                offset += length
            }
        }
    }, "create RX")
    guard let proc else { throw BridgeError(message: "missing_io_proc") }
    defer { AudioDeviceDestroyIOProcID(aggregate, proc) }
    try checked(AudioDeviceStart(aggregate, proc), "start RX")
    defer { AudioDeviceStop(aggregate, proc) }
    let deadline = Date().addingTimeInterval(min(600, max(0.1, seconds)))
    while Date() < deadline {
        lock.lock(); let stopped = failed; lock.unlock()
        if stopped { throw BridgeError(message: "RX_consumer_backpressure") }
        Thread.sleep(forTimeInterval: 0.02)
    }
}

func diagnostics() throws {
    let devices = try objects(kAudioHardwarePropertyDevices).map { id -> [String: Any] in
        ["output_sample_rate": (try? scalar(id, kAudioDevicePropertyNominalSampleRate, Float64(0))) ?? 0, "name": try text(id, kAudioObjectPropertyName), "input_channels": (try? channels(id, kAudioObjectPropertyScopeInput)) ?? 0,
         "output_channels": (try? channels(id, kAudioObjectPropertyScopeOutput)) ?? 0]
    }
    json(["devices": devices, "accessibility_trusted": AXIsProcessTrusted(), "process_tap_api": true,
          "system_audio_capture_permission": "not_tested", "phone_rx_verified": false, "phone_tx_verified": false,
          "control_verified": false, "ready": false, "reason": "requires_setup"])
}

signal(SIGPIPE, SIG_IGN)
do {
    let args = CommandLine.arguments, command = args.count > 1 ? args[1] : "capabilities"
    let seconds = args.count > 2 ? (Double(args[2]) ?? 2) : 2
    switch command {
    case "control-server": controlServer()
    case "control-snapshot": json(try PhoneController().snapshot())
    case "tap-probe":
        if #available(macOS 14.2, *) {
            let candidates = try objects(kAudioHardwarePropertyProcessObjectList).filter { (try? text($0, kAudioProcessPropertyBundleID)) == "com.apple.mobilephone" }
            guard !candidates.isEmpty else { throw BridgeError(message: "Phone_audio_process_not_available") }
            let description = CATapDescription(stereoMixdownOfProcesses: candidates)
            description.isPrivate = true; description.muteBehavior = .unmuted
            var tap: AudioObjectID = 0
            try checked(AudioHardwareCreateProcessTap(description, &tap), "create Phone probe tap")
            AudioHardwareDestroyProcessTap(tap)
            json(["tap_created": true, "capture_started": false, "audio_content_read": false, "permission_verified": false])
        } else { throw BridgeError(message: "macOS_14_2_required") }
    case "selftest":
        try PhoneAudioRouteLease.selftest()
        let ring = FloatRing(); ring.append([0.5, -0.5, .nan, 2, -2])
        ring.lock.lock()
        let actual = (0..<6).map { _ in ring.nextLocked() }
        ring.lock.unlock()
        guard actual == [0.5, -0.5, 0, 1, -1, 0] else { throw BridgeError(message: "ring_selftest_failed") }
        guard try PhoneController.normalized("+12025550123") == "+12025550123",
              PhoneController.digits("(202) 555-0100") == "2025550100" else { throw BridgeError(message: "controller_selftest_failed") }
        for invalid in ["911;open", "++1234", "12", "１２３４"] {
            do { _ = try PhoneController.normalized(invalid); throw BridgeError(message: "invalid_number_accepted") }
            catch let error as BridgeError { if error.message == "invalid_number_accepted" { throw error } }
        }
        json(["passed": true, "audio_devices_opened": false, "Phone_UI_touched": false])
    case "capabilities": try diagnostics()
    case "virtual-loopback": try virtualLoopback(seconds: seconds)
    case "tx": try transmit(seconds: seconds)
    case "rx-phone":
        if #available(macOS 14.2, *) { try capture(seconds: seconds) }
        else { throw BridgeError(message: "macOS_14_2_required") }
    default: throw BridgeError(message: "unknown_command")
    }
} catch {
    let reason = (error as? BridgeError)?.message ?? "native_bridge_failed"
    FileHandle.standardError.write(Data((reason + "\n").utf8)); exit(1)
}
