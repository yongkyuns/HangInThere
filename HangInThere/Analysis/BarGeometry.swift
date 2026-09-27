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
        return method == .manualEdge
    }
}
