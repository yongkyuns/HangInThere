// Re-run the exact framework-free production counter on recorded observations.
// This does not perform new inference. Counting requires an independent bar edge.
import Foundation

private struct Row: Decodable {
    let frameIndex: Int
    let timebase: String
    let timestamp: PresentationTime?
    let imageSize: ImageSize
    let people: [PoseObservation]
    let backend: String
    let requestRevision: Int?
}
private struct Report: Encodable {
    let scope = "saved-prediction movement diagnostic; optional fixed apparatus reference; no strict-form verdict"
    let frames: Int
    let usableTrackingFrames: Int
    let trackingCoverage: Double?
    let referenceEdge: BarSegment?
    let summary: ExerciseCounter.Summary
    let events: [ExerciseCounter.Event]
}

@main private enum CountReplay {
    static func main() {
        do { try run() }
        catch {
            FileHandle.standardError.write(Data("Counting diagnostic failed: \(error.localizedDescription)\n".utf8))
            exit(2)
        }
    }
    private static func run() throws {
        guard CommandLine.arguments.count == 3 || CommandLine.arguments.count == 7,
              let exercise = ExerciseCounter.Exercise(rawValue: CommandLine.arguments[1]),
              let side = ArmMeasurement.Side(rawValue: CommandLine.arguments[2]) else {
            throw failure("Usage: count-replay pullUp|dip left|right [barX1 barY1 barX2 barY2] < observations.jsonl")
        }
        let referenceEdge: BarSegment?
        if CommandLine.arguments.count == 7 {
            let values = CommandLine.arguments[3...6].compactMap(Double.init)
            guard values.count == 4 else { throw failure("Bar coordinates must be finite numbers.") }
            let edge = BarSegment(a: Point2D(x: values[0], y: values[1]),
                                  b: Point2D(x: values[2], y: values[3]))
            guard edge.isValid else { throw failure("Bar reference must be a valid finite segment.") }
            referenceEdge = edge
        } else {
            referenceEdge = nil
        }

        var counter = ExerciseCounter(exercise: exercise, side: side)
        var frames = 0
        var usableTrackingFrames = 0
        var events: [ExerciseCounter.Event] = []
        while let line = readLine() {
            let row = try JSONDecoder().decode(Row.self, from: Data(line.utf8))
            guard row.frameIndex == frames, row.timebase == "source_pts",
                  let timestamp = row.timestamp, timestamp.seconds.isFinite else {
                throw failure("Ordered frames and actual source timestamps are required; no assumed FPS.")
            }
            let pose = PoseResult(timestamp: timestamp, imageSize: row.imageSize,
                                  people: row.people, backend: row.backend,
                                  requestRevision: row.requestRevision ?? 0)
            if let event = counter.consume(pose, referenceEdge: referenceEdge) { events.append(event) }
            if referenceEdge != nil, counter.trackingIssue == nil {
                usableTrackingFrames += 1
            }
            frames += 1
        }
        guard frames > 0 else { throw failure("No observations supplied.") }
        if let event = counter.finish() { events.append(event) }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let trackingCoverage = frames > 0
            ? Double(usableTrackingFrames) / Double(frames)
            : nil
        print(String(decoding: try encoder.encode(Report(
            frames: frames,
            usableTrackingFrames: usableTrackingFrames,
            trackingCoverage: trackingCoverage,
            referenceEdge: referenceEdge,
            summary: counter.summary,
            events: events
        )), as: UTF8.self))
    }
    private static func failure(_ message: String) -> NSError {
        NSError(domain: "CountReplay", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}