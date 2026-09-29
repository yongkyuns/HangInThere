import Foundation
import Testing
@testable import HangInThere

struct LiveDebugSessionTests {
    @Test func manifestAlignsWorkoutAndBarToRecordedVideoOrigin() {
        var session = LiveSetSession()
        session.start(exercise: .pullUp, side: .left)
        feed(&session, [(10.0, 170), (10.15, 170), (10.30, 80), (10.45, 80)])
        session.finish()

        let bar = ConfirmedBar(
            role: .pullUpGrip,
            method: .manualEdge,
            referenceEdge: ExerciseCounterTests.referenceEdge,
            oppositeEdge: nil,
            imageSize: ImageSize(width: 640, height: 480),
            sourceTime: PresentationTime(value: 9500, timescale: 1000)
        )
        let capture = LiveDebugCaptureSummary(
            videoFileName: "video.mov",
            videoSHA256: String(repeating: "a", count: 64),
            videoBytes: 1234,
            firstCameraSeconds: 9.0,
            lastCameraSeconds: 11.0,
            appendedSamples: 60,
            droppedQueueSamples: 1,
            droppedWriterSamples: 2
        )

        let manifest = LiveDebugSessionManifest.make(
            sessionID: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!,
            capture: capture,
            qualificationFileName: "qualification.json",
            qualificationSHA256: String(repeating: "b", count: 64),
            qualificationBytes: 456,
            liveSet: session,
            confirmedBar: bar,
            appVersion: "1.0",
            appBuild: "42"
        )

        #expect(manifest.schemaVersion == 1)
        #expect(manifest.capture.durationSeconds == 2)
        #expect(manifest.capture.appendedSamples == 60)
        #expect(manifest.capture.droppedQueueSamples == 1)
        #expect(manifest.capture.droppedWriterSamples == 2)
        #expect(manifest.workout.counterPolicyVersion == ExerciseCounter.policyVersion)
        #expect(manifest.workout.observedMovements == 1)
        #expect(abs((manifest.workout.setStartCaptureSeconds ?? -1) - 1.0) < 1e-9)
        #expect(abs((manifest.workout.setEndCaptureSeconds ?? -1) - 1.45) < 1e-9)
        #expect(manifest.workout.movementCaptureSeconds.count == 1)
        #expect(abs((manifest.workout.movementCaptureSeconds.first ?? -1) - 1.45) < 1e-9)
        #expect(abs((manifest.barReference?.confirmationCaptureSeconds ?? -1) - 0.5) < 1e-9)
        #expect(manifest.barReference?.role == "pullUpGrip")
        #expect(manifest.video.name == "video.mov")
        #expect(manifest.qualification.name == "qualification.json")
    }

    @Test func timestampsOutsideRecordedRangeAreNotInvented() {
        var session = LiveSetSession()
        session.start(exercise: .dip, side: .right)
        feed(&session, [(5.0, 170), (5.15, 170)])
        session.finish()

        let capture = LiveDebugCaptureSummary(
            videoFileName: "video.mov",
            videoSHA256: String(repeating: "a", count: 64),
            videoBytes: 10,
            firstCameraSeconds: 10,
            lastCameraSeconds: 12,
            appendedSamples: 2,
            droppedQueueSamples: 0,
            droppedWriterSamples: 0
        )
        let manifest = LiveDebugSessionManifest.make(
            sessionID: UUID(),
            capture: capture,
            qualificationFileName: "qualification.json",
            qualificationSHA256: String(repeating: "b", count: 64),
            qualificationBytes: 10,
            liveSet: session,
            confirmedBar: nil,
            appVersion: nil,
            appBuild: nil
        )

        #expect(manifest.workout.setStartCaptureSeconds == nil)
        #expect(manifest.workout.setEndCaptureSeconds == nil)
        #expect(manifest.workout.movementCaptureSeconds.isEmpty)
        #expect(manifest.barReference == nil)
    }

    private func feed(_ session: inout LiveSetSession, _ samples: [(Double, Double)]) {
        for (time, angle) in samples {
            session.consume(
                ExerciseCounterTests.pose(
                    time,
                    degrees: angle,
                    exercise: session.exercise,
                    side: session.side
                ),
                referenceEdge: ExerciseCounterTests.referenceEdge
            )
        }
    }
}
