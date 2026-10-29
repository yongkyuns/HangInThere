import CryptoKit
import Foundation
import WorkoutCore

/// Compares the actual Apple FFI with saved JVM decisions on original hand
/// observations and real trained weights. Interoperability, not accuracy.
@main enum BinaryGripClassifierBridgeCheck {
    static func main() throws {
        guard CommandLine.arguments.count == 4 else { throw CocoaError(.fileReadInvalidFileName) }
        let model = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
        let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[2]))
        var invalidModelRejected = false
        do { _ = try BinaryGripClassifierBridge(modelJson: "{}") } catch { invalidModelRejected = true }
        guard invalidModelRejected else { throw CocoaError(.coderInvalidValue) }
        let classifier = try BinaryGripClassifierBridge(modelJson: String(decoding: model, as: UTF8.self))
        var count = 0; var maximumDifference = 0.0
        for line in String(decoding: data, as: UTF8.self).split(separator: "\n") {
            let row = try JSONSerialization.jsonObject(with: Data(line.utf8)) as! [String: Any]
            let observation = String(decoding: try JSONSerialization.data(withJSONObject: row["observation"]!), as: UTF8.self)
            let expected = row["decisions"] as! [String: [String: Any]]
            for side in ["left", "right"] {
                let encoded = try classifier.classifyJson(observationJson: observation, side: side)
                let actual = try JSONSerialization.jsonObject(with: Data(encoded.utf8)) as! [String: Any]
                guard actual["grip"] as? String == expected[side]!["grip"] as? String else { throw CocoaError(.coderInvalidValue) }
                let a = actual["underhandScore"] as? Double; let b = expected[side]!["underhandScore"] as? Double
                guard (a == nil) == (b == nil) else { throw CocoaError(.coderInvalidValue) }
                if let a, let b {
                    maximumDifference = max(maximumDifference, abs(a-b))
                    guard abs(a-b) <= 1e-12 else { throw CocoaError(.coderInvalidValue) }
                }
                count += 1
            }
        }
        let report: [String: Any] = ["decisions": count, "maximumScoreDifference": maximumDifference,
            "modelSHA256": hash(model), "observationsSHA256": hash(data), "passed": true, "invalidModelRejected": invalidModelRejected,
            "scope": "Actual Apple FFI/JVM agreement on local development observations; not independent accuracy or phone inference timing"]
        try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]).write(
            to: URL(fileURLWithPath: CommandLine.arguments[3]), options: .withoutOverwriting)
        print("Apple/JVM classifier parity: \(count) decisions pass")
    }
    static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
}
