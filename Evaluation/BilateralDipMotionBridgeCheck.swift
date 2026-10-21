import Foundation
import WorkoutCore

/// Real Native ABI, observed image cycles explicitly distinct from policy-8 reps.
enum BilateralDipMotionBridgeCheck {
    static func run() throws -> Int {
        let support = try CurvedDipRailAcquisitionBridgeCheck.acquiredReference()
        func replay(_ drops: [(Double, Double)], camera: String = "ready") throws -> [String: Any] {
            let bridge = BilateralDipMotionBridge()
            var result: [String: Any] = [:]
            for (index, drop) in drops.enumerated() {
                let pose = BilateralGripDisplacementBridgeCheck.frame(ticks: 1000 + index * 200,
                    leftDrop: drop.0, rightDrop: drop.1)
                let request: [String: Any] = ["pose": pose, "support": support, "camera": camera]
                let json = String(decoding: try JSONSerialization.data(withJSONObject: request), as: UTF8.self)
                let output = try bridge.observeJson(input: json)
                result = try JSONSerialization.jsonObject(with: Data(output.utf8)) as! [String: Any]
            }
            return result
        }
        let cycle = [(0.0, 0.0), (0, 0), (100, 100), (100, 100), (0, 0), (0, 0)]
        let valid = try replay(cycle)
        precondition(valid["imageCycles"] as! Int == 1 && valid["policyVersion"] as! Int == 1)
        precondition(valid["observedMovements"] == nil)
        precondition((valid["event"] as! [String: Any])["reason"] as! String == "bilateralImageCycleDepthFormAndLoadUnverified")
        let unfinished = try replay(Array(cycle.prefix(4)))
        precondition(unfinished["imageCycles"] as! Int == 0)
        let oneArm = try replay(cycle.map { ($0.0, 0.0) })
        precondition(oneArm["imageCycles"] as! Int == 0)
        let asymmetric = try replay(Array(cycle.prefix(4)) + [(0, 100), (100, 100), (0, 0), (0, 0)])
        precondition(asymmetric["imageCycles"] as! Int == 0 && asymmetric["interruptedAttempts"] as! Int == 1)
        let cameraUnavailable = try replay(cycle, camera: "unavailable")
        precondition(cameraUnavailable["imageCycles"] as! Int == 0)
        let moved = try replay(cycle, camera: "moved")
        precondition(moved["imageCycles"] as! Int == 0)
        let bridge = BilateralDipMotionBridge()
        do {
            _ = try bridge.observeJson(input: "{}")
            fatalError("Malformed motion transport was accepted")
        } catch { }
        return 7
    }
}
