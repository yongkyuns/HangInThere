import Foundation
import CryptoKit
import os

// This entry point drives the actual native replay controller and shipping Apple
// analysis bridge. It does not reimplement acquisition, counting or analytics.
@main private enum PublicWorkoutReplay {
    @MainActor static func main() async throws {
        guard let invocation = PublicReplayPipeline.parse(arguments: CommandLine.arguments) else {
            throw failure("Usage: public-replay MANIFEST ROOT OUTPUT REVISION [SHARD_INDEX SHARD_COUNT] [--candidate-bilateral-dips]")
        }
        let arguments = invocation.arguments
        let pipeline = invocation.pipeline
        let manifestURL = URL(fileURLWithPath: arguments[1])
        let root = URL(fileURLWithPath: arguments[2]).resolvingSymlinksInPath().standardizedFileURL
        let output = URL(fileURLWithPath: arguments[3])
        let manifestData = try Data(contentsOf: manifestURL)
        let manifestHash = SHA256.hash(data: manifestData).map { String(format: "%02x", $0) }.joined()
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let manifest = try decoder.decode(PublicReplayManifest.self, from: manifestData)
        let shardIndex = arguments.count == 7 ? Int(arguments[5]) : 0
        let shardCount = arguments.count == 7 ? Int(arguments[6]) : 1
        guard manifest.schemaVersion == 1, manifest.scope == "public_repetition_diagnostic",
              !manifest.heldOutQualification, !manifest.cases.isEmpty,
              Set(manifest.cases.map(\.id)).count == manifest.cases.count,
              let shardIndex, let shardCount, shardCount > 0, shardCount <= manifest.cases.count,
              (0..<shardCount).contains(shardIndex) else { throw failure("Invalid public manifest/shard") }
        let cases = manifest.cases.enumerated().filter { $0.offset % shardCount == shardIndex }.map(\.element)
        var reports: [[String: Any]] = []
        for item in cases {
            let id = item.id
            let media = item.media
            var report: [String: Any] = ["id": id, "sha256": media.sha256, "status": "failed", "frames": 0,
                "acceptance_seconds": [Double](), "completed_seconds": [Double](), "source_clock_verified": false,
                "selected_exercise": item.selectedExercise.rawValue, "tracking_side": item.trackingSide.rawValue]
            let clock = PublicReplayClock()
            let quality = PublicPoseQuality(exercise: item.selectedExercise)
            let sceneTrace = PublicSceneTrace()
            let model = ReplayController(dipMethod: pipeline.dipMethod, estimator: quality, trace: clock.observe,
                sceneWorker: VisionStaticSceneRegistrationWorker(includeDiagnosticHomography: true),
                sceneTrace: sceneTrace.observe)
            model.configureCounting(exercise: item.selectedExercise, side: item.trackingSide)
            let started = ContinuousClock.now
            var verifiedURL: URL?
            var finished = false
            do {
                let url = root.appendingPathComponent(media.path).resolvingSymlinksInPath().standardizedFileURL
                guard media.path.hasPrefix("videos/"), media.path.hasSuffix(".mp4"),
                      url.path.hasPrefix(root.path + "/"), try sha256(url) == media.sha256 else { throw failure("Media integrity mismatch") }
                verifiedURL = url
                model.open(url)
                try await wait(model) { model.phase != .loading }
                guard model.phase == .paused, model.frame?.pose.backend == "Apple Vision 2D",
                      model.frame?.pose.requestRevision == 1 else { throw failure(model.errorMessage ?? "Vision did not open") }
                model.play()
                // Operational watchdog for slow hosted VM inference, not a
                // source-time, counting or physical-device performance gate.
                try await wait(model, timeoutSeconds: 900) { model.phase == .finished || model.phase == .failed }
                guard model.phase == .finished else { throw failure(model.errorMessage ?? "Replay failed") }
                finished = true
            } catch {
                report["error"] = error.localizedDescription
            }
            // Read before close resets the controller, including on failures.
            let mediaVerified = verifiedURL.flatMap { try? sha256($0) } == media.sha256
            let progress = PublicReplayMeasurements(model: model, expected: item.sourcePtsSeconds,
                observed: clock.values, finished: finished, mediaVerified: mediaVerified)
            report.merge(progress.fields) { _, new in new }
            if finished, progress.status == "failed" { report["error"] = "Replay frame/source-clock integrity failed" }
            report["tracking_coverage"] = model.trackingCoverage.map { $0 as Any } ?? NSNull()
            report["automatic_bar_state"] = String(describing: model.automaticBarState)
            report["camera_tracking_issue"] = model.cameraTrackingIssue.map { $0 as Any } ?? NSNull()
            report["scene_registration_evidence"] = sceneTrace.report
            report["selected_exercise"] = model.counter.exercise.rawValue
            report["tracking_side"] = model.counter.side.rawValue
            if pipeline == .bilateralDipsV1 {
                report.merge(PublicReplayCountingIdentity(model: model).fields) { _, value in value }
                report["reference_side"] = item.trackingSide.rawValue
                report["tracking_side"] = "both"
                if model.usesBilateralDip {
                    report["legacy_automatic_bar_state"] = report.removeValue(forKey: "automatic_bar_state")
                    report["scene_registration_evidence"] = ["collection": "notCollectedByLegacyTrace",
                        "scope": "The source-owned dip processor enforces current scene evidence; this legacy diagnostic callback does not observe it."]
                }
            }
            model.close()
            report["pose_quality"] = quality.summary
            report["elapsed_seconds"] = ReplayTrace.milliseconds(since: started) / 1000
            reports.append(report)
            FileHandle.standardOutput.write(Data("\(id): \(report["status"]!)\n".utf8))
        }
        guard try sha256(manifestURL) == manifestHash else { throw failure("Manifest changed while evaluating") }
        var value: [String: Any] = ["schema_version": 1, "manifest_sha256": manifestHash,
            "source_revision": arguments[4], "platform": "apple_host", "backend": "Apple Vision 2D",
            "request_revision": 1,
            "progress_evidence_version": 1,
            "shard_index": shardIndex, "shard_count": shardCount,
            "exercise_selection": manifest.cases.allSatisfy { $0.selectedExercise == .pullUp }
                ? "pullUp selected in production native replay" : "per-case exercise selected in production native replay",
            "replay_configuration_version": pipeline.rawValue, "cases": reports]
        if pipeline == .legacyV8 {
            value["counter_policy_version"] = ExerciseCounter.policyVersion
        } else {
            value["counting_method_configuration"] = "bilateralDips_v1"
        }
        try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys])
            .write(to: output, options: .withoutOverwriting)
    }
    @MainActor private static func wait(_ model: ReplayController, timeoutSeconds: Int = 300, until done: () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(timeoutSeconds))
        while !done() {
            guard ContinuousClock.now < deadline else { throw failure("Native public replay timed out") }
            try await Task.sleep(for: .milliseconds(20))
        }
    }
    private static func sha256(_ url: URL) throws -> String {
        // Streaming verification is implemented by CryptoKit, as in app storage.
        var digest = SHA256()
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        while let data = try handle.read(upToCount: 65536), !data.isEmpty { digest.update(data: data) }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }
    private static func failure(_ message: String) -> NSError { NSError(domain: "PublicWorkoutReplay", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
}

// Bounded metadata from actual, accepted controller observations. This does not
// run another registration, simulate policy, or change counting availability.
private final class PublicSceneTrace: Sendable {
    private struct State: Sendable {
        var samples: [ReplaySceneTrace] = []
        var truncated = false
    }
    private let state = OSAllocatedUnfairLock(initialState: State())
    private static let maximumSamples = 512

    func observe(_ event: ReplaySceneTrace) {
        state.withLock {
            guard $0.samples.count < Self.maximumSamples else { $0.truncated = true; return }
            $0.samples.append(event)
        }
    }

    var report: [String: Any] {
        let value = state.withLock { $0 }
        let samples: [[String: Any]] = value.samples.map { event in
            let matrix = event.measurement?.diagnosticHomography
            return [
                "source_seconds": event.sourceSeconds,
                "calibration_seconds": event.calibrationSeconds,
                "reference_width": event.imageSize.width,
                "reference_height": event.imageSize.height,
                "measurement_available": event.measurement != nil,
                "translations_pixels": event.measurement?.translations.map { [$0.dxPixels, $0.dyPixels] } ?? [],
                "scale_fraction": event.measurement?.globalScaleFraction.map { $0 as Any } ?? NSNull(),
                "homography_column_major": matrix.map { $0.map { $0.isFinite ? $0 as Any : NSNull() } as Any } ?? NSNull(),
                "homography_nonfinite_indices": matrix.map { values in values.indices.filter { !values[$0].isFinite } } ?? [],
                "state_before": event.stateBefore, "state_after": event.stateAfter,
                "movement_kind": event.movementKind.map { $0 as Any } ?? NSNull()
            ]
        }
        return ["samples": samples, "truncated": value.truncated, "maximum_samples": Self.maximumSamples,
            "scope": "Actual native measurements and controller source-time qualification; diagnostic metadata, no physical scale or camera-motion ground truth."]
    }
}
