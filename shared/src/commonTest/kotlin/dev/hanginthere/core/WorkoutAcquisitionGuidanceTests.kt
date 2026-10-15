package dev.hanginthere.core

import kotlin.test.*

class WorkoutAcquisitionGuidanceTests {
    private val cue = "Hold your start position · keep the phone steady"
    private val holding = AutomaticPullUpBar.State.holding
    private fun pose(seconds: Double = 1.0, dip: Boolean = false) = CounterFixtures.bilateral(
        seconds, 170.0, 170.0, exercise = if (dip) ExerciseCounter.Exercise.dip else ExerciseCounter.Exercise.pullUp)
    private fun diagnostic(seconds: Double = 1.0) = AutomaticReferenceStability.Diagnostic(
        AutomaticReferenceStability.Status.accumulating, 2, .31, seconds)
    private fun advice(p: PoseResult = pose(), state: AutomaticPullUpBar.State = holding,
        d: AutomaticReferenceStability.Diagnostic? = diagnostic(), idle: Boolean = true,
        bar: Boolean = false, dip: Boolean = false) = WorkoutFramingGuidance.setupAdvice(
            p, idle, bar, true, !dip, ArmMeasurement.Side.left, state, d)?.message

    @Test fun onlyFreshLiveDwellCanRequestAStartingHold() {
        assertEquals(cue, advice())
        assertEquals(cue, advice(pose(dip = true), dip = true))
        assertEquals(cue, advice(d = diagnostic(.2)), "The existing maximum frame gap is inclusive")
        assertNull(advice(d = null)); assertNull(advice(bar = true)); assertNull(advice(idle = false))
        for (state in AutomaticPullUpBar.State.entries.filter { it != holding }) assertNull(advice(state = state))
        for (d in listOf(diagnostic(.199999), diagnostic(1.000001), diagnostic(Double.NaN),
            diagnostic().copy(sourceSeconds = null), diagnostic().copy(observations = 0),
            diagnostic().copy(stableSeconds = -.01), diagnostic().copy(stableSeconds = Double.POSITIVE_INFINITY),
            diagnostic().copy(status = AutomaticReferenceStability.Status.qualified),
            diagnostic().copy(status = AutomaticReferenceStability.Status.sourceRegression))) assertNull(advice(d = d))
    }
    @Test fun poseAndEquipmentAdviceRemainFirstAndSearchMustStillBePossible() {
        fun changed(confidence: Double) = pose().let { p -> p.copy(people = p.people.map { person ->
            PoseObservation(person.landmarks.map { if (it.joint == PoseJoint.rightWrist) it.copy(confidence = confidence) else it }) }) }
        assertEquals("Angle camera so both arms and hands are clear", advice(changed(.01)))
        assertEquals("Keep both hands and shoulders clearly visible", advice(changed(.4)))
        val overhead = pose().let { p -> p.copy(people = p.people.map { person ->
            PoseObservation(person.landmarks.map { it.copy(position = it.position.copy(y = it.position.y - 450.0)) }) }) }
        assertEquals("Leave room above your hands for the bar", advice(overhead))
        assertNull(advice(pose(dip = true)), "A stale pull-up context cannot request a hang during dip support")
        assertNull(advice(pose(), dip = true))
        assertEquals("Show one athlete clearly", advice(pose().copy(people = emptyList())))
    }
    @Test fun debounceRecoveryAndLifecycleCannotCarryAnOldAcquisitionCue() {
        val feedback = WorkoutFramingFeedback()
        fun read(t: Double, d: AutomaticReferenceStability.Diagnostic? = diagnostic(t), bar: Boolean = false,
            idle: Boolean = true) = feedback.observe(pose(t), idle, bar, true, true, ArmMeasurement.Side.left,
                acquisitionState = holding, referenceStability = d)
        assertNull(read(0.0)); assertEquals(cue, read(.31))
        val reset = diagnostic(.32).copy(status = AutomaticReferenceStability.Status.edgeChanged, observations = 1, stableSeconds = 0.0)
        assertEquals(cue, read(.32, reset))
        assertEquals(1, reset.observations); assertEquals(0.0, reset.stableSeconds)
        assertNull(read(.33, null)); assertNull(read(.34)); assertEquals(cue, read(.65))
        assertNull(read(.66, bar = true)); assertNull(read(.67)); assertNull(read(.68, idle = false))
        assertNull(read(.69)); feedback.reset(); assertNull(read(.70))
    }
    @Test fun rawBridgeMissingUnknownAndStaleEvidenceCannotInventDwell() {
        val bridge = WorkoutFramingBridge(); val points = pose().people.single().landmarks
        val joints = points.map { PoseJoint.entries.indexOf(it.joint) }.toIntArray()
        val triples = points.flatMap { listOf(it.position.x, it.position.y, it.confidence) }.toDoubleArray()
        fun read(t: Double, state: String = "holding", status: String = "accumulating", source: Double = t) =
            bridge.observeSetupWithAcquisition(t, 1000.0, 1000.0, 1, joints, triples, true, false, true, true,
                "left", state, status, source, 2, .31)
        assertNull(read(0.0)); assertEquals(cue, read(.31))
        assertNull(read(.32, status = "unknown")); assertNull(read(.33, state = "unknown"))
        assertNull(read(.34, source = -.6)); assertNull(read(.35, source = Double.NaN))
        assertNull(read(.36)); assertEquals(cue, read(.67))
        assertNull(bridge.observeSetup(.68, 1000.0, 1000.0, 1, joints, triples, true, false, true, true, "left"))
        assertNull(read(.69))
        bridge.resetFeedback(); assertNull(read(0.0))
        assertEquals(cue, read(1.0 / 3.0), "Fresh source time must survive microsecond pose-transport truncation")
    }
    @Test fun acquisitionInvalidationPreservesVisibilityConfirmation() {
        val feedback = WorkoutFramingFeedback()
        fun read(t: Double, unclear: Boolean = false): String? {
            val p = pose(t).let { original -> if (!unclear) original else original.copy(people = original.people.map { person ->
                PoseObservation(person.landmarks.map { if (it.joint == PoseJoint.rightWrist) it.copy(confidence = .01) else it }) }) }
            return feedback.observe(p, true, false, true, true, ArmMeasurement.Side.left,
                acquisitionState = holding, referenceStability = diagnostic(t))
        }
        assertNull(read(0.0)); assertTrue(feedback.invalidateAcquisition())
        assertFalse(feedback.invalidateAcquisition()); assertNull(read(.01))
        assertEquals(cue, read(.32)); assertTrue(feedback.invalidateAcquisition())
        assertNull(read(.33)); assertNull(read(.34, unclear = true))
        assertFalse(feedback.invalidateAcquisition())
        assertNull(read(.63, unclear = true)); assertFalse(feedback.invalidateAcquisition())
        assertEquals("Angle camera so both arms and hands are clear", read(.65, unclear = true))
        assertFalse(feedback.invalidateAcquisition())
        assertEquals("Angle camera so both arms and hands are clear", read(.66, unclear = true))
    }
    @Test fun actualRuntimeDwellAdviceNeverConfirmsOrStartsASet() {
        val runtime = WorkoutRuntime(WorkoutExerciseSelection.pullUp)
        val attitude = PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)!!
        val candidates = listOf(listOf(BarLineCandidate(CounterFixtures.edge, 800.0)))
        fun read(t: Double) = runtime.observe(WorkoutRuntime.FrameInput(pose(t), attitude = attitude,
            motionTimestamp = t, candidates = candidates))
        assertNull(read(0.0).framingHint)
        for (t in listOf(.31, .62)) {
            val s = read(t); assertEquals(cue, s.framingHint)
            assertNull(s.referenceEdge); assertNull(s.countdownSeconds); assertEquals(0, s.observedMovements)
            assertEquals(LiveSetSession.Phase.idle, s.phase)
        }
        val confirmed = read(.93)
        assertNotNull(confirmed.referenceEdge); assertNull(confirmed.framingHint)
        assertEquals(0, confirmed.observedMovements); assertEquals(LiveSetSession.Phase.idle, confirmed.phase)
    }
}
