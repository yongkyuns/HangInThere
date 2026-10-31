package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/**
 * Policy v9 provisional movement counter. All time is source presentation time.
 * Pull-ups retain each usable arm's hang baseline. Dips use a fixed retained grip
 * and frozen grip-to-shoulder axis, never the rail silhouette's direction.
 * This class is confined to one analysis owner; no camera/UI or form decisions.
 */
class ExerciseCounter(val exercise: Exercise = Exercise.pullUp, val side: ArmMeasurement.Side = ArmMeasurement.Side.left) {
    @Serializable enum class Exercise {
        pullUp, dip, pushUp;
        val title: String get() = when (this) { pullUp -> "Pull-up"; dip -> "Parallel-bar dip"; pushUp -> "Push-up" }
    }
    @Serializable enum class Phase {
        seekingStart, ready, outbound, returning, finished;
        val title: String get() = when (this) {
            seekingStart -> "Show extended starting position"
            ready -> "Ready"
            outbound -> "Moving toward bent-arm endpoint"
            returning -> "Return to extended position"
            finished -> "Sequence finished"
        }
    }
    @Serializable enum class Outcome { movement, partial, interrupted }
    @Serializable data class Event(val outcome: Outcome, val sourceSeconds: Double, val reason: String)
    @Serializable data class Summary(
        val policyVersion: Int, val exercise: Exercise, val side: ArmMeasurement.Side,
        val armTracking: String? = null,
        val phase: Phase, val observedMovements: Int, val partialAttempts: Int,
        val interruptedAttempts: Int, val trackingIssue: String? = null, val formVerification: String,
    )
    companion object {
        // v9 also prevents analytics completing a pull-up after positive hand
        // release while the independent automatic set boundary is confirming it.
        val barExercises = listOf(Exercise.pullUp, Exercise.dip)
        const val policyVersion = 9
        const val extendedDegrees = 155.0
        const val departureDegrees = 140.0
        const val bentDegrees = 100.0
        const val endpointDwellSeconds = 0.12
        const val maximumGapSeconds = 0.35
        const val maximumDipObservationOutageSeconds = 0.75
        const val minimumBarDistanceReductionFraction = 0.20
        const val minimumTravelImageFraction = 0.04
        // Relative support sanity gates, not anatomical depth/lockout criteria.
        const val minimumDipSupportDegrees = 120.0
        const val minimumDipDepartureDegrees = 15.0
        const val minimumDipBendExcursionDegrees = 30.0
        const val dipDepartureTravelFraction = 0.50
        const val dipReturnTravelFraction = 0.50
        const val minimumDipTravelArmFraction = 0.18
        const val dipTopToleranceArmFraction = 0.08
    }

    var phase: Phase = Phase.seekingStart; private set
    var observedMovements = 0; private set
    var partialAttempts = 0; private set
    var interruptedAttempts = 0; private set
    var trackingIssue: String? = null; private set
    var lastEvent: Event? = null; private set
    private val mutableEvents = mutableListOf<Event>()
    val events: List<Event> get() = mutableEvents.toList()
    private var lastTimestamp: PresentationTime? = null
    private val lastTime: Double? get() = lastTimestamp?.seconds
    private val pullUpAnchors = mutableMapOf<ArmMeasurement.Side, Sample>()
    private val pullUpExtendedSince = mutableMapOf<ArmMeasurement.Side, PresentationTime>()
    private var pullUpEndpointWitnesses = emptySet<ArmMeasurement.Side>()
    private var pullUpSamples = emptyMap<ArmMeasurement.Side, Sample>()
    private var dipSupportAxis: DipSupportMeasurement.Anchor? = null
    private var dipSample: DipSample? = null
    private var dipAnchor: DipSample? = null
    private var dipTopCandidate: DipSample? = null
    // Negative evidence only: a witnessed but unconfirmed return cannot be
    // followed by another descent inside the same completed-cycle record.
    private val dipUnconfirmedReturns = DipWitnesses()
    private val dipOutboundLows = DipWitnesses()
    private var dipBottom: DipSample? = null
    private var dipLastGeometryTime: PresentationTime? = null
    private var dipSupportCandidateSince: PresentationTime? = null
    private enum class Endpoint { extended, bent }
    private var endpoint: Endpoint? = null
    private var endpointSince: PresentationTime? = null
    private val endpointElapsedSeconds: Double get() = endpointSince?.let { lastTimestamp?.elapsedSeconds(it) } ?: 0.0
    private var activeAttempt = false
    private data class Sample(val degrees: Double, val shoulderToBarPixels: Double, val imageShortSide: Double)
    private data class DipSample(val degrees: Double?, val supportHeightPixels: Double,
        val imageShortSide: Double, val otherDegrees: Double?)
    /** Bounded frontier of actual samples, ordered by caller-supplied evidence
     * keys. Dominance never combines coordinates from different observations. */
    private class DipWitnesses {
        private data class Entry(val sample: DipSample, val height: Double, val angle: Double)
        private val entries = mutableListOf<Entry>()
        fun any(predicate: (DipSample) -> Boolean) = entries.any { predicate(it.sample) }
        fun clear() = entries.clear()
        fun retain(sample: DipSample, height: Double, angle: Double): Boolean {
            if (entries.any { it.height >= height && it.angle >= angle }) return true
            entries.removeAll { it.height <= height && it.angle <= angle }
            if (entries.size >= 64) return false
            entries.add(Entry(sample, height, angle))
            return true
        }
    }

