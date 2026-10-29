package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class RelativeDipProductionParityTests {
    @Test fun productionSelectionAndMotionMatchFrozenDevelopmentObservations() {
        val input = System.getenv("RELATIVE_DIP_MANIFEST") ?: return
        val expectedRoot = File(checkNotNull(System.getenv("RELATIVE_DIP_EXPECTED")))
        val output = File(checkNotNull(System.getenv("RELATIVE_DIP_PARITY")))
        val json = WorkoutRecordCodec.json
        val cases = json.parseToJsonElement(File(input).readText()).jsonObject.getValue("sources").jsonArray
        val scores = json.parseToJsonElement(File(expectedRoot, "candidate-comparison.json").readText())
            .jsonObject.getValue("cases").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
        var total = 0
        val summaries = mutableListOf<JsonObject>()
        for(entry in cases) {
            val case = entry.jsonObject; val id = case.getValue("id").jsonPrimitive.content
            val stride = case.getValue("stride").jsonPrimitive.int
            val expectedFile = File(expectedRoot, "reports/$id.jsonl")
            val hash = MessageDigest.getInstance("SHA-256").digest(expectedFile.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(scores.getValue(id).jsonObject.getValue("reportSHA256").jsonPrimitive.content, hash)
            val expected = expectedFile.readLines().iterator()
            val selection = BodyAthleteSelection(); val motion = DipCycleMotion()
            var generation: Long? = null; var seen = 0; var admitted = 0; var cycles = 0
            File(case.getValue("poses").jsonPrimitive.content).useLines { lines -> lines.forEach { line ->
                if(seen++ % stride != 0) return@forEach
                val raw = json.decodeFromString<PoseResult>(line)
                val decision = selection.observe(raw)
                if(generation == null || generation != decision.sourceGeneration) motion.interrupt("athleteAssociationEpoch")
                generation = decision.sourceGeneration
                if(decision.status == BodyAthleteAssociation.Status.lost) motion.interrupt("athleteAssociationLost")
                val actual = motion.observe(selection.frame!!.pose, true,
                    athleteContinuous = decision.status == BodyAthleteAssociation.Status.associated)
                val frozen = json.parseToJsonElement(expected.next()).jsonObject
                assertEquals("$id frame$admitted", frozen.getValue("cycles").jsonPrimitive.int, actual.cycles)
                assertEquals(frozen.getValue("phase").jsonPrimitive.content, actual.phase.name)
                assertEquals(frozen.getValue("reason").jsonPrimitive.content, actual.reason)
                assertEquals(frozen["event"], json.encodeToJsonElement(DipCycleMotion.Snapshot.serializer(), actual).jsonObject["event"])
                assertEquals(frozen.getValue("association").jsonObject["candidateIndex"], decision.candidateIndex?.let(::JsonPrimitive))
                cycles = actual.cycles; admitted++
            } }
            assertFalse(expected.hasNext()); total += admitted
            summaries += buildJsonObject { put("id", id); put("frames", admitted); put("cycles", cycles) }
        }
        output.parentFile.mkdirs()
        output.writeText(buildJsonObject { put("cases", JsonArray(summaries)); put("framesCompared", total)
            put("source", "Frozen v52 exposed mechanics; no native lifecycle/physical qualification") }.toString())
    }
}
