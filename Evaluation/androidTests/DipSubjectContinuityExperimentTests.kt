package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

class DipSubjectContinuityExperimentTests {
    /** Mechanism only: same dip candidate and whole original source; association
     * chooses an original person index or supplies no body. No labels/windows/
     * expected count or target index is available to either component. */
    @Test fun evaluateOriginalSourcesWithExplicitSubjectAssociation() {
        val input=System.getenv("DIP_SUBJECT_MANIFEST") ?: return
        val output=File(checkNotNull(System.getenv("DIP_SUBJECT_OUTPUT")));output.mkdirs()
        val json=WorkoutRecordCodec.json
        val manifest=json.parseToJsonElement(File(input).readText()).jsonObject
        for(element in manifest.getValue("sources").jsonArray) {
            val case=element.jsonObject;val id=case.getValue("id").jsonPrimitive.content
            val stride=case["stride"]?.jsonPrimitive?.int ?: 1
            require(id.isNotBlank() && '/' !in id && ".." !in id && stride in 1..3)
            val motion=DipRelativeCycleMotionExperiment()
            var generation=0L;var source:ForegroundAssociation.Source?=null
            var association:BodyAthleteAssociationExperiment?=null
            var resetNext=false;var seen=0;var admitted=0
            File(output,"$id.jsonl").bufferedWriter().use { writer ->
                File(case.getValue("poses").jsonPrimitive.content).useLines { lines ->lines.filter { it.isNotBlank() }.forEach { line ->
                    if(seen++ % stride != 0) return@forEach
                    val raw=json.decodeFromString<PoseResult>(line)
                    if(source==null || resetNext || source!!.imageSize!=raw.imageSize || source!!.backend!=raw.backend || source!!.revision!=raw.requestRevision) {
                        source=ForegroundAssociation.Source(generation++,raw.imageSize,raw.backend,raw.requestRevision)
                        association=BodyAthleteAssociationExperiment(source!!)
                        motion.interrupt("athleteAssociationEpoch")
                        resetNext=false
                    }
                    val decision=association!!.observe(source!!,raw.timestamp,raw.people)
                    if(decision.status==BodyAthleteAssociationExperiment.Status.lost) {
                        motion.interrupt("athleteAssociationLost");resetNext=true
                    }
                    val selected=raw.copy(people=listOfNotNull(decision.candidateIndex?.let { raw.people[it] }))
                    val state=motion.observe(selected,cameraReady=true,
                        athleteContinuous=decision.status==BodyAthleteAssociationExperiment.Status.associated)
                    val row=buildJsonObject {
                        for((k,v) in json.encodeToJsonElement(state).jsonObject) put(k,v)
                        put("association",json.encodeToJsonElement(decision));put("rawPeople",raw.people.size)
                    }
                    writer.write(row.toString());writer.newLine();admitted++
                } }
            }
            println("Dip association mechanics $id: $seen original/$admitted admitted frames; no automatic app or physical qualification")
        }
    }
}
