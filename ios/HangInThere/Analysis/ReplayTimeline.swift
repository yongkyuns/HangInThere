import Foundation

struct ReplayTimeline: Sendable {
    private(set) var first: Double?
    private(set) var last: Double?
    private(set) var acceptedFrames = 0

    mutating func accept(_ timestamp: PresentationTime) -> Bool {
        let seconds = timestamp.seconds
        guard seconds.isFinite, seconds >= 0, last.map({ seconds > $0 }) ?? true else { return false }
        if first == nil { first = seconds }
        last = seconds
        acceptedFrames += 1
        return true
    }

    var elapsed: Double { max(0, (last ?? 0) - (first ?? 0)) }
}

// Source-time pacing, not a synthetic nominal-FPS clock. When inference is slow,
// replay slows down instead of dropping frames or separating the pose and image.
struct ReplayPacing: Sendable {
    static func delay(sourceDelta: Double, wallDelta: Double) -> Double {
        guard sourceDelta.isFinite, wallDelta.isFinite else { return 0 }
        return max(0, sourceDelta - max(0, wallDelta))
    }
}