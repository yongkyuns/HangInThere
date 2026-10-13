package dev.hanginthere.android.session

import android.content.Context
import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.security.MessageDigest

/** Independent atomic annotation files; never scanned as measured workout JSON. */
class WorkoutGripCorrectionStore(context: Context, private val directory: File = File(context.filesDir,"workout-grip-corrections")) {
    private fun file(id:String):File {
        val key=MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory,"$key.json")
    }
    private fun ledger(set:WorkoutSetRecord):GripCorrectionLedger {
        val file=file(set.id)
        if(!file.exists())return GripCorrectionLedger(set.id)
        check(file.length()<=512*1024) { "Grip correction file is too large" }
        return WorkoutRecordCodec.json.decodeFromString(file.readText())
    }
    fun report(set:WorkoutSetRecord,useCorrections:Boolean=true):GripVariationReport=WorkoutGripReview.report(set,if(useCorrections)ledger(set) else null)
    fun correct(set:WorkoutSetRecord,repId:String?,orientation:GripOrientation?,width:GripWidth?) = synchronized(writeLock) {
        val updated=WorkoutGripReview.correct(set,ledger(set),repId,orientation,width,System.currentTimeMillis())
        check(directory.exists() || directory.mkdirs()) { "Cannot create grip correction directory" }
        val destination=file(set.id);val temporary=File(directory,destination.name+".pending")
        temporary.outputStream().use { stream -> stream.write(WorkoutRecordCodec.json.encodeToString(updated).toByteArray());stream.flush();stream.fd.sync() }
        check(temporary.renameTo(destination)) { "Cannot save grip correction" }
    }
    companion object { private val writeLock=Any() }
}
