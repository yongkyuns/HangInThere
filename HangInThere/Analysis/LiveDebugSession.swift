import Foundation

enum LiveDebugCaptureState: Equatable, Sendable {
    case idle
    case recording
    case finalizing
    case ready
    case failed(String)
}

struct LiveDebugCaptureSummary: Equatable, Sendable {
    let videoFileName: String
    let videoSHA256: String
    let videoBytes: Int64
    let firstCameraSeconds: Double
    let lastCameraSeconds: Double
    let appendedSamples: Int
    let droppedQueueSamples: Int
    let droppedWriterSamples: Int

    var durationSeconds: Double {
        max(0, lastCameraSeconds - firstCameraSeconds)
    }
}

struct LiveDebugSessionManifest: Codable, Equatable, Sendable {
    struct FileRecord: Codable, Equatable, Sendable {
        let name: String
        let sha256: String
        let bytes: Int64
    }

    struct Capture: Codable, Equatable, Sendable {
        let durationSeconds: Double
        let appendedSamples: Int
        let droppedQueueSamples: Int
        let droppedWriterSamples: Int
    }

    struct Workout: Codable, Equatable, Sendable {
        let counterPolicyVersion: Int
        let exercise: String
        let side: String
        let phase: String
        let endReason: String?
        let observedMovements: Int
        let partialAttempts: Int
        let interruptedAttempts: Int
        let trackingCoverage: Double?
        let setStartCaptureSeconds: Double?
        let setEndCaptureSeconds: Double?
        let movementCaptureSeconds: [Double]
    }

    struct Segment: Codable, Equatable, Sendable {
        let ax: Double
        let ay: Double
        let bx: Double
        let by: Double

        init(_ segment: BarSegment) {
            ax = segment.a.x
            ay = segment.a.y
            bx = segment.b.x
            by = segment.b.y
        }
    }

    struct BarReference: Codable, Equatable, Sendable {
        let role: String
        let method: String
        let referenceEdge: Segment
        let oppositeEdge: Segment?
        let imageWidth: Double
        let imageHeight: Double
        let confirmationCaptureSeconds: Double?
    }

    let schemaVersion: Int
    let sessionID: String
    let scope: String
    let appVersion: String?
    let appBuild: String?
    let video: FileRecord
    let qualification: FileRecord
    let capture: Capture
    let workout: Workout
    let barReference: BarReference?

    static func make(
        sessionID: UUID,
        capture: LiveDebugCaptureSummary,
        qualificationFileName: String,
        qualificationSHA256: String,
        qualificationBytes: Int64,
        liveSet: LiveSetSession,
        confirmedBar: ConfirmedBar?,
        appVersion: String?,
        appBuild: String?
    ) -> Self {
        let origin = capture.firstCameraSeconds
        let end = capture.lastCameraSeconds

        func relative(_ seconds: Double?) -> Double? {
            guard let seconds, seconds.isFinite,
                  origin.isFinite, end.isFinite,
                  seconds >= origin - 1e-6,
                  seconds <= end + 1e-6
            else { return nil }
            return max(0, seconds - origin)
        }

        let movements = liveSet.movementSourceSeconds.compactMap { relative($0) }
        let barReference = confirmedBar.map { bar in
            BarReference(
                role: bar.role.rawValue,
                method: bar.method.rawValue,
                referenceEdge: Segment(bar.referenceEdge),
                oppositeEdge: bar.oppositeEdge.map(Segment.init),
                imageWidth: bar.imageSize.width,
                imageHeight: bar.imageSize.height,
                confirmationCaptureSeconds: relative(bar.sourceTime.seconds)
            )
        }

        return Self(
            schemaVersion: 1,
            sessionID: sessionID.uuidString,
            scope: "opt-in local debug capture; raw video plus runtime metadata for offline evaluation",
            appVersion: appVersion,
            appBuild: appBuild,
            video: FileRecord(
                name: capture.videoFileName,
                sha256: capture.videoSHA256,
                bytes: capture.videoBytes
            ),
            qualification: FileRecord(
                name: qualificationFileName,
                sha256: qualificationSHA256,
                bytes: qualificationBytes
            ),
            capture: Capture(
                durationSeconds: capture.durationSeconds,
                appendedSamples: capture.appendedSamples,
                droppedQueueSamples: capture.droppedQueueSamples,
                droppedWriterSamples: capture.droppedWriterSamples
            ),
            workout: Workout(
                counterPolicyVersion: ExerciseCounter.policyVersion,
                exercise: liveSet.exercise.rawValue,
                side: liveSet.side.rawValue,
                phase: liveSet.phase.rawValue,
                endReason: liveSet.endReason?.rawValue,
                observedMovements: liveSet.observedMovements,
                partialAttempts: liveSet.partialAttempts,
                interruptedAttempts: liveSet.interruptedAttempts,
                trackingCoverage: liveSet.trackingCoverage,
                setStartCaptureSeconds: relative(liveSet.firstSourceTimestampSeconds),
                setEndCaptureSeconds: relative(liveSet.lastSourceTimestampSeconds),
                movementCaptureSeconds: movements
            ),
            barReference: barReference
        )
    }
}