    val armTracking: String get() = if (exercise == Exercise.pullUp) "bothUsableArms" else "selectedGripWithAvailableArms"

    /** Same current samples, anchors, and dwell as counting; reading cannot advance progress. */
    val pullUpProgress: PullUpProgress? get() {
        if (exercise != Exercise.pullUp) return null
        val number = if (phase == Phase.returning || phase == Phase.finished) observedMovements else observedMovements + 1
        fun snapshot(stage: PullUpProgress.Stage, cue: PullUpProgress.Cue,
            fraction: Double = 0.0, reset: Double = 0.0) =
            PullUpProgress(stage, number, fraction, reset, cue, pullUpSamples.size)
        if (phase == Phase.finished) return snapshot(PullUpProgress.Stage.finished, PullUpProgress.Cue.finished)
        trackingIssue?.let { issue ->
            val cue = when (issue) {
                "barReferenceUnavailable" -> PullUpProgress.Cue.setBar
                "armBaselineUnavailable", "sourceTimeGap", "nonIncreasingTimestamp", "invalidTimestamp" -> PullUpProgress.Cue.reacquireHang
                else -> PullUpProgress.Cue.showArms
            }
            return snapshot(PullUpProgress.Stage.paused, cue)
        }
        if (pullUpSamples.isEmpty()) return snapshot(PullUpProgress.Stage.startingHang, PullUpProgress.Cue.hang)
        val dwell = normalizedProgress(endpointElapsedSeconds / endpointDwellSeconds)
        val extensionFraction = pullUpSamples.values.minOfOrNull {
            normalizedProgress((it.degrees - bentDegrees) / (extendedDegrees - bentDegrees))
        } ?: 0.0
        val reset = min(0.99, 0.9 * extensionFraction + if (endpoint == Endpoint.extended) 0.1 * dwell else 0.0)
        if (phase == Phase.seekingStart) return snapshot(PullUpProgress.Stage.startingHang, PullUpProgress.Cue.hang, reset = reset)
        if (phase == Phase.returning) return snapshot(PullUpProgress.Stage.counted, PullUpProgress.Cue.lower, fraction = 1.0, reset = reset)
        if (phase == Phase.outbound && endpoint == Endpoint.extended)
            return snapshot(PullUpProgress.Stage.startingHang, PullUpProgress.Cue.hang, reset = reset)
        var elbowFraction = 1.0; var travelFraction = 1.0
        for ((side, sample) in pullUpSamples) {
            val anchor = pullUpAnchors[side] ?: return snapshot(PullUpProgress.Stage.startingHang, PullUpProgress.Cue.reacquireHang)
            elbowFraction = min(elbowFraction, normalizedProgress((extendedDegrees - sample.degrees) / (extendedDegrees - bentDegrees)))
            travelFraction = min(travelFraction, normalizedProgress((anchor.shoulderToBarPixels - sample.shoulderToBarPixels) /
                requiredTravel(anchor.shoulderToBarPixels, anchor.imageShortSide)))
        }
        val geometry = min(elbowFraction, travelFraction)
        val fraction = min(0.99, 0.9 * geometry + if (endpoint == Endpoint.bent) 0.1 * dwell else 0.0)
        if (phase == Phase.ready && pullUpSamples.values.all { it.degrees >= extendedDegrees })
            return snapshot(PullUpProgress.Stage.ready, PullUpProgress.Cue.pull)
        if (endpoint == Endpoint.bent) return snapshot(PullUpProgress.Stage.holdingTop, PullUpProgress.Cue.hold, fraction)
        return snapshot(PullUpProgress.Stage.pulling,
            if (travelFraction < elbowFraction) PullUpProgress.Cue.pullHigher else PullUpProgress.Cue.finishPull, fraction)
    }

