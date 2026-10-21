package dev.hanginthere.android.session

import android.content.Context
import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.security.MessageDigest

/** Independent atomic annotation files; never scanned as measured workout JSON. */
class WorkoutGripCorrectionStore(context: Context, private val directory: File = File(context.filesDir,"workout-grip-corrections")) {
    private fun file(id:String):File = archiveFile(directory, id)
    private fun ledger(set:WorkoutSetRecord):GripCorrectionLedger {
        return archiveLedger(directory, set.id) ?: GripCorrectionLedger(set.id)
    }
    fun report(set:WorkoutSetRecord,useCorrections:Boolean=true):GripVariationReport=WorkoutGripReview.report(set,if(useCorrections)ledger(set) else null)
    fun correct(set:WorkoutSetRecord,repId:String?,orientation:GripOrientation?,width:GripWidth?) = synchronized(writeLock) {
        val updated=WorkoutGripReview.correct(set,ledger(set),repId,orientation,width,System.currentTimeMillis())
        check(directory.exists() || directory.mkdirs()) { "Cannot create grip correction directory" }
        val destination=file(set.id);val temporary=File(directory,destination.name+".pending")
        temporary.outputStream().use { stream -> stream.write(WorkoutRecordCodec.json.encodeToString(updated).toByteArray());stream.flush();stream.fd.sync() }
        check(temporary.renameTo(destination)) { "Cannot save grip correction" }
    }
    companion object {
        private val writeLock=Any()
        private fun archiveFile(directory: File, id: String): File {
            val key=MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
            return File(directory,"$key.json")
        }
        fun archiveLedger(directory: File, id: String): GripCorrectionLedger? = synchronized(writeLock) {
            val file = archiveFile(directory, id)
            if (!file.exists() && !File(file.path + ".bak").exists()) null else {
                val text = android.util.AtomicFile(file).openRead().use { input ->
                    check(file.length() <= 512 * 1024)
                    input.bufferedReader().use { it.readText() }
                }
                WorkoutRecordCodec.json.decodeFromString(text)
            }
        }
        fun restoreLedger(directory: File, record: WorkoutSetRecord, incoming: GripCorrectionLedger, publish: Boolean) = synchronized(writeLock) {
            val merged = WorkoutGripReview.mergeLedgers(record, archiveLedger(directory, record.id) ?: GripCorrectionLedger(record.id), incoming)
            if (publish) {
                check(directory.isDirectory || directory.mkdirs())
                val destination = archiveFile(directory, record.id); val atomic = android.util.AtomicFile(destination); val output = atomic.startWrite()
                try { output.write(WorkoutRecordCodec.json.encodeToString(merged).toByteArray()); atomic.finishWrite(output) }
                catch (failure: Throwable) { atomic.failWrite(output); throw failure }
            }
        }
        fun deleteLedger(directory: File, id: String) = synchronized(writeLock) {
            val file = archiveFile(directory, id)
            android.util.AtomicFile(file).delete()
            val legacyPending = File(directory, file.name + ".pending")
            if (legacyPending.exists()) check(legacyPending.delete())
            check(!file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
        }
    }
}
