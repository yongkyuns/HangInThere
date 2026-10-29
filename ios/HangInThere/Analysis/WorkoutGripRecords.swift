import Foundation
import WorkoutCore

/// Presentation of the shared body-scale/hand-visibility policy. A green card
/// confirms capture evidence only; it does not claim a classified grip.
struct WorkoutGripCaptureFeedback: Codable, Equatable, Sendable {
    var state: String
    var shoulderFraction: Double? = nil
    var torsoFraction: Double? = nil
    var leftHand = "waiting"
    var rightHand = "waiting"
    var observationAge: Double? = nil
    var minimumShoulderFraction = 0.18
    var minimumTorsoFraction = 0.28
    var policyVersion = 1
    var bodyContext: String? = nil
    var failure: String? = nil

    var isGood: Bool { state == "handsDetected" }
    var isChecking: Bool { state == "checking" }
    var title: String {
        switch state {
        case "moveCloser": "MOVE PHONE CLOSER"
        case "handsClipped": "KEEP HANDS IN FRAME"
        case "showHands": "SHOW BOTH HANDS"
        case "frontRear": "USE FRONT OR REAR VIEW"
        case "showUpperBody": "SHOW YOUR UPPER BODY"
        case "stepIntoFrame": "STEP INTO FRAME"
        case "oneAthlete": "ONE ATHLETE IN VIEW"
        case "handsDetected": "BOTH HANDS DETECTED"
        case "analysisUnavailable", "unavailable": "CHECK CAMERA ANALYSIS"
        default: "CHECKING HANDS"
        }
    }
    var detail: String {
        switch state {
        case "moveCloser": "You look too small in frame. Move the phone closer; keep both hands and your upper body visible."
        case "handsClipped": "Leave space around both hands. Move or tilt the phone to include them."
        case "showHands": "One or both hands aren't detected. Keep them separate and unobstructed."
        case "frontRear": "Position the phone toward your front or back so both hands are clear."
        case "showUpperBody": "Keep your shoulders and hips visible so camera distance can be checked."
        case "stepIntoFrame": "Keep your upper body and both gripping hands in view."
        case "oneAthlete": "Keep other people outside the camera view."
        case "handsDetected": "Body scale is sufficient and both hands are detected. Grip classification is checked separately."
        case "analysisUnavailable", "unavailable": "Camera or hand analysis is unavailable. This isn't a distance warning."
        default:
            if bodyContext == "bodyFrameUnavailable" {
                "Upper-body orientation isn't available yet. Keep shoulders and hips visible from the front or rear."
            } else if bodyContext == "athleteMismatch" {
                "Waiting for hand analysis that matches your current body position. Keep one athlete clearly in view."
            } else {
                "Waiting for fresh hand analysis. Keep both hands and your upper body in view."
            }
        }
    }
    static func handTitle(_ value: String) -> String {
        switch value {
        case "detected": "Seen"
        case "notDetected": "Not seen"
        case "wristUnclear": "Wrist unclear"
        case "edge": "At edge"
        default: "Checking"
        }
    }
    var handsDiagnostic: String {
        "L: \(Self.handTitle(leftHand))  ·  R: \(Self.handTitle(rightHand))"
    }
    var bodyDiagnostic: String {
        func percent(_ value: Double?) -> String { value.map { String(format: "%.0f%%", $0 * 100) } ?? "—" }
        return "Shoulders \(percent(shoulderFraction)) · Torso \(percent(torsoFraction))"
    }
    var targetDiagnostic: String {
        String(format: "Size target: shoulders ≥%.0f%% or torso ≥%.0f%%", minimumShoulderFraction * 100, minimumTorsoFraction * 100)
    }
    var contextDiagnostic: String {
        let title = switch bodyContext {
        case "frameMatched": "Body and view matched"
        case "bodyFrameUnavailable": "Body orientation unavailable"
        case "athleteMismatch": "Body position hasn't matched"
        case "viewUnsupported": "Side view unsupported"
        case "staleObservation": "Hand result expired"
        case "imageMismatch": "Camera image size changed"
        case "timestampMismatch": "Hand result time doesn't match"
        case "inferenceFailure": "Inference unavailable"
        default: "Waiting for body / hand result"
        }
        return failure.map { "\(title) · \($0)" } ?? title
    }
}

/// Optional grip metadata guidance. It never participates in set readiness,
/// exercise selection, or rep counting. Uses the exact shared view gate.
struct WorkoutGripViewFeedback {
    static let repositionPrompt = "Move camera toward your front or back"
    let prompt: String?

