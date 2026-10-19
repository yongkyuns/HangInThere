package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.*

/** Separately versioned bilateral image-motion analysis. A cycle requires two
 * independently observed hands, fixed finite supports, a measured starting top,
 * bilateral travel and a sustained measured return. It is NOT an anatomical
 * depth, form, load or policy-8 ExerciseCounter verdict. Keep these cycle results
 * distinct from qualified reps. Native owners bind original pose/pixels/source
 * epochs and supply actual current camera evidence; missing evidence is never
 * stationary evidence. All calls require one serial owner.
 */
class BilateralDipMotion {
    @Serializable enum class Phase { seekingTop, ready, outbound, returning }
    @Serializable enum class Camera { ready, unavailable, moved }
    @Serializable enum class Outcome { imageCycle, partial, interrupted }
    @Serializable data class Event(val outcome: Outcome, val sourceTime: PresentationTime,
        val reason: String, val departureTime: PresentationTime?, val bottomTime: PresentationTime?,
        val firstReturnTime: PresentationTime?)
    @Serializable data class Snapshot(val phase: Phase, val imageCycles: Int, val partialAttempts: Int,
        val interruptedAttempts: Int, val availability: String, val progress: Double,
        val event: Event? = null, val policyVersion: Int = BilateralDipMotion.policyVersion)
    private var phase = Phase.seekingTop
    private var cycles = 0
    private var partials = 0
    private var interruptions = 0
    private var highWater: PresentationTime? = null
    private var lastValid: PresentationTime? = null
    private var reference: CurvedDipRailAcquisition.Reference? = null
    /** Also fences automatic release: pre-movement pixels cannot describe a
     * dismount after the camera has moved. Not a mechanical contact verdict. */
    internal val hasCurrentSupport: Boolean get() = reference != null
    private var reacquireAfter: PresentationTime? = null
    private var candidate: BilateralGripDisplacement.Sample? = null
    private var top: BilateralGripDisplacement.Sample? = null
    private var anchor: BilateralGripDisplacement.Anchor? = null
    private var endpoint: String? = null
    private var endpointSince: PresentationTime? = null
    private var departureTime: PresentationTime? = null
    private var bottomTime: PresentationTime? = null
    // Negative evidence only, never endpoint dwell or positive coordinates.
    // Each component is an actual witnessed near-top travel. Any subsequent arm
    // descending from it invalidates an unresolved return; no attempts are merged.
    private val unconfirmedReturnTravel = arrayOfNulls<Double>(2)

    fun resetSource() {
        clear(); highWater = null; reference = null; reacquireAfter = null; cycles = 0; partials = 0; interruptions = 0
    }
    private fun clear() {
        phase = Phase.seekingTop; lastValid = null; candidate = null; top = null; anchor = null
        endpoint = null; endpointSince = null; departureTime = null; bottomTime = null
        unconfirmedReturnTravel.fill(null)
    }
    private fun breakDwell() { endpoint = null; endpointSince = null }
    private fun active() = phase == Phase.outbound || phase == Phase.returning
    private fun close(reason: String, time: PresentationTime, outcome: Outcome = Outcome.interrupted): Event? {
        val event = if (active()) Event(outcome, time, reason, departureTime, bottomTime, endpointSince.takeIf { endpoint == "return" || endpoint == "partialReturn" }) else null
        if (event != null) when (outcome) {
            Outcome.imageCycle -> cycles++
            Outcome.partial -> partials++
            Outcome.interrupted -> interruptions++
        }
        clear(); return event
    }
    private fun snapshot(reason: String, progress: Double = 0.0, event: Event? = null) =
        Snapshot(phase, cycles, partials, interruptions, reason, progress.coerceIn(0.0, 1.0), event)

