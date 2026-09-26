import CoreGraphics
import Vision

// Owned by VideoReplay's actor. No SDK request is shared between executors.
final class VisionPoseEstimator {
    private let request: VNDetectHumanBodyPoseRequest = {
        let request = VNDetectHumanBodyPoseRequest()
        request.revision = VNDetectHumanBodyPoseRequestRevision1
        return request
    }()

    func estimate(image: CGImage, timestamp: Double) throws -> PoseObservation {
        try VNImageRequestHandler(cgImage: image, orientation: .up).perform([request])
        let bodies = request.results ?? []
        let size = ImageSize(width: Double(image.width), height: Double(image.height))
        var landmarks: [Joint: Landmark] = [:]
        // P0 deliberately refuses to choose between people. Identity continuity
        // belongs in P1; choosing the largest person every frame would hide jumps.
        if bodies.count == 1, let body = bodies.first {
            let points = try body.recognizedPoints(.all)
            for (joint, key) in Self.keys {
                guard let point = points[key], point.confidence > 0,
                      let position = PoseGeometry.visionPoint(
                        x: point.location.x, y: point.location.y, image: size
                      ) else { continue }
                landmarks[joint] = Landmark(position: position, confidence: point.confidence)
            }
        }
        return PoseObservation(timestamp: timestamp, imageSize: size,
                               personCount: bodies.count, landmarks: landmarks)
    }

    private static let keys: [(Joint, VNHumanBodyPoseObservation.JointName)] = [
        (.nose, .nose), (.neck, .neck), (.root, .root),
        (.leftEye, .leftEye), (.rightEye, .rightEye),
        (.leftEar, .leftEar), (.rightEar, .rightEar),
        (.leftShoulder, .leftShoulder), (.rightShoulder, .rightShoulder),
        (.leftElbow, .leftElbow), (.rightElbow, .rightElbow),
        (.leftWrist, .leftWrist), (.rightWrist, .rightWrist),
        (.leftHip, .leftHip), (.rightHip, .rightHip),
        (.leftKnee, .leftKnee), (.rightKnee, .rightKnee),
        (.leftAnkle, .leftAnkle), (.rightAnkle, .rightAnkle)
    ]
}
