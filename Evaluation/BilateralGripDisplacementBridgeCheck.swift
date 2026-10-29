import Foundation
import WorkoutCore

/// Image geometry transport; this does not qualify camera, contact or reps.
enum BilateralGripDisplacementBridgeCheck {
    static func frame(ticks: Int = 0, leftDrop: Double = 0, rightDrop: Double = 0,
                      handShift: Double = 0, elbows: Bool = true, scored: Bool = true) -> [String: Any] {
        var landmarks: [[String: Any]] = [], tracked: [[String: Any]] = [], hands: [[String: Any]] = []
        for (index, side) in ["left", "right"].enumerated() {
            let x = index == 0 ? 200.0 : 500.0
            for (joint, y) in [("Shoulder", 200.0 + (index == 0 ? leftDrop : rightDrop)), ("Elbow", 400.0), ("Wrist", 600.0)] {
                if joint == "Elbow" && !elbows { continue }
                let position = ["x": x, "y": y]
                landmarks.append(["joint": side + joint, "position": position, "confidence": 0.9])
                if joint != "Elbow" { tracked.append(["joint": side + joint, "position": position, "score": 0.9]) }
            }
            let points = ["indexMCP", "middleMCP", "pinkyMCP"].enumerated().map { i, joint -> [String: Any] in
                var point: [String: Any] = ["joint": joint, "position": ["x": x + (index == 0 ? handShift : 0) + Double(i - 1) * 6, "y": 640.0]]
                if scored { point["score"] = 0.9 }
                return point
            }
            hands.append(["points": points])
        }
        let size = ["width": 720.0, "height": 1280.0]
        return ["timestamp": ["value": ticks, "timescale": 1000], "imageSize": size,
                "people": [["landmarks": landmarks]], "backend": "authored-body", "requestRevision": 1,
                "gripObservation": ["seconds": Double(ticks) / 1000, "imageSize": size, "trackedBody": tracked,
                    "body": [], "hands": hands, "modelId": "authored-hand", "backend": "authored-hand-backend"]]
    }
    static func input(_ anchor: [String: Any], _ current: [String: Any]) throws -> String {
        String(data: try JSONSerialization.data(withJSONObject: ["anchor": anchor, "current": current], options: .sortedKeys), encoding: .utf8)!
    }
    static func run() throws -> Int {
        let bridge = BilateralGripDisplacementBridge(), top = frame()
        func measure(_ current: [String: Any], anchor: [String: Any]? = nil) throws -> [String: Any]? {
            guard let json = try bridge.measureJson(input: input(anchor ?? top, current)) else { return nil }
            return try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]
        }
        let result = try measure(frame(ticks: 100, leftDrop: 100, rightDrop: 80))!
        precondition((result["left"] as! [String: Any])["axialPixels"] as! Double == 100)
        precondition((result["right"] as! [String: Any])["axialPixels"] as! Double == 80)
        let missing = try measure(frame(ticks: 100, leftDrop: 100, elbows: false))!
        precondition((missing["left"] as! [String: Any])["axialPixels"] as! Double == 100)
        let missingTop = try measure(frame(ticks: 100), anchor: frame(elbows: false))
        precondition(missingTop == nil)
        var cached = frame(ticks: 100), grip = cached["gripObservation"] as! [String: Any]
        grip["seconds"] = 0.099; cached["gripObservation"] = grip
        let stale = try measure(cached)
        precondition(stale == nil)
        let retained = try measure(frame(ticks: 100, handShift: 25))!
        precondition((retained["left"] as! [String: Any])["retainsGrip"] as! Bool)
        let moved = try measure(frame(ticks: 100, handShift: 49))!
        precondition(!((moved["left"] as! [String: Any])["retainsGrip"] as! Bool))
        let unscored = try measure(frame(ticks: 100, scored: false))!
        precondition(!(unscored["handPointsHaveScores"] as! Bool))
        let equal = try measure(frame(leftDrop: 50))
        let reverse = try measure(frame(ticks: -1))
        precondition(equal == nil && reverse == nil)
        return 10
    }
}
