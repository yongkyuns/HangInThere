package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** A finite, ordered image observation in upright pixels. No endpoints are
 * extended. This is geometry, not evidence of an apparatus, grip or body load. */
@Serializable
data class ObservedCurve(val points: List<Point2D>)

/** Bounded correspondence for partially occluded curved image features.
 * Native adapters supply observations; this shared code owns all comparison
 * math. This measures geometric agreement, not unique physical identity: nearby
 * branches or distinct objects can agree within tolerance. A match does not
 * qualify a bar or replace a ConfirmedBar reference. */
object ObservedCurveGeometry {
    const val maximumPoints = 256
    const val sampleSpacingPixels = 4.0
    private const val epsilon = 1e-8
    private data class Path(val points: List<Point2D>, val arc: List<Double>) {
        val length get() = arc.last()
    }
    private data class Projection(val point: Point2D, val distance: Double, val interior: Boolean)
    internal data class Sampling(val points: List<Point2D>, val arc: List<Double>)

    /** Shared bounded arc sampling for observed-rail fitting and correspondence. */
    internal fun sampling(curve: ObservedCurve, size: ImageSize): Sampling? {
        val path = path(curve, size) ?: return null
        val points = sample(path)
        return Sampling(points, points.indices.map {
            if (it == points.lastIndex) path.length else it * sampleSpacingPixels
        })
    }

    fun isValid(curve: ObservedCurve, size: ImageSize): Boolean = path(curve, size) != null

    /** Distance to actually observed finite support, never an infinite tangent. */
    fun distance(curve: ObservedCurve, point: Point2D, size: ImageSize): Double? {
        if (!point.isFinite || point.x !in 0.0..size.width || point.y !in 0.0..size.height) return null
        val p = path(curve, size) ?: return null
        return project(p.points, point)?.distance
    }

    /** Both curves need 65% of their shorter observed arc within .008 of the
     * image short side, with local directions within 3 degrees. Directions
     * are compared over the SAME common observed subarc: independently clipped
     * endpoint windows otherwise reject identical curved features.
     * Invalid, self-intersecting, exactly backtracking, closed or over-budget
     * observations fail closed. A simple near-hairpin still needs an independent
     * uniqueness check at the feature-selection boundary. */
    fun matches(first: ObservedCurve, second: ObservedCurve, size: ImageSize): Boolean {
        val p = path(first, size) ?: return false
        val q = path(second, size) ?: return false
        val a = sample(p); val b = sample(q)
        val tolerance = min(size.width, size.height) * .008
        val window = max(1, ceil(max(24.0, min(size.width, size.height) * .04) /
            (2 * sampleSpacingPixels)).toInt()).coerceAtMost(maximumPoints)
        val required = .65 * min(p.length, q.length)
        return covered(a, b, window, tolerance) >= required &&
            covered(b, a, window, tolerance) >= required
    }

    private fun path(curve: ObservedCurve, size: ImageSize): Path? {
        val points = curve.points
        if (!size.isValid || points.size !in 2..maximumPoints || points.any {
                !it.isFinite || it.x !in 0.0..size.width || it.y !in 0.0..size.height
            }) return null
        val arc = mutableListOf(0.0)
        for (i in 1 until points.size) {
            val length = separation(points[i - 1], points[i])
            if (!length.isFinite() || length <= epsilon) return null
            arc += arc.last() + length
        }
        if (!arc.last().isFinite() || arc.last() < 2 ||
            ceil(arc.last() / sampleSpacingPixels) + 1 > maximumPoints) return null
        // Reject backtracking adjacent segments and nonadjacent intersections.
        // A folded projection cannot establish an unambiguous feature path.
        for (i in 1 until points.lastIndex) {
            val a = points[i - 1]; val b = points[i]; val c = points[i + 1]
            if (abs(signedDistance(a, b, c)) <= epsilon &&
                (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y) < 0) return null
        }
        for (i in 0 until points.lastIndex) for (j in i + 2 until points.lastIndex) {
            if (intersects(points[i], points[i + 1], points[j], points[j + 1])) return null
        }
        return Path(points, arc)
    }

