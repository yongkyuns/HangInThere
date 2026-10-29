package dev.hanginthere.core
import kotlin.test.*
import kotlin.math.*
class OverheadPullUpRecoveryIntegrationTests {
 private fun pose(ms:Long,rise:Double=0.0,degrees:Double=170.0,partial:Boolean=false,drop:Double=0.0,dx:Double=0.0):PoseResult {
  val h=350-rise;val off=h/(2*tan(degrees*PI/360));val points=listOf(250.0,650.0).flatMapIndexed {i,x ->val names=ArmMeasurement.Side.entries[i].joints
   listOf(Landmark(names[0],Point2D(x+dx,600-rise),.9),Landmark(names[1],Point2D(x+dx+if(i==0)-off else off,(850-rise+drop)/2),if(partial&&i==1).2 else .9),Landmark(names[2],Point2D(x+dx,250+drop),.9))
  }+listOf(Landmark(PoseJoint.leftHip,Point2D(250+dx,1000-rise),.9),Landmark(PoseJoint.rightHip,Point2D(650+dx,1000-rise),.9))
  return PoseResult(PresentationTime(ms,1000),ImageSize(900.0,1400.0),listOf(PoseObservation(points)),"authored-owner-recovery",1)
 }
 private fun feed(owner:OverheadPullUpWorkout,selection:BodyAthleteSelection,p:PoseResult,camera:OverheadPullUpMotion.Camera=OverheadPullUpMotion.Camera.ready,automaticStart:Boolean=true,automaticFinish:Boolean=false):OverheadPullUpWorkout.Snapshot {
  selection.observe(p);return owner.observe(selection,camera,automaticStart=automaticStart,automaticFinish=automaticFinish)
 }
 private fun roundTrip(record:WorkoutSetRecord,expected:Int) {
  assertEquals(expected,record.completedCount);assertEquals(4,record.countPolicyVersion);assertEquals(8,record.boundaryPolicyVersion)
  val state=WorkoutLibrary.register(WorkoutLibraryState(),listOf(record));val restored=WorkoutLibraryBackup.restore(WorkoutLibraryState(),WorkoutLibraryBackup.export(state))
  assertEquals(state.entries,restored.entries);assertEquals(expected,restored.entries.single().measured.completedCount)
 }
 @Test fun measuredPartialSupportPreservesOwnerAttemptButCannotSupplyEndpoint() {
  val owner=OverheadPullUpWorkout("partial-owner");val selection=BodyAthleteSelection()
  for(ms in 0L..1000L step 40)feed(owner,selection,pose(ms))
  for(ms in 1040L..1440L step 40)feed(owner,selection,pose(ms,140.0,100.0))
  for(ms in 1480L..2200L step 40) {
   val s=feed(owner,selection,pose(ms,140.0,100.0,partial=true));assertFalse(s.motion.observationUsable);assertEquals(0,owner.record().completedCount)
  }
  for(ms in 2240L..2720L step 40)feed(owner,selection,pose(ms))
  owner.finish("manual");val record=owner.record();roundTrip(record,1)
  val rep=record.completedReps.single();assertFalse(assertNotNull(rep.extremeAt) in 1.48..2.20);assertFalse(assertNotNull(rep.returnedAt) in 1.48..2.20)
 }
 @Test fun cameraRecoveryWaitsForFreshLowerAndOnlyLaterFullCycleSurvivesOwnerAndStorage() {
  val owner=OverheadPullUpWorkout("lower-owner");val selection=BodyAthleteSelection()
  val frames=(0L..1000L step 40).map {pose(it)}+(1040L..1800L step 40).map {pose(it,140.0,100.0)}+
   (1840L..2240L step 40).map {pose(it,100.0,120.0)}+(2280L..2680L step 40).map {pose(it,180.0,80.0)}+
   (2720L..3120L step 40).map {pose(it,100.0,120.0)}+(3160L..3520L step 40).map {pose(it)}+
   (3560L..3960L step 40).map {pose(it,140.0,100.0)}+(4000L..4440L step 40).map {pose(it)}
  for(p in frames){feed(owner,selection,p,if(p.timestamp.value in 1440L..1800L)OverheadPullUpMotion.Camera.unavailable else OverheadPullUpMotion.Camera.ready);if(p.timestamp.value<3560)assertEquals(0,owner.record().completedCount)}
  owner.finish("manual");roundTrip(owner.record(),1)
  val rep=owner.record().completedReps.single();assertTrue(rep.startedAt>=3.16);assertTrue(rep.returnedAt!!>=4.0)
 }
 @Test fun knownCameraMovementDuringPrivatePreparationDropsOldLowerConstraint() {
  val owner=OverheadPullUpWorkout("private-moved");val selection=BodyAthleteSelection()
  for(ms in 0L..1000L step 40)feed(owner,selection,pose(ms),automaticStart=false)
  assertFalse(owner.hasStarted)
  val moved=feed(owner,selection,pose(1040),OverheadPullUpMotion.Camera.moved,automaticStart=false)
  assertEquals(OverheadPullUpMotion.Phase.idle,moved.motion.phase)
  var last:OverheadPullUpWorkout.Snapshot?=null
  for(ms in 1080L..1880L step 40)last=feed(owner,selection,pose(ms,dx=80.0),automaticStart=false)
  assertEquals(OverheadPullUpMotion.Phase.ready,last!!.motion.phase)
  assertNotEquals("waitingForFreshLower",last!!.motion.reason);assertFalse(owner.hasStarted);assertEquals(0,owner.record().completedCount)
 }
 @Test fun partialSupportThenActualReleaseCannotBecomeCompletedReturn() {
  val owner=OverheadPullUpWorkout("partial-release");val selection=BodyAthleteSelection()
  for(ms in 0L..1000L step 40)feed(owner,selection,pose(ms),automaticFinish=true)
  for(ms in 1040L..1440L step 40)feed(owner,selection,pose(ms,140.0,100.0),automaticFinish=true)
  for(ms in 1480L..1760L step 40)feed(owner,selection,pose(ms,140.0,100.0,partial=true),automaticFinish=true)
  for(ms in 1800L..3200L step 40){if(owner.isFinished)break;feed(owner,selection,pose(ms,drop=700.0),automaticFinish=true)}
  assertTrue(owner.isFinished);assertEquals("disengaged",owner.record().endReason);roundTrip(owner.record(),0)
 }
}
