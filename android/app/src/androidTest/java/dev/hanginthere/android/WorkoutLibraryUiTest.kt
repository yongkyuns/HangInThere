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
import java.util.UUID

class WorkoutLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun shippingLibraryExposesContextHistoryPlansAndPrivacyControls() {
        val store = WorkoutRecordStore.live(compose.activity.applicationContext)
        val id = UUID.randomUUID().toString()
        val time = System.currentTimeMillis()
        val record = WorkoutSetRecord(id, ExerciseCounter.Exercise.pullUp, 0.0, 8.0, "manual", emptyList(), "authored", 1,
            trackedAtEpochMilliseconds = time, activity = WorkoutActivity(emptyList(), listOf(WorkoutPhaseTotal(WorkoutMotionPhase.hang, 8.0)), 8.0, 0.0, 8.0, 8.0, false, 2))
        store.save(record)
        store.libraryCommand(WorkoutLibraryCommand("review", setId = id, correction = WorkoutReviewCorrection(context = WorkoutContext(
            assistance = WorkoutAssistance.none, addedLoadKg = 0.0, equipment = "test bar", cameraView = "front")), atEpochMilliseconds = time))
        try {
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme { WorkoutLibraryDialog {} } } }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Build a repeatable baseline").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Build a repeatable baseline").assertIsDisplayed()
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("Review and correct").assertIsDisplayed()
            compose.onNodeWithText("Plans").performClick()
            compose.onNodeWithText("Plan your practice").assertIsDisplayed()
            compose.onNodeWithText("Storage").performClick()
            compose.onNodeWithText("Restore backup").assertIsDisplayed()
            compose.onNodeWithText("Keep explicitly attached review videos").assertIsDisplayed()
            compose.onNodeWithText("No upload runs automatically. Backup and video export do not grant training consent.").performScrollTo().assertIsDisplayed()
        } finally { store.libraryCommand(WorkoutLibraryCommand("delete", setId = id, atEpochMilliseconds = System.currentTimeMillis())) }
    }
}
