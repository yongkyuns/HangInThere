package dev.hanginthere.android.session

import android.content.Context
import android.util.AtomicFile
import dev.hanginthere.core.WorkoutAnalysis
import dev.hanginthere.core.WorkoutSetRecord
import dev.hanginthere.core.WorkoutRecordCodec
import dev.hanginthere.core.WorkoutWriteQueue
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/** The serial analysis owner performs local persistence; no video is retained. */
class WorkoutRecordStore(context: Context, private val directory: File = File(context.filesDir, "workouts")) {
    companion object {
        private var liveStore: WorkoutRecordStore? = null
        @Synchronized fun live(context: Context): WorkoutRecordStore = liveStore ?: WorkoutRecordStore(context).also { liveStore = it }
    }
    private val writes = WorkoutWriteQueue()
    @Synchronized fun enqueue(record: WorkoutSetRecord, finalized: Boolean) = writes.enqueue(record, finalized)
    @Synchronized fun flushPending() {
        while (true) {
            val write = writes.next() ?: return
            if (write.finalized) save(write.record) else saveDraft(write.record)
            writes.acknowledge(write.token)
        }
    }
    @Synchronized fun save(record: WorkoutSetRecord) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create workout storage" }
        require(record.id.matches(Regex("[A-Za-z0-9-]{1,64}")))
        val summary = WorkoutRecordCodec.summary(record)
        val file = File(directory, "${record.id}.json")
        if (restoreAtomic(file)) {
            check(WorkoutAnalysis.json.decodeFromString<WorkoutSetRecord>(readAtomic(file)) == summary) { "Conflicting workout record" }
        } else {
            writeAtomic(File(directory, "${record.id}.motion.json"), WorkoutAnalysis.json.encodeToString(record))
            writeAtomic(file, WorkoutAnalysis.json.encodeToString(summary))
        }
        val pending=File(directory,"${record.id}.pending.json")
        AtomicFile(pending).delete()
        check(!pending.exists() && !File(pending.path+".bak").exists() && !File(pending.path+".new").exists()) { "Cannot clean workout journal" }
        var bytes = 0L
        committedFiles().filter { it.name.endsWith(".motion.json") }.sortedByDescending { it.lastModified() }
            .forEachIndexed { index, motion ->
                bytes += motion.length()
                if (index >= WorkoutRecordCodec.maximumFeatureSets || bytes > WorkoutRecordCodec.maximumFeatureBytes)
                    check(motion.delete()) { "Cannot expire motion features" }
            }
    }
    @Synchronized fun saveDraft(delta: WorkoutSetRecord) {
        require(delta.id.matches(Regex("[A-Za-z0-9-]{1,64}")))
        check(directory.isDirectory || directory.mkdirs())
        if(restoreAtomic(File(directory,"${delta.id}.json"))) return
        val file=File(directory,"${delta.id}.pending.json")
        val old=if(restoreAtomic(file)) WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(readAtomic(file)) else null
        writeAtomic(file,WorkoutRecordCodec.json.encodeToString(WorkoutRecordCodec.mergeDraft(old,delta)))
    }
    private fun readAtomic(file: File): String = AtomicFile(file).openRead().bufferedReader().use{it.readText()}
    private fun restoreAtomic(file: File): Boolean {
        if(File(file.path+".bak").exists()) AtomicFile(file).openRead().use { }
        return file.exists()
    }
    private fun committedFiles(): List<File> = directory.listFiles().orEmpty()
        .map { if(it.name.endsWith(".bak")) File(directory,it.name.removeSuffix(".bak")) else it }
        .distinctBy { it.name }.filter { it.extension=="json" && restoreAtomic(it) }
    private fun writeAtomic(file: File, text: String) {
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write(text.toByteArray()); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
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
}