    val dipProgress: DipProgress? get() {
        if (exercise != Exercise.dip) return null
        val sample = dipSample
        val armCount = sample?.let { (if (it.degrees == null) 0 else 1) + (if (it.otherDegrees == null) 0 else 1) } ?: 0
        fun snapshot(stage: DipProgress.Stage, cue: DipProgress.Cue,
            fraction: Double = 0.0, number: Int = observedMovements + 1) = DipProgress(stage, number, fraction, cue, armCount)
        if (phase == Phase.finished) return snapshot(DipProgress.Stage.finished, DipProgress.Cue.finished, number = observedMovements)
        trackingIssue?.let { issue ->
            val cue = when (issue) {
                "barReferenceUnavailable" -> DipProgress.Cue.setRail
                "dipGripMoved" -> DipProgress.Cue.reestablishGrip
                "dipSupportNeeded", "dipArmBaselineUnavailable", "returnNotSustained", "dipReturnUnresolved", "sourceTimeGap", "nonIncreasingTimestamp", "invalidTimestamp" -> DipProgress.Cue.reacquireSupport
                else -> DipProgress.Cue.showArms
            }
            return snapshot(DipProgress.Stage.paused, cue)
        }
        // Earned events take precedence over a briefly hidden elbow on the counted frame.
        val event = lastEvent
        if (phase == Phase.ready && event?.outcome == Outcome.movement && event.sourceSeconds == lastTime)
            return snapshot(DipProgress.Stage.counted, DipProgress.Cue.readyNext, 1.0, observedMovements)
        val anchor = dipAnchor
        val degrees = sample?.degrees
        val topDegrees = anchor?.degrees
        if (phase == Phase.seekingStart || sample == null || anchor == null || degrees == null || topDegrees == null)
            return snapshot(DipProgress.Stage.startingSupport, DipProgress.Cue.support)
        val dwell = normalizedProgress(endpointElapsedSeconds / endpointDwellSeconds)
        val required = requiredDipTravel(anchor)
        val descentTravel = normalizedProgress((anchor.supportHeightPixels - sample.supportHeightPixels) / required)
        fun bendFraction(angle: Double, top: Double): Double {
            val target = max(bentDegrees, top - minimumDipBendExcursionDegrees)
            return normalizedProgress((top - angle) / (top - target))
        }
        var bend = bendFraction(degrees, topDegrees)
        if (sample.otherDegrees != null && anchor.otherDegrees != null)
            bend = min(bend, bendFraction(sample.otherDegrees, anchor.otherDegrees))
        val tolerance = dipTopTolerance(sample)
        if (phase == Phase.ready) {
            val time = lastTime
            if (event?.outcome == Outcome.movement && time != null && time - event.sourceSeconds <= DipProgress.countConfirmationSeconds &&
                anchor.supportHeightPixels - sample.supportHeightPixels <= tolerance && degrees >= max(minimumDipSupportDegrees, topDegrees - 20))
                return snapshot(DipProgress.Stage.counted, DipProgress.Cue.readyNext, 1.0, observedMovements)
            if (descentTravel == 0.0 || bend == 0.0) return snapshot(DipProgress.Stage.ready, DipProgress.Cue.lower)
        }
        val bottom = dipBottom
        if (phase == Phase.returning && bottom?.degrees != null) {
            val targetHeight = max(anchor.supportHeightPixels - tolerance, bottom.supportHeightPixels + dipReturnTravelFraction * required)
            val recovery = normalizedProgress((sample.supportHeightPixels - bottom.supportHeightPixels) / (targetHeight - bottom.supportHeightPixels))
            fun extensionFraction(angle: Double, bottom: Double, top: Double): Double {
                val target = max(minimumDipSupportDegrees, top - 20)
                return normalizedProgress((angle - bottom) / max(1.0, target - bottom))
            }
            var extensionProgress = extensionFraction(degrees, bottom.degrees, topDegrees)
            if (sample.otherDegrees != null && anchor.otherDegrees != null)
                extensionProgress = min(extensionProgress, extensionFraction(sample.otherDegrees,
                    bottom.otherDegrees ?: min(bentDegrees, anchor.otherDegrees - 30), anchor.otherDegrees))
            val geometry = min(recovery, extensionProgress)
            val fraction = min(0.99, 0.5 + 0.4 * geometry + if (endpoint == Endpoint.extended) 0.09 * dwell else 0.0)
            if (endpoint == Endpoint.extended) return snapshot(DipProgress.Stage.holdingTop, DipProgress.Cue.hold, fraction)
            return snapshot(DipProgress.Stage.rising, if (recovery <= extensionProgress) DipProgress.Cue.rise else DipProgress.Cue.extendArms, fraction)
        }
        if (phase == Phase.outbound && endpoint == Endpoint.extended)
            return snapshot(DipProgress.Stage.startingSupport, DipProgress.Cue.support)
        val fraction = min(0.49, 0.45 * min(descentTravel, bend) + if (endpoint == Endpoint.bent) 0.05 * dwell else 0.0)
        if (endpoint == Endpoint.bent) return snapshot(DipProgress.Stage.holdingBottom, DipProgress.Cue.hold, fraction)
        return snapshot(DipProgress.Stage.lowering, if (descentTravel < bend) DipProgress.Cue.lowerFurther else DipProgress.Cue.bendArms, fraction)
    }

