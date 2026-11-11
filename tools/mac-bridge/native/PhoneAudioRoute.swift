import Foundation
import ApplicationServices

// Application-local microphone routing. Call ownership is enforced by PhoneController.
// Never writes a CoreAudio default or touches the separate Output menu section.
final class PhoneAudioRouteLease {
    struct Choice: Equatable {
        let identifier: String
        let marked: Bool
    }
    struct Saved {
        let previous: String
        let changed: Bool
    }
    private var saved: Saved?
    private let target = "filmage_audio_device"

    static func selected(_ choices: [Choice]) throws -> String {
        guard !choices.isEmpty,
              Set(choices.map { $0.identifier }).count == choices.count,
              choices.allSatisfy({ !$0.identifier.isEmpty }) else {
            throw BridgeError(message: "Phone_microphone_choices_ambiguous")
        }
        let selected = choices.filter { $0.marked }
        guard selected.count == 1 else { throw BridgeError(message: "Phone_microphone_selection_unreadable") }
        return selected[0].identifier
    }
    // Restoration uses compare-and-set: a manual route change always wins.
    static func restoration(previous: String, current: String, target: String, changed: Bool) -> String? {
        changed && current == target && previous != target ? previous : nil
    }
    private func get(_ element: AXUIElement, _ key: String) -> CFTypeRef? {
        var value: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, key as CFString, &value) == .success else { return nil }
        return value
    }
    private func string(_ element: AXUIElement, _ key: String) -> String? { get(element, key) as? String }
    private func children(_ element: AXUIElement) -> [AXUIElement] { get(element, kAXChildrenAttribute) as? [AXUIElement] ?? [] }
    private func unique(_ elements: [AXUIElement], _ predicate: (AXUIElement) -> Bool) throws -> AXUIElement {
        let matches = elements.filter(predicate)
        guard matches.count == 1 else { throw BridgeError(message: "Phone_audio_menu_not_unique") }
        return matches[0]
    }
    private func menuItem(_ app: AXUIElement) throws -> AXUIElement {
        guard let bar = get(app, kAXMenuBarAttribute) else { throw BridgeError(message: "Phone_menu_bar_missing") }
        return try unique(children(bar as! AXUIElement)) {
            string($0, kAXIdentifierAttribute) == "com.apple.facetime.menu.video"
        }
    }
    private func section(_ item: AXUIElement) throws -> [AXUIElement] {
        let menu = try unique(children(item)) { string($0, kAXRoleAttribute) == kAXMenuRole }
        let entries = children(menu)
        // These exact section headers and identifiers were observed in Phone 26's Video menu.
        // Locale/version changes fail closed rather than selecting a same-name output device.
        let starts = entries.indices.filter { string(entries[$0], kAXTitleAttribute) == "Microphone" }
        let ends = entries.indices.filter { string(entries[$0], kAXTitleAttribute) == "Output" }
        guard starts.count == 1, ends.count == 1, starts[0] < ends[0] else {
            throw BridgeError(message: "Phone_microphone_section_not_observed")
        }
        let items = Array(entries[(starts[0] + 1)..<ends[0]])
        guard items.count > 0, items.count <= 32 else { throw BridgeError(message: "Phone_microphone_section_bound") }
        return items
    }
    private func current(_ items: [AXUIElement]) throws -> String {
        try Self.selected(items.map {
            Choice(identifier: string($0, kAXIdentifierAttribute) ?? "",
                   marked: !(string($0, kAXMenuItemMarkCharAttribute) ?? "").isEmpty)
        })
    }
    private func opened<T>(_ app: AXUIElement, _ body: (AXUIElement) throws -> T) throws -> T {
        let item = try menuItem(app)
        guard (get(item, kAXEnabledAttribute) as? Bool) == true,
              AXUIElementPerformAction(item, kAXPressAction as CFString) == .success else {
            throw BridgeError(message: "Phone_audio_menu_open_failed")
        }
        defer { _ = AXUIElementPerformAction(item, kAXCancelAction as CFString) }
        return try body(item)
    }
    private func select(_ identifier: String, _ item: AXUIElement) throws {
        let control = try unique(section(item)) { string($0, kAXIdentifierAttribute) == identifier }
        guard (get(control, kAXEnabledAttribute) as? Bool) == true,
              AXUIElementPerformAction(control, kAXPressAction as CFString) == .success else {
            throw BridgeError(message: "Phone_microphone_select_failed")
        }
    }
    func snapshot(app: AXUIElement) throws -> [String: Any] {
        // No click or menu opening in a diagnostic snapshot.
        let items = try section(menuItem(app))
        return ["selection_readable": true, "microphone_identifier": try current(items),
                "dedicated_input_present": items.filter { string($0, kAXIdentifierAttribute) == target }.count == 1,
                "route_lease_active": saved != nil, "defaults_changed": false]
    }
    func acquire(app: AXUIElement) throws -> [String: Any] {
        guard saved == nil else { throw BridgeError(message: "Phone_audio_route_already_leased") }
        let previous = try opened(app) { item -> String in
            let selected = try current(section(item))
            // Save before mutation, including an uncertain/failed AXPress response.
            saved = Saved(previous: selected, changed: selected != target)
            if selected != target { try select(target, item) }
            return selected
        }
        // A successful AXPress is not a readback: obtain a new menu and compare selection.
        let readback = try opened(app) { try current(section($0)) }
        guard readback == target else { throw BridgeError(message: "Phone_microphone_readback_mismatch") }
        return ["route_leased": true, "microphone_readback_verified": true,
                "changed": previous != target, "defaults_changed": false, "phone_tx_verified": false]
    }
    func restore(app: AXUIElement) throws -> [String: Any] {
        guard let prior = saved else { return ["route_restored": true, "changed": false] }
        var changed = false
        try opened(app) { item in
            let actual = try current(section(item))
            if let destination = Self.restoration(previous: prior.previous, current: actual, target: target, changed: prior.changed) {
                try select(destination, item); changed = true
            }
        }
        if changed {
            let actual = try opened(app) { try current(section($0)) }
            guard actual == prior.previous else { throw BridgeError(message: "Phone_microphone_restore_readback_mismatch") }
        }
        saved = nil
        return ["route_restored": true, "changed": changed, "defaults_changed": false]
    }
    static func selftest() throws {
        let choices = [Choice(identifier: "use_system_setting", marked: true), Choice(identifier: "filmage_audio_device", marked: false)]
        guard try selected(choices) == "use_system_setting",
              restoration(previous: "use_system_setting", current: "filmage_audio_device", target: "filmage_audio_device", changed: true) == "use_system_setting",
              restoration(previous: "use_system_setting", current: "blackhole_2ch", target: "filmage_audio_device", changed: true) == nil,
              restoration(previous: "filmage_audio_device", current: "filmage_audio_device", target: "filmage_audio_device", changed: false) == nil else {
            throw BridgeError(message: "audio_route_policy_failed")
        }
        for invalid in [[], [Choice(identifier: "x", marked: false)], [Choice(identifier: "x", marked: true), Choice(identifier: "y", marked: true)], [Choice(identifier: "x", marked: true), Choice(identifier: "x", marked: false)]] {
            do { _ = try selected(invalid); throw BridgeError(message: "ambiguous_audio_route_accepted") }
            catch let error as BridgeError { if error.message == "ambiguous_audio_route_accepted" { throw error } }
        }
    }
}