    private fun separation(a: Point2D, b: Point2D) = hypot(b.x - a.x, b.y - a.y)
    private fun signedDistance(a: Point2D, b: Point2D, p: Point2D): Double {
        val length = separation(a, b)
        return (b.x - a.x) / length * (p.y - a.y) - (b.y - a.y) / length * (p.x - a.x)
    }
    private fun intersects(a: Point2D, b: Point2D, c: Point2D, d: Point2D): Boolean {
        if (max(a.x, b.x) < min(c.x, d.x) - epsilon ||
            max(c.x, d.x) < min(a.x, b.x) - epsilon ||
            max(a.y, b.y) < min(c.y, d.y) - epsilon ||
            max(c.y, d.y) < min(a.y, b.y) - epsilon) return false
        fun straddles(x: Double, y: Double) = (x <= epsilon && y >= -epsilon) ||
            (y <= epsilon && x >= -epsilon)
        return straddles(signedDistance(a, b, c), signedDistance(a, b, d)) &&
            straddles(signedDistance(c, d, a), signedDistance(c, d, b))
    }

    private fun sample(path: Path): List<Point2D> {
        val result = mutableListOf<Point2D>(); var segment = 0; var t = 0.0
        while (t < path.length) {
            while (segment < path.points.size - 2 && path.arc[segment + 1] < t) segment++
            val a = path.points[segment]; val b = path.points[segment + 1]
            val ratio = (t - path.arc[segment]) / (path.arc[segment + 1] - path.arc[segment])
            result += Point2D(a.x + ratio * (b.x - a.x), a.y + ratio * (b.y - a.y))
            t += sampleSpacingPixels
        }
        result += path.points.last()
        return result
    }

    private fun project(path: List<Point2D>, point: Point2D): Projection? {
        var best: Projection? = null
        for (i in 0 until path.lastIndex) {
            val a = path[i]; val b = path[i + 1]; val length = separation(a, b)
            val ux = (b.x - a.x) / length; val uy = (b.y - a.y) / length
            val raw = (point.x - a.x) * ux + (point.y - a.y) * uy
            val offset = raw.coerceIn(0.0, length)
            val p = Point2D(a.x + offset * ux, a.y + offset * uy)
            val distance = separation(p, point)
            if (distance.isFinite() && (best == null || distance < best.distance)) {
                val interior = !(i == 0 && raw < -1e-10 * length ||
                    i == path.lastIndex - 1 && raw > (1 + 1e-10) * length)
                best = Projection(p, distance, interior)
            }
        }
        return best
    }

    private fun covered(a: List<Point2D>, b: List<Point2D>, window: Int, tolerance: Double): Double {
        val valid = a.indices.map { i ->
            val center = project(b, a[i]) ?: return@map false
            if (!center.interior || center.distance > tolerance) return@map false
            val q0 = project(b, a[max(0, i - window)]) ?: return@map false
            val q1 = project(b, a[min(a.lastIndex, i + window)]) ?: return@map false
            val p0 = project(a, q0.point) ?: return@map false
            val p1 = project(a, q1.point) ?: return@map false
            val u = separation(p0.point, p1.point); val v = separation(q0.point, q1.point)
            if (u < 2 || v < 2 || max(p0.distance, p1.distance) > tolerance) return@map false
            val dot = (p1.point.x - p0.point.x) / u * ((q1.point.x - q0.point.x) / v) +
                (p1.point.y - p0.point.y) / u * ((q1.point.y - q0.point.y) / v)
            abs(dot) >= cos(3.0 * PI / 180)
        }
        return (0 until a.lastIndex).sumOf { if (valid[it] && valid[it + 1]) separation(a[it], a[it + 1]) else 0.0 }
    }
}
