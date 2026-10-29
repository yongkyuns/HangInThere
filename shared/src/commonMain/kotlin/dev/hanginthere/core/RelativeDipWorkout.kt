package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.math.hypot
import kotlin.math.min

/** One rail-free set owner. Native capture owns real camera calibration pixels
 * and phone/scene stability; the shared owner receives that independent verdict.
 * Readiness, motion, positive release and durable analytics use source time.
 */
class RelativeDipWorkout(private val setId: String, private val trackedAtEpochMilliseconds: Long? = null) {
    companion object {
        /** v4 confirms large bilateral support exit without a rep-angle gate. */
        const val boundaryPolicyVersion = 4
        private const val torsoExitFraction = .35
    }
    @Serializable data class Snapshot(val motion: BilateralTorsoDipMotion.Snapshot, val started: Boolean,
        val analysisRevision: Int, val analyzedFrames: Int, val usableFrames: Int, val endReason: String?)
    private val motion = BilateralTorsoDipMotion()
    private var analysis = newAnalysis()
    private fun newAnalysis() = WorkoutAnalysis(setId, ExerciseCounter.Exercise.dip,
        BilateralTorsoDipMotion.policyVersion, BilateralTorsoDipMotion.countMethod)
    private val boundary = AutomaticSetBoundary()
    private var first: Double? = null
    private var last: PoseResult? = null
    private var started = false
    private var analysisStarted = false
    private var associationGeneration: Long? = null
    private var endReason: String? = null
    private var analyzed = 0
    private var usable = 0
    private var readinessSample: MotionSample? = null
    private var supportWitness: BilateralTorsoDipMotion.Sample? = null
    private var releaseLast: PresentationTime? = null
    private var releaseTorsoExited = false
    private var manualPreparation: Pair<PoseResult, MotionSample>? = null
    private var firstInputSeconds: Double? = null
    private var startedEpochMilliseconds: Long? = null
    val closedRecordCount get() = if(started) analysis.records.size else 0
    val hasStarted get() = started
    val isFinished get() = endReason != null
    val revision get() = analysis.revision

