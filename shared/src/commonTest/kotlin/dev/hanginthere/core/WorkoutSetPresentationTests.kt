package dev.hanginthere.core

import kotlin.math.exp
import kotlin.test.*
import kotlinx.serialization.decodeFromString

class WorkoutSetPresentationTests {
    private fun activity(vararg spans: WorkoutPhaseSpan) = WorkoutActivity(spans.toList(), emptyList(),0.0,0.0,0.0,0.0,false)
    private fun span(phase: WorkoutMotionPhase,start: Double,end: Double, observed: Double = end-start) =
        WorkoutPhaseSpan("$phase-$start",phase,start,end,observed)
    private fun record(): WorkoutSetRecord {
        val source = BodyAthleteSelection(); val owner = RelativeDipWorkout("test")
        val frames = (0L..600L step 40).map { BodyDipFixtures.pose(it) }+BodyDipFixtures.cycle(640)
        frames.forEach { source.observe(it); owner.observe(source,DipCycleMotion.Camera.ready) }
        owner.finish("manual"); return owner.record()
    }
    @Test fun demandWeightsAndRecoveryAreIndependentOfExerciseAndSourceClock() {
        val pull = WorkoutSetPresentation.demand(activity(span(WorkoutMotionPhase.hang,9000.0,9004.0),
            span(WorkoutMotionPhase.pulling,9004.0,9006.0),span(WorkoutMotionPhase.lowering,9006.0,9008.0)))
        val dip = WorkoutSetPresentation.demand(activity(span(WorkoutMotionPhase.support,0.0,4.0),
            span(WorkoutMotionPhase.lowering,4.0,6.0),span(WorkoutMotionPhase.rising,6.0,8.0)))
        assertEquals(4.0,pull.units); assertEquals(pull.units,dip.units)
        assertEquals(pull.points.map { it.seconds },dip.points.map { it.seconds })
        assertEquals(4*exp(-.5),pull.recovery.last().units,1e-10)
        assertFalse(pull.hasGaps)
    }
    @Test fun demandDoesNotBridgeGapsOverlapsReleaseOrInvalidSpans() {
        val result = WorkoutSetPresentation.demand(activity(span(WorkoutMotionPhase.pulling,0.0,5.0,2.0),
            span(WorkoutMotionPhase.pulling,4.0,6.0),span(WorkoutMotionPhase.lowering,6.0,8.0,Double.NaN),
            span(WorkoutMotionPhase.release,8.0,90.0)))
        assertEquals(3.0,result.units); assertTrue(result.hasGaps)
        assertEquals(listOf(3.0,5.0,5.0,6.0),result.points.map { it.seconds })
        assertEquals(90.0,result.recovery.first().seconds)
        // Apple Codable uses strings for special floats at this narrow transport boundary.
        val json = """{"phases":[{"id":"x","phase":"pulling","startedAt":0,"endedAt":2,"observedSeconds":"NaN"}],"totals":[],"longestHangSeconds":0,"longestSupportSeconds":0,"observedSeconds":0,"elapsedSeconds":0,"timelineTruncated":false}"""
        val bridged = WorkoutRecordCodec.json.decodeFromString<WorkoutSetPresentation.Demand>(WorkoutSetPresentationBridge().demandJson(json))
        assertTrue(bridged.points.isEmpty())
    }
    @Test fun tempoUsesDipPressAndPullUpLiftingAndRequiresComparableObservedRange() {
        val base = record(); val rep = base.completedReps.single()
        fun set(exercise: ExerciseCounter.Exercise) = base.copy(exercise=exercise,reps=(1..3).map { i ->
            rep.copy(id="r$i",ordinal=i,exercise=exercise,startedAt=i*10.0,extremeAt=i*10.0+1,
                returnedAt=i*10.0+1+if(i==3)3.0 else 2.0, durationCoverage=1.0,curveTruncated=false,
                left=AngleMetrics(90.0,170.0,120.0,2.0,1.0),right=AngleMetrics(90.0,170.0,120.0,2.0,1.0))
        })
        assertEquals(.5,WorkoutSetPresentation.tempo(set(ExerciseCounter.Exercise.dip)).change)
        assertEquals(0.0,WorkoutSetPresentation.tempo(set(ExerciseCounter.Exercise.pullUp)).change)
        val unreliable = set(ExerciseCounter.Exercise.dip).let { it.copy(reps=it.reps.map { r -> r.copy(durationCoverage=.2) }) }
        assertNull(WorkoutSetPresentation.tempo(unreliable).change)
        val changed = set(ExerciseCounter.Exercise.dip).let { it.copy(reps=it.reps.map { r ->
            if(r.ordinal==3)r.copy(left=r.left.copy(minimum=140.0),right=r.right.copy(minimum=140.0)) else r }) }
        assertTrue(WorkoutSetPresentation.tempo(changed).comparisonNote.startsWith("Movement range changed"))
    }
    @Test fun keyframesUseOnlyCompletedSourceEventsWithinTheClip() {
        val record = record(); val rep = record.completedReps.single()
        val markers = WorkoutSetPresentation.keyframes(record,rep.startedAt)
        assertEquals(listOf("Start","Bottom","Return"),markers.map { it.phase })
        assertEquals(0.0,markers.first().clipSeconds)
        assertEquals(listOf("Return"),WorkoutSetPresentation.keyframes(record,rep.returnedAt!!).map { it.phase })
        assertTrue(WorkoutSetPresentation.keyframes(record,Double.NaN).isEmpty())
        assertTrue(WorkoutSetPresentation.keyframes(record.copy(reps=record.reps.map { it.copy(returnedAt=null) }),0.0).isEmpty())
    }
}
