import Foundation

// Apparatus geometry uses upright top-left pixels, just like PoseResult.
// A straight image feature is only a candidate until the user confirms the bar.
struct BarSegment: Codable, Equatable, Sendable {
    let a: Point2D
    let b: Point2D
    var length: Double { hypot(b.x - a.x, b.y - a.y) }
    var isValid: Bool { a.isFinite && b.isFinite && length.isFinite && length >= 2 }
    var midpoint: Point2D { Point2D(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2) }
    var ordered: Self {
        a.x < b.x || (a.x == b.x && a.y <= b.y) ? self : Self(a: b, b: a)
    }
    func distance(to p: Point2D) -> Double {
        guard isValid, p.isFinite else { return .infinity }
        let t = max(0, min(1, ((p.x-a.x)*(b.x-a.x) + (p.y-a.y)*(b.y-a.y)) / (length*length)))
        return hypot(p.x - a.x - t*(b.x-a.x), p.y - a.y - t*(b.y-a.y))
    }
    // Distance to the infinite image line defined by this observed edge. The
    // finite endpoints establish the line; exercise motion may project beyond
    // their span, so movement analysis must not clamp the body point to an end.
    func perpendicularDistance(to p: Point2D) -> Double {
        guard isValid, p.isFinite else { return .infinity }
        let cross = (b.x-a.x)*(p.y-a.y) - (b.y-a.y)*(p.x-a.x)
        return abs(cross) / length
    }
}

struct BarRegion: Equatable, Sendable {
    let minX: Double, minY: Double, maxX: Double, maxY: Double
    var width: Double { maxX - minX }
    var height: Double { maxY - minY }
    func contains(_ p: Point2D) -> Bool {
        p.isFinite && (minX...maxX).contains(p.x) && (minY...maxY).contains(p.y)
    }
    func isValid(in size: ImageSize) -> Bool {
        size.isValid && [minX, minY, maxX, maxY].allSatisfy(\.isFinite)
            && minX >= 0 && minY >= 0 && maxX <= size.width && maxY <= size.height
            && width >= 16 && height >= 16
    }
    // Vision contours use Float coordinates. A crop-border sample can overshoot
    // [0,1] by a few ULPs; normalize only that rounding, not genuinely outside data.
    func contourPoint(x: Float, y: Float) -> Point2D? {
        let tolerance = 4 * Float.ulpOfOne
        guard x.isFinite, y.isFinite, (-tolerance...1+tolerance).contains(x),
              (-tolerance...1+tolerance).contains(y),
              [minX,minY,maxX,maxY].allSatisfy(\.isFinite), width > 0, height > 0 else { return nil }
        return Point2D(x: minX + Double(min(1,max(0,x)))*width,
                       y: minY + (1-Double(min(1,max(0,y))))*height)
    }
    init(_ a: Point2D, _ b: Point2D) {
        minX = min(a.x, b.x); minY = min(a.y, b.y)
        maxX = max(a.x, b.x); maxY = max(a.y, b.y)
    }
}

struct BarCandidate: Codable, Equatable, Sendable {
    let firstEdge: BarSegment
    let secondEdge: BarSegment
    // A deterministic geometry ranking, NOT a probability of being a bar.
    let geometryScore: Double
    var centerline: BarSegment {
        BarSegment(a: Point2D(x: (firstEdge.a.x+secondEdge.a.x)/2, y: (firstEdge.a.y+secondEdge.a.y)/2),
                   b: Point2D(x: (firstEdge.b.x+secondEdge.b.x)/2, y: (firstEdge.b.y+secondEdge.b.y)/2))
    }
    // "Upper" is an image-space silhouette edge, not calibrated world height.
    // Near-vertical rails have no useful upper-edge interpretation.
    var upperImageEdge: BarSegment? {
        guard abs(centerline.b.x-centerline.a.x) >= 0.2 * centerline.length else { return nil }
        return firstEdge.midpoint.y <= secondEdge.midpoint.y ? firstEdge : secondEdge
    }
}


struct BarLineCandidate: Codable, Equatable, Sendable {
    let edge: BarSegment
    // Deterministic observed-support ranking, not semantic confidence.
    let geometryScore: Double
}

enum BarLineFitter {
    private struct Group {
        var points: [Point2D]
        var ux: Double
        var uy: Double
        var offset: Double
        var t0: Double
        var t1: Double
    }

