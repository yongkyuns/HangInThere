@preconcurrency import AVFoundation
import XCTest
@testable import HangInThere

final class CountedSetVideoRecorderTests: XCTestCase {
    private func record(_ id: String, start: Double = 102, end: Double = 105, completed: Bool = true) -> WorkoutSetRecord {
        let angle = WorkoutAngleMetrics(minimum: 80, maximum: 170, mean: 120, observedSeconds: end - start, coverage: 1)
        let rep = WorkoutRepRecord(id: id + "-rep", setId: id, ordinal: 1, exercise: .pullUp,
            startedAt: start, extremeAt: start + 1, acceptedAt: start + 1, returnedAt: completed ? end : nil,
            closedAt: end, closeReason: completed ? "returned" : "disengaged", outcome: "accepted",
            outboundSeconds: 1, returnSeconds: completed ? end - start - 1 : nil, left: angle, right: angle,
            shoulderExcursion: 0.1, bilateralExtremeOffsetSeconds: 0, usableDurationSeconds: end - start,
            durationCoverage: 1, samples: [], curveTruncated: false, countPolicyVersion: 8, metricVersion: 1,
            acceptanceReason: nil, formVerification: nil)
        return WorkoutSetRecord(id: id, exercise: .pullUp, startedAt: start - 1, endedAt: end + 2,
            endReason: "disengaged", reps: [rep], backend: "authored video mechanics", modelRevision: 1,
            schemaVersion: 1, lifecycleVersion: 1, trackingSide: .left, imageSize: nil, referenceEdge: nil,
            trackedAtEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000))
    }
    private func frame(_ time: Double, green: Bool) throws -> CMSampleBuffer {
        var pixel: CVPixelBuffer?
        XCTAssertEqual(CVPixelBufferCreate(nil, 64, 96, kCVPixelFormatType_32BGRA, nil, &pixel), kCVReturnSuccess)
        let buffer = try XCTUnwrap(pixel)
        CVPixelBufferLockBaseAddress(buffer, [])
        let bytes = try XCTUnwrap(CVPixelBufferGetBaseAddress(buffer)).assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        for y in 0..<96 { for x in 0..<64 {
            let i = y * stride + x * 4
            bytes[i] = green ? 0 : 255; bytes[i + 1] = green ? 255 : 0; bytes[i + 2] = 0; bytes[i + 3] = 255
        } }
        CVPixelBufferUnlockBaseAddress(buffer, [])
        var format: CMVideoFormatDescription?
        XCTAssertEqual(CMVideoFormatDescriptionCreateForImageBuffer(allocator: nil, imageBuffer: buffer, formatDescriptionOut: &format), noErr)
        var timing = CMSampleTimingInfo(duration: CMTime(value: 1, timescale: 10), presentationTimeStamp: CMTime(seconds: time, preferredTimescale: 1000), decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        XCTAssertEqual(CMSampleBufferCreateReadyWithImageBuffer(allocator: nil, imageBuffer: buffer, formatDescription: try XCTUnwrap(format), sampleTiming: &timing, sampleBufferOut: &sample), noErr)
        return try XCTUnwrap(sample)
    }
    private func feed(_ recorder: CountedSetVideoRecorder, from: Int, to: Int, green: Range<Int>) async throws {
        for tenth in from..<to {
            recorder.append(try frame(Double(tenth) / 10, green: green.contains(tenth)))
            await recorder.drain()
            try await Task.sleep(for: .milliseconds(5)) // Let the real encoder consume its bounded input.
        }
    }
    private func finish(_ recorder: CountedSetVideoRecorder, _ record: WorkoutSetRecord) async throws -> URL? {
        try await withCheckedThrowingContinuation { continuation in
            recorder.complete(record) { continuation.resume(with: $0) }
        }
    }
    func testCompletedRangeExcludesSetupReleaseAndUnfinishedAttempts() {
        var set = record("range")
        let partial = record("partial", start: 99, end: 101, completed: false).reps[0]
        set.reps.insert(partial, at: 0)
        XCTAssertEqual(CountedSetVideoRange(record: set)?.start, 102)
        XCTAssertEqual(CountedSetVideoRange(record: set)?.end, 105)
        XCTAssertNil(CountedSetVideoRange(record: record("practice", completed: false)))
    }
    func testRealEncoderTrimsNonzeroCameraClockAcrossChunksAndKeepsConsecutiveSetsSeparate() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let recorder = CountedSetVideoRecorder(directory: directory)
        recorder.setEnabled(true)
        // The owner confirms after the first cycle. Pre-roll must recover its start.
        try await feed(recorder, from: 1000, to: 1060, green: 1020..<1050)
        recorder.begin(setId: "first", startedAt: 101)
        try await feed(recorder, from: 1060, to: 1080, green: 1020..<1050)
        let firstResult = try await finish(recorder, record("first"))
        let first = try XCTUnwrap(firstResult)
        let asset = AVURLAsset(url: first)
        let duration = try await asset.load(.duration).seconds
        XCTAssertEqual(duration, 3, accuracy: 0.11)
        let generator = AVAssetImageGenerator(asset: asset)
        for seconds in [0.2, 2.8] {
            let image = try await generator.image(at: CMTime(seconds: seconds, preferredTimescale: 1000)).image
            let rgb = VideoTestSupport.rgb(image, normalizedTopLeft: Point2D(x: 0.5, y: 0.5))
            XCTAssertGreaterThan(rgb[1], 200, "Setup/release blue frames leaked into the retained clip")
            XCTAssertLessThan(rgb[2], 30)
        }
        try await feed(recorder, from: 1080, to: 1140, green: 1090..<1120)
        recorder.begin(setId: "second", startedAt: 108)
        let secondResult = try await finish(recorder, record("second", start: 109, end: 112))
        let second = try XCTUnwrap(secondResult)
        XCTAssertNotEqual(first, second)
        let secondDuration = try await AVURLAsset(url: second).load(.duration).seconds
        XCTAssertEqual(secondDuration, 3, accuracy: 0.11)
        let store = WorkoutRecordStore(directory: directory.appendingPathComponent("store"))
        let savedId = try await store.saveRecordedSetVideo(source: first, record: record("first"))
        let id = try XCTUnwrap(savedId)
        let media = try await store.libraryMediaURL(id: id)
        XCTAssertNotNil(media)
        let reviewResult = try await store.libraryVideoReview(id: id)
        let review = try XCTUnwrap(reviewResult)
        XCTAssertEqual(review.markers.map(\.clipSeconds), [0, 1, 3])
        let folder = try XCTUnwrap(review.thumbnailDirectory)
        XCTAssertNil(review.thumbnailError)
        for marker in review.markers {
            XCTAssertTrue(FileManager.default.fileExists(atPath: folder.appendingPathComponent(marker.filename).path))
        }
        _ = try await store.libraryCommand("{\"action\":\"delete\",\"setId\":\"first\",\"atEpochMilliseconds\":\(Int64(Date().timeIntervalSince1970 * 1000))}")
        let resurrected = try await store.saveRecordedSetVideo(source: first, record: record("first"))
        XCTAssertNil(resurrected)
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.path))
        try await store.save(record("second", start: 109, end: 112))
        let attached = try await store.attachLibraryMedia(source: second, setId: "second")
        let manualReview = try await store.libraryVideoReview(id: attached)
        XCTAssertTrue(manualReview?.markers.isEmpty == true, "Never guess a manually attached video's clock")
    }
    func testExpiredPreRollIsReportedInsteadOfSavingAnIncompleteSetVideo() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let recorder = CountedSetVideoRecorder(directory: directory)
        recorder.setEnabled(true)
        try await feed(recorder, from: 1000, to: 1200, green: 1020..<1050)
        recorder.begin(setId: "late", startedAt: 101)
        do {
            _ = try await finish(recorder, record("late"))
            XCTFail("A clip missing the counted start must not be published")
        } catch { XCTAssertEqual(error as? CountedSetVideoError, .unavailable) }
    }
    func testZeroRepAndDisabledRecordingPublishNoClip() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let recorder = CountedSetVideoRecorder(directory: directory)
        recorder.setEnabled(true); recorder.begin(setId: "practice", startedAt: 101)
        try await feed(recorder, from: 1000, to: 1060, green: 1020..<1050)
        let practice = try await finish(recorder, record("practice", completed: false))
        XCTAssertNil(practice)
        recorder.setEnabled(false); recorder.begin(setId: "off", startedAt: 101)
        let disabled = try await finish(recorder, record("off"))
        XCTAssertNil(disabled)
    }
    func testTempoUsesLiftingPhaseAndRejectsChangedRangeOrPoorCoverage() throws {
        func set(_ exercise: ExerciseCounter.Exercise, changedRange: Bool = false, poorCoverage: Bool = false) throws -> WorkoutSetRecord {
            var result = record("tempo")
            var object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(result)) as! [String: Any]
            object["exercise"] = exercise.rawValue
            let original = (object["reps"] as! [[String: Any]])[0]
            object["reps"] = (0..<3).map { index -> [String: Any] in
                var rep = original
                rep["id"] = "tempo-\(index)"; rep["ordinal"] = index + 1
                rep["exercise"] = exercise.rawValue
                rep["startedAt"] = Double(index * 10)
                rep["extremeAt"] = Double(index * 10) + (exercise == .pullUp && index == 2 ? 1.5 : 1)
                rep["returnedAt"] = Double(index * 10) + (index == 2 ? 2.5 : 2)
                if poorCoverage && index == 2 { rep["durationCoverage"] = 0.4 }
                if changedRange && index == 2 {
                    for side in ["left", "right"] { var angle = rep[side] as! [String: Any]; angle["minimum"] = 140; rep[side] = angle }
                }
                return rep
            }
            result = try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: object))
            return result
        }
        for exercise in ExerciseCounter.Exercise.allCases {
            let tempo = WorkoutTempo(record: try set(exercise))
            XCTAssertEqual(try XCTUnwrap(tempo.change), 0.5, accuracy: 0.001)
            XCTAssertNil(WorkoutTempo(record: try set(exercise, changedRange: true)).change)
            XCTAssertNil(WorkoutTempo(record: try set(exercise, poorCoverage: true)).change)
        }
        XCTAssertNil(WorkoutTempo(record: record("one")).change)
    }
}
