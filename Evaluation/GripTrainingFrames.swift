import AVFoundation
import CryptoKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Unlabelled local development samples from the production upright decoder.
@main enum GripTrainingFrames {
    static func main() async throws {
        guard CommandLine.arguments.count == 3 else { throw CocoaError(.fileReadInvalidFileName) }
        let video = URL(fileURLWithPath: CommandLine.arguments[1])
        let out = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
        guard !FileManager.default.fileExists(atPath: out.path) else { throw CocoaError(.fileWriteFileExists) }
        try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let hash = try digest(video)
        let reader = VideoReplayReader(); _ = try await reader.open(video)
        let encoder = JSONEncoder(); encoder.outputFormatting = .sortedKeys
        var rows = Data(); var count = 0; var samples = 0; var next = -Double.infinity
        var sourceTimes: [PresentationTime] = []
        while let frame = try await reader.nextFrame() {
            count += 1; sourceTimes.append(frame.pose.timestamp)
            guard frame.pose.timestamp.seconds >= next else { continue }
            next = frame.pose.timestamp.seconds + 0.25
            let name = String(format: "frame-%04d.png", samples); samples += 1
            let imageURL = out.appendingPathComponent(name)
            let dest = CGImageDestinationCreateWithURL(imageURL as CFURL, UTType.png.identifier as CFString, 1, nil)!
            CGImageDestinationAddImage(dest, frame.image, nil)
            guard CGImageDestinationFinalize(dest) else { throw CocoaError(.fileWriteUnknown) }
            let pose = try JSONSerialization.jsonObject(with: encoder.encode(frame.pose))
            let row: [String: Any] = ["pose": pose, "image": name, "imageSHA256": try digest(imageURL), "recordingId": hash]
            rows.append(try JSONSerialization.data(withJSONObject: row, options: .sortedKeys)); rows.append(10)
        }
        await reader.close()
        guard try digest(video) == hash else { throw ReplayError.invalidTime }
        try rows.write(to: out.appendingPathComponent("frames.jsonl"), options: .withoutOverwriting)
        let meta: [String: Any] = ["videoSHA256": hash, "decodedFrames": count, "samples": samples,
            "sourcePTS_SHA256": SHA256.hash(data: try encoder.encode(sourceTimes)).map { String(format: "%02x", $0) }.joined(),
            "sampling": "First original frame then at least 0.25 source seconds between samples; no resampling",
            "scope": "Native Mac production body observations; unlabelled local training candidates, not accuracy evidence"]
        try JSONSerialization.data(withJSONObject: meta, options: [.prettyPrinted, .sortedKeys]).write(to: out.appendingPathComponent("intake.json"))
        print("\(count) decoded frames, \(samples) unlabelled samples")
    }
    static func digest(_ url: URL) throws -> String {
        let f = try FileHandle(forReadingFrom: url); defer { try? f.close() }
        var hash = SHA256()
        while let d = try f.read(upToCount: 1024 * 1024), !d.isEmpty { hash.update(data: d) }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
