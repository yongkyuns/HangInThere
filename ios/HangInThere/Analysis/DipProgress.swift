import Foundation

// A read-only snapshot of dip counting evidence. Descent earns the first half;
// recovery earns the second. Only a movement event can display 100% or green.
struct DipProgress: Equatable, Sendable {
    enum Stage: Equatable, Sendable {
        case startingSupport, ready, lowering, holdingBottom, rising, holdingTop, counted, paused, finished
    }
    enum Cue: Equatable, Sendable {
        case support, lower, lowerFurther, bendArms, rise, extendArms, hold, readyNext
        case showArms, reacquireSupport, reestablishGrip, setRail, finished
    }

    let stage: Stage
    let repNumber: Int
    let fraction: Double
    let cue: Cue
    let trackedArmCount: Int

    // Count confirmation lasts on source time, and disappears as the next
    // descent begins. No display timer advances either rep or hold progress.
    static let countConfirmationSeconds = 0.65
}