    val summary: Summary get() = Summary(policyVersion, exercise, side, armTracking, phase, observedMovements,
        partialAttempts, interruptedAttempts, trackingIssue, "unverified")

    fun reset() {
        interrupt()
        phase = Phase.seekingStart; observedMovements = 0; partialAttempts = 0; interruptedAttempts = 0
        trackingIssue = null; lastEvent = null; mutableEvents.clear(); lastTimestamp = null
        clearEvidence()
    }

    fun consume(pose: PoseResult, referenceEdge: BarSegment? = null): Event? {
        if (exercise == Exercise.pushUp) { interrupt("unsupportedExercise"); return null }
        if (phase == Phase.finished) return null
        val time = pose.timestamp
        if (!time.seconds.isFinite()) return interrupt("invalidTimestamp")
        // Subtract source ticks before converting to seconds. Absolute Double
        // timestamps lose exact dwell/gap boundaries and can alias fresh frames.
        val gap = lastTimestamp?.let { time.elapsedSeconds(it) }
        if (gap != null && !gap.isFinite()) return interrupt("invalidTimestamp")
        if (gap != null && gap <= 0) return interrupt("nonIncreasingTimestamp")
        lastTimestamp = time
        var event: Event? = null
        if (gap != null && gap > maximumGapSeconds) event = interrupt("sourceTimeGap")
        if (referenceEdge == null || !referenceEdge.isValid || !pose.imageSize.isValid ||
            referenceEdge.a.x !in 0.0..pose.imageSize.width || referenceEdge.a.y !in 0.0..pose.imageSize.height ||
            referenceEdge.b.x !in 0.0..pose.imageSize.width || referenceEdge.b.y !in 0.0..pose.imageSize.height)
            return interrupt("barReferenceUnavailable") ?: event
        return (if (exercise == Exercise.dip) consumeDip(pose, referenceEdge, time) else consumePullUp(pose, referenceEdge, time)) ?: event
    }

