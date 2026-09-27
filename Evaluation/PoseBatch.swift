// Host-only batch entry point. Compiled with the exact app sources, not a second estimator.
import Foundation
import CoreImage
import ImageIO

private struct Job: Decodable {
    let id: String
    let kind: String
    let paths: [String]
}
private struct Observation: Encodable {
    let frameIndex: Int
    let timebase: String
    let timestamp: PresentationTime?
    let imageSize: ImageSize
    let people: [PoseObservation]
    let backend: String
    let requestRevision: Int
    let processingMilliseconds: Double
}
private struct Completion: Encodable {
    let status: String
    let frames: Int
    let target: String
    let operatingSystem: String
}

@main
private enum PoseBatch {
    static func main() async throws {
        guard CommandLine.arguments.count == 3 else {
            throw NSError(domain: "PoseBatch", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Usage: pose-batch jobs.json output-directory"])
        }
        let jobs = try JSONDecoder().decode([Job].self,
            from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
        let root = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let context = CIContext(options: [.cacheIntermediates: false])
        for job in jobs {
            // Identifiers are validated again at this executable boundary.
            guard !job.id.isEmpty, job.id.utf8.allSatisfy({
                (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0)
                    || $0 == 45 || $0 == 95
            }) else { throw CocoaError(.fileWriteInvalidFileName) }
            let directory = root.appendingPathComponent(job.id, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
            let output = directory.appendingPathComponent("observations.jsonl")
            guard FileManager.default.createFile(atPath: output.path, contents: nil) else {
                throw CocoaError(.fileWriteUnknown)
            }
            let handle = try FileHandle(forWritingTo: output)
            var frames = 0
            let reader = VideoReplayReader()
            var status = "processed"
            do {
                if job.kind == "video", job.paths.count == 1 {
                    _ = try await reader.open(URL(fileURLWithPath: job.paths[0]))
                    while let frame = try await reader.nextFrame() {
                        try Task.checkCancellation()
                        try emit(frame.pose, index: frames, timebase: "source_pts",
                                 milliseconds: frame.processingMilliseconds, encoder: encoder, handle: handle)
                        frames += 1
                    }
                } else if job.kind == "images", !job.paths.isEmpty {
                    for path in job.paths {
                        try Task.checkCancellation()
                        try autoreleasepool {
                            let start = ContinuousClock.now
                            let url = URL(fileURLWithPath: path)
                            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                                  let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
                                throw CocoaError(.fileReadCorruptFile)
                            }
                            let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
                            let orientation = (properties?[kCGImagePropertyOrientation] as? NSNumber)?.int32Value ?? 1
                            guard (1...8).contains(orientation) else { throw CocoaError(.fileReadCorruptFile) }
                            let oriented = CIImage(cgImage: image).oriented(forExifOrientation: orientation)
                            guard let upright = context.createCGImage(oriented, from: oriented.extent) else {
                                throw CocoaError(.fileReadCorruptFile)
                            }
                            // Still-image requests have no source clock. The API requires a
                            // value, but this sentinel is NEVER exported as a media timestamp.
                            let result = try VisionPoseEstimator().estimate(image: upright,
                                timestamp: PresentationTime(value: 0, timescale: 1))
                            let elapsed = start.duration(to: .now).components
                            let ms = Double(elapsed.seconds) * 1_000 + Double(elapsed.attoseconds) / 1e15
                            try emit(result, index: frames, timebase: "frame_index",
                                     milliseconds: ms, encoder: encoder, handle: handle)
                        }
                        frames += 1
                    }
                } else { throw CocoaError(.fileReadCorruptFile) }
                if frames == 0 { status = "no_frames" }
            } catch {
                // Preserve partial observations for diagnosis, but never mark them complete.
                // Avoid private filesystem paths in public CI reports or stdout.
                status = "engine_failure"
            }
            await reader.close()
            try handle.close()
            let completion = Completion(status: status, frames: frames,
                target: "native_macos", operatingSystem: ProcessInfo.processInfo.operatingSystemVersionString)
            try encoder.encode(completion).write(to: directory.appendingPathComponent("completion.json"), options: .atomic)
        }
    }
    private static func emit(_ result: PoseResult, index: Int, timebase: String,
                             milliseconds: Double, encoder: JSONEncoder, handle: FileHandle) throws {
        let observation = Observation(frameIndex: index, timebase: timebase,
            timestamp: timebase == "source_pts" ? result.timestamp : nil,
            imageSize: result.imageSize, people: result.people, backend: result.backend,
            requestRevision: result.requestRevision, processingMilliseconds: milliseconds)
        try handle.write(contentsOf: encoder.encode(observation))
        try handle.write(contentsOf: Data([10]))
    }
}
