import Foundation
import Testing
@testable import HangInThere

struct LiveFramingAssessmentTests {
    @Test func oneVisibleSelectedArmIsReady() {
        let pose = makePose(side: .left)
        let result = LiveFramingAssessment(pose: pose, side: .left)
        #expect(result.state == .ready)
        #expect(result.state.isReady)
    }

    @Test func oppositeArmCannotSatisfySelectedSide() {
        let pose = makePose(side: .right)
        let result = LiveFramingAssessment(pose: pose, side: .left)
        #expect(result.state == .selectedArmHidden)
    }

    @Test func noPersonAndMultiplePeopleStayDistinct() {
        let empty = PoseResult(
            timestamp: PresentationTime(value: 0, timescale: 30),
            imageSize: ImageSize(width: 720, height: 1280),
            people: [],
            backend: "test",
            requestRevision: 0
        )
        #expect(LiveFramingAssessment(pose: empty, side: .left).state == .noPerson)

        let person = makePose(side: .left).people[0]
        let multiple = PoseResult(
            timestamp: PresentationTime(value: 0, timescale: 30),
            imageSize: ImageSize(width: 720, height: 1280),
            people: [person, person],
            backend: "test",
            requestRevision: 0
        )
        #expect(LiveFramingAssessment(pose: multiple, side: .left).state == .multiplePeople)
    }

    @Test func lowConfidenceArmIsNotReady() {
        let pose = makePose(side: .left, confidence: 0.1)
        let result = LiveFramingAssessment(pose: pose, side: .left)
        #expect(result.state == .selectedArmUnclear)
    }

    @Test func invalidImageGeometryDoesNotBecomeReady() {
        let base = makePose(side: .left)
        let pose = PoseResult(
            timestamp: base.timestamp,
            imageSize: ImageSize(width: 0, height: 1280),
            people: base.people,
            backend: base.backend,
            requestRevision: base.requestRevision
        )
        #expect(LiveFramingAssessment(pose: pose, side: .left).state == .analysisUnavailable)
    }

    @Test func setupReadinessRequiresCameraFramingAndBar() {
        #expect(
            LiveSetupReadiness(
                cameraReady: false,
                framing: .ready,
                barConfirmed: true
            ).state == .cameraUnavailable
        )
        #expect(
            LiveSetupReadiness(
                cameraReady: true,
                framing: .selectedArmHidden,
                barConfirmed: true
            ).state == .framingIncomplete
        )
        #expect(
            LiveSetupReadiness(
                cameraReady: true,
                framing: .ready,
                barConfirmed: false
            ).state == .barReferenceNeeded
        )
        #expect(
            LiveSetupReadiness(
                cameraReady: true,
                framing: .ready,
                barConfirmed: true
            ).state == .ready
        )
    }

    private func makePose(
        side: ArmMeasurement.Side,
        confidence: Double = 0.9
    ) -> PoseResult {
        let joints = side.joints
        let landmarks = [
            Landmark(joint: joints[0], position: Point2D(x: 300, y: 450), confidence: confidence),
            Landmark(joint: joints[1], position: Point2D(x: 340, y: 350), confidence: confidence),
            Landmark(joint: joints[2], position: Point2D(x: 310, y: 250), confidence: confidence)
        ]
        return PoseResult(
            timestamp: PresentationTime(value: 0, timescale: 30),
            imageSize: ImageSize(width: 720, height: 1280),
            people: [PoseObservation(landmarks: landmarks)],
            backend: "test",
            requestRevision: 0
        )
    }
}
