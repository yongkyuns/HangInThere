package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

/** Only substitutes torso assessment; all exact-counter replay fields remain
 * unchanged. No classifier labels or lifecycle references are accepted here. */
class GripTorsoViewV16Tests {
    @Test fun exportAuthorizedTorsoRevision() {
        val input = System.getenv("GRIP_TORSO_CONTEXT_INPUT") ?: return
        val body = checkNotNull(System.getenv("GRIP_TORSO_BODY_INPUT"))
        val output = checkNotNull(System.getenv("GRIP_TORSO_CONTEXT_OUTPUT"))
        val json = WorkoutRecordCodec.json
        val observations = File(body).readLines().associate { line ->
            val r = json.parseToJsonElement(line).jsonObject
            r.getValue("imageSHA256").jsonPrimitive.content to r["observation"]?.takeIf { it != JsonNull }?.let {
                json.decodeFromJsonElement<GripObservation>(it)
            }
        }
        File(output).bufferedWriter().use { writer -> File(input).forEachLine { line ->
            val row = json.parseToJsonElement(line).jsonObject
            val observation = observations[row.getValue("imageSHA256").jsonPrimitive.content]
            val view = observation?.let { WorkoutGripTorsoView.assess(it) } ?: WorkoutGripTorsoView.Assessment(reason="notSampled")
            val result = buildJsonObject {
                row.forEach { (k,v) -> if(k!="bodyView")put(k,v) }
                put("bodyView",json.encodeToJsonElement(view))
            }
            writer.write(result.toString());writer.newLine()
        } }
    }
}
