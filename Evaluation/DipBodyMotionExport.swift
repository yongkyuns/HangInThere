import AVFoundation
import CryptoKit
import Foundation

/// Development observations only. Uses the production decoder/orientation/body
/// estimator, without rail or independent hand inference. No labels enter here.
@main enum DipBodyMotionExport {
    static func main() async throws {
        guard CommandLine.arguments.count == 3 else {
            throw NSError(domain: "DipBodyMotionExport", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Usage: dip-body-export VIDEO OUTPUT_DIRECTORY"])
        }
        let source = URL(fileURLWithPath: CommandLine.arguments[1])
        let directory = URL(fileURLWithPath: CommandLine.arguments[2])
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = directory.appendingPathComponent("poses.jsonl")
        guard !FileManager.default.fileExists(atPath: target.path) else {
            throw CocoaError(.fileWriteFileExists)
        }
        let before = try digest(source)
        // Independent decoded BGRA PTS inventory, not compressed packet times.
        let asset = AVURLAsset(url: source)
        let track = try await asset.loadTracks(withMediaType: .video).first!
        let inventory = try AVAssetReader(asset: asset)
        let frames = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        inventory.add(frames)
        guard inventory.startReading() else { throw ReplayError.noFrames }
        var expected: [PresentationTime] = []
        while let frame = frames.copyNextSampleBuffer() {
            let time = CMSampleBufferGetPresentationTimeStamp(frame)
            expected.append(PresentationTime(value: time.value, timescale: time.timescale))
        }
        guard inventory.status == .completed, !expected.isEmpty else { throw ReplayError.noFrames }
        let reader = VideoReplayReader()
        _ = try await reader.open(source)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        var data = Data(); var observed: [PresentationTime] = []
        while let frame = try await reader.nextFrame() {
            observed.append(frame.pose.timestamp)
            data.append(try encoder.encode(frame.pose)); data.append(10)
            if observed.count % 150 == 0 { print("Exported \(observed.count) source frames"); fflush(stdout) }
        }
        await reader.close()
        guard observed == expected, try digest(source) == before else { throw ReplayError.invalidTime }
        try data.write(to: target, options: .withoutOverwriting)
        let report: [String: Any] = ["videoSHA256": before, "observationsSHA256": try digest(target),
            "frames": observed.count, "sourceClockVerified": true,
            "bodyEstimator": "production VisionPoseEstimator / Apple Vision revision 1",
            "railsRequired": false, "independentHandsRequired": false,
            "scope": "Native Mac body inference; development recording, not physical iPhone live qualification."]
        try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys])
            .write(to: directory.appendingPathComponent("export.json"), options: .withoutOverwriting)
        print("Verified all \(observed.count) decoded source frames")
    }
    static func digest(_ url: URL) throws -> String {
        let file = try FileHandle(forReadingFrom: url); defer { try? file.close() }
        var hash = SHA256()
        while let data = try file.read(upToCount: 1024 * 1024), !data.isEmpty { hash.update(data: data) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
