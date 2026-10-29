import AVFoundation
import CoreGraphics
import CoreImage
import CryptoKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// A bounded Mac input diagnostic. This uses the app's unmodified Vision body
/// estimator and rendering geometry, not its automatic controller. Both fresh
/// SDK outputs are retained; no inferred joints, counters or labels enter here.
@main enum BodyPoseCropProbe {
    struct Region: Codable {
        let left: Int, top: Int, width: Int, height: Int
        var rect: CGRect { CGRect(x: left, y: top, width: width, height: height) }

        // Historical PersonInferenceRegion recipe 1, reproduced for this
        // standalone native diagnostic without rebuilding WorkoutCore.
        static func select(_ pose: PoseResult) -> Region? {
            guard pose.people.count == 1 else { return nil }
            let person = pose.people[0]
            guard Set(person.landmarks.map(\.joint)).count == person.landmarks.count else { return nil }
            let points = person.landmarks.filter {
                $0.isVisible() && (0...pose.imageSize.width).contains($0.position.x)
                    && (0...pose.imageSize.height).contains($0.position.y)
            }
            let anchors: Set<PoseJoint> = [.leftShoulder, .rightShoulder, .leftHip, .rightHip]
            guard points.count >= 8, anchors.isSubset(of: Set(points.map(\.joint))),
                  let minX = points.map({ $0.position.x }).min(),
                  let maxX = points.map({ $0.position.x }).max(),
                  let minY = points.map({ $0.position.y }).min(),
                  let maxY = points.map({ $0.position.y }).max(),
                  maxX - minX >= 16, maxY - minY >= 16 else { return nil }
            let padX = max(16, (maxX - minX) * 0.15)
            let padY = max(16, (maxY - minY) * 0.15)
            let left = Int(floor(max(0, minX - padX)))
            let top = Int(floor(max(0, minY - padY)))
            let right = Int(ceil(min(pose.imageSize.width, maxX + padX)))
            let bottom = Int(ceil(min(pose.imageSize.height, maxY + padY)))
            guard right > left, bottom > top else { return nil }
            return Region(left: left, top: top, width: right - left, height: bottom - top)
        }

        func restore(_ pose: PoseResult, sourceSize: ImageSize) throws -> PoseResult {
            guard pose.imageSize == ImageSize(width: Double(width), height: Double(height)),
                  pose.gripObservation == nil, pose.estimatedWorldConvention == nil else { throw ProbeError.invalidCrop }
            return PoseResult(timestamp: pose.timestamp, imageSize: sourceSize,
                people: pose.people.map { person in
                    PoseObservation(landmarks: person.landmarks.filter {
                        $0.position.isFinite && (0...Double(width)).contains($0.position.x)
                            && (0...Double(height)).contains($0.position.y)
                    }.map {
                        Landmark(joint: $0.joint,
                            position: Point2D(x: $0.position.x + Double(left), y: $0.position.y + Double(top)),
                            confidence: $0.confidence)
                    })
                }, backend: pose.backend, requestRevision: pose.requestRevision)
        }
    }

    struct Window: Decodable { let id: String; let start: Int64; let end: Int64 }
    struct Config: Decodable { let source: String; let windows: [Window] }
    struct Row: Encodable {
        let caseId: String
        let ordinal: Int
        let pose: PoseResult
        let cropPose: PoseResult?
        let cropRawPose: PoseResult?
        let region: Region?
        let regionWitnessTime: PresentationTime?
        let pixelsSHA256: String
        let cropPixelsSHA256: String?
        let fullMilliseconds: Double
        let cropMilliseconds: Double?
    }
    enum ProbeError: Error { case usage, decoding, geometry, invalidCrop, pixelMismatch, existingOutput }

