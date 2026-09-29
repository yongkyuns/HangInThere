import AVFoundation
import CoreImage
import Foundation
import Testing
@testable import HangInThere

enum FixtureError: LocalizedError {
    case failed(String)
    var errorDescription: String? {
        switch self { case .failed(let message): message }
    }
}

private final class FixtureBundleToken: NSObject {}

enum VideoTestSupport {
    static let timestamps: [CMTime] = [0, 10, 23, 41].map { CMTime(value: $0, timescale: 100) }

    static func resource(_ name: String) throws -> URL {
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        #else
        let bundle = Bundle(for: FixtureBundleToken.self)
        #endif
        let root = try #require(bundle.resourceURL)
        let url = root.appendingPathComponent("Fixtures/generated/\(name)")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw FixtureError.failed("Missing \(name). Run python3 scripts/prepare-fixtures.py before building tests. Missing real media is a failure, not a skipped model check.")
        }
        return url
    }

    static func fixtureResource(_ name: String) throws -> URL {
        #if SWIFT_PACKAGE
        let bundle = Bundle.module
        #else
        let bundle = Bundle(for: FixtureBundleToken.self)
        #endif
        let root = try #require(bundle.resourceURL)
        let url = root.appendingPathComponent("Fixtures/\(name)")
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw FixtureError.failed("Missing fixture metadata \(name). Test resources are incomplete.")
        }
        return url
    }

    // Original synthetic pixels, not a human-pose accuracy fixture. Four coloured
    // quadrants make every rotation/reflection observable after actual decoding.
    static func makeVideo(transform: CGAffineTransform = .identity,
                          timestamps: [CMTime] = VideoTestSupport.timestamps) async throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("quadrants-\(UUID().uuidString).mp4")
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let width = 160, height = 96
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width, AVVideoHeightKey: height
        ])
        input.expectsMediaDataInRealTime = false
        input.transform = transform
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: width, kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:]
        ])
        guard writer.canAdd(input) else { throw FixtureError.failed("Cannot add video writer input.") }
        writer.add(input)
        guard writer.startWriting() else { throw FixtureError.failed(writer.error?.localizedDescription ?? "Writer failed to start.") }
        writer.startSession(atSourceTime: .zero)
        do {
            for pts in timestamps {
                let deadline = ContinuousClock.now.advanced(by: .seconds(10))
                while !input.isReadyForMoreMediaData {
                    guard writer.status == .writing, ContinuousClock.now < deadline else {
                        throw FixtureError.failed(writer.error?.localizedDescription ?? "Writer readiness timed out.")
                    }
                    try await Task.sleep(for: .milliseconds(5))
                }
                var optionalBuffer: CVPixelBuffer?
                let status = CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA,
                                                  [kCVPixelBufferIOSurfacePropertiesKey as String: [:]] as CFDictionary,
                                                  &optionalBuffer)
                guard status == kCVReturnSuccess, let buffer = optionalBuffer else {
                    throw FixtureError.failed("Cannot allocate test frame.")
                }
                CVPixelBufferLockBaseAddress(buffer, [])
                guard let base = CVPixelBufferGetBaseAddress(buffer) else {
                    CVPixelBufferUnlockBaseAddress(buffer, [])
                    throw FixtureError.failed("Test frame has no pixel storage.")
                }
                let bytes = base.assumingMemoryBound(to: UInt8.self)
                let stride = CVPixelBufferGetBytesPerRow(buffer)
                for y in 0..<height {
                    for x in 0..<width {
                        let offset = y * stride + x * 4
                        let top = y < height / 2, left = x < width / 2
                        let rgb: (UInt8, UInt8, UInt8)
                        switch (top, left) {
                        case (true, true): rgb = (240, 0, 0)
                        case (true, false): rgb = (0, 240, 0)
                        case (false, true): rgb = (0, 0, 240)
                        case (false, false): rgb = (240, 240, 0)
                        }
                        bytes[offset] = rgb.2; bytes[offset + 1] = rgb.1
                        bytes[offset + 2] = rgb.0; bytes[offset + 3] = 255
                    }
                }
                CVPixelBufferUnlockBaseAddress(buffer, [])
                guard adaptor.append(buffer, withPresentationTime: pts) else {
                    throw FixtureError.failed(writer.error?.localizedDescription ?? "Could not append frame.")
                }
            }
            writer.endSession(atSourceTime: CMTimeAdd(timestamps.last ?? .zero, CMTime(value: 10, timescale: 100)))
            input.markAsFinished()
            await writer.finishWriting()
            guard writer.status == .completed else {
                throw FixtureError.failed(writer.error?.localizedDescription ?? "Could not finish test video.")
            }
            return url
        } catch {
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: url)
            throw error
        }
    }

    static func rgb(_ image: CGImage, normalizedTopLeft point: Point2D) -> [UInt8] {
        var rgba = [UInt8](repeating: 0, count: 4)
        let bounds = CGRect(x: (Double(image.width) * point.x).rounded(.down),
                            y: (Double(image.height) * (1 - point.y)).rounded(.down), width: 1, height: 1)
        let context = CIContext(options: [.useSoftwareRenderer: true])
        rgba.withUnsafeMutableBytes { bytes in
            context.render(CIImage(cgImage: image), toBitmap: bytes.baseAddress!, rowBytes: 4,
                           bounds: bounds, format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())
        }
        return Array(rgba.prefix(3))
    }

    static func hasVisibleArm(_ result: PoseResult) -> Bool {
        result.people.contains { person in
            let left = [PoseJoint.leftShoulder, .leftElbow, .leftWrist]
            let right = [PoseJoint.rightShoulder, .rightElbow, .rightWrist]
            return left.allSatisfy { person.landmark($0, minimumConfidence: 0.2) != nil }
                || right.allSatisfy { person.landmark($0, minimumConfidence: 0.2) != nil }
        }
    }
}

// Explicit test-only inference, never a production fallback or model evidence.
// Return a nonempty sentinel so tests detect dropped/replaced estimator output.
struct TestPoseEstimator: PoseEstimator {
    static let backend = "Test pose estimator (not Vision)"
    static let failureMessage = "Injected pose inference failure."
    let failAtOrAfter: Double?

    init(failAtOrAfter: Double? = nil) {
        self.failAtOrAfter = failAtOrAfter
    }

    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        if let failAtOrAfter, timestamp.seconds >= failAtOrAfter {
            throw FixtureError.failed(Self.failureMessage)
        }
        let size = ImageSize(width: Double(image.width), height: Double(image.height))
        let marker = Landmark(joint: .leftWrist,
                              position: Point2D(x: size.width / 4, y: size.height / 4), confidence: 1)
        return PoseResult(timestamp: timestamp, imageSize: size,
                          people: [PoseObservation(landmarks: [marker])],
                          backend: Self.backend, requestRevision: 0)
    }
}
