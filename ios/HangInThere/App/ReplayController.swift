import Foundation
import Observation
import WorkoutCore

enum ReplayPhase: String {
    case idle = "Choose a video"
    case loading = "Opening video…"
    case paused = "Paused"
    case playing = "Replaying"
    case finished = "Replay complete"
    case failed = "Unable to replay"
}

// Retained for explicit legacy pipeline qualification. The app presents no
// method setting; new dip replays use the source-owned bilateral processor.
enum ReplayDipMethod { case selectedGripV8, bilateralImageMotion }

struct BarSetupFrame: Identifiable, Sendable {
    let id = UUID()
    let frame: ProcessedFrame
    let role: ConfirmedBar.Role
    let generation: UInt64
}

// Read-only qualification evidence. No images or workout records are retained.
struct ReplaySceneTrace: Sendable {
    typealias Handler = @Sendable (ReplaySceneTrace) -> Void
    let sourceSeconds: Double
    let calibrationSeconds: Double
    let imageSize: ImageSize
    let measurement: StaticSceneRegistrationMeasurement?
    let stateBefore: String
    let stateAfter: String
    let movementKind: String?
}

@MainActor @Observable
final class ReplayController {
    private(set) var counter = ExerciseCounter()
    private let workoutAnalysis = WorkoutAnalysisAdapter()
    private(set) var analysisRecord: WorkoutSetRecord?
    var repAnalysis: [WorkoutRepRecord] { usesBilateralDip ? dipReps : workoutAnalysis.reps }
    var usesBilateralDip: Bool { counter.exercise == .dip && dipMethod == .bilateralImageMotion }
    var observedMovements: Int { usesBilateralDip ? dipSnapshot?.motion.imageCycles ?? 0 : counter.observedMovements }
    var hasCountingReference: Bool { usesBilateralDip ? dipSupport != nil : currentBar != nil }
    // Historical source-clock qualification is distinct from current setup:
    // moving the camera later cannot erase previously observed valid cycles.
    var hasObservedReference: Bool { usesBilateralDip ? dipReferenceSeen : currentBar != nil }
    var partialAttempts: Int { usesBilateralDip ? dipSnapshot?.motion.partialAttempts ?? 0 : counter.partialAttempts }
    var interruptedAttempts: Int { usesBilateralDip ? dipSnapshot?.motion.interruptedAttempts ?? 0 : counter.interruptedAttempts }
    var movementPhaseTitle: String {
        guard usesBilateralDip else { return counter.phase.title }
        switch dipSnapshot?.motion.phase {
        case "ready": return "Ready to lower"
        case "outbound": return "Lowering"
        case "returning": return "Returning to support"
        default: return "Find your top support"
        }
    }
    private(set) var dipSnapshot: DipWorkoutSnapshot?
    private(set) var dipSupport: DipRailReference?
    private(set) var dipReps: [WorkoutRepRecord] = []
    private(set) var dipMovementTimes: [Double] = []
    private var dipReferenceSeen = false
    private(set) var bar: ConfirmedBar?
    private(set) var automaticBarState: AutomaticPullUpBar.State = .lookingForGrip
    private(set) var phase: ReplayPhase = .idle
    private(set) var frame: ProcessedFrame?
    private(set) var sourceName: String?
    private(set) var errorMessage: String?
    private(set) var failureReport: String?
    private(set) var displayedFrames = 0
    private(set) var durationSeconds = 0.0
    private(set) var countingFrames = 0
    private(set) var analyzableFrames = 0
    private(set) var cameraTrackingIssue: String?
    @ObservationIgnored private var firstSourceTime = 0.0
    @ObservationIgnored private let estimator: any PoseEstimator
    @ObservationIgnored private let dipMethod: ReplayDipMethod
    @ObservationIgnored private let trace: ReplayTrace.Handler?
    @ObservationIgnored private let gripObserverFactory: (@Sendable (ExerciseCounter.Exercise) -> any GripObservationScheduling)?
    @ObservationIgnored private var reader: VideoReplayReader
    @ObservationIgnored private var operation: Task<Void, Never>?
    @ObservationIgnored private var pending: ProcessedFrame?
    @ObservationIgnored private var frameDiscardSignal = ReplayFrameDiscardSignal()
    @ObservationIgnored private var session: UInt64 = 0 {
        didSet { resetAutomaticBarSetup() }
    }
    @ObservationIgnored private var playback: UInt64 = 0
    @ObservationIgnored private let barDetector: any BarEdgeDetector
    @ObservationIgnored private var automaticBarPolicy = AutomaticApparatusReference()
    @ObservationIgnored private var lastAutomaticBarSeconds: Double?
    @ObservationIgnored private let sceneWorker: any StaticSceneRegistering
    @ObservationIgnored private let sceneTrace: ReplaySceneTrace.Handler?
    @ObservationIgnored private let scene = WorkoutSceneBridge()
    @ObservationIgnored private var sceneReference: StaticSceneRegistrationReference?
    @ObservationIgnored private var lastSceneSeconds: Double?
    @ObservationIgnored private var sceneGeneration: UInt64 = 0
    @ObservationIgnored private var sceneMovementReason: String?
    @ObservationIgnored private let dipProcessorFactory: @Sendable () -> any DipWorkoutProcessing
    @ObservationIgnored private var dipProcessor: any DipWorkoutProcessing

