package dev.hanginthere.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Optional full-source regression. Traces are pinned before inference; labels
 * are scored afterwards. Camera readiness is assumed, not device-qualified. */
class PushUpCoverageReplayTests {
    @Test fun nativeTracesThroughAutomaticFrontAndSideOwner() {
        val path = System.getenv("PUSHUP_COVERAGE_MANIFEST") ?: return
        val codec = WorkoutRecordCodec.json
        val specs = codec.parseToJsonElement(File(path).readText()).jsonObject.getValue("cases").jsonArray
        val reports = specs.map { item ->
            val spec = item.jsonObject
            val file = File(spec.getValue("trace").jsonPrimitive.content)
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(spec.getValue("sha256").jsonPrimitive.content, hash)
            val workout = SideViewPushUpWorkout(spec.getValue("id").jsonPrimitive.content, allowFrontView = true)
            val records = mutableListOf<WorkoutSetRecord>()
            val cues = mutableMapOf<String, Int>()
            fun drain() { while (workout.nextWrite() != null) {
                val write = workout.nextWrite()!!
                if (write.finalized) records += write.record
                workout.acknowledgeWrite()
            } }
            file.forEachLine { line ->
                val state = workout.observe(codec.decodeFromString<PoseResult>(line), SideViewPushUpMotion.Camera.ready)
                cues[state.cue.name] = (cues[state.cue.name] ?: 0)+1
                drain()
            }
            workout.finish("endOfInput"); drain()
            WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(), records))
            buildJsonObject {
                put("id", spec.getValue("id")); put("cues", codec.encodeToJsonElement(cues))
                put("records", codec.encodeToJsonElement(records))
            }
        }
        File(checkNotNull(System.getenv("PUSHUP_COVERAGE_OUTPUT"))).writeText(codec.encodeToString(reports))
        specs.zip(reports).forEach { (item, report) ->
            val spec = item.jsonObject
            val records = codec.decodeFromJsonElement<List<WorkoutSetRecord>>(report.getValue("records"))
            val reps = records.flatMap { it.completedReps }
            val windows = spec.getValue("returnWindows").jsonArray.map { w -> w.jsonArray.map { it.jsonPrimitive.double } }
            assertEquals(windows.size, reps.size, report.toString())
            windows.forEach { (a,b) -> assertEquals(1, reps.count { it.returnedAt!! in a..b }, report.toString()) }
            if (windows.isNotEmpty()) assertTrue(records.all { it.countMethod == spec.getValue("method").jsonPrimitive.content }, report.toString())
        }
    }
}
