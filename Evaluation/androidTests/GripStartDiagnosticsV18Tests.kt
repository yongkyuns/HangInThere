package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals

/** Read-only diagnostics beside the unchanged counter replay. An unacquired
 * support reference is not positive evidence that the athlete released. */
class GripStartDiagnosticsV18Tests {
    private fun reason(tracked: Boolean, arms: PullUpArmMeasurement, reference: Boolean,
                       hadReference: Boolean, phase: String, eligible: Boolean): String = when {
        !tracked -> "athleteTrackingUnavailable"
        arms.arms.isEmpty() -> "armsUnavailable"
        !reference && hadReference -> "supportReferenceLost"
        !reference && !arms.isExtended -> "startingHangNotObserved"
        !reference -> "supportReferenceNotAcquired"
        eligible -> "counterExerciseActive"
        phase == "seekingStart" -> "holdStartingHang"
        else -> "waitingForExerciseMotion"
    }

    @Test fun absentReferenceDoesNotMeanRelease() {
        val pose = PoseResult(PresentationTime(0, 1), ImageSize(100.0, 100.0), emptyList(), "test", 1)
        assertEquals("athleteTrackingUnavailable", reason(false, PullUpArmMeasurement(pose), false, false, "seekingStart", false))
        assertEquals("armsUnavailable", reason(true, PullUpArmMeasurement(pose), false, false, "seekingStart", false))
    }

    @Test fun startingPostureAndLostSupportHaveDifferentActions() {
        fun arms(degrees: Double): PullUpArmMeasurement {
            val angle = degrees * Math.PI / 180
            val joints = listOf(
                Landmark(PoseJoint.leftShoulder, Point2D(50.0, 70.0), 1.0),
                Landmark(PoseJoint.leftElbow, Point2D(50.0, 40.0), 1.0),
                Landmark(PoseJoint.leftWrist, Point2D(50.0+30*sin(angle), 40.0+30*cos(angle)), 1.0))
            return PullUpArmMeasurement(PoseResult(PresentationTime(0, 1), ImageSize(100.0, 100.0), listOf(PoseObservation(joints)), "test", 1))
        }
        val bent = arms(120.0)
        val extended = arms(170.0)
        assertEquals("startingHangNotObserved", reason(true, bent, false, false, "seekingStart", false))
        assertEquals("supportReferenceLost", reason(true, bent, false, true, "seekingStart", false))
        assertEquals("supportReferenceNotAcquired", reason(true, extended, false, false, "seekingStart", false))
        assertEquals("holdStartingHang", reason(true, extended, true, false, "seekingStart", false))
        assertEquals("waitingForExerciseMotion", reason(true, extended, true, true, "ready", false))
        assertEquals("counterExerciseActive", reason(true, bent, true, true, "outbound", true))
    }

    @Test fun exportAuthorizedDiagnostic() {
        val input = System.getenv("GRIP_START_DIAGNOSTIC_INPUT") ?: return
        val replay = checkNotNull(System.getenv("GRIP_START_DIAGNOSTIC_REPLAY"))
        val output = checkNotNull(System.getenv("GRIP_START_DIAGNOSTIC_OUTPUT"))
        val json = WorkoutRecordCodec.json
        val counters = File(replay).readLines().map { json.parseToJsonElement(it).jsonObject }
        var index = 0
        var identity: Pair<String, Int>? = null
        var hadReference = false
        File(output).bufferedWriter().use { writer ->
            File(input).forEachLine { line ->
                val row = json.parseToJsonElement(line).jsonObject
                val current = counters[index++]
                check(row["imageSHA256"] == current["imageSHA256"])
                val key = current.getValue("recordingId").jsonPrimitive.content to current.getValue("sourceEpoch").jsonPrimitive.int
                if (identity != key) { identity = key; hadReference = false }
                val pose = json.decodeFromJsonElement<PoseResult>(row.getValue("pose"))
                val arms = PullUpArmMeasurement(pose)
                val reference = current["reference"] != JsonNull
                val eligible = current.getValue("exercise").jsonObject.getValue("eligible").jsonPrimitive.boolean
                val diagnostic = reason(row["trackId"] != JsonNull, arms, reference, hadReference,
                    current.getValue("phase").jsonPrimitive.content, eligible)
                hadReference = hadReference || reference
                val result = buildJsonObject {
                    put("recordingId", current.getValue("recordingId")); put("seconds", pose.timestamp.seconds)
                    put("reason", diagnostic); put("extended", arms.isExtended)
                    put("requiredExtendedDegrees", ExerciseCounter.extendedDegrees)
                    put("arms", buildJsonArray {
                        arms.arms.forEach { arm -> add(buildJsonObject {
                            put("side", arm.side.name); put("degrees", arm.estimate.elbowDegrees)
                            put("minimumJointConfidence", arm.estimate.minimumJointConfidence)
                        }) }
                    })
                }
                writer.write(result.toString()); writer.newLine()
            }
        }
        check(index == counters.size)
    }
}
