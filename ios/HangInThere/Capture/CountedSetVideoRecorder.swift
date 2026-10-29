@preconcurrency import AVFoundation
import Foundation

/// The retained movie spans completed image cycles only, never setup/release attempts.
struct CountedSetVideoRange: Equatable, Sendable {
    let start: Double
    let end: Double
    init?(record: WorkoutSetRecord) {
        let reps = record.completedReps
        guard let start = reps.map(\.startedAt).min(),
              let end = reps.compactMap(\.returnedAt).max(),
              start.isFinite, end.isFinite, end > start else { return nil }
        self.start = start; self.end = end
    }
}

enum CountedSetVideoError: LocalizedError, Equatable {
    case unavailable, tooLong, writingFailed
    var errorDescription: String? {
        switch self {
        case .unavailable: "The full counted segment wasn't available. Your reps are still saved."
        case .tooLong: "The set exceeded the 10-minute video limit. Your reps are still saved."
        case .writingFailed: "The set video couldn't be saved. Your reps are still saved."
        }
    }
}

/// Camera PTS owns the video clock. A small, compressed rolling window recovers
/// automatic starts that are confirmed after the first rep. Idle files are pruned;
/// only the completed-rep interval is published to the workout library.
/// Mutable state is confined to queue; at most two camera buffers can be pending.
final class CountedSetVideoRecorder: @unchecked Sendable {
    private final class Sample: @unchecked Sendable {
        let buffer: CMSampleBuffer
        init(_ buffer: CMSampleBuffer) { self.buffer = buffer }
    }
    private final class Piece: @unchecked Sendable {
        let url: URL
        let start: Double
        var end: Double
        let writer: AVAssetWriter
        let input: AVAssetWriterInput
        let done = DispatchGroup()
        init(url: URL, start: Double, writer: AVAssetWriter, input: AVAssetWriterInput) {
            self.url = url; self.start = start; self.end = start; self.writer = writer; self.input = input
            done.enter()
        }
        func finished() async {
            await withCheckedContinuation { continuation in
                done.notify(queue: .global(qos: .utility)) { continuation.resume() }
            }
        }
    }
    private let queue = DispatchQueue(label: "dev.hanginthere.set-video", qos: .utility)
    private let bufferLock = NSLock()
    private var pendingBuffers = 0
    private let directory: URL
    private var enabled = false
    private var pieces: [Piece] = []
    private var current: Piece?
    private var setId: String?
    private var setStart: Double?
    private var failure: (any Error)?
    private let preRollSeconds = 12.0

