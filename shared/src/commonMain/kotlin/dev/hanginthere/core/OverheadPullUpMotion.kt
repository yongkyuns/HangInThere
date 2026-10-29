package dev.hanginthere.core


import kotlinx.serialization.Serializable
import kotlin.math.*

/** Source-owned image cycles from actual overhead support and two measured arms
 * with corroborated world/relative shoulder motion. No BarSegment,
 * image-contact classifier, anatomical lockout or grip metadata input.
 * Camera readiness remains an independent caller verdict; pose never proves it.
 * Caller-owned independent camera qualification is mandatory. */
class OverheadPullUpMotion {
    @Serializable enum class Camera { ready, unavailable, moved }
    @Serializable enum class Phase { idle, ready, outbound, returning }
    @Serializable data class Event(val outcome: String, val reason: String, val seconds: Double,
        val setKey: String?, val departure: Double?, val peak: Double?, val lowerTurn: Double?,
        val witnessSlots: List<Int> = emptyList(), val observedCompletion: Double? = null,
        val startWitnessAt: Double? = null)
    @Serializable data class Snapshot(val seconds: Double?, val phase: Phase, val cycles: Int,
        val reason: String, val travel: List<Double> = emptyList(), val drift: List<Double> = emptyList(),
        val degrees: List<Double> = emptyList(), val event: Event? = null,
        val observationUsable: Boolean = false, val sourceAccepted: Boolean = true,
        val partialAttempts: Int = 0, val interruptedAttempts: Int = 0,
        val progress: Double = 0.0, val countMethod: String = OverheadPullUpMotion.countMethod, val policyVersion: Int = OverheadPullUpMotion.policyVersion,
        val supportWitnessSlots: List<Int> = emptyList(), val peakWitnessSlots: List<Int> = emptyList(),
        val revokedArmSlots: List<Int> = emptyList(), val verticalSupportDrift: List<Double> = emptyList(),
        val events: List<Event> = emptyList())
    internal data class Arm(val shoulder: Point2D, val wrist: Point2D, val degrees: Double, val length: Double) {
        val height get() = shoulder.y - wrist.y
    }
    internal data class Sample(val time: PresentationTime, val size: ImageSize, val backend: String,
        val revision: Int, val arms: List<Arm>, val overheadReleaseWitness: Boolean)
    internal data class CompletedEvidence(val event: Event,val support: Sample,val start: Sample)
    internal data class AnalysisStep(val time: PresentationTime,val sample: Sample?,val phase: Phase,
        val usable: Boolean,val event: Event?)
    private val deliverySteps=mutableListOf<AnalysisStep>()
    internal val analysisSteps: List<AnalysisStep> get()=deliverySteps.toList()
    private val completionEvidence = mutableListOf<CompletedEvidence>()
    internal val completedEvidence: List<CompletedEvidence> get() = completionEvidence.toList()
    private data class LowerRecovery(val support: Sample,val lower: Sample,var previousLower: Sample? = null)
    private var lowerRecovery: LowerRecovery? = null
    private var phase = Phase.idle
    private var candidate: Sample? = null
    private val calibrationSamples = mutableListOf<Sample>()
    internal var observedSetStart: PresentationTime? = null; private set
    private var startWitness: Sample? = null
    private var refinesSupport = true
    private var generation: Long? = null
    private var lastGeometry: Companion.Geometry? = null
    internal var currentSample: Sample? = null; private set
    internal val measuredSupport get() = support
    private var cycles = 0
    private var partials = 0
    private var interruptions = 0
    private var highWater: PresentationTime? = null
    private var lastUsable: PresentationTime? = null
    private var setKey: String? = null
    private var support: Sample? = null
    private val revokedArmSlots = mutableSetOf<Int>()
    private var peakWitnesses = emptySet<Int>()
    private var lower: Sample? = null
    private var peak: Sample? = null
    private var returnLow: Sample? = null
    private var departure: Double? = null
    private var turnSince: PresentationTime? = null
    private var lastMeasured: Sample? = null
    val hasObservedExcursion get() = peak != null || phase == Phase.returning || cycles > 0

