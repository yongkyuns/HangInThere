package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

/** Already-qualified, immutable v30 decisions. No crops, scores or clocks are
 * changed. These are source-identity episodes, not fresh phone qualification. */
class GripSetMetadataV31Tests {
    @Test fun exportSetMetadataProjection() {
        val input=System.getenv("GRIP_SET_V31_INPUT") ?: return
        val output=File(checkNotNull(System.getenv("GRIP_SET_V31_OUTPUT")))
        check(!output.exists())
        val json=WorkoutRecordCodec.json
        val results=json.parseToJsonElement(File(input).readText()).jsonArray.map { entry ->
            val item=entry.jsonObject
            val original=json.decodeFromJsonElement<WorkoutSetRecord>(item.getValue("record"))
            val owner=WorkoutSetGripAnalysis(original.id)
            item.getValue("decisions").jsonArray.forEach { value ->
                val decision=value.jsonObject
                owner.observeConfirmed(GripOrientation.valueOf(decision.getValue("label").jsonPrimitive.content),
                    decision.getValue("seconds").jsonPrimitive.double,
                    "frozen-v30-surface-neutral", "torchscript-offline")
            }
            val annotated=original.copy(setGrip=owner.current)
            check(original.reps==annotated.reps)
            val report=WorkoutGripReview.report(annotated)
            check(report.reps.zip(original.reps).all { (variation,rep) ->
                variation.measured==rep.grip && variation.width==(rep.grip?.width ?: GripWidth.unknown)
            })
            buildJsonObject {
                put("caseId",item.getValue("caseId"));put("sourceEpoch",item.getValue("sourceEpoch"))
                put("record",json.encodeToJsonElement(annotated));put("report",json.encodeToJsonElement(report))
            }
        }
        output.writeText(JsonArray(results).toString()+"\n")
    }
}
