package dev.hanginthere.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class WorkoutJourneyTests {
    private val now = 20000L * 86400000
    private fun record(id: String = "set", durations: List<Double> = listOf(2.0, 2.0, 2.0),
        ranges: List<Double> = durations.map { 80.0 }, time: Long? = now, observedCoverage: Double? = 1.0): WorkoutSetRecord {
        var start = 0.0
        val reps = durations.mapIndexed { index, duration ->
            val angle = AngleMetrics(170 - ranges[index], 170.0, 130.0, duration, 1.0)
            RepRecord("$id:rep:${index+1}", id, index+1, ExerciseCounter.Exercise.dip, start,
                start+duration/2, start+duration, start+duration, start+duration, "returned", "accepted",
                duration/2, duration/2, angle, angle, .2, .0, duration, observedCoverage, emptyList(), false).also { start+=duration }
        }
        return WorkoutSetRecord(id, ExerciseCounter.Exercise.dip, 0.0, start+2, "disengaged", reps,
            "authored", 1, trackedAtEpochMilliseconds = time,
            activity = observedCoverage?.let { WorkoutActivity(emptyList(), listOf(WorkoutPhaseTotal(WorkoutMotionPhase.support, 2.0)),
                0.0, 2.0, (start+2)*it, start+2, false, 2) })
    }
    private fun library(vararg records: WorkoutSetRecord) = WorkoutLibrary.register(WorkoutLibraryState(), records.toList())
    @Test fun steadySetGetsOneAutomaticNextStepWithoutContextInput() {
        val original=record();val digest=WorkoutJourney.set(original)
        assertEquals("steady",digest.quality.level);assertEquals(3,digest.completedReps)
        assertEquals("Next time: match 3 reps",digest.coaching.title)
        assertEquals(1.0,digest.outwardSeconds);assertEquals(1.0,digest.returnSeconds)
        assertEquals(80.0,digest.excursionDegrees)
        val report=WorkoutJourney.report(library(original),now,0)
        assertEquals(1,report.workoutCount);assertEquals(3,report.completedReps);assertEquals(1,report.practiceDays)
        assertEquals(digest.coaching,report.coaching)
    }
    @Test fun recordingGapsCannotBeRatedAsPoorMovement() {
        val limited=WorkoutJourney.set(record(observedCoverage=.5))
        assertEquals("limited",limited.quality.level)
        assertEquals("Keep both arms in view",limited.coaching.title)
        val missing=WorkoutJourney.set(record(observedCoverage=null))
        assertEquals("recorded",missing.quality.level);assertNull(missing.coverage)
        assertNull(missing.outwardSeconds)
    }
    @Test fun oneRepAndSingleArmCoverageDoNotReceiveConsistencyGrades() {
        assertEquals("recorded",WorkoutJourney.set(record(durations=listOf(2.0))).quality.level)
        val base=record()
        val oneArm=base.copy(reps=base.reps.map { it.copy(right=it.right.copy(coverage=.2)) })
        assertEquals("recorded",WorkoutJourney.set(oneArm).quality.level)
    }
    @Test fun timingAndRangeChangesLeadToSpecificCoaching() {
        val pace=WorkoutJourney.set(record(durations=listOf(2.0,2.0,4.0)))
        assertEquals("pace",pace.quality.level);assertEquals("Find a repeatable rhythm",pace.coaching.title)
        val range=WorkoutJourney.set(record(ranges=listOf(80.0,80.0,40.0)))
        assertEquals("range",range.quality.level);assertEquals("Match your first rep's range",range.coaching.title)
    }
    @Test fun unfinishedAttemptsNeverAffectCompletedRepTimingOrRange() {
        val base=record()
        val unfinished=base.reps.last().copy(id="set:rep:4",ordinal=4,acceptedAt=null,returnedAt=null,
            closeReason="dipGripMoved",outboundSeconds=40.0,returnSeconds=null)
        val digest=WorkoutJourney.set(base.copy(reps=base.reps+unfinished))
        assertEquals(3,digest.completedReps);assertEquals(1,digest.practiceAttempts)
        assertEquals(1.0,digest.outwardSeconds);assertEquals("steady",digest.quality.level)
        assertEquals("unobserved",digest.reps.last().status)
        assertNull(digest.reps.last().outwardSeconds)
    }
    @Test fun holdsAreUsefulPracticeWithoutInventingRepsOrFormScores() {
        val hold=record(durations=emptyList()).copy(reps=emptyList())
        val digest=WorkoutJourney.set(hold)
        assertEquals("practice",digest.quality.level);assertEquals(0,digest.completedReps)
        assertEquals("Build on your 2s support hold",digest.coaching.title)
        assertEquals(1,WorkoutJourney.report(library(hold),now,0).practiceDays)
    }
    @Test fun runningSetIsProvisional() {
        val digest=WorkoutJourney.set(record().copy(endReason="running"))
        assertEquals("tracking",digest.quality.level);assertEquals("Keep going",digest.coaching.title)
    }
    @Test fun missingPhaseTimingRemainsUnknownForCompletedLegacyReps() {
        val base=record()
        val summary=WorkoutJourney.set(base.copy(reps=base.reps.map { it.copy(outboundSeconds=null,returnSeconds=null) }))
        assertEquals(3,summary.completedReps);assertNull(summary.outwardSeconds);assertNull(summary.returnSeconds)
        assertEquals("recorded",summary.quality.level)
        assertTrue(summary.reps.all { it.outwardSeconds==null && it.returnSeconds==null })
        assertTrue(summary.reps.all { it.totalSeconds==null })
    }
    @Test fun midnightAndPeriodBoundaryUseEachSetsDateInsteadOfTheWorkoutStart() {
        val state=library(record("before",time=now-5*60000),record("after",time=now+5*60000))
        val report=WorkoutJourney.report(state,now+10*60000,0)
        assertEquals(1,report.workoutCount);assertEquals(2,report.practiceDays);assertEquals(6,report.completedReps)
        assertEquals(listOf(3,3),report.days.takeLast(2).map { it.completedReps })
        assertEquals(report.days.last().workoutIds,report.days[26].workoutIds)
        val boundary=library(record("outside",time=now-27*86400000L-5*60000),record("inside",time=now-27*86400000L+5*60000))
        val window=WorkoutJourney.report(boundary,now+10*60000,0)
        assertEquals(3,window.completedReps);assertEquals(1,window.practiceDays);assertEquals(1,window.workoutCount)
    }
    @Test fun everyColoredDayCanLoadAllWorkoutsBeyondTheInitialWindow() {
        val rows=(0..109).map { record("set-$it",time=now-it*3600000L) }
        val state=library(*rows.toTypedArray());val report=WorkoutJourney.report(state,now,0)
        val shown=report.workouts.map { it.id }.toSet()
        val day=report.days.first { it.workoutIds.any { id -> id !in shown } }
        val loaded=mutableListOf<String>();var cursor:WorkoutJourneyCursor?=null
        do {
            val page=WorkoutJourney.page(state,now,0,day.day,cursor,2)
            loaded+=page.workouts.map { it.id };cursor=page.nextCursor
        } while(cursor!=null)
        assertEquals(day.workoutIds.toSet(),loaded.toSet());assertEquals(loaded.size,loaded.distinct().size)
        val earlier=WorkoutJourney.page(state,now,0,cursor=report.nextCursor)
        assertEquals(10,earlier.workouts.size)
        val first=WorkoutJourney.page(state,now,0,limit=1)
        assertFailsWith<IllegalArgumentException> { WorkoutJourney.page(state.copy(revision=state.revision+1),now,0,cursor=first.nextCursor) }
        assertFailsWith<IllegalArgumentException> { WorkoutJourney.page(state,now,0,day.day,cursor=first.nextCursor) }
    }
    @Test fun correctionsAndSplitsAreRespectedWithoutDuplicatingHolds() {
        val original=record()
        val correction=WorkoutReviewCorrection(attempts=listOf(
            WorkoutAttemptCorrection(original.reps[0].id,WorkoutAttemptClassification.excluded),
            WorkoutAttemptCorrection(original.reps[1].id,WorkoutAttemptClassification.userCompleted)),
            partitions=listOf(WorkoutSetPartition("part-a",original.reps.take(2).map { it.id },true),
                WorkoutSetPartition("part-b",original.reps.takeLast(1).map { it.id },false)))
        val state=WorkoutLibrary.apply(library(original),WorkoutLibraryCommand("review",setId=original.id,correction=correction,atEpochMilliseconds=now))
        val workout=WorkoutJourney.report(state,now,0).workouts.single()
        assertEquals(1,workout.completedReps);assertEquals(2,workout.setCount)
        assertEquals(1,workout.sets.sumOf { it.reportedReps })
        assertEquals(2.0,workout.sets.mapNotNull { it.holdSeconds }.sum())
    }
    @Test fun correctedExerciseDoesNotReuseOriginalMotionMetrics() {
        val state=WorkoutLibrary.apply(library(record()),WorkoutLibraryCommand("review",setId="set",
            correction=WorkoutReviewCorrection(exercise=ExerciseCounter.Exercise.pullUp),atEpochMilliseconds=now))
        val digest=WorkoutJourney.report(state,now,0).workouts.single().sets.single()
        assertEquals(0,digest.completedReps);assertNull(digest.holdSeconds);assertNull(digest.excursionDegrees)
        assertEquals("practice",digest.quality.level)
    }
    @Test fun sessionsAndLocalDaysAreAutomaticAndMissingDatesStayOutOfTheCalendar() {
        val report=WorkoutJourney.report(library(record("a",time=now-86400000),record("b",time=now-86400000+60000),
            record("c",time=now),record("undated",time=null),record("future",time=now+1)),now,0)
        assertEquals(3,report.workouts.size);assertEquals(2,report.workoutCount)
        assertEquals(2,report.practiceDays);assertEquals(9,report.completedReps)
        assertEquals(28,report.days.size)
        assertEquals(2,report.workouts.first { it.completedReps==6 }.setCount)
        assertFalse(report.workouts.any { row -> row.sets.any { it.id=="future" } })
        val offset=WorkoutJourney.report(library(record(time=now-1)),now,60)
        assertEquals(1,offset.days.last().workoutIds.size)
    }
    @Test fun boundedHistoryPreservesFullPeriodTotalsAndStableIds() {
        val rows=(0..109).map { record("set-$it",time=now-it*3600000L) }
        val state=library(*rows.toTypedArray());val report=WorkoutJourney.report(state,now,0)
        assertEquals(100,report.workouts.size);assertEquals(110,report.workoutCount)
        assertEquals(330,report.completedReps)
        assertEquals(report,WorkoutJourney.report(state.copy(entries=state.entries.reversed()),now,0))
        assertEquals(report,WorkoutRecordCodec.json.decodeFromString<WorkoutJourneyReport>(WorkoutRecordCodec.json.encodeToString(report)))
    }
    @Test fun sharedBridgeUsesTheSameProjectionAndKeepsCurvesOutOfOverview() {
        val source=record()
        val decoded=WorkoutRecordCodec.json.decodeFromString<WorkoutSetDigest>(WorkoutJourneyBridge().setJson(WorkoutRecordCodec.json.encodeToString(source)))
        assertEquals(WorkoutJourney.set(source),decoded)
        val overview=WorkoutInsights.report(library(source),null,now,0).overview!!
        assertTrue(overview.workouts.single().sets.single().reps.isEmpty())
    }
}
