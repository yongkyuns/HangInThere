import Foundation
import Testing
@testable import HangInThere

@Suite struct AutomaticDipRailsTests {
    @Test func oneVisibleArmCanAcquireOnlyItsObservedRail() throws {
        for side in ArmMeasurement.Side.allCases {
            func single(_ time: Double, degrees: Double = 170) -> PoseResult {
                let frame = pose(time, degrees: degrees)
                let opposite: ArmMeasurement.Side = side == .left ? .right : .left
                return PoseResult(timestamp: frame.timestamp, imageSize: frame.imageSize,
                    people: frame.people.map { person in PoseObservation(landmarks: person.landmarks.filter {
                        !opposite.joints.contains($0.joint)
                    }) }, backend: frame.backend, requestRevision: frame.requestRevision)
            }
            let search = try #require(AutomaticApparatusReference.search(pose: single(0), exercise: .dip))
            #expect(search.hands.count == 1 && search.regions.count == 1)
            let observed = rails[side == .left ? 0 : 1]
            var acquisition = AutomaticApparatusReference(), identity = AutomaticExerciseDetection()
            var reference: ConfirmedBar?
            for time in [0.0, 0.3, 0.6, 0.9] {
                let frame = single(time)
                #expect(AutomaticExerciseDetection.candidate(in: frame) == .dip)
                _ = identity.observe(frame)
                reference = acquisition.observe(pose: frame, exercise: .dip, side: side, candidates: [observed])
            }
            #expect(identity.exercise == .dip)
            let bar = try #require(reference)
            #expect(observed.contains { $0.edge.ordered == bar.referenceEdge })
            #expect(bar.oppositeEdge == nil)
            var counter = ExerciseCounter(exercise: .dip, side: side)
            for (time, angle) in [(1.0,170.0),(1.15,170),(1.3,80),(1.45,80),(1.6,170),(1.75,170),
                                   (1.9,80),(2.05,80),(2.2,120)] {
                counter.consume(single(time, degrees: angle), referenceEdge: bar.referenceEdge)
            }
            counter.finish()
            #expect(counter.observedMovements == 1)
            #expect(counter.interruptedAttempts == 1)
        }
    }

    func pose(_ time: Double, degrees: Double = 170, shift: Double = 0) -> PoseResult {
        DipSupportTests.pose(time, degrees: degrees, shift: .init(x: shift, y: 0), otherDegrees: degrees)
    }
    func singlePose(_ time: Double, side: ArmMeasurement.Side = .left) -> PoseResult {
        let frame = pose(time)
        let opposite: ArmMeasurement.Side = side == .left ? .right : .left
        return PoseResult(timestamp: frame.timestamp, imageSize: frame.imageSize,
            people: frame.people.map { person in PoseObservation(landmarks: person.landmarks.filter {
                !opposite.joints.contains($0.joint)
            }) }, backend: frame.backend, requestRevision: frame.requestRevision)
    }

    @Test func singleContactCannotHideMalformedOppositeEvidence() {
        let frame = singlePose(0)
        let hidden = Landmark(joint: .rightShoulder, position: .init(x: 700, y: 300), confidence: 0.1)
        for bad in [Landmark(joint: hidden.joint, position: hidden.position, confidence: .nan),
                    .init(joint: hidden.joint, position: hidden.position, confidence: 1.01),
                    .init(joint: hidden.joint, position: .init(x: -1, y: 300), confidence: 0.1),
                    .init(joint: hidden.joint, position: .init(x: 700, y: .nan), confidence: 0.1)] {
            let invalid = PoseResult(timestamp: frame.timestamp, imageSize: frame.imageSize,
                people: frame.people.map { .init(landmarks: $0.landmarks + [bad]) },
                backend: frame.backend, requestRevision: frame.requestRevision)
            #expect(AutomaticApparatusReference.search(pose: invalid, exercise: .dip) == nil)
            #expect(AutomaticExerciseDetection.apparatusCandidate(in: invalid) == nil)
        }
        let duplicate = PoseResult(timestamp: frame.timestamp, imageSize: frame.imageSize,
            people: frame.people.map { .init(landmarks: $0.landmarks + [hidden, hidden]) },
            backend: frame.backend, requestRevision: frame.requestRevision)
        #expect(AutomaticSingleDipRail.contact(in: duplicate) == nil)
    }

