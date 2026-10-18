package dev.hanginthere.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Geometry of an already estimated image registration, independent of its SDK.
 * This does not establish that a transform fits the static background.
 */
object ImageRegistrationGeometry {
    /** Local area-equivalent scale at the image center, in input pixel coordinates.
     * The 3x3 homography is column-major. Differentiating its perspective divide
     * is essential: the upper-left 2x2 determinant is only sufficient for affine
     * transforms. A pole across the image, reflection or degenerate mapping is
     * unavailable evidence. No images or transform arrays are retained.
     */
    fun areaScaleAtImageCenter(columnMajor: DoubleArray, width: Double, height: Double): Double? {
        if (columnMajor.size != 9 || !ImageSize(width, height).isValid ||
            columnMajor.any { !it.isFinite() }) return null
        // Homographies are defined up to a nonzero scalar. Normalize before
        // arithmetic so an equivalent matrix cannot overflow its determinant.
        val magnitude = columnMajor.maxOf { abs(it) }
        if (magnitude == 0.0) return null
        val h = DoubleArray(9) { columnMajor[it] / magnitude }
        val x = width / 2
        val y = height / 2
        fun denominator(px: Double, py: Double) = h[2] * px + h[5] * py + h[8]
        val q = denominator(x, y)
        if (!q.isFinite() || q == 0.0) return null
        // The denominator is linear, so the four corners bound its sign over
        // the full image. Never treat a horizon crossing as a usable scale.
        val corners = doubleArrayOf(h[8], denominator(width, 0.0),
            denominator(0.0, height), denominator(width, height))
        if (corners.any { !it.isFinite() || it == 0.0 || (it > 0) != (q > 0) }) return null
        val u = (h[0] * x + h[3] * y + h[6]) / q
        val v = (h[1] * x + h[4] * y + h[7]) / q
        val a = (h[0] - u * h[2]) / q
        val b = (h[1] - v * h[2]) / q
        val c = (h[3] - u * h[5]) / q
        val d = (h[4] - v * h[5]) / q
        val determinant = a * d - b * c
        return sqrt(determinant).takeIf { it.isFinite() && it > 0 }
    }
}
