import AVFoundation
import Foundation
import Testing
@testable import HangInThere

struct LiveDebugVideoRecorderTests {
    @Test func recorderWritesReplayableVideoAndPinsItsBytes() async throws {
        let sourceURL = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: sourceURL) }

        let asset = AVURLAsset(url: sourceURL)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else {
            throw FixtureError.failed("Synthetic source has no video track.")
        }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String:
                    kCVPixelFormatType_32BGRA
            ]
        )
        guard reader.canAdd(output) else {
            throw FixtureError.failed("Cannot add debug recorder source output.")
        }
        reader.add(output)
        guard reader.startReading() else {
            throw FixtureError.failed(
                reader.error?.localizedDescription ?? "Reader did not start."
            )
        }

        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("debug-recorder-\(UUID().uuidString).mov")
        let recorder = try LiveDebugVideoRecorder(outputURL: destination)
        var offered = 0
        while let sample = output.copyNextSampleBuffer() {
            recorder.offer(sample)
            offered += 1
        }
        #expect(offered == VideoTestSupport.timestamps.count)

        let result = await recorder.finish()
        let recording: LiveDebugVideoRecorder.Recording
        switch result {
        case .success(let value):
            recording = value
        case .failure(let failure):
            throw FixtureError.failed("Debug recorder failed: \(failure)")
        }
        defer { try? FileManager.default.removeItem(at: recording.fileURL) }

        #expect(recording.summary.appendedSamples > 0)
        #expect(
            recording.summary.appendedSamples
                + recording.summary.droppedQueueSamples
                + recording.summary.droppedWriterSamples
                == offered
        )
        #expect(recording.summary.videoBytes > 0)
        #expect(recording.summary.videoSHA256.count == 64)
        #expect(
            LiveDebugDigest.sha256(fileURL: recording.fileURL)
                == recording.summary.videoSHA256
        )

        let recordedAsset = AVURLAsset(url: recording.fileURL)
        let recordedTracks = try await recordedAsset.loadTracks(withMediaType: .video)
        #expect(recordedTracks.count == 1)
        let duration = try await recordedAsset.load(.duration)
        #expect(duration.seconds >= 0)
    }

    @Test func routerDetachesWithoutOfferingFutureSamples() async throws {
        let sourceURL = try await VideoTestSupport.makeVideo()
        defer { try? FileManager.default.removeItem(at: sourceURL) }

        let asset = AVURLAsset(url: sourceURL)
        let track = try #require(try await asset.loadTracks(withMediaType: .video).first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String:
                    kCVPixelFormatType_32BGRA
            ]
        )
        reader.add(output)
        #expect(reader.startReading())

        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("debug-router-\(UUID().uuidString).mov")
        let recorder = try LiveDebugVideoRecorder(outputURL: destination)
        let router = LiveDebugCaptureRouter()
        router.attach(recorder)

        let first = try #require(output.copyNextSampleBuffer())
        router.offer(first)
        let detached = try #require(router.detach())
        #expect(detached === recorder)

        while let sample = output.copyNextSampleBuffer() {
            router.offer(sample)
        }

        let result = await recorder.finish()
        switch result {
        case .success(let recording):
            defer { try? FileManager.default.removeItem(at: recording.fileURL) }
            #expect(recording.summary.appendedSamples == 1)
        case .failure(let failure):
            throw FixtureError.failed("Detached recorder failed: \(failure)")
        }
    }
}
