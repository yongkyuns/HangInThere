import Foundation
import Testing
@testable import HangInThere

struct GeometryTests {
    @Test func visionCoordinatesUseUprightPixels() throws {
        let point = try #require(PoseGeometry.visionPoint(Point2D(x: 0.25, y: 0.75),
                                                        imageSize: ImageSize(width: 1920, height: 1080)))
        #expect(point == Point2D(x: 480, y: 270))
    }

    @Test func invalidCoordinatesAreNotFabricated() {
        #expect(PoseGeometry.visionPoint(Point2D(x: .nan, y: 0), imageSize: ImageSize(width: 100, height: 100)) == nil)
        #expect(PoseGeometry.visionPoint(Point2D(x: 1.1, y: 0), imageSize: ImageSize(width: 100, height: 100)) == nil)
        #expect(PoseGeometry.visionPoint(Point2D(x: 0.5, y: 0.5), imageSize: ImageSize(width: 0, height: 100)) == nil)
    }

    @Test(arguments: [0.0, 30.0, 90.0, 160.0, 180.0])
    func knownElbowAngles(degrees: Double) throws {
        let radians = degrees * .pi / 180
        let angle = try #require(PoseGeometry.elbowAngle(
            shoulder: Point2D(x: 100, y: 0), elbow: Point2D(x: 0, y: 0),
            wrist: Point2D(x: 100 * cos(radians), y: 100 * sin(radians))))
        #expect(abs(angle - degrees) < 1e-6)
    }

    @Test func elbowAngleRejectsMissingLengthAndNonfiniteData() {
        let zero = Point2D(x: 0, y: 0)
        #expect(PoseGeometry.elbowAngle(shoulder: zero, elbow: zero, wrist: Point2D(x: 1, y: 0)) == nil)
        #expect(PoseGeometry.elbowAngle(shoulder: Point2D(x: .infinity, y: 2), elbow: zero,
                                       wrist: Point2D(x: 0, y: 1)) == nil)
    }

    @Test func anglesAreTranslationAndUniformScaleInvariant() throws {
        let points = [Point2D(x: 12, y: 35), Point2D(x: 41, y: 77), Point2D(x: 19, y: 127)]
        let expected = try #require(PoseGeometry.elbowAngle(shoulder: points[0], elbow: points[1], wrist: points[2]))
        for scale in [0.01, 0.5, 1.0, 3.0, 100.0] {
            let p = points.map { Point2D(x: $0.x * scale + 123, y: $0.y * scale - 85) }
            let actual = try #require(PoseGeometry.elbowAngle(shoulder: p[0], elbow: p[1], wrist: p[2]))
            #expect(abs(actual - expected) < 1e-8)
        }
    }

    @Test func nonSquareImageIsNotAnisotropicallyNormalized() throws {
        let size = ImageSize(width: 200, height: 100)
        let shoulder = try #require(PoseGeometry.visionPoint(Point2D(x: 1, y: 1), imageSize: size))
        let elbow = try #require(PoseGeometry.visionPoint(Point2D(x: 0.5, y: 0), imageSize: size))
        let wrist = try #require(PoseGeometry.visionPoint(Point2D(x: 1, y: 0), imageSize: size))
        let angle = try #require(PoseGeometry.elbowAngle(shoulder: shoulder, elbow: elbow, wrist: wrist))
        #expect(abs(angle - 45) < 1e-8)
    }

    @Test func aspectFitIncludesLetterboxAndIsReversible() throws {
        let fit = try #require(AspectFit(image: ImageSize(width: 1920, height: 1080),
                                        viewport: ImageSize(width: 400, height: 400)))
        #expect(abs(fit.offset.y - 87.5) < 1e-10)
        let original = Point2D(x: 960, y: 540)
        let displayed = fit.displayPoint(original)
        #expect(displayed == Point2D(x: 200, y: 200))
        #expect(fit.imagePoint(displayed) == original)
        #expect(AspectFit(image: ImageSize(width: 0, height: 100),
                          viewport: ImageSize(width: 400, height: 400)) == nil)
    }

    @Test func allEightImageOrientationsHaveConsistentOrigins() throws {
        let w = 640.0, h = 360.0
        let transforms = [
            Affine2D(), Affine2D(a: -1, d: 1, tx: w),
            Affine2D(a: -1, d: -1, tx: w, ty: h), Affine2D(a: 1, d: -1, ty: h),
            Affine2D(a: 0, b: 1, c: 1, d: 0),
            Affine2D(a: 0, b: 1, c: -1, d: 0, tx: h),
            Affine2D(a: 0, b: -1, c: -1, d: 0, tx: h, ty: w),
            Affine2D(a: 0, b: -1, c: 1, d: 0, ty: w)
        ]
        for transform in transforms {
            let geometry = try #require(OrientedGeometry(sourceSize: ImageSize(width: w, height: h),
                                                        preferredTransform: transform))
            for p in [Point2D(x: 0, y: 0), Point2D(x: 13, y: 97), Point2D(x: w, y: h)] {
                let top = geometry.topLeftTransform.apply(p)
                let ci = geometry.coreImageTransform.apply(Point2D(x: p.x, y: h - p.y))
                #expect(abs(ci.x - top.x) < 1e-9)
                #expect(abs(ci.y - (geometry.outputSize.height - top.y)) < 1e-9)
                #expect(top.x >= 0 && top.x <= geometry.outputSize.width)
                #expect(top.y >= 0 && top.y <= geometry.outputSize.height)
            }
        }
    }

    @Test func clockwiseQuarterTurnHasKnownCornerMapping() throws {
        let geometry = try #require(OrientedGeometry(
            sourceSize: ImageSize(width: 640, height: 360),
            preferredTransform: Affine2D(a: 0, b: 1, c: -1, d: 0, tx: 360)))
        #expect(geometry.outputSize == ImageSize(width: 360, height: 640))
        #expect(geometry.topLeftTransform.apply(Point2D(x: 0, y: 0)) == Point2D(x: 360, y: 0))
        #expect(geometry.topLeftTransform.apply(Point2D(x: 640, y: 360)) == Point2D(x: 0, y: 640))
    }

    @Test func trackTranslationDoesNotBecomePreviewOffset() throws {
        let geometry = try #require(OrientedGeometry(sourceSize: ImageSize(width: 640, height: 360),
                                                    preferredTransform: Affine2D(tx: -850, ty: 140)))
        #expect(geometry.topLeftTransform == Affine2D())
        #expect(geometry.outputSize == ImageSize(width: 640, height: 360))
    }

    @Test func invalidTrackTransformsAreRejected() {
        let size = ImageSize(width: 100, height: 100)
        #expect(OrientedGeometry(sourceSize: size, preferredTransform: Affine2D(a: 0, d: 0)) == nil)
        #expect(OrientedGeometry(sourceSize: size, preferredTransform: Affine2D(tx: .nan)) == nil)
        #expect(OrientedGeometry(sourceSize: ImageSize(width: -1, height: 100), preferredTransform: Affine2D()) == nil)
    }
}