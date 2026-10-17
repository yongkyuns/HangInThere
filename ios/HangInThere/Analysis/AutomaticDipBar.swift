import Foundation

// Straight-bar dips share one tube between both hands. This selector is separate
// from parallel-rail identity: duplicated local rails still cannot qualify as
// two independent supports. Every reference remains a fresh, finite image edge.
struct AutomaticDipBar: Sendable {
    private var stability = AutomaticReferenceStability()
    private(set) var state: AutomaticPullUpBar.State = .lookingForGrip
    var referenceStabilityDiagnostic: AutomaticReferenceStability.Diagnostic? {
        state == .holding || state == .locked ? stability.diagnostic : nil
    }

    mutating func reset() { self = Self() }

    static func region(grip: AutomaticDipRails.Grip, size: ImageSize) -> BarRegion? {
        guard size.isValid, grip.hands.count == 2, grip.hands.allSatisfy(\.isFinite) else { return nil }
        let short = min(size.width, size.height)
        let region = BarRegion(
            .init(x: max(0, grip.hands.map(\.x).min()! - short * 0.16),
                  y: max(0, grip.hands.map(\.y).min()! - short * 0.08)),
            .init(x: min(size.width, grip.hands.map(\.x).max()! + short * 0.16),
                  y: min(size.height, grip.hands.map(\.y).max()! + short * 0.16)))
        return region.isValid(in: size) ? region : nil
    }

    private static func select(_ candidates: [BarLineCandidate], grip: AutomaticDipRails.Grip,
                               size: ImageSize, region: BarRegion) -> BarSegment? {
        let short = min(size.width, size.height)
        let span = hypot(grip.hands[0].x - grip.hands[1].x, grip.hands[0].y - grip.hands[1].y)
        let crop = BarRegion(.init(x: floor(region.minX) - 1, y: floor(region.minY) - 1),
                             .init(x: ceil(region.maxX) + 1, y: ceil(region.maxY) + 1))
        let bounds = BarRegion(.init(x: 0, y: 0), .init(x: size.width, y: size.height))
        let edges = candidates.map { $0.edge.ordered }.filter {
            $0.isValid && crop.contains($0.a) && crop.contains($0.b)
                && bounds.contains($0.a) && bounds.contains($0.b)
        }
        var proposals: [(edge: BarSegment, distance: Double, score: Double)] = []
        for i in edges.indices {
            for j in edges.indices where j > i {
                // Grasp occlusion can shorten one silhouette. A real paired
                // tube must still share observed support over half the grip span.
                guard let pair = BarFitter.pair(edges[i], edges[j], minimumLength: max(24, span * 0.5)) else { continue }
                let center = pair.centerline.ordered
                let ux = (center.b.x-center.a.x)/center.length, uy = (center.b.y-center.a.y)/center.length
                // In top support the wrists lie on/above the tube center in
                // upright pixels. A nearby torso stripe above either grip is
                // not support. This side-of-line check never extends evidence;
                // both hands must also be close to the finite upper edge below.
                guard grip.hands.allSatisfy({ hand in
                    let signed = ux*(hand.y-center.a.y)-uy*(hand.x-center.a.x)
                    return signed.isFinite && signed <= 0
                }) else { continue }
                let upper = edges[i].midpoint.y <= edges[j].midpoint.y ? edges[i] : edges[j]
                let distances = grip.hands.map { upper.distance(to: $0) }
                guard upper.length >= span * 0.65,
                      abs(upper.b.y - upper.a.y) <= (upper.b.x - upper.a.x) * tan(15 * .pi / 180),
                      distances.allSatisfy({ $0.isFinite && $0 <= short * 0.12 }) else { continue }
                proposals.append((upper, distances.max()!, pair.geometryScore))
            }
        }
        proposals.sort {
            if $0.distance != $1.distance { return $0.distance < $1.distance }
            return $0.score > $1.score
        }
        guard let first = proposals.first else { return nil }
        // Several eligible but distinct bars leave the actual grip ambiguous.
        guard proposals.allSatisfy({ AutomaticReferenceStability.sameLine(first.edge, $0.edge,
            tolerance: short * 0.01) }) else { return nil }
        return first.edge
    }

    mutating func observe(pose: PoseResult, side: ArmMeasurement.Side,
                          candidates: [BarLineCandidate]) -> ConfirmedBar? {
        guard state != .locked else { return nil }
        guard let grip = AutomaticDipRails.grip(in: pose),
              let region = Self.region(grip: grip, size: pose.imageSize) else { reset(); return nil }
        guard let edge = Self.select(candidates, grip: grip, size: pose.imageSize, region: region) else {
            reset(); state = candidates.isEmpty ? .findingEdge : .ambiguous; return nil
        }
        state = .holding
        guard stability.observe(edges: [edge], grips: grip.hands, pose: pose) else { return nil }
        // The role identifies the tracked hand; both hands witness this shared bar.
        let bar = ConfirmedBar(role: side == .left ? .leftDipRail : .rightDipRail,
            method: .automaticContours, referenceEdge: edge, oppositeEdge: nil,
            imageSize: pose.imageSize, sourceTime: pose.timestamp)
        guard bar.isValid else { reset(); return nil }
        state = .locked
        return bar
    }
}