    private fun clear() {
        lowerRecovery = null
        phase = Phase.idle; candidate = null; refinesSupport = true; lastGeometry = null; support = null; lower = null; peak = null; returnLow = null
        calibrationSamples.clear(); observedSetStart = null; startWitness = null
        departure = null; turnSince = null; lastMeasured = null; lastUsable = null
        revokedArmSlots.clear(); peakWitnesses = emptySet()
    }
    private fun active() = phase == Phase.outbound || phase == Phase.returning
    private fun cancel(reason: String, time: Double,preserveLower: Boolean = false): Event? {
        // Old measurements constrain requalification; never movement credit.
        val retained = if(preserveLower) lowerRecovery ?: support?.let { held -> lower?.let { LowerRecovery(held,it) } } else null

        val event = if (active()) Event("interrupted", reason, time, setKey, departure, peak?.time?.seconds, null,peakWitnesses.sorted()) else null
        if (event != null) interruptions++
        clear(); lowerRecovery = retained?.copy(previousLower=null); return event
    }
    /** Cancels evidence without forgetting the monotonic source fence. */
    fun interrupt(reason: String) = cancel(reason, highWater?.seconds ?: 0.0)
    private fun arm(current: Sample) {
        phase = Phase.ready; lower = current; peak = null; returnLow = null
        peakWitnesses = emptySet()
        departure = null; turnSince = null; lastMeasured = current
    }

