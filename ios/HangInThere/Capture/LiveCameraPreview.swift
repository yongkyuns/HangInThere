#if os(iOS)
import AVFoundation
import CoreImage
import CoreMotion
import Observation
import SwiftUI
import UIKit
import class WorkoutCore.WorkoutGripCaptureBridge
import class WorkoutCore.BilateralTorsoDipMotion
import class WorkoutCore.RelativeDipWorkout
import class WorkoutCore.CameraAcquisitionEvidence
import class WorkoutCore.BodyAthleteSelection
import class WorkoutCore.BodyAthleteAssociation
import class WorkoutCore.OverheadPullUpMotion
import class WorkoutCore.OverheadPullUpWorkout
import class WorkoutCore.WorkoutLifecycle
import class WorkoutCore.SideViewPushUpWorkoutBridge

struct PushUpPracticeSnapshot: Decodable, Sendable {
    struct Rep: Decodable, Sendable { let returnedAt: Double }
    struct SetSummary: Decodable, Sendable { let reps: [Rep] }
    let title: String
    let setCycles: Int
    let setStarted: Bool
    let progress: Double?
    let targetReached: Bool
    let observationUsable: Bool
    let endedSet: SetSummary?
}

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
    static func apply(to connection: AVCaptureConnection, rotationAngle: Double = 90) throws {
        guard connection.isVideoRotationAngleSupported(rotationAngle) else {
            throw LiveCameraSetupError.unsupportedPortraitRotation
        }
        connection.videoRotationAngle = rotationAngle
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
            "This camera cannot provide the frame orientation required by live setup."
        }
    }
}

struct PhoneMotionSample: Sendable {
    let attitude: PhoneOrientationStability.Quaternion
    let timestamp: Double
    var gravity: SIMD3<Double>? = nil
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
    private let setVideoRecorder: CountedSetVideoRecorder

    init(
        estimator: any PoseEstimator = VisionPoseEstimator(),
        gripEnabled: Bool = true,
        setVideoRecorder: CountedSetVideoRecorder,
        publish: @escaping @Sendable (LiveAnalyzerEvent) -> Void
    ) {
        self.estimator = estimator
        self.setVideoRecorder = setVideoRecorder
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
        setVideoRecorder.append(sampleBuffer)
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
    private(set) var cameraPlacement = LiveCameraPlacement()
    private var armVisibility = LiveArmVisibilityFeedback()
    var needsArmRepositioning: Bool {
        isCameraReady && !suspended && readinessFrameFresh && liveSet.phase == .running
            && armVisibility.needsRepositioning
    }
    var framingHint: String? {
        liveSet.phase == .idle ? workoutAnalysis.framingHint : nil
    }
    private(set) var analyzedFrames = 0
    private(set) var droppedFrames = 0
    private(set) var analysisFailures = 0
    private(set) var lastProcessingMilliseconds: Double?
    private(set) var lastSceneRegistrationMilliseconds: Double?
    private(set) var sceneRegistrationFailures = 0
    private(set) var bar: ConfirmedBar? {
        didSet {
            if oldValue != bar { resetFramingFeedback() }
            if bar == nil { resetSetupRecovery() }
        }
    }
    private(set) var automaticBarState: AutomaticPullUpBar.State = .lookingForGrip
    private(set) var exerciseSelection: WorkoutExerciseSelection = .automatic
    private var automaticExercise = AutomaticExerciseDetection()
    private var recoveringAutomaticSetup = false
    private(set) var handsFreeStartEnabled = true
    private(set) var handsFreeStart = HandsFreeSetStart()
    private(set) var liveSet = LiveSetSession()
    private let workoutAnalysis = WorkoutAnalysisAdapter()
    @ObservationIgnored private let dipAthleteSelection: BodyAthleteSelection
    let isPushUpPractice: Bool
    @ObservationIgnored private var pushUpBridge: SideViewPushUpWorkoutBridge?
    private(set) var pushUpSnapshot: PushUpPracticeSnapshot?
    private(set) var lastPushUpSetCount = 0
    private(set) var pushUpVideoRotationAngle: Double = 90
    private var bodyCameraIdle: Bool { isPushUpPractice ? pushUpSnapshot?.setStarted != true : liveSet.phase == .idle }
    private(set) var dipAthleteDecision: BodyAthleteSelectionDecision?
    var usesBodyDipTracking: Bool { exercise == .dip }
    var usesBodyPullUpTracking: Bool { !isPushUpPractice && exercise == .pullUp }
    var usesBodyMotionTracking: Bool { isPushUpPractice || usesBodyDipTracking || usesBodyPullUpTracking }
    var bodyDipPreparation: BodyDipWorkoutSnapshot? { workoutAnalysis.bodyDipSnapshot }
    var bodyPullUpPreparation: BodyPullUpWorkoutSnapshot? { workoutAnalysis.bodyPullUpSnapshot }
    private struct BodyCameraCalibration {
        let size: ImageSize
        let backend: String
        let revision: Int
        let sourceTime: PresentationTime
    }
    @ObservationIgnored private let bodyCameraEvidence = CameraAcquisitionEvidence()
    private(set) var bodyCameraReacquisitions = 0
    var bodyCameraIndependentObservations: Int { Int(bodyCameraEvidence.independentObservations) }
    var bodyCameraReferenceSeconds: Double? { hasBodyCameraCalibration ? bodyCameraCalibration?.sourceTime.seconds : nil }
    private var bodyCameraCalibration: BodyCameraCalibration?
    var hasBodyCameraCalibration: Bool { bodyCameraCalibration != nil && sceneReference != nil }
    var hasBodyDipCameraCalibration: Bool { usesBodyDipTracking && hasBodyCameraCalibration }
    private var hasCameraCalibration: Bool { bar != nil || hasBodyCameraCalibration }
    private var liveCountMethod: String? { exercise == .dip ? BilateralTorsoDipMotion.companion.countMethod : OverheadPullUpMotion.companion.countMethod }
    private var liveBoundaryPolicyVersion: Int { exercise == .dip ? Int(RelativeDipWorkout.companion.boundaryPolicyVersion) : Int(OverheadPullUpWorkout.companion.boundaryPolicyVersion) }
    private var liveCountPolicyVersion: Int { exercise == .dip ? Int(BilateralTorsoDipMotion.companion.policyVersion) : Int(OverheadPullUpMotion.companion.policyVersion) }
    private var liveStartingEvidencePolicyVersion: Int { usesBodyDipTracking || usesBodyPullUpTracking ? 1 : WorkoutAnalysisAdapter.startingEvidencePolicyVersion }
    private(set) var latestCompletedWorkout: WorkoutSetRecord?
    private(set) var latestAfterSetReport: WorkoutAfterSetReport?
    private(set) var workoutHistory: [WorkoutSetRecord] = []
    private(set) var workoutStorageError: String?
    var repAnalysis: [WorkoutRepRecord] { workoutAnalysis.reps }
    var gripLabel: String? { liveSet.phase == .running ? workoutAnalysis.gripLabel : nil }
    var gripViewPrompt: String? {
        guard resolvedExercise == .pullUp, isCameraReady, !suspended, liveSet.phase != .finished else { return nil }
        return WorkoutGripViewFeedback(pose: latestPose).prompt
    }
    @ObservationIgnored private let gripCaptureBridge = WorkoutGripCaptureBridge()
    private var latestGripCaptureFeedback = WorkoutGripCaptureFeedback(state: "checking")
    var gripCaptureFeedback: WorkoutGripCaptureFeedback? {
        guard exercise == .pullUp, isCameraReady, !suspended, liveSet.phase != .finished else { return nil }
        guard readinessFrameFresh else { return WorkoutGripCaptureFeedback(state: "checking") }
        return latestGripCaptureFeedback
    }
    var activeWorkoutRecord: WorkoutSetRecord? { liveSet.phase == .running ? workoutAnalysis.currentRecord : nil }
    // The legacy counter records acceptance at the top. Live rep totals use
    // the same completed-return eligibility as saved history and results.
    var completedRepCount: Int {
        if liveSet.phase == .running { return workoutAnalysis.currentRecord?.completedCount ?? 0 }
        return liveSet.phase == .finished ? latestCompletedWorkout?.completedCount ?? 0 : 0
    }
    func workoutRecordForReview() -> WorkoutSetRecord? { liveSet.phase == .running ? workoutAnalysis.reviewRecord() : nil }
    var showsSetStarted: Bool { workoutAnalysis.showsSetStarted }
    private var archivedSetId: String?
    @ObservationIgnored private var workoutWriteTask: Task<Void, Never>?
    private var qualifiedStartPose: PoseResult?
    private var lastAnalyzedFrameUptime: Double?
    private(set) var readinessFrameFresh = false
    private(set) var phoneOrientation = PhoneOrientationStability()
    private(set) var motionSampleAvailable = false
    private(set) var sceneTranslation = StaticSceneStability()
    private(set) var qualification = LiveDeviceQualificationRecorder()
    private(set) var debugCaptureState: LiveDebugCaptureState = .unavailable
    private(set) var recordsSetVideos = UserDefaults.standard.object(forKey: "recordCountedSets") == nil || UserDefaults.standard.bool(forKey: "recordCountedSets")
    private(set) var recordingVideoSetId: String?
    private(set) var workoutVideoError: String?
    @ObservationIgnored private let setVideoRecorder = CountedSetVideoRecorder()

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
    @ObservationIgnored private let sceneRegistrationWorker: any StaticSceneRegistering
    @ObservationIgnored private let phoneMotionProvider: (@MainActor () -> PhoneMotionSample?)?
    @ObservationIgnored private var lastPhoneMotionTimestamp: Double?
    @ObservationIgnored private var lastPhoneMotionReceiptUptime: Double?
    #if DEBUG
    private var evaluationInput = false
    private var evaluationAcquisitionEvents: [[String: Any]] = []
    #endif
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

    init(sceneRegistrationWorker: any StaticSceneRegistering = VisionStaticSceneRegistrationWorker(),
         phoneMotionProvider: (@MainActor () -> PhoneMotionSample?)? = nil,
         pushUpPractice: Bool = false) {
        self.sceneRegistrationWorker = sceneRegistrationWorker
        self.phoneMotionProvider = phoneMotionProvider
        self.isPushUpPractice = pushUpPractice
        self.pushUpBridge = nil
        self.dipAthleteSelection = BodyAthleteSelection(profile: pushUpPractice ? .sideview : .upright)
        super.init()
    }

    func finishPushUpPractice(reason: String = "manual") {
        guard let pushUpBridge else { return }
        pushUpBridge.finish(reason: reason)
        collectPushUpWrites()
        pushUpSnapshot = nil
    }

    func setPushUpOrientation(_ orientation: UIInterfaceOrientation) {
        guard isPushUpPractice else { return }
        let angle: Double = switch orientation {
        case .landscapeRight: 0
        case .landscapeLeft: 180
        case .portraitUpsideDown: 270
        default: 90
        }
        guard angle != pushUpVideoRotationAngle else { return }
        finishPushUpPractice(reason: "orientationChanged")
        clearBar()
        pushUpVideoRotationAngle = angle
        for output in [videoOutput?.connection(with: .video), movieOutput?.connection(with: .video)].compactMap({ $0 }) {
            try? LiveCameraConnectionConfiguration.apply(to: output, rotationAngle: angle)
        }
    }

    private func consumePushUpPractice(_ pose: PoseResult) {
        if pushUpBridge == nil {
            pushUpBridge = SideViewPushUpWorkoutBridge(sessionId: UUID().uuidString,
                trackedAtEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000))
        }
        guard let pushUpBridge else { return }
        let qualified = cameraPlacement.isUsable && hasBodyCameraCalibration && currentPhoneMotionSample() != nil &&
            phoneOrientation.state.allowsLiveSet && sceneIsReady
        let moved = phoneOrientation.state == .moved || sceneTranslation.state == .moved
        do {
            let json = try pushUpBridge.observeJson(poseJson: String(decoding: JSONEncoder().encode(pose), as: UTF8.self),
                camera: moved ? "moved" : qualified ? "ready" : "unavailable")
            let snapshot = try JSONDecoder().decode(PushUpPracticeSnapshot.self, from: Data(json.utf8))
            pushUpSnapshot = snapshot
            if let ended = snapshot.endedSet { lastPushUpSetCount = ended.reps.count }
            collectPushUpWrites()
        } catch {
            finishPushUpPractice(reason: "analysisFailure")
        }
    }

    private func collectPushUpWrites() {
        struct Write: Decodable { let record: WorkoutSetRecord; let finalized: Bool }
        guard let pushUpBridge else { return }
        while let json = pushUpBridge.nextWriteJson() {
            do {
                let write = try JSONDecoder().decode(Write.self, from: Data(json.utf8))
                if write.finalized {
                    lastPushUpSetCount = write.record.completedCount
                    latestCompletedWorkout = write.record
                    workoutHistory.insert(write.record, at: 0)
                    workoutHistory = Array(workoutHistory.prefix(50))
                }
                persistWorkout(write.record, finalized: write.finalized)
                pushUpBridge.acknowledgeWrite()
            } catch {
                workoutStorageError = "Push-up summary unavailable: \(error.localizedDescription)"
                break // Keep the pending write; never silently discard evidence.
            }
        }
    }

    #if DEBUG
    /// Authored controller tests only: no camera authorization, recording, disk
    /// publication or speech. Production frame delivery still calls accept.
    func beginEvaluationInput() {
        precondition(!session.isRunning)
        evaluationInput = true; startRequested = true; state = .ready
    }
    func acceptEvaluationFrame(_ frame: ProcessedFrame) {
        precondition(evaluationInput)
        pollPhoneOrientation()
        accept(.frame(frame))
    }
    func settleEvaluationInput(waitForAcquisition: Bool = false) async {
        if waitForAcquisition { await automaticBarTask?.value }
        await sceneRegistrationTask?.value
    }
    /// Read-only policy diagnostics plus drained test-only events. No evidence
    /// is supplied to acquisition, readiness, camera qualification or counting.
    func takeEvaluationSetupDiagnostics() -> [String: Any] {
        precondition(evaluationInput)
        let diagnostic = automaticBarPolicy.referenceStabilityDiagnostic
        let result: [String: Any] = [
            "configuredExercise": exercise.rawValue,
            "apparatusCandidate": automaticExercise.apparatusCandidate?.rawValue ?? "unknown",
            "exerciseIdentityResolved": resolvedExercise != nil,
            "observedBarConfirmed": bar.map { $0.role == barRole && $0.imageSize == latestFrame?.pose.imageSize } ?? false,
            "recoveringSetup": recoveringAutomaticSetup,
            "framing": framing.state.rawValue,
            "startingPose": latestPose.map { hasStartingPoseEvidence($0) } ?? false,
            "acquisitionState": String(describing: automaticBarState),
            "barSelectionPolicyVersion": AutomaticPullUpBar.selectionPolicyVersion,
            "barGripAssessmentPolicyVersion": AutomaticPullUpBar.gripAssessmentVersion,
            "barEdgeExtractionPolicyVersion": VisionBarDetector.extractionPolicyVersion,
            "barEdgePixelBackendVersion": VisionBarDetector.pixelBackendVersion,
            "exerciseIdentityPolicyVersion": AutomaticExerciseDetection.identityPolicyVersion,
            "bodyAthleteAssociationPolicyVersion": Int(BodyAthleteAssociation.companion.version),
            "sceneConsensusPolicyVersion": StaticSceneStability.policyVersion,
            "sceneRegistrationMeasurementPolicyVersion": VisionStaticSceneRegistrationWorker.measurementPolicyVersion,
            "cameraAcquisitionPolicyVersion": Int(CameraAcquisitionEvidence.companion.policyVersion),
            "bodyCameraIndependentObservations": bodyCameraIndependentObservations,
            "bodyCameraReacquisitions": bodyCameraReacquisitions,
            "bodyCameraReferenceSeconds": bodyCameraReferenceSeconds as Any? ?? NSNull(),
            "startingEvidencePolicyVersion": liveStartingEvidencePolicyVersion,
            "startingEvidenceMethod": liveCountMethod ?? "legacyStartingPose",
            "capturePlacementPolicyVersion": LiveCameraPlacement.policyVersion,
            "cameraTiltDegrees": cameraPlacement.tiltDegrees as Any? ?? NSNull(),
            "cameraPlacementUsable": cameraPlacement.isUsable,
            "acquisitionPending": automaticBarTask != nil,
            "setupGeneration": setupGeneration,
            "stabilityStatus": diagnostic?.status.rawValue as Any? ?? NSNull(),
            "stableSeconds": diagnostic?.stableSeconds as Any? ?? NSNull(),
            "stabilityObservations": diagnostic?.observations as Any? ?? NSNull(),
            "events": evaluationAcquisitionEvents
        ]
        evaluationAcquisitionEvents.removeAll(keepingCapacity: true)
        return result
    }
    private func recordEvaluationAcquisition(_ stage: String, seconds: Double,
                                             counts: [Int] = [], candidates: [[BarLineCandidate]] = []) {
        guard evaluationInput else { return }
        evaluationAcquisitionEvents.append([
            "stage": stage, "sourceSeconds": seconds,
            "latestSourceSeconds": latestPose?.timestamp.seconds as Any? ?? NSNull(),
            "generation": setupGeneration, "candidateCounts": counts,
            "state": String(describing: automaticBarState),
            "candidateEdges": candidates.map { batch in batch.map { candidate in
                [candidate.edge.a.x, candidate.edge.a.y, candidate.edge.b.x, candidate.edge.b.y]
            } }
        ])
    }
    func endEvaluationInput() {
        precondition(evaluationInput)
        dipAthleteSelection.reset()
        dipAthleteDecision = nil
        workoutAnalysis.interrupt(reason: "evaluationEnded")
        setupGeneration &+= 1
        automaticBarTask?.cancel(); automaticBarTask = nil
        startRequested = false; sceneRegistrationTask?.cancel(); sceneRegistrationTask = nil; state = .idle
    }
    #endif

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
            && pushUpSnapshot?.setStarted != true
            && !isSwitchingCamera && !suspended
            && state != .starting && state != .requestingPermission
    }

    @discardableResult
    func selectCamera(_ position: LiveCameraPosition) async -> Bool {
        guard position != cameraPosition else { return true }
        guard canSwitchCamera else { return false }
        finishPushUpPractice(reason: "cameraChanged")
        dipAthleteSelection.reset()
        dipAthleteDecision = nil
        cameraPosition = position
        automaticBarPolicy.reset()
        automaticExercise.reset()
        clearBar()
        latestFrame = nil
        framing = LiveFramingAssessment()
        resetFramingFeedback()

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
            counterPolicyVersion: liveCountPolicyVersion,
            exercise: liveSet.exercise.rawValue,
            side: liveSet.side.rawValue,
            observedMovements: liveSet.observedMovements,
            partialAttempts: liveSet.partialAttempts,
            interruptedAttempts: liveSet.interruptedAttempts,
            setAnalyzedFrames: liveSet.analyzedFrameCount,
            setUsableTrackingFrames: liveSet.usableTrackingFrameCount,
            trackingCoverage: liveSet.trackingCoverage,
            setPhase: liveSet.phase.rawValue,
            setEndReason: liveSet.endReason?.rawValue,
            countMethod: liveCountMethod,
            boundaryPolicyVersion: liveBoundaryPolicyVersion,
            completedReps: completedRepCount
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
            "componentPolicies": [
                "barEdgeExtraction": VisionBarDetector.extractionPolicyVersion,
                "barEdgePixelBackend": VisionBarDetector.pixelBackendVersion,
                "barSelection": AutomaticPullUpBar.selectionPolicyVersion,
                "exerciseIdentity": AutomaticExerciseDetection.identityPolicyVersion,
                "bodyAthleteAssociation": Int(BodyAthleteAssociation.companion.version),
                "sceneConsensus": StaticSceneStability.policyVersion,
                "cameraAcquisition": Int(CameraAcquisitionEvidence.companion.policyVersion),
                "startingEvidence": liveStartingEvidencePolicyVersion
            ],
            "captureProfile": [
                "placementPolicyVersion": LiveCameraPlacement.policyVersion,
                "maximumTiltDegrees": LiveCameraPlacement.maximumTiltDegrees,
                "placementInstruction": cameraPlacement.instruction,
                "heightAutomaticallyMeasured": false,
                "snapshotTiltDegrees": cameraPlacement.tiltDegrees as Any? ?? NSNull()
            ],
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
            "counterPolicyVersion": liveCountPolicyVersion,
            "countMethod": (liveCountMethod as Any?) ?? NSNull(),
            "boundaryPolicyVersion": liveBoundaryPolicyVersion,
            "armTracking": exercise == .dip ? "bilateralBodyArms" : liveSet.counter.armTracking,
            "exercise": liveSet.exercise.rawValue,
            "side": liveSet.side.rawValue,
            "set": [
                "phase": liveSet.phase.rawValue,
                "endReason": (liveSet.endReason?.rawValue as Any?) ?? NSNull(),
                "observedMovements": liveSet.observedMovements,
                "completedReps": completedRepCount,
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

    var sceneIsReady: Bool {
        sceneTranslation.isReady(at: latestFrame?.pose.timestamp.seconds) &&
            (!hasBodyCameraCalibration || bodyCameraEvidence.hasIndependentEvidence)
    }

    var setupReadiness: LiveSetupReadiness {
        LiveSetupReadiness(
            cameraReady: isCameraReady,
            framing: framing.state,
            barConfirmed: currentBar != nil,
            phoneStable: phoneOrientation.state.allowsLiveSet,
            sceneStable: sceneIsReady,
            bodyCameraCalibrated: usesBodyMotionTracking ? (hasBodyCameraCalibration || currentBar != nil) : nil,
            startingPose: latestPose.map { hasStartingPoseEvidence($0) } ?? false,
            placementReady: cameraPlacement.isUsable,
            identityResolved: resolvedExercise != nil
        )
    }

    var readinessChecklist: LiveReadinessChecklist {
        let arms = latestPose.map { PullUpArmMeasurement(pose: $0).arms } ?? []
        let armDescription = arms.count == 2 ? "Both measurable" : arms.first.map {
            $0.side == .left ? "Left measurable" : "Right measurable"
        } ?? "No measurable arms"
        return LiveReadinessChecklist(cameraReady: isCameraReady && !suspended,
            frameFresh: readinessFrameFresh && latestPose != nil,
            framing: framing, exerciseTitle: resolvedExercise?.title, armDescription: armDescription,
            barConfirmed: bar.map { $0.role == barRole && $0.imageSize == latestFrame?.pose.imageSize } ?? false,
            acquisition: automaticBarState,
            stability: automaticBarPolicy.referenceStabilityDiagnostic,
            motionAvailable: motionSampleAvailable, phoneStable: phoneOrientation.state.allowsLiveSet,
            sceneStable: sceneIsReady,
            startingPose: latestPose.map { hasStartingPoseEvidence($0) } ?? false,
            paused: workoutDetailsPresented, handsFree: handsFreeStartEnabled,
            setupBusy: pendingBarMotionSample != nil || !debugCaptureAllowsBarSetup,
            bodyCameraCalibrated: usesBodyMotionTracking ? (hasBodyCameraCalibration || currentBar != nil) : nil,
            placement: cameraPlacement, pullUpMotion: usesBodyPullUpTracking)
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

    private func applyWorkout(exercise: ExerciseCounter.Exercise, side: ArmMeasurement.Side,
                              preserveBodyCamera: Bool = false) {
        self.exercise = exercise
        trackingSide = side
        if preserveBodyCamera {
            // Pose identity/preparation and independently observed camera
            // calibration have separate lifetimes. Do not replace the scene
            // reference merely because the selected body is briefly absent.
            resetFramingFeedback()
            resetAutomaticBarSetup()
            // The source-owned dip selector/counter handles actual observation
            // loss. Static exercise search losing its candidate is not a new
            // athlete, camera epoch or reason to erase a measured trajectory.
        } else { clearBar() }
        liveSet.reset(exercise: exercise, side: side)
        updateFraming(latestFrame?.pose)
    }

    private func resetFramingFeedback() {
        armVisibility.reset()
        workoutAnalysis.resetFramingFeedback()
        gripCaptureBridge.reset()
        latestGripCaptureFeedback = WorkoutGripCaptureFeedback(state: "checking")
    }

    private func updateFraming(_ pose: PoseResult?) {
        guard let pose else {
            framing = LiveFramingAssessment()
            resetFramingFeedback()
            return
        }
        framing = LiveFramingAssessment(pose: pose, side: trackingSide,
            usesBothArms: exerciseSelection == .automatic || exercise == .pullUp,
            requiresBothArms: usesBodyMotionTracking)
        if let input = try? JSONEncoder().encode(pose),
           let raw = try? gripCaptureBridge.observeJson(poseJson: String(decoding: input, as: UTF8.self)),
           let assessment = try? JSONDecoder().decode(WorkoutGripCaptureFeedback.self, from: Data(raw.utf8)) {
            latestGripCaptureFeedback = assessment
        } else {
            latestGripCaptureFeedback = WorkoutGripCaptureFeedback(state: "unavailable")
        }
        if usesBodyMotionTracking {
            workoutAnalysis.resetFramingFeedback()
            return
        }
        _ = workoutAnalysis.observeSetupFraming(pose, idle: liveSet.phase == .idle,
            barConfirmed: bar != nil, automatic: exerciseSelection == .automatic, pullUp: exercise == .pullUp,
            side: trackingSide, acquisitionState: automaticBarState,
            referenceStability: automaticBarPolicy.referenceStabilityDiagnostic)
    }

    // Also exercised without starting capture in controller regression tests.
    // No automatic identity changes are allowed during a set or debug recording.
    func observeExerciseSelection(_ pose: PoseResult) {
        recoveringAutomaticSetup = false
        guard exerciseSelection == .automatic, liveSet.phase == .idle,
              !debugCaptureState.isActive, pendingBarMotionSample == nil else { return }
        let previous = automaticExercise.exercise
        let previousApparatus = automaticExercise.apparatusCandidate
        if workoutAnalysis.retainSetup(pose, exercise: previous) {
            recoveringAutomaticSetup = true
            resetHandsFreeStart()
            return
        }
        let next = automaticExercise.observe(pose,
            reference:bar?.role == .pullUpGrip ? bar?.referenceEdge : nil)
        // A selected athlete with two measured arms below the shoulders may
        // prepare the rail-free owner without ever meeting rail-search width.
        // This selects a private probe only: identity/start still requires the
        // owner's corroborated full trajectory and independently ready camera.
        let arms = PullUpArmMeasurement(pose:pose).arms
        let mayPrepareDip = next == nil && automaticExercise.apparatusCandidate == nil &&
            dipAthleteDecision?.status == "associated" && arms.count == 2 &&
            arms.allSatisfy { $0.wrist.y > $0.shoulder.y }
        let target = automaticExercise.apparatusCandidate ?? (mayPrepareDip ? .dip : exercise)
        if previousApparatus != automaticExercise.apparatusCandidate || (previous != nil && next == nil) || target != exercise {
            let sameCameraContext = bodyCameraCalibration.map {
                $0.size == pose.imageSize && $0.backend == pose.backend && $0.revision == pose.requestRevision
            } == true
            let retainCamera = target == exercise && bar == nil &&
                hasBodyCameraCalibration && sameCameraContext && cameraPlacement.isUsable &&
                currentPhoneMotionSample() != nil && phoneOrientation.state != .moved && sceneTranslation.state != .moved
            applyWorkout(exercise: target, side: trackingSide, preserveBodyCamera: retainCamera)
        }
    }

    func beginBarSetup() -> BarSetupFrame? {
        guard liveSet.phase != .running,
              resolvedExercise != nil,
              isCameraReady,
              cameraPlacement.isUsable,
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
        resetSetupRecovery()
        analyzer?.resetGripInference()
        resetHandsFreeStart()
        automaticBarTask?.cancel()
        automaticBarTask = nil
        lastAutomaticBarSeconds = nil
        resetAutomaticBarObservation()
    }

    private func resetSetupRecovery() {
        recoveringAutomaticSetup = false
        workoutAnalysis.resetSetupRecovery()
    }

    private func resetAutomaticBarObservation(_ state: AutomaticPullUpBar.State = .lookingForGrip) {
        automaticBarPolicy.invalidateEvidence()
        automaticBarState = state
        workoutAnalysis.resetAcquisitionFramingFeedback()
    }

    func setHandsFreeStartEnabled(_ enabled: Bool) {
        guard liveSet.phase == .idle else { return }
        handsFreeStartEnabled = enabled
        workoutAnalysis.resetBodyDipPreparation()
        workoutAnalysis.resetBodyPullUpPreparation()
        resetHandsFreeStart()
    }

    func setWorkoutDetailsPresented(_ presented: Bool) {
        workoutDetailsPresented = presented
        if liveSet.phase == .idle { workoutAnalysis.resetBodyDipPreparation() }
        if liveSet.phase == .idle { workoutAnalysis.resetBodyPullUpPreparation() }
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
        #if DEBUG
        guard !evaluationInput else { return }
        #endif
        countdownSpeech.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
        countdownSpeech.speak(utterance)
    }

    private func updateHandsFreeStart(_ frame: ProcessedFrame) {
        guard !usesBodyDipTracking, !usesBodyPullUpTracking, handsFreeStartEnabled, liveSet.phase == .idle else { return }
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
        eligible = eligible && hasStartingPoseEvidence(frame.pose)
        if !eligible || workoutDetailsPresented { qualifiedStartPose = nil }
        if let previous = qualifiedStartPose {
            let gap = frame.pose.timestamp.elapsedSeconds(since: previous.timestamp)
            if !gap.isFinite || gap <= 0 || gap > ExerciseCounter.maximumGapSeconds {
                qualifiedStartPose = nil
            }
        }
        let baseline = qualifiedStartPose
        if eligible && qualifiedStartPose == nil { qualifiedStartPose = frame.pose }
        if observeHandsFreeReadiness(frame.pose,
            eligible: eligible && !workoutDetailsPresented) {
            if startSet(discardQueuedFrame: false), let baseline, let reference = currentBar?.referenceEdge {
                liveSet.consume(baseline, referenceEdge: reference)
                _ = workoutAnalysis.consume(baseline, reference: reference, counter: liveSet.counter,
                    event: nil, automaticFinish: true)
            }
        }
    }

    private func hasStartingPoseEvidence(_ pose: PoseResult, reference: BarSegment? = nil) -> Bool {
        if usesBodyPullUpTracking, reference == nil {
            return workoutAnalysis.bodyPullUpSnapshot.map {
                $0.motion.phase == "ready" && $0.motion.observationUsable && $0.motion.sourceAccepted && $0.motion.seconds == pose.timestamp.seconds
            } ?? false
        }
        if usesBodyDipTracking, reference == nil {
            return workoutAnalysis.bodyDipSnapshot.map {
                $0.motion.phase == "ready" && $0.motion.observationUsable && $0.motion.sourceAccepted && $0.motion.seconds == pose.timestamp.seconds
            } ?? false
        }
        return !recoveringAutomaticSetup && workoutAnalysis.hasStartingPose(pose, exercise: exercise,
            automatic: exerciseSelection == .automatic, side: trackingSide, reference: reference ?? currentBar?.referenceEdge)
    }

    // The final readiness gate is also exercised without camera authorization.
    func observeHandsFreeReadiness(_ pose: PoseResult, eligible: Bool, reference: BarSegment? = nil) -> Bool {
        workoutAnalysis.ready(at: pose.timestamp, eligible: eligible && hasStartingPoseEvidence(pose, reference: reference))
    }

    private func maybeFindAutomaticBar(_ frame: ProcessedFrame) {
        guard !usesBodyMotionTracking, exercise == .pullUp,
              exerciseSelection.exercise != nil || automaticExercise.apparatusCandidate != nil,
              !recoveringAutomaticSetup,
              liveSet.phase == .idle, bar == nil,
              pendingBarMotionSample == nil, automaticBarTask == nil,
              debugCaptureAllowsBarSetup, framing.state.isReady, cameraPlacement.isUsable,
              let motion = currentPhoneMotionSample() else { return }
        let seconds = frame.pose.timestamp.seconds
        guard seconds.isFinite else { return }
        if let lastAutomaticBarSeconds, seconds - lastAutomaticBarSeconds < AutomaticPullUpBar.samplingInterval {
            return
        }
        lastAutomaticBarSeconds = seconds
        guard let grip = AutomaticApparatusReference.search(pose: frame.pose, exercise: exercise) else {
            resetAutomaticBarObservation()
            return
        }
        guard let reference = VisionStaticSceneRegistrationWorker.makeReference(image: frame.image) else { return }
        let token = setupGeneration
        #if DEBUG
        recordEvaluationAcquisition("scheduled", seconds: seconds)
        #endif
        automaticBarTask = Task { @MainActor [weak self] in
            guard let self else { return }
            var candidates: [[BarLineCandidate]] = []
            do {
                for region in grip.regions {
                    candidates.append(try await automaticBarDetector.detect(image: frame.image, region: region,
                        minimumObservedLength: grip.minimumObservedLength))
                }
            } catch {
                #if DEBUG
                self.recordEvaluationAcquisition("detectorError", seconds: seconds)
                #endif
                guard self.setupGeneration == token else { return }
                self.automaticBarTask = nil
                self.resetAutomaticBarObservation(.findingEdge)
                return
            }
            guard self.setupGeneration == token else {
                #if DEBUG
                self.recordEvaluationAcquisition("contextChanged", seconds: seconds, counts: candidates.map(\.count))
                #endif
                return
            }
            self.automaticBarTask = nil
            // The result belongs to the last bilateral frame. During bounded
            // recovery, neither accept it nor erase the earlier observed dwell.
            guard !self.recoveringAutomaticSetup else {
                #if DEBUG
                self.recordEvaluationAcquisition("recoveringSetup", seconds: seconds, counts: candidates.map(\.count))
                #endif
                return
            }
            guard !Task.isCancelled, self.isCameraReady, !self.suspended,
                  self.cameraPlacement.isUsable,
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
                #if DEBUG
                self.recordEvaluationAcquisition("resultRejected", seconds: seconds, counts: candidates.map(\.count))
                #endif
                self.resetAutomaticBarObservation()
                return
            }
            let proposal = self.automaticBarPolicy.observe(pose: frame.pose, exercise: self.exercise, side: self.trackingSide, candidates: candidates)
            self.automaticBarState = self.automaticBarPolicy.state
            #if DEBUG
            self.recordEvaluationAcquisition("observed", seconds: seconds, counts: candidates.map(\.count), candidates: candidates)
            #endif
            if self.automaticBarState != .holding {
                self.workoutAnalysis.resetAcquisitionFramingFeedback()
            }
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
              cameraPlacement.isUsable,
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
        bodyCameraCalibration = nil
        bodyCameraEvidence.reset()
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil
        self.bar = bar
        updateFraming(latestFrame?.pose)
        if debugCaptureState.isActive {
            debugCaptureBarSnapshot = bar
        }
        return true
    }

    func clearBar() {
        guard liveSet.phase != .running else { return }
        finishPushUpPractice(reason: "cameraSetupReset")
        resetFramingFeedback()
        resetAutomaticBarSetup()
        setupGeneration &+= 1
        bar = nil
        pendingBarMotionSample = nil
        pendingBarSceneReference = nil
        sceneReference = nil
        bodyCameraCalibration = nil
        bodyCameraEvidence.reset()
        workoutAnalysis.resetBodyDipPreparation()
        workoutAnalysis.resetBodyPullUpPreparation()
        phoneOrientation.reset()
        sceneTranslation.reset()
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        lastSceneRegistrationSeconds = nil
    }

    @discardableResult
    func startSet(discardQueuedFrame: Bool = true) -> Bool {
        if usesBodyPullUpTracking {
            guard liveSet.phase == .idle, setupReadiness.state.isReady, readinessFrameFresh,
                  let snapshot = workoutAnalysis.bodyPullUpSnapshot else { return false }
            if exerciseSelection == .automatic, !automaticExercise.lock() { return false }
            guard workoutAnalysis.startBodyPullUpManually() else { return false }
            beginBodyPullUpSet(snapshot)
            discardNextSetFrame = discardQueuedFrame
            return true
        }
        if usesBodyDipTracking {
            guard liveSet.phase == .idle, setupReadiness.state.isReady, readinessFrameFresh,
                  let snapshot = workoutAnalysis.bodyDipSnapshot else { return false }
            if exerciseSelection == .automatic, !automaticExercise.lock() { return false }
            guard workoutAnalysis.startBodyDipManually() else { return false }
            beginBodyDipSet(snapshot)
            discardNextSetFrame = discardQueuedFrame
            return true
        }
        guard setupReadiness.state.isReady,
              resolvedExercise != nil,
              liveSet.phase != .running
        else { return false }

        if exerciseSelection == .automatic, !automaticExercise.lock() { return false }
        resetHandsFreeStart()
        workoutAnalysis.start(exercise: exercise)
        archivedSetId = nil
        liveSet.start(exercise: exercise, side: trackingSide)
        beginSetVideoIfNeeded()
        if workoutAnalysis.beginStartFeedback(isRunning: liveSet.phase == .running) {
            speakWorkoutCue(workoutAnalysis.startSpokenMessage)
        }
        // The bounded analysis stream may already contain one pre-start event.
        // Drop exactly the next delivered frame so a new set cannot begin from
        // a queued observation preceding the tap or spoken start cue.
        discardNextSetFrame = discardQueuedFrame
        return true
    }

    private func beginBodyDipSet(_ snapshot: BodyDipWorkoutSnapshot) {
        resetHandsFreeStart()
        archivedSetId = nil
        liveSet.beginBodyDip(snapshot, side: trackingSide, startedAt: workoutAnalysis.currentRecord?.startedAt)
        beginSetVideoIfNeeded()
        if workoutAnalysis.beginStartFeedback(isRunning: true) { speakWorkoutCue(workoutAnalysis.startSpokenMessage) }
        discardNextSetFrame = false
    }

    private func beginBodyPullUpSet(_ snapshot: BodyPullUpWorkoutSnapshot) {
        resetHandsFreeStart(); archivedSetId = nil
        liveSet.beginBodyPullUp(snapshot, side: trackingSide, startedAt: workoutAnalysis.currentRecord?.startedAt,
            completedTimes: workoutAnalysis.reps.filter(\.isCompleteForAnalytics).compactMap(\.returnedAt))
        beginSetVideoIfNeeded()
        if workoutAnalysis.beginStartFeedback(isRunning: true) { speakWorkoutCue(workoutAnalysis.startSpokenMessage) }
        discardNextSetFrame = false
    }

    private func consumeBodyPullUpFrame(_ frame: ProcessedFrame) {
        guard usesBodyPullUpTracking, liveSet.phase != .finished else { return }
        if liveSet.phase == .running, discardNextSetFrame { discardNextSetFrame = false; return }
        let qualified = hasCameraCalibration && cameraPlacement.isUsable && currentPhoneMotionSample() != nil &&
            phoneOrientation.state.allowsLiveSet && sceneIsReady
        let moved = phoneOrientation.state == .moved || sceneTranslation.state == .moved
        #if DEBUG
        let recordCalendarTime = !evaluationInput
        #else
        let recordCalendarTime = true
        #endif
        guard let snapshot = workoutAnalysis.observeOverheadPullUp(frame.pose, selection: dipAthleteSelection,
            camera: moved ? "moved" : qualified ? "ready" : "unavailable",
            automaticStart: handsFreeStartEnabled && !workoutDetailsPresented && !recoveringAutomaticSetup,
            recordCalendarTime: recordCalendarTime) else {
            if liveSet.phase == .running {
                liveSet.interruptAndFinish(reason: "bodyPullUpAnalysisFailure", endReason: .cameraFailure)
                archiveWorkoutSet()
            }
            return
        }
        if liveSet.phase == .idle, snapshot.started {
            guard qualified, !workoutDetailsPresented else { workoutAnalysis.resetBodyPullUpPreparation(); return }
            if exerciseSelection == .automatic {
                // A source-owned bilateral excursion is a separate identity
                // route from the static 0.6s posture dwell. Never a grip gate.
                if resolvedExercise != .pullUp,
                   !automaticExercise.confirmObservedOverheadPullUpMovement(frame.pose) {
                    workoutAnalysis.resetBodyPullUpPreparation(); return
                }
                guard automaticExercise.lock() else { workoutAnalysis.resetBodyPullUpPreparation(); return }
            }
            beginBodyPullUpSet(snapshot)
        } else if liveSet.phase == .running { liveSet.consumeBodyPullUp(snapshot) }
        if liveSet.phase == .running {
            if let draft = workoutAnalysis.drainDraft() { persistWorkout(draft, finalized: false) }
            if let reason = snapshot.endReason.flatMap(LiveSetSession.EndReason.init(rawValue:)) {
                liveSet.finish(reason: reason); archiveWorkoutSet(); finishDebugCaptureIfNeeded()
            }
        }
    }

    /// Camera setup uses real pixels and motion measurements independently of
    /// apparatus. A pose can permit calibration but cannot declare it stable.
    private func maybeCalibrateBodyCamera(_ frame: ProcessedFrame) {
        // A manually calibrated reference keeps its independent scene/phone
        // lifetime. Automatic body setup never needs an apparatus reference.
        guard usesBodyMotionTracking, bar == nil else { return }
        let pose = frame.pose
        if let old = bodyCameraCalibration,
           old.size != pose.imageSize || old.backend != pose.backend || old.revision != pose.requestRevision {
            if liveSet.phase == .running {
                liveSet.interruptAndFinish(reason: "bodyCameraContextChanged", endReason: .setupInvalidated)
                archiveWorkoutSet(); finishDebugCaptureIfNeeded()
            }
            clearBar()
        }
        let motion = currentPhoneMotionSample()
        let eligible = cameraPlacement.isUsable && debugCaptureAllowsBarSetup && !workoutDetailsPresented &&
            dipAthleteDecision?.status == "associated" && pose.people.count == 1 && motion != nil
        let replacing = hasBodyCameraCalibration && bodyCameraEvidence.shouldReacquire(
            sourceValue:pose.timestamp.value,sourceTimescale:pose.timestamp.timescale,
            sceneReady:sceneTranslation.isReady(at:pose.timestamp.seconds),idle:bodyCameraIdle,
            eligible:eligible && phoneOrientation.state.allowsLiveSet)
        guard bodyCameraIdle, (!hasBodyCameraCalibration || replacing), eligible,
              let motion,
              let reference = VisionStaticSceneRegistrationWorker.makeReference(image: frame.image),
              reference.imageSize == pose.imageSize,
              phoneOrientation.calibrate(motion.attitude, timestamp: motion.timestamp),
              sceneTranslation.calibrate(imageSize: reference.imageSize),
              bodyCameraEvidence.beginReference(sourceValue:pose.timestamp.value,sourceTimescale:pose.timestamp.timescale) else { return }
        setupGeneration &+= 1
        sceneRegistrationTask?.cancel()
        sceneRegistrationTask = nil
        sceneReference = reference
        bodyCameraCalibration = BodyCameraCalibration(size: pose.imageSize, backend: pose.backend, revision: pose.requestRevision,
            sourceTime:pose.timestamp)
        if replacing { bodyCameraReacquisitions += 1 }
        workoutAnalysis.resetBodyDipPreparation()
        workoutAnalysis.resetBodyPullUpPreparation()
        lastSceneRegistrationSeconds = nil
    }

    private func consumeBodyDipFrame(_ frame: ProcessedFrame) {
        guard usesBodyDipTracking, liveSet.phase != .finished else { return }
        if liveSet.phase == .running, discardNextSetFrame { discardNextSetFrame = false; return }
        let qualified = cameraPlacement.isUsable && hasBodyCameraCalibration && currentPhoneMotionSample() != nil &&
            phoneOrientation.state.allowsLiveSet && sceneIsReady
        let moved = phoneOrientation.state == .moved || sceneTranslation.state == .moved
        #if DEBUG
        let recordCalendarTime = !evaluationInput
        #else
        let recordCalendarTime = true
        #endif
        guard let snapshot = workoutAnalysis.observeBodyDip(frame.pose, selection: dipAthleteSelection,
            camera: moved ? "moved" : qualified ? "ready" : "unavailable",
            automaticStart: handsFreeStartEnabled && !workoutDetailsPresented && !recoveringAutomaticSetup,
            recordCalendarTime: recordCalendarTime) else {
            if liveSet.phase == .running {
                liveSet.interruptAndFinish(reason: "bodyDipAnalysisFailure", endReason: .cameraFailure)
                archiveWorkoutSet()
            }
            return
        }
        if liveSet.phase == .idle, snapshot.started {
            guard qualified, !workoutDetailsPresented else { workoutAnalysis.resetBodyDipPreparation(); return }
            if exerciseSelection == .automatic {
                // The shared owner has committed a corroborated bilateral
                // excursion with this selected athlete and ready camera.
                // Static apparatus-search width/dwell is an alternative route.
                if resolvedExercise != .dip,
                   !automaticExercise.confirmObservedDipMovement(frame.pose) {
                    workoutAnalysis.resetBodyDipPreparation(); return
                }
                guard automaticExercise.lock() else { workoutAnalysis.resetBodyDipPreparation(); return }
            }
            beginBodyDipSet(snapshot)
        } else if liveSet.phase == .running {
            liveSet.consumeBodyDip(snapshot)
        }
        if liveSet.phase == .running {
            if let draft = workoutAnalysis.drainDraft() { persistWorkout(draft, finalized: false) }
            if let reason = snapshot.endReason.flatMap(LiveSetSession.EndReason.init(rawValue:)) {
                liveSet.finish(reason: reason); archiveWorkoutSet(); finishDebugCaptureIfNeeded()
            }
        }
    }

    func loadWorkoutHistory() async {
        let completedId = latestCompletedWorkout?.id
        do {
            let history = try await WorkoutRecordStore.shared.recent()
            var report: WorkoutAfterSetReport?
            if let completedId { report = try await WorkoutRecordStore.shared.libraryAfterSetIfNotDeleted(id: completedId) }
            // A new set can finish while storage is being read.
            guard latestCompletedWorkout?.id == completedId else { return }
            workoutHistory = history
            if completedId != nil {
                latestAfterSetReport = report
                if report == nil { latestCompletedWorkout = nil }
            }
        }
        catch { workoutStorageError = error.localizedDescription }
    }

    private func archiveWorkoutSet() {
        guard liveSet.phase == .finished, archivedSetId != workoutAnalysis.setId,
              let record = workoutAnalysis.finish(reason: liveSet.endReason?.rawValue ?? "manual"),
              archivedSetId != record.id else { return }
        archivedSetId = record.id
        if let report = try? record.afterSetReport() {
            latestAfterSetReport = report
            speakWorkoutCue(report.spokenCue)
        }
        latestCompletedWorkout = record.hasPractice ? record : nil
        finishSetVideo(record)
        guard record.hasPractice else { return }
        workoutHistory.insert(record, at: 0)
        workoutHistory = Array(workoutHistory.prefix(50))
        persistWorkout(record, finalized: true)
    }

    func setRecordsSetVideos(_ enabled: Bool) {
        guard liveSet.phase != .running else { return }
        recordsSetVideos = enabled
        UserDefaults.standard.set(enabled, forKey: "recordCountedSets")
        setVideoRecorder.setEnabled(enabled)
    }

    private func beginSetVideoIfNeeded() {
        #if DEBUG
        guard !evaluationInput else { return }
        #endif
        guard liveSet.phase == .running, let record = workoutAnalysis.currentRecord,
              recordingVideoSetId == nil else { return }
        recordsSetVideos = UserDefaults.standard.object(forKey: "recordCountedSets") == nil || UserDefaults.standard.bool(forKey: "recordCountedSets")
        setVideoRecorder.setEnabled(recordsSetVideos)
        guard recordsSetVideos else { return }
        workoutVideoError = nil
        recordingVideoSetId = record.id
        setVideoRecorder.begin(setId: record.id, startedAt: record.startedAt)
    }

    private func finishSetVideo(_ record: WorkoutSetRecord) {
        guard recordingVideoSetId == record.id else { return }
        recordingVideoSetId = nil
        setVideoRecorder.complete(record) { [weak self] result in
            Task { @MainActor in
                do {
                    guard let url = try result.get() else { return }
                    defer { try? FileManager.default.removeItem(at: url) }
                    _ = try await WorkoutRecordStore.shared.saveRecordedSetVideo(source: url, record: record)
                    NotificationCenter.default.post(name: .workoutVideoSaved, object: nil)
                    self?.workoutVideoError = nil
                } catch { self?.workoutVideoError = error.localizedDescription }
            }
        }
    }

    private func persistWorkout(_ record: WorkoutSetRecord, finalized: Bool) {
        if !finalized { beginSetVideoIfNeeded() }
        #if DEBUG
        guard !evaluationInput else { return }
        #endif
        Task {
            do {
                try await WorkoutRecordStore.shared.enqueue(record, finalized: finalized)
                try await WorkoutRecordStore.shared.flushPending()
                if finalized, latestAfterSetReport != nil, latestCompletedWorkout?.id == record.id {
                    latestAfterSetReport = try await WorkoutRecordStore.shared.libraryAfterSet(id: record.id)
                }
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
                    if let record = self?.latestCompletedWorkout {
                        self?.latestAfterSetReport = try await WorkoutRecordStore.shared.libraryAfterSet(id: record.id)
                    }
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
        resetFramingFeedback()
        resetHandsFreeStart()
        discardNextSetFrame = false
        liveSet.finish()
        archiveWorkoutSet()
        finishDebugCaptureIfNeeded()
    }

    func prepareNextSet() {
        guard liveSet.phase == .finished else { return }
        resetFramingFeedback()
        resetHandsFreeStart()
        if liveSet.phase == .finished { archiveWorkoutSet() }
        finishDebugCaptureIfNeeded()
        discardNextSetFrame = false
        liveSet.prepareNextSet()
        workoutAnalysis.resetBodyDipPreparation()
        workoutAnalysis.resetBodyPullUpPreparation()
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
        automaticBarPolicy.reset()
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
        resetFramingFeedback()

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
        resetFramingFeedback()
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
        finishPushUpPractice(reason: "sourceEnded")
        dipAthleteSelection.reset()
        dipAthleteDecision = nil
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
        resetFramingFeedback()
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
               (try? LiveCameraConnectionConfiguration.apply(to: movieConnection, rotationAngle: pushUpVideoRotationAngle)) != nil {
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
        try LiveCameraConnectionConfiguration.apply(to: connection, rotationAngle: pushUpVideoRotationAngle)
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
        // Reset after any in-flight callback from the old analyzer, before a
        // replacement delegate can append frames from the new camera stream.
        analysisQueue.async { [setVideoRecorder] in setVideoRecorder.reset() }
        analyzer = nil
    }

    private func startAnalysisStream(output: AVCaptureVideoDataOutput) {
        cancelAnalysisStream()
        let generation = analysisGeneration
        let eventStream = AsyncStream<LiveAnalyzerEvent>(
            bufferingPolicy: .bufferingNewest(1)
        ) { continuation in
            setVideoRecorder.setEnabled(!isPushUpPractice && recordsSetVideos)
            let analyzer = LiveFrameAnalyzer(gripEnabled: !isPushUpPractice && resolvedExercise != .dip, setVideoRecorder: setVideoRecorder) { event in
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
        finishPushUpPractice(reason: counterReason)
        dipAthleteSelection.reset()
        dipAthleteDecision = nil

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
        resetFramingFeedback()
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
        guard let sample = rawPhoneMotionSample(), sample.timestamp.isFinite else { return nil }
        let now = ProcessInfo.processInfo.systemUptime
        if let last = lastPhoneMotionTimestamp, sample.timestamp < last { return nil }
        if lastPhoneMotionTimestamp == nil || sample.timestamp > lastPhoneMotionTimestamp! {
            lastPhoneMotionTimestamp = sample.timestamp; lastPhoneMotionReceiptUptime = now
        }
        guard let receipt = lastPhoneMotionReceiptUptime, now-receipt <= ExerciseCounter.maximumGapSeconds else { return nil }
        return sample
    }

    private func rawPhoneMotionSample() -> PhoneMotionSample? {
        if let phoneMotionProvider { return phoneMotionProvider() }
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

        return PhoneMotionSample(attitude: attitude, timestamp: motion.timestamp,
            gravity: .init(motion.gravity.x, motion.gravity.y, motion.gravity.z))
    }

    private func pollPhoneOrientation() {
        let sample = currentPhoneMotionSample()
        motionSampleAvailable = sample != nil
        cameraPlacement = LiveCameraPlacement(gravity: sample?.gravity)

        guard hasCameraCalibration else { return }
        guard let sample else {
            phoneOrientation.markUnavailable()
            invalidateForPhoneOrientationLoss()
            return
        }

        if phoneOrientation.observe(sample.attitude, timestamp: sample.timestamp) == .moved {
            invalidateForPhoneMovement()
        }
    }

    private func invalidateForPhoneMovement() {
        guard hasCameraCalibration else { return }
        finishPushUpPractice(reason: "phoneMoved")

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
        guard hasCameraCalibration else { return }
        finishPushUpPractice(reason: "phoneOrientationUnavailable")

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
        guard hasCameraCalibration,
              let reference = sceneReference,
              sceneRegistrationTask == nil
        else { return }

        let seconds = frame.pose.timestamp.seconds
        guard seconds.isFinite else { return }
        if hasBodyCameraCalibration,
           !bodyCameraEvidence.permitsRegistration(sourceValue:frame.pose.timestamp.value,sourceTimescale:frame.pose.timestamp.timescale) { return }
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
                if !Task.isCancelled, self.setupGeneration == token, self.hasCameraCalibration {
                    self.sceneRegistrationFailures += 1
                    self.lastSceneRegistrationMilliseconds = nil
                    self.sceneTranslation.observe(translations: [], globalScaleFraction: nil, timestamp: seconds)
                    if self.hasBodyCameraCalibration {
                        self.bodyCameraEvidence.observeScene(sourceValue:frame.pose.timestamp.value,
                            sourceTimescale:frame.pose.timestamp.timescale,stationary:false)
                    }
                    self.sceneRegistrationTask = nil
                }
                return
            }

            let duration = started.duration(to: .now).components
            let registrationMilliseconds = Double(duration.seconds) * 1_000
                + Double(duration.attoseconds) / 1e15

            guard !Task.isCancelled,
                  self.setupGeneration == token,
                  self.hasCameraCalibration
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
            if self.hasBodyCameraCalibration {
                let stationary = state == .stable &&
                    (self.sceneTranslation.latestShiftFraction ?? .infinity) < StaticSceneStability.movementThresholdFraction &&
                    (self.sceneTranslation.latestScaleFraction ?? .infinity) < StaticSceneStability.scaleThresholdFraction &&
                    self.sceneTranslation.latestConsensusPatches >= StaticSceneStability.minimumConsensusPatches
                self.bodyCameraEvidence.observeScene(sourceValue:frame.pose.timestamp.value,
                    sourceTimescale:frame.pose.timestamp.timescale,stationary:stationary)
            }
            self.sceneRegistrationTask = nil
            if state == .moved {
                self.invalidateForSceneMovement()
            }
        }
    }

    private func invalidateForSceneMovement() {
        guard hasCameraCalibration else { return }
        finishPushUpPractice(reason: "sceneMoved")

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
                let fresh = self.lastAnalyzedFrameUptime.map {
                    ProcessInfo.processInfo.systemUptime - $0 <= ExerciseCounter.maximumGapSeconds
                } ?? false
                if self.readinessFrameFresh != fresh {
                    self.readinessFrameFresh = fresh
                    if !fresh { self.finishPushUpPractice(reason: "sourceFramesUnavailable") }
                }
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
        analyzer?.setGripEnabled(!isPushUpPractice && resolvedExercise != .dip)
        guard startRequested, !suspended, state == .ready else { return }

        switch event {
        case .frame(let rawFrame):
            let frame = selectBodyAthlete(rawFrame)
            // Placement uses fresh absolute gravity; relative camera movement
            // and image registration remain independent qualification gates.
            pollPhoneOrientation()
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
            readinessFrameFresh = true
            latestFrame = frame
            analyzedFrames += 1
            if let cue = workoutAnalysis.trackingSpokenCue(isRunning: liveSet.phase == .running, issue: liveSet.trackingIssue) { speakWorkoutCue(cue) }
            lastProcessingMilliseconds = frame.processingMilliseconds
            if isPushUpPractice {
                maybeCalibrateBodyCamera(frame)
                maybeCheckSceneTranslation(frame)
                consumePushUpPractice(rawFrame.pose)
                return
            }
            observeExerciseSelection(frame.pose)
            updateFraming(frame.pose)
            maybeCalibrateBodyCamera(frame)
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
            if usesBodyDipTracking {
                consumeBodyDipFrame(frame)
            } else if usesBodyPullUpTracking {
                consumeBodyPullUpFrame(frame)
            } else if liveSet.phase == .running, wasRunning || !discardNextSetFrame {
                if discardNextSetFrame {
                    discardNextSetFrame = false
                } else if let referenceEdge = currentBar?.referenceEdge {
                    let frameReference = cameraPlacement.isUsable ? sceneTranslation.qualifiedReference(
                        referenceEdge, at: frame.pose.timestamp.seconds) : nil
                    let eventCount = liveSet.counter.events.count
                    liveSet.consume(frame.pose, referenceEdge: frameReference)
                    let event = liveSet.counter.events.count > eventCount ? liveSet.counter.events.last : nil
                    let automaticEnd = workoutAnalysis.consume(frame.pose, reference: frameReference,
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

            armVisibility.observe(frame.pose, running: liveSet.phase == .running,
                side: trackingSide, usesBothArms: true, requiresBothArms: usesBodyMotionTracking)

        case .dropped:
            droppedFrames += 1

        case .failed:
            finishPushUpPractice(reason: "inferenceFailure")
            readinessFrameFresh = false
            resetHandsFreeStart()
            if exerciseSelection == .automatic, liveSet.phase == .idle {
                automaticExercise.reset()
                clearBar()
            }
            analysisFailures += 1
            latestFrame = nil
            lastProcessingMilliseconds = nil
            framing = LiveFramingAssessment(state: .analysisUnavailable)
            resetFramingFeedback()
            liveSet.interrupt(reason: "inferenceFailure")
            analyzer?.resetGripInference()
            workoutAnalysis.interrupt(reason: "inferenceFailure")
        }
    }

    /// Both exercise owners consume this original source selection. Selecting
    /// one athlete never copies missing joints or changes the original pixels.
    private func selectBodyAthlete(_ frame: ProcessedFrame) -> ProcessedFrame {
        let decision: BodyAthleteSelectionDecision?
        do {
            let raw = try dipAthleteSelection.observeJson(poseJson: String(decoding: JSONEncoder().encode(frame.pose), as: UTF8.self))
            decision = try JSONDecoder().decode(BodyAthleteSelectionDecision.self, from: Data(raw.utf8))
        } catch { decision = nil }
        dipAthleteDecision = decision
        let index = decision?.candidateIndex.flatMap { frame.pose.people.indices.contains($0) ? $0 : nil }
        let pose = PoseResult(timestamp: frame.pose.timestamp, imageSize: frame.pose.imageSize,
            people: index.map { [frame.pose.people[$0]] } ?? [], backend: frame.pose.backend,
            requestRevision: frame.pose.requestRevision,
            gripObservation: index == 0 && !usesBodyDipTracking ? frame.pose.gripObservation : nil,
            estimatedWorldConvention: frame.pose.estimatedWorldConvention)
        return ProcessedFrame(image: frame.image, pose: pose, processingMilliseconds: frame.processingMilliseconds)
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
    var rotationAngle: Double = 90

    func makeUIView(context: Context) -> CameraPreviewView {
        let view = CameraPreviewView()
        view.rotationAngle = rotationAngle
        view.previewLayer.videoGravity = .resizeAspect
        view.previewLayer.session = session
        return view
    }

    func updateUIView(_ view: CameraPreviewView, context: Context) {
        view.rotationAngle = rotationAngle
        if view.previewLayer.session !== session {
            view.previewLayer.session = session
        }
        view.updateRotation()
    }
}

final class CameraPreviewView: UIView {
    var rotationAngle: Double = 90
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
        try? LiveCameraConnectionConfiguration.apply(to: connection, rotationAngle: rotationAngle)
    }
}
#endif
