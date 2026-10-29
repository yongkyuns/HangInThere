package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class PullUpCycleMotionExperimentTests {
    private fun pose(ms: Long, rise: Double = 0.0, degrees: Double = 170.0,
        wristDrop: Double = 0.0, rightRise: Double = rise, rightDegrees: Double = degrees): PoseResult {
        val points = listOf(250.0, 650.0).flatMapIndexed { i, x ->
            val joints = ArmMeasurement.Side.entries[i].joints
            val height = 350 - if (i == 0) rise else rightRise
            val angle = if (i == 0) degrees else rightDegrees
            val offset = height / (2 * tan(angle * PI / 360))
            listOf(Landmark(joints[0], Point2D(x, 250 + height + wristDrop), .9),
                Landmark(joints[1], Point2D(x + if (i == 0) -offset else offset, 250 + height / 2 + wristDrop), .9),
                Landmark(joints[2], Point2D(x, 250 + wristDrop), .9))
        }
        return PoseResult(PresentationTime(ms, 1000), ImageSize(900.0, 1400.0), listOf(PoseObservation(points)), "authored", 1)
    }
    private fun segment(start: Long, end: Long, rise: Double = 0.0, degrees: Double = 170.0) =
        (start..end step 40).map { pose(it, rise, degrees) }
    private fun cycle() = segment(0, 400) + segment(440, 720, 140.0, 100.0) + segment(760, 1120)
    private fun run(frames: List<PoseResult>, gate: (PoseResult) -> String? = { "set-a" },
        camera: (PoseResult) -> Boolean = { true }, support: (PoseResult) -> Boolean = { true }): List<PullUpCycleMotionExperiment.Snapshot> {
        val model = PullUpCycleMotionExperiment()
        return frames.map { model.observe(it, gate(it), camera(it), testBar.takeIf { _ -> support(it) }) }
    }
    @Test fun heldMeasuredFullReturnCountsOneWithoutEndOfInputClosure() {
        val snapshots = run(cycle())
        assertEquals(1, snapshots.last().cycles)
        val event = snapshots.mapNotNull { it.event }.single { it.outcome == "imageCycle" }
        assertEquals("measuredLowerReturn", event.reason)
        assertTrue(event.peak!! < event.lowerTurn!! && event.lowerTurn!! <= event.seconds)
        assertEquals(0, run(cycle().filter { it.timestamp.value <= 760 }).last().cycles)
    }
    @Test fun bentLowerTurnSeparatesCyclesAndLaterHeldBentReturnCompletes() {
        val frames = segment(0, 400) + segment(440, 720, 140.0, 100.0) + segment(760, 1040, 35.0, 140.0) +
            segment(1080, 1360, 140.0, 100.0) + segment(1400, 1800, 35.0, 140.0)
        val results = run(frames)
        assertEquals(2, results.last().cycles)
        assertEquals(listOf("observedLowerTurn", "measuredLowerReturn"), results.mapNotNull { it.event }.filter { it.outcome == "imageCycle" }.map { it.reason })
        assertTrue(frames.filter { it.timestamp.value >= 760 }.all { ArmMeasurement(it, ArmMeasurement.Side.left).estimate!!.elbowDegrees < 155 })
    }
    @Test fun setupAndRestCannotDonateMovementOrThePriorSetsPeak() {
        assertEquals(0, run(cycle(), gate = { null }).last().cycles)
        assertEquals(0, run(cycle(), gate = { if (it.timestamp.value < 760) "set-a" else "set-b" }).last().cycles)
        assertEquals(0, run(cycle(), gate = { if (it.timestamp.value in 440..720) null else "set-a" }).last().cycles)
    }
    @Test fun holdNoiseAndOneArmMovementCannotSupplyBilateralCycles() {
        assertEquals(0, run(segment(0, 2400)).last().cycles)
        assertEquals(0, run(cycle().map { if (it.timestamp.value in 440..720) pose(it.timestamp.value, 10.0, 165.0) else it }).last().cycles)
        assertEquals(0, run(cycle().map { if (it.timestamp.value in 440..720) pose(it.timestamp.value, 140.0, 100.0, rightRise = 0.0, rightDegrees = 170.0) else it }).last().cycles)
    }
    @Test fun angleChangeWithoutBodyMotionAndBodyTravelWithoutBendingDoNotCount() {
        for ((rise, angle) in listOf(0.0 to 100.0, 140.0 to 170.0))
            assertEquals(0, run(cycle().map { if (it.timestamp.value in 440..720) pose(it.timestamp.value, rise, angle) else it }).last().cycles)
    }
    @Test fun partialReturnThenHandsAndShouldersFallingTogetherCannotComplete() {
        val frames = segment(0, 400) + segment(440, 720, 140.0, 100.0) + segment(760, 1040, 100.0, 120.0) +
            (1080L..1520L step 40).map { pose(it, 100.0, 120.0, wristDrop = (it - 1040) * .3) }
        assertEquals(0, run(frames).last().cycles)
        assertTrue(frames.last().people.single().landmark(PoseJoint.leftShoulder)!!.position.y > 600)
    }
    @Test fun unfinishedFinalReturnIsNotInventedAtSourceEnd() {
        val frames = segment(0, 400) + segment(440, 720, 140.0, 100.0) + segment(760, 1000, 100.0, 120.0)
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun continuousFinalDescentThenReleaseCountsWithoutEndpointPause() {
        val descent = (760L..1040L step 40).map { ms ->
            val rise = (1040 - ms) / 2.0
            pose(ms, rise, 170 - rise / 2)
        }
        val release = (1080L..1320L step 40).map { pose(it, wristDrop = (it - 1040) * .8) }
        val results = run(segment(0, 400) + segment(440, 720, 140.0, 100.0) + descent + release)
        assertEquals(1, results.last().cycles)
        assertTrue(results.first { it.event?.outcome == "imageCycle" }.seconds!! <= 1.04)
    }
    @Test fun isolatedUpperBodyPoseSpikeCannotCreateACompletedCycle() {
        val frames = segment(0, 400) + listOf(pose(440, 140.0, 100.0)) + segment(480, 1400)
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun isolatedLowerPoseSpikeDuringPartialReturnCannotComplete() {
        val frames = segment(0, 400) + segment(440, 720, 140.0, 100.0) + segment(760, 1000, 100.0, 120.0) +
            listOf(pose(1040)) + segment(1080, 1600, 100.0, 120.0)
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun movingHandsAroundStationaryShouldersCannotCreateACycle() {
        val frames = cycle().map { f ->
            if (f.timestamp.value in 440..720) pose(f.timestamp.value, 140.0, 100.0, wristDrop = 140.0) else f
        }
        assertTrue(frames.all { it.people.single().landmark(PoseJoint.leftShoulder)!!.position.y == 600.0 })
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun cameraAndActualSupportLossInterruptRatherThanComplete() {
        assertEquals(0, run(cycle(), camera = { it.timestamp.value !in 760..1120 }).last().cycles)
        assertEquals(0, run(cycle(), support = { it.timestamp.value !in 760..1120 }).last().cycles)
    }
    @Test fun weakMissingDuplicatedCrowdedAndOutOfFrameArmsCannotComplete() {
        for (bad in listOf("weak", "missing", "duplicate", "crowd", "outside")) {
            val frames = cycle().map { f ->
                if (f.timestamp.value !in 760..1120) f else when (bad) {
                    "weak" -> f.copy(people = f.people.map { p -> p.copy(landmarks = p.landmarks.map { it.copy(confidence = .1) }) })
                    "missing" -> f.copy(people = listOf(f.people[0].copy(landmarks = f.people[0].landmarks.dropLast(1))))
                    "duplicate" -> f.copy(people = listOf(f.people[0].copy(landmarks = f.people[0].landmarks + f.people[0].landmarks.first())))
                    "crowd" -> f.copy(people = f.people + f.people)
                    else -> f.copy(people = f.people.map { p -> p.copy(landmarks = p.landmarks.map { it.copy(position = Point2D(-1.0, it.position.y)) }) })
                }
            }
            assertEquals(bad, 0, run(frames).last().cycles)
        }
    }
    @Test fun sourceGapRegressionAndContextCannotBridgeAnAttempt() {
        assertEquals(0, run(cycle().filter { it.timestamp.value !in 760..1080 }).last().cycles)
        assertEquals(0, run(cycle().map { if (it.timestamp.value == 760L) it.copy(timestamp = PresentationTime(100, 1000)) else it }).last().cycles)
        assertEquals(0, run(cycle().map { if (it.timestamp.value in 760..1120) it.copy(backend = "other") else it }).last().cycles)
    }
    @Test fun duplicateAndOlderFramesCannotEarnASecondCycleOrRewindClock() {
        val model = PullUpCycleMotionExperiment()
        val original = cycle().map { model.observe(it, "a", true, testBar) }
        assertEquals(1, original.last().cycles)
        val repeated = cycle().map { model.observe(it, "a", true, testBar) }
        assertTrue(repeated.all { it.reason == "sourceRegression" && it.cycles == 1 })
    }

    @Test fun wholeAnatomicalSideSwapsKeepSpatialArmIdentity() {
        val frames = cycle().map { f ->
            if (f.timestamp.value !in 440..720) f else f.copy(people = f.people.map { person -> person.copy(landmarks = person.landmarks.map { p ->
                val pair = ArmMeasurement.Side.entries.map { it.joints }.firstOrNull { p.joint in it }
                val other = if (pair == ArmMeasurement.Side.left.joints) ArmMeasurement.Side.right.joints else ArmMeasurement.Side.left.joints
                p.copy(joint = other[pair!!.indexOf(p.joint)])
            }) })
        }
        assertEquals(1, run(frames).last().cycles)
    }
    @Test fun briefCollapsedWristsSupplyNoPeakOrDwellButDoNotResetSpatialHistory() {
        val frames = cycle().map { f ->
            if (f.timestamp.value != 800L) f else f.copy(people = f.people.map { person ->
                val left = person.landmark(PoseJoint.leftWrist)!!
                person.copy(landmarks = person.landmarks.map { if (it.joint == PoseJoint.rightWrist) it.copy(position = left.position) else it })
            })
        }
        val results = run(frames)
        assertEquals("bodyUnavailable", results.first { it.seconds == .8 }.reason)
        assertNull(results.first { it.seconds == .8 }.event)
        assertEquals(1, results.last().cycles)
        assertTrue(results.first { it.event?.outcome == "imageCycle" }.seconds!! >= .96)
    }

    /** Opt-in original-frame experiment. The input has only the actual app's
     * recorded setup/lifecycle/camera verdict plus source pose, never labels.
     * Streaming keeps the 31k-frame source bounded in memory. */
    @Test fun evaluateActualAutomaticPathObservations() {
        val input = System.getenv("PULLUP_MOTION_INPUT") ?: return
        val output = File(checkNotNull(System.getenv("PULLUP_MOTION_OUTPUT")))
        check(!output.exists()) { "Preserve existing experimental evidence" }
        output.parentFile.mkdirs()
        val json = WorkoutRecordCodec.json
        val models = mutableMapOf<String, PullUpCycleMotionExperiment>()
        val counts = mutableMapOf<String, Int>()
        val isolatedReferences = mutableMapOf<String, BarSegment>()
        output.bufferedWriter().use { writer ->
            File(input).useLines { lines -> lines.filter { it.isNotBlank() }.forEach { line ->
                val row = json.parseToJsonElement(line).jsonObject
                val id = row.getValue("delivery").jsonPrimitive.content
                val pose = json.decodeFromJsonElement<PoseResult>(row.getValue("pose"))
                val key = row["activeSetKey"]?.jsonPrimitive?.contentOrNull
                val model = models.getOrPut(id) { PullUpCycleMotionExperiment() }
                // Explicit development-only isolation probe. The source's
                // actual locked acquisition event supplies observed contours
                // and its original pose; production selects the edge. This
                // cannot be reported as an automatic app start/camera result.
                row["isolatedReferenceCandidates"]?.let { candidates ->
                    val sourcePose = json.decodeFromJsonElement<PoseResult>(row.getValue("isolatedReferencePose"))
                    val observed = json.decodeFromJsonElement<List<BarLineCandidate>>(candidates)
                    val grip = checkNotNull(AutomaticPullUpBar.grip(sourcePose))
                    isolatedReferences[id] = checkNotNull(AutomaticPullUpBar.select(observed, grip, sourcePose.imageSize))
                }
                val edge = if (row["isolatedMotionProbe"]?.jsonPrimitive?.booleanOrNull == true) isolatedReferences[id]
                    else row["reference"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement<BarSegment>(it) }
                val result = model.observe(pose, key, row.getValue("cameraReady").jsonPrimitive.boolean, edge)
                val record = buildJsonObject {
                    put("delivery", id); put("activeSetKey", key?.let(::JsonPrimitive) ?: JsonNull)
                    put("sourceTime", json.encodeToJsonElement(pose.timestamp)); put("motion", json.encodeToJsonElement(result))
                    if (row["isolatedMotionProbe"]?.jsonPrimitive?.booleanOrNull == true) {
                        put("isolatedMotionProbe", true)
                        put("retainedSelectedReference", edge?.let { json.encodeToJsonElement(it) } ?: JsonNull)
                    }
                }
                writer.write(json.encodeToString(record)); writer.newLine()
                counts[id] = result.cycles
            } }
        }
        println("Development-only wrist-relative pull-up cycles: $counts")
    }
    companion object {
        private val testBar = BarSegment(Point2D(100.0, 250.0), Point2D(800.0, 250.0))
    }
}
