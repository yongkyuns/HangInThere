import AVFoundation
import CoreImage
import Foundation

struct VideoInfo: Sendable {
    let durationSeconds: Double
}

struct ProcessedFrame: Sendable {
    let image: CGImage
    let pose: PoseResult
    let processingMilliseconds: Double
}

enum ReplayError: LocalizedError {
    case noVideo, notOpen, invalidTime, invalidGeometry, noFrames
    case decoding(String)

    var errorDescription: String? {
        switch self {
        case .noVideo: "The selected file has no supported video track. Choose an MP4 or MOV video."
        case .notOpen: "Open a video before starting replay."
        case .invalidTime: "This video contains invalid or out-of-order frame timestamps."
        case .invalidGeometry: "This video has an unsupported size, crop, or pixel aspect ratio."
        case .noFrames: "No video frames could be decoded from this file."
        case .decoding(let message): "Video processing failed: \(message)"
        }
    }
}

// Preparation transfers an exclusively owned decoder graph into this actor.
// After that, the reader, sample buffers, CIContext and inference stay here.
// The main actor only receives immutable CGImages and Sendable observation data.
// One nextFrame() call consumes exactly one source frame; no frame queue or timer.
actor VideoReplayReader {
    private var reader: AVAssetReader?
    private var output: AVAssetReaderTrackOutput?
    private var localURL: URL?
    private var transform = Affine2D()
    private var timeline = ReplayTimeline()
    private var generation: UInt64 = 0
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let estimator: any PoseEstimator
    private let maximumDimension = 1280.0

    init(estimator: any PoseEstimator = VisionPoseEstimator()) {
        self.estimator = estimator
    }

    func open(_ source: URL) async throws -> VideoInfo {
        close()
        let token = generation
        do {
            try Task.checkCancellation()
            let copy = try importCopy(source)
            return try await prepare(copy, token: token)
        } catch {
            if generation == token { close() }
            throw error
        }
    }

    private func importCopy(_ source: URL) throws -> URL {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let suffix = source.pathExtension.isEmpty ? "mov" : source.pathExtension
        let copy = FileManager.default.temporaryDirectory
            .appendingPathComponent("HangInThere-\(UUID().uuidString).\(suffix)")
        localURL = copy
        try FileManager.default.copyItem(at: source, to: copy)
        return copy
    }

    func rewind() async throws -> VideoInfo {
        guard let localURL else { throw ReplayError.notOpen }
        generation &+= 1
        let token = generation
        reader?.cancelReading()
        reader = nil
        output = nil
        timeline = ReplayTimeline()
        do {
            return try await prepare(localURL, token: token)
        } catch {
            if generation == token {
                reader?.cancelReading()
                reader = nil
                output = nil
            }
            throw error
        }
    }

    private func prepare(_ url: URL, token: UInt64) async throws -> VideoInfo {
        let prepared = try await Self.makeDecoder(url)
        try Task.checkCancellation()
        guard token == generation else { throw CancellationError() }
        // Do not start a decoder for a cancelled or superseded session.
        guard prepared.reader.startReading() else {
            throw ReplayError.decoding(prepared.reader.error?.localizedDescription ?? "Could not start the decoder.")
        }
        transform = prepared.transform
        timeline = ReplayTimeline()
        reader = prepared.reader
        output = prepared.output
        return prepared.info
    }

    // AVAssetTrack is non-Sendable in the supported SDK. Load it outside actor
    // isolation, then transfer the whole fresh object graph once using `sending`.
    // Nothing here is shared with another task or retained after the transfer;
    // the compiler checks ownership without @preconcurrency/@unchecked Sendable.
    private nonisolated static func makeDecoder(_ url: URL) async throws -> sending (
        reader: AVAssetReader, output: AVAssetReaderTrackOutput,
        transform: Affine2D, info: VideoInfo
    ) {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else {
            throw ReplayError.noVideo
        }
        let preferred = try await track.load(.preferredTransform)
        let range = try await track.load(.timeRange)
        try Task.checkCancellation()
        guard range.duration.isNumeric, range.duration.seconds.isFinite,
              range.duration.seconds > 0 else { throw ReplayError.invalidTime }

        let nextReader = try AVAssetReader(asset: asset)
        let nextOutput = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ])
        nextOutput.alwaysCopiesSampleData = false
        guard nextReader.canAdd(nextOutput) else { throw ReplayError.noVideo }
        nextReader.add(nextOutput)
        return (
            nextReader, nextOutput,
            Affine2D(a: preferred.a, b: preferred.b, c: preferred.c,
                     d: preferred.d, tx: preferred.tx, ty: preferred.ty),
            VideoInfo(durationSeconds: range.duration.seconds)
        )
    }

    func nextFrame() throws -> ProcessedFrame? {
        guard let reader, let output else { throw ReplayError.notOpen }
        return try autoreleasepool {
            let started = ContinuousClock.now
            guard let sample = output.copyNextSampleBuffer() else {
                if reader.status == .completed { return nil }
                throw ReplayError.decoding(reader.error?.localizedDescription ?? "The decoder stopped unexpectedly.")
            }
            let pts = CMSampleBufferGetPresentationTimeStamp(sample)
            guard pts.isNumeric, pts.epoch == 0 else { throw ReplayError.invalidTime }
            let timestamp = PresentationTime(value: pts.value, timescale: pts.timescale)
            guard timeline.accept(timestamp) else { throw ReplayError.invalidTime }
            guard let buffer = CMSampleBufferGetImageBuffer(sample) else {
                throw ReplayError.decoding("A decoded frame has no image buffer.")
            }
            let width = Double(CVPixelBufferGetWidth(buffer))
            let height = Double(CVPixelBufferGetHeight(buffer))
            // The first profile is square-pixel video. Reject non-square pixels
            // and nontrivial clean apertures instead of drawing a shifted overlay.
            if let format = CMSampleBufferGetFormatDescription(sample) {
                let displayed = CMVideoFormatDescriptionGetPresentationDimensions(
                    format, usePixelAspectRatio: true, useCleanAperture: true)
                guard abs(displayed.width - width) < 1, abs(displayed.height - height) < 1 else {
                    throw ReplayError.invalidGeometry
                }
            }
            guard let geometry = OrientedGeometry(
                sourceSize: ImageSize(width: width, height: height), preferredTransform: transform
            ) else { throw ReplayError.invalidGeometry }
            let t = geometry.coreImageTransform
            var pixels = CIImage(cvPixelBuffer: buffer).transformed(by: CGAffineTransform(
                a: t.a, b: t.b, c: t.c, d: t.d, tx: t.tx, ty: t.ty))
            let scale = min(1, maximumDimension / max(geometry.outputSize.width, geometry.outputSize.height))
            pixels = pixels.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            let rect = CGRect(x: 0, y: 0,
                              width: max(1, (geometry.outputSize.width * scale).rounded()),
                              height: max(1, (geometry.outputSize.height * scale).rounded()))
            guard let image = context.createCGImage(pixels, from: rect) else {
                throw ReplayError.decoding("The oriented image could not be rendered.")
            }
            let pose = try estimator.estimate(image: image, timestamp: timestamp)
            let duration = started.duration(to: .now).components
            let milliseconds = Double(duration.seconds) * 1_000 + Double(duration.attoseconds) / 1e15
            return ProcessedFrame(image: image, pose: pose, processingMilliseconds: milliseconds)
        }
    }

    func close() {
        generation &+= 1
        reader?.cancelReading()
        reader = nil
        output = nil
        timeline = ReplayTimeline()
        if let localURL { try? FileManager.default.removeItem(at: localURL) }
        localURL = nil
    }
}
