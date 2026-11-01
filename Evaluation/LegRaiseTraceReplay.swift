import CryptoKit
import Foundation
import WorkoutCore

/// Reuses pinned production pose observations after a common-policy edit.
/// No native SDK or camera-quality claim is added by replaying a trace.
@main enum LegRaiseTraceReplay {
    struct Config: Decodable { let cases: [Case] }
    struct Case: Decodable { let id: String; let trace: String; let sha256: String }
    enum Failure: Error { case usage, hash, existingOutput }
    static func main() throws {
        let args = CommandLine.arguments
        guard args.count == 4, ["legRaise", "lyingLegRaise"].contains(args[3]) else { throw Failure.usage }
        let config = try JSONDecoder().decode(Config.self, from: Data(contentsOf: URL(fileURLWithPath: args[1])))
        let output = URL(fileURLWithPath: args[2])
        guard !FileManager.default.fileExists(atPath: output.path) else { throw Failure.existingOutput }
        var reports: [[String: Any]] = []
        for spec in config.cases {
            let data = try Data(contentsOf: URL(fileURLWithPath: spec.trace))
            guard SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == spec.sha256 else { throw Failure.hash }
            let counter = FloorWorkoutBridge(exercise: args[3], sessionId: spec.id.replacingOccurrences(of: "_", with: "-"), trackedAtEpochMilliseconds: 0)
            var events: [[String: Any]] = [], records: [String: [String: Any]] = [:]
            func drain() throws {
                while let value = counter.nextWriteJson() {
                    let write = try JSONSerialization.jsonObject(with: Data(value.utf8)) as! [String: Any]
                    let record = write["record"] as! [String: Any]
                    records[record["id"] as! String] = record
                    counter.acknowledgeWrite()
                }
            }
            for line in String(decoding: data, as: UTF8.self).split(separator: "\n") {
                let state = try counter.observeJson(poseJson: String(line), camera: "ready")
                let row = try JSONSerialization.jsonObject(with: Data(state.utf8)) as! [String: Any]
                if let rep = row["rep"] as? [String: Any] { events.append(rep) }
                try drain()
            }
            counter.finish(reason: "endOfInput"); try drain()
            reports.append(["id": spec.id, "events": events, "records": Array(records.values)])
        }
        try JSONSerialization.data(withJSONObject: reports, options: [.prettyPrinted, .sortedKeys]).write(to: output)
    }
}
