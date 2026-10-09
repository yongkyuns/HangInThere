import Foundation
import Testing
@testable import HangInThere

@Suite struct AutomaticPullUpBarTests {
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
