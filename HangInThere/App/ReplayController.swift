import Foundation
import Observation

enum ReplayPhase: String {
    case idle = "Choose a video"
    case loading = "Opening video…"
    case paused = "Paused"
    case playing = "Replaying"
    case finished = "Replay complete"
    case failed = "Unable to replay"
}

struct BarSetupFrame: Identifiable, Sendable {
    let id = UUID()
    let frame: ProcessedFrame
    let role: ConfirmedBar.Role
    let generation: UInt64
}

@MainActor @Observable
final class ReplayController {
    private(set) var counter = ExerciseCounter()
    private(set) var bar: ConfirmedBar?
    private(set) var phase: ReplayPhase = .idle
    private(set) var frame: ProcessedFrame?
    private(set) var sourceName: String?
    private(set) var errorMessage: String?
    private(set) var failureReport: String?
    private(set) var displayedFrames = 0
    private(set) var durationSeconds = 0.0
    @ObservationIgnored private var firstSourceTime = 0.0
    @ObservationIgnored private let estimator: any PoseEstimator
    @ObservationIgnored private var reader: VideoReplayReader
    @ObservationIgnored private var operation: Task<Void, Never>?
    @ObservationIgnored private var pending: ProcessedFrame?
    @ObservationIgnored private var session: UInt64 = 0
    @ObservationIgnored private var playback: UInt64 = 0

    init(estimator: any PoseEstimator = VisionPoseEstimator()) {
        self.estimator = estimator
        self.reader = VideoReplayReader(estimator: estimator)
    }

    var barRole: ConfirmedBar.Role {
        counter.exercise == .pullUp ? .pullUpGrip : (counter.side == .left ? .leftDipRail : .rightDipRail)
    }
    var currentBar: ConfirmedBar? {
        guard let frame, let bar, bar.role == barRole, bar.imageSize == frame.pose.imageSize else { return nil }
        return bar
    }
    func beginBarSetup() -> BarSetupFrame? {
        guard phase == .paused || phase == .playing || phase == .finished, let frame else { return nil }
        pause()
        return BarSetupFrame(frame: frame, role: barRole, generation: session)
    }
    @discardableResult
    func confirmBar(_ bar: ConfirmedBar, for setup: BarSetupFrame) -> Bool {
        guard phase == .paused || phase == .finished,
              setup.generation == session, setup.role == barRole, bar.role == barRole,
              bar.isValid, bar.imageSize == frame?.pose.imageSize,
              bar.sourceTime == frame?.pose.timestamp,
              bar.sourceTime == setup.frame.pose.timestamp else { return false }
        self.bar = bar
        // Re-run the set from source time zero so every counted frame uses the
        // same independently confirmed fixed apparatus reference.
        restart(preserveBar: true)
        return true
    }
    func clearBar() {
        bar = nil
        counter.reset()
    }

    // Switching exercise/arm replays from the beginning instead of mixing two
    // policies in one set. Only displayed source frames advance the counter.
    func configureCounting(exercise: ExerciseCounter.Exercise, side: ArmMeasurement.Side) {
        guard phase != .loading, counter.exercise != exercise || counter.side != side else { return }
        pause()
        counter = ExerciseCounter(exercise: exercise, side: side)
        bar = nil
        if canRestart { restart(preserveBar: false) }
    }

    var canPlay: Bool { phase == .paused }
    var canRestart: Bool { frame != nil && phase != .loading }
    var elapsed: Double { max(0, (frame?.pose.timestamp.seconds ?? 0) - firstSourceTime) }
    var progress: Double {
        if phase == .finished { return 1 }
        return durationSeconds > 0 ? min(1, max(0, elapsed / durationSeconds)) : 0
    }

    func open(_ url: URL) {
        let previous = operation
        previous?.cancel()
        session &+= 1
        playback &+= 1
        let token = session
        let oldReader = reader
        let nextReader = VideoReplayReader(estimator: estimator)
        reader = nextReader
        frame = nil
        pending = nil
        displayedFrames = 0
        counter.reset()
        bar = nil
        durationSeconds = 0
        errorMessage = nil
        failureReport = nil
        sourceName = url.lastPathComponent
        phase = .loading
        operation = Task {
            await previous?.value
            await oldReader.close()
            guard session == token, !Task.isCancelled else { return }
            do {
                let info = try await nextReader.open(url)
                let first = try await nextReader.nextFrame()
                guard session == token, !Task.isCancelled else { await nextReader.close(); return }
                try showFirst(first, info: info)
            } catch {
                await nextReader.close()
                if session == token, !Task.isCancelled { fail(error) }
            }
        }
    }

    func close() {
        let previous = operation
        previous?.cancel()
        session &+= 1
        playback &+= 1
        let token = session
        let oldReader = reader
        reader = VideoReplayReader(estimator: estimator)
        frame = nil
        pending = nil
        sourceName = nil
        errorMessage = nil
        failureReport = nil
        displayedFrames = 0
        counter.reset()
        bar = nil
        durationSeconds = 0
        phase = .idle
        operation = Task {
            await previous?.value
            await oldReader.close()
            if session == token { operation = nil }
        }
    }

