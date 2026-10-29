package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Source-owned rail-free relative dip movement.
 * Both measured shoulders must travel in the image AND relative to their
 * current wrists. One consistently observed elbow corroborates the bend;
 * front/rear projection need not expose the other elbow's flexion angle.
 * Missing observations earn no turnaround/endpoint evidence. Camera readiness
 * remains an independent input, never a pose-derived assumption in production.
 */
class DipCycleMotion {
    @Serializable enum class Camera { ready, unavailable, moved }
    @Serializable enum class Phase { seekingTop, ready, outbound, returning }
    @Serializable data class Event(val outcome: String, val reason: String, val seconds: Double,
        val departure: Double?, val peak: Double?)
    @Serializable data class Snapshot(val seconds: Double?, val phase: Phase, val cycles: Int,
        val reason: String, val observationUsable: Boolean, val sourceAccepted: Boolean = true,
        val travelArmFractions: List<Double> = emptyList(), val wristDriftArmFractions: List<Double> = emptyList(),
        val elbowDegrees: List<Double> = emptyList(), val event: Event? = null,
        val preparationRetained: Boolean = false,
        val partialAttempts: Int = 0, val interruptedAttempts: Int = 0,
        val progress: Double = 0.0, val policyVersion: Int = DipCycleMotion.policyVersion)
    internal data class Arm(val shoulder: Point2D, val wrist: Point2D, val degrees: Double, val length: Double) {
        val height get() = wrist.y - shoulder.y
        val isTop get() = degrees >= 140 && height >= .35 * length
    }
    internal data class Sample(val time: PresentationTime, val size: ImageSize, val backend: String,
        val revision: Int, val arms: List<Arm>, val horizontalTorso: Boolean)
    private var phase = Phase.seekingTop
    private var cycles = 0
    private var partials = 0
    private var interruptions = 0
    private var highWater: PresentationTime? = null
    private var lastUsable: PresentationTime? = null
    private var topCandidate: Sample? = null
    private var support: Sample? = null
    private var baseline: Sample? = null
    private var lastMeasured: Sample? = null
    private var peak: Sample? = null
    private var returnTop: Sample? = null
    private var peakWitnesses = emptyList<Int>()
    private var departure: Double? = null
    private var turnSince: PresentationTime? = null
    private var completedInContext = false
    private var lastArmContinuous = false
    private var recoveryFrom: PresentationTime? = null
    private var lastCycleEventTime: PresentationTime? = null
    internal val measuredTop: Sample? get() = support
    val hasObservedExcursion: Boolean get() = peak != null || completedInContext

    private fun clear() {
        phase = Phase.seekingTop; lastUsable = null; topCandidate = null; support = null
        baseline = null; lastMeasured = null; peak = null; returnTop = null; peakWitnesses = emptyList()
        departure = null; turnSince = null
        completedInContext = false; lastArmContinuous = false; recoveryFrom = null
        lastCycleEventTime = null
    }
    private fun active() = phase == Phase.outbound || phase == Phase.returning
    private fun cancel(reason: String, seconds: Double): Event? {
        val e = if (active()) Event("interrupted", reason, seconds, departure, peak?.time?.seconds) else null
        if (e != null) interruptions++
        clear(); return e
    }
    fun interrupt(reason: String) = cancel(reason, highWater?.seconds ?: 0.0)
    private fun loseArmEvidence(time: PresentationTime, continuousAthlete: Boolean): Event? {
        val last = recoveryFrom ?: lastUsable
        if (!continuousAthlete || !lastArmContinuous || !completedInContext || support == null ||
            last == null || recoveryExpired(time, last))
            return cancel("bodyObservationOutage", time.seconds)
        val event = if (active()) Event("interrupted", "bodyObservationOutage", time.seconds, departure, peak?.time?.seconds) else null
        if (event != null) interruptions++
        // Retain only set preparation. The unfinished peak, return and every
        // adjacent-pair witness are discarded, so nothing can complete across
        // the outage. A new top pair and new departure must be observed.
        phase = Phase.seekingTop; baseline = null; topCandidate = null
        lastUsable = null; lastMeasured = null; peak = null; returnTop = null
        peakWitnesses = emptyList(); departure = null; turnSince = null
        lastCycleEventTime = null
        recoveryFrom = last
        return event
    }
    private fun rearm(sample: Sample) {
        phase = Phase.ready; baseline = sample; peak = null; returnTop = null; peakWitnesses = emptyList()
        departure = null; turnSince = null; lastMeasured = sample
        recoveryFrom = null
    }

    fun observe(pose: PoseResult, cameraReady: Boolean, allowDeparture: Boolean = true,
        athleteContinuous: Boolean = false): Snapshot {
        val time = pose.timestamp
        val previous = highWater
        var event: Event? = null
        var current: Sample? = null
        fun result(reason: String, travel: List<Double> = emptyList(), drift: List<Double> = emptyList()) =
            Snapshot(time.seconds.takeIf { it.isFinite() }, phase, cycles, reason,
                current != null && cameraReady && reason !in listOf("sourceRegression", "contextChanged", "horizontalTorso", "overheadPosture", "positiveBilateralRelease"),
                reason != "sourceRegression", travel, drift, current?.arms?.map { it.degrees } ?: emptyList(), event,
                preparationRetained = recoveryFrom != null, partialAttempts = partials,
                interruptedAttempts = interruptions, progress = when {
                    event?.outcome == "imageCycle" -> 1.0
                    travel.size != 2 -> 0.0
                    phase == Phase.outbound -> .45 * travel.minOf { (it / .18).coerceIn(0.0, 1.0) }
                    phase == Phase.returning -> .45 + .5 * (1 - travel.maxOf { (it / .18).coerceIn(0.0, 1.0) })
                    else -> 0.0
                })
        if (!time.seconds.isFinite() || time.seconds < 0 || previous?.let { compare(time, it, zero) != 1 } == true) {
            event = cancel("sourceRegression", previous?.seconds ?: 0.0)
            return result("sourceRegression")
        }
        highWater = time
        if (previous?.let { compare(time, it, gap) == 1 } == true)
            event = cancel("sourceTimeGap", time.seconds)
        if (!cameraReady) {
            event = cancel("cameraUnavailable", time.seconds) ?: event
            return result("cameraUnavailable")
        }
        val fixed = support
        if (fixed != null && (fixed.size != pose.imageSize || fixed.backend != pose.backend || fixed.revision != pose.requestRevision)) {
            event = cancel("contextChanged", time.seconds) ?: event
            return result("contextChanged")
        }
        val continuous = athleteContinuous && measuredShoulders(pose)
        if (!continuous) lastArmContinuous = false
        if (recoveryFrom != null && !continuous) {
            event = cancel("athleteContinuityUnavailable", time.seconds) ?: event
            return result("athleteContinuityUnavailable")
        }
        if (recoveryFrom?.let { recoveryExpired(time, it) } == true) {
            event = cancel("recoveryExpired", time.seconds) ?: event
            return result("recoveryExpired")
        }
        current = sample(pose)
        if (current == null) {
            // Keep only the private trajectory across <=0.35s of SDK outage.
            // Neither the top preparation nor turn/pair evidence bridges it.
            topCandidate = null; lastMeasured = null; turnSince = null
            if (lastUsable?.let { compare(time, it, gap) == 1 } == true)
                event = loseArmEvidence(time, continuous) ?: event
            return result("bodyUnavailable")
        }
        val now = current!!
        if (now.horizontalTorso) {
            event = cancel("horizontalTorso", time.seconds) ?: event
            return result("horizontalTorso")
        }
        if (now.arms.all { it.height < -.02 * min(now.size.width, now.size.height) }) {
            event = cancel("overheadPosture", time.seconds) ?: event
            return result("overheadPosture")
        }
        val releaseOrigin = support
        if (releaseOrigin != null && positiveRelease(releaseOrigin, now)) {
            event = cancel("positiveBilateralRelease", time.seconds) ?: event
            return result("positiveBilateralRelease")
        }
        if (lastUsable?.let { compare(time, it, gap) == 1 } == true)
            event = loseArmEvidence(time, continuous) ?: event
        lastUsable = time
        lastArmContinuous = continuous
        val prior = lastMeasured
        lastMeasured = now
        if (phase == Phase.seekingTop) {
            if (!now.arms.all { it.isTop }) { topCandidate = null; return result("holdExtendedTop") }
            if (recoveryFrom != null) {
                val old = support!!
                val wristsStillAtSupport = now.arms.indices.all { i ->
                    distance(now.arms[i].wrist, old.arms[i].wrist) <= .12 * old.arms[i].length
                }
                if (prior != null && prior.arms.all { it.isTop } && stablePair(prior, now) && wristsStillAtSupport) {
                    support = now; topCandidate = null; rearm(now)
                    return result("recoveredMeasuredTop")
                }
                return result("reacquiringMeasuredTop")
            }
            val candidate = topCandidate
            val stable = candidate != null && stablePair(candidate, now)
            if (!stable) topCandidate = now
            if (stable && compare(time, candidate!!.time, topDwell)?.let { it >= 0 } == true) {
                support = now; topCandidate = null; rearm(now)
            }
            return result(if (phase == Phase.ready) "ready" else "acquiringTop")
        }
        val origin = support!!
        val base = baseline!!
        val lengths = origin.arms.map { it.length }
        val required = lengths.map { max(.18 * it, .02 * min(now.size.width, now.size.height)) }
        val travel = now.arms.indices.map { (base.arms[it].height - now.arms[it].height) / lengths[it] }
        val drift = now.arms.indices.map { distance(now.arms[it].wrist, origin.arms[it].wrist) / lengths[it] }
        fun displaced(a: Sample, fraction: Double) = a.arms.indices.all { i ->
            base.arms[i].height - a.arms[i].height >= required[i] * fraction &&
                a.arms[i].shoulder.y - base.arms[i].shoulder.y >= required[i] * fraction
        }
        fun bendWitnesses(a: Sample, b: Sample) = a.arms.indices.filter { i ->
            listOf(a, b).all { s -> base.arms[i].degrees - s.arms[i].degrees >= 30 || s.arms[i].degrees <= 100 }
        }
        fun nearTop(a: Sample) = a.arms.indices.all { i ->
            a.arms[i].height >= base.arms[i].height - .08 * lengths[i] &&
                a.arms[i].shoulder.y <= base.arms[i].shoulder.y + .08 * lengths[i]
        }
        fun recoveredFrom(low: Sample, a: Sample, b: Sample) = listOf(a, b).all { s ->
            s.arms.indices.all { i ->
                s.arms[i].height - low.arms[i].height >= required[i] &&
                    low.arms[i].shoulder.y - s.arms[i].shoulder.y >= required[i]
            }
        }
        fun elbowReturnedFrom(low: Sample, a: Sample, b: Sample) = peakWitnesses.any { i ->
            listOf(a, b).all { it.arms[i].degrees - low.arms[i].degrees >= 15 }
        }
        if (phase == Phase.ready) {
            if (!allowDeparture) {
                if (now.arms.all { it.isTop }) rearm(now) else clear()
                return result(if (phase == Phase.ready) "ready" else "holdExtendedTop", travel, drift)
            }
            if (prior != null && listOf(prior, now).all { a -> a.arms.indices.all { i ->
                    a.arms[i].height > base.arms[i].height &&
                        a.arms[i].shoulder.y < base.arms[i].shoulder.y && a.arms[i].degrees >= 140
                } }) {
                // A standing preparation can precede the actual mounted top.
                // Replace BOTH the measured support/scale and cycle baseline
                // using a conservative actual member of two observed higher
                // tops, before departure. Never retain standing hand locations
                // as release anchors for the later mounted exercise.
                val higher = listOf(prior, now).minBy { it.arms.sumOf { a -> a.height } }
                baseline = higher; support = higher
                return result("higherPreparation", travel, drift)
            }
            if (!displaced(now, .5) || now.arms.indices.none { base.arms[it].degrees - now.arms[it].degrees >= 15 })
                return result("ready", travel, drift)
            // Upper-turn confirmation can arrive while the next lowering is
            // already observed. That exact confirmation sample may also be
            // the next departure; its corroborated peak must not be timestamped
            // before an artificially later start. Never borrow a preparation
            // sample or a sample preceding the previous completed event.
            val sharedTurnDeparture = prior?.takeIf { a ->
                lastCycleEventTime?.let { compare(a.time, it, zero) == 0 } == true &&
                    displaced(a, .5) && a.arms.indices.any { base.arms[it].degrees - a.arms[it].degrees >= 15 }
            }
            phase = Phase.outbound; departure = sharedTurnDeparture?.time?.seconds ?: time.seconds
            peak = null; returnTop = null; peakWitnesses = emptyList()
        }
        if (phase == Phase.outbound) {
            if (prior != null && displaced(now, 1.0) && displaced(prior, 1.0)) {
                val witnesses = bendWitnesses(prior, now)
                if (witnesses.isNotEmpty()) {
                    // Conservative actual member of two adjacent observations;
                    // never synthesize a missing wrist, angle or extreme.
                    val candidate = listOf(prior, now).maxBy { it.arms.sumOf { a -> a.height } }
                    if (peak == null || candidate.arms.sumOf { it.height } < peak!!.arms.sumOf { it.height }) {
                        peak = candidate; peakWitnesses = witnesses
                    }
                }
            }
            val low = peak
            val reverses = low != null && now.arms.indices.all { i ->
                now.arms[i].height - low.arms[i].height >= .04 * lengths[i] &&
                    low.arms[i].shoulder.y - now.arms[i].shoulder.y >= .04 * lengths[i]
            }
            // At sparse cadence, a whole observed recovery can finish before
            // an intermediate turn's dwell expires. Keep that stronger pair
            // evidence: both measured shoulders recover the full required
            // distance in world/current-wrist coordinates, with the same
            // elbow witness. The ordinary endpoint/upper-turn checks below
            // still decide completion; recovery magnitude alone earns none.
            val fullReturnPair = low != null && prior != null &&
                recoveredFrom(low, prior, now) && elbowReturnedFrom(low, prior, now)
            if (fullReturnPair) {
                phase = Phase.returning; turnSince = null
            } else if (reverses) {
                if (turnSince == null) turnSince = time
                if (compare(time, turnSince!!, turnDwell)?.let { it >= 0 } == true) {
                    phase = Phase.returning; turnSince = null
                }
            } else turnSince = null
            if (peak == null && prior != null && nearTop(now) && nearTop(prior) &&
                time.seconds - departure!! >= turnDwell.seconds) {
                event = Event("partial", "insufficientObservedTravel", time.seconds, departure, null)
                partials++
                rearm(now); return result("insufficientObservedTravel", travel, drift)
            }
        }
        if (phase == Phase.returning && prior != null) {
            val low = peak!!
            val recovered = recoveredFrom(low, prior, now)
            val sameElbowReturned = elbowReturnedFrom(low, prior, now)
            if (recovered && sameElbowReturned) {
                val candidate = listOf(prior, now).minBy { it.arms.sumOf { a -> a.height } }
                if (returnTop == null || candidate.arms.sumOf { it.height } > returnTop!!.arms.sumOf { it.height }) returnTop = candidate
            }
            val high = returnTop
            val upperTurn = high != null && listOf(prior, now).all { a -> a.arms.indices.all { i ->
                high.arms[i].height - a.arms[i].height >= .04 * lengths[i] &&
                    a.arms[i].shoulder.y - high.arms[i].shoulder.y >= .04 * lengths[i]
            } }
            // Either two measured arrivals at the previous top, or a returned
            // trajectory with a corroborated actual upper turn. Neither needs
            // a pause or matching anatomical extension. A held partial ascent
            // and EOF supply neither event; positive release was checked first.
            val arrived = high != null && recovered && sameElbowReturned && nearTop(prior) && nearTop(now)
            if (arrived || upperTurn) {
                cycles++; completedInContext = true
                val reason = if (upperTurn) "observedUpperTurn" else "measuredTopReturn"
                event = Event("imageCycle", reason, time.seconds, departure, low.time.seconds)
                rearm(if (upperTurn) high!! else now)
                lastCycleEventTime = time
                lastMeasured = now
                return result(reason, travel, drift)
            }
        }
        return result("tracking", travel, drift)
    }

    companion object {
        const val countMethod = "bilateralDipRelativeMotion"
        const val policyVersion = 1
        private val zero = SourceTimeLimit(0, 1)
        private val gap = SourceTimeLimit(7, 20)
        private val topDwell = SourceTimeLimit(1, 4)
        private val turnDwell = SourceTimeLimit(3, 25)
        // The outer lifecycle already closes after three seconds unavailable.
        // This limit never completes a rep; it only expires cached preparation.
        private const val recoverySeconds = 3L
        private fun compare(end: PresentationTime, start: PresentationTime, limit: SourceTimeLimit) =
            SourceTimeComparison.compareElapsed(end, start, limit)
        private fun distance(a: Point2D, b: Point2D) = hypot(a.x - b.x, a.y - b.y)
        internal fun sameContext(a: Sample, b: Sample) = a.size == b.size && a.backend == b.backend && a.revision == b.revision
        private fun recoveryExpired(end: PresentationTime, start: PresentationTime): Boolean {
            // SourceTimeLimit deliberately supports only sub-second bounds.
            // Compare whole seconds first, then bounded rational remainders;
            // do not add ticks to a potentially overflowing absolute clock.
            if (end.timescale <= 0 || start.timescale <= 0 || end.value < 0 || start.value < 0) return true
            val whole = end.value / end.timescale - start.value / start.timescale
            if (whole != recoverySeconds) return whole > recoverySeconds
            return SourceTimeComparison.compareFractions(end.value % end.timescale, end.timescale.toLong(),
                start.value % start.timescale, start.timescale.toLong()) >= 0
        }
        private fun stablePair(a: Sample, b: Sample) = a.arms.indices.all { i ->
            distance(b.arms[i].wrist, a.arms[i].wrist) <= .06 * a.arms[i].length &&
                distance(b.arms[i].shoulder, a.arms[i].shoulder) <= .08 * a.arms[i].length
        }
        private fun measuredShoulders(pose: PoseResult): Boolean {
            val person = pose.people.singleOrNull() ?: return false
            return listOf(PoseJoint.leftShoulder, PoseJoint.rightShoulder).all { joint ->
                val p = person.landmark(joint) ?: return@all false
                p.position.isFinite && p.confidence.isFinite() && p.confidence <= 1 &&
                    p.position.x in 0.0..pose.imageSize.width && p.position.y in 0.0..pose.imageSize.height
            }
        }
        private fun positiveRelease(origin: Sample, now: Sample) = now.arms.indices.all { i ->
            val a = origin.arms[i]
            distance(now.arms[i].wrist, a.wrist) > .12 * a.length &&
                -((now.arms[i].shoulder.x - a.shoulder.x) * (a.shoulder.x - a.wrist.x) +
                    (now.arms[i].shoulder.y - a.shoulder.y) * (a.shoulder.y - a.wrist.y)) /
                distance(a.shoulder, a.wrist) >= .35 * a.length
        }
        internal fun sample(pose: PoseResult): Sample? {
            val person = pose.people.singleOrNull() ?: return null
            if (!pose.imageSize.isValid || pose.backend.isBlank() || person.landmarks.size > 64 ||
                person.landmarks.map { it.joint }.distinct().size != person.landmarks.size) return null
            val arms = ArmMeasurement.Side.entries.map { side ->
                val estimate = ArmMeasurement(pose, side).estimate ?: return null
                Arm(person.landmark(side.joints[0])!!.position, person.landmark(side.joints[2])!!.position,
                    estimate.elbowDegrees, estimate.upperArmPixels + estimate.forearmPixels)
            }.sortedBy { it.wrist.x }
            val floor = ArmMeasurement.minimumSegmentFraction * min(pose.imageSize.width, pose.imageSize.height)
            if (arms[1].wrist.x - arms[0].wrist.x < 2 * floor || arms[1].shoulder.x - arms[0].shoulder.x < floor ||
                distance(person.landmark(PoseJoint.leftElbow)!!.position, person.landmark(PoseJoint.rightElbow)!!.position) < floor) return null
            // Hips are optional; positively observed horizontal/reversed torso
            // excludes rows/pushups. Missing hips cannot invent torso evidence.
            val hips = listOf(PoseJoint.leftHip, PoseJoint.rightHip).mapNotNull { person.landmark(it, .3)?.position }
            val horizontal = if (hips.size != 2) false else {
                val dy = hips.map { it.y }.average() - arms.map { it.shoulder.y }.average()
                val dx = hips.map { it.x }.average() - arms.map { it.shoulder.x }.average()
                dy <= 0 || abs(dx) > dy
            }
            return Sample(pose.timestamp, pose.imageSize, pose.backend, pose.requestRevision, arms, horizontal)
        }
    }
}
