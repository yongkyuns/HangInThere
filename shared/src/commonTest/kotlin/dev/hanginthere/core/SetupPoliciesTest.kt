package dev.hanginthere.core

import kotlin.test.*
import kotlin.math.*

class SetupPoliciesTest {
    private val size=ImageSize(1000.0,1000.0)
    private fun pose(seconds:Double,dip:Boolean=true):PoseResult {
        val shoulders=if(dip) listOf(Point2D(350.0,300.0),Point2D(650.0,300.0)) else listOf(Point2D(300.0,500.0),Point2D(700.0,500.0))
        val wrists=if(dip) listOf(Point2D(250.0,550.0),Point2D(750.0,550.0)) else listOf(Point2D(250.0,200.0),Point2D(750.0,200.0))
        val joints=listOf(PoseJoint.leftShoulder,PoseJoint.leftElbow,PoseJoint.leftWrist,PoseJoint.rightShoulder,PoseJoint.rightElbow,PoseJoint.rightWrist)
        val points=listOf(shoulders[0],Point2D((shoulders[0].x+wrists[0].x)/2,(shoulders[0].y+wrists[0].y)/2),wrists[0],shoulders[1],Point2D((shoulders[1].x+wrists[1].x)/2,(shoulders[1].y+wrists[1].y)/2),wrists[1])
        return PoseResult(PresentationTime((seconds*1000).roundToLong(),1000),size,listOf(PoseObservation(joints.zip(points).map{Landmark(it.first,it.second,1.0)})),"test",1)
    }
    private fun line(x:Double,y0:Double=590.0,y1:Double=700.0)=BarLineCandidate(BarSegment(Point2D(x,y0),Point2D(x,y1)),y1-y0)
    private fun rails()=listOf(listOf(line(250.0),line(258.0)),listOf(line(750.0),line(758.0)))
    private val identity=PhoneOrientationStability.Quaternion.create(0.0,0.0,0.0,1.0)!!
    private val stablePatches=listOf(StaticSceneStability.PatchTranslation(0.0,0.0),StaticSceneStability.PatchTranslation(.1,.1),StaticSceneStability.PatchTranslation(-.1,-.1))
    private fun frame(seconds:Double,dip:Boolean=true,candidates:List<List<BarLineCandidate>> = rails(),paused:Boolean=false)=WorkoutRuntime.FrameInput(pose(seconds,dip),candidates,identity,seconds,stablePatches,0.0,seconds,paused)

