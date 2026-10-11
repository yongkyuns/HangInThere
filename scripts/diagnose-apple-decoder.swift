// Synthetic native-decoder diagnosis only. Never used by the app or pose engine.
import AVFoundation
import CryptoKit
import Foundation
import VideoToolbox

private struct Fixture: Decodable {
    let videoSha256: String
    let videoBytes: Int
    let sourceWidth: Int
    let sourceHeight: Int
    let framePtsSeconds: [Double]
}

private struct NativeFrame: Encodable {
    let ptsSeconds: Double?
    let callbackStatus: OSStatus
    let callbackFlags: UInt32
    let pixelFormat: OSType?
    let width: Int?
    let height: Int?
    let yCbCr: [[Int]]?
    let samplingError: String?
}

// VideoToolbox may invoke the C callback on another thread. Copy only scalar
// pixels while the buffer is valid; every access to the frame array is locked.
private final class Frames: @unchecked Sendable {
    private let lock = NSLock()
    private var frames: [NativeFrame] = []

    func append(status: OSStatus, flags: VTDecodeInfoFlags, buffer: CVPixelBuffer?, pts: CMTime) {
        var samples: [[Int]]?
        var error: String?
        if let buffer {
            let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
            let locked = CVPixelBufferLockBaseAddress(buffer, .readOnly)
            if locked != kCVReturnSuccess {
                error = "Pixel-buffer lock failed: \(locked)"
            } else {
                defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
                if CVPixelBufferGetPixelFormatType(buffer) != kCVPixelFormatType_420YpCbCr8Planar
                    || CVPixelBufferGetPlaneCount(buffer) != 3 || width < 4 || height < 4 {
                    error = "Expected three-plane limited-range I420"
                } else {
                    var values = Array(repeating: [Int](), count: 4)
                    for plane in 0..<3 {
                        let divisor = plane == 0 ? 1 : 2
                        let planeWidth = CVPixelBufferGetWidthOfPlane(buffer, plane)
                        let planeHeight = CVPixelBufferGetHeightOfPlane(buffer, plane)
                        let stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, plane)
                        guard planeWidth == width / divisor, planeHeight == height / divisor,
                              stride >= planeWidth,
                              let base = CVPixelBufferGetBaseAddressOfPlane(buffer, plane) else {
                            error = "Invalid planar dimensions, stride or address"; break
                        }
                        let bytes = base.assumingMemoryBound(to: UInt8.self)
                        for (index, point) in [(width / 4, height / 4), (3 * width / 4, height / 4),
                                               (width / 4, 3 * height / 4), (3 * width / 4, 3 * height / 4)].enumerated() {
                            values[index].append(Int(bytes[(point.1 / divisor) * stride + point.0 / divisor]))
                        }
                    }
                    if error == nil { samples = values }
                }
            }
        } else { error = "Callback returned no pixel buffer" }
        let seconds = pts.seconds
        let frame = NativeFrame(ptsSeconds: seconds.isFinite ? seconds : nil, callbackStatus: status,
                                callbackFlags: flags.rawValue,
                                pixelFormat: buffer.map(CVPixelBufferGetPixelFormatType),
                                width: buffer.map(CVPixelBufferGetWidth), height: buffer.map(CVPixelBufferGetHeight),
                                yCbCr: samples, samplingError: error)
        lock.lock(); defer { lock.unlock() }
        frames.append(frame)
    }

    func snapshot() -> [NativeFrame] {
        lock.lock(); defer { lock.unlock() }
        return frames
    }
}

private struct CaseReport: Encodable {
    let fixture: String
    let hardwareAllowed: Bool
    var hardwareQueryStatus: OSStatus?
    var hardwareUsed: Bool?
    var emptySamples = 0
    var decodeStatuses: [OSStatus] = []
    var finishStatus: OSStatus?
    var waitStatus: OSStatus?
    var readerCompleted = false
    var frames: [NativeFrame] = []
    var failures: [String] = []
}

