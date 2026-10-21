package dev.hanginthere.core

import kotlin.test.*

class WorkoutFramingFeedbackTests {
    private val angle = "Angle camera so both arms and hands are clear"
    private fun pose(seconds: Double, weak: Boolean = true): PoseResult {
        val p = CounterFixtures.bilateral(seconds, 170.0, 170.0)
        return if (!weak) p else p.copy(people = p.people.map { person -> PoseObservation(
            person.landmarks.map { if (it.joint == PoseJoint.rightWrist) it.copy(confidence = .01) else it }) })
    }
    private fun WorkoutFramingFeedback.read(seconds: Double, weak: Boolean = true,
        idle: Boolean = true, bar: Boolean = false, automatic: Boolean = true,
        pullUp: Boolean = true, side: ArmMeasurement.Side = ArmMeasurement.Side.left) =
        observe(pose(seconds, weak), idle, bar, automatic, pullUp, side)

    @Test fun oneBadFrameCannotPublishDirectionAndClearFramingClearsImmediately() {
        val feedback = WorkoutFramingFeedback()
        assertNull(feedback.read(0.0))
        assertNull(feedback.read(.1, weak = false))
        assertNull(feedback.read(.2))
        assertNull(feedback.read(.35))
        assertEquals(angle, feedback.read(.51))
        assertNull(feedback.read(.52, weak = false))
        assertNull(feedback.read(.53))
    }
    @Test fun changingCameraAdviceDoesNotKeepAnOldInstructionOrConfirmNoise() {
        val feedback = WorkoutFramingFeedback()
        feedback.read(0.0); assertEquals(angle, feedback.read(.31))
        val clipped = pose(.32, weak = false).let { p -> p.copy(people = p.people.map { person ->
            PoseObservation(person.landmarks.map { if (it.joint == PoseJoint.rightWrist)
                it.copy(position = Point2D(it.position.x, -1.0)) else it }) }) }
        assertNull(feedback.observe(clipped, true, false, true, true, ArmMeasurement.Side.left))
        assertNull(feedback.read(.33))
        assertEquals(angle, feedback.read(.64))
    }
    @Test fun duplicatesRewindsAndMissingIntervalsCannotEarnConfirmation() {
        val feedback = WorkoutFramingFeedback()
        feedback.read(0.0); assertEquals(angle, feedback.read(.31))
        repeat(10) { assertNull(feedback.read(.31)) }
        assertNull(feedback.read(.50))
        assertNull(feedback.read(.30))
        assertEquals(angle, feedback.read(.61))
        assertNull(feedback.read(1.0))
        assertEquals(angle, feedback.read(1.31))
    }
    @Test fun identityAndPlatformFaultsAreImmediateAndResetCameraAdvice() {
        val feedback = WorkoutFramingFeedback()
        feedback.read(0.0); assertEquals(angle, feedback.read(.31))
        val p = pose(.32)
        assertEquals("Show one athlete clearly", feedback.observe(p.copy(people = emptyList()), true, false, true, true, ArmMeasurement.Side.left))
        assertEquals("Keep one athlete in view", feedback.observe(p.copy(people = p.people + p.people), true, false, true, true, ArmMeasurement.Side.left))
        val invalid = p.copy(people = p.people.map { person -> PoseObservation(person.landmarks.map {
            if (it.joint == PoseJoint.leftWrist) it.copy(confidence = Double.NaN) else it }) })
        assertEquals("Pose unavailable · retry the camera", feedback.observe(invalid, true, false, true, true, ArmMeasurement.Side.left))
        assertNull(feedback.read(.33))
        assertEquals("Pose unavailable · retry the camera", feedback.observe(p, true, false, true, true, ArmMeasurement.Side.left, sourceSeconds = Double.NaN))
        assertNull(feedback.read(.34))
    }
    @Test fun configurationLifecycleAndResolutionChangesStartFresh() {
        val feedback = WorkoutFramingFeedback()
        feedback.read(0.0); assertEquals(angle, feedback.read(.31))
        assertNull(feedback.read(.32, bar = true, automatic = false, pullUp = false))
        assertNull(feedback.read(.33, bar = true, automatic = false, pullUp = false, side = ArmMeasurement.Side.right))
        assertEquals("Improve lighting · keep the selected arm and hand clear",
            feedback.read(.64, bar = true, automatic = false, pullUp = false, side = ArmMeasurement.Side.right))
        assertNull(feedback.read(.65, idle = false))
        assertNull(feedback.read(.66))
        assertEquals(angle, feedback.read(.97))
        feedback.reset(); assertNull(feedback.read(.98))
        assertEquals(angle, feedback.read(1.29))
        val resized = pose(1.30).copy(imageSize = ImageSize(1200.0, 1200.0))
        assertNull(feedback.observe(resized, true, false, true, true, ArmMeasurement.Side.left))
    }
    @Test fun rawBridgeFaultsAndNonFiniteClockCannotCarryOldConfirmation() {
        val bridge = WorkoutFramingBridge()
        val points = pose(0.0).people.single().landmarks
        val joints = points.map { PoseJoint.entries.indexOf(it.joint) }.toIntArray()
        val triples = points.flatMap { listOf(it.position.x, it.position.y, it.confidence) }.toDoubleArray()
        fun read(seconds: Double) = bridge.observeSetup(seconds, 1000.0, 1000.0, 1, joints, triples, true, false, true, true, "left")
        assertNull(read(0.0)); assertEquals(angle, read(.31))
        assertFailsWith<IllegalArgumentException> {
            bridge.observeSetup(.32, 1000.0, 1000.0, 1, joints, doubleArrayOf(), true, false, true, true, "left")
        }
        assertNull(read(.33))
        assertEquals("Pose unavailable · retry the camera", read(Double.NaN))
        assertNull(read(.34)); assertEquals(angle, read(.65))
        bridge.resetFeedback(); assertNull(read(.66))
    }
}