    fun observe(selection: BodyAthleteSelection, activeSetKey: String?, cameraReady: Boolean,
        allowDeparture: Boolean = true): Snapshot {
        // Only a new public observation retires the prior delivery. A later
        // private reset in this call cannot erase an already completed cycle.
        completionEvidence.clear()
        deliverySteps.clear()
        val frame = checkNotNull(selection.frame)
        val pose = frame.pose
        val time = pose.timestamp
        val previous = highWater
        var event: Event? = null
        val confirmedEvents = mutableListOf<Event>()
        var sample: Sample? = null
        fun result(reason: String, travel: List<Double> = emptyList(), drift: List<Double> = emptyList()): Snapshot {
            currentSample = sample
            val usable = sample != null && cameraReady && usableReason(reason)
            if(reason!="sourceRegression" && time.seconds.isFinite()) {
                if(deliverySteps.isEmpty())deliverySteps.add(AnalysisStep(time,sample,phase,usable,event))
                else if(deliverySteps.last().time!=time)
                    deliverySteps.add(AnalysisStep(time,sample,phase,usable,null))
            }
            val progress = when {
                event?.outcome == "imageCycle" -> 1.0
                // The display may follow measured retreat before the counting
                // phase's reversal dwell is confirmed; this grants no rep evidence.
                phase == Phase.outbound -> max(
                    .45 * ((travel.minOrNull() ?: 0.0) / .18).coerceIn(0.0, 1.0), returnProgress(travel))
                phase == Phase.returning -> returnProgress(travel)
                else -> 0.0
            }
            return Snapshot(time.seconds.takeIf { it.isFinite() }, phase, cycles, reason, travel, drift,
                sample?.arms?.map { it.degrees } ?: emptyList(), event, usable, reason != "sourceRegression", partials, interruptions, progress,
                supportWitnessSlots=support?.arms?.indices?.filter { it !in revokedArmSlots } ?: emptyList(),
                peakWitnessSlots=peakWitnesses.sorted(), revokedArmSlots=revokedArmSlots.sorted(),
                verticalSupportDrift=support?.let { fixed -> sample?.arms?.mapIndexed { i,a ->
                    abs(a.wrist.y-fixed.arms[i].wrist.y)/fixed.arms[i].length
                } } ?: emptyList(),events=confirmedEvents.ifEmpty { listOfNotNull(event) })
        }
        if (!time.seconds.isFinite() || time.seconds < 0 ||
            previous?.let { compare(time, it, zero) != 1 } == true) {
            event = cancel("sourceRegression", previous?.seconds ?: 0.0)
            return result("sourceRegression")
        }
        highWater = time
        if (previous?.let { compare(time, it, gap) == 1 } == true) event = cancel("sourceTimeGap", time.seconds,preserveLower=true)
        if (setKey != activeSetKey) {
            event = cancel("setChanged", time.seconds) ?: event
            setKey = activeSetKey
        }
        if (generation != null && generation != frame.decision.sourceGeneration)
            event = cancel("athleteEpochChanged", time.seconds) ?: event
        generation = frame.decision.sourceGeneration
        if (activeSetKey == null || !cameraReady) {
            val reason = if (activeSetKey == null) "outsideAutomaticSet" else "cameraUnavailable"
            event = cancel(reason, time.seconds,preserveLower=reason=="cameraUnavailable") ?: event
            return result(reason)
        }
        val observedGeometry = if (frame.decision.status == BodyAthleteAssociation.Status.associated) geometry(pose) else null
        if (observedGeometry?.horizontal == true) {
            event = cancel("horizontalTorso",time.seconds) ?: event
            return result("horizontalTorso")
        }
        val recovering = lowerRecovery
        if(recovering != null && frame.decision.status == BodyAthleteAssociation.Status.associated && positiveRelease(pose,recovering.support)) {
            event = cancel("released",time.seconds) ?: event
            return result("released")
        }
        sample = if (frame.decision.status == BodyAthleteAssociation.Status.associated) sample(pose) else null
        if (sample != null && support != null && uncorroboratedCollapsedDeparture(pose,sample!!,support!!)) sample=null
        if (sample == null) {
            lowerRecovery?.previousLower = null
            // A collapsed SDK chain is not a measured movement or release.
            // Keep only the private attempt across a short outage; neither
            // turnaround nor endpoint dwell can bridge an unavailable frame.
            lastGeometry = null
            candidate = null; calibrationSamples.clear(); turnSince = null; lastMeasured = null
            // Fresh partial support can preserve an existing private attempt.
            // It cannot calibrate, initiate, refine a peak, or supply a return.
            val partialSupport = active() && peak != null && support?.let { partialSupport(pose,it) } == true
            if (partialSupport) { lastUsable = time; returnLow = null }
            if (lastUsable?.let { compare(time, it, gap) == 1 } == true)
                event = cancel("bodyObservationOutage", time.seconds,preserveLower=true) ?: event
            return result(if (partialSupport) "partialObservedSupport" else "bodyUnavailable")
        }
        // Expire the old attempt before using this fully observed frame for recovery.
        if (lastUsable?.let { compare(time, it, gap) == 1 } == true)
            event = cancel("bodyObservationOutage", time.seconds,preserveLower=true) ?: event
        lowerRecovery?.let { recovery ->
            val current = checkNotNull(sample)
            if(current.size != recovery.support.size || current.backend != recovery.support.backend || current.revision != recovery.support.revision) {
                event = cancel("contextChanged",time.seconds) ?: event
                return result("contextChanged")
            }
            fun freshLower(value: Sample) = value.arms.indices.all { i ->
                val length = recovery.support.arms[i].length
                distance(value.arms[i].wrist,recovery.support.arms[i].wrist) <= .12*length &&
                    value.arms[i].height >= recovery.lower.arms[i].height - .08*length &&
                    value.arms[i].shoulder.y >= recovery.lower.arms[i].shoulder.y - .08*length &&
                    value.arms[i].degrees >= recovery.lower.arms[i].degrees - 20
            }
            val qualifies = freshLower(current)
            val priorLower = recovery.previousLower
            lastGeometry = null; lastMeasured = null; turnSince = null; lastUsable = time
            if(qualifies && priorLower != null && freshLower(priorLower)) {
                // Only this fresh current frame becomes the new lower sample.
                // The old lower and previous frame are qualification references.
                lowerRecovery = null; support = current; arm(current); lastGeometry = observedGeometry
                return result("freshLowerRecovered")
            }
            recovery.previousLower = current.takeIf {qualifies}
            // Current full geometry remains visible to lifecycle/diagnostics,
            // while idle: it cannot become an excursion or completed endpoint.
            return result("waitingForFreshLower")
        }
        val priorGeometry = lastGeometry
        lastGeometry = observedGeometry
        if (priorGeometry != null && observedGeometry != null)
            departureReason(priorGeometry.wrists,observedGeometry.wrists)?.let { reason ->
                event=cancel(reason,time.seconds) ?: event
                return result(reason)
            }
        val current = sample!!
        val oldSupport = support
        if (oldSupport != null && (oldSupport.size != current.size || oldSupport.backend != current.backend || oldSupport.revision != current.revision)) {
            event = cancel("contextChanged", time.seconds) ?: event
            // A context change cannot supply a new lower endpoint on that frame.
            return result("contextChanged")
        }
        lastUsable = time
        val prior = lastMeasured
        lastMeasured = current
        if (phase == Phase.idle) {
            val initial = candidate
            if (initial == null || current.arms.indices.any { i ->
                    distance(current.arms[i].wrist, initial.arms[i].wrist) > .12 * initial.arms[i].length }) {
                calibrationSamples.clear()
                if (!current.arms.all { it.height >= .35 * it.length }) {
                    candidate = null
                    return result("waitingForOverheadLower")
                }
                candidate = current
                calibrationSamples.add(current)
                return result("calibratingOverheadHands")
            }
            // The initial actual lower establishes overhead posture. Shoulders
            // may rise through it during qualification; stable observed hands
            // retain support. No inferred path fills an unavailable sample.
            if(calibrationSamples.size>=256) {
                candidate=null;calibrationSamples.clear()
                return result("calibrationSampleLimit")
            }
            calibrationSamples.add(current)
            if (compare(time, initial.time, calibration)?.let { it >= 0 } != true)
                return result("calibratingOverheadHands")
            val observed=calibrationSamples.toList()
            candidate=null;calibrationSamples.clear()
            support=initial;arm(initial)
            deliverySteps.add(AnalysisStep(initial.time,initial,Phase.ready,true,null))
            var movement=Movement("ready",emptyList(),emptyList(),null)
            var previousActual: Sample=initial
            for(actual in observed.drop(1)) {
                // Preparation may refine support within the private history.
                // Apply the same departure rule against those actual anchors,
                // using adjacent actual frames rather than a saved lower turn.
                val loss=departureReason(previousActual.arms.map { it.wrist },actual.arms.map { it.wrist })
                if(loss!=null) {
                    val interrupted=cancel(loss,actual.time.seconds)
                    if(interrupted!=null)confirmedEvents.add(interrupted)
                    movement=Movement(loss,emptyList(),emptyList(),interrupted)
                    deliverySteps.add(AnalysisStep(actual.time,actual,phase,false,interrupted))
                    break
                }
                val preceding=lastMeasured;lastMeasured=actual
                movement=advance(actual,preceding,allowDeparture)
                deliverySteps.add(AnalysisStep(actual.time,actual,phase,usableReason(movement.reason),movement.event))
                previousActual=actual
                movement.event?.let { original ->
                    // Completion becomes public only once support is proven.
                    // Both the confirmation frame and observed movement time
                    // remain actual source frames; preserve every event.
                    confirmedEvents.add(if(original.outcome=="imageCycle")original.copy(seconds=time.seconds) else original)
                }
                if(phase==Phase.idle)break
            }
            event=confirmedEvents.lastOrNull() ?: event
            return result(movement.reason,movement.travel,movement.drift)
        }
        val movement = advance(current,prior,allowDeparture)
        event = movement.event ?: event
        return result(movement.reason,movement.travel,movement.drift)
    }

