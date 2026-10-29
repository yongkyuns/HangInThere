package dev.hanginthere.core

import kotlin.test.*
import kotlin.math.*

class BilateralTorsoDipMotionTests {
    @Test fun subTopReturnAndAnotherDescentCannotCompleteUntilARealTopReturn() {
        for (drop in listOf(40.0, 60.0, 80.0)) for (missingOtherWrist in listOf(false, true)) {
            val omitted = if (missingOtherWrist) setOf(PoseJoint.rightWrist) else emptySet()
            val frames = (0L..1000L step 40).map { pose(it) } +
                (1040L..1360L step 40).map { pose(it, 140.0, 100.0, omitted = omitted) } +
                (1400L..2000L step 40).map { pose(it, drop, 120.0, omitted = omitted) } +
                (2040L..2400L step 40).map { pose(it, 140.0, 100.0, omitted = omitted) }
            assertEquals(0, run(frames).last().cycles, "Partial return $drop, missing opposite wrist $missingOtherWrist")
            val complete = frames + (2440L..2800L step 40).map { pose(it, omitted = omitted) }
            assertEquals(1, run(complete).last().cycles)
        }
    }

    @Test fun aMeasuredQualifiedUpperSupportTurnCanCompleteBelowTheInitialWorldHeight() {
        for (missingOtherWrist in listOf(false, true)) {
            val omitted = if (missingOtherWrist) setOf(PoseJoint.rightWrist) else emptySet()
            val frames = (0L..1000L step 40).map { pose(it) } +
                (1040L..1360L step 40).map { pose(it, 140.0, 100.0, omitted = omitted) } +
                (1400L..2000L step 40).map { pose(it, 60.0, 150.0, omitted = omitted) } +
                (2040L..2400L step 40).map { pose(it, 140.0, 100.0, omitted = omitted) }
            val event = run(frames).mapNotNull { it.event }.single { it.outcome == "imageCycle" }
            assertEquals("observedUpperTurn", event.reason)
        }
    }

