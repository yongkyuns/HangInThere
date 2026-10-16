import AVFoundation
import CoreImage
import Dispatch
import Foundation
import os

protocol GripObservationScheduling: Sendable {
    func observe(image: CGImage, pose: PoseResult) -> WorkoutGripObservation?
    func reset()
    func setEnabled(_ enabled: Bool)
}

struct VideoInfo: Sendable {
    let durationSeconds: Double
}

struct ProcessedFrame: Sendable {
    let image: CGImage
    let pose: PoseResult
    let processingMilliseconds: Double
}

// A playback pause cancels its task but must preserve an already consumed frame
// for resume. Source disposal instead marks this session's frames obsolete.
// Only the flag crosses executors; decoder objects remain actor-owned. Never
// hold the lock while calling a native API or awaiting another actor.
struct ReplayFrameDiscardSignal: Sendable {
    private let state = OSAllocatedUnfairLock(initialState: false)

    func discard() { state.withLock { $0 = true } }

    func check() throws {
        if state.withLock({ $0 }) { throw CancellationError() }
    }
}

// Opt-in pipeline-stage diagnostics contain only timing and source time. The
// callback runs on the worker executing the stage; it must not block that work.
// Begin/end pairs also identify a native call that has not returned yet.
// End marks return or throw, not success. Durations include callback overhead
// and scheduler delays, so these are diagnostics rather than throughput budgets.
struct ReplayTrace: Sendable {
    enum Stage: String, Sendable {
        case operationWait, rewindCancellation
        case context, preparation, decoding, geometry, rendering, estimation, counting
    }
    enum Boundary: String, Sendable { case began, ended }
    typealias Handler = @Sendable (ReplayTrace) -> Void

    let stage: Stage
    let boundary: Boundary
    let sourceSeconds: Double?
    let milliseconds: Double?
    var renderingBackend: FrameRenderingBackend? = nil

    static func milliseconds(since start: ContinuousClock.Instant) -> Double {
        let duration = start.duration(to: .now).components
        return Double(duration.seconds) * 1_000 + Double(duration.attoseconds) / 1e15
    }
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

// AVAssetReader, Core Image and inference perform blocking native work. Run
// those actor jobs on a dedicated serial Dispatch executor instead of occupying
// a Swift cooperative worker while a framework waits for its own producer.
// Dispatch owns thread scheduling; the actor still checks isolation and the
// controller still requests one frame at a time.
final class NativeFrameExecutor: SerialExecutor {
    private let queue: DispatchQueue
    init(label: String = "dev.hanginthere.replay", qos: DispatchQoS = .userInitiated) {
        queue = DispatchQueue(label: label, qos: qos)
    }

