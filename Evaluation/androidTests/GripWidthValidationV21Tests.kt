package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Research-only source-view input. No reference grip or width enters inference.
 * Support initialization matches v16; this is not automatic bar acquisition. */
class GripWidthValidationV21Tests {
    @Test fun exportSeparateVideoValidation() {
        val input=System.getenv("GRIP_WIDTH_V21_INPUT") ?: return
        val output=File(checkNotNull(System.getenv("GRIP_WIDTH_V21_OUTPUT")))
        val records=File(checkNotNull(System.getenv("GRIP_WIDTH_V21_RECORDS")))
        check(!output.exists() && !records.exists())
        val json=WorkoutRecordCodec.json
        var identity:String?=null; var epoch=0
        var counter=ExerciseCounter(); var features=WorkoutMotionFeatures()
        var context=WorkoutGripExerciseContext(); var width=WorkoutGripWidth()
        var analysis=WorkoutAnalysis("unset",ExerciseCounter.Exercise.pullUp)
        var reference:BarSegment?=null
        var spans=emptyList<GripWidthSpan>(); var previousTime:Double?=null
        var previousWidth=GripWidth.unknown; var latest=GripEstimate()
        val sets=mutableListOf<JsonObject>()
        fun finish() {
            if(identity==null)return
            analysis.finish("sourceEnded")
            val ledger=WorkoutGripRecord(emptyList(),spans,latest)
            sets+=buildJsonObject {
                put("identity",identity);put("sourceEpoch",epoch)
                put("reps",JsonArray(analysis.records.map { rep -> json.encodeToJsonElement(rep.copy(
                    grip=WorkoutGripAnalysis.summary(ledger,rep.startedAt,rep.closedAt))) }))
            }
        }
        output.bufferedWriter().use { writer -> File(input).forEachLine { line ->
            val row=json.parseToJsonElement(line).jsonObject
            val pose=json.decodeFromJsonElement<PoseResult>(row.getValue("pose"))
            val key=row.getValue("recordingId").jsonPrimitive.content+":"+row.getValue("shotId").jsonPrimitive.content+":"+row["trackId"]+":"+pose.imageSize
            if(identity!=key) {
                finish();epoch++;identity=key;counter=ExerciseCounter();features=WorkoutMotionFeatures()
                context=WorkoutGripExerciseContext();width=WorkoutGripWidth();reference=null
                analysis=WorkoutAnalysis("v21:$epoch",ExerciseCounter.Exercise.pullUp)
                spans=emptyList();previousTime=null;previousWidth=GripWidth.unknown;latest=GripEstimate()
            }
            val arms=PullUpArmMeasurement(pose);val short=min(pose.imageSize.width,pose.imageSize.height)
            if(reference==null && arms.isExtended && arms.arms.all { it.shoulder.y-it.wrist.y>short*.08 }) {
                val wrists=arms.arms.map { it.wrist }
                reference=if(wrists.size==2 && abs(wrists[0].x-wrists[1].x)>short*.04) BarSegment(wrists[0],wrists[1])
                    else wrists.firstOrNull()?.let { BarSegment(Point2D(0.0,it.y),Point2D(pose.imageSize.width,it.y)) }
            }
            val support=ArmMeasurement.Side.entries.mapNotNull { side ->
                pose.people.singleOrNull()?.landmark(side.joints[2])?.position?.let { wrist ->
                    side.name to (reference?.distance(wrist)?.let { it<=short*.085 }==true)
                }
            }.toMap()
            val retained=arms.arms.isNotEmpty() && support.values.any { it }
            val event=if(!retained)counter.interrupt("gripReferenceLost") else counter.consume(pose,reference)
            val sample=features.extract(pose,reference,retained && counter.trackingIssue==null,counter.phase)
            val state=context.observe(pose.timestamp.seconds,counter.phase,sample.usable,sample.released || !retained)
            val measured=width.observe(pose,reference,counter.phase,sample.usable && !sample.released,
                row.getValue("widthViewSupported").jsonPrimitive.boolean)
            val time=pose.timestamp.seconds;val dt=previousTime?.let { time-it }
            val credit=if(measured.width!=GripWidth.unknown && previousWidth==measured.width && dt!=null && dt>0 && dt<=ExerciseCounter.maximumGapSeconds)dt else 0.0
            val before=spans.lastOrNull()
            spans=if(before!=null && before.width==measured.width && before.reason==measured.reason)
                spans.dropLast(1)+before.copy(endedAt=time,projectedSpanRatio=measured.projectedSpanRatio,observedSeconds=before.observedSeconds+credit)
                else spans+GripWidthSpan("v21:$epoch:${spans.size}",time,time,measured.width,measured.projectedSpanRatio,0.0,measured.reason)
            latest=GripEstimate(width=measured.width,projectedSpanRatio=measured.projectedSpanRatio,widthPolicyVersion=WorkoutGripWidth.policyVersion,widthReason=measured.reason)
            analysis.observe(sample,counter.phase,event)
            previousTime=time;previousWidth=measured.width
            writer.write(buildJsonObject {
                put("recordingId",row.getValue("recordingId"));put("caseId",row.getValue("caseId"));put("shotId",row.getValue("shotId"))
                put("imageSHA256",row.getValue("imageSHA256"));put("seconds",time);put("trackId",row.getValue("trackId"))
                put("sourceEpoch",epoch);put("phase",counter.phase.name);put("trackingIssue",counter.trackingIssue)
                put("movements",counter.observedMovements);put("released",sample.released || !retained)
                put("reference",json.encodeToJsonElement(reference));put("supportBySide",json.encodeToJsonElement(support))
                put("exercise",json.encodeToJsonElement(state));put("width",measured.width.name)
                put("projectedSpanRatio",measured.projectedSpanRatio);put("widthReason",measured.reason)
                put("widthPolicyVersion",WorkoutGripWidth.policyVersion)
            }.toString());writer.newLine()
            if(!retained || sample.released) { reference=null;features.reset() }
        } }
        finish()
        records.writeText(JsonArray(sets).toString()+"\n")
    }
}
