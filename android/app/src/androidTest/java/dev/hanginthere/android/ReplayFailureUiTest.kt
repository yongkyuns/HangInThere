package dev.hanginthere.android

import android.net.Uri
import androidx.activity.compose.setContent
import dev.hanginthere.android.ui.HangTheme
import dev.hanginthere.android.ui.WorkoutScreen
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.session.ReplayOutcome
import dev.hanginthere.android.session.WorkoutViewModel
import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Authored black pixels test the real decoder/model and a recoverable setup failure. */
@RunWith(AndroidJUnit4::class)
class ReplayFailureUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun noPersonRecordingShowsSetupFailureAndAllowsRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val video = File(app.cacheDir, "no-person-ui.mp4")
        instrumentation.context.assets.open("generated/no-person.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val model = ViewModelProvider(compose.activity)[WorkoutViewModel::class.java]
        compose.runOnUiThread { compose.activity.setContent { HangTheme { WorkoutScreen(model,allowCamera=false) } } }
        try {
            compose.runOnUiThread { model.replay(Uri.fromFile(video)) }
            compose.waitUntil(120_000) { model.state.value.finished }
            val state = model.state.value
            val output = requireNotNull(app.getExternalFilesDir("evaluation")); output.mkdirs()
            File(output, "ui-no-person-report.json").writeText(buildJsonObject {
                put("fixture", "authored-black-no-person"); put("scope", "setup failure and retry; no dynamic accuracy claim")
                put("outcome", state.replayOutcome?.name ?: "unknown"); put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
                put("phase", state.result?.snapshot?.phase?.name ?: "unknown")
                put("observedMovements", state.result?.snapshot?.observedMovements ?: -1)
            }.toString())
            assertEquals(ReplayOutcome.Kind.setupUnavailable, state.replayOutcome)
            assertNotNull("Unqualified recording must explain setup failure", state.error)
            assertEquals(0, state.result?.snapshot?.observedMovements)
            compose.onNodeWithText("Evaluate again").assertIsDisplayed().performClick()
            assertNotSame("Retry must start a new evaluation", state, model.state.value)
            compose.waitUntil(120_000) { model.state.value.finished }
            assertEquals(ReplayOutcome.Kind.setupUnavailable, model.state.value.replayOutcome)
            compose.onNodeWithText("Evaluate again").assertIsDisplayed()
        } finally { video.delete() }
    }

    @Test fun homeLiveEntryDiscardsInterruptedReplayAndStaleResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = ViewModelProvider(compose.activity)[WorkoutViewModel::class.java]
        compose.runOnUiThread {
            model.replay(Uri.EMPTY)
            model.suspendForBackground()
            assertTrue(model.state.value.replay)
            assertTrue(model.state.value.interrupted)
            model.prepareLive(WorkoutExerciseSelection.dip)
        }
        model.inferenceExecutor.submit {}.get(30, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        val ready = model.state.value
        assertFalse(ready.replay)
        assertFalse(ready.interrupted)
        assertFalse(ready.finished)
        assertEquals(WorkoutExerciseSelection.dip, ready.selection)
        assertNull(ready.result)
    }

    @Test fun terminalReplayIgnoresManualSetupWithoutLosingRetry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val video = File(app.cacheDir, "terminal-manual-ui.mp4")
        instrumentation.context.assets.open("generated/no-person.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val model = ViewModelProvider(compose.activity)[WorkoutViewModel::class.java]
        compose.runOnUiThread { compose.activity.setContent { HangTheme { WorkoutScreen(model,allowCamera=false) } } }
        try {
            // Neither exercise selection may restart a terminal replay from a stale tap.
            for (selection in listOf(WorkoutExerciseSelection.automatic, WorkoutExerciseSelection.pullUp)) {
                compose.runOnUiThread { model.replay(Uri.fromFile(video), selection) }
                compose.waitUntil(120_000) { model.state.value.finished }
                val ended = model.state.value
                assertEquals(ReplayOutcome.Kind.setupUnavailable, ended.replayOutcome)
                val result = requireNotNull(ended.result)
                assertNull(result.snapshot.referenceEdge)
                compose.runOnUiThread { model.startManually() }
                model.inferenceExecutor.submit {}.get(30, TimeUnit.SECONDS)
                instrumentation.waitForIdleSync()
                assertEquals(ended, model.state.value)
                compose.onNodeWithText("Evaluate again").assertIsDisplayed()
            }
        } finally { video.delete() }
    }
}
