package dev.hanginthere.core

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

/** Optional pinned public observations. Labels never enter counting; camera
 * stability is assumed. This is not live native-camera qualification. */
class LyingLegRaiseReplayTests {
    @Test fun publicTracesMatchDurableCounts() {
        val path=System.getenv("LYING_LEGRAISE_REPLAY_MANIFEST") ?: return
        val target=File(checkNotNull(System.getenv("LYING_LEGRAISE_REPLAY_OUTPUT"))).apply { mkdirs() }
        val json=WorkoutRecordCodec.json
        val specs=json.parseToJsonElement(File(path).readText()).jsonObject.getValue("cases").jsonArray
        val reports=specs.map { value ->
            val spec=value.jsonObject;val id=spec.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[A-Za-z0-9_-]+")))
            val trace=File(spec.getValue("trace").jsonPrimitive.content)
            val hash=MessageDigest.getInstance("SHA-256").digest(trace.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(spec.getValue("sha256").jsonPrimitive.content,hash)
            val workout=LyingLegRaiseWorkout(id.replace('_','-'))
            val events=mutableListOf<LyingLegRaiseMotion.Rep>()
            val records=mutableListOf<WorkoutSetRecord>()
            val cues=mutableMapOf<String,Int>()
            fun drain() { while(workout.nextWrite()!=null) {
                val write=checkNotNull(workout.nextWrite())
                if(write.finalized) records+=write.record
                workout.acknowledgeWrite()
            } }
            File(target,"$id-states.jsonl").bufferedWriter().use { out ->
                trace.forEachLine { line ->
                    val pose=json.decodeFromString<PoseResult>(line)
                    val state=workout.observe(pose,SideViewPushUpMotion.Camera.ready)
                    state.rep?.let { events+=it };cues[state.title]=(cues[state.title] ?: 0)+1
                    out.appendLine(buildJsonObject { put("time",pose.timestamp.seconds);put("state",json.encodeToJsonElement(state)) }.toString())
                    drain()
                }
            }
            workout.finish("endOfInput");drain()
            WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(),records))
            assertEquals(events.map { it.returnedAt },records.flatMap { it.completedReps }.map { it.returnedAt })
            buildJsonObject { put("id",id);put("events",json.encodeToJsonElement(events));put("cues",json.encodeToJsonElement(cues));put("records",json.encodeToJsonElement(records)) }
        }
        File(target,"report.json").writeText(json.encodeToString(reports))
    }
}
