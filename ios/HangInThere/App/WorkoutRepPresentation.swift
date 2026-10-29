import Foundation

struct WorkoutKeyframeMarker: Codable, Equatable, Identifiable, Sendable {
    let repId: String
    let ordinal: Int
    let phase: String
    let sourceSeconds: Double
    let clipSeconds: Double
    var id: String { repId + "-" + phase }
    var filename: String { "\(ordinal)-\(phase).jpg" }

    static func markers(record: WorkoutSetRecord, clipStart: Double) -> [Self] {
        guard clipStart.isFinite, let end = record.completedReps.compactMap(\.returnedAt).max(), end.isFinite else { return [] }
        return record.completedReps.prefix(60).flatMap { rep in
            [("Start", Optional(rep.startedAt)), (rep.exercise == .pullUp ? "Top" : "Bottom", rep.extremeAt), ("Return", rep.returnedAt)].compactMap { phase, time in
                guard let time, time.isFinite, time >= clipStart, time <= end else { return nil }
                return Self(repId: rep.id, ordinal: rep.ordinal, phase: phase, sourceSeconds: time, clipSeconds: time - clipStart)
            }
        }
    }
}

/// Describes observed timing, never muscle fatigue or physiological effort.
struct WorkoutTempo: Sendable {
    struct Rep: Identifiable, Sendable {
        let id: String
        let ordinal: Int
        let outward: Double
        let returning: Double
        let lifting: Double
        let range: Double?
        let reliable: Bool
    }
    let reps: [Rep]
    let change: Double?
    let comparisonNote: String

    init(record: WorkoutSetRecord) {
        reps = record.completedReps.sorted { $0.ordinal < $1.ordinal }.compactMap { rep in
            guard let turn = rep.extremeAt, let returned = rep.returnedAt else { return nil }
            let outward = turn - rep.startedAt, returning = returned - turn
            guard outward.isFinite, returning.isFinite, outward > 0, returning > 0 else { return nil }
            let angles = [rep.left, rep.right].compactMap { metric -> Double? in
                guard let min = metric.minimum, let max = metric.maximum, min.isFinite, max.isFinite, max > min,
                      (metric.coverage ?? 0) >= 0.8 else { return nil }
                return max - min
            }
            let range = angles.isEmpty ? nil : angles.reduce(0, +) / Double(angles.count)
            return Rep(id: rep.id, ordinal: rep.ordinal, outward: outward, returning: returning,
                       lifting: record.exercise == .pullUp ? outward : returning,
                       range: range, reliable: (rep.durationCoverage ?? 0) >= 0.8 && !rep.curveTruncated)
        }
        guard reps.count >= 3 else {
            change = nil; comparisonNote = "Three comparable reps are needed to show a tempo trend."; return
        }
        let baseline = Array(reps.prefix(2))
        guard let last = reps.last, (baseline + [last]).allSatisfy({ $0.reliable && ($0.range ?? 0) >= 20 }),
              let firstRange = baseline[0].range, let secondRange = baseline[1].range, let lastRange = last.range else {
            change = nil; comparisonNote = "Tracking coverage is insufficient for a tempo comparison."; return
        }
        let referenceRange = (firstRange + secondRange) / 2
        guard [firstRange, secondRange, lastRange].allSatisfy({ abs($0 / referenceRange - 1) <= 0.2 }) else {
            change = nil; comparisonNote = "Movement range changed; these reps aren't comparable."; return
        }
        let reference = baseline.map(\.lifting).reduce(0, +) / 2
        change = last.lifting / reference - 1
        comparisonNote = "Last rep versus the first two, with similar observed range. Intentional pacing can also change tempo."
    }
}
