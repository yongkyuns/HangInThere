package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.*

/** Raw ordered contour observations in upright image pixels. Their original
 * adjacency is retained; rejecting a point never joins the surrounding pieces. */
@Serializable
data class RailContour(val points: List<Point2D>, val darkOnLight: Boolean)

/** Geometric proposal, not a confirmed apparatus, contact or repetition. The
 * boundaries contain only mutually observed finite support; contour indices
 * refer to this one input observation, not persistent physical-object identity. */
@Serializable
data class ObservedRailCandidate(val firstContour: Int, val secondContour: Int,
    val firstBoundary: ObservedCurve, val secondBoundary: ObservedCurve,
    val supportArcPixels: Double, val minimumWidthPixels: Double, val maximumWidthPixels: Double,
    val contactDistancePixels: Double)

/** Pairs locally parallel curved silhouettes without fitting their chords.
 * Native adapters own pixels/edge extraction; this shared code owns geometry.
 * The fixed width/angle/arc rules originate in the source-recording experiment;
 * this implementation additionally preserves original simplified contour vertices,
 * ordered correspondence and reciprocal support on the same observed subarc.
 * Each run retains at most 256 simplified vertices and 256 arc samples.
 * The four-million-operation guard covers simplification/tangent/intersection
 * work; bounded curve validation has additional quadratic work. Candidate
 * ambiguity, distinct rails, source continuity, camera and grip dwell belong to
 * acquisition; receiving a proposal never confirms any of those conditions. */
object ObservedRailFitter {
    /** The existing curved-only consumer keeps its original proposal set.
     * finiteEdges also accepts truly observed straight subpaths; it does not
     * replace a curved path with its chord or extend either finite boundary. */
    @Serializable enum class Mode { curvedOnly, finiteEdges }
    /** Image-region pruning, not evidence of mechanical contact. Independent
     * MCP centres may project below a gripped tube. aroundGrip retains actual
     * source paths throughout the supplied ROI and uses the same finite-distance
     * bound. Existing consumers keep their original below-grip restriction. */
    @Serializable enum class ContactRegion { belowGrip, aroundGrip }
    const val maximumRuns = 512
    const val maximumCandidates = 512
    private const val maximumComparisons = 4_000_000
    private const val borderPixels = 1.5
    private val cornerCosine = cos(30.0 * PI / 180)
    private val tangentCosine = cos(5.0 * PI / 180)
    private class Budget {
        private var remaining = maximumComparisons
        fun spend() { if (--remaining < 0) throw BarFitException("tooComplex") }
    }
    private data class Run(val contour: Int, val dark: Boolean, val original: List<Point2D>, val originalArc: List<Double>,
        val points: List<Point2D>, val arc: List<Double>, val tangents: List<Point2D>)
    private data class Intersection(val point: Point2D, val sourceArc: Double, val width: Double)
    private data class Pairing(val first: ObservedCurve, val second: ObservedCurve,
        val arc: Double, val narrow: Double, val wide: Double)

