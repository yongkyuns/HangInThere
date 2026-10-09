// Reference policy oracle: compiles the existing production Swift engine unchanged.
import Foundation

private struct Step: Codable {
    let pose: PoseResult?
    let interrupt: String?
}
private struct Case: Codable {
    let id: String
    let exercise: ExerciseCounter.Exercise
    let side: ArmMeasurement.Side
    let referenceEdge: BarSegment?
    let expectedMovements: Int
    let steps: [Step]
}
private struct Snapshot: Encodable {
    let summary: ExerciseCounter.Summary
    let event: ExerciseCounter.Event?
    let pullUpProgress: PullUpProgress?
    let dipProgress: DipProgress?
    enum CodingKeys: String, CodingKey { case summary, event, pullUpProgress, dipProgress }
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(summary, forKey: .summary)
        try container.encodeIfPresent(event, forKey: .event)
        struct Progress: Encodable {
            let stage: String; let repNumber: Int; let fraction: Double
            let resetFraction: Double?; let cue: String; let trackedArmCount: Int
        }
        if let p = pullUpProgress {
            try container.encode(Progress(stage: String(describing: p.stage), repNumber: p.repNumber,
                fraction: p.fraction, resetFraction: p.resetFraction, cue: String(describing: p.cue),
                trackedArmCount: p.trackedArmCount), forKey: .pullUpProgress)
        }
        if let p = dipProgress {
            try container.encode(Progress(stage: String(describing: p.stage), repNumber: p.repNumber,
                fraction: p.fraction, resetFraction: nil, cue: String(describing: p.cue),
                trackedArmCount: p.trackedArmCount), forKey: .dipProgress)
        }
    }
}
private struct Result: Encodable {
    let input: Case
    let snapshots: [Snapshot]
    let final: Snapshot
}
private struct Report: Encodable {
    let policyVersion: Int
    let cases: [Result]
    let benchmark: Benchmark
}
private struct Benchmark: Encodable {
    let scope = "same-host core only; includes counter initialization, excludes pose inference and camera"
    let cases: Int
    let iterations: Int
    let framesPerIteration: Int
    let p50NanosecondsPerFrame: Double
    let p95NanosecondsPerFrame: Double
    let checksum: Double
}
@main private enum WorkoutOracle {
    static func run(_ input: Case) -> Result {
        var counter = ExerciseCounter(exercise: input.exercise, side: input.side)
        var snapshots: [Snapshot] = []
        for step in input.steps {
            let event: ExerciseCounter.Event?
            if let reason = step.interrupt { event = counter.interrupt(reason: reason) }
            else if let pose = step.pose { event = counter.consume(pose, referenceEdge: input.referenceEdge) }
            else { fatalError("Empty oracle step") }
            snapshots.append(Snapshot(summary: counter.summary, event: event,
                pullUpProgress: counter.pullUpProgress, dipProgress: counter.dipProgress))
        }
        let event = counter.finish()
        precondition(counter.observedMovements == input.expectedMovements,
            "Ground truth mismatch: \(input.id) expected \(input.expectedMovements), got \(counter.observedMovements)")
        return Result(input: input, snapshots: snapshots, final: Snapshot(summary: counter.summary,
            event: event, pullUpProgress: counter.pullUpProgress, dipProgress: counter.dipProgress))
    }
    static func main() throws {
        let cases = try JSONDecoder().decode([Case].self,
            from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
        precondition(cases.count >= 10)
        let results = cases.map(run)
        let frames = cases.reduce(0) { $0 + $1.steps.count }
        for _ in 0..<20 { for input in cases { _ = run(input) } }
        var timings: [Double] = []
        var checksum = 0.0
        for _ in 0..<100 {
            let start = DispatchTime.now().uptimeNanoseconds
            for input in cases {
                let result = run(input)
                for snapshot in result.snapshots {
                    checksum += Double(snapshot.summary.observedMovements)
                        + (snapshot.pullUpProgress?.fraction ?? 0) + (snapshot.dipProgress?.fraction ?? 0)
                }
            }
            timings.append(Double(DispatchTime.now().uptimeNanoseconds - start) / Double(frames))
        }
        timings.sort()
        let report = Report(policyVersion: ExerciseCounter.policyVersion, cases: results,
            benchmark: Benchmark(cases: cases.count, iterations: timings.count, framesPerIteration: frames,
                p50NanosecondsPerFrame: timings[50], p95NanosecondsPerFrame: timings[95], checksum: checksum))
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        print(String(decoding: try encoder.encode(report), as: UTF8.self))
    }
}