    @Test func singleRailRequiresUniquePairedSupportAndItsConfiguredSide() {
        let above = [rail(490, y: 495), rail(500, y: 495)]
        let another = [rail(540), rail(550)]
        for fault in ["unpaired", "ambiguous", "above", "bilateral", "wrongSide"] {
            var policy = AutomaticSingleDipRail()
            for time in [0.0, 0.3, 0.6, 0.9, 1.2] {
                let frame = fault == "bilateral" ? pose(time) : singlePose(time)
                let candidates = fault == "unpaired" ? [rails[0][0]] : fault == "above" ? above
                    : fault == "ambiguous" ? rails[0] + another : rails[0]
                #expect(policy.observe(pose: frame, side: fault == "wrongSide" ? .right : .left,
                    candidates: candidates) == nil)
            }
            #expect(policy.state != .locked)
        }
        let contradictory = DipSupportTests.pose(0, degrees: 170, otherDegrees: 100)
        #expect(AutomaticSingleDipRail.contact(in: contradictory) == nil)
        #expect(AutomaticExerciseDetection.candidate(in: contradictory) == nil)
    }

    @Test func singleReferenceClockFaultCannotLowerHighWater() {
        var policy = AutomaticSingleDipRail()
        for time in [1.0, 1.3, 1.6, 0.1, 0.4, 0.7, 1.0, 1.3, 1.6, 1.7, 2.0, 2.3] {
            #expect(policy.observe(pose: singlePose(time), side: .left, candidates: rails[0]) == nil)
        }
        #expect(policy.observe(pose: singlePose(2.6), side: .left, candidates: rails[0]) != nil)
    }

    @Test func singleReferenceNeedsFreshDwellAfterSourceContextChanges() {
        for revisionChanged in [false, true] {
            var policy = AutomaticSingleDipRail()
            for time in [0.0, 0.3, 0.6] {
                #expect(policy.observe(pose: singlePose(time), side: .left, candidates: rails[0]) == nil)
            }
            for time in [0.9, 1.2, 1.5, 1.8] {
                let frame = singlePose(time)
                let changed = PoseResult(timestamp: frame.timestamp, imageSize: frame.imageSize, people: frame.people,
                    backend: revisionChanged ? frame.backend : "new provider",
                    requestRevision: frame.requestRevision + (revisionChanged ? 1 : 0))
                let bar = policy.observe(pose: changed, side: .left, candidates: rails[0])
                #expect((bar != nil) == (time == 1.8))
            }
        }
    }

    @Test func asyncSearchRejectsDifferentAnatomicalContactAtIdenticalPixels() throws {
        let frame = singlePose(0)
        let changed = PoseResult(timestamp: frame.timestamp, imageSize: frame.imageSize,
            people: frame.people.map { person in .init(landmarks: person.landmarks.map { point in
                let index = ArmMeasurement.Side.left.joints.firstIndex(of: point.joint)!
                return Landmark(joint: ArmMeasurement.Side.right.joints[index], position: point.position,
                    confidence: point.confidence)
            }) }, backend: frame.backend, requestRevision: frame.requestRevision)
        let left = try #require(AutomaticApparatusReference.search(pose: frame, exercise: .dip))
        let right = try #require(AutomaticApparatusReference.search(pose: changed, exercise: .dip))
        #expect(left.hands == right.hands)
        #expect(!left.matches(right, size: frame.imageSize))
    }

    @Test func acquisitionOwnerKeepsClockAcrossModeAndProviderChanges() {
        for changeProvider in [false, true] {
            var policy = AutomaticApparatusReference()
            func frame(_ time: Double, changed: Bool = false) -> PoseResult {
                let original = singlePose(time)
                return PoseResult(timestamp: original.timestamp, imageSize: original.imageSize, people: original.people,
                    backend: changed ? "new provider" : original.backend, requestRevision: original.requestRevision)
            }
            for time in [1.0, 1.3, 1.6] {
                #expect(policy.observe(pose: frame(time), exercise: .dip, side: .left, candidates: [rails[0]]) == nil)
            }
            #expect(policy.observe(pose: pose(1.7), exercise: .dip, side: .left, candidates: rails) == nil)
            policy.invalidateEvidence()
            for time in [0.1, 0.4, 0.7, 1.0, 1.3, 1.6] {
                #expect(policy.observe(pose: frame(time, changed: changeProvider), exercise: .dip,
                    side: .left, candidates: [rails[0]]) == nil)
            }
            for time in [1.8, 2.1, 2.4, 2.7] {
                let bar = policy.observe(pose: frame(time, changed: changeProvider), exercise: .dip,
                    side: .left, candidates: [rails[0]])
                #expect((bar != nil) == (time == 2.7))
            }
            policy.reset()
            for time in [0.0, 0.3, 0.6, 0.9] {
                let bar = policy.observe(pose: frame(time), exercise: .dip, side: .left, candidates: [rails[0]])
                #expect((bar != nil) == (time == 0.9))
            }
        }
    }

    @Test func missingOppositeElbowCannotHideClearlyOverheadContact() {
        for side in ArmMeasurement.Side.allCases {
            let other: ArmMeasurement.Side = side == .left ? .right : .left
            for overhead in [false, true] {
                var identity = AutomaticExerciseDetection(), policy = AutomaticApparatusReference()
                var reference: ConfirmedBar?
                for time in [0.0, 0.3, 0.6, 0.9] {
                    let original = singlePose(time, side: side)
                    let partial = [Landmark(joint: other.joints[0], position: .init(x: 600, y: 300), confidence: 1),
                        Landmark(joint: other.joints[2], position: .init(x: 600, y: overhead ? 200 : 500), confidence: 1)]
                    let frame = PoseResult(timestamp: original.timestamp, imageSize: original.imageSize,
                        people: original.people.map { .init(landmarks: $0.landmarks + partial) },
                        backend: original.backend, requestRevision: original.requestRevision)
                    #expect((AutomaticSingleDipRail.contact(in: frame) != nil) == !overhead)
                    #expect((AutomaticApparatusReference.search(pose: frame, exercise: .dip) != nil) == !overhead)
                    #expect(AutomaticExerciseDetection.candidate(in: frame) == (overhead ? nil : .dip))
                    _ = identity.observe(frame)
                    reference = policy.observe(pose: frame, exercise: .dip, side: side,
                        candidates: [rails[side == .left ? 0 : 1]])
                }
                #expect(identity.exercise == (overhead ? nil : .dip))
                #expect((reference != nil) == !overhead)
            }
        }
    }
    func rail(_ x: Double, y: Double = 540, reversed: Bool = false) -> BarLineCandidate {
        let a = Point2D(x: x, y: y), b = Point2D(x: x, y: y + 120)
        return .init(edge: .init(a: reversed ? b : a, b: reversed ? a : b), geometryScore: 120)
    }
    var rails: [[BarLineCandidate]] { [[rail(490),rail(500)], [rail(690),rail(700)]] }

    @Test func observedHandlesMayBeginAtTheGripWithoutAnArtificialGap() throws {
        for offset in [0.0, 10.0, 39.0] {
            for reversed in [false, true] {
                let handles = [[490.0, 500.0], [690.0, 700.0]].map { xs in
                    xs.map { rail($0, y: 500 + offset, reversed: reversed) }
                }
                for side in ArmMeasurement.Side.allCases {
                    var policy = AutomaticDipRails()
                    #expect(policy.observe(pose: pose(0), side: side, candidates: handles) == nil)
                    // A fresh matching silhouette must sustain each independently
                    // paired handle, even when its visible end reaches the grasp.
                    let silhouettes = handles.map { [$0[1]] }
                    for time in [0.3, 0.6] {
                        #expect(policy.observe(pose: pose(time), side: side, candidates: silhouettes) == nil)
                    }
                    let proposal = policy.observe(pose: pose(1), side: side, candidates: silhouettes)
                    let bar = try #require(proposal)
                    #expect(bar.referenceEdge == handles[side == .left ? 0 : 1][1].edge.ordered)
                    #expect(policy.state == .locked)
                }
            }
        }
    }

    @Test func dipSearchPreservesShortObservedSegmentsOfCurvedHandles() throws {
        let frame = pose(0)
        let search = try #require(AutomaticApparatusReference.search(pose: frame, exercise: .dip))
        let minimum = try #require(search.minimumObservedLength)
        #expect(minimum == 40)
        let candidates = try zip([500.0, 700.0], search.regions.prefix(2)).map { x, region in
            let contours = [0.0, 10.0].map { offset in
                [(x - 10 + offset, 505.0), (x - 10 + offset, 550.0),
                 (x + offset, 590.0), (x + 25 + offset, 625.0)].map { Point2D(x: $0.0, y: $0.1) }
            }
            #expect(try BarLineFitter.candidates(contours: contours, region: region, size: frame.imageSize).isEmpty)
            return try BarLineFitter.candidates(contours: contours, region: region, size: frame.imageSize,
                                               minimumObservedLength: minimum)
        }
        for side in ArmMeasurement.Side.allCases {
            var adapter = AutomaticApparatusReference()
            for time in [0.0, 0.3, 0.6] {
                #expect(adapter.observe(pose: pose(time), exercise: .dip, side: side, candidates: candidates) == nil)
            }
            let proposal = adapter.observe(pose: pose(1), exercise: .dip, side: side, candidates: candidates)
            let bar = try #require(proposal)
            let x = side == .left ? 500.0 : 700.0
            #expect(abs(bar.referenceEdge.a.x - x) < 1e-8 && abs(bar.referenceEdge.b.x - x) < 1e-8)
            #expect(bar.referenceEdge.a.y == 505 && bar.referenceEdge.b.y == 550)
        }
    }

    @Test func smallCropKeepsFreshPartialSilhouettesAfterPairedSupport() throws {
        func frame(_ time: Double) -> PoseResult {
            DipSupportTests.pose(time, scale: 0.192, otherDegrees: 170)
        }
        let search = try #require(AutomaticApparatusReference.search(pose: frame(0), exercise: .dip))
        let minimum = try #require(search.minimumObservedLength)
        #expect(minimum == 24)
        func observed(partial: Bool) throws -> [[BarLineCandidate]] {
            try zip([96.0, 134.4], search.regions.prefix(2)).map { x, region in
                let contours = (partial ? [x] : [x - 3, x]).map { px in
                    [Point2D(x: px, y: 101), Point2D(x: px, y: partial ? 121 : (px == x ? 131 : 130))]
                }
                let result = try BarLineFitter.candidates(contours: contours, region: region,
                    size: frame(0).imageSize, minimumObservedLength: minimum)
                #expect(result.count == (partial ? 1 : 2))
                return result
            }
        }
        let paired = try observed(partial: false), fresh = try observed(partial: true)
        for side in ArmMeasurement.Side.allCases {
            var adapter = AutomaticApparatusReference()
            #expect(adapter.observe(pose: frame(0), exercise: .dip, side: side, candidates: paired) == nil)
            for time in [0.3, 0.6] {
                #expect(adapter.observe(pose: frame(time), exercise: .dip, side: side, candidates: fresh) == nil)
                #expect(adapter.state == .holding)
            }
            // The final confirmed edge still needs 24px; short fresh evidence
            // sustains dwell until the full observed silhouette is visible.
            let proposal = adapter.observe(pose: frame(1), exercise: .dip, side: side, candidates: paired)
            let bar = try #require(proposal)
            #expect(abs(bar.referenceEdge.length - 30) < 1e-8)
        }
    }

    @Test func evenASmallExtensionAboveTheGripCannotBecomeARail() {
        let forearms = [[490.0, 500.0], [690.0, 700.0]].map { xs in
            xs.map { rail($0, y: 499.999) }
        }
        for side in ArmMeasurement.Side.allCases {
            var policy = AutomaticDipRails()
            for time in [0.0, 0.3, 0.6, 1.0] {
                #expect(policy.observe(pose: pose(time), side: side, candidates: forearms) == nil)
            }
            #expect(policy.state != .locked)
        }
    }

    @Test func aWitnessedHandleStillNeedsFreshEvidenceBelowTheGrip() {
        let paired = [[490.0, 500.0], [690.0, 700.0]].map { xs in xs.map { rail($0, y: 500) } }
        for side in ArmMeasurement.Side.allCases {
            var policy = AutomaticDipRails()
            _ = policy.observe(pose: pose(0), side: side, candidates: paired)
            for time in [0.3, 0.6, 1.0] {
                #expect(policy.observe(pose: pose(time), side: side,
                    candidates: [[rail(500, y: 499.999)], [rail(700, y: 499.999)]]) == nil)
            }
            #expect(policy.state != .locked)
        }
    }

    var sharedBar: [BarLineCandidate] { [505.0,515.0].map { y in
        .init(edge:.init(a:.init(x:450,y:y),b:.init(x:750,y:y)),geometryScore:300)
    } }

    @Test func sharedDipBarAcquiresThroughCommonAdapterForEitherHand() throws {
        for side in ArmMeasurement.Side.allCases {
            var adapter = AutomaticApparatusReference()
            let search = try #require(AutomaticApparatusReference.search(pose:pose(0),exercise:.dip))
            #expect(search.regions.count == 3)
            for time in [0.0,0.3,0.6] {
                #expect(adapter.observe(pose:pose(time),exercise:.dip,side:side,candidates:[[],[],sharedBar]) == nil)
            }
            let proposal = adapter.observe(pose:pose(1),exercise:.dip,side:side,candidates:[[],[],sharedBar])
            let bar = try #require(proposal)
            #expect(bar.referenceEdge == sharedBar[0].edge)
            #expect(bar.role == (side == .left ? .leftDipRail : .rightDipRail))
            #expect(adapter.state == .locked)
        }
    }

    @Test func sharedDipBarRequiresFreshPairAndFiniteBilateralSupport() throws {
        let shortened = [sharedBar[0],BarLineCandidate(edge:.init(a:.init(x:480,y:515),b:.init(x:560,y:515)),geometryScore:80)]
        let distant = sharedBar.map { BarLineCandidate(edge:.init(a:.init(x:$0.edge.a.x,y:$0.edge.a.y+150),
            b:.init(x:$0.edge.b.x,y:$0.edge.b.y+150)),geometryScore:300) }
        let ambiguous = sharedBar + sharedBar.map { BarLineCandidate(edge:.init(a:.init(x:$0.edge.a.x,y:$0.edge.a.y+50),
            b:.init(x:$0.edge.b.x,y:$0.edge.b.y+50)),geometryScore:300) }
        let aboveGrip = sharedBar.map { BarLineCandidate(edge:.init(a:.init(x:$0.edge.a.x,y:$0.edge.a.y-65),
            b:.init(x:$0.edge.b.x,y:$0.edge.b.y-65)),geometryScore:300) }
        for candidates in [[sharedBar[0]],shortened,distant,ambiguous,aboveGrip,[rail(500,y:420),rail(510,y:420)]] {
            var policy = AutomaticDipBar()
            for time in [0.0,0.3,0.6,1,1.3] {
                #expect(policy.observe(pose:pose(time),side:.left,candidates:candidates) == nil)
            }
            #expect(policy.state != .locked)
        }
        var policy = AutomaticDipBar()
        for time in [0.0,0.3,0.6] { #expect(policy.observe(pose:pose(time),side:.left,candidates:sharedBar) == nil) }
        #expect(policy.observe(pose:pose(0.9),side:.left,candidates:[sharedBar[0]]) == nil)
        for time in [1.2,1.5,1.8] { #expect(policy.observe(pose:pose(time),side:.left,candidates:sharedBar) == nil) }
        let proposal = policy.observe(pose:pose(2.2),side:.left,candidates:sharedBar)
        let bar = try #require(proposal)
        #expect(bar.referenceEdge == sharedBar[0].edge)
    }

    // The paired center is close enough, but its longer selected silhouette
    // lies beyond the grip-distance limit. Its score exceeds the valid rail.
    func unreachableTube(hand: Point2D, outward: Double = -1, reversed: Bool = false) -> [BarLineCandidate] {
        [(118.0,150.0),(88.0,140.0)].map { offset, length in
            let a = Point2D(x:hand.x + outward * offset,y:hand.y + 40)
            let b = Point2D(x:a.x,y:a.y + length)
            return .init(edge:.init(a:reversed ? b : a,b:reversed ? a : b),geometryScore:length)
        }
    }

    @Test func ineligibleHigherRankedTubeCannotHideValidObservedRails() throws {
        for reversed in [false,true] {
            let clutter = [500.0,700.0].enumerated().map { index, x in
                unreachableTube(hand:.init(x:x,y:500),outward:index == 0 ? -1 : 1,reversed:reversed)
            }
            for side in ArmMeasurement.Side.allCases {
                var policy = AutomaticDipRails()
                let candidates = zip(clutter,rails).map { reversed ? $1 + $0 : $0 + $1 }
                for time in [0.0,0.3,0.6] {
                    #expect(policy.observe(pose:pose(time),side:side,candidates:candidates) == nil)
                }
                let proposal = policy.observe(pose:pose(1),side:side,candidates:candidates)
                let bar = try #require(proposal)
                #expect(bar.referenceEdge == rail(side == .left ? 500 : 700).edge)
                #expect(bar.role == (side == .left ? .leftDipRail : .rightDipRail))
            }
        }
    }

    @Test func wristAlignedRailOutranksLongerOffsetClutter() throws {
        // Occlusion can hide the top of the actual handle. A longer nearby tube
        // is closer to the wrist by finite endpoint distance, but its supporting
        // line is offset from the grasp. Ranking must not extend either edge.
        let valid = [500.0,700.0].map { x in [rail(x-10,y:570),rail(x,y:570)] }
        let clutter = [500.0,700.0].map { x in [x-40,x-30].map { px in
            BarLineCandidate(edge:.init(a:.init(x:px,y:540),b:.init(x:px,y:690)),geometryScore:150)
        } }
        for reversed in [false,true] {
            let candidates = zip(clutter,valid).map { bad, good in
                let input = bad + good
                return reversed ? input.reversed().map {
                    BarLineCandidate(edge:.init(a:$0.edge.b,b:$0.edge.a),geometryScore:$0.geometryScore)
                } : input
            }
            for side in ArmMeasurement.Side.allCases {
                let index = side == .left ? 0 : 1, hand = Point2D(x:side == .left ? 500 : 700,y:500)
                #expect(AutomaticDipRails.select(candidates:candidates[index],hand:hand,size:pose(0).imageSize)
                    == valid[index][1].edge)
                var policy = AutomaticDipRails()
                for time in [0.0,0.3,0.6] { #expect(policy.observe(pose:pose(time),side:side,candidates:candidates) == nil) }
                let proposal = policy.observe(pose:pose(1),side:side,candidates:candidates)
                let bar = try #require(proposal)
                #expect(bar.referenceEdge == valid[index][1].edge)
                #expect(bar.role == (side == .left ? .leftDipRail : .rightDipRail))
            }
        }
    }

    @Test func unreachableSelectedSilhouetteCannotAcquireByItsCloserCenterline() {
        let hand = Point2D(x:500,y:500), size = pose(0).imageSize
        for reversed in [false,true] {
            let clutter = unreachableTube(hand:hand,reversed:reversed)
            #expect(AutomaticDipRails.select(candidates:clutter,hand:hand,size:size) == nil)
            var policy = AutomaticDipRails()
            for time in [0.0,0.3,0.6,1,1.3] {
                #expect(policy.observe(pose:pose(time),side:.left,candidates:[clutter,rails[1]]) == nil)
                #expect(policy.state == .ambiguous)
            }
        }
    }

    @Test func finiteOversizedGeometryCannotAcquireWithNonFiniteGripDistance() {
        let hand = Point2D(x:500,y:5e154)
        let oversized = [490.0,500.0].map { x in
            BarLineCandidate(edge:.init(a:.init(x:x,y:1e155),b:.init(x:x,y:2e155)),geometryScore:1e155)
        }
        #expect(oversized.allSatisfy { $0.edge.isValid })
        #expect(AutomaticDipRails.select(candidates:oversized,hand:hand,size:.init(width:1000,height:3e155)) == nil)
    }

    func transverseRails(slope: Double = 0, rightOffset: Double = 0,
                         oppositeSilhouettes: Bool = false, reversed: Bool = false,
                         rightSlope: Double? = nil) -> [[BarLineCandidate]] {
        [500.0,700.0].enumerated().map { index, x in
            [0.0,10.0].enumerated().map { edgeIndex, height in
                // Unequal support can make the two crops select opposite sides
                // of the same tube. Tube identity must survive that choice.
                let half = oppositeSilhouettes && index == edgeIndex ? 55.0 : 50.0
                func point(_ px: Double) -> Point2D {
                    let direction = index == 1 ? (rightSlope ?? slope) : slope
                    return .init(x: px, y: 550 + slope * (x - 500) + direction * (px - x)
                        + height + (index == 1 ? rightOffset : 0))
                }
                let a = point(x - half), b = point(x + half)
                return BarLineCandidate(edge:.init(a:reversed ? b : a,b:reversed ? a : b),geometryScore:2*half)
            }
        }
    }

    @Test func separatedFragmentsOfOneTubeCannotBecomeTwoRails() {
        for slope in [0.0,0.03] {
            for opposite in [false,true] {
                for reversed in [false,true] {
                    for side in ArmMeasurement.Side.allCases {
                        var policy = AutomaticDipRails()
                        let fragments = transverseRails(slope:slope,oppositeSilhouettes:opposite,reversed:reversed)
                        for time in [0.0,0.3,0.6,1,1.3] {
                            #expect(policy.observe(pose:pose(time),side:side,candidates:fragments) == nil)
                            #expect(policy.state == .ambiguous)
                        }
                    }
                }
            }
        }
    }

    @Test func independentlyOffsetTransverseRailsStillAcquire() throws {
        for slope in [0.0,0.03] {
            for side in ArmMeasurement.Side.allCases {
                var policy = AutomaticDipRails()
                let independent = transverseRails(slope:slope,rightOffset:45,oppositeSilhouettes:true,
                    rightSlope:slope == 0 ? nil : -0.02)
                for time in [0.0,0.3,0.6] {
                    #expect(policy.observe(pose:pose(time),side:side,candidates:independent) == nil)
                }
                let proposal = policy.observe(pose:pose(1),side:side,candidates:independent)
                let bar = try #require(proposal)
                #expect(independent[side == .left ? 0 : 1].contains { $0.edge.ordered == bar.referenceEdge })
                #expect(policy.state == .locked)
            }
        }
    }

    @Test func freshSelectionCannotReplaceDistinctRailsWithOneTube() {
        for side in ArmMeasurement.Side.allCases {
            var policy = AutomaticDipRails()
            _ = policy.observe(pose:pose(0),side:side,candidates:transverseRails(rightOffset:45))
            for time in [0.3,0.6,1,1.3,1.6] {
                #expect(policy.observe(pose:pose(time),side:side,candidates:transverseRails(oppositeSilhouettes:true)) == nil)
                #expect(policy.state == .ambiguous)
            }
        }
    }

    @Test func losingEitherWitnessRequiresTwoFreshPairsAndRestartsDwell() throws {
        var policy = AutomaticDipRails()
        _ = policy.observe(pose:pose(0),side:.left,candidates:rails)
        let changed = [[rail(500)],[rail(720),rail(730)]]
        for time in [0.3,0.6,1] {
            #expect(policy.observe(pose:pose(time),side:.left,candidates:changed) == nil)
            #expect(policy.state == .ambiguous)
        }
        for time in [1.3,1.6,1.9] {
            #expect(policy.observe(pose:pose(time),side:.left,candidates:rails) == nil)
        }
        let proposal = policy.observe(pose:pose(2.3),side:.left,candidates:rails)
        #expect(try #require(proposal).isValid)
    }

    @Test func supportingLineIdentityIsSymmetricButTrackingStillRequiresOverlap() {
        let p = BarSegment(a:.init(x:100,y:100),b:.init(x:200,y:100))
        for reversed in [false,true] {
            func fragment(_ offset: Double) -> BarSegment {
                let a=Point2D(x:300,y:100+offset),b=Point2D(x:400,y:100+offset)
                return .init(a:reversed ? b : a,b:reversed ? a : b)
            }
            let boundary = fragment(5)
            #expect(AutomaticReferenceStability.sameSupportingLine(p,boundary,tolerance:5))
            #expect(AutomaticReferenceStability.sameSupportingLine(boundary,p,tolerance:5))
            #expect(!AutomaticReferenceStability.sameSupportingLine(p,fragment(5.001),tolerance:5))
            #expect(!AutomaticReferenceStability.sameLine(p,fragment(0),tolerance:5))
        }
        for angle in [4.99,5.01] {
            let q=BarSegment(a:.init(x:300,y:100),b:.init(x:400,y:100+100*tan(angle * .pi / 180)))
            #expect(AutomaticReferenceStability.sameSupportingLine(p,q,tolerance:1000,angleDegrees:5) == (angle < 5))
        }
    }

    @Test func bothObservedHandlesLockAndRetainSelectedAnatomicalRole() throws {
        for side in ArmMeasurement.Side.allCases {
            var policy = AutomaticDipRails()
            for time in [0.0,0.3,0.6] { #expect(policy.observe(pose: pose(time), side: side, candidates: rails) == nil) }
            let proposal = policy.observe(pose: pose(1), side: side, candidates: rails)
            let bar = try #require(proposal)
            #expect(bar.isValid)
            #expect(bar.role == (side == .left ? .leftDipRail : .rightDipRail))
            #expect(bar.referenceEdge == rail(side == .left ? 500 : 700).edge)
            #expect(bar.method == .automaticContours)
            #expect(policy.state == .locked)
            #expect(policy.observe(pose: pose(1.3, shift: 30), side: side, candidates: rails) == nil)
            policy.reset()
            #expect(policy.state == .lookingForGrip)
        }
    }

    @Test func noRailsSingleEdgesAndOnlyOneHandleCannotLock() {
        for candidates in [[],[[],[]],[[rail(490)],[rail(690)]],[[rail(490),rail(500)],[]]] {
            var policy = AutomaticDipRails()
            for time in [0.0,0.3,0.6,1,1.3] {
                #expect(policy.observe(pose: pose(time), side: .left, candidates: candidates) == nil)
            }
            #expect(policy.state != .locked)
        }
    }

    @Test func unsupportedWristsMissingHandsOrMultiplePeopleCannotAcquire() {
        let base = pose(0)
        // Flexion can guide apparatus search, but remains ineligible to start.
        #expect(AutomaticDipRails.grip(in: pose(0, degrees: 80)) != nil)
        #expect(!AutomaticDipRails.isStartingPosition(pose(0, degrees: 80)))
        let unsupported = PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
            people: [.init(landmarks: base.people[0].landmarks.map { point in
                point.joint == .leftWrist ? Landmark(joint: point.joint,
                    position: .init(x: point.position.x, y: 100), confidence: point.confidence) : point
            })], backend: base.backend, requestRevision: base.requestRevision)
        for invalid in [unsupported,
            PoseResult(timestamp: base.timestamp, imageSize: base.imageSize, people: [], backend: base.backend, requestRevision: 0),
            PoseResult(timestamp: base.timestamp, imageSize: base.imageSize, people: base.people + base.people, backend: base.backend, requestRevision: 0),
            PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
                people: [.init(landmarks: base.people[0].landmarks.filter { $0.joint != .rightWrist })], backend: base.backend, requestRevision: 0)] {
            #expect(AutomaticDipRails.grip(in: invalid) == nil)
        }
    }

    @Test func clothingAboveHandsAndDistantBackgroundPairsAreRejected() {
        let size = pose(0).imageSize, hand = Point2D(x: 500, y: 500)
        #expect(AutomaticDipRails.select(candidates: rails[0],hand:hand,size:.init(width:-1,height:1000)) == nil)
        #expect(AutomaticDipRails.select(candidates: [rail(490,y:320),rail(500,y:320)],hand:hand,size:size) == nil)
        #expect(AutomaticDipRails.select(candidates: [rail(100),rail(110)],hand:hand,size:size) == nil)
        let crossing = [BarLineCandidate(edge:.init(a:.init(x:490,y:490),b:.init(x:510,y:610)),geometryScore:120),rail(500)]
        #expect(AutomaticDipRails.select(candidates:crossing,hand:hand,size:size) == nil)
    }

    @Test func duplicateSourceTimesAndEitherMovingHandResetDwell() {
        for interruption in ["duplicate","gap","grip","rail","missing"] {
            var policy = AutomaticDipRails()
            for t in [0.0,0.3,0.6] { _ = policy.observe(pose:pose(t),side:.left,candidates:rails) }
            switch interruption {
            case "duplicate": #expect(policy.observe(pose:pose(0.6),side:.left,candidates:rails) == nil)
            case "gap": #expect(policy.observe(pose:pose(1.5),side:.left,candidates:rails) == nil)
            case "grip": #expect(policy.observe(pose:pose(1,shift:25),side:.left,candidates:rails) == nil)
            case "rail": #expect(policy.observe(pose:pose(1),side:.left,candidates:[[rail(510),rail(520)],rails[1]]) == nil)
            default: #expect(policy.observe(pose:pose(1),side:.left,candidates:[rails[0],[]]) == nil)
            }
            #expect(policy.state != .locked)
        }
    }

    @Test func reversedEndpointsPreserveVerticalRailAcquisition() throws {
        var policy = AutomaticDipRails()
        let reversed = [[rail(490,reversed:true),rail(500,reversed:true)],[rail(690,reversed:true),rail(700,reversed:true)]]
        for t in [0.0,0.3,0.6] { _ = policy.observe(pose:pose(t),side:.left,candidates:reversed) }
        let proposal = policy.observe(pose:pose(1),side:.left,candidates:reversed)
        let bar = try #require(proposal)
        #expect(bar.isValid)
        let p = pose(0)
        let grip = try #require(AutomaticDipRails.grip(in:p))
        #expect(grip.regions.allSatisfy { $0.isValid(in:p.imageSize) })
        #expect(!grip.regions[0].contains(grip.hands[1]))
    }

    @Test func pairedHandleIdentityRequiresFreshMatchingEvidenceThroughOcclusion() throws {
        var policy=AutomaticDipRails()
        _ = policy.observe(pose:pose(0),side:.left,candidates:rails)
        for t in [0.3,0.6] {
            #expect(policy.observe(pose:pose(t),side:.left,candidates:[[rail(500)],[rail(700)]]) == nil)
        }
        let proposal=policy.observe(pose:pose(1),side:.left,candidates:[[rail(500)],[rail(700)]])
        #expect(try #require(proposal).isValid)
        policy.reset()
        for t in [0.0,0.3,0.6,1] {
            #expect(policy.observe(pose:pose(t),side:.left,candidates:[[rail(500)],[rail(700)]]) == nil)
        }
    }

    @Test func collinearForearmCannotReplaceAWitnessedHandle() {
        var policy=AutomaticDipRails()
        _ = policy.observe(pose:pose(0),side:.left,candidates:rails)
        let forearms=[[rail(500,y:440)],[rail(700,y:440)]]
        for t in [0.3,0.6,1] {
            #expect(policy.observe(pose:pose(t),side:.left,candidates:forearms) == nil)
        }
        #expect(policy.state != .locked)
    }

    @Test func torsoFacingTrouserEdgesCannotBeGripRails() throws {
        let base=pose(0)
        let landmarks=base.people[0].landmarks.map { landmark in
            let offset = landmark.joint == .leftShoulder ? 60.0 : (landmark.joint == .rightShoulder ? -60.0 : 0)
            return Landmark(joint:landmark.joint,position:.init(x:landmark.position.x+offset,y:landmark.position.y),confidence:landmark.confidence)
        }
        let support=PoseResult(timestamp:base.timestamp,imageSize:base.imageSize,
            people:[.init(landmarks:landmarks)],backend:base.backend,requestRevision:0)
        let grip=try #require(AutomaticDipRails.grip(in:support))
        #expect(grip.regions[0].maxX == 530)
        #expect(grip.regions[1].minX == 670)
        var policy=AutomaticDipRails()
        for t in [0.0,0.3,0.6,1] {
            let p=PoseResult(timestamp:.init(value:Int64(t*1000),timescale:1000),imageSize:support.imageSize,
                people:support.people,backend:support.backend,requestRevision:0)
            #expect(policy.observe(pose:p,side:.left,candidates:[[rail(540),rail(550)],[rail(650),rail(660)]]) == nil)
        }
        #expect(policy.state != .locked)
    }

    @Test func integralCropRoundingDoesNotEraseObservedHandleEdges() throws {
        let rounded = [[490.0,500.0],[690.0,700.0]].map { xs in
            xs.map { x in BarLineCandidate(edge:.init(a:.init(x:x,y:540),b:.init(x:x,y:700.5)),geometryScore:160) }
        }
        var policy=AutomaticDipRails()
        for t in [0.0,0.3,0.6] { _ = policy.observe(pose:pose(t),side:.left,candidates:rounded) }
        let proposal=policy.observe(pose:pose(1),side:.left,candidates:rounded)
        #expect(try #require(proposal).isValid)
    }

    @Test func resettingCommonAdapterAllowsExerciseAndRailReacquisition() {
        var adapter = AutomaticApparatusReference()
        for t in [0.0,0.3,0.6,1] { _ = adapter.observe(pose:pose(t),exercise:.dip,side:.left,candidates:rails) }
        #expect(adapter.state == .locked)
        adapter.reset()
        #expect(adapter.state == .lookingForGrip)
        #expect(adapter.observe(pose:pose(2),exercise:.dip,side:.right,candidates:rails) == nil)
    }
}

