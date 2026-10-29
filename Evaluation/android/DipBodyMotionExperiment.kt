package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.Serializable
import kotlin.math.*

/** Development-only body-pose dip experiment. No rail, hand-model or leg gate.
 * A counted image cycle does not verify contact, load, depth or strict form.
 * This class is in the evaluation test source set, not either shipping app.
 * One source and one serial owner per instance; timestamps are never resampled.
 */
class DipBodyMotionExperiment {
    @Serializable enum class Phase { seekingTop, ready, outbound, returning }
    @Serializable data class Event(val departure: Double, val bottom: Double, val returned: Double, val counted: Double)
    @Serializable data class Snapshot(val seconds: Double, val phase: Phase, val cycles: Int, val reason: String,
        val elbowDegrees: List<Double> = emptyList(), val travelArmFractions: List<Double> = emptyList(),
        val wristDriftArmFractions: List<Double> = emptyList(), val crossedLegCue: Boolean? = null,
        val event: Event? = null)
    private data class Arm(val shoulder: Point2D, val wrist: Point2D, val degrees: Double, val length: Double) {
        val top get() = degrees >= 140 && wrist.y - shoulder.y >= .35 * length
    }
    private data class Sample(val pose: PoseResult, val arms: List<Arm>)
    private var phase = Phase.seekingTop
    private var cycles = 0
    private var lastTime: PresentationTime? = null
    private var candidate: Sample? = null
    private var baseline: Sample? = null
    private var dwellStart: PresentationTime? = null
    private var dwellKind: String? = null
    private var departure: Double? = null
    private var bottom: Double? = null
    private var firstReturn: Double? = null

