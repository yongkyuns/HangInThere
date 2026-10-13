package dev.hanginthere.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hanginthere.android.session.WorkoutRecordStore
import dev.hanginthere.android.ui.WorkoutLibraryDialog
import dev.hanginthere.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import android.util.Log
import androidx.compose.ui.semantics.SemanticsNode
import java.io.File
import java.util.TimeZone
import java.util.UUID

class WorkoutLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun shippingLibraryExposesContextHistoryPlansAndPrivacyControls() {
        val root = File(compose.activity.cacheDir, "library-ui-test-${UUID.randomUUID()}")
        val store = WorkoutRecordStore(compose.activity.applicationContext, root)
        val id = UUID.randomUUID().toString()
        val time = System.currentTimeMillis()
        val record = WorkoutSetRecord(id, ExerciseCounter.Exercise.pullUp, 0.0, 8.0, "manual", emptyList(), "authored", 1,
            trackedAtEpochMilliseconds = time, activity = WorkoutActivity(emptyList(), listOf(WorkoutPhaseTotal(WorkoutMotionPhase.hang, 8.0)), 8.0, 0.0, 8.0, 8.0, false, 2))
        store.save(record)
        val context = WorkoutContext(assistance = WorkoutAssistance.none, addedLoadKg = 0.0, equipment = "test bar", cameraView = "front")
        store.libraryCommand(WorkoutLibraryCommand("review", setId = id, correction = WorkoutReviewCorrection(context = context), atEpochMilliseconds = time))
        store.libraryCommand(WorkoutLibraryCommand("plan", plan = WorkoutPlan("ui-plan", "My pull-up practice",
            WorkoutInsights.localDay(time, TimeZone.getDefault().getOffset(time) / 60000),
            listOf(WorkoutPlanItem("ui-item", ExerciseCounter.Exercise.pullUp, WorkoutGoalMetric.holdSeconds, 5.0, 2, context))), atEpochMilliseconds = time))
        try {
            assertEquals("Build a repeatable baseline", WorkoutInsights.report(store.libraryState(), null,
                System.currentTimeMillis(), TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000).focus.title)
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme { WorkoutLibraryDialog(recordStore = store) {} } } }
            try { compose.waitUntil(10000) { compose.onAllNodesWithText("Build a repeatable baseline").fetchSemanticsNodes().isNotEmpty() } }
            catch (failure: Throwable) {
                var remaining = 128
                fun dump(node: SemanticsNode) {
                    if (remaining-- <= 0) return
                    Log.e("WorkoutLibraryUi", node.config.toString())
                    node.children.forEach(::dump)
                }
                compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::dump)
                throw failure
            }
            compose.onNodeWithText("Build a repeatable baseline").assertIsDisplayed()
            compose.onNodeWithContentDescription("Observed hold seconds, oldest to newest; unavailable values are gaps.", substring = true).assertExists()
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("Review and correct").assertIsDisplayed()
            compose.onNodeWithText("Plans").performClick()
            compose.onNodeWithText("Plan your practice").assertIsDisplayed()
            compose.onNodeWithText("Pull-up: 2 sets of 5.0 hold seconds per set").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("1/2 sets meet this target · preview; link to confirm").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Link recorded workout").performScrollTo().performClick()
            compose.onNodeWithText("Pull-up · 1 sets", substring = true).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("1/2 sets meet this target").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Storage").performClick()
            compose.onNodeWithText("Restore backup").assertIsDisplayed()
            compose.onNodeWithText("Keep explicitly attached review videos").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("No upload runs automatically. Backup and video export do not grant training consent.").performScrollTo().assertIsDisplayed()
        } finally { root.deleteRecursively() }
    }
}