    init(directory: URL = FileManager.default.temporaryDirectory.appendingPathComponent("CountedSetVideo", isDirectory: true)) {
        // A process-specific child avoids deleting files still owned by an export.
        self.directory = directory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        // Recover abandoned temporary chunks after a crash; never touch recent exports.
        Task.detached(priority: .utility) {
            let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.creationDateKey])) ?? []
            for file in files {
                if let created = try? file.resourceValues(forKeys: [.creationDateKey]).creationDate,
                   created.timeIntervalSinceNow < -86_400 { try? FileManager.default.removeItem(at: file) }
            }
        }
    }
    func setEnabled(_ enabled: Bool) {
        queue.async { self.enabled = enabled; if !enabled { self.clear() } }
    }
    func reset() { queue.async { self.clear() } }
    func begin(setId: String, startedAt: Double?) {
        queue.async {
            guard self.enabled else { return }
            self.setId = setId; self.setStart = startedAt
        }
    }
    func append(_ buffer: CMSampleBuffer) {
        // Never wait on the encoder from the camera/analysis callback.
        guard bufferLock.try() else { return }
        let accepted = pendingBuffers < 2
        if accepted { pendingBuffers += 1 }
        bufferLock.unlock()
        guard accepted else { return }
        let sample = Sample(buffer)
        queue.async {
            defer { self.bufferLock.withLock { self.pendingBuffers -= 1 } }
            guard self.enabled, self.failure == nil else { return }
            do { try self.consume(sample.buffer) }
            catch { self.failure = error; self.closeCurrent() }
        }
    }
    func complete(_ record: WorkoutSetRecord, completion: @escaping @Sendable (Result<URL?, any Error>) -> Void) {
        queue.async {
            guard self.enabled, self.setId == record.id else { completion(.success(nil)); return }
            self.closeCurrent()
            let pieces = self.pieces, failure = self.failure, directory = self.directory
            self.pieces = []; self.setId = nil; self.setStart = nil; self.failure = nil
            let range = CountedSetVideoRange(record: record)
            Task.detached(priority: .utility) {
                for piece in pieces { await piece.finished() }
                defer { for piece in pieces { try? FileManager.default.removeItem(at: piece.url) } }
                guard let range else { completion(.success(nil)); return }
                do {
                    if let failure { throw failure }
                    let url = try await Self.export(pieces: pieces, range: range, directory: directory)
                    completion(.success(url))
                } catch { completion(.failure(error)) }
            }
        }
    }
    // Also used to drive real encoder tests without racing the bounded camera queue.
    func drain() async { await withCheckedContinuation { continuation in queue.async { continuation.resume() } } }

    private func consume(_ sample: CMSampleBuffer) throws {
        let pts = CMSampleBufferGetPresentationTimeStamp(sample)
        guard pts.isNumeric, pts.epoch == 0, let format = CMSampleBufferGetFormatDescription(sample) else { return }
        let seconds = pts.seconds
        if let start = setStart, seconds - start > 600 { throw CountedSetVideoError.tooLong }
        if let current, seconds - current.start >= 3 { closeCurrent(at: pts) }
        if current == nil {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let url = directory.appendingPathComponent(UUID().uuidString).appendingPathExtension("mov")
            let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
            let dimensions = CMVideoFormatDescriptionGetDimensions(format)
            let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
                AVVideoCodecKey: AVVideoCodecType.h264,
                AVVideoWidthKey: Int(dimensions.width), AVVideoHeightKey: Int(dimensions.height),
                AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 1_400_000, AVVideoMaxKeyFrameIntervalKey: 30]
            ])
            input.expectsMediaDataInRealTime = true
            guard writer.canAdd(input) else { throw CountedSetVideoError.writingFailed }
            writer.add(input)
            guard writer.startWriting() else { throw writer.error ?? CountedSetVideoError.writingFailed }
            writer.startSession(atSourceTime: pts)
            let piece = Piece(url: url, start: seconds, writer: writer, input: input)
            pieces.append(piece); current = piece
        }
        guard let current, current.input.isReadyForMoreMediaData else { return }
        guard current.input.append(sample) else { throw current.writer.error ?? CountedSetVideoError.writingFailed }
        let duration = CMSampleBufferGetDuration(sample).seconds
        current.end = seconds + (duration.isFinite && duration > 0 ? duration : 1 / 30)
        if setId == nil {
            let expired = pieces.filter { $0 !== current && $0.end < seconds - preRollSeconds }
            pieces.removeAll { piece in expired.contains { $0 === piece } }
            dispose(expired)
        }
    }
    private func closeCurrent(at end: CMTime? = nil) {
        guard let piece = current else { return }
        current = nil
        guard piece.writer.status == .writing else { piece.done.leave(); return }
        if let end { piece.writer.endSession(atSourceTime: end); piece.end = end.seconds }
        piece.input.markAsFinished()
        piece.writer.finishWriting { piece.done.leave() }
    }
    private func clear() {
        closeCurrent(); let old = pieces; pieces = []
        setId = nil; setStart = nil; failure = nil
        dispose(old)
    }
    private func dispose(_ pieces: [Piece]) {
        Task.detached(priority: .utility) {
            for piece in pieces { await piece.finished(); try? FileManager.default.removeItem(at: piece.url) }
        }
    }
    private static func export(pieces: [Piece], range: CountedSetVideoRange, directory: URL) async throws -> URL {
        let selected = pieces.filter { $0.end > range.start && $0.start < range.end }
        guard let first = selected.first, let last = selected.last,
              first.start <= range.start + 0.1, last.end >= range.end - 0.1 else { throw CountedSetVideoError.unavailable }
        let composition = AVMutableComposition()
        guard let destination = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else { throw CountedSetVideoError.writingFailed }
        for piece in selected {
            guard piece.writer.status == .completed else { throw piece.writer.error ?? CountedSetVideoError.writingFailed }
            let asset = AVURLAsset(url: piece.url)
            guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw CountedSetVideoError.unavailable }
            let trackRange = try await track.load(.timeRange)
            let start = max(range.start, piece.start)
            let end = min(range.end, piece.start + trackRange.end.seconds)
            guard end > start else { continue }
            try destination.insertTimeRange(CMTimeRange(start: CMTime(seconds: start - piece.start, preferredTimescale: 60_000), duration: CMTime(seconds: end - start, preferredTimescale: 60_000)), of: track, at: CMTime(seconds: start - range.start, preferredTimescale: 60_000))
        }
        let url = directory.appendingPathComponent("set-" + UUID().uuidString).appendingPathExtension("mov")
        guard let exporter = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetHighestQuality) else { throw CountedSetVideoError.writingFailed }
        exporter.outputURL = url; exporter.outputFileType = .mov
        exporter.timeRange = CMTimeRange(start: .zero, duration: CMTime(seconds: range.end - range.start, preferredTimescale: 60_000))
        await exporter.export()
        guard exporter.status == .completed else {
            try? FileManager.default.removeItem(at: url)
            throw exporter.error ?? CountedSetVideoError.writingFailed
        }
        return url
    }
}
