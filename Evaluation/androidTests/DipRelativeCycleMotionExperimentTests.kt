package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class DipRelativeCycleMotionExperimentTests {
    private fun pose(ms: Long, drop: Double = 0.0, degrees: Double = 170.0,
        rightDrop: Double = drop, rightDegrees: Double = degrees,
        wristDrop: Double = 0.0, rightWristDrop: Double = wristDrop, horizontal: Boolean = false): PoseResult {
        val arms = listOf(250.0, 650.0).flatMapIndexed { i, x ->
            val joints = ArmMeasurement.Side.entries[i].joints
            val d = if (i == 0) drop else rightDrop
            val angle = if (i == 0) degrees else rightDegrees
            val wy = 600 + if (i == 0) wristDrop else rightWristDrop
            val sy = 280 + d
            val offset = (wy - sy) / (2 * tan(angle * PI / 360))
            listOf(Landmark(joints[0], Point2D(x, sy), .9),
                Landmark(joints[1], Point2D(x + if (i == 0) -offset else offset, (sy + wy) / 2), .9),
                Landmark(joints[2], Point2D(x, wy), .9))
        }
        val hips = listOf(250.0, 650.0).mapIndexed { i, x ->
            Landmark(if (i == 0) PoseJoint.leftHip else PoseJoint.rightHip,
                Point2D(x + if (horizontal) 600 else 0, if (horizontal) 300.0 + drop else 900.0 + drop), .9)
        }
        return PoseResult(PresentationTime(ms, 1000), ImageSize(1600.0, 1400.0),
            listOf(PoseObservation(arms + hips)), "authored", 1)
    }
    private fun segment(a: Long, b: Long, drop: Double = 0.0, degrees: Double = 170.0,
        rightDegrees: Double = degrees) = (a..b step 40).map { pose(it, drop, degrees, rightDegrees = rightDegrees) }
    private fun cycle() = segment(0, 400) + segment(440, 760, 140.0, 100.0, 170.0) + segment(800, 1200)
    private fun run(frames: List<PoseResult>, camera: (PoseResult) -> Boolean = { true },
        departure: (PoseResult) -> Boolean = { true }): List<DipRelativeCycleMotionExperiment.Snapshot> {
        val model = DipRelativeCycleMotionExperiment()
        return frames.map { model.observe(it, camera(it), departure(it)) }
    }

    @Test fun oneForeshortenedElbowDoesNotVetoMeasuredBilateralLowerReturn() {
        val frames = cycle()
        val old = BodyDipMotion()
        assertEquals(0, frames.map { old.observe(it, BodyDipMotion.Camera.ready) }.last().cycles)
        val current = run(frames)
        assertEquals(1, current.last().cycles)
        val event = current.mapNotNull { it.event }.single { it.outcome == "imageCycle" }
        assertTrue(event.departure!! <= event.peak!! && event.peak!! < event.seconds)
    }
    @Test fun observedWristDriftAloneCannotEraseCorroboratedBodyTravel() {
        val frames = cycle().map { p ->
            if (p.timestamp.value in 440..760) pose(p.timestamp.value, 140.0, 100.0,
                rightDegrees = 170.0, rightWristDrop = 60.0) else p
        }
        assertEquals(1, run(frames).last().cycles)
    }
    @Test fun aHigherMeasuredMountReplacesStandingPreparationBeforeTheFirstDip() {
        val standing = (0L..400L step 40).map { pose(it, 120.0, wristDrop = -80.0) }
        val mounted = segment(440, 840)
        val lowering = segment(880, 1200, 180.0, 100.0, 170.0)
        val returned = segment(1240, 1640)
        assertEquals(1, run(standing + mounted + lowering + returned).last().cycles)
    }
    @Test fun staticShallowOneShoulderAndUnbentMovementDoNotCount() {
        assertEquals(0, run(segment(0, 2400)).last().cycles)
        for (kind in listOf("shallow", "oneShoulder", "unbent", "angleOnly", "handsOnly")) {
            val frames = cycle().map { p ->
                if (p.timestamp.value !in 440..760) p else when (kind) {
                    "shallow" -> pose(p.timestamp.value, 10.0, 165.0)
                    "oneShoulder" -> pose(p.timestamp.value, 140.0, 100.0, rightDrop = 0.0)
                    "unbent" -> pose(p.timestamp.value, 140.0, 170.0)
                    "angleOnly" -> pose(p.timestamp.value, 0.0, 100.0)
                    else -> pose(p.timestamp.value, 0.0, 100.0, wristDrop = -140.0)
                }
            }
            assertEquals(kind, 0, run(frames).last().cycles)
        }
    }
    @Test fun wholeBodyAndHandsFallingTogetherCannotCount() {
        val frames = cycle().map { p ->
            if (p.timestamp.value in 440..760) pose(p.timestamp.value, 140.0, 100.0, wristDrop = 140.0) else p
        }
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun transientSDKOutageKeepsOnlyPrivateTrajectoryAndEarnsNoDwell() {
        val frames = cycle().map { if (it.timestamp.value in 600..640) it.copy(people = emptyList()) else it }
        assertEquals(1, run(frames).last().cycles)
        assertEquals(0, run(cycle().map { if (it.timestamp.value in 440..760) it.copy(people = emptyList()) else it }).last().cycles)
        assertEquals(0, run(cycle().map { if (it.timestamp.value in 560..1000) it.copy(people = emptyList()) else it }).last().cycles)
    }
    @Test fun partialFinalReturnAndEOFDoNotInventCompletion() {
        assertEquals(0, run(cycle().filter { it.timestamp.value <= 800 }).last().cycles)
        val frames = segment(0, 400) + segment(440, 760, 140.0, 100.0, 170.0) + segment(800, 1600, 90.0, 120.0, 170.0)
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun continuouslyMovingFinalReturnNeedsNoEndpointPause() {
        val movingReturn = (800L..1080L step 40).map { ms ->
            val drop = (1080 - ms) / 2.0
            pose(ms, drop, 170 - drop / 2, rightDegrees = 170.0)
        }
        val release = (1120L..1400L step 40).map { ms -> pose(ms, wristDrop = (ms - 1080) * .5) }
        val result = run(segment(0, 400) + segment(440, 760, 140.0, 100.0, 170.0) + movingReturn + release)
        assertEquals(1, result.last().cycles)
        assertTrue(result.first { it.event?.outcome == "imageCycle" }.seconds!! <= 1.08)
    }
    @Test fun fullyMeasuredFastReturnsCannotBeMissedWhileWaitingForAnIntermediateTurn() {
        val frames = (0L..400L step 100).map { pose(it) } + listOf(
            pose(500, 60.0, 140.0, rightDegrees = 170.0),
            pose(600, 140.0, 100.0, rightDegrees = 170.0),
            pose(700, 120.0, 110.0, rightDegrees = 170.0),
            pose(800, 20.0, 160.0, rightDegrees = 170.0),
            pose(900),
            pose(1000, 60.0, 140.0, rightDegrees = 170.0),
            pose(1100, 140.0, 100.0, rightDegrees = 170.0),
            pose(1200, 120.0, 110.0, rightDegrees = 170.0),
            pose(1300, 20.0, 160.0, rightDegrees = 170.0),
            pose(1400)
        )
        val events = run(frames).mapNotNull { it.event }.filter { it.outcome == "imageCycle" }
        assertEquals(2, events.size)
        assertTrue("The first full return must finish before the next lowering", events.first().seconds <= .9)
        assertTrue(events.first().seconds < events.last().departure!!)
    }
    @Test fun theObservedUpperTurnCanAlsoWitnessTheNextDepartureButNeverAnEarlierPeak() {
        val frames = segment(0, 400) + segment(440, 760, 140.0, 100.0, 170.0) +
            listOf(pose(800, 50.0, 145.0, rightDegrees = 170.0),
                pose(900, 50.0, 145.0, rightDegrees = 170.0),
                pose(1000, 50.0, 145.0, rightDegrees = 170.0),
                pose(1100, 110.0, 115.0, rightDegrees = 170.0),
                pose(1200, 120.0, 110.0, rightDegrees = 170.0),
                pose(1300, 140.0, 100.0, rightDegrees = 170.0),
                pose(1400, 50.0, 145.0, rightDegrees = 170.0),
                pose(1500, 50.0, 145.0, rightDegrees = 170.0))
        val events = run(frames).mapNotNull { it.event }.filter { it.outcome == "imageCycle" }
        assertEquals(2, events.size)
        assertTrue(events.all { it.departure!! <= it.peak!! && it.peak < it.seconds })
        assertTrue(events.first().seconds <= events.last().departure!!)
    }
    @Test fun fullRecoveryMagnitudeAloneDoesNotTurnOneTopSpikeIntoACompletion() {
        val frames = segment(0, 400) + segment(440, 760, 140.0, 100.0, 170.0) +
            segment(800, 1040, 70.0, 145.0, 170.0) + listOf(pose(1080)) +
            segment(1120, 1800, 70.0, 145.0, 170.0)
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun missingReturnFramesCannotSupplyAnAdjacentMeasuredRecoveryPair() {
        val returned = (800L..1600L step 40).mapIndexed { index, ms ->
            pose(ms).let { if (index % 2 == 0) it else it.copy(people = emptyList()) }
        }
        assertEquals(0, run(segment(0, 400) + segment(440, 760, 140.0, 100.0, 170.0) + returned).last().cycles)
    }
    @Test fun aSingleLoweringOrReturnSpikeCannotCreateACycle() {
        assertEquals(0, run(segment(0, 400) + listOf(pose(440, 140.0, 100.0)) + segment(480, 1400)).last().cycles)
        val frames = segment(0, 400) + segment(440, 760, 140.0, 100.0, 170.0) +
            segment(800, 1040, 90.0, 120.0, 170.0) + listOf(pose(1080)) + segment(1120, 1800, 90.0, 120.0, 170.0)
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun cameraSourceAndContextFaultsCannotBridgeReturn() {
        assertEquals(0, run(cycle(), camera = { it.timestamp.value !in 800..1200 }).last().cycles)
        assertEquals(0, run(cycle().filter { it.timestamp.value !in 800..1160 }).last().cycles)
        assertEquals(0, run(cycle().map { if (it.timestamp.value == 800L) it.copy(timestamp = PresentationTime(100, 1000)) else it }).last().cycles)
        assertEquals(0, run(cycle().map { if (it.timestamp.value in 800..1200) it.copy(requestRevision = 2) else it }).last().cycles)
    }
    @Test fun horizontalTorsoAndClearlyOverheadHandsCannotSupplyDipEvidence() {
        assertEquals(0, run(cycle().map { p -> pose(p.timestamp.value,
            if (p.timestamp.value in 440..760) 140.0 else 0.0,
            if (p.timestamp.value in 440..760) 100.0 else 170.0, horizontal = true) }).last().cycles)
        val overhead = cycle().map { p -> p.copy(people = p.people.map { person -> person.copy(landmarks = person.landmarks.map { l ->
            l.copy(position = Point2D(l.position.x, 1400 - l.position.y))
        }) }) }
        assertEquals(0, run(overhead).last().cycles)
    }
    @Test fun manualPreviewCannotBankAutomaticCycles() {
        assertEquals(0, run(cycle(), departure = { false }).last().cycles)
    }
    @Test fun weakMissingDuplicatedCrowdedAndInvalidObservationsCannotInventThePeak() {
        for (bad in listOf("weak", "missing", "duplicate", "crowd", "outside")) {
            val frames = cycle().map { p ->
                if (p.timestamp.value !in 440..760) p else when (bad) {
                    "weak" -> p.copy(people = p.people.map { it.copy(landmarks = it.landmarks.map { l -> l.copy(confidence = .1) }) })
                    "missing" -> p.copy(people = p.people.map { it.copy(landmarks = it.landmarks.filter { l -> l.joint != PoseJoint.leftWrist }) })
                    "duplicate" -> p.copy(people = p.people.map { it.copy(landmarks = it.landmarks + it.landmarks.first()) })
                    "crowd" -> p.copy(people = p.people + p.people)
                    else -> p.copy(people = p.people.map { it.copy(landmarks = it.landmarks.map { l -> l.copy(position = Point2D(-1.0, l.position.y)) }) })
                }
            }
            assertEquals(bad, 0, run(frames).last().cycles)
        }
    }
    @Test fun lowerBodyIsOptionalAndWholeSDKSideSwapsKeepSpatialArmSlots() {
        assertEquals(1, run(cycle().map { p -> p.copy(people = p.people.map { it.copy(landmarks = it.landmarks.filter { l -> l.joint !in listOf(PoseJoint.leftHip, PoseJoint.rightHip) }) }) }).last().cycles)
        val frames = cycle().map { p ->
            if (p.timestamp.value !in 440..760) p else p.copy(people = p.people.map { person -> person.copy(landmarks = person.landmarks.map { l ->
                val left = ArmMeasurement.Side.left.joints
                val right = ArmMeasurement.Side.right.joints
                when (l.joint) {
                    in left -> l.copy(joint = right[left.indexOf(l.joint)])
                    in right -> l.copy(joint = left[right.indexOf(l.joint)])
                    else -> l
                }
            }) })
        }
        assertEquals(1, run(frames).last().cycles)
    }
    @Test fun repeatedOldSourceCannotRewindTheFenceOrEarnAnotherCycle() {
        val model = DipRelativeCycleMotionExperiment()
        assertEquals(1, cycle().map { model.observe(it, true) }.last().cycles)
        val repeated = cycle().map { model.observe(it, true) }
        assertTrue(repeated.all { it.cycles == 1 && !it.sourceAccepted && it.reason == "sourceRegression" })
    }

    private fun weakWrist(ms: Long) = pose(ms).let { p ->
        p.copy(people = p.people.map { person -> person.copy(landmarks = person.landmarks.map { l ->
            if (l.joint == PoseJoint.leftWrist) l.copy(confidence = .1) else l
        }) })
    }
    private fun recoveryPrefix() = cycle() + segment(1240, 1560, 140.0, 100.0, 170.0) +
        (1600L..2000L step 40).map(::weakWrist)
    private fun freshCycleAfterRecovery() = listOf(pose(2040), pose(2080)) +
        segment(2120, 2440, 140.0, 100.0, 170.0) + segment(2480, 2880)

    @Test fun continuousAthleteCanReprepareAfterArmLossWithoutBankingTheMissingReturn() {
        // Independent torso association remains valid while the far wrist is
        // unusable. The unfinished second movement is discarded, and a wholly
        // new third movement can start from a fresh measured top pair.
        val model = DipRelativeCycleMotionExperiment()
        val states = (recoveryPrefix() + freshCycleAfterRecovery()).map { model.observe(it, true, athleteContinuous = true) }
        val events = states.mapNotNull { it.event }.filter { it.outcome == "imageCycle" }
        assertEquals(2, events.size)
        assertTrue(events.last().departure!! >= 2.12)
        assertTrue(events.last().peak!! >= 2.12)
        assertEquals(1, states.first { it.seconds == 2.08 }.cycles)
    }
    @Test fun missingReturnCannotBeBankedByRecoveryAndOneTopCannotReprepare() {
        val model = DipRelativeCycleMotionExperiment()
        val states = (recoveryPrefix() + listOf(pose(2040), pose(2080))).map {
            model.observe(it, true, athleteContinuous = true)
        }
        assertEquals(1, states.last().cycles)
        assertEquals("recoveredMeasuredTop", states.last().reason)
        val spike = DipRelativeCycleMotionExperiment()
        val frames = recoveryPrefix() + listOf(pose(2040)) +
            segment(2080, 2400, 140.0, 100.0, 170.0) + listOf(pose(2440))
        assertEquals(1, frames.map { spike.observe(it, true, athleteContinuous = true) }.last().cycles)
    }
    @Test fun noPriorCompletedCycleAndNoIndependentAssociationCannotUseFastRecovery() {
        assertEquals(1, run(recoveryPrefix() + freshCycleAfterRecovery()).last().cycles)
        val model = DipRelativeCycleMotionExperiment()
        val frames = segment(0, 400) + segment(440, 760, 140.0, 100.0, 170.0) +
            (800L..1200L step 40).map(::weakWrist) + listOf(pose(1240), pose(1280)) +
            segment(1320, 1640, 140.0, 100.0, 170.0) + segment(1680, 2080)
        assertEquals(0, frames.map { model.observe(it, true, athleteContinuous = true) }.last().cycles)
    }
    @Test fun associationCameraAndRawContextFaultsRevokeRecoveryDuringWeakArms() {
        for (faultAt in listOf(1800L, 1960L)) for (fault in listOf("association", "camera", "revision", "size", "backend", "clock")) {
            val model = DipRelativeCycleMotionExperiment()
            val frames = recoveryPrefix() + freshCycleAfterRecovery()
            val result = frames.map { original ->
                val atFault = original.timestamp.value == faultAt
                val p = if (!atFault) original else when (fault) {
                    "revision" -> original.copy(requestRevision = 2)
                    "size" -> original.copy(imageSize = ImageSize(1601.0, 1400.0))
                    "backend" -> original.copy(backend = "other")
                    "clock" -> original.copy(timestamp = PresentationTime(faultAt - 100, 1000))
                    else -> original
                }
                model.observe(p, !(atFault && fault == "camera"),
                    athleteContinuous = !(atFault && fault == "association"))
            }
            assertEquals(fault, 1, result.last().cycles)
        }
    }
    @Test fun cachedPreparationExpiresAndCannotSurviveARealRelease() {
        val long = DipRelativeCycleMotionExperiment()
        val frames = cycle() + (1240L..4400L step 40).map(::weakWrist) +
            freshCycleAfterRecovery().map { it.copy(timestamp = PresentationTime(it.timestamp.value + 2400, 1000)) }
        assertEquals(1, frames.map { long.observe(it, true, athleteContinuous = true) }.last().cycles)
        val release = DipRelativeCycleMotionExperiment()
        val released = recoveryPrefix() + listOf(pose(2040, 200.0, wristDrop = 200.0)) +
            freshCycleAfterRecovery().map { it.copy(timestamp = PresentationTime(it.timestamp.value + 40, 1000)) }
        val states = released.map { release.observe(it, true, athleteContinuous = true) }
        assertTrue(states.any { it.reason == "positiveBilateralRelease" })
        assertEquals(1, states.last().cycles)
    }
    @Test fun recoveryExpiryUsesExactSourceTimeAcrossRationalScales() {
        val model = DipRelativeCycleMotionExperiment()
        val frames = recoveryPrefix() + (2040L..4520L step 40).map(::weakWrist)
        assertTrue(frames.map { model.observe(it, true, athleteContinuous = true) }.last().preparationRetained)
        // Last usable arms were at1.56s. Different rational scale supplies the
        // last tick before4.56s and the exact3s boundary without Double rounding.
        val before = weakWrist(4560).copy(timestamp = PresentationTime(4_564_559, 1_001_000))
        val exact = weakWrist(4560).copy(timestamp = PresentationTime(4_564_560, 1_001_000))
        assertTrue(model.observe(before, true, athleteContinuous = true).preparationRetained)
        val expired = model.observe(exact, true, athleteContinuous = true)
        assertFalse(expired.preparationRetained)
        assertEquals("recoveryExpired", expired.reason)
        assertEquals(1, expired.cycles)
    }
    @Test fun isolatedUsableArmsCannotRefreshAnUnqualifiedRecoveryDeadline() {
        val model = DipRelativeCycleMotionExperiment()
        val isolated = setOf(2120L, 2640L, 3160L, 3680L, 4200L)
        val frames = recoveryPrefix() + (2040L..4560L step 40).map { ms ->
            if (ms in isolated) pose(ms, 140.0, 100.0, rightDegrees = 170.0) else weakWrist(ms)
        }
        val end = frames.map { model.observe(it, true, athleteContinuous = true) }.last()
        assertFalse(end.preparationRetained)
        assertEquals("recoveryExpired", end.reason)
        assertEquals(1, end.cycles)
    }

    /** Full-source mechanism diagnostics only. Runtime manifest has source pose
     * paths/IDs, never reference events/counts/bout windows. Authored ready camera
     * and explicit dip selection are disclosed; no automatic acquisition credit.
     */
    @Test fun evaluateSourceSeparatedOriginalObservations() {
        val input = System.getenv("DIP_RELATIVE_MANIFEST") ?: return
        val output = File(checkNotNull(System.getenv("DIP_RELATIVE_OUTPUT")))
        output.mkdirs()
        val json = WorkoutRecordCodec.json
        val manifest = json.parseToJsonElement(File(input).readText()).jsonObject
        for (element in manifest.getValue("sources").jsonArray) {
            val source = element.jsonObject
            val id = source.getValue("id").jsonPrimitive.content
            require(id.isNotBlank() && '/' !in id && ".." !in id)
            val stride = source["stride"]?.jsonPrimitive?.int ?: 1
            require(stride in 1..3)
            val model = DipRelativeCycleMotionExperiment()
            var frames = 0
            var seen = 0
            File(output, "$id.jsonl").bufferedWriter().use { writer ->
                File(source.getValue("poses").jsonPrimitive.content).useLines { lines -> lines.filter { it.isNotBlank() }.forEach { line ->
                    val index = seen++
                    if (index % stride != 0) return@forEach
                    val pose = json.decodeFromString<PoseResult>(line)
                    val state = model.observe(pose, cameraReady = true)
                    writer.write(json.encodeToString(state)); writer.newLine(); frames++
                } }
            }
            println("Dip relative mechanics $id: $seen original / $frames admitted source frames, stride $stride; authored ready camera, explicit dip only")
        }
    }
}
