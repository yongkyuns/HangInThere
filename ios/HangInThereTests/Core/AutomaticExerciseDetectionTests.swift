import Testing
@testable import HangInThere

@Suite struct AutomaticExerciseDetectionTests {
    func pose(_ exercise: ExerciseCounter.Exercise, _ seconds: Double) -> PoseResult {
        exercise == .pullUp ? AutomaticPullUpBarTests().pose(seconds) : AutomaticDipRailsTests().pose(seconds)
    }

    func bentPullUpPose(_ seconds: Double) -> PoseResult {
        let original = pose(.pullUp, seconds)
        return PoseResult(timestamp: original.timestamp, imageSize: original.imageSize,
            people: [.init(landmarks: original.people[0].landmarks.map {
                $0.joint == .leftElbow
                    ? Landmark(joint: .leftElbow, position: .init(x: 320, y: 340), confidence: 0.9) : $0
            })], backend: original.backend, requestRevision: original.requestRevision)
    }

    @Test func distinguishesStableStartingPosesBeforeApparatusAcquisition() {
        for exercise in ExerciseCounter.Exercise.allCases {
            var detection = AutomaticExerciseDetection()
            for t in [0.0, 0.2, 0.4] { #expect(detection.observe(pose(exercise, t)) == nil) }
            #expect(detection.observe(pose(exercise, 0.7)) == exercise)
            #expect(!detection.isLocked)
            // Identity supplies no invented apparatus; no contours means no lock.
            var apparatus = AutomaticApparatusReference()
            for t in [0.7, 1, 1.3, 1.6, 2] {
                #expect(apparatus.observe(pose: pose(exercise, t), exercise: exercise,
                    side: .left, candidates: exercise == .dip ? [[], []] : [[]]) == nil)
            }
            #expect(apparatus.state != .locked)
        }
    }

    @Test func rejectsMissingLowConfidenceMixedOrBentStartingArms() {
        let base = pose(.pullUp, 0)
        let observations: [[PoseObservation]] = [[], base.people + base.people,
            [.init(landmarks: base.people[0].landmarks.filter { $0.joint != .rightWrist })],
            [.init(landmarks: base.people[0].landmarks.map {
                Landmark(joint: $0.joint, position: $0.position, confidence: 0.2)
            })],
            [.init(landmarks: base.people[0].landmarks.map {
                Landmark(joint: $0.joint, position: $0.joint == .rightWrist ? .init(x: 180, y: 700) : $0.position,
                    confidence: $0.confidence)
            })],
            [.init(landmarks: base.people[0].landmarks.map {
                Landmark(joint: $0.joint, position: $0.joint == .leftElbow ? .init(x: 320, y: 340) : $0.position,
                    confidence: $0.confidence)
            })]]
        for people in observations {
            let invalid = PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
                people: people, backend: base.backend, requestRevision: base.requestRevision)
            #expect(AutomaticExerciseDetection.candidate(in: invalid) == nil)
        }
        #expect(AutomaticExerciseDetection.candidate(in: AutomaticDipRailsTests().pose(0, degrees: 100)) == nil)
    }

    @Test func raisedBentArmsCanGuideContourSearchButCannotConfirmExerciseOrStartSet() {
        var detection = AutomaticExerciseDetection()
        let fixture = AutomaticPullUpBarTests()
        for t in [0.0, 0.2, 0.4, 0.7, 1.0] {
            #expect(detection.observe(bentPullUpPose(t)) == nil)
            #expect(detection.apparatusCandidate == .pullUp)
        }
        let locked = detection.lock()
        #expect(!locked)
        for t in [1.2, 1.4, 1.6] { #expect(detection.observe(fixture.pose(t)) == nil) }
        #expect(detection.observe(fixture.pose(1.9)) == .pullUp)
    }

    @Test func qualifiedIdentitySurvivesFlexionWithFreshBilateralGrip() {
        var detection = AutomaticExerciseDetection()
        for t in [0.0, 0.2, 0.4, 0.7] { _ = detection.observe(pose(.pullUp, t)) }
        #expect(detection.exercise == .pullUp)
        for t in [0.9, 1.1, 1.3, 1.5] {
            let bent = bentPullUpPose(t)
            #expect(AutomaticExerciseDetection.candidate(in: bent) == nil)
            #expect(detection.observe(bent) == .pullUp)
            #expect(!detection.isLocked)
            #expect(!HandsFreeSetStart.isStartingPosition(pose: bent,
                referenceEdge: AutomaticPullUpBarTests().candidate().edge))
        }
    }

    @Test func unlockedFlexionIdentityNeverBridgesLostGripOrSourceContext() {
        for fault in ["missingHand", "lowConfidence", "crowd", "gap", "duplicate", "reverse", "resize", "invalidTime"] {
            var detection = AutomaticExerciseDetection()
            for t in [0.0, 0.2, 0.4, 0.7] { _ = detection.observe(pose(.pullUp, t)) }
            var invalid = bentPullUpPose(0.9)
            switch fault {
            case "gap": invalid = bentPullUpPose(1.5)
            case "duplicate": invalid = bentPullUpPose(0.7)
            case "reverse": invalid = bentPullUpPose(0.6)
            default:
                let people = invalid.people.map { person in
                    PoseObservation(landmarks: person.landmarks.compactMap { point in
                        if fault == "missingHand", point.joint == .rightWrist { return nil }
                        return fault == "lowConfidence"
                            ? Landmark(joint: point.joint, position: point.position, confidence: 0.1) : point
                    })
                }
                invalid = PoseResult(timestamp: fault == "invalidTime" ? .init(value: 0, timescale: 0) : invalid.timestamp,
                    imageSize: fault == "resize" ? .init(width: 800, height: 1400) : invalid.imageSize,
                    people: fault == "crowd" ? people + people : people,
                    backend: invalid.backend, requestRevision: invalid.requestRevision)
            }
            #expect(detection.observe(invalid) == nil)
            #expect(detection.exercise == nil)
            #expect(detection.observe(bentPullUpPose(1.7)) == nil)
        }
    }

    @Test func switchingPoseClearsIdentityAndRequiresFreshDwell() {
        var detection = AutomaticExerciseDetection()
        for t in [0.0, 0.2, 0.4, 0.7] { _ = detection.observe(pose(.pullUp, t)) }
        #expect(detection.exercise == .pullUp)
        for t in [0.8, 1, 1.2] { #expect(detection.observe(pose(.dip, t)) == nil) }
        #expect(detection.observe(pose(.dip, 1.5)) == .dip)
    }

    @Test func gapsDuplicateReversedInvalidTimesAndResizingCannotEarnDwell() {
        for interruption in ["gap", "duplicate", "reversed", "invalid", "resize"] {
            var detection = AutomaticExerciseDetection()
            for t in [0.0, 0.2, 0.4] { _ = detection.observe(pose(.pullUp, t)) }
            var interrupted = pose(.pullUp, interruption == "gap" ? 1.5 : 0.7)
            if interruption == "duplicate" { interrupted = pose(.pullUp, 0.4) }
            if interruption == "reversed" { interrupted = pose(.pullUp, 0.3) }
            if interruption == "invalid" || interruption == "resize" {
                interrupted = PoseResult(timestamp: interruption == "invalid" ? .init(value: 0, timescale: 0) : interrupted.timestamp,
                    imageSize: interruption == "resize" ? .init(width: 800, height: 1400) : interrupted.imageSize,
                    people: interrupted.people, backend: interrupted.backend, requestRevision: 1)
            }
            #expect(detection.observe(interrupted) == nil)
            #expect(detection.exercise == nil)
        }
    }

    @Test func unclearPoseCancelsAnAlreadyDetectedIdentity() {
        var detection = AutomaticExerciseDetection()
        for t in [0.0, 0.2, 0.4, 0.7] { _ = detection.observe(pose(.dip, t)) }
        #expect(detection.exercise == .dip)
        #expect(detection.observe(AutomaticDipRailsTests().pose(0.8, degrees: 90)) == nil)
        for t in [1.0, 1.2, 1.4] { #expect(detection.observe(pose(.dip, t)) == nil) }
        #expect(detection.observe(pose(.dip, 1.7)) == .dip)
    }

    @Test func setLockSurvivesMotionOcclusionAndOtherExerciseUntilReset() {
        var detection = AutomaticExerciseDetection()
        let emptyLock = detection.lock()
        #expect(!emptyLock)
        for t in [0.0, 0.2, 0.4, 0.7] { _ = detection.observe(pose(.pullUp, t)) }
        let acquiredLock = detection.lock()
        #expect(acquiredLock)
        #expect(detection.observe(pose(.dip, 1)) == .pullUp)
        #expect(detection.observe(AutomaticDipRailsTests().pose(2, degrees: 90)) == .pullUp)
        detection.reset()
        #expect(!detection.isLocked)
        #expect(detection.exercise == nil)
        for t in [3.0, 3.2, 3.4, 3.7] { _ = detection.observe(pose(.dip, t)) }
        #expect(detection.exercise == .dip)
    }
}
