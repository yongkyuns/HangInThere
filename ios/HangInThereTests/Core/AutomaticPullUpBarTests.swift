import Foundation
import Testing
@testable import HangInThere

@Suite struct AutomaticPullUpBarTests {
    @Test func narrowMeasuredHandsAcquireOnlyObservedStableSupport() throws {
        func frame(_ t: Double) -> PoseResult {
            let points: [(PoseJoint,Double,Double)] = [
                (.leftShoulder,250,600),(.leftElbow,360,425),(.leftWrist,470,250),
                (.rightShoulder,650,600),(.rightElbow,590,425),(.rightWrist,530,250)]
            return PoseResult(timestamp:.init(value:Int64((t*1000).rounded()),timescale:1000),
                imageSize:.init(width:900,height:1400),people:[.init(landmarks:points.map {
                    .init(joint:$0.0,position:.init(x:$0.1,y:$0.2),confidence:0.9)
                })],backend:"authored",requestRevision:1)
        }
        let bar=BarSegment(a:.init(x:350,y:245),b:.init(x:650,y:245))
        #expect(AutomaticPullUpBar.grip(in:frame(0)) != nil)
        var acquisition=AutomaticPullUpBar()
        for t in [0.0,0.3,0.6] {
            #expect(acquisition.observe(pose:frame(t),candidates:[.init(edge:bar,geometryScore:300)]) == nil)
        }
        let confirmed=acquisition.observe(pose:frame(0.9),candidates:[.init(edge:bar,geometryScore:300)])
        #expect(try #require(confirmed).referenceEdge == bar)
        var identity=AutomaticExerciseDetection()
        for t in [1.0,1.2,1.4,1.6] {
            let observed=identity.observe(frame(t),reference:bar)
            #expect(observed == (t == 1.6 ? .pullUp : nil))
        }
        var noPixels=AutomaticPullUpBar()
        for t in [0.0,0.3,0.6,0.9] { #expect(noPixels.observe(pose:frame(t),candidates:[]) == nil) }
    }

    @Test func distinctWristsCannotHideCollapsedOrCrossedArms() {
        for fault in ["elbows","shoulders","crossedShoulders"] {
            let original=pose(0)
            let modified=original.people[0].landmarks.map { point -> Landmark in
                let position: Point2D
                if fault == "elbows", [.leftElbow,.rightElbow].contains(point.joint) {
                    position = .init(x:350,y:440)
                } else if fault == "shoulders", [.leftShoulder,.rightShoulder].contains(point.joint) {
                    position = .init(x:350,y:550)
                } else if fault == "crossedShoulders", point.joint == .leftShoulder {
                    position = .init(x:280,y:550)
                } else if fault == "crossedShoulders", point.joint == .rightShoulder {
                    position = .init(x:410,y:550)
                } else { position=point.position }
                return .init(joint:point.joint,position:position,confidence:point.confidence)
            }
            let frame=PoseResult(timestamp:original.timestamp,imageSize:original.imageSize,
                people:[.init(landmarks:modified)],backend:original.backend,requestRevision:original.requestRevision)
            #expect(PullUpArmMeasurement(pose:frame).arms.count == 2)
            #expect(AutomaticPullUpBar.grip(in:frame) == nil)
            var identity=AutomaticExerciseDetection()
            let confirmed=identity.confirmObservedPullUpMovement(frame,reference:candidate().edge)
            #expect(!confirmed)
        }
    }

    @Test func slopedEdgeUsesEachHandLocationWithoutRelaxingSideTolerance() throws {
        let size = ImageSize(width:720,height:1280)
        for slope in [-tan(10 * Double.pi/180),tan(10 * Double.pi/180)] {
            func edge(_ offset:Double) -> BarSegment {
                .init(a:.init(x:210,y:300+slope*(210-350)+offset),
                      b:.init(x:490,y:300+slope*(490-350)+offset))
            }
            let grip = AutomaticPullUpBar.Grip(
                left:.init(x:520,y:300+slope*(520-350)+3),
                right:.init(x:180,y:300+slope*(180-350)+3),
                region:.init(.init(x:100,y:200),.init(x:600,y:400)))
            let observed = BarLineCandidate(edge:edge(0),geometryScore:280)
            #expect(AutomaticPullUpBar.select(candidates:[observed],grip:grip,size:size) == observed.edge)
            #expect(AutomaticPullUpBar.select(candidates:[.init(edge:edge(28),geometryScore:280)],grip:grip,size:size) == nil)
            let reversed = BarLineCandidate(edge:.init(a:observed.edge.b,b:observed.edge.a),geometryScore:280)
            #expect(AutomaticPullUpBar.select(candidates:[reversed],grip:grip,size:size) == observed.edge)
        }
    }
    let size = ImageSize(width: 720, height: 1280)
    func pose(_ seconds: Double, offset: Double = 0) -> PoseResult {
        let points: [(PoseJoint, Double, Double)] = [
            (.leftWrist, 520 + offset, 340), (.leftElbow, 480 + offset, 440), (.leftShoulder, 410 + offset, 550),
            (.rightWrist, 180 + offset, 340), (.rightElbow, 210 + offset, 440), (.rightShoulder, 280 + offset, 550)
        ]
        return PoseResult(timestamp: .init(value: Int64((seconds * 1000).rounded()), timescale: 1000),
                          imageSize: size, people: [.init(landmarks: points.map {
            Landmark(joint: $0.0, position: .init(x: $0.1, y: $0.2), confidence: 0.9)
        })], backend: "synthetic", requestRevision: 1)
    }
    func candidate(_ y: Double = 300, x: Double = 210, end: Double = 490) -> BarLineCandidate {
        .init(edge: .init(a: .init(x: x, y: y), b: .init(x: end, y: y)), geometryScore: 280)
    }
    @Test func requiresObservedEdgeAndStableDwellThenLocksOneFixedLine() throws {
        var policy = AutomaticPullUpBar()
        for t in [0.0, 0.3, 0.6] { #expect(policy.observe(pose: pose(t), candidates: [candidate()]) == nil) }
        let proposal = policy.observe(pose: pose(1.0), candidates: [candidate()])
        let bar = try #require(proposal)
        #expect(bar.isValid)
        #expect(bar.method == .automaticContours)
        #expect(bar.referenceEdge == candidate().edge)
        #expect(bar.sourceTime == pose(1).timestamp)
        #expect(policy.state == .locked)
        // Subsequent wrist/edge movement cannot move the locked line.
        #expect(policy.observe(pose: pose(1.3, offset: 40), candidates: [candidate(320)]) == nil)
        #expect(policy.state == .locked)
        policy.reset()
        #expect(policy.state == .lookingForGrip)
        #expect(policy.observe(pose: pose(2), candidates: [candidate()]) == nil)
    }
    @Test func poseWithoutVisibleImageEvidenceCannotBecomeBar() {
        var policy = AutomaticPullUpBar()
        for t in stride(from: 0.0, through: 2.0, by: 0.3) {
            #expect(policy.observe(pose: pose(t), candidates: []) == nil)
        }
        #expect(policy.state == .findingEdge)
    }
    @Test func ambiguousBarsNeverChooseTheHighestScore() throws {
        let grip = try #require(AutomaticPullUpBar.grip(in: pose(0)))
        #expect(AutomaticPullUpBar.select(candidates: [candidate(290), candidate(320)], grip: grip, size: size) == nil)
        var policy = AutomaticPullUpBar()
        #expect(policy.observe(pose: pose(0), candidates: [candidate(290), candidate(320)]) == nil)
        #expect(policy.state == .ambiguous)
    }
    @Test func thinBarEdgesResolveToUpperObservedEdge() throws {
        let grip = try #require(AutomaticPullUpBar.grip(in: pose(0)))
        #expect(AutomaticPullUpBar.select(candidates: [candidate(310), candidate(300)], grip: grip, size: size) == candidate().edge)
    }
    @Test func rejectsOneHandSupportsBackgroundAndVerticalEdges() throws {
        let grip = try #require(AutomaticPullUpBar.grip(in: pose(0)))
        let vertical = BarLineCandidate(edge: .init(a: .init(x: 180, y: 200), b: .init(x: 180, y: 400)), geometryScore: 200)
        #expect(AutomaticPullUpBar.select(candidates: [candidate(200), candidate(370), candidate(x: 140, end: 220), vertical], grip: grip, size: size) == nil)
    }
    @Test func requiresOneAthleteBothHandsAndRaisedArms() {
        let original = pose(0)
        for people in [[], original.people + original.people,
                       [PoseObservation(landmarks: original.people[0].landmarks.filter { $0.joint != .rightWrist })],
                       [PoseObservation(landmarks: original.people[0].landmarks.map {
            Landmark(joint: $0.joint, position: $0.position, confidence: 0.2)
        })], [PoseObservation(landmarks: original.people[0].landmarks.map {
            Landmark(joint: $0.joint, position: .init(x: $0.position.x, y: $0.joint == .leftWrist ? 600 : $0.position.y), confidence: $0.confidence)
        })]] {
            let invalid = PoseResult(timestamp: original.timestamp, imageSize: size, people: people, backend: "synthetic", requestRevision: 1)
            #expect(AutomaticPullUpBar.grip(in: invalid) == nil)
        }
    }
    @Test func gapsMissingEvidenceAndDuplicateTimestampsResetDwell() {
        for interruption in ["gap", "missing", "duplicate", "changedEdge", "changedGrip"] {
            var policy = AutomaticPullUpBar()
            for t in [0.0, 0.3, 0.6] { #expect(policy.observe(pose: pose(t), candidates: [candidate()]) == nil) }
            switch interruption {
            case "gap": #expect(policy.observe(pose: pose(1.5), candidates: [candidate()]) == nil)
            case "missing": #expect(policy.observe(pose: pose(0.9), candidates: []) == nil)
            case "duplicate": #expect(policy.observe(pose: pose(0.6), candidates: [candidate()]) == nil)
            case "changedEdge": #expect(policy.observe(pose: pose(0.9), candidates: [candidate(315)]) == nil)
            default: #expect(policy.observe(pose: pose(0.9, offset: 20), candidates: [candidate()]) == nil)
            }
            #expect(policy.state != .locked)
        }
    }
    @Test func resizeResetsTemporalEvidence() {
        var policy = AutomaticPullUpBar()
        for t in [0.0, 0.3, 0.6] { _ = policy.observe(pose: pose(t), candidates: [candidate()]) }
        let original = pose(0.9)
        let resized = PoseResult(timestamp: original.timestamp, imageSize: .init(width: 800, height: 1400),
                                 people: original.people, backend: "synthetic", requestRevision: 1)
        #expect(policy.observe(pose: resized, candidates: [candidate()]) == nil)
        #expect(policy.state == .holding)
    }
}
