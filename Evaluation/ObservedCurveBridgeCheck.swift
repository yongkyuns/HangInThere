import Foundation
import WorkoutCore

/// Authored geometry transport only. It does not qualify apparatus or counting.
enum ObservedCurveBridgeCheck {
    static func run() -> Int {
        let geometry = ObservedCurveGeometry.shared
        let size = WorkoutCore.ImageSize(width: 720, height: 1280)
        func arc(_ radius: Double, _ low: Double, _ high: Double) -> ObservedCurve {
            ObservedCurve(points: (0...100).map { i in
                let t = low + (high - low) * Double(i) / 100
                return WorkoutCore.Point2D(x: 200 + radius * cos(t), y: 600 + radius * sin(t))
            })
        }
        var checks = 0
        for radius in [20.0, 25, 40, 80] {
            let a = arc(radius, -1, 1), b = arc(radius, -0.7, 0.7)
            precondition(geometry.matches(first: a, second: b, size: size))
            precondition(geometry.matches(first: ObservedCurve(points: Array(b.points.reversed())), second: a, size: size))
            checks += 2
        }
        precondition(!geometry.matches(first: arc(30, -1, -0.2), second: arc(30, 0.2, 1), size: size))
        let elbow = ObservedCurve(points: [WorkoutCore.Point2D(x: 100, y: 100),
            WorkoutCore.Point2D(x: 100, y: 150), WorkoutCore.Point2D(x: 150, y: 150)])
        precondition(geometry.distance(curve: elbow, point: WorkoutCore.Point2D(x: 150, y: 100), size: size)?.doubleValue == 50)
        precondition(!geometry.isValid(curve: ObservedCurve(points: []), size: size))
        precondition(!geometry.isValid(curve: ObservedCurve(points: elbow.points + [elbow.points[0]]), size: size))
        precondition(geometry.distance(curve: elbow, point: WorkoutCore.Point2D(x: -1, y: 100), size: size) == nil)
        return checks + 5
    }
}
