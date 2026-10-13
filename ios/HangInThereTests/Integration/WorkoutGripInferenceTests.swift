#if os(iOS)
import CoreGraphics
import XCTest
@testable import HangInThere

final class WorkoutGripInferenceTests: XCTestCase {
    /// Loads both pinned native models and runs body inference. A blank image must be unavailable evidence,
    /// never a fabricated grip, and SDK/model load errors fail this smoke test.
    func testPinnedModelsExecuteWithoutInventingHands() async throws {
        let context = try XCTUnwrap(CGContext(data: nil, width: 64, height: 64, bitsPerComponent: 8,
            bytesPerRow: 256, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        let image = try XCTUnwrap(context.makeImage())
        let joints: [PoseJoint] = [.leftShoulder,.rightShoulder,.leftHip,.rightHip,.leftWrist,.rightWrist]
        let pose = PoseResult(timestamp: PresentationTime(value: 0, timescale: 1000), imageSize: ImageSize(width: 64, height: 64),
            people: [PoseObservation(landmarks: joints.map { Landmark(joint: $0, position: Point2D(x: 32, y: 32), confidence: 1) })],
            backend: "authored SDK smoke", requestRevision: 0)
        let result = await GripInferenceWorker().estimate(image: image, pose: pose)
        XCTAssertEqual(result.failure, "bodyFrameUnavailable", "Bundled models and native runtime must execute")
        XCTAssertTrue(result.hands.isEmpty)
        XCTAssertEqual(result.seconds, 0)
        XCTAssertEqual(result.imageSize, pose.imageSize)
        XCTAssertEqual(result.trackedBody.count, 6)
        XCTAssertEqual(result.coordinateConvention, "cameraRightDownAway_v1")
    }
    func testPrimaryAndFallbackHandCropsDoNotInventHandsOnBlankPixels() async throws {
        let context = try XCTUnwrap(CGContext(data: nil, width: 256, height: 256, bitsPerComponent: 8,
            bytesPerRow: 1024, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        let image = try XCTUnwrap(context.makeImage())
        let points: [(PoseJoint,Point2D,WorkoutGripVector3)] = [
            (.leftShoulder,.init(x: 64,y: 128),.init(x: 0.2,y: -0.2,z: 0)),
            (.rightShoulder,.init(x: 192,y: 128),.init(x: -0.2,y: -0.2,z: 0)),
            (.leftHip,.init(x: 64,y: 210),.init(x: 0.2,y: 0.2,z: 0)),
            (.rightHip,.init(x: 192,y: 210),.init(x: -0.2,y: 0.2,z: 0)),
            (.leftWrist,.init(x: 64,y: 64),.init(x: 0.3,y: -0.4,z: 0)),
            (.rightWrist,.init(x: 192,y: 64),.init(x: -0.3,y: -0.4,z: 0))]
        let pose = PoseResult(timestamp: PresentationTime(value: 0, timescale: 1000), imageSize: ImageSize(width: 256, height: 256),
            people: [PoseObservation(landmarks: points.map { Landmark(joint: $0.0,position: $0.1,confidence: 1) })],
            backend: "authored blank hand crops", requestRevision: 0)
        let body = points.map { WorkoutGripBodyPoint(joint: $0.0.rawValue,position: $0.1,world: $0.2,score: 1) }
        let tracked = body.map { WorkoutGripTrackedPoint(joint: $0.joint,position: $0.position,score: 1) }
        let valid = WorkoutGripObservation(seconds: 0,imageSize: pose.imageSize,trackedBody: tracked,body: body,hands: [],modelId: "authored",backend: "authored")
        let invalid = WorkoutGripObservation(seconds: 0,imageSize: pose.imageSize,trackedBody: tracked,
            body: body.map { .init(joint: $0.joint,position: $0.position,world: .init(x: 0,y: 0,z: 0),score: 1) },hands: [],modelId: "authored",backend: "authored")
        let worker = GripInferenceWorker()
        let skipped = try await worker.estimateHands(image: image,pose: pose,context: invalid)
        XCTAssertEqual(skipped.modelCalls,0,"A rejected body frame must not run the hand model")
        XCTAssertTrue(skipped.hands.isEmpty)
        let result = try await worker.estimateHands(image: image,pose: pose,context: valid)
        XCTAssertEqual(result.modelCalls,4,"Both bounded crop candidates must actually execute for each wrist")
        XCTAssertTrue(result.hands.isEmpty,"Pinned native inference must not fabricate a hand on blank pixels")
        let stale = WorkoutGripObservation(seconds: 1,imageSize: pose.imageSize,trackedBody: tracked,body: body,hands: [],modelId: "authored",backend: "authored")
        let staleResult = try await worker.estimateHands(image: image,pose: pose,context: stale)
        XCTAssertEqual(staleResult.modelCalls,0,"A foreign source context cannot authorize hand inference")
    }
}
#endif
