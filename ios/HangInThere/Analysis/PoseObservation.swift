import Foundation

// All geometry is in unmirrored, upright image pixels, origin at top left.
// These values have no dependency on the pose SDK or the user interface.
struct Point2D: Codable, Equatable, Sendable {
    let x: Double
    let y: Double

    var isFinite: Bool { x.isFinite && y.isFinite }
}

struct ImageSize: Codable, Equatable, Sendable {
    let width: Double
    let height: Double

    var isValid: Bool {
        width.isFinite && height.isFinite && width > 0 && height > 0
    }
}

enum PoseJoint: String, CaseIterable, Codable, Sendable {
    case nose, neck, root
    case leftEye, rightEye, leftEar, rightEar
    case leftShoulder, rightShoulder, leftElbow, rightElbow, leftWrist, rightWrist
    case leftHip, rightHip, leftKnee, rightKnee, leftAnkle, rightAnkle
}

struct Landmark: Codable, Equatable, Sendable {
    let joint: PoseJoint
    let position: Point2D
    let confidence: Double

    // A display threshold, not a calibrated probability or a form decision.
    func isVisible(minimumConfidence: Double = 0.3) -> Bool {
        position.isFinite && confidence.isFinite && confidence > 0
            && confidence >= minimumConfidence && confidence <= 1
    }
}

struct PoseObservation: Codable, Equatable, Sendable {
    let landmarks: [Landmark]

    func landmark(_ joint: PoseJoint, minimumConfidence: Double = 0.3) -> Landmark? {
        landmarks.first { $0.joint == joint && $0.isVisible(minimumConfidence: minimumConfidence) }
    }

    var visibleLandmarkCount: Int { landmarks.filter { $0.isVisible() }.count }
}

struct PresentationTime: Codable, Equatable, Sendable {
    let value: Int64
    let timescale: Int32

    var seconds: Double { timescale > 0 ? Double(value) / Double(timescale) : .nan }
}

struct PoseResult: Codable, Equatable, Sendable {
    let timestamp: PresentationTime
    let imageSize: ImageSize
    let people: [PoseObservation]
    let backend: String
    let requestRevision: Int

    // P0 draws each returned skeleton separately; it does not claim to select or
    // persist the identity of an athlete. Counting/identity continuity is P1/P2.
}