import Foundation

// Shared fixed-reference dwell. Hands locate the search, while every returned
// segment remains an observed image edge. Supports vertical projected rails.
struct AutomaticReferenceStability: Sendable {
    enum Status: String, Codable, Sendable {
        case waiting, invalidEvidence, sourceRegression, candidateStarted, contextChanged
        case sourceGap, edgeChanged, gripMoved, accumulating, qualified
    }
    // Counts/timing only; no image geometry and no additional qualification rule.
    struct Diagnostic: Codable, Equatable, Sendable {
        var status: Status = .waiting
        var observations = 0
        var stableSeconds = 0.0
        var sourceSeconds: Double? = nil
    }
    static let minimumObservations = 4
    static let minimumStableSeconds = 0.9
    static let maximumFrameGap = 0.8
    private(set) var diagnostic = Diagnostic()
    private var edges: [BarSegment] = []
    private var grips: [Point2D] = []
    private var size: ImageSize?
    private var firstTime: PresentationTime?
    private var lastTime: PresentationTime?
    private var observations = 0

    mutating func reset() { self = Self() }

    mutating func observe(edges next: [BarSegment], grips hands: [Point2D], pose: PoseResult) -> Bool {
        let seconds = pose.timestamp.seconds
        guard seconds.isFinite, !next.isEmpty, next.allSatisfy(\.isValid),
              !hands.isEmpty, hands.allSatisfy(\.isFinite), pose.imageSize.isValid else {
            reset(); diagnostic = Diagnostic(status: .invalidEvidence,
                sourceSeconds: seconds.isFinite ? seconds : nil); return false
        }
        let gap = lastTime.map { pose.timestamp.elapsedSeconds(since: $0) }
        if let gap, !gap.isFinite || gap <= 0 {
            reset(); diagnostic = Diagnostic(status: .sourceRegression, sourceSeconds: seconds); return false
        }
        let short = min(pose.imageSize.width, pose.imageSize.height)
        // Report the first failing condition in the existing qualification order.
        let restart: Status?
        if lastTime == nil { restart = .candidateStarted }
        else if size != pose.imageSize || edges.count != next.count || grips.count != hands.count { restart = .contextChanged }
        else if gap! > Self.maximumFrameGap { restart = .sourceGap }
        else if !zip(edges, next).allSatisfy({ Self.sameLine($0, $1, tolerance: short * 0.008) }) { restart = .edgeChanged }
        else if !zip(grips, hands).allSatisfy({ hypot($0.x - $1.x, $0.y - $1.y) <= short * 0.02 }) { restart = .gripMoved }
        else { restart = nil }
        if restart != nil {
            edges = next; grips = hands; size = pose.imageSize
            firstTime = pose.timestamp; observations = 0
        }
        lastTime = pose.timestamp
        observations += 1
        let elapsed = pose.timestamp.elapsedSeconds(since: firstTime ?? pose.timestamp)
        let qualified = observations >= Self.minimumObservations && elapsed >= Self.minimumStableSeconds
        diagnostic = Diagnostic(status: qualified ? .qualified : restart ?? .accumulating,
            observations: observations, stableSeconds: elapsed, sourceSeconds: seconds)
        return qualified
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

// Both measurable supported wrists can locate independently observed rails
// during flexion. Starting a set separately requires extended top support.
// Never use the line between wrists, a body segment, or a rack box as a rail.
struct AutomaticDipRails: Sendable {
    struct Grip: Sendable {
        let hands: [Point2D] // Anatomical left, right, in unmirrored pixels.
        let regions: [BarRegion]
    }
    private(set) var state: AutomaticPullUpBar.State = .lookingForGrip
    private var stability = AutomaticReferenceStability()
    var referenceStabilityDiagnostic: AutomaticReferenceStability.Diagnostic? {
        state == .holding || state == .locked ? stability.diagnostic : nil
    }
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
        grip(in: pose, requiresTopSupport: false)
    }

    static func isStartingPosition(_ pose: PoseResult) -> Bool {
        grip(in: pose, requiresTopSupport: true) != nil
    }

    private static func grip(in pose: PoseResult, requiresTopSupport: Bool) -> Grip? {
        guard pose.imageSize.isValid, pose.timestamp.seconds.isFinite else { return nil }
        let arms = PullUpArmMeasurement(pose: pose).arms
        guard arms.count == 2 else { return nil }
        guard arms.allSatisfy({
            (!requiresTopSupport || $0.estimate.elbowDegrees + 1e-6 >= DipSupportMeasurement.startingDegrees)
                && $0.wrist.y - $0.shoulder.y >= 0.35 * ($0.estimate.upperArmPixels + $0.estimate.forearmPixels)
        }) else { return nil }
        return apparatusGrip(in: pose, arms: arms)
    }

    // Measured contact locates apparatus through every elbow/shoulder phase.
    // It never supplies starting evidence, and both arm chains remain required.
    static func apparatusGrip(in pose: PoseResult) -> Grip? {
        guard pose.imageSize.isValid, pose.timestamp.seconds.isFinite else { return nil }
        return apparatusGrip(in: pose, arms: PullUpArmMeasurement(pose: pose).arms)
    }

    private static func apparatusGrip(in pose: PoseResult, arms: [PullUpArmMeasurement.Arm]) -> Grip? {
        guard arms.count == 2 else { return nil }
        let short = min(pose.imageSize.width, pose.imageSize.height)
        let hands = arms.map(\.wrist)
        // Each rail's independently observed finite support establishes its
        // topology; oblique views need not give the hands equal image height.
        guard abs(hands[0].x - hands[1].x) >= short * 0.12 else { return nil }
        let regions = arms.compactMap { contactRegion(arm: $0, size: pose.imageSize) }
        guard regions.count == 2 else { return nil }
        return Grip(hands: hands, regions: regions)
    }

    // Shared body-excluding crop for either contact mode.
    static func contactRegion(arm: PullUpArmMeasurement.Arm, size: ImageSize) -> BarRegion? {
        let short = min(size.width, size.height), hand = arm.wrist
        var minX = max(0, hand.x - short * 0.12), maxX = min(size.width, hand.x + short * 0.12)
        if hand.x - arm.shoulder.x > short * 0.04 { minX = max(minX, (hand.x + arm.shoulder.x) / 2) }
        if arm.shoulder.x - hand.x > short * 0.04 { maxX = min(maxX, (hand.x + arm.shoulder.x) / 2) }
        let region = BarRegion(.init(x: minX, y: max(0, hand.y - short * 0.08)),
                               .init(x: maxX, y: min(size.height, hand.y + short * 0.20)))
        return region.isValid(in: size) ? region : nil
    }

    static func select(candidates: [BarLineCandidate], hand: Point2D, size: ImageSize,
                       requireUnique: Bool = false, preferredEdge: BarSegment? = nil) -> BarSegment? {
        selection(candidates: candidates, hand: hand, size: size,
                  requireUnique: requireUnique, preferredEdge: preferredEdge)?.edge
    }

    static func minimumPairLength(in size: ImageSize) -> Double {
        max(24, min(size.width, size.height) * 0.04)
    }

    private static func isBelowGrip(_ edge: BarSegment, hand: Point2D) -> Bool {
        // A handle can be visible immediately below the grasp. An artificial
        // gap discards that observed support; edges above the wrist still fail.
        min(edge.a.y, edge.b.y) >= hand.y
    }

    private static func selection(candidates: [BarLineCandidate], hand: Point2D, size: ImageSize,
                                  requireUnique: Bool = false, preferredEdge: BarSegment? = nil) -> ObservedRail? {
        guard size.isValid, hand.isFinite else { return nil }
        let short = min(size.width, size.height)
        let bounds = BarRegion(.init(x: 0, y: 0), .init(x: size.width, y: size.height))
        var tubes: [(pair: BarCandidate, edge: BarSegment)] = []
        for i in candidates.indices {
            for j in candidates.indices where j > i {
                guard let pair = BarFitter.pair(candidates[i].edge, candidates[j].edge,
                    minimumLength: Self.minimumPairLength(in: size)),
                    bounds.contains(pair.firstEdge.a), bounds.contains(pair.firstEdge.b),
                    bounds.contains(pair.secondEdge.a), bounds.contains(pair.secondEdge.b),
                    pair.centerline.distance(to: hand) <= short * 0.12,
                    pair.centerline.midpoint.y >= hand.y + short * 0.025 else { continue }
                let a = candidates[i].edge, b = candidates[j].edge
                guard Self.isBelowGrip(a, hand: hand), Self.isBelowGrip(b, hand: hand) else { continue }
                let edge = a.length == b.length ? (a.distance(to: hand) <= b.distance(to: hand) ? a : b)
                    : (a.length > b.length ? a : b)
                // Rank only complete proposals. A nearby fitted centerline
                // cannot let an unreachable silhouette hide a valid rail.
                guard edge.distance(to: hand) <= short * 0.12 else { continue }
                tubes.append((pair, edge))
            }
        }
        // A grasp can occlude the handle's upper end. Prefer alignment with
        // the hand over longer offset clutter or a closer visible endpoint.
        // Eligibility above still requires finite, observed support near it.
        tubes.sort {
            let a = $0.pair.centerline.perpendicularDistance(to: hand)
            let b = $1.pair.centerline.perpendicularDistance(to: hand)
            if a != b { return a < b }
            if $0.pair.geometryScore != $1.pair.geometryScore { return $0.pair.geometryScore > $1.pair.geometryScore }
            return $0.pair.centerline.distance(to: hand) < $1.pair.centerline.distance(to: hand)
        }
        guard let tube = tubes.first else { return nil }
        if requireUnique && !tubes.allSatisfy({ AutomaticReferenceStability.sameLine(
            tube.pair.centerline, $0.pair.centerline, tolerance: short * 0.01) }) { return nil }
        // Visible lengths can alternate between the two silhouettes of one
        // physical tube. Keep the old silhouette only if it is freshly observed
        // in this fully eligible pair, using the unchanged dwell correspondence.
        if let preferredEdge, let fresh = [tube.pair.firstEdge, tube.pair.secondEdge].filter({
            $0.distance(to: hand) <= short * 0.12 && Self.isBelowGrip($0, hand: hand)
                && AutomaticReferenceStability.sameLine(preferredEdge, $0, tolerance: short * 0.008)
        }).min(by: { preferredEdge.perpendicularDistance(to: $0.midpoint)
            < preferredEdge.perpendicularDistance(to: $1.midpoint) }) {
            return ObservedRail(pair: tube.pair, edge: fresh.ordered)
        }
        // Select one of the real silhouette edges, never the fitted centerline.
        return ObservedRail(pair: tube.pair, edge: tube.edge.ordered)
    }

    mutating func observe(pose: PoseResult, side: ArmMeasurement.Side,
                          candidates: [[BarLineCandidate]]) -> ConfirmedBar? {
        guard state != .locked else { return nil }
        guard let grip = Self.apparatusGrip(in: pose), candidates.count == 2 else { reset(); return nil }
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
                        && Self.isBelowGrip($0, hand: grip.hands[index])
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

// One current measured arm can locate its own uniquely observed rail. This
// neither invents the hidden side nor bypasses ambiguous bilateral evidence.
struct AutomaticSingleDipRail: Sendable {
    struct Contact: Sendable {
        let arm: PullUpArmMeasurement.Arm
        let region: BarRegion
        var isSupportLike: Bool {
            arm.wrist.y - arm.shoulder.y >= 0.35 * (arm.estimate.upperArmPixels + arm.estimate.forearmPixels)
        }
        var isStartingPosition: Bool {
            isSupportLike && arm.estimate.elbowDegrees + 1e-6 >= DipSupportMeasurement.startingDegrees
        }
    }
    private struct Source: Equatable, Sendable { let size: ImageSize; let backend: String; let revision: Int }
    private var source: Source?
    private var highWater: PresentationTime?
    private var witnessedSide: ArmMeasurement.Side?
    private var witnessedEdge: BarSegment?
    private var stability = AutomaticReferenceStability()
    private(set) var state: AutomaticPullUpBar.State = .lookingForGrip
    var referenceStabilityDiagnostic: AutomaticReferenceStability.Diagnostic? {
        state == .holding || state == .locked ? stability.diagnostic : nil
    }
    mutating func reset() { self = Self() }
    private mutating func clearEvidence() {
        stability.reset(); witnessedSide = nil; witnessedEdge = nil; state = .lookingForGrip
    }
    static func contact(in pose: PoseResult) -> Contact? {
        guard pose.imageSize.isValid, pose.timestamp.seconds.isFinite, pose.people.count == 1 else { return nil }
        let points = pose.people[0].landmarks
        guard points.count <= 64 else { return nil }
        // Missing/weak joints do not hide malformed evidence elsewhere in the
        // hidden chain. The same finite/confidence/image bounds still apply.
        for joint in ArmMeasurement.Side.allCases.flatMap(\.joints) {
            let matches = points.filter { $0.joint == joint }
            guard matches.count <= 1, matches.allSatisfy({ $0.position.isFinite && $0.confidence.isFinite
                && (0...1).contains($0.confidence) && (0...pose.imageSize.width).contains($0.position.x)
                && (0...pose.imageSize.height).contains($0.position.y) }) else { return nil }
        }
        let arms = PullUpArmMeasurement(pose: pose).arms
        guard arms.count == 1, let arm = arms.first,
              let region = AutomaticDipRails.contactRegion(arm: arm, size: pose.imageSize) else { return nil }
        let other: ArmMeasurement.Side = arm.side == .left ? .right : .left
        let shoulder = points.first { $0.joint == other.joints[0] && $0.isVisible() }
        let wrist = points.first { $0.joint == other.joints[2] && $0.isVisible() }
        // An unavailable elbow cannot hide clearly overhead contact geometry.
        // This reuses the existing overhead-discrimination boundary.
        if let shoulder, let wrist,
           shoulder.position.y - wrist.position.y > min(pose.imageSize.width, pose.imageSize.height) * 0.08 { return nil }
        return Contact(arm: arm, region: region)
    }
    mutating func observe(pose: PoseResult, side: ArmMeasurement.Side,
                          candidates: [BarLineCandidate]) -> ConfirmedBar? {
        guard state != .locked else { return nil }
        let context = Source(size: pose.imageSize, backend: pose.backend, revision: pose.requestRevision)
        let gap = highWater.map { pose.timestamp.elapsedSeconds(since: $0) }
        guard pose.timestamp.seconds.isFinite, gap.map({ $0.isFinite && $0 > 0 }) ?? true else {
            clearEvidence(); return nil
        }
        highWater = pose.timestamp
        if source != context { clearEvidence(); source = context }
        guard let contact = Self.contact(in: pose), contact.arm.side == side else { clearEvidence(); return nil }
        if witnessedSide != nil && witnessedSide != contact.arm.side { clearEvidence() }
        guard candidates.count <= BarFitter.maximumSegments else { clearEvidence(); return nil }
        let region = contact.region
        let crop = BarRegion(.init(x: floor(region.minX) - 1, y: floor(region.minY) - 1),
                             .init(x: ceil(region.maxX) + 1, y: ceil(region.maxY) + 1))
        let local = candidates.filter { crop.contains($0.edge.a) && crop.contains($0.edge.b) }
        guard let edge = AutomaticDipRails.select(candidates: local, hand: contact.arm.wrist,
            size: pose.imageSize, requireUnique: true, preferredEdge: witnessedEdge) else {
            clearEvidence(); state = candidates.isEmpty ? .findingEdge : .ambiguous; return nil
        }
        witnessedSide = contact.arm.side; witnessedEdge = edge; state = .holding
        guard stability.observe(edges: [edge], grips: [contact.arm.wrist], pose: pose) else { return nil }
        let bar = ConfirmedBar(role: side == .left ? .leftDipRail : .rightDipRail, method: .automaticContours,
            referenceEdge: edge, oppositeEdge: nil, imageSize: pose.imageSize, sourceTime: pose.timestamp)
        guard bar.isValid else { clearEvidence(); return nil }
        state = .locked; return bar
    }
}

// One acquisition interface for live camera and recorded review. The underlying
// selectors differ, but cancellation, freshness and camera calibration stay shared.
struct AutomaticApparatusReference: Sendable {
    struct Search: Sendable {
        let hands: [Point2D]
        let regions: [BarRegion]
        let minimumObservedLength: Double?
        let gripSides: [ArmMeasurement.Side]
        init(hands: [Point2D], regions: [BarRegion], minimumObservedLength: Double? = nil,
             gripSides: [ArmMeasurement.Side] = []) {
            self.hands = hands; self.regions = regions; self.minimumObservedLength = minimumObservedLength
            self.gripSides = gripSides
        }
        func matches(_ other: Search, size: ImageSize) -> Bool {
            gripSides == other.gripSides && hands.count == other.hands.count && zip(hands, other.hands).allSatisfy {
                hypot($0.x - $1.x, $0.y - $1.y) <= min(size.width, size.height) * 0.02
            }
        }
    }
    private var pullUp = AutomaticPullUpBar()
    private var dip = AutomaticDipRails()
    private var sharedDipBar = AutomaticDipBar()
    private var singleDipRail = AutomaticSingleDipRail()
    private struct Source: Equatable, Sendable { let size: ImageSize; let backend: String; let revision: Int }
    private var source: Source?
    private var highWater: PresentationTime?
    private(set) var state: AutomaticPullUpBar.State = .lookingForGrip
    private(set) var referenceStabilityDiagnostic: AutomaticReferenceStability.Diagnostic?
    mutating func reset() { self = Self() }
    // Cancellation revokes evidence; only reset() activates a new source epoch.
    mutating func invalidateEvidence() { clearEvidence() }
    private mutating func clearEvidence() {
        pullUp.reset(); dip.reset(); sharedDipBar.reset(); singleDipRail.reset()
        state = .lookingForGrip; referenceStabilityDiagnostic = nil
    }
    static func search(pose: PoseResult, exercise: ExerciseCounter.Exercise) -> Search? {
        switch exercise {
        case .pullUp:
            guard let grip = AutomaticPullUpBar.grip(in: pose) else { return nil }
            return Search(hands: [grip.left, grip.right], regions: [grip.region], gripSides: [.left, .right])
        case .dip:
            guard let grip = AutomaticDipRails.apparatusGrip(in: pose) else {
                guard let contact = AutomaticSingleDipRail.contact(in: pose) else { return nil }
                return Search(hands: [contact.arm.wrist], regions: [contact.region],
                    minimumObservedLength: AutomaticDipRails.minimumPairLength(in: pose.imageSize),
                    gripSides: [contact.arm.side])
            }
            let shared = AutomaticDipBar.region(grip: grip, size: pose.imageSize)
            // Retain observed curved-handle fragments that are long enough for
            // paired support, rather than imposing a whole-crop length first.
            return Search(hands: grip.hands, regions: grip.regions + (shared.map { [$0] } ?? []),
                          minimumObservedLength: AutomaticDipRails.minimumPairLength(in: pose.imageSize), gripSides: [.left, .right])
        }
    }
    mutating func observe(pose: PoseResult, exercise: ExerciseCounter.Exercise,
                          side: ArmMeasurement.Side, candidates: [[BarLineCandidate]]) -> ConfirmedBar? {
        // Only explicit reset() activates a new source clock. Mode/context
        // changes revoke evidence without allowing stale frames to earn dwell.
        let gap = highWater.map { pose.timestamp.elapsedSeconds(since: $0) }
        guard pose.timestamp.seconds.isFinite, gap.map({ $0.isFinite && $0 > 0 }) ?? true else {
            clearEvidence(); return nil
        }
        highWater = pose.timestamp
        let context = Source(size: pose.imageSize, backend: pose.backend, revision: pose.requestRevision)
        if source != context { clearEvidence(); source = context }
        let bar: ConfirmedBar?
        switch exercise {
        case .pullUp:
            bar = pullUp.observe(pose: pose, candidates: candidates.first ?? []); state = pullUp.state
            referenceStabilityDiagnostic = pullUp.referenceStabilityDiagnostic
        case .dip:
            if candidates.count == 1 {
                dip.reset(); sharedDipBar.reset()
                let single = singleDipRail.observe(pose: pose, side: side, candidates: candidates[0])
                state = singleDipRail.state; referenceStabilityDiagnostic = singleDipRail.referenceStabilityDiagnostic
                return single
            }
            singleDipRail.reset()
            guard candidates.count == 2 || candidates.count == 3 else {
                dip.reset(); sharedDipBar.reset(); state = .lookingForGrip; referenceStabilityDiagnostic = nil; return nil
            }
            bar = dip.observe(pose: pose, side: side, candidates: Array(candidates.prefix(2)))
            if dip.state == .holding || dip.state == .locked {
                // Preserve an independently witnessed parallel-rail dwell.
                sharedDipBar.reset(); state = dip.state; referenceStabilityDiagnostic = dip.referenceStabilityDiagnostic
            } else {
                let shared = sharedDipBar.observe(pose: pose, side: side,
                    candidates: candidates.count == 3 ? candidates[2] : [])
                state = sharedDipBar.state == .holding || sharedDipBar.state == .locked ? sharedDipBar.state : dip.state
                referenceStabilityDiagnostic = sharedDipBar.state == .holding || sharedDipBar.state == .locked
                    ? sharedDipBar.referenceStabilityDiagnostic : dip.referenceStabilityDiagnostic
                return shared
            }
        }
        return bar
    }
}
