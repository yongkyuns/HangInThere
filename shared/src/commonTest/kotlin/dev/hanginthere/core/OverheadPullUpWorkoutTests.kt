package dev.hanginthere.core

import kotlin.test.*
import kotlin.math.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class OverheadPullUpWorkoutTests {
    private fun pose(ms: Long,rise: Double = 0.0,degrees: Double = 170.0,wristDrop: Double = 0.0,
        omitted: Set<PoseJoint> = emptySet()): PoseResult {
        val height = 350-rise
        val offset = height/(2*tan(degrees*PI/360))
        val points = listOf(250.0,650.0).flatMapIndexed { i,x ->
            val chain = ArmMeasurement.Side.entries[i].joints
            listOf(Landmark(chain[0],Point2D(x,600-rise),.9),
                Landmark(chain[1],Point2D(x+if(i==0)-offset else offset,(600-rise+250+wristDrop)/2),.9),
                Landmark(chain[2],Point2D(x,250+wristDrop),.9))
        } + listOf(Landmark(PoseJoint.leftHip,Point2D(250.0,1000-rise),.9),
            Landmark(PoseJoint.rightHip,Point2D(650.0,1000-rise),.9))
        return PoseResult(PresentationTime(ms,1000),ImageSize(900.0,1400.0),
            listOf(PoseObservation(points.filterNot { it.joint in omitted })),"authored",1)
    }
    private fun cycle(offset: Long = 0) = (0L..1000L step 40).map { pose(it+offset) } +
        (1040L..1360L step 40).map { pose(it+offset,140.0,100.0) } +
        (1400L..1800L step 40).map { pose(it+offset) }
    // Complete at an actual source frame before qualification, then invalidate
    // private support. Proof is public only at offset+250ms.
    private fun privateCycle(offset: Long) = listOf(pose(offset),pose(offset+1,wristDrop=-35.0)) +
        (2L..20L).map { pose(offset+it,140.0,100.0) } +
        (21L..145L).map { pose(offset+it,wristDrop=-35.0) } +
        (146L..400L).map { pose(offset+it,wristDrop=35.0) }
    private fun observe(owner: OverheadPullUpWorkout,selection: BodyAthleteSelection,p: PoseResult,
        camera: OverheadPullUpMotion.Camera = OverheadPullUpMotion.Camera.ready,
        automaticStart: Boolean = true): OverheadPullUpWorkout.Snapshot {
        selection.observe(p)
        return owner.observe(selection,camera,automaticFinish=false,automaticStart=automaticStart)
    }
    @Test fun completedSourceCycleHasOneSavedCountWithoutAnApparatusReference() {
        val owner = OverheadPullUpWorkout("source",1000)
        val selection = BodyAthleteSelection()
        var merged: WorkoutSetRecord? = null
        for(p in cycle()) {
            observe(owner,selection,p)
            owner.draftDeltaJson()?.let { merged = WorkoutRecordCodec.mergeDraft(merged,WorkoutRecordCodec.json.decodeFromString(it)) }
        }
        assertTrue(owner.hasStarted); assertEquals(1,owner.record().completedCount)
        assertEquals(1,merged!!.completedCount)
        owner.finish("manual")
        val record = owner.record()
        assertEquals(OverheadPullUpMotion.countMethod,record.countMethod)
        assertEquals(4,record.countPolicyVersion); assertEquals(8,record.boundaryPolicyVersion)
        assertNull(record.referenceEdge); assertNotNull(record.trackedAtEpochMilliseconds)
        val rep = record.reps.single { it.isCompleteForAnalytics }
        assertEquals(rep.returnedAt,rep.countConfirmedAt)
        assertTrue(rep.startedAt < rep.extremeAt!! && rep.extremeAt!! < rep.returnedAt!!)
        val state = WorkoutLibrary.register(WorkoutLibraryState(),listOf(record))
        val restored = WorkoutLibraryBackup.restore(WorkoutLibraryState(),WorkoutLibraryBackup.export(state))
        assertEquals(state.entries,restored.entries)
        assertEquals(rep.countConfirmedAt,restored.entries.single().measured.reps.single().countConfirmedAt)
    }
    @Test fun completedPrivateCycleSurvivesLaterSupportResetAndRetainsBothSourceTimes() {
        val owner = OverheadPullUpWorkout("private")
        val selection = BodyAthleteSelection()
        val frames = (0L..600L step 40).map { pose(it) }+privateCycle(601)
        val actualTimes = frames.map { it.timestamp.seconds }.toSet()
        val rows = frames.map { p -> p.timestamp.seconds to observe(owner,selection,p) }
        assertTrue(owner.hasStarted)
        val rep = owner.record().reps.single { it.isCompleteForAnalytics }
        assertEquals(.85,rep.countConfirmedAt)
        assertTrue(rep.returnedAt!! < rep.countConfirmedAt!!)
        assertTrue(listOfNotNull(rep.startedAt,rep.extremeAt,rep.acceptedAt,rep.returnedAt,rep.countConfirmedAt).all { it in actualTimes })
        assertTrue(rows.filter { it.first < rep.countConfirmedAt!! }.none { it.second.started })
        assertEquals(1,owner.record().completedCount)
    }
    @Test fun midSetRequalificationDoesNotRetroactivelyPublishAnEarlierCountOrRewindAnalysis() {
        val owner = OverheadPullUpWorkout("midset")
        val selection = BodyAthleteSelection()
        val rows = mutableListOf<Pair<Double,Int>>()
        var merged: WorkoutSetRecord? = null
        fun feed(p: PoseResult,camera: OverheadPullUpMotion.Camera = OverheadPullUpMotion.Camera.ready) {
            observe(owner,selection,p,camera)
            rows.add(p.timestamp.seconds to owner.record().completedCount)
            owner.draftDeltaJson()?.let { merged = WorkoutRecordCodec.mergeDraft(merged,WorkoutRecordCodec.json.decodeFromString(it)) }
        }
        cycle().forEach { feed(it) }
        feed(pose(1840),OverheadPullUpMotion.Camera.unavailable)
        // Force the full private-history qualification path: retained lower
        // recovery is a separate policy and has its own prefix check below.
        owner.interrupt("explicitSupportInvalidation")
        privateCycle(1880).forEach { feed(it) }
        val record = owner.record()
        assertEquals(2,record.completedCount)
        assertEquals(2,merged!!.completedCount)
        val second = record.completedReps.last()
        assertEquals(2.13,second.countConfirmedAt)
        assertTrue(second.returnedAt!! < second.countConfirmedAt!!)
        for((at,count) in rows) assertEquals(count,record.completedReps.count { it.countConfirmedAt!! <= at },"at source $at")
        assertTrue(rows.filter { it.first in 1.84..2.129 }.all { it.second==1 })
        assertFalse(record.reps.any { it.closeReason=="nonIncreasingTimestamp" })
        owner.finish("manual")
        WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(),listOf(owner.record())))
    }
    @Test fun retainedLowerRecoveryPublishesOnlyAtFreshObservedCompletionWithoutRewinding() {
        val owner=OverheadPullUpWorkout("retained-midset");val selection=BodyAthleteSelection()
        val rows=mutableListOf<Pair<Double,Int>>();val actualTimes=mutableSetOf<Double>()
        var merged:WorkoutSetRecord?=null
        fun feed(p:PoseResult,camera:OverheadPullUpMotion.Camera=OverheadPullUpMotion.Camera.ready) {
            observe(owner,selection,p,camera);actualTimes+=p.timestamp.seconds
            rows+=p.timestamp.seconds to owner.record().completedCount
            owner.draftDeltaJson()?.let {merged=WorkoutRecordCodec.mergeDraft(merged,WorkoutRecordCodec.json.decodeFromString(it))}
        }
        cycle().forEach {feed(it)};feed(pose(1840),OverheadPullUpMotion.Camera.unavailable)
        privateCycle(1880).forEach {feed(it)}
        val record=owner.record();assertEquals(2,record.completedCount);assertEquals(2,merged!!.completedCount)
        val second=record.completedReps.last();assertEquals(2.021,second.countConfirmedAt);assertEquals(second.returnedAt,second.countConfirmedAt)
        assertTrue(listOfNotNull(second.startedAt,second.extremeAt,second.acceptedAt,second.returnedAt,second.countConfirmedAt).all {it in actualTimes})
        for((at,count) in rows)assertEquals(count,record.completedReps.count {it.countConfirmedAt!!<=at},"at source $at")
        assertTrue(rows.filter {it.first<second.countConfirmedAt!!}.all {it.second<=1})
        assertFalse(record.reps.any {it.closeReason=="nonIncreasingTimestamp"})
        owner.finish("manual");WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(),listOf(owner.record())))
    }
    @Test fun unavailableCameraCannotConfirmThePrivateHistory() {
        val owner = OverheadPullUpWorkout("unavailable")
        val selection = BodyAthleteSelection()
        for(p in (0L..600L step 40).map { pose(it) }+privateCycle(601))
            observe(owner,selection,p,if(p.timestamp.value>=850) OverheadPullUpMotion.Camera.unavailable else OverheadPullUpMotion.Camera.ready)
        assertFalse(owner.hasStarted); assertEquals(0,owner.record().completedCount)
        assertNull(owner.draftDeltaJson())
    }
    @Test fun previewCannotBankCyclesBeforeManualStart() {
        val owner = OverheadPullUpWorkout("manual")
        val selection = BodyAthleteSelection()
        for(p in cycle()) observe(owner,selection,p,automaticStart=false)
        assertFalse(owner.hasStarted); assertNull(owner.draftDeltaJson())
        assertTrue(owner.startManually()); assertEquals(1.8,owner.record().startedAt)
        for(p in cycle(1840)) observe(owner,selection,p,automaticStart=false)
        assertEquals(1,owner.record().completedCount)
    }
    @Test fun staticShallowAndUnobservableSetupCannotStart() {
        for(kind in listOf("static","shallow","missing","crowd","camera")) {
            val owner = OverheadPullUpWorkout(kind)
            val selection = BodyAthleteSelection()
            for(p in cycle()) {
                val frame = when(kind) {
                    "static" -> pose(p.timestamp.value)
                    "shallow" -> pose(p.timestamp.value,if(p.timestamp.value in 1040..1360)10.0 else 0.0,165.0)
                    "missing" -> pose(p.timestamp.value,omitted=setOf(PoseJoint.leftWrist))
                    "crowd" -> p.copy(people=p.people+p.people)
                    else -> p
                }
                observe(owner,selection,frame,if(kind=="camera") OverheadPullUpMotion.Camera.unavailable else OverheadPullUpMotion.Camera.ready)
            }
            assertFalse(owner.hasStarted,kind); assertEquals(0,owner.record().completedCount,kind)
            assertNull(owner.draftDeltaJson(),kind)
        }
    }
    @Test fun unconfirmedHistoryIsDiscardedWhenTheOwnerIsInterruptedOrFinished() {
        for(stop in listOf("interrupt","finish")) {
            val owner = OverheadPullUpWorkout(stop)
            val selection = BodyAthleteSelection()
            for(p in (0L..600L step 40).map { pose(it) }+privateCycle(601).filter { it.timestamp.value < 850 })
                observe(owner,selection,p)
            if(stop=="interrupt") owner.interrupt("cameraUnavailable") else owner.finish("manual")
            assertFalse(owner.hasStarted); assertEquals(0,owner.record().completedCount)
        }
    }
    @Test fun confirmationEvidenceCannotBeErasedForgedOrMovedOutsideTheSet() {
        val owner = OverheadPullUpWorkout("validation")
        val selection = BodyAthleteSelection()
        cycle().forEach { observe(owner,selection,it) }; owner.finish("manual")
        val record = owner.record()
        val rep = record.reps.single()
        fun bad(next: RepRecord) = record.copy(reps=listOf(next))
        for(invalid in listOf(null,Double.NaN,rep.returnedAt!!-.01,record.endedAt!!+.01)) {
            val next = bad(rep.copy(countConfirmedAt=invalid))
            assertFailsWith<IllegalArgumentException> { WorkoutLibrary.register(WorkoutLibraryState(),listOf(next)) }
            assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(null,next) }
        }
        assertFailsWith<IllegalArgumentException> {
            WorkoutRecordCodec.mergeDraft(record,bad(rep.copy(countConfirmedAt=rep.countConfirmedAt!!+.001)).copy(endedAt=record.endedAt!!+1))
        }
        val legacy = record.copy(boundaryPolicyVersion=2,countMethod=PullUpCycleMotion.countMethod,countPolicyVersion=PullUpCycleMotion.policyVersion,
            reps=listOf(rep.copy(countMethod=PullUpCycleMotion.countMethod,countPolicyVersion=PullUpCycleMotion.policyVersion,countConfirmedAt=null)))
        WorkoutLibrary.register(WorkoutLibraryState(),listOf(legacy))
        assertFalse(WorkoutRecordCodec.json.encodeToString(legacy).contains("countConfirmedAt"))
        assertFailsWith<IllegalArgumentException> {
            WorkoutLibrary.register(WorkoutLibraryState(),listOf(legacy.copy(reps=listOf(legacy.reps.single().copy(countConfirmedAt=rep.returnedAt)))))
        }
        for(wrong in listOf(record.copy(boundaryPolicyVersion=1),record.copy(boundaryPolicyVersion=2),
            record.copy(boundaryPolicyVersion=3),record.copy(boundaryPolicyVersion=4),record.copy(boundaryPolicyVersion=5),record.copy(boundaryPolicyVersion=6),record.copy(boundaryPolicyVersion=7),
            record.copy(countPolicyVersion=99),record.copy(exercise=ExerciseCounter.Exercise.dip)))
            assertFailsWith<IllegalArgumentException> { WorkoutLibrary.register(WorkoutLibraryState(),listOf(wrong)) }
    }
    @Test fun oldAndCurrentSourcePoliciesRemainReadableAndSeparate() {
        val owner=OverheadPullUpWorkout("current-policy");val selection=BodyAthleteSelection()
        cycle().forEach {observe(owner,selection,it)};owner.finish("manual")
        val current=owner.record()
        val old=current.copy(id="previous-policy",countPolicyVersion=1,boundaryPolicyVersion=5,
            reps=current.reps.map {it.copy(id="previous-policy-${it.ordinal}",setId="previous-policy",countPolicyVersion=1)},
            activity=null,grip=null,setGrip=null)
        val quality=current.copy(id="quality-policy",countPolicyVersion=2,boundaryPolicyVersion=6,
            reps=current.reps.map {it.copy(id="quality-policy-${it.ordinal}",setId="quality-policy",countPolicyVersion=2)},
            activity=null,grip=null,setGrip=null)
        val recovery=current.copy(id="recovery-policy",countPolicyVersion=3,boundaryPolicyVersion=7,
            reps=current.reps.map {it.copy(id="recovery-policy-${it.ordinal}",setId="recovery-policy",countPolicyVersion=3)},
            activity=null,grip=null,setGrip=null)
        val state=WorkoutLibrary.register(WorkoutLibraryState(),listOf(old,quality,recovery,current))
        val restored=WorkoutLibraryBackup.restore(WorkoutLibraryState(),WorkoutLibraryBackup.export(state))
        assertEquals(state.entries,restored.entries)
        assertEquals(setOf(1,2,3,4),restored.entries.map {it.measured.countPolicyVersion}.toSet())
        assertEquals(setOf(5,6,7,8),restored.entries.map {it.measured.boundaryPolicyVersion}.toSet())
        val context=WorkoutContext(assistance=WorkoutAssistance.none,addedLoadKg=0.0,equipment="bar",cameraView="front")
        assertNotEquals(context.comparisonKey(current.exercise,1,1,current.countMethod),context.comparisonKey(current.exercise,1,2,current.countMethod))
        assertNotEquals(context.comparisonKey(current.exercise,1,2,current.countMethod),context.comparisonKey(current.exercise,1,3,current.countMethod))
        assertNotEquals(context.comparisonKey(current.exercise,1,3,current.countMethod),context.comparisonKey(current.exercise,1,4,current.countMethod))
        for(bad in listOf(current.copy(boundaryPolicyVersion=5),current.copy(boundaryPolicyVersion=6),current.copy(boundaryPolicyVersion=7),
            recovery.copy(boundaryPolicyVersion=8),recovery.copy(boundaryPolicyVersion=6),quality.copy(boundaryPolicyVersion=8),
            quality.copy(boundaryPolicyVersion=7),quality.copy(boundaryPolicyVersion=5),old.copy(boundaryPolicyVersion=6),old.copy(boundaryPolicyVersion=7))) {
            assertFailsWith<IllegalArgumentException>{WorkoutLibrary.register(WorkoutLibraryState(),listOf(bad))}
            assertFailsWith<IllegalArgumentException>{WorkoutRecordCodec.mergeDraft(null,bad)}
        }
    }

    @Test fun sourceGenerationOrCameraMovementClosesWithoutBridgingAnotherCycle() {
        for(kind in listOf("source","camera")) {
            val owner = OverheadPullUpWorkout(kind)
            val selection = BodyAthleteSelection()
            cycle().forEach { observe(owner,selection,it) }
            val p = pose(1840,140.0,100.0)
            if(kind=="source") selection.reset()
            selection.observe(p)
            owner.observe(selection,if(kind=="camera") OverheadPullUpMotion.Camera.moved else OverheadPullUpMotion.Camera.ready)
            assertTrue(owner.isFinished,kind)
            assertEquals(if(kind=="camera")"setupInvalidated" else "trackingLost",owner.record().endReason)
            assertEquals(1,owner.record().completedCount)
        }
    }
}