    /** The reference must be the actual shared acquisition result for this
     * source, retained across transient missing hand observations. A different
     * reference cancels the prior attempt; it cannot donate a new baseline. */
    fun observe(pose: PoseResult, support: CurvedDipRailAcquisition.Reference?, camera: Camera): Snapshot {
        val time = pose.timestamp
        if (!time.seconds.isFinite() || highWater?.let { compare(time, it, zero) != 1 } == true) {
            val event = close("sourceRegression", highWater ?: time)
            return snapshot("sourceRegression", event = event)
        }
        highWater = time
        // Transport is bounded before structural equality or curve geometry.
        // This sanity check cannot reconstruct acquisition dwell/provenance;
        // callers must retain the actual reference returned by acquisition.
        val finiteSupport = support?.takeIf { value -> value.imageSize.isValid && value.sourceTime.seconds.isFinite() &&
            value.hands.size == 2 && value.hands.all { it.isFinite } &&
            listOf(value.left, value.right).all { rail -> listOf(rail.firstBoundary, rail.secondBoundary).all {
                it.points.size in 2..ObservedCurveGeometry.maximumPoints
            } }
        }
        var event: Event? = null
        if (camera == Camera.moved) {
            event = close("cameraMoved", time)
            // A later ready measurement cannot resurrect pre-movement pixels.
            // Keep this lower bound until explicit activation of a new source.
            reacquireAfter = time; reference = null
            return snapshot("cameraMoved", event = event)
        }
        val currentSupport = finiteSupport?.takeIf { value ->
            reacquireAfter?.let { compare(value.sourceTime, it, zero) == 1 } ?: true
        }
        if (reference != currentSupport) {
            event = close("supportChanged", time); reference = currentSupport
        }
        if (reacquireAfter != null && currentSupport == null) {
            breakDwell(); return snapshot("supportReacquisitionRequired", event = event)
        }
        val previous = lastValid
        if (previous != null && compare(time, previous, outage) == 1)
            event = close("observationOutage", time) ?: event
        val sample = BilateralGripDisplacement.Sample.create(pose)
        val usable = camera == Camera.ready && sample != null && currentSupport != null &&
            currentSupport.imageSize == sample.imageSize && compare(time, currentSupport.sourceTime, zero)?.let { it >= 0 } == true &&
            currentSupport.context == CurvedDipRailAcquisition.Context(sample.bodyBackend, sample.bodyRevision, sample.handModel, sample.handBackend) &&
            listOf(sample.left, sample.right).zip(listOf(currentSupport.left, currentSupport.right)).all { (arm, rail) ->
                val short = min(sample.imageSize.width, sample.imageSize.height)
                val distances = listOf(rail.firstBoundary, rail.secondBoundary).map {
                    ObservedCurveGeometry.distance(it, arm.grip, sample.imageSize) ?: return@all false
                }
                distances.min() <= .12 * short
            }
        if (!usable) {
            breakDwell()
            return snapshot(if (camera != Camera.ready) "cameraUnavailable" else "observationUnavailable", event = event)
        }
        val continuous = lastValid?.let { compare(time, it, gap)?.let { value -> value <= 0 } } == true
        lastValid = time
        val arms = listOf(sample.left, sample.right)
        if (phase == Phase.seekingTop) {
            if (!sample.isStartingTop) {
                candidate = null; breakDwell(); return snapshot("waitingForMeasuredTop", event = event)
            }
            val old = candidate
            val stable = old != null && arms.zip(listOf(old.left, old.right)).all { (a, b) ->
                hypot(a.shoulder.x - b.shoulder.x, a.shoulder.y - b.shoulder.y) <= .08 * (b.projectedArmPixels ?: 0.0) &&
                    hypot(a.grip.x - b.grip.x, a.grip.y - b.grip.y) <= .02 * min(sample.imageSize.width, sample.imageSize.height)
            }
            if (!stable) { candidate = sample; breakDwell() }
            if (sustained("top", time, continuous && stable)) arm(sample)
            return snapshot(if (phase == Phase.ready) "ready" else "acquiringMeasuredTop", event = event)
        }
        val baseline = top ?: return snapshot("missingAnchor", event = close("missingAnchor", time) ?: event)
        val measurement = anchor?.measure(sample)
        if (measurement == null || !measurement.left.retainsGrip || !measurement.right.retainsGrip)
            return snapshot("gripOrContextChanged", event = close("gripOrContextChanged", time) ?: event)
        val baselineArms = listOf(baseline.left, baseline.right)
        val measured = listOf(measurement.left, measurement.right)
        val travel = measured.map { it.axialPixels }
        val required = measured.map { max(ExerciseCounter.minimumDipTravelArmFraction * it.baselineArmPixels,
            .02 * min(sample.imageSize.width, sample.imageSize.height)) }
        if (active() && travel.indices.any { i ->
                unconfirmedReturnTravel[i]?.let { travel[i] - it >= ExerciseCounter.dipDepartureTravelFraction * required[i] } == true
            }) return snapshot("returnNotSustained", event = close("returnNotSustained", time) ?: event)
        val armHeightReturned = travel.indices.map { i ->
            travel[i] <= min(ExerciseCounter.dipReturnTravelFraction * required[i], .08 * measured[i].baselineArmPixels)
        }
        val heightReturned = armHeightReturned.all { it }
        val returned = heightReturned && arms.indices.all { i ->
            arms[i].elbowDegrees?.let { it >= max(ExerciseCounter.minimumDipSupportDegrees,
                baselineArms[i].elbowDegrees!! - 20) } == true
        }
        if (phase == Phase.ready) {
            if (travel.indices.none { travel[it] >= ExerciseCounter.dipDepartureTravelFraction * required[it] })
                return snapshot("ready", event = event)
            // An asymmetric departure cannot substitute for bilateral travel.
            if (!travel.indices.all { travel[it] >= ExerciseCounter.dipDepartureTravelFraction * required[it] })
                return snapshot("waitingForBilateralTravel", event = event)
            phase = Phase.outbound; departureTime = time; breakDwell()
        }
        if (phase == Phase.outbound) {
            if (travel.indices.all { travel[it] >= required[it] }) {
                if (sustained("bottom", time, continuous)) {
                    bottomTime = endpointSince; phase = Phase.returning; breakDwell()
                }
            } else if (returned) {
                if (sustained("partialReturn", time, continuous)) {
                    event = close("returnedBeforeBilateralTravel", time, Outcome.partial)
                    if (sample.isStartingTop) arm(sample)
                }
            } else breakDwell()
        } else if (phase == Phase.returning) {
            if (returned) {
                if (sustained("return", time, continuous)) {
                    val completedReturnStart = endpointSince
                    event = close("bilateralImageCycleDepthFormAndLoadUnverified", time, Outcome.imageCycle)
                        ?.copy(firstReturnTime = completedReturnStart)
                    if (sample.isStartingTop) arm(sample)
                }
            } else breakDwell()
        }
        if (active()) travel.indices.forEach { i ->
            if (armHeightReturned[i]) unconfirmedReturnTravel[i] = min(unconfirmedReturnTravel[i] ?: travel[i], travel[i])
        }
        val fraction = travel.indices.minOf { (travel[it] / required[it]).coerceIn(0.0, 1.0) }
        val progress = when {
            event?.outcome == Outcome.imageCycle -> 1.0
            phase == Phase.returning -> .45 + .5 * (1 - travel.indices.maxOf { (travel[it] / required[it]).coerceIn(0.0, 1.0) })
            phase == Phase.outbound -> .45 * fraction
            else -> 0.0
        }
        return snapshot("available", progress, event)
    }
    private fun arm(sample: BilateralGripDisplacement.Sample) {
        val next = BilateralGripDisplacement.Anchor.create(sample) ?: return
        clear(); lastValid = sample.timestamp; top = sample; anchor = next; phase = Phase.ready
    }
    private fun sustained(kind: String, time: PresentationTime, continuous: Boolean): Boolean {
        if (!continuous || endpoint != kind) { endpoint = kind; endpointSince = time }
        return endpointSince?.let { compare(time, it, dwell)?.let { value -> value >= 0 } } == true
    }
    companion object {
        const val policyVersion = 1
        private val zero = SourceTimeLimit(0, 1)
        private val gap = SourceTimeLimit(7, 20)
        private val outage = SourceTimeLimit(3, 4)
        private val dwell = SourceTimeLimit(3, 25)
        private fun compare(end: PresentationTime, start: PresentationTime, limit: SourceTimeLimit) =
            SourceTimeComparison.compareElapsed(end, start, limit)
    }
}

/** Apple transport for independently qualified image-motion analysis only. */
class BilateralDipMotionBridge {
    private val engine = BilateralDipMotion()
    @Serializable private data class Input(val pose: PoseResult,
        val support: CurvedDipRailAcquisition.Reference?, val camera: BilateralDipMotion.Camera)
    fun resetSource() = engine.resetSource()
    @Throws(IllegalArgumentException::class)
    fun observeJson(input: String): String {
        val value = WorkoutRecordCodec.json.decodeFromString<Input>(input)
        return WorkoutRecordCodec.json.encodeToString(engine.observe(value.pose, value.support, value.camera))
    }
}
