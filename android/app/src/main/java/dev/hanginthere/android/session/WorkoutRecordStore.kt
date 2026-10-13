package dev.hanginthere.android.session

import android.content.Context
import android.util.AtomicFile
import dev.hanginthere.core.WorkoutAnalysis
import dev.hanginthere.core.WorkoutSetRecord
import dev.hanginthere.core.WorkoutRecordCodec
import dev.hanginthere.core.WorkoutWriteQueue
import dev.hanginthere.core.WorkoutProgressEvidence
import dev.hanginthere.core.WorkoutProgression
import dev.hanginthere.core.*
import android.net.Uri
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** The serial analysis owner performs local persistence; no video is retained. */
class WorkoutRecordStore(private val context: Context, private val directory: File = File(context.filesDir, "workouts")) {
    companion object {
        private var liveStore: WorkoutRecordStore? = null
        @Synchronized fun live(context: Context): WorkoutRecordStore = liveStore ?: WorkoutRecordStore(context).also { liveStore = it }
    }
    private val writes = WorkoutWriteQueue()
    private val activeJournalIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var libraryCache: WorkoutLibraryState? = null
    private var deletedIds: Set<String> = emptySet()
    private var restoringLibrary = false
    private val gripDirectory = if (directory == File(context.filesDir, "workouts")) File(context.filesDir, "workout-grip-corrections") else File(directory, "GripCorrections")
    @Synchronized fun enqueue(record: WorkoutSetRecord, finalized: Boolean) {
        if (record.id !in deletedIds) writes.enqueue(record, finalized)
    }
    @Synchronized fun flushPending() {
        while (true) {
            val write = writes.next() ?: return
            if (write.finalized) save(write.record) else saveDraft(write.record)
            writes.acknowledge(write.token)
        }
    }
    @Synchronized fun save(record: WorkoutSetRecord) {
        if (deleted(record.id)) { writes.discard(record.id); return }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create workout storage" }
        require(record.id.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val summary = WorkoutRecordCodec.summary(record)
        val file = File(directory, "${record.id}.json")
        check(publicationsDirectory.isDirectory || publicationsDirectory.mkdirs())
        val publication = File(publicationsDirectory, "${record.id}.json")
        writeAtomic(publication, WorkoutRecordCodec.json.encodeToString(record.id))
        if (restoreAtomic(file)) {
            check(WorkoutAnalysis.json.decodeFromString<WorkoutSetRecord>(readAtomic(file)) == summary) { "Conflicting workout record" }
        } else {
            writeAtomic(File(directory, "${record.id}.motion.json"), WorkoutAnalysis.json.encodeToString(record))
            writeAtomic(file, WorkoutAnalysis.json.encodeToString(summary))
        }
        deleteAtomic(File(directory,"${record.id}.pending.json"))
        activeJournalIds.remove(record.id)
        val old = libraryState()
        val next = WorkoutLibrary.register(old, listOf(record))
        if (old != next) publishLibrary(next)
        deleteAtomic(publication)
        var bytes = 0L
        committedFiles().filter { it.name.endsWith(".motion.json") }.sortedByDescending { it.lastModified() }
            .forEachIndexed { index, motion ->
                bytes += motion.length()
                if (index >= WorkoutRecordCodec.maximumFeatureSets || bytes > WorkoutRecordCodec.maximumFeatureBytes)
                    deleteAtomic(motion)
            }
    }
    @Synchronized fun saveDraft(delta: WorkoutSetRecord) {
        if (deleted(delta.id)) { writes.discard(delta.id); return }
        require(delta.id.matches(Regex("[A-Za-z0-9-]{1,64}")))
        check(directory.isDirectory || directory.mkdirs())
        if(restoreAtomic(File(directory,"${delta.id}.json"))) return
        activeJournalIds.add(delta.id)
        val file=File(directory,"${delta.id}.pending.json")
        val old=if(restoreAtomic(file)) WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(readAtomic(file)) else null
        writeAtomic(file,WorkoutRecordCodec.json.encodeToString(WorkoutRecordCodec.mergeDraft(old,delta)))
    }
    private fun readAtomic(file: File): String = AtomicFile(file).openRead().bufferedReader().use{it.readText()}
    private fun restoreAtomic(file: File): Boolean {
        if(File(file.path+".bak").exists() || (file.exists() && File(file.path+".new").exists())) AtomicFile(file).openRead().use { }
        return file.exists()
    }
    private fun deleteAtomic(file: File) {
        AtomicFile(file).delete()
        check(!file.exists() && !File(file.path+".bak").exists() && !File(file.path+".new").exists()) { "Cannot clean workout storage" }
    }
    private fun committedFiles(): List<File> = directory.listFiles().orEmpty()
        .map { if(it.name.endsWith(".bak")) File(directory,it.name.removeSuffix(".bak")) else it }
        .distinctBy { it.name }.filter { it.extension=="json" &&
            (it.isFile || File(it.path + ".bak").isFile) && restoreAtomic(it) }
    private fun writeAtomic(file: File, text: String) {
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(text.toByteArray()); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    /** Read only immutable published summaries; no inference lock or motion
     * sidecars. UI loads on Dispatchers.IO, keeping camera delivery independent. */
    @Synchronized fun progressEvidence(excludingSetId: String? = null): List<WorkoutProgressEvidence> = directory.listFiles().orEmpty()
        .filter { file ->
            if(file.extension!="json" || file.name.endsWith(".motion.json")) false
            else if(file.name.endsWith(".pending.json")) {
                val id=file.name.removeSuffix(".pending.json")
                id!=excludingSetId && !activeJournalIds.contains(id) && !File(directory,"$id.json").exists()
            } else true
        }.map { file ->
            val record=WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(file.readText())
            WorkoutProgression.evidence(if(file.name.endsWith(".pending.json"))WorkoutRecordCodec.recover(record) else record)
        }
    @Synchronized fun recent(): List<WorkoutSetRecord> = committedFiles()
        .filter { it.extension == "json" && !it.name.endsWith(".motion.json") &&
            !(it.name.endsWith(".pending.json") && File(directory,it.name.removeSuffix(".pending.json")+".json").exists()) }
        .sortedByDescending { it.lastModified() }.take(50)
        .map { summary ->
            if(summary.name.endsWith(".pending.json")) return@map WorkoutRecordCodec.recover(WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(readAtomic(summary)))
            val motion = File(directory, "${summary.nameWithoutExtension}.motion.json")
            WorkoutAnalysis.json.decodeFromString(if (motion.exists()) readAtomic(motion) else readAtomic(summary))
        }

    private val libraryDirectory get() = File(directory, "Library")
    private val libraryFile get() = File(libraryDirectory, "index.json")
    private val libraryJournal get() = File(libraryDirectory, "state-journal.json")
    private val mediaDirectory get() = File(libraryDirectory, "Media")
    private val publicationsDirectory get() = File(libraryDirectory, "Publication")
    private val deletionDirectory get() = File(libraryDirectory, "Deletion")
    private fun markDeletion(id: String) {
        check(deletionDirectory.isDirectory || deletionDirectory.mkdirs())
        writeAtomic(File(deletionDirectory, "$id.json"), WorkoutRecordCodec.json.encodeToString(id))
    }
    private fun cleanupDeletions() {
        val markers = deletionDirectory.listFiles().orEmpty().map { if (it.name.endsWith(".bak")) File(deletionDirectory, it.name.removeSuffix(".bak")) else it }
            .distinctBy { it.name }.filter { it.extension == "json" && restoreAtomic(it) }
        markers.forEach { marker ->
            val id = WorkoutRecordCodec.json.decodeFromString<String>(readAtomic(marker))
            require(WorkoutCorrections.safeId(id) && marker.name == "$id.json")
            if (id in deletedIds) {
                writes.discard(id); activeJournalIds.remove(id)
                WorkoutGripCorrectionStore.deleteLedger(gripDirectory, id)
                listOf(".json", ".motion.json", ".pending.json").forEach { deleteAtomic(File(directory, id + it)) }
            }
            deleteAtomic(marker)
        }
    }
    @Synchronized fun libraryState(): WorkoutLibraryState {
        libraryCache?.let { state ->
            cleanupDeletions()
            val pending = File(libraryDirectory, "restore-pending.json")
            if (!restoringLibrary && restoreAtomic(pending)) libraryRestore(readAtomic(pending))
            return requireNotNull(libraryCache)
        }
        val hasJournal = restoreAtomic(libraryJournal); val hasIndex = restoreAtomic(libraryFile)
        val restored = if (hasJournal) WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(readAtomic(libraryJournal))
            else if (hasIndex) WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(readAtomic(libraryFile)) else WorkoutLibraryState()
        WorkoutLibrary.validate(restored)
        deletedIds = restored.deletions.map { it.setId }.toSet()
        cleanupDeletions()
        val publications = publicationsDirectory.listFiles().orEmpty().map { if (it.name.endsWith(".bak")) File(publicationsDirectory, it.name.removeSuffix(".bak")) else it }
            .distinctBy { it.name }.filter { it.extension == "json" && restoreAtomic(it) }
        val pendingSummaries = publications.mapNotNull { marker ->
            val id = WorkoutRecordCodec.json.decodeFromString<String>(readAtomic(marker))
            require(WorkoutCorrections.safeId(id) && marker.name == "$id.json")
            File(directory, "$id.json").takeIf { restoreAtomic(it) }
        }
        // Initial migration scans old summaries once. Normal cold loads touch
        // only the compact index and interrupted publication markers.
        val files = if (hasJournal || hasIndex) pendingSummaries else committedFiles()
        val summaries = files.filter { !it.name.endsWith(".motion.json") && !it.name.endsWith(".pending.json") }
            .map { WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(readAtomic(it)) }
        val recoveredDrafts = committedFiles().filter { it.name.endsWith(".pending.json") &&
            it.name.removeSuffix(".pending.json") !in activeJournalIds && !File(directory, it.name.removeSuffix(".pending.json") + ".json").exists() }
            .map { WorkoutRecordCodec.recover(WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(readAtomic(it))) }
        val state = WorkoutLibrary.maintain(WorkoutLibrary.register(restored, summaries + recoveredDrafts), System.currentTimeMillis())
        publishLibrary(state); cleanupDeletions(); pruneLibraryMedia(state)
        publications.forEach(::deleteAtomic)
        val pending = File(libraryDirectory, "restore-pending.json")
        if (!restoringLibrary && restoreAtomic(pending)) libraryRestore(readAtomic(pending))
        return requireNotNull(libraryCache)
    }
    private fun publishLibrary(state: WorkoutLibraryState) {
        check(libraryDirectory.isDirectory || libraryDirectory.mkdirs())
        writeAtomic(libraryJournal, WorkoutRecordCodec.json.encodeToString(state))
        libraryCache = state
        deletedIds = state.deletions.map { it.setId }.toSet()
        writeAtomic(libraryFile, WorkoutRecordCodec.json.encodeToString(state))
    }
    private fun deleted(id: String): Boolean { if (libraryCache == null) libraryState(); return id in deletedIds }
    @Synchronized fun libraryCommand(command: WorkoutLibraryCommand): WorkoutLibraryState {
        val next = WorkoutLibrary.apply(libraryState(), command)
        if (command.action == "delete") markDeletion(requireNotNull(command.setId))
        if (command.action == "deleteSources") command.setIds.forEach(::markDeletion)
        publishLibrary(next)
        cleanupDeletions()
        pruneLibraryMedia(next)
        return next
    }
    @Synchronized fun libraryRecord(id: String): WorkoutSetRecord? {
        require(WorkoutCorrections.safeId(id))
        if (deleted(id)) return null
        val summary = File(directory, "$id.json"); val motion = File(directory, "$id.motion.json")
        val pending = File(directory, "$id.pending.json")
        return if (restoreAtomic(summary)) WorkoutRecordCodec.json.decodeFromString(readAtomic(if (restoreAtomic(motion)) motion else summary))
            else if (id !in activeJournalIds && restoreAtomic(pending)) WorkoutRecordCodec.recover(WorkoutRecordCodec.json.decodeFromString(readAtomic(pending)))
            else libraryState().entries.firstOrNull { it.measured.id == id }?.measured
    }
    @Synchronized fun libraryForReview(): WorkoutLibraryState {
        val previous = libraryState(); val next = WorkoutLibrary.maintain(previous, System.currentTimeMillis())
        if (next != previous) publishLibrary(next)
        pruneLibraryMedia(next)
        return next
    }
    @Synchronized fun libraryExport(): String {
        val state = libraryState()
        val summaries = state.entries.map { requireNotNull(libraryRecord(it.measured.id)).let(WorkoutRecordCodec::summary) }
        val ledgers = summaries.mapNotNull { WorkoutGripCorrectionStore.archiveLedger(gripDirectory, it.id) }
        return WorkoutLibraryBackup.export(state, summaries, ledgers)
    }
    @Synchronized fun libraryRestore(backup: String): WorkoutLibraryState {
        val wasRestoring = restoringLibrary; restoringLibrary = true
        try {
        val next = WorkoutLibraryBackup.restore(libraryState(), backup)
        val envelope = WorkoutLibraryBackup.decode(backup); val summaries = envelope.measuredSummaries
        envelope.gripCorrections.forEach { ledger -> WorkoutGripCorrectionStore.restoreLedger(gripDirectory, summaries.single { it.id == ledger.setId }, ledger, false) }
        summaries.forEach { record -> val file = File(directory, "${record.id}.json")
            if (restoreAtomic(file)) require(WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(readAtomic(file)) == record) { "Immutable archival summary conflict" }
        }
        val pending = File(libraryDirectory, "restore-pending.json")
        writeAtomic(pending, backup)
        next.deletions.filter { it.setId !in deletedIds }.forEach { markDeletion(it.setId) }
        publishLibrary(next)
        cleanupDeletions()
        summaries.filter { record -> next.deletions.none { it.setId == record.id } }.forEach { record ->
            val file = File(directory, "${record.id}.json")
            if (!restoreAtomic(file)) writeAtomic(file, WorkoutRecordCodec.json.encodeToString(record))
        }
        envelope.gripCorrections.filter { ledger -> next.deletions.none { it.setId == ledger.setId } }.forEach { ledger ->
            WorkoutGripCorrectionStore.restoreLedger(gripDirectory, summaries.single { it.id == ledger.setId }, ledger, true)
        }
        pruneLibraryMedia(next); deleteAtomic(pending)
        return next
        } finally { restoringLibrary = wasRestoring }
    }
    private fun pruneLibraryMedia(state: WorkoutLibraryState) {
        val retained = state.media.map { it.id }.toSet()
        mediaDirectory.listFiles().orEmpty().filter { it.nameWithoutExtension !in retained }.forEach { check(it.delete()) }
    }
    @Synchronized fun attachLibraryMedia(source: Uri, setId: String): String {
        val state = libraryState()
        require(state.mediaPolicy.enabled && state.entries.any { it.measured.id == setId })
        require(context.contentResolver.getType(source)?.startsWith("video/") == true) { "Select a video recording" }
        check(mediaDirectory.isDirectory || mediaDirectory.mkdirs())
        val id = java.util.UUID.randomUUID().toString(); val target = File(mediaDirectory, "$id.mov")
        try {
            var bytes = 0L
            requireNotNull(context.contentResolver.openInputStream(source)).use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    bytes += count
                    require(bytes <= state.mediaPolicy.maximumBytes) { "Video exceeds the local retention limit" }
                    output.write(buffer, 0, count)
                }
            } }
            val now = System.currentTimeMillis()
            val next = WorkoutLibrary.apply(state, WorkoutLibraryCommand("attachMedia", media = WorkoutLocalMedia(id, setId, bytes, now), atEpochMilliseconds = now))
            require(next.media.any { it.id == id }) { "Video cannot fit the local retention limit" }
            publishLibrary(next); pruneLibraryMedia(next)
            return id
        } catch (failure: Throwable) { target.delete(); throw failure }
    }
    @Synchronized fun libraryMedia(id: String): File? {
        require(WorkoutCorrections.safeId(id))
        if (libraryForReview().media.none { it.id == id }) return null
        return File(mediaDirectory, "$id.mov").takeIf { it.isFile }
    }
}
