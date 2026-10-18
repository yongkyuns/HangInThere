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
    private var firstTime: PresentationTime?
    private var lastTime: PresentationTime?
    private var imageSize: ImageSize?
    private var observations = 0

    mutating func reset() { self = Self() }

    static func candidate(in pose: PoseResult) -> ExerciseCounter.Exercise? {
        guard let candidate = apparatusCandidate(in: pose) else { return nil }
        if candidate == .pullUp, !PullUpArmMeasurement(pose: pose).isExtended { return nil }
        if candidate == .dip, !AutomaticDipRails.isStartingPosition(pose) { return nil }
        return candidate
    }

    // Start looking for the actual gripping edge while the hang settles. This
    // can guide contour acquisition, but cannot identify/start a workout yet.
    static func apparatusCandidate(in pose: PoseResult) -> ExerciseCounter.Exercise? {
        let pullUp = AutomaticApparatusReference.search(pose: pose, exercise: .pullUp) != nil
        // Contact search can continue through deep bends. Pose identity still
        // requires current support geometry, independently of apparatus search.
        let dip = AutomaticDipRails.grip(in: pose) != nil
        guard pullUp != dip else { return nil }
        return pullUp ? .pullUp : .dip
    }

    // Preserve an actually witnessed dip search context through a deep bend,
    // using fresh bilateral contact below the shoulders. Queries earn no dwell
    // and cannot establish identity or reuse an expired/image-changed context.
    func searchCandidate(in pose: PoseResult) -> ExerciseCounter.Exercise? {
        if let fresh = Self.apparatusCandidate(in: pose) { return fresh }
        guard apparatusCandidate == .dip, imageSize == pose.imageSize, let lastTime else { return nil }
        let gap = pose.timestamp.elapsedSeconds(since: lastTime)
        guard gap.isFinite, gap >= 0, gap <= HandsFreeSetStart.maximumFrameGap,
              AutomaticDipRails.apparatusGrip(in: pose) != nil else { return nil }
        let arms = PullUpArmMeasurement(pose: pose).arms
        return arms.count == 2 && arms.allSatisfy({ $0.wrist.y > $0.shoulder.y }) ? .dip : nil
    }

    @discardableResult
    mutating func observe(_ pose: PoseResult) -> ExerciseCounter.Exercise? {
        guard !isLocked else { return exercise }
        let seconds = pose.timestamp.seconds
        guard seconds.isFinite, let next = searchCandidate(in: pose) else {
            reset(); return nil
        }
        if let lastTime {
            let gap = pose.timestamp.elapsedSeconds(since: lastTime)
            if !gap.isFinite || gap <= 0 || gap > HandsFreeSetStart.maximumFrameGap {
                reset(); return nil
            }
        }
        if apparatusCandidate != next || imageSize != pose.imageSize {
            reset()
            apparatusCandidate = next
            imageSize = pose.imageSize
        }
        lastTime = pose.timestamp
        // Bending during the first pull must not discard already qualified
        // identity or restart apparatus acquisition. This does not earn a rep
        // or relax the separate starting-position requirement.
        if exercise == next { return exercise }
        guard Self.candidate(in: pose) == next else {
            exercise = nil; firstTime = nil; observations = 0
            return nil
        }
        if firstTime == nil { firstTime = pose.timestamp }
        observations += 1
        // Reuse source-tick subtraction for exact dwell/gap boundaries; no tolerance is added.
        if observations >= 4, pose.timestamp.elapsedSeconds(since: firstTime ?? pose.timestamp) >= Self.minimumStableSeconds {
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
