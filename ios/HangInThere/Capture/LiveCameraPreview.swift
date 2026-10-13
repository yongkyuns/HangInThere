#if os(iOS)
import AVFoundation
import CoreImage
import CoreMotion
import Observation
import SwiftUI
import UIKit

enum LiveDebugCaptureState: Equatable, Sendable {
    case unavailable
    case idle
    case starting
    case recording
    case stopping
    case ready
    case failed(String)

    var isActive: Bool {
        switch self {
        case .starting, .recording, .stopping: true
        default: false
        }
    }
}

enum LiveCameraPosition: String, CaseIterable, Sendable {
    case rear
    case front

    var title: String { rawValue.capitalized }
    var capturePosition: AVCaptureDevice.Position { self == .rear ? .back : .front }
}

// Keep preview, Vision pixels, frozen bar setup and debug movies in the same
// portrait, unmirrored coordinate system, including on the front camera.
enum LiveCameraConnectionConfiguration {
    static func apply(to connection: AVCaptureConnection) throws {
        guard connection.isVideoRotationAngleSupported(90) else {
            throw LiveCameraSetupError.unsupportedPortraitRotation
        }
        connection.videoRotationAngle = 90
        connection.automaticallyAdjustsVideoMirroring = false
        if connection.isVideoMirroringSupported {
            connection.isVideoMirrored = false
        }
        if connection.isVideoStabilizationSupported {
            connection.preferredVideoStabilizationMode = .off
        }
    }
}

enum LiveCameraState: Equatable, Sendable {
    case idle
    case requestingPermission
    case starting
    case ready
    case denied
    case unavailable
    case interrupted(String)
    case failed(String)

    var title: String {
        switch self {
        case .idle: "Camera not started"
        case .requestingPermission: "Waiting for camera access"
        case .starting: "Starting camera"
        case .ready: "Camera ready"
        case .denied: "Camera access is off"
        case .unavailable: "Selected camera unavailable"
        case .interrupted: "Camera interrupted"
        case .failed: "Camera unavailable"
        }
    }

    var detail: String {
        switch self {
        case .idle:
            "The selected camera starts only while this setup screen is open."
        case .requestingPermission:
            "Allow camera access to position the phone for a live workout."
        case .starting:
            "Preparing the selected camera preview."
        case .ready:
            "Keep the phone fixed once the athlete and apparatus are framed."
        case .denied:
            "Enable camera access in Settings. Recorded-video review remains available."
        case .unavailable:
            "Choose the other camera to continue live setup."
        case .interrupted(let message):
            message
        case .failed(let message):
            message
        }
    }
}

private enum LiveCameraSetupError: LocalizedError {
    case noCamera
    case cannotAddInput
    case cannotAddVideoOutput
    case unsupportedPortraitRotation

    var errorDescription: String? {
        switch self {
        case .noCamera:
            "The selected camera is not available."
        case .cannotAddInput:
            "The selected camera could not be added to the capture session."
        case .cannotAddVideoOutput:
            "Live camera frames could not be connected for analysis."
        case .unsupportedPortraitRotation:
            "This camera cannot provide the portrait frame orientation required by live setup."
        }
    }
}

private struct PhoneMotionSample: Sendable {
    let attitude: PhoneOrientationStability.Quaternion
    let timestamp: Double
}

private enum LiveAnalyzerEvent: Sendable {
    case frame(ProcessedFrame)
    case dropped
    case failed
}

// AVCaptureVideoDataOutput invokes this object only on the serial analysis queue.
// Inference is synchronous on that queue and alwaysDiscardsLateVideoFrames is
// enabled, so the app never creates an unbounded Task/frame backlog.
private final class LiveFrameAnalyzer: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let estimator: any PoseEstimator
    private let gripInference = GripInferenceScheduler()
    private let publish: @Sendable (LiveAnalyzerEvent) -> Void

    init(
        estimator: any PoseEstimator = VisionPoseEstimator(),
        gripEnabled: Bool = true,
        publish: @escaping @Sendable (LiveAnalyzerEvent) -> Void
    ) {
        self.estimator = estimator
        gripInference.setEnabled(gripEnabled)
        self.publish = publish
    }

    func setGripEnabled(_ enabled: Bool) { gripInference.setEnabled(enabled) }
    func resetGripInference() { gripInference.reset() }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        autoreleasepool {
            let started = ContinuousClock.now
            let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
            guard pts.isNumeric, pts.epoch == 0,
                  let buffer = CMSampleBufferGetImageBuffer(sampleBuffer)
            else {
                publish(.failed)
                return
            }

            let pixels = CIImage(cvPixelBuffer: buffer)
            guard let image = context.createCGImage(pixels, from: pixels.extent) else {
                publish(.failed)
                return
            }

            do {
                var pose = try estimator.estimate(
                    image: image,
                    timestamp: PresentationTime(value: pts.value, timescale: pts.timescale)
                )
                pose.gripObservation = gripInference.observe(image: image, pose: pose)
                let duration = started.duration(to: .now).components
                let milliseconds = Double(duration.seconds) * 1_000
                    + Double(duration.attoseconds) / 1e15
                publish(.frame(ProcessedFrame(
                    image: image,
                    pose: pose,
                    processingMilliseconds: milliseconds
                )))
            } catch {
                publish(.failed)
            }
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didDrop sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        publish(.dropped)
    }
}

@MainActor
@Observable
final class LiveCameraPreviewController: NSObject {
    private(set) var state: LiveCameraState = .idle
    private(set) var cameraPosition: LiveCameraPosition = .rear
    private(set) var isSwitchingCamera = false
    private(set) var framing = LiveFramingAssessment()
    private(set) var analyzedFrames = 0
    private(set) var droppedFrames = 0
    private(set) var analysisFailures = 0
    private(set) var lastProcessingMilliseconds: Double?
    private(set) var lastSceneRegistrationMilliseconds: Double?
    private(set) var sceneRegistrationFailures = 0
    private(set) var bar: ConfirmedBar?
    private(set) var automaticBarState: AutomaticPullUpBar.State = .lookingForGrip
    private(set) var exerciseSelection: WorkoutExerciseSelection = .automatic
    private var automaticExercise = AutomaticExerciseDetection()
    private(set) var handsFreeStartEnabled = true
    private(set) var handsFreeStart = HandsFreeSetStart()
    private(set) var liveSet = LiveSetSession()
    private let workoutAnalysis = WorkoutAnalysisAdapter()
    private(set) var latestCompletedWorkout: WorkoutSetRecord?
    private(set) var workoutHistory: [WorkoutSetRecord] = []
    private(set) var workoutStorageError: String?
    var repAnalysis: [WorkoutRepRecord] { workoutAnalysis.reps }
    var gripLabel: String? { liveSet.phase == .running ? workoutAnalysis.gripLabel : nil }
    var activeWorkoutRecord: WorkoutSetRecord? { liveSet.phase == .running ? workoutAnalysis.currentRecord : nil }
    func workoutRecordForReview() -> WorkoutSetRecord? { liveSet.phase == .running ? workoutAnalysis.reviewRecord() : nil }
    var showsSetStarted: Bool { workoutAnalysis.showsSetStarted }
    private var archivedSetId: String?
    @ObservationIgnored private var workoutWriteTask: Task<Void, Never>?
    private var qualifiedStartPose: PoseResult?
    private var lastAnalyzedFrameUptime: Double?
    private(set) var phoneOrientation = PhoneOrientationStability()
    private(set) var motionSampleAvailable = false
    private(set) var sceneTranslation = StaticSceneStability()
    private(set) var qualification = LiveDeviceQualificationRecorder()
    private(set) var debugCaptureState: LiveDebugCaptureState = .unavailable

