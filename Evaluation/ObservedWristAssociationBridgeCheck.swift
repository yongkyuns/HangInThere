import Foundation
import WorkoutCore

/// Source-fixture correspondence through the actual Apple ABI. These checks
/// neither rerun a pose SDK nor qualify exercise counts or device performance.
enum ObservedWristAssociationBridgeCheck {
    static func run() throws -> Int {
        let data = try Data(contentsOf: URL(fileURLWithPath: "Evaluation/fixtures/observed-wrist-source.json"))
        let fixture = try JSONSerialization.jsonObject(with: data) as! [String: Any]
        let cases = fixture["cases"] as! [[String: Any]]
        precondition(cases.count == 4)
        let bridge = ObservedWristAssociationBridge()
        func associate(_ pose: [String: Any], time: [String: Any]? = nil) throws -> [String: Any]? {
            let frame: [String: Any] = ["pose": pose, "handSourceTime": time ?? (pose["timestamp"] as! [String: Any]), "requiresJointConfidence": true]
            let input = String(decoding: try JSONSerialization.data(withJSONObject: frame), as: UTF8.self)
            guard let output = try bridge.associateJson(input: input) else { return nil }
            return try JSONSerialization.jsonObject(with: Data(output.utf8)) as? [String: Any]
        }
        var checks = 0
        for value in cases {
            let pose = value["pose"] as! [String: Any]
            let original = try associate(pose)!
            let matches = original["matches"] as! [[String: Any]]
            let sides = matches.map { ($0["bodyWrists"] as! [[String: Any]]).map { $0["side"] as! String } }
            precondition(sides == (value["expectedSides"] as! [String]).map { [$0] })
            precondition(matches.allSatisfy { ($0["originalPersonIndex"] as! Int) == 0 })
            precondition((original["unresolvedHands"] as! [Any]).isEmpty)
            checks += 1

            var partial = pose
            partial["people"] = (pose["people"] as! [[String: Any]]).map { person in
                var person = person
                person["landmarks"] = (person["landmarks"] as! [[String: Any]]).filter {
                    !["leftElbow", "rightElbow"].contains($0["joint"] as! String)
                }
                return person
            }
            var hand = pose["gripObservation"] as! [String: Any]
            hand["hands"] = (hand["hands"] as! [[String: Any]]).map { observation in
                var observation = observation
                observation["points"] = (observation["points"] as! [[String: Any]]).filter { ($0["joint"] as! String) == "wrist" }
                return observation
            }
            partial["gripObservation"] = hand
            let measuredWristOnly = try associate(partial)!
            precondition(NSDictionary(dictionary: original).isEqual(to: measuredWristOnly))
            checks += 1

            var wrongTime = pose["timestamp"] as! [String: Any]
            wrongTime["value"] = (wrongTime["value"] as! NSNumber).int64Value + 1
            let wrongSource = try associate(pose, time: wrongTime)
            precondition(wrongSource == nil)
            checks += 1
        }
        return checks
    }
}
