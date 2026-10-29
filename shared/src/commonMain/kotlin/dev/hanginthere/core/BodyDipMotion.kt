package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Rail-free bilateral dip movement. A serial owner supplies a single athlete's
 * upright pose and independent camera qualification for that source frame.
 * Neither hands, legs, inferred rails nor grip classification establish a top.
 * Image cycles do not verify physical support, anatomical depth, form or load.
 */
class BodyDipMotion {
    @Serializable enum class Phase { seekingTop, ready, outbound, returning }
    @Serializable enum class Camera { ready, unavailable, moved }
    @Serializable enum class Outcome { imageCycle, partial, interrupted }
    @Serializable data class Event(val outcome: Outcome, val seconds: Double, val reason: String,
        val departure: Double?, val bottom: Double?, val returned: Double?, val peak: Double?)
    @Serializable data class Snapshot(val seconds: Double?, val phase: Phase, val cycles: Int,
        val partialAttempts: Int, val interruptedAttempts: Int, val reason: String,
        val observationUsable: Boolean, val sourceAccepted: Boolean = true, val progress: Double = 0.0,
        val elbowDegrees: List<Double> = emptyList(), val travelArmFractions: List<Double> = emptyList(),
        val wristDriftArmFractions: List<Double> = emptyList(), val event: Event? = null,
        val policyVersion: Int = BodyDipMotion.policyVersion)
    internal data class Arm(val shoulder: Point2D, val wrist: Point2D, val degrees: Double, val length: Double) {
        val isTop get() = degrees >= 140 && wrist.y - shoulder.y >= .35 * length
    }
    internal data class Sample(val time: PresentationTime, val size: ImageSize, val backend: String,
        val revision: Int, val arms: List<Arm>)
    private var phase = Phase.seekingTop
    private var cycles = 0
    private var partials = 0
    private var interruptions = 0
    private var highWater: PresentationTime? = null
    private var candidate: Sample? = null
    private var top: Sample? = null
    internal val measuredTop: Sample? get() = top
    private var endpointSince: PresentationTime? = null
    private var endpoint: String? = null
    private var departure: Double? = null
    private var bottom: Double? = null
    private var firstReturn: Double? = null
    private var peak: Pair<Double, Double>? = null

    private fun clear() {
        phase = Phase.seekingTop; candidate = null; top = null
        departure = null; bottom = null; firstReturn = null; peak = null; breakDwell()
    }
    private fun active() = phase == Phase.outbound || phase == Phase.returning
    private fun close(reason: String, time: Double, outcome: Outcome = Outcome.interrupted): Event? {
        val event = if(active()) Event(outcome, time, reason, departure, bottom, firstReturn, peak?.second) else null
        if(event != null) when(outcome) {
            Outcome.imageCycle -> cycles++
            Outcome.partial -> partials++
            Outcome.interrupted -> interruptions++
        }
        clear(); return event
    }
    /** Owner interruptions preserve counts and the source high-water fence. */
    fun interrupt(reason: String): Event? = close(reason, highWater?.seconds ?: 0.0)
    /** Only a genuinely new source/set may forget its timestamp fence. */
    fun resetSource() { clear(); highWater = null; cycles = 0; partials = 0; interruptions = 0 }
    private fun breakDwell() { endpointSince = null; endpoint = null }
    private fun sustained(kind: String, time: PresentationTime, limit: SourceTimeLimit): Boolean {
        if(endpoint != kind) { endpointSince = time; endpoint = kind }
        return compare(time, endpointSince!!, limit)?.let { it >= 0 } == true
    }