@Suite struct DipAcquisitionStartTests {
    @Test func bentSupportCanAcquireObservedRailsOrSharedBarWithoutStartingEvidence() throws {
        let fixture = AutomaticDipRailsTests()
        let shared = fixture.sharedBar
        for side in ArmMeasurement.Side.allCases {
            for candidates in [fixture.rails, [[], [], shared]] {
                var identity = AutomaticExerciseDetection(), apparatus = AutomaticApparatusReference()
                var reference: ConfirmedBar?
                for seconds in [0.0, 0.3, 0.6, 0.9] {
                    let pose = fixture.pose(seconds, degrees: 100)
                    #expect(AutomaticApparatusReference.search(pose: pose, exercise: .dip) != nil)
                    #expect(!AutomaticDipRails.isStartingPosition(pose))
                    #expect(identity.observe(pose) == (seconds == 0.9 ? .dip : nil))
                    #expect(AutomaticExerciseDetection.candidate(in: pose) == nil)
                    #expect(identity.apparatusCandidate == .dip)
                    reference = apparatus.observe(pose: pose, exercise: .dip, side: side, candidates: candidates)
                }
                let bar = try #require(reference)
                #expect(bar.method == .automaticContours)
                let locked = identity.lock()
                #expect(locked) // Identity is not starting extension or a rep.
                var counter = ExerciseCounter(exercise: .dip, side: side)
                for seconds in [1.0, 1.1, 1.2] {
                    counter.consume(fixture.pose(seconds, degrees: 100), referenceEdge: bar.referenceEdge)
                }
                #expect(counter.phase == .seekingStart)
                #expect(counter.events.isEmpty && counter.observedMovements == 0)
            }
        }
    }