    static func rgba(_ image: CGImage) throws -> Data {
        var data = Data(count: image.width * image.height * 4)
        let ok = data.withUnsafeMutableBytes { bytes in
            guard let context = CGContext(data: bytes.baseAddress, width: image.width, height: image.height,
                bitsPerComponent: 8, bytesPerRow: image.width * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.setBlendMode(.copy)
            context.interpolationQuality = .none
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            return true
        }
        guard ok else { throw ProbeError.geometry }
        return data
    }

    static func hash(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func checkedCrop(_ image: CGImage, region: Region, original: Data) throws -> (CGImage, Data) {
        guard let crop = image.cropping(to: region.rect), crop.width == region.width,
              crop.height == region.height else { throw ProbeError.invalidCrop }
        let pixels = try rgba(crop)
        for y in 0..<region.height {
            let src = ((region.top + y) * image.width + region.left) * 4
            let dst = y * region.width * 4
            guard pixels[dst..<(dst + region.width * 4)] == original[src..<(src + region.width * 4)] else {
                throw ProbeError.pixelMismatch
            }
        }
        return (crop, pixels)
    }

    static func selfCheck() throws {
        // Asymmetric opaque colors distinguish all translations and y-flips.
        var bytes = Data()
        for i in 0..<48 { bytes.append(contentsOf: [UInt8(i * 3), UInt8(255 - i * 2), UInt8(i), UInt8(255)]) }
        let image = CGImage(width: 8, height: 6, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: 32,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: CGDataProvider(data: bytes as CFData)!, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
        let original = try rgba(image)
        guard original == bytes else { throw ProbeError.pixelMismatch }
        let region = Region(left: 2, top: 1, width: 3, height: 4)
        _ = try checkedCrop(image, region: region, original: original)
        let time = PresentationTime(value: 148140, timescale: 300)
        let raw = PoseResult(timestamp: time, imageSize: ImageSize(width: 3, height: 4),
            people: [PoseObservation(landmarks: [Landmark(joint: .leftWrist,
                position: Point2D(x: 1, y: 2), confidence: 0.75)])], backend: "synthetic geometry control", requestRevision: 1)
        let restored = try region.restore(raw, sourceSize: ImageSize(width: 8, height: 6))
        guard restored.timestamp == time,
              restored.people[0].landmarks[0].position == Point2D(x: 3, y: 3),
              restored.people[0].landmarks[0].confidence == 0.75 else { throw ProbeError.invalidCrop }
    }

    static func save(_ image: CGImage, to url: URL) throws {
        guard let target = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
            throw ProbeError.geometry
        }
        CGImageDestinationAddImage(target, image, nil)
        guard CGImageDestinationFinalize(target) else { throw ProbeError.geometry }
    }

    static func main() async throws {
        try selfCheck()
        if CommandLine.arguments.count == 2 && CommandLine.arguments[1] == "--self-check" {
            print("Asymmetric pixel crop and source-coordinate restoration controls passed")
            return
        }
        guard CommandLine.arguments.count == 3 else { throw ProbeError.usage }
        let config = try JSONDecoder().decode(Config.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
        let directory = URL(fileURLWithPath: CommandLine.arguments[2])
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = directory.appendingPathComponent("fresh-poses.jsonl")
        guard !FileManager.default.fileExists(atPath: target.path) else { throw ProbeError.existingOutput }
        FileManager.default.createFile(atPath: target.path, contents: nil)
        let file = try FileHandle(forWritingTo: target)
        defer { try? file.close() }
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let asset = AVURLAsset(url: URL(fileURLWithPath: config.source))
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw ProbeError.decoding }
        let t = try await track.load(.preferredTransform)
        let transform = Affine2D(a: t.a, b: t.b, c: t.c, d: t.d, tx: t.tx, ty: t.ty)
        let context = CIContext(options: [.cacheIntermediates: false])
        let estimator = VisionPoseEstimator()
        for window in config.windows {
            let reader = try AVAssetReader(asset: asset)
            reader.timeRange = CMTimeRange(start: CMTime(value: window.start, timescale: 1),
                duration: CMTime(value: window.end - window.start, timescale: 1))
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
            output.alwaysCopiesSampleData = false
            reader.add(output)
            guard reader.startReading() else { throw ProbeError.decoding }
            var witness: (Region, PresentationTime)?
            var ordinal = 0
            while let sample = output.copyNextSampleBuffer() {
                try autoreleasepool {
                    let pts = CMSampleBufferGetPresentationTimeStamp(sample)
                    let time = PresentationTime(value: pts.value, timescale: pts.timescale)
                    guard pts.isNumeric, pts.epoch == 0, time.seconds >= Double(window.start),
                          time.seconds < Double(window.end), let buffer = CMSampleBufferGetImageBuffer(sample) else {
                        throw ProbeError.decoding
                    }
                    let size = ImageSize(width: Double(CVPixelBufferGetWidth(buffer)), height: Double(CVPixelBufferGetHeight(buffer)))
                    if let format = CMSampleBufferGetFormatDescription(sample) {
                        let display = CMVideoFormatDescriptionGetPresentationDimensions(format, usePixelAspectRatio: true, useCleanAperture: true)
                        guard abs(display.width - size.width) < 1, abs(display.height - size.height) < 1 else { throw ProbeError.geometry }
                    }
                    guard let geometry = OrientedGeometry(sourceSize: size, preferredTransform: transform) else { throw ProbeError.geometry }
                    let source = CIImage(cvPixelBuffer: buffer)
                    var rendered = BitmapFrameRenderer.render(buffer, geometry: geometry,
                        colorSpace: source.colorSpace, maximumDimension: 1280)
                    if rendered == nil {
                        let g = geometry.coreImageTransform
                        let scale = min(1, 1280 / max(geometry.outputSize.width, geometry.outputSize.height))
                        let pixels = source.transformed(by: CGAffineTransform(a: g.a, b: g.b, c: g.c, d: g.d, tx: g.tx, ty: g.ty))
                            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
                        rendered = context.createCGImage(pixels, from: CGRect(x: 0, y: 0,
                            width: max(1, (geometry.outputSize.width * scale).rounded()),
                            height: max(1, (geometry.outputSize.height * scale).rounded())))
                    }
                    guard let image = rendered else { throw ProbeError.geometry }
                    let began = ContinuousClock.now
                    let full = try estimator.estimate(image: image, timestamp: time)
                    let fullMs = milliseconds(since: began)
                    if full.people.count > 1 { witness = nil }
                    else if let region = Region.select(full) { witness = (region, time) }
                    if let old = witness, time.elapsedSeconds(since: old.1) > 0.35 { witness = nil }
                    let pixels = try rgba(image)
                    var raw: PoseResult?, restored: PoseResult?, cropPixels: Data?, cropMs: Double?
                    if let selected = witness {
                        let (crop, data) = try checkedCrop(image, region: selected.0, original: pixels)
                        let cropStarted = ContinuousClock.now
                        raw = try estimator.estimate(image: crop, timestamp: time)
                        cropMs = milliseconds(since: cropStarted)
                        restored = try selected.0.restore(raw!, sourceSize: full.imageSize)
                        cropPixels = data
                        if abs(time.seconds - 493.8) < 0.000001 {
                            try save(image, to: directory.appendingPathComponent("failure-source-493.8.png"))
                            try save(crop, to: directory.appendingPathComponent("failure-crop-493.8.png"))
                        }
                    }
                    let row = Row(caseId: window.id, ordinal: ordinal, pose: full, cropPose: restored,
                        cropRawPose: raw, region: witness?.0, regionWitnessTime: witness?.1,
                        pixelsSHA256: hash(pixels), cropPixelsSHA256: cropPixels.map(hash),
                        fullMilliseconds: fullMs, cropMilliseconds: cropMs)
                    var encoded = try encoder.encode(row); encoded.append(10)
                    try file.write(contentsOf: encoded)
                    ordinal += 1
                    if ordinal % 300 == 0 { print("\(window.id): \(ordinal) original-time frames"); fflush(stdout) }
                }
            }
            guard reader.status == .completed else { throw ProbeError.decoding }
            print("Completed \(window.id): \(ordinal) frames"); fflush(stdout)
        }
        try file.synchronize()
    }

    static func milliseconds(since start: ContinuousClock.Instant) -> Double {
        let duration = start.duration(to: .now).components
        return Double(duration.seconds) * 1000 + Double(duration.attoseconds) / 1e15
    }
}
