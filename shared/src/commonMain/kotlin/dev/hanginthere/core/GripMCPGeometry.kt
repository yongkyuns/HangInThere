package dev.hanginthere.core

/** Same-frame image measurements only. A centre never becomes a body wrist. */
internal object GripMCPGeometry {
    data class Measurement(val centre: Point2D, val fullyScored: Boolean)
    private val required = setOf(GripHandJoint.indexMCP, GripHandJoint.middleMCP, GripHandJoint.pinkyMCP)

    fun measure(hand: GripHandObservation, size: ImageSize, requiresScores: Boolean = false): Measurement? {
        fun inside(p: Point2D) = p.isFinite && p.x in 0.0..size.width && p.y in 0.0..size.height
        if (!size.isValid || hand.points.size !in 3..6 || hand.points.map { it.joint }.distinct().size != hand.points.size ||
            hand.points.any { !inside(it.position) || it.score?.let { s -> !s.isFinite() || s !in 0.0..1.0 } == true }) return null
        val region = hand.sourceRegion
        if (region != null && !region.isValidFor(size)) return null
        val points = hand.points.filter { it.joint in required }
        if (points.size != 3 || points.any { point ->
                requiresScores && point.score == null || point.score?.let { it < ArmMeasurement.confidenceThreshold } == true ||
                    region?.let { point.position.x !in it.left.toDouble()..(it.left.toDouble() + it.width) ||
                        point.position.y !in it.top.toDouble()..(it.top.toDouble() + it.height) } == true
            }) return null
        val centre = Point2D(points.sumOf { it.position.x / 3 }, points.sumOf { it.position.y / 3 })
        return centre.takeIf { it.isFinite }?.let { Measurement(it, points.all { p -> p.score != null }) }
    }
}
