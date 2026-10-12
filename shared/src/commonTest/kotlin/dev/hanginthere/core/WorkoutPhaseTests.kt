package dev.hanginthere.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class WorkoutPhaseTests {
    private fun frame(time: Double, angle: Double? = 170.0, usable: Boolean = true, released: Boolean = false) =
        MotionSample(time,angle,angle,null,usable,released)
    @Test fun startBeforeFirstFrameCannotPublishAnEmptyJournal() {
        val session=LiveSetSession();session.start(ExerciseCounter.Exercise.pullUp,ArmMeasurement.Side.left)
        assertNull(session.draftDelta)
        session.analysis.observe(frame(0.0),ExerciseCounter.Phase.ready,null)
        assertNull(session.draftDelta)
        session.analysis.observe(frame(.1),ExerciseCounter.Phase.ready,null)
        val first=WorkoutRecordCodec.mergeDraft(null,assertNotNull(session.draftDelta))
        session.analysis.observe(frame(.2),ExerciseCounter.Phase.ready,null)
        val next=WorkoutRecordCodec.mergeDraft(first,assertNotNull(session.draftDelta))
        assertEquals(.2,next.activity!!.longestHangSeconds,1e-9)
        assertEquals(0,next.completedCount)
    }
    @Test fun changedSpanDeltasPreserveEntireBoundedJournalTimeline() {
        val a=WorkoutAnalysis("hang",ExerciseCounter.Exercise.pullUp)
        var journal: WorkoutSetRecord?=null
        for(i in 0..400) {
            val phase=if(i%4<2)ExerciseCounter.Phase.ready else ExerciseCounter.Phase.outbound
            a.observe(frame(i*.1,if(phase==ExerciseCounter.Phase.ready)170.0 else 100.0),phase,null)
            val delta=WorkoutSetRecord("hang",ExerciseCounter.Exercise.pullUp,0.0,i*.1,"running",emptyList(),null,null,activity=a.activityDelta)
            assertTrue(delta.activity!!.phases.size<=3)
            journal=WorkoutRecordCodec.mergeDraft(journal,delta)
        }
        assertEquals(a.activity,journal!!.activity)
    }
    @Test fun revisionGatedGapDeltaIncludesUncheckpointedHoldTail() {
        val a=WorkoutAnalysis("hang",ExerciseCounter.Exercise.pullUp)
        var journal: WorkoutSetRecord?=null
        var revision=-1
        for(time in (0..11).map { it*.1 }+listOf(2.0,2.1,2.2)) {
            a.observe(frame(time),ExerciseCounter.Phase.ready,null)
            if(a.revision!=revision) {
                journal=WorkoutRecordCodec.mergeDraft(journal,WorkoutSetRecord("hang",ExerciseCounter.Exercise.pullUp,0.0,time,"running",emptyList(),null,null,activity=a.activityDelta))
                revision=a.revision
            }
        }
        a.finish("disengaged")
        journal=WorkoutRecordCodec.mergeDraft(journal,WorkoutSetRecord("hang",ExerciseCounter.Exercise.pullUp,0.0,2.2,"disengaged",emptyList(),null,null,activity=a.activityDelta))
        assertEquals(a.activity,journal!!.activity)
        assertEquals(1.1,journal.activity!!.phases.first().endedAt,1e-9)
    }
    @Test fun sustainedOutageDoesNotSerializeOnEveryFrame() {
        val a=WorkoutAnalysis("outage",ExerciseCounter.Exercise.pullUp)
        for(i in 0..500) a.observe(frame(i*.05,null,usable=false),ExerciseCounter.Phase.seekingStart,null)
        assertTrue(a.revision<40)
        assertEquals(0.0,a.activity.observedSeconds)
        assertEquals(1,a.activity.phases.size)
    }
    @Test fun hangOnlyWorkoutEarnsPracticeButNoRep() {
        val a=WorkoutAnalysis("hang",ExerciseCounter.Exercise.pullUp)
        for(i in 0..100) a.observe(frame(i*.1),ExerciseCounter.Phase.ready,null)
        a.finish("disengaged")
        assertTrue(a.records.isEmpty());assertTrue(a.activity.hasPractice)
        assertEquals(10.0,a.activity.seconds(WorkoutMotionPhase.hang),1e-9)
        assertEquals(10.0,a.activity.longestHangSeconds,1e-9)
        assertEquals(1,a.activity.phases.size)
        assertTrue(a.revision < 20, "Holds must not serialize on every frame")
    }
    @Test fun hangTimeIsIndependentOfSamplingRate() {
        fun run(step: Double): WorkoutActivity {
            val a=WorkoutPhaseAnalysis("hang",ExerciseCounter.Exercise.pullUp)
            for(i in 0..(10/step).toInt()) a.observe(frame(i*step),ExerciseCounter.Phase.ready)
            return a.activity
        }
        assertEquals(run(.1).longestHangSeconds,run(.02).longestHangSeconds,1e-9)
    }
    @Test fun dipRecordsSupportNotHangAndShowsDirectionalPhases() {
        val a=WorkoutPhaseAnalysis("dip",ExerciseCounter.Exercise.dip)
        for(i in 0..10) a.observe(frame(i*.1,150.0),ExerciseCounter.Phase.ready)
        a.observe(frame(1.1,130.0),ExerciseCounter.Phase.outbound)
        a.observe(frame(1.2,90.0),ExerciseCounter.Phase.returning)
        a.observe(frame(1.3,90.0),ExerciseCounter.Phase.returning)
        a.observe(frame(1.4,130.0),ExerciseCounter.Phase.returning)
        assertEquals(1.0,a.activity.longestSupportSeconds,1e-9)
        assertEquals(0.0,a.activity.longestHangSeconds)
        assertTrue(a.activity.phases.any { it.phase == WorkoutMotionPhase.lowering })
        assertTrue(a.activity.phases.any { it.phase == WorkoutMotionPhase.bottomHold })
        assertTrue(a.activity.phases.any { it.phase == WorkoutMotionPhase.rising })
    }
    @Test fun missingArmAndSourceGapCannotBridgeHolds() {
        val a=WorkoutPhaseAnalysis("hang",ExerciseCounter.Exercise.pullUp)
        for(i in 0..10) a.observe(frame(i*.1),ExerciseCounter.Phase.ready)
        a.observe(frame(1.1).copy(rightDegrees=null),ExerciseCounter.Phase.ready)
        a.observe(frame(1.2),ExerciseCounter.Phase.ready)
        a.observe(frame(1.3),ExerciseCounter.Phase.ready)
        a.observe(frame(3.0),ExerciseCounter.Phase.ready)
        a.observe(frame(3.1),ExerciseCounter.Phase.ready)
        assertEquals(1.0,a.activity.longestHangSeconds,1e-9)
        assertEquals(1.2,a.activity.seconds(WorkoutMotionPhase.hang),1e-9)
        assertEquals(1.2,a.activity.phases.filter { it.phase==WorkoutMotionPhase.hang }[1].startedAt,1e-9)
        assertTrue(a.activity.phases.any { it.phase == WorkoutMotionPhase.unobserved })
    }
    @Test fun releaseNeverAddsHangTime() {
        val a=WorkoutPhaseAnalysis("hang",ExerciseCounter.Exercise.pullUp)
        for(i in 0..10) a.observe(frame(i*.1),ExerciseCounter.Phase.ready)
        for(i in 11..25) a.observe(frame(i*.1,released=true),ExerciseCounter.Phase.seekingStart)
        assertEquals(1.0,a.activity.longestHangSeconds,1e-9)
        assertEquals(1.0,a.activity.observedSeconds,1e-9)
    }
    @Test fun rejectedTimeCannotEraseHighWaterOrEarnTime() {
        val a=WorkoutPhaseAnalysis("hang",ExerciseCounter.Exercise.pullUp)
        a.observe(frame(10.0),ExerciseCounter.Phase.ready)
        a.observe(frame(10.1),ExerciseCounter.Phase.ready)
        a.observe(frame(Double.NaN),ExerciseCounter.Phase.ready)
        for(i in 0..20) a.observe(frame(i*.1),ExerciseCounter.Phase.ready)
        a.observe(frame(10.2),ExerciseCounter.Phase.ready)
        a.observe(frame(10.3),ExerciseCounter.Phase.ready)
        assertEquals(.2,a.activity.observedSeconds,1e-9)
        assertEquals(.1,a.activity.longestHangSeconds,1e-9)
    }
    @Test fun timelineIsBoundedWithoutLosingIntegrals() {
        val a=WorkoutPhaseAnalysis("hang",ExerciseCounter.Exercise.pullUp)
        for(i in 0..1000) a.observe(frame(i*.1,if(i%2==0)170.0 else 100.0),
            if(i%2==0)ExerciseCounter.Phase.ready else ExerciseCounter.Phase.outbound)
        assertEquals(WorkoutPhaseAnalysis.maximumSpans,a.activity.phases.size)
        assertTrue(a.activity.timelineTruncated)
        assertTrue(a.activity.observedSeconds > 40)
    }
    @Test fun legacyRecordsDoNotInventHoldMetricsAndSummaryKeepsNewMetrics() {
        val a=WorkoutAnalysis("hang",ExerciseCounter.Exercise.pullUp)
        for(i in 0..20) a.observe(frame(i*.1),ExerciseCounter.Phase.ready,null)
        val record=WorkoutSetRecord("hang",ExerciseCounter.Exercise.pullUp,0.0,2.0,"disengaged",emptyList(),null,null,activity=a.activity)
        val json=WorkoutRecordCodec.json.encodeToString(record)
        assertEquals(record,WorkoutRecordCodec.json.decodeFromString(WorkoutRecordCodec().summaryJson(json)))
        val legacy=WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(json.replace(Regex(",\"activity\":.*}$"),"}"))
        assertNull(legacy.activity)
    }
}
