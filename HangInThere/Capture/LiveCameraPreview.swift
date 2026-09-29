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
        case .unavailable: "Rear camera unavailable"
        case .interrupted: "Camera interrupted"
        case .failed: "Camera unavailable"
        }
    }

    var detail: String {
        switch self {
        case .idle:
            "The rear camera starts only while this setup screen is open."
        case .requestingPermission:
            "Allow camera access to position the phone for a live workout."
        case .starting:
            "Preparing the rear camera preview."
        case .ready:
            "Keep the phone fixed once the athlete and apparatus are framed."
        case .denied:
            "Enable camera access in Settings. Recorded-video review remains available."
        case .unavailable:
            "A rear wide-angle camera is required for the first live-workout profile."
        case .interrupted(let message):
            message
        case .failed(let message):
            message
        }
    }
}

private enum LiveCameraSetupError: LocalizedError {
    case noRearCamera
    case cannotAddInput
    case cannotAddVideoOutput
    case unsupportedPortraitRotation

    var errorDescription: String? {
        switch self {
        case .noRearCamera:
            "No supported rear camera is available."
        case .cannotAddInput:
            "The rear camera could not be added to the capture session."
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
    private let publish: @Sendable (LiveAnalyzerEvent) -> Void

    init(
        estimator: any PoseEstimator = VisionPoseEstimator(),
        publish: @escaping @Sendable (LiveAnalyzerEvent) -> Void
    ) {
        self.estimator = estimator
        self.publish = publish
    }

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
                let pose = try estimator.estimate(
                    image: image,
                    timestamp: PresentationTime(value: pts.value, timescale: pts.timescale)
                )
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
    private(set) var framing = LiveFramingAssessment()
    private(set) var analyzedFrames = 0
    private(set) var droppedFrames = 0
    private(set) var analysisFailures = 0
    private(set) var lastProcessingMilliseconds: Double?
    private(set) var lastSceneRegistrationMilliseconds: Double?
    private(set) var sceneRegistrationFailures = 0
    private(set) var bar: ConfirmedBar?
    private(set) var liveSet = LiveSetSession()
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
    @ObservationIgnored private var startRequested = false
    @ObservationIgnored private var videoOutput: AVCaptureVideoDataOutput?
    @ObservationIgnored private var movieOutput: AVCaptureMovieFileOutput?
    @ObservationIgnored private var analyzer: LiveFrameAnalyzer?
    @ObservationIgnored private var analysisEventTask: Task<Void, Never>?
    @ObservationIgnored private var captureWatchdogTask: Task<Void, Never>?
    @ObservationIgnored private var exercise: ExerciseCounter.Exercise = .pullUp
    @ObservationIgnored private var trackingSide: ArmMeasurement.Side = .left
    @ObservationIgnored private var latestFrame: ProcessedFrame?
    @ObservationIgnored private var setupGeneration: UInt64 = 0
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
    @ObservationIgnored private var debugCaptureSessionJSON: String?
    @ObservationIgnored private var debugCaptureQualificationJSON: String?

    var isCameraReady: Bool { state == .ready }
    var isSuspended: Bool { suspended }

    var barRole: ConfirmedBar.Role {
        exercise == .pullUp
            ? .pullUpGrip
            : (trackingSide == .left ? .leftDipRail : .rightDipRail)
    }

    var currentBar: ConfirmedBar? {
        guard let bar,
              bar.role == barRole,
              bar.imageSize == latestFrame?.pose.imageSize
        else { return nil }
        return bar
    }

    var latestImageSize: ImageSize? { latestFrame?.pose.imageSize }

    var completedDebugCapture: (videoURL: URL, sessionJSON: String, qualificationJSON: String)? {
        guard debugCaptureState == .ready,
              let videoURL = debugCaptureURL,
              let sessionJSON = debugCaptureSessionJSON,
              let qualificationJSON = debugCaptureQualificationJSON
        else { return nil }
        return (videoURL, sessionJSON, qualificationJSON)
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
        guard isCameraReady,
              let movieOutput,
              !movieOutput.isRecording,
              !debugCaptureState.isActive
        else { return false }

        discardCompletedDebugCapture()
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("HangInThere-debug-\(UUID().uuidString)")
            .appendingPathExtension("mov")
        try? FileManager.default.removeItem(at: url)

        debugCaptureURL = url
        debugCaptureBarSnapshot = currentBar
        debugCaptureStartedUptimeSeconds = nil
        debugCaptureFinishedUptimeSeconds = nil
        debugCaptureSessionJSON = nil
        debugCaptureQualificationJSON = nil
        debugCaptureState = .starting
        movieOutput.startRecording(to: url, recordingDelegate: self)
        return true
    }

    func stopDebugCapture() {
        guard let movieOutput, movieOutput.isRecording else { return }
        debugCaptureState = .stopping
        movieOutput.stopRecording()
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
        debugCaptureSessionJSON = nil
        debugCaptureQualificationJSON = nil
        debugCaptureState = movieOutput == nil ? .unavailable : .idle
    }

    private func finishDebugCaptureIfNeeded() {
        if movieOutput?.isRecording == true {
            stopDebugCapture()
        }
    }

    private func makeDebugSessionMetadataJSON() -> String {
        var payload: [String: Any] = [
            "schemaVersion": 1,
            "scope": "local developer qualification capture; movement only, not form qualification",
            "capture": [
                "backend": "AVCaptureMovieFileOutput",
                "includesSetup": true,
                "audioRecorded": false,
                "durationSeconds": max(
                    0,
                    (debugCaptureFinishedUptimeSeconds ?? ProcessInfo.processInfo.systemUptime)
                        - (debugCaptureStartedUptimeSeconds ?? ProcessInfo.processInfo.systemUptime)
                )
            ],
            "counterPolicyVersion": ExerciseCounter.policyVersion,
            "exercise": liveSet.exercise.rawValue,
            "side": liveSet.side.rawValue,
            "set": [
                "phase": liveSet.phase.rawValue,
                "endReason": liveSet.endReason?.rawValue as Any,
                "observedMovements": liveSet.observedMovements,
                "partialAttempts": liveSet.partialAttempts,
                "interruptedAttempts": liveSet.interruptedAttempts,
                "analyzedFrames": liveSet.analyzedFrameCount,
                "usableTrackingFrames": liveSet.usableTrackingFrameCount,
                "trackingCoverage": liveSet.trackingCoverage as Any,
                "durationSeconds": liveSet.durationSeconds,
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
        guard liveSet.phase != .running else { return }
        let changed = self.exercise != exercise || trackingSide != side
        self.exercise = exercise
        trackingSide = side
        if changed {
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
            liveSet.reset(exercise: exercise, side: side)
        }
        if let latestFrame {
            framing = LiveFramingAssessment(pose: latestFrame.pose, side: side)
        } else {
            framing = LiveFramingAssessment()
        }
    }

    func beginBarSetup() -> BarSetupFrame? {
        guard liveSet.phase != .running,
              isCameraReady,
              framing.state.isReady,
              let latestFrame,
              let motionSample = currentPhoneMotionSample(),
              let sceneReference = VisionStaticSceneRegistrationWorker.makeReference(
                image: latestFrame.image
              )
        else { return nil }

        let setup = BarSetupFrame(
            frame: latestFrame,
            role: barRole,
            generation: setupGeneration
        )
        pendingBarMotionSample = (setup.id, motionSample)
        pendingBarSceneReference = (setup.id, sceneReference)
        return setup
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
    func startSet() -> Bool {
        guard setupReadiness.state.isReady,
              liveSet.phase != .running
        else { return false }

        liveSet.start(exercise: exercise, side: trackingSide)
        // The bounded analysis stream may already contain one pre-tap event.
        // Drop exactly the next delivered frame so a new set cannot begin from
        // pixels captured before the user pressed Start.
        discardNextSetFrame = true
        return true
    }

    func stopSet() {
        discardNextSetFrame = false
        liveSet.finish()
        finishDebugCaptureIfNeeded()
    }

    func prepareNextSet() {
        finishDebugCaptureIfNeeded()
        discardNextSetFrame = false
        liveSet.prepareNextSet()
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
        guard startRequested, suspended else { return }

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
        } catch LiveCameraSetupError.noRearCamera {
            state = .unavailable
            startRequested = false
            return
        } catch {
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
        }
        finishDebugCaptureIfNeeded()
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
        analysisEventTask?.cancel()
        analysisEventTask = nil
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
        guard !configured else { return }
        guard let device = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .back
        ) else {
            throw LiveCameraSetupError.noRearCamera
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
               movieConnection.isVideoRotationAngleSupported(90) {
                movieConnection.videoRotationAngle = 90
                self.movieOutput = movieOutput
                debugCaptureState = .idle
            } else {
                session.removeOutput(movieOutput)
                debugCaptureState = .unavailable
            }
        } else {
            debugCaptureState = .unavailable
        }

        guard let connection = output.connection(with: .video),
              connection.isVideoRotationAngleSupported(90)
        else {
            session.removeOutput(output)
            session.removeInput(input)
            throw LiveCameraSetupError.unsupportedPortraitRotation
        }
        connection.videoRotationAngle = 90

        let eventStream = AsyncStream<LiveAnalyzerEvent>(
            bufferingPolicy: .bufferingNewest(1)
        ) { continuation in
            let analyzer = LiveFrameAnalyzer { event in
                continuation.yield(event)
            }
            self.analyzer = analyzer
            output.setSampleBufferDelegate(analyzer, queue: analysisQueue)
        }

        analysisEventTask = Task { @MainActor [weak self] in
            for await event in eventStream {
                guard !Task.isCancelled else { return }
                self?.accept(event)
            }
        }

        videoOutput = output
        configured = true
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
        guard startRequested, !suspended else { return }

        switch event {
        case .frame(let frame):
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
                }
            }
            latestFrame = frame
            analyzedFrames += 1
            lastProcessingMilliseconds = frame.processingMilliseconds
            framing = LiveFramingAssessment(pose: frame.pose, side: trackingSide)
            maybeCheckSceneTranslation(frame)
            recordQualificationSample()

            if liveSet.phase == .running {
                if discardNextSetFrame {
                    discardNextSetFrame = false
                } else if let referenceEdge = currentBar?.referenceEdge {
                    liveSet.consume(frame.pose, referenceEdge: referenceEdge)
                } else {
                    liveSet.interruptAndFinish(
                        reason: "barReferenceUnavailable",
                        endReason: .setupInvalidated
                    )
                }
            }

        case .dropped:
            droppedFrames += 1

        case .failed:
            analysisFailures += 1
            latestFrame = nil
            lastProcessingMilliseconds = nil
            framing = LiveFramingAssessment(state: .analysisUnavailable)
            liveSet.interrupt(reason: "inferenceFailure")
        }
    }
}

struct LiveCameraPreviewSurface: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.previewLayer.videoGravity = .resizeAspectFill
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
        guard let connection = previewLayer.connection,
              connection.isVideoRotationAngleSupported(90) else { return }
        connection.videoRotationAngle = 90
    }
}
#endif
