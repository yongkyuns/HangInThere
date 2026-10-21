package dev.hanginthere.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class WorkoutProgressionTests {
    private fun hold(id: String, time: Long? = 100, seconds: Double = 10.0, exercise: ExerciseCounter.Exercise = ExerciseCounter.Exercise.pullUp): WorkoutSetRecord {
        val phase=if(exercise==ExerciseCounter.Exercise.pullUp)WorkoutMotionPhase.hang else WorkoutMotionPhase.support
        return WorkoutSetRecord(id,exercise,0.0,seconds,"disengaged",emptyList(),null,null,
            trackedAtEpochMilliseconds=time,activity=WorkoutActivity(emptyList(),listOf(WorkoutPhaseTotal(phase,seconds)),
                if(phase==WorkoutMotionPhase.hang)seconds else 0.0,if(phase==WorkoutMotionPhase.support)seconds else 0.0,seconds,seconds,false))
    }
    private fun attempt(complete: Boolean): RepRecord {
        val metric=AngleMetrics(110.0,170.0,140.0,1.0,1.0)
        return RepRecord("set:rep:1","set",1,ExerciseCounter.Exercise.pullUp,0.0,.5,.5,if(complete)1.0 else null,
            1.0,if(complete)"returned" else "disengaged","accepted",.5,if(complete).5 else null,metric,metric,
            null,null,1.0,1.0,emptyList(),false)
    }
    @Test fun compactAllTimeEvidenceKeepsMilestonesBeyondDetailRetention() {
        val sets=listOf(hold("old",time=1,seconds=30.0))+ (2..80).map { hold("set-$it",time=it.toLong(),seconds=2.0) }
        val evidence=sets.map(WorkoutProgression::evidence)
        val goal=WorkoutGoal(ExerciseCounter.Exercise.pullUp,WorkoutGoalMetric.holdSeconds,20.0,0)
        val report=WorkoutProgression.reportEvidence(evidence,goal.exercise,goal)
        assertEquals(30.0,report.bestHoldSeconds)
        assertTrue(report.goalAchieved)
        assertEquals(1L,report.milestones.first { it.id=="hold:30" }.achievedAtEpochMilliseconds)
        assertEquals(20,report.trend.size)
        assertEquals(WorkoutProgression.report(sets,goal.exercise,goal),report)
    }
    @Test fun appleOmittedNullsDecodeInCompactEvidenceBridge() {
        val compact=WorkoutProgression.evidence(hold("hold"))
        val nativeJSON=WorkoutRecordCodec.json.encodeToString(listOf(compact))
        assertFalse(nativeJSON.contains("elbowExcursionDegrees"))
        val report=WorkoutProgressBridge().reportEvidenceJson(nativeJSON,"pullUp",null)
        assertEquals(10.0,WorkoutAnalysis.json.decodeFromString<WorkoutProgressReport>(report).bestHoldSeconds)
    }
    @Test fun undatedQualifyingPracticeCannotInventFirstAchievementDate() {
        val report=WorkoutProgression.report(listOf(hold("unknown",null),hold("dated",200)),ExerciseCounter.Exercise.pullUp)
        assertTrue(report.milestones.first { it.id=="hold:10" }.achieved)
        assertNull(report.milestones.first { it.id=="hold:10" }.achievedAtEpochMilliseconds)
    }
    @Test fun beginnerProgressIncludesHangOnlySets() {
        val report=WorkoutProgression.report(listOf(hold("one")),ExerciseCounter.Exercise.pullUp)
        assertEquals(10.0,report.bestHoldSeconds);assertEquals(10.0,report.totalHoldSeconds)
        assertEquals(0,report.completedReps);assertEquals(11.0,report.goal.target)
        assertTrue(report.milestones.first { it.id == "hold:10" }.achieved)
        assertFalse(report.milestones.first { it.id == "reps:1" }.achieved)
        assertTrue(report.coaching.contains("baseline"))
    }
    @Test fun incompleteAcceptedAttemptIsPracticeNotRepOrRepMilestone() {
        val set=hold("set").copy(reps=listOf(attempt(false)))
        val report=WorkoutProgression.report(listOf(set),ExerciseCounter.Exercise.pullUp)
        assertEquals(1,report.partialAttempts);assertEquals(0,report.completedReps)
        assertEquals(60.0,report.bestElbowExcursionDegrees)
        assertTrue(report.milestones.first { it.id == "firstAttempt" }.achieved)
        assertFalse(report.milestones.first { it.id == "reps:1" }.achieved)
    }
    @Test fun completedCycleUnlocksRepGoalAndMilestone() {
        val report=WorkoutProgression.report(listOf(hold("set").copy(reps=listOf(attempt(true)))),ExerciseCounter.Exercise.pullUp)
        assertEquals(1,report.completedReps);assertEquals(WorkoutGoalMetric.completedReps,report.goal.metric)
        assertEquals(2.0,report.goal.target)
        assertTrue(report.milestones.first { it.id == "reps:1" }.achieved)
    }
    @Test fun goalOnlyUsesWorkoutsAfterCreationAndStaysFixed() {
        val goal=WorkoutGoal(ExerciseCounter.Exercise.pullUp,WorkoutGoalMetric.holdSeconds,11.0,200)
        val before=hold("old",100,20.0)
        assertNull(WorkoutProgression.report(listOf(before),goal.exercise,goal).goalBest)
        val report=WorkoutProgression.report(listOf(before,hold("new",300,12.0)),goal.exercise,goal)
        assertEquals(goal,report.goal);assertEquals(12.0,report.goalBest);assertEquals(1.0,report.goalFraction)
        assertTrue(report.goalAchieved)
    }
    @Test fun exerciseHistoriesAndGoalsAreIndependent() {
        val report=WorkoutProgression.report(listOf(hold("pull",seconds=30.0),hold("dip",seconds=6.0,exercise=ExerciseCounter.Exercise.dip)),ExerciseCounter.Exercise.dip)
        assertEquals("Top support",report.holdTitle);assertEquals(6.0,report.bestHoldSeconds)
        assertEquals(7.0,report.goal.target)
        assertFailsWith<IllegalArgumentException> { WorkoutProgression.report(emptyList(),ExerciseCounter.Exercise.dip,WorkoutGoal(ExerciseCounter.Exercise.pullUp,WorkoutGoalMetric.holdSeconds,5.0)) }
    }
    @Test fun legacyAndUndatedHistoryHasNoInventedTimeOrHold() {
        val legacy=hold("old",null).copy(activity=null,reps=listOf(attempt(false)))
        val report=WorkoutProgression.report(listOf(legacy),legacy.exercise)
        assertNull(report.bestHoldSeconds);assertTrue(report.trend.isEmpty());assertTrue(report.coaching.contains("older"))
    }
    @Test fun deduplicationAndLiveDraftCannotInflateProgress() {
        val set=hold("one")
        val report=WorkoutProgression.report(listOf(set,set,hold("draft",seconds=100.0).copy(endReason="running")),set.exercise)
        assertEquals(10.0,report.totalHoldSeconds);assertEquals(1,report.trend.size)
    }
    @Test fun goalsRejectUnusableTargetsAndBridgeRoundTrips() {
        val bridge=WorkoutProgressBridge()
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,0.0,-1.0,3601.0))
            assertFailsWith<IllegalArgumentException> { bridge.goalJson("pullUp","holdSeconds",value,200) }
        assertFailsWith<IllegalArgumentException> { bridge.goalJson("dip","completedReps",1.5,200) }
        val goal=bridge.goalJson("pullUp","holdSeconds",11.0,200)
        val json=bridge.reportJson(WorkoutAnalysis.json.encodeToString(listOf(hold("new",300,12.0))),"pullUp",goal)
        assertTrue(WorkoutAnalysis.json.decodeFromString<WorkoutProgressReport>(json).goalAchieved)
    }
}
