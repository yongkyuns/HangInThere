import Foundation
import WorkoutCore

/// Source-fixture transport and lifecycle checks through the actual Apple ABI.
/// Follow-up absence/expiry inputs are authored, not new SDK measurements.
enum ObservedHandContinuityBridgeCheck {
    static func run() throws -> (checks: Int, p95: Double) {
        let data = try Data(contentsOf: URL(fileURLWithPath: "Evaluation/fixtures/observed-wrist-source.json"))
        let cases = (try JSONSerialization.jsonObject(with: data) as! [String: Any])["cases"] as! [[String: Any]]
        precondition(cases.count == 4)
        func json(_ value: [String: Any]) throws -> String {
            String(decoding: try JSONSerialization.data(withJSONObject: value), as: UTF8.self)
        }
        func decoded(_ value: String?) throws -> [String: Any] {
            precondition(value != nil)
            return try JSONSerialization.jsonObject(with: Data(value!.utf8)) as! [String: Any]
        }
        var checks = 0
        var latencies: [Double] = []
        for value in cases {
            let pose = value["pose"] as! [String: Any]
            let time = pose["timestamp"] as! [String: Any]
            let hands = (pose["gripObservation"] as! [String: Any])["hands"] as! [[String: Any]]
            let frame: [String: Any] = ["generation": 0, "sourceEpoch": "fixture-\(value["clip"]!)",
                "sourceFrameOrdinal": 0, "pose": pose, "handSourceTime": time,
                "handSourceFrameOrdinal": 0, "requiresJointConfidence": true]
            let input = try json(["frame": frame, "seedHandIndices": Array(hands.indices)])
            let bridge = ObservedHandContinuityBridge()
            let initial = try decoded(bridge.startJson(input: input))
            let initialTracks = initial["tracks"] as! [[String: Any]]
            precondition(initialTracks.count == hands.count)
            for (index, track) in initialTracks.enumerated() {
                let point = (hands[index]["points"] as! [[String: Any]]).first { ($0["joint"] as! String) == "wrist" }!
                let sample = track["currentSample"] as! [String: Any]
                precondition(track["state"] as! String == "fresh")
                precondition(NSDictionary(dictionary: sample["position"] as! [String: Any]).isEqual(to: point["position"] as! [String: Any]))
            }
            checks += 1
            let replacement = try bridge.startJson(input: input)
            precondition(replacement == nil)
            checks += 1
            var missingPose = pose
            missingPose.removeValue(forKey: "gripObservation")
            let scale = (time["timescale"] as! NSNumber).int64Value
            let ticks = (time["value"] as! NSNumber).int64Value
            missingPose["timestamp"] = ["value": ticks + scale, "timescale": scale]
            let missing: [String: Any] = ["generation": 0, "sourceEpoch": frame["sourceEpoch"]!,
                "sourceFrameOrdinal": 1, "pose": missingPose]
            let expired = try decoded(bridge.observeJson(input: json(missing)))
            let tracks = expired["tracks"] as! [[String: Any]]
            precondition(tracks.allSatisfy { ($0["state"] as! String) == "terminated" && ($0["currentSample"] as? [String: Any]) == nil })
            precondition(zip(initialTracks, tracks).allSatisfy { before, after in
                NSDictionary(dictionary: before["seed"] as! [String: Any]).isEqual(to: after["lastObservedSample"] as! [String: Any])
            })
            checks += 1
            precondition(bridge.reset() == 1)
            let staleStart = try bridge.startJson(input: input)
            precondition(staleStart == nil)
            var nextFrame = frame
            nextFrame["generation"] = 1
            let nextInput = try json(["frame": nextFrame, "seedHandIndices": Array(hands.indices)])
            _ = try decoded(bridge.startJson(input: nextInput))
            let delayed = try bridge.observeJson(input: json(missing))
            precondition(delayed == nil)
            checks += 1
            if latencies.isEmpty {
                // Authored repeated-position stream measures host transport,
                // not fresh inference, camera cadence or physical performance.
                let performance = ObservedHandContinuityBridge()
                _ = try decoded(performance.startJson(input: input))
                for index in 1...2000 {
                    let nextTicks = ticks + Int64(index) * scale / 30
                    var nextPose = pose
                    var nextHand = pose["gripObservation"] as! [String: Any]
                    nextHand["seconds"] = Double(nextTicks) / Double(scale)
                    nextPose["timestamp"] = ["value": nextTicks, "timescale": scale]
                    nextPose["gripObservation"] = nextHand
                    var next = frame
                    next["pose"] = nextPose
                    next["sourceFrameOrdinal"] = index
                    next["handSourceFrameOrdinal"] = index
                    next["handSourceTime"] = nextPose["timestamp"]
                    let payload = try json(next)
                    let start = ContinuousClock.now
                    let output = try performance.observeJson(input: payload)
                    let duration = start.duration(to: .now).components
                    latencies.append(Double(duration.seconds) * 1000 + Double(duration.attoseconds) / 1e15)
                    let state = try decoded(output)
                    precondition((state["tracks"] as! [[String: Any]]).allSatisfy { ($0["state"] as! String) == "fresh" })
                }
            }
        }
        latencies.sort()
        precondition(checks == 16 && latencies.count == 2000)
        return (checks, latencies[Int(Double(latencies.count - 1) * 0.95)])
    }
}