    fun candidates(contours: List<RailContour>, region: BarRegion, size: ImageSize,
                   grip: Point2D, mode: Mode = Mode.curvedOnly,
                   contactRegion: ContactRegion = ContactRegion.belowGrip): List<ObservedRailCandidate> {
        if (!region.isValid(size) || !grip.isFinite || grip.x !in 0.0..size.width || grip.y !in 0.0..size.height)
            throw BarFitException("invalidRegion")
        if (contours.size > BarFitter.maximumSegments) throw BarFitException("tooComplex")
        var pointCount = 0
        for (contour in contours) {
            if (contour.points.size > BarFitter.maximumPoints - pointCount) throw BarFitException("tooComplex")
            pointCount += contour.points.size
            if (contour.points.any { !it.isFinite || !region.contains(it) }) throw BarFitException("invalidContour")
        }
        val budget = Budget()
        val short = min(size.width, size.height)
        val minimum = AutomaticDipRails.minimumPairLength(size)
        val runs = mutableListOf<Run>()
        for ((index, contour) in contours.withIndex()) {
            val piece = mutableListOf<Point2D>()
            fun finishPiece() {
                if (piece.size >= if (mode == Mode.curvedOnly) 3 else 2)
                    addRuns(piece, index, contour.darkOnLight, minimum, size, budget, runs, mode)
                piece.clear()
            }
            for (point in contour.points) {
                if (contactRegion == ContactRegion.belowGrip && point.y < grip.y || point.x <= region.minX + borderPixels ||
                    point.x >= region.maxX - borderPixels || point.y <= region.minY + borderPixels ||
                    point.y >= region.maxY - borderPixels) finishPiece()
                else if (piece.lastOrNull() != point) piece += point
            }
            finishPiece()
        }
        val result = mutableListOf<ObservedRailCandidate>()
        for (i in runs.indices) for (j in i + 1 until runs.size) {
            val p = runs[i]; val q = runs[j]
            if (p.dark != q.dark) continue
            val matched = pair(p, q, minimum, size, budget) ?: continue
            val firstDistance = ObservedCurveGeometry.distance(matched.first, grip, size) ?: continue
            val secondDistance = ObservedCurveGeometry.distance(matched.second, grip, size) ?: continue
            val distance = min(firstDistance, secondDistance)
            if (distance > short * .12) continue
            val selected = if (firstDistance <= secondDistance) p else q
            if (contactRegion == ContactRegion.belowGrip &&
                selected.original.sumOf { it.y / selected.original.size } < grip.y + short * .025) continue
            result += ObservedRailCandidate(p.contour, q.contour, matched.first, matched.second,
                matched.arc, matched.narrow, matched.wide, distance)
            if (result.size > maximumCandidates) throw BarFitException("tooComplex")
        }
        // Preserve contour/run enumeration, including every alternative.
        // Sub-ULP hypot differences must not reorder equivalent proposals on
        // JVM and Native. Proximity is a bound, not a semantic quality score.
        return result
    }

    private fun addRuns(points: List<Point2D>, index: Int, dark: Boolean, minimum: Double, size: ImageSize,
                        budget: Budget, result: MutableList<Run>, mode: Mode) {
        val poly = BarFitter.simplify(points, 1.0, budget::spend)
        if (poly.size < if (mode == Mode.curvedOnly) 3 else 2) return
        var start = 0
        fun retain(end: Int) {
            if (end - start < if (mode == Mode.curvedOnly) 2 else 1) return
            val original = poly.subList(start, end + 1).toList()
            val sampled = ObservedCurveGeometry.sampling(ObservedCurve(original), size) ?: return
            if (sampled.arc.last() < minimum) return
            val window = max(1, ceil(minimum / (2 * ObservedCurveGeometry.sampleSpacingPixels)).toInt())
            val tangents = sampled.points.indices.map { i ->
                val a = sampled.points[max(0, i - window)]
                val b = sampled.points[min(sampled.points.lastIndex, i + window)]
                val length = hypot(b.x - a.x, b.y - a.y)
                if (length <= 0) return
                Point2D((b.x - a.x) / length, (b.y - a.y) / length)
            }
            var curved = false
            for (i in tangents.indices) for (j in i + 1 until tangents.size) {
                budget.spend()
                if (dot(tangents[i], tangents[j]) <= tangentCosine) curved = true
            }
            if (!curved && mode == Mode.curvedOnly) return
            val originalArc = mutableListOf(0.0)
            original.zipWithNext().forEach { (a, b) -> originalArc += originalArc.last() + hypot(b.x - a.x, b.y - a.y) }
            result += Run(index, dark, original, originalArc, sampled.points, sampled.arc, tangents)
            if (result.size > maximumRuns) throw BarFitException("tooComplex")
        }
        for (i in 1 until poly.lastIndex) {
            val a = delta(poly[i], poly[i - 1]); val b = delta(poly[i + 1], poly[i])
            if (dot(a, b) / (hypot(a.x, a.y) * hypot(b.x, b.y)) < cornerCosine) {
                retain(i); start = i
            }
        }
        retain(poly.lastIndex)
    }

