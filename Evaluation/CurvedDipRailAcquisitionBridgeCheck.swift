import Foundation
import WorkoutCore

/// Actual stateful Native FFI; authored geometry cannot qualify apparatus or reps.
enum CurvedDipRailAcquisitionBridgeCheck {
    private static func json(_ value: Any) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: value), as: UTF8.self)
    }
    private static func object(_ value: String) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: Data(value.utf8)) as! [String: Any]
    }
    static func acquiredReference() throws -> [String: Any] {
        let engine = CurvedDipRailAcquisitionBridge()
        let rails = try candidates(engine: engine)
        var result: [String: Any] = [:]
        for ticks in [0, 300, 600, 900] {
            result = try object(engine.observeJson(input: json(["pose": BilateralGripDisplacementBridgeCheck.frame(ticks: ticks),
                "generation": engine.generation, "candidates": rails])))
        }
        return result["reference"] as! [String: Any]
    }
    private static func candidates(engine: CurvedDipRailAcquisitionBridge) throws -> [[[String: Any]]] {
        let frame = BilateralGripDisplacementBridgeCheck.frame()
        let search = try object(engine.searchJson(input: json(frame))!)
        precondition(search["generation"] as! Int64 == engine.generation)
        precondition((search["regions"] as! [[String: Any]]).count == 2)
        let hands = search["hands"] as! [[String: Double]]
        let regions = search["regions"] as! [[String: Any]]
        let fitter = ObservedRailFitterBridge()
        let rails = try hands.enumerated().map { side, hand -> [[String: Any]] in
            let contours: [[String: Any]] = [60.0, 68.0].map { radius in
                ["darkOnLight": true, "points": (0...60).map { i -> [String: Double] in
                    let angle = -0.6 + Double(i) * 1.2 / 60
                    return ["x": hand["x"]! - 50 + radius * cos(angle), "y": 700 + radius * sin(angle)]
                }]
            }
            let result = try fitter.fitJson(input: json(["imageSize": frame["imageSize"]!,
                "region": regions[side], "grip": hand, "contours": contours]))
            let all = try JSONSerialization.jsonObject(with: Data(result.utf8)) as! [[String: Any]]
            return [all.max { ($0["supportArcPixels"] as! Double) < ($1["supportArcPixels"] as! Double) }!]
        }
        return rails
    }
    static func run() throws -> Int {
        func json(_ value: Any) throws -> String {
            String(decoding: try JSONSerialization.data(withJSONObject: value), as: UTF8.self)
        }
        func object(_ value: String) throws -> [String: Any] {
            try JSONSerialization.jsonObject(with: Data(value.utf8)) as! [String: Any]
        }
        let engine = CurvedDipRailAcquisitionBridge()
        let rails = try candidates(engine: engine)
        func observe(_ ticks: Int, generation: Int64? = nil, values: [[[String: Any]]]? = nil) throws -> [String: Any] {
            try object(engine.observeJson(input: json(["pose": BilateralGripDisplacementBridgeCheck.frame(ticks: ticks),
                "generation": generation ?? engine.generation, "candidates": values ?? rails])))
        }
        for ticks in [0, 300, 600] { let value = try observe(ticks); precondition(value["reference"] == nil) }
        let output = try observe(900), reference = output["reference"] as! [String: Any]
        precondition((reference["sourceTime"] as! [String: Int])["value"] == 900)
        precondition((reference["hands"] as! [[String: Any]]).count == 2)
        precondition((reference["context"] as! [String: Any])["handModel"] as! String == "authored-hand")
        let deliveredOnce = try observe(1200); precondition(deliveredOnce["reference"] == nil)
        let old = engine.generation
        engine.invalidateEvidence()
        precondition(engine.generation != old)
        let reversed = try observe(0)
        precondition((reversed["diagnostic"] as! [String: Any])["status"] as! String == "sourceRegression")
        let before = try observe(1500)
        let stale = try observe(10000, generation: old)
        precondition(NSDictionary(dictionary: before).isEqual(to: stale))
        engine.resetSource()
        for ticks in [0, 300, 600] { let value = try observe(ticks); precondition(value["reference"] == nil) }
        let fresh = try observe(900); precondition(fresh["reference"] != nil)
        engine.resetSource()
        let unavailable = try observe(0, values: [rails[0], []])
        precondition((unavailable["diagnostic"] as! [String: Any])["status"] as! String == "unavailable")
        do {
            _ = try engine.observeJson(input: "{}")
            fatalError("Malformed acquisition transport became an ordinary unavailable frame")
        } catch { }
        return 8
    }
}