    @Test func originalDipTopSupportBoundaryRemainsSeparateFromSearch() {
        let fixture = AutomaticDipRailsTests()
        for degrees in [80.0, 100, 139.99, 140, 170] {
            let pose = fixture.pose(0, degrees: degrees)
            #expect(AutomaticDipRails.grip(in: pose) != nil)
            #expect(AutomaticDipRails.isStartingPosition(pose) == (degrees >= 140))
            #expect(AutomaticExerciseDetection.candidate(in: pose) == (degrees >= 140 ? .dip : nil))
        }
    }

    @Test func qualifiedDipIdentitySurvivesFlexionButCannotQualifyAColdBentStart() {
        let fixture = AutomaticDipRailsTests()
        var policy = AutomaticExerciseDetection()
        for time in [0.0, 0.2, 0.4, 0.6] { _ = policy.observe(fixture.pose(time)) }
        #expect(policy.exercise == .dip)
        for time in [0.8, 1.0, 1.2, 1.4] {
            let frame = fixture.pose(time, degrees: 100)
            #expect(policy.observe(frame) == .dip)
            #expect(AutomaticExerciseDetection.candidate(in: frame) == nil)
        }
        let invalid = fixture.pose(1.6, degrees: 100)
        let missing = PoseResult(timestamp: invalid.timestamp, imageSize: invalid.imageSize,
            people: [.init(landmarks: invalid.people[0].landmarks.filter { $0.joint != .rightWrist })],
            backend: invalid.backend, requestRevision: invalid.requestRevision)
        #expect(policy.observe(missing) == nil)
        #expect(policy.observe(fixture.pose(1.8, degrees: 100)) == nil)
    }
}

