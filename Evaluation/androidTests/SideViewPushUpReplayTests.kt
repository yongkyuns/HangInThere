package dev.hanginthere.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Full-source native pose observations through the common association/counter.
 * Camera.ready is ASSUMED here to isolate rules; this cannot qualify native
 * camera evidence, model throughput or a device installation. No label enters observe(). */
class SideViewPushUpReplayTests {
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    private val codec = WorkoutRecordCodec.json

    @Test fun completeVideoReplayAndTwoSetReleaseRegression() {
        val manifestPath = System.getenv("PUSHUP_REPLAY_MANIFEST") ?: return
        val destination = File(checkNotNull(System.getenv("PUSHUP_REPLAY_OUTPUT"))).apply { mkdirs() }
        val manifest = codec.parseToJsonElement(File(manifestPath).readText()).jsonObject
        val summaries = mutableListOf<JsonObject>()
        for (item in manifest.getValue("cases").jsonArray) {
            val spec = item.jsonObject
            val id = spec.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val trace = File(spec.getValue("trace").jsonPrimitive.content)
            assertEquals(spec.getValue("traceSHA256").jsonPrimitive.content, hash(trace))
            val reference = File(spec.getValue("reference").jsonPrimitive.content)
            assertEquals(spec.getValue("referenceSHA256").jsonPrimitive.content, hash(reference))
            val label = codec.parseToJsonElement(reference.readText()).jsonObject.getValue("clips").jsonArray
                .single { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject
            for (engine in listOf("visionPose", "mediaPipePose")) {
                val motion = SideViewPushUpMotion()
                val workout = SideViewPushUpWorkout("replay-$id-$engine")
                val records = mutableListOf<WorkoutSetRecord>()
                val journals = mutableMapOf<String, WorkoutSetRecord>()
                fun drain() { while (workout.nextWrite() != null) {
                    val write = checkNotNull(workout.nextWrite())
                    if (write.finalized) records += write.record
                    else journals[write.record.id] = WorkoutRecordCodec.mergeDraft(journals[write.record.id], write.record)
                    workout.acknowledgeWrite()
                } }
                val events = mutableListOf<SideViewPushUpMotion.Rep>()
                val sets = linkedMapOf<Int, SideViewPushUpMotion.SetSummary>()
                val cues = mutableMapOf<String, Int>()
                val observed = mutableListOf<PoseResult>()
                var frames = 0
                File(destination, "$id-$engine-frames.jsonl").bufferedWriter().use { output ->
                    trace.forEachLine { line ->
                        val row = codec.parseToJsonElement(line).jsonObject
                        val pose = codec.decodeFromJsonElement<PoseResult>(row.getValue(engine))
                        observed += pose
                        val state = motion.observe(pose, SideViewPushUpMotion.Camera.ready)
                        workout.observe(pose, SideViewPushUpMotion.Camera.ready); drain()
                        state.rep?.let(events::add)
                        state.endedSet?.let { sets[it.index] = it }
                        cues[state.cue.name] = (cues[state.cue.name] ?: 0)+1
                        output.appendLine(codec.encodeToString(state)); frames++
                    }
                }
                motion.finish()?.let { sets[it.index] = it }
                workout.finish("endOfInput"); drain()
                WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(), records))
                assertEquals(events.map { it.returnedAt }, records.flatMap { it.completedReps }.map { it.returnedAt })
                assertEquals(events.map { it.startedAt }, records.flatMap { it.completedReps }.map { it.startedAt })
                assertEquals(events.map { it.deepestAt }, records.flatMap { it.completedReps }.map { it.extremeAt })
                assertEquals(events.size, journals.values.sumOf { WorkoutRecordCodec.recover(it).completedCount })
                File(destination, "$id-$engine-workouts.json").writeText(codec.encodeToString(records))
                File(destination, "$id-$engine-sets.json").writeText(codec.encodeToString(sets.values.toList()))
                val windows = label.getValue("returnWindows").jsonArray.map { v -> v.jsonArray.map { it.jsonPrimitive.double } }
                val matched = windows.map { (a, b) -> events.count { it.returnedAt in a..b } }
                val zeroWindows = label.getValue("zeroCountWindows").jsonArray.map { it.jsonObject }
                val zeroCounts = zeroWindows.map { w -> events.count { it.returnedAt in
                    w.getValue("start").jsonPrimitive.double..w.getValue("end").jsonPrimitive.double } }
                summaries += buildJsonObject {
                    put("id", id); put("engine", engine); put("frames", frames)
                    put("scope", "Common source association and counting; independent native camera qualification assumed, not tested")
                    put("reps", codec.encodeToJsonElement(events)); put("sets", codec.encodeToJsonElement(sets.values.toList()))
                    put("referenceMatches", codec.encodeToJsonElement(matched)); put("zeroWindowCounts", codec.encodeToJsonElement(zeroCounts))
                    put("cues", codec.encodeToJsonElement(cues))
                }
                // Complete recordings repeated with shifted source ticks are a
                // regression, NOT independent videos/subjects or holdout evidence.
                if (id == "side") {
                    val repeated = SideViewPushUpMotion()
                    val repeatedSets = linkedMapOf<Int, SideViewPushUpMotion.SetSummary>()
                    val offset = observed.last().timestamp.value + 1200
                    repeat(2) { copy -> observed.forEach { pose ->
                        val shifted = pose.copy(timestamp = pose.timestamp.copy(value = pose.timestamp.value+copy*offset))
                        repeated.observe(shifted, SideViewPushUpMotion.Camera.ready)
                        repeated.latestCompletedSet?.let { repeatedSets[it.index] = it }
                    } }
                    repeated.finish()?.let { repeatedSets[it.index] = it }
                    File(destination, "$id-$engine-repeated-sets.json").writeText(codec.encodeToString(repeatedSets.values.toList()))
                    // Persist all evidence before assertions below.
                    summaries[summaries.lastIndex] = JsonObject(summaries.last()+
                        ("repeatedSetCounts" to codec.encodeToJsonElement(repeatedSets.values.map { it.reps.size })))
                }
            }
        }
        File(destination, "results.json").writeText(JsonArray(summaries).toString())
        for (row in summaries) {
            val id = row.getValue("id").jsonPrimitive.content
            assertTrue(row.getValue("zeroWindowCounts").jsonArray.all { it.jsonPrimitive.int == 0 }, row.toString())
            if (id == "side") {
                assertEquals(5, row.getValue("reps").jsonArray.size, row.toString())
                assertEquals(List(5) { JsonPrimitive(1) }, row.getValue("referenceMatches").jsonArray.toList())
                assertEquals(listOf(5, 5), row.getValue("repeatedSetCounts").jsonArray.map { it.jsonPrimitive.int })
            } else assertTrue(row.getValue("reps").jsonArray.isEmpty(), "Front view must not count")
        }
    }
}
