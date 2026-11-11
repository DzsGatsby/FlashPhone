import Foundation
import AppKit
import ApplicationServices

// Deliberately narrow Phone.app AX adapter. No coordinates, global keystrokes or Recents actions.
final class PhoneController {
    struct Lease {
        let taskID: String
        let callID: String
        let number: String
        let expiresAt: Int64
        var issued = false
    }
    private var lease: Lease?
    private var seen = Set<String>()
    private let appBundle = "com.apple.mobilephone"

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
        guard number.utf8.count >= 3, number.utf8.count <= 24 else { throw BridgeError(message: "invalid_number") }
        let chars = Array(number.utf8)
        guard chars.enumerated().allSatisfy({ offset, c in (c >= 48 && c <= 57) || (offset == 0 && c == 43) }),
              chars.filter({ $0 >= 48 && $0 <= 57 }).count >= 3 else { throw BridgeError(message: "invalid_number") }
        return number
    }
    static func digits(_ number: String) -> String { String(number.filter { $0 >= "0" && $0 <= "9" }) }

    func snapshot() throws -> [String: Any] {
        let app = try application()
        let safeLabels = Set(["keypad", "call", "end", "end call", "hang up", "answer", "decline", "cancel", "mute", "unmute", "speaker", "audio", "hold"])
        let safeIDs = Set(["windowSceneWindow", "Accept", "Cancel", "End", "EndCall", "Hangup", "HangUp", "Mute", "Unmute", "Keypad", "Audio", "Decline"])
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

    func request(_ input: [String: Any]) throws -> [String: Any] {
        let action = input["action"] as? String ?? ""
        if action == "snapshot" { return try snapshot() }
        if action == "capabilities" {
            return ["control_handler": true, "accessibility_trusted": AXIsProcessTrusted(),
                    "dial_selector_implemented": true, "active_selector_verified": false,
                    "hangup_selector_verified": false, "control_verified": false, "ready": false, "reason": "requires_setup"]
        }
        guard let taskID = input["task_id"] as? String, UUID(uuidString: taskID) != nil else { throw BridgeError(message: "invalid_task_id") }
        if action == "prepare" {
            guard lease == nil, !seen.contains(taskID) else { throw BridgeError(message: "task_already_owned_or_seen") }
            guard let raw = input["number"] as? String, let expires = input["expires_at"] as? Int64 else { throw BridgeError(message: "invalid_prepare") }
            let number = try Self.normalized(raw)
            guard expires <= Int64(Date().timeIntervalSince1970 * 1000) + 120000 else { throw BridgeError(message: "invalid_call_lease") }
            try checkDeadline(expires)
            // Commit the one-shot tombstone BEFORE any UI mutation. A failure never automatically retries.
            seen.insert(taskID)
            let app = try application()
            let windows = get(app, kAXWindowsAttribute) as? [AXUIElement] ?? []
            let window = try unique(windows) { string($0, kAXIdentifierAttribute) == "windowSceneWindow" }
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
            lease = valueLease; prepared = true
            return ["phase": "prepared", "call_id": valueLease.callID, "call_issued": false]
        }
        guard var current = lease, current.taskID == taskID,
              input["expected_call_id"] as? String == current.callID else { throw BridgeError(message: "call_ownership_mismatch") }
        if action == "dial" {
            // Active/end selectors remain unverified; this prototype MUST NOT issue a call yet.
            // After CUA verification, this branch can consume the lease before pressing popover Accept.
            try checkDeadline(current.expiresAt)
            guard !current.issued else { throw BridgeError(message: "call_already_issued") }
            throw BridgeError(message: "requires_setup_active_and_end_control")
        }
        if action == "cancel" {
            guard !current.issued else { throw BridgeError(message: "handoff_required_no_implicit_hangup") }
            let app = try application(), target = try popover(app)
            let field = try unique(descendants(target)) { string($0, kAXRoleAttribute) == kAXTextFieldRole }
            guard let readback = string(field, kAXValueAttribute), Self.digits(readback) == Self.digits(current.number) else {
                throw BridgeError(message: "Phone_draft_changed_do_not_touch")
            }
            guard AXUIElementSetAttributeValue(field, kAXValueAttribute as CFString, "" as CFString) == .success else {
                throw BridgeError(message: "Phone_draft_clear_failed")
            }
            current.issued = false; lease = nil
            return ["phase": "canceled", "call_issued": false, "call_id": current.callID]
        }
        throw BridgeError(message: "requires_setup_action_not_verified")
    }
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
