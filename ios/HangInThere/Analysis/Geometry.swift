import Foundation

struct Affine2D: Equatable, Sendable {
    var a: Double = 1
    var b: Double = 0
    var c: Double = 0
    var d: Double = 1
    var tx: Double = 0
    var ty: Double = 0

    func apply(_ point: Point2D) -> Point2D {
        Point2D(x: a * point.x + c * point.y + tx,
                y: b * point.x + d * point.y + ty)
    }

    var isFinite: Bool { [a, b, c, d, tx, ty].allSatisfy(\.isFinite) }
}

struct OrientedGeometry: Sendable {
    let sourceSize: ImageSize
    let outputSize: ImageSize
    let topLeftTransform: Affine2D
    let coreImageTransform: Affine2D

    // A track transform is defined in video (top-left) coordinates. Core Image
    // uses a bottom-left origin. Conjugate by the two y-flips rather than blindly
    // applying the track matrix to a CIImage (which reverses portrait rotations).
    init?(sourceSize: ImageSize, preferredTransform t: Affine2D) {
        guard sourceSize.isValid, t.isFinite,
              abs(t.a * t.d - t.b * t.c) > 1e-9 else { return nil }
        let corners = [Point2D(x: 0, y: 0), Point2D(x: sourceSize.width, y: 0),
                       Point2D(x: 0, y: sourceSize.height),
                       Point2D(x: sourceSize.width, y: sourceSize.height)].map(t.apply)
        guard corners.allSatisfy(\.isFinite),
              let minX = corners.map(\.x).min(), let maxX = corners.map(\.x).max(),
              let minY = corners.map(\.y).min(), let maxY = corners.map(\.y).max() else { return nil }
        let size = ImageSize(width: maxX - minX, height: maxY - minY)
        guard size.isValid else { return nil }
        self.sourceSize = sourceSize
        outputSize = size
        topLeftTransform = Affine2D(a: t.a, b: t.b, c: t.c, d: t.d,
                                   tx: t.tx - minX, ty: t.ty - minY)
        coreImageTransform = Affine2D(a: t.a, b: -t.b, c: -t.c, d: t.d,
                                      tx: t.c * sourceSize.height + t.tx - minX,
                                      ty: size.height - t.d * sourceSize.height - t.ty + minY)
    }
}

enum PoseGeometry {
    static func visionPoint(_ point: Point2D, imageSize: ImageSize) -> Point2D? {
        guard point.isFinite, imageSize.isValid,
              (0...1).contains(point.x), (0...1).contains(point.y) else { return nil }
        return Point2D(x: point.x * imageSize.width, y: (1 - point.y) * imageSize.height)
    }

    // Interior image-plane angle. A straight arm is 180 degrees. This is not a
    // measured anatomical 3D angle, even when the landmarks look plausible.
    static func elbowAngle(shoulder: Point2D, elbow: Point2D, wrist: Point2D) -> Double? {
        guard [shoulder, elbow, wrist].allSatisfy(\.isFinite) else { return nil }
        let ux = shoulder.x - elbow.x, uy = shoulder.y - elbow.y
        let vx = wrist.x - elbow.x, vy = wrist.y - elbow.y
        let u = hypot(ux, uy), v = hypot(vx, vy)
        guard u > 1e-6, v > 1e-6, u.isFinite, v.isFinite else { return nil }
        let cosine = (ux / u) * (vx / v) + (uy / u) * (vy / v)
        return acos(min(1, max(-1, cosine))) * 180 / .pi
    }
}

struct AspectFit: Sendable {
    let scale: Double
    let offset: Point2D

    init?(image: ImageSize, viewport: ImageSize) {
        guard image.isValid, viewport.isValid else { return nil }
        scale = min(viewport.width / image.width, viewport.height / image.height)
        offset = Point2D(x: (viewport.width - image.width * scale) / 2,
                         y: (viewport.height - image.height * scale) / 2)
    }

    func displayPoint(_ point: Point2D) -> Point2D {
        Point2D(x: offset.x + point.x * scale, y: offset.y + point.y * scale)
    }

    func imagePoint(_ point: Point2D) -> Point2D {
        Point2D(x: (point.x - offset.x) / scale, y: (point.y - offset.y) / scale)
    }
}