    private fun partialSupport(pose: PoseResult, held: Sample): Boolean {
        if (pose.imageSize != held.size || pose.backend != held.backend || pose.requestRevision != held.revision) return false
        val person = pose.people.singleOrNull() ?: return false
        val current = geometry(pose) ?: return false
        if (current.horizontal) return false
        val estimates = ArmMeasurement.Side.entries.map { ArmMeasurement(pose,it) }
        if (estimates.count { it.estimate != null } != 1) return false
        val missing = estimates.single { it.estimate == null }.unavailableReason
        if (missing !in setOf(ArmMeasurement.UnavailableReason.missingJoint,
                ArmMeasurement.UnavailableReason.lowConfidence,ArmMeasurement.UnavailableReason.shortProjectedSegment)) return false
        for (side in ArmMeasurement.Side.entries) for (joint in listOf(side.joints[0],side.joints[2])) {
            val point = person.landmark(joint) ?: return false
            if (!point.confidence.isFinite() || point.confidence !in ArmMeasurement.confidenceThreshold..1.0) return false
        }
        return current.wrists.indices.all { i -> distance(current.wrists[i],held.arms[i].wrist) <= .12*held.arms[i].length }
    }

    private fun uncorroboratedCollapsedDeparture(pose: PoseResult,current: Sample,held: Sample): Boolean {
        val person=pose.people.singleOrNull() ?: return true
        val chains=ArmMeasurement.Side.entries.map { side ->
            val estimate=ArmMeasurement(pose,side).estimate ?: return true
            val wrist=person.landmark(side.joints[2])?.position ?: return true
            wrist to (min(estimate.upperArmPixels,estimate.forearmPixels)<.2*max(estimate.upperArmPixels,estimate.forearmPixels))
        }.sortedBy {it.first.x}
        // A valid short projection at observed support stays usable. A newly
        // collapsed chain cannot corroborate movement away from that support.
        // No point is replaced; missing-frame clocks/endpoints stay unchanged.
        return current.arms.indices.any { i -> chains[i].second &&
            abs(current.arms[i].wrist.y-held.arms[i].wrist.y)>.12*held.arms[i].length
        }
    }

