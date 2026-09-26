import CoreGraphics
import ImageIO
import Vision

protocol PoseEstimator {
    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult
}

struct VisionPoseEstimator: PoseEstimator {
    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        let request = VNDetectHumanBodyPoseRequest()
        request.revision = VNDetectHumanBodyPoseRequestRevision1
        // The pixels are already oriented. Do not apply the track orientation again.
        try VNImageRequestHandler(cgImage: image, orientation: .up, options: [:]).perform([request])
        let size = ImageSize(width: Double(image.width), height: Double(image.height))
        let people = try (request.results ?? []).map { observation in
            let points = try observation.recognizedPoints(.all)
            let landmarks = Self.joints.compactMap { joint, visionJoint -> Landmark? in
                guard let point = points[visionJoint], point.confidence > 0,
                      let position = PoseGeometry.visionPoint(
                        Point2D(x: point.location.x, y: point.location.y), imageSize: size
                      ) else { return nil }
                return Landmark(joint: joint, position: position, confidence: Double(point.confidence))
            }
            return PoseObservation(landmarks: landmarks)
        }
        return PoseResult(timestamp: timestamp, imageSize: size, people: people,
                          backend: "Apple Vision 2D", requestRevision: request.revision)
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