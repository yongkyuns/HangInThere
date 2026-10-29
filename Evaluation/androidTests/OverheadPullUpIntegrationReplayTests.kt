package dev.hanginthere.core

import dev.hanginthere.evaluation.OverheadPullUpMotionExperiment
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Development replay of the actual shared owner/storage; camera readiness is
 * explicitly authored. This does not qualify native camera or physical iPhone. */
class OverheadPullUpIntegrationReplayTests {
    private data class Prefix(val time: Double,val id: String,val count: Int)
    @Test fun originalSourceMatchesFrozenPrototypeAndPersistsWithCausalCountTimes() {
        val input = System.getenv("OVERHEAD_PULLUP_INPUT") ?: return
        val destination = System.getenv("OVERHEAD_OWNER_OUTPUT") ?: return
        val output = File(destination)
        check(!output.exists() || output.listFiles()?.isEmpty()==true)
        output.mkdirs()
        val json = WorkoutRecordCodec.json
        val cases = json.parseToJsonElement(File(input).readText()).jsonObject.getValue("cases").jsonArray
        val summaries = mutableListOf<JsonObject>()
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val bytes = ByteArray(65536)
                while(true) { val count=stream.read(bytes); if(count<0) break; digest.update(bytes,0,count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        for(value in cases) {
            val spec = value.jsonObject
            val id = spec.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val source = File(spec.getValue("poses").jsonPrimitive.content)
            val pinned = spec.getValue("poseSHA256").jsonPrimitive.content
            assertEquals(pinned,sha(source))
            val selection = BodyAthleteSelection()
            val prototype = OverheadPullUpMotionExperiment()
            val production = OverheadPullUpMotion()
            val movedAt = spec["cameraMovesAt"]?.jsonPrimitive?.double
            var ordinal = 0
            fun nextOwner() = OverheadPullUpWorkout("$id-${ordinal++}")
            var owner = nextOwner()
            val records = mutableListOf<WorkoutSetRecord>()
            val prefixes = mutableListOf<Prefix>()
            val originalTimes = mutableSetOf<Double>()
            var merged: WorkoutSetRecord? = null
            var frames = 0; var componentCycles = 0; var completedEvents = 0
            File(output,"$id.jsonl").bufferedWriter().use { writer ->
                source.forEachLine { line ->
                    val pose = json.decodeFromString<PoseResult>(line)
                    selection.observe(pose)
                    val moved = movedAt?.let { pose.timestamp.seconds >= it } == true
                    val expected = prototype.observe(selection,"component",!moved)
                    val actual = production.observe(selection,"component",!moved)
                    val expectedJson = json.encodeToJsonElement(expected).jsonObject.toMutableMap().also {
                        it["countMethod"] = JsonPrimitive(OverheadPullUpMotion.countMethod)
                        it["policyVersion"] = JsonPrimitive(OverheadPullUpMotion.policyVersion)
                        // The frozen counting prototype predates measured-range display scaling.
                        it.remove("progress")
                    }
                    val actualJson = json.encodeToJsonElement(actual).jsonObject.filterKeys { it != "progress" }
                    assertEquals("$id ${pose.timestamp}",JsonObject(expectedJson),JsonObject(actualJson))
                    componentCycles = actual.cycles
                    if(owner.isFinished) { owner=nextOwner(); merged=null }
                    val state = owner.observe(selection,if(moved)OverheadPullUpMotion.Camera.moved else OverheadPullUpMotion.Camera.ready)
                    originalTimes.add(pose.timestamp.seconds)
                    if(state.started) {
                        val record = owner.record(true)
                        assertEquals("$id ${pose.timestamp}",state.motion.cycles,record.completedCount)
                        assertEquals(OverheadPullUpMotion.countMethod,record.countMethod)
                        assertEquals(OverheadPullUpMotion.policyVersion,record.countPolicyVersion); assertEquals(OverheadPullUpWorkout.boundaryPolicyVersion,record.boundaryPolicyVersion)
                        prefixes.add(Prefix(pose.timestamp.seconds,record.id,record.completedCount))
                    }
                    owner.draftDeltaJson()?.let {
                        merged=WorkoutRecordCodec.mergeDraft(merged,json.decodeFromString(it))
                        assertEquals(owner.record().completedCount,merged!!.completedCount)
                    }
                    if(owner.isFinished && owner.hasStarted) records.add(owner.record())
                    completedEvents += state.motion.events.count { it.outcome=="imageCycle" }
                    writer.appendLine(buildJsonObject {
                        put("seconds",pose.timestamp.seconds);put("sourceTime",json.encodeToJsonElement(pose.timestamp))
                        put("owner",json.encodeToJsonElement(state));put("count",owner.record().completedCount)
                        if(owner.hasStarted) put("activeSetId",owner.record().id)
                    }.toString())
                    frames++
                }
            }
            assertEquals(spec.getValue("frames").jsonPrimitive.int,frames)
            assertEquals(pinned,sha(source))
            val active = owner.record(true).takeIf { owner.hasStarted && !owner.isFinished }
            val all = records+listOfNotNull(active)
            val indexed = all.associateBy { it.id }
            for(prefix in prefixes) assertEquals("$id at ${prefix.time}",prefix.count,
                indexed.getValue(prefix.id).completedReps.count { it.countConfirmedAt!! <= prefix.time })
            for(record in all) {
                assertTrue(record.startedAt in originalTimes); assertTrue(record.endedAt in originalTimes)
                for(rep in record.reps) {
                    assertTrue(rep.startedAt in originalTimes); assertTrue(rep.closedAt in originalTimes)
                    assertTrue(listOfNotNull(rep.extremeAt,rep.acceptedAt,rep.returnedAt,rep.countConfirmedAt).all { it in originalTimes })
                    assertTrue(WorkoutCountingMethod.validCountConfirmation(rep,record.endedAt))
                }
            }
            val saved = WorkoutLibrary.register(WorkoutLibraryState(),records)
            val restored = WorkoutLibraryBackup.restore(WorkoutLibraryState(),WorkoutLibraryBackup.export(saved))
            assertEquals(saved.entries,restored.entries)
            val summary = buildJsonObject {
                put("id",id);put("frames",frames);put("poseSHA256",pinned)
                put("componentCycles",componentCycles);put("completedEvents",completedEvents)
                put("consistentCountPrefixes",prefixes.size);put("records",json.encodeToJsonElement(records))
                active?.let { put("activeAtSourceEnd",json.encodeToJsonElement(it)) }
                put("restoredRecords",restored.entries.size)
                put("restoredCompletedCycles",restored.entries.sumOf { it.measured.completedCount })
                put("scope","Actual shared source owner and storage; authored camera readiness and cached Mac Vision poses. Development-exposed footage, no independent or physical iPhone qualification.")
            }
            File(output,"$id-summary.json").writeText(summary.toString()); summaries.add(summary)
        }
        File(output,"summary.json").writeText(JsonArray(summaries).toString())
    }
}
