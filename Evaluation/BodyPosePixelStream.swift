import AVFoundation
import CoreGraphics
import CryptoKit
import Foundation

/// Body-model input diagnostic only. Streams app-decoded pixels, source clocks
/// and a fresh Vision reference estimated from that exact original CGImage.
@main enum BodyPosePixelStream {
    struct Config: Decodable { let cases: [Case] }
    struct Case: Decodable { let id: String; let path: String; let sha256: String; let windows: [[Double]]? }
    struct ReferenceEstimator: PoseEstimator {
        let windows: [[Double]]?
        func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
            if windows?.contains(where: { $0[0] <= timestamp.seconds && timestamp.seconds < $0[1] }) ?? true {
                return try VisionPoseEstimator().estimate(image: image, timestamp: timestamp)
            }
            // The production reader has no seek. Outside declared diagnostic
            // windows, decode/render only; these placeholders never leave this
            // process or reach a measurement owner, counter or pose export.
            return PoseResult(timestamp: timestamp,
                imageSize: ImageSize(width: Double(image.width), height: Double(image.height)),
                people: [], backend: "NotInferredOutsideDiagnosticWindow", requestRevision: 0)
        }
    }
    enum Failure: Error { case usage, input, pixels, clock, hash }
    static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
    static func fileDigest(_ url: URL) throws -> String {
        let file = try FileHandle(forReadingFrom: url); defer { try? file.close() }
        var state = SHA256()
        while let bytes = try file.read(upToCount: 1_048_576), !bytes.isEmpty { state.update(data: bytes) }
        return state.finalize().map { String(format: "%02x", $0) }.joined()
    }
    static func rgba(_ image: CGImage) throws -> Data {
        var data = Data(count: image.width * image.height * 4)
        let ok = data.withUnsafeMutableBytes { bytes in
            guard let context = CGContext(data: bytes.baseAddress, width: image.width, height: image.height,
                bitsPerComponent: 8, bytesPerRow: image.width * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.setBlendMode(.copy); context.interpolationQuality = .none
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            return true
        }
        guard ok else { throw Failure.pixels }
        return data
    }
    static func selfCheck() throws {
        var bytes = Data()
        for index in 0..<48 {
            bytes.append(contentsOf: [UInt8(index * 3), UInt8(255-index * 2), UInt8(index), 255])
        }
        let image = CGImage(width: 8, height: 6, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: 32,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: CGDataProvider(data: bytes as CFData)!, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
        guard try rgba(image) == bytes else { throw Failure.pixels }
    }
    static func emit(_ header: [String: Any], payload: Data? = nil) throws {
        var encoded = try JSONSerialization.data(withJSONObject: header, options: [.sortedKeys])
        encoded.append(10)
        try FileHandle.standardOutput.write(contentsOf: encoded)
        if let payload { try FileHandle.standardOutput.write(contentsOf: payload) }
    }
    static func main() async throws {
        try selfCheck()
        if CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--self-check" {
            try emit(["kind": "self-check", "asymmetricRGBAControlPassed": true]); return
        }
        guard CommandLine.arguments.count == 3 else { throw Failure.usage }
        let config = try JSONDecoder().decode(Config.self,
            from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
        guard let item = config.cases.first(where: { $0.id == CommandLine.arguments[2] }) else { throw Failure.input }
        let url = URL(fileURLWithPath: item.path)
        guard try fileDigest(url) == item.sha256 else { throw Failure.hash }
        if let windows = item.windows {
            guard !windows.isEmpty, windows.allSatisfy({ $0.count == 2 && $0[0].isFinite && $0[1].isFinite && $0[0] < $0[1] }) else {
                throw Failure.input
            }
        }
        func selected(_ time: PresentationTime) -> Bool {
            item.windows?.contains(where: { $0[0] <= time.seconds && time.seconds < $0[1] }) ?? true
        }
        // Separate full-source BGRA decode inventory, never a nominal-FPS clock.
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw Failure.input }
        let inventoryReader = try AVAssetReader(asset: asset)
        let inventoryOutput = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA])
        inventoryReader.add(inventoryOutput)
        guard inventoryReader.startReading() else { throw Failure.input }
        var inventory: [PresentationTime] = []
        while let frame = inventoryOutput.copyNextSampleBuffer() {
            let pts = CMSampleBufferGetPresentationTimeStamp(frame)
            guard pts.isNumeric, pts.epoch == 0 else { throw Failure.clock }
            inventory.append(PresentationTime(value: pts.value, timescale: pts.timescale))
        }
        guard inventoryReader.status == .completed, !inventory.isEmpty else { throw Failure.input }
        let expected = inventory.filter(selected)
        guard !expected.isEmpty else { throw Failure.input }
        try emit(["kind": "source", "id": item.id, "sourceSHA256": item.sha256,
            "sourceInventoryFrames": inventory.count, "selectedFrames": expected.count,
            "scope": "Production VideoReplayReader pixels and fresh Vision1 reference; no counter or label inputs"])
        let reader = VideoReplayReader(estimator: ReferenceEstimator(windows: item.windows))
        _ = try await reader.open(url)
        var ordinal = 0
        var decoded = 0
        let end = item.windows?.map { $0[1] }.max()
        while let frame = try await reader.nextFrame() {
            let time = frame.pose.timestamp
            guard decoded < inventory.count, time == inventory[decoded] else { throw Failure.clock }
            decoded += 1
            if let end, time.seconds >= end { break }
            guard selected(time) else { continue }
            guard ordinal < expected.count, time == expected[ordinal] else { throw Failure.clock }
            try autoreleasepool {
                guard let nativeBytes = frame.image.dataProvider?.data else { throw Failure.pixels }
                let bytes = try rgba(frame.image)
                guard frame.pose.backend == "Apple Vision 2D", frame.pose.requestRevision == 1 else { throw Failure.input }
                let vision = try JSONSerialization.jsonObject(with: JSONEncoder().encode(frame.pose))
                try emit(["kind": "frame", "ordinal": ordinal, "visionPose": vision,
                    "timestamp": ["value": time.value, "timescale": Int64(time.timescale)],
                    "width": frame.image.width, "height": frame.image.height, "payloadBytes": bytes.count,
                    "providerSHA256": digest(nativeBytes as Data), "rgbaSHA256": digest(bytes)], payload: bytes)
            }
            ordinal += 1
        }
        await reader.close()
        guard ordinal == expected.count, try fileDigest(url) == item.sha256 else { throw Failure.hash }
        try emit(["kind": "end", "id": item.id, "sourceSHA256": item.sha256,
            "sourceInventoryFrames": inventory.count, "decodedPrefixFrames": decoded,
            "selectedFrames": ordinal, "sourceClockVerified": true])
    }
}
