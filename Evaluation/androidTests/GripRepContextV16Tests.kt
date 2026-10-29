package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Exact shared counter and torso geometry, not a Python reproduction. Opt-in
 * research replay: observed wrists seed a support reference, not an image bar
 * detector or a verified apparatus. Never reads reference grip/phase labels. */
class GripRepContextV16Tests {
    @Test fun exportAuthorizedReplay() {
        val input = System.getenv("GRIP_REP_CONTEXT_INPUT") ?: return
        val output = checkNotNull(System.getenv("GRIP_REP_CONTEXT_OUTPUT"))
        val json = WorkoutRecordCodec.json
        var counter = ExerciseCounter()
        var features = WorkoutMotionFeatures()
        var context = WorkoutGripExerciseContext()
        var reference: BarSegment? = null
        var identity: String? = null
        var sourceEpoch = 0
        File(output).bufferedWriter().use { writer ->
            File(input).forEachLine { line ->
                val row = json.parseToJsonElement(line).jsonObject
                val pose = json.decodeFromJsonElement<PoseResult>(row.getValue("pose"))
                val key = row.getValue("recordingId").jsonPrimitive.content + ":" + row["trackId"].toString() + ":" + pose.imageSize + ":" + pose.backend
                if (identity != key) {
                    counter = ExerciseCounter(); features = WorkoutMotionFeatures()
                    context = WorkoutGripExerciseContext(); reference = null
                    identity = key; sourceEpoch++
                }
                val arms = PullUpArmMeasurement(pose)
                val short = min(pose.imageSize.width, pose.imageSize.height)
                // Setup reference only; existing counter owns extension dwell,
                // departure, endpoint and return. No extra readiness detector.
                if (reference == null && arms.isExtended && arms.arms.all { it.shoulder.y-it.wrist.y > short*.08 }) {
                    val wrists = arms.arms.map { it.wrist }
                    reference = if (wrists.size == 2 && abs(wrists[0].x-wrists[1].x) > short*.04)
                        BarSegment(wrists[0], wrists[1]) else wrists.firstOrNull()?.let {
                        BarSegment(Point2D(0.0, it.y), Point2D(pose.imageSize.width, it.y))
                    }
                }
                val support = ArmMeasurement.Side.entries.mapNotNull { side ->
                    pose.people.singleOrNull()?.landmark(side.joints[2])?.position?.let { wrist ->
                        side.name to (reference?.distance(wrist)?.let { d -> d <= short*.085 } == true)
                    }
                }.toMap()
                val retained = arms.arms.isNotEmpty() && support.values.any { it }
                if (!retained) counter.interrupt("gripReferenceLost") else counter.consume(pose, reference)
                val sample = features.extract(pose, reference, retained && counter.trackingIssue == null, counter.phase)
                val state = context.observe(pose.timestamp.seconds, counter.phase, sample.usable, sample.released || !retained)
                if (!retained || sample.released) { reference = null; features.reset() }
                val observation = row["observation"]?.takeIf { it != JsonNull }?.let { json.decodeFromJsonElement<GripObservation>(it) }
                val basis = observation?.let { WorkoutGripGeometry.bodyBasis(it) }
                val facing = basis?.facing?.z
                val supported = basis?.let { WorkoutGripGeometry.supportsView(it) } == true
                val result = buildJsonObject {
                    put("recordingId", row.getValue("recordingId")); put("imageSHA256", row.getValue("imageSHA256"))
                    put("seconds", pose.timestamp.seconds); put("trackId", row.getValue("trackId"))
                    put("sourceEpoch", sourceEpoch); put("counterPolicyVersion", ExerciseCounter.policyVersion)
                    put("phase", counter.phase.name); put("trackingIssue", counter.trackingIssue)
                    put("movements", counter.observedMovements); put("released", sample.released || !retained)
                    put("reference", json.encodeToJsonElement(reference)); put("supportBySide", json.encodeToJsonElement(support))
                    put("exercise", json.encodeToJsonElement(state))
                    put("bodyView", buildJsonObject {
                        put("available", basis != null); put("supported", supported); put("facingZ", facing)
                        put("alignmentThreshold", WorkoutGripGeometry.minimumCameraFacingAlignment)
                        put("view", if (!supported) "unknown" else if (facing!! < 0) "front" else "rear")
                    })
                }
                writer.write(result.toString()); writer.newLine()
            }
        }
    }
}
