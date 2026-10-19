import Foundation
import WorkoutCore

/// Authored expectations across the shipping common scene bridge and the
/// independent legacy Swift monitor. No native registration is fabricated.
enum SceneConsensusBridgeCheck {
    static func run() -> Int {
        let cases: [(String, [(Double, Double)], String)] = [
            ("independent-outliers", [(-54, 57), (-60, 0), (1, 0), (0, 0)], "stable"),
            ("reordered-outliers", [(0, 0), (1, 0), (-60, 0), (-54, 57)], "stable"),
            ("two-clusters", [(-56, 0), (-53, 0), (1, 0), (0, 0)], "calibrating"),
            ("overlapping-clusters", [(0, 0), (7, 0), (14, 0)], "calibrating"),
            ("equivalent-stationary-majorities", [(-6, 0), (-2, 0), (0, 0), (0, 0)], "stable"),
            ("equivalent-moving-majorities", [(14, 0), (18, 0), (20, 0), (20, 0)], "moved"),
            ("nearby-mixed-motion-majorities", [(0, 0), (4, 0), (6, 0), (6, 0)], "calibrating"),
            ("nearby-pairs-share-only-one-patch", [(-6, 0), (0, 0), (1, 0)], "calibrating"),
            ("nontransitive-stationary-hypotheses", [(-6, 0), (0, 0), (0, 0), (6, 0)], "calibrating"),
            ("moving-majority", [(12, 0), (12, 1), (13, 0), (0, 0)], "moved"),
            ("moving-pair", [(12, 0), (13, 1), (-60, 50), (0, 0)], "moved"),
            ("single-patch", [(0, 0)], "calibrating"),
            ("over-budget", Array(repeating: (0, 0), count: 5), "calibrating"),
            ("nonfinite-not-votes", [(0, 0), (1, 0), (.nan, 0), (0, .infinity)], "stable"),
            ("large-finite-median", [(.greatestFiniteMagnitude, 0), (.greatestFiniteMagnitude, 0)], "moved")
        ]
        for (name, points, expected) in cases {
            let common = WorkoutSceneBridge()
            let diagnostic = WorkoutCore.StaticSceneStability()
            var swift = StaticSceneStability()
            precondition(common.calibrate(width: 720, height: 1280))
            precondition(diagnostic.calibrate(imageShortSide: 720))
            precondition(swift.calibrate(imageSize: ImageSize(width: 720, height: 1280)))
            let transport = KotlinDoubleArray(size: Int32(points.count * 2))
            let patches = points.enumerated().map { index, point in
                transport.set(index: Int32(index * 2), value: point.0)
                transport.set(index: Int32(index * 2 + 1), value: point.1)
                return StaticSceneStability.PatchTranslation(dxPixels: point.0, dyPixels: point.1)
            }
            for seconds in [1.0, 1.3] {
                let state = common.observe(seconds: seconds, translations: transport, scaleFraction: 0)
                let nativePatches = points.map {
                    WorkoutCore.StaticSceneStability.PatchTranslation(dxPixels: $0.0, dyPixels: $0.1)
                }
                diagnostic.observe(translations: nativePatches, globalScaleFraction: KotlinDouble(double: 0), timestamp: seconds)
                swift.observe(translations: patches, globalScaleFraction: 0, timestamp: seconds)
                precondition(state == swift.state.rawValue, name)
                precondition(common.isReady(seconds: seconds) == swift.isReady(at: seconds), name)
                precondition(diagnostic.latestShiftFraction?.doubleValue == swift.latestShiftFraction, name)
                precondition(Int(diagnostic.latestConsensusPatches) == swift.latestConsensusPatches, name)
                precondition(diagnostic.movementKind?.name == swift.movementKind?.rawValue, name)
            }
            precondition(common.state == expected, name)
            precondition(swift.state.rawValue == expected, name)
        }
        return cases.count
    }
}
