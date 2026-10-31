import CryptoKit
import Foundation
import WorkoutCore

/// Common-rule/Apple ABI parity on pinned native observations. Camera readiness
/// is an explicit diagnostic assumption; no device or capture qualification.
@main enum SideViewPushUpReplay {
    enum Failure: Error { case usage, manifest, hash, outputExists }
    static func main() throws {
        guard CommandLine.arguments.count == 3 else { throw Failure.usage }
        let manifest = URL(fileURLWithPath: CommandLine.arguments[1])
        let output = URL(fileURLWithPath: CommandLine.arguments[2])
        try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
        guard let config = try JSONSerialization.jsonObject(with: Data(contentsOf: manifest)) as? [String: Any],
              let cases = config["cases"] as? [[String: Any]] else { throw Failure.manifest }
        for spec in cases {
            guard let id = spec["id"] as? String, id.range(of: "^[a-zA-Z0-9-]+$", options: .regularExpression) != nil,
                  let path = spec["trace"] as? String, let hash = spec["traceSHA256"] as? String else { throw Failure.manifest }
            let bytes = try Data(contentsOf: URL(fileURLWithPath: path))
            guard SHA256.hash(data: bytes).map({ String(format: "%02x", $0) }).joined() == hash else { throw Failure.hash }
            let lines = String(decoding: bytes, as: UTF8.self).split(separator: "\n")
            for engine in ["visionPose", "mediaPipePose"] {
                let motion = SideViewPushUpMotionBridge()
                let target = output.appendingPathComponent("\(id)-\(engine)-frames.jsonl")
                guard !FileManager.default.fileExists(atPath: target.path) else { throw Failure.outputExists }
                FileManager.default.createFile(atPath: target.path, contents: nil)
                let file = try FileHandle(forWritingTo: target)
                var sets: [Int: [String: Any]] = [:]
                for line in lines {
                    try autoreleasepool {
                        guard let row = try JSONSerialization.jsonObject(with: Data(line.utf8)) as? [String: Any],
                              let pose = row[engine] else { throw Failure.manifest }
                        let poseJSON = String(decoding: try JSONSerialization.data(withJSONObject: pose, options: [.sortedKeys]), as: UTF8.self)
                        let state = try motion.observeJson(poseJson: poseJSON, camera: "ready")
                        var data = Data(state.utf8); data.append(10); try file.write(contentsOf: data)
                        if let object = try JSONSerialization.jsonObject(with: Data(state.utf8)) as? [String: Any],
                           let ended = object["endedSet"] as? [String: Any], let index = ended["index"] as? Int {
                            sets[index] = ended
                        }
                    }
                }
                try file.close()
                let finish = motion.finishJson(reason: "endOfInput")
                if let ended = try JSONSerialization.jsonObject(with: Data(finish.utf8), options: [.fragmentsAllowed]) as? [String: Any],
                   let index = ended["index"] as? Int { sets[index] = ended }
                let sorted = sets.keys.sorted().compactMap { sets[$0] }
                try JSONSerialization.data(withJSONObject: sorted, options: [.prettyPrinted, .sortedKeys])
                    .write(to: output.appendingPathComponent("\(id)-\(engine)-sets.json"))
                print("\(id) / \(engine): \(lines.count) pinned source frames")
            }
        }
    }
}
