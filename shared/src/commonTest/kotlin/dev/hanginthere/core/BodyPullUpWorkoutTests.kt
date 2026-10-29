package dev.hanginthere.core

import kotlin.test.*
import kotlin.math.*

class BodyPullUpWorkoutTests {
    private val edge=BarSegment(Point2D(100.0,250.0),Point2D(800.0,250.0))
    private fun pose(ms:Long,rise:Double=0.0,degrees:Double=170.0,wristDrop:Double=0.0):PoseResult {
        val h=350-rise;val offset=h/(2*tan(degrees*PI/360))
        val points=listOf(250.0,650.0).flatMapIndexed { i,x ->
            val joints=ArmMeasurement.Side.entries[i].joints
            listOf(Landmark(joints[0],Point2D(x,250+h+wristDrop),.9),Landmark(joints[1],Point2D(x+if(i==0)-offset else offset,250+h/2+wristDrop),.9),Landmark(joints[2],Point2D(x,250+wristDrop),.9))
        }
        return PoseResult(PresentationTime(ms,1000),ImageSize(900.0,1400.0),listOf(PoseObservation(points)),"authored",1)
    }
    private fun cycle(offset:Long=0)=(0L..1120L step 40).map { t -> pose(t+offset,if(t in 440..720)140.0 else 0.0,if(t in 440..720)100.0 else 170.0) }
    @Test fun actualMovementCommitsAndStorageDisplayCountsShareOneMethod() {
        val owner=BodyPullUpWorkout("pullup",1000)
        var merged:WorkoutSetRecord?=null
        var state:BodyPullUpWorkout.Snapshot?=null
        for(p in cycle()) {
            state=owner.observe(p,edge,PullUpCycleMotion.Camera.ready)
            owner.draftDeltaJson()?.let { merged=WorkoutRecordCodec.mergeDraft(merged,WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(it)) }
        }
        assertTrue(state!!.started);assertEquals(1,state.motion.cycles)
        owner.finish("manual")
        val record=owner.record();assertEquals(1,record.completedCount);assertEquals(1,merged!!.completedCount)
        assertEquals(PullUpCycleMotion.countMethod,record.countMethod);assertEquals(1,record.countPolicyVersion)
        assertEquals(2,record.schemaVersion);assertEquals(2,record.boundaryPolicyVersion)
        assertEquals(edge,record.referenceEdge);assertNotNull(record.trackedAtEpochMilliseconds)
        WorkoutLibrary.validate(WorkoutLibraryState(entries=listOf(WorkoutLibraryEntry(WorkoutLibrary.compact(record)))))
        assertNotNull(record.reps.single().extremeAt);assertTrue(record.reps.single().isCompleteForAnalytics)
    }
    @Test fun staticSetupAndSmallBobsCannotStartOrCreateDrafts() {
        val owner=BodyPullUpWorkout("setup")
        for(t in 0L..2000L step 40) {
            val p=pose(t,if(t in 440..720)10.0 else 0.0,if(t in 440..720)165.0 else 170.0)
            assertFalse(owner.observe(p,edge,PullUpCycleMotion.Camera.ready).started)
        }
        assertNull(owner.draftDeltaJson());assertFalse(owner.record().hasPractice);assertEquals(0,owner.closedRecordCount)
    }
    @Test fun manualPreparationCannotBankPreviewCyclesAndKeepsItsNewSourceTime() {
        val owner=BodyPullUpWorkout("manual")
        for(p in cycle()) assertFalse(owner.observe(p,edge,PullUpCycleMotion.Camera.ready,automaticStart=false).started)
        assertTrue(owner.startManually());assertEquals(1.12,owner.record().startedAt)
        for(p in cycle(1160)) owner.observe(p,edge,PullUpCycleMotion.Camera.ready,automaticStart=false)
        owner.finish("manual");assertEquals(1,owner.record().completedCount)
    }
    @Test fun motionWithoutRealReferenceCameraOrBothMeasuredArmsCannotStart() {
        for(kind in listOf("bar","camera","arm","crowd")) {
            val owner=BodyPullUpWorkout(kind)
            for(p in cycle()) {
                val frame=when(kind) {
                    "arm" -> p.copy(people=listOf(p.people.single().copy(landmarks=p.people.single().landmarks.dropLast(1))))
                    "crowd" -> p.copy(people=p.people+p.people)
                    else -> p
                }
                owner.observe(frame,edge.takeUnless { kind=="bar" },if(kind=="camera")PullUpCycleMotion.Camera.unavailable else PullUpCycleMotion.Camera.ready)
            }
            assertFalse(owner.hasStarted);assertEquals(0,owner.record().completedCount);assertNull(owner.draftDeltaJson())
        }
    }
    @Test fun partialReturnReleaseCannotEarnAnExtraCycleAndNaturallyCloses() {
        val owner=BodyPullUpWorkout("release")
        for(p in cycle()) owner.observe(p,edge,PullUpCycleMotion.Camera.ready)
        for(t in 1160L..1480L step 40) owner.observe(pose(t,140.0,100.0),edge,PullUpCycleMotion.Camera.ready)
        for(t in 1520L..1760L step 40) owner.observe(pose(t,100.0,120.0),edge,PullUpCycleMotion.Camera.ready)
        for(t in 1800L..3200L step 40) {
            if(owner.isFinished)break
            owner.observe(pose(t,100.0,120.0,wristDrop=200.0),edge,PullUpCycleMotion.Camera.ready)
        }
        assertTrue(owner.isFinished);assertEquals("disengaged",owner.record().endReason)
        assertEquals(1,owner.record().completedCount)
    }
    @Test fun interruptionDoesNotMutateFrozenRecordReferenceOrEraseCompletedCycles() {
        val owner=BodyPullUpWorkout("interrupted")
        var merged:WorkoutSetRecord?=null
        for(p in cycle()) {owner.observe(p,edge,PullUpCycleMotion.Camera.ready);owner.draftDeltaJson()?.let { merged=WorkoutRecordCodec.mergeDraft(merged,WorkoutRecordCodec.json.decodeFromString(it)) }}
        owner.interrupt("cameraUnavailable")
        owner.observe(pose(1200),null,PullUpCycleMotion.Camera.unavailable)
        owner.finish("cameraInterrupted")
        val result=WorkoutRecordCodec.mergeDraft(merged,owner.record())
        assertEquals(edge,result.referenceEdge);assertEquals(1,result.completedCount)
    }
    @Test fun sourceRegressionCannotCompleteOrRewindTheHighWaterClock() {
        val owner=BodyPullUpWorkout("clock")
        for(p in cycle().filter { it.timestamp.value<=720 }) owner.observe(p,edge,PullUpCycleMotion.Camera.ready)
        for(p in cycle().filter { it.timestamp.value<=720 }) assertFalse(owner.observe(p,edge,PullUpCycleMotion.Camera.ready).motion.sourceAccepted)
        owner.finish("manual");assertEquals(0,owner.record().completedCount)
    }
    @Test fun methodCohortsAreDistinctAndWrongExerciseOrUnknownVersionsAreRejected() {
        WorkoutGoal(ExerciseCounter.Exercise.pullUp,WorkoutGoalMetric.completedReps,5.0,countMethod=PullUpCycleMotion.countMethod,countPolicyVersion=1).validate()
        assertFailsWith<IllegalArgumentException> { WorkoutGoal(ExerciseCounter.Exercise.dip,WorkoutGoalMetric.completedReps,5.0,countMethod=PullUpCycleMotion.countMethod,countPolicyVersion=1).validate() }
        assertFailsWith<IllegalArgumentException> { WorkoutGoal(ExerciseCounter.Exercise.pullUp,WorkoutGoalMetric.completedReps,5.0,countMethod=PullUpCycleMotion.countMethod,countPolicyVersion=99).validate() }
        val context=WorkoutContext(assistance=WorkoutAssistance.none,addedLoadKg=0.0,equipment="bar",cameraView="front")
        val key=context.comparisonKey(ExerciseCounter.Exercise.pullUp,WorkoutAnalysis.metricVersion,1,PullUpCycleMotion.countMethod)!!
        assertEquals(PullUpCycleMotion.countMethod,WorkoutComparisonKey.decode(key).last())
        assertNotEquals(key,context.comparisonKey(ExerciseCounter.Exercise.pullUp,WorkoutAnalysis.metricVersion,ExerciseCounter.policyVersion))
    }
}
