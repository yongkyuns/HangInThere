import Foundation
import Testing
@testable import HangInThere

struct ExerciseCounterTests {
    // Original analytic arm chains, not predictions or anatomical ground truth.
    // Wrist remains fixed, segments have equal length; shoulder moves with angle.
    static func pose(_ time: Double, degrees: Double = 170,
                     exercise: ExerciseCounter.Exercise = .pullUp,
                     side: ArmMeasurement.Side = .left,
                     confidence: Double = 1, wristX: Double = 500,
                     shoulderOverride: Double? = nil,
                     verticalOffset: Double = 0) -> PoseResult {
        let half = degrees * .pi / 360
        let direction = exercise == .pullUp ? 1.0 : -1.0
        let y = 120 * sin(half) * direction
        let points = [Point2D(x: wristX, y: (shoulderOverride ?? 500 + 2 * y) + verticalOffset),
                      Point2D(x: wristX + 120 * cos(half), y: 500 + y + verticalOffset),
                      Point2D(x: wristX, y: 500 + verticalOffset)]
        return PoseResult(timestamp: PresentationTime(value: Int64((time * 1000).rounded()), timescale: 1000),
                          imageSize: ImageSize(width: 1000, height: 1000),
                          people: [PoseObservation(landmarks: zip(side.joints, points).map {
                              Landmark(joint: $0.0, position: $0.1, confidence: confidence)
                          })], backend: "analytic test arm", requestRevision: 0)
    }
    static let referenceEdge = BarSegment(a: Point2D(x: 100, y: 500), b: Point2D(x: 900, y: 500))

    private func feed(_ counter: inout ExerciseCounter, _ samples: [(Double, Double)]) {
        for (time, angle) in samples {
            counter.consume(Self.pose(time, degrees: angle, exercise: counter.exercise, side: counter.side),
                            referenceEdge: Self.referenceEdge)
        }
    }
    private func arm(_ counter: inout ExerciseCounter) { feed(&counter, [(0,170),(0.15,170)]) }

    // Original geometry with independently specified source ticks; converting a
    // large absolute source origin to Double must not change endpoint eligibility.
    private static func clockPose(_ ticks: Int64, degrees: Double,
                                  exercise: ExerciseCounter.Exercise,
                                  side: ArmMeasurement.Side, timescale: Int32 = 1_000_000) -> PoseResult {
        let base = pose(0, degrees: degrees, exercise: exercise, side: side)
        return PoseResult(timestamp: PresentationTime(value: ticks, timescale: timescale),
                          imageSize: base.imageSize, people: base.people,
                          backend: base.backend, requestRevision: base.requestRevision)
    }