    fun observe(selection: BodyAthleteSelection, camera: DipCycleMotion.Camera, automaticFinish: Boolean = true,
        automaticStart: Boolean = true): Snapshot {
        check(endReason == null) { "Create a new set owner after finishing" }
        val frame = checkNotNull(selection.frame) { "Observe the original source before counting" }
        val pose = frame.pose
        val decision = frame.decision
        val identityLost = decision.status == BodyAthleteAssociation.Status.lost ||
            associationGeneration?.let { it != decision.sourceGeneration } == true
        if (identityLost) interrupt("athleteAssociationLost")
        associationGeneration = decision.sourceGeneration
        val state = motion.observe(selection, camera == DipCycleMotion.Camera.ready,
            allowDeparture = started || automaticStart)
        manualPreparation = null
        if(!state.sourceAccepted) {
            analysis.interrupt(state.reason); boundary.breakContinuity(); readinessSample = null; supportWitness = null
            resetReleaseWitness()
            return Snapshot(state, started, analysis.revision, analyzed, usable, endReason)
        }
        if(firstInputSeconds == null) firstInputSeconds = pose.timestamp.seconds
        val current = motion.currentSample
        // Unavailable registration supplies neither motion nor release credit,
        // but does not prove that this camera epoch moved. Retain the private
        // measured hand locations so fresh, requalified frames can witness a
        // later dismount. Actual movement/context/source loss still revoke them.
        if(camera == DipCycleMotion.Camera.moved || supportWitness?.let {
                it.size != pose.imageSize || it.backend != pose.backend || it.revision != pose.requestRevision
            } == true) supportWitness = null
        // Rebase standing preparation when the actual mount is measured, but
        // never let post-dismount standing preparation follow the athlete and
        // erase the original support departure during the release dwell.
        if(!releaseTorsoExited && (!started || state.reason in listOf("higherPreparation", "recoveredMeasuredTop")))
            motion.measuredTop?.let { supportWitness = it }
        val short = min(pose.imageSize.width, pose.imageSize.height)
        val positiveRelease = released(pose, camera, decision.status == BodyAthleteAssociation.Status.associated)
        val feature = MotionSample(pose.timestamp.seconds, current?.arms?.get(0)?.degrees,
            current?.arms?.get(1)?.degrees,
            if(state.observationUsable) current?.shoulders?.map { it.y / short }?.average() else null,
            state.observationUsable, positiveRelease,
            postureReleased = positiveRelease && largeSupportDeparture(pose))
        val phase = when(state.phase) {
            BilateralTorsoDipMotion.Phase.seekingTop -> ExerciseCounter.Phase.seekingStart
            BilateralTorsoDipMotion.Phase.ready -> ExerciseCounter.Phase.ready
            BilateralTorsoDipMotion.Phase.outbound -> ExerciseCounter.Phase.outbound
            BilateralTorsoDipMotion.Phase.returning -> ExerciseCounter.Phase.returning
        }
        val event = state.event?.let { e -> WorkoutAnalysis.MotionEvent(when(e.outcome) {
            "imageCycle" -> WorkoutAnalysis.MotionOutcome.completed
            "partial" -> WorkoutAnalysis.MotionOutcome.partial
            else -> WorkoutAnalysis.MotionOutcome.interrupted
        }, e.seconds, e.reason, e.peak.takeIf { e.outcome == "imageCycle" }) }
        if(!started && phase == ExerciseCounter.Phase.ready && feature.usable) manualPreparation = pose to feature
        // Preparation and shallow setup bobs stay private. A corroborated full
        // bilateral excursion commits the automatic set, including its earlier
        // actual preparation/departure samples. Static posture cannot commit it.
        if(!analysisStarted && phase == ExerciseCounter.Phase.outbound && feature.usable) {
            analysisStarted = true
            readinessSample?.takeIf { feature.seconds > it.seconds && feature.seconds - it.seconds <= ExerciseCounter.maximumGapSeconds }?.let {
                first = it.seconds; analysis.observeMotion(it, ExerciseCounter.Phase.ready, null); analyzed++; usable++
            }
        }
        if(analysisStarted || started) {
            analysis.observeMotion(feature, phase, event)
            analyzed++; if(feature.usable) usable++
            if(first == null) first = feature.seconds
            last = pose
        }
        if(!started && automaticStart && state.observationUsable && analysisStarted && motion.hasObservedExcursion) {
            started = true; bindCalendarStart()
        }
        if(started) {
            last = pose
            if(identityLost) finish("trackingLost")
            else if(camera == DipCycleMotion.Camera.moved) finish("setupInvalidated")
            else if(automaticFinish) boundary.observe(feature, true)?.let(::finish)
        } else if(analysisStarted && phase in listOf(ExerciseCounter.Phase.ready, ExerciseCounter.Phase.seekingStart) && !motion.hasObservedExcursion) {
            analysis = newAnalysis(); analysisStarted = false; first = null; analyzed = 0; usable = 0
        }
        readinessSample = feature.takeIf { !analysisStarted && phase == ExerciseCounter.Phase.ready && feature.usable }
        return Snapshot(state, started, analysis.revision, analyzed, usable, endReason)
    }

    /** An explicit user start can record hold-only practice. Preparation while
     * automatic start is disabled cannot accumulate cycles before the tap. The
     * native owner also requires a fresh frame and qualified camera at the tap. */
    fun startManually(): Boolean {
        if(started || isFinished || analysisStarted) return false
        val (pose, feature) = manualPreparation ?: return false
        started = true; analysisStarted = true; first = feature.seconds; last = pose
        bindCalendarStart()
        analysis.observeMotion(feature, ExerciseCounter.Phase.ready, null); analyzed++; usable++
        manualPreparation = null
        return true
    }
    private fun bindCalendarStart() {
        if(startedEpochMilliseconds == null && trackedAtEpochMilliseconds != null) {
            val elapsed = ((first ?: return) - (firstInputSeconds ?: return)) * 1000
            if(elapsed.isFinite() && elapsed >= 0 && elapsed < Long.MAX_VALUE-trackedAtEpochMilliseconds.toDouble())
                startedEpochMilliseconds = trackedAtEpochMilliseconds + elapsed.toLong()
        }
    }