    private fun consumePullUp(pose: PoseResult, referenceEdge: BarSegment, time: PresentationTime): Event? {
        val measurement = PullUpArmMeasurement(pose)
        if (measurement.arms.isEmpty()) return interrupt(measurement.unavailableReason?.name ?: "unusableArm")
        val shortSide = min(pose.imageSize.width, pose.imageSize.height)
        val samples = mutableMapOf<ArmMeasurement.Side, Sample>()
        for (arm in measurement.arms) {
            val distance = referenceEdge.perpendicularDistance(arm.shoulder)
            if (!distance.isFinite() || !shortSide.isFinite() || shortSide <= 0) return interrupt("invalidBarGeometry")
            samples[arm.side] = Sample(arm.estimate.elbowDegrees, distance, shortSide)
        }
        val witnesses = samples.keys.toSet()
        pullUpSamples = samples
        val extended = samples.values.all { it.degrees >= extendedDegrees }
        for (side in ArmMeasurement.Side.entries) {
            val sample = samples[side]
            if (sample != null && sample.degrees >= extendedDegrees) {
                if (pullUpExtendedSince[side] == null) pullUpExtendedSince[side] = time
            } else pullUpExtendedSince.remove(side)
        }
        if (extended || phase == Phase.seekingStart || phase == Phase.ready || phase == Phase.returning) {
            for ((side, sample) in samples) {
                val since = pullUpExtendedSince[side]
                if (since != null && time.elapsedSeconds(since) >= endpointDwellSeconds) pullUpAnchors[side] = sample
            }
        }
        if (phase != Phase.seekingStart && !extended && samples.keys.any { pullUpAnchors[it] == null })
            return interrupt("armBaselineUnavailable")
        trackingIssue = null
        val bent = samples.all { (side, sample) -> reachedBentEndpoint(sample, side) }
        when (phase) {
            Phase.seekingStart -> if (sustainedPullUp(if (extended) Endpoint.extended else null, witnesses, time)) armPullUp()
            Phase.ready -> if (!extended && samples.values.all { it.degrees < departureDegrees }) {
                activeAttempt = true; phase = Phase.outbound; endpointSince = null
                sustainedPullUp(if (bent) Endpoint.bent else null, witnesses, time)
            }
            Phase.outbound -> {
                if (extended) {
                    if (sustainedPullUp(Endpoint.extended, witnesses, time)) {
                        val event = record(Outcome.partial, time.seconds, "returnedBeforeBentEndpoint")
                        armPullUp(); return event
                    }
                } else if (sustainedPullUp(if (bent) Endpoint.bent else null, witnesses, time)) {
                    phase = Phase.returning; endpointSince = null
                    val event = record(Outcome.movement, time.seconds, "barReferencedTop;chinClearanceNotMeasured")
                    activeAttempt = false; return event
                }
            }
            Phase.returning -> if (sustainedPullUp(if (extended) Endpoint.extended else null, witnesses, time)) armPullUp()
            Phase.finished -> Unit
        }
        return null
    }

    /** Disjoint left/right endpoint glimpses cannot be stitched into continuous dwell. */
    private fun sustainedPullUp(next: Endpoint?, witnesses: Set<ArmMeasurement.Side>, time: PresentationTime): Boolean {
        if (next == null) pullUpEndpointWitnesses = emptySet()
        else if (next != endpoint || endpointSince == null) pullUpEndpointWitnesses = witnesses
        else {
            val continuous = pullUpEndpointWitnesses.intersect(witnesses)
            if (continuous.isEmpty()) { endpointSince = null; pullUpEndpointWitnesses = witnesses }
            else pullUpEndpointWitnesses = continuous
        }
        return sustained(next, time)
    }

