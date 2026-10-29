package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

/** Diagnostic component replay. View eligibility comes from the frozen source
 * screen; it is not a synthetic body frame or an automatic-view claim. */
class GripWidthMetadataV20Tests {
    @Test fun exportAuthorizedWidthReplay() {
        val input=System.getenv("GRIP_WIDTH_INPUT") ?: return
        val context=checkNotNull(System.getenv("GRIP_WIDTH_CONTEXT"))
        val setup=checkNotNull(System.getenv("GRIP_WIDTH_SETUP"))
        val output=checkNotNull(System.getenv("GRIP_WIDTH_OUTPUT"))
        val json=WorkoutRecordCodec.json
        val cases=json.parseToJsonElement(File(setup).readText()).jsonObject.getValue("cases").jsonArray
            .map { it.jsonObject }.associateBy { it.getValue("recordingId").jsonPrimitive.content }
        val frames=File(context).readLines().map { json.parseToJsonElement(it).jsonObject }
        var identity:Pair<String,Int>?=null
        var width=WorkoutGripWidth()
        var index=0
        File(output).bufferedWriter().use { writer ->
            File(input).forEachLine { line ->
                val row=json.parseToJsonElement(line).jsonObject
                val frame=frames[index++]
                check(row["recordingId"]==frame["recordingId"] && row["imageSHA256"]==frame["imageSHA256"])
                val id=row.getValue("recordingId").jsonPrimitive.content
                val key=id to frame.getValue("sourceEpoch").jsonPrimitive.int
                if(identity!=key) { identity=key; width=WorkoutGripWidth() }
                val pose=json.decodeFromJsonElement<PoseResult>(row.getValue("pose"))
                val reference=frame["reference"]?.takeIf { it!=JsonNull }?.let { json.decodeFromJsonElement<BarSegment>(it) }
                val usable=frame["trackingIssue"]==JsonNull && !frame.getValue("released").jsonPrimitive.boolean && row["trackId"]!=JsonNull
                val source=cases.getValue(id)
                val view=source["confirmedView"]?.jsonPrimitive?.contentOrNull
                val supported=source.getValue("supported").jsonPrimitive.boolean && view in listOf("front","rear")
                val result=width.observe(pose,reference,ExerciseCounter.Phase.valueOf(frame.getValue("phase").jsonPrimitive.content),usable,supported)
                writer.write(buildJsonObject {
                    put("recordingId",id);put("seconds",pose.timestamp.seconds);put("imageSHA256",row.getValue("imageSHA256"))
                    put("sourceEpoch",key.second);put("width",result.width.name)
                    put("projectedSpanRatio",result.projectedSpanRatio);put("reason",result.reason)
                    put("widthPolicyVersion",WorkoutGripWidth.policyVersion)
                }.toString());writer.newLine()
            }
        }
        check(index==frames.size)
    }
}