    private fun usableReason(reason: String)=reason !in listOf("sourceRegression","contextChanged","released",
        "overheadPostureLost","horizontalTorso","supportWitnessDeparture","peakSupportWitnessLost",
        "outsideAutomaticSet","athleteEpochChanged")
    private fun departureReason(prior: List<Point2D>,current: List<Point2D>): String? {
        val held=support ?: return null
        // In the near-level front/rear profile, translation along horizontal
        // support does not revoke its vertical witness. Keep Euclidean drift
        // diagnostic. Revocation requires two actual adjacent measurements.
        revokedArmSlots.addAll(current.indices.filter { i -> listOf(prior,current).all { wrists ->
            abs(wrists[i].y-held.arms[i].wrist.y)>.12*held.arms[i].length
        } })
        if(peakWitnesses.isNotEmpty()) {
            peakWitnesses=peakWitnesses-revokedArmSlots
            if(peakWitnesses.isEmpty())return "peakSupportWitnessLost"
        }
        return if(held.arms.indices.all { it in revokedArmSlots })"supportWitnessDeparture" else null
    }
    // Presentation only: the minimum accepted excursion is not this rep's range.
    // Use the same measured height coordinates as travel; never fill absent observations.
    private fun returnProgress(travel: List<Double>): Double {
        val base = lower ?: return 0.0
        val top = peak ?: return 0.0
        val fixed = support ?: return 0.0
        if (travel.size != 2) return 0.0
        val excursion = base.arms.indices.map { i ->
            (base.arms[i].height - top.arms[i].height) / fixed.arms[i].length
        }
        if (excursion.any { !it.isFinite() || it <= 0.0 }) return 0.0
        val remaining = travel.indices.maxOf { travel[it] / excursion[it] }.coerceIn(0.0, 1.0)
        return .5 + .45 * (1 - remaining)
    }

