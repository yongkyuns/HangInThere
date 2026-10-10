import Foundation

// Shared fixed-reference dwell. Hands locate the search, while every returned
// segment remains an observed image edge. Supports vertical projected rails.
struct AutomaticReferenceStability: Sendable {
    private var edges: [BarSegment] = []
    private var grips: [Point2D] = []
    private var size: ImageSize?
    private var firstSeconds: Double?
    private var lastSeconds: Double?
    private var observations = 0

    mutating func reset() { self = Self() }

    mutating func observe(edges next: [BarSegment], grips hands: [Point2D], pose: PoseResult) -> Bool {
        let seconds = pose.timestamp.seconds
        guard seconds.isFinite, !next.isEmpty, next.allSatisfy(\.isValid),
              !hands.isEmpty, hands.allSatisfy(\.isFinite), pose.imageSize.isValid else {
            reset(); return false
        }
        if let lastSeconds, seconds <= lastSeconds { reset(); return false }
        let short = min(pose.imageSize.width, pose.imageSize.height)
        let continues = size == pose.imageSize && edges.count == next.count && grips.count == hands.count
            && lastSeconds.map { seconds - $0 <= 0.8 } == true
            && zip(edges, next).allSatisfy { Self.sameLine($0, $1, tolerance: short * 0.008) }
            && zip(grips, hands).allSatisfy { hypot($0.x - $1.x, $0.y - $1.y) <= short * 0.02 }
        if !continues {
            edges = next; grips = hands; size = pose.imageSize
            firstSeconds = seconds; observations = 0
        }
        lastSeconds = seconds
        observations += 1
        return observations >= 4 && seconds - (firstSeconds ?? seconds) >= 0.9
    }

    static func sameLine(_ p: BarSegment, _ q: BarSegment, tolerance: Double,
                         angleDegrees: Double = 3, overlapFraction: Double = 0.65) -> Bool {
        guard sameSupportingLine(p, q, tolerance: tolerance, angleDegrees: angleDegrees) else { return false }
        let ux = (p.b.x - p.a.x) / p.length, uy = (p.b.y - p.a.y) / p.length
        func projection(_ point: Point2D) -> Double { (point.x - p.a.x) * ux + (point.y - p.a.y) * uy }
        let a = projection(q.a), b = projection(q.b)
        let overlap = min(p.length, max(a, b)) - max(0, min(a, b))
        return overlap >= min(p.length, q.length) * overlapFraction
    }

    // Unlike tracking, apparatus identity must also compare disjoint fragments.
    // This tests their image lines without extending either observed segment.
    static func sameSupportingLine(_ p: BarSegment, _ q: BarSegment, tolerance: Double,
                                   angleDegrees: Double = 3) -> Bool {
        guard p.isValid, q.isValid else { return false }
        let ux = (p.b.x - p.a.x) / p.length, uy = (p.b.y - p.a.y) / p.length
        let dot = abs((ux * (q.b.x - q.a.x) + uy * (q.b.y - q.a.y)) / q.length)
        return dot >= cos(angleDegrees * .pi / 180)
            && p.perpendicularDistance(to: q.a) <= tolerance && p.perpendicularDistance(to: q.b) <= tolerance
            && q.perpendicularDistance(to: p.a) <= tolerance && q.perpendicularDistance(to: p.b) <= tolerance
    }
}

// Both hands must show top support and have independently observed local rail
// edges. Never use the line between wrists, a body segment, or a rack box as a rail.
struct AutomaticDipRails: Sendable {
    struct Grip: Sendable {
        let hands: [Point2D] // Anatomical left, right, in unmirrored pixels.
        let regions: [BarRegion]
    }
    private(set) var state: AutomaticPullUpBar.State = .lookingForGrip
    private var stability = AutomaticReferenceStability()
    private struct ObservedRail {
        let pair: BarCandidate
        let edge: BarSegment
        var halfWidth: Double {
            let center = pair.centerline
            return [pair.firstEdge.a, pair.firstEdge.b, pair.secondEdge.a, pair.secondEdge.b]
                .map { center.perpendicularDistance(to: $0) }.max() ?? 0
        }
    }
    private var witnessedRails: [ObservedRail] = []
    private var witnessedHands: [Point2D] = []
    mutating func reset() { self = Self() }

