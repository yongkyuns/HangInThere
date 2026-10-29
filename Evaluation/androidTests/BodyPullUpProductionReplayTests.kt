package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BodyPullUpProductionReplayTests {
    @Test fun productionMotionMatchesFrozenCandidateOnEveryOriginalFrame() {
        val path=System.getenv("PULLUP_MOTION_INPUT") ?: return
        val output=System.getenv("PULLUP_PRODUCTION_PARITY_REPORT") ?: return
        val json=WorkoutRecordCodec.json
        val old=mutableMapOf<String,PullUpCycleMotionExperiment>()
        val current=mutableMapOf<String,PullUpCycleMotion>()
        val frames=mutableMapOf<String,Int>();val cycles=mutableMapOf<String,Int>()
        File(path).useLines { lines -> lines.filter { it.isNotBlank() }.forEach { line ->
            val row=json.parseToJsonElement(line).jsonObject
            val id=row.getValue("delivery").jsonPrimitive.content
            val pose=json.decodeFromJsonElement<PoseResult>(row.getValue("pose"))
            val key=row["activeSetKey"]?.jsonPrimitive?.contentOrNull
            val camera=row.getValue("cameraReady").jsonPrimitive.boolean
            val edge=row["reference"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<BarSegment>(it) }
            val a=old.getOrPut(id){PullUpCycleMotionExperiment()}.observe(pose,key,camera,edge)
            val b=current.getOrPut(id){PullUpCycleMotion()}.observe(pose,key,camera,edge)
            val at="$id at ${pose.timestamp}"
            assertEquals(at,a.phase.name,b.phase.name);assertEquals(at,a.cycles,b.cycles)
            assertEquals(at,a.reason,b.reason);assertEquals(at,a.travel,b.travel);assertEquals(at,a.drift,b.drift)
            assertEquals(at,a.degrees,b.degrees)
            assertEquals(at,json.encodeToJsonElement(a.event),json.encodeToJsonElement(b.event))
            frames[id]=(frames[id] ?: 0)+1;cycles[id]=b.cycles
        } }
        File(output).writeText(buildJsonObject {
            put("scope","Original recorded source frames, cached Mac bodies, actual v46 camera/set masks; mechanism parity to exposed candidate, not iOS/live qualification.")
            put("method",PullUpCycleMotion.countMethod);put("policyVersion",PullUpCycleMotion.policyVersion)
            put("allFramesIdentical",true)
            put("deliveries",buildJsonObject { frames.forEach { (id,n) -> put(id,buildJsonObject { put("frames",n);put("cycles",cycles.getValue(id)) }) } })
        }.toString())
    }
}
