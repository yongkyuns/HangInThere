package dev.hanginthere.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
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

    @Test fun analysisDistinguishesAcceptedCountFromUnobservedReturn() {
        val analysis=WorkoutAnalysis("ui-test",ExerciseCounter.Exercise.pullUp)
        analysis.observe(MotionSample(0.0,170.0,170.0,.2,true,false),ExerciseCounter.Phase.ready,null)
        analysis.observe(MotionSample(.2,80.0,80.0,.1,true,false),ExerciseCounter.Phase.outbound,null)
        analysis.observe(MotionSample(.4,80.0,80.0,.1,true,false),ExerciseCounter.Phase.returning,
            ExerciseCounter.Event(ExerciseCounter.Outcome.movement,.4,"authored UI test"))
        analysis.finish("trackingLost")
        var closed=false
        compose.runOnUiThread {
            compose.activity.setContent { MaterialTheme {
                WorkoutReviewDialog(analysis.records,emptyList(),null) { closed=true }
            } }
        }
        compose.onNodeWithText("Rep 1 · accepted").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Left and right elbow angles for rep 1; missing intervals are gaps").assertExists()
        compose.onNodeWithText("The complete return was not observed.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertTrue(closed)
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