private struct Report: Encodable {
    let schemaVersion = 1
    let scope = "Synthetic native I420 pixels before Core Image, rotation or Vision; no app accuracy/performance qualification"
    let operatingSystem = ProcessInfo.processInfo.operatingSystemVersionString
    let expectedYCbCr = [[59, 103, 232], [162, 47, 33], [30, 232, 118], [207, 23, 137]]
    let channelTolerance = 2
    var cases: [CaseReport] = []
    var passed: Bool { !cases.isEmpty && cases.allSatisfy { $0.failures.isEmpty } }

    enum CodingKeys: String, CodingKey {
        case schemaVersion, scope, operatingSystem, expectedYCbCr, channelTolerance, cases, passed
    }

    func encode(to encoder: any Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(schemaVersion, forKey: .schemaVersion)
        try container.encode(scope, forKey: .scope)
        try container.encode(operatingSystem, forKey: .operatingSystem)
        try container.encode(expectedYCbCr, forKey: .expectedYCbCr)
        try container.encode(channelTolerance, forKey: .channelTolerance)
        try container.encode(cases, forKey: .cases)
        try container.encode(passed, forKey: .passed)
    }
}

private func decode(_ url: URL, fixture: Fixture, hardware: Bool) async throws -> CaseReport {
    var result = CaseReport(fixture: url.lastPathComponent, hardwareAllowed: hardware)
    let asset = AVURLAsset(url: url)
    guard let track = try await asset.loadTracks(withMediaType: .video).first else {
        result.failures.append("Missing video track"); return result
    }
    let formats = try await track.load(.formatDescriptions)
    guard let format = formats.first, CMFormatDescriptionGetMediaSubType(format) == kCMVideoCodecType_H264 else {
        result.failures.append("Missing H264 format description"); return result
    }
    let reader = try AVAssetReader(asset: asset)
    defer { reader.cancelReading() }
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
    output.alwaysCopiesSampleData = false
    guard reader.canAdd(output) else { result.failures.append("Cannot add compressed reader output"); return result }
    reader.add(output)
    guard reader.startReading() else { result.failures.append("Reader did not start: \(String(describing: reader.error))"); return result }
    let frames = Frames()
    var callback = VTDecompressionOutputCallbackRecord(decompressionOutputCallback: { reference, _, status, flags, buffer, pts, _ in
        guard let reference else { return }
        Unmanaged<Frames>.fromOpaque(reference).takeUnretainedValue().append(status: status, flags: flags, buffer: buffer, pts: pts)
    }, decompressionOutputRefCon: Unmanaged.passUnretained(frames).toOpaque())
    let specification = [kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder as String: hardware] as CFDictionary
    let attributes = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8Planar] as CFDictionary
    var session: VTDecompressionSession?
    let created = VTDecompressionSessionCreate(allocator: kCFAllocatorDefault, formatDescription: format,
        decoderSpecification: specification, imageBufferAttributes: attributes, outputCallback: &callback,
        decompressionSessionOut: &session)
    guard created == noErr, let session else { result.failures.append("Decoder creation failed: \(created)"); return result }
    defer { withExtendedLifetime(frames) { VTDecompressionSessionInvalidate(session) } }
    while let sample = output.copyNextSampleBuffer() {
        // AVAssetReader can expose zero-payload timing samples. They are not
        // compressed pictures; submitting them produces kVTParameterErr.
        guard let data = CMSampleBufferGetDataBuffer(sample), CMBlockBufferGetDataLength(data) > 0 else {
            result.emptySamples += 1; continue
        }
        result.decodeStatuses.append(VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample,
            flags: [], frameRefcon: nil, infoFlagsOut: nil))
    }
    result.readerCompleted = reader.status == .completed
    result.finishStatus = VTDecompressionSessionFinishDelayedFrames(session)
    result.waitStatus = VTDecompressionSessionWaitForAsynchronousFrames(session)
    let property = UnsafeMutablePointer<CFTypeRef?>.allocate(capacity: 1)
    property.initialize(to: nil)
    defer { property.deinitialize(count: 1); property.deallocate() }
    result.hardwareQueryStatus = VTSessionCopyProperty(session,
        key: kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder,
        allocator: kCFAllocatorDefault, valueOut: property)
    if result.hardwareQueryStatus == noErr { result.hardwareUsed = property.pointee as? Bool }
    result.frames = frames.snapshot()
    if !result.readerCompleted { result.failures.append("Reader failed to complete: \(String(describing: reader.error))") }
    if result.decodeStatuses.count != fixture.framePtsSeconds.count || result.decodeStatuses.contains(where: { $0 != noErr }) {
        result.failures.append("Compressed submissions did not all succeed with the expected picture count")
    }
    if result.finishStatus != noErr || result.waitStatus != noErr { result.failures.append("Decoder drain failed") }
    // Some software backends do not implement this optional property. Retain
    // that status and leave hardwareUsed absent rather than inventing a value.
    if result.hardwareQueryStatus != noErr && result.hardwareQueryStatus != kVTPropertyNotSupportedErr {
        result.failures.append("Hardware-use property query failed")
    }
    if !hardware && result.hardwareUsed == true { result.failures.append("Decoder ignored hardware-disabled request") }
    if result.frames.count != fixture.framePtsSeconds.count { result.failures.append("Unexpected callback frame count") }
    let reference = Report()
    for (index, frame) in result.frames.enumerated() {
        let flags = VTDecodeInfoFlags(rawValue: frame.callbackFlags)
        if frame.callbackStatus != noErr || flags.contains(.frameDropped) || frame.samplingError != nil {
            result.failures.append("Frame \(index): callback or pixel sampling failed")
        }
        if frame.width != fixture.sourceWidth || frame.height != fixture.sourceHeight { result.failures.append("Frame \(index): wrong dimensions") }
        if index >= fixture.framePtsSeconds.count || frame.ptsSeconds == nil
            || abs((frame.ptsSeconds ?? 0) - fixture.framePtsSeconds[index]) > 1e-6 {
            result.failures.append("Frame \(index): unexpected source PTS/order")
        }
        guard let pixels = frame.yCbCr, pixels.count == reference.expectedYCbCr.count,
              pixels.allSatisfy({ $0.count == 3 }) else { result.failures.append("Frame \(index): missing planar samples"); continue }
        for quadrant in pixels.indices {
            for channel in 0..<3 where abs(pixels[quadrant][channel] - reference.expectedYCbCr[quadrant][channel]) > reference.channelTolerance {
                result.failures.append("Frame \(index), quadrant \(quadrant), YCbCr channel \(channel): \(pixels[quadrant][channel]) != \(reference.expectedYCbCr[quadrant][channel]) ±\(reference.channelTolerance)")
            }
        }
    }
    return result
}

