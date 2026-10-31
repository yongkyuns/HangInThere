import Foundation
import WorkoutCore

/// Version 1: relative demand from observed phase time, not measured muscle fatigue.
/// No personal capacity, force, calories or between-set carryover is inferred.
struct WorkoutDemand: Codable, Sendable {
    struct Point: Codable, Identifiable, Sendable {
        let id: Int
        let seconds: Double
        let units: Double
        let segment: Int
    }
    static let recoverySeconds = 60.0 // Prototype assumption, not a measured recovery rate.
    let points: [Point]
    let recovery: [Point]
    let units: Double
    let isPartial: Bool
    let hasGaps: Bool

    static func weight(for phase: String) -> Double? {
        WorkoutSetPresentationBridge().weight(phase: phase)?.doubleValue
    }

    static func recovered(_ units: Double, after seconds: Double) -> Double {
        WorkoutSetPresentationBridge().recovered(units: units, seconds: seconds)
    }

    init(activity: WorkoutActivity?) {
        self = sharedPresentation(activity) { WorkoutSetPresentationBridge().demandJson(activityJson: $0) }
    }
}

struct WorkoutKeyframeMarker: Codable, Equatable, Identifiable, Sendable {
    let repId: String
    let ordinal: Int
    let phase: String
    let sourceSeconds: Double
    let clipSeconds: Double
    var id: String { repId + "-" + phase }
    var filename: String { "\(ordinal)-\(phase).jpg" }

    static func markers(record: WorkoutSetRecord, clipStart: Double) -> [Self] {
        sharedPresentation(record) { WorkoutSetPresentationBridge().keyframesJson(recordJson: $0, clipStart: clipStart) }
    }
}

/// Describes observed timing, never muscle fatigue or physiological effort.
struct WorkoutTempo: Codable, Sendable {
    struct Rep: Codable, Identifiable, Sendable {
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
        self = sharedPresentation(record) { WorkoutSetPresentationBridge().tempoJson(recordJson: $0) }
    }
}

// These DTOs contain already validated, locally stored records. A codec mismatch
// is a programming error; never quietly substitute a fabricated analysis.
private func sharedPresentation<Input: Encodable, Output: Decodable>(_ input: Input,
    _ calculate: (String) -> String) -> Output {
    let encoder = JSONEncoder()
    encoder.nonConformingFloatEncodingStrategy = .convertToString(positiveInfinity: "Infinity", negativeInfinity: "-Infinity", nan: "NaN")
    let source = String(data: try! encoder.encode(input), encoding: .utf8)!
    return try! JSONDecoder().decode(Output.self, from: Data(calculate(source).utf8))
}