    private fun reset() {
        phase = Phase.seekingTop; candidate = null; baseline = null
        departure = null; bottom = null; firstReturn = null; breakDwell()
    }
    private fun breakDwell() { dwellStart = null; dwellKind = null }
    private fun sustained(kind: String, time: PresentationTime, limit: SourceTimeLimit): Boolean {
        if (dwellKind != kind) { dwellStart = time; dwellKind = kind }
        return SourceTimeComparison.compareElapsed(time, dwellStart!!, limit)?.let { it >= 0 } == true
    }
    fun observe(pose: PoseResult): Snapshot {
        val time = pose.timestamp
        val previous = lastTime
        val ordering = previous?.let { SourceTimeComparison.compareElapsed(time, it, SourceTimeLimit(0, 1)) }
        if (!time.seconds.isFinite() || previous != null && ordering != 1) {
            reset()
            // Keep the high-water timestamp: regression cannot restart an old cycle.
            return Snapshot(time.seconds, phase, cycles, "sourceRegression")
        }
        val gap = previous != null && SourceTimeComparison.compareElapsed(time, previous, SourceTimeLimit(35, 100)) == 1
        lastTime = time
        if (gap) reset()
        val sample = sample(pose)
        fun result(reason: String, travel: List<Double> = emptyList(), drift: List<Double> = emptyList(), event: Event? = null) =
            Snapshot(time.seconds, phase, cycles, reason, sample?.arms?.map { it.degrees } ?: emptyList(),
                travel, drift, crossedLegCue(pose), event)
        if (sample == null) { reset(); return result("bodyUnavailable") }
        val arms = sample.arms
        if (phase == Phase.seekingTop) {
            if (!arms.all { it.top }) { candidate = null; breakDwell(); return result("holdExtendedTop") }
            val old = candidate
            val stable = old != null && sameContext(old, sample) && arms.indices.all { i ->
                distance(arms[i].wrist, old.arms[i].wrist) <= .06 * old.arms[i].length &&
                    distance(arms[i].shoulder, old.arms[i].shoulder) <= .08 * old.arms[i].length
            }
            if (!stable) { candidate = sample; breakDwell() }
            if (stable && sustained("top", time, SourceTimeLimit(1, 4))) {
                baseline = sample; candidate = null; phase = Phase.ready; breakDwell()
            }
            return result(if (phase == Phase.ready) "ready" else "acquiringTop")
        }
        val top = baseline!!
        if (!sameContext(top, sample)) { reset(); return result("contextChanged") }
        val drift = arms.indices.map { distance(arms[it].wrist, top.arms[it].wrist) / top.arms[it].length }
        if (drift.any { it > .12 }) { reset(); return result("wristsMoved", drift = drift) }
        // Freeze the actually observed top's wrist/shoulder axis and arm scale.
        val travel = arms.indices.map { i ->
            val a = top.arms[i]; val span = distance(a.shoulder, a.wrist)
            -((arms[i].shoulder.x - a.shoulder.x) * (a.shoulder.x - a.wrist.x) +
                (arms[i].shoulder.y - a.shoulder.y) * (a.shoulder.y - a.wrist.y)) / (span * a.length)
        }
        val required = top.arms.map { max(.18, .02 * min(pose.imageSize.width, pose.imageSize.height) / it.length) }
        val bend = arms.indices.map { top.arms[it].degrees - arms[it].degrees }
        val returned = arms.indices.all { i -> travel[i] <= .08 && arms[i].degrees >= max(140.0, top.arms[i].degrees - 20) }
        // A witnessed but unheld return cannot be merged into a later attempt.
        if (phase == Phase.returning && firstReturn != null && !returned &&
            travel.indices.any { travel[it] >= required[it] * .5 }) {
            reset(); return result("returnNotSustained", travel, drift)
        }
        if (phase == Phase.ready) {
            // Follow genuinely higher near-extension tops before a departure;
            // never adapt the anchor downward to absorb a descent.
            if (arms.all { it.degrees >= 155 } && travel.all { it < -.02 }) {
                baseline = sample; return result("higherTop", travel, drift)
            }
            if (!arms.indices.all { travel[it] >= required[it] * .5 && bend[it] >= 15 })
                return result("ready", travel, drift)
            phase = Phase.outbound; departure = time.seconds; breakDwell()
        }
        var event: Event? = null
        if (phase == Phase.outbound) {
            val lowered = arms.indices.all { i -> travel[i] >= required[i] && (bend[i] >= 30 || arms[i].degrees <= 100) }
            when {
                lowered -> if (sustained("bottom", time, SourceTimeLimit(3, 25))) {
                    bottom = dwellStart!!.seconds; phase = Phase.returning; breakDwell()
                }
                returned -> if (sustained("partialReturn", time, SourceTimeLimit(3, 25))) {
                    reset(); return result("partial", travel, drift)
                }
                else -> breakDwell()
            }
        } else if (phase == Phase.returning) {
            if (returned) {
                if (firstReturn == null) firstReturn = time.seconds
                if (sustained("return", time, SourceTimeLimit(3, 25))) {
                    event = Event(departure!!, bottom!!, dwellStart!!.seconds, time.seconds)
                    cycles++; reset()
                    // A completed return supplies a new measured top, only if
                    // the current arms independently meet starting geometry.
                    if (arms.all { it.top }) { baseline = sample; phase = Phase.ready }
                }
            } else breakDwell()
        }
        return result(if (event == null) "tracking" else "bodyImageCycleFormAndLoadUnverified", travel, drift, event)
    }
    private fun sameContext(a: Sample, b: Sample) = a.pose.imageSize == b.pose.imageSize &&
        a.pose.backend == b.pose.backend && a.pose.requestRevision == b.pose.requestRevision
    private fun sample(pose: PoseResult): Sample? {
        val person = pose.people.singleOrNull() ?: return null
        if (!pose.imageSize.isValid || pose.backend.isBlank() || person.landmarks.size > 64 ||
            person.landmarks.map { it.joint }.distinct().size != person.landmarks.size) return null
        val arms = ArmMeasurement.Side.entries.map { side ->
            val estimate = ArmMeasurement(pose, side).estimate ?: return null
            Arm(person.landmark(side.joints[0])!!.position, person.landmark(side.joints[2])!!.position,
                estimate.elbowDegrees, estimate.upperArmPixels + estimate.forearmPixels)
        }
        return Sample(pose, arms)
    }
    companion object {
        const val method = "experimentalBilateralBodyDipMotion"
        const val version = 1
        private fun distance(a: Point2D, b: Point2D) = hypot(a.x - b.x, a.y - b.y)
        /** Optional image cue only. Opposite hip/ankle ordering suggests crossing;
         * unknown or uncrossed legs never block readiness or add a repetition. */
        fun crossedLegCue(pose: PoseResult): Boolean? {
            val person = pose.people.singleOrNull() ?: return null
            fun point(joint: PoseJoint) = person.landmark(joint)?.position?.takeIf {
                it.x in 0.0..pose.imageSize.width && it.y in 0.0..pose.imageSize.height
            }
            val lh = point(PoseJoint.leftHip) ?: return null; val rh = point(PoseJoint.rightHip) ?: return null
            val la = point(PoseJoint.leftAnkle) ?: return null; val ra = point(PoseJoint.rightAnkle) ?: return null
            val tolerance = .02 * min(pose.imageSize.width, pose.imageSize.height)
            if (abs(lh.x - rh.x) <= tolerance || abs(la.x - ra.x) <= tolerance) return null
            return (lh.x - rh.x) * (la.x - ra.x) < 0
        }
    }
}
