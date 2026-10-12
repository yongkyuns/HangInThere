package dev.hanginthere.core

import kotlinx.serialization.encodeToString

/**
 * Small stable Apple-facing ABI. Raw triples preserve duplicate/missing joints;
 * native adapters do not implement measurements, metrics, or lifecycle rules.
 * JSON is produced only when rep/set records change, never for camera pixels.
 */
class WorkoutAnalysisBridge(setId: String, exercise: String, private val trackedAtEpochMilliseconds: Long) {
    private val setId=setId
    private var first:Double?=null
    private var last:Double?=null
    private var backend:String?=null
    private var trackingSide:ArmMeasurement.Side?=null
    private var imageSize:ImageSize?=null
    private var referenceEdge:BarSegment?=null
    private var modelRevision:Int?=null
    private var endReason="running"
    private val exercise = ExerciseCounter.Exercise.valueOf(exercise)
    private val analysis = WorkoutAnalysis(setId, this.exercise)
    private val boundary = AutomaticSetBoundary()
    private val motionFeatures = WorkoutMotionFeatures()
    private val start = AutomaticWorkoutStart()
    val jointNames get() = PoseJoint.entries.map{it.name}
    val policyVersion get() = ExerciseCounter.policyVersion
    val revision get() = analysis.revision
    fun observe(seconds: Double, width: Double, height: Double, peopleCount: Int,
                joints: IntArray, triples: DoubleArray, reference: DoubleArray,
                phase: String, usable: Boolean, backend: String, modelRevision: Int, trackingSide: String, eventOutcome: String?, eventTime: Double,
                eventReason: String?, hasActivity: Boolean, automaticFinish: Boolean): String? {
        if(seconds.isFinite() && (last==null || seconds>last!!)){if(first==null) first=seconds;last=seconds}
        this.backend=backend;this.modelRevision=modelRevision
        val pose = pose(seconds, width, height, peopleCount, joints, triples)
        val bar = if (reference.size == 4) BarSegment(Point2D(reference[0], reference[1]), Point2D(reference[2], reference[3])) else null
        this.trackingSide=ArmMeasurement.Side.valueOf(trackingSide)
        if(imageSize==null) imageSize=pose.imageSize
        if(referenceEdge==null) referenceEdge=bar
        val sample = motionFeatures.extract(pose, bar, usable,ExerciseCounter.Phase.valueOf(phase)).copy(seconds=seconds)
        val event = eventOutcome?.let { ExerciseCounter.Event(ExerciseCounter.Outcome.valueOf(it), eventTime, eventReason.orEmpty()) }
        analysis.observe(sample, ExerciseCounter.Phase.valueOf(phase), event)
        return if (automaticFinish) boundary.observe(sample, hasActivity || ExerciseCounter.Phase.valueOf(phase) in listOf(ExerciseCounter.Phase.ready,ExerciseCounter.Phase.outbound,ExerciseCounter.Phase.returning)) else null
    }
    fun ready(seconds: Double, eligible: Boolean) = start.observe(seconds, eligible)
    fun resetReadiness() = start.reset()
    fun interrupt(reason: String) { analysis.interrupt(reason); boundary.reset() }
    fun finish(reason: String) { analysis.finish(reason);endReason=reason }
    fun draftDeltaJson():String? = (analysis.activeRecord ?: analysis.records.lastOrNull())?.let{
        WorkoutAnalysis.json.encodeToString(WorkoutSetRecord(setId,exercise,first,last,"running",listOf(it),backend,modelRevision,trackingSide=trackingSide,imageSize=imageSize,referenceEdge=referenceEdge,trackedAtEpochMilliseconds=trackedAtEpochMilliseconds))
    }
    fun setJson() = WorkoutAnalysis.json.encodeToString(WorkoutSetRecord(setId,exercise,first,last,endReason,analysis.records,backend,modelRevision,trackingSide=trackingSide,imageSize=imageSize,referenceEdge=referenceEdge,trackedAtEpochMilliseconds=trackedAtEpochMilliseconds))
    fun recordsJson() = analysis.recordsJson()
    fun lastClosedRecordJson():String? = analysis.records.lastOrNull()?.let{WorkoutAnalysis.json.encodeToString(it)}
    private fun pose(seconds: Double, width: Double, height: Double, peopleCount: Int, joints: IntArray, triples: DoubleArray): PoseResult {
        require(triples.size == joints.size * 3)
        val landmarks = joints.mapIndexed { i, joint ->
            Landmark(PoseJoint.entries[joint], Point2D(triples[i * 3], triples[i * 3 + 1]), triples[i * 3 + 2])
        }
        return PoseResult(PresentationTime((seconds * 1_000_000).toLong(), 1_000_000), ImageSize(width, height),
            when (peopleCount) { 0 -> emptyList(); 1 -> listOf(PoseObservation(landmarks)); else -> List(peopleCount.coerceAtMost(2)) { PoseObservation(emptyList()) } },
            "native adapter", 0)
    }
}
