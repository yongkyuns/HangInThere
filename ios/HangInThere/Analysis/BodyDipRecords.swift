import Foundation

struct BodyAthleteSelectionDecision: Codable, Sendable {
    let status: String
    let reason: String
    let candidateIndex: Int?
    let sourceGeneration: Int64
    let policyVersion: Int
}

/// Native presentation/transport only. All movement and set policy stays in the
/// shared source-owned body-dip engine; camera qualification stays native.
struct BodyDipWorkoutSnapshot: Codable, Equatable, Sendable {
    struct Motion: Codable, Equatable, Sendable {
        struct Event: Codable, Equatable, Sendable {
            let outcome: String
            let seconds: Double
            let reason: String
            let departure: Double?
            let bottom: Double?
            let returned: Double?
            let peak: Double?
            let measuredArmSlots: [Int]?
        }
        let seconds: Double?
        let phase: String
        let cycles: Int
        let partialAttempts: Int
        let interruptedAttempts: Int
        let reason: String
        let observationUsable: Bool
        let sourceAccepted: Bool
        let progress: Double
        /// Image-side slots stay in place when an arm is unavailable.
        let elbowDegrees: [Double?]
        let travelArmFractions: [Double]
        let wristDriftArmFractions: [Double?]
        let currentArmSlots: [Int]?
        let relativeTravel: [Double?]?
        let countMethod: String?
        let event: Event?
        let policyVersion: Int
        var targetProgress: Double? = nil
        var targetReached: Bool? = nil
    }
    let motion: Motion
    let started: Bool
    let analysisRevision: Int
    let analyzedFrames: Int
    let usableFrames: Int
    let endReason: String?
}

/// Source-owned pull-up movement transport; no legacy counter runs in parallel.
struct BodyPullUpWorkoutSnapshot: Codable, Equatable, Sendable {
    struct Motion: Codable, Equatable, Sendable {
        struct Event: Codable, Equatable, Sendable {
            let outcome: String
            let reason: String
            let seconds: Double
            let setKey: String?
            let departure: Double?
            let peak: Double?
            let lowerTurn: Double?
            var observedCompletion: Double? = nil
            var startWitnessAt: Double? = nil
        }
        let seconds: Double?
        let phase: String
        let cycles: Int
        let reason: String
        let travel: [Double]
        let drift: [Double]
        let degrees: [Double]
        let event: Event?
        let observationUsable: Bool
        let sourceAccepted: Bool
        let partialAttempts: Int
        let interruptedAttempts: Int
        let progress: Double
        let policyVersion: Int
        var targetProgress: Double? = nil
        var targetReached: Bool? = nil
        var countMethod: String? = nil
        var events: [Event]? = nil
    }
    let motion: Motion
    let started: Bool
    let analysisRevision: Int
    let analyzedFrames: Int
    let usableFrames: Int
    let endReason: String?
}
