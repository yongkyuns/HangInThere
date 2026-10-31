package dev.hanginthere.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File
import kotlin.test.*

/** Same native observations/camera verdicts through the Android lifecycle on JVM.
 * Separates common-rule/Apple-FFI parity from native model accuracy. */
class MotionNativeReplayTests {
    @Test fun sourceBoundFullVideoControllerComparison() {
        val input = System.getenv("MOTION_PARITY_MANIFEST") ?: return
        val destination = System.getenv("MOTION_PARITY_OUTPUT") ?: return
        val output = File(destination).apply { mkdirs() }
        val cases = WorkoutRecordCodec.json.parseToJsonElement(File(input).readText()).jsonObject.getValue("cases").jsonArray
        val summaries = mutableListOf<JsonObject>()
        for (value in cases) {
            val spec = value.jsonObject; val id = spec.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            var serial = 0
            val runtime = MotionWorkoutRuntime(WorkoutExerciseSelection.automatic, newSetId = { "$id-${serial++}" })
            val source = BodyAthleteSelection()
            val records = linkedMapOf<String,WorkoutSetRecord>();var generation: Int? = null;var frames = 0
            val trace=File(spec.getValue("trace").jsonPrimitive.content)
            val pinned=spec["traceSHA256"]?.jsonPrimitive?.content
            if(pinned!=null) assertEquals(pinned,java.security.MessageDigest.getInstance("SHA-256").digest(trace.readBytes()).joinToString("") { "%02x".format(it) })
            trace.forEachLine { line ->
                val row = WorkoutRecordCodec.json.parseToJsonElement(line).jsonObject
                val pose = WorkoutRecordCodec.json.decodeFromJsonElement<PoseResult>(row.getValue("pose"))
                val epoch = row.getValue("cameraGeneration").jsonPrimitive.int
                if (generation != epoch) { runtime.resetPreparation(); generation = epoch }
                source.observe(pose)
                runtime.observe(source,OverheadPullUpMotion.Camera.valueOf(row.getValue("camera").jsonPrimitive.content))
                runtime.latestCompletedSet?.let { records[it.id] = it }; frames++
            }
            runtime.finish("endOfInput");runtime.latestCompletedSet?.let { records[it.id] = it }
            spec["expected"]?.jsonPrimitive?.int?.let { assertEquals(it,records.values.sumOf { r -> r.completedCount },id) }
            File(output,"$id-records.json").writeText(WorkoutRecordCodec.json.encodeToString(records.values.toList()))
            File(output,"$id-analysis.json").writeText(buildJsonArray {
                records.values.forEach { record -> add(buildJsonObject {
                    put("demand",WorkoutRecordCodec.json.encodeToJsonElement(WorkoutSetPresentation.demand(record.activity)))
                    put("tempo",WorkoutRecordCodec.json.encodeToJsonElement(WorkoutSetPresentation.tempo(record)))
                    put("keyframes",WorkoutRecordCodec.json.encodeToJsonElement(WorkoutSetPresentation.keyframes(record,record.startedAt ?: 0.0)))
                }) }
            }.toString())
            summaries += buildJsonObject { put("id",id);put("frames",frames);put("completed",records.values.sumOf { it.completedCount });put("sets",records.size) }
        }
        File(output,"results.json").writeText(JsonArray(summaries).toString())
        assertTrue(summaries.all { it.getValue("frames").jsonPrimitive.int > 0 })
    }
}
