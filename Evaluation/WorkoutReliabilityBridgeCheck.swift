import Foundation
import WorkoutCore

/// Actual Swift -> Kotlin adapter checks with authored poses. No vision/model or
/// physical-device accuracy claim. Both apps consume the same recovery policy.
enum WorkoutReliabilityBridgeCheck {
    private static func pose(_ ticks: Int64, angle: Double = 170, exercise: ExerciseCounter.Exercise,
                             missing: ArmMeasurement.Side? = nil) -> PoseResult {
        let half = angle * .pi / 360
        let y = 120 * sin(half) * (exercise == .pullUp ? 1.0 : -1.0)
        var landmarks: [Landmark] = []
        for side in ArmMeasurement.Side.allCases where side != missing {
            let x = side == .left ? 300.0 : 700.0
            let joints: [PoseJoint] = side == .left ? [.leftShoulder, .leftElbow, .leftWrist] : [.rightShoulder, .rightElbow, .rightWrist]
            let points = [Point2D(x: x, y: 500 + 2 * y), Point2D(x: x + 120 * cos(half), y: 500 + y), Point2D(x: x, y: 500)]
            landmarks += zip(joints, points).map { Landmark(joint: $0, position: $1, confidence: 1) }
        }
        return PoseResult(timestamp: .init(value: ticks, timescale: 1_000_000), imageSize: .init(width: 1000, height: 1000),
            people: [PoseObservation(landmarks: landmarks)], backend: "authored reliability transport", requestRevision: 0)
    }

    @MainActor static func run() throws {
        var cases = 0
        for exercise in ExerciseCounter.Exercise.allCases {
            let adapter = WorkoutAnalysisAdapter()
            for missing in ArmMeasurement.Side.allCases {
                adapter.resetSetupRecovery()
                precondition(!adapter.retainSetup(pose(1_100_000, exercise: exercise), exercise: exercise))
                precondition(adapter.retainSetup(pose(1_200_000, exercise: exercise, missing: missing), exercise: exercise))
                precondition(adapter.retainSetup(pose(1_450_000, exercise: exercise, missing: missing), exercise: exercise))
                precondition(!adapter.retainSetup(pose(1_450_001, exercise: exercise, missing: missing), exercise: exercise))
                precondition(!adapter.retainSetup(pose(1_460_000, exercise: exercise, missing: missing), exercise: exercise))
                cases += 1
            }
            adapter.start(exercise: exercise, recordCalendarTime: false)
            var counter = ExerciseCounter(exercise: exercise)
            let edge = BarSegment(a: Point2D(x: 100, y: 500), b: Point2D(x: 900, y: 500))
            for (ticks, angle, missing): (Int64, Double, ArmMeasurement.Side?) in [
                (0,170,nil),(150_000,170,nil),(300_000,130,nil),
                (450_000,80,.right),(600_000,80,.right),(750_000,120,nil),
                (900_000,170,nil),(1_050_000,170,nil)
            ] {
                let frame = pose(ticks, angle: angle, exercise: exercise, missing: missing)
                let count = counter.events.count
                counter.consume(frame, referenceEdge: edge)
                _ = adapter.consume(frame, reference: edge, counter: counter,
                    event: counter.events.count > count ? counter.events.last : nil, automaticFinish: false)
            }
            let set = try require(adapter.finish(reason: "manual"))
            let rep = try require(set.reps.first)
            precondition(set.completedCount == 1 && counter.observedMovements == 1)
            precondition(rep.extremeAt == nil && rep.outboundSeconds == nil && rep.returnSeconds == nil)
            precondition(rep.metricVersion == 2 && set.activity?.metricVersion == 3)
            precondition(rep.left.minimum != nil && rep.right.minimum != nil && rep.returnedAt != nil)
            cases += 1
        }
        let adapter = WorkoutAnalysisAdapter()
        var latencies: [Double] = []
        for i in 0..<2000 {
            let missing: ArmMeasurement.Side? = i % 4 == 0 ? nil : .right
            let frame = pose(Int64(i) * 40_000, exercise: .pullUp, missing: missing)
            let start = ContinuousClock.now
            let retained = adapter.retainSetup(frame, exercise: .pullUp)
            let elapsed = start.duration(to: .now).components
            latencies.append(Double(elapsed.seconds) * 1000 + Double(elapsed.attoseconds) / 1e15)
            precondition(retained == (missing != nil))
        }
        latencies.sort()
        let p95 = latencies[Int(Double(latencies.count - 1) * 0.95)]
        let report: [String: Any] = ["cases": cases, "frames": latencies.count,
            "setupRecoveryHostBridgeP95Milliseconds": p95, "passed": p95 < 5,
            "scope": "Authored source-clock recovery and observed-metric Swift/shared ABI regressions; no native vision or physical performance qualification"]
        let data = try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
        try data.write(to: URL(fileURLWithPath: "build/workout-analysis/reliability-bridge-check.json"), options: .atomic)
        print(String(decoding: data, as: UTF8.self))
        precondition(p95 < 5, "Setup recovery exceeds the existing 5 ms host FFI budget")
    }

    private static func require<T>(_ value: T?) throws -> T {
        guard let value else { throw NSError(domain: "ReliabilityBridge", code: 1) }
        return value
    }
}
