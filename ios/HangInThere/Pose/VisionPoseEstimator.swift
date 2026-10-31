import CoreGraphics
import ImageIO
import Vision

// Estimators can be supplied to a replay actor without weakening isolation.
protocol PoseEstimator: Sendable {
    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult
}

struct VisionPoseEstimator: PoseEstimator {
    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        try estimate(image: image, timestamp: timestamp, orientation: .up)
    }

    func estimate(image: CGImage, timestamp: PresentationTime, orientation: CGImagePropertyOrientation) throws -> PoseResult {
        let request = VNDetectHumanBodyPoseRequest()
        request.revision = VNDetectHumanBodyPoseRequestRevision1
        // Capture/track rotation is already in the pixels. Optional inference
        // rotation only helps the body model; landmarks return to source pixels.
        try VNImageRequestHandler(cgImage: image, orientation: orientation, options: [:]).perform([request])
        let size = ImageSize(width: Double(image.width), height: Double(image.height))
        let people = try (request.results ?? []).map { observation in
            let points = try observation.recognizedPoints(.all)
            let landmarks = Self.joints.compactMap { joint, visionJoint -> Landmark? in
                guard let point = points[visionJoint], point.confidence > 0,
                      let position = Self.imagePoint(Point2D(x: point.location.x, y: point.location.y),
                        imageSize: size, orientation: orientation) else { return nil }
                return Landmark(joint: joint, position: position, confidence: Double(point.confidence))
            }
            return PoseObservation(landmarks: landmarks)
        }
        return PoseResult(timestamp: timestamp, imageSize: size, people: people,
                          backend: "Apple Vision 2D", requestRevision: request.revision)
    }

    static func imagePoint(_ point: Point2D, imageSize: ImageSize, orientation: CGImagePropertyOrientation) -> Point2D? {
        let sideways = orientation == .right || orientation == .left
        let orientedSize = sideways ? ImageSize(width: imageSize.height, height: imageSize.width) : imageSize
        guard let p = PoseGeometry.visionPoint(point, imageSize: orientedSize) else { return nil }
        switch orientation {
        case .up: return p
        case .right: return Point2D(x: p.y, y: imageSize.height - p.x)
        case .left: return Point2D(x: imageSize.width - p.y, y: p.x)
        default: return nil
        }
    }

    private static let joints: [(PoseJoint, VNHumanBodyPoseObservation.JointName)] = [
        (.nose, .nose), (.neck, .neck), (.root, .root),
        (.leftEye, .leftEye), (.rightEye, .rightEye), (.leftEar, .leftEar), (.rightEar, .rightEar),
        (.leftShoulder, .leftShoulder), (.rightShoulder, .rightShoulder),
        (.leftElbow, .leftElbow), (.rightElbow, .rightElbow),
        (.leftWrist, .leftWrist), (.rightWrist, .rightWrist),
        (.leftHip, .leftHip), (.rightHip, .rightHip), (.leftKnee, .leftKnee), (.rightKnee, .rightKnee),
        (.leftAnkle, .leftAnkle), (.rightAnkle, .rightAnkle)
    ]
}

/// Horizontal bodies can lose hips/knees in Vision's default upright pass.
/// Select one complete SDK observation, never combine joints across passes.
/// All passes use the same source frame and the existing visibility threshold.
struct SideViewVisionPoseEstimator: PoseEstimator {
    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        let estimator = VisionPoseEstimator()
        var best = try estimator.estimate(image: image, timestamp: timestamp)
        for orientation in [CGImagePropertyOrientation.right, .left] {
            let candidate = try estimator.estimate(image: image, timestamp: timestamp, orientation: orientation)
            if Self.visibilityScore(candidate) > Self.visibilityScore(best) { best = candidate }
        }
        return best
    }

    static func visibilityScore(_ pose: PoseResult) -> Double {
        pose.people.map { person in
            let sides: [[PoseJoint]] = [
                [.leftShoulder, .leftElbow, .leftWrist, .leftHip, .leftKnee, .leftAnkle],
                [.rightShoulder, .rightElbow, .rightWrist, .rightHip, .rightKnee, .rightAnkle]
            ]
            return sides.map { joints in
                let visible = joints.compactMap { person.landmark($0) }
                return Double(visible.count) + visible.map(\.confidence).reduce(0, +) / 10
            }.max() ?? 0
        }.max() ?? 0
    }
}
