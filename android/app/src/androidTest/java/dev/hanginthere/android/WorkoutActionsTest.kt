package dev.hanginthere.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.mutableStateOf
import dev.hanginthere.android.ui.WorkoutStartFeedbackOverlay
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hanginthere.android.session.AnalysisPipeline
import dev.hanginthere.android.session.ReplayOutcome
import dev.hanginthere.android.session.WorkoutViewModel
import dev.hanginthere.android.ui.WorkoutActions
import dev.hanginthere.android.ui.WorkoutReviewDialog
import dev.hanginthere.core.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutActionsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun liveStartCueAnnouncesOnceExpiresAndRearmsForNextSet() {
        val runtime = WorkoutRuntime()
        val snapshot = mutableStateOf(runtime.snapshot)
        val live = mutableStateOf(true)
        val foreground = mutableStateOf(true)
        val now = mutableStateOf(10.0)
        val policy = WorkoutStartFeedback()
        val spoken = mutableListOf<String>()
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
            WorkoutStartFeedbackOverlay(snapshot.value, live.value, foreground.value, policy,
                announce = { spoken.add(it) }, nowSeconds = { now.value })
        } } }
        compose.onNodeWithTag("liveSetStarted").assertDoesNotExist()
        compose.runOnUiThread {
            runtime.session.start(ExerciseCounter.Exercise.dip,ArmMeasurement.Side.left)
            snapshot.value = runtime.snapshot
        }
        compose.onNodeWithText("SET STARTED").assertIsDisplayed()
        compose.runOnUiThread { snapshot.value = snapshot.value.copy(observedMovements = 1); now.value = 11.0 }
        compose.onNodeWithText("SET STARTED").assertIsDisplayed()
        compose.runOnUiThread { assertTrue(spoken == listOf("Set started")); now.value = 12.1 }
        compose.onNodeWithTag("liveSetStarted").assertDoesNotExist()
        compose.runOnUiThread {
            runtime.session.finish(); snapshot.value = runtime.snapshot
        }
        compose.onNodeWithTag("liveSetStarted").assertDoesNotExist()
        compose.runOnUiThread {
            runtime.session.start(ExerciseCounter.Exercise.pullUp,ArmMeasurement.Side.left)
            snapshot.value = runtime.snapshot
        }
        compose.onNodeWithText("SET STARTED").assertIsDisplayed()
        compose.runOnUiThread { assertTrue(spoken.size == 2); foreground.value = false }
        compose.onNodeWithTag("liveSetStarted").assertDoesNotExist()
        compose.runOnUiThread { foreground.value = true }
        compose.onNodeWithTag("liveSetStarted").assertDoesNotExist()
        compose.runOnUiThread {
            live.value = false
            runtime.session.start(ExerciseCounter.Exercise.dip,ArmMeasurement.Side.left)
            snapshot.value = runtime.snapshot
        }
        compose.onNodeWithTag("liveSetStarted").assertDoesNotExist()
        compose.runOnUiThread { assertTrue(spoken.size == 2) }
    }

    @Test fun analysisExcludesAcceptedPullUpWithUnobservedReturn() {
        val analysis=WorkoutAnalysis("ui-test",ExerciseCounter.Exercise.pullUp)
        analysis.observe(MotionSample(0.0,170.0,170.0,.2,true,false),ExerciseCounter.Phase.ready,null)
        analysis.observe(MotionSample(.2,80.0,80.0,.1,true,false),ExerciseCounter.Phase.outbound,null)
        analysis.observe(MotionSample(.4,80.0,80.0,.1,true,false),ExerciseCounter.Phase.returning,
            ExerciseCounter.Event(ExerciseCounter.Outcome.movement,.4,"authored UI test"))
        analysis.finish("trackingLost")
        var closed=false
        compose.runOnUiThread {
            compose.activity.setContent { MaterialTheme {
                val set = WorkoutSetRecord("ui-test", ExerciseCounter.Exercise.pullUp, 0.0, .4,
                    "trackingLost", analysis.records, "authored UI test", 0)
                WorkoutReviewDialog(analysis.records,listOf(set),null,goalsEnabled=false) { closed=true }
            } }
        }
        compose.onNodeWithText("Hangs, support holds and partial attempts are practice. Completed cycles appear in rep timing.").assertIsDisplayed()
        compose.onAllNodesWithText("Rep 1", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Left and right elbow angles for rep 1; missing intervals are gaps").assertDoesNotExist()
        compose.onNodeWithText("Saved sets").assertExists()
        compose.onNodeWithText("Practice attempts · 1").assertExists()
        compose.onNodeWithText("Practice attempts · 1").performScrollTo().performClick()
        compose.onNodeWithText("Attempt 1 · Return not recorded").performScrollTo().performClick()
        compose.onNodeWithText("Return not recorded").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The movement reached the counting position, but its return was not recorded. A completed rep cannot be confirmed.").assertExists()
        compose.onNodeWithText("Done").performClick()
        assertTrue(closed)
    }

    @Test fun pullUpReviewIncludesOnlyCompletedCycles() = assertCompletedReview(ExerciseCounter.Exercise.pullUp)

    @Test fun recoveredActiveAttemptExplainsAnInterruptionInsteadOfLiveTracking() {
        val analysis=WorkoutAnalysis("recovered-ui",ExerciseCounter.Exercise.pullUp)
        analysis.observe(MotionSample(0.0,170.0,170.0,.2,true,false),ExerciseCounter.Phase.ready,null)
        analysis.observe(MotionSample(.2,120.0,120.0,.1,true,false),ExerciseCounter.Phase.outbound,null)
        val raw=WorkoutSetRecord("recovered-ui",ExerciseCounter.Exercise.pullUp,0.0,.2,"running",
            listOf(requireNotNull(analysis.activeRecord)),"authored recovery UI test",0)
        val recovered=WorkoutRecordCodec.recover(WorkoutRecordCodec.summary(raw))
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
            WorkoutReviewDialog(emptyList(),listOf(recovered),null,goalsEnabled=false) {}
        } } }
        compose.onNodeWithText("Practice attempts · 1").performScrollTo().performClick()
        compose.onNodeWithText("Attempt 1 · Observation interrupted").performScrollTo().performClick()
        compose.onNodeWithText("Observation interrupted").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Interrupted recording recovered").assertExists()
        compose.onNodeWithText("In progress").assertDoesNotExist()
        compose.onNodeWithText("A return was observed",substring=true).assertDoesNotExist()
        assertTrue(recovered.reps.single().closeReason=="inProgress")
    }
    @Test fun dipReviewIncludesOnlyCompletedCycles() = assertCompletedReview(ExerciseCounter.Exercise.dip)

    private fun assertCompletedReview(exercise: ExerciseCounter.Exercise) {
        val analysis = WorkoutAnalysis("ui-test", exercise)
        fun frame(time: Double, angle: Double, phase: ExerciseCounter.Phase, accepted: Boolean = false) {
            analysis.observe(MotionSample(time,angle,angle,.2,true,false),phase,
                if (accepted) ExerciseCounter.Event(ExerciseCounter.Outcome.movement,time,"authored UI test") else null)
        }
        frame(0.0,170.0,ExerciseCounter.Phase.ready)
        frame(.2,100.0,ExerciseCounter.Phase.outbound)
        frame(.4,80.0,ExerciseCounter.Phase.returning,exercise == ExerciseCounter.Exercise.pullUp)
        frame(.6,170.0,ExerciseCounter.Phase.ready,exercise == ExerciseCounter.Exercise.dip)
        frame(.8,100.0,ExerciseCounter.Phase.outbound)
        frame(1.0,80.0,ExerciseCounter.Phase.returning,exercise == ExerciseCounter.Exercise.pullUp)
        analysis.finish("trackingLost")
        val partial = analysis.records.last().copy(id="ui-test:rep:3",ordinal=3,acceptedAt=null,
            outcome="partial",closeReason="partial")
        val set = WorkoutSetRecord("ui-test",exercise,0.0,1.0,"trackingLost",analysis.records+partial,"authored UI test",0)
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
            WorkoutReviewDialog(set.reps,listOf(set),null,goalsEnabled=false) {}
        } } }
        compose.onNodeWithText("Rep 1 · accepted").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Left and right elbow angles for rep 1; missing intervals are gaps").assertExists()
        compose.onNodeWithText("${exercise.title} · 1 rep · Interrupted").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Rep 2", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Rep 3", substring = true).assertCountEquals(0)
        // Completed history remains selectable while the current set contains
        // only unfinished attempts and therefore has no timing bars.
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
            WorkoutReviewDialog(set.reps.drop(1),listOf(set),null,goalsEnabled=false) {}
        } } }
        compose.onNodeWithText("Rep 1 ·", substring = true).performScrollTo().performClick()
        compose.onNodeWithText("Rep 1 · accepted").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Rep 2", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Rep 3", substring = true).assertCountEquals(0)
    }

    @Test fun zeroRepCurrentSetCannotBorrowOlderRepTiming() {
        val analysis=WorkoutAnalysis("older",ExerciseCounter.Exercise.pullUp)
        analysis.observe(MotionSample(0.0,170.0,170.0,.2,true,false),ExerciseCounter.Phase.ready,null)
        analysis.observe(MotionSample(.2,100.0,100.0,.15,true,false),ExerciseCounter.Phase.outbound,null)
        analysis.observe(MotionSample(.4,80.0,80.0,.1,true,false),ExerciseCounter.Phase.returning,
            ExerciseCounter.Event(ExerciseCounter.Outcome.movement,.4,"authored review test"))
        analysis.observe(MotionSample(.6,170.0,170.0,.2,true,false),ExerciseCounter.Phase.ready,null)
        val older=WorkoutSetRecord("older",ExerciseCounter.Exercise.pullUp,0.0,.6,"manual",analysis.records,null,null,activity=analysis.activity)
        val hold=WorkoutSetRecord("new-hang",ExerciseCounter.Exercise.pullUp,0.0,5.0,"running",emptyList(),null,null,
            activity=WorkoutActivity(emptyList(),listOf(WorkoutPhaseTotal(WorkoutMotionPhase.hang,5.0)),5.0,0.0,5.0,5.0,false))
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
            WorkoutReviewDialog(emptyList(),listOf(older),null,currentSet=hold,goalsEnabled=false) {}
        } } }
        compose.onNodeWithText("Set phases").assertIsDisplayed()
        compose.onNodeWithText("Rep timing").assertDoesNotExist()
        compose.onNodeWithText("Rep 1 · accepted").assertDoesNotExist()
        compose.onNodeWithText("Pull-up · 1 rep · Ended manually").performScrollTo().performClick()
        compose.onNodeWithText("Rep timing").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Rep 1 · accepted").performScrollTo().assertIsDisplayed()
    }
    @Test fun terminalReplayFailureCannotRenderStaleEndSetAction() {
        val runtime = WorkoutRuntime()
        runtime.session.start(ExerciseCounter.Exercise.dip, ArmMeasurement.Side.left)
        val pose = PoseResult(PresentationTime(0, 1), ImageSize(320.0, 240.0), emptyList(), "authored control test", 1)
        // Simulate the older rendered running snapshot racing the terminal failure.
        val state = WorkoutViewModel.State(replay = true, finished = true, error = "Decoder failed",
            replayOutcome = ReplayOutcome.Kind.analysisFailed,
            result = AnalysisPipeline.Result(pose, runtime.snapshot, .5, "Push up", 0.0))
        var retried = false
        compose.runOnUiThread {
            compose.activity.setContent {
                MaterialTheme { WorkoutActions(state, onFinish = { error("Stale finish action") }, onNext = { retried = true }, onRestart = {}) }
            }
        }
        compose.onNodeWithText("End set").assertDoesNotExist()
        compose.onNodeWithText("Evaluate again").assertIsDisplayed().performClick()
        assertTrue(retried)
    }
}
