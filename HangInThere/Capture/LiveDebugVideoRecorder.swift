#if os(iOS)
@preconcurrency import AVFoundation
@preconcurrency import CoreMedia
import CryptoKit
import Foundation

enum LiveDebugDigest {
    static func sha256(data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func sha256(fileURL: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty {
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

final class LiveDebugVideoRecorder: @unchecked Sendable {
    enum Failure: Error, Equatable, Sendable {
        case noSamples
        case invalidSample
        case cannotCreateWriter(String)
        case cannotAddWriterInput
        case writerFailed(String)
        case fileInspectionFailed(String)
    }

    struct Recording: Sendable {
        let fileURL: URL
        let summary: LiveDebugCaptureSummary
    }

    static let maximumPendingSamples = 4

    private let outputURL: URL
    private let queue = DispatchQueue(
        label: "dev.yongkyuns.HangInThere.debug-video-writer",
        qos: .utility
    )
    private let lock = NSLock()

    private var accepting = true
    private var pendingSamples = 0
    private var droppedQueueSamples = 0

    // Accessed only on queue.
    private var writer: AVAssetWriter?
    private var input: AVAssetWriterInput?
    private var firstPTS: CMTime?
    private var lastPTS: CMTime?
    private var appendedSamples = 0
    private var droppedWriterSamples = 0
    private var terminalFailure: Failure?

    init(outputURL: URL) throws {
        self.outputURL = outputURL
        let parent = outputURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(
            at: parent,
            withIntermediateDirectories: true
        )
        try? FileManager.default.removeItem(at: outputURL)
    }

    func offer(_ sampleBuffer: CMSampleBuffer) {
        guard CMSampleBufferDataIsReady(sampleBuffer) else { return }

        lock.lock()
        guard accepting else {
            lock.unlock()
            return
        }
        guard pendingSamples < Self.maximumPendingSamples else {
            droppedQueueSamples += 1
            lock.unlock()
            return
        }
        pendingSamples += 1
        lock.unlock()

        queue.async { [self] in
            defer {
                lock.lock()
                pendingSamples -= 1
                lock.unlock()
            }
            append(sampleBuffer)
        }
    }

    func finish() async -> Result<Recording, Failure> {
        lock.lock()
        accepting = false
        lock.unlock()

        return await withCheckedContinuation { continuation in
            queue.async { [self] in
                if let terminalFailure {
                    continuation.resume(returning: .failure(terminalFailure))
                    return
                }
                guard let writer, let input,
                      appendedSamples > 0,
                      let firstPTS, let lastPTS
                else {
                    continuation.resume(returning: .failure(.noSamples))
                    return
                }

                input.markAsFinished()
                writer.finishWriting { [self] in
                    queue.async { [self] in
                        guard writer.status == .completed else {
                            let message = writer.error?.localizedDescription
                                ?? "AVAssetWriter status \(writer.status.rawValue)"
                            continuation.resume(
                                returning: .failure(.writerFailed(message))
                            )
                            return
                        }

                        do {
                            let attributes = try FileManager.default.attributesOfItem(
                                atPath: outputURL.path
                            )
                            let bytes = (attributes[.size] as? NSNumber)?.int64Value ?? 0
                            let hash = try LiveDebugDigest.sha256(fileURL: outputURL)
                            let first = CMTimeGetSeconds(firstPTS)
                            let last = CMTimeGetSeconds(lastPTS)
                            guard first.isFinite, last.isFinite, last >= first else {
                                continuation.resume(returning: .failure(.invalidSample))
                                return
                            }

                            lock.lock()
                            let queueDrops = droppedQueueSamples
                            lock.unlock()

                            continuation.resume(returning: .success(Recording(
                                fileURL: outputURL,
                                summary: LiveDebugCaptureSummary(
                                    videoFileName: outputURL.lastPathComponent,
                                    videoSHA256: hash,
                                    videoBytes: bytes,
                                    firstCameraSeconds: first,
                                    lastCameraSeconds: last,
                                    appendedSamples: appendedSamples,
                                    droppedQueueSamples: queueDrops,
                                    droppedWriterSamples: droppedWriterSamples
                                )
                            )))
                        } catch {
                            continuation.resume(
                                returning: .failure(
                                    .fileInspectionFailed(error.localizedDescription)
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    func cancelAndDelete() {
        lock.lock()
        accepting = false
        lock.unlock()
        queue.async { [self] in
            writer?.cancelWriting()
            writer = nil
            input = nil
            try? FileManager.default.removeItem(at: outputURL)
        }
    }

    private func append(_ sampleBuffer: CMSampleBuffer) {
        guard terminalFailure == nil else { return }

        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        guard pts.isNumeric, pts.epoch == 0,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)
        else {
            terminalFailure = .invalidSample
            return
        }

        if writer == nil {
            do {
                try startWriter(
                    firstSample: sampleBuffer,
                    pixelBuffer: pixelBuffer,
                    pts: pts
                )
            } catch let failure as Failure {
                terminalFailure = failure
                return
            } catch {
                terminalFailure = .cannotCreateWriter(error.localizedDescription)
                return
            }
        }

        guard let writer, let input, writer.status == .writing else {
            terminalFailure = .writerFailed(
                self.writer?.error?.localizedDescription ?? "writer not in writing state"
            )
            return
        }
        guard input.isReadyForMoreMediaData else {
            droppedWriterSamples += 1
            return
        }
        guard input.append(sampleBuffer) else {
            terminalFailure = .writerFailed(
                writer.error?.localizedDescription ?? "append returned false"
            )
            return
        }

        if firstPTS == nil { firstPTS = pts }
        lastPTS = pts
        appendedSamples += 1
    }

    private func startWriter(
        firstSample: CMSampleBuffer,
        pixelBuffer: CVPixelBuffer,
        pts: CMTime
    ) throws {
        guard let format = CMSampleBufferGetFormatDescription(firstSample) else {
            throw Failure.invalidSample
        }

        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        guard width > 0, height > 0 else { throw Failure.invalidSample }

        let writer: AVAssetWriter
        do {
            writer = try AVAssetWriter(outputURL: outputURL, fileType: .mov)
        } catch {
            throw Failure.cannotCreateWriter(error.localizedDescription)
        }

        let pixelsPerFrame = width * height
        let bitRate = min(12_000_000, max(2_000_000, pixelsPerFrame * 4))
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitRate,
                AVVideoExpectedSourceFrameRateKey: 30,
                AVVideoMaxKeyFrameIntervalKey: 30
            ]
        ]
        let input = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: settings,
            sourceFormatHint: format
        )
        input.expectsMediaDataInRealTime = true

        guard writer.canAdd(input) else {
            throw Failure.cannotAddWriterInput
        }
        writer.add(input)
        guard writer.startWriting() else {
            throw Failure.writerFailed(
                writer.error?.localizedDescription ?? "startWriting returned false"
            )
        }
        writer.startSession(atSourceTime: pts)

        self.writer = writer
        self.input = input
    }
}

final class LiveDebugCaptureRouter: @unchecked Sendable {
    private let lock = NSLock()
    private var recorder: LiveDebugVideoRecorder?

    func attach(_ recorder: LiveDebugVideoRecorder) {
        lock.lock()
        self.recorder = recorder
        lock.unlock()
    }

    @discardableResult
    func detach() -> LiveDebugVideoRecorder? {
        lock.lock()
        defer { lock.unlock() }
        let current = recorder
        recorder = nil
        return current
    }

    func offer(_ sampleBuffer: CMSampleBuffer) {
        lock.lock()
        let current = recorder
        lock.unlock()
        current?.offer(sampleBuffer)
    }
}
#endif
