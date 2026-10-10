import Foundation
import Testing
@testable import HangInThere

@Suite struct AutomaticDipRailsTests {
    func pose(_ time: Double, degrees: Double = 170, shift: Double = 0) -> PoseResult {
        DipSupportTests.pose(time, degrees: degrees, shift: .init(x: shift, y: 0), otherDegrees: degrees)
    }
    func rail(_ x: Double, y: Double = 540, reversed: Bool = false) -> BarLineCandidate {
        let a = Point2D(x: x, y: y), b = Point2D(x: x, y: y + 120)
        return .init(edge: .init(a: reversed ? b : a, b: reversed ? a : b), geometryScore: 120)
    }
    var rails: [[BarLineCandidate]] { [[rail(490),rail(500)], [rail(690),rail(700)]] }

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

    @Test func loweredArmsMissingHandsOrMultiplePeopleCannotAcquire() {
        let base = pose(0)
        for invalid in [pose(0, degrees: 80),
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
