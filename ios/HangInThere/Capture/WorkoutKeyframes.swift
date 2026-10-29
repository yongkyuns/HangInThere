@preconcurrency import AVFoundation
import Foundation
import ImageIO
import UniformTypeIdentifiers

extension Notification.Name {
    static let workoutVideoSaved = Notification.Name("dev.hanginthere.workout-video-saved")
}

struct WorkoutVideoOrigin: Codable, Sendable {
    let setId: String
    let clipStart: Double
}
struct WorkoutVideoReviewData: Sendable {
    let url: URL
    let record: WorkoutSetRecord?
    let markers: [WorkoutKeyframeMarker]
    let thumbnailDirectory: URL?
    let thumbnailError: String?
    var clipDuration: Double? {
        guard let first = markers.first, let end = record?.completedReps.compactMap(\.returnedAt).max() else { return nil }
        return end - (first.sourceSeconds - first.clipSeconds)
    }
}

/// Small, persistent derivatives. The library prunes these with their source clip.
/// No inference or video encoding runs on the camera queue or main actor.
enum WorkoutKeyframeExtractor {
    static func extract(video: URL, markers: [WorkoutKeyframeMarker], directory: URL) async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let asset = AVURLAsset(url: video)
        let duration = try await asset.load(.duration).seconds
        guard duration.isFinite, duration > 0 else { throw CountedSetVideoError.unavailable }
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 256, height: 256)
        generator.requestedTimeToleranceBefore = CMTime(seconds: 0.06, preferredTimescale: 60_000)
        generator.requestedTimeToleranceAfter = CMTime(seconds: 0.06, preferredTimescale: 60_000)
        var bytes = 0
        for marker in markers {
            try Task.checkCancellation()
            let target = directory.appendingPathComponent(marker.filename)
            if let count = try? target.resourceValues(forKeys: [.fileSizeKey]).fileSize { bytes += count; continue }
            // The last return may equal movie duration, which is outside its last frame.
            let seconds = min(marker.clipSeconds, max(0, duration - 1 / 30))
            let image = try await generator.image(at: CMTime(seconds: seconds, preferredTimescale: 60_000)).image
            let data = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { throw CountedSetVideoError.writingFailed }
            CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.72] as CFDictionary)
            guard CGImageDestinationFinalize(destination), bytes + data.length <= 2_097_152 else { throw CocoaError(.fileWriteOutOfSpace) }
            try (data as Data).write(to: target, options: .atomic)
            bytes += data.length
        }
    }
}
