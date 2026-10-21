package dev.hanginthere.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hanginthere.android.session.WorkoutRecordStore
import dev.hanginthere.android.ui.WorkoutLibraryDialog
import dev.hanginthere.android.ui.LibraryDashboardLoader
import dev.hanginthere.android.ui.loadLibraryDashboard
import dev.hanginthere.android.ui.LibraryLoadEvent
import dev.hanginthere.core.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import android.util.Log
import androidx.compose.ui.semantics.SemanticsNode
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class WorkoutLibraryUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun fastDefaultLoadsPublishPresentationStateOnTheMainThread() {
        val root = File(compose.activity.cacheDir, "library-ui-test-${UUID.randomUUID()}")
        val store = WorkoutRecordStore(compose.activity.applicationContext, root)
        store.save(hangRecord(UUID.randomUUID().toString(), System.currentTimeMillis()))
        val visible = mutableStateOf(false)
        val readyThreads = CopyOnWriteArrayList<Boolean>()
        val events = CopyOnWriteArrayList<LibraryLoadEvent>()
        try {
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
                if (visible.value) WorkoutLibraryDialog(recordStore = store, loadObserver = {
                    events.add(it)
                    if (it.stage == "ready.assigned") readyThreads.add(
                        android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                }) {}
            } } }
            repeat(3) { index ->
                compose.runOnUiThread { visible.value = true }
                compose.waitUntil(10_000) { events.count { it.stage == "published.ready" } == index + 1 }
                compose.onNodeWithText("Your practice").assertIsDisplayed()
                compose.runOnUiThread { visible.value = false }
                compose.waitUntil(10_000) { events.count { it.stage == "disposed" } == index + 1 }
            }
            assertEquals(listOf(true, true, true), readyThreads.toList())
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            root.deleteRecursively()
        }
    }
    @Test fun defaultLoaderSurvivesParentRecompositionAndReopensFresh() {
        val root = File(compose.activity.cacheDir, "library-ui-test-${UUID.randomUUID()}")
        val store = WorkoutRecordStore(compose.activity.applicationContext, root)
        store.save(hangRecord(UUID.randomUUID().toString(), System.currentTimeMillis()))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = thread(name = "LibraryUiStoreGate", isDaemon = true) {
            synchronized(store) { entered.countDown(); release.await(30, TimeUnit.SECONDS) }
        }
        val visible = mutableStateOf(true)
        val parentRevision = mutableIntStateOf(0)
        val committedRevision = AtomicInteger(-1)
        val events = CopyOnWriteArrayList<LibraryLoadEvent>()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
                val revision = parentRevision.intValue
                Text("Parent $revision")
                if (visible.value) WorkoutLibraryDialog(recordStore = store,
                    loadObserver = { events.add(it) },
                    onClose = { if (revision >= 0) visible.value = false })
                SideEffect { committedRevision.set(revision) }
            } } }
            compose.waitUntil(10_000) { events.any { it.stage == "load.enter" } }
            compose.onNodeWithTag("workout-library-loading").assertIsDisplayed()
            repeat(3) { index ->
                // The callback captures the value, so each revision changes a
                // dialog parameter instead of only recomposing sibling text.
                compose.runOnIdle { parentRevision.intValue = index + 1 }
                compose.waitUntil(10_000) { committedRevision.get() == index + 1 }
            }
            assertEquals(1, events.count { it.stage == "load.enter" })
            assertTrue(events.none { it.stage == "ready.assigned" })
            release.countDown(); owner.join(3_000)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Your practice").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { events.any { it.stage == "published.ready" } }
            compose.runOnUiThread { visible.value = false }
            compose.waitUntil(10_000) { events.any { it.stage == "disposed" } }
            compose.runOnUiThread { visible.value = true }
            compose.waitUntil(10_000) { events.count { it.stage == "published.ready" } == 2 }
            compose.onNodeWithText("Your practice").assertIsDisplayed()
            val requests = events.filter { it.stage == "load.enter" }
            assertEquals(2, requests.size)
            assertEquals(2, requests.map { it.composition }.distinct().size)
            assertTrue(requests.all { it.request == 1 })
        } finally {
            release.countDown(); owner.join(3_000)
            compose.runOnUiThread { compose.activity.setContent {} }
            root.deleteRecursively()
        }
    }

    @Test fun disposingLibraryCancelsItsOutstandingLoadWithoutPublishingReady() {
        val root = File(compose.activity.cacheDir, "library-ui-test-${UUID.randomUUID()}")
        val store = WorkoutRecordStore(compose.activity.applicationContext, root)
        val release = CompletableDeferred<Unit>()
        val loader: LibraryDashboardLoader = { source, cursor, key ->
            release.await(); loadLibraryDashboard(source, cursor, key)
        }
        val visible = mutableStateOf(true)
        val events = CopyOnWriteArrayList<LibraryLoadEvent>()
        try {
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
                if (visible.value) WorkoutLibraryDialog(recordStore = store, dashboardLoader = loader,
                    loadObserver = { events.add(it) }) {}
            } } }
            compose.waitUntil(10_000) { events.any { it.stage == "load.enter" } }
            compose.onNodeWithTag("workout-library-loading").assertIsDisplayed()
            compose.runOnUiThread { visible.value = false }
            compose.waitUntil(10_000) { events.any { it.stage == "load.cancelled" } && events.any { it.stage == "disposed" } }
            release.complete(Unit)
            compose.waitForIdle()
            assertTrue(events.none { it.stage == "ready.assigned" || it.stage == "published.ready" })
            compose.onNodeWithText("Your practice").assertDoesNotExist()
        } finally {
            release.cancel()
            compose.runOnUiThread { compose.activity.setContent {} }
            root.deleteRecursively()
        }
    }
    @Test fun libraryWaitsForTheActualLoadBeforeShowingHistory() {
        val root = File(compose.activity.cacheDir, "library-ui-test-${UUID.randomUUID()}")
        val store = WorkoutRecordStore(compose.activity.applicationContext, root)
        val release = CompletableDeferred<Unit>()
        val loader: LibraryDashboardLoader = { source, cursor, key ->
            release.await()
            loadLibraryDashboard(source, cursor, key)
        }
        try {
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
                WorkoutLibraryDialog(recordStore = store, dashboardLoader = loader) {}
            } } }
            compose.onNodeWithTag("workout-library-loading").assertIsDisplayed()
            compose.onNodeWithText("Your practice").assertDoesNotExist()
            compose.onNodeWithText("Retry").assertDoesNotExist()
            release.complete(Unit)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Your practice").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("workout-library-loading").assertDoesNotExist()
            compose.onNodeWithTag("workout-library-load-error").assertDoesNotExist()
        } finally { release.cancel(); root.deleteRecursively() }
    }
    @Test fun nullMessageLoadFailureStopsLoadingAndCanRetry() {
        val root = File(compose.activity.cacheDir, "library-ui-test-${UUID.randomUUID()}")
        val store = WorkoutRecordStore(compose.activity.applicationContext, root)
        val attempts = AtomicInteger()
        val loader: LibraryDashboardLoader = { source, cursor, key ->
            if (attempts.incrementAndGet() == 1) throw IllegalStateException()
            loadLibraryDashboard(source, cursor, key)
        }
        try {
            compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
                WorkoutLibraryDialog(recordStore = store, dashboardLoader = loader) {}
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("workout-library-load-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Couldn't load your workout history. Try again.").assertIsDisplayed()
            compose.onNodeWithTag("workout-library-loading").assertDoesNotExist()
            compose.onNodeWithText("Your practice").assertDoesNotExist()
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Your practice").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("workout-library-load-error").assertDoesNotExist()
            assertEquals(2, attempts.get())
        } finally { root.deleteRecursively() }
    }
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
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("workout-history-list").performScrollToNode(hasText(oldestDate))
        compose.onNodeWithText(oldestDate).assertIsDisplayed()
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
            dev.hanginthere.android.ui.WorkoutReviewDialog(current=record.reps,history=emptyList(),error=null,currentSet=record) {}
        } } }
        compose.onNodeWithText("Set summary").assertIsDisplayed()
        compose.onNodeWithText("completed reps").assertIsDisplayed()
        compose.onNodeWithText("History").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Your practice").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Done").onLast().performClick()
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
        val record = hangRecord(id, time)
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
                // Keep the original timeout/default loader. A loading node alone
                // cannot distinguish blocked IO from an effect/publication fault.
                // Class/line-only stacks expose no workout or exception contents.
                Thread.getAllStackTraces().entries
                    .filter { it.key.name == "main" || it.key.name.startsWith("DefaultDispatcher-worker-") }
                    .sortedWith(compareBy({ it.key.name != "main" }, { it.key.name })).take(16).forEach { (thread, stack) ->
                        Log.e("WorkoutLibraryUi", "Load timeout: ${thread.name} ${thread.state}")
                        stack.take(16).forEach { Log.e("WorkoutLibraryUi", it.toString()) }
                    }
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
            compose.onNodeWithText("Longest hang").assertIsDisplayed()
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

    private fun hangRecord(id: String, time: Long) = WorkoutSetRecord(id, ExerciseCounter.Exercise.pullUp,
        0.0, 8.0, "manual", emptyList(), "authored", 1, trackedAtEpochMilliseconds = time,
        activity = WorkoutActivity(emptyList(), listOf(WorkoutPhaseTotal(WorkoutMotionPhase.hang, 8.0)),
            8.0, 0.0, 8.0, 8.0, false, 2))
}