    let session = AVCaptureSession()

    @ObservationIgnored private let sessionQueue = DispatchQueue(
        label: "dev.yongkyuns.HangInThere.camera-session",
        qos: .userInitiated
    )
    @ObservationIgnored private let analysisQueue = DispatchQueue(
        label: "dev.yongkyuns.HangInThere.camera-analysis",
        qos: .userInitiated
    )
    @ObservationIgnored private let motionManager = CMMotionManager()
    @ObservationIgnored private let sceneRegistrationWorker = VisionStaticSceneRegistrationWorker()
    @ObservationIgnored private var configured = false
    @ObservationIgnored private var analysisGeneration: UInt64 = 0
    @ObservationIgnored private var startRequested = false
    @ObservationIgnored private var videoOutput: AVCaptureVideoDataOutput?
    @ObservationIgnored private var movieOutput: AVCaptureMovieFileOutput?
    @ObservationIgnored private var analyzer: LiveFrameAnalyzer?
    @ObservationIgnored private var analysisEventTask: Task<Void, Never>?
    @ObservationIgnored private var captureWatchdogTask: Task<Void, Never>?
    @ObservationIgnored private var exercise: ExerciseCounter.Exercise = .pullUp
    @ObservationIgnored private var trackingSide: ArmMeasurement.Side = .left
    // Views must observe accepted frames even when framing stays "ready";
    // otherwise the live skeleton can freeze while the camera keeps streaming.
    private var latestFrame: ProcessedFrame?
    @ObservationIgnored private var setupGeneration: UInt64 = 0 {
        didSet { resetAutomaticBarSetup() }
    }
    @ObservationIgnored private let automaticBarDetector = VisionBarDetector()
    @ObservationIgnored private var automaticBarPolicy = AutomaticApparatusReference()
    @ObservationIgnored private var automaticBarTask: Task<Void, Never>?
    @ObservationIgnored private var lastAutomaticBarSeconds: Double?
    @ObservationIgnored private let countdownSpeech = AVSpeechSynthesizer()
    @ObservationIgnored private var lastHandsFreeFrameUptime: Double?
    private(set) var workoutDetailsPresented = false
    @ObservationIgnored private var discardNextSetFrame = false
    @ObservationIgnored private var suspended = false
    @ObservationIgnored private var pendingBarMotionSample: (id: UUID, sample: PhoneMotionSample)?
    @ObservationIgnored private var pendingBarSceneReference: (id: UUID, reference: StaticSceneRegistrationReference)?
    @ObservationIgnored private var sceneReference: StaticSceneRegistrationReference?
    @ObservationIgnored private var sceneRegistrationTask: Task<Void, Never>?
    @ObservationIgnored private var lastSceneRegistrationSeconds: Double?
    @ObservationIgnored private var debugCaptureURL: URL?
    @ObservationIgnored private var debugCaptureBarSnapshot: ConfirmedBar?
    @ObservationIgnored private var debugCaptureStartedUptimeSeconds: Double?
    @ObservationIgnored private var debugCaptureFinishedUptimeSeconds: Double?
    @ObservationIgnored private var debugCaptureFirstAnalyzedSourceSeconds: Double?
    @ObservationIgnored private var debugCaptureLastAnalyzedSourceSeconds: Double?
    @ObservationIgnored private var debugCaptureSessionJSON: String?
    @ObservationIgnored private var debugCaptureQualificationJSON: String?
    @ObservationIgnored private var discardDebugCaptureWhenFinished = false

    var isCameraReady: Bool { state == .ready }
    var resolvedExercise: ExerciseCounter.Exercise? {
        exerciseSelection.exercise ?? automaticExercise.exercise
    }
    var workoutTitle: String {
        guard let resolvedExercise else { return "Auto · detecting" }
        let title = resolvedExercise == .pullUp ? "Pull-up" : "Dips"
        return exerciseSelection == .automatic && liveSet.phase == .idle ? "\(title) detected" : title
    }
    var isSuspended: Bool { suspended }
    var canSwitchCamera: Bool {
        liveSet.phase == .idle && !debugCaptureState.isActive
            && !isSwitchingCamera && !suspended
            && state != .starting && state != .requestingPermission
    }

    @discardableResult
    func selectCamera(_ position: LiveCameraPosition) async -> Bool {
        guard position != cameraPosition else { return true }
        guard canSwitchCamera else { return false }
        cameraPosition = position
        automaticExercise.reset()
        clearBar()
        latestFrame = nil
        framing = LiveFramingAssessment()

        // Also tear down a stopped session so a later start uses the new input.
        // Selecting before opening setup must not request permission or start capture.
        let shouldRestart = startRequested || state == .unavailable || isFailed
        isSwitchingCamera = true
        defer { isSwitchingCamera = false }
        if shouldRestart {
            startRequested = true
            state = .starting
        }
        cancelAnalysisStream()
        captureWatchdogTask?.cancel()
        captureWatchdogTask = nil
        motionManager.stopDeviceMotionUpdates()
        motionSampleAvailable = false
        await setCaptureRunning(false)
        resetCaptureConfiguration()

        guard shouldRestart else { return true }
        // Closing or backgrounding setup during the queue hop must not restart it.
        guard startRequested, !suspended, !Task.isCancelled else {
            if state == .starting { state = .idle }
            return false
        }
        startRequested = false
        await start()
        return isCameraReady
    }

    private var isFailed: Bool {
        if case .failed = state { return true }
        return false
    }

    var barRole: ConfirmedBar.Role {
        exercise == .pullUp
            ? .pullUpGrip
            : (trackingSide == .left ? .leftDipRail : .rightDipRail)
    }

    var currentBar: ConfirmedBar? {
        guard resolvedExercise != nil, let bar,
              bar.role == barRole,
              bar.imageSize == latestFrame?.pose.imageSize
        else { return nil }
        return bar
    }

    var latestImageSize: ImageSize? { latestFrame?.pose.imageSize }
    var latestPose: PoseResult? { latestFrame?.pose }

    var completedDebugCapture: (videoURL: URL, sessionJSON: String, qualificationJSON: String)? {
        guard debugCaptureState == .ready,
              let videoURL = debugCaptureURL,
              let sessionJSON = debugCaptureSessionJSON,
              let qualificationJSON = debugCaptureQualificationJSON
        else { return nil }
        return (videoURL, sessionJSON, qualificationJSON)
    }

    var canStartDebugCapture: Bool {
        guard isCameraReady,
              resolvedExercise != nil,
              liveSet.phase == .idle,
              let movieOutput
        else { return false }
        return !movieOutput.isRecording && !debugCaptureState.isActive
    }

    var debugCaptureAllowsBarSetup: Bool {
        switch debugCaptureState {
        case .starting, .stopping:
            return false
        case .recording:
            return debugCaptureFirstAnalyzedSourceSeconds != nil
        default:
            return true
        }
    }

    var qualificationThermalLevel: LiveDeviceQualificationRecorder.ThermalLevel {
        currentThermalLevel()
    }