    @Test func endpointDwellUsesSourceTicksAtEveryOrigin() {
        for exercise in ExerciseCounter.Exercise.allCases {
            for side in ArmMeasurement.Side.allCases {
                for origin in [Int64(0), 1_100_000, 9_007_199_254_740_992] {
                    var c = ExerciseCounter(exercise: exercise, side: side)
                    let frames: [(Int64, Double)] = [
                        (0,170),(120_000,170),(240_000,80),(360_000,80),(480_000,170),(600_000,170)
                    ]
                    for (offset, degrees) in frames {
                        c.consume(Self.clockPose(origin + offset, degrees: degrees, exercise: exercise, side: side),
                                  referenceEdge: Self.referenceEdge)
                        if offset == 120_000 { #expect(c.phase == .ready) }
                        if offset == 360_000 { #expect(c.phase == .returning) }
                    }
                    #expect(c.phase == .ready)
                    #expect(c.observedMovements == 1)
                    #expect(c.partialAttempts == 0)
                    #expect(c.interruptedAttempts == 0)
                }
            }
        }
    }

    @Test func endpointDwellNeverRoundsUpAOneTickShortHold() {
        for exercise in ExerciseCounter.Exercise.allCases {
            for side in ArmMeasurement.Side.allCases {
                for origin in [Int64(1_100_000), 9_007_199_254_740_992] {
                    var c = ExerciseCounter(exercise: exercise, side: side)
                    func frame(_ offset: Int64, _ degrees: Double) -> PoseResult {
                        Self.clockPose(origin + offset, degrees: degrees, exercise: exercise, side: side)
                    }
                    c.consume(frame(0,170), referenceEdge: Self.referenceEdge)
                    c.consume(frame(119_999,170), referenceEdge: Self.referenceEdge)
                    #expect(c.phase == .seekingStart)
                    c.consume(frame(120_000,170), referenceEdge: Self.referenceEdge)
                    #expect(c.phase == .ready)
                    c.consume(frame(240_000,80), referenceEdge: Self.referenceEdge)
                    c.consume(frame(359_999,80), referenceEdge: Self.referenceEdge)
                    #expect(c.phase == .outbound)
                    #expect(c.observedMovements == 0)
                    c.consume(frame(360_000,80), referenceEdge: Self.referenceEdge)
                    #expect(c.phase == .returning)
                    c.consume(frame(480_000,170), referenceEdge: Self.referenceEdge)
                    c.consume(frame(599_999,170), referenceEdge: Self.referenceEdge)
                    #expect(c.phase == .returning)
                    c.consume(frame(600_000,170), referenceEdge: Self.referenceEdge)
                    #expect(c.phase == .ready)
                    #expect(c.observedMovements == 1)
                    #expect(c.interruptedAttempts == 0)
                }
            }
        }
    }

    @Test func sourceGapUsesExactAllowedBoundaryWithoutExtraTolerance() {
        for exercise in ExerciseCounter.Exercise.allCases {
            for side in ArmMeasurement.Side.allCases {
                for excess in [Int64(0), 1] {
                    var c = ExerciseCounter(exercise: exercise, side: side)
                    let frames: [(Int64, Double)] = [(0,170),(150_000,170),(300_000,80),(650_000 + excess,80)]
                    for (offset, degrees) in frames {
                        c.consume(Self.clockPose(offset, degrees: degrees, exercise: exercise, side: side),
                                  referenceEdge: Self.referenceEdge)
                    }
                    if excess == 0 {
                        #expect(c.phase == .returning)
                        #expect(c.interruptedAttempts == 0)
                    } else {
                        #expect(c.phase == .seekingStart)
                        #expect(c.observedMovements == 0)
                        #expect(c.interruptedAttempts == 1)
                        #expect(c.lastEvent?.reason == "sourceTimeGap")
                    }
                }
            }
        }
    }

    @Test func rejectedSourceClocksCannotPoisonCounterRecovery() {
        let faults: [(Int64, Int64, Int32, String)] = [
            (0,240_000,1_000_000,"nonIncreasingTimestamp"),
            (0,239_999,1_000_000,"nonIncreasingTimestamp"),
            (0,400_000,0,"invalidTimestamp"),
            (.min,.max,1_000_000,"invalidTimestamp")
        ]
        for exercise in ExerciseCounter.Exercise.allCases {
            for side in ArmMeasurement.Side.allCases {
                for (origin, badTicks, scale, reason) in faults {
                    var c = ExerciseCounter(exercise: exercise, side: side)
                    for (offset, degrees) in [(Int64(0),170.0),(120_000,170),(240_000,80)] {
                        c.consume(Self.clockPose(origin + offset, degrees: degrees, exercise: exercise, side: side),
                                  referenceEdge: Self.referenceEdge)
                    }
                    #expect(c.phase == .outbound)
                    c.consume(Self.clockPose(badTicks, degrees: 80, exercise: exercise, side: side, timescale: scale),
                              referenceEdge: Self.referenceEdge)
                    #expect(c.phase == .seekingStart)
                    #expect(c.observedMovements == 0)
                    #expect(c.interruptedAttempts == 1)
                    #expect(c.lastEvent?.reason == reason)
                    for offset in [Int64(360_000),480_000] {
                        c.consume(Self.clockPose(origin + offset, degrees: 170, exercise: exercise, side: side),
                                  referenceEdge: Self.referenceEdge)
                    }
                    #expect(c.phase == .ready)
                    #expect(c.interruptedAttempts == 1)
                }
            }
        }
    }

    @Test func mixedTimescalesRetainNonBoundaryCounterCycles() {
        for exercise in ExerciseCounter.Exercise.allCases {
            for side in ArmMeasurement.Side.allCases {
                var c = ExerciseCounter(exercise: exercise, side: side)
                let frames: [(Int64, Int32, Double)] = [(1_100,1_000,170),(1_250_000,1_000_000,170),
                    (1_400,1_000,80),(1_550_000,1_000_000,80),(1_700,1_000,170),(1_850_000,1_000_000,170)]
                for (ticks, scale, degrees) in frames {
                    c.consume(Self.clockPose(ticks, degrees: degrees, exercise: exercise, side: side, timescale: scale),
                              referenceEdge: Self.referenceEdge)
                }
                #expect(c.phase == .ready)
                #expect(c.observedMovements == 1)
                #expect(c.interruptedAttempts == 0)
            }
        }
    }

    static func poseWithLowConfidenceElbow(
        _ time: Double,
        degrees: Double,
        exercise: ExerciseCounter.Exercise,
        side: ArmMeasurement.Side
    ) -> PoseResult {
        let base = pose(time, degrees: degrees, exercise: exercise, side: side)
        let elbow = side.joints[1]
        let landmarks = base.people[0].landmarks.map { landmark in
            Landmark(joint: landmark.joint, position: landmark.position,
                     confidence: landmark.joint == elbow ? 0.1 : landmark.confidence)
        }
        return PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
                          people: [PoseObservation(landmarks: landmarks)],
                          backend: base.backend, requestRevision: base.requestRevision)
    }

    static func poseWithLowConfidenceShoulder(
        _ time: Double,
        degrees: Double,
        exercise: ExerciseCounter.Exercise,
        side: ArmMeasurement.Side
    ) -> PoseResult {
        let base = pose(time, degrees: degrees, exercise: exercise, side: side)
        let shoulder = side.joints[0]
        let landmarks = base.people[0].landmarks.map { landmark in
            Landmark(joint: landmark.joint, position: landmark.position,
                     confidence: landmark.joint == shoulder ? 0.1 : landmark.confidence)
        }
        return PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
                          people: [PoseObservation(landmarks: landmarks)],
                          backend: base.backend, requestRevision: base.requestRevision)
    }

    static func noPersonPose(_ time: Double) -> PoseResult {
        PoseResult(timestamp: PresentationTime(value: Int64((time * 1000).rounded()), timescale: 1000),
                   imageSize: ImageSize(width: 1000, height: 1000), people: [],
                   backend: "analytic test arm", requestRevision: 0)
    }

