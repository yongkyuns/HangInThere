package dev.hanginthere.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import dev.hanginthere.android.session.WorkoutRecordStore
import dev.hanginthere.android.ui.*
import dev.hanginthere.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkoutLoggingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun alternatingEntriesTapUndoAndEditPersistWithoutCamera() {
        val root = File(compose.activity.cacheDir, "logging-ui-${UUID.randomUUID()}")
        val store = WorkoutRecordStore(compose.activity, root)
        try {
            compose.runOnUiThread { compose.activity.setContent { HangTheme { WorkoutLogDialog(recordStore = store) {} } } }
            fun choose(name: String) {
                compose.onNodeWithTag("chooseLogExercise").performScrollTo().performClick()
                compose.onNode(hasText(name) and hasClickAction()).performScrollTo().performClick()
            }
            fun enter(tag: String, value: String) { compose.onNodeWithTag(tag).performTextReplacement(value) }
            choose("Bench press"); enter("logWeight", "165"); enter("logReps", "5")
            compose.onNodeWithTag("saveLoggedSet").performScrollTo().performClick()
            compose.waitUntil(10000) { store.libraryState().workoutLogs.singleOrNull()?.sets?.size == 1 }
            choose("EZ curls"); enter("logWeight", "75.9"); enter("logReps", "7")
            compose.onNodeWithTag("saveLoggedSet").performScrollTo().performClick()
            compose.waitUntil(10000) { store.libraryState().workoutLogs.single().sets.size == 2 }
            choose("Bench press")
            compose.onNodeWithTag("logWeight").assertTextContains("165")
            compose.onNodeWithTag("openTapCounter").performScrollTo().performClick()
            repeat(6) { compose.onNodeWithTag("tapRep").performClick() }
            compose.onNodeWithText("Undo").performClick()
            compose.onNodeWithTag("finishTapSet").performClick()
            compose.waitUntil(10000) { store.libraryState().workoutLogs.single().sets.size == 3 }
            val first = store.libraryState().workoutLogs.single().sets.first()
            compose.onNodeWithTag("logged-set-${first.id}").performScrollTo().performClick()
            compose.onNodeWithTag("editLogReps").performTextReplacement("6")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10000) { store.libraryState().workoutLogs.single().sets.first().reps == 6 }
            compose.onNodeWithTag("logReps").performClick().performImeAction()
            compose.onNodeWithTag("chooseLogExercise").performScrollTo()
            screenshot("logging-light")
            val reloaded = WorkoutRecordStore(compose.activity, root).libraryState().workoutLogs.single()
            assertEquals(listOf(6, 7, 5), reloaded.sets.map { it.reps })
            assertEquals("tap", reloaded.sets.last().method); assertNotNull(reloaded.sets.last().startedAtEpochMilliseconds)
            assertTrue(store.libraryState().entries.isEmpty())
            val restored = WorkoutRecordStore(compose.activity, File(root, "restored"))
            restored.libraryRestore(store.libraryExport())
            assertEquals(reloaded, restored.libraryState().workoutLogs.single())
        } finally { compose.runOnUiThread { compose.activity.setContent {} }; root.deleteRecursively() }
    }
    @Test fun calendarLoadsOldWorkoutsAndSwitchesBetweenWeekMonthAndList() {
        val root = File(compose.activity.cacheDir, "calendar-ui-${UUID.randomUUID()}")
        val store = WorkoutRecordStore(compose.activity, root)
        val zone = java.time.ZoneId.systemDefault().id
        val range = WorkoutCalendarPolicy.dayByKey(20030, zone) // 2024-11-03, outside the recent overview.
        val log = WorkoutLog("historical", range.startEpochMilliseconds + 3600000,
            listOf(WorkoutLoggedSet("bench", "Bench press", 5, 165.0), WorkoutLoggedSet("curl", "EZ curls", 7, 75.9)), range.startEpochMilliseconds)
        store.saveWorkoutLog(log)
        val state = store.libraryState(); val now = System.currentTimeMillis()
        val dark = mutableStateOf(false)
        try {
            compose.runOnUiThread { compose.activity.setContent { HangTheme(dark.value) { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                WorkoutJourneyPanel(WorkoutJourney.report(state, now, 0),
                    loadPage = { day, cursor -> WorkoutJourney.page(state, now, 0, day, cursor) }, onOpenSet = {},
                    initialAnchor = range.startEpochMilliseconds,
                    loadCalendar = { WorkoutJourney.calendarDays(state, it, now) },
                    loadRange = { day, cursor -> WorkoutJourney.page(state, now, 0, day = day?.day, cursor = cursor, range = day) })
            } } } }
            compose.onNodeWithTag("calendar-mode-month").assertIsDisplayed()
            screenshot("calendar-month-light")
            compose.onNodeWithTag("calendar-day-${range.day}").performScrollTo().performClick()
            compose.onNodeWithTag("workout-history-list").performScrollToNode(hasText("12 reps · 2 sets"))
            compose.onNodeWithText("12 reps · 2 sets").assertIsDisplayed()
            compose.onNodeWithTag("workout-history-list").performScrollToIndex(0)
            compose.onNodeWithTag("calendar-mode-week").performClick()
            compose.runOnUiThread { dark.value = true }
            screenshot("calendar-week-dark")
            compose.onNodeWithTag("calendar-day-${range.day}").assertIsDisplayed()
            compose.onNodeWithTag("calendar-day-${range.day - 8}").assertDoesNotExist()
            compose.onNodeWithTag("calendar-mode-month").performClick()
            compose.onNodeWithContentDescription("Previous month").performClick()
            compose.onNodeWithText("October 2024").assertIsDisplayed()
            compose.onNodeWithContentDescription("Next month").performClick()
            compose.onNodeWithTag("calendar-day-${range.day}").assertIsDisplayed()
            compose.onNodeWithTag("workout-layout-list").performClick()
            compose.onNodeWithTag("calendar-mode-month").assertDoesNotExist()
            screenshot("workout-list-dark")
            compose.onNodeWithTag("workout-history-list").performScrollToNode(hasText("12 reps · 2 sets"))
            compose.onNodeWithText("12 reps · 2 sets").assertIsDisplayed()
        } finally { compose.runOnUiThread { compose.activity.setContent {} }; root.deleteRecursively() }
    }
    @Test fun homeOpensCalendarAndManualLoggingDirectly() {
        compose.onNodeWithText("Your workouts").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("workout-layout-calendar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("calendar-mode-month").assertIsDisplayed()
        screenshot("library-month-light")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Log workout").performClick()
        compose.onNodeWithTag("chooseLogExercise").assertIsDisplayed()
        compose.onNodeWithTag("openTapCounter").assertIsDisplayed()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Display capture must wait for the compositor, beyond Compose's idle state.
        Thread.sleep(150)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val output = File(instrumentation.targetContext.getExternalFilesDir("calendar-logging-check"), "$name.png")
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }

}
