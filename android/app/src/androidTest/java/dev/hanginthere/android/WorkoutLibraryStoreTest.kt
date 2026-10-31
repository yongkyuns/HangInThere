package dev.hanginthere.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.session.WorkoutRecordStore
import dev.hanginthere.android.session.WorkoutGripCorrectionStore
import dev.hanginthere.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class) class WorkoutLibraryStoreTest {
    private fun record(id: String = UUID.randomUUID().toString()) = WorkoutSetRecord(id, ExerciseCounter.Exercise.pullUp,
        0.0, 8.0, "manual", emptyList(), "authored", 1, trackedAtEpochMilliseconds = 100000,
        activity = WorkoutActivity(emptyList(), listOf(WorkoutPhaseTotal(WorkoutMotionPhase.hang, 8.0)), 8.0, 0.0, 8.0, 8.0, false, 2))
    @Test fun cameraAndEnteredSetsShareOneWorkoutWithoutDuplicatingMeasurements() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "mixed-log-${UUID.randomUUID()}")
        try {
            val store = WorkoutRecordStore(context, root); val original = record(); store.save(original)
            val contextValue = WorkoutContext(equipment = "gym", effort = 6)
            store.libraryCommand(WorkoutLibraryCommand("review", setId = original.id,
                correction = WorkoutReviewCorrection(context = contextValue), atEpochMilliseconds = 200000))
            val log = WorkoutLog("mixed", 100000, listOf(WorkoutLoggedSet("curl", "EZ curls", 7, 75.9)), 100000)
            store.linkCameraSet(original, log, 10.0, "lb")
            val reloaded = WorkoutRecordStore(context, root).libraryState()
            val correction = WorkoutCorrections.current(reloaded.reviews, original.id)
            assertEquals("mixed", correction.workoutId); assertEquals(6, correction.context.effort)
            assertEquals("gym", correction.context.equipment); assertEquals(4.5359237, correction.context.addedLoadKg!!, .0000001)
            val workout = WorkoutJourney.workout(reloaded, "mixed", System.currentTimeMillis())!!
            assertEquals(2, workout.setCount); assertEquals(7, workout.totalReps); assertEquals(0, workout.completedReps)
            assertEquals(1, workout.sets.size); assertEquals(8.0, workout.sets.single().holdSeconds!!, 0.0)
        } finally { root.deleteRecursively() }
    }
    @Test fun batchDeletionErasesEverySourceAndSurvivesReloadAndRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "library-test-${UUID.randomUUID()}")
        try {
            val store = WorkoutRecordStore(context, root); val originals = listOf(record(), record())
            originals.forEach { store.save(it); store.enqueue(it, true) }
            val backup = store.libraryExport()
            store.libraryCommand(WorkoutLibraryCommand("deleteSources", setIds = originals.map { it.id }, atEpochMilliseconds = 200000))
            store.flushPending()
            val reload = WorkoutRecordStore(context, root); reload.libraryRestore(backup)
            originals.forEach { assertNull(reload.libraryRecord(it.id)); assertFalse(File(root, "${it.id}.json").exists()) }
            assertEquals(2, reload.libraryState().deletions.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun reviewDeletionAndBackupPreserveImmutableEvidenceAcrossReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "library-test-${UUID.randomUUID()}")
        try {
            val store = WorkoutRecordStore(context, root); val original = record()
            store.save(original)
            val measured = File(root, "${original.id}.json").readBytes()
            store.libraryCommand(WorkoutLibraryCommand("review", setId = original.id, correction = WorkoutReviewCorrection(exercise = ExerciseCounter.Exercise.dip), atEpochMilliseconds = 200000))
            assertArrayEquals(measured, File(root, "${original.id}.json").readBytes())
            val reload = WorkoutRecordStore(context, root)
            assertEquals(ExerciseCounter.Exercise.dip, WorkoutLibrary.page(reload.libraryState()).sets.single().exercise)
            reload.libraryCommand(WorkoutLibraryCommand("undo", setId = original.id, atEpochMilliseconds = 200001))
            assertEquals(ExerciseCounter.Exercise.pullUp, WorkoutLibrary.page(reload.libraryState()).sets.single().exercise)
            val backup = reload.libraryExport()
            reload.enqueue(original, true)
            reload.libraryCommand(WorkoutLibraryCommand("delete", setId = original.id, atEpochMilliseconds = 200002))
            reload.flushPending()
            val deleted = WorkoutRecordStore(context, root)
            deleted.save(original); deleted.libraryRestore(backup)
            assertNull(deleted.libraryRecord(original.id)); assertTrue(deleted.libraryState().entries.isEmpty())
            assertFalse(File(root, "${original.id}.json").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun restoresExpiredCurveSummaryAndRejectsImmutableConflictBeforePublishing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "library-test-${UUID.randomUUID()}")
        try {
            val source = WorkoutRecordStore(context, File(root, "source")); val original = record()
            source.save(original); val backup = source.libraryExport()
            val target = WorkoutRecordStore(context, File(root, "target"))
            target.libraryRestore(backup); val before = target.libraryState(); target.libraryRestore(backup)
            assertEquals(before, target.libraryState()); assertEquals(8.0, target.libraryRecord(original.id)?.activity?.longestHangSeconds)
            val conflict = backup.replace("\"longestHangSeconds\":8.0", "\"longestHangSeconds\":9.0")
            assertNotEquals(backup, conflict)
            try { target.libraryRestore(conflict); fail("Conflicting immutable evidence must be rejected") } catch (_: IllegalArgumentException) { }
            assertEquals(before, target.libraryState())
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptIndexRecoversWithoutErasingDeletionHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "library-test-${UUID.randomUUID()}")
        try {
            val store = WorkoutRecordStore(context, root); val original = record(); store.save(original)
            store.libraryCommand(WorkoutLibraryCommand("delete", setId = original.id, atEpochMilliseconds = 200000))
            val index = File(root, "Library/index.json"); index.writeText("corrupt")
            val recovered = WorkoutRecordStore(context, root)
            assertTrue(recovered.libraryState().deletions.any { it.setId == original.id }); assertNotEquals("corrupt", index.readText())
            recovered.save(original); assertNull(recovered.libraryRecord(original.id))
            index.writeText("corrupt"); File(root, "Library/state-journal.json").writeText("corrupt")
            try { WorkoutRecordStore(context, root).libraryState(); fail("Loss of both recovery copies must be surfaced") } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
    @Test fun coldIndexDoesNotDecodeOldArchivesAndGripPhaseBackupsSurviveRestore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "library-test-${UUID.randomUUID()}")
        try {
            val sourceRoot = File(root, "source"); val store = WorkoutRecordStore(context, sourceRoot)
            val original = record().let { it.copy(activity = it.activity!!.copy(phases = listOf(WorkoutPhaseSpan("hold", WorkoutMotionPhase.hang, 0.0, 8.0, 8.0)))) }
            store.save(original)
            val grip = WorkoutGripCorrectionStore(context, File(sourceRoot, "GripCorrections"))
            grip.correct(original, null, GripOrientation.underhand, GripWidth.narrow)
            val backup = store.libraryExport(); val targetRoot = File(root, "target"); val target = WorkoutRecordStore(context, targetRoot)
            target.libraryRestore(backup)
            assertEquals(original.activity!!.phases, target.libraryRecord(original.id)?.activity?.phases)
            assertEquals(GripOrientation.underhand, WorkoutGripCorrectionStore.archiveLedger(File(targetRoot, "GripCorrections"), original.id)?.corrections?.single()?.orientation)
            File(sourceRoot, "${original.id}.json").writeText("corrupt archival summary"); assertTrue(File(sourceRoot, "${original.id}.motion.json").delete())
            assertTrue(WorkoutRecordStore(context, sourceRoot).libraryState().entries.any { it.measured.id == original.id })
            try { store.libraryRecord(original.id); fail("Direct detail corruption must be surfaced") } catch (_: IllegalArgumentException) { }
            target.libraryCommand(WorkoutLibraryCommand("delete", setId = original.id, atEpochMilliseconds = 200000))
            assertNull(WorkoutGripCorrectionStore.archiveLedger(File(targetRoot, "GripCorrections"), original.id))
        } finally { root.deleteRecursively() }
    }
    @Test fun coldReloadExpiresMediaAndRecoversInterruptedDrafts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "library-test-${UUID.randomUUID()}")
        try {
            val store = WorkoutRecordStore(context, root); val original = record(); store.save(original)
            store.libraryCommand(WorkoutLibraryCommand("mediaPolicy", mediaPolicy = WorkoutMediaPolicy(true, 2, 100, 1), atEpochMilliseconds = 200000))
            store.libraryCommand(WorkoutLibraryCommand("attachMedia", media = WorkoutLocalMedia("clip", original.id, 3, 200000), atEpochMilliseconds = 200000))
            val media = File(root, "Library/Media"); assertTrue(media.isDirectory || media.mkdirs()); File(media, "clip.mov").writeBytes(byteArrayOf(1,2,3))
            val draft = record().copy(endReason = "running")
            File(root, "${draft.id}.pending.json").writeText(WorkoutRecordCodec.json.encodeToString(WorkoutSetRecord.serializer(), draft))
            val recovered = WorkoutRecordStore(context, root); assertTrue(recovered.libraryState().media.isEmpty())
            assertEquals("recoveredInterruption", recovered.libraryRecord(draft.id)?.endReason)
            assertFalse(File(media, "clip.mov").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun publishedTombstoneRetriesPhysicalCleanupAfterTermination() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "library-test-${UUID.randomUUID()}")
        try {
            val store = WorkoutRecordStore(context, root); val original = record(); store.save(original)
            val gripRoot = File(root, "GripCorrections")
            WorkoutGripCorrectionStore(context, gripRoot).correct(original, null, GripOrientation.underhand, null)
            val ledgerFile = gripRoot.listFiles()!!.single()
            File(gripRoot, ledgerFile.name + ".pending").writeText("Unpublished legacy annotation")
            val state = store.libraryState()
            val markers = File(root, "Library/Deletion"); assertTrue(markers.isDirectory || markers.mkdirs())
            File(markers, "${original.id}.json").writeText(WorkoutRecordCodec.json.encodeToString(kotlinx.serialization.serializer<String>(), original.id))
            val deleted = WorkoutLibrary.apply(state, WorkoutLibraryCommand("delete", setId = original.id, atEpochMilliseconds = 200000))
            File(root, "Library/state-journal.json").writeText(WorkoutRecordCodec.json.encodeToString(WorkoutLibraryState.serializer(), deleted))
            File(root, "Library/index.json").writeText(WorkoutRecordCodec.json.encodeToString(WorkoutLibraryState.serializer(), deleted))
            assertTrue(File(root, "${original.id}.json").exists())
            WorkoutRecordStore(context, root).libraryState()
            assertFalse(File(root, "${original.id}.json").exists()); assertFalse(File(root, "${original.id}.motion.json").exists())
            assertNull(WorkoutGripCorrectionStore.archiveLedger(gripRoot, original.id))
            assertTrue(gripRoot.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun orphanPublicationAndInterruptedRestoreRecoverAfterReload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "library-test-${UUID.randomUUID()}")
        try {
            val store = WorkoutRecordStore(context, root); store.save(record())
            val orphan = record()
            val publications = File(root, "Library/Publication"); assertTrue(publications.isDirectory || publications.mkdirs())
            File(publications, "${orphan.id}.json").writeText(WorkoutRecordCodec.json.encodeToString(kotlinx.serialization.serializer<String>(), orphan.id))
            File(root, "${orphan.id}.json").writeText(WorkoutRecordCodec.json.encodeToString(WorkoutSetRecord.serializer(), orphan))
            val recovered = WorkoutRecordStore(context, root)
            assertTrue(recovered.libraryState().entries.any { it.measured.id == orphan.id })
            val backup = recovered.libraryExport()
            File(root, "Library/restore-pending.json").writeText(backup)
            WorkoutRecordStore(context, root).libraryState()
            assertFalse(File(root, "Library/restore-pending.json").exists())
        } finally { root.deleteRecursively() }
    }
}