    // Guided single-edge recovery. It can bridge short gaps between collinear
    // observed contour fragments, but never extends past the outermost samples.
    static func candidates(contours: [[Point2D]], region: BarRegion,
                           size: ImageSize) throws -> [BarLineCandidate] {
        guard region.isValid(in: size) else { throw BarFitError.invalidRegion }
        guard contours.reduce(0, { $0 + $1.count }) <= BarFitter.maximumPoints else {
            throw BarFitError.tooComplex
        }

        var budget = 4_000_000
        var segments: [BarSegment] = []
        for contour in contours {
            guard contour.count > 1, contour.allSatisfy({ region.contains($0) }) else { continue }
            let points = try simplify(contour, tolerance: 1.0, budget: &budget)
            for i in 1..<points.count {
                let edge = BarSegment(a: points[i - 1], b: points[i])
                if edge.isValid, edge.length >= 2 { segments.append(edge) }
                guard segments.count <= BarFitter.maximumSegments else { throw BarFitError.tooComplex }
            }
        }

        let major = max(region.width, region.height)
        let maximumGap = min(45.0, 0.20 * major)
        var groups: [Group] = []
        for edge in segments.sorted(by: { $0.length > $1.length }) {
            guard let unit = canonicalUnit(edge) else { continue }
            let points = [edge.a, edge.b]
            var placed = false
            for index in groups.indices {
                let dot = max(-1.0, min(1.0, unit.0 * groups[index].ux + unit.1 * groups[index].uy))
                guard acos(dot) <= 8 * .pi / 180 else { continue }
                let gnx = -groups[index].uy, gny = groups[index].ux
                let candidateOffset = points.reduce(0.0) {
                    $0 + $1.x * gnx + $1.y * gny
                } / 2
                let projections = points.map { $0.x * groups[index].ux + $0.y * groups[index].uy }
                let r0 = projections.min()!, r1 = projections.max()!
                let gap = max(groups[index].t0 - r1, r0 - groups[index].t1, 0)
                guard abs(candidateOffset - groups[index].offset) <= 5,
                      gap <= maximumGap else { continue }
                groups[index].points.append(contentsOf: points)
                if let fitted = fit(groups[index].points) {
                    groups[index].ux = fitted.ux
                    groups[index].uy = fitted.uy
                    groups[index].offset = fitted.offset
                    groups[index].t0 = fitted.t0
                    groups[index].t1 = fitted.t1
                }
                placed = true
                break
            }
            if !placed, let fitted = fit(points) {
                groups.append(Group(points: points, ux: fitted.ux, uy: fitted.uy,
                                    offset: fitted.offset, t0: fitted.t0, t1: fitted.t1))
            }
        }

        var result: [BarLineCandidate] = []
        for group in groups {
            let nx = -group.uy, ny = group.ux
            let a = Point2D(x: group.t0 * group.ux + group.offset * nx,
                            y: group.t0 * group.uy + group.offset * ny)
            let b = Point2D(x: group.t1 * group.ux + group.offset * nx,
                            y: group.t1 * group.uy + group.offset * ny)
            let edge = BarSegment(a: a, b: b).ordered
            guard edge.isValid, edge.length >= 0.25 * major,
                  !isCropBorder(edge, region: region) else { continue }
            result.append(BarLineCandidate(edge: edge, geometryScore: edge.length))
        }

        result.sort {
            if $0.geometryScore != $1.geometryScore { return $0.geometryScore > $1.geometryScore }
            if $0.edge.midpoint.y != $1.edge.midpoint.y { return $0.edge.midpoint.y < $1.edge.midpoint.y }
            return $0.edge.midpoint.x < $1.edge.midpoint.x
        }
        var unique: [BarLineCandidate] = []
        for candidate in result {
            if !unique.contains(where: {
                $0.edge.distance(to: candidate.edge.midpoint) < 2
                    && candidate.edge.distance(to: $0.edge.midpoint) < 2
            }) {
                unique.append(candidate)
            }
        }
        return Array(unique.prefix(12))
    }

    private static func canonicalUnit(_ edge: BarSegment) -> (Double, Double)? {
        guard edge.isValid else { return nil }
        var ux = (edge.b.x - edge.a.x) / edge.length
        var uy = (edge.b.y - edge.a.y) / edge.length
        if ux < 0 || (abs(ux) < 1e-12 && uy < 0) { ux = -ux; uy = -uy }
        return (ux, uy)
    }

    private static func fit(_ points: [Point2D])
        -> (ux: Double, uy: Double, offset: Double, t0: Double, t1: Double)? {
        guard points.count >= 2, points.allSatisfy({ $0.isFinite }) else { return nil }
        let mx = points.reduce(0.0) { $0 + $1.x } / Double(points.count)
        let my = points.reduce(0.0) { $0 + $1.y } / Double(points.count)
        var xx = 0.0, xy = 0.0, yy = 0.0
        for point in points {
            let dx = point.x - mx, dy = point.y - my
            xx += dx * dx; xy += dx * dy; yy += dy * dy
        }
        guard xx + yy > 1e-12 else { return nil }
        let angle = 0.5 * atan2(2 * xy, xx - yy)
        var ux = cos(angle), uy = sin(angle)
        if ux < 0 || (abs(ux) < 1e-12 && uy < 0) { ux = -ux; uy = -uy }
        let nx = -uy, ny = ux
        let offset = points.reduce(0.0) { $0 + $1.x * nx + $1.y * ny } / Double(points.count)
        let ts = points.map { $0.x * ux + $0.y * uy }
        guard let t0 = ts.min(), let t1 = ts.max(), t1 > t0 else { return nil }
        return (ux, uy, offset, t0, t1)
    }