@main private struct DecoderDiagnosis {
    static func main() async throws {
        guard CommandLine.arguments.count == 2 else { throw CocoaError(.fileReadInvalidFileName) }
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        var report = Report()
        for name in ["quadrants-h264-vfr-portrait", "quadrants-h264-4x4-vfr-portrait"] {
            let fixture = try decoder.decode(Fixture.self, from: Data(contentsOf: root.appendingPathComponent(name + ".json")))
            let url = root.appendingPathComponent(name + ".mp4")
            let data = try Data(contentsOf: url)
            let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard hash == fixture.videoSha256, data.count == fixture.videoBytes,
                  fixture.sourceWidth == 160, fixture.sourceHeight == 96,
                  fixture.framePtsSeconds == [0, 0.1, 0.23, 0.41] else { throw CocoaError(.fileReadCorruptFile) }
            for hardware in [true, false] {
                do { report.cases.append(try await decode(url, fixture: fixture, hardware: hardware)) }
                catch { var failed = CaseReport(fixture: url.lastPathComponent, hardwareAllowed: hardware)
                    failed.failures.append(String(describing: error)); report.cases.append(failed) }
            }
        }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.keyEncodingStrategy = .convertToSnakeCase
        FileHandle.standardOutput.write(try encoder.encode(report)); FileHandle.standardOutput.write(Data("\n".utf8))
        if !report.passed { exit(1) }
    }
}
