import Foundation

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
struct WorkoutGripVariationReport: Codable, Sendable { let reps: [WorkoutGripRepVariation]; let totals: [WorkoutGripVariationTotal]; let policyVersion: Int }
