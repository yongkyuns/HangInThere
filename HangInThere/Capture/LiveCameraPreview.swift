#if os(iOS)
import AVFoundation
import CoreImage
import Observation
import SwiftUI
import UIKit

enum LiveCameraState: Equatable, Sendable {
    case idle
    case requestingPermission
    case starting
    case ready
    case denied
    case unavailable
    case failed(String)

    var title: String {
        switch self {
        case .idle: "Camera not started"
        case .requestingPermission: "Waiting for camera access"
        case .starting: "Starting camera"
        case .ready: "Camera ready"
        case .denied: "Camera access is off"
        case .unavailable: "Rear camera unavailable"
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
final class LiveCameraPreviewController {
    private(set) var state: LiveCameraState = .idle
    private(set) var framing = LiveFramingAssessment()
    private(set) var analyzedFrames = 0
    private(set) var droppedFrames = 0
    private(set) var analysisFailures = 0
    private(set) var lastProcessingMilliseconds: Double?
    private(set) var bar: ConfirmedBar?
    private(set) var liveSet = LiveSetSession()

    let session = AVCaptureSession()

    @ObservationIgnored private let sessionQueue = DispatchQueue(
        label: "dev.yongkyuns.HangInThere.camera-session",
        qos: .userInitiated
    )
    @ObservationIgnored private let analysisQueue = DispatchQueue(
        label: "dev.yongkyuns.HangInThere.camera-analysis",
        qos: .userInitiated
    )
    @ObservationIgnored private var configured = false
    @ObservationIgnored private var startRequested = false
    @ObservationIgnored private var videoOutput: AVCaptureVideoDataOutput?
    @ObservationIgnored private var analyzer: LiveFrameAnalyzer?
    @ObservationIgnored private var analysisEventTask: Task<Void, Never>?
    @ObservationIgnored private var exercise: ExerciseCounter.Exercise = .pullUp
    @ObservationIgnored private var trackingSide: ArmMeasurement.Side = .left
    @ObservationIgnored private var latestFrame: ProcessedFrame?
    @ObservationIgnored private var setupGeneration: UInt64 = 0
    @ObservationIgnored private var discardNextSetFrame = false

    var isCameraReady: Bool { state == .ready }

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

    var setupReadiness: LiveSetupReadiness {
        LiveSetupReadiness(
            cameraReady: isCameraReady,
            framing: framing.state,
            barConfirmed: currentBar != nil
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
              let latestFrame
        else { return nil }

        return BarSetupFrame(
            frame: latestFrame,
            role: barRole,
            generation: setupGeneration
        )
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
              bar.sourceTime == setup.frame.pose.timestamp
        else { return false }

        self.bar = bar
        return true
    }

    func clearBar() {
        guard liveSet.phase != .running else { return }
        setupGeneration &+= 1
        bar = nil
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
    }

    func prepareNextSet() {
        discardNextSetFrame = false
        liveSet.prepareNextSet()
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

        analyzedFrames = 0
        droppedFrames = 0
        analysisFailures = 0
        lastProcessingMilliseconds = nil
        latestFrame = nil
        setupGeneration &+= 1
        bar = nil
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

        framing = LiveFramingAssessment()
        state = .starting
        let session = session
        sessionQueue.async {
            if !session.isRunning {
                session.startRunning()
            }
        }

        let deadline = ContinuousClock.now.advanced(by: .seconds(8))
        while !session.isRunning {
            guard startRequested else { return }
            guard ContinuousClock.now < deadline else {
                state = .failed("The camera did not start. Close setup and try again.")
                startRequested = false
                return
            }
            try? await Task.sleep(for: .milliseconds(50))
        }
        guard startRequested else { return }
        state = .ready
    }

    func stop() {
        if liveSet.phase == .running {
            liveSet.finish()
        }
        discardNextSetFrame = false
        startRequested = false
        if state == .ready || state == .starting || state == .requestingPermission {
            state = .idle
        }
        framing = LiveFramingAssessment()
        latestFrame = nil
        setupGeneration &+= 1
        bar = nil
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

    private func accept(_ event: LiveAnalyzerEvent) {
        guard startRequested else { return }

        switch event {
        case .frame(let frame):
            if let bar, bar.imageSize != frame.pose.imageSize {
                self.bar = nil
                setupGeneration &+= 1
            }
            latestFrame = frame
            analyzedFrames += 1
            lastProcessingMilliseconds = frame.processingMilliseconds
            framing = LiveFramingAssessment(pose: frame.pose, side: trackingSide)

            if liveSet.phase == .running {
                if discardNextSetFrame {
                    discardNextSetFrame = false
                } else {
                    liveSet.consume(frame.pose, referenceEdge: currentBar?.referenceEdge)
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
