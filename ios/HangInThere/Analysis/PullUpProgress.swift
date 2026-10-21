import Foundation

// A display snapshot of the counter's current evidence, not form qualification.
// Only the actual counted state can display 100% ascent progress.
struct PullUpProgress: Equatable, Sendable {
    enum Stage: Equatable, Sendable { case startingHang, ready, pulling, holdingTop, counted, paused, finished }
    enum Cue: Equatable, Sendable {
        case hang, pull, pullHigher, finishPull, hold, lower, showArms, reacquireHang, setBar, finished
    }

    let stage: Stage
    let repNumber: Int
    let fraction: Double
    let resetFraction: Double
    let cue: Cue
    let trackedArmCount: Int

    static func fraction(_ value: Double) -> Double {
        normalizedProgress(value)
    }
}

func normalizedProgress(_ value: Double) -> Double {
    value.isFinite ? min(1, max(0, value)) : 0
}