@Suite struct DipContactAcquisitionTests {
    private func pose(_ seconds: Double, deep: Bool = false, rightHeight: Double = 0) -> PoseResult {
        let original = DipSupportTests.pose(seconds, otherDegrees: 170)
        let landmarks = original.people[0].landmarks.map { point -> Landmark in
            let side = ArmMeasurement.Side.allCases.first { $0.joints.contains(point.joint) }
            let x = side == .left ? 500.0 : 700.0
            let position: Point2D
            if deep && (point.joint == .leftShoulder || point.joint == .rightShoulder) {
                position = .init(x: x, y: 480)
            } else if deep && (point.joint == .leftElbow || point.joint == .rightElbow) {
                position = .init(x: x + 100, y: 400)
            } else { position = point.position }
            return .init(joint: point.joint, position: .init(x: position.x,
                y: position.y + (side == .right ? rightHeight : 0)), confidence: point.confidence)
        }
        return .init(timestamp: original.timestamp, imageSize: original.imageSize,
            people: [.init(landmarks: landmarks)], backend: original.backend, requestRevision: original.requestRevision)
    }
    private func rails(_ rightHeight: Double = 0) -> [[BarLineCandidate]] {
        [500.0, 700.0].enumerated().map { index, x in
            let y = 540.0 + (index == 1 ? rightHeight : 0)
            return [x - 10, x].map { px in .init(edge: .init(a: .init(x: px, y: y),
                b: .init(x: px, y: y + 120)), geometryScore: 120) }
        }
    }

