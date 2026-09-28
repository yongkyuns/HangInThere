import AVFoundation
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

    var errorDescription: String? {
        switch self {
        case .noRearCamera:
            "No supported rear camera is available."
        case .cannotAddInput:
            "The rear camera could not be added to the capture session."
        }
    }
}

@MainActor
@Observable
final class LiveCameraPreviewController {
    private(set) var state: LiveCameraState = .idle

    let session = AVCaptureSession()

    @ObservationIgnored private let sessionQueue = DispatchQueue(
        label: "dev.yongkyuns.HangInThere.camera-session",
        qos: .userInitiated
    )
    @ObservationIgnored private var configured = false
    @ObservationIgnored private var startRequested = false

    var isReady: Bool { state == .ready }

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

        session.beginConfiguration()
        defer { session.commitConfiguration() }

        if session.canSetSessionPreset(.hd1280x720) {
            session.sessionPreset = .hd1280x720
        }
        guard session.canAddInput(input) else {
            throw LiveCameraSetupError.cannotAddInput
        }
        session.addInput(input)
        configured = true
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
    }
}

final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }
}
