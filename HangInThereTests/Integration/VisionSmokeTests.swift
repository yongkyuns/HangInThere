import Foundation
import ImageIO
import Testing
@testable import HangInThere

@Suite(.serialized)
struct VisionSmokeTests {
    @Test func realHumanImageProducesAnArmChain() throws {
        let url = try VideoTestSupport.resource("pullup-smoke.png")
        let source = try #require(CGImageSourceCreateWithURL(url as CFURL, nil))
        let image = try #require(CGImageSourceCreateImageAtIndex(source, 0, nil))
        let result = try VisionPoseEstimator().estimate(image: image, timestamp: PresentationTime(value: 1, timescale: 1))
        #expect(result.requestRevision == 1)
        #expect(VideoTestSupport.hasVisibleArm(result), "A real human fixture must produce shoulder/elbow/wrist observations, not just a successful request.")
        print("[Vision smoke] \(ProcessInfo.processInfo.operatingSystemVersionString); revision=\(result.requestRevision); people=\(result.people.count)")
    }

    @Test func realVideoUsesTheAppReaderAndMatchesDecodedSourceTimestamps() async throws {
        let url = try VideoTestSupport.resource("pullup-smoke.mp4")
        let metadataURL = try VideoTestSupport.resource("prepared.json")
        let metadata = try JSONDecoder().decode(PreparedFixture.self, from: Data(contentsOf: metadataURL))
        let reader = VideoReplayReader()
        _ = try await reader.open(url)
        var timestamps: [Double] = []
        var framesWithArm = 0
        while let frame = try await reader.nextFrame() {
            timestamps.append(frame.pose.timestamp.seconds)
            if VideoTestSupport.hasVisibleArm(frame.pose) { framesWithArm += 1 }
            #expect(frame.pose.imageSize == ImageSize(width: Double(frame.image.width), height: Double(frame.image.height)))
            #expect(frame.pose.requestRevision == 1)
        }
        await reader.close()
        #expect(timestamps.count == metadata.framePTSSeconds.count)
        #expect(timestamps.count == 40, "The prepared 4-second, 10-FPS fixture must not silently change length.")
        for (actual, expected) in zip(timestamps, metadata.framePTSSeconds) {
            #expect(abs(actual - expected) < 1e-6)
        }
        #expect(framesWithArm > 0, "Decoding a video without real pose extraction is not a model smoke test.")
        print("[Video smoke] frames=\(timestamps.count); armFrames=\(framesWithArm); derivativeSHA256=\(metadata.videoSHA256); not a counting/accuracy benchmark")
    }
}

private struct PreparedFixture: Decodable {
    let framePTSSeconds: [Double]
    let videoSHA256: String

    enum CodingKeys: String, CodingKey {
        case framePTSSeconds = "frame_pts_seconds"
        case videoSHA256 = "video_sha256"
    }
}