    // Combine independently generated chains into one athlete, preserving each
    // shoulder's geometry and allowing deliberate one-arm observation outages.
    private static func bilateralPose(_ time: Double, left: Double?, right: Double?,
                                      rightOffset: Double = 0) -> PoseResult {
        let base = pose(time)
        var landmarks: [Landmark] = []
        if let left { landmarks += pose(time, degrees: left, wristX: 300).people[0].landmarks }
        if let right {
            landmarks += pose(time, degrees: right, side: .right, wristX: 700,
                              verticalOffset: rightOffset).people[0].landmarks
        }
        return PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
                          people: [PoseObservation(landmarks: landmarks)],
                          backend: base.backend, requestRevision: base.requestRevision)
    }

    private func feedBoth(_ c: inout ExerciseCounter, _ time: Double,
                          _ left: Double?, _ right: Double?, rightOffset: Double = 0) {
        c.consume(Self.bilateralPose(time, left: left, right: right, rightOffset: rightOffset),
                  referenceEdge: Self.referenceEdge)
    }

    @Test func bilateralPullUpsIgnoreSidePreferenceAndRequireBothEndpoints() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(side: side)
            feedBoth(&c, 0, 170, 170)
            feedBoth(&c, 0.15, 170, 170)
            feedBoth(&c, 0.3, 80, 170)
            feedBoth(&c, 0.45, 80, 170)
            #expect(c.phase == .ready)
            #expect(c.observedMovements == 0)
            feedBoth(&c, 0.6, 80, 80)
            feedBoth(&c, 0.75, 80, 80)
            #expect(c.observedMovements == 1)
            feedBoth(&c, 0.9, 170, 80)
            feedBoth(&c, 1.05, 170, 80)
            #expect(c.phase == .returning)
            feedBoth(&c, 1.2, 170, 170)
            feedBoth(&c, 1.35, 170, 170)
            feedBoth(&c, 1.5, 80, 80)
            feedBoth(&c, 1.65, 80, 80)
            #expect(c.observedMovements == 2)
        }
    }

    @Test func bothBentArmsMustMeetTravelGateIndividually() {
        var c = ExerciseCounter()
        feedBoth(&c, 0, 170, 170)
        feedBoth(&c, 0.15, 170, 170)
        // Right elbow bends, but its shoulder does not travel toward the bar.
        feedBoth(&c, 0.3, 80, 80, rightOffset: 90)
        feedBoth(&c, 0.45, 80, 80, rightOffset: 90)
        #expect(c.phase == .outbound)
        #expect(c.observedMovements == 0)
        feedBoth(&c, 0.6, 80, 80)
        feedBoth(&c, 0.75, 80, 80)
        #expect(c.observedMovements == 1)
    }

    @Test func obscuredArmFallsBackToPreviouslyAnchoredArm() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter()
            feedBoth(&c, 0, 170, 170)
            feedBoth(&c, 0.15, 170, 170)
            feedBoth(&c, 0.3, side == .left ? 80 : nil, side == .right ? 80 : nil)
            feedBoth(&c, 0.45, side == .left ? 80 : nil, side == .right ? 80 : nil)
            #expect(c.observedMovements == 1)
            #expect(c.interruptedAttempts == 0)
        }
    }

    @Test func newlyVisibleBentArmCannotBorrowOtherArmsBaseline() {
        var c = ExerciseCounter()
        feedBoth(&c, 0, 170, nil)
        feedBoth(&c, 0.15, 170, nil)
        feedBoth(&c, 0.3, 80, nil)
        feedBoth(&c, 0.45, 80, 80)
        #expect(c.observedMovements == 0)
        #expect(c.phase == .seekingStart)
        #expect(c.interruptedAttempts == 1)
        #expect(c.trackingIssue == "armBaselineUnavailable")
    }

    @Test func disjointArmGlimpsesCannotCompleteEndpointDwell() {
        var c = ExerciseCounter()
        feedBoth(&c, 0, 170, 170)
        feedBoth(&c, 0.15, 170, 170)
        feedBoth(&c, 0.3, 80, nil)
        feedBoth(&c, 0.45, nil, 80)
        #expect(c.observedMovements == 0)
        feedBoth(&c, 0.6, nil, 80)
        #expect(c.observedMovements == 1)
    }

    @Test func continuousArmAllowsDwellWhenOtherArmDropsOut() {
        var c = ExerciseCounter()
        feedBoth(&c, 0, 170, 170)
        feedBoth(&c, 0.15, 170, 170)
        feedBoth(&c, 0.3, 80, 80)
        feedBoth(&c, 0.45, nil, 80)
        #expect(c.observedMovements == 1)
    }

    @Test func armsKeepIndependentShoulderBaselines() {
        var c = ExerciseCounter()
        feedBoth(&c, 0, 170, 170, rightOffset: 100)
        feedBoth(&c, 0.15, 170, 170, rightOffset: 100)
        feedBoth(&c, 0.3, 80, 80, rightOffset: 100)
        feedBoth(&c, 0.45, 80, 80, rightOffset: 100)
        #expect(c.observedMovements == 1)
        #expect(c.interruptedAttempts == 0)
    }

    @Test func partialReturnRefreshesPullUpBaseline() {
        var c = ExerciseCounter()
        feedBoth(&c, 0, 170, 170)
        feedBoth(&c, 0.15, 170, 170)
        feedBoth(&c, 0.3, 130, 130)
        feedBoth(&c, 0.45, 170, 170, rightOffset: -70)
        feedBoth(&c, 0.6, 170, 170, rightOffset: -70)
        #expect(c.partialAttempts == 1)
        // Against the new right-arm baseline, this endpoint has insufficient travel.
        feedBoth(&c, 0.75, 80, 80)
        feedBoth(&c, 0.9, 80, 80)
        #expect(c.observedMovements == 0)
    }

    @Test func summaryRecordsBilateralPolicyAndReadsHistoricalReports() throws {
        let summary = ExerciseCounter().summary
        #expect(summary.armTracking == "bothUsableArms")
        #expect(ExerciseCounter(exercise: .dip).summary.armTracking == "selectedGripWithAvailableArms")
        let data = try JSONEncoder().encode(summary)
        var historical = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        historical.removeValue(forKey: "armTracking")
        let decoded = try JSONDecoder().decode(ExerciseCounter.Summary.self,
            from: JSONSerialization.data(withJSONObject: historical))
        #expect(decoded.armTracking == nil)
        #expect(decoded.observedMovements == summary.observedMovements)
    }

    @Test func pullUpCountsAtTopAndRequiresReturnBeforeNext() {
        var c = ExerciseCounter()
        arm(&c)
        feed(&c, [(0.3,80),(0.45,80)])
        #expect(c.observedMovements == 1)
        #expect(c.lastEvent?.sourceSeconds == 0.45)
        #expect(c.lastEvent?.reason == "barReferencedTop;chinClearanceNotMeasured")
        feed(&c, [(0.6,80),(0.75,80),(0.9,170),(1.05,170),(1.2,80),(1.35,80)])
        #expect(c.observedMovements == 2)
        #expect(c.events.filter { $0.outcome == .movement }.map(\.sourceSeconds) == [0.45, 1.35])
        #expect(c.summary.formVerification == "unverified")
    }
    @Test func dipCountsOnlyAfterReturnToTop() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        arm(&c)
        feed(&c, [(0.3,80),(0.45,80)])
        #expect(c.observedMovements == 0)
        #expect(c.phase == .returning)
        feed(&c, [(0.6,170),(0.75,170)])
        #expect(c.observedMovements == 1)
        #expect(c.lastEvent?.reason == "barReferencedCycle;dipDepthAndFormNotQualified")
    }
    @Test func dipProjectedTopDoesNotNeedPullUpLockoutAngle() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        feed(&c, [(0,140),(0.15,140),(0.3,70),(0.45,70),(0.6,140),(0.75,140)])
        #expect(c.observedMovements == 1)
        #expect(c.interruptedAttempts == 0)
        #expect(c.lastEvent?.reason == "barReferencedCycle;dipDepthAndFormNotQualified")
    }
    @Test(arguments: [80.0, 110.0]) func briefDipReturnCannotMergeTheNextDescentIntoOneCompletedRep(bottom: Double) {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            feed(&c, [(0,170),(0.15,170),(0.3,bottom),(0.45,bottom),(0.6,170),(0.65,140),(0.7,80)])
            #expect(c.observedMovements == 0)
            #expect(c.partialAttempts == 0)
            #expect(c.interruptedAttempts == 1)
            #expect(c.lastEvent?.reason == "returnNotSustained")
            #expect(c.phase == .seekingStart)
            #expect(c.dipProgress?.cue == .reacquireSupport)
            feed(&c, [(0.75,80),(0.9,170),(1.05,170)])
            #expect(c.observedMovements == 0)
            feed(&c, [(1.2,80),(1.35,80),(1.5,170),(1.65,170)])
            #expect(c.observedMovements == 1)
            #expect(c.events.filter { $0.outcome == .movement }.map(\.sourceSeconds) == [1.65])
        }
    }
    @Test func dipUpperJitterAndSubTopBouncesDoNotInventANewAttempt() {
        for side in ArmMeasurement.Side.allCases {
            for upperJitter in [true, false] {
                var c = ExerciseCounter(exercise: .dip, side: side)
                feed(&c, [(0,170),(0.15,170),(0.3,80),(0.45,80)])
                if upperJitter { feed(&c, [(0.6,170),(0.65,160),(0.75,170)]) }
                else { feed(&c, [(0.6,120),(0.75,80),(0.9,170),(1.05,170)]) }
                #expect(c.observedMovements == 1)
                #expect(c.partialAttempts == 0)
            }
        }
    }
    @Test func dipReturnUsesActualHeightAndAngleWitnessesTogether() {
        for side in ArmMeasurement.Side.allCases {
            for returns in [[(150.0,0.0),(170.0,0.0)], [(150.0,0.0),(170.0,10.0)], [(170.0,10.0),(150.0,0.0)]] {
                var c = ExerciseCounter(exercise: .dip, side: side)
                feed(&c, [(0,170),(0.15,170),(0.3,80),(0.45,80)])
                for (index, witness) in returns.enumerated() {
                    c.consume(Self.dipTopPose(0.6 + Double(index) * 0.05, degrees: witness.0,
                        side: side, heightDrop: witness.1), referenceEdge: Self.referenceEdge)
                }
                c.consume(Self.dipTopPose(0.67, side: side, elbowVisible: false), referenceEdge: Self.referenceEdge)
                c.consume(Self.dipTopPose(0.7, degrees: 140, side: side, heightDrop: 40), referenceEdge: Self.referenceEdge)
                #expect(c.observedMovements == 0)
                #expect(c.interruptedAttempts == 1)
                #expect(c.lastEvent?.reason == "returnNotSustained")
            }
        }
    }
    @Test func dipDepartureBoundaryPlateauIsNotAnObservedReturn() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            for (time, degrees, drop) in [(0.0,170.0,0.0),(0.15,170.0,0.0),(0.3,150.0,10.0),
                (0.34,150.0,10.0),(0.38,130.0,20.0),(0.53,130.0,20.0),(0.6,170.0,0.0),(0.75,170.0,0.0)] {
                c.consume(Self.dipTopPose(time, degrees: degrees, side: side, heightDrop: drop, supportSpan: 100),
                    referenceEdge: Self.referenceEdge)
            }
            #expect(c.observedMovements == 1)
            #expect(c.interruptedAttempts == 0)
            #expect(c.partialAttempts == 0)
        }
    }
    @Test func outboundDipReturnRetainsLowAngleAtTheSameHeight() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            for (time, degrees, drop) in [(0.0,170.0,0.0),(0.15,170.0,0.0),(0.3,150.0,15.0),
                (0.34,130.0,15.0),(0.38,150.0,0.0),(0.42,130.0,20.0)] {
                c.consume(Self.dipTopPose(time, degrees: degrees, side: side, heightDrop: drop, supportSpan: 100),
                    referenceEdge: Self.referenceEdge)
            }
            #expect(c.interruptedAttempts == 1)
            #expect(c.lastEvent?.reason == "returnNotSustained")
            #expect(c.observedMovements == 0)
            for (time, degrees, drop) in [(0.57,130.0,20.0),(0.7,170.0,0.0),(0.85,170.0,0.0),
                (0.99,130.0,20.0),(1.14,130.0,20.0),(1.29,170.0,0.0),(1.44,170.0,0.0)] {
                c.consume(Self.dipTopPose(time, degrees: degrees, side: side, heightDrop: drop, supportSpan: 100),
                    referenceEdge: Self.referenceEdge)
            }
            #expect(c.observedMovements == 1)
            #expect(c.lastEvent?.sourceSeconds == 1.44)
        }
    }
    @Test func outboundDipReturnCannotCombineSeparateLowExtrema() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            for (time, degrees, drop) in [(0.0,170.0,0.0),(0.15,170.0,0.0),(0.3,150.0,20.0),
                (0.34,130.0,15.0),(0.38,150.0,7.0),(0.42,130.0,20.0),(0.57,130.0,20.0),(0.7,170.0,0.0),(0.85,170.0,0.0)] {
                c.consume(Self.dipTopPose(time, degrees: degrees, side: side, heightDrop: drop, supportSpan: 100),
                    referenceEdge: Self.referenceEdge)
            }
            #expect(c.interruptedAttempts == 0)
            #expect(c.observedMovements == 1)
        }
    }
    @Test func separateDipReturnWitnessesCannotInventCombinedMaximumGeometry() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            feed(&c, [(0,170),(0.15,170),(0.3,80),(0.45,80)])
            c.consume(Self.dipTopPose(0.6, degrees: 150, side: side), referenceEdge: Self.referenceEdge)
            c.consume(Self.dipTopPose(0.65, degrees: 170, side: side, heightDrop: 10), referenceEdge: Self.referenceEdge)
            c.consume(Self.dipTopPose(0.7, degrees: 140, side: side, heightDrop: 25), referenceEdge: Self.referenceEdge)
            #expect(c.interruptedAttempts == 0)
            c.consume(Self.dipTopPose(0.85, degrees: 170, side: side, heightDrop: 10), referenceEdge: Self.referenceEdge)
            c.consume(Self.dipTopPose(1, degrees: 170, side: side, heightDrop: 10), referenceEdge: Self.referenceEdge)
            #expect(c.observedMovements == 1)
        }
    }
    @Test func unresolvedDipReturnHistoryIsBoundedAndRequiresFreshSupport() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            feed(&c, [(0,170),(0.15,170),(0.3,80),(0.45,80)])
            for index in 0...64 {
                c.consume(Self.dipTopPose(0.6 + Double(index) * 0.001, degrees: 151 + Double(index) * 0.1,
                    side: side, heightDrop: Double(index) * 0.05), referenceEdge: Self.referenceEdge)
            }
            #expect(c.observedMovements == 0)
            #expect(c.interruptedAttempts == 1)
            #expect(c.lastEvent?.reason == "dipReturnUnresolved")
            #expect(c.phase == .seekingStart)
            #expect(c.dipProgress?.cue == .reacquireSupport)
        }
    }
    @Test func sourceInterruptionRevokesUnconfirmedDipReturn() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            feed(&c, [(0,170),(0.15,170),(0.3,80),(0.45,80),(0.6,170),(0.6,170)])
            #expect(c.lastEvent?.reason == "nonIncreasingTimestamp")
            feed(&c, [(0.8,170),(0.95,170),(1.1,80),(1.25,80),(1.4,170),(1.55,170)])
            #expect(c.observedMovements == 1)
            #expect(c.interruptedAttempts == 1)
            #expect(c.lastEvent?.sourceSeconds == 1.55)
        }
    }
    @Test func dipCanAcquireSupportAcrossShortPersonDropout() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        c.consume(
            Self.pose(0, degrees: 140, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        c.consume(Self.noPersonPose(0.125), referenceEdge: Self.referenceEdge)
        c.consume(
            Self.pose(0.25, degrees: 140, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        #expect(c.phase == .ready)
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 0)
    }

    @Test func dipStartRecoversAfterExpiredElbowConfidenceHole() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            feed(&c, [(0,140)])
            for tick in 1...9 {
                c.consume(Self.poseWithLowConfidenceElbow(Double(tick) / 10, degrees: 140,
                    exercise: .dip, side: side), referenceEdge: Self.referenceEdge)
                #expect(c.phase == .seekingStart)
                #expect(c.observedMovements == 0)
            }
            feed(&c, [(1,140)])
            #expect(c.phase == .seekingStart)
            feed(&c, [(1.15,140)])
            #expect(c.phase == .ready)
            feed(&c, [(1.3,70),(1.45,70),(1.6,140),(1.75,140)])
            #expect(c.observedMovements == 1)
            #expect(c.lastEvent?.sourceSeconds == 1.75)
            #expect(c.interruptedAttempts == 0)
        }
    }

    @Test func expiredDipStartCannotReuseItsOldExtensionWitness() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            feed(&c, [(0,140)])
            for tick in 1...9 {
                c.consume(Self.poseWithLowConfidenceElbow(Double(tick) / 10, degrees: 140,
                    exercise: .dip, side: side), referenceEdge: Self.referenceEdge)
            }
            feed(&c, [(1,130),(1.15,130),(1.3,130)])
            #expect(c.phase == .seekingStart)
            #expect(c.observedMovements == 0)
            feed(&c, [(1.45,140),(1.6,140)])
            #expect(c.phase == .ready)
            #expect(c.events.isEmpty)
        }
    }

    @Test func dipStartKeepsItsWitnessAtTheAllowedOutageBoundary() {
        for side in ArmMeasurement.Side.allCases {
            var c = ExerciseCounter(exercise: .dip, side: side)
            feed(&c, [(0,140)])
            for tick in 1...7 {
                c.consume(Self.poseWithLowConfidenceElbow(Double(tick) / 10, degrees: 140,
                    exercise: .dip, side: side), referenceEdge: Self.referenceEdge)
            }
            feed(&c, [(0.75,140)])
            #expect(c.phase == .ready)
            #expect(c.observedMovements == 0)
            #expect(c.events.isEmpty)
        }
    }

    @Test func dipPartialRecoveryRequiresReturningNearStartingTop() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        feed(&c, [(0,140),(0.15,140),(0.3,70),(0.45,70),(0.6,110),(0.75,110)])
        #expect(c.observedMovements == 0)
        #expect(c.phase == .returning)
        feed(&c, [(0.9,140),(1.05,140)])
        #expect(c.observedMovements == 1)
        #expect(c.partialAttempts == 0)
        #expect(c.interruptedAttempts == 0)
    }

    @Test func dipTravelUsesConfirmedGripAndArmScale() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        for (time, angle) in [(0.0,140.0),(0.15,140.0),(0.3,80.0),
                              (0.45,80.0),(0.6,140.0),(0.75,140.0)] {
            c.consume(
                Self.pose(time, degrees: angle, exercise: .dip, side: .right,
                          verticalOffset: -250),
                referenceEdge: BarSegment(a: .init(x: 100, y: 250), b: .init(x: 900, y: 250))
            )
        }
        #expect(c.observedMovements == 1)
        #expect(c.interruptedAttempts == 0)
    }

    @Test func dipGeometryCannotAcquireUnobservedExtensionAcrossElbowConfidenceDrop() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        feed(&c, [(0,140),(0.15,140),(0.3,70),(0.45,70),(0.6,110)])
        c.consume(
            Self.poseWithLowConfidenceElbow(0.75, degrees: 140, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        #expect(c.observedMovements == 0)
        feed(&c, [(0.9,140)])
        c.consume(
            Self.poseWithLowConfidenceElbow(1.05, degrees: 140, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        #expect(c.observedMovements == 1)

        feed(&c, [(1.2,70),(1.35,70),(1.5,140)])
        c.consume(
            Self.poseWithLowConfidenceElbow(1.65, degrees: 140, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        #expect(c.observedMovements == 2)
        #expect(c.interruptedAttempts == 0)
    }

    @Test func dipReturnEndpointNeedsReliableShoulderEvidence() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        feed(&c, [(0,140),(0.15,140),(0.3,70),(0.45,70),(0.6,110)])
        c.consume(
            Self.poseWithLowConfidenceShoulder(0.75, degrees: 140, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        #expect(c.observedMovements == 0)
        feed(&c, [(0.9,140),(1.05,140)])
        #expect(c.observedMovements == 1)
        #expect(c.partialAttempts == 0)
        #expect(c.interruptedAttempts == 0)
    }

    // Keep the observed shoulder/wrist fixed while varying the measured elbow.
    // This isolates arm evidence from the independent support-height gate.
    private static func dipTopPose(_ time: Double, degrees: Double = 170,
                                   side: ArmMeasurement.Side, elbowVisible: Bool = true, heightDrop: Double = 0,
                                   supportSpan: Double = 240 * sin(85 * .pi / 180)) -> PoseResult {
        let base = pose(time, exercise: .dip, side: side)
        let points = base.people[0].landmarks
        let wrist = points[2].position
        let shoulder = Point2D(x: points[0].position.x, y: wrist.y - supportSpan + heightDrop)
        let elbow = Point2D(x: wrist.x + (wrist.y - shoulder.y) / (2 * tan(degrees * .pi / 360)),
                            y: (shoulder.y + wrist.y) / 2)
        return PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
                          people: [PoseObservation(landmarks: points.map {
                              $0.joint == side.joints[0] ? Landmark(joint: $0.joint, position: shoulder, confidence: $0.confidence)
                              : $0.joint == side.joints[1]
                                  ? Landmark(joint: $0.joint, position: elbow, confidence: elbowVisible ? 1 : 0.1) : $0
                          })], backend: base.backend, requestRevision: base.requestRevision)
    }

    private func verifyDipReturnWitness(fullCycle: Bool) {
        for side in ArmMeasurement.Side.allCases {
            for contradicted in [false, true] {
                var c = ExerciseCounter(exercise: .dip, side: side)
                // 110° departs support but its shoulder travel falls short of
                // the bottom gate; its later return can only be a partial.
                feed(&c, [(0,170),(0.15,170),(0.3,fullCycle ? 80 : 110),(0.45,fullCycle ? 80 : 110)])
                let activePhase: ExerciseCounter.Phase = fullCycle ? .returning : .outbound
                #expect(c.phase == activePhase)
                c.consume(Self.dipTopPose(0.6, side: side), referenceEdge: Self.referenceEdge)
                if contradicted {
                    c.consume(Self.dipTopPose(0.65, degrees: fullCycle ? 140 : 110, side: side),
                              referenceEdge: Self.referenceEdge)
                }
                c.consume(Self.dipTopPose(0.8, side: side, elbowVisible: false), referenceEdge: Self.referenceEdge)
                if contradicted {
                    c.consume(Self.dipTopPose(0.95, side: side, elbowVisible: false), referenceEdge: Self.referenceEdge)
                    #expect(c.phase == activePhase)
                    #expect(c.observedMovements == 0)
                    #expect(c.partialAttempts == 0)
                    #expect(c.events.isEmpty)
                    c.consume(Self.dipTopPose(1.1, side: side), referenceEdge: Self.referenceEdge)
                    #expect(c.observedMovements == 0)
                    #expect(c.partialAttempts == 0)
                    c.consume(Self.dipTopPose(1.25, side: side, elbowVisible: false), referenceEdge: Self.referenceEdge)
                }
                #expect(c.observedMovements == (fullCycle ? 1 : 0))
                #expect(c.partialAttempts == (fullCycle ? 0 : 1))
                #expect(c.interruptedAttempts == 0)
                #expect(c.lastEvent?.sourceSeconds == (contradicted ? 1.25 : 0.8))
            }
        }
    }

    @Test func contradictedDipReturnCannotReuseTopWitnessAcrossElbowOutage() {
        verifyDipReturnWitness(fullCycle: true)
    }

    @Test func contradictedPartialDipReturnNeedsFreshTopWitness() {
        verifyDipReturnWitness(fullCycle: false)
    }

    @Test func lowConfidenceShoulderCannotAcquireDipStart() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        c.consume(
            Self.poseWithLowConfidenceShoulder(0, degrees: 140, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        c.consume(
            Self.poseWithLowConfidenceShoulder(0.15, degrees: 140, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        #expect(c.phase == .seekingStart)
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 0)
    }

    @Test func dipGripMovementCannotBeAcceptedAsTopBaselineDrift() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        feed(&c, [(0,140),(0.15,140)])
        c.consume(
            Self.pose(0.3, degrees: 70, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        c.consume(
            Self.pose(0.45, degrees: 70, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        // Moving the whole support pose also moves the hand. It must break the
        // fixed grip instead of manufacturing a local completed cycle.
        c.consume(
            Self.pose(0.6, degrees: 140, exercise: .dip, side: .right, verticalOffset: 40),
            referenceEdge: Self.referenceEdge
        )
        c.consume(
            Self.pose(0.75, degrees: 140, exercise: .dip, side: .right, verticalOffset: 40),
            referenceEdge: Self.referenceEdge
        )
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 1)
    }
    @Test func dipBentHoldCannotArmAsAStart() {
        var c = ExerciseCounter(exercise: .dip)
        feed(&c, [(0,70),(0.15,70),(0.3,75),(0.45,70)])
        #expect(c.phase == .seekingStart)
        #expect(c.observedMovements == 0)
    }
    @Test func shortDipPersonDropoutPausesWithoutResettingAttempt() {
        var c = ExerciseCounter(exercise: .dip)
        feed(&c, [(0,140),(0.15,140),(0.3,70)])
        c.consume(Self.noPersonPose(0.425), referenceEdge: Self.referenceEdge)
        feed(&c, [(0.55,70),(0.7,70),(0.85,140),(1.0,140)])
        #expect(c.observedMovements == 1)
        #expect(c.interruptedAttempts == 0)
    }
    @Test func longDipPersonDropoutInterruptsActiveAttempt() {
        var c = ExerciseCounter(exercise: .dip)
        feed(&c, [(0,140),(0.15,140),(0.3,70)])
        c.consume(Self.noPersonPose(0.45), referenceEdge: Self.referenceEdge)
        c.consume(Self.noPersonPose(0.8), referenceEdge: Self.referenceEdge)
        c.consume(Self.noPersonPose(1.1), referenceEdge: Self.referenceEdge)
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 1)
        #expect(c.phase == .seekingStart)
    }
    @Test func dipRailReturnCanSustainAcrossElbowConfidenceDrop() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        feed(&c, [(0,140),(0.15,140),(0.3,70),(0.45,70),(0.6,140)])
        c.consume(
            Self.poseWithLowConfidenceElbow(0.75, degrees: 140, exercise: .dip, side: .right),
            referenceEdge: Self.referenceEdge
        )
        #expect(c.observedMovements == 1)
        #expect(c.interruptedAttempts == 0)
    }
    @Test func initialMidRepOrBentHoldCannotCount() {
        var c = ExerciseCounter()
        feed(&c, [(0,80),(0.15,80),(0.3,100),(0.45,80)])
        #expect(c.observedMovements == 0)
        #expect(c.phase == .seekingStart)
    }
    @Test func finalPullUpHoldDoesNotNeedDescentToKeepCount() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,80),(0.45,80)])
        c.finish(); c.finish()
        #expect(c.observedMovements == 1)
        #expect(c.interruptedAttempts == 0)
    }
    @Test func incompleteDipAtEOFIsNotARep() {
        var c = ExerciseCounter(exercise: .dip); arm(&c)
        feed(&c, [(0.3,80),(0.45,80)])
        c.finish(); c.finish()
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 1)
        #expect(c.lastEvent?.reason == "endOfInput")
    }
    @Test func shortAttemptReturningBeforeEndpointIsPartialOnly() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,130),(0.45,125),(0.6,170),(0.75,170)])
        #expect(c.partialAttempts == 1)
        #expect(c.observedMovements == 0)
        #expect(c.phase == .ready)
    }
    @Test func jitterDoesNotCreateEndpointsOrRepeatedCounts() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,99),(0.36,101),(0.42,99),(0.48,101),(0.54,99),(0.60,101)])
        #expect(c.observedMovements == 0)
        #expect(c.phase == .outbound)
    }
    @Test func endpointKindsDoNotShareDwell() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,80),(0.45,170)])
        #expect(c.partialAttempts == 0, "Bent dwell must not count as extended dwell.")
        c.consume(Self.pose(0.6), referenceEdge: Self.referenceEdge)
        #expect(c.partialAttempts == 1)
    }
    @Test func slowHoldCountsOnce() {
        var c = ExerciseCounter(); arm(&c)
        for i in 2...50 { c.consume(Self.pose(Double(i) * 0.15, degrees: 80), referenceEdge: Self.referenceEdge) }
        #expect(c.observedMovements == 1)
    }
    @Test func missingJointCannotBorrowOtherArmOrPreviousFrame() {
        var c = ExerciseCounter(); arm(&c)
        c.consume(Self.pose(0.3, degrees: 120), referenceEdge: Self.referenceEdge)
        c.consume(Self.pose(0.45, degrees: 80, side: .right), referenceEdge: Self.referenceEdge)
        feed(&c, [(0.6,80),(0.75,80)])
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 1)
        #expect(c.phase == .seekingStart)
    }
    @Test func lowConfidenceInterruptsAndRequiresFreshStart() {
        var c = ExerciseCounter(); arm(&c)
        c.consume(Self.pose(0.3, degrees: 120), referenceEdge: Self.referenceEdge)
        c.consume(Self.pose(0.45, degrees: 80, confidence: 0.1), referenceEdge: Self.referenceEdge)
        #expect(c.trackingIssue == "lowConfidence")
        feed(&c, [(0.6,170),(0.75,170),(0.9,80),(1.05,80)])
        #expect(c.observedMovements == 1)
        #expect(c.interruptedAttempts == 1)
    }
    @Test func multiplePeopleInterruptRatherThanSelectConvenientSkeleton() {
        var c = ExerciseCounter(); arm(&c)
        c.consume(Self.pose(0.3, degrees: 120), referenceEdge: Self.referenceEdge)
        let p = Self.pose(0.45, degrees: 80)
        c.consume(PoseResult(timestamp: p.timestamp, imageSize: p.imageSize,
                             people: p.people + p.people, backend: p.backend, requestRevision: 0),
                  referenceEdge: Self.referenceEdge)
        #expect(c.trackingIssue == "multiplePeople")
        #expect(c.interruptedAttempts == 1)
    }
    @Test func gapCannotBridgeMissingTopEvidence() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,80),(1,80),(1.15,80)])
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 1)
        #expect(c.lastEvent?.reason == "sourceTimeGap")
    }
    @Test func duplicateAndOutOfOrderTimesNeverCount() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,80),(0.3,80),(0.2,80),(0.45,80)])
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 1)
    }
    @Test func invalidTimesAreNotReplacedWithFrameRate() throws {
        var c = ExerciseCounter(); arm(&c)
        c.consume(Self.pose(0.3, degrees: 120), referenceEdge: Self.referenceEdge)
        let p = Self.pose(0.45, degrees: 80)
        c.consume(PoseResult(timestamp: PresentationTime(value: 1, timescale: 0),
                             imageSize: p.imageSize, people: p.people, backend: p.backend, requestRevision: 0),
                  referenceEdge: Self.referenceEdge)
        #expect(c.trackingIssue == "invalidTimestamp")
        #expect(c.interruptedAttempts == 1)
        _ = try JSONEncoder().encode(c.summary)
    }
    @Test func wristScreenDriftNoLongerActsAsAContactGuard() {
        var c = ExerciseCounter(); arm(&c)
        // Horizontal screen translation of the whole analytic chain does not
        // change shoulder-to-bar distance and must not be treated as contact loss.
        c.consume(Self.pose(0.3, degrees: 80, wristX: 650), referenceEdge: Self.referenceEdge)
        #expect(c.trackingIssue == nil)
        #expect(c.observedMovements == 0)
        #expect(c.phase == .outbound)
    }
    @Test func bentAngleWithoutTravelTowardBarDoesNotReachEndpoint() {
        var c = ExerciseCounter(); arm(&c)
        c.consume(Self.pose(0.3, degrees: 80, verticalOffset: 220), referenceEdge: Self.referenceEdge)
        c.consume(Self.pose(0.45, degrees: 80, verticalOffset: 220), referenceEdge: Self.referenceEdge)
        #expect(c.observedMovements == 0)
        #expect(c.phase == .outbound)
    }
    @Test func missingBarReferenceCannotCount() {
        var c = ExerciseCounter()
        c.consume(Self.pose(0))
        c.consume(Self.pose(0.15))
        c.consume(Self.pose(0.3, degrees: 80))
        c.consume(Self.pose(0.45, degrees: 80))
        #expect(c.observedMovements == 0)
        #expect(c.trackingIssue == "barReferenceUnavailable")
        #expect(c.phase == .seekingStart)
    }
    @Test func obliqueReferenceUsesPerpendicularDistance() {
        let edge = BarSegment(a: Point2D(x: 100, y: 400), b: Point2D(x: 900, y: 600))
        #expect(abs(edge.perpendicularDistance(to: Point2D(x: 500, y: 500))) < 1e-9)
        #expect(edge.perpendicularDistance(to: Point2D(x: 500, y: 600)) > 90)
    }
    @Test func sourceTimeOffsetDoesNotChangeCount() {
        var c = ExerciseCounter()
        feed(&c, [(10,170),(10.15,170),(10.3,80),(10.45,80)])
        #expect(c.observedMovements == 1)
        #expect(c.lastEvent?.sourceSeconds == 10.45)
    }
    @Test func resetClearsEverythingButExerciseAndArm() {
        var c = ExerciseCounter(exercise: .dip, side: .right); arm(&c)
        feed(&c, [(0.3,80),(0.45,80),(0.6,170),(0.75,170)])
        c.reset()
        #expect(c.observedMovements == 0)
        #expect(c.lastEvent == nil)
        #expect(c.events.isEmpty)
        #expect(c.phase == .seekingStart)
        #expect(c.exercise == .dip && c.side == .right)
        arm(&c)
        #expect(c.phase == .ready)
    }
    @Test func finishFreezesStateUntilReset() {
        var c = ExerciseCounter(); c.finish()
        feed(&c, [(0,170),(0.15,170),(0.3,80),(0.45,80)])
        #expect(c.observedMovements == 0)
        #expect(c.phase == .finished)
    }
}
