import Foundation

// Selection belongs to setup. Counters and saved results always use a concrete
// exercise; "Auto" must never masquerade as a third movement type.
enum WorkoutExerciseSelection: String, CaseIterable, Sendable {
    case automatic, pullUp, dip

    init(exercise: ExerciseCounter.Exercise) {
        self = exercise == .pullUp ? .pullUp : .dip
    }
    var exercise: ExerciseCounter.Exercise? {
        switch self {
        case .automatic: nil
        case .pullUp: .pullUp
        case .dip: .dip
        }
    }
    var title: String {
        switch self {
        case .automatic: "Auto"
        case .pullUp: "Pull-up"
        case .dip: "Dips"
        }
    }
}

// Initial starting-pose identity remains valid through motion only while fresh
// bilateral grip evidence agrees. Actual contours, camera stability and a full
// starting position still qualify independently before a set can start.
struct AutomaticExerciseDetection: Sendable {
    static let minimumStableSeconds = 0.6
    private(set) var exercise: ExerciseCounter.Exercise?
    private(set) var apparatusCandidate: ExerciseCounter.Exercise?
    private(set) var isLocked = false
    private var firstSeconds: Double?
    private var lastSeconds: Double?
    private var imageSize: ImageSize?
    private var observations = 0

    mutating func reset() { self = Self() }

    static func candidate(in pose: PoseResult) -> ExerciseCounter.Exercise? {
        guard let candidate = apparatusCandidate(in: pose) else { return nil }
        if candidate == .pullUp, !PullUpArmMeasurement(pose: pose).isExtended { return nil }
        return candidate
    }

    // Start looking for the actual gripping edge while the hang settles. This
    // can guide contour acquisition, but cannot identify/start a workout yet.
    static func apparatusCandidate(in pose: PoseResult) -> ExerciseCounter.Exercise? {
        let pullUp = AutomaticApparatusReference.search(pose: pose, exercise: .pullUp) != nil
        let dip = AutomaticApparatusReference.search(pose: pose, exercise: .dip) != nil
        guard pullUp != dip else { return nil }
        return pullUp ? .pullUp : .dip
    }

    @discardableResult
    mutating func observe(_ pose: PoseResult) -> ExerciseCounter.Exercise? {
        guard !isLocked else { return exercise }
        let seconds = pose.timestamp.seconds
        guard seconds.isFinite, let next = Self.apparatusCandidate(in: pose) else {
            reset(); return nil
        }
        if let lastSeconds, seconds <= lastSeconds || seconds - lastSeconds > HandsFreeSetStart.maximumFrameGap {
            reset(); return nil
        }
        if apparatusCandidate != next || imageSize != pose.imageSize {
            reset()
            apparatusCandidate = next
            imageSize = pose.imageSize
        }
        lastSeconds = seconds
        // Bending during the first pull must not discard already qualified
        // identity or restart apparatus acquisition. This does not earn a rep
        // or relax the separate starting-position requirement.
        if exercise == next { return exercise }
        guard Self.candidate(in: pose) == next else {
            exercise = nil; firstSeconds = nil; observations = 0
            return nil
        }
        if firstSeconds == nil { firstSeconds = seconds }
        observations += 1
        if observations >= 4, seconds - (firstSeconds ?? seconds) >= Self.minimumStableSeconds {
            exercise = next
        }
        return exercise
    }

    @discardableResult
    mutating func lock() -> Bool {
        guard exercise != nil else { return false }
        isLocked = true
        return true
    }
}