    private data class ReleaseArm(val shoulder: Point2D, val wrist: Point2D)

    /** Current shoulders and wrists witness exit independently of elbow angle.
     * The support scale is still from an actual, fully measured qualified top;
     * no missing joint or arm length is synthesized for either motion or exit. */
    private fun releaseArms(pose: PoseResult): List<ReleaseArm>? {
        val person = pose.people.singleOrNull() ?: return null
        if(!pose.imageSize.isValid || person.landmarks.size > 64 ||
            person.landmarks.map { it.joint }.distinct().size != person.landmarks.size) return null
        val arms = ArmMeasurement.Side.entries.map { side ->
            val points = listOf(side.joints[0], side.joints[2]).map { joint ->
                val point = person.landmark(joint) ?: return null
                if(!point.position.isFinite || !point.confidence.isFinite() || point.confidence > 1 ||
                    point.position.x !in 0.0..pose.imageSize.width || point.position.y !in 0.0..pose.imageSize.height) return null
                point.position
            }
            ReleaseArm(points[0], points[1])
        }.sortedBy { it.shoulder.x }
        // Preserve independently measurable spatial slots; coincident hands or
        // a reversed/side-on shoulder projection cannot prove bilateral exit.
        val floor = ArmMeasurement.minimumSegmentFraction * min(pose.imageSize.width, pose.imageSize.height)
        return arms.takeIf { it[1].wrist.x-it[0].wrist.x >= 2*floor &&
            it[1].shoulder.x-it[0].shoulder.x >= floor }
    }

    /** Both current wrists must leave their measured support and the torso must
     * positively exit. Missing hands or camera/athlete evidence earns no dwell. */
    private fun released(pose: PoseResult, camera: DipCycleMotion.Camera, athleteAssociated: Boolean): Boolean {
        val time = pose.timestamp
        val gap = releaseLast?.let { SourceTimeComparison.compareElapsed(time,it,SourceTimeLimit(7,20)) }
        if(releaseLast != null && (gap == null || gap > 0)) releaseTorsoExited = false
        releaseLast = time
        val support = supportWitness
        val current = releaseArms(pose)
        if(support == null || current == null || !athleteAssociated || camera != DipCycleMotion.Camera.ready ||
            support.size != pose.imageSize || support.backend != pose.backend || support.revision != pose.requestRevision) {
            releaseTorsoExited = false
            return false
        }
        // Reuse the motion policy's arm-relative support-loss distance. Also
        // require the torso to leave its original support zone: regripping or
        // raising the hands while staying at the top is not a dismount.
        // Support is always an actual full two-arm top; no current arm is filled in.
        val arms = current.zip(support.arms.map { checkNotNull(it) })
        if(!arms.all { (now,top) -> hypot(now.wrist.x-top.wrist.x, now.wrist.y-top.wrist.y) > .12 * top.length }) {
            releaseTorsoExited = false
            return false
        }
        if(arms.withIndex().all { (i, pair) ->
            val (now, top) = pair
            val shoulder = support.shoulders[i]
            val span = hypot(shoulder.x-top.wrist.x, shoulder.y-top.wrist.y)
            val lowered = -((now.shoulder.x-shoulder.x)*(shoulder.x-top.wrist.x) +
                (now.shoulder.y-shoulder.y)*(shoulder.y-top.wrist.y)) / span
            lowered >= torsoExitFraction * top.length
        }) releaseTorsoExited = true
        // Walking toward a fixed camera can raise the shoulders in the image.
        // Once torso exit was actually seen with BOTH wrists departed, keep it
        // only while both remain continuously departed. The common one-second
        // dwell is unchanged; returning either wrist, missing camera/body,
        // source gaps or context loss revoke this witness.
        return releaseTorsoExited
    }
    /** A larger measured support exit corroborates the already observed torso
     * exit. Camera/actor/context and both actual wrists were checked above.
     * Neither an absent elbow nor an image apparatus is inferred. Smaller hand
     * displacement retains the common one-second fallback. */
    private fun largeSupportDeparture(pose: PoseResult): Boolean {
        val held=supportWitness ?: return false
        val current=releaseArms(pose) ?: return false
        return current.indices.all { i ->
            val arm=held.arms[i] ?: return false
            hypot(current[i].wrist.x-arm.wrist.x,current[i].wrist.y-arm.wrist.y)>=torsoExitFraction*arm.length
        }
    }
    private fun resetReleaseWitness() { releaseLast = null; releaseTorsoExited = false }
    fun interrupt(reason: String) {
        if(isFinished) return
        motion.interrupt(reason); analysis.interrupt(reason); boundary.breakContinuity()
        resetReleaseWitness()
        readinessSample = null; supportWitness = null; manualPreparation = null
        if(!started) { analysis = newAnalysis(); analysisStarted = false; first = null; analyzed = 0; usable = 0 }
    }
    fun finish(reason: String) {
        if(isFinished) return
        require(reason.isNotBlank() && reason.length <= 64)
        motion.interrupt(reason); analysis.finish(reason); endReason = reason
    }
    fun record(includeActive: Boolean = false): WorkoutSetRecord = record(
        if(started) analysis.records + listOfNotNull(if(includeActive)analysis.activeRecord else null) else emptyList(), analysis.activity)
    private fun record(reps: List<RepRecord>, activity: WorkoutActivity): WorkoutSetRecord = WorkoutSetRecord(
        setId, ExerciseCounter.Exercise.dip, first.takeIf { started }, last?.timestamp?.seconds, endReason ?: "running", reps,
        last?.backend, last?.requestRevision, schemaVersion = 2, imageSize = last?.imageSize,
        trackedAtEpochMilliseconds = startedEpochMilliseconds, activity = activity.takeIf { started },
        countMethod = BilateralTorsoDipMotion.countMethod, countPolicyVersion = BilateralTorsoDipMotion.policyVersion,
        boundaryPolicyVersion = boundaryPolicyVersion)
    // An explicit manual start has a durable identity before its first hold
    // interval or rep. Private automatic preparation still publishes nothing.
    fun draftDeltaJson(): String? = if(started)
        WorkoutRecordCodec.json.encodeToString(record(listOfNotNull(analysis.activeRecord ?: analysis.records.lastOrNull()), analysis.activityDelta)) else null
    fun reviewDeltaJson(): String = WorkoutRecordCodec.json.encodeToString(record(if(started) listOfNotNull(analysis.activeRecord) else emptyList(), analysis.activity))
    fun lastClosedRecordJson(): String? = if(started) analysis.records.lastOrNull()?.let { WorkoutRecordCodec.json.encodeToString(it) } else null
}

