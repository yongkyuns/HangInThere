import XCTest
@testable import HangInThere

final class GeometryTests: XCTestCase {
    func testVisionCoordinatesUsePixelsAndTopLeftOrigin() throws {
        let image = ImageSize(width: 1920, height: 1080)
        XCTAssertEqual(PoseGeometry.visionPoint(x: 0.25, y: 0.75, image: image), Point2D(x: 480, y: 270))
        XCTAssertEqual(PoseGeometry.visionPoint(x: 0, y: 1, image: image), Point2D(x: 0, y: 0))
        XCTAssertNil(PoseGeometry.visionPoint(x: .nan, y: 0, image: image))
        XCTAssertNil(PoseGeometry.visionPoint(x: 1.1, y: 0, image: image))
        XCTAssertNil(PoseGeometry.visionPoint(x: 0, y: 0, image: ImageSize(width: 0, height: 10)))
    }

    func testAspectFitLandscapeLetterbox() throws {
        let image = ImageSize(width: 1920, height: 1080)
        let rect = try XCTUnwrap(PoseGeometry.aspectFit(image: image, viewport: ImageSize(width: 400, height: 400)))
        XCTAssertEqual(rect, FitRect(x: 0, y: 87.5, width: 400, height: 225))
        XCTAssertEqual(rect.map(Point2D(x: 960, y: 540), from: image), Point2D(x: 200, y: 200))
    }

    func testAspectFitPortraitAndInvalidViewport() throws {
        let image = ImageSize(width: 1080, height: 1920)
        XCTAssertEqual(PoseGeometry.aspectFit(image: image, viewport: ImageSize(width: 400, height: 400)),
                       FitRect(x: 87.5, y: 0, width: 225, height: 400))
        XCTAssertNil(PoseGeometry.aspectFit(image: image, viewport: ImageSize(width: .infinity, height: 400)))
    }

    func testAnglesAndUniformTransformInvariance() throws {
        let shoulder = Point2D(x: 20, y: 10), elbow = Point2D(x: 20, y: 30), wrist = Point2D(x: 40, y: 30)
        let angle = try XCTUnwrap(PoseGeometry.angle(shoulder: shoulder, elbow: elbow, wrist: wrist))
        XCTAssertEqual(angle, 90, accuracy: 1e-9)
        func transform(_ p: Point2D) -> Point2D { Point2D(x: p.x * 3 + 200, y: p.y * 3 - 70) }
        XCTAssertEqual(try XCTUnwrap(PoseGeometry.angle(shoulder: transform(shoulder), elbow: transform(elbow),
                                                      wrist: transform(wrist))), angle, accuracy: 1e-9)
        XCTAssertEqual(try XCTUnwrap(PoseGeometry.angle(shoulder: Point2D(x: 0, y: 0),
            elbow: Point2D(x: 0, y: 20), wrist: Point2D(x: 0, y: 40))), 180, accuracy: 1e-9)
        XCTAssertNil(PoseGeometry.angle(shoulder: elbow, elbow: elbow, wrist: wrist))
        XCTAssertNil(PoseGeometry.angle(shoulder: Point2D(x: .nan, y: 0), elbow: elbow, wrist: wrist))
    }

    func testEightVideoOrientationsAndUnsupportedWarp() throws {
        let matrices: [(Double, Double, Double, Double)] = [
            (1, 0, 0, 1), (-1, 0, 0, 1), (-1, 0, 0, -1), (1, 0, 0, -1),
            (0, 1, 1, 0), (0, 1, -1, 0), (0, -1, -1, 0), (0, -1, 1, 0)
        ]
        for (index, m) in matrices.enumerated() {
            XCTAssertEqual(try VideoOrientation(a: m.0, b: m.1, c: m.2, d: m.3).exif, Int32(index + 1))
        }
        XCTAssertThrowsError(try VideoOrientation(a: 1, b: 0.2, c: 0, d: 1))
        XCTAssertThrowsError(try VideoOrientation(a: 2, b: 0, c: 0, d: 2))
    }

    func testSourceTimelineRejectsDuplicateInvalidAndReversedTimestamps() throws {
        var timeline = FrameTimeline()
        try timeline.accept(5.25)
        try timeline.accept(5.29)
        XCTAssertThrowsError(try timeline.accept(5.29))
        XCTAssertThrowsError(try timeline.accept(4))
        XCTAssertThrowsError(try timeline.accept(.nan))
        XCTAssertThrowsError(try timeline.accept(.infinity))
        XCTAssertEqual(timeline.count, 2)
        XCTAssertEqual(timeline.lastTimestamp, 5.29)
        timeline.reset()
        try timeline.accept(0)
        XCTAssertEqual(timeline.count, 1)
    }
}
