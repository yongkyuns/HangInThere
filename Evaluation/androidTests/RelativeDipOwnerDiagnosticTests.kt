package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

/** Attributes failures after the actual native baseline finishes. Authored
 * camera readiness is explicit and supplies no native/camera qualification.
 * The production selector/owner receive every admitted original source frame,
 * not labels, reference counts, mount windows or authored per-set resets. */
class RelativeDipOwnerDiagnosticTests {
    @Test fun fullOriginalSourcesThroughTheProductionOwnerWithAuthoredReadyCamera() {
        val input = System.getenv("RELATIVE_DIP_OWNER_MANIFEST") ?: return
        val output = File(checkNotNull(System.getenv("RELATIVE_DIP_OWNER_OUTPUT")))
        output.mkdirs()
        val json = WorkoutRecordCodec.json
        val manifest = json.parseToJsonElement(File(input).readText()).jsonObject
        for (entry in manifest.getValue("sources").jsonArray) {
            val case = entry.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            val stride = case["stride"]?.jsonPrimitive?.int ?: 1
            require(id.isNotBlank() && '/' !in id && ".." !in id && stride in 1..3)
            val selection = BodyAthleteSelection()
            var number = 0
            fun nextOwner() = RelativeDipWorkout("diagnostic-${number++}")
            var owner = nextOwner()
            val records = mutableListOf<WorkoutSetRecord>()
            var frames = 0
            var seen = 0
            var end: Double? = null
            File(output, "$id-rows.jsonl").bufferedWriter().use { writer ->
                File(case.getValue("poses").jsonPrimitive.content).useLines { lines ->
                    lines.filter { it.isNotBlank() }.forEach { line ->
                        if (seen++ % stride != 0) return@forEach
                        val raw = json.decodeFromString<PoseResult>(line)
                        val decision = selection.observe(raw)
                        if (owner.isFinished) owner = nextOwner()
                        val snapshot = owner.observe(selection, DipCycleMotion.Camera.ready)
                        if (owner.isFinished && owner.hasStarted) records += owner.record()
                        end = raw.timestamp.seconds
                        val row = buildJsonObject {
                            put("seconds", raw.timestamp.seconds)
                            put("sourceValue", raw.timestamp.value); put("sourceTimescale", raw.timestamp.timescale)
                            put("phase", if (owner.isFinished) "finished" else if (owner.hasStarted) "running" else "idle")
                            put("snapshot", json.encodeToJsonElement(snapshot))
                            put("association", json.encodeToJsonElement(decision))
                            if (owner.hasStarted) put("record", json.encodeToJsonElement(owner.record(includeActive = true)))
                        }
                        writer.write(row.toString()); writer.newLine(); frames++
                    }
                }
            }
            val report = buildJsonObject {
                put("id", id); put("sourceFrames", seen); put("admittedFrames", frames)
                put("sourceEnd", end?.let(::JsonPrimitive) ?: JsonNull)
                put("records", json.encodeToJsonElement(records))
                if (owner.hasStarted && !owner.isFinished)
                    put("activeAtSourceEnd", json.encodeToJsonElement(owner.record(includeActive = true)))
                put("scope", "Production selector and shared dip owner, explicit dip and authored ready camera. Causal diagnostic only; no native acquisition, camera, physical or independent accuracy credit. No forced EOF closure.")
            }
            File(output, "$id.json").writeText(report.toString())
            println("Common dip owner diagnostic $id: $seen original/$frames admitted frames, ${records.size} closed records")
        }
    }
}