    init(dipMethod: ReplayDipMethod = .bilateralImageMotion,
         estimator: any PoseEstimator = VisionPoseEstimator(), barDetector: any BarEdgeDetector = VisionBarDetector(),
         trace: ReplayTrace.Handler? = nil, gripObserverFactory: (@Sendable (ExerciseCounter.Exercise) -> any GripObservationScheduling)? = nil,
         sceneWorker: any StaticSceneRegistering = VisionStaticSceneRegistrationWorker(),
         sceneTrace: ReplaySceneTrace.Handler? = nil,
         dipProcessorFactory: @escaping @Sendable () -> any DipWorkoutProcessing = { VisionDipWorkout() }) {
        self.estimator = estimator
        self.dipMethod = dipMethod
        self.trace = trace
        self.gripObserverFactory = gripObserverFactory
        self.barDetector = barDetector
        self.sceneWorker = sceneWorker
        self.sceneTrace = sceneTrace
        self.dipProcessorFactory = dipProcessorFactory
        self.dipProcessor = dipProcessorFactory()
        self.reader = VideoReplayReader(estimator: estimator, trace: trace, gripObserver: gripObserverFactory?(.pullUp))
    }

    var barRole: ConfirmedBar.Role {
        counter.exercise == .pullUp ? .pullUpGrip : (counter.side == .left ? .leftDipRail : .rightDipRail)
    }
    var currentBar: ConfirmedBar? {
        guard let frame, let bar, bar.role == barRole, bar.imageSize == frame.pose.imageSize else { return nil }
        return bar
    }
    func beginBarSetup() -> BarSetupFrame? {
        guard !usesBilateralDip else { return nil }
        guard phase == .paused || phase == .playing || phase == .finished, let frame else { return nil }
        pause()
        return BarSetupFrame(frame: frame, role: barRole, generation: session)
    }
    @discardableResult
    func confirmBar(_ bar: ConfirmedBar, for setup: BarSetupFrame) -> Bool {
        guard !usesBilateralDip, phase == .paused || phase == .finished,
              setup.generation == session, setup.role == barRole, bar.role == barRole,
              bar.isValid, bar.imageSize == frame?.pose.imageSize,
              bar.sourceTime == frame?.pose.timestamp,
              bar.sourceTime == setup.frame.pose.timestamp else { return false }
        self.bar = bar
        sceneReference = VisionStaticSceneRegistrationWorker.makeReference(image: setup.frame.image)
        // Re-run the set from source time zero so every counted frame uses the
        // same independently confirmed fixed apparatus reference.
        restart(preserveBar: true)
        return true
    }
    func clearBar() {
        pause()
        resetAutomaticBarSetup()
        bar = nil
        resetSceneQualification(preserveReference: false)
        counter.reset()
        resetAnalysisMetrics()
    }

