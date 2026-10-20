import Foundation
import WorkoutCore

/// Exercise the actual exported Swift/Native API and native storage DTOs, not
/// a second implementation of motion qualification or descriptive metrics.
enum BilateralDipWorkoutBridgeCheck {
    static func run() throws -> Int {
        let support = try CurvedDipRailAcquisitionBridgeCheck.acquiredReference()
        func replay(_ drops: [Double], movedAt: Int? = nil) throws -> WorkoutSetRecord {
            let bridge = BilateralDipWorkoutBridge(setId: "native-workout-check", trackedAtEpochMilliseconds: 0)
            for (index, drop) in drops.enumerated() {
                let pose = BilateralGripDisplacementBridgeCheck.frame(ticks: 1000 + index * 200,
                    leftDrop: drop, rightDrop: drop)
                let input: [String: Any] = ["pose": pose, "support": support,
                    "camera": index == movedAt ? "moved" : "ready", "automaticFinish": false]
                _ = try bridge.observeJson(input: String(decoding: JSONSerialization.data(withJSONObject: input), as: UTF8.self))
            }
            bridge.finish(reason: "sourceEnded")
            let json = bridge.setJson(includeActive: false)
            let library = WorkoutLibraryBridge()
            _ = try library.registerJson(stateJson: library.emptyJson(), recordsJson: "[" + json + "]")
            return try JSONDecoder().decode(WorkoutSetRecord.self, from: Data(json.utf8))
        }
        let complete = try replay([0, 0, 100, 120, 0, 0])
        precondition(complete.schemaVersion == 2 && complete.completedCount == 1)
        precondition(complete.countMethod == "bilateralDipImageMotion" && complete.countPolicyVersion == 1)
        precondition(complete.reps[0].countMethod == complete.countMethod && complete.reps[0].countPolicyVersion == 1)
        precondition(complete.reps[0].extremeAt == 1.6)
        precondition(complete.reps[0].left.mean! <= 180 && complete.reps[0].right.mean! <= 180)
        let completeReport = try complete.afterSetReport()
        precondition(completeReport.practiceAttempts == 0)
        let unfinished = try replay([0, 0, 100, 120])
        precondition(unfinished.completedCount == 0 && unfinished.reps[0].acceptedAt == nil)
        let interrupted = try replay([0, 0, 100, 120, 0], movedAt: 4)
        precondition(interrupted.completedCount == 0)
        let interruptedReport = try interrupted.afterSetReport()
        precondition(interruptedReport.practiceAttempts == 0)
        func feedback(_ pose: [String: Any], support: [String: Any]? = nil) throws -> DipWorkoutSnapshot {
            let engine = BilateralDipWorkoutBridge(setId: "native-feedback-check", trackedAtEpochMilliseconds: 0)
            let input: [String: Any] = ["pose": pose, "support": support as Any? ?? NSNull(), "camera": "unavailable"]
            let json = try engine.observeJson(input: String(decoding: JSONSerialization.data(withJSONObject: input), as: UTF8.self))
            return try JSONDecoder().decode(DipWorkoutSnapshot.self, from: Data(json.utf8))
        }
        var noHands = BilateralGripDisplacementBridgeCheck.frame(ticks: 1000)
        noHands.removeValue(forKey: "gripObservation")
        let handFeedback = try feedback(noHands)
        precondition(handFeedback.observationGuidance == "Hand tracking unavailable · show both grips clearly")
        let railFeedback = try feedback(BilateralGripDisplacementBridgeCheck.frame(ticks: 1000))
        precondition(railFeedback.observationGuidance == "Looking for dip rails · keep grips and rails visible")
        let cameraFeedback = try feedback(BilateralGripDisplacementBridgeCheck.frame(ticks: 1000), support: support)
        precondition(cameraFeedback.observationGuidance == "Camera stability unavailable · keep the phone steady")
        var legacy = try JSONSerialization.jsonObject(with: JSONEncoder().encode(railFeedback)) as! [String: Any]
        legacy.removeValue(forKey: "observationGuidance")
        let oldSnapshot = try JSONDecoder().decode(DipWorkoutSnapshot.self, from: JSONSerialization.data(withJSONObject: legacy))
        precondition(oldSnapshot.observationGuidance == nil && oldSnapshot.motion == railFeedback.motion)
        let goalJSON = try WorkoutProgressBridge().goalForMethodJson(exercise: "dip", metric: "completedReps",
            target: 2, createdAtEpochMilliseconds: 1000, countMethod: complete.countMethod, countPolicyVersion: 1)
        let goal = try JSONDecoder().decode(WorkoutGoalRecord.self, from: Data(goalJSON.utf8))
        precondition(goal.countMethod == complete.countMethod && goal.countPolicyVersion == 1)
        let bridge = BilateralDipWorkoutBridge(setId: "native-workout-check", trackedAtEpochMilliseconds: 0)
        do {
            _ = try bridge.observeJson(input: "{}")
            fatalError("Malformed workout transport was accepted")
        } catch { }
        return 9
    }
}
