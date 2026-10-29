package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class DipBodyMotionExperimentTests {
    // Authored kinematic controls, with bend and shoulder travel independently
    // variable. Real clip observations are read only through the opt-in runner.
    private fun pose(ms: Long, drop: Double = 0.0, degrees: Double = 170.0,
        wristShift: Double = 0.0, rightDrop: Double = drop, rightDegrees: Double = degrees): PoseResult {
        val points = listOf(250.0, 650.0).flatMapIndexed { i, x ->
            val joints = ArmMeasurement.Side.entries[i].joints
            val d = if (i == 0) drop else rightDrop
            val angle = if (i == 0) degrees else rightDegrees
            val height = 320.0 - d
            val offset = height / (2 * tan(angle * PI / 360))
            listOf(Landmark(joints[0], Point2D(x, 280 + d), .9),
                Landmark(joints[1], Point2D(x + if (i == 0) -offset else offset, 600 - height / 2), .9),
                Landmark(joints[2], Point2D(x + wristShift, 600.0), .9))
        }
        return PoseResult(PresentationTime(ms, 1000), ImageSize(900.0, 1400.0), listOf(PoseObservation(points)), "authored", 1)
    }
    private fun cycle(): List<PoseResult> = (0L..400L step 40).map { pose(it) } +
        (440L..680L step 40).map { pose(it, 100.0, 110.0) } +
        (720L..1000L step 40).map { pose(it) }
    private fun count(frames: List<PoseResult>) = DipBodyMotionExperiment().let { model -> frames.map(model::observe).last().cycles }

    @Test fun fullTopLowerReturnCountsOnceWithoutRailsHandsOrLegs() {
        assertEquals(1, count(cycle()))
        assertTrue(cycle().all { it.gripObservation == null && it.people[0].landmarks.size == 6 })
    }
    @Test fun noTopBeforeMountingCannotEarnARep() {
        val frames = (0L..400L step 40).map { pose(it, 100.0, 110.0) } +
            (440L..900L step 40).map { pose(it) }
        assertEquals(0, count(frames))
    }
    @Test fun holdingAndLegAdjustmentsDoNotCount() {
        assertEquals(0, count((0L..2000L step 40).map { pose(it) }))
    }
    @Test fun shallowBobsAndOneArmAdjustmentsDoNotCount() {
        assertEquals(0, count(cycle().map { f ->
            if (f.timestamp.value in 440..680) pose(f.timestamp.value, 20.0, 160.0) else f
        }))
        assertEquals(0, count(cycle().map { f ->
            if (f.timestamp.value in 440..680) pose(f.timestamp.value, 100.0, 110.0, rightDrop = 0.0, rightDegrees = 170.0) else f
        }))
    }
    @Test fun elbowBendingWithoutShoulderTravelAndTravelWithoutBendingDoNotCount() {
        assertEquals(0, count(cycle().map { f -> if (f.timestamp.value in 440..680) pose(f.timestamp.value, 0.0, 110.0) else f }))
        assertEquals(0, count(cycle().map { f -> if (f.timestamp.value in 440..680) pose(f.timestamp.value, 100.0, 170.0) else f }))
    }
    @Test fun movingHandsDuringSetupInvalidateTheBaseline() {
        assertEquals(0, count(cycle().map { f -> if (f.timestamp.value in 440..680) pose(f.timestamp.value, 100.0, 110.0, 80.0) else f }))
    }
    @Test fun dismountWithoutReturnCannotCount() {
        assertEquals(0, count(cycle().filter { it.timestamp.value <= 680 }))
    }
    @Test fun missingArmAndExtraAthleteBreakAttempts() {
        for (bad in listOf("missing", "multiple", "duplicate", "confidence")) {
            assertEquals(bad, 0, count(cycle().map { f ->
                if (f.timestamp.value !in 440..680) f else when (bad) {
                    "missing" -> f.copy(people = listOf(f.people[0].copy(landmarks = f.people[0].landmarks.dropLast(1))))
                    "multiple" -> f.copy(people = f.people + f.people)
                    "duplicate" -> f.copy(people = listOf(f.people[0].copy(landmarks = f.people[0].landmarks + f.people[0].landmarks.first())))
                    else -> f.copy(people = f.people.map { person -> person.copy(landmarks = person.landmarks.map { it.copy(confidence = .1) }) })
                }
            }))
        }
    }
    @Test fun sourceGapRegressionAndChangingGeometryCannotBridgeACycle() {
        assertEquals(0, count(cycle().filter { it.timestamp.value !in 440..680 }))
        assertEquals(0, count(cycle().map { if (it.timestamp.value == 720L) it.copy(timestamp = PresentationTime(100, 1000)) else it }))
        assertEquals(0, count(cycle().map { if (it.timestamp.value in 440..680) it.copy(imageSize = ImageSize(950.0, 1400.0)) else it }))
    }
    @Test fun endpointsNeedObservedDwell() {
        assertEquals(0, count(cycle().filter { it.timestamp.value !in 480..680 }))
        assertEquals(0, count(cycle().filter { it.timestamp.value <= 720 }))
    }
    @Test fun projectedCrossingIsOptionalAndMissingLegsAreUnknown() {
        val base = cycle()
        assertNull(DipBodyMotionExperiment.crossedLegCue(base.first()))
        for (crossed in listOf(true, false)) {
            val frames = base.map { f ->
                val extra = listOf(
                    Landmark(PoseJoint.leftHip, Point2D(400.0, 700.0), .9), Landmark(PoseJoint.rightHip, Point2D(500.0, 700.0), .9),
                    Landmark(PoseJoint.leftAnkle, Point2D(if (crossed) 550.0 else 350.0, 1100.0), .9),
                    Landmark(PoseJoint.rightAnkle, Point2D(if (crossed) 350.0 else 550.0, 1100.0), .9))
                f.copy(people = listOf(f.people[0].copy(landmarks = f.people[0].landmarks + extra)))
            }
            assertEquals(crossed, DipBodyMotionExperiment.crossedLegCue(frames.first()))
            assertEquals(1, count(frames))
        }
    }

    /** Explicit local development run; reports all frames and events without a
     * five-count assertion or access to reference windows during detection. */
    @Test fun evaluateAuthorizedLocalObservations() {
        val source = System.getenv("DIP_BODY_POSES") ?: return
        val output = checkNotNull(System.getenv("DIP_BODY_REPORT"))
        val json = WorkoutRecordCodec.json
        val poses = File(source).readLines().filter { it.isNotBlank() }.map { json.decodeFromString<PoseResult>(it) }
        val results = linkedMapOf<String, List<DipBodyMotionExperiment.Snapshot>>()
        for (variant in listOf("original", "noLowerBody")) {
            val model = DipBodyMotionExperiment()
            results[variant] = poses.map { pose -> model.observe(if (variant == "original") pose else pose.copy(
                people = pose.people.map { it.copy(landmarks = it.landmarks.filter { p ->
                    p.joint !in setOf(PoseJoint.leftHip, PoseJoint.rightHip, PoseJoint.leftKnee, PoseJoint.rightKnee, PoseJoint.leftAnkle, PoseJoint.rightAnkle)
                }) })) }
        }
        // Windows label the evaluation only. The model sees no labels, expected
        // count, time offsets, exercise quotas or special setup mode.
        System.getenv("DIP_BODY_REFERENCE")?.let { path ->
            val reference = json.parseToJsonElement(File(path).readText()).jsonObject
            for (window in reference.getValue("zeroCountWindows").jsonArray) {
                val w = window.jsonObject
                val start = w.getValue("start").jsonPrimitive.double
                val end = w.getValue("end").jsonPrimitive.double
                val model = DipBodyMotionExperiment()
                results["control_" + w.getValue("name").jsonPrimitive.content] = poses
                    .filter { it.timestamp.seconds in start..end }.map(model::observe)
            }
        }
        val report = buildJsonObject {
            put("method", DipBodyMotionExperiment.method); put("version", DipBodyMotionExperiment.version)
            put("frames", poses.size)
            put("railsRequired", false); put("legsRequired", false)
            put("scope", "Test-only development body-pose experiment; native Mac observations through JVM, no phone/app integration or form/load verification.")
            put("variants", buildJsonObject { results.forEach { (name, values) -> put(name, json.encodeToJsonElement(values)) } })
        }
        File(output).apply { parentFile.mkdirs(); writeText(Json { prettyPrint = true }.encodeToString(report)) }
        println("Body-motion experiment: " + results.mapValues { it.value.last().cycles })
    }
}
