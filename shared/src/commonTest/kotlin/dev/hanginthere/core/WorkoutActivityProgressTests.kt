package dev.hanginthere.core

import kotlin.test.*
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class WorkoutActivityProgressTests {
    private val now=Instant.parse("2026-10-28T16:00:00Z").toEpochMilliseconds()
    private fun at(text:String)=Instant.parse(text).toEpochMilliseconds()
    private fun record(id:String,time:Long?=now,coverage:Double=.3):WorkoutSetRecord {
        val angles=AngleMetrics(90.0,170.0,130.0,2.0,1.0)
        val rep=RepRecord("$id:rep:1",id,1,ExerciseCounter.Exercise.pullUp,0.0,1.0,1.0,2.0,2.0,"returned","accepted",1.0,1.0,
            angles,angles,.2,.1,2.0,coverage,emptyList(),false)
        return WorkoutSetRecord(id,rep.exercise,0.0,3.0,"manual",listOf(rep),"authored",1,trackedAtEpochMilliseconds=time)
    }
    private fun log(reps:Int,time:Long=now,exercise:String="Pull-ups")=WorkoutLog("log",time,listOf(WorkoutLoggedSet("logged",exercise,reps)),now)
    private fun report(state:WorkoutLibraryState,time:Long=now)=WorkoutActivityProgress.report(state,time,"America/Toronto",2)
    @Test fun cameraAndManualRepsCountWithoutContextOrCoverageGates() {
        val camera=record("camera")
        var state=WorkoutLibrary.register(WorkoutLibraryState(),listOf(camera,camera))
        state=state.copy(workoutLogs=listOf(log(4)),activityGoals=listOf(WorkoutActivityGoal("Pull-ups",10)))
        val result=report(state)
        assertEquals(5,result.reps);assertEquals(2,result.sets);assertEquals(1,result.activeDays)
        assertEquals(1,result.cameraReps);assertEquals(4,result.loggedReps)
        assertEquals(5,result.goals.single().remaining);assertEquals(.5,result.goals.single().fraction)
        assertEquals(5.0,WorkoutFeedback.activitySummary(state,"camera",now,"America/Toronto",2).goalValue)
    }
    @Test fun correctionsDeletionAndBackupUseTheSameTotals() {
        val camera=record("camera")
        var state=WorkoutLibrary.register(WorkoutLibraryState(activityGoals=listOf(WorkoutActivityGoal("Pull-ups",5))),listOf(camera)).copy(workoutLogs=listOf(log(4)))
        assertTrue(report(state).goals.single().achieved)
        state=WorkoutLibrary.apply(state,WorkoutLibraryCommand("review",setId="camera",correction=WorkoutReviewCorrection(
            attempts=listOf(WorkoutAttemptCorrection(camera.reps.single().id,WorkoutAttemptClassification.practice))),atEpochMilliseconds=now))
        assertEquals(4,report(state).reps)
        state=WorkoutLibrary.apply(state,WorkoutLibraryCommand("deleteWorkoutLog",id="log",atEpochMilliseconds=now))
        assertEquals(0,report(state).reps)
        val restored=WorkoutLibraryBackup.restore(WorkoutLibraryState(),WorkoutLibraryBackup.export(state))
        assertEquals(state.activityGoals,restored.activityGoals);assertEquals(0,report(restored).goals.single().reps)
    }
    @Test fun periodsResetAndFutureOrUndatedSetsNeverInflateProgress() {
        val monday=at("2026-10-26T16:00:00Z")
        var state=WorkoutLibrary.register(WorkoutLibraryState(),listOf(record("monday",monday),record("undated",null),record("future",now+10000)))
        state=state.copy(workoutLogs=listOf(log(3)),activityGoals=listOf(WorkoutActivityGoal("Pull-ups",4)))
        assertEquals(4,report(state).goals.single().reps);assertEquals(1,report(state).excludedUndatedSets)
        assertEquals(3,report(state.copy(activityGoals=listOf(WorkoutActivityGoal("Pull-ups",4,WorkoutGoalPeriod.day)))).goals.single().reps)
        assertEquals(0,report(state,at("2026-11-02T17:00:00Z")).goals.single().reps)
    }
    @Test fun aliasesAndAllExercisesShareActivityRules() {
        var state=WorkoutLibraryState(workoutLogs=listOf(log(5,exercise="sit ups")))
        state=WorkoutLibrary.apply(state,WorkoutLibraryCommand("activityGoal",activityGoal=WorkoutActivityGoal("Sit-ups",4),atEpochMilliseconds=now))
        assertTrue(report(state).goals.single().achieved)
        state=WorkoutLibrary.apply(state,WorkoutLibraryCommand("activityGoal",activityGoal=WorkoutActivityGoal("Sit ups",8),atEpochMilliseconds=now))
        assertEquals(1,state.activityGoals.size);assertEquals(3,report(state).goals.single().remaining)
        assertFailsWith<IllegalArgumentException>{ WorkoutActivityGoal("Dips",0).validate() }
        assertFailsWith<IllegalArgumentException>{ WorkoutActivityGoal("",10).validate() }
    }
    @Test fun comparisonUsesSameLocalTimeAcrossDaylightSaving() {
        val time=at("2026-11-01T17:00:00Z") // Noon after clocks go back.
        val state=WorkoutLibraryState(workoutLogs=listOf(log(7,at("2026-10-25T16:30:00Z")))) // 12:30 last Sunday: still ahead of comparison cutoff.
        assertEquals(0,report(state,time).previousReps)
        val earlier=state.copy(workoutLogs=listOf(log(7,at("2026-10-25T15:30:00Z"))))
        assertEquals(7,report(earlier,time).previousReps)
    }
    @Test fun sessionSummaryComparesVolumeForTheSameExercises() {
        val old=log(4,now-86400000).copy(id="older")
        val state=WorkoutLibraryState(workoutLogs=listOf(old,log(7)))
        val workout=WorkoutJourney.report(state,now,0).workouts.first()
        assertEquals(7,workout.totalReps);assertEquals(7,workout.progress?.bestSet)
        assertEquals(4,workout.progress?.previousReps)
        assertEquals("3 more reps than your last workout",workout.progress?.headline)
    }
    @Test fun reportedRepsAgreeAcrossGoalsSessionAndCalendarWithoutDuplicatingLinkedLogs() {
        val camera=record("camera")
        var state=WorkoutLibrary.register(WorkoutLibraryState(activityGoals=listOf(WorkoutActivityGoal("Pull-ups",5))),listOf(camera))
        state=WorkoutLibrary.apply(state,WorkoutLibraryCommand("review",setId="camera",correction=WorkoutReviewCorrection(workoutId="log",
            attempts=listOf(WorkoutAttemptCorrection(camera.reps.single().id,WorkoutAttemptClassification.userCompleted))),atEpochMilliseconds=now))
        state=state.copy(workoutLogs=listOf(log(4)))
        val activity=report(state);val journey=WorkoutJourney.report(state,now,0)
        assertEquals(5,activity.reps);assertTrue(activity.goals.single().achieved)
        assertEquals(5,journey.workouts.single().totalReps)
        assertEquals(5,journey.days.last().completedReps+journey.days.last().loggedReps)
    }
}