/** Apple ABI uses the same rail-free owner as Kotlin clients. Camera readiness
 * is supplied by the actual native phone/scene gate, never inferred from a pose. */
class RelativeDipWorkoutBridge(setId: String, trackedAtEpochMilliseconds: Long = 0) {
    private val workout = RelativeDipWorkout(setId, trackedAtEpochMilliseconds.takeIf { it > 0 })
    val closedRecordCount get() = workout.closedRecordCount
    val revision get() = workout.revision
    val hasStarted get() = workout.hasStarted
    @Throws(IllegalArgumentException::class, IllegalStateException::class)
    fun observeSelectedJson(selection: BodyAthleteSelection, sourceValue: Long, sourceTimescale: Int,
        camera: String, automaticFinish: Boolean, automaticStart: Boolean): String {
        require(selection.frame?.raw?.timestamp == PresentationTime(sourceValue, sourceTimescale)) {
            "The selected athlete must belong to this exact source frame"
        }
        return WorkoutRecordCodec.json.encodeToString(workout.observe(selection,
            DipCycleMotion.Camera.valueOf(camera), automaticFinish, automaticStart))
    }
    fun startManually(): Boolean = workout.startManually()
    fun interrupt(reason: String) = workout.interrupt(reason)
    fun finish(reason: String) = workout.finish(reason)
    fun setJson(): String = WorkoutRecordCodec.json.encodeToString(workout.record())
    fun draftDeltaJson(): String? = workout.draftDeltaJson()
    fun reviewDeltaJson(): String = workout.reviewDeltaJson()
    fun lastClosedRecordJson(): String? = workout.lastClosedRecordJson()
}