    fun observe(pose: PoseResult, camera: Camera, allowDeparture: Boolean = true): Snapshot {
        val time = pose.timestamp
        val previous = highWater
        var event: Event? = null
        var sample: Sample? = null
        fun result(reason: String, travel: List<Double> = emptyList(), drift: List<Double> = emptyList(),
            usable: Boolean = sample != null && camera == Camera.ready, sourceAccepted: Boolean = true): Snapshot {
            val required = top?.arms?.map { max(.18, .02 * min(pose.imageSize.width, pose.imageSize.height) / it.length) }
            val progress = when {
                event?.outcome == Outcome.imageCycle -> 1.0
                travel.size != 2 || required == null -> 0.0
                phase == Phase.outbound -> .45 * travel.indices.minOf { (travel[it] / required[it]).coerceIn(0.0, 1.0) }
                phase == Phase.returning -> .45 + .5 * (1 - travel.indices.maxOf { (travel[it] / required[it]).coerceIn(0.0, 1.0) })
                else -> 0.0
            }
            return Snapshot(time.seconds.takeIf { it.isFinite() }, phase, cycles, partials, interruptions, reason,
                usable, sourceAccepted, progress, sample?.arms?.map { it.degrees } ?: emptyList(), travel, drift, event)
        }
        if(!time.seconds.isFinite() || time.seconds < 0 || previous?.let { compare(time, it, zero) != 1 } == true) {
            event = close("sourceRegression", previous?.seconds ?: 0.0)
            return result("sourceRegression", usable = false, sourceAccepted = false)
        }
        highWater = time
        if(previous?.let { compare(time, it, gap) == 1 } == true)
            event = close("sourceTimeGap", time.seconds)
        if(camera != Camera.ready) {
            event = close(if(camera == Camera.moved) "cameraMoved" else "cameraUnavailable", time.seconds) ?: event
            return result(if(camera == Camera.moved) "cameraMoved" else "cameraUnavailable", usable = false)
        }
        sample = sample(pose)
        if(sample == null) {
            event = close("bodyUnavailable", time.seconds) ?: event
            return result("bodyUnavailable", usable = false)
        }
        val current = sample!!
        val arms = current.arms
        if(phase == Phase.seekingTop) {
            if(!arms.all { it.isTop }) { candidate = null; breakDwell(); return result("holdExtendedTop") }
            val old = candidate
            val stable = old != null && sameContext(old, current) && arms.indices.all { i ->
                distance(arms[i].wrist, old.arms[i].wrist) <= .06 * old.arms[i].length &&
                    distance(arms[i].shoulder, old.arms[i].shoulder) <= .08 * old.arms[i].length
            }
            if(!stable) { candidate = current; breakDwell() }
            if(stable && sustained("top", time, topDwell)) { top = current; candidate = null; phase = Phase.ready; breakDwell() }
            return result(if(phase == Phase.ready) "ready" else "acquiringTop")
        }
        val baseline = top!!
        if(!sameContext(baseline, current)) {
            event = close("contextChanged", time.seconds) ?: event
            return result("contextChanged", usable = false)
        }
        val drift = arms.indices.map { distance(arms[it].wrist, baseline.arms[it].wrist) / baseline.arms[it].length }
        if(drift.any { it > .12 }) {
            event = close("wristsMoved", time.seconds) ?: event
            return result("wristsMoved", drift = drift, usable = false)
        }
        val travel = arms.indices.map { i ->
            val a = baseline.arms[i]; val span = distance(a.shoulder, a.wrist)
            -((arms[i].shoulder.x - a.shoulder.x) * (a.shoulder.x - a.wrist.x) +
                (arms[i].shoulder.y - a.shoulder.y) * (a.shoulder.y - a.wrist.y)) / (span * a.length)
        }
        val required = baseline.arms.map { max(.18, .02 * min(pose.imageSize.width, pose.imageSize.height) / it.length) }
        val bend = arms.indices.map { baseline.arms[it].degrees - arms[it].degrees }
        val returned = arms.indices.all { i -> travel[i] <= .08 && arms[i].degrees >= max(140.0, baseline.arms[i].degrees - 20) }
        if(phase == Phase.returning && firstReturn != null && !returned &&
            travel.indices.any { travel[it] >= required[it] * .5 }) {
            event = close("returnNotSustained", time.seconds) ?: event
            return result("returnNotSustained", travel, drift, usable = false)
        }
        if(phase == Phase.ready) {
            if(!allowDeparture) {
                if(!arms.all { it.isTop }) { clear(); return result("holdExtendedTop") }
                return result("ready", travel, drift)
            }
            if(arms.all { it.degrees >= 155 } && travel.all { it < -.02 }) {
                top = current; return result("higherTop", travel, drift)
            }
            if(!arms.indices.all { travel[it] >= required[it] * .5 && bend[it] >= 15 }) return result("ready", travel, drift)
            phase = Phase.outbound; departure = time.seconds; breakDwell()
        }
        if(active()) {
            val meanTravel = travel.average()
            if(peak == null || meanTravel > peak!!.first) peak = meanTravel to time.seconds
        }
        if(phase == Phase.outbound) {
            val lowered = arms.indices.all { i -> travel[i] >= required[i] && (bend[i] >= 30 || arms[i].degrees <= 100) }
            when {
                lowered -> if(sustained("bottom", time, endpointDwell)) {
                    bottom = endpointSince!!.seconds; phase = Phase.returning; breakDwell()
                }
                returned -> if(sustained("partialReturn", time, endpointDwell)) {
                    event = close("partial", time.seconds, Outcome.partial); return result("partial", travel, drift)
                }
                else -> breakDwell()
            }
        } else if(phase == Phase.returning) {
            if(returned) {
                if(firstReturn == null) firstReturn = time.seconds
                if(sustained("return", time, endpointDwell)) {
                    event = close("bodyDipImageCycleFormAndLoadUnverified", time.seconds, Outcome.imageCycle)
                    if(arms.all { it.isTop }) { top = current; phase = Phase.ready }
                }
            } else breakDwell()
        }
        return result(if(event == null) "tracking" else event!!.reason, travel, drift)
    }

    companion object {
        const val countMethod = "bilateralBodyDipMotion"
        const val policyVersion = 1
        private val zero = SourceTimeLimit(0, 1)
        private val gap = SourceTimeLimit(7, 20)
        private val topDwell = SourceTimeLimit(1, 4)
        private val endpointDwell = SourceTimeLimit(3, 25)
        private fun compare(end: PresentationTime, start: PresentationTime, limit: SourceTimeLimit) =
            SourceTimeComparison.compareElapsed(end, start, limit)
        private fun distance(a: Point2D, b: Point2D) = hypot(a.x - b.x, a.y - b.y)
        internal fun sameContext(a: Sample, b: Sample) = a.size == b.size && a.backend == b.backend && a.revision == b.revision
        internal fun sample(pose: PoseResult): Sample? {
            val person = pose.people.singleOrNull() ?: return null
            if(!pose.imageSize.isValid || pose.backend.isBlank() || person.landmarks.size > 64 ||
                person.landmarks.map { it.joint }.distinct().size != person.landmarks.size) return null
            val arms = ArmMeasurement.Side.entries.map { side ->
                val estimate = ArmMeasurement(pose, side).estimate ?: return null
                Arm(person.landmark(side.joints[0])!!.position, person.landmark(side.joints[2])!!.position,
                    estimate.elbowDegrees, estimate.upperArmPixels + estimate.forearmPixels)
            }
            return Sample(pose.timestamp, pose.imageSize, pose.backend, pose.requestRevision, arms)
        }
    }
}