    func qualificationReportJSON() -> String {
        let report = qualification.makeReport(
            uptimeSeconds: ProcessInfo.processInfo.systemUptime,
            analyzedFrames: analyzedFrames,
            droppedFrames: droppedFrames,
            analysisFailures: analysisFailures,
            sceneRegistrationFailures: sceneRegistrationFailures,
            counterPolicyVersion: ExerciseCounter.policyVersion,
            exercise: liveSet.exercise.rawValue,
            side: liveSet.side.rawValue,
            observedMovements: liveSet.observedMovements,
            partialAttempts: liveSet.partialAttempts,
            interruptedAttempts: liveSet.interruptedAttempts,
            setAnalyzedFrames: liveSet.analyzedFrameCount,
            setUsableTrackingFrames: liveSet.usableTrackingFrameCount,
            trackingCoverage: liveSet.trackingCoverage,
            setPhase: liveSet.phase.rawValue,
            setEndReason: liveSet.endReason?.rawValue
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(report),
              let text = String(data: data, encoding: .utf8)
        else {
            return "{\"schemaVersion\":1,\"error\":\"reportEncodingFailed\"}"
        }
        return text
    }

    @discardableResult
    func startDebugCapture() -> Bool {
        guard canStartDebugCapture,
              let movieOutput
        else { return false }

        // Automatic setup may already have locked before the debug button is
        // pressed. Reacquire inside the movie so its calibration is reproducible.
        clearBar()
        discardCompletedDebugCapture()
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("HangInThere-debug-\(UUID().uuidString)")
            .appendingPathExtension("mov")
        try? FileManager.default.removeItem(at: url)

        debugCaptureURL = url
        debugCaptureBarSnapshot = currentBar
        debugCaptureStartedUptimeSeconds = nil
        debugCaptureFinishedUptimeSeconds = nil
        debugCaptureFirstAnalyzedSourceSeconds = nil
        debugCaptureLastAnalyzedSourceSeconds = nil
        debugCaptureSessionJSON = nil
        debugCaptureQualificationJSON = nil
        discardDebugCaptureWhenFinished = false
        debugCaptureState = .starting
        movieOutput.startRecording(to: url, recordingDelegate: self)
        return true
    }

    func stopDebugCapture() {
        guard let movieOutput else { return }
        if movieOutput.isRecording {
            snapshotDebugCaptureMetadataIfNeeded()
            debugCaptureState = .stopping
            movieOutput.stopRecording()
        } else if debugCaptureState == .starting {
            snapshotDebugCaptureMetadataIfNeeded()
            debugCaptureState = .stopping
        }
    }

    func discardCompletedDebugCapture() {
        guard !debugCaptureState.isActive else { return }
        if let debugCaptureURL {
            try? FileManager.default.removeItem(at: debugCaptureURL)
        }
        debugCaptureURL = nil
        debugCaptureBarSnapshot = nil
        debugCaptureStartedUptimeSeconds = nil
        debugCaptureFinishedUptimeSeconds = nil
        debugCaptureFirstAnalyzedSourceSeconds = nil
        debugCaptureLastAnalyzedSourceSeconds = nil
        debugCaptureSessionJSON = nil
        debugCaptureQualificationJSON = nil
        discardDebugCaptureWhenFinished = false
        debugCaptureState = movieOutput == nil ? .unavailable : .idle
    }

    private func discardDebugCaptureOnExit() {
        if debugCaptureState.isActive {
            discardDebugCaptureWhenFinished = true
            stopDebugCapture()
        } else {
            discardCompletedDebugCapture()
        }
    }

    private func finishDebugCaptureIfNeeded() {
        if debugCaptureState == .starting || movieOutput?.isRecording == true {
            stopDebugCapture()
        }
    }

    private func snapshotDebugCaptureMetadataIfNeeded() {
        guard debugCaptureSessionJSON == nil || debugCaptureQualificationJSON == nil else {
            return
        }
        debugCaptureFinishedUptimeSeconds = ProcessInfo.processInfo.systemUptime
        debugCaptureSessionJSON = makeDebugSessionMetadataJSON()
        debugCaptureQualificationJSON = qualificationReportJSON()
    }

    private func makeDebugSessionMetadataJSON() -> String {
        var payload: [String: Any] = [
            "schemaVersion": 1,
            "scope": "local developer qualification capture; movement only, not form qualification",
            "capture": [
                "backend": "AVCaptureMovieFileOutput",
                "includesSetup": true,
                "audioRecorded": false,
                "cameraPosition": cameraPosition.rawValue,
                "videoMirrored": false,
                "durationSeconds": max(
                    0,
                    (debugCaptureFinishedUptimeSeconds ?? ProcessInfo.processInfo.systemUptime)
                        - (debugCaptureStartedUptimeSeconds ?? ProcessInfo.processInfo.systemUptime)
                ),
                "firstAnalyzedSourceSeconds":
                    (debugCaptureFirstAnalyzedSourceSeconds as Any?) ?? NSNull(),
                "lastAnalyzedSourceSeconds":
                    (debugCaptureLastAnalyzedSourceSeconds as Any?) ?? NSNull()
            ],
            "counterPolicyVersion": ExerciseCounter.policyVersion,
            "armTracking": liveSet.counter.armTracking,
            "exercise": liveSet.exercise.rawValue,
            "side": liveSet.side.rawValue,
            "set": [
                "phase": liveSet.phase.rawValue,
                "endReason": (liveSet.endReason?.rawValue as Any?) ?? NSNull(),
                "observedMovements": liveSet.observedMovements,
                "partialAttempts": liveSet.partialAttempts,
                "interruptedAttempts": liveSet.interruptedAttempts,
                "analyzedFrames": liveSet.analyzedFrameCount,
                "usableTrackingFrames": liveSet.usableTrackingFrameCount,
                "trackingCoverage": (liveSet.trackingCoverage as Any?) ?? NSNull(),
                "durationSeconds": liveSet.durationSeconds,
                "firstSourceSeconds":
                    (liveSet.firstSourceTimestampSeconds as Any?) ?? NSNull(),
                "lastSourceSeconds":
                    (liveSet.lastSourceTimestampSeconds as Any?) ?? NSNull(),
                "movementTimes": liveSet.movementTimes
            ],
            "app": [
                "version": Bundle.main.object(
                    forInfoDictionaryKey: "CFBundleShortVersionString"
                ) as? String ?? "unknown",
                "build": Bundle.main.object(
                    forInfoDictionaryKey: "CFBundleVersion"
                ) as? String ?? "unknown"
            ]
        ]

        if let bar = debugCaptureBarSnapshot {
            payload["barReference"] = [
                "role": bar.role.rawValue,
                "method": bar.method.rawValue,
                "a": ["x": bar.referenceEdge.a.x, "y": bar.referenceEdge.a.y],
                "b": ["x": bar.referenceEdge.b.x, "y": bar.referenceEdge.b.y],
                "imageSize": [
                    "width": bar.imageSize.width,
                    "height": bar.imageSize.height
                ],
                "sourceSeconds": bar.sourceTime.seconds
            ]
        } else {
            payload["barReference"] = NSNull()
        }

        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(
                withJSONObject: payload,
                options: [.prettyPrinted, .sortedKeys]
              )
        else {
            return "{\"schemaVersion\":1,\"error\":\"debugMetadataEncodingFailed\"}"
        }
        return String(decoding: data, as: UTF8.self) + "\n"
    }

    var setupReadiness: LiveSetupReadiness {
        LiveSetupReadiness(
            cameraReady: isCameraReady,
            framing: framing.state,
            barConfirmed: currentBar != nil,
            phoneStable: phoneOrientation.state.allowsLiveSet,
            sceneStable: sceneTranslation.state.allowsLiveSet
        )
    }

    func configureWorkout(
        exercise: ExerciseCounter.Exercise,
        side: ArmMeasurement.Side
    ) {
        configureWorkout(selection: WorkoutExerciseSelection(exercise: exercise), side: side)
    }

    func configureWorkout(selection: WorkoutExerciseSelection, side: ArmMeasurement.Side) {
        guard liveSet.phase != .running,
              !debugCaptureState.isActive
        else { return }
        let changed = exerciseSelection != selection || trackingSide != side
        guard changed else { return }
        exerciseSelection = selection
        automaticExercise.reset()
        applyWorkout(exercise: selection.exercise ?? exercise, side: side)
    }

    private func applyWorkout(exercise: ExerciseCounter.Exercise, side: ArmMeasurement.Side) {
        self.exercise = exercise
        trackingSide = side
        clearBar()
        liveSet.reset(exercise: exercise, side: side)
        if let latestFrame {
            framing = LiveFramingAssessment(pose: latestFrame.pose, side: side,
                usesBothArms: exerciseSelection == .automatic || exercise == .pullUp)
        } else {
            framing = LiveFramingAssessment()
        }
    }

    // Also exercised without starting capture in controller regression tests.
    // No automatic identity changes are allowed during a set or debug recording.
    func observeExerciseSelection(_ pose: PoseResult) {
        guard exerciseSelection == .automatic, liveSet.phase == .idle,
              !debugCaptureState.isActive, pendingBarMotionSample == nil else { return }
        let previous = automaticExercise.exercise
        let previousApparatus = automaticExercise.apparatusCandidate
        let next = automaticExercise.observe(pose)
        if previousApparatus != automaticExercise.apparatusCandidate || (previous != nil && next == nil) {
            applyWorkout(exercise: automaticExercise.apparatusCandidate ?? exercise, side: trackingSide)
        }
    }

    func beginBarSetup() -> BarSetupFrame? {
        guard liveSet.phase != .running,
              resolvedExercise != nil,
              isCameraReady,
              framing.state.isReady,
              debugCaptureAllowsBarSetup,
              let latestFrame,
              let motionSample = currentPhoneMotionSample(),
              let sceneReference = VisionStaticSceneRegistrationWorker.makeReference(
                image: latestFrame.image
              )
        else { return nil }

        if debugCaptureState == .recording {
            let latestSourceSeconds = latestFrame.pose.timestamp.seconds
            guard latestSourceSeconds.isFinite,
                  let firstCapturedSourceSeconds = debugCaptureFirstAnalyzedSourceSeconds,
                  latestSourceSeconds >= firstCapturedSourceSeconds
            else { return nil }
        }

        // Opening manual setup invalidates any contour work already in flight.
        resetAutomaticBarSetup()
        setupGeneration &+= 1
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        let setup = BarSetupFrame(
            frame: latestFrame,
            role: barRole,
            generation: setupGeneration
        )
        pendingBarMotionSample = (setup.id, motionSample)
        pendingBarSceneReference = (setup.id, sceneReference)
        return setup
    }

    func endBarSetup() {
        resetAutomaticBarSetup()
        // Invalidate a cancelled task even when its actor call finishes after
        // automatic acquisition resumes. It must not clear a newer task's slot.
        setupGeneration &+= 1
        pendingBarMotionSample = nil
        pendingBarSceneReference = nil
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil
    }

    private func resetAutomaticBarSetup() {
        analyzer?.resetGripInference()
        resetHandsFreeStart()
        automaticBarTask?.cancel()
        automaticBarTask = nil
        lastAutomaticBarSeconds = nil
        automaticBarPolicy.reset()
        automaticBarState = .lookingForGrip
    }

    func setHandsFreeStartEnabled(_ enabled: Bool) {
        guard liveSet.phase == .idle else { return }
        handsFreeStartEnabled = enabled
        resetHandsFreeStart()
    }

    func setWorkoutDetailsPresented(_ presented: Bool) {
        workoutDetailsPresented = presented
        resetHandsFreeStart()
    }

    private func resetHandsFreeStart() {
        handsFreeStart.reset()
        workoutAnalysis.resetReadiness()
        qualifiedStartPose = nil
        lastHandsFreeFrameUptime = nil
        countdownSpeech.stopSpeaking(at: .immediate)
    }

    private func speakWorkoutCue(_ text: String) {
        countdownSpeech.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
        countdownSpeech.speak(utterance)
    }

    private func updateHandsFreeStart(_ frame: ProcessedFrame) {
        guard handsFreeStartEnabled, liveSet.phase == .idle else { return }
        var eligible = false
        if setupReadiness.state.isReady, pendingBarMotionSample == nil,
           debugCaptureAllowsBarSetup, currentPhoneMotionSample() != nil,
           let bar = currentBar {
            eligible = exercise == .dip || HandsFreeSetStart.isStartingPosition(
                pose: frame.pose, referenceEdge: bar.referenceEdge
            )
        }
        // Shared readiness uses only fresh source frames after real apparatus,
        // motion and scene qualification. No gesture or countdown is required.
        if !eligible || workoutDetailsPresented { qualifiedStartPose = nil }
        if let previous = qualifiedStartPose,
           frame.pose.timestamp.seconds <= previous.timestamp.seconds ||
           frame.pose.timestamp.seconds - previous.timestamp.seconds > ExerciseCounter.maximumGapSeconds {
            qualifiedStartPose = nil
        }
        let baseline = qualifiedStartPose
        if eligible && qualifiedStartPose == nil { qualifiedStartPose = frame.pose }
        if workoutAnalysis.ready(seconds: frame.pose.timestamp.seconds,
            eligible: eligible && !workoutDetailsPresented) {
            if startSet(discardQueuedFrame: false), let baseline, let reference = currentBar?.referenceEdge {
                liveSet.consume(baseline, referenceEdge: reference)
                _ = workoutAnalysis.consume(baseline, reference: reference, counter: liveSet.counter,
                    event: nil, automaticFinish: true)
            }
        }
    }

    private func maybeFindAutomaticBar(_ frame: ProcessedFrame) {
        guard exerciseSelection.exercise != nil || automaticExercise.apparatusCandidate != nil,
              liveSet.phase == .idle, bar == nil,
              pendingBarMotionSample == nil, automaticBarTask == nil,
              debugCaptureAllowsBarSetup, framing.state.isReady,
              let motion = currentPhoneMotionSample() else { return }
        let seconds = frame.pose.timestamp.seconds
        guard seconds.isFinite else { return }
        if let lastAutomaticBarSeconds, seconds - lastAutomaticBarSeconds < AutomaticPullUpBar.samplingInterval {
            return
        }
        lastAutomaticBarSeconds = seconds
        guard let grip = AutomaticApparatusReference.search(pose: frame.pose, exercise: exercise) else {
            automaticBarPolicy.reset()
            automaticBarState = .lookingForGrip
            return
        }
        guard let reference = VisionStaticSceneRegistrationWorker.makeReference(image: frame.image) else { return }
        let token = setupGeneration
        automaticBarTask = Task { @MainActor [weak self] in
            guard let self else { return }
            var candidates: [[BarLineCandidate]] = []
            do {
                for region in grip.regions {
                    candidates.append(try await automaticBarDetector.detect(image: frame.image, region: region))
                }
            } catch {
                guard self.setupGeneration == token else { return }
                self.automaticBarTask = nil
                self.automaticBarPolicy.reset()
                self.automaticBarState = .findingEdge
                return
            }
            guard self.setupGeneration == token else { return }
            self.automaticBarTask = nil
            guard !Task.isCancelled, self.isCameraReady, !self.suspended,
                  self.liveSet.phase == .idle, self.bar == nil,
                  self.pendingBarMotionSample == nil, self.debugCaptureAllowsBarSetup,
                  let latest = self.latestFrame,
                  latest.pose.imageSize == frame.pose.imageSize,
                  latest.pose.timestamp.seconds - seconds <= 0.8,
                  let latestGrip = AutomaticApparatusReference.search(pose: latest.pose, exercise: self.exercise),
                  let currentMotion = self.currentPhoneMotionSample(),
                  motion.attitude.angularDistanceDegrees(to: currentMotion.attitude) < PhoneOrientationStability.movementThresholdDegrees,
                  grip.matches(latestGrip, size: frame.pose.imageSize)
            else {
                self.automaticBarPolicy.reset()
                self.automaticBarState = .lookingForGrip
                return
            }
            let proposal = self.automaticBarPolicy.observe(pose: frame.pose, exercise: self.exercise, side: self.trackingSide, candidates: candidates)
            self.automaticBarState = self.automaticBarPolicy.state
            guard let proposal else { return }
            let setup = BarSetupFrame(frame: frame, role: self.barRole, generation: token)
            self.pendingBarMotionSample = (setup.id, motion)
            self.pendingBarSceneReference = (setup.id, reference)
            if !self.confirmBar(proposal, for: setup) {
                self.endBarSetup()
            }
        }
    }

    @discardableResult
    func confirmBar(_ bar: ConfirmedBar, for setup: BarSetupFrame) -> Bool {
        guard liveSet.phase != .running,
              isCameraReady,
              setup.generation == setupGeneration,
              setup.role == barRole,
              bar.role == barRole,
              bar.isValid,
              bar.imageSize == setup.frame.pose.imageSize,
              bar.sourceTime == setup.frame.pose.timestamp,
              let pending = pendingBarMotionSample,
              pending.id == setup.id,
              let pendingScene = pendingBarSceneReference,
              pendingScene.id == setup.id,
              phoneOrientation.calibrate(
                pending.sample.attitude,
                timestamp: pending.sample.timestamp
              ),
              sceneTranslation.calibrate(
                imageSize: pendingScene.reference.imageSize
              )
        else { return false }

        pendingBarMotionSample = nil
        pendingBarSceneReference = nil
        sceneReference = pendingScene.reference
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil
        self.bar = bar
        if debugCaptureState.isActive {
            debugCaptureBarSnapshot = bar
        }
        return true
    }

    func clearBar() {
        guard liveSet.phase != .running else { return }
        resetAutomaticBarSetup()
        setupGeneration &+= 1
        bar = nil
        pendingBarMotionSample = nil
        pendingBarSceneReference = nil
        sceneReference = nil
        phoneOrientation.reset()
        sceneTranslation.reset()
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil
    }

    @discardableResult
    func startSet(discardQueuedFrame: Bool = true) -> Bool {
        guard setupReadiness.state.isReady,
              resolvedExercise != nil,
              liveSet.phase != .running
        else { return false }

        if exerciseSelection == .automatic, !automaticExercise.lock() { return false }
        resetHandsFreeStart()
        workoutAnalysis.start(exercise: exercise)
        archivedSetId = nil
        liveSet.start(exercise: exercise, side: trackingSide)
        if workoutAnalysis.beginStartFeedback(isRunning: liveSet.phase == .running) {
            speakWorkoutCue(workoutAnalysis.startSpokenMessage)
        }
        // The bounded analysis stream may already contain one pre-start event.
        // Drop exactly the next delivered frame so a new set cannot begin from
        // a queued observation preceding the tap or spoken start cue.
        discardNextSetFrame = discardQueuedFrame
        return true
    }

    func loadWorkoutHistory() async {
        do { workoutHistory = try await WorkoutRecordStore.shared.recent() }
        catch { workoutStorageError = error.localizedDescription }
    }

    private func archiveWorkoutSet() {
        guard liveSet.phase == .finished, archivedSetId != workoutAnalysis.setId,
              let record = workoutAnalysis.finish(reason: liveSet.endReason?.rawValue ?? "manual"),
              archivedSetId != record.id else { return }
        archivedSetId = record.id
        guard record.hasPractice else { return }
        latestCompletedWorkout = record
        workoutHistory.insert(record, at: 0)
        workoutHistory = Array(workoutHistory.prefix(50))
        persistWorkout(record, finalized: true)
    }

    private func persistWorkout(_ record: WorkoutSetRecord, finalized: Bool) {
        Task {
            do {
                try await WorkoutRecordStore.shared.enqueue(record, finalized: finalized)
                try await WorkoutRecordStore.shared.flushPending()
                workoutStorageError = nil
            } catch {
                workoutStorageError = "Workout save will retry: \(error.localizedDescription)"
                retryWorkoutWrites()
            }
        }
    }

    private func retryWorkoutWrites() {
        guard workoutWriteTask == nil else { return }
        workoutWriteTask = Task { [weak self] in
            while !Task.isCancelled {
                do {
                    try await WorkoutRecordStore.shared.flushPending()
                    self?.workoutStorageError = nil
                    break
                } catch {
                    self?.workoutStorageError = "Workout save will retry: \(error.localizedDescription)"
                    try? await Task.sleep(for: .seconds(1))
                }
            }
            self?.workoutWriteTask = nil
        }
    }

    func stopSet() {
        resetHandsFreeStart()
        discardNextSetFrame = false
        liveSet.finish()
        archiveWorkoutSet()
        finishDebugCaptureIfNeeded()
    }

    func prepareNextSet() {
        guard liveSet.phase == .finished else { return }
        resetHandsFreeStart()
        if liveSet.phase == .finished { archiveWorkoutSet() }
        finishDebugCaptureIfNeeded()
        discardNextSetFrame = false
        liveSet.prepareNextSet()
        if exerciseSelection == .automatic {
            automaticExercise.reset()
            clearBar()
        }
    }

    func suspendForSceneLoss() {
        suspend(
            counterReason: "appInactive",
            endReason: .appInactive,
            message: "Live setup was cleared because the app left the foreground. Re-check framing and set the bar again.",
            stopCapture: true
        )
    }

    func resumeAfterInterruption() async {
        guard startRequested, suspended, !isSwitchingCamera else { return }

        // A scene loss during a camera switch may leave capture torn down.
        // Rebuild it before resuming instead of starting a session with no input.
        do {
            try configureIfNeeded()
        } catch {
            resetCaptureConfiguration()
            state = .failed(error.localizedDescription)
            return
        }
        phoneOrientation.reset()
        startMotionMonitoring()
        state = .starting
        await setCaptureRunning(true)
        guard startRequested else { return }

        guard session.isRunning, !session.isInterrupted else {
            motionManager.stopDeviceMotionUpdates()
            motionSampleAvailable = false
            state = .interrupted("The camera is still unavailable. Try Resume camera again when the interruption ends.")
            return
        }

        suspended = false
        automaticExercise.reset()
        state = .ready
    }

    func start() async {
        guard !startRequested else { return }
        startRequested = true

        let authorized: Bool
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            authorized = true
        case .notDetermined:
            state = .requestingPermission
            authorized = await AVCaptureDevice.requestAccess(for: .video)
        case .denied, .restricted:
            authorized = false
        @unknown default:
            authorized = false
        }

        guard startRequested, !Task.isCancelled else { return }

        guard authorized else {
            state = .denied
            startRequested = false
            return
        }

        suspended = false
        qualification.reset(startUptimeSeconds: ProcessInfo.processInfo.systemUptime)
        analyzedFrames = 0
        droppedFrames = 0
        analysisFailures = 0
        lastProcessingMilliseconds = nil
        lastSceneRegistrationMilliseconds = nil
        sceneRegistrationFailures = 0
        latestFrame = nil
        setupGeneration &+= 1
        bar = nil
        pendingBarMotionSample = nil
        pendingBarSceneReference = nil
        sceneReference = nil
        phoneOrientation.reset()
        sceneTranslation.reset()
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil
        motionSampleAvailable = false
        discardNextSetFrame = false
        liveSet.reset(exercise: exercise, side: trackingSide)
        framing = LiveFramingAssessment()

        do {
            try configureIfNeeded()
        } catch LiveCameraSetupError.noCamera {
            resetCaptureConfiguration()
            state = .unavailable
            startRequested = false
            return
        } catch {
            resetCaptureConfiguration()
            state = .failed(error.localizedDescription)
            startRequested = false
            return
        }

        startMotionMonitoring()
        framing = LiveFramingAssessment()
        state = .starting
        await setCaptureRunning(true)
        guard startRequested else { return }

        guard session.isRunning, !session.isInterrupted else {
            motionManager.stopDeviceMotionUpdates()
            motionSampleAvailable = false
            state = .failed("The camera did not start. Close Live Workout and try again.")
            startRequested = false
            return
        }

        state = .ready
        startCaptureWatchdogIfNeeded()
    }