    private fun consumeDip(pose: PoseResult, referenceEdge: BarSegment, time: PresentationTime): Event? {
        if (pose.people.size > 1) return interrupt("multiplePeople")
        if (pose.people.isEmpty()) return dipObservationUnavailable("noPerson", time)
        val support = DipSupportMeasurement.create(pose, side, referenceEdge) ?: return dipObservationUnavailable("dipSupportUnclear", time)
        // Shoulders/wrists can outlast the initial elbow witness. An expired
        // candidate must reacquire extension and its grip axis, not remain stuck.
        if (phase == Phase.seekingStart && dipSupportCandidateSince?.let {
                time.elapsedSeconds(it) > maximumDipObservationOutageSeconds
            } == true) interrupt("dipSupportNeeded")
        if (dipSupportAxis == null) {
            val axis = DipSupportMeasurement.Anchor.create(support)
            if (!support.isStartingSupport || axis == null) { trackingIssue = "dipSupportNeeded"; return null }
            dipSupportAxis = axis
        }
        val axis = dipSupportAxis ?: return interrupt("dipGripMoved")
        if (!axis.retainsGrip(support)) return interrupt("dipGripMoved")
        val sample = DipSample(support.degrees, axis.height(support.shoulder),
            min(pose.imageSize.width, pose.imageSize.height), support.otherDegrees)
        dipSample = sample; dipLastGeometryTime = time
        trackingIssue = if (support.degrees == null) "lowConfidence" else null
        if (sample.otherDegrees != null) {
            if (phase == Phase.seekingStart && dipTopCandidate?.otherDegrees == null) {
                // A new arm cannot borrow time from an earlier single-arm hold.
                if (!support.isStartingSupport) return interrupt("dipArmBaselineUnavailable")
                dipTopCandidate = null; dipSupportCandidateSince = null
            } else if (phase != Phase.seekingStart && dipAnchor?.otherDegrees == null) {
                // Its first bottom/return observation is not a top baseline.
                // Reacquire one actual coherent support before the next cycle.
                val interrupted = interrupt("dipArmBaselineUnavailable")
                // Interruption guarantees seekingStart. One re-entry can seed
                // fresh support from this actual frame, never restore a cycle.
                consumeDip(pose, referenceEdge, time)
                return interrupted
            }
        }
        if ((phase == Phase.outbound || phase == Phase.returning) &&
                dipUnconfirmedReturns.any { dipHasDeparted(sample, it) }) return interrupt("returnNotSustained")
        when (phase) {
            Phase.seekingStart -> {
                if (!isDipSupportLike(sample)) {
                    if (sample.degrees != null) { dipTopCandidate = null; dipSupportCandidateSince = null }
                    sustained(null, time); return null
                }
                dipTopCandidate = mergedDipTopCandidate(dipTopCandidate, sample)
                val since = dipSupportCandidateSince
                if (since == null) dipSupportCandidateSince = time
                else if (time.elapsedSeconds(since) >= endpointDwellSeconds && time.elapsedSeconds(since) <= maximumDipObservationOutageSeconds)
                    armDip(dipTopCandidate ?: sample)
            }
            Phase.ready -> {
                var anchor = dipAnchor ?: return interrupt("missingDipAnchor")
                // Improve the witnessed top, never drag it down during descent.
                // armDip establishes a fresh local top after each completed cycle.
                if (sample.degrees != null && sample.degrees >= extendedDegrees) {
                    anchor = DipSample(maxDipDegrees(anchor.degrees, sample.degrees), max(anchor.supportHeightPixels,sample.supportHeightPixels), sample.imageShortSide,
                        maxDipDegrees(anchor.otherDegrees, sample.otherDegrees))
                    dipAnchor = anchor
                } else if (sample.supportHeightPixels > anchor.supportHeightPixels) {
                    anchor = DipSample(maxDipDegrees(anchor.degrees, sample.degrees), sample.supportHeightPixels,
                        sample.imageShortSide, maxDipDegrees(anchor.otherDegrees, sample.otherDegrees))
                    dipAnchor = anchor
                } else if (isDipSupportLike(sample) && sample.degrees != null && anchor.degrees != null && sample.degrees > anchor.degrees!!) {
                    anchor = DipSample(sample.degrees, anchor.supportHeightPixels, anchor.imageShortSide,
                        maxDipDegrees(anchor.otherDegrees, sample.otherDegrees))
                    dipAnchor = anchor
                }
                // An obtuse projected elbow can still descend from its acquired
                // top. The support-like acquisition check must not mask motion.
                if (dipHasDeparted(sample, anchor)) {
                    activeAttempt = true; phase = Phase.outbound; endpoint = null; endpointSince = null
                    dipTopCandidate = null; dipBottom = null
                    dipOutboundLows.clear()
                    if (!retainDipWitness(sample, upper = false)) return interrupt("dipReturnUnresolved")
                    sustained(if (dipReachedBentEndpoint(sample, anchor)) Endpoint.bent else null, time)
                }
            }
            Phase.outbound -> {
                val anchor = dipAnchor ?: return interrupt("missingDipAnchor")
                if (dipReturnToAnchorEvidence(sample, anchor)) {
                    if (sustained(Endpoint.extended, time)) {
                        val event = record(Outcome.partial, time.seconds, "returnedBeforeBentEndpoint")
                        armDip(dipTopCandidate ?: anchor); return event
                    }
                    // Departure and partial-return height gates touch. A
                    // plateau there is not an ascent from an observed low.
                    if (dipOutboundLows.any { dipHasDeparted(it, sample) } && !retainDipWitness(sample))
                        return interrupt("dipReturnUnresolved")
                } else if (sustained(if (dipReachedBentEndpoint(sample, anchor)) Endpoint.bent else null, time)) {
                    phase = Phase.returning; dipBottom = sample; endpoint = null; endpointSince = null; dipTopCandidate = null
                    dipOutboundLows.clear()
                }
                if (phase == Phase.outbound && !retainDipWitness(sample, upper = false)) return interrupt("dipReturnUnresolved")
            }
            Phase.returning -> {
                val anchor = dipAnchor ?: return interrupt("missingDipCycleAnchor")
                var bottom = dipBottom ?: return interrupt("missingDipCycleAnchor")
                if (sample.degrees != null && sample.supportHeightPixels < bottom.supportHeightPixels) {
                    dipBottom = sample; bottom = sample
                }
                if (dipReturnFromBottomEvidence(sample, bottom, anchor)) {
                    if (sustained(Endpoint.extended, time)) {
                        val event = record(Outcome.movement, time.seconds, "barReferencedCycle;dipDepthAndFormNotQualified")
                        armDip(dipTopCandidate ?: sample); return event
                    }
                    if (!retainDipWitness(sample)) return interrupt("dipReturnUnresolved")
                } else sustained(null, time)
            }
            Phase.finished -> Unit
        }
        return null
    }

