import Foundation
import CryptoKit

// This entry point drives the actual native replay controller and shipping Apple
// analysis bridge. It does not reimplement acquisition, counting or analytics.
@main private enum PublicWorkoutReplay {
    @MainActor static func main() async throws {
        guard [5, 7].contains(CommandLine.arguments.count) else { throw failure("Usage: public-replay MANIFEST ROOT OUTPUT REVISION [SHARD_INDEX SHARD_COUNT]") }
        let manifestURL = URL(fileURLWithPath: CommandLine.arguments[1])
        let root = URL(fileURLWithPath: CommandLine.arguments[2]).resolvingSymlinksInPath().standardizedFileURL
        let output = URL(fileURLWithPath: CommandLine.arguments[3])
        let manifestData = try Data(contentsOf: manifestURL)
        let manifestHash = SHA256.hash(data: manifestData).map { String(format: "%02x", $0) }.joined()
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        let manifest = try decoder.decode(PublicReplayManifest.self, from: manifestData)
        let shardIndex = CommandLine.arguments.count == 7 ? Int(CommandLine.arguments[5]) : 0
        let shardCount = CommandLine.arguments.count == 7 ? Int(CommandLine.arguments[6]) : 1
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
            let model = ReplayController(estimator: quality, trace: clock.observe)
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
            report["selected_exercise"] = model.counter.exercise.rawValue
            report["tracking_side"] = model.counter.side.rawValue
            model.close()
            report["pose_quality"] = quality.summary
            report["elapsed_seconds"] = ReplayTrace.milliseconds(since: started) / 1000
            reports.append(report)
            FileHandle.standardOutput.write(Data("\(id): \(report["status"]!)\n".utf8))
        }
        guard try sha256(manifestURL) == manifestHash else { throw failure("Manifest changed while evaluating") }
        let value: [String: Any] = ["schema_version": 1, "manifest_sha256": manifestHash,
            "source_revision": CommandLine.arguments[4], "platform": "apple_host", "backend": "Apple Vision 2D",
            "counter_policy_version": ExerciseCounter.policyVersion, "request_revision": 1,
            "progress_evidence_version": 1,
            "shard_index": shardIndex, "shard_count": shardCount,
            "exercise_selection": manifest.cases.allSatisfy { $0.selectedExercise == .pullUp }
                ? "pullUp selected in production native replay" : "per-case exercise selected in production native replay",
            "replay_configuration_version": 1, "cases": reports]
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
