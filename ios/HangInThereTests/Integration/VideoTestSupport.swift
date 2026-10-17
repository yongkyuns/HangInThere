import AVFoundation
import CoreImage
import Dispatch
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
    // Write BGRA samples directly: mechanics must not depend on a native writer
    // starting or finalizing. Compressed decoding has its own pinned
    // fixture in VideoReplayReaderTests; there is no silent codec fallback here.
    static func makeVideo(transform: CGAffineTransform = .identity,
                          timestamps: [CMTime] = VideoTestSupport.timestamps,
                          width: Int = 160, height: Int = 96,
                          cyclingQuadrants: Bool = false) async throws -> URL {
        try await RawMovieFixture.make(transform: transform, timestamps: timestamps,
                                       width: width, height: height, cyclingQuadrants: cyclingQuadrants)
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

// Test-only QuickTime container authoring. Mechanics still decode every sample
// through the production AVAssetReader; no native encoder/writer or substitute
// image is involved. Atom layouts follow Apple's QuickTime File Format.
private enum RawMovieFixture {
    private static let queue = DispatchQueue(label: "dev.hanginthere.tests.raw-movie")
    // This helper authors small mechanics fixtures, never a recording corpus.
    // Caps also bound sample-table sizes before converting atom lengths to UInt32.
    private static let maximumFrames = 512
    private static let maximumFrameBytes: UInt64 = 4 * 1024 * 1024
    private static let maximumMovieBytes: UInt64 = 64 * 1024 * 1024

    private struct WorkBudget: Sendable {
        let deadline = ContinuousClock.now.advanced(by: .seconds(10))
        let cancellation = ReplayFrameDiscardSignal()

        func check() throws {
            try cancellation.check()
            guard ContinuousClock.now < deadline else { throw FixtureError.failed("Raw fixture creation timed out.") }
        }
    }

    static func make(transform: CGAffineTransform, timestamps: [CMTime], width: Int,
                     height: Int, cyclingQuadrants: Bool) async throws -> URL {
        try Task.checkCancellation()
        let budget = WorkBudget()
        let url: URL = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    do { continuation.resume(returning: try write(transform: transform, timestamps: timestamps,
                        width: width, height: height, cyclingQuadrants: cyclingQuadrants, budget: budget)) }
                    catch { continuation.resume(throwing: error) }
                }
            }
        } onCancel: { budget.cancellation.discard() }
        do { try Task.checkCancellation(); return url }
        catch { try? FileManager.default.removeItem(at: url); throw error }
    }

    private static func write(transform: CGAffineTransform, timestamps: [CMTime], width: Int,
                              height: Int, cyclingQuadrants: Bool, budget: WorkBudget) throws -> URL {
        try budget.check()
        guard width > 0, height > 0, width <= Int(UInt16.max), height <= Int(UInt16.max),
              !timestamps.isEmpty, timestamps.count <= maximumFrames,
              timestamps.allSatisfy({ $0.isNumeric && $0.epoch == 0 && $0.timescale > 0 && $0.value >= 0 }),
              zip(timestamps, timestamps.dropFirst()).allSatisfy({ CMTimeCompare($0, $1) < 0 }) else {
            throw FixtureError.failed("Invalid raw fixture dimensions or timestamps.")
        }
        let frameBytes = UInt64(width) * UInt64(height) * 4
        guard frameBytes <= maximumFrameBytes, frameBytes * UInt64(timestamps.count) <= maximumMovieBytes else {
            throw FixtureError.failed("Raw fixture exceeds test byte limits.")
        }
        // Use an exact common timebase, including the original 0.1s final hold.
        var timescale: Int64 = 100
        func gcd(_ a: Int64, _ b: Int64) -> Int64 { b == 0 ? a : gcd(b, a % b) }
        for time in timestamps {
            try budget.check()
            let multiplier = Int64(time.timescale) / gcd(timescale, Int64(time.timescale))
            guard timescale <= Int64(Int32.max) / multiplier else { throw FixtureError.failed("Fixture timebase exceeds QuickTime limits.") }
            timescale *= multiplier
        }
        let ticks = try timestamps.map { time -> UInt32 in
            try budget.check()
            let multiplier = timescale / Int64(time.timescale)
            guard time.value <= Int64(UInt32.max) / multiplier else { throw FixtureError.failed("Fixture PTS exceeds QuickTime limits.") }
            return UInt32(time.value * multiplier)
        }
        let lastHold = UInt32(timescale / 10)
        guard ticks.last! <= UInt32.max - lastHold else { throw FixtureError.failed("Fixture duration exceeds QuickTime limits.") }
        let duration = ticks.last! + lastHold
        let sampleBytes = UInt32(frameBytes)
        func fixed(_ value: CGFloat) throws -> UInt32 {
            guard value.isFinite, let signed = Int32(exactly: (Double(value) * 65_536).rounded()) else {
                throw FixtureError.failed("Fixture matrix exceeds QuickTime limits.")
            }
            return UInt32(bitPattern: signed)
        }
        let matrix = try [fixed(transform.a), fixed(transform.b), 0,
                          fixed(transform.c), fixed(transform.d), 0,
                          fixed(transform.tx), fixed(transform.ty), 0x4000_0000].map(u32).reduce(Data(), +)
        let identity = [UInt32(0x10000), 0, 0, 0, 0x10000, 0, 0, 0, 0x4000_0000].map(u32).reduce(Data(), +)
        let movieHeader = atom("mvhd", zeros(12), u32(UInt32(timescale)), u32(duration),
            u32(0x10000), u16(0x100), zeros(10), identity, zeros(24), u32(2))
        let trackHeader = atom("tkhd", u32(7), zeros(8), u32(1), u32(0), u32(duration),
            zeros(16), matrix, u32(UInt32(width) << 16), u32(UInt32(height) << 16))
        let mediaHeader = atom("mdhd", zeros(12), u32(UInt32(timescale)), u32(duration), zeros(4))
        let handler = atom("hdlr", zeros(4), Data("mhlrvide".utf8), zeros(12), Data([0]))
        let dataReference = atom("dinf", atom("dref", zeros(4), u32(1), atom("url ", u32(1))))
        let sampleDescription = atom("stsd", zeros(4), u32(1), atom("BGRA", zeros(6), u16(1),
            zeros(16), u16(UInt16(width)), u16(UInt16(height)), u32(0x0048_0000), u32(0x0048_0000),
            zeros(4), u16(1), zeros(32), u16(32), u16(UInt16.max)))
        var sampleTimes = Data()
        for index in ticks.indices {
            try budget.check()
            sampleTimes += u32(1)
            sampleTimes += u32(index + 1 < ticks.count ? ticks[index + 1] - ticks[index] : lastHold)
        }
        let fileHeader = atom("ftyp", Data("qt  ".utf8), u32(0), Data("qt  ".utf8))
        // Shift presentation timestamps directly. An initial empty edit would
        // make AVAssetReader synthesize a black sample at zero.
        let compositionTimes = ticks[0] == 0 ? Data() : atom("ctts", zeros(4), u32(1),
            u32(UInt32(ticks.count)), u32(ticks[0]))
        let sampleTable = atom("stbl", sampleDescription,
            atom("stts", zeros(4), u32(UInt32(ticks.count)), sampleTimes),
            compositionTimes,
            atom("stsc", zeros(4), u32(1), u32(1), u32(UInt32(ticks.count)), u32(1)),
            atom("stsz", zeros(4), u32(sampleBytes), u32(UInt32(ticks.count))),
            atom("stco", zeros(4), u32(1), u32(UInt32(fileHeader.count + 8))))
        let media = atom("mdia", mediaHeader, handler,
            atom("minf", atom("vmhd", u32(1), zeros(8)), dataReference, sampleTable))
        let movie = atom("moov", movieHeader, atom("trak", trackHeader, media))
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("quadrants-\(UUID().uuidString).mov")
        try budget.check()
        guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw FixtureError.failed("Cannot create raw fixture.") }
        do {
            let file = try FileHandle(forWritingTo: url)
            defer { try? file.close() }
            try file.write(contentsOf: fileHeader)
            try file.write(contentsOf: u32(sampleBytes * UInt32(ticks.count) + 8) + Data("mdat".utf8))
            for index in ticks.indices {
                try budget.check()
                try file.write(contentsOf: quadrantBytes(width: width, height: height,
                    phase: cyclingQuadrants ? index % 4 : 0, budget: budget))
            }
            try budget.check()
            try file.write(contentsOf: movie)
            try file.close()
            try budget.check()
            return url
        } catch { try? FileManager.default.removeItem(at: url); throw error }
    }

    private static func quadrantBytes(width: Int, height: Int, phase: Int, budget: WorkBudget) throws -> Data {
        try budget.check()
        let colors: [(UInt8, UInt8, UInt8)] = [(240, 0, 0), (0, 240, 0), (0, 0, 240), (240, 240, 0)]
        var data = Data(count: width * height * 4)
        try data.withUnsafeMutableBytes { (bytes: UnsafeMutableRawBufferPointer) in
            for y in 0..<height {
                try budget.check()
                for x in 0..<width {
                let quadrant = (y < height / 2 ? 0 : 2) + (x < width / 2 ? 0 : 1)
                let rgb = colors[(quadrant + phase) % 4]; let offset = (y * width + x) * 4
                bytes[offset] = rgb.2; bytes[offset + 1] = rgb.1; bytes[offset + 2] = rgb.0; bytes[offset + 3] = 255
            } }
        }
        return data
    }
    private static func zeros(_ count: Int) -> Data { Data(count: count) }
    private static func u16(_ value: UInt16) -> Data { var big = value.bigEndian; return withUnsafeBytes(of: &big) { Data($0) } }
    private static func u32(_ value: UInt32) -> Data { var big = value.bigEndian; return withUnsafeBytes(of: &big) { Data($0) } }
    private static func atom(_ name: String, _ parts: Data...) -> Data {
        let payload = parts.reduce(into: Data()) { $0.append($1) }
        return u32(UInt32(payload.count + 8)) + Data(name.utf8) + payload
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