    static func grip(in pose: PoseResult) -> Grip? {
        guard pose.imageSize.isValid, pose.timestamp.seconds.isFinite else { return nil }
        let arms = PullUpArmMeasurement(pose: pose).arms
        guard arms.count == 2 else { return nil }
        let short = min(pose.imageSize.width, pose.imageSize.height)
        guard arms.allSatisfy({
            $0.estimate.elbowDegrees + 1e-6 >= DipSupportMeasurement.startingDegrees
                && $0.wrist.y - $0.shoulder.y >= 0.35 * ($0.estimate.upperArmPixels + $0.estimate.forearmPixels)
        }) else { return nil }
        let hands = arms.map(\.wrist)
        guard abs(hands[0].x - hands[1].x) >= short * 0.12,
              abs(hands[0].y - hands[1].y) <= short * 0.10 else { return nil }
        let regions = arms.map { arm in
            let hand = arm.wrist
            var minX = max(0, hand.x - short * 0.12), maxX = min(pose.imageSize.width, hand.x + short * 0.12)
            // In a frontal/oblique view the grip is outside its shoulder. Clip
            // the torso-facing part of the crop so trouser/torso silhouettes
            // cannot masquerade as handles below an imperfect wrist estimate.
            if hand.x - arm.shoulder.x > short * 0.04 { minX = max(minX, (hand.x + arm.shoulder.x) / 2) }
            if arm.shoulder.x - hand.x > short * 0.04 { maxX = min(maxX, (hand.x + arm.shoulder.x) / 2) }
            return BarRegion(.init(x: minX, y: max(0, hand.y - short * 0.08)),
                             .init(x: maxX, y: min(pose.imageSize.height, hand.y + short * 0.20)))
        }
        guard regions.allSatisfy({ $0.isValid(in: pose.imageSize) }) else { return nil }
        return Grip(hands: hands, regions: regions)
    }

    static func select(candidates: [BarLineCandidate], hand: Point2D, size: ImageSize) -> BarSegment? {
        selection(candidates: candidates, hand: hand, size: size)?.edge
    }

    private static func selection(candidates: [BarLineCandidate], hand: Point2D, size: ImageSize) -> ObservedRail? {
        guard size.isValid, hand.isFinite else { return nil }
        let short = min(size.width, size.height)
        let bounds = BarRegion(.init(x: 0, y: 0), .init(x: size.width, y: size.height))
        var tubes: [(pair: BarCandidate, edge: BarSegment)] = []
        for i in candidates.indices {
            for j in candidates.indices where j > i {
                guard let pair = BarFitter.pair(candidates[i].edge, candidates[j].edge,
                    minimumLength: max(24, short * 0.04)),
                    bounds.contains(pair.firstEdge.a), bounds.contains(pair.firstEdge.b),
                    bounds.contains(pair.secondEdge.a), bounds.contains(pair.secondEdge.b),
                    pair.centerline.distance(to: hand) <= short * 0.12,
                    pair.centerline.midpoint.y >= hand.y + short * 0.025 else { continue }
                let a = candidates[i].edge, b = candidates[j].edge
                guard min(a.a.y, a.b.y) >= hand.y + short * 0.04,
                      min(b.a.y, b.b.y) >= hand.y + short * 0.04 else { continue }
                let edge = a.length == b.length ? (a.distance(to: hand) <= b.distance(to: hand) ? a : b)
                    : (a.length > b.length ? a : b)
                // Rank only complete proposals. A nearby fitted centerline
                // cannot let an unreachable silhouette hide a valid rail.
                guard edge.distance(to: hand) <= short * 0.12 else { continue }
                tubes.append((pair, edge))
            }
        }
        tubes.sort {
            if $0.pair.geometryScore != $1.pair.geometryScore { return $0.pair.geometryScore > $1.pair.geometryScore }
            return $0.pair.centerline.distance(to: hand) < $1.pair.centerline.distance(to: hand)
        }
        guard let tube = tubes.first else { return nil }
        // Select one of the real silhouette edges, never the fitted centerline.
        return ObservedRail(pair: tube.pair, edge: tube.edge.ordered)
    }

