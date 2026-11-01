package dev.hanginthere.core

import kotlin.math.*
import kotlin.test.*

class LyingLegRaiseTests {
    private var milliseconds=0L
    private fun pose(lift: Double=0.0, missing: Set<PoseJoint> = emptySet(), sitUp: Boolean=false, tuck: Boolean=false): PoseResult {
        val angle=lift*70*PI/180
        val hip=Point2D(430.0,430.0);val knee=Point2D(430+160*cos(angle),430-160*sin(angle))
        val ankle=if(tuck) Point2D(knee.x-100,knee.y+100) else Point2D(430+320*cos(angle),430-320*sin(angle))
        val points=listOf(PoseJoint.leftShoulder to if(sitUp) Point2D(350.0,220.0) else Point2D(200.0,430.0),
            PoseJoint.leftHip to hip,PoseJoint.leftKnee to knee,PoseJoint.leftAnkle to ankle)
        return PoseResult(PresentationTime(milliseconds,1000),ImageSize(1000.0,600.0),listOf(PoseObservation(
            points.filter { it.first !in missing }.map { Landmark(it.first,it.second,.95) })),"lying-legraise-test",1)
    }
    private fun feed(m: LyingLegRaiseMotion,lift: Double=0.0,duration: Int=400,
        camera: SideViewPushUpMotion.Camera=SideViewPushUpMotion.Camera.ready,
        change: ()->PoseResult={pose(lift)}): LyingLegRaiseMotion.Snapshot {
        var s: LyingLegRaiseMotion.Snapshot?=null
        repeat(duration/20) { s=m.observe(change(),camera);milliseconds+=20 };return checkNotNull(s)
    }
    @Test fun steadyStartFullLiftAndReturnCountWithoutArmsOrHead() {
        val m=LyingLegRaiseMotion()
        val ready=feed(m,duration=1600);assertEquals(LyingLegRaiseMotion.Phase.ready,ready.phase,ready.title)
        assertFalse(feed(m).setStarted)
        repeat(3) { feed(m,1.0);assertEquals(it+1,feed(m).cycles) }
        var ended: LyingLegRaiseMotion.SetSummary?=null
        repeat(50) { ended=feed(m,duration=20) { pose(sitUp=true) }.endedSet ?: ended }
        assertEquals(3,ended?.reps?.size)
    }
    @Test fun slightlyRaisedShouldersAndHoveringLegsCanEstablishAStart() {
        val m=LyingLegRaiseMotion()
        val state=feed(m,.2,duration=1600) { pose(.2).let { p -> p.copy(people=p.people.map { person ->
            person.copy(landmarks=person.landmarks.map { if(it.joint==PoseJoint.leftShoulder) it.copy(position=Point2D(200.0,360.0)) else it })
        }) } }
        assertEquals(LyingLegRaiseMotion.Phase.ready,state.phase,state.title)
    }
    @Test fun shortLowEndpointAfterStableTorsoCanAcquireButRaisedMotionCannot() {
        val m=LyingLegRaiseMotion();feed(m,1.0,duration=1600)
        assertEquals(LyingLegRaiseMotion.Phase.ready,feed(m,duration=180).phase)
        feed(m,1.0);assertEquals(1,feed(m).cycles)
    }
    @Test fun occludedFarHipDoesNotDiscardTheVisibleNearSide() {
        // Authored regression for the phone diagnostic: the far hip falls
        // below confidence while a complete near-side torso/leg remains visible.
        fun bilateral(lift: Double, weakFarHip: Boolean): PoseResult {
            val p=pose(lift)
            val left=p.people.single().landmarks
            val right=left.map { landmark ->
                val joint=PoseJoint.valueOf(landmark.joint.name.replace("left","right"))
                landmark.copy(joint=joint,position=Point2D(landmark.position.x-
                    if(joint==PoseJoint.rightShoulder) 20 else 0,landmark.position.y+18))
            }
            return p.copy(people=listOf(PoseObservation(left.map {
                if(weakFarHip && it.joint==PoseJoint.leftHip) it.copy(confidence=.29) else it
            }+right)))
        }
        val m=LyingLegRaiseMotion()
        val ready=feed(m,duration=1600) { bilateral(0.0,false) }
        assertEquals(ArmMeasurement.Side.right,ready.side)
        val partial=feed(m,.5) { bilateral(.5,true) }
        assertEquals(LyingLegRaiseMotion.Phase.lifting,partial.phase)
        assertFalse(partial.setStarted)
        assertTrue(partial.progress!! in 0.0..1.0)
        assertEquals("Lift higher",partial.title)
        feed(m,1.0) { bilateral(1.0,true) }
        assertEquals(1,feed(m) { bilateral(0.0,true) }.cycles)
        assertEquals(1,feed(m,duration=800) { bilateral(0.0,true).let { p ->
            p.copy(people=p.people.map { person -> person.copy(landmarks=person.landmarks.filterNot {
                it.joint==PoseJoint.rightHip
            }) })
        } }.cycles)
        assertEquals(LyingLegRaiseMotion.Phase.seekingStart,feed(m,duration=20) {
            bilateral(0.0,true).let { it.copy(people=it.people+it.people) }
        }.phase)
    }
    @Test fun raisedSetupShallowLiftsTucksAndTorsoRiseCannotCount() {
        val m=LyingLegRaiseMotion()
        assertEquals(0,feed(m,1.0,duration=1600).cycles)
        feed(m,duration=1600);feed(m,.35);assertEquals(0,feed(m).cycles)
        feed(m,1.0) { pose(1.0,tuck=true) };assertEquals(0,feed(m).cycles)
        feed(m,1.0,duration=600) { pose(1.0,sitUp=true) };assertEquals(0,feed(m,duration=1600).cycles)
    }
    @Test fun tuckAndLaterExtensionCannotBridgeIntoALegRaise() {
        val m=LyingLegRaiseMotion();feed(m,duration=1600);feed(m,1.0)
        feed(m,1.0,duration=200) { pose(1.0,tuck=true) }
        feed(m,1.0);assertEquals(0,feed(m).cycles)
        feed(m,1.0);assertEquals(1,feed(m).cycles)
    }
    @Test fun aCroppedFootIsAllowedButMissingKneeOrHipCannotCompleteAnEndpoint() {
        val m=LyingLegRaiseMotion();val foot=setOf(PoseJoint.leftAnkle)
        feed(m,duration=1600) { pose(missing=foot) };feed(m,1.0) { pose(1.0,foot) }
        assertEquals(1,feed(m) { pose(missing=foot) }.cycles)
        for(joint in listOf(PoseJoint.leftKnee,PoseJoint.leftHip)) {
            val blocked=LyingLegRaiseMotion();feed(blocked,duration=1600);feed(blocked,1.0)
            assertEquals(0,feed(blocked,duration=800) { pose(missing=setOf(joint)) }.cycles)
            assertEquals(0,feed(blocked,duration=1600).cycles)
        }
    }
    @Test fun cameraSourceAndAthleteFaultsCannotBridgeAnIncompleteRep() {
        for(fault in listOf("camera","clock","person")) {
            val m=LyingLegRaiseMotion();feed(m,duration=1600);feed(m,1.0)
            when(fault) {
                "camera" -> feed(m,camera=SideViewPushUpMotion.Camera.moved)
                "clock" -> { milliseconds+=1000;feed(m) }
                "person" -> feed(m,duration=1000) { pose().let { it.copy(people=it.people+it.people) } }
            }
            assertEquals(0,feed(m,duration=1600).cycles,fault)
        }
    }
    @Test fun durableRecoveryTimingAndGoalsStaySeparateFromHangingRaises() {
        val w=LyingLegRaiseWorkout("lyinglegraise",100000)
        fun frames(lift: Double,duration: Int) { repeat(duration/20) { w.observe(pose(lift),SideViewPushUpMotion.Camera.ready);milliseconds+=20 } }
        frames(0.0,1600);repeat(10) { frames((it+1)/10.0,20) };frames(1.0,400)
        repeat(10) { frames(1-(it+1)/10.0,20) };frames(0.0,400);w.finish("manual")
        val writes=mutableListOf<LyingLegRaiseWorkout.Write>()
        while(w.nextWrite()!=null) { writes+=checkNotNull(w.nextWrite());w.acknowledgeWrite() }
        val record=writes.last().record
        assertTrue(writes.last().finalized);assertEquals(1,record.completedCount)
        assertEquals(ExerciseCounter.Exercise.lyingLegRaise,record.exercise)
        WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(),listOf(record)))
        WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(),listOf(record.copy(
            countPolicyVersion=1,reps=record.reps.map { it.copy(countPolicyVersion=1) }))))
        val recovered=writes.filterNot { it.finalized }.map { it.record }.reduce(WorkoutRecordCodec::mergeDraft)
        assertEquals(1,WorkoutRecordCodec.recover(recovered).completedCount)
        assertFalse(WorkoutEvidence.assess(record.completedReps.single()).angleComparisonEligible)
        assertNotNull(WorkoutJourney.set(record).outwardSeconds)
        assertEquals("Top",WorkoutSetPresentation.keyframes(record,0.0)[1].phase)
        assertNull(WorkoutSetPresentation.tempo(record).change)
        assertEquals("lyinglegraises",WorkoutActivityProgress.exerciseKey(record.exercise.title))
        assertNotEquals(WorkoutActivityProgress.exerciseKey("Hanging leg raises"),WorkoutActivityProgress.exerciseKey(record.exercise.title))
        assertEquals("lyingLegRaise",WorkoutVoice.command("start lying leg raises")?.exercise)
        assertEquals("lyingLegRaise",WorkoutVoice.command("start lying raises")?.exercise)
    }
}
