import Foundation
import WorkoutCore

/// Actual shipping geometry transport and source-derived rail/clutter fixtures.
/// Source pixels, apparatus identity, load, camera and count are not qualified.
enum DipRailSearchGeometryBridgeCheck {
    private struct Fixture: Decodable {
        struct Case: Decodable {
            let pose: PoseResult
            let side: ArmMeasurement.Side
            let originalCandidates: [BarLineCandidate]
            let originalSelected: BarSegment?
            let outwardRegion: BarRegion
            let outwardCandidates: [BarLineCandidate]
            let outwardSelected: BarSegment?
        }
        let cases: [Case]
    }
    static func run() throws -> (checks: Int, p95: Double) {
        let fixture = try JSONDecoder().decode(Fixture.self,
            from: Data(contentsOf: URL(fileURLWithPath: "Evaluation/fixtures/legacy-dip-search-context.json")))
        precondition(fixture.cases.count == 4)
        let bridge = DipRailSearchGeometryBridge()
        precondition(bridge.policyVersion == 2 && AutomaticDipRails.contactSearchPolicyVersion == 2)
        var checks = 1
        func equal(_ a: BarRegion, _ b: BarRegion) {
            precondition(zip([a.minX, a.minY, a.maxX, a.maxY], [b.minX, b.minY, b.maxX, b.maxY])
                .allSatisfy { abs($0 - $1) < 1e-9 })
        }
        for row in fixture.cases {
            guard let arm = PullUpArmMeasurement(pose: row.pose).arms.first(where: { $0.side == row.side }),
                  let region = AutomaticDipRails.contactRegion(arm: arm, size: row.pose.imageSize) else { fatalError("Missing source arm") }
            equal(row.outwardRegion, region)
            let original = AutomaticDipRails.select(candidates: row.originalCandidates, hand: arm.wrist,
                size: row.pose.imageSize, requireUnique: true)
            if let expected = row.originalSelected {
                guard let original else { fatalError("Lost original frozen source selection") }
                precondition(AutomaticReferenceStability.sameLine(expected, original, tolerance: 1e-8))
            } else { precondition(original == nil) }
            let selected = AutomaticDipRails.select(candidates: row.outwardCandidates, hand: arm.wrist,
                size: row.pose.imageSize, requireUnique: true)
            if let expected = row.outwardSelected {
                guard let selected else { fatalError("Lost source rail") }
                precondition(AutomaticReferenceStability.sameLine(expected, selected, tolerance: 1e-8))
            } else { precondition(selected == nil) }
            checks += 3
        }
        for invalid in [Double.nan, .infinity, 0, -1] {
            precondition(bridge.region(shoulderX: 100, shoulderY: 100, gripX: 250, gripY: 300,
                imageWidth: 960, imageHeight: 540, upperArmPixels: invalid) == nil)
            checks += 1
        }
        // Include Apple object creation and typed return conversion. This is a
        // small host FFI budget, not inference/device performance parity.
        var latencies: [Double] = []
        for i in 0..<2000 {
            let mirrored = i.isMultiple(of: 2)
            let shoulder = Point2D(x: mirrored ? 850 : 110, y: 100)
            let wrist = Point2D(x: mirrored ? 710 : 250, y: 300)
            let upper = Double(100 + i % 200)
            let arm = PullUpArmMeasurement.Arm(side: .left, estimate: .init(elbowDegrees: 150, upperArmPixels: upper,
                forearmPixels: 100, minimumJointConfidence: 0.9), shoulder: shoulder, wrist: wrist)
            let start = ContinuousClock.now
            guard let actual = AutomaticDipRails.contactRegion(arm: arm, size: .init(width: 960, height: 540)) else {
                fatalError("Missing authored search geometry")
            }
            let elapsed = start.duration(to: .now).components
            latencies.append(Double(elapsed.seconds) * 1000 + Double(elapsed.attoseconds) / 1e15)
            let oldMin = wrist.x - 540 * 0.12, oldMax = wrist.x + 540 * 0.12
            let expected = BarRegion(.init(x: mirrored ? max(0, min(oldMin, wrist.x - upper)) : oldMin, y: 256.8),
                                     .init(x: mirrored ? oldMax : min(960, max(oldMax, wrist.x + upper)), y: 408))
            equal(expected, actual)
        }
        latencies.sort()
        return (checks, latencies[Int(Double(latencies.count - 1) * 0.95)])
    }
}
