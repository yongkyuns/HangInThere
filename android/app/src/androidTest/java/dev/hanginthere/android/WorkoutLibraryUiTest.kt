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
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class WorkoutLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun olderPagedWorkoutCanOpenItsSet() {
        val now = 20_000L * 86_400_000
        val records = (0..109).map { index ->
            WorkoutSetRecord("history-$index", ExerciseCounter.Exercise.pullUp, 0.0, 8.0, "manual", emptyList(), "authored", 1,
                trackedAtEpochMilliseconds = now - index * 3_600_000L,
                activity = WorkoutActivity(emptyList(), listOf(WorkoutPhaseTotal(WorkoutMotionPhase.hang, 8.0)), 8.0, 0.0, 8.0, 8.0, false, 2))
        }
        val state = WorkoutLibrary.register(WorkoutLibraryState(), records)
        val report = WorkoutJourney.report(state, now, 0)
        val opened = AtomicReference<String?>(null)
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
            dev.hanginthere.android.ui.WorkoutJourneyPanel(report,
                loadPage = { day, cursor -> WorkoutJourney.page(state, now, 0, day, cursor) },
                onOpenSet = { opened.set(it.id) })
        } } }
        compose.onNodeWithTag("workout-history-list").performScrollToNode(hasText("Earlier workouts"))
        compose.onNodeWithText("Earlier workouts").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Earlier workouts").fetchSemanticsNodes().isEmpty() }
        val oldestDate = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(records.last().trackedAtEpochMilliseconds!!))
        compose.onNodeWithTag("workout-history-list").performScrollToNode(hasText(oldestDate))
        compose.onNodeWithText(oldestDate).performClick()
        compose.onNodeWithTag("workout-history-list").performScrollToNode(hasText("Set 1 · Pull-up"))
        compose.onNodeWithText("Set 1 · Pull-up").performClick()
        assertEquals(records.last().id, opened.get())
    }
    @Test fun setSummaryRevealsRepCurvesOnlyAfterDrillDown() {
        val angles=AngleMetrics(80.0,170.0,125.0,2.0,1.0)
        val reps=(0..1).map { index ->
            val start=index*2.0
            RepRecord("ui-set:rep:${index+1}","ui-set",index+1,ExerciseCounter.Exercise.dip,
                start,start+1,start+2,start+2,start+2,"returned","accepted",1.0,1.0,
                angles,angles,.2,.0,2.0,1.0,(0..10).map { step ->
                    val angle=170.0-90.0*(1-kotlin.math.abs(step-5)/5.0)
                    MotionSample(start+step*.2,angle,angle,null,true,false)
                },false)
        }
        val interrupted=reps.last().copy(id="ui-set:rep:3",ordinal=3,acceptedAt=null,returnedAt=null,closeReason="trackingLost")
        val record=WorkoutSetRecord("ui-set",ExerciseCounter.Exercise.dip,0.0,6.0,"disengaged",reps+interrupted,"authored",1)
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
            dev.hanginthere.android.ui.WorkoutReviewDialog(current=record.reps,history=emptyList(),error=null,currentSet=record,showsLibraryLink=false) {}
        } } }
        compose.onNodeWithText("Set summary").assertIsDisplayed()
        compose.onNodeWithText("completed reps").assertIsDisplayed()
        compose.onNodeWithText("Elbow angle",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Rep 3").assertDoesNotExist()
        compose.onNodeWithText("Rep 1").performScrollTo().performClick()
        compose.onNodeWithText("Rep details").assertIsDisplayed()
        compose.onNodeWithText("Elbow angle",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Left mean / range",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Camera measurements",substring=true).performScrollTo().performClick()
        compose.onNodeWithText("Left mean / range",substring=true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Set summary").assertIsDisplayed()
    }
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
            try { compose.waitUntil(10000) { compose.onAllNodesWithText("Your practice").fetchSemanticsNodes().isNotEmpty() } }
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
            compose.onNodeWithText("Your practice").assertIsDisplayed()
            compose.onNodeWithText("Build on your 8s hang").assertIsDisplayed()
            compose.onNodeWithText("Target").assertDoesNotExist()
            compose.onNodeWithTag("workout-history-list").performScrollToNode(hasText("0 reps · 1 set"))
            compose.onNodeWithText("0 reps · 1 set").performClick()
            compose.onNodeWithTag("workout-history-list").performScrollToNode(hasText("Set 1 · Pull-up"))
            compose.onNodeWithText("Set 1 · Pull-up").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Set summary").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("seconds of practice").assertIsDisplayed()
            compose.onNodeWithText("Left mean / range",substring=true).assertDoesNotExist()
            compose.onAllNodesWithText("Done").onLast().performClick()
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Manage recordings").performClick()
            compose.onNodeWithText("Review and correct").assertIsDisplayed()
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Training plans").performClick()
            compose.onNodeWithText("Plan your practice").assertIsDisplayed()
            compose.onNodeWithText("Pull-up: 2 sets of 5.0 hold seconds per set").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("1/2 sets meet this target · preview; link to confirm").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Link recorded workout").performScrollTo().performClick()
            compose.onNodeWithText("Pull-up · 1 sets", substring = true).performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("1/2 sets meet this target").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("More").performClick()
            compose.onNodeWithText("Storage and export").performClick()
            compose.onNodeWithText("Restore backup").assertIsDisplayed()
            compose.onNodeWithText("Keep explicitly attached review videos").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("No upload runs automatically. Backup and video export do not grant training consent.").performScrollTo().assertIsDisplayed()
        } finally { root.deleteRecursively() }
    }
}
