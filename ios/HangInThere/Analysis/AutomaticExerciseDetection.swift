import Foundation

// Selection belongs to setup. Counters and saved results always use a concrete
// exercise; "Auto" must never masquerade as a third movement type.
enum WorkoutExerciseSelection: String, CaseIterable, Sendable {
    case automatic, pullUp, dip

    init(exercise: ExerciseCounter.Exercise) {
        precondition(exercise != .pushUp, "Push-ups use the side-view preview route")
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

// Dip support posture identifies the exercise independently of starting extension.
// Actual contours, camera stability and a fresh full starting position still
// qualify independently before a set can start.
struct AutomaticExerciseDetection: Sendable {
    static let minimumStableSeconds = 0.6
    static let identityPolicyVersion = 8
    private(set) var exercise: ExerciseCounter.Exercise?
    private(set) var apparatusCandidate: ExerciseCounter.Exercise?
    private(set) var isLocked = false
    private var witnessSides: [ArmMeasurement.Side] = []
    private var firstTime: PresentationTime?
    private var lastTime: PresentationTime?
    private var imageSize: ImageSize?
    private var backend: String?
    private var revision: Int?
    private var observations = 0
    // Positions from the hang which actually qualified identity, never a bar
    // reference and never advanced by retention frames or pure search queries.
    private var witnessedHangHands: [Point2D] = []
    private var identitySupportReference: BarSegment?

    mutating func reset() { self = Self() }

    static func candidate(in pose: PoseResult) -> ExerciseCounter.Exercise? {
        guard let candidate = apparatusCandidate(in: pose) else { return nil }
        if candidate == .pullUp, !PullUpArmMeasurement(pose: pose).isExtended { return nil }
        if candidate == .dip, !AutomaticDipRails.isStartingPosition(pose),
           AutomaticSingleDipRail.contact(in: pose)?.isStartingPosition != true { return nil }
        return candidate
    }

    // Start looking for the actual gripping edge while the hang settles. This
    // can guide contour acquisition, but cannot identify/start a workout yet.
    static func apparatusCandidate(in pose: PoseResult) -> ExerciseCounter.Exercise? {
        let pullUp = AutomaticApparatusReference.search(pose: pose, exercise: .pullUp) != nil
        // Contact search can continue through deep bends. Pose identity still
        // requires current support geometry, independently of apparatus search.
        let dip = AutomaticDipRails.grip(in: pose) != nil || AutomaticSingleDipRail.contact(in: pose)?.isSupportLike == true
        guard pullUp != dip else { return nil }
        return pullUp ? .pullUp : .dip
    }

    // Preserve an actually witnessed dip search context through a deep bend,
    // using fresh observed contact below the shoulders. Queries earn no dwell
    // and cannot establish identity or reuse an expired/image-changed context.
    func searchCandidate(in pose: PoseResult, reference: BarSegment? = nil) -> ExerciseCounter.Exercise? {
        if observedPullUpIdentityReference(pose, reference: reference, minimumScore: ArmMeasurement.confidenceThreshold) != nil { return .pullUp }
        if retainsWitnessedPullUp(pose, reference: reference) { return .pullUp }
        if let fresh = Self.apparatusCandidate(in: pose) { return fresh }
        guard apparatusCandidate == .dip, imageSize == pose.imageSize, let lastTime else { return nil }
        let gap = pose.timestamp.elapsedSeconds(since: lastTime)
        guard gap.isFinite, gap >= 0, gap <= HandsFreeSetStart.maximumFrameGap,
              AutomaticApparatusReference.search(pose: pose, exercise: .dip) != nil else { return nil }
        let arms = PullUpArmMeasurement(pose: pose).arms
        guard arms.map(\.side) == witnessSides else { return nil }
        return !arms.isEmpty && arms.allSatisfy({ $0.wrist.y > $0.shoulder.y }) ? .dip : nil
    }

    // A qualified hang can bend before contour acquisition finishes. Retain
    // identity beside the same actually observed hands; these positions cannot
    // become an apparatus reference or earn a new identity/start/rep.
    private func retainsWitnessedPullUp(_ pose: PoseResult, reference: BarSegment?) -> Bool {
        guard exercise == .pullUp, apparatusCandidate == .pullUp,
              imageSize == pose.imageSize, backend == pose.backend, revision == pose.requestRevision,
              let lastTime else { return false }
        let gap = pose.timestamp.elapsedSeconds(since:lastTime)
        guard gap.isFinite, gap >= 0, gap <= HandsFreeSetStart.maximumFrameGap else { return false }
        let arms = PullUpArmMeasurement(pose:pose).arms
        guard arms.count == 2, arms.map(\.side) == witnessSides else { return false }
        let short = min(pose.imageSize.width,pose.imageSize.height)
        guard let reference else {
            guard witnessedHangHands.count == 2,
                  let person = pose.people.first,
                  [.leftWrist, .rightWrist].allSatisfy({ person.landmark($0, minimumConfidence: 0.5) != nil }) else { return false }
            return zip(arms, witnessedHangHands).allSatisfy {
                hypot($0.wrist.x - $1.x, $0.wrist.y - $1.y) <= short * 0.02
            }
        }
        guard reference.isValid else { return false }
        let edge = reference.ordered
        guard [edge.a,edge.b].allSatisfy({ (0...pose.imageSize.width).contains($0.x) && (0...pose.imageSize.height).contains($0.y) }) else { return false }
        // Use the already validated current arm chains. Shoulders can rise
        // above the bar in a high pull; only the confirmed hands preserve this
        // previously witnessed identity. Discovery/start remain independent.
        return arms.allSatisfy { arm in
            edge.distance(to:arm.wrist) <= short * 0.085
        }
    }

    private func observedPullUpIdentityReference(_ pose: PoseResult, reference: BarSegment?, minimumScore: Double = 0.5) -> BarSegment? {
        guard let edge = reference?.ordered, edge.isValid, pose.imageSize.isValid,
              [edge.a,edge.b].allSatisfy({ (0...pose.imageSize.width).contains($0.x) && (0...pose.imageSize.height).contains($0.y) }),
              let person = pose.people.first, pose.people.count == 1 else { return nil }
        // A changing overhead crop during the first pull does not invalidate
        // an actually observed bar. Current measured hands/reference/context
        // still earn the original dwell; motion/start remains a separate gate.
        let measurement = PullUpArmMeasurement(pose:pose)
        let short = min(pose.imageSize.width,pose.imageSize.height)
        let hands = [PoseJoint.leftWrist,.rightWrist,.leftShoulder,.rightShoulder].compactMap {
            person.landmark($0,minimumConfidence:minimumScore)?.position
        }
        guard hands.count == 4, PullUpArmMeasurement.hasDistinctFrontRearArms(in: pose) else { return nil }
        return measurement.arms.count == 2 && measurement.arms.allSatisfy { arm in
            let signed = ((edge.b.x-edge.a.x)*(arm.wrist.y-edge.a.y)-(edge.b.y-edge.a.y)*(arm.wrist.x-edge.a.x))/edge.length
            return edge.distance(to:arm.wrist) <= short*0.085 && signed >= -short*0.015 && arm.shoulder.y-arm.wrist.y >= -short*0.02
        } ? edge : nil
    }

    @discardableResult
    mutating func observe(_ pose: PoseResult, reference: BarSegment? = nil) -> ExerciseCounter.Exercise? {
        guard !isLocked else { return exercise }
        let seconds = pose.timestamp.seconds
        guard seconds.isFinite, let next = searchCandidate(in: pose, reference:reference) else {
            reset(); return nil
        }
        if let lastTime {
            let gap = pose.timestamp.elapsedSeconds(since: lastTime)
            if !gap.isFinite || gap <= 0 || gap > HandsFreeSetStart.maximumFrameGap {
                reset(); return nil
            }
        }
        let sides = PullUpArmMeasurement(pose: pose).arms.map(\.side)
        if apparatusCandidate != next || imageSize != pose.imageSize || witnessSides != sides
            || backend != pose.backend || revision != pose.requestRevision {
            reset()
            apparatusCandidate = next
            imageSize = pose.imageSize
            backend = pose.backend
            revision = pose.requestRevision
            witnessSides = sides
        }
        lastTime = pose.timestamp
        // Bending during the first pull must not discard already qualified
        // identity or restart apparatus acquisition. This does not earn a rep
        // or relax the separate starting-position requirement.
        if exercise == next { return exercise }
        // Beside a confirmed observed bar, sustained overhead arms establish
        // identity independently of the brief extended pose needed to start.
        // Without a reference, retain original extended-hang discovery.
        let support = next == .pullUp ? observedPullUpIdentityReference(pose,reference:reference) : nil
        let identityPose = support != nil ? .pullUp : next == .dip ? Self.apparatusCandidate(in:pose) : Self.candidate(in:pose)
        guard identityPose == next else {
            exercise = nil; firstTime = nil; observations = 0; identitySupportReference = nil
            return nil
        }
        if identitySupportReference != support {
            // Arrival of an observed bar preserves valid extended-hang dwell;
            // changing or losing a reference already used by this dwell does not.
            if identitySupportReference != nil { firstTime = nil; observations = 0 }
            identitySupportReference = support
        }
        if firstTime == nil { firstTime = pose.timestamp }
        observations += 1
        // Reuse source-tick subtraction for exact dwell/gap boundaries; no tolerance is added.
        if observations >= 4, pose.timestamp.elapsedSeconds(since: firstTime ?? pose.timestamp) >= Self.minimumStableSeconds {
            exercise = next
            if next == .pullUp { witnessedHangHands = PullUpArmMeasurement(pose: pose).arms.map(\.wrist) }
        }
        return exercise
    }

    @discardableResult
    mutating func lock() -> Bool {
        guard exercise != nil else { return false }
        isLocked = true
        return true
    }

    /// Called only after the selected, source-owned dip counter commits an
    /// actual bilateral excursion with an independently ready camera. A static
    /// pose or this helper alone cannot start a set or emit a rep.
    mutating func confirmObservedDipMovement(_ pose: PoseResult) -> Bool {
        if isLocked { return exercise == .dip }
        let arms = PullUpArmMeasurement(pose:pose).arms
        let short = min(pose.imageSize.width,pose.imageSize.height)
        guard pose.timestamp.seconds.isFinite, pose.timestamp.seconds >= 0,
              pose.imageSize.isValid, pose.people.count == 1, arms.count == 2,
              arms.allSatisfy({ $0.wrist.y - $0.shoulder.y >= -0.02 * short }) else { return false }
        if let lastTime {
            let gap = pose.timestamp.elapsedSeconds(since:lastTime)
            guard gap.isFinite, gap >= 0, gap <= HandsFreeSetStart.maximumFrameGap,
                  imageSize == pose.imageSize, backend == pose.backend, revision == pose.requestRevision else { return false }
        }
        exercise = .dip; apparatusCandidate = .dip; imageSize = pose.imageSize
        backend = pose.backend; revision = pose.requestRevision
        witnessSides = arms.map(\.side); witnessedHangHands = []; identitySupportReference = nil
        lastTime = pose.timestamp
        return true
    }

    /// Only the current shared motion owner's committed bilateral excursion
    /// beside an independently qualified observed bar may call this route.
    mutating func confirmObservedPullUpMovement(_ pose: PoseResult, reference: BarSegment?) -> Bool {
        if isLocked { return exercise == .pullUp }
        guard observedPullUpIdentityReference(pose, reference: reference,
              minimumScore: ArmMeasurement.confidenceThreshold) != nil else { return false }
        if let lastTime {
            let gap = pose.timestamp.elapsedSeconds(since: lastTime)
            guard gap.isFinite, gap >= 0, gap <= HandsFreeSetStart.maximumFrameGap else { return false }
        }
        exercise = .pullUp; apparatusCandidate = .pullUp; imageSize = pose.imageSize
        backend = pose.backend; revision = pose.requestRevision
        witnessSides = PullUpArmMeasurement(pose: pose).arms.map(\.side)
        witnessedHangHands = PullUpArmMeasurement(pose: pose).arms.map(\.wrist)
        lastTime = pose.timestamp
        return true
    }

    /// Only called after the source-owned overhead counter commits its measured
    /// bilateral trajectory with an independently qualified camera. The earlier
    /// observed support establishes the exercise even during a high pull.
    mutating func confirmObservedOverheadPullUpMovement(_ pose: PoseResult) -> Bool {
        if isLocked { return exercise == .pullUp }
        let arms = PullUpArmMeasurement(pose: pose).arms
        guard pose.timestamp.seconds.isFinite, pose.timestamp.seconds >= 0,
              pose.imageSize.isValid, pose.people.count == 1, arms.count == 2 else { return false }
        if let lastTime {
            let gap = pose.timestamp.elapsedSeconds(since: lastTime)
            guard gap.isFinite, gap >= 0, gap <= HandsFreeSetStart.maximumFrameGap,
                  imageSize == pose.imageSize, backend == pose.backend, revision == pose.requestRevision else { return false }
        }
        exercise = .pullUp; apparatusCandidate = .pullUp; imageSize = pose.imageSize
        backend = pose.backend; revision = pose.requestRevision
        witnessSides = arms.map(\.side); witnessedHangHands = arms.map(\.wrist)
        identitySupportReference = nil; lastTime = pose.timestamp
        return true
    }
}