    init(pose: PoseResult?) {
        guard let pose, pose.people.count == 1,
              let observation = pose.gripObservation,
              observation.imageSize == pose.imageSize,
              pose.timestamp.seconds.isFinite, observation.seconds.isFinite,
              pose.timestamp.seconds >= observation.seconds,
              pose.timestamp.seconds - observation.seconds <= 0.5,
              let input = try? JSONEncoder().encode(observation),
              let raw = try? WorkoutGripInferenceBridge().orientationJson(observationJson: String(decoding: input, as: UTF8.self)),
              let estimate = try? JSONDecoder().decode(WorkoutGripEstimate.self, from: Data(raw.utf8)) else {
            prompt = nil
            return
        }
        prompt = estimate.reason == "gripViewUnsupported" ? Self.repositionPrompt : nil
    }
}

// SDK-free, versioned transport. Optional depth/score stays absent when unavailable.
struct WorkoutGripVector3: Codable, Equatable, Sendable { let x: Double; let y: Double; let z: Double }
struct WorkoutGripTrackedPoint: Codable, Equatable, Sendable { let joint: String; let position: Point2D; let score: Double }
struct WorkoutGripBodyPoint: Codable, Equatable, Sendable { let joint: String; let position: Point2D; let world: WorkoutGripVector3; let score: Double }
struct WorkoutGripHandPoint: Codable, Equatable, Sendable { let joint: String; let position: Point2D; let world: WorkoutGripVector3?; let score: Double? }
struct WorkoutGripHandObservation: Codable, Equatable, Sendable { let points: [WorkoutGripHandPoint]; let sdkHandedness: String?; let sdkHandednessScore: Double?; var sourceRegion: WorkoutGripRegion? = nil }
struct WorkoutGripObservation: Codable, Equatable, Sendable {
    let seconds: Double; let imageSize: ImageSize; let trackedBody: [WorkoutGripTrackedPoint]
    let body: [WorkoutGripBodyPoint]; let hands: [WorkoutGripHandObservation]
    let modelId: String; let backend: String
    var coordinateConvention = "cameraRightDownAway_v1"
    var failure: String? = nil
    var schemaVersion = 1
}
struct WorkoutGripEstimate: Codable, Equatable, Sendable {
    let left: String; let right: String; let width: String; let projectedSpanRatio: Double?
    let orientation: String; let reason: String?; let modelId: String?
    let orientationCoverage: Double?; let widthCoverage: Double?; let policyVersion: Int; var inferenceBackend: String? = nil
    var widthPolicyVersion: Int? = nil
    var widthReason: String? = nil
    var shoulderRatioLabel: String? {
        guard ["narrow", "shoulderWidth", "wide"].contains(width),
              let ratio = projectedSpanRatio, ratio.isFinite, (0.1...4).contains(ratio) else { return nil }
        return String(format: "%.1f× shoulders", ratio)
    }
}
struct WorkoutGripOrientationSpan: Codable, Equatable, Sendable {
    let id: String; let startedAt: Double; let endedAt: Double; let left: String; let right: String
    let observedSeconds: Double; let reason: String?; let modelId: String?; var inferenceBackend: String? = nil
}
struct WorkoutGripWidthSpan: Codable, Equatable, Sendable {
    let id: String; let startedAt: Double; let endedAt: Double; let width: String
    let projectedSpanRatio: Double?; let observedSeconds: Double; let reason: String?
}
struct WorkoutGripRecord: Codable, Equatable, Sendable {
    let orientationSpans: [WorkoutGripOrientationSpan]; let widthSpans: [WorkoutGripWidthSpan]
    let latest: WorkoutGripEstimate; let timelineTruncated: Bool; let policyVersion: Int
}
struct WorkoutGripRegion: Codable, Equatable, Sendable { let left: Int; let top: Int; let width: Int; let height: Int }
struct WorkoutGripCropRequest: Codable, Sendable { let side: String; let regions: [WorkoutGripRegion] }
struct WorkoutGripRepVariation: Codable, Identifiable, Sendable {
    let repId: String; let ordinal: Int; let orientation: String; let width: String
    let label: String; let orientationSource: String; let widthSource: String; let measured: WorkoutGripEstimate?
    var id: String { repId }
}
struct WorkoutGripVariationTotal: Codable, Sendable { let label: String; let completedReps: Int }
struct WorkoutSetGripMetadata: Codable, Equatable, Sendable {
    let setId: String
    let orientation: String
    let firstConfirmedAt: Double?
    let lastConfirmedAt: Double?
    let modelId: String?
    let inferenceBackend: String?
    let reason: String
    let policyVersion: Int
    var isConfirmed: Bool {
        ["underhand", "overhand", "neutral", "mixed"].contains(orientation) && reason == "confirmedWithinSet"
    }
    var title: String {
        switch orientation {
        case "underhand": "Underhand"
        case "overhand": "Overhand"
        case "neutral": "Neutral"
        case "mixed": "Mixed grip"
        default: "Unknown"
        }
    }
}
struct WorkoutGripVariationReport: Codable, Sendable {
    let reps: [WorkoutGripRepVariation]; let totals: [WorkoutGripVariationTotal]; let policyVersion: Int
    var setGrip: WorkoutSetGripMetadata? = nil
}
