import Foundation
import Testing
@testable import HangInThere

struct ObservationTests {
    @Test(arguments: [0.0, -1.0, Double.nan, Double.infinity, 1.1, 0.29])
    func invalidOrLowConfidenceIsNotVisible(confidence: Double) {
        #expect(!Landmark(joint: .leftWrist, position: Point2D(x: 2, y: 3), confidence: confidence).isVisible())
    }

    @Test func absentJointIsNotReplacedWithZeroCoordinates() {
        let empty = PoseObservation(landmarks: [])
        #expect(empty.landmark(.leftWrist) == nil)
        #expect(empty.visibleLandmarkCount == 0)
    }

    @Test func visibilityRequiresFiniteGeometry() {
        #expect(!Landmark(joint: .nose, position: Point2D(x: .nan, y: 12), confidence: 1).isVisible())
        #expect(Landmark(joint: .nose, position: Point2D(x: 10, y: 12), confidence: 0.3).isVisible())
    }

    @Test func observationRoundTripPreservesTimeCoordinatesAndRevision() throws {
        let pose = PoseResult(timestamp: PresentationTime(value: 12345, timescale: 600),
                              imageSize: ImageSize(width: 1280, height: 720),
                              people: [PoseObservation(landmarks: [
                                Landmark(joint: .leftShoulder, position: Point2D(x: 123, y: 321), confidence: 0.8)
                              ])], backend: "test", requestRevision: 1)
        let data = try JSONEncoder().encode(pose)
        #expect(try JSONDecoder().decode(PoseResult.self, from: data) == pose)
    }

    @Test func peopleRemainSeparateObservations() {
        let first = PoseObservation(landmarks: [Landmark(joint: .leftWrist, position: Point2D(x: 1, y: 2), confidence: 1)])
        let second = PoseObservation(landmarks: [Landmark(joint: .rightWrist, position: Point2D(x: 3, y: 4), confidence: 1)])
        #expect(first.landmark(.rightWrist) == nil)
        #expect(second.landmark(.leftWrist) == nil)
    }
}