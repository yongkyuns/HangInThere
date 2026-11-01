package dev.hanginthere.core

import kotlin.test.*

class SideViewSitUpTests {
    private var milliseconds = 0L
    private fun pose(up: Boolean = false, missing: Set<PoseJoint> = emptySet(), shift: Double = 0.0, lift: Double? = null): PoseResult {
        val fraction=lift ?: if(up) 1.0 else 0.0
        val points = listOf(PoseJoint.leftShoulder to Point2D(180+220*fraction,400-220*fraction),
            PoseJoint.leftHip to Point2D(420.0+shift,400.0),PoseJoint.leftKnee to Point2D(550.0+shift,310.0),
            PoseJoint.leftAnkle to Point2D(660.0+shift,410.0))
        return PoseResult(PresentationTime(milliseconds,1000),ImageSize(1000.0,600.0),listOf(PoseObservation(
            points.filter { it.first !in missing }.map { Landmark(it.first,it.second,.95) })),"situp-test",1)
    }
    private fun feed(motion: SideViewSitUpMotion, up: Boolean = false, duration: Int = 400,
        camera: SideViewPushUpMotion.Camera = SideViewPushUpMotion.Camera.ready,
        change: (PoseResult)->PoseResult = {it}): SideViewSitUpMotion.Snapshot {
        var last: SideViewSitUpMotion.Snapshot? = null
        repeat(duration/20) { last=motion.observe(change(pose(up)),camera); milliseconds+=20 }
        return checkNotNull(last)
    }
    @Test fun heldStartFullRiseAndReturnCountAndStandingReleases() {
        val motion=SideViewSitUpMotion()
        assertEquals(SideViewSitUpMotion.Phase.ready,feed(motion,duration=1600).phase)
        assertFalse(feed(motion).setStarted)
        repeat(2) { feed(motion,up=true); assertEquals(it+1,feed(motion).cycles) }
        val released=feed(motion,duration=420) { pose(shift=180.0) }
        assertEquals(2,released.endedSet?.reps?.size)
        assertEquals("positionChanged",released.endedSet?.endReason)
    }
    @Test fun losingTheUnusedFarHipDoesNotDiscardTheNearSideCycle() {
        fun bilateral(p: PoseResult, missing: PoseJoint? = null): PoseResult {
            val left=p.people.single().landmarks
            val right=left.map {
                val joint=PoseJoint.valueOf(it.joint.name.replace("left","right"))
                it.copy(joint=joint,position=Point2D(it.position.x-if(joint==PoseJoint.rightShoulder)20 else 0,it.position.y+10))
            }
            return p.copy(people=listOf(PoseObservation((left+right).filterNot { it.joint==missing })))
        }
        val m=SideViewSitUpMotion()
        assertEquals(ArmMeasurement.Side.right,feed(m,duration=1600) { bilateral(it) }.side)
        assertTrue(feed(m,up=true) { bilateral(it,PoseJoint.leftHip) }.observationUsable)
        assertEquals(1,feed(m) { bilateral(it,PoseJoint.leftHip) }.cycles)
        feed(m,up=true) { bilateral(it,PoseJoint.rightHip) }
        assertEquals(1,feed(m,duration=1600) { bilateral(it,PoseJoint.rightHip) }.cycles)
    }
    @Test fun setupStaticHoldsShallowMotionAndMissingEndpointsCannotCount() {
        val motion=SideViewSitUpMotion()
        assertEquals(0,feed(motion,up=true,duration=2000).cycles)
        feed(motion,duration=1600)
        feed(motion,up=true,duration=1000) { p -> p.copy(people=p.people.map { person -> person.copy(landmarks=person.landmarks.map {
            if(it.joint==PoseJoint.leftShoulder) it.copy(position=Point2D(220.0,350.0)) else it
        }) }) }
        assertEquals(0,feed(motion).cycles)
        feed(motion,up=true)
        assertEquals(0,feed(motion,duration=800) { pose(missing=setOf(PoseJoint.leftHip)) }.cycles)
        assertEquals(0,feed(motion,duration=1600).cycles)
    }
    @Test fun returnUsesLyingBackPostureAndBriefHipJitterCannotRelocateTheSet() {
        val m=SideViewSitUpMotion();feed(m,duration=1600);feed(m,up=true)
        val jitter=feed(m,duration=100) { pose(shift=180.0) }
        assertFalse(jitter.observationUsable);assertNull(jitter.endedSet)
        val returned=feed(m) { p -> p.copy(people=p.people.map { person -> person.copy(landmarks=person.landmarks.map {
            if(it.joint==PoseJoint.leftShoulder) it.copy(position=Point2D(200.0,350.0)) else it
        }) }) }
        assertEquals(1,returned.cycles)
    }
    @Test fun aCroppedFootCanUsePositiveElevatedKneeEvidenceButMissingHipCannotAcquire() {
        val m=SideViewSitUpMotion()
        assertEquals(SideViewSitUpMotion.Phase.ready,feed(m,duration=1600) { pose(missing=setOf(PoseJoint.leftAnkle)) }.phase)
        feed(m,up=true) { pose(up=true,missing=setOf(PoseJoint.leftAnkle)) }
        assertEquals(1,feed(m) { pose(missing=setOf(PoseJoint.leftAnkle)) }.cycles)
        val missing=SideViewSitUpMotion()
        assertEquals(SideViewSitUpMotion.Phase.seekingStart,feed(missing,duration=1600) { pose(missing=setOf(PoseJoint.leftHip)) }.phase)
    }
    @Test fun cameraAndSourceInterruptionsCannotCompletePendingReps() {
        for (fault in listOf("camera","clock","position","person")) {
            val m=SideViewSitUpMotion();feed(m,duration=1600);feed(m,up=true)
            when(fault) {
                "camera" -> feed(m,camera=SideViewPushUpMotion.Camera.moved)
                "clock" -> { milliseconds+=1000;feed(m) }
                "position" -> feed(m,duration=600) { pose(shift=180.0) }
                "person" -> feed(m,duration=1000) { it.copy(people=it.people+it.people) }
            }
            assertEquals(0,feed(m,duration=1600).cycles,fault)
        }
    }
    @Test fun durableRecordsKeepTimingWithoutInventingElbowMetrics() {
        val workout=SideViewSitUpWorkout("sit-up",100000)
        fun frames(up: Boolean,duration: Int) { repeat(duration/20) {
            workout.observe(pose(up),SideViewPushUpMotion.Camera.ready);milliseconds+=20
        } }
        fun ramp(up: Boolean) { repeat(10) { i ->
            val amount=(i+1)/10.0
            workout.observe(pose(lift=if(up) amount else 1-amount),SideViewPushUpMotion.Camera.ready);milliseconds+=20
        } }
        frames(false,1600);ramp(true);frames(true,400);ramp(false);frames(false,400);workout.finish("manual")
        val writes=mutableListOf<SideViewSitUpWorkout.Write>()
        while(workout.nextWrite()!=null) { writes+=checkNotNull(workout.nextWrite());workout.acknowledgeWrite() }
        val record=writes.last().record
        assertTrue(writes.last().finalized);assertEquals(1,record.completedCount)
        assertEquals(ExerciseCounter.Exercise.sitUp,record.exercise)
        assertTrue(record.completedReps.all { it.left.minimum==null && it.right.minimum==null && it.outboundSeconds!!>0 && it.returnSeconds!!>0 })
        WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(),listOf(record)))
        WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(),listOf(record.copy(
            countPolicyVersion=1,reps=record.reps.map { it.copy(countPolicyVersion=1) }))))
        val recovered=writes.filterNot { it.finalized }.map { it.record }.reduce(WorkoutRecordCodec::mergeDraft)
        assertEquals(1,WorkoutRecordCodec.recover(recovered).completedCount)
        assertFalse(WorkoutEvidence.assess(record.completedReps.single()).angleComparisonEligible)
        assertNotNull(WorkoutJourney.set(record).outwardSeconds)
        assertEquals("Top",WorkoutSetPresentation.keyframes(record,0.0)[1].phase)
        assertEquals(WorkoutSetPresentation.tempo(record).reps.single().outward,WorkoutSetPresentation.tempo(record).reps.single().lifting)
        assertNull(WorkoutSetPresentation.tempo(record).change)
    }
}
