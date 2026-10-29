package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

class GripPatchDatasetTests {
    @Test fun exportSourceBoundSubjectPlans() {
        val input=System.getenv("GRIP_PATCH_INPUT") ?: return
        val output=checkNotNull(System.getenv("GRIP_PATCH_OUTPUT"))
        val json=WorkoutRecordCodec.json
        val version=System.getenv("GRIP_PATCH_POLICY")?.toInt() ?: 1
        require(version in 1..2)
        var source: String?=null;var selector:GripPatchSubject?=null;var revised:GripPatchSubjectV2?=null
        File(output).bufferedWriter().use { writer -> File(input).forEachLine { line ->
            val row=json.parseToJsonElement(line).jsonObject
            val id=row.getValue("recordingId").jsonPrimitive.content
            if (source!=id) {source=id;selector=GripPatchSubject(id);revised=GripPatchSubjectV2(id)}
            val pose=json.decodeFromJsonElement<PoseResult>(row.getValue("pose"))
            val plan=if(version==2)revised!!.observe(pose) else selector!!.observe(pose)
            val result=buildJsonObject { row.forEach { (k,v)->put(k,v) };put("patchPlan",json.encodeToJsonElement(plan)) }
            writer.write(result.toString());writer.newLine()
        } }
    }
}
