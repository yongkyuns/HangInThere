import Foundation
import Observation

@MainActor @Observable
final class ReplayModel {
    enum Phase { case empty, loading, ready, playing, paused, finished, failed }

    private(set) var phase: Phase = .empty
    private(set) var frame: AnalyzedFrame?
    private(set) var errorMessage: String?
    private(set) var filename: String?
    private var source: URL?
    private var session: VideoReplay?
    private var task: Task<Void, Never>?
    private var generation = UUID()

    var canPlay: Bool { phase == .ready || phase == .paused }
    var canRestart: Bool { source != nil && phase != .loading }

    func load(_ url: URL) {
        task?.cancel()
        let previous = session
        Task { await previous?.close() }
        generation = UUID()
        let token = generation
        let session = VideoReplay()
        self.session = session
        source = url
        filename = url.lastPathComponent
        frame = nil
        errorMessage = nil
        phase = .loading
        task = Task {
            defer { if generation == token { task = nil } }
            do {
                try await session.open(url)
                guard let first = try await session.next() else { throw ReplayError.emptyVideo }
                guard generation == token, !Task.isCancelled else { await session.close(); return }
                frame = first
                phase = .ready
            } catch {
                await session.close()
                guard generation == token else { return }
                if !(error is CancellationError) { fail(error) }
            }
        }
    }

    func play() {
        guard canPlay, let session else { return }
        phase = .playing
        // A quick pause/resume reuses the existing loop; it never creates a second
        // reader consumer or an unbounded task queue.
        guard task == nil else { return }
        let token = generation
        task = Task {
            defer { if generation == token { task = nil } }
            do {
                while phase == .playing, generation == token {
                    let started = ContinuousClock.now
                    let lastTime = frame?.pose.timestamp
                    guard let next = try await session.next() else {
                        guard generation == token else { return }
                        phase = .finished
                        return
                    }
                    guard generation == token, !Task.isCancelled else { return }
                    // Analysis-paced replay: never skip frames to catch up. Pause
                    // may finish one in-flight frame, but never consumes it unseen.
                    if let lastTime, phase == .playing {
                        let dt = max(0, next.pose.timestamp - lastTime)
                        let deadline = started.advanced(by: .seconds(dt))
                        while ContinuousClock.now < deadline, phase == .playing {
                            try await Task.sleep(for: .milliseconds(20))
                        }
                    }
                    guard generation == token, !Task.isCancelled else { return }
                    frame = next
                }
            } catch {
                guard generation == token else { return }
                if !(error is CancellationError) { fail(error) }
            }
        }
    }

    func pause() { if phase == .playing { phase = .paused } }
    func restart() { if let source { load(source) } }

    func importFailed(_ error: Error) {
        // Cancelling the system picker leaves the current replay untouched.
        if (error as NSError).code != NSUserCancelledError { errorMessage = error.localizedDescription }
    }

    func shutdown() {
        task?.cancel()
        task = nil
        generation = UUID()
        let previous = session
        session = nil
        Task { await previous?.close() }
        frame = nil
        phase = .empty
    }

    private func fail(_ error: Error) {
        phase = .failed
        frame = nil
        errorMessage = error.localizedDescription
    }
}
