package dev.hanginthere.core
import kotlin.test.*
import kotlin.math.*
class OverheadPullUpDeadlineOwnerTests {
 private fun pose(ms:Long,rise:Double=0.0,missing:Boolean=false):PoseResult {
  val length=350/(2*sin(85*PI/180));val h=350-rise;val offset=sqrt(length*length-h*h/4)
  val points=listOf(250.0,650.0).flatMapIndexed {i,x ->val j=ArmMeasurement.Side.entries[i].joints
   listOf(Landmark(j[0],Point2D(x,600-rise),.9),Landmark(j[1],Point2D(x+if(i==0)-offset else offset,(850-rise)/2),if(missing).2 else .9),Landmark(j[2],Point2D(x,250.0),.9))
  }+listOf(Landmark(PoseJoint.leftHip,Point2D(250.0,1000-rise),.9),Landmark(PoseJoint.rightHip,Point2D(650.0,1000-rise),.9))
  return PoseResult(PresentationTime(ms,1000),ImageSize(900.0,1400.0),listOf(PoseObservation(points)),"authored-owner-deadline",1)
 }
 private fun frames()=(0L..1000L step 40).map {pose(it)}+(1040L..1440L step 40).map {pose(it,140.0)}+
  (1480L..1760L step 40).map {pose(it,70*(1+cos(PI*(it-1440)/360)),missing=true)}+
  (1800L..2600L step 40).map {pose(it,150*(1-cos(2*PI*(it-1800)/800)))}+(2640L..2800L step 40).map {pose(it)}
 private fun feed(owner:OverheadPullUpWorkout,selection:BodyAthleteSelection,p:PoseResult,camera:OverheadPullUpMotion.Camera=OverheadPullUpMotion.Camera.ready):OverheadPullUpWorkout.Snapshot {
  selection.observe(p);return owner.observe(selection,camera,automaticFinish=false)
 }
 @Test fun firstTwoFreshLowerFramesRecoverOwnerAndSavedFutureCycle() {
  val owner=OverheadPullUpWorkout("deadline-owner");val selection=BodyAthleteSelection();var merged:WorkoutSetRecord?=null
  val prefixes=mutableListOf<Pair<Double,Int>>();val fullTimes=mutableSetOf<Double>()
  for(p in frames()) {
   val state=feed(owner,selection,p);prefixes+=p.timestamp.seconds to owner.record().completedCount
   if(p.timestamp.value !in 1480L..1760L)fullTimes+=p.timestamp.seconds
   owner.draftDeltaJson()?.let {merged=WorkoutRecordCodec.mergeDraft(merged,WorkoutRecordCodec.json.decodeFromString(WorkoutSetRecord.serializer(),it))}
   if(p.timestamp.value<=1840)assertEquals(0,owner.record().completedCount)
   if(p.timestamp.value==1800L)assertEquals("waitingForFreshLower",state.motion.reason)
   if(p.timestamp.value==1840L)assertEquals("freshLowerRecovered",state.motion.reason)
  }
  owner.finish("manual");val record=owner.record();assertEquals(1,record.completedCount);assertEquals(1,merged!!.completedCount)
  assertEquals(4,record.countPolicyVersion);assertEquals(8,record.boundaryPolicyVersion)
  val rep=record.completedReps.single();assertTrue(rep.startedAt>=1.84)
  assertTrue(listOfNotNull(rep.startedAt,rep.extremeAt,rep.acceptedAt,rep.returnedAt,rep.countConfirmedAt).all {it in fullTimes})
  for((time,count) in prefixes)assertEquals(count,record.completedReps.count {it.countConfirmedAt!!<=time},"at $time")
  val state=WorkoutLibrary.register(WorkoutLibraryState(),listOf(record));val restored=WorkoutLibraryBackup.restore(WorkoutLibraryState(),WorkoutLibraryBackup.export(state));assertEquals(state.entries,restored.entries)
 }
 @Test fun missingSecondFreshLowerCannotBorrowOldWitness() {
  val owner=OverheadPullUpWorkout("deadline-missing");val selection=BodyAthleteSelection()
  for(original in frames()) {
   val p=if(original.timestamp.value==1840L)pose(1840,150*(1-cos(2*PI*40/800)),missing=true) else original
   feed(owner,selection,p)
  }
  owner.finish("manual");assertEquals(0,owner.record().completedCount)
 }
 @Test fun knownCameraMovementDuringRecoveryCannotRestoreOldAttempt() {
  val owner=OverheadPullUpWorkout("deadline-moved");val selection=BodyAthleteSelection()
  for(p in frames().filter {it.timestamp.value<=1800})feed(owner,selection,p)
  feed(owner,selection,pose(1840),OverheadPullUpMotion.Camera.moved)
  assertTrue(owner.isFinished);assertEquals("setupInvalidated",owner.record().endReason);assertEquals(0,owner.record().completedCount)
 }
}