    func restart(preserveBar: Bool = true) {
        guard canRestart else { return }
        let previous = operation
        previous?.cancel()
        session &+= 1
        playback &+= 1
        let token = session
        let currentReader = reader
        pending = nil
        errorMessage = nil
        failureReport = nil
        phase = .loading
        counter.reset()
        if !preserveBar { bar = nil }
        operation = Task {
            await previous?.value
            guard session == token, !Task.isCancelled else { return }
            do {
                let info = try await currentReader.rewind()
                let first = try await currentReader.nextFrame()
                guard session == token, !Task.isCancelled else { return }
                try showFirst(first, info: info)
            } catch {
                if session == token, !Task.isCancelled { fail(error) }
            }
        }
    }

    private func showFirst(_ first: ProcessedFrame?, info: VideoInfo) throws {
        guard let first else { throw ReplayError.noFrames }
        frame = first
        counter.consume(first.pose, referenceEdge: currentBar?.referenceEdge)
        firstSourceTime = first.pose.timestamp.seconds
        displayedFrames = 1
        durationSeconds = info.durationSeconds
        phase = .paused
        operation = nil
    }

    func pause() {
        guard phase == .playing else { return }
        phase = .paused
        playback &+= 1
        operation?.cancel()
        // Do not discard the task yet: resume waits for it. An inference already
        // in progress must hand its consumed frame to `pending`, not lose it.
    }

    func play() {
        guard canPlay, let frame else { return }
        let previous = operation
        let currentReader = reader
        let token = session
        playback &+= 1
        let playToken = playback
        phase = .playing
        operation = Task {
            await previous?.value
            guard isCurrent(token, playToken) else { return }
            let clock = ContinuousClock()
            var lastShownAt = clock.now
            var lastPTS = frame.pose.timestamp.seconds
            do {
                while isCurrent(token, playToken) {
                    let next: ProcessedFrame?
                    if let pending { next = pending } else { next = try await currentReader.nextFrame() }
                    // Save a consumed result even if pause cancelled this task.
                    // Replacing/restarting the source, however, invalidates it.
                    guard session == token else { return }
                    pending = next
                    guard isCurrent(token, playToken) else { return }
                    guard let next else {
                        counter.finish()
                        phase = .finished
                        operation = nil
                        return
                    }
                    let spent = lastShownAt.duration(to: clock.now).components
                    let wall = Double(spent.seconds) + Double(spent.attoseconds) / 1e18
                    let delay = ReplayPacing.delay(sourceDelta: next.pose.timestamp.seconds - lastPTS,
                                                   wallDelta: wall)
                    if delay > 0 { try await clock.sleep(for: .seconds(delay)) }
                    guard isCurrent(token, playToken) else { return }
                    if let bar, bar.imageSize != next.pose.imageSize {
                        self.bar = nil
                        counter.reset()
                    }
                    self.frame = next
                    counter.consume(next.pose, referenceEdge: currentBar?.referenceEdge)
                    pending = nil
                    displayedFrames += 1
                    lastPTS = next.pose.timestamp.seconds
                    lastShownAt = clock.now
                }
            } catch is CancellationError {
                // A source change, pause, or lifecycle event intentionally stopped replay.
            } catch {
                if isCurrent(token, playToken) { fail(error) }
            }
        }
    }

    func reportImportFailure(_ error: Error) {
        pause()
        errorMessage = error.localizedDescription
        failureReport = makeFailureReport(error, operation: "file selection")
    }

    private func isCurrent(_ token: UInt64, _ playToken: UInt64) -> Bool {
        session == token && playback == playToken && phase == .playing && !Task.isCancelled
    }

    private func fail(_ error: Error) {
        counter.interrupt()
        phase = .failed
        errorMessage = error.localizedDescription
        failureReport = makeFailureReport(error, operation: "replay")
        operation = nil
    }

    // An explicit user share action can export this text. Never include a source
    // URL/name, image, landmarks, error description/userInfo, or device identifier.
    // A retained frame describes the LAST SUCCESS, not the frame that failed.
    private func makeFailureReport(_ error: Error, operation: String) -> String {
        #if targetEnvironment(simulator)
        let environment = "iOS simulator"
        #elseif os(iOS)
        let environment = "physical iOS device"
        #else
        let environment = "native host"
        #endif
        #if arch(arm64)
        let architecture = "arm64"
        #elseif arch(x86_64)
        let architecture = "x86_64"
        #else
        let architecture = "other"
        #endif
        let systemError = error as NSError
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"
        var lines = [
            "HangInThere failure report v1",
            "App: \(version) (\(build))",
            "Environment: \(environment); architecture: \(architecture)",
            "OS: \(ProcessInfo.processInfo.operatingSystemVersionString)",
            "Operation: \(operation)",
            "Estimator type: \(String(reflecting: type(of: estimator)))",
            "Error domain: \(systemError.domain); code: \(systemError.code)",
            "Successfully displayed frames: \(displayedFrames)"
        ]
        if let frame {
            lines.append("Last successful pose: \(frame.pose.backend), revision \(frame.pose.requestRevision)")
            lines.append("Last successful image: \(frame.image.width) x \(frame.image.height)")
            lines.append("Last successful source time: \(frame.pose.timestamp.value)/\(frame.pose.timestamp.timescale)")
        }
        lines.append("No video, filenames, paths, landmarks, or device identifiers included.")
        return lines.joined(separator: "\n")
    }
}