    // Switching exercise/arm replays from the beginning instead of mixing two
    // policies in one set. Only displayed source frames advance the counter.
    func configureCounting(exercise: ExerciseCounter.Exercise, side: ArmMeasurement.Side) {
        guard phase != .loading, counter.exercise != exercise || counter.side != side else { return }
        pause()
        counter = ExerciseCounter(exercise: exercise, side: side)
        bar = nil
        resetSceneQualification(preserveReference: false)
        resetAnalysisMetrics()
        if canRestart { restart(preserveBar: false) }
    }

    var canPlay: Bool { phase == .paused }
    var canRestart: Bool { frame != nil && phase != .loading }
    var elapsed: Double { max(0, (frame?.pose.timestamp.seconds ?? 0) - firstSourceTime) }
    var progress: Double {
        if phase == .finished { return 1 }
        return durationSeconds > 0 ? min(1, max(0, elapsed / durationSeconds)) : 0
    }
    var trackingCoverage: Double? {
        guard countingFrames > 0 else { return nil }
        return Double(analyzableFrames) / Double(countingFrames)
    }
    var movementTimes: [Double] {
        if usesBilateralDip { return dipMovementTimes.map { max(0, $0 - firstSourceTime) } }
        return counter.events.compactMap { event in
            guard event.outcome == .movement else { return nil }
            return max(0, event.sourceSeconds - firstSourceTime)
        }
    }