    func stop() {
        if liveSet.phase == .running {
            liveSet.finish()
            archiveWorkoutSet()
        }
        discardDebugCaptureOnExit()
        discardNextSetFrame = false
        suspended = false
        startRequested = false
        if state == .ready || state == .starting || state == .requestingPermission {
            state = .idle
        }
        framing = LiveFramingAssessment()
        latestFrame = nil
        setupGeneration &+= 1
        bar = nil
        pendingBarMotionSample = nil
        pendingBarSceneReference = nil
        sceneReference = nil
        phoneOrientation.reset()
        sceneTranslation.reset()
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil
        captureWatchdogTask?.cancel()
        captureWatchdogTask = nil
        motionManager.stopDeviceMotionUpdates()
        motionSampleAvailable = false
        cancelAnalysisStream()
        let session = session
        sessionQueue.async {
            if session.isRunning {
                session.stopRunning()
            }
        }
    }

    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    private func configureIfNeeded() throws {
        if configured, let videoOutput {
            startAnalysisStream(output: videoOutput)
            return
        }
        guard let device = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: cameraPosition.capturePosition
        ) else {
            throw LiveCameraSetupError.noCamera
        }

        let input = try AVCaptureDeviceInput(device: device)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]

        session.beginConfiguration()
        defer { session.commitConfiguration() }

        if session.canSetSessionPreset(.hd1280x720) {
            session.sessionPreset = .hd1280x720
        }
        guard session.canAddInput(input) else {
            throw LiveCameraSetupError.cannotAddInput
        }
        session.addInput(input)

        guard session.canAddOutput(output) else {
            session.removeInput(input)
            throw LiveCameraSetupError.cannotAddVideoOutput
        }
        session.addOutput(output)

        let movieOutput = AVCaptureMovieFileOutput()
        if session.canAddOutput(movieOutput) {
            session.addOutput(movieOutput)
            if let movieConnection = movieOutput.connection(with: .video),
               (try? LiveCameraConnectionConfiguration.apply(to: movieConnection)) != nil {
                self.movieOutput = movieOutput
                if debugCaptureState != .ready { debugCaptureState = .idle }
            } else {
                session.removeOutput(movieOutput)
                if debugCaptureState != .ready { debugCaptureState = .unavailable }
            }
        } else {
            if debugCaptureState != .ready { debugCaptureState = .unavailable }
        }

        guard let connection = output.connection(with: .video) else {
            throw LiveCameraSetupError.cannotAddVideoOutput
        }
        try LiveCameraConnectionConfiguration.apply(to: connection)
        videoOutput = output
        configured = true
        startAnalysisStream(output: output)
    }

    private func resetCaptureConfiguration() {
        cancelAnalysisStream()
        session.beginConfiguration()
        for output in session.outputs { session.removeOutput(output) }
        for input in session.inputs { session.removeInput(input) }
        session.commitConfiguration()
        videoOutput = nil
        movieOutput = nil
        configured = false
        // Preserve a completed debug capture until it is exported or discarded.
        if debugCaptureState != .ready { debugCaptureState = .unavailable }
    }

    private func cancelAnalysisStream() {
        analysisGeneration &+= 1
        analysisEventTask?.cancel()
        analysisEventTask = nil
        videoOutput?.setSampleBufferDelegate(nil, queue: nil)
        analyzer = nil
    }

    private func startAnalysisStream(output: AVCaptureVideoDataOutput) {
        cancelAnalysisStream()
        let generation = analysisGeneration
        let eventStream = AsyncStream<LiveAnalyzerEvent>(
            bufferingPolicy: .bufferingNewest(1)
        ) { continuation in
            let analyzer = LiveFrameAnalyzer(gripEnabled: resolvedExercise != .dip) { event in
                continuation.yield(event)
            }
            self.analyzer = analyzer
            output.setSampleBufferDelegate(analyzer, queue: analysisQueue)
        }

        analysisEventTask = Task { @MainActor [weak self] in
            for await event in eventStream {
                guard !Task.isCancelled else { return }
                guard let self, self.analysisGeneration == generation else { return }
                self.accept(event)
            }
        }
    }

    private func setCaptureRunning(_ running: Bool) async {
        let session = session
        await withCheckedContinuation { continuation in
            sessionQueue.async {
                if running {
                    if !session.isRunning {
                        session.startRunning()
                    }
                } else if session.isRunning {
                    session.stopRunning()
                }
                continuation.resume()
            }
        }
    }

    private func suspend(
        counterReason: String,
        endReason: LiveSetSession.EndReason,
        message: String,
        stopCapture: Bool
    ) {
        guard startRequested, !suspended else { return }

        if liveSet.phase == .running {
            liveSet.interruptAndFinish(
                reason: counterReason,
                endReason: endReason
            )
        }
        if liveSet.phase == .finished { archiveWorkoutSet() }
        finishDebugCaptureIfNeeded()

        discardNextSetFrame = false
        suspended = true
        state = .interrupted(message)
        framing = LiveFramingAssessment()
        latestFrame = nil
        setupGeneration &+= 1
        bar = nil
        pendingBarMotionSample = nil
        pendingBarSceneReference = nil
        sceneReference = nil
        phoneOrientation.reset()
        sceneTranslation.reset()
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil

        if stopCapture {
            motionManager.stopDeviceMotionUpdates()
            motionSampleAvailable = false
            let session = session
            sessionQueue.async {
                if session.isRunning {
                    session.stopRunning()
                }
            }
        }
    }

    private func startMotionMonitoring() {
        guard motionManager.isDeviceMotionAvailable else {
            motionSampleAvailable = false
            phoneOrientation.markUnavailable()
            return
        }
        motionManager.deviceMotionUpdateInterval = 0.05
        if !motionManager.isDeviceMotionActive {
            motionManager.startDeviceMotionUpdates()
        }
        motionSampleAvailable = currentPhoneMotionSample() != nil
        if phoneOrientation.state == .unavailable {
            phoneOrientation.reset()
        }
    }

    private func currentPhoneMotionSample() -> PhoneMotionSample? {
        guard motionManager.isDeviceMotionActive,
              let motion = motionManager.deviceMotion,
              motion.timestamp.isFinite,
              let attitude = PhoneOrientationStability.Quaternion(
                x: motion.attitude.quaternion.x,
                y: motion.attitude.quaternion.y,
                z: motion.attitude.quaternion.z,
                w: motion.attitude.quaternion.w
              )
        else { return nil }

        return PhoneMotionSample(attitude: attitude, timestamp: motion.timestamp)
    }

    private func pollPhoneOrientation() {
        let sample = currentPhoneMotionSample()
        motionSampleAvailable = sample != nil

        guard bar != nil else { return }
        guard let sample else {
            if !motionManager.isDeviceMotionActive {
                phoneOrientation.markUnavailable()
                invalidateForPhoneOrientationLoss()
            }
            return
        }

        if phoneOrientation.observe(sample.attitude, timestamp: sample.timestamp) == .moved {
            invalidateForPhoneMovement()
        }
    }

    private func invalidateForPhoneMovement() {
        guard bar != nil else { return }

        if liveSet.phase == .running {
            liveSet.interruptAndFinish(
                reason: "phoneMoved",
                endReason: .phoneMoved
            )
            if liveSet.phase == .finished { archiveWorkoutSet() }
            finishDebugCaptureIfNeeded()
        }

        setupGeneration &+= 1
        bar = nil
        pendingBarMotionSample = nil
        pendingBarSceneReference = nil
        sceneReference = nil
        sceneTranslation.reset()
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil
    }

    private func invalidateForPhoneOrientationLoss() {
        guard bar != nil else { return }

        if liveSet.phase == .running {
            liveSet.interruptAndFinish(
                reason: "phoneOrientationUnavailable",
                endReason: .setupInvalidated
            )
            if liveSet.phase == .finished { archiveWorkoutSet() }
            finishDebugCaptureIfNeeded()
        }

        setupGeneration &+= 1
        bar = nil
        pendingBarMotionSample = nil
        pendingBarSceneReference = nil
        sceneReference = nil
        sceneTranslation.reset()
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil
    }

    private func maybeCheckSceneTranslation(_ frame: ProcessedFrame) {
        guard bar != nil,
              let reference = sceneReference,
              sceneRegistrationTask == nil
        else { return }

        let seconds = frame.pose.timestamp.seconds
        guard seconds.isFinite else { return }
        if let lastSceneRegistrationSeconds,
           seconds - lastSceneRegistrationSeconds < 0.33 {
            return
        }

        lastSceneRegistrationSeconds = seconds
        let token = setupGeneration
        let image = frame.image
        sceneRegistrationTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let measurement: StaticSceneRegistrationMeasurement
            let started = ContinuousClock.now
            do {
                measurement = try await sceneRegistrationWorker.measure(
                    reference: reference,
                    image: image
                )
            } catch {
                if self.setupGeneration == token {
                    self.sceneRegistrationFailures += 1
                    self.lastSceneRegistrationMilliseconds = nil
                    self.sceneRegistrationTask = nil
                }
                return
            }

            let duration = started.duration(to: .now).components
            let registrationMilliseconds = Double(duration.seconds) * 1_000
                + Double(duration.attoseconds) / 1e15

            guard !Task.isCancelled,
                  self.setupGeneration == token,
                  self.bar != nil
            else {
                if self.setupGeneration == token {
                    self.sceneRegistrationTask = nil
                }
                return
            }

            self.lastSceneRegistrationMilliseconds = registrationMilliseconds
            let state = self.sceneTranslation.observe(
                translations: measurement.translations,
                globalScaleFraction: measurement.globalScaleFraction,
                timestamp: seconds
            )
            self.sceneRegistrationTask = nil
            if state == .moved {
                self.invalidateForSceneMovement()
            }
        }
    }

    private func invalidateForSceneMovement() {
        guard bar != nil else { return }

        let kind = sceneTranslation.movementKind
        if liveSet.phase == .running {
            let reason = kind == .scale ? "sceneScaled" : "sceneShifted"
            let endReason: LiveSetSession.EndReason = kind == .scale ? .sceneScaled : .sceneShifted
            liveSet.interruptAndFinish(
                reason: reason,
                endReason: endReason
            )
            if liveSet.phase == .finished { archiveWorkoutSet() }
            finishDebugCaptureIfNeeded()
        }

        setupGeneration &+= 1
        bar = nil
        pendingBarMotionSample = nil
        pendingBarSceneReference = nil
        sceneReference = nil
        phoneOrientation.reset()
        sceneTranslation.reset()
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil
    }

    private func recordQualificationSample() {
        qualification.record(
            uptimeSeconds: ProcessInfo.processInfo.systemUptime,
            visionProcessingMilliseconds: lastProcessingMilliseconds,
            sceneRegistrationMilliseconds: lastSceneRegistrationMilliseconds,
            analyzedFrames: analyzedFrames,
            droppedFrames: droppedFrames,
            analysisFailures: analysisFailures,
            sceneRegistrationFailures: sceneRegistrationFailures,
            orientationDeltaDegrees: phoneOrientation.latestDeltaDegrees,
            sceneShiftFraction: sceneTranslation.latestShiftFraction,
            sceneScaleFraction: sceneTranslation.latestScaleFraction,
            sceneTranslationConsensusPatches: sceneTranslation.latestConsensusPatches,
            sceneScaleMeasurementAvailable: sceneTranslation.latestScaleMeasurementAvailable,
            thermalLevel: currentThermalLevel(),
            setPhase: liveSet.phase.rawValue
        )
    }

    private func currentThermalLevel() -> LiveDeviceQualificationRecorder.ThermalLevel {
        switch ProcessInfo.processInfo.thermalState {
        case .nominal: .nominal
        case .fair: .fair
        case .serious: .serious
        case .critical: .critical
        @unknown default: .unknown
        }
    }

    private func startCaptureWatchdogIfNeeded() {
        guard captureWatchdogTask == nil else { return }

        captureWatchdogTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                guard let self else { return }
                guard self.startRequested,
                      !self.suspended,
                      self.state == .ready else { continue }

                self.pollPhoneOrientation()
                if self.liveSet.phase == .running, let last = self.lastAnalyzedFrameUptime,
                   self.workoutAnalysis.observationOutage(elapsed: ProcessInfo.processInfo.systemUptime - last) {
                    self.liveSet.interruptAndFinish(reason: "sourceFramesUnavailable", endReason: .trackingLost)
                    self.archiveWorkoutSet()
                    self.finishDebugCaptureIfNeeded()
                }
                if case .countdown = self.handsFreeStart.state {
                    let stale = self.lastHandsFreeFrameUptime.map {
                        ProcessInfo.processInfo.systemUptime - $0 > HandsFreeSetStart.maximumFrameGap
                    } ?? true
                    if stale || !self.setupReadiness.state.isReady {
                        self.resetHandsFreeStart()
                    }
                }
                self.recordQualificationSample()

                if self.session.isInterrupted {
                    self.suspend(
                        counterReason: "cameraInterrupted",
                        endReason: .cameraInterrupted,
                        message: "The camera was interrupted. Re-check framing and set the bar again before another set.",
                        stopCapture: false
                    )
                } else if !self.session.isRunning {
                    self.suspend(
                        counterReason: "cameraFailure",
                        endReason: .cameraFailure,
                        message: "The camera session stopped unexpectedly. Resume the camera and repeat setup before another set.",
                        stopCapture: false
                    )
                }
            }
        }
    }

    private func accept(_ event: LiveAnalyzerEvent) {
        analyzer?.setGripEnabled(resolvedExercise != .dip)
        guard startRequested, !suspended, state == .ready else { return }

        switch event {
        case .frame(let frame):
            if debugCaptureState == .recording || debugCaptureState == .stopping {
                let seconds = frame.pose.timestamp.seconds
                if seconds.isFinite {
                    if debugCaptureFirstAnalyzedSourceSeconds == nil {
                        debugCaptureFirstAnalyzedSourceSeconds = seconds
                    }
                    debugCaptureLastAnalyzedSourceSeconds = seconds
                }
            }

            if let bar, bar.imageSize != frame.pose.imageSize {
                self.bar = nil
                setupGeneration &+= 1
                pendingBarMotionSample = nil
                pendingBarSceneReference = nil
                sceneReference = nil
                phoneOrientation.reset()
                sceneTranslation.reset()
                sceneRegistrationTask?.cancel()
                sceneRegistrationTask = nil
                lastSceneRegistrationSeconds = nil
                if liveSet.phase == .running {
                    liveSet.interruptAndFinish(
                        reason: "barReferenceUnavailable",
                        endReason: .setupInvalidated
                    )
                    if liveSet.phase == .finished { archiveWorkoutSet() }
                    finishDebugCaptureIfNeeded()
                }
            }
            lastAnalyzedFrameUptime = ProcessInfo.processInfo.systemUptime
            latestFrame = frame
            analyzedFrames += 1
            lastProcessingMilliseconds = frame.processingMilliseconds
            observeExerciseSelection(frame.pose)
            framing = LiveFramingAssessment(pose: frame.pose, side: trackingSide,
                usesBothArms: exerciseSelection == .automatic || exercise == .pullUp)
            maybeFindAutomaticBar(frame)
            maybeCheckSceneTranslation(frame)
            recordQualificationSample()
            if liveSet.phase == .finished, workoutAnalysis.shouldResume(reason: liveSet.endReason) {
                archiveWorkoutSet()
                let lost = liveSet.endReason == .trackingLost
                prepareNextSet()
                if lost { clearBar() }
            }
            let wasRunning = liveSet.phase == .running
            updateHandsFreeStart(frame)

            // Manual starts reject queued pixels. Automatic starts consume the
            // qualified source frame immediately and seed its observed baseline.
            if liveSet.phase == .running, wasRunning || !discardNextSetFrame {
                if discardNextSetFrame {
                    discardNextSetFrame = false
                } else if let referenceEdge = currentBar?.referenceEdge {
                    let eventCount = liveSet.counter.events.count
                    liveSet.consume(frame.pose, referenceEdge: referenceEdge)
                    let event = liveSet.counter.events.count > eventCount ? liveSet.counter.events.last : nil
                    let automaticEnd = workoutAnalysis.consume(frame.pose, reference: referenceEdge,
                        counter: liveSet.counter, event: event, automaticFinish: true)
                    if let draft = workoutAnalysis.drainDraft() {
                        persistWorkout(draft, finalized: false)
                    }
                    if let reason = automaticEnd {
                        liveSet.finish(reason: reason)
                        archiveWorkoutSet()
                        finishDebugCaptureIfNeeded()
                    }
                } else {
                    liveSet.interruptAndFinish(
                        reason: "barReferenceUnavailable",
                        endReason: .setupInvalidated
                    )
                    if liveSet.phase == .finished { archiveWorkoutSet() }
                    finishDebugCaptureIfNeeded()
                }
            }

        case .dropped:
            droppedFrames += 1

        case .failed:
            resetHandsFreeStart()
            if exerciseSelection == .automatic, liveSet.phase == .idle {
                automaticExercise.reset()
                clearBar()
            }
            analysisFailures += 1
            latestFrame = nil
            lastProcessingMilliseconds = nil
            framing = LiveFramingAssessment(state: .analysisUnavailable)
            liveSet.interrupt(reason: "inferenceFailure")
            analyzer?.resetGripInference()
            workoutAnalysis.interrupt(reason: "inferenceFailure")
        }
    }
}

