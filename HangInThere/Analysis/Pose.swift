import Foundation

// Analysis uses image pixels (top-left origin), never preview coordinates or SDK objects.
struct ImageSize: Sendable, Equatable {
    let width: Double
    let height: Double

    var isValid: Bool { width.isFinite && height.isFinite && width > 0 && height > 0 }
}

struct Point2D: Sendable, Equatable {
    let x: Double
    let y: Double
}

enum Joint: String, CaseIterable, Sendable {
    case nose, neck, root, leftEye, rightEye, leftEar, rightEar
    case leftShoulder, rightShoulder, leftElbow, rightElbow, leftWrist, rightWrist
    case leftHip, rightHip, leftKnee, rightKnee, leftAnkle, rightAnkle
}

struct Landmark: Sendable, Equatable {
    let position: Point2D
    let confidence: Float
}

struct PoseObservation: Sendable {
    let timestamp: Double
    let imageSize: ImageSize
    let personCount: Int
    let landmarks: [Joint: Landmark]

    var status: String {
        switch personCount {
        case 0: return "No person detected"
        case 1: return landmarks.isEmpty ? "Landmarks unavailable" : "Pose detected — not form-validated"
        default: return "Multiple people — pose withheld"
        }
    }
}

struct FitRect: Equatable, Sendable {
    let x: Double
    let y: Double
    let width: Double
    let height: Double

    func map(_ point: Point2D, from image: ImageSize) -> Point2D {
        Point2D(x: x + point.x * width / image.width,
                y: y + point.y * height / image.height)
    }
}

enum PoseGeometry {
    static func visionPoint(x: Double, y: Double, image: ImageSize) -> Point2D? {
        guard image.isValid, x.isFinite, y.isFinite,
              (0...1).contains(x), (0...1).contains(y) else { return nil }
        return Point2D(x: x * image.width, y: (1 - y) * image.height)
    }

    static func aspectFit(image: ImageSize, viewport: ImageSize) -> FitRect? {
        guard image.isValid, viewport.isValid else { return nil }
        let scale = min(viewport.width / image.width, viewport.height / image.height)
        let width = image.width * scale
        let height = image.height * scale
        return FitRect(x: (viewport.width - width) / 2,
                       y: (viewport.height - height) / 2, width: width, height: height)
    }

    // Interior image-plane angle; it is NOT a calibrated 3D anatomical angle.
    static func angle(shoulder: Point2D, elbow: Point2D, wrist: Point2D) -> Double? {
        let ux = shoulder.x - elbow.x, uy = shoulder.y - elbow.y
        let vx = wrist.x - elbow.x, vy = wrist.y - elbow.y
        let lu = hypot(ux, uy), lv = hypot(vx, vy)
        guard lu.isFinite, lv.isFinite, lu > 1e-6, lv > 1e-6 else { return nil }
        let cosine = (ux / lu) * (vx / lv) + (uy / lu) * (vy / lv)
        return acos(max(-1, min(1, cosine))) * 180 / .pi
    }
}

struct FrameTimeline: Sendable {
    private(set) var lastTimestamp: Double?
    private(set) var count = 0

    mutating func accept(_ timestamp: Double) throws {
        guard timestamp.isFinite else { throw TimelineError.nonfinite }
        if let previous = lastTimestamp, timestamp <= previous {
            throw TimelineError.nonmonotonic
        }
        lastTimestamp = timestamp
        count += 1
    }

    mutating func reset() { self = FrameTimeline() }
}

enum TimelineError: Error, Equatable { case nonfinite, nonmonotonic }

// AVFoundation's preferredTransform is expressed in source image coordinates.
// Only the eight orthogonal transforms are supported; reject shear/scale rather
// than silently misaligning landmarks. Translation only repositions the extent.
// Raw values are the EXIF orientation values consumed by Core Image.
struct VideoOrientation: Sendable, Equatable {
    let exif: Int32

    init(a: Double, b: Double, c: Double, d: Double) throws {
        let candidates: [(Double, Double, Double, Double, Int32)] = [
            (1, 0, 0, 1, 1), (-1, 0, 0, 1, 2), (-1, 0, 0, -1, 3),
            (1, 0, 0, -1, 4), (0, 1, 1, 0, 5), (0, 1, -1, 0, 6),
            (0, -1, -1, 0, 7), (0, -1, 1, 0, 8)
        ]
        guard let match = candidates.first(where: {
            abs(a - $0.0) < 1e-4 && abs(b - $0.1) < 1e-4 &&
            abs(c - $0.2) < 1e-4 && abs(d - $0.3) < 1e-4
        }) else { throw ReplayError.unsupportedTransform }
        exif = match.4
    }
}

enum ReplayError: LocalizedError {
    case noVideo, unsupportedTransform, decoderUnavailable, invalidFrame, emptyVideo

    var errorDescription: String? {
        switch self {
        case .noVideo: return "This file has no readable video track. Choose a local MOV or MP4 video."
        case .unsupportedTransform: return "This video's transform is unsupported. Export a standard, unwarped MOV or MP4."
        case .decoderUnavailable: return "The video decoder could not start. Try a different MOV or MP4 file."
        case .invalidFrame: return "The video contains an invalid image or timestamp."
        case .emptyVideo: return "The video did not contain any decodable frames."
        }
    }
}
