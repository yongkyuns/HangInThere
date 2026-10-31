import CoreGraphics
import CoreImage
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
    private let context = CIContext(options: [.cacheIntermediates: false])
    private static let sides: [[PoseJoint]] = [
        [.leftShoulder, .leftElbow, .leftWrist, .leftHip, .leftKnee, .leftAnkle],
        [.rightShoulder, .rightElbow, .rightWrist, .rightHip, .rightKnee, .rightAnkle]
    ]

    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        let direct = try strongestObservation(image: image, timestamp: timestamp)
        let completeBody = direct.people.contains { person in Self.sides.contains { joints in
            joints.allSatisfy { person.landmark($0) != nil }
        } }
        guard !completeBody else { return direct }
        // Give a truncated body room in the model's input. Padding contains no
        // body pixels, and every point outside the original image is discarded.
        let padding = Point2D(x: floor(Double(image.width) * 0.3), y: floor(Double(image.height) * 0.3))
        let canvas = CGRect(x: 0, y: 0, width: Double(image.width) + 2 * padding.x,
                            height: Double(image.height) + 2 * padding.y)
        let background = CIImage(color: CIColor(red: 0.18, green: 0.18, blue: 0.18)).cropped(to: canvas)
        let foreground = CIImage(cgImage: image).transformed(by: .init(translationX: padding.x, y: padding.y))
        guard let padded = context.createCGImage(foreground.composited(over: background), from: canvas) else { return direct }
        let candidate = try strongestObservation(image: padded, timestamp: timestamp,
            restoringTo: .init(width: Double(image.width), height: Double(image.height)), padding: padding)
        return Self.isBetter(candidate, than: direct) ? candidate : direct
    }

    private func strongestObservation(image: CGImage, timestamp: PresentationTime,
                                      restoringTo size: ImageSize? = nil,
                                      padding: Point2D = .init(x: 0, y: 0)) throws -> PoseResult {
        let estimator = VisionPoseEstimator()
        func restore(_ pose: PoseResult) -> PoseResult {
            size.map { Self.removingPadding(pose, imageSize: $0, padding: padding) } ?? pose
        }
        var best = restore(try estimator.estimate(image: image, timestamp: timestamp))
        for orientation in [CGImagePropertyOrientation.right, .left] {
            // Select using only evidence inside the camera image; a pose must
            // never win because the model placed extra joints in the padding.
            let candidate = restore(try estimator.estimate(image: image, timestamp: timestamp, orientation: orientation))
            if Self.isBetter(candidate, than: best) { best = candidate }
        }
        return best
    }

    static func removingPadding(_ pose: PoseResult, imageSize: ImageSize, padding: Point2D) -> PoseResult {
        let people = pose.people.compactMap { person -> PoseObservation? in
            let landmarks = person.landmarks.compactMap { landmark -> Landmark? in
                let point = Point2D(x: landmark.position.x - padding.x, y: landmark.position.y - padding.y)
                guard point.x >= 0, point.x <= imageSize.width, point.y >= 0, point.y <= imageSize.height else { return nil }
                return Landmark(joint: landmark.joint, position: point, confidence: landmark.confidence)
            }
            return landmarks.isEmpty ? nil : PoseObservation(landmarks: landmarks)
        }
        return PoseResult(timestamp: pose.timestamp, imageSize: imageSize, people: people,
                          backend: pose.backend, requestRevision: pose.requestRevision)
    }

    private static func hasCountingLandmarks(_ pose: PoseResult) -> Bool {
        pose.people.contains { person in sides.contains { joints in
            joints.prefix(4).allSatisfy { person.landmark($0) != nil } &&
                joints.suffix(2).contains { person.landmark($0) != nil }
        } }
    }

    private static func isBetter(_ candidate: PoseResult, than current: PoseResult) -> Bool {
        let candidateUsable = hasCountingLandmarks(candidate), currentUsable = hasCountingLandmarks(current)
        if candidateUsable != currentUsable { return candidateUsable }
        return visibilityScore(candidate) > visibilityScore(current)
    }

    static func visibilityScore(_ pose: PoseResult) -> Double {
        pose.people.map { person in sides.map { joints in
            let visible = joints.compactMap { person.landmark($0) }
            return Double(visible.count) + visible.map(\.confidence).reduce(0, +) / 10
        }.max() ?? 0 }.max() ?? 0
    }
}