    private static func isCropBorder(_ edge: BarSegment, region: BarRegion) -> Bool {
        let tolerance = 2.0
        return (abs(edge.a.x - region.minX) <= tolerance && abs(edge.b.x - region.minX) <= tolerance)
            || (abs(edge.a.x - region.maxX) <= tolerance && abs(edge.b.x - region.maxX) <= tolerance)
            || (abs(edge.a.y - region.minY) <= tolerance && abs(edge.b.y - region.minY) <= tolerance)
            || (abs(edge.a.y - region.maxY) <= tolerance && abs(edge.b.y - region.maxY) <= tolerance)
    }

    private static func simplify(_ points: [Point2D], tolerance: Double,
                                 budget: inout Int) throws -> [Point2D] {
        guard points.count > 2 else { return points }
        var keep = Set([0, points.count - 1])
        var stack = [(0, points.count - 1)]
        while let (a, b) = stack.popLast() {
            guard b > a + 1 else { continue }
            let segment = BarSegment(a: points[a], b: points[b])
            var farthest = a, distance = tolerance
            for i in (a + 1)..<b {
                budget -= 1
                guard budget >= 0 else { throw BarFitError.tooComplex }
                let d = segment.isValid
                    ? segment.distance(to: points[i])
                    : hypot(points[i].x - points[a].x, points[i].y - points[a].y)
                if d > distance { distance = d; farthest = i }
            }
            if farthest != a {
                keep.insert(farthest)
                stack.append((a, farthest))
                stack.append((farthest, b))
            }
        }
        return keep.sorted().map { points[$0] }
    }
}

enum BarFitError: Error { case tooComplex, invalidRegion, invalidContour }

enum BarFitter {
    static let maximumPoints = 200_000
    static let maximumSegments = 512

    // Work in pixel units; normalized x/y have different scales on a non-square image.
    // All retained segments follow real contour samples, never an extrapolated rack box.
    static func candidates(contours: [[Point2D]], region: BarRegion, size: ImageSize) throws -> [BarCandidate] {
        guard region.isValid(in: size) else { throw BarFitError.invalidRegion }
        guard contours.reduce(0, { $0 + $1.count }) <= maximumPoints else { throw BarFitError.tooComplex }
        var edges: [BarSegment] = []
        var workBudget = 4_000_000
        let minimumLength = max(24, 0.30 * max(region.width, region.height))
        for contour in contours {
            guard contour.count > 2, contour.allSatisfy({ region.contains($0) }) else { continue }
            var closed = contour
            if closed.first != closed.last { closed.append(closed[0]) }
            let points = try simplify(closed, tolerance: 1.5, budget: &workBudget)
            for i in 1..<points.count {
                let line = BarSegment(a: points[i-1], b: points[i]).ordered
                guard line.isValid, line.length >= minimumLength else { continue }
                // The crop border itself is not evidence of a gripping-bar edge.
                let border = (abs(line.a.x-region.minX) < 1.5 && abs(line.b.x-region.minX) < 1.5)
                    || (abs(line.a.x-region.maxX) < 1.5 && abs(line.b.x-region.maxX) < 1.5)
                    || (abs(line.a.y-region.minY) < 1.5 && abs(line.b.y-region.minY) < 1.5)
                    || (abs(line.a.y-region.maxY) < 1.5 && abs(line.b.y-region.maxY) < 1.5)
                if !border { edges.append(line) }
                guard edges.count <= maximumSegments else { throw BarFitError.tooComplex }
            }
        }
        var result: [BarCandidate] = []
        for i in edges.indices {
            for j in edges.indices where j > i {
                if let candidate = pair(edges[i], edges[j], minimumLength: minimumLength) {
                    result.append(candidate)
                }
            }
        }
        result.sort {
            if $0.geometryScore != $1.geometryScore { return $0.geometryScore > $1.geometryScore }
            if $0.centerline.midpoint.y != $1.centerline.midpoint.y { return $0.centerline.midpoint.y < $1.centerline.midpoint.y }
            return $0.centerline.midpoint.x < $1.centerline.midpoint.x
        }
        var unique: [BarCandidate] = []
        for candidate in result {
            // Collapse duplicate contours from opposite contrast polarities, not adjacent bars.
            if !unique.contains(where: {
                let same = $0.firstEdge.distance(to: candidate.firstEdge.midpoint) < 1.5
                    && $0.secondEdge.distance(to: candidate.secondEdge.midpoint) < 1.5
                let reversed = $0.firstEdge.distance(to: candidate.secondEdge.midpoint) < 1.5
                    && $0.secondEdge.distance(to: candidate.firstEdge.midpoint) < 1.5
                return same || reversed
            }) { unique.append(candidate) }
        }
        return Array(unique.prefix(12))
    }