extension LiveCameraPreviewController: AVCaptureFileOutputRecordingDelegate {
    nonisolated func fileOutput(
        _ output: AVCaptureFileOutput,
        didStartRecordingTo fileURL: URL,
        from connections: [AVCaptureConnection]
    ) {
        Task { @MainActor [weak self] in
            guard let self, self.debugCaptureURL == fileURL else { return }
            self.debugCaptureStartedUptimeSeconds = ProcessInfo.processInfo.systemUptime
            if self.debugCaptureState == .stopping {
                self.movieOutput?.stopRecording()
            } else {
                self.debugCaptureState = .recording
            }
        }
    }

    nonisolated func fileOutput(
        _ output: AVCaptureFileOutput,
        didFinishRecordingTo outputFileURL: URL,
        from connections: [AVCaptureConnection],
        error: Error?
    ) {
        let error = error as NSError?
        let explicitlyFinished = error?.userInfo[
            AVErrorRecordingSuccessfullyFinishedKey
        ] as? Bool
        let succeeded = error == nil || explicitlyFinished == true
        let message = error?.localizedDescription

        Task { @MainActor [weak self] in
            guard let self else { return }
            self.debugCaptureFinishedUptimeSeconds = ProcessInfo.processInfo.systemUptime

            if self.discardDebugCaptureWhenFinished {
                try? FileManager.default.removeItem(at: outputFileURL)
                self.debugCaptureURL = nil
                self.debugCaptureBarSnapshot = nil
                self.debugCaptureStartedUptimeSeconds = nil
                self.debugCaptureFinishedUptimeSeconds = nil
                self.debugCaptureFirstAnalyzedSourceSeconds = nil
                self.debugCaptureLastAnalyzedSourceSeconds = nil
                self.debugCaptureSessionJSON = nil
                self.debugCaptureQualificationJSON = nil
                self.discardDebugCaptureWhenFinished = false
                self.debugCaptureState = self.movieOutput == nil ? .unavailable : .idle
                return
            }

            guard self.debugCaptureState == .stopping else {
                try? FileManager.default.removeItem(at: outputFileURL)
                self.debugCaptureURL = nil
                self.debugCaptureSessionJSON = nil
                self.debugCaptureQualificationJSON = nil
                self.debugCaptureState = .failed(
                    message ?? "Debug recording ended before finalization was requested."
                )
                return
            }

            guard succeeded,
                  FileManager.default.fileExists(atPath: outputFileURL.path)
            else {
                try? FileManager.default.removeItem(at: outputFileURL)
                self.debugCaptureState = .failed(
                    message ?? "The debug movie could not be finalized."
                )
                return
            }

            self.debugCaptureURL = outputFileURL
            if self.debugCaptureSessionJSON == nil
                || self.debugCaptureQualificationJSON == nil {
                self.snapshotDebugCaptureMetadataIfNeeded()
            }
            self.debugCaptureState = .ready
        }
    }
}

struct LiveCameraPreviewSurface: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.previewLayer.videoGravity = .resizeAspect
        view.previewLayer.session = session
        return view
    }

    func updateUIView(_ view: CameraPreviewView, context: Context) {
        if view.previewLayer.session !== session {
            view.previewLayer.session = session
        }
        view.updateRotation()
    }
}

final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        updateRotation()
    }

    func updateRotation() {
        guard let connection = previewLayer.connection else { return }
        try? LiveCameraConnectionConfiguration.apply(to: connection)
    }
}
#endif
