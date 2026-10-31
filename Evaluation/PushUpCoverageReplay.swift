import CoreGraphics
import CryptoKit
import Foundation
import WorkoutCore

/// Full-source pixel crops through the production decoder/estimator and common
/// counter. Camera readiness is assumed; this is not physical-device qualification.
@main enum PushUpCoverageReplay {
    struct Config: Decodable { let cases: [Case] }
    struct Case: Decodable {
        let id: String
        let path: String
        let sha256: String
        /// Fractional top-left crop; nil preserves the complete decoded image.
        let crop: [Double]?
    }
    struct Estimator: PoseEstimator {
        let crop: [Double]?
        func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
            var input = image
            if let crop {
                guard crop.count == 4, crop.allSatisfy({ $0.isFinite && (0...1).contains($0) }),
                      crop[2] > 0, crop[3] > 0, crop[0]+crop[2] <= 1, crop[1]+crop[3] <= 1,
                      let cropped = image.cropping(to: CGRect(
                        x: crop[0]*Double(image.width), y: crop[1]*Double(image.height),
                        width: crop[2]*Double(image.width), height: crop[3]*Double(image.height)))
                else { throw Failure.crop }
                input = cropped
            }
            return try SideViewVisionPoseEstimator().estimate(image: input, timestamp: timestamp)
        }
    }
    enum Failure: Error { case usage, crop, hash, existingOutput }
    static func hash(_ url: URL) throws -> String {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var state = SHA256()
        while let bytes = try file.read(upToCount: 1_048_576), !bytes.isEmpty { state.update(data: bytes) }
        return state.finalize().map { String(format: "%02x", $0) }.joined()
    }
    static func main() async throws {
        guard CommandLine.arguments.count == 3 else { throw Failure.usage }
        let config = try JSONDecoder().decode(Config.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
        let target = URL(fileURLWithPath: CommandLine.arguments[2])
        guard !FileManager.default.fileExists(atPath: target.path) else { throw Failure.existingOutput }
        var reports: [[String: Any]] = []
        for spec in config.cases {
            let url = URL(fileURLWithPath: spec.path)
            guard try hash(url) == spec.sha256 else { throw Failure.hash }
            let traceRoot = ProcessInfo.processInfo.environment["PUSHUP_COVERAGE_TRACE"].map { URL(fileURLWithPath: $0) }
            var trace: FileHandle?
            if let traceRoot {
                guard spec.id.range(of: "^[a-zA-Z0-9-]+$", options: .regularExpression) != nil else { throw Failure.usage }
                try FileManager.default.createDirectory(at: traceRoot, withIntermediateDirectories: true)
                let url = traceRoot.appendingPathComponent(spec.id + ".jsonl")
                guard !FileManager.default.fileExists(atPath: url.path) else { throw Failure.existingOutput }
                FileManager.default.createFile(atPath: url.path, contents: nil)
                trace = try FileHandle(forWritingTo: url)
            }
            defer { try? trace?.close() }
            let reader = VideoReplayReader(estimator: Estimator(crop: spec.crop))
            _ = try await reader.open(url)
            do {
                let counter = SideViewPushUpMotionBridge()
                var events: [[String: Any]] = [], cues: [String: Int] = [:], frames = 0
                while let frame = try await reader.nextFrame() {
                    let json = String(decoding: try JSONEncoder().encode(frame.pose), as: UTF8.self)
                    if let trace { try trace.write(contentsOf: Data((json + "\n").utf8)) }
                    let state = try counter.observeJson(poseJson: json, camera: "ready")
                    let row = try JSONSerialization.jsonObject(with: Data(state.utf8)) as! [String: Any]
                    if let rep = row["rep"] as? [String: Any] { events.append(rep) }
                    cues[row["cue"] as? String ?? "unknown", default: 0] += 1
                    frames += 1
                }
                await reader.close()
                reports.append(["id": spec.id, "frames": frames, "events": events, "cues": cues,
                                "policyVersion": Int(SideViewPushUpMotion.companion.policyVersion),
                                "sourceSHA256": spec.sha256])
                try FileHandle.standardOutput.write(contentsOf: Data("\(spec.id): \(events.count) cycles / \(frames) source frames\n".utf8))
            } catch {
                await reader.close()
                throw error
            }
        }
        try JSONSerialization.data(withJSONObject: reports, options: [.prettyPrinted, .sortedKeys]).write(to: target)
    }
}
