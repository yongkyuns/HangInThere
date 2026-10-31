import Testing
@testable import HangInThere

@Suite struct AutomaticExerciseDetectionTests {
    @Test func committedOverheadIdentityUsesCurrentArmsWithoutRequiringAnImageReference() {
        var identity=AutomaticExerciseDetection()
        for t in [0.0,0.2,0.4,0.6] { let result=identity.observe(bentPullUpPose(t));#expect(result == nil) }
        #expect(identity.exercise == nil)
        let confirmed=identity.confirmObservedOverheadPullUpMovement(highPull(0.8));#expect(confirmed)
        #expect(identity.exercise == .pullUp)
        let locked=identity.lock();#expect(locked)
        let different=identity.confirmObservedDipMovement(pose(.dip,1));#expect(!different)
        for fault in ["missing","crowd","backend","revision","gap","regression"] {
            var detector=AutomaticExerciseDetection()
            for t in [0.0,0.2,0.4,0.6] {_ = detector.observe(bentPullUpPose(t))}
            let p=highPull(0.8)
            let people=fault == "missing" ? p.people.map { person in PoseObservation(landmarks:person.landmarks.filter {$0.joint != .rightWrist}) } : fault == "crowd" ? p.people+p.people : p.people
            let changed=PoseResult(timestamp:fault == "gap" ? .init(value:2000,timescale:1000) : fault == "regression" ? .init(value:400,timescale:1000) : p.timestamp,
                imageSize:p.imageSize,people:people,backend:fault == "backend" ? "changed" : p.backend,requestRevision:fault == "revision" ? 2 : p.requestRevision)
            let result=detector.confirmObservedOverheadPullUpMovement(changed);#expect(!result)
        }
    }
    @Test func actualObservedReferenceSurvivesUnqualifiedHighPullAndKeepsOriginalDwell() {
        var known = AutomaticExerciseDetection(), unknown = AutomaticExerciseDetection()
        let bar = AutomaticPullUpBarTests().candidate().edge
        for t in [0.0,0.2,0.4,0.6] {
            #expect(AutomaticExerciseDetection.apparatusCandidate(in: highPull(t)) == nil)
            #expect(known.observe(highPull(t),reference:bar) == (t == 0.6 ? .pullUp : nil))
            #expect(known.apparatusCandidate == .pullUp)
            #expect(unknown.observe(highPull(t)) == nil)
        }
    }
    @Test func weakCurrentHandPreservesSearchWithoutEarningPostureIdentity() {
        var identity = AutomaticExerciseDetection()
        let bar = AutomaticPullUpBarTests().candidate().edge
        for t in [0.0,0.2,0.4,0.6,0.8] {
            let p = bentPullUpPose(t)
            let weak = PoseResult(timestamp:p.timestamp,imageSize:p.imageSize,
                people:p.people.map { person in .init(landmarks:person.landmarks.map { point in
                    .init(joint:point.joint,position:point.position,confidence:point.joint == .rightWrist ? 0.49 : point.confidence)
                }) },backend:p.backend,requestRevision:p.requestRevision)
            #expect(identity.observe(weak,reference:bar) == nil)
            #expect(identity.apparatusCandidate == .pullUp)
        }
        let locked = identity.lock()
        #expect(!locked)
    }
    @Test func observedBarArrivalPreservesAlreadyMeasuredExtendedHangDwell() {
        var identity = AutomaticExerciseDetection()
        for t in [0.0,0.2,0.4] { #expect(identity.observe(pose(.pullUp,t)) == nil) }
        let reference = AutomaticPullUpBarTests().candidate().edge
        #expect(identity.observe(bentPullUpPose(0.6),reference:reference) == .pullUp)
        #expect(!HandsFreeSetStart.isStartingPosition(pose:bentPullUpPose(0.6),referenceEdge:reference))
    }

    @Test func confirmedSupportIdentifiesFlexedOverheadArmsButCannotStartThem() {
        var identity = AutomaticExerciseDetection()
        let reference = AutomaticPullUpBarTests().candidate().edge
        for ticks:Int64 in [0,200_000,400_000,599_999,600_000] {
            let original = bentPullUpPose(Double(ticks)/1e6)
            let p = PoseResult(timestamp:.init(value:ticks,timescale:1_000_000),imageSize:original.imageSize,
                people:original.people,backend:original.backend,requestRevision:original.requestRevision)
            #expect(AutomaticExerciseDetection.candidate(in:p) == nil)
            #expect(identity.observe(p,reference:reference) == (ticks == 600_000 ? .pullUp : nil))
            #expect(!HandsFreeSetStart.isStartingPosition(pose:p,referenceEdge:reference))
        }
        #expect(!identity.isLocked)
    }

    @Test func knownSupportIdentityCannotBorrowTimeFromChangedOrMissingReference() {
        let a = AutomaticPullUpBarTests().candidate().edge
        let b = AutomaticPullUpBarTests().candidate(290).edge
        for missing in [false,true] {
            var identity = AutomaticExerciseDetection()
            for t in [0.0,0.2,0.4] { #expect(identity.observe(bentPullUpPose(t),reference:a) == nil) }
            #expect(identity.observe(bentPullUpPose(0.6),reference:missing ? nil : b) == nil)
            for t in [0.8,1.0,1.2] {
                #expect(identity.observe(bentPullUpPose(t),reference:b) == (!missing && t == 1.2 ? .pullUp : nil))
            }
            if missing { #expect(identity.observe(bentPullUpPose(1.4),reference:b) == .pullUp) }
        }
        var identity = AutomaticExerciseDetection()
        for t in [0.0,0.2,0.4] { #expect(identity.observe(bentPullUpPose(t),reference:a) == nil) }
        #expect(identity.observe(bentPullUpPose(0.6),reference:.init(a:a.b,b:a.a)) == .pullUp)
    }

    func highPull(_ seconds:Double) -> PoseResult {
        let original = pose(.pullUp,seconds)
        return .init(timestamp:original.timestamp,imageSize:original.imageSize,
            people:[.init(landmarks:original.people[0].landmarks.map { point in
                let position:Point2D
                switch point.joint {
                case .leftShoulder: position = .init(x:410,y:350)
                case .rightShoulder: position = .init(x:280,y:350)
                case .leftElbow: position = .init(x:580,y:400)
                case .rightElbow: position = .init(x:130,y:400)
                default: position = point.position
                }
                return .init(joint:point.joint,position:position,confidence:point.confidence)
            })],backend:original.backend,requestRevision:original.requestRevision)
    }

    @Test func confirmedReferenceRetainsHangIdentityThroughHighPullWithoutEarningStart() {
        var identity = AutomaticExerciseDetection()
        for t in [0.0,0.2,0.4,0.6] { identity.observe(pose(.pullUp,t)) }
        let reference = AutomaticPullUpBarTests().candidate().edge
        for t in [0.8,1.0] {
            let base = highPull(t)
            let frame = PoseResult(timestamp:base.timestamp,imageSize:base.imageSize,
                people:base.people.map { person in .init(landmarks:person.landmarks.map { point in
                    guard t == 1.0, [.leftShoulder,.rightShoulder].contains(point.joint) else { return point }
                    return .init(joint:point.joint,position:.init(x:point.position.x,y:270),confidence:point.confidence)
                }) },backend:base.backend,requestRevision:base.requestRevision)
            #expect(AutomaticExerciseDetection.candidate(in:frame) == nil)
            #expect(!HandsFreeSetStart.isStartingPosition(pose:frame,referenceEdge:reference))
            #expect(identity.observe(frame,reference:reference) == .pullUp)
        }
        #expect(!identity.isLocked)
    }

    @Test func confirmedHangRetentionRequiresFreshCurrentArmsAndSameSource() {
        let reference = AutomaticPullUpBarTests().candidate().edge
        for fault in ["invalidReference","distantReference","missingWrist","backend","revision","gap","notWitnessed"] {
            var identity = AutomaticExerciseDetection()
            if fault != "notWitnessed" {
                for t in [0.0,0.2,0.4,0.6] { identity.observe(pose(.pullUp,t)) }
            }
            let base = highPull(fault == "gap" ? 1.0 : 0.8)
            let frame = PoseResult(timestamp:base.timestamp,imageSize:base.imageSize,
                people:base.people.map { person in .init(landmarks:person.landmarks.filter { fault != "missingWrist" || $0.joint != .rightWrist }) },
                backend:fault == "backend" ? "changed" : base.backend,
                requestRevision:fault == "revision" ? 2 : base.requestRevision)
            let supplied:BarSegment? = fault == "invalidReference" ? .init(a:.init(x:0,y:0),b:.init(x:0,y:0)) : fault == "distantReference"
                ? .init(a:.init(x:210,y:100),b:.init(x:490,y:100)) : reference
            #expect(identity.observe(frame,reference:supplied) == nil, "\(fault)")
        }
    }
    @Test func qualifiedHangIdentitySurvivesHighPullBeforeContourLockWithoutInventingSupport() {
        var identity = AutomaticExerciseDetection()
        for t in [0.0,0.2,0.4,0.6] { identity.observe(pose(.pullUp,t)) }
        for t in [0.8,1.0,1.2] {
            let frame = highPull(t)
            #expect(AutomaticExerciseDetection.candidate(in:frame) == nil)
            #expect(identity.observe(frame) == .pullUp)
            #expect(!identity.isLocked)
            #expect(!HandsFreeSetStart.isStartingPosition(pose:frame,
                referenceEdge:AutomaticPullUpBarTests().candidate().edge))
        }
        var unknown = AutomaticExerciseDetection()
        for t in [0.0,0.2,0.4,0.6,0.8] { #expect(unknown.observe(highPull(t)) == nil) }
        let locked = unknown.lock()
        #expect(!locked)
    }

    @Test func prelockHangRetentionCannotFollowIncrementalHandDriftOrWeakHands() {
        let short = min(pose(.pullUp,0).imageSize.width,pose(.pullUp,0).imageSize.height)
        for weak in [false,true] {
            var identity = AutomaticExerciseDetection()
            for t in [0.0,0.2,0.4,0.6] { identity.observe(pose(.pullUp,t)) }
            for (t,offset) in [(0.8,0.019),(1.0,0.038)] {
                let base = highPull(t)
                let frame = PoseResult(timestamp:base.timestamp,imageSize:base.imageSize,
                    people:base.people.map { person in .init(landmarks:person.landmarks.map { p in
                        .init(joint:p.joint,position:.init(x:p.position.x+short*offset,y:p.position.y),
                            confidence:weak && [.leftWrist,.rightWrist].contains(p.joint) ? 0.4 : p.confidence)
                    }) },backend:base.backend,requestRevision:base.requestRevision)
                #expect(identity.observe(frame) == (!weak && t == 0.8 ? .pullUp : nil))
            }
        }
    }
    @Test func measuredArmsPreserveConfirmedContextButCannotBypassDiscoveryQuality() {
        let reference = AutomaticPullUpBarTests().candidate().edge
        for confidence in [0.4,0.2] {
            var identity = AutomaticExerciseDetection()
            for t in [0.0,0.2,0.4,0.6] { identity.observe(pose(.pullUp,t)) }
            let base = highPull(0.8)
            let frame = PoseResult(timestamp:base.timestamp,imageSize:base.imageSize,
                people:base.people.map { person in .init(landmarks:person.landmarks.map {
                    .init(joint:$0.joint,position:$0.position,confidence:confidence)
                }) },backend:base.backend,requestRevision:base.requestRevision)
            #expect(AutomaticExerciseDetection.candidate(in:frame) == nil)
            #expect(identity.observe(frame,reference:reference) == (confidence == 0.4 ? .pullUp : nil))
            var unknown = AutomaticExerciseDetection()
            #expect(unknown.observe(frame,reference:reference) == nil)
        }
    }
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

    @Test func flexedDipSupportIdentifiesExerciseWithoutStartingExtension() {
        var identity = AutomaticExerciseDetection()
        let fixture = AutomaticDipRailsTests()
        for time in [0.0, 0.2, 0.4, 0.6] {
            let frame = fixture.pose(time, degrees: 100)
            #expect(AutomaticDipRails.grip(in: frame) != nil)
            #expect(AutomaticExerciseDetection.candidate(in: frame) == nil)
            #expect(identity.observe(frame) == (time == 0.6 ? .dip : nil))
            #expect(!AutomaticDipRails.isStartingPosition(frame))
            for side in ArmMeasurement.Side.allCases {
                var counter = ExerciseCounter(exercise: .dip, side: side)
                _ = counter.consume(frame, referenceEdge: fixture.rails[side == .left ? 0 : 1][0].edge)
                #expect(counter.phase == .seekingStart)
                #expect(counter.events.isEmpty)
            }
        }
    }

    @Test func interruptedFlexedDipIdentityRequiresFreshFullSupportDwell() {
        let fixture = AutomaticDipRailsTests()
        func support(_ time: Double) -> PoseResult {
            let frame = fixture.pose(time, degrees: 100)
            return PoseResult(timestamp: .init(value: Int64((time * 1_000_000).rounded()), timescale: 1_000_000),
                imageSize: frame.imageSize, people: frame.people, backend: frame.backend,
                requestRevision: frame.requestRevision)
        }
        for missingHand in [false, true] {
            var identity = AutomaticExerciseDetection()
            for time in [0.0, 0.2, 0.4] { #expect(identity.observe(support(time)) == nil) }
            let frame = support(0.5)
            let interrupted = PoseResult(timestamp: frame.timestamp, imageSize: frame.imageSize,
                people: frame.people.map { person in PoseObservation(landmarks: person.landmarks.compactMap { point in
                    if missingHand && point.joint == .rightWrist { return nil }
                    if !missingHand && (point.joint == .leftShoulder || point.joint == .rightShoulder) {
                        return Landmark(joint: point.joint,
                            position: .init(x: point.position.x, y: 480), confidence: point.confidence)
                    }
                    return point
                }) }, backend: frame.backend, requestRevision: frame.requestRevision)
            #expect(identity.observe(interrupted) == nil)
            for time in [0.6, 0.8, 1.0, 1.199999] { #expect(identity.observe(support(time)) == nil) }
            #expect(identity.observe(support(1.2)) == .dip)
        }
    }

    func identityPose(_ exercise: ExerciseCounter.Exercise, _ ticks: Int64, timescale: Int32 = 1_000_000) -> PoseResult {
        let original = pose(exercise, 0)
        return PoseResult(timestamp: .init(value: ticks, timescale: timescale), imageSize: original.imageSize,
            people: original.people, backend: original.backend, requestRevision: original.requestRevision)
    }

    @Test func identitySourceDwellIsExactButCannotRoundUpOrUseThreeSamples() {
        for exercise in ExerciseCounter.Exercise.barExercises {
            for origin: Int64 in [0, 1_100_000, 60_100_000, 9_007_199_254_740_990] {
                var policy = AutomaticExerciseDetection()
                for delta: Int64 in [0, 200_000, 400_000, 599_999] {
                    let result = policy.observe(identityPose(exercise, origin + delta))
                    #expect(result == nil)
                }
                let result = policy.observe(identityPose(exercise, origin + 600_000))
                #expect(result == exercise); #expect(!policy.isLocked)
            }
            var exact = AutomaticExerciseDetection(), sparse = AutomaticExerciseDetection()
            for t: Int64 in [1_100_000, 1_300_000, 1_500_000] {
                let result = exact.observe(identityPose(exercise, t)); #expect(result == nil)
            }
            let exactResult = exact.observe(identityPose(exercise, 1_700_000)); #expect(exactResult == exercise)
            for t: Int64 in [1_100_000, 1_400_000, 1_700_000] {
                let result = sparse.observe(identityPose(exercise, t)); #expect(result == nil)
            }
            let sparseResult = sparse.observe(identityPose(exercise, 1_700_001)); #expect(sparseResult == exercise)
        }
    }

    @Test func identitySourceGapAcceptsExactLimitAndRejectsOneTickMore() {
        for exercise in ExerciseCounter.Exercise.barExercises {
            for extra: Int64 in [0, 1] {
                var policy = AutomaticExerciseDetection()
                for t: Int64 in [1_000_000, 1_200_000, 1_550_000 + extra] {
                    let result = policy.observe(identityPose(exercise, t)); #expect(result == nil)
                }
                let result = policy.observe(identityPose(exercise, 1_650_000))
                #expect(result == (extra == 0 ? exercise : nil))
            }
        }
    }

    @Test func identitySourceClockFaultsClearDwellAndMixedScalesRemainSupported() {
        for exercise in ExerciseCounter.Exercise.barExercises {
            for fault in [PresentationTime(value: 1_500_000, timescale: 1_000_000),
                          .init(value: 1_499_999, timescale: 1_000_000), .init(value: 0, timescale: 0),
                          .init(value: 1, timescale: .min), .init(value: .max, timescale: 1_000_000)] {
                var policy = AutomaticExerciseDetection()
                for t: Int64 in [1_100_000, 1_300_000, 1_500_000] { _ = policy.observe(identityPose(exercise, t)) }
                let invalid = policy.observe(identityPose(exercise, fault.value, timescale: fault.timescale))
                #expect(invalid == nil); #expect(policy.exercise == nil); #expect(policy.apparatusCandidate == nil)
                let next = policy.observe(identityPose(exercise, 1_700_000)); #expect(next == nil)
            }
            var overflow = AutomaticExerciseDetection(), mixed = AutomaticExerciseDetection()
            let first = overflow.observe(identityPose(exercise, .min)); #expect(first == nil)
            let last = overflow.observe(identityPose(exercise, .max)); #expect(last == nil)
            #expect(overflow.apparatusCandidate == nil)
            for time in [PresentationTime(value: 0, timescale: 1000), .init(value: 200_000, timescale: 1_000_000),
                         .init(value: 400, timescale: 1000)] {
                let result = mixed.observe(identityPose(exercise, time.value, timescale: time.timescale))
                #expect(result == nil)
            }
            let result = mixed.observe(identityPose(exercise, 600_000)); #expect(result == exercise)
        }
    }

    @Test func distinguishesStableStartingPosesBeforeApparatusAcquisition() {
        for exercise in ExerciseCounter.Exercise.barExercises {
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
        let base = AutomaticDipRailsTests().pose(0.8, degrees: 90)
        let unclear = PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
            people: [.init(landmarks: base.people[0].landmarks.map { point in
                Landmark(joint: point.joint, position: point.position,
                    confidence: point.joint == .rightWrist ? 0.1 : point.confidence)
            })], backend: base.backend, requestRevision: base.requestRevision)
        #expect(detection.observe(unclear) == nil)
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
