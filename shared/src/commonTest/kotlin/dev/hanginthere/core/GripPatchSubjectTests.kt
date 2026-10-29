package dev.hanginthere.core

import kotlin.test.*

class GripPatchSubjectTests {
    private fun person(raised: Boolean = true, dx: Double = 0.0) = PoseObservation(listOf(
        Landmark(PoseJoint.leftShoulder,Point2D(600.0+dx,400.0),.9),
        Landmark(PoseJoint.rightShoulder,Point2D(400.0+dx,400.0),.9),
        Landmark(PoseJoint.leftElbow,Point2D(650.0+dx,300.0),.9),
        Landmark(PoseJoint.rightElbow,Point2D(350.0+dx,300.0),.9),
        Landmark(PoseJoint.leftWrist,Point2D(700.0+dx,if(raised)100.0 else 700.0),.9),
        Landmark(PoseJoint.rightWrist,Point2D(300.0+dx,if(raised)100.0 else 700.0),.9)))
    private fun pose(t:Long,people:List<PoseObservation> = listOf(person())) =
        PoseResult(PresentationTime(t,1000),ImageSize(1000.0,1000.0),people,"authored2D",1)
    private fun acquire(s:GripPatchSubject, people:List<PoseObservation> = listOf(person())):GripPatchSubject.Plan {
        assertEquals("acquiringAthlete",s.observe(pose(0,people)).status)
        assertEquals("acquiringAthlete",s.observe(pose(125,people)).status)
        return s.observe(pose(250,people))
    }
    @Test fun originalUpperBody2DNeedsNoDepthHipsOrAuxiliaryPose() {
        val p=acquire(GripPatchSubject("a"));assertEquals("ready",p.status)
        assertEquals(2,p.targets.size);assertEquals("a",p.sourceId);assertEquals(0,p.personIndex)
    }
    @Test fun spotterAllowedAndReorderingDoesNotChangeAthlete() {
        val s=GripPatchSubject("a");assertEquals(1,acquire(s,listOf(person(false),person())).personIndex)
        val p=s.observe(pose(500,listOf(person(),person(false))))
        assertEquals("ready",p.status);assertEquals(0,p.personIndex)
        assertEquals(Point2D(700.0,100.0),p.targets.first().wrist)
    }
    @Test fun twoRaisedAthletesAndNoAthleteAreUnavailable() {
        assertEquals("competingAthletes",GripPatchSubject("a").observe(pose(0,listOf(person(),person()))).status)
        assertEquals("noRaisedHandsAthlete",GripPatchSubject("a").observe(pose(0,listOf(person(false)))).status)
    }
    @Test fun overlappingWristsAndCompetingWristInSearchRegionReject() {
        val overlap=person().copy(landmarks=person().landmarks.map {
            if(it.joint==PoseJoint.leftWrist)it.copy(position=Point2D(310.0,100.0)) else it })
        assertEquals("overlappingHands",GripPatchSubject("a").observe(pose(0,listOf(overlap))).status)
        val intruder=person(false).copy(landmarks=person(false).landmarks.map {
            if(it.joint==PoseJoint.leftWrist)it.copy(position=Point2D(700.0,110.0)) else it })
        assertEquals("competingHandInCrop",acquire(GripPatchSubject("a"),listOf(person(),intruder)).status)
    }
    @Test fun gapsClockReversalAndContextChangesReacquire() {
        val s=GripPatchSubject("a");acquire(s)
        assertEquals("sourceClockUnavailable",s.observe(pose(250)).status)
        assertEquals("acquiringAthlete",s.observe(pose(500)).status)
        assertEquals("acquiringAthlete",s.observe(pose(900)).status)
        assertEquals("acquiringAthlete",s.observe(pose(1000).copy(requestRevision=2)).status)
        assertEquals("sourceClockUnavailable",s.observe(pose(1100).copy(timestamp=PresentationTime(0,0))).status)
    }
    @Test fun dropoutAndDifferentAthleteNeverReuseOldCrop() {
        val s=GripPatchSubject("a");acquire(s)
        assertEquals("noRaisedHandsAthlete",s.observe(pose(500,emptyList())).status)
        assertEquals("acquiringAthlete",s.observe(pose(750)).status)
        assertEquals("acquiringAthlete",s.observe(pose(1000,listOf(person(dx=180.0)))).status)
    }
    @Test fun duplicateJointAndInvalidDimensionsReject() {
        assertEquals("noRaisedHandsAthlete",GripPatchSubject("a").observe(pose(0,listOf(person().copy(landmarks=person().landmarks+person().landmarks.first())))).status)
        assertEquals("invalidContext",GripPatchSubject("a").observe(pose(0).copy(imageSize=ImageSize(0.0,1000.0))).status)
    }
    private fun acquireV2(people:List<PoseObservation>):Pair<GripPatchSubjectV2,GripPatchSubject.Plan> {
        val s=GripPatchSubjectV2("a")
        assertEquals("acquiringAthlete",s.observe(pose(0,people)).status)
        assertEquals("acquiringAthlete",s.observe(pose(125,people)).status)
        return s to s.observe(pose(250,people))
    }
    @Test fun revisedSingleVisibleHandDoesNotInventOtherWrist() {
        val one=person().copy(landmarks=person().landmarks.filter{it.joint!=PoseJoint.rightWrist})
        val (s,p)=acquireV2(listOf(one))
        assertEquals("ready",p.status);assertEquals(2,p.policyVersion)
        assertEquals(listOf(ArmMeasurement.Side.left),p.targets.map{it.side})
        assertNull(p.targets.single().otherWrist)
        assertEquals("noRaisedHandsAthlete",s.observe(pose(500,emptyList())).status)
        assertEquals("acquiringAthlete",s.observe(pose(750,listOf(one))).status)
    }
    @Test fun revisedLowConfidenceWristNeedsObservedConfidentElbow() {
        val weak=person().copy(landmarks=person().landmarks.map{
            if(it.joint==PoseJoint.leftWrist)it.copy(confidence=.35) else it })
        assertEquals(2,acquireV2(listOf(weak)).second.targets.size)
        val unsupported=weak.copy(landmarks=weak.landmarks.filter{it.joint!=PoseJoint.leftElbow})
        assertEquals(listOf(ArmMeasurement.Side.right),acquireV2(listOf(unsupported)).second.targets.map{it.side})
    }
    @Test fun revisedOverlapIsRelativeToAthleteAndStillRejectsTouchingWrists() {
        val narrow=person().copy(landmarks=person().landmarks.map{
            when(it.joint){
                PoseJoint.leftShoulder->it.copy(position=Point2D(525.0,400.0))
                PoseJoint.rightShoulder->it.copy(position=Point2D(475.0,400.0))
                PoseJoint.leftWrist->it.copy(position=Point2D(535.0,100.0))
                PoseJoint.rightWrist->it.copy(position=Point2D(465.0,100.0))
                else->it
            }})
        assertEquals("ready",acquireV2(listOf(narrow)).second.status)
        val touching=narrow.copy(landmarks=narrow.landmarks.map{if(it.joint==PoseJoint.leftWrist)it.copy(position=Point2D(475.0,100.0)) else it})
        assertEquals("overlappingHands",GripPatchSubjectV2("a").observe(pose(0,listOf(touching))).status)
    }
    @Test fun revisedCompetingAthletesAndIntrudingSpotterStillReject() {
        assertEquals("competingAthletes",GripPatchSubjectV2("a").observe(pose(0,listOf(person(),person()))).status)
        val intruder=person(false).copy(landmarks=person(false).landmarks.map{
            if(it.joint==PoseJoint.leftWrist)it.copy(position=Point2D(700.0,110.0)) else it})
        assertEquals("competingHandInCrop",acquireV2(listOf(person(),intruder)).second.status)
    }
    @Test fun revisedSourceDiscontinuityReacquiresWithoutOldTargets() {
        val (s,_)=acquireV2(listOf(person()))
        assertEquals("sourceClockUnavailable",s.observe(pose(250)).status)
        assertEquals("acquiringAthlete",s.observe(pose(800)).status)
        assertEquals("acquiringAthlete",s.observe(pose(900).copy(requestRevision=3)).status)
        assertEquals("noRaisedHandsAthlete",s.observe(pose(1000,listOf(person(false)))).status)
    }
    @Test fun revisedPreservesSupportedBilateralPlansWhenTorsoJitters() {
        val original=GripPatchSubject("a");val revised=GripPatchSubjectV2("a")
        var ready=0
        for(i in 0L..12L){
            val base=person();val p=base.copy(landmarks=base.landmarks.map{
                if(it.joint==PoseJoint.leftShoulder || it.joint==PoseJoint.rightShoulder)
                    it.copy(position=Point2D(it.position.x,it.position.y+if(i%2==0L)0 else 170)) else it})
            val frame=pose(i*125,listOf(p));val a=original.observe(frame);val b=revised.observe(frame)
            if(a.status=="ready"){ready++;assertEquals(a.copy(policyVersion=2),b)}
        }
        assertTrue(ready>0)
    }
    @Test fun revisedDoesNotAcquireCloseSupportHandsBelowObservedHead() {
        val base=person();val support=base.copy(landmarks=base.landmarks.map{
            when(it.joint){
                PoseJoint.leftWrist->it.copy(position=Point2D(535.0,300.0))
                PoseJoint.rightWrist->it.copy(position=Point2D(465.0,300.0))
                else->it
            }}+Landmark(PoseJoint.nose,Point2D(500.0,200.0),.9))
        val s=GripPatchSubjectV2("a")
        for(t in listOf(0L,125L,250L))assertEquals("noRaisedHandsAthlete",s.observe(pose(t,listOf(support))).status)
    }
}
