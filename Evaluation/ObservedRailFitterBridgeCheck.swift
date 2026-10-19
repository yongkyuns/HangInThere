import Foundation
import WorkoutCore

enum ObservedRailFitterBridgeCheck {
    static func run() throws -> Int {
        let bridge = ObservedRailFitterBridge()
        func arc(_ radius: Double) -> [[String: Double]] {
            (0...120).map { i in
                let angle = -0.8 + 1.6 * Double(i) / 120
                return ["x": 300 + radius * cos(angle), "y": 600 + radius * sin(angle)]
            }
        }
        func request(_ radii: [Double], dark: Bool = true) throws -> String {
            let value: [String: Any] = ["imageSize": ["width": 720, "height": 1280],
                "region": ["minX": 100, "minY": 400, "maxX": 650, "maxY": 900],
                "grip": ["x": 355, "y": 480],
                "contours": radii.enumerated().map { index, radius -> [String: Any] in
                    ["points": arc(radius), "darkOnLight": index == 0 || dark]
                }]
            return String(decoding: try JSONSerialization.data(withJSONObject: value), as: UTF8.self)
        }
        func fit(_ radii: [Double], dark: Bool = true) throws -> [[String: Any]] {
            let result = try bridge.fitJson(input: request(radii, dark: dark))
            return try JSONSerialization.jsonObject(with: Data(result.utf8)) as! [[String: Any]]
        }
        let paired = try fit([80, 100])
        precondition(!paired.isEmpty)
        for candidate in paired {
            precondition((candidate["supportArcPixels"] as! Double) >= 28.8)
            precondition((candidate["minimumWidthPixels"] as! Double) >= 2)
            precondition((candidate["maximumWidthPixels"] as! Double) <= 40)
            precondition((candidate["contactDistancePixels"] as! Double) <= 86.4)
            let first = candidate["firstBoundary"] as! [String: Any]
            precondition((first["points"] as! [[String: Any]]).count > 2)
        }
        let duplicate = try fit([80, 80]), wide = try fit([80, 130]), contrast = try fit([80, 100], dark: false)
        precondition(duplicate.isEmpty && wide.isEmpty && contrast.isEmpty)
        let alternatives = try fit([80, 100, 115])
        precondition(alternatives.count >= 3)
        do {
            _ = try bridge.fitJson(input: "{}")
            fatalError("Malformed contour transport became an ordinary empty observation")
        } catch { }
        return 6
    }
}
