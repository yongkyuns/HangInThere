// Re-run the exact framework-free production counter on recorded observations.
// This does not perform new inference or evaluate counting accuracy.
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
    let scope = "saved-prediction movement diagnostic; no independent temporal labels"
    let frames: Int
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
        guard CommandLine.arguments.count == 3,
              let exercise = ExerciseCounter.Exercise(rawValue: CommandLine.arguments[1]),
              let side = ArmMeasurement.Side(rawValue: CommandLine.arguments[2]) else {
            throw failure("Usage: count-replay pullUp|dip left|right < observations.jsonl")
        }
        var counter = ExerciseCounter(exercise: exercise, side: side)
        var frames = 0
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
            if let event = counter.consume(pose) { events.append(event) }
            frames += 1
        }
        guard frames > 0 else { throw failure("No observations supplied.") }
        if let event = counter.finish() { events.append(event) }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        print(String(decoding: try encoder.encode(Report(frames: frames, summary: counter.summary, events: events)), as: UTF8.self))
    }
    private static func failure(_ message: String) -> NSError {
        NSError(domain: "CountReplay", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
