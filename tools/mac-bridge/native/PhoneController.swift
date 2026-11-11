import Foundation
import AppKit
import ApplicationServices
import CryptoKit

// Deliberately narrow Phone.app AX adapter. No coordinates, global keystrokes or Recents actions.
final class PhoneController {
    typealias Lease = PhoneCallOwnership
    private var lease: Lease?
    private var seen = Set<String>()
    private let appBundle = "com.apple.mobilephone"
    private let audioRoute = PhoneAudioRouteLease()
    // No controlled ACTIVE/end AX evidence has been collected for this build.
    private let layout: PhoneCallLayout? = nil
    private var preparedPID: pid_t?
    private var preparedPopover: AXUIElement?
    private var preparedField: AXUIElement?
    private var issuedPID: pid_t?
    private var boundContainer: AXUIElement?
    private var issuedAt: TimeInterval?
    private let diagnosticSalt = UUID().uuidString

    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private func selectorMatches(_ element: AXUIElement, _ selector: PhoneCallLayout.Selector) -> Bool {
        string(element, kAXRoleAttribute) == selector.role &&
        string(element, kAXIdentifierAttribute) == selector.identifier &&
        (selector.description == nil || string(element, kAXDescriptionAttribute) == selector.description)
    }
    private func matches(_ lhs: AXUIElement, _ rhs: AXUIElement) -> Bool { CFEqual(lhs, rhs) }
    private func token(_ text: String) -> String {
        SHA256.hash(data: Data((diagnosticSalt + text).utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
    }
    private func processID(_ element: AXUIElement) throws -> pid_t {
        var pid: pid_t = 0
        guard AXUIElementGetPid(element, &pid) == .success, pid > 0 else { throw BridgeError(message: "Phone_process_not_available") }
        return pid
    }
    private func observeOwnedCall(_ app: AXUIElement) throws {
        guard var current = lease, current.issued else { return }
        guard let layout, try processID(app) == issuedPID else { current.loseOwnership(); lease = current; return }
        let all = try descendants(app)
        let containers = all.filter { selectorMatches($0, layout.container) }
        guard containers.count == 1 else {
            // Missing UI after clicking does not prove no call/ended. Only the bounded initial
            // request interval may remain dialing, and never authorizes audio or hangup.
            if current.scopeID != nil || containers.count > 1 || ProcessInfo.processInfo.systemUptime - (issuedAt ?? 0) >= 10 {
                current.loseOwnership(); lease = current
            }
            return
        }
        let container = containers[0]
        if let boundContainer, !matches(boundContainer, container) { current.loseOwnership(); lease = current; return }
        let nodes = try descendants(container)
        let peer = try unique(nodes) { selectorMatches($0, layout.peer) }
        let direction = try unique(nodes) { selectorMatches($0, layout.outgoing) }
        let state = try unique(nodes) { selectorMatches($0, layout.state) }
        guard let rawPeer = string(peer, kAXValueAttribute),
              let normalized = try? Self.normalized(rawPeer.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "").replacingOccurrences(of: "(", with: "").replacingOccurrences(of: ")", with: "")),
              let value = string(state, layout.stateAttribute), let phase = layout.stateValues[value] else {
            current.loseOwnership(); lease = current; return
        }
        let scopeID = current.scopeID ?? UUID().uuidString.lowercased()
        current.observe(PhoneCallEvidence(scopeID: scopeID, peerNumber: normalized,
            outgoing: string(direction, kAXValueAttribute) == layout.outgoingValue, phase: phase, unique: true))
        if current.ownershipVerified { boundContainer = container }
        lease = current
    }
    private func stateResponse(_ current: Lease) -> [String: Any] {
        ["phase": current.phase.rawValue, "call_id": current.callID, "call_issued": current.issued,
         "carrier_ended": current.carrierEnded, "ownership_verified": current.ownershipVerified,
         "active": current.activeOwned, "control_verified": layout != nil]
    }

    private func get(_ element: AXUIElement, _ key: String) -> CFTypeRef? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, key as CFString, &value) == .success else { return nil }
        return value
    }
    private func string(_ element: AXUIElement, _ key: String) -> String? { get(element, key) as? String }
    private func descendants(_ root: AXUIElement, maxDepth: Int = 12) throws -> [AXUIElement] {
        var queue: [(AXUIElement, Int)] = [(root, 0)], result: [AXUIElement] = []
        while !queue.isEmpty {
            let (element, depth) = queue.removeFirst(); result.append(element)
            if result.count > 2048 { throw BridgeError(message: "Phone_AX_tree_bound_exceeded") }
            if depth >= maxDepth { continue }
            let children = get(element, kAXChildrenAttribute) as? [AXUIElement] ?? []
            queue.append(contentsOf: children.map { ($0, depth + 1) })
        }
        return result
    }
    private func unique(_ elements: [AXUIElement], _ predicate: (AXUIElement) -> Bool) throws -> AXUIElement {
        let matches = elements.filter(predicate)
        guard matches.count == 1 else { throw BridgeError(message: "Phone_AX_target_not_unique") }
        return matches[0]
    }
    private func application() throws -> AXUIElement {
        guard AXIsProcessTrusted() else { throw BridgeError(message: "accessibility_permission_required") }
        // Do not launch Phone, unlock the Mac or switch Apple Account in response to network commands.
        let applications = NSRunningApplication.runningApplications(withBundleIdentifier: appBundle)
        guard applications.count == 1 else { throw BridgeError(message: "Phone_must_be_running") }
        let app = AXUIElementCreateApplication(applications[0].processIdentifier)
        AXUIElementSetMessagingTimeout(app, 1)
        return app
    }
    private func press(_ element: AXUIElement) throws {
        guard (get(element, kAXEnabledAttribute) as? Bool) == true else { throw BridgeError(message: "Phone_action_disabled") }
        guard AXUIElementPerformAction(element, kAXPressAction as CFString) == .success else {
            throw BridgeError(message: "Phone_action_failed")
        }
    }
    private func popover(_ app: AXUIElement) throws -> AXUIElement {
        try unique(descendants(app)) { string($0, kAXRoleAttribute) == "AXPopover" }
    }
    private func checkDeadline(_ expiresAt: Int64) throws {
        guard Int64(Date().timeIntervalSince1970 * 1000) < expiresAt else { throw BridgeError(message: "call_lease_expired") }
    }
    static func normalized(_ number: String) throws -> String {
        guard number.utf8.count >= 7, number.utf8.count <= 16 else { throw BridgeError(message: "invalid_number") }
        let chars = Array(number.utf8)
        guard chars.enumerated().allSatisfy({ offset, c in (c >= 48 && c <= 57) || (offset == 0 && c == 43) }),
              (7...15).contains(chars.filter({ $0 >= 48 && $0 <= 57 }).count) else { throw BridgeError(message: "invalid_number") }
        return number
    }
    static func digits(_ number: String) -> String { String(number.filter { $0 >= "0" && $0 <= "9" }) }

    func snapshot() throws -> [String: Any] {
        let app = try application()
        let safeLabels = Set(["keypad", "call", "end", "end call", "hang up", "answer", "decline", "cancel", "mute", "unmute", "speaker", "audio", "hold"])
        let safeIDs = Set(["SceneWindow", "Accept", "Cancel", "End", "EndCall", "Hangup", "HangUp", "Mute", "Unmute", "Keypad", "Audio", "Decline"])
        let roles = Set(["AXWindow", "AXPopover", "AXToolbar", "AXButton", "AXMenuButton", "AXTextField"])
        var nodes: [[String: Any]] = []
        for (index, element) in try descendants(app).enumerated() {
            let role = string(element, kAXRoleAttribute) ?? ""
            if !roles.contains(role) { continue }
            var entry: [String: Any] = ["index": index, "role": role, "enabled": (get(element, kAXEnabledAttribute) as? Bool) ?? false]
            if let identifier = string(element, kAXIdentifierAttribute) {
                entry["identifier_present"] = true
                if safeIDs.contains(identifier) { entry["identifier"] = identifier }
            }
            if let description = string(element, kAXDescriptionAttribute)?.lowercased(), safeLabels.contains(description) {
                entry["control_label"] = description
            }
            nodes.append(entry)
        }
        return ["ok": true, "nodes": nodes, "text_values_read": false, "phone_numbers_returned": false, "control_verified": false]
    }

    func diagnoseCall(expectedNumber: String? = nil) throws -> [String: Any] {
        let app = try application()
        let safe = Set(["call", "end", "end call", "hang up", "answer", "decline", "cancel", "mute", "unmute", "keypad", "audio", "hold"])
        let safeIDs = Set(["SceneWindow", "Accept", "Cancel", "End", "EndCall", "Hangup", "HangUp", "Mute", "Unmute", "Keypad", "Audio", "Decline"])
        let expected = try expectedNumber.map(Self.normalized)
        var queue: [(AXUIElement, [Int])] = [(app, [])], nodes: [[String: Any]] = []
        var index = 0
        while !queue.isEmpty {
            let (element, path) = queue.removeFirst()
            guard index < 2048 else { throw BridgeError(message: "Phone_AX_tree_bound_exceeded") }
            var item: [String: Any] = ["index": index, "path": path,
                "role": string(element, kAXRoleAttribute) ?? "unknown",
                "enabled": get(element, kAXEnabledAttribute) as? Bool ?? false]
            if let identifier = string(element, kAXIdentifierAttribute) {
                item["identifier_token"] = token(identifier)
                if safeIDs.contains(identifier) { item["identifier"] = identifier }
            }
            if let description = string(element, kAXDescriptionAttribute)?.lowercased(), safe.contains(description) { item["control_label"] = description }
            let value = string(element, kAXValueAttribute) ?? ""
            let title = string(element, kAXTitleAttribute) ?? ""
            let descriptions = [value, title, string(element, kAXDescriptionAttribute) ?? ""]
            let numbers = descriptions.compactMap { text -> String? in
                let compact = text.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "").replacingOccurrences(of: "(", with: "").replacingOccurrences(of: ")", with: "")
                return try? Self.normalized(compact)
            }
            item["value_present"] = !value.isEmpty
            item["title_present"] = !title.isEmpty
            item["duration_candidate"] = descriptions.contains { $0.range(of: "^[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?$", options: .regularExpression) != nil }
            item["full_number_present"] = !numbers.isEmpty
            if let expected { item["matches_expected_number"] = numbers.contains(expected) }
            nodes.append(item); index += 1
            if path.count < 12 {
                let children = get(element, kAXChildrenAttribute) as? [AXUIElement] ?? []
                queue.append(contentsOf: children.enumerated().map { ($0.element, path + [$0.offset]) })
            }
        }
        var result: [String: Any] = ["ok": true, "nodes": nodes, "text_values_read": true, "text_values_returned": false,
            "phone_numbers_returned": false, "ui_mutated": false, "control_verified": false, "ready": false,
            "reason": "requires_setup_active_and_end_control"]
        result["audio_route"] = (try? audioRoute.snapshot(app: app)) ?? ["selection_readable": false]
        return result
    }

    func request(_ input: [String: Any]) throws -> [String: Any] {
        let action = input["action"] as? String ?? ""
        if action == "snapshot" { return try snapshot() }
        if action == "diagnose-call" { return try diagnoseCall(expectedNumber: input["expected_number"] as? String) }
        if action == "capabilities" {
            return ["control_handler": true, "accessibility_trusted": AXIsProcessTrusted(),
                    "dial_selector_implemented": true, "active_selector_verified": layout != nil,
                    "hangup_selector_verified": layout != nil, "route_lease_verified": false,
                    "control_verified": layout != nil, "ready": false, "reason": "requires_setup"]
        }
        guard let taskID = input["task_id"] as? String, UUID(uuidString: taskID) != nil else { throw BridgeError(message: "invalid_task_id") }
        if action == "prepare" {
            guard lease == nil, !seen.contains(taskID) else { throw BridgeError(message: "task_already_owned_or_seen") }
            guard let raw = input["number"] as? String, let expires = input["expires_at"] as? Int64 else { throw BridgeError(message: "invalid_prepare") }
            let number = try Self.normalized(raw)
            guard expires <= Int64(Date().timeIntervalSince1970 * 1000) + 120000 else { throw BridgeError(message: "invalid_call_lease") }
            try checkDeadline(expires)
            // Commit the one-shot tombstone BEFORE any UI mutation. A failure never automatically retries.
            guard seen.count < 4096 else { throw BridgeError(message: "native_task_tombstones_full") }
            seen.insert(taskID)
            let app = try application()
            let windows = get(app, kAXWindowsAttribute) as? [AXUIElement] ?? []
            // Phone and its Settings window share SceneWindow. Select the unique window
            // containing the observed keypad toolbar, never a Settings or Recents action.
            let window = try unique(windows) { window in
                guard string(window, kAXIdentifierAttribute) == "SceneWindow",
                      let nodes = try? descendants(window) else { return false }
                return nodes.contains { element in
                    string(element, kAXRoleAttribute) == kAXToolbarRole &&
                    ((try? descendants(element)) ?? []).contains { control in
                        string(control, kAXRoleAttribute) == kAXButtonRole &&
                        string(control, kAXDescriptionAttribute) == "Keypad"
                    }
                }
            }
            let toolbar = try unique(descendants(window)) { string($0, kAXRoleAttribute) == kAXToolbarRole }
            let keypad = try unique(descendants(toolbar)) {
                string($0, kAXRoleAttribute) == kAXButtonRole && string($0, kAXDescriptionAttribute) == "Keypad"
            }
            try press(keypad)
            let timeout = Date().addingTimeInterval(1)
            var target: AXUIElement?
            repeat {
                target = try? popover(app)
                if target != nil { break }
                Thread.sleep(forTimeInterval: 0.025)
            } while Date() < timeout
            guard let target else { throw BridgeError(message: "Phone_keypad_not_present") }
            let field = try unique(descendants(target)) { string($0, kAXRoleAttribute) == kAXTextFieldRole }
            var settable = DarwinBoolean(false)
            guard AXUIElementIsAttributeSettable(field, kAXValueAttribute as CFString, &settable) == .success,
                  settable.boolValue else { throw BridgeError(message: "Phone_keypad_not_settable") }
            guard Self.digits(string(field, kAXValueAttribute) ?? "").isEmpty else {
                throw BridgeError(message: "Phone_existing_draft_do_not_touch")
            }
            var prepared = false
            defer {
                if !prepared, let readback = string(field, kAXValueAttribute), Self.digits(readback) == Self.digits(number) {
                    _ = AXUIElementSetAttributeValue(field, kAXValueAttribute as CFString, "" as CFString)
                }
            }
            guard AXUIElementSetAttributeValue(field, kAXValueAttribute as CFString, number as CFString) == .success else {
                throw BridgeError(message: "Phone_number_entry_failed")
            }
            guard let value = string(field, kAXValueAttribute), Self.digits(value) == Self.digits(number) else {
                throw BridgeError(message: "Phone_number_readback_mismatch")
            }
            // Verify the exact popover-local action, but DO NOT press in prepare.
            _ = try unique(descendants(target)) {
                string($0, kAXRoleAttribute) == kAXButtonRole && string($0, kAXIdentifierAttribute) == "Accept" && string($0, kAXDescriptionAttribute) == "Call"
            }
            try checkDeadline(expires)
            let valueLease = Lease(taskID: taskID, callID: UUID().uuidString.lowercased(), number: number, expiresAt: expires)
            let pid = try processID(app)
            lease = valueLease
            preparedPID = pid; preparedPopover = target; preparedField = field
            prepared = true
            return ["phase": "prepared", "call_id": valueLease.callID, "call_issued": false]
        }
        guard var current = lease, current.taskID == taskID,
              input["expected_call_id"] as? String == current.callID else { throw BridgeError(message: "call_ownership_mismatch") }
        if action == "dial" {
            // No network flag can enable the unverified layout. Once implemented from real
            // evidence, consume this lease before the one and only non-idempotent AX action.
            try current.consumeDial(now: nowMs(), selectorsVerified: layout != nil)
            let app = try application(), target = try popover(app)
            guard try processID(app) == preparedPID, let preparedPopover, matches(preparedPopover, target),
                  let layout, try descendants(app).filter({ selectorMatches($0, layout.container) }).isEmpty else {
                throw BridgeError(message: "Phone_changed_or_existing_call_do_not_dial")
            }
            let field = try unique(descendants(target)) { string($0, kAXRoleAttribute) == kAXTextFieldRole }
            guard let preparedField, matches(preparedField, field) else { throw BridgeError(message: "Phone_draft_instance_changed") }
            guard let value = string(field, kAXValueAttribute), Self.digits(value) == Self.digits(current.number) else {
                throw BridgeError(message: "Phone_draft_changed_do_not_touch")
            }
            let accept = try unique(descendants(target)) {
                string($0, kAXRoleAttribute) == kAXButtonRole && string($0, kAXIdentifierAttribute) == "Accept" && string($0, kAXDescriptionAttribute) == "Call"
            }
            try checkDeadline(current.expiresAt)
            issuedPID = try processID(app); issuedAt = ProcessInfo.processInfo.systemUptime
            lease = current
            do { try press(accept) }
            catch {
                current.loseOwnership(); lease = current
                return stateResponse(current).merging(["reason": "Phone_dial_outcome_unknown_no_retry"]) { _, new in new }
            }
            return stateResponse(current)
        }
        if action == "status" {
            if current.issued {
                do { try observeOwnedCall(application()) }
                catch { current.loseOwnership(); lease = current }
            }
            return stateResponse(lease ?? current)
        }
        if action == "hangup" || action == "end" {
            let app = try application()
            try observeOwnedCall(app)
            guard var owned = lease, owned.activeOwned || (owned.ownershipVerified && [.dialing, .ringing].contains(owned.phase)),
                  let layout, let container = boundContainer else { throw BridgeError(message: "owned_call_not_confirmed") }
            let end = try unique(descendants(container)) { selectorMatches($0, layout.end) }
            try owned.consumeEnd(); lease = owned
            do { try press(end) }
            catch { owned.loseOwnership(); lease = owned }
            // An End button press is not carrier-ended evidence. status must positively confirm it.
            return stateResponse(lease ?? owned)
        }
        if action == "media-route" {
            let app = try application()
            try observeOwnedCall(app)
            guard lease?.activeOwned == true else { throw BridgeError(message: "owned_active_call_required_for_media") }
            return try audioRoute.acquire(app: app).merging(stateResponse(lease!)) { _, new in new }
        }
        if action == "restore_route" || action == "release" {
            let restored = try audioRoute.restore(app: application())
            if action == "release" { lease = nil; boundContainer = nil; issuedPID = nil; issuedAt = nil
                self.preparedPID = nil; self.preparedPopover = nil; self.preparedField = nil }
            return restored.merging(stateResponse(current)) { _, new in new }
        }
        if action == "cancel" {
            guard !current.issued else { throw BridgeError(message: "handoff_required_no_implicit_hangup") }
            let app = try application(), target = try popover(app)
            guard try processID(app) == preparedPID, let preparedPopover, matches(preparedPopover, target) else {
                throw BridgeError(message: "Phone_draft_instance_changed")
            }
            let field = try unique(descendants(target)) { string($0, kAXRoleAttribute) == kAXTextFieldRole }
            guard let preparedField, matches(preparedField, field) else { throw BridgeError(message: "Phone_draft_instance_changed") }
            guard let readback = string(field, kAXValueAttribute), Self.digits(readback) == Self.digits(current.number) else {
                throw BridgeError(message: "Phone_draft_changed_do_not_touch")
            }
            guard AXUIElementSetAttributeValue(field, kAXValueAttribute as CFString, "" as CFString) == .success else {
                throw BridgeError(message: "Phone_draft_clear_failed")
            }
            current.issued = false; lease = nil
            self.preparedPID = nil; self.preparedPopover = nil; self.preparedField = nil
            return ["phase": "canceled", "call_issued": false, "call_id": current.callID]
        }
        throw BridgeError(message: "requires_setup_action_not_verified")
    }
}

// Pure ownership state; no AX, audio, Apple Account, or network operations.
enum PhoneOwnedPhase: String { case prepared, dialing, ringing, active, ending, ended, unknown }
struct PhoneCallEvidence {
    let scopeID: String
    let peerNumber: String
    let outgoing: Bool
    let phase: PhoneOwnedPhase
    let unique: Bool
}
struct PhoneCallOwnership {
    let taskID: String
    let callID: String
    let number: String
    let expiresAt: Int64
    var issued = false
    var phase: PhoneOwnedPhase = .prepared
    private(set) var scopeID: String?
    private(set) var ownershipLost = false
    private(set) var endIssued = false

    mutating func consumeDial(now: Int64, selectorsVerified: Bool) throws {
        guard selectorsVerified else { throw BridgeError(message: "requires_setup_active_and_end_control") }
        guard !issued, phase == .prepared else { throw BridgeError(message: "call_already_issued") }
        guard now < expiresAt else { throw BridgeError(message: "call_lease_expired") }
        // This happens BEFORE AXPress. A failed/unknown AX reply never grants another attempt.
        issued = true; phase = .dialing
    }
    mutating func observe(_ evidence: PhoneCallEvidence) {
        guard issued, !ownershipLost, phase != .ended else { return }
        guard evidence.unique, evidence.outgoing, evidence.peerNumber == number,
              !evidence.scopeID.isEmpty, scopeID == nil || scopeID == evidence.scopeID else {
            loseOwnership(); return
        }
        // A terminal marker cannot establish first ownership of an unobserved call.
        guard scopeID != nil || [.dialing, .ringing, .active].contains(evidence.phase) else { loseOwnership(); return }
        if phase == .active && [.dialing, .ringing].contains(evidence.phase) { loseOwnership(); return }
        guard evidence.phase != .prepared && evidence.phase != .unknown else { loseOwnership(); return }
        scopeID = evidence.scopeID
        if phase == .ending && evidence.phase != .ended { return }
        phase = evidence.phase
    }
    mutating func loseOwnership() { if phase != .ended { ownershipLost = true; phase = .unknown } }
    mutating func consumeEnd() throws {
        guard issued, scopeID != nil, !ownershipLost, !endIssued,
              [.dialing, .ringing, .active].contains(phase) else {
            throw BridgeError(message: "owned_call_not_confirmed")
        }
        endIssued = true; phase = .ending
    }
    var activeOwned: Bool { issued && scopeID != nil && !ownershipLost && phase == .active }
    var carrierEnded: Bool { phase == .ended && scopeID != nil && !ownershipLost }
    var ownershipVerified: Bool { issued && scopeID != nil && !ownershipLost }
}

// This profile must be filled from an actual controlled Phone.app ACTIVE/end observation.
// Never populate it from a network command, an audio-setup flag, button guesses or Recents.
struct PhoneCallLayout {
    struct Selector {
        let role: String
        let identifier: String
        let description: String?
    }
    let container: Selector
    let peer: Selector
    let outgoing: Selector
    let state: Selector
    let outgoingValue: String
    let stateValues: [String: PhoneOwnedPhase]
    let end: Selector
    let stateAttribute: String
}

func controlServer() {
    let controller = PhoneController()
    while let line = readLine() {
        guard line.utf8.count <= 16384 else { json(["ok": false, "reason": "request_too_large"]); continue }
        do {
            guard let data = line.data(using: .utf8), let request = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw BridgeError(message: "invalid_request")
            }
            var response = try controller.request(request); response["ok"] = true; json(response)
        } catch {
            json(["ok": false, "reason": (error as? BridgeError)?.message ?? "controller_failed"])
        }
    }
}
