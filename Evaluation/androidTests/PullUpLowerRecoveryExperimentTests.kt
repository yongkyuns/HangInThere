package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/** A stricter form hypothesis must preserve labelled movement cycles before shipping. */
class PullUpLowerRecoveryExperimentTests {
    private val edge = BarSegment(Point2D(100.0, 250.0), Point2D(800.0, 250.0))
    private fun pose(ms: Long, rise: Double = 0.0, degrees: Double = 170.0): PoseResult {
        val h = 350-rise
        val offset = h / (2*tan(degrees*PI/360))
        val points = listOf(250.0, 650.0).flatMapIndexed { i, x ->
            val joints = ArmMeasurement.Side.entries[i].joints
            listOf(Landmark(joints[0], Point2D(x, 250+h), .9),
                Landmark(joints[1], Point2D(x + if (i == 0) -offset else offset, 250+h/2), .9),
                Landmark(joints[2], Point2D(x, 250.0), .9))
        }
        return PoseResult(PresentationTime(ms, 1000), ImageSize(900.0, 1400.0),
            listOf(PoseObservation(points)), "authored", 1)
    }

    @Test fun stricterRecoveryChangesNonLockoutCycleSemanticsAndPreservesEventualFullReturn() {
        for (rise in listOf(20.0, 40.0, 60.0)) {
            val owner = BodyPullUpWorkout("non-lockout-$rise")
            val experiment = PullUpLowerRecoveryExperiment()
            val frames = (0L..1000L step 40).map { pose(it) } +
                (1040L..1360L step 40).map { pose(it, 140.0, 100.0) } +
                (1400L..2000L step 40).map { pose(it, rise, 120.0) } +
                (2040L..2400L step 40).map { pose(it, 140.0, 100.0) }
            for (frame in frames) {
                owner.observe(frame, edge, PullUpCycleMotion.Camera.ready)
                assertEquals(0, experiment.observe(frame, "experiment", true, edge).cycles)
            }
            assertTrue(owner.hasStarted)
            assertEquals(1, owner.record(true).completedCount)
            for (ms in 2440L..2800L step 40) experiment.observe(pose(ms), "experiment", true, edge)
            assertEquals(1, experiment.observe(pose(2840), "experiment", true, edge).cycles)
        }
    }

    @Test fun qualifiedNonLockoutTurnIsPreservedByTheHypothesis() {
        val experiment = PullUpLowerRecoveryExperiment()
        val frames = (0L..1000L step 40).map { pose(it) } +
            (1040L..1360L step 40).map { pose(it, 140.0, 100.0) } +
            (1400L..2000L step 40).map { pose(it, 60.0, 151.0) } +
            (2040L..2400L step 40).map { pose(it, 140.0, 100.0) }
        val events = frames.mapNotNull { experiment.observe(it, "experiment", true, edge).event }
        assertEquals("observedLowerTurn", events.single { it.outcome == "imageCycle" }.reason)
    }

    @Test fun pairedOriginalFrameComparisonWithoutChangingTheProductionOwner() {
        val input = System.getenv("PULLUP_MOTION_INPUT") ?: return
        val out = System.getenv("PULLUP_LOWER_RECOVERY_REPORT") ?: return
        val output = File(out)
        check(!output.exists()) { "Keep previous experiment results" }
        output.parentFile.mkdirs()
        val json = WorkoutRecordCodec.json
        val old = mutableMapOf<String, PullUpCycleMotion>()
        val candidate = mutableMapOf<String, PullUpLowerRecoveryExperiment>()
        var frames = 0
        output.bufferedWriter().use { writer ->
            File(input).useLines { lines -> lines.filter { it.isNotBlank() }.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                val id = row.getValue("delivery").jsonPrimitive.content
                val pose = json.decodeFromJsonElement<PoseResult>(row.getValue("pose"))
                val key = row["activeSetKey"]?.jsonPrimitive?.contentOrNull
                val ready = row.getValue("cameraReady").jsonPrimitive.boolean
                val bar = row["reference"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<BarSegment>(it) }
                val before = old.getOrPut(id) { PullUpCycleMotion() }.observe(pose, key, ready, bar)
                val after = candidate.getOrPut(id) { PullUpLowerRecoveryExperiment() }.observe(pose, key, ready, bar)
                writer.write(buildJsonObject {
                    put("delivery", id)
                    put("sourceTime", json.encodeToJsonElement(pose.timestamp))
                    put("before", json.encodeToJsonElement(before))
                    put("candidate", json.encodeToJsonElement(after))
                }.toString())
                writer.newLine(); frames++
            } }
        }
        assertEquals(34722, frames)
    }
}
