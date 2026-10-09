package dev.hanginthere.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hanginthere.android.session.AnalysisPipeline
import dev.hanginthere.android.session.ReplayOutcome
import dev.hanginthere.android.session.WorkoutViewModel
import dev.hanginthere.android.ui.WorkoutActions
import dev.hanginthere.core.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutActionsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