    private fun reachedBentEndpoint(sample: Sample, side: ArmMeasurement.Side): Boolean {
        val anchor = pullUpAnchors[side] ?: return false
        val required = requiredTravel(anchor.shoulderToBarPixels, anchor.imageShortSide)
        return sample.degrees <= bentDegrees && anchor.shoulderToBarPixels - sample.shoulderToBarPixels >= required
    }
    private fun requiredTravel(anchorDistance: Double, imageShortSide: Double): Double =
        max(minimumBarDistanceReductionFraction * anchorDistance, minimumTravelImageFraction * imageShortSide)
    private fun requiredDipTravel(sample: DipSample): Double =
        max(minimumDipTravelArmFraction * (dipSupportAxis?.armLength ?: 0.0), 0.02 * sample.imageShortSide)
    private fun maxDipDegrees(a: Double?, b: Double?): Double? = when {
        a != null && b != null -> max(a, b)
        a != null -> a
        else -> b
    }
    private fun mergedDipTopCandidate(current: DipSample?, sample: DipSample): DipSample =
        if (current == null) sample else DipSample(maxDipDegrees(current.degrees, sample.degrees),
            max(current.supportHeightPixels, sample.supportHeightPixels), sample.imageShortSide,
            maxDipDegrees(current.otherDegrees, sample.otherDegrees))
    private fun isDipSupportLike(sample: DipSample): Boolean = sample.degrees?.let { it >= minimumDipSupportDegrees } ?: false
    private fun dipHasDeparted(sample: DipSample, anchor: DipSample): Boolean {
        val degrees = sample.degrees ?: return false; val anchorDegrees = anchor.degrees ?: return false
        val travel = anchor.supportHeightPixels - sample.supportHeightPixels
        return travel >= dipDepartureTravelFraction * requiredDipTravel(anchor) && anchorDegrees - degrees >= minimumDipDepartureDegrees
    }
    /** Both histories can only invalidate a merged attempt, never supply dwell
     * or replace the confirmed bottom. Missing angles cannot create witnesses. */
    private fun retainDipWitness(sample: DipSample, upper: Boolean = true): Boolean {
        val degrees = sample.degrees ?: return true
        return if (upper) dipUnconfirmedReturns.retain(sample,
            sample.supportHeightPixels - dipDepartureTravelFraction * requiredDipTravel(sample), degrees)
        else dipOutboundLows.retain(sample, -sample.supportHeightPixels, -degrees)
    }
    private fun dipReachedBentEndpoint(sample: DipSample, anchor: DipSample): Boolean {
        val degrees = sample.degrees ?: return false; val anchorDegrees = anchor.degrees ?: return false
        val travel = anchor.supportHeightPixels - sample.supportHeightPixels
        val otherBent = if (sample.otherDegrees != null && anchor.otherDegrees != null)
            sample.otherDegrees <= bentDegrees || anchor.otherDegrees - sample.otherDegrees >= minimumDipBendExcursionDegrees else true
        return otherBent && travel >= requiredDipTravel(anchor) &&
            (degrees <= bentDegrees || anchorDegrees - degrees >= minimumDipBendExcursionDegrees)
    }
    private fun dipReturnToAnchorEvidence(sample: DipSample, anchor: DipSample): Boolean {
        val recovered = anchor.supportHeightPixels - sample.supportHeightPixels <= dipReturnTravelFraction * requiredDipTravel(anchor)
        if (!recovered) { dipTopCandidate = null; return false }
        if (isDipSupportLike(sample)) { dipTopCandidate = mergedDipTopCandidate(dipTopCandidate, sample); return true }
        // Missing angles can sustain an already witnessed top, never acquire one.
        if (sample.degrees == null && dipTopCandidate != null) {
            dipTopCandidate = mergedDipTopCandidate(dipTopCandidate, sample); return true
        }
        // Visible flexion contradicts the prior top; a later missing elbow
        // cannot reuse that witness to complete a new endpoint dwell.
        dipTopCandidate = null
        return false
    }
    private fun dipReturnFromBottomEvidence(sample: DipSample, bottom: DipSample, cycleAnchor: DipSample): Boolean {
        if (cycleAnchor.supportHeightPixels - sample.supportHeightPixels > dipTopTolerance(sample) ||
            sample.supportHeightPixels - bottom.supportHeightPixels < dipReturnTravelFraction * requiredDipTravel(cycleAnchor)) {
            dipTopCandidate = null; return false
        }
        if (sample.otherDegrees != null && cycleAnchor.otherDegrees != null &&
            sample.otherDegrees < max(minimumDipSupportDegrees, cycleAnchor.otherDegrees - 20)) {
            dipTopCandidate = null; return false
        }
        if (sample.degrees != null && cycleAnchor.degrees != null && sample.degrees >= max(minimumDipSupportDegrees, cycleAnchor.degrees - 20)) {
            dipTopCandidate = mergedDipTopCandidate(dipTopCandidate, sample); return true
        }
        if (sample.degrees == null && dipTopCandidate != null) {
            dipTopCandidate = mergedDipTopCandidate(dipTopCandidate, sample); return true
        }
        dipTopCandidate = null
        return false
    }
    private fun dipObservationUnavailable(reason: String, time: PresentationTime): Event? {
        trackingIssue = reason; dipSample = null; endpoint = null; endpointSince = null
        val lastGeometry = dipLastGeometryTime ?: return null
        if (time.elapsedSeconds(lastGeometry) <= maximumDipObservationOutageSeconds) return null
        dipTopCandidate = null; dipSupportCandidateSince = null
        return interrupt(reason)
    }
    private fun dipTopTolerance(sample: DipSample): Double =
        max(dipTopToleranceArmFraction * (dipSupportAxis?.armLength ?: 0.0), 0.01 * sample.imageShortSide)
    private fun sustained(next: Endpoint?, time: PresentationTime): Boolean {
        if (next == null) { endpoint = null; endpointSince = null; return false }
        val since = endpointSince
        if (endpoint != next || since == null) { endpoint = next; endpointSince = time; return false }
        return time.elapsedSeconds(since) >= endpointDwellSeconds
    }
    private fun armPullUp() {
        phase = Phase.ready; endpoint = null; endpointSince = null; pullUpEndpointWitnesses = emptySet(); activeAttempt = false
    }
    private fun armDip(sample: DipSample) {
        phase = Phase.ready; dipAnchor = sample; dipTopCandidate = null; dipBottom = null; dipSupportCandidateSince = null
        dipUnconfirmedReturns.clear()
        dipOutboundLows.clear()
        endpoint = null; endpointSince = null; activeAttempt = false; trackingIssue = null
    }
    private fun clearEvidence() {
        pullUpAnchors.clear(); pullUpExtendedSince.clear(); pullUpEndpointWitnesses = emptySet()
        dipSupportAxis = null; dipSample = null; dipAnchor = null; pullUpSamples = emptyMap()
        dipTopCandidate = null; dipBottom = null; dipSupportCandidateSince = null; dipLastGeometryTime = null
        dipUnconfirmedReturns.clear()
        dipOutboundLows.clear()
        endpoint = null; endpointSince = null; activeAttempt = false
    }
    fun interrupt(reason: String = "inferenceFailure"): Event? {
        if (phase == Phase.finished) return null
        val event = if (activeAttempt) record(Outcome.interrupted, lastTime ?: 0.0, reason) else null
        phase = Phase.seekingStart; clearEvidence(); trackingIssue = reason
        return event
    }
    fun finish(): Event? {
        if (phase == Phase.finished) return null
        val event = interrupt("endOfInput")
        phase = Phase.finished; trackingIssue = null
        return event
    }
    private fun record(outcome: Outcome, time: Double, reason: String): Event {
        when (outcome) {
            Outcome.movement -> observedMovements++
            Outcome.partial -> partialAttempts++
            Outcome.interrupted -> interruptedAttempts++
        }
        val event = Event(outcome, time, reason)
        lastEvent = event; mutableEvents += event
        return event
    }
}