    static func pair(_ p: BarSegment, _ q: BarSegment, minimumLength: Double) -> BarCandidate? {
        guard p.isValid, q.isValid, minimumLength.isFinite, minimumLength >= 2 else { return nil }
        let p = p.ordered, q = q.ordered
        let ux = (p.b.x-p.a.x)/p.length, uy = (p.b.y-p.a.y)/p.length
        let vx = (q.b.x-q.a.x)/q.length, vy = (q.b.y-q.a.y)/q.length
        let dot = ux*vx + uy*vy
        guard dot >= cos(5 * .pi / 180) else { return nil }
        func projection(_ r: Point2D) -> Double { (r.x-p.a.x)*ux + (r.y-p.a.y)*uy }
        let q0 = projection(q.a), q1 = projection(q.b)
        let lo = max(0, q0), hi = min(p.length, q1)
        guard hi-lo >= minimumLength, q1-q0 > 0 else { return nil }
        func point(_ t: Double) -> Point2D { Point2D(x: p.a.x+t*ux, y: p.a.y+t*uy) }
        func onQ(_ t: Double) -> Point2D {
            let k = (t-q0)/(q1-q0)
            return Point2D(x: q.a.x+k*(q.b.x-q.a.x), y: q.a.y+k*(q.b.y-q.a.y))
        }
        let a = point(lo), b = point(hi), c = onQ(lo), d = onQ(hi)
        let w0 = (c.x-a.x)*(-uy) + (c.y-a.y)*ux
        let w1 = (d.x-b.x)*(-uy) + (d.y-b.y)*ux
        let narrow = min(abs(w0), abs(w1)), wide = max(abs(w0), abs(w1))
        // Require two distinct noncrossing edges with shared visible support.
        // A crop truncates length independently of bar thickness: do not require
        // a whole-apparatus aspect ratio from this local visible section.
        guard w0*w1 > 0, narrow >= 2, wide <= 40,
              narrow >= 0.4*wide, hi-lo >= wide else { return nil }
        return BarCandidate(firstEdge: BarSegment(a: a, b: b), secondEdge: BarSegment(a: c, b: d),
                            geometryScore: (hi-lo)*dot)
    }

    private static func simplify(_ points: [Point2D], tolerance: Double, budget: inout Int) throws -> [Point2D] {
        guard points.count > 2 else { return points }
        var keep = Set([0, points.count-1])
        var stack = [(0, points.count-1)]
        while let (a, b) = stack.popLast() {
            guard b > a+1 else { continue }
            let segment = BarSegment(a: points[a], b: points[b])
            var farthest = a, distance = tolerance
            for i in (a+1)..<b {
                budget -= 1
                guard budget >= 0 else { throw BarFitError.tooComplex }
                let d = segment.isValid ? segment.distance(to: points[i]) : hypot(points[i].x-points[a].x, points[i].y-points[a].y)
                if d > distance { distance = d; farthest = i }
            }
            if farthest != a { keep.insert(farthest); stack.append((a, farthest)); stack.append((farthest, b)) }
        }
        return keep.sorted().map { points[$0] }
    }
}

struct ConfirmedBar: Equatable, Sendable {
    enum Role: String, Hashable, Sendable {
        case pullUpGrip, leftDipRail, rightDipRail
        var title: String {
            switch self {
            case .pullUpGrip: "Pull-up gripping bar"
            case .leftDipRail: "Dip rail for the left hand"
            case .rightDipRail: "Dip rail for the right hand"
            }
        }
    }
    enum Method: String, Sendable { case guidedContours, manualEdge }
    let role: Role
    let method: Method
    let referenceEdge: BarSegment
    let oppositeEdge: BarSegment?
    let imageSize: ImageSize
    let sourceTime: PresentationTime

    var isValid: Bool {
        let bounds = BarRegion(Point2D(x: 0, y: 0), Point2D(x: imageSize.width, y: imageSize.height))
        guard imageSize.isValid, sourceTime.seconds.isFinite, referenceEdge.isValid,
              referenceEdge.length >= 24, bounds.contains(referenceEdge.a), bounds.contains(referenceEdge.b) else { return false }
        if let oppositeEdge {
            return method == .guidedContours && oppositeEdge.isValid && bounds.contains(oppositeEdge.a)
                && bounds.contains(oppositeEdge.b)
                && BarFitter.pair(referenceEdge, oppositeEdge, minimumLength: 24) != nil
        }
        return method == .manualEdge || method == .guidedContours
    }
}