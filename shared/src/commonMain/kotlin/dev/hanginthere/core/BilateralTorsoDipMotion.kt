package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Source-owned dip motion. Current
 * bilateral shoulder motion plus one complete measured arm corroborate a body
 * cycle after full bilateral top calibration. Absent joints remain absent. */
class BilateralTorsoDipMotion {
    @Serializable enum class Phase { seekingTop, ready, outbound, returning }
    @Serializable data class Event(val outcome: String, val reason: String, val seconds: Double,
        val departure: Double?, val peak: Double?, val measuredArmSlots: List<Int>)
    @Serializable data class Snapshot(val seconds: Double?, val phase: Phase, val cycles: Int,
        val reason: String, val observationUsable: Boolean, val sourceAccepted: Boolean,
        val currentArmSlots: List<Int>, val travelArmFractions: List<Double> = emptyList(),
        val relativeTravel: List<Double?> = emptyList(), val event: Event? = null,
        val elbowDegrees: List<Double?> = emptyList(),
        val wristDriftArmFractions: List<Double?> = emptyList(),
        val partialAttempts: Int = 0, val interruptedAttempts: Int = 0,
        val progress: Double = 0.0,
        val countMethod: String = BilateralTorsoDipMotion.countMethod,
        val policyVersion: Int = BilateralTorsoDipMotion.policyVersion,
        val targetProgress: Double? = null, val targetReached: Boolean = false)
    internal data class Arm(val wrist: Point2D, val degrees: Double, val length: Double)
    internal data class Sample(val time: PresentationTime, val size: ImageSize, val backend: String,
        val revision: Int, val shoulders: List<Point2D>, val wrists: List<Point2D?>,
        val arms: List<Arm?>, val horizontal: Boolean) {
        val complete get() = arms.indices.filter { arms[it] != null }
        val full get() = complete.size == 2
        fun height(i: Int) = arms[i]!!.wrist.y - shoulders[i].y
        fun armIsTop(i: Int) = arms[i]?.let { it.degrees >= 140 && height(i) >= .35 * it.length } == true
        fun isTop() = full && arms.indices.all(::armIsTop)
        val shoulderSum get() = shoulders.sumOf { it.y }
    }
    private var phase = Phase.seekingTop
    private var cycles = 0
    private var partials = 0
    private var interruptions = 0
    private var completedInContext = false
    internal val measuredTop: Sample? get() = support
    internal var currentSample: Sample? = null; private set
    val hasObservedExcursion: Boolean get() = peak != null || completedInContext
    private var highWater: PresentationTime? = null
    private var lastUsable: PresentationTime? = null
    private var generation: Long? = null
    private var topCandidate: Sample? = null
    private var support: Sample? = null
    private var baseline: Sample? = null
    private var lastMeasured: Sample? = null
    private var peak: Sample? = null
    private var peakWitnesses = emptySet<Int>()
    private var returnedTop: Sample? = null
    private var returnWitnesses = emptySet<Int>()
    private var departure: Double? = null
    private var turnSince: PresentationTime? = null
    private var lastArmEvidence = mutableListOf<PresentationTime?>(null, null)
    private var lastCycleEventTime: PresentationTime? = null
    private var lastGeometry: Sample? = null
    private var revokedArmSlots = mutableSetOf<Int>()

    private fun clear() {
        phase = Phase.seekingTop; lastUsable = null; topCandidate = null; completedInContext = false
        support = null; baseline = null; lastMeasured = null; peak = null
        peakWitnesses = emptySet(); returnedTop = null; returnWitnesses = emptySet()
        departure = null; turnSince = null
        lastArmEvidence = mutableListOf(null, null)
        lastCycleEventTime = null
        lastGeometry = null; revokedArmSlots = mutableSetOf()
    }
    private fun cancel(reason: String, seconds: Double): Event? {
        val event = if (phase == Phase.outbound || phase == Phase.returning)
            Event("interrupted", reason, seconds, departure, peak?.time?.seconds, peakWitnesses.sorted()) else null
        if (event != null) interruptions++
        clear(); return event
    }
    fun interrupt(reason: String) = cancel(reason, highWater?.seconds ?: 0.0)
    private fun rearm(sample: Sample) {
        phase = Phase.ready; baseline = sample; lastMeasured = sample
        peak = null; peakWitnesses = emptySet(); returnedTop = null; returnWitnesses = emptySet()
        departure = null; turnSince = null
    }
    fun observe(selection: BodyAthleteSelection, cameraReady: Boolean, allowDeparture: Boolean = true): Snapshot {
        val frame = checkNotNull(selection.frame)
        currentSample = null
        val pose = frame.pose
        val time = pose.timestamp
        var current: Sample? = null
        var event: Event? = null
        fun result(reason: String, world: List<Double> = emptyList(), relative: List<Double?> = emptyList()): Snapshot {
            currentSample = current
            val usable = current?.complete?.isNotEmpty() == true && cameraReady && reason !in listOf(
                "sourceRegression", "contextChanged", "horizontalTorso", "overheadPosture", "positiveBilateralRelease", "supportWitnessDeparture")
            return Snapshot(time.seconds.takeIf { it.isFinite() }, phase, cycles, reason, usable,
                reason != "sourceRegression", current?.complete ?: emptyList(), world, relative, event,
                elbowDegrees = current?.arms?.map { it?.degrees } ?: emptyList(),
                wristDriftArmFractions = current?.wrists?.mapIndexed { i, wrist ->
                    val origin = support?.arms?.get(i)
                    if (wrist != null && origin != null) distance(wrist, origin.wrist) / origin.length else null
                } ?: emptyList(),
                partialAttempts = partials, interruptedAttempts = interruptions,
                progress = when {
                    event?.outcome == "imageCycle" -> 1.0
                    world.size != 2 -> 0.0
                    // Show measured return movement while the counter still
                    // waits for reversal confirmation. Never predict a count.
                    phase == Phase.outbound -> max(
                        .45 * world.minOf { (it / .18).coerceIn(0.0, 1.0) }, returnProgress(world))
                    phase == Phase.returning -> returnProgress(world)
                    else -> 0.0
                }, targetProgress = if (usable) targetProgress(current) else null, targetReached = usable && peak != null)
        }
        val previous = highWater
        if (!time.seconds.isFinite() || time.seconds < 0 || previous?.let { compare(time, it, zero) != 1 } == true) {
            event = cancel("sourceRegression", previous?.seconds ?: 0.0)
            return result("sourceRegression")
        }
        highWater = time
        if (previous?.let { compare(time, it, gap) == 1 } == true) event = cancel("sourceTimeGap", time.seconds)
        if (generation != null && generation != frame.decision.sourceGeneration)
            event = cancel("athleteEpochChanged", time.seconds) ?: event
        generation = frame.decision.sourceGeneration
        if (!cameraReady) {
            event = cancel("cameraUnavailable", time.seconds) ?: event
            return result("cameraUnavailable")
        }
        val fixed = support
        if (fixed != null && (fixed.size != pose.imageSize || fixed.backend != pose.backend || fixed.revision != pose.requestRevision)) {
            event = cancel("contextChanged", time.seconds) ?: event
            return result("contextChanged")
        }
        current = if (frame.decision.status == BodyAthleteAssociation.Status.associated) sample(pose) else null
        val now = current
        if (now != null && now.horizontal) {
            event = cancel("horizontalTorso", time.seconds) ?: event
            return result("horizontalTorso")
        }
        // Release uses current four-point evidence independently of elbow
        // availability, with only previously measured full-calibration scale.
        if (now != null && fixed != null && positiveRelease(fixed, now)) {
            event = cancel("positiveBilateralRelease", time.seconds) ?: event
            return result("positiveBilateralRelease")
        }
        val previousGeometry = lastGeometry
        lastGeometry = now
        if (now != null && fixed != null && previousGeometry != null) {
            val departed = now.wrists.indices.filter { i -> listOf(previousGeometry, now).all { s ->
                s.wrists[i]?.let { distance(it, fixed.arms[i]!!.wrist) > .12 * fixed.arms[i]!!.length } == true
            } }
            revokedArmSlots.addAll(departed)
            // This also applies before departure and when elbows are absent:
            // a known lost support cannot be silently restored at a low pose.
            if (departed.isNotEmpty() && now.complete.none { it !in revokedArmSlots }) {
                event = cancel("supportWitnessDeparture", time.seconds) ?: event
                return result("supportWitnessDeparture")
            }
        }
        if (now == null || now.complete.isEmpty()) {
            topCandidate = null; lastMeasured = null; turnSince = null
            if (lastUsable?.let { compare(time, it, gap) == 1 } == true)
                event = cancel("bodyObservationOutage", time.seconds) ?: event
            return result("bodyUnavailable")
        }
        if (now.complete.all { now.height(it) < -.02 * min(now.size.width, now.size.height) }) {
            event = cancel("overheadPosture", time.seconds) ?: event
            return result("overheadPosture")
        }
        if (lastUsable?.let { compare(time, it, gap) == 1 } == true)
            event = cancel("bodyObservationOutage", time.seconds) ?: event
        // A different arm being visible cannot extend the age of an old bend
        // witness. Reappearing measurements do not retroactively fill its gap.
        if (peakWitnesses.isNotEmpty()) {
            val calibration = support!!
            val fresh = peakWitnesses.filter { i ->
                val ageValid = lastArmEvidence[i]?.let { compare(time, it, gap)?.let { it <= 0 } } == true
                val knownDeparturePair = lastMeasured?.let { prior -> listOf(prior, now).all { s ->
                    s.wrists[i]?.let { distance(it, calibration.arms[i]!!.wrist) > .12 * calibration.arms[i]!!.length } == true
                } } == true
                ageValid && !knownDeparturePair && i !in revokedArmSlots
            }.toSet()
            if (fresh.isEmpty()) event = cancel("armWitnessUnavailable", time.seconds) ?: event
            else peakWitnesses = fresh
        }
        for (i in now.complete) {
            val calibration = support
            // A visible wrist away from its calibrated support cannot renew
            // the age of that support witness. A single noisy point earns no
            // movement or departure proof; two actual departed points revoke it.
            if (i !in revokedArmSlots && (calibration == null || distance(now.arms[i]!!.wrist, calibration.arms[i]!!.wrist) <= .12 * calibration.arms[i]!!.length))
                lastArmEvidence[i] = time
        }
        lastUsable = time
        val prior = lastMeasured
        lastMeasured = now
        if (phase == Phase.seekingTop) {
            if (!now.isTop()) { topCandidate = null; return result("holdFullyMeasuredTop") }
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
        val lengths = origin.arms.map { it!!.length }
        val required = lengths.map { max(.18 * it, .02 * min(now.size.width, now.size.height)) }
        val world = now.shoulders.indices.map { (now.shoulders[it].y - base.shoulders[it].y) / lengths[it] }
        val relative = now.arms.indices.map { i ->
            if (base.arms[i] != null && now.arms[i] != null) (base.height(i) - now.height(i)) / lengths[i] else null
        }
        fun stableWrist(a: Sample, i: Int) = a.arms[i]?.let { distance(it.wrist, origin.arms[i]!!.wrist) <= .12 * lengths[i] } == true
        fun displaced(a: Sample, fraction: Double): Set<Int> {
            if (!a.shoulders.indices.all { a.shoulders[it].y - base.shoulders[it].y >= required[it] * fraction }) return emptySet()
            return a.complete.filter { i -> i !in revokedArmSlots && base.arms[i] != null && stableWrist(a, i) &&
                base.height(i) - a.height(i) >= required[i] * fraction }.toSet()
        }
        fun bendPair(a: Sample, b: Sample) = (displaced(a, 1.0) intersect displaced(b, 1.0)).filter { i ->
            listOf(a, b).all { base.arms[i]!!.degrees - it.arms[i]!!.degrees >= 30 || it.arms[i]!!.degrees <= 100 }
        }.toSet()
        fun nearTop(a: Sample, witnesses: Set<Int>) = a.shoulders.indices.all {
            a.shoulders[it].y <= base.shoulders[it].y + .08 * lengths[it]
        } && witnesses.any { i -> i !in revokedArmSlots && a.arms[i] != null && base.arms[i] != null && stableWrist(a, i) &&
            a.height(i) >= base.height(i) - .08 * lengths[i] }
        fun returnedPair(low: Sample, a: Sample, b: Sample): Set<Int> {
            if (!listOf(a, b).all { s -> s.shoulders.indices.all { low.shoulders[it].y - s.shoulders[it].y >= required[it] } })
                return emptySet()
            return peakWitnesses.filter { i -> listOf(a, b).all { s ->
                s.arms[i] != null && low.arms[i] != null && stableWrist(s, i) &&
                    s.height(i) - low.height(i) >= required[i] && s.arms[i]!!.degrees - low.arms[i]!!.degrees >= 15
            } }.toSet()
        }
        if (phase == Phase.ready) {
            // Only two fully measured higher tops may replace calibration.
            if (revokedArmSlots.isEmpty() && prior != null && prior.full && now.full && base.full && listOf(prior, now).all { a ->
                a.arms.indices.all { a.height(it) > base.height(it) && a.shoulders[it].y < base.shoulders[it].y && a.arms[it]!!.degrees >= 140 }
            }) {
                val higher = listOf(prior, now).maxBy { it.shoulderSum }
                support = higher; rearm(higher)
                return result("higherPreparation", world, relative)
            }
            if (!allowDeparture) return result("ready", world, relative)
            val witness = displaced(now, .5).filter { base.arms[it]!!.degrees - now.arms[it]!!.degrees >= 15 }
            if (witness.isEmpty()) return result("ready", world, relative)
            val sharedDeparture = prior?.takeIf { a ->
                lastCycleEventTime?.let { compare(a.time, it, zero) == 0 } == true &&
                    displaced(a, .5).any { base.arms[it]!!.degrees - a.arms[it]!!.degrees >= 15 }
            }
            phase = Phase.outbound; departure = sharedDeparture?.time?.seconds ?: time.seconds
            peak = null; peakWitnesses = emptySet(); returnedTop = null; returnWitnesses = emptySet()
        }
        if (phase == Phase.outbound) {
            if (prior != null) {
                val witnesses = bendPair(prior, now)
                if (witnesses.isNotEmpty()) {
                    // Conservative real member of a pair, never a synthetic
                    // extremum, missing wrist or copied elbow angle.
                    val candidate = listOf(prior, now).minBy { it.shoulderSum }
                    if (peak == null || candidate.shoulderSum > peak!!.shoulderSum) {
                        peak = candidate; peakWitnesses = witnesses
                    }
                }
            }
            val low = peak
            val recovered = if (low != null && prior != null) returnedPair(low, prior, now) else emptySet()
            val reverses = low != null && now.shoulders.indices.all {
                low.shoulders[it].y - now.shoulders[it].y >= .04 * lengths[it]
            } && peakWitnesses.any { now.arms[it] != null && low.arms[it] != null && stableWrist(now, it) &&
                now.height(it) - low.height(it) >= .04 * lengths[it] }
            if (recovered.isNotEmpty()) { phase = Phase.returning; turnSince = null }
            else if (reverses) {
                if (turnSince == null) turnSince = time
                if (compare(time, turnSince!!, turnDwell)?.let { it >= 0 } == true) { phase = Phase.returning; turnSince = null }
            } else turnSince = null
            val common = if (prior == null) emptySet() else (prior.complete intersect now.complete.toSet()).toSet()
            if (peak == null && prior != null && nearTop(prior, common) && nearTop(now, common) &&
                time.seconds - departure!! >= turnDwell.seconds) {
                partials++
                event = Event("partial", "insufficientObservedTravel", time.seconds, departure, null, emptyList())
                rearm(now); return result("insufficientObservedTravel", world, relative)
            }
        }
        if (phase == Phase.returning && prior != null) {
            val low = peak!!
            val recovered = returnedPair(low, prior, now)
            if (recovered.isNotEmpty()) {
                val candidate = listOf(prior, now).maxBy { it.shoulderSum }
                if (returnedTop == null || candidate.shoulderSum < returnedTop!!.shoulderSum) {
                    returnedTop = candidate; returnWitnesses = recovered
                }
            }
            val high = returnedTop
            val upperWitnesses = if (high == null) emptySet() else returnWitnesses.filter { i ->
                // A reversal below the calibrated top is only a completed
                // return when this same arm actually reached supported-top
                // readiness. Repeated bent-arm partials cannot bank cycles.
                high.armIsTop(i) && listOf(prior, now).all { a -> a.arms[i] != null && high.arms[i] != null && stableWrist(a, i) &&
                    high.height(i) - a.height(i) >= .04 * lengths[i] && a.shoulders.indices.all {
                        a.shoulders[it].y - high.shoulders[it].y >= .04 * lengths[it]
                    } }
            }.toSet()
            val arrived = high != null && recovered.isNotEmpty() && nearTop(prior, recovered) && nearTop(now, recovered)
            if (arrived || upperWitnesses.isNotEmpty()) {
                cycles++; completedInContext = true
                val reason = if (upperWitnesses.isNotEmpty()) "observedUpperTurn" else "measuredTopReturn"
                event = Event("imageCycle", reason, time.seconds, departure, low.time.seconds,
                    (if (upperWitnesses.isNotEmpty()) upperWitnesses else recovered).sorted())
                rearm(if (upperWitnesses.isNotEmpty()) high!! else now); lastMeasured = now
                lastCycleEventTime = time
                return result(reason, world, relative)
            }
        }
        return result("tracking", world, relative)
    }

    // Presentation only; count thresholds and endpoint evidence stay independent.
    // Display only: both shoulders and a currently measured, stable arm must
    // reach the same displacement/bend gates used by displaced() and bendPair().
    private fun targetProgress(current: Sample?): Double? {
        val now = current ?: return null
        val base = baseline ?: return null
        val fixed = support ?: return null
        val required = fixed.arms.map { max(.18 * it!!.length, .02 * min(now.size.width, now.size.height)) }
        val world = now.shoulders.indices.minOf { (now.shoulders[it].y - base.shoulders[it].y) / required[it] }
        val arms = now.complete.filter { i -> base.arms[i] != null && i !in revokedArmSlots &&
            distance(now.arms[i]!!.wrist, fixed.arms[i]!!.wrist) <= .12 * fixed.arms[i]!!.length }
        if (arms.isEmpty()) return null
        val corroboration = arms.maxOf { i ->
            val bend = min(30.0, base.arms[i]!!.degrees - 100).coerceAtLeast(1.0)
            min((base.height(i) - now.height(i)) / required[i],
                (base.arms[i]!!.degrees - now.arms[i]!!.degrees) / bend)
        }
        return min(world, corroboration).coerceIn(0.0, 1.0)
    }

    private fun returnProgress(world: List<Double>): Double {
        val base = baseline ?: return 0.0
        val low = peak ?: return 0.0
        val fixed = support ?: return 0.0
        val excursion = base.shoulders.indices.map { i ->
            (low.shoulders[i].y - base.shoulders[i].y) / fixed.arms[i]!!.length
        }
        if (excursion.any { !it.isFinite() || it <= 0.0 }) return 0.0
        val remaining = world.indices.maxOf { world[it] / excursion[it] }.coerceIn(0.0, 1.0)
        return .5 + .45 * (1 - remaining)
    }

    companion object {
        const val countMethod = "bilateralTorsoDipRelativeMotion"
        /** v2 requires measured top-support evidence for upper-turn completion. */
        const val policyVersion = 2
        private val zero = SourceTimeLimit(0, 1)
        private val gap = SourceTimeLimit(7, 20)
        private val topDwell = SourceTimeLimit(1, 4)
        private val turnDwell = SourceTimeLimit(3, 25)
        private fun compare(a: PresentationTime, b: PresentationTime, limit: SourceTimeLimit) = SourceTimeComparison.compareElapsed(a, b, limit)
        private fun distance(a: Point2D, b: Point2D) = hypot(a.x - b.x, a.y - b.y)
        private fun stablePair(a: Sample, b: Sample) = a.full && b.full && a.arms.indices.all {
            distance(a.arms[it]!!.wrist, b.arms[it]!!.wrist) <= .06 * a.arms[it]!!.length &&
                distance(a.shoulders[it], b.shoulders[it]) <= .08 * a.arms[it]!!.length
        }
        private fun positiveRelease(origin: Sample, now: Sample) = now.wrists.indices.all { i ->
            val wrist = now.wrists[i] ?: return@all false
            val arm = origin.arms[i]!!
            distance(wrist, arm.wrist) > .12 * arm.length &&
                -((now.shoulders[i].x-origin.shoulders[i].x)*(origin.shoulders[i].x-arm.wrist.x) +
                    (now.shoulders[i].y-origin.shoulders[i].y)*(origin.shoulders[i].y-arm.wrist.y)) /
                    distance(origin.shoulders[i], arm.wrist) >= .35 * arm.length
        }
        private fun sample(pose: PoseResult): Sample? {
            val person = pose.people.singleOrNull() ?: return null
            if (!pose.imageSize.isValid || pose.backend.isBlank() || person.landmarks.size > 64 ||
                person.landmarks.map { it.joint }.distinct().size != person.landmarks.size) return null
            fun position(joint: PoseJoint) = person.landmark(joint)?.position?.takeIf {
                it.x in 0.0..pose.imageSize.width && it.y in 0.0..pose.imageSize.height
            }
            val sides = ArmMeasurement.Side.entries.sortedBy { position(it.joints[0])?.x ?: Double.POSITIVE_INFINITY }
            val shoulders = sides.map { position(it.joints[0]) ?: return null }
            val floor = ArmMeasurement.minimumSegmentFraction * min(pose.imageSize.width, pose.imageSize.height)
            if (shoulders[1].x - shoulders[0].x < floor) return null
            val wrists = sides.map { position(it.joints[2]) }
            val arms = sides.map { side -> ArmMeasurement(pose, side).estimate?.let {
                Arm(person.landmark(side.joints[2])!!.position, it.elbowDegrees, it.upperArmPixels + it.forearmPixels)
            } }
            if (wrists.all { it != null } && wrists[1]!!.x - wrists[0]!!.x < 2 * floor) return null
            val elbows = sides.map { position(it.joints[1]) }
            if (elbows.all { it != null } && distance(elbows[0]!!, elbows[1]!!) < floor) return null
            val hips = listOf(PoseJoint.leftHip, PoseJoint.rightHip).mapNotNull { position(it) }
            val horizontal = if (hips.size != 2) false else {
                val dy = hips.map { it.y }.average() - shoulders.map { it.y }.average()
                val dx = hips.map { it.x }.average() - shoulders.map { it.x }.average()
                dy <= 0 || abs(dx) > dy
            }
            return Sample(pose.timestamp, pose.imageSize, pose.backend, pose.requestRevision, shoulders, wrists, arms, horizontal)
        }
    }
}
