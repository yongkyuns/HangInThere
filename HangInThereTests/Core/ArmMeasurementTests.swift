import Foundation
import Testing
@testable import HangInThere

struct ArmMeasurementTests {
    private let size = ImageSize(width: 960, height: 540)

    private func arm(_ side: ArmMeasurement.Side, degrees: Double = 90) -> [Landmark] {
        let theta = degrees * .pi / 180
        let points = [Point2D(x: 400, y: 200), Point2D(x: 400, y: 300),
                      Point2D(x: 400 + 100 * sin(theta), y: 300 - 100 * cos(theta))]
        return zip(side.joints, points).map { Landmark(joint: $0.0, position: $0.1, confidence: 0.8) }
    }

    private func pose(_ points: [Landmark], image: ImageSize? = nil) -> PoseResult {
        PoseResult(timestamp: PresentationTime(value: 123, timescale: 100), imageSize: image ?? size,
                   people: [PoseObservation(landmarks: points)], backend: "test", requestRevision: 1)
    }

    private func changed(_ points: [Landmark], at index: Int, position: Point2D? = nil,
                         confidence: Double? = nil) -> [Landmark] {
        var copy = points
        copy[index] = Landmark(joint: points[index].joint, position: position ?? points[index].position,
                               confidence: confidence ?? points[index].confidence)
        return copy
    }

    @Test(arguments: [30.0, 90.0, 160.0, 180.0])
    func bothArmsUseInteriorAngleInPixels(degrees: Double) throws {
        for side in ArmMeasurement.Side.allCases {
            let measured = ArmMeasurement(pose: pose(arm(side, degrees: degrees)), side: side)
            let value = try #require(measured.estimate)
            #expect(abs(value.elbowDegrees - degrees) < 1e-9)
            #expect(abs(value.upperArmPixels - 100) < 1e-9)
            #expect(abs(value.forearmPixels - 100) < 1e-9)
            #expect(value.minimumJointConfidence == 0.8)
            #expect(measured.unavailableReason == nil)
        }
    }

    @Test func missingWristIsNotFilledFromOtherArmOrPreviousFrame() {
        let full = arm(.left) + arm(.right)
        #expect(ArmMeasurement(pose: pose(full), side: .left).estimate != nil)
        let partial = full.filter { $0.joint != .leftWrist }
        let measured = ArmMeasurement(pose: pose(partial), side: .left)
        #expect(measured.unavailableReason == .missingJoint)
        #expect(measured.estimate == nil)
        #expect(ArmMeasurement(pose: pose(partial), side: .right).estimate != nil)
    }

    @Test func emptyAndMultiplePeopleNeverChooseAnArm() {
        let person = PoseObservation(landmarks: arm(.left))
        for people in [[], [person, person]] {
            let input = PoseResult(timestamp: PresentationTime(value: 0, timescale: 1), imageSize: size,
                                   people: people, backend: "test", requestRevision: 1)
            let measured = ArmMeasurement(pose: input, side: .left)
            #expect(measured.estimate == nil)
            #expect(measured.unavailableReason == (people.isEmpty ? .noPerson : .multiplePeople))
        }
    }

    @Test(arguments: [0.0, 0.1, 0.299999])
    func lowConfidenceInAnyJointSuppressesAngle(confidence: Double) {
        for index in 0..<3 {
            let input = pose(changed(arm(.left), at: index, confidence: confidence))
            let measured = ArmMeasurement(pose: input, side: .left)
            #expect(measured.unavailableReason == .lowConfidence)
            #expect(measured.estimate == nil)
        }
    }

    @Test func thresholdAndMinimumScoreAreExplicit() throws {
        let input = pose(changed(arm(.left), at: 2, confidence: 0.3))
        let value = try #require(ArmMeasurement(pose: input, side: .left).estimate)
        #expect(value.minimumJointConfidence == 0.3)
        #expect(ArmMeasurement.confidenceThreshold == 0.3)
        #expect(ArmMeasurement.minimumSegmentFraction == 0.02)
        #expect(ArmMeasurement.policyVersion == 1)
    }

    @Test(arguments: [-0.1, 1.1, Double.nan, Double.infinity])
    func invalidScoresNeverBecomeMeasurements(confidence: Double) {
        let measured = ArmMeasurement(pose: pose(changed(arm(.left), at: 0, confidence: confidence)), side: .left)
        #expect(measured.unavailableReason == .invalidJoint)
        #expect(measured.estimate == nil)
    }