    mutating func observe(pose: PoseResult, side: ArmMeasurement.Side,
                          candidates: [[BarLineCandidate]]) -> ConfirmedBar? {
        guard state != .locked else { return nil }
        guard let grip = Self.grip(in: pose), candidates.count == 2 else { reset(); return nil }
        let short = min(pose.imageSize.width, pose.imageSize.height)
        if witnessedHands.count == 2,
           !zip(witnessedHands, grip.hands).allSatisfy({ hypot($0.x - $1.x, $0.y - $1.y) <= short * 0.02 }) {
            reset()
        }
        let local = grip.regions.enumerated().map { index, crop in
            // The detector uses an integral crop and fitted contour endpoints;
            // allow only their pixel rounding, without admitting torso edges.
            let observedCrop = BarRegion(.init(x: floor(crop.minX) - 1, y: floor(crop.minY) - 1),
                                         .init(x: ceil(crop.maxX) + 1, y: ceil(crop.maxY) + 1))
            return candidates[index].filter {
                observedCrop.contains($0.edge.a) && observedCrop.contains($0.edge.b)
            }
        }
        var rails: [ObservedRail] = []
        if witnessedRails.count == 2 {
            // A previously paired handle can retain identity through partial
            // occlusion only while a fresh matching silhouette edge is observed.
            for index in 0..<2 {
                let witness = witnessedRails[index]
                let matching = local[index].map(\.edge).filter {
                    $0.distance(to: grip.hands[index]) <= short * 0.12
                        && min($0.a.y, $0.b.y) >= grip.hands[index].y + short * 0.04
                        && AutomaticReferenceStability.sameLine(witness.edge, $0,
                            tolerance: short * 0.01, angleDegrees: 5, overlapFraction: 0.3)
                }.sorted {
                    let a = witness.edge.perpendicularDistance(to: $0.midpoint)
                    let b = witness.edge.perpendicularDistance(to: $1.midpoint)
                    return a == b ? $0.length > $1.length : a < b
                }.first
                if let matching { rails.append(ObservedRail(pair: witness.pair, edge: matching.ordered)) }
            }
        }
        if rails.count != 2 {
            // Losing either witness starts a new bilateral acquisition. A lone
            // old silhouette cannot legitimize a newly selected second tube.
            reset()
            rails = (0..<2).compactMap {
                Self.selection(candidates: local[$0], hand: grip.hands[$0], size: pose.imageSize)
            }
        }
        guard rails.count == 2 else {
            reset(); state = candidates.allSatisfy(\.isEmpty) ? .findingEdge : .ambiguous; return nil
        }
        let edges = rails.map(\.edge)
        // Midpoints can be far apart on one tube. Compare its full paired width
        // as well, even when each crop selected a different silhouette edge.
        let sameTube = AutomaticReferenceStability.sameSupportingLine(rails[0].pair.centerline,
            rails[1].pair.centerline, tolerance: rails[0].halfWidth + rails[1].halfWidth + short * 0.01,
            angleDegrees: 5)
        let sameFreshEdge = AutomaticReferenceStability.sameSupportingLine(edges[0], edges[1],
            tolerance: short * 0.01, angleDegrees: 5)
        guard hypot(edges[0].midpoint.x - edges[1].midpoint.x,
                    edges[0].midpoint.y - edges[1].midpoint.y) >= short * 0.10,
              !sameTube, !sameFreshEdge else {
            reset(); state = .ambiguous; return nil
        }
        if witnessedRails.isEmpty { witnessedRails = rails; witnessedHands = grip.hands }
        state = .holding
        guard stability.observe(edges: edges, grips: grip.hands, pose: pose) else { return nil }
        let bar = ConfirmedBar(role: side == .left ? .leftDipRail : .rightDipRail,
            method: .automaticContours, referenceEdge: edges[side == .left ? 0 : 1],
            oppositeEdge: nil, imageSize: pose.imageSize, sourceTime: pose.timestamp)
        guard bar.isValid else { reset(); return nil }
        state = .locked
        return bar
    }
}

// One acquisition interface for live camera and recorded review. The underlying
// selectors differ, but cancellation, freshness and camera calibration stay shared.
struct AutomaticApparatusReference: Sendable {
    struct Search: Sendable {
        let hands: [Point2D]
        let regions: [BarRegion]
        func matches(_ other: Search, size: ImageSize) -> Bool {
            hands.count == other.hands.count && zip(hands, other.hands).allSatisfy {
                hypot($0.x - $1.x, $0.y - $1.y) <= min(size.width, size.height) * 0.02
            }
        }
    }
    private var pullUp = AutomaticPullUpBar()
    private var dip = AutomaticDipRails()
    private(set) var state: AutomaticPullUpBar.State = .lookingForGrip
    mutating func reset() { self = Self() }
    static func search(pose: PoseResult, exercise: ExerciseCounter.Exercise) -> Search? {
        switch exercise {
        case .pullUp:
            guard let grip = AutomaticPullUpBar.grip(in: pose) else { return nil }
            return Search(hands: [grip.left, grip.right], regions: [grip.region])
        case .dip:
            guard let grip = AutomaticDipRails.grip(in: pose) else { return nil }
            return Search(hands: grip.hands, regions: grip.regions)
        }
    }
    mutating func observe(pose: PoseResult, exercise: ExerciseCounter.Exercise,
                          side: ArmMeasurement.Side, candidates: [[BarLineCandidate]]) -> ConfirmedBar? {
        let bar: ConfirmedBar?
        switch exercise {
        case .pullUp: bar = pullUp.observe(pose: pose, candidates: candidates.first ?? []); state = pullUp.state
        case .dip: bar = dip.observe(pose: pose, side: side, candidates: candidates); state = dip.state
        }
        return bar
    }
}
