import AVFoundation
import XCTest
@testable import HangInThere

final class ReplayTests: XCTestCase {
    private func fixture() throws -> URL {
        try XCTUnwrap(Bundle(for: Self.self).url(forResource: "pullups", withExtension: "mov", subdirectory: "Fixtures"),
                      "Missing licensed real-video fixture. Run python3 scripts/prepare-fixtures.py before building.")
    }

    func testRealVideoFlowsThroughActualVisionWithSourceTimestamps() async throws {
        let url = try fixture()
        let asset = AVURLAsset(url: url)
        let tracks = try await asset.loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(tracks.first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        var expectedTimes: [Double] = []
        while let sample = output.copyNextSampleBuffer() {
            expectedTimes.append(CMSampleBufferGetPresentationTimeStamp(sample).seconds)
        }
        // MPEG-4 decode order can differ from presentation order. Sort the
        // complete independent compressed-sample timeline before taking a prefix.
        // A prefix of decode-order samples can omit earlier presentation frames.
        XCTAssertEqual(reader.status, .completed)
        expectedTimes = Array(expectedTimes.sorted().prefix(16))
        XCTAssertEqual(expectedTimes.count, 16)

        let replay = VideoReplay()
        try await replay.open(url)
        var framesWithTorso = 0
        for (index, timestamp) in expectedTimes.enumerated() {
            let decoded = try await replay.next()
            let frame = try XCTUnwrap(decoded)
            XCTAssertEqual(frame.index, index + 1)
            XCTAssertEqual(frame.pose.timestamp, timestamp, accuracy: 1e-6)
            XCTAssertEqual(frame.image.width, Int(frame.pose.imageSize.width))
            XCTAssertEqual(frame.image.height, Int(frame.pose.imageSize.height))
            XCTAssertGreaterThan(frame.image.width, 300)
            XCTAssertGreaterThan(frame.image.height, 200)
            let required: [Joint] = [.leftShoulder, .rightShoulder, .leftHip, .rightHip]
            if frame.pose.personCount == 1 && required.allSatisfy({ (frame.pose.landmarks[$0]?.confidence ?? 0) > 0.1 }) {
                framesWithTorso += 1
            }
            for landmark in frame.pose.landmarks.values {
                XCTAssertTrue((0...frame.pose.imageSize.width).contains(landmark.position.x))
                XCTAssertTrue((0...frame.pose.imageSize.height).contains(landmark.position.y))
                XCTAssertGreaterThan(landmark.confidence, 0)
            }
        }
        await replay.close()
        XCTAssertGreaterThanOrEqual(framesWithTorso, 8, "Real Vision torso extraction failed; mocks cannot pass this test.")
    }

    func testRestartReopensAtIdenticalSourceFrameAndResetsIndex() async throws {
        let replay = VideoReplay()
        let url = try fixture()
        try await replay.open(url)
        let a = try await replay.next()
        _ = try await replay.next()
        await replay.close()
        try await replay.open(url)
        let b = try await replay.next()
        XCTAssertEqual(try XCTUnwrap(a).pose.timestamp, try XCTUnwrap(b).pose.timestamp)
        XCTAssertEqual(b?.index, 1)
        await replay.close()
    }

    func testMissingOrInvalidFileFailsRatherThanReturningSuccessfulEmptyReplay() async throws {
        let replay = VideoReplay()
        do {
            try await replay.open(URL(fileURLWithPath: "/not-a-video/absent.mov"))
            XCTFail("Opening a missing file must fail")
        } catch { /* Expected: reader/asset failure. */ }
        await replay.close()
    }

    @MainActor
    func testUIModelReceivesRealFramesAndRestartResetsPlayback() async throws {
        let model = ReplayModel()
        defer { model.shutdown() }
        model.load(try fixture())
        try await Self.waitUntil { model.phase != .loading }
        XCTAssertEqual(model.phase, .ready, model.errorMessage ?? "")
        let first = try XCTUnwrap(model.frame)
        XCTAssertEqual(first.index, 1)
        XCTAssertEqual(first.pose.personCount, 1)
        XCTAssertFalse(first.pose.landmarks.isEmpty)

        model.play()
        // Exercise immediate pause/resume while the one playback task exists.
        model.pause()
        model.play()
        try await Self.waitUntil { (model.frame?.index ?? 0) >= 3 || model.phase == .failed }
        XCTAssertEqual(model.phase, .playing, model.errorMessage ?? "")
        XCTAssertGreaterThan(try XCTUnwrap(model.frame).pose.timestamp, first.pose.timestamp)
        model.pause()
        XCTAssertEqual(model.phase, .paused)

        model.restart()
        try await Self.waitUntil { model.phase != .loading }
        XCTAssertEqual(model.phase, .ready, model.errorMessage ?? "")
        XCTAssertEqual(model.frame?.index, 1)
        XCTAssertEqual(model.frame?.pose.timestamp, first.pose.timestamp)
    }

    @MainActor
    private static func waitUntil(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(30))
        while !condition(), ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertTrue(condition(), "Timed out waiting for replay state")
    }

    @MainActor
    func testReplacingImportCannotPublishTheOldSession() async throws {
        let model = ReplayModel()
        model.load(try fixture())
        model.load(URL(fileURLWithPath: "/not-a-video/absent.mov"))
        let deadline = ContinuousClock.now.advanced(by: .seconds(20))
        while model.phase == .loading, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertEqual(model.phase, .failed)
        XCTAssertNil(model.frame)
        XCTAssertNotNil(model.errorMessage)
        model.shutdown()
    }
}
