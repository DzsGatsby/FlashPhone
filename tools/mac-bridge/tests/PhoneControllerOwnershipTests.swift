import Foundation

// Link only the controller and app-local route policy, never the production audio main.
struct BridgeError: Error { let message: String }
func json(_ value: [String: Any]) { }

@main
struct PhoneControllerOwnershipTests {
    static var assertions = 0
    static func check(_ condition: Bool, _ message: String) throws {
        assertions += 1
        if !condition { throw BridgeError(message: message) }
    }
    static func rejects(_ operation: () throws -> Void, _ message: String) throws {
        var rejected = false
        do { try operation() } catch { rejected = true }
        try check(rejected, message)
    }
    static func fresh() -> PhoneCallOwnership {
        PhoneCallOwnership(taskID: "synthetic-task", callID: "synthetic-call", number: "+12025550123", expiresAt: 120_000)
    }
    static func evidence(scope: String = "synthetic-scope", peer: String = "+12025550123",
        outgoing: Bool = true, phase: PhoneOwnedPhase = .active, unique: Bool = true) -> PhoneCallEvidence {
        PhoneCallEvidence(scopeID: scope, peerNumber: peer, outgoing: outgoing, phase: phase, unique: unique)
    }
    static func main() throws {
        var unverified = fresh()
        try rejects({ try unverified.consumeDial(now: 1, selectorsVerified: false) }, "unverified_selectors_dialed")
        try check(!unverified.issued, "unverified_consumed")
        var expired = fresh()
        try rejects({ try expired.consumeDial(now: 120_000, selectorsVerified: true) }, "deadline_equality_dialed")
        try check(!expired.issued, "expired_consumed")
        var live = fresh()
        try live.consumeDial(now: 1, selectorsVerified: true)
        try check(live.issued && live.phase == .dialing && !live.activeOwned && !live.carrierEnded, "request_is_not_active")
        try rejects({ try live.consumeDial(now: 2, selectorsVerified: true) }, "duplicate_dial_permitted")
        try rejects({ try live.consumeEnd() }, "unbound_global_end_permitted")
        live.observe(evidence(phase: .ringing))
        try check(live.phase == .ringing && live.ownershipVerified && !live.activeOwned, "ringing_misreported")
        live.observe(evidence())
        try check(live.activeOwned && !live.carrierEnded, "active_not_bound")
        try live.consumeEnd()
        try check(live.phase == .ending && !live.carrierEnded, "button_press_called_ended")
        try rejects({ try live.consumeEnd() }, "duplicate_end_permitted")
        live.observe(evidence(phase: .active))
        try check(live.phase == .ending, "stale_active_reenabled_end")
        live.observe(evidence(phase: .ended))
        try check(live.carrierEnded, "positive_end_missing")
        live.observe(evidence(scope: "new-call"))
        try check(live.carrierEnded, "later_call_replaced_terminal_result")
        for altered in [evidence(peer: "+12025550124"), evidence(peer: "5550123"), evidence(outgoing: false),
                        evidence(unique: false), evidence(scope: ""), evidence(phase: .ended)] {
            var owned = fresh(); try owned.consumeDial(now: 1, selectorsVerified: true)
            owned.observe(altered)
            try check(owned.ownershipLost && !owned.activeOwned && !owned.carrierEnded, "foreign_or_terminal_claimed")
            owned.observe(evidence())
            try check(!owned.activeOwned, "lost_ownership_reacquired")
            try rejects({ try owned.consumeEnd() }, "foreign_call_hung_up")
        }
        var swapped = fresh(); try swapped.consumeDial(now: 1, selectorsVerified: true)
        swapped.observe(evidence()); swapped.observe(evidence(scope: "replacement"))
        try check(swapped.ownershipLost && !swapped.activeOwned, "same_peer_new_container_accepted")
        var vanished = fresh(); try vanished.consumeDial(now: 1, selectorsVerified: true)
        vanished.observe(evidence()); vanished.loseOwnership()
        try check(vanished.phase == .unknown && !vanished.carrierEnded, "window_disappearance_called_ended")
        var stale = fresh(); try stale.consumeDial(now: 1, selectorsVerified: true)
        stale.observe(evidence()); stale.observe(evidence(phase: .ringing))
        try check(stale.ownershipLost, "stale_phase_regression_accepted")
        for bad in ["911", "++12025550123", "12025550123;", "１２３４５６７８９０", "1234567890123456"] {
            try rejects({ _ = try PhoneController.normalized(bad) }, "unsafe_number_accepted")
        }
        try check(try PhoneController.normalized("+12025550123") == "+12025550123", "valid_number_rejected")
        try PhoneAudioRouteLease.selftest()
        print("Phone ownership offline assertions passed: \(assertions); no AX calls, no Phone UI, no audio devices")
    }
}
