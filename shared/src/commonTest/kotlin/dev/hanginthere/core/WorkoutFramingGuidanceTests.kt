package dev.hanginthere.core

import kotlin.test.*

class WorkoutFramingGuidanceTests {
    private fun pose(change: (Landmark) -> Landmark = { it }): PoseResult {
        val points = listOf(
            Landmark(PoseJoint.leftShoulder, Point2D(220.0, 120.0), .9),
            Landmark(PoseJoint.leftElbow, Point2D(210.0, 75.0), .9),
            Landmark(PoseJoint.leftWrist, Point2D(200.0, 30.0), .9),
            Landmark(PoseJoint.rightShoulder, Point2D(420.0, 120.0), .9),
            Landmark(PoseJoint.rightElbow, Point2D(430.0, 75.0), .9),
            Landmark(PoseJoint.rightWrist, Point2D(440.0, 30.0), .9))
        return PoseResult(PresentationTime(0, 1000), ImageSize(640.0, 360.0),
            listOf(PoseObservation(points.map(change))), "authored", 1)
    }
    @Test fun clearArmsAreSilentAndIdentityProblemsRemainExplicit() {
        val p = pose()
        assertNull(WorkoutFramingGuidance.message(p))
        assertEquals("Show one athlete clearly", WorkoutFramingGuidance.message(p.copy(people = emptyList())))
        assertEquals("Keep one athlete in view", WorkoutFramingGuidance.message(p.copy(people = p.people + p.people)))
    }
    @Test fun clippedAndMarginalArmsSuggestReframingWithoutClaimingValidity() {
        assertEquals("Move phone back · keep shoulders, elbows and hands in frame",
            WorkoutFramingGuidance.message(pose { if (it.joint == PoseJoint.rightWrist) it.copy(position = Point2D(440.0, -2.0)) else it }))
        assertEquals("Move phone back · leave room around arms and hands",
            WorkoutFramingGuidance.message(pose { if (it.joint == PoseJoint.rightWrist) it.copy(position = Point2D(440.0, 5.0)) else it }))
    }
    @Test fun oneUnclearArmSuggestsAngleAndTwoUnclearArmsSuggestVisibility() {
        assertEquals("Angle camera so both arms and hands are clear", WorkoutFramingGuidance.message(
            pose { if (it.joint == PoseJoint.rightWrist) it.copy(confidence = .01) else it }))
        assertEquals("Improve lighting · keep arms and hands clear", WorkoutFramingGuidance.message(
            pose { if (it.joint in listOf(PoseJoint.leftWrist, PoseJoint.rightWrist)) it.copy(confidence = .01) else it }))
        val p = pose()
        assertEquals("Keep shoulders, elbows and hands in frame", WorkoutFramingGuidance.message(
            p.copy(people = listOf(PoseObservation(p.people.single().landmarks.filter { it.joint != PoseJoint.rightWrist })))))
    }
    @Test fun malformedEvidenceIsNotBlamedOnTheAthlete() {
        for (score in listOf(Double.NaN, -1.0, 1.1)) assertEquals("Pose unavailable · retry the camera",
            WorkoutFramingGuidance.message(pose { if (it.joint == PoseJoint.leftWrist) it.copy(confidence = score) else it }))
        val p = pose()
        val duplicate = p.copy(people = listOf(PoseObservation(p.people.single().landmarks + p.people.single().landmarks.first())))
        assertEquals("Pose unavailable · retry the camera", WorkoutFramingGuidance.message(duplicate))
        assertEquals("Pose unavailable · retry the camera", WorkoutFramingGuidance.message(p.copy(imageSize = ImageSize(Double.NaN, 360.0))))
    }
    @Test fun selectedArmFallbackDoesNotRequireTheOtherArm() {
        val p = pose { if (it.joint == PoseJoint.rightWrist) it.copy(confidence = .01) else it }
        assertNull(WorkoutFramingGuidance.message(p, requireBothArms = false, side = ArmMeasurement.Side.left))
        assertNotNull(WorkoutFramingGuidance.message(p, requireBothArms = false, side = ArmMeasurement.Side.right))
    }
    @Test fun confirmedManualDipUsesSelectedArmAdviceAndRunningSetsStaySilent() {
        val p = pose { if (it.joint == PoseJoint.rightWrist) it.copy(confidence = .01) else it }
        assertEquals("Improve lighting · keep the selected arm and hand clear", WorkoutFramingGuidance.setupMessage(
            p, idle = true, barConfirmed = true, automatic = false, pullUp = false, side = ArmMeasurement.Side.right))
        assertNull(WorkoutFramingGuidance.setupMessage(p, true, true, false, false, ArmMeasurement.Side.left))
        assertEquals("Angle camera so both arms and hands are clear",
            WorkoutFramingGuidance.setupMessage(p, true, true, true, false, ArmMeasurement.Side.left))
        assertNull(WorkoutFramingGuidance.setupMessage(p, false, false, true, true, ArmMeasurement.Side.right))
        val runtime = WorkoutRuntime(WorkoutExerciseSelection.dip, ArmMeasurement.Side.right)
        val bar = ConfirmedBar(ConfirmedBar.Role.rightDipRail, ConfirmedBar.Method.manualEdge,
            BarSegment(Point2D(400.0, 30.0), Point2D(500.0, 30.0)), imageSize = p.imageSize, sourceTime = p.timestamp)
        val attitude = PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)!!
        assertTrue(runtime.confirmManualBar(bar, attitude, 0.0))
        assertNull(runtime.observe(WorkoutRuntime.FrameInput(p, attitude = attitude, motionTimestamp = 0.0)).framingHint)
        val snapshot = runtime.observe(WorkoutRuntime.FrameInput(p.copy(timestamp = PresentationTime(310, 1000)),
            attitude = attitude, motionTimestamp = .31))
        assertEquals("framingIncomplete", snapshot.setupIssue)
        assertEquals("Improve lighting · keep the selected arm and hand clear", snapshot.framingHint)
        assertNotNull(snapshot.referenceEdge)
        assertEquals(0, snapshot.observedMovements)
        assertNull(snapshot.countdownSeconds)
        runtime.session.start(ExerciseCounter.Exercise.dip, ArmMeasurement.Side.right)
        assertNull(runtime.snapshot.framingHint)
        runtime.stop(); runtime.nextSet()
        assertNull(runtime.snapshot.framingHint, "A new set cannot reuse the previous setup instruction")
        assertNull(runtime.observe(WorkoutRuntime.FrameInput(p.copy(timestamp = PresentationTime(320, 1000)),
            attitude = attitude, motionTimestamp = .32)).framingHint)
    }
    @Test fun runtimeGuidanceClearsWithFreshEvidenceOrInvalidatedSetup() {
        val runtime = WorkoutRuntime()
        val unclear = pose { if (it.joint == PoseJoint.rightWrist) it.copy(confidence = .01) else it }
        val first = runtime.observe(WorkoutRuntime.FrameInput(unclear))
        assertEquals("framingIncomplete", first.setupIssue)
        assertNull(first.framingHint, "One unclear frame must not publish a camera instruction")
        assertEquals(0, first.observedMovements)
        assertNull(first.countdownSeconds)
        val stable = runtime.observe(WorkoutRuntime.FrameInput(unclear.copy(timestamp = PresentationTime(310, 1000))))
        assertEquals("Angle camera so both arms and hands are clear", stable.framingHint)
        assertEquals(0, stable.observedMovements)
        assertNull(stable.countdownSeconds)
        assertNull(runtime.observe(WorkoutRuntime.FrameInput(pose().copy(timestamp = PresentationTime(400, 1000)))).framingHint)
        runtime.observe(WorkoutRuntime.FrameInput(unclear.copy(timestamp = PresentationTime(500, 1000))))
        runtime.interrupt(LiveSetSession.EndReason.cameraInterrupted)
        assertNull(runtime.snapshot.framingHint)
    }
    @Test fun rawBridgePreservesDuplicateJointsAndRejectsMalformedTriples() {
        val p = pose()
        val points = p.people.single().landmarks + p.people.single().landmarks.first()
        val bridge = WorkoutFramingBridge()
        val joints = points.map { PoseJoint.entries.indexOf(it.joint) }.toIntArray()
        val triples = points.flatMap { listOf(it.position.x, it.position.y, it.confidence) }.toDoubleArray()
        assertEquals("Pose unavailable · retry the camera", bridge.message(640.0, 360.0, 1, joints, triples, true, "left"))
        assertFailsWith<IllegalArgumentException> { bridge.message(640.0, 360.0, 1, joints, doubleArrayOf(), true, "left") }
    }
}