    private fun pair(p: Run, q: Run, minimum: Double, size: ImageSize, budget: Budget): Pairing? {
        var sign = 0; var start = 0
        val matches = mutableListOf<Intersection>()
        val widths = mutableListOf<Double>()
        var best: Pairing? = null
        fun finish(end: Int) {
            if (matches.size >= 2) {
                val arc = p.arc[end - 1] - p.arc[start]
                val narrow = widths.min(); val wide = widths.max()
                if (arc >= minimum && arc >= wide && narrow >= .4 * wide && (best == null || arc > best!!.arc)) {
                    val qStart = min(matches.first().sourceArc, matches.last().sourceArc)
                    val qEnd = max(matches.first().sourceArc, matches.last().sourceArc)
                    val first = clipped(p, p.arc[start], p.arc[end - 1])
                    val second = clipped(q, qStart, qEnd)
                    // Reciprocal support must describe this same observed
                    // subarc, not a different matching part of a folded path.
                    val reciprocal = reciprocal(q, qStart, qEnd, p, p.arc[start], p.arc[end - 1], minimum, budget)
                    if (reciprocal && ObservedCurveGeometry.isValid(first, size) && ObservedCurveGeometry.isValid(second, size))
                        best = Pairing(first, second, arc, narrow, wide)
                }
            }
            matches.clear(); widths.clear()
        }
        for (i in p.points.indices) {
            val intersection = intersect(p.points[i], p.tangents[i], q, 0.0, q.arc.last(), budget)
            val nextSign = if (intersection == null) 0 else if (intersection.width > 0) 1 else -1
            if (nextSign == 0 || nextSign != sign) { finish(i); start = i; sign = nextSign }
            if (intersection != null) {
                // Reversed source order is valid; changing direction or
                // stopping on the contour cannot connect two observed pieces.
                val previous = matches.lastOrNull()?.sourceArc
                val direction = if (matches.size >= 2) matches.last().sourceArc - matches[matches.lastIndex - 1].sourceArc else null
                val advance = previous?.let { intersection.sourceArc - it }
                if (advance != null && (abs(advance) <= 1e-8 || direction != null && advance * direction <= 0)) {
                    finish(i); start = i
                }
                matches += intersection; widths += abs(intersection.width)
            }
        }
        finish(p.points.size)
        return best
    }

    private fun intersect(point: Point2D, tangent: Point2D, target: Run,
                          low: Double, high: Double, budget: Budget): Intersection? {
        val normal = Point2D(-tangent.y, tangent.x)
        var nearest: Intersection? = null
        for (j in 0 until target.original.lastIndex) {
            budget.spend()
            val a = target.original[j]; val v = delta(target.original[j + 1], a)
            val denominator = cross(normal, v)
            if (abs(denominator) <= 1e-9) continue
            val offset = delta(a, point)
            val distance = cross(offset, v) / denominator
            val fraction = cross(offset, normal) / denominator
            val arc = target.originalArc[j] + fraction * (target.originalArc[j + 1] - target.originalArc[j])
            if (fraction !in 0.0..1.0 || arc !in low..high || abs(distance) !in 2.0..40.0 ||
                abs(dot(tangentAt(target, arc), tangent)) < tangentCosine ||
                nearest?.let { abs(distance) >= abs(it.width) } == true) continue
            nearest = Intersection(Point2D(a.x + fraction * v.x, a.y + fraction * v.y), arc, distance)
        }
        return nearest
    }

