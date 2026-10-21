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
        func uniqueMatches(_ pose: [String: Any], time: [String: Any]? = nil) throws -> [[String: Any]]? {
            let frame: [String: Any] = ["pose": pose, "handSourceTime": time ?? (pose["timestamp"] as! [String: Any]), "requiresJointConfidence": true]
            let input = String(decoding: try JSONSerialization.data(withJSONObject: frame), as: UTF8.self)
            guard let output = try bridge.uniqueArmMatchesJson(input: input) else { return nil }
            return try JSONSerialization.jsonObject(with: Data(output.utf8)) as? [[String: Any]]
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
            let selected = try uniqueMatches(pose)!
            precondition(NSArray(array: selected).isEqual(to: matches))
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
            let wrongSelectionSource = try uniqueMatches(pose, time: wrongTime)
            precondition(wrongSelectionSource == nil)
            checks += 1
        }
        // Authored augmentation of a source fixture: the extra hand is a
        // negative control, not another SDK observation from this recording.
        var augmented = cases[0]["pose"] as! [String: Any]
        let size = augmented["imageSize"] as! [String: Double]
        let width = size["width"]!, height = size["height"]!
        let people = augmented["people"] as! [[String: Any]]
        let wrists = people.flatMap { person in
            (person["landmarks"] as! [[String: Any]]).filter {
                ["leftWrist", "rightWrist"].contains($0["joint"] as! String)
            }.map { $0["position"] as! [String: Double] }
        }
        let corners = [(1.0, 1.0), (width - 1, 1.0), (1.0, height - 1), (width - 1, height - 1)]
        let remote = corners.first { x, y in
            wrists.allSatisfy { hypot($0["x"]! - x, $0["y"]! - y) > 0.12 * min(width, height) }
        }!
        var hand = augmented["gripObservation"] as! [String: Any]
        let originalHand = (hand["hands"] as! [[String: Any]])[0]
        let distant: [String: Any] = ["points": [["joint": "wrist", "position": ["x": remote.0, "y": remote.1], "score": 0.9]]]
        hand["hands"] = [originalHand, distant]
        augmented["gripObservation"] = hand
        let measured = try associate(augmented)!
        let unresolved = (measured["unresolvedHands"] as! [[String: Any]]).first!
        let point = unresolved["observedWrist"] as! [String: Double]
        precondition(point["x"] == remote.0 && point["y"] == remote.1)
        precondition((unresolved["candidatePersonIndices"] as! [Int]).isEmpty)
        let selected = try uniqueMatches(augmented)!
        precondition(selected.count == 1 && NSArray(array: selected).isEqual(to: measured["matches"] as! [[String: Any]]))
        checks += 1

        hand["hands"] = [originalHand, ["points": []]]
        augmented["gripObservation"] = hand
        let unavailable = try associate(augmented)!
        precondition((unavailable["matches"] as! [Any]).count == 1)
        let missing = (unavailable["unresolvedHands"] as! [[String: Any]]).first!
        precondition(missing["observedWrist"] == nil || missing["observedWrist"] is NSNull)
        let unavailableSelection = try uniqueMatches(augmented)!
        precondition(unavailableSelection.isEmpty)
        checks += 1
        return checks
    }
}
