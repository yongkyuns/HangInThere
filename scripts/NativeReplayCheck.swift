import AVFoundation
import Foundation
import Vision

// Compiled with the app's EXACT Analysis/Pose.swift, VideoReplay.swift, and
// VisionPoseEstimator.swift. No second estimator or video implementation.
// This qualifies native macOS integration only, never iPhone/simulator behavior.
@main
struct NativeReplayCheck {
    struct CheckFailure: Error, CustomStringConvertible {
        let description: String
    }

    static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw CheckFailure(description: message) }
    }

    nonisolated static func sourceTimes(_ url: URL) async throws -> [Double] {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else {
            throw ReplayError.noVideo
        }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        guard reader.canAdd(output) else { throw ReplayError.decoderUnavailable }
        reader.add(output)
        guard reader.startReading() else { throw ReplayError.decoderUnavailable }
        var times: [Double] = []
        while let sample = output.copyNextSampleBuffer() {
            times.append(CMSampleBufferGetPresentationTimeStamp(sample).seconds)
        }
        guard reader.status == .completed else { throw reader.error ?? ReplayError.invalidFrame }
        return times.sorted()
    }

    static func main() async throws {
        try require(CommandLine.arguments.count == 2, "Pass the prepared pullups.mov path.")
        let url = URL(fileURLWithPath: CommandLine.arguments[1])
        print("Native host: \(ProcessInfo.processInfo.operatingSystemVersionString)")
        print("Vision body-pose revision: \(VNDetectHumanBodyPoseRequestRevision1)")
        let expected = Array(try await sourceTimes(url).prefix(16))
        try require(expected.count == 16, "Fixture has fewer than 16 source frames.")
        let replay = VideoReplay()
        try await replay.open(url)
        do {
            var torsoFrames = 0
            for (index, time) in expected.enumerated() {
                guard let frame = try await replay.next() else { throw ReplayError.emptyVideo }
                try require(frame.index == index + 1, "Incorrect frame index.")
                try require(abs(frame.pose.timestamp - time) < 1e-6, "Source timestamp changed.")
                try require(frame.image.width == Int(frame.pose.imageSize.width) &&
                            frame.image.height == Int(frame.pose.imageSize.height), "Image/pose dimensions differ.")
                let required: [Joint] = [.leftShoulder, .rightShoulder, .leftHip, .rightHip]
                if frame.pose.personCount == 1 && required.allSatisfy({
                    (frame.pose.landmarks[$0]?.confidence ?? 0) > 0.1
                }) { torsoFrames += 1 }
                print("frame=\(frame.index) pts=\(frame.pose.timestamp) people=\(frame.pose.personCount) landmarks=\(frame.pose.landmarks.count)")
            }
            try require(torsoFrames >= 8, "Fewer than 8/16 frames have real Vision torso landmarks.")
            await replay.close()
            try await replay.open(url)
            guard let first = try await replay.next() else { throw ReplayError.emptyVideo }
            try require(first.index == 1 && abs(first.pose.timestamp - expected[0]) < 1e-6,
                        "Restart did not reset to the first source frame.")
            await replay.close()
            print("PASS: native macOS real-Vision replay; 16 source timestamps, \(torsoFrames) torso frames, restart.")
            print("NOT QUALIFIED: iOS simulator, physical iPhone, exercise accuracy, or rep counting.")
        } catch {
            await replay.close()
            throw error
        }
    }
}