    @Test func deepContactCanAcquireWithoutEarningStartingEvidence() throws {
        let shared = [505.0, 515.0].map { y in
            BarLineCandidate(edge: .init(a: .init(x: 450, y: y), b: .init(x: 750, y: y)), geometryScore: 300)
        }
        for side in ArmMeasurement.Side.allCases {
            for candidates in [rails(), [[], [], shared]] {
                var apparatus = AutomaticApparatusReference()
                var reference: ConfirmedBar?
                for seconds in [0.0, 0.3, 0.6, 1.0] {
                    let frame = pose(seconds, deep: true)
                    #expect(AutomaticDipRails.grip(in: frame) == nil)
                    #expect(AutomaticApparatusReference.search(pose: frame, exercise: .dip) != nil)
                    #expect(AutomaticExerciseDetection.candidate(in: frame) == nil)
                    reference = apparatus.observe(pose: frame, exercise: .dip, side: side, candidates: candidates)
                }
                let bar = try #require(reference)
                var counter = ExerciseCounter(exercise: .dip, side: side)
                for seconds in [1.1, 1.3, 1.5] { counter.consume(pose(seconds, deep: true), referenceEdge: bar.referenceEdge) }
                #expect(counter.phase == .seekingStart)
                #expect(counter.events.isEmpty)
            }
        }
    }

