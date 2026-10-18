package dev.hanginthere.core

import kotlin.math.*

/** One freshly observed tube supports both hands; parallel-rail identity stays separate. */
class AutomaticDipBar {
    private var stability = AutomaticReferenceStability()
    var state = AutomaticPullUpBar.State.lookingForGrip; private set
    val referenceStabilityDiagnostic get() = stability.diagnostic.takeIf {
        state==AutomaticPullUpBar.State.holding || state==AutomaticPullUpBar.State.locked
    }
    fun reset() { stability = AutomaticReferenceStability(); state = AutomaticPullUpBar.State.lookingForGrip }

    fun observe(pose: PoseResult, side: ArmMeasurement.Side, candidates: List<BarLineCandidate>): ConfirmedBar? {
        if (state == AutomaticPullUpBar.State.locked) return null
        val grip = AutomaticDipRails.apparatusGrip(pose) ?: run { reset(); return null }
        val region = region(grip, pose.imageSize) ?: run { reset(); return null }
        val edge = select(candidates, grip, pose.imageSize, region) ?: run {
            reset(); state = if (candidates.isEmpty()) AutomaticPullUpBar.State.findingEdge else AutomaticPullUpBar.State.ambiguous
            return null
        }
        state = AutomaticPullUpBar.State.holding
        if (!stability.observe(listOf(edge), grip.hands, pose)) return null
        // The role identifies the tracked hand; both hands witness this shared bar.
        val bar = ConfirmedBar(if (side == ArmMeasurement.Side.left) ConfirmedBar.Role.leftDipRail else ConfirmedBar.Role.rightDipRail,
            ConfirmedBar.Method.automaticContours, edge, null, pose.imageSize, pose.timestamp)
        if (!bar.isValid) { reset(); return null }
        state = AutomaticPullUpBar.State.locked
        return bar
    }

    companion object {
        fun region(grip: AutomaticDipRails.Grip, size: ImageSize): BarRegion? {
            if (!size.isValid || grip.hands.size != 2 || grip.hands.any { !it.isFinite }) return null
            val short = min(size.width, size.height)
            val region = BarRegion(max(0.0, grip.hands.minOf { it.x } - short * .16),
                max(0.0, grip.hands.minOf { it.y } - short * .08),
                min(size.width, grip.hands.maxOf { it.x } + short * .16),
                min(size.height, grip.hands.maxOf { it.y } + short * .16))
            return region.takeIf { it.isValid(size) }
        }

        private fun select(candidates: List<BarLineCandidate>, grip: AutomaticDipRails.Grip,
                           size: ImageSize, region: BarRegion): BarSegment? {
            val short = min(size.width, size.height)
            val span = hypot(grip.hands[0].x - grip.hands[1].x, grip.hands[0].y - grip.hands[1].y)
            val crop = BarRegion(floor(region.minX) - 1, floor(region.minY) - 1, ceil(region.maxX) + 1, ceil(region.maxY) + 1)
            val bounds = BarRegion(0.0, 0.0, size.width, size.height)
            val edges = candidates.map { it.edge.ordered }.filter {
                it.isValid && crop.contains(it.a) && crop.contains(it.b) && bounds.contains(it.a) && bounds.contains(it.b)
            }
            data class Proposal(val edge: BarSegment, val distance: Double, val score: Double)
            val proposals = mutableListOf<Proposal>()
            for (i in edges.indices) for (j in i + 1 until edges.size) {
                // Grasp occlusion can shorten one silhouette. Keep at least
                // half the grip span as genuinely paired observed support.
                val pair = BarFitter.pair(edges[i], edges[j], max(24.0, span * .5)) ?: continue
                val center = pair.centerline.ordered
                val ux = (center.b.x-center.a.x)/center.length; val uy = (center.b.y-center.a.y)/center.length
                // Wrists in top support lie on/above the tube center in upright
                // pixels. A torso stripe above either grip is not support.
                // Finite upper-edge proximity is still required for both hands.
                if (grip.hands.any { hand ->
                    val signed = ux*(hand.y-center.a.y)-uy*(hand.x-center.a.x)
                    !signed.isFinite() || signed > 0
                }) continue
                val upper = if (edges[i].midpoint.y <= edges[j].midpoint.y) edges[i] else edges[j]
                val distances = grip.hands.map { upper.distance(it) }
                if (upper.length < span * .65 || abs(upper.b.y - upper.a.y) > (upper.b.x - upper.a.x) * tan(15 * PI / 180) ||
                    distances.any { !it.isFinite() || it > short * .12 }) continue
                proposals += Proposal(upper, distances.max(), pair.geometryScore)
            }
            val first = proposals.sortedWith(compareBy<Proposal> { it.distance }.thenByDescending { it.score }).firstOrNull() ?: return null
            // Several eligible but distinct bars leave the actual grip ambiguous.
            return first.edge.takeIf { proposals.all { AutomaticReferenceStability.sameLine(first.edge,it.edge,short * .01) } }
        }
    }
}