    @Test func duplicateJointIsNotResolvedByConfidenceOrArrayOrder() {
        for score in [0.1, 0.9] {
            let points = arm(.left) + [Landmark(joint: .leftWrist, position: Point2D(x: 300, y: 300), confidence: score)]
            for order in [points, Array(points.reversed())] {
                let measured = ArmMeasurement(pose: pose(order), side: .left)
                #expect(measured.unavailableReason == .duplicateJoint)
                #expect(measured.estimate == nil)
            }
        }
    }

    @Test func invalidOrOutsideImagePointsAreNotClamped() {
        for point in [Point2D(x: -1, y: 200), Point2D(x: 961, y: 200),
                      Point2D(x: 200, y: 541), Point2D(x: .nan, y: 0)] {
            let measured = ArmMeasurement(pose: pose(changed(arm(.left), at: 1, position: point)), side: .left)
            #expect(measured.unavailableReason == .invalidJoint)
            #expect(measured.estimate == nil)
        }
    }

    @Test func invalidImageGeometryIsUnavailable() {
        for image in [ImageSize(width: 0, height: 540), ImageSize(width: .infinity, height: 540),
                      ImageSize(width: 960, height: .nan)] {
            let measured = ArmMeasurement(pose: pose(arm(.left), image: image), side: .left)
            #expect(measured.unavailableReason == .invalidImageSize)
            #expect(measured.estimate == nil)
        }
    }

    @Test func shortProjectedSegmentsAndZeroLengthAreUnavailable() {
        for offset in [0.0, 1.0, 10.0] {
            for index in [0, 2] {
                let point = Point2D(x: 400 + offset, y: 300)
                let measured = ArmMeasurement(pose: pose(changed(arm(.left), at: index, position: point)), side: .left)
                #expect(measured.unavailableReason == .shortProjectedSegment)
                #expect(measured.estimate == nil)
            }
        }
    }

    @Test func uniformResizingPreservesAngleAndAvailability() throws {
        for base in [arm(.left, degrees: 160), changed(arm(.left), at: 2, position: Point2D(x: 401, y: 300))] {
            let original = ArmMeasurement(pose: pose(base), side: .left)
            for scale in [0.25, 0.5, 2.0] {
                let scaled = base.map { Landmark(joint: $0.joint,
                    position: Point2D(x: $0.position.x * scale, y: $0.position.y * scale), confidence: $0.confidence) }
                let measured = ArmMeasurement(pose: pose(scaled, image: ImageSize(width: size.width * scale,
                                                                               height: size.height * scale)), side: .left)
                #expect(measured.unavailableReason == original.unavailableReason)
                if let expected = original.estimate {
                    let value = try #require(measured.estimate)
                    #expect(abs(value.elbowDegrees - expected.elbowDegrees) < 1e-9)
                    #expect(abs(value.upperArmPixels - expected.upperArmPixels * scale) < 1e-9)
                } else { #expect(measured.estimate == nil) }
            }
        }
    }

    @Test func translationAndMirroringDoNotChangeTheAngleOrSide() throws {
        let base = arm(.left, degrees: 160)
        let expected = try #require(ArmMeasurement(pose: pose(base), side: .left).estimate)
        for reflected in [false, true] {
            let transformed = base.map { Landmark(joint: $0.joint, position: Point2D(
                x: reflected ? size.width - $0.position.x : $0.position.x + 20,
                y: $0.position.y + 10), confidence: $0.confidence) }
            let measured = ArmMeasurement(pose: pose(transformed), side: .left)
            let value = try #require(measured.estimate)
            #expect(abs(value.elbowDegrees - expected.elbowDegrees) < 1e-9)
            #expect(measured.side == .left)
        }
    }

    @Test func serializationKeepsUnavailableDistinctFromZero() throws {
        for input in [pose(arm(.right)), pose([])] {
            let measurement = ArmMeasurement(pose: input, side: .right)
            let bytes = try JSONEncoder().encode(measurement)
            let decoded = try JSONDecoder().decode(ArmMeasurement.self, from: bytes)
            #expect(decoded == measurement)
            #expect((decoded.estimate == nil) != (decoded.unavailableReason == nil))
        }
    }
}
