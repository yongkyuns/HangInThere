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

private struct LivePoseFrame: Sendable {
    let pose: PoseResult
    let processingMilliseconds: Double
}

private enum LiveAnalyzerEvent: Sendable {
    case frame(LivePoseFrame)
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
                publish(.frame(LivePoseFrame(
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
    @ObservationIgnored private var trackingSide: ArmMeasurement.Side = .left
    @ObservationIgnored private var latestPose: PoseResult?

    var isCameraReady: Bool { state == .ready }

    func setTrackingSide(_ side: ArmMeasurement.Side) {
        guard trackingSide != side else { return }
        trackingSide = side
        if let latestPose {
            framing = LiveFramingAssessment(pose: latestPose, side: side)
        } else {
            framing = LiveFramingAssessment()
        }
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
        startRequested = false
        if state == .ready || state == .starting || state == .requestingPermission {
            state = .idle
        }
        framing = LiveFramingAssessment()
        latestPose = nil
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

        let analyzer = LiveFrameAnalyzer { [weak self] event in
            Task { @MainActor [weak self] in
                self?.accept(event)
            }
        }

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
            throw LiveCameraSetupError.cannotAddVideoOutput
        }
        session.addOutput(output)

        guard let connection = output.connection(with: .video),
              connection.isVideoRotationAngleSupported(90)
        else {
            throw LiveCameraSetupError.unsupportedPortraitRotation
        }
        connection.videoRotationAngle = 90

        output.setSampleBufferDelegate(analyzer, queue: analysisQueue)
        videoOutput = output
        self.analyzer = analyzer
        configured = true
    }

    private func accept(_ event: LiveAnalyzerEvent) {
        guard startRequested else { return }

        switch event {
        case .frame(let frame):
            latestPose = frame.pose
            analyzedFrames += 1
            lastProcessingMilliseconds = frame.processingMilliseconds
            framing = LiveFramingAssessment(pose: frame.pose, side: trackingSide)

        case .dropped:
            droppedFrames += 1

        case .failed:
            analysisFailures += 1
            latestPose = nil
            lastProcessingMilliseconds = nil
            framing = LiveFramingAssessment(state: .analysisUnavailable)
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
