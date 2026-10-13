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
}
#endif