    private fun tangentAt(run: Run, arc: Double): Point2D {
        val index = floor(arc / ObservedCurveGeometry.sampleSpacingPixels).toInt().coerceIn(0, run.arc.lastIndex - 1)
        val fraction = ((arc - run.arc[index]) / (run.arc[index + 1] - run.arc[index])).coerceIn(0.0, 1.0)
        val a = run.tangents[index]; val b = run.tangents[index + 1]
        val x = a.x + fraction * (b.x - a.x); val y = a.y + fraction * (b.y - a.y)
        val length = hypot(x, y)
        return if (length > 0) Point2D(x / length, y / length) else Point2D(0.0, 0.0)
    }

    private fun pointAt(run: Run, arc: Double): Point2D {
        val index = run.originalArc.indexOfLast { it <= arc }.coerceAtMost(run.original.lastIndex - 1)
        val fraction = (arc - run.originalArc[index]) / (run.originalArc[index + 1] - run.originalArc[index])
        val a = run.original[index]; val b = run.original[index + 1]
        return Point2D(a.x + fraction * (b.x - a.x), a.y + fraction * (b.y - a.y))
    }

    private fun clipped(run: Run, low: Double, high: Double): ObservedCurve = ObservedCurve(
        listOf(pointAt(run, low)) + run.original.indices.filter { run.originalArc[it] > low && run.originalArc[it] < high }
            .map { run.original[it] } + pointAt(run, high))

    private fun reciprocal(source: Run, low: Double, high: Double, target: Run,
                           targetLow: Double, targetHigh: Double, minimum: Double, budget: Budget): Boolean {
        if (high - low < minimum) return false
        // The two endpoints already have forward normal witnesses. Check all
        // intervening source vertices and regular arc samples on the same arc.
        val positions = mutableListOf<Double>()
        for (arc in (source.originalArc + source.arc).filter { it > low && it < high }.sorted()) {
            // The same location can occur as both an original vertex and an
            // arc-grid sample with rounding error. Use the correspondence
            // precision here too, rather than treating it as stalled motion.
            if (positions.lastOrNull()?.let { arc - it <= 1e-8 } != true) positions += arc
        }
        var sign = 0; var direction = 0; var previous: Double? = null
        val widths = mutableListOf<Double>()
        for (arc in positions) {
            val match = intersect(pointAt(source, arc), tangentAt(source, arc), target, targetLow, targetHigh, budget) ?: return false
            val nextSign = if (match.width > 0) 1 else -1
            if (sign != 0 && nextSign != sign) return false
            sign = nextSign
            previous?.let {
                val advance = match.sourceArc - it
                val nextDirection = if (advance > 1e-8) 1 else if (advance < -1e-8) -1 else return false
                if (direction != 0 && direction != nextDirection) return false
                direction = nextDirection
            }
            previous = match.sourceArc; widths += abs(match.width)
        }
        return widths.isNotEmpty() && widths.min() >= .4 * widths.max()
    }
    private fun delta(a: Point2D, b: Point2D) = Point2D(a.x - b.x, a.y - b.y)
    private fun dot(a: Point2D, b: Point2D) = a.x * b.x + a.y * b.y
    private fun cross(a: Point2D, b: Point2D) = a.x * b.y - a.y * b.x
}

/** Stateless native transport of ordered contour observations; no acquisition. */
class ObservedRailFitterBridge {
    @Serializable private data class Input(val contours: List<RailContour>, val region: BarRegion,
        val imageSize: ImageSize, val grip: Point2D, val mode: ObservedRailFitter.Mode = ObservedRailFitter.Mode.curvedOnly,
        val contactRegion: ObservedRailFitter.ContactRegion = ObservedRailFitter.ContactRegion.belowGrip)
    @Throws(IllegalArgumentException::class)
    fun fitJson(input: String): String {
        val value = WorkoutRecordCodec.json.decodeFromString<Input>(input)
        return WorkoutRecordCodec.json.encodeToString(ObservedRailFitter.candidates(
            value.contours, value.region, value.imageSize, value.grip, value.mode, value.contactRegion))
    }
}