    @Test fun geometryPreservesObservedSupportAndRejectsCrossingEdges() {
        val p=BarSegment(Point2D(20.0,10.0),Point2D(100.0,10.0));val q=BarSegment(Point2D(40.0,16.0),Point2D(90.0,16.0))
        val pair=assertNotNull(BarFitter.pair(p,q,24.0))
        assertEquals(Point2D(40.0,10.0),pair.firstEdge.a);assertEquals(Point2D(90.0,10.0),pair.firstEdge.b)
        assertNull(BarFitter.pair(p,BarSegment(Point2D(20.0,7.0),Point2D(100.0,13.0)),24.0))
        assertEquals(50.0,p.perpendicularDistance(Point2D(150.0,60.0)))
        assertTrue(p.distance(Point2D(150.0,60.0))>50)
    }
    @Test fun lineFitterMergesFragmentsWithoutExtendingAndExcludesCropBorder() {
        val region=BarRegion(0.0,0.0,200.0,100.0)
        val contours=listOf(listOf(Point2D(20.0,25.0),Point2D(80.0,25.0)),listOf(Point2D(90.0,25.0),Point2D(170.0,25.0)),listOf(Point2D(0.0,0.0),Point2D(200.0,0.0)))
        val candidates=BarLineFitter.candidates(contours,region,ImageSize(200.0,100.0))
        val edge=assertNotNull(candidates.singleOrNull()).edge
        assertEquals(Point2D(20.0,25.0),edge.a);assertEquals(Point2D(170.0,25.0),edge.b)
        assertFailsWith<BarFitException>{BarLineFitter.candidates(contours,BarRegion(0.0,0.0,300.0,100.0),ImageSize(200.0,100.0))}
    }
    @Test fun dipRequiresBothActuallyObservedRailSilhouettes() {
        val policy=AutomaticDipRails()
        for(t in listOf(0.0,.3,.6,.9,1.2)) assertNull(policy.observe(pose(t),ArmMeasurement.Side.left,listOf(listOf(line(250.0)),listOf(line(750.0)))))
        for(t in listOf(1.5,1.8,2.1)) assertNull(policy.observe(pose(t),ArmMeasurement.Side.left,rails()))
        val locked=assertNotNull(policy.observe(pose(2.5),ArmMeasurement.Side.left,rails()))
        assertEquals(ConfirmedBar.Role.leftDipRail,locked.role);assertEquals(rails()[0][0].edge,locked.referenceEdge)
        assertNull(locked.oppositeEdge);assertEquals(AutomaticPullUpBar.State.locked,policy.state)
    }
    @Test fun dipRejectsForearmsTorsoContoursAndOnlyOneHandle() {
        val policy=AutomaticDipRails()
        for(t in listOf(0.0,.3,.6,.9,1.2)) assertNull(policy.observe(pose(t),ArmMeasurement.Side.right,listOf(listOf(line(325.0),line(333.0)),rails()[1])))
        for(t in listOf(1.5,1.8,2.1,2.4)) assertNull(policy.observe(pose(t),ArmMeasurement.Side.right,listOf(listOf(line(250.0,520.0,700.0),line(258.0,520.0,700.0)),rails()[1])))
        assertNull(policy.observe(pose(2.7),ArmMeasurement.Side.left,listOf(rails()[0],emptyList())))
    }
    @Test fun railWitnessSurvivesPartialOcclusionButNotUnrelatedEdges() {
        val policy=AutomaticDipRails();assertNull(policy.observe(pose(0.0),ArmMeasurement.Side.right,rails()))
        val partial=listOf(listOf(line(250.0,600.0,690.0)),listOf(line(750.0,600.0,690.0)))
        assertNull(policy.observe(pose(.3),ArmMeasurement.Side.right,partial));assertNull(policy.observe(pose(.6),ArmMeasurement.Side.right,partial))
        assertNotNull(policy.observe(pose(.9),ArmMeasurement.Side.right,partial))
        policy.reset();assertNull(policy.observe(pose(1.2),ArmMeasurement.Side.left,partial))
    }
    @Test fun referenceDwellIsOrientationInvariantAndDoesNotEarnDuplicateTime() {
        val policy=AutomaticReferenceStability();val edge=rails()[0][0].edge;val reversed=BarSegment(edge.b,edge.a)
        assertTrue(AutomaticReferenceStability.sameLine(edge,reversed,1.0))
        val hand=Point2D(250.0,550.0)
        for(t in listOf(0.0,.3,.6)) assertFalse(policy.observe(listOf(edge),listOf(hand),pose(t)))
        assertFalse(policy.observe(listOf(reversed),listOf(hand),pose(.6)))
        for(t in listOf(.9,1.2,1.5)) assertFalse(policy.observe(listOf(edge),listOf(hand),pose(t)))
        assertTrue(policy.observe(listOf(edge),listOf(hand),pose(1.8)))
        assertFalse(policy.observe(listOf(edge),listOf(hand),pose(3.0)))
    }
    @Test fun pullupDistinguishesThinBarEdgesFromSeparateBars() {
        val grip=assertNotNull(AutomaticPullUpBar.grip(pose(0.0,false)))
        fun horizontal(y:Double)=BarLineCandidate(BarSegment(Point2D(200.0,y),Point2D(800.0,y)),600.0)
        assertEquals(horizontal(195.0).edge,AutomaticPullUpBar.select(listOf(horizontal(195.0),horizontal(200.0)),grip,size))
        assertNull(AutomaticPullUpBar.select(listOf(horizontal(150.0),horizontal(195.0)),grip,size))
    }
    @Test fun identityRequiresConsecutiveExtendedPoseAndLocksForSet() {
        val policy=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4)) assertNull(policy.observe(pose(t)))
        assertEquals(ExerciseCounter.Exercise.dip,policy.observe(pose(.6)))
        assertTrue(policy.lock());assertEquals(ExerciseCounter.Exercise.dip,policy.observe(pose(.8,false)))
        policy.reset();assertFalse(policy.lock());assertNull(policy.observe(pose(2.0)));assertNull(policy.observe(pose(2.6)))
    }
    @Test fun countdownPausesOnMissingFramesOrSettingsAndStartsOnlyOnce() {
        val policy=HandsFreeSetStart()
        for(i in 0..10) assertFalse(policy.observe(i*.2,true))
        assertFalse(policy.observe(2.2,true,paused=true));assertEquals(HandsFreeSetStart.Phase.waiting,policy.state.phase)
        for(i in 0..14) assertFalse(policy.observe(3+i*.2,true))
        assertTrue(policy.observe(6.0,true));assertFalse(policy.observe(6.2,true))
        policy.reset();policy.observe(0.0,true);policy.observe(.6,true);assertEquals(3,policy.state.seconds)
        policy.observe(.6,true);assertEquals(HandsFreeSetStart.Phase.waiting,policy.state.phase)
    }
    @Test fun orientationRotationDwellIsLatchedAndTimestampOrdered() {
        val policy=PhoneOrientationStability();assertTrue(policy.calibrate(identity,0.0))
        val rotated=PhoneOrientationStability.Quaternion.create(0.0,0.0,sin(PI/180),cos(PI/180))!!
        assertEquals(PhoneOrientationStability.State.stable,policy.observe(rotated,.1))
        assertEquals(PhoneOrientationStability.State.stable,policy.observe(rotated,.1))
        assertEquals(PhoneOrientationStability.State.moved,policy.observe(rotated,.4))
        assertEquals(PhoneOrientationStability.State.moved,policy.observe(identity,.5))
        assertNull(PhoneOrientationStability.Quaternion.create(0.0,0.0,0.0,0.0))
    }
    @Test fun staticSceneRequiresIndependentScaleAndTranslationAndRejectsOutlier() {
        val policy=StaticSceneStability();assertTrue(policy.calibrate(size))
        assertEquals(StaticSceneStability.State.calibrating,policy.observe(stablePatches,null,0.0))
        assertEquals(StaticSceneStability.State.stable,policy.observe(stablePatches+StaticSceneStability.PatchTranslation(900.0,900.0),0.0,.1))
        assertEquals(3,policy.latestConsensusPatches)
        val moved=List(3){StaticSceneStability.PatchTranslation(20.0,0.0)}
        assertEquals(StaticSceneStability.State.stable,policy.observe(moved,0.0,.2))
        assertEquals(StaticSceneStability.State.moved,policy.observe(moved,0.0,.5))
        assertEquals(StaticSceneStability.MovementKind.translation,policy.movementKind)
    }
    @Test fun runtimeNeverStartsFromPoseIdentityWithoutImageApparatus() {
        val runtime=WorkoutRuntime()
        for(i in 0..70) runtime.observe(frame(i*.1,candidates=emptyList()))
        assertEquals(ExerciseCounter.Exercise.dip,runtime.snapshot.exercise)
        assertNull(runtime.snapshot.referenceEdge);assertNull(runtime.snapshot.countdownSeconds)
        assertEquals(LiveSetSession.Phase.idle,runtime.snapshot.phase)
    }
    @Test fun runtimeAcquiresAndStartsWithActualCameraEvidenceThenLocksIdentity() {
        val runtime=WorkoutRuntime()
        for(i in 0..55) runtime.observe(frame(i*.1))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        assertEquals(ExerciseCounter.Exercise.dip,runtime.snapshot.exercise)
        assertTrue(runtime.session.analyzedFrames>0)
        runtime.observe(frame(5.6,false));assertEquals(ExerciseCounter.Exercise.dip,runtime.snapshot.exercise)
        runtime.stop();runtime.nextSet();assertEquals(LiveSetSession.Phase.idle,runtime.snapshot.phase);assertNull(runtime.snapshot.exercise);assertNull(runtime.snapshot.referenceEdge)
    }
    @Test fun runtimeRejectsUnavailableCameraEvidenceAndInterruptsChangedGeometry() {
        val runtime=WorkoutRuntime()
        for(i in 0..50) runtime.observe(frame(i*.1).copy(globalScaleFraction=null))
        assertEquals(LiveSetSession.Phase.idle,runtime.snapshot.phase);assertNull(runtime.snapshot.countdownSeconds)
        for(i in 51..90) runtime.observe(frame(i*.1))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        runtime.observe(frame(9.1).copy(pose=pose(9.1).copy(imageSize=ImageSize(500.0,1000.0))))
        assertEquals(LiveSetSession.Phase.finished,runtime.snapshot.phase);assertEquals(LiveSetSession.EndReason.setupInvalidated,runtime.session.endReason)
    }
    @Test fun runtimeWatchdogAndStaleSensorCannotEarnCountdown() {
        val runtime=WorkoutRuntime()
        for(i in 0..20) runtime.observe(frame(i*.1))
        assertNotNull(runtime.snapshot.countdownSeconds)
        runtime.tick(2.6);assertNull(runtime.snapshot.countdownSeconds)
        runtime.observe(frame(2.7).copy(motionTimestamp=1.0));assertNull(runtime.snapshot.countdownSeconds)
        assertEquals(LiveSetSession.Phase.idle,runtime.snapshot.phase)
    }
    @Test fun replayConsumesSourceZeroAndNextFrameWithoutLiveCountdownOrQueuedSkip() {
        val runtime=WorkoutRuntime()
        for(i in 0..20) runtime.observe(frame(i*.1))
        assertTrue(runtime.rewindReplay(identity,0.0))
        assertTrue(runtime.snapshot.isReplayReview)
        runtime.observe(frame(0.0))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        assertEquals(0.0,runtime.session.firstSourceSeconds)
        assertEquals(1,runtime.session.analyzedFrames)
        assertNull(runtime.snapshot.countdownSeconds)
        runtime.observe(frame(.1));assertEquals(2,runtime.session.analyzedFrames)
        runtime.observe(frame(.2));assertEquals(3,runtime.session.analyzedFrames)
        assertEquals(.2,runtime.session.lastSourceSeconds)
    }
    @Test fun replayRewindRetainsObservedReferenceButRequiresFreshActualScene() {
        val runtime=WorkoutRuntime()
        for(i in 0..20) runtime.observe(frame(i*.1))
        val edge=assertNotNull(runtime.snapshot.referenceEdge)
        assertTrue(runtime.rewindReplay(identity,0.0))
        assertEquals(edge,runtime.snapshot.referenceEdge)
        runtime.observe(frame(0.0).copy(globalScaleFraction=null))
        assertNull(runtime.snapshot.countdownSeconds)
        runtime.observe(frame(.1))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        assertEquals(.1,runtime.session.firstSourceSeconds)
        assertEquals(1,runtime.session.analyzedFrames)
        assertNull(runtime.snapshot.countdownSeconds)
        runtime.observe(frame(.2));assertEquals(2,runtime.session.analyzedFrames)
        for(i in 3..45) runtime.observe(frame(i*.1))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        assertEquals(edge,runtime.snapshot.referenceEdge)
    }
}
