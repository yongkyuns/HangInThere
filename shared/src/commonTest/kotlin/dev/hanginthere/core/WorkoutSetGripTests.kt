package dev.hanginthere.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.*

class WorkoutSetGripTests {
    private fun annotation(id:String="set",type:GripOrientation=GripOrientation.underhand)=
        WorkoutSetGripAnalysis(id).also { it.observeConfirmed(type,8.0,"model","test") }.current
    private fun record():WorkoutSetRecord {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        for(n in 0..1) {
            val t=n*4.0
            a.observe(MotionSample(t,170.0,170.0,null,true,false),ExerciseCounter.Phase.ready,null)
            a.observe(MotionSample(t+.2,90.0,90.0,null,true,false),ExerciseCounter.Phase.outbound,null)
            a.observe(MotionSample(t+.4,80.0,80.0,null,true,false),ExerciseCounter.Phase.returning,
                ExerciseCounter.Event(ExerciseCounter.Outcome.movement,t+.4,"test"))
            a.observe(MotionSample(t+.6,170.0,170.0,null,true,false),ExerciseCounter.Phase.ready,null)
        }
        return WorkoutSetRecord("set",ExerciseCounter.Exercise.pullUp,0.0,10.0,"manual",a.records.map {
            it.copy(grip=GripEstimate(orientationCoverage=0.0,width=GripWidth.shoulderWidth,widthCoverage=.95))
        },"test",1)
    }
    @Test fun laterConfirmationLabelsEarlierRepsWithoutChangingTheirMeasurements() {
        val raw=record();val saved=WorkoutRecordCodec.json.encodeToString(raw.reps)
        val report=WorkoutGripReview.report(raw.copy(setGrip=annotation()))
        assertEquals(2,report.reps.size)
        assertTrue(report.reps.all { it.orientation==GripOrientation.underhand && it.orientationSource=="setEstimated" })
        assertTrue(report.reps.all { it.measured?.orientation==GripOrientation.unknown && it.measured.orientationCoverage==0.0 })
        assertTrue(report.reps.all { it.width==GripWidth.shoulderWidth && it.widthSource=="estimated" })
        assertEquals(2,report.totals.single().completedReps)
        assertEquals(saved,WorkoutRecordCodec.json.encodeToString(raw.reps))
    }
    @Test fun unknownHandsDoNotEraseIdentifiedSetGrip() {
        val a=WorkoutSetGripAnalysis("set");a.observeConfirmed(GripOrientation.underhand,2.0,"m","b")
        val first=a.current
        a.observeConfirmed(GripOrientation.unknown,3.0,"m","b")
        assertEquals(first,a.current)
        a.observeConfirmed(GripOrientation.underhand,4.0,"m","b")
        assertEquals(2.0,a.current.firstConfirmedAt);assertEquals(4.0,a.current.lastConfirmedAt)
    }
    @Test fun contradictoryConfirmedEvidenceStaysUnknownRatherThanLastWins() {
        val a=WorkoutSetGripAnalysis("set")
        a.observeConfirmed(GripOrientation.underhand,1.0,"m","b")
        a.observeConfirmed(GripOrientation.overhand,2.0,"m","b")
        a.observeConfirmed(GripOrientation.underhand,3.0,"m","b")
        assertEquals(GripOrientation.unknown,a.current.orientation)
        assertEquals("conflictingSetGripEvidence",a.current.reason)
        assertTrue(WorkoutGripReview.report(record().copy(setGrip=a.current)).reps.all { it.orientation==GripOrientation.unknown })
    }
    @Test fun eachSetStartsUnknownAndAlternatingSetsStayIndependent() {
        val a=WorkoutSetGripAnalysis("one");val b=WorkoutSetGripAnalysis("two")
        a.observeConfirmed(GripOrientation.underhand,1.0,"m","b")
        assertEquals(GripOrientation.unknown,b.current.orientation)
        b.observeConfirmed(GripOrientation.overhand,8.0,"m","b")
        assertEquals(GripOrientation.underhand,a.current.orientation)
        assertEquals(GripOrientation.overhand,b.current.orientation)
        assertNotEquals(a.current.setId,b.current.setId)
    }
    @Test fun cachedRegressingOrInvalidEvidenceDoesNotAdvanceAnnotation() {
        val a=WorkoutSetGripAnalysis("set");a.observeConfirmed(GripOrientation.underhand,2.0,"m","b")
        val old=a.current
        for(t in listOf(2.0,1.0,Double.NaN,Double.POSITIVE_INFINITY,-1.0))
            a.observeConfirmed(GripOrientation.overhand,t,"m","b")
        assertEquals(old,a.current)
    }
    @Test fun modelOrBackendDisagreementCannotPoolIntoOneConfidentSet() {
        for(pair in listOf("new" to "b","m" to "new")) {
            val a=WorkoutSetGripAnalysis("set");a.observeConfirmed(GripOrientation.underhand,1.0,"m","b")
            a.observeConfirmed(GripOrientation.underhand,2.0,pair.first,pair.second)
            assertFalse(a.current.isConfirmed)
        }
    }
    @Test fun annotationsFromAnotherSetOrOutsideSourceWindowAreIgnored() {
        for(metadata in listOf(annotation("other"),annotation().copy(firstConfirmedAt=-1.0),
            annotation().copy(lastConfirmedAt=11.0),annotation().copy(firstConfirmedAt=null),
            annotation().copy(policyVersion=9))) {
            val report=WorkoutGripReview.report(record().copy(setGrip=metadata))
            assertNull(report.setGrip)
            assertTrue(report.reps.all { it.orientation==GripOrientation.unknown })
        }
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(null,record().copy(setGrip=annotation("other"))) }
    }
    @Test fun correctionsWinAndResetToAutomaticSetEstimate() {
        val r=record().copy(setGrip=annotation());val first=r.reps.first().id
        var ledger=WorkoutGripReview.correct(r,GripCorrectionLedger(r.id),first,GripOrientation.overhand,null,1)
        assertEquals(listOf(GripOrientation.overhand,GripOrientation.underhand),WorkoutGripReview.report(r,ledger).reps.map { it.orientation })
        ledger=WorkoutGripReview.correct(r,ledger,first,null,null,2)
        assertTrue(WorkoutGripReview.report(r,ledger).reps.all { it.orientation==GripOrientation.underhand })
    }
    @Test fun compactSummarySerializationAndJournalRetainSetAnnotation() {
        val r=record().copy(setGrip=annotation())
        assertEquals(r,WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(WorkoutRecordCodec.json.encodeToString(r)))
        assertEquals(r.setGrip,WorkoutLibrary.compact(r).setGrip)
        val before=record().copy(endedAt=5.0,reps=r.reps.take(1))
        val delta=r.copy(reps=r.reps.takeLast(1))
        val merged=WorkoutRecordCodec.mergeDraft(before,delta)
        assertEquals(r.setGrip,merged.setGrip);assertEquals(r.reps.map { it.id },merged.reps.map { it.id })
        assertEquals(r.setGrip,WorkoutRecordCodec.mergeDraft(merged,r.copy(endedAt=11.0,setGrip=null)).setGrip)
        val legacy=WorkoutRecordCodec.json.encodeToString(record()).replace(",\"setGrip\":null","")
        assertNull(WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(legacy).setGrip)
    }
    @Test fun incompleteAttemptsDoNotBecomeCompletedRepsWhenSetGripArrives() {
        val r=record();val partial=r.reps.first().copy(returnedAt=null,closeReason="manual")
        val report=WorkoutGripReview.report(r.copy(reps=listOf(partial)+r.reps.drop(1),setGrip=annotation()))
        assertTrue(report.reps.all { it.orientation==GripOrientation.underhand })
        assertEquals(1,report.totals.single().completedReps)
    }
}