    @Test fun currentShouldersMustHaveValidImageCoordinates() {
        val invalid = cycle(setOf(PoseJoint.rightWrist)).map { pose ->
            if (pose.timestamp.value < 1040) pose else pose.copy(people = pose.people.map { person ->
                person.copy(landmarks = person.landmarks.map {
                    if (it.joint == PoseJoint.rightShoulder) it.copy(position = Point2D(1700.0, it.position.y)) else it
                })
            })
        }
        assertEquals(0, run(invalid).last().cycles)
    }
    private fun pose(ms: Long, drop: Double = 0.0, degrees: Double = 170.0,
        rightDrop: Double = drop, rightDegrees: Double = degrees, wristDrop: Double = 0.0,
        omitted: Set<PoseJoint> = emptySet(), horizontal: Boolean = false): PoseResult {
        val points = listOf(250.0, 650.0).flatMapIndexed { i, x ->
            val chain = ArmMeasurement.Side.entries[i].joints
            val dy = if (i == 0) drop else rightDrop
            val angle = if (i == 0) degrees else rightDegrees
            val shoulder = 280 + dy; val wrist = 600 + wristDrop
            val offset = (wrist - shoulder) / (2 * tan(angle * PI / 360))
            listOf(Landmark(chain[0], Point2D(x, shoulder), .9),
                Landmark(chain[1], Point2D(x + if (i == 0) -offset else offset, (shoulder + wrist) / 2), .9),
                Landmark(chain[2], Point2D(x, wrist), .9))
        } + listOf(Landmark(PoseJoint.leftHip, Point2D(if (horizontal) 1100.0 else 250.0, 900.0 + drop), .9),
            Landmark(PoseJoint.rightHip, Point2D(if (horizontal) 1500.0 else 650.0, 900.0 + drop), .9))
        return PoseResult(PresentationTime(ms, 1000), ImageSize(1600.0, 1400.0),
            listOf(PoseObservation(points.filterNot { it.joint in omitted })), "authored", 1)
    }
    private fun cycle(omitted: Set<PoseJoint> = emptySet(), rightWitness: Boolean = false) =
        (0L..1000L step 40).map { pose(it) } + (1040L..1360L step 40).map {
            pose(it, 140.0, if (rightWitness) 170.0 else 100.0,
                rightDegrees = if (rightWitness) 100.0 else 170.0, omitted = omitted)
        } + (1400L..1800L step 40).map { pose(it, omitted = omitted) }
    private fun run(frames: List<PoseResult>, camera: (PoseResult) -> Boolean = { true }): List<BilateralTorsoDipMotion.Snapshot> {
        val selection = BodyAthleteSelection(); val motion = BilateralTorsoDipMotion()
        return frames.map { selection.observe(it); motion.observe(selection, camera(it)) }
    }
    @Test fun fullBilateralAndEitherSingleCurrentArmCanCorroborateCompletedBodyCycle() {
        for (kind in listOf("full", "rightWristMissing", "leftElbowMissing")) {
            val omitted = when (kind) { "rightWristMissing" -> setOf(PoseJoint.rightWrist); "leftElbowMissing" -> setOf(PoseJoint.leftElbow); else -> emptySet() }
            val result = run(cycle(omitted, kind == "leftElbowMissing"))
            assertEquals(1, result.last().cycles, kind)
            val event = result.mapNotNull { it.event }.single { it.outcome == "imageCycle" }
            assertTrue(event.departure!! <= event.peak!! && event.peak!! < event.seconds)
            assertTrue(event.measuredArmSlots.isNotEmpty())
        }
    }
    @Test fun initialCalibrationCannotUseAnUnobservedArm() {
        val frames = cycle(setOf(PoseJoint.rightWrist)).map { p ->
            p.copy(people = p.people.map { it.copy(landmarks = it.landmarks.filterNot { p -> p.joint == PoseJoint.rightWrist }) })
        }
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun bilateralWorldTravelAndActualBendingArmAndWristAreAllRequired() {
        for (kind in listOf("static", "shallow", "oneShoulder", "unbent", "angleOnly", "handsOnly", "bodyAndHands")) {
            val frames = cycle(setOf(PoseJoint.rightWrist)).map { p ->
                if (p.timestamp.value !in 1040..1360) p else when (kind) {
                    "static" -> pose(p.timestamp.value)
                    "shallow" -> pose(p.timestamp.value, 10.0, 165.0)
                    "oneShoulder" -> pose(p.timestamp.value, 140.0, 100.0, rightDrop = 0.0)
                    "unbent" -> pose(p.timestamp.value, 140.0, 170.0)
                    "angleOnly" -> pose(p.timestamp.value, 0.0, 100.0)
                    "handsOnly" -> pose(p.timestamp.value, 0.0, 100.0, wristDrop = -140.0)
                    else -> pose(p.timestamp.value, 140.0, 100.0, wristDrop = 140.0)
                }
            }
            assertEquals(0, run(frames).last().cycles, kind)
        }
    }
    @Test fun missingBothArmsOrAShoulderOrClockGapCannotComplete() {
        for (kind in listOf("bothArms", "shoulder", "sourceGap", "camera", "backend", "revision", "imageSize")) {
            var frames = cycle(setOf(PoseJoint.rightWrist)).map { p ->
                if (p.timestamp.value !in 1160..1640) p else when (kind) {
                    "bothArms" -> p.copy(people = p.people.map { it.copy(landmarks = it.landmarks.filterNot { l -> l.joint in setOf(PoseJoint.leftWrist, PoseJoint.rightWrist) }) })
                    "shoulder" -> p.copy(people = p.people.map { it.copy(landmarks = it.landmarks.filterNot { l -> l.joint == PoseJoint.rightShoulder }) })
                    "backend" -> p.copy(backend = "newBackend")
                    "revision" -> p.copy(requestRevision = 2)
                    "imageSize" -> p.copy(imageSize = ImageSize(1601.0, 1400.0))
                    else -> p
                }
            }
            if (kind == "sourceGap") frames = frames.filterNot { it.timestamp.value in 1160..1640 }
            assertEquals(0, run(frames) { kind != "camera" || it.timestamp.value !in 1160..1200 }.last().cycles, kind)
        }
    }
    @Test fun bendAndReturnCannotBeBorrowedFromDifferentArms() {
        val frames = cycle(setOf(PoseJoint.rightWrist)).map { p ->
            if (p.timestamp.value < 1400) p else pose(p.timestamp.value, omitted = setOf(PoseJoint.leftWrist))
        }
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun peakHoldPartialReturnDismountAndEOFAreNotCompletions() {
        val lower = cycle(setOf(PoseJoint.rightWrist)).filter { it.timestamp.value <= 1360 }
        assertEquals(0, run(lower).last().cycles)
        val partial = lower + (1400L..2200L step 40).map { pose(it, 90.0, 120.0, omitted = setOf(PoseJoint.rightWrist)) }
        assertEquals(0, run(partial).last().cycles)
        val dismount = lower + (1400L..1800L step 40).map { pose(it, 140.0, 100.0, wristDrop = 180.0) }
        assertEquals(0, run(dismount).last().cycles)
    }
    @Test fun duplicateAndRegressingSourceTimesCancelPrivateEvidence() {
        val frames = cycle(setOf(PoseJoint.rightWrist)).toMutableList()
        val index = frames.indexOfFirst { it.timestamp.value == 1240L }
        frames[index] = frames[index].copy(timestamp = frames[index - 1].timestamp)
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun horizontalPositivelyObservedTorsoCannotCount() {
        val frames = cycle(setOf(PoseJoint.rightWrist)).map { p ->
            if (p.timestamp.value !in 1040..1360) p else pose(p.timestamp.value, 140.0, 100.0, horizontal = true)
        }
        assertEquals(0, run(frames).last().cycles)
    }
    @Test fun aKnownDepartureOfTheOnlyWitnessWristCannotBeBridgedIntoARep() {
        val lower = cycle(setOf(PoseJoint.rightWrist)).filter { it.timestamp.value <= 1360 }
        val departed = (1400L..1800L step 40).map {
            pose(it, 140.0, 100.0, wristDrop = 160.0, omitted = setOf(PoseJoint.rightWrist))
        }
        val regripped = (1840L..2240L step 40).map { pose(it, omitted = setOf(PoseJoint.rightWrist)) }
        assertEquals(0, run(lower + departed + regripped).last().cycles)
    }
    @Test fun oneNoisyWristPointEarnsNoDeparturePairOrMovementEvidence() {
        val frames = cycle(setOf(PoseJoint.rightWrist)).map { p ->
            if (p.timestamp.value != 1240L) p else pose(1240, 140.0, 100.0,
                wristDrop = 160.0, omitted = setOf(PoseJoint.rightWrist))
        }
        assertEquals(1, run(frames).last().cycles)
    }
    @Test fun knownSupportLossBeforeDepartureRequiresFreshBilateralCalibration() {
        val prepared = (0L..1000L step 40).map { pose(it) }
        val released = (1040L..1240L step 40).map { pose(it, wristDrop = 160.0, omitted = setOf(PoseJoint.rightWrist)) }
        val regrippedLow = (1280L..1640L step 40).map { pose(it, 140.0, 100.0, omitted = setOf(PoseJoint.rightWrist)) }
        val returned = (1680L..2080L step 40).map { pose(it, omitted = setOf(PoseJoint.rightWrist)) }
        assertEquals(0, run(prepared + released + regrippedLow + returned).last().cycles)
    }
}