    func enqueue(_ job: consuming ExecutorJob) {
        let unownedJob = UnownedJob(job)
        queue.async { [self] in
            unownedJob.runSynchronously(on: asUnownedSerialExecutor())
        }
    }
}

// Preparation transfers an exclusively owned decoder graph into this actor.
// After that, the reader, sample buffers, CIContext and inference stay here.
// The main actor only receives immutable CGImages and Sendable observation data.
// One nextFrame() call consumes exactly one source frame; no frame queue or timer.
actor VideoReplayReader {
    private nonisolated let executor = NativeFrameExecutor()
    nonisolated var unownedExecutor: UnownedSerialExecutor { executor.asUnownedSerialExecutor() }

    private var reader: AVAssetReader?
    private var output: AVAssetReaderTrackOutput?
    private var localURL: URL?
    private var transform = Affine2D()
    private var timeline = ReplayTimeline()
    private var generation: UInt64 = 0
    // Create once on this actor's executor at first render. Actor initializers
    // run on their caller, which may be the presentation actor.
    private var context: CIContext?
    private let estimator: any PoseEstimator
    private let trace: ReplayTrace.Handler?
    private let gripObserver: (any GripObservationScheduling)?
    private let maximumDimension = 1280.0

    init(estimator: any PoseEstimator = VisionPoseEstimator(), trace: ReplayTrace.Handler? = nil, gripObserver: (any GripObservationScheduling)? = nil) {
        self.estimator = estimator
        self.trace = trace
        self.gripObserver = gripObserver
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

    func setGripInferenceEnabled(_ enabled: Bool) {
        gripObserver?.setEnabled(enabled)
    }

    func rewind() async throws -> VideoInfo {
        guard let localURL else { throw ReplayError.notOpen }
        generation &+= 1
        gripObserver?.reset()
        let token = generation
        measure(.rewindCancellation) {
            reader?.cancelReading()
            reader = nil
            output = nil
            timeline = ReplayTimeline()
        }
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
        let started = ContinuousClock.now
        trace?(.init(stage: .preparation, boundary: .began, sourceSeconds: nil, milliseconds: nil))
        defer {
            trace?(.init(stage: .preparation, boundary: .ended, sourceSeconds: nil,
                         milliseconds: ReplayTrace.milliseconds(since: started)))
        }
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

    func nextFrame(discard: ReplayFrameDiscardSignal? = nil) throws -> ProcessedFrame? {
        try autoreleasepool { try consumeFrame(discard: discard) }
    }

    // Keep non-Sendable sample/buffer borrows in an explicitly actor-isolated
    // method; only the actor reference enters Foundation's pool closure.
    private func consumeFrame(discard: ReplayFrameDiscardSignal?) throws -> ProcessedFrame? {
        guard let reader, let output else { throw ReplayError.notOpen }
        try discard?.check()
        let started = ContinuousClock.now
        let decoded = measure(.decoding, { output.copyNextSampleBuffer() })
        // A blocking native read cannot observe disposal until it returns.
        // Avoid rendering/inference for that obsolete session afterwards.
        try discard?.check()
        guard let sample = decoded else {
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
        let geometry = try measure(.geometry, sourceSeconds: timestamp.seconds) {
            guard let geometry = OrientedGeometry(
                sourceSize: ImageSize(width: width, height: height), preferredTransform: transform
            ) else { throw ReplayError.invalidGeometry }
            return geometry
        }
        try discard?.check()
        let source = CIImage(cvPixelBuffer: buffer)
        let colorSpace = source.colorSpace
        var rendered: CGImage?
        if BitmapFrameRenderer.supports(buffer, geometry: geometry, colorSpace: colorSpace,
                                        maximumDimension: maximumDimension, isolation: self) {
            rendered = bitmapImage(buffer, geometry: geometry, colorSpace: colorSpace, sourceSeconds: timestamp.seconds)
            try discard?.check()
        }
        if rendered == nil {
            let renderingContext = imageContext()
            try discard?.check()
            rendered = measure(.rendering, sourceSeconds: timestamp.seconds, renderingBackend: .coreImage) {
                let t = geometry.coreImageTransform
                var pixels = source.transformed(by: CGAffineTransform(
                    a: t.a, b: t.b, c: t.c, d: t.d, tx: t.tx, ty: t.ty))
                let scale = min(1, maximumDimension / max(geometry.outputSize.width, geometry.outputSize.height))
                pixels = pixels.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
                let rect = CGRect(x: 0, y: 0,
                                  width: max(1, (geometry.outputSize.width * scale).rounded()),
                                  height: max(1, (geometry.outputSize.height * scale).rounded()))
                return renderingContext.createCGImage(pixels, from: rect)
            }
        }
        guard let image = rendered else {
            throw ReplayError.decoding("The oriented image could not be rendered.")
        }
        try discard?.check()
        var pose = try measure(.estimation, sourceSeconds: timestamp.seconds) {
            try estimator.estimate(image: image, timestamp: timestamp)
        }
        try discard?.check()
        pose.gripObservation = gripObserver?.observe(image: image, pose: pose)
        let milliseconds = ReplayTrace.milliseconds(since: started)
        return ProcessedFrame(image: image, pose: pose, processingMilliseconds: milliseconds)
    }

    // Borrow the buffer directly on this actor rather than capture it in a
    // diagnostic closure while a lazy CIImage can still reference its pixels.
    private func bitmapImage(_ buffer: CVPixelBuffer, geometry: OrientedGeometry,
                             colorSpace: CGColorSpace?, sourceSeconds: Double) -> CGImage? {
        let started = ContinuousClock.now
        trace?(.init(stage: .rendering, boundary: .began, sourceSeconds: sourceSeconds,
                     milliseconds: nil, renderingBackend: .bitmap))
        defer {
            trace?(.init(stage: .rendering, boundary: .ended, sourceSeconds: sourceSeconds,
                         milliseconds: ReplayTrace.milliseconds(since: started), renderingBackend: .bitmap))
        }
        return BitmapFrameRenderer.render(buffer, geometry: geometry, colorSpace: colorSpace,
                                          maximumDimension: maximumDimension, isolation: self)
    }

    private func imageContext() -> CIContext {
        if let context { return context }
        let created = measure(.context) { CIContext(options: [.cacheIntermediates: false]) }
        context = created
        return created
    }

    private func measure<Result>(_ stage: ReplayTrace.Stage, sourceSeconds: Double? = nil,
                                 renderingBackend: FrameRenderingBackend? = nil,
                                 _ work: () throws -> Result) rethrows -> Result {
        guard let trace else { return try work() }
        let started = ContinuousClock.now
        trace(.init(stage: stage, boundary: .began, sourceSeconds: sourceSeconds, milliseconds: nil,
                    renderingBackend: renderingBackend))
        defer {
            trace(.init(stage: stage, boundary: .ended, sourceSeconds: sourceSeconds,
                        milliseconds: ReplayTrace.milliseconds(since: started), renderingBackend: renderingBackend))
        }
        return try work()
    }

    func close() {
        gripObserver?.reset()
        generation &+= 1
        reader?.cancelReading()
        reader = nil
        output = nil
        timeline = ReplayTimeline()
        if let localURL { try? FileManager.default.removeItem(at: localURL) }
        localURL = nil
    }
}
