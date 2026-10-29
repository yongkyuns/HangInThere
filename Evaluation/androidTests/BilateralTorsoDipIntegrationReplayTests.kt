package dev.hanginthere.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exposed causal integration diagnostic, not native acquisition or qualification.
 * Inputs are original SDK poses and independently recorded camera verdicts;
 * labels and expected counts never enter either movement or set owner. */
class BilateralTorsoDipIntegrationReplayTests {
    @Test fun originalFramesMatchPrototypeAndPersistThroughActualSetOwner() {
        val input = System.getenv("TORSO_DIP_INPUT") ?: return
        val output = File(checkNotNull(System.getenv("TORSO_DIP_OUTPUT")))
        check(!output.exists()); output.mkdirs()
        val json = WorkoutRecordCodec.json
        val summaries = mutableListOf<JsonObject>()
        val config = Json.parseToJsonElement(File(input).readText()).jsonObject
        config.getValue("cases").jsonArray.forEach { item ->
            val data = item.jsonObject
            val id = data.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val selection = BodyAthleteSelection()
            val prototype = PartialArmDipMotionExperiment()
            val production = BilateralTorsoDipMotion()
            var number = 0
            fun nextOwner() = RelativeDipWorkout("$id-${number++}")
            var owner = nextOwner()
            val records = mutableListOf<WorkoutSetRecord>()
            var frames = 0; var completed = 0; var prefixes = 0; var componentCycles = 0
            File(output, "$id.jsonl").bufferedWriter().use { writer ->
                File(data.getValue("input").jsonPrimitive.content).forEachLine { line ->
                    val row = Json.parseToJsonElement(line).jsonObject
                    val pose = json.decodeFromString<PoseResult>(row.getValue("pose").toString())
                    selection.observe(pose)
                    val camera = row.getValue("cameraReady").jsonPrimitive.boolean
                    val expected = prototype.observe(selection, camera)
                    val actual = production.observe(selection, camera)
                    componentCycles = actual.cycles
                    val context = "$id at ${pose.timestamp}"
                    assertEquals(context, expected.cycles, actual.cycles)
                    assertEquals(context, expected.phase.name, actual.phase.name)
                    assertEquals(context, expected.reason, actual.reason)
                    assertEquals(context, json.encodeToJsonElement(expected.event), json.encodeToJsonElement(actual.event))
                    if (owner.isFinished) owner = nextOwner()
                    val state = owner.observe(selection, if (camera) DipCycleMotion.Camera.ready else DipCycleMotion.Camera.unavailable)
                    if (owner.hasStarted) {
                        val record = owner.record(true)
                        assertEquals(context, state.motion.cycles, record.completedCount)
                        assertEquals(BilateralTorsoDipMotion.countMethod, record.countMethod)
                        assertEquals(BilateralTorsoDipMotion.policyVersion, record.countPolicyVersion)
                        assertEquals(RelativeDipWorkout.boundaryPolicyVersion, record.boundaryPolicyVersion)
                        prefixes++
                    }
                    if (owner.isFinished && owner.hasStarted) records += owner.record()
                    if (state.motion.event?.outcome == "imageCycle") completed++
                    writer.appendLine(buildJsonObject {
                        put("seconds", pose.timestamp.seconds)
                        put("sourceValue", pose.timestamp.value); put("sourceTimescale", pose.timestamp.timescale)
                        put("candidate", json.encodeToJsonElement(actual))
                        put("owner", json.encodeToJsonElement(state))
                        if (owner.hasStarted) put("activeSetId", owner.record().id)
                    }.toString())
                    frames++
                }
            }
            val bridge = WorkoutLibraryBridge()
            val saved = bridge.registerJson(bridge.emptyJson(), json.encodeToString(records))
            val restored = bridge.restoreJson(bridge.emptyJson(), bridge.exportJson(saved, null, null))
            val library = json.decodeFromString<WorkoutLibraryState>(restored)
            assertEquals(records.size, library.entries.size)
            assertEquals(records.sumOf { it.completedCount }, library.entries.sumOf { it.measured.completedCount })
            val summary = buildJsonObject {
                put("id", id); put("frames", frames); put("componentCycles", componentCycles)
                put("ownerCompletedEvents", completed); put("consistentCountPrefixes", prefixes)
                put("records", json.encodeToJsonElement(records))
                if (owner.hasStarted && !owner.isFinished) put("activeAtSourceEnd", json.encodeToJsonElement(owner.record(true)))
                put("restoredRecords", library.entries.size)
                put("restoredCompletedCycles", library.entries.sumOf { it.measured.completedCount })
            }
            File(output, "$id-summary.json").writeText(summary.toString())
            summaries += summary
        }
        File(output, "summary.json").writeText(JsonArray(summaries).toString())
    }
}
