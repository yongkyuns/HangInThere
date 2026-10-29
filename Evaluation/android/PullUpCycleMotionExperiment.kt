package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.Serializable
import kotlin.math.*

/** Development-only causal cycle experiment. The actual app's source-bound
 * automatic set and independent camera verdict are inputs, never inferred from
 * labels. Current wrist-relative shoulder height avoids interpreting falling
 * hands and shoulders together as a completed return. This is not a form,
 * lockout, load or physical-contact classifier and is not shipped in either app.
 */
class PullUpCycleMotionExperiment {
    @Serializable enum class Phase { idle, ready, outbound, returning }
    @Serializable data class Event(val outcome: String, val reason: String, val seconds: Double,
        val setKey: String?, val departure: Double?, val peak: Double?, val lowerTurn: Double?)
    @Serializable data class Snapshot(val seconds: Double?, val phase: Phase, val cycles: Int,
        val reason: String, val travel: List<Double> = emptyList(), val drift: List<Double> = emptyList(),
        val degrees: List<Double> = emptyList(), val event: Event? = null)
    private data class Arm(val shoulder: Point2D, val wrist: Point2D, val degrees: Double, val length: Double) {
        val height get() = shoulder.y - wrist.y
    }
    private data class Sample(val time: PresentationTime, val size: ImageSize, val backend: String,
        val revision: Int, val arms: List<Arm>)
    private var phase = Phase.idle
    private var cycles = 0
    private var highWater: PresentationTime? = null
    private var lastUsable: PresentationTime? = null
    private var setKey: String? = null
    private var support: Sample? = null
    private var lower: Sample? = null
    private var peak: Sample? = null
    private var returnLow: Sample? = null
    private var departure: Double? = null
    private var turnSince: PresentationTime? = null
    private var lastMeasured: Sample? = null

    private fun clear() {
        phase = Phase.idle; support = null; lower = null; peak = null; returnLow = null
        departure = null; turnSince = null; lastMeasured = null; lastUsable = null
    }
    private fun active() = phase == Phase.outbound || phase == Phase.returning
    private fun cancel(reason: String, time: Double): Event? {
        val event = if (active()) Event("interrupted", reason, time, setKey, departure, peak?.time?.seconds, null) else null
        clear(); return event
    }
    private fun arm(current: Sample) {
        phase = Phase.ready; lower = current; peak = null; returnLow = null
        departure = null; turnSince = null; lastMeasured = current
    }

    fun observe(pose: PoseResult, activeSetKey: String?, cameraReady: Boolean, reference: BarSegment?): Snapshot {
        val time = pose.timestamp
        val previous = highWater
        var event: Event? = null
        var sample: Sample? = null
        fun result(reason: String, travel: List<Double> = emptyList(), drift: List<Double> = emptyList()) =
            Snapshot(time.seconds.takeIf { it.isFinite() }, phase, cycles, reason, travel, drift,
                sample?.arms?.map { it.degrees } ?: emptyList(), event)
        if (!time.seconds.isFinite() || time.seconds < 0 ||
            previous?.let { compare(time, it, zero) != 1 } == true) {
            event = cancel("sourceRegression", previous?.seconds ?: 0.0)
            return result("sourceRegression")
        }
        highWater = time
        if (previous?.let { compare(time, it, gap) == 1 } == true) event = cancel("sourceTimeGap", time.seconds)
        if (setKey != activeSetKey) {
            event = cancel("setChanged", time.seconds) ?: event
            setKey = activeSetKey
        }
        if (activeSetKey == null || !cameraReady || reference?.isValid != true ||
            listOf(reference.a, reference.b).any { it.x !in 0.0..pose.imageSize.width || it.y !in 0.0..pose.imageSize.height }) {
            val reason = if (activeSetKey == null) "outsideAutomaticSet" else if (!cameraReady) "cameraUnavailable" else "supportUnavailable"
            event = cancel(reason, time.seconds) ?: event
            return result(reason)
        }
        sample = sample(pose)
        if (sample == null) {
            // A collapsed SDK chain is not a measured movement or release.
            // Keep only the private attempt across a short outage; neither
            // turnaround nor endpoint dwell can bridge an unavailable frame.
            turnSince = null; lastMeasured = null
            if (lastUsable?.let { compare(time, it, gap) == 1 } == true)
                event = cancel("bodyObservationOutage", time.seconds) ?: event
            return result("bodyUnavailable")
        }
        val current = sample!!
        val oldSupport = support
        if (oldSupport != null && (oldSupport.size != current.size || oldSupport.backend != current.backend || oldSupport.revision != current.revision)) {
            event = cancel("contextChanged", time.seconds) ?: event
            // A context change cannot supply a new lower endpoint on that frame.
            return result("contextChanged")
        }
        if (lastUsable?.let { compare(time, it, gap) == 1 } == true)
            event = cancel("bodyObservationOutage", time.seconds) ?: event
        lastUsable = time
        val prior = lastMeasured
        lastMeasured = current
        if (phase == Phase.idle) {
            // The actual owner has already qualified the start. Reacquisition
            // still requires two current overhead arms, not arms-down release.
            if (!current.arms.all { it.height >= .35 * it.length }) return result("waitingForOverheadLower")
            support = current; arm(current)
            return result("ready")
        }
        val fixed = support!!
        val base = lower!!
        val lengths = fixed.arms.map { it.length }
        val drift = current.arms.indices.map { i -> distance(current.arms[i].wrist, fixed.arms[i].wrist) / lengths[i] }
        // SDK wrist jitter or a whole-arm side swap is not release. Reuse
        // production's positive bilateral departure from both the held hands
        // and actual finite observed bar; no image-contact classifier is added.
        if (MotionFeatureExtractor.extract(pose, reference, true, fixed.arms.map { it.wrist }).released) {
            event = cancel("released", time.seconds) ?: event
            return result("released", drift = drift)
        }
        if (current.arms.any { it.height <= 0 }) {
            event = cancel("overheadPostureLost", time.seconds) ?: event
            return result("overheadPostureLost", drift = drift)
        }
        val required = lengths.map { max(.18 * it, .02 * min(current.size.width, current.size.height)) }
        val travel = current.arms.indices.map { i -> (base.arms[i].height - current.arms[i].height) / lengths[i] }
        val bend = current.arms.indices.map { i -> base.arms[i].degrees - current.arms[i].degrees }
        fun raised(a: Sample) = a.arms.indices.all { i ->
            base.arms[i].height - a.arms[i].height >= required[i] &&
                base.arms[i].shoulder.y - a.arms[i].shoulder.y >= required[i] &&
                base.arms[i].degrees - a.arms[i].degrees >= 15
        }
        fun nearLower(a: Sample) = a.arms.indices.all { i ->
            a.arms[i].height >= base.arms[i].height - .08 * lengths[i] &&
                a.arms[i].shoulder.y >= base.arms[i].shoulder.y - .08 * lengths[i] &&
                a.arms[i].degrees >= base.arms[i].degrees - 20
        }
        if (phase == Phase.ready) {
            // A genuinely lower preparatory hang can update the lower endpoint;
            // upward motion can never move the baseline toward the peak.
            if (current.arms.indices.all { i -> current.arms[i].height > base.arms[i].height && current.arms[i].degrees >= base.arms[i].degrees }) {
                lower = current
                return result("lowerPreparation", travel, drift)
            }
            if (!current.arms.indices.all { i -> travel[i] * lengths[i] >= required[i] * .5 &&
                    base.arms[i].shoulder.y - current.arms[i].shoulder.y >= required[i] * .5 && bend[i] >= 15 })
                return result("ready", travel, drift)
            phase = Phase.outbound; departure = time.seconds; peak = null
        }
        if (phase == Phase.outbound) {
            if (prior != null && raised(current) && raised(prior)) {
                // Each extreme has adjacent observed corroboration. Choose an
                // actual, less-extreme sample, never synthetic/averaged joints.
                val candidate = listOf(prior, current).maxBy { it.arms.sumOf { arm -> arm.height } }
                if (peak == null || candidate.arms.sumOf { it.height } < peak!!.arms.sumOf { it.height }) peak = candidate
            }
            val top = peak
            val reverses = top != null && current.arms.indices.all { i ->
                current.arms[i].height - top.arms[i].height >= .04 * lengths[i] &&
                    current.arms[i].shoulder.y - top.arms[i].shoulder.y >= .04 * lengths[i]
            }
            if (reverses) {
                if (turnSince == null) turnSince = time
                if (compare(time, turnSince!!, dwell)?.let { it >= 0 } == true) {
                    phase = Phase.returning; returnLow = null; turnSince = null
                }
            } else turnSince = null
            if (peak == null && prior != null && nearLower(current) && nearLower(prior) &&
                time.seconds - departure!! >= dwell.seconds) {
                event = Event("partial", "insufficientObservedTravel", time.seconds, setKey, departure, null, null)
                arm(current)
                return result("insufficientObservedTravel", travel, drift)
            }
        }
        if (phase == Phase.returning) {
            val top = peak!!
            fun lowered(a: Sample) = a.arms.indices.all { i ->
                a.arms[i].height - top.arms[i].height >= required[i] &&
                    a.arms[i].shoulder.y - top.arms[i].shoulder.y >= required[i] &&
                    a.arms[i].degrees - top.arms[i].degrees >= 15
            }
            if (prior != null && lowered(current) && lowered(prior)) {
                val candidate = listOf(prior, current).minBy { it.arms.sumOf { arm -> arm.height } }
                if (returnLow == null || candidate.arms.sumOf { it.height } > returnLow!!.arms.sumOf { it.height }) returnLow = candidate
            }
            val bottom = returnLow
            val lowerTurn = bottom != null && prior != null && listOf(prior, current).all { a ->
                a.arms.indices.all { i -> bottom.arms[i].height - a.arms[i].height >= .04 * lengths[i] &&
                    bottom.arms[i].shoulder.y - a.arms[i].shoulder.y >= .04 * lengths[i]
                }
            }
            // Lowering has already been confirmed on fresh source time. Arrival
            // needs two observed samples at the prior lower region, not a pause.
            val arrived = bottom != null && prior != null && nearLower(current) && nearLower(prior)
            if (lowerTurn || arrived) {
                cycles++
                event = Event("imageCycle", if (lowerTurn) "observedLowerTurn" else "measuredLowerReturn", time.seconds,
                    setKey, departure, top.time.seconds, bottom!!.time.seconds)
                arm(if (lowerTurn) bottom else current)
                return result(event!!.reason, travel, drift)
            }
        }
        return result("tracking", travel, drift)
    }

    companion object {
        const val method = "experimentalBilateralPullUpRelativeMotion"
        const val version = 3
        private val zero = SourceTimeLimit(0, 1)
        private val gap = SourceTimeLimit(7, 20)
        private val dwell = SourceTimeLimit(3, 25)
        private fun compare(end: PresentationTime, start: PresentationTime, limit: SourceTimeLimit) = SourceTimeComparison.compareElapsed(end, start, limit)
        private fun distance(a: Point2D, b: Point2D) = hypot(a.x - b.x, a.y - b.y)
        private fun sample(pose: PoseResult): Sample? {
            val person = pose.people.singleOrNull() ?: return null
            if (!pose.imageSize.isValid || pose.backend.isBlank() || person.landmarks.size > 64 ||
                person.landmarks.map { it.joint }.distinct().size != person.landmarks.size) return null
            val arms = ArmMeasurement.Side.entries.map { side ->
                val estimate = ArmMeasurement(pose, side).estimate ?: return null
                Arm(person.landmark(side.joints[0])!!.position, person.landmark(side.joints[2])!!.position,
                    estimate.elbowDegrees, estimate.upperArmPixels + estimate.forearmPixels)
            }.sortedBy { it.wrist.x }
            // For the declared front/rear, uncrossed-arm view, image-side
            // slots are stable when Vision swaps anatomical side labels.
            // Never repair a collapsed/mixed chain with invented joints.
            val floor = ArmMeasurement.minimumSegmentFraction * min(pose.imageSize.width, pose.imageSize.height)
            if (arms[1].wrist.x - arms[0].wrist.x < 2 * floor ||
                arms[1].shoulder.x - arms[0].shoulder.x < floor ||
                distance(person.landmark(PoseJoint.leftElbow)!!.position, person.landmark(PoseJoint.rightElbow)!!.position) < floor)
                return null
            return Sample(pose.timestamp, pose.imageSize, pose.backend, pose.requestRevision, arms)
        }
    }
}
