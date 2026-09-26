import AVFoundation
import CoreImage
import Foundation

struct AnalyzedFrame: Sendable {
    let image: CGImage
    let pose: PoseObservation
    let index: Int
}

// A concrete sequential reader, not a frame-source framework. Decode, orientation
// and Vision run on this actor, off the main actor. Only one frame is requested at
// a time. Rendering the very same CGImage eliminates player/overlay clock drift.
actor VideoReplay {
    private var reader: AVAssetReader?
    private var output: AVAssetReaderTrackOutput?
    private var scopedURL: URL?
    private var hasSecurityScope = false
    private var orientation: Int32 = 1
    private var timeline = FrameTimeline()
    private let estimator = VisionPoseEstimator()
    private let context = CIContext(options: [.cacheIntermediates: false])

    func open(_ url: URL) async throws {
        close()
        let access = url.startAccessingSecurityScopedResource()
        // Keep this scope local while awaiting metadata: actor reentrancy cannot
        // cause a concurrent close() to release a different import's access.
        do {
            let asset = AVURLAsset(url: url)
            guard let track = try await asset.loadTracks(withMediaType: .video).first else {
                throw ReplayError.noVideo
            }
            let transform = try await track.load(.preferredTransform)
            try Task.checkCancellation()
            let orientation = try VideoOrientation(a: transform.a, b: transform.b,
                                                   c: transform.c, d: transform.d)
            let reader = try AVAssetReader(asset: asset)
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
            ])
            output.alwaysCopiesSampleData = false
            guard reader.canAdd(output) else { throw ReplayError.decoderUnavailable }
            reader.add(output)
            guard reader.startReading() else { throw reader.error ?? ReplayError.decoderUnavailable }
            self.reader = reader
            self.output = output
            self.orientation = orientation.exif
            scopedURL = url
            hasSecurityScope = access
        } catch {
            if access { url.stopAccessingSecurityScopedResource() }
            throw error
        }
    }

    func next() throws -> AnalyzedFrame? {
        try Task.checkCancellation()
        guard let reader, let output else { throw ReplayError.decoderUnavailable }
        return try autoreleasepool {
            guard let sample = output.copyNextSampleBuffer() else {
                if reader.status == .failed { throw reader.error ?? ReplayError.invalidFrame }
                if reader.status == .cancelled { throw CancellationError() }
                return nil
            }
            let timestamp = CMSampleBufferGetPresentationTimeStamp(sample).seconds
            try timeline.accept(timestamp)
            guard let pixels = CMSampleBufferGetImageBuffer(sample) else {
                throw ReplayError.invalidFrame
            }
            let oriented = CIImage(cvPixelBuffer: pixels).oriented(forExifOrientation: orientation)
            // Bound retained image size, uniformly. Vision and the UI receive the
            // same rendered pixels. No later crop/mirror/rotation is applied.
            let scale = min(1, 1280 / max(oriented.extent.width, oriented.extent.height))
            let normalized = oriented.transformed(by: CGAffineTransform(
                translationX: -oriented.extent.minX, y: -oriented.extent.minY
            )).transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            guard let image = context.createCGImage(normalized, from: normalized.extent) else {
                throw ReplayError.invalidFrame
            }
            let pose = try estimator.estimate(image: image, timestamp: timestamp)
            return AnalyzedFrame(image: image, pose: pose, index: timeline.count)
        }
    }

    func close() {
        reader?.cancelReading()
        output = nil
        reader = nil
        if hasSecurityScope { scopedURL?.stopAccessingSecurityScopedResource() }
        scopedURL = nil
        hasSecurityScope = false
        timeline.reset()
    }
}
