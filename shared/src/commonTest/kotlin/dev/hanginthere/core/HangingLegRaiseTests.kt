package dev.hanginthere.core

import kotlin.test.*

class HangingLegRaiseTests {
    private var milliseconds=0L
    private fun pose(lift: Double=0.0,missing: Set<PoseJoint> = emptySet(),shift: Double=0.0,overhead: Boolean=true): PoseResult {
        val points=listOf(PoseJoint.leftWrist to Point2D(280.0,if(overhead) 80.0 else 390.0),
            PoseJoint.leftElbow to Point2D(305.0,165.0),PoseJoint.leftShoulder to Point2D(310.0,250.0),
            PoseJoint.leftHip to Point2D(320.0+shift,430.0),PoseJoint.leftKnee to Point2D(325.0+160*lift+shift,590-175*lift),
            PoseJoint.leftAnkle to Point2D(325.0+320*lift+shift,730-300*lift))
        return PoseResult(PresentationTime(milliseconds,1000),ImageSize(1000.0,800.0),listOf(PoseObservation(
            points.filter { it.first !in missing }.map { Landmark(it.first,it.second,.95) })),"legraise-test",1)
    }
    private fun feed(m: HangingLegRaiseMotion,lift: Double=0.0,duration: Int=400,
        camera: SideViewPushUpMotion.Camera=SideViewPushUpMotion.Camera.ready,
        change: ()->PoseResult={pose(lift)}): HangingLegRaiseMotion.Snapshot {
        var s: HangingLegRaiseMotion.Snapshot?=null
        repeat(duration/20) { s=m.observe(change(),camera);milliseconds+=20 };return checkNotNull(s)
    }
    @Test fun onlyCompleteLiftAndReturnCountsAndReleaseEndsSet() {
        val m=HangingLegRaiseMotion()
        val ready=feed(m,duration=1600);assertEquals(HangingLegRaiseMotion.Phase.ready,ready.phase,ready.title)
        assertFalse(feed(m).setStarted)
        repeat(3) { feed(m,1.0);assertEquals(it+1,feed(m).cycles) }
        val released=feed(m,duration=420) { pose(overhead=false) }
        assertEquals(3,released.endedSet?.reps?.size)
    }
    @Test fun visibleNearTorsoSurvivesUnusedFarHipLoss() {
        fun bilateral(lift: Double, missing: PoseJoint? = null): PoseResult {
            val p=pose(lift)
            val left=p.people.single().landmarks
            val right=left.map {
                val joint=PoseJoint.valueOf(it.joint.name.replace("left","right"))
                it.copy(joint=joint,position=Point2D(it.position.x-if(joint==PoseJoint.rightShoulder)20 else 0,it.position.y+10))
            }
            return p.copy(people=listOf(PoseObservation((left+right).filterNot { it.joint==missing })))
        }
        val m=HangingLegRaiseMotion()
        assertEquals(ArmMeasurement.Side.right,feed(m,duration=1600) { bilateral(0.0) }.side)
        assertTrue(feed(m,1.0) { bilateral(1.0,PoseJoint.leftHip) }.observationUsable)
        assertEquals(1,feed(m) { bilateral(0.0,PoseJoint.leftHip) }.cycles)
        feed(m,1.0) { bilateral(1.0,PoseJoint.rightHip) }
        assertEquals(1,feed(m,duration=1600) { bilateral(0.0,PoseJoint.rightHip) }.cycles)
        val handLost=HangingLegRaiseMotion()
        feed(handLost,duration=1600) { bilateral(0.0) }
        feed(handLost,1.0) { bilateral(1.0,PoseJoint.rightWrist) }
        assertEquals(0,feed(handLost,duration=800) { bilateral(0.0,PoseJoint.rightWrist) }.cycles)
    }
    @Test fun setupHoldsPartialLiftsMissingEndpointsAndSwingDoNotCount() {
        val m=HangingLegRaiseMotion()
        assertEquals(0,feed(m,1.0,duration=1600).cycles)
        feed(m,duration=1600);feed(m,.35);assertEquals(0,feed(m).cycles)
        feed(m,1.0);assertEquals(0,feed(m,duration=800) { pose(missing=setOf(PoseJoint.leftKnee)) }.cycles)
        feed(m,duration=1600);feed(m,1.0)
        feed(m,duration=800) { pose(shift=180.0) }
        assertEquals(0,feed(m,duration=1600).cycles)
    }
    @Test fun headFootAndElbowCropsAreAllowedButHandAndHipAreRequired() {
        val m=HangingLegRaiseMotion();val cropped=setOf(PoseJoint.leftAnkle,PoseJoint.leftElbow)
        feed(m,duration=1600) { pose(missing=cropped) };feed(m,1.0) { pose(1.0,cropped) }
        assertEquals(1,feed(m) { pose(missing=cropped) }.cycles)
        for(joint in listOf(PoseJoint.leftWrist,PoseJoint.leftHip)) {
            assertEquals(HangingLegRaiseMotion.Phase.seekingStart,feed(HangingLegRaiseMotion(),duration=1600) { pose(missing=setOf(joint)) }.phase)
        }
    }
    @Test fun cameraClockAndAthleteLossCancelPendingEvidence() {
        for(fault in listOf("camera","clock","person")) {
            val m=HangingLegRaiseMotion();feed(m,duration=1600);feed(m,1.0)
            when(fault) {
                "camera" -> feed(m,camera=SideViewPushUpMotion.Camera.moved)
                "clock" -> { milliseconds+=1000;feed(m) }
                "person" -> feed(m,duration=1000) { pose().let { it.copy(people=it.people+it.people) } }
            }
            assertEquals(0,feed(m,duration=1600).cycles,fault)
        }
    }
    @Test fun recordsRecoverAndRetainTimingWithoutElbowOrFatigueClaims() {
        val w=HangingLegRaiseWorkout("legraise",100000)
        fun frames(lift: Double,duration: Int) { repeat(duration/20) { w.observe(pose(lift),SideViewPushUpMotion.Camera.ready);milliseconds+=20 } }
        frames(0.0,1600);repeat(10) { frames((it+1)/10.0,20) };frames(1.0,400)
        repeat(10) { frames(1-(it+1)/10.0,20) };frames(0.0,400);w.finish("manual")
        val writes=mutableListOf<HangingLegRaiseWorkout.Write>()
        while(w.nextWrite()!=null) { writes+=checkNotNull(w.nextWrite());w.acknowledgeWrite() }
        val record=writes.last().record
        assertTrue(writes.last().finalized);assertEquals(1,record.completedCount)
        assertEquals(ExerciseCounter.Exercise.legRaise,record.exercise)
        WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(),listOf(record)))
        WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(),listOf(record.copy(
            countPolicyVersion=1,reps=record.reps.map { it.copy(countPolicyVersion=1) }))))
        val recovered=writes.filterNot { it.finalized }.map { it.record }.reduce(WorkoutRecordCodec::mergeDraft)
        assertEquals(1,WorkoutRecordCodec.recover(recovered).completedCount)
        assertFalse(WorkoutEvidence.assess(record.completedReps.single()).angleComparisonEligible)
        assertNotNull(WorkoutJourney.set(record).outwardSeconds)
        assertEquals("Top",WorkoutSetPresentation.keyframes(record,0.0)[1].phase)
        assertNull(WorkoutSetPresentation.tempo(record).change)
        assertEquals("legraises",WorkoutActivityProgress.exerciseKey(record.exercise.title))
        assertEquals("legRaise",WorkoutVoice.command("start leg raises")?.exercise)
    }
}