    func open(_ url: URL) {
        let previous = operation
        previous?.cancel()
        let discard = beginFrameSession()
        session &+= 1
        playback &+= 1
        let token = session
        let oldReader = reader
        let nextReader = VideoReplayReader(estimator: estimator, trace: trace, gripObserver: gripObserverFactory?(counter.exercise))
        reader = nextReader
        frame = nil
        pending = nil
        displayedFrames = 0
        counter.reset()
        bar = nil
        resetSceneQualification(preserveReference: false)
        durationSeconds = 0
        resetAnalysisMetrics()
        let currentDip = dipProcessor
        let isDip = usesBilateralDip
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
                let first = try await nextReader.nextFrame(discard: discard)
                guard session == token, !Task.isCancelled else { await nextReader.close(); return }
                let dipResult = if isDip, let first { try await currentDip.process(first) } else { nil as DipWorkoutFrameResult? }
                guard session == token, !Task.isCancelled else { await nextReader.close(); return }
                try showFirst(first, info: info, dipResult: dipResult)
            } catch {
                await nextReader.close()
                if session == token, !Task.isCancelled { await fail(error, token: token) }
            }
        }
    }

    func close() {
        let previous = operation
        previous?.cancel()
        frameDiscardSignal.discard()
        session &+= 1
        playback &+= 1
        let token = session
        let oldReader = reader
        reader = VideoReplayReader(estimator: estimator, trace: trace, gripObserver: gripObserverFactory?(counter.exercise))
        frame = nil
        pending = nil
        sourceName = nil
        errorMessage = nil
        failureReport = nil
        displayedFrames = 0
        counter.reset()
        bar = nil
        resetSceneQualification(preserveReference: false)
        durationSeconds = 0
        resetAnalysisMetrics()
        phase = .idle
        operation = Task {
            await previous?.value
            await oldReader.close()
            if session == token { operation = nil }
        }
    }

    func restart(preserveBar: Bool = true, resumePlayback: Bool = false) {
        guard canRestart else { return }
        let previous = operation
        previous?.cancel()
        let discard = beginFrameSession()
        session &+= 1
        playback &+= 1
        let token = session
        let currentReader = reader
        let gripEnabled = counter.exercise == .pullUp
        pending = nil
        errorMessage = nil
        failureReport = nil
        phase = .loading
        counter.reset()
        resetAnalysisMetrics()
        if !preserveBar { bar = nil }
        resetSceneQualification(preserveReference: preserveBar && bar != nil)
        let currentDip = dipProcessor
        let isDip = usesBilateralDip
        operation = Task {
            await waitForPreviousOperation(previous)
            guard session == token, !Task.isCancelled else { return }
            do {
                await currentReader.setGripInferenceEnabled(gripEnabled)
                let info = try await currentReader.rewind()
                let first = try await currentReader.nextFrame(discard: discard)
                guard session == token, !Task.isCancelled else { return }
                if !isDip, let first {
                    guard await qualifyScene(first, token: token) else { return }
                }
                let dipResult = if isDip, let first { try await currentDip.process(first) } else { nil as DipWorkoutFrameResult? }
                guard session == token, !Task.isCancelled else { return }
                try showFirst(first, info: info, dipResult: dipResult)
                if resumePlayback { play() }
            } catch {
                if session == token, !Task.isCancelled { await fail(error, token: token) }
            }
        }
    }

    // This wait runs on the presentation actor. Trace it separately from
    // reader work so an in-flight detector cannot look like a decoder stall.
    private func waitForPreviousOperation(_ previous: Task<Void, Never>?) async {
        guard let trace else { await previous?.value; return }
        let started = ContinuousClock.now
        trace(.init(stage: .operationWait, boundary: .began, sourceSeconds: nil, milliseconds: nil))
        defer {
            trace(.init(stage: .operationWait, boundary: .ended, sourceSeconds: nil,
                        milliseconds: ReplayTrace.milliseconds(since: started)))
        }
        await previous?.value
    }

    private func beginFrameSession() -> ReplayFrameDiscardSignal {
        resetAutomaticBarSetup(newSourceEpoch: true)
        frameDiscardSignal.discard()
        frameDiscardSignal = ReplayFrameDiscardSignal()
        return frameDiscardSignal
    }

    private func showFirst(_ first: ProcessedFrame?, info: VideoInfo, dipResult: DipWorkoutFrameResult? = nil) throws {
        guard let first else { throw ReplayError.noFrames }
        frame = first
        firstSourceTime = first.pose.timestamp.seconds
        if let dipResult { publishDip(dipResult) } else { consumeForCounting(first.pose) }
        displayedFrames = 1
        durationSeconds = info.durationSeconds
        phase = .paused
        operation = nil
    }

    func pause() {
        resetAutomaticBarSetup()
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
        let discard = frameDiscardSignal
        let token = session
        let currentDip = dipProcessor
        let isDip = usesBilateralDip
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
                    if let pending { next = pending } else { next = try await currentReader.nextFrame(discard: discard) }
                    // Save a consumed result even if pause cancelled this task.
                    // Replacing/restarting the source, however, invalidates it.
                    guard session == token else { return }
                    pending = next
                    guard isCurrent(token, playToken) else { return }
                    guard let next else {
                        if isDip {
                            let record = try await currentDip.finish(reason: "endOfInput")
                            guard isCurrent(token, playToken) else { return }
                            analysisRecord = record
                            dipReps = record.reps
                        } else {
                            counter.finish()
                            analysisRecord = workoutAnalysis.finish(reason: sceneMovementReason ?? "endOfInput")
                        }
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
                    if isDip {
                        let result = try await currentDip.process(next)
                        guard isCurrent(token, playToken) else { return }
                        publishDip(result)
                        pending = nil
                        displayedFrames += 1
                        lastPTS = next.pose.timestamp.seconds
                        lastShownAt = clock.now
                        continue
                    }
                    if let bar, bar.imageSize != next.pose.imageSize {
                        self.bar = nil
                        resetSceneQualification(preserveReference: false)
                        counter.reset()
                        resetAnalysisMetrics()
                    }
                    self.frame = next
                    if await findAutomaticBar(next, token: token, playToken: playToken) { return }
                    guard isCurrent(token, playToken) else { return }
                    guard await qualifyScene(next, token: token, playToken: playToken),
                          isCurrent(token, playToken) else { return }
                    consumeForCounting(next.pose)
                    pending = nil
                    displayedFrames += 1
                    lastPTS = next.pose.timestamp.seconds
                    lastShownAt = clock.now
                }
            } catch is CancellationError {
                // A source change, pause, or lifecycle event intentionally stopped replay.
            } catch {
                if isCurrent(token, playToken) { await fail(error, token: token) }
            }
        }
    }

    private func publishDip(_ result: DipWorkoutFrameResult) {
        let started = ContinuousClock.now
        trace?(.init(stage: .counting, boundary: .began, sourceSeconds: result.frame.pose.timestamp.seconds, milliseconds: nil))
        defer { trace?(.init(stage: .counting, boundary: .ended, sourceSeconds: result.frame.pose.timestamp.seconds,
            milliseconds: ReplayTrace.milliseconds(since: started))) }
        frame = result.frame
        dipSnapshot = result.snapshot
        dipSupport = result.support
        if result.support != nil { dipReferenceSeen = true }
        countingFrames = result.snapshot.analyzedFrames
        analyzableFrames = result.snapshot.usableFrames
        cameraTrackingIssue = result.camera == "moved" ? "cameraMoved" :
            (result.support != nil && result.camera != "ready" ? "cameraSceneUnavailable" : nil)
        if let rep = result.closedRep, dipReps.last?.id != rep.id { dipReps.append(rep) }
        if let event = result.snapshot.motion.event, event.outcome == "imageCycle" {
            dipMovementTimes.append(event.sourceTime.seconds)
        }
    }

    private func consumeForCounting(_ pose: PoseResult) {
        let started = ContinuousClock.now
        trace?(.init(stage: .counting, boundary: .began, sourceSeconds: pose.timestamp.seconds, milliseconds: nil))
        defer {
            trace?(.init(stage: .counting, boundary: .ended, sourceSeconds: pose.timestamp.seconds,
                         milliseconds: ReplayTrace.milliseconds(since: started)))
        }
        // Displayed apparatus geometry is distinct from a currently qualified
        // counting reference. The counter and analytics receive the same edge.
        let hasReference = currentBar != nil
        let referenceEdge = scene.isReady(seconds: pose.timestamp.seconds) ? currentBar?.referenceEdge : nil
        cameraTrackingIssue = hasReference && referenceEdge == nil
            ? sceneMovementReason ?? "cameraSceneUnavailable" : nil
        if hasReference { countingFrames += 1 }
        let eventCount = counter.events.count
        counter.consume(pose, referenceEdge: referenceEdge)
        _ = workoutAnalysis.consume(pose, reference: referenceEdge, counter: counter,
            event: counter.events.count > eventCount ? counter.events.last : nil, automaticFinish: false)
        if referenceEdge != nil, counter.trackingIssue == nil { analyzableFrames += 1 }
    }

    private func resetAutomaticBarSetup(newSourceEpoch: Bool = false) {
        if newSourceEpoch { automaticBarPolicy.reset() }
        else { automaticBarPolicy.invalidateEvidence() }
        automaticBarState = .lookingForGrip
        lastAutomaticBarSeconds = nil
    }

    private func findAutomaticBar(_ frame: ProcessedFrame, token: UInt64, playToken: UInt64) async -> Bool {
        guard bar == nil else { return false }
        let seconds = frame.pose.timestamp.seconds
        guard seconds.isFinite else {
            automaticBarPolicy.invalidateEvidence()
            automaticBarState = .lookingForGrip
            return false
        }
        if let lastAutomaticBarSeconds, seconds - lastAutomaticBarSeconds < AutomaticPullUpBar.samplingInterval { return false }
        lastAutomaticBarSeconds = seconds
        guard let grip = AutomaticApparatusReference.search(pose: frame.pose, exercise: counter.exercise) else {
            automaticBarPolicy.invalidateEvidence()
            automaticBarState = .lookingForGrip
            return false
        }
        var candidates: [[BarLineCandidate]] = []
        do {
            for region in grip.regions {
                candidates.append(try await barDetector.detect(image: frame.image, region: region,
                    minimumObservedLength: grip.minimumObservedLength))
                guard isCurrent(token, playToken) else { return false }
            }
        }
        catch {
            guard isCurrent(token, playToken) else { return false }
            automaticBarPolicy.invalidateEvidence()
            automaticBarState = .findingEdge
            return false
        }
        guard isCurrent(token, playToken) else { return false }
        let proposal = automaticBarPolicy.observe(pose: frame.pose, exercise: counter.exercise, side: counter.side, candidates: candidates)
        automaticBarState = automaticBarPolicy.state
        guard let proposal else { return false }
        bar = proposal
        sceneReference = VisionStaticSceneRegistrationWorker.makeReference(image: frame.image)
        // Rewind once, then continue automatically so movements preceding bar
        // acquisition are evaluated with the same fixed reference too.
        restart(preserveBar: true, resumePlayback: true)
        return true
    }

    private func resetSceneQualification(preserveReference: Bool) {
        sceneGeneration &+= 1
        if !preserveReference { sceneReference = nil }
        lastSceneSeconds = nil
        sceneMovementReason = nil
        scene.reset()
        if let reference = sceneReference {
            _ = scene.calibrate(width: reference.imageSize.width, height: reference.imageSize.height)
        }
        cameraTrackingIssue = bar == nil ? nil : "cameraSceneUnavailable"
    }

    // Registration compares actual current pixels with the exact bar-source
    // image. Rewind resets evidence clocks, never the calibration pixels.
    private func qualifyScene(_ frame: ProcessedFrame, token: UInt64, playToken: UInt64? = nil) async -> Bool {
        guard let calibrationBar = bar, sceneMovementReason == nil, let reference = sceneReference else { return true }
        let seconds = frame.pose.timestamp.seconds
        if let lastSceneSeconds, seconds.isFinite,
           seconds - lastSceneSeconds >= 0,
           seconds - lastSceneSeconds < AutomaticPullUpBar.samplingInterval { return true }
        let generation = sceneGeneration
        let measurement = try? await sceneWorker.measure(reference: reference, image: frame.image)
        guard session == token, !Task.isCancelled,
              playToken.map({ isCurrent(token, $0) }) ?? true else { return false }
        // Clearing setup while the first rewound registration is in flight
        // revokes that evidence, but the decoded frame still opens normally.
        // A canceled playback/source replacement must instead retain/discard
        // its pending frame through the owning operation's existing guards.
        guard sceneGeneration == generation else { return bar == nil && sceneReference == nil }
        lastSceneSeconds = seconds
        let patches = measurement?.translations ?? []
        let values = KotlinDoubleArray(size: Int32(patches.count * 2))
        for (index, patch) in patches.enumerated() {
            values.set(index: Int32(index * 2), value: patch.dxPixels)
            values.set(index: Int32(index * 2 + 1), value: patch.dyPixels)
        }
        let stateBefore = scene.state
        _ = scene.observe(seconds: seconds, translations: values,
            scaleFraction: measurement?.globalScaleFraction ?? .nan)
        sceneTrace?(.init(sourceSeconds: seconds, calibrationSeconds: calibrationBar.sourceTime.seconds,
            imageSize: reference.imageSize, measurement: measurement, stateBefore: stateBefore,
            stateAfter: scene.state, movementKind: scene.movementKind))
        if scene.state == "moved", sceneMovementReason == nil {
            let reason = scene.movementKind == "scale" ? "sceneScaled" : "sceneShifted"
            sceneMovementReason = reason
            _ = counter.interrupt(reason: reason)
            workoutAnalysis.interrupt(reason: reason)
        }
        return true
    }

    private func resetAnalysisMetrics() {
        workoutAnalysis.start(exercise: counter.exercise, recordCalendarTime: false)
        analysisRecord = nil
        countingFrames = 0
        analyzableFrames = 0
        dipProcessor = dipProcessorFactory()
        dipSnapshot = nil; dipSupport = nil; dipReps = []; dipMovementTimes = []; dipReferenceSeen = false
    }

    func reportImportFailure(_ error: Error) {
        pause()
        errorMessage = error.localizedDescription
        failureReport = makeFailureReport(error, operation: "file selection")
    }

    private func isCurrent(_ token: UInt64, _ playToken: UInt64) -> Bool {
        session == token && playback == playToken && phase == .playing && !Task.isCancelled
    }

    private func fail(_ error: Error, token: UInt64) async {
        if usesBilateralDip {
            let record = try? await dipProcessor.finish(reason: "analysisFailure")
            guard session == token, !Task.isCancelled else { return }
            analysisRecord = record
            dipReps = record?.reps ?? []
        } else {
            counter.interrupt()
            workoutAnalysis.interrupt(reason: "analysisFailure")
            analysisRecord = workoutAnalysis.finish(reason: "analysisFailure")
        }
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