    @Test func unequalImageHeightsRequireTwoActuallyCorrespondingRails() throws {
        for side in ArmMeasurement.Side.allCases {
            var apparatus = AutomaticApparatusReference()
            var reference: ConfirmedBar?
            for seconds in [0.0, 0.3, 0.6, 1.0] {
                let frame = pose(seconds, rightHeight: 150)
                #expect(AutomaticDipRails.isStartingPosition(frame))
                reference = apparatus.observe(pose: frame, exercise: .dip, side: side, candidates: rails(150))
            }
            let bar = try #require(reference)
            #expect(bar.referenceEdge == rails(150)[side == .left ? 0 : 1][1].edge)
            var wrong = AutomaticApparatusReference()
            for seconds in [0.0, 0.3, 0.6, 1.0, 1.3] {
                #expect(wrong.observe(pose: pose(seconds, rightHeight: 150), exercise: .dip,
                    side: side, candidates: rails()) == nil)
            }
        }
    }

    @Test func overheadContactSearchCannotEstablishDipPoseIdentity() {
        for seconds in [0.0, 0.3, 0.6, 1.0] {
            let frame = AutomaticPullUpBarTests().pose(seconds)
            #expect(AutomaticApparatusReference.search(pose: frame, exercise: .dip) != nil)
            #expect(AutomaticDipRails.grip(in: frame) == nil)
            #expect(AutomaticExerciseDetection.apparatusCandidate(in: frame) == .pullUp)
        }
    }

    @Test func witnessedDipContextSurvivesChangingShouldersWithoutEarningNewStartDwell() {
        var identity = AutomaticExerciseDetection()
        var apparatus = AutomaticApparatusReference()
        var reference: ConfirmedBar?
        for seconds in [0.0, 0.2, 0.4, 0.6, 0.9, 1.2] {
            let frame = pose(seconds, deep: seconds >= 0.9)
            for _ in 0..<3 { #expect(identity.searchCandidate(in: frame) == .dip) }
            _ = identity.observe(frame)
            #expect(identity.exercise == .dip || seconds < 0.6)
            reference = apparatus.observe(pose: frame, exercise: .dip, side: .left, candidates: rails()) ?? reference
        }
        #expect(reference != nil)
        #expect(!AutomaticDipRails.isStartingPosition(pose(1.2, deep: true)))
        for fault in ["gap", "reverse", "duplicate", "overhead", "missing", "size"] {
            var known = AutomaticExerciseDetection()
            for seconds in [0.0, 0.2, 0.4, 0.6] { _ = known.observe(pose(seconds)) }
            var invalid = pose(fault == "gap" ? 1 : fault == "reverse" ? 0.5 : fault == "duplicate" ? 0.6 : 0.8, deep: true)
            if fault == "overhead" { invalid = AutomaticPullUpBarTests().pose(0.8) }
            if fault == "missing" {
                invalid = .init(timestamp: invalid.timestamp, imageSize: invalid.imageSize,
                    people: [.init(landmarks: invalid.people[0].landmarks.filter { $0.joint != .rightWrist })],
                    backend: invalid.backend, requestRevision: invalid.requestRevision)
            }
            if fault == "size" {
                invalid = .init(timestamp: invalid.timestamp, imageSize: .init(width: 1100, height: 1100),
                    people: invalid.people, backend: invalid.backend, requestRevision: invalid.requestRevision)
            }
            _ = known.observe(invalid)
            #expect(known.apparatusCandidate != .dip)
            #expect(known.searchCandidate(in: pose(1.1, deep: true)) == nil)
        }
        var unknown = AutomaticExerciseDetection()
        for seconds in [0.0, 0.2, 0.4, 0.6, 0.8] {
            #expect(unknown.searchCandidate(in: pose(seconds, deep: true)) == nil)
            #expect(unknown.observe(pose(seconds, deep: true)) == nil)
            #expect(unknown.apparatusCandidate == nil)
        }
    }
}