    private data class Movement(val reason: String,val travel: List<Double>,val drift: List<Double>,val event: Event?)
    /** Only current-context, actually observed samples enter this transition.
     * Calibration may replay its bounded private history; source/actor/camera
     * acceptance already happened once for every original sample. */
    private fun advance(current: Sample,prior: Sample?,allowDeparture: Boolean): Movement {
        val time=current.time
        var event: Event?=null
        fun result(reason: String,travel: List<Double> = emptyList(),drift: List<Double> = emptyList()) =
            Movement(reason,travel,drift,event)
        val fixed = support!!
        val base = lower!!
        val lengths = fixed.arms.map { it.length }
        val drift = current.arms.indices.map { i -> distance(current.arms[i].wrist, fixed.arms[i].wrist) / lengths[i] }
        // The initial measured lower witness establishes overhead support.
        // A high pull may raise shoulders through wrist height. Actual fixed
        // hands retain that support; only observed departure/source/camera
        // faults revoke it, not an anatomical height/form condition at the top.
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
        fun supportPair(a: Sample,b: Sample) = a.arms.indices.filter { i ->
            i !in revokedArmSlots && listOf(a,b).all { s -> abs(s.arms[i].wrist.y-fixed.arms[i].wrist.y) <= .12*lengths[i] }
        }.toSet()
        if (phase == Phase.ready) {
            if (!allowDeparture) {
                if (current.arms.all { it.height >= .35 * it.length }) arm(current) else clear()
                return result(if (phase == Phase.ready) "ready" else "waitingForOverheadLower", travel, drift)
            }
            // A genuinely lower preparatory hang can update the lower endpoint;
            // upward motion can never move the baseline toward the peak.
            if (current.arms.indices.all { i -> current.arms[i].height > base.arms[i].height && current.arms[i].degrees >= base.arms[i].degrees }) {
                // Standing/mount preparation can project much shorter arms.
                // Before first departure only, the actual deeper lower sample
                // also supplies its actual scale/hand anchors. Never rebase a
                // peak/return or follow hands during a committed excursion.
                if (refinesSupport) support = current
                lower = current
                return result("lowerPreparation", travel, drift)
            }
            if (!current.arms.indices.all { i -> travel[i] * lengths[i] >= required[i] * .5 &&
                    base.arms[i].shoulder.y - current.arms[i].shoulder.y >= required[i] * .5 && bend[i] >= 15 })
                return result("ready", travel, drift)
            refinesSupport = false
            if(observedSetStart==null) {observedSetStart=base.time;startWitness=base}
            phase = Phase.outbound; departure = time.seconds; peak = null
        }
        if (phase == Phase.outbound) {
            if (prior != null && raised(current) && raised(prior)) {
                val witnesses = supportPair(prior,current)
                // Each extreme has adjacent observed corroboration. Choose an
                // actual, less-extreme sample, never synthetic/averaged joints.
                val candidate = listOf(prior, current).maxBy { it.arms.sumOf { arm -> arm.height } }
                if (witnesses.isNotEmpty() &&
                    (peak == null || candidate.arms.sumOf { it.height } < peak!!.arms.sumOf { it.height })) {
                    peak = candidate
                    // A later, higher actual peak cannot add a new witness to
                    // an existing attempt after the original one disappeared.
                    peakWitnesses = if(peakWitnesses.isEmpty()) witnesses else peakWitnesses.intersect(witnesses)
                    if(peakWitnesses.isEmpty()) {
                        event = cancel("peakSupportWitnessLost",time.seconds) ?: event
                        return result("peakSupportWitnessLost",travel,drift)
                    }
                }
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
                partials++
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
            val witnessedReturn = prior != null && peakWitnesses.intersect(supportPair(prior,current)).isNotEmpty()
            if (prior != null && witnessedReturn && lowered(current) && lowered(prior)) {
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
            if (witnessedReturn && (lowerTurn || arrived)) {
                cycles++
                event = Event("imageCycle", if (lowerTurn) "observedLowerTurn" else "measuredLowerReturn", time.seconds,
                    setKey, departure, top.time.seconds, bottom!!.time.seconds,peakWitnesses.sorted(),time.seconds,
                    checkNotNull(startWitness).time.seconds)
                completionEvidence.add(CompletedEvidence(event!!,fixed,checkNotNull(startWitness)))
                arm(if (lowerTurn) bottom else current)
                return result(event!!.reason, travel, drift)
            }
        }
        return result("tracking", travel, drift)
    }

    companion object {
        const val countMethod = "overheadPullUpRelativeMotion"
        const val policyVersion = 4
        private val zero = SourceTimeLimit(0, 1)
        private val gap = SourceTimeLimit(7, 20)
        private val dwell = SourceTimeLimit(3, 25)
        private val calibration = SourceTimeLimit(1, 4)
        private fun compare(end: PresentationTime, start: PresentationTime, limit: SourceTimeLimit) = SourceTimeComparison.compareElapsed(end, start, limit)
        private fun distance(a: Point2D, b: Point2D) = hypot(a.x - b.x, a.y - b.y)
        internal data class Geometry(val shoulders: List<Point2D>, val wrists: List<Point2D>, val horizontal: Boolean)
        internal fun geometry(pose: PoseResult): Geometry? {
            val person = pose.people.singleOrNull() ?: return null
            if (!pose.imageSize.isValid || person.landmarks.size > 64 ||
                person.landmarks.map { it.joint }.distinct().size != person.landmarks.size) return null
            val pairs = ArmMeasurement.Side.entries.map { side ->
                val points = listOf(side.joints[0], side.joints[2]).map { joint ->
                    val p = person.landmark(joint) ?: return null
                    if (!p.position.isFinite || !p.confidence.isFinite() || p.confidence > 1 ||
                        p.position.x !in 0.0..pose.imageSize.width || p.position.y !in 0.0..pose.imageSize.height) return null
                    p.position
                }
                points[0] to points[1]
            }.sortedBy { it.second.x }
            val floor = ArmMeasurement.minimumSegmentFraction * min(pose.imageSize.width, pose.imageSize.height)
            if (pairs[1].first.x-pairs[0].first.x < floor || pairs[1].second.x-pairs[0].second.x < 2*floor) return null
            val hips = listOf(PoseJoint.leftHip,PoseJoint.rightHip).mapNotNull { person.landmark(it)?.position }
            val horizontal = if(hips.size != 2) false else {
                val dx=hips.map { it.x }.average()-pairs.map { it.first.x }.average()
                val dy=hips.map { it.y }.average()-pairs.map { it.first.y }.average()
                dy<=0 || abs(dx)>dy
            }
            return Geometry(pairs.map { it.first }, pairs.map { it.second },horizontal)
        }
        internal fun handsDeparted(pose: PoseResult, held: Sample): Boolean {
            if (held.size != pose.imageSize || held.backend != pose.backend || held.revision != pose.requestRevision) return false
            val current = geometry(pose) ?: return false
            if(current.horizontal) return false
            return held.arms.indices.all { i ->
                distance(current.wrists[i],held.arms[i].wrist) > .12*held.arms[i].length
            }
        }
        internal fun positiveRelease(pose: PoseResult, held: Sample): Boolean {
            if(!handsDeparted(pose,held)) return false
            val current=geometry(pose) ?: return false
            // Invert the already used overhead height criterion. Actual hands
            // must leave their actual anchors AND move below both shoulders.
            // No elbow/hip estimate or fabricated apparatus is required for exit.
            return held.arms.indices.all { i ->
                current.wrists[i].y-current.shoulders[i].y >= .35*held.arms[i].length
            }
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
            // For the declared front/rear, uncrossed-arm view, image-side
            // slots are stable when Vision swaps anatomical side labels.
            // Never repair a collapsed/mixed chain with invented joints.
            val floor = ArmMeasurement.minimumSegmentFraction * min(pose.imageSize.width, pose.imageSize.height)
            if (arms[1].wrist.x - arms[0].wrist.x < 2 * floor ||
                arms[1].shoulder.x - arms[0].shoulder.x < floor ||
                distance(person.landmark(PoseJoint.leftElbow)!!.position, person.landmark(PoseJoint.rightElbow)!!.position) < floor)
                return null
            return Sample(pose.timestamp, pose.imageSize, pose.backend, pose.requestRevision, arms,
                OverheadSupportReleasePosture.matches(pose,down=false))
        }
    }
}
