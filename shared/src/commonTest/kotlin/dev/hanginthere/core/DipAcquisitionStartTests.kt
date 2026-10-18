package dev.hanginthere.core

import kotlin.math.roundToLong
import kotlin.test.*

class DipAcquisitionStartTests {
    private fun pose(seconds: Double, degrees: Double = 100.0, missing: ArmMeasurement.Side? = null): PoseResult {
        val original = CounterFixtures.projectedDip(seconds, degrees, if (degrees < 140) .7 else 1.0)
        return original.copy(timestamp = PresentationTime((seconds * 1_000_000).roundToLong(), 1_000_000),
            people = original.people.map { person -> PoseObservation(person.landmarks.filter {
                missing == null || it.joint !in missing.joints
            }) })
    }
    private fun line(x: Double) = BarLineCandidate(BarSegment(Point2D(x, 540.0), Point2D(x, 660.0)), 120.0)
    private val rails = listOf(listOf(line(300.0), line(310.0)), listOf(line(700.0), line(710.0)))
    private val sharedBar = listOf(505.0, 525.0).map {
        BarLineCandidate(BarSegment(Point2D(250.0, it), Point2D(750.0, it)), 500.0)
    }
    private val attitude = PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)!!
    private val patches = listOf(StaticSceneStability.PatchTranslation(0.0, 0.0),
        StaticSceneStability.PatchTranslation(.1, .1), StaticSceneStability.PatchTranslation(-.1, -.1))
    private fun input(seconds: Double, degrees: Double = 100.0, missing: ArmMeasurement.Side? = null) =
        WorkoutRuntime.FrameInput(pose(seconds, degrees, missing), rails, attitude, seconds, patches, 0.0, seconds)

    @Test fun flexedSupportedWristsAcquireOnlyActuallyObservedApparatus() {
        for (side in ArmMeasurement.Side.entries) for (candidates in listOf(rails, listOf(emptyList(), emptyList(), sharedBar))) {
            val identity = AutomaticExerciseDetection()
            val apparatus = AutomaticApparatusReference()
            var reference: ConfirmedBar? = null
            for (seconds in listOf(0.0, .3, .6, .9)) {
                val frame = pose(seconds)
                assertNotNull(AutomaticApparatusReference.search(frame, ExerciseCounter.Exercise.dip))
                assertFalse(AutomaticDipRails.isStartingPosition(frame))
                assertEquals(if (seconds == .9) ExerciseCounter.Exercise.dip else null, identity.observe(frame))
                assertNull(AutomaticExerciseDetection.candidate(frame))
                assertEquals(ExerciseCounter.Exercise.dip, identity.apparatusCandidate)
                reference = apparatus.observe(frame, ExerciseCounter.Exercise.dip, side, candidates)
            }
            val bar = assertNotNull(reference)
            assertEquals(ConfirmedBar.Method.automaticContours, bar.method)
            assertTrue(identity.lock()) // Identity still cannot supply starting extension.
            val counter = ExerciseCounter(ExerciseCounter.Exercise.dip, side)
            for (seconds in listOf(1.0, 1.1, 1.2)) counter.consume(pose(seconds), bar.referenceEdge)
            assertEquals(ExerciseCounter.Phase.seekingStart, counter.phase)
            assertTrue(counter.events.isEmpty())
            assertEquals(0, counter.observedMovements)
        }
        val missingEdges = AutomaticApparatusReference()
        for (seconds in listOf(0.0, .3, .6, .9, 1.2))
            assertNull(missingEdges.observe(pose(seconds), ExerciseCounter.Exercise.dip, ArmMeasurement.Side.left,
                listOf(emptyList(), emptyList(), emptyList())))
        assertNotEquals(AutomaticPullUpBar.State.locked, missingEdges.state)
    }

    @Test fun freshStartingPredicateKeepsTheOriginalTopSupportBoundary() {
        for (degrees in listOf(80.0, 100.0, 139.99, 140.0, 170.0)) {
            val frame = pose(0.0, degrees)
            assertNotNull(AutomaticDipRails.grip(frame))
            assertEquals(degrees >= 140, AutomaticDipRails.isStartingPosition(frame))
            assertEquals(if (degrees >= 140) ExerciseCounter.Exercise.dip else null,
                AutomaticExerciseDetection.candidate(frame))
        }
        for (missing in ArmMeasurement.Side.entries) {
            assertNull(AutomaticDipRails.grip(pose(0.0, 170.0, missing)))
            assertFalse(AutomaticDipRails.isStartingPosition(pose(0.0, 170.0, missing)))
        }
    }

    @Test fun retainedDipIdentityAndApparatusCannotEarnReadinessDuringFlexion() {
        val runtime = WorkoutRuntime(automaticWorkouts = true)
        for (seconds in listOf(0.0, .2, .4, .6)) runtime.observe(input(seconds, 170.0))
        assertEquals(ExerciseCounter.Exercise.dip, runtime.resolvedExercise)
        for (i in 4..10) {
            val snapshot = runtime.observe(input(i * .2))
            assertEquals(ExerciseCounter.Exercise.dip, snapshot.exercise)
            assertEquals(LiveSetSession.Phase.idle, snapshot.phase)
            assertEquals(0, snapshot.observedMovements)
        }
        val reference = assertNotNull(runtime.confirmedBar)
        runtime.observe(input(2.1, 170.0))
        runtime.observe(input(2.219999, 170.0))
        assertEquals(LiveSetSession.Phase.idle, runtime.snapshot.phase)
        runtime.observe(input(2.22, 170.0))
        assertEquals(LiveSetSession.Phase.running, runtime.snapshot.phase)
        assertEquals(reference, runtime.confirmedBar)
        assertEquals(2.1, runtime.session.firstSourceSeconds)
        assertEquals(0, runtime.session.observedMovements)
    }

    @Test fun explicitDipCanAcquireBentButNeedsFreshSelectedArmTopSupportToStart() {
        for (side in ArmMeasurement.Side.entries) {
            val runtime = WorkoutRuntime(selection = WorkoutExerciseSelection.dip, side = side, automaticWorkouts = true)
            for (i in 0..30) {
                assertEquals(LiveSetSession.Phase.idle, runtime.observe(input(i * .1)).phase)
            }
            assertNotNull(runtime.confirmedBar)
            val other = if (side == ArmMeasurement.Side.left) ArmMeasurement.Side.right else ArmMeasurement.Side.left
            runtime.observe(input(3.1, 170.0, other))
            runtime.observe(input(3.219999, 170.0, other))
            assertEquals(LiveSetSession.Phase.idle, runtime.snapshot.phase)
            runtime.observe(input(3.22, 170.0, other))
            assertEquals(LiveSetSession.Phase.running, runtime.snapshot.phase)
            assertEquals(3.1, runtime.session.firstSourceSeconds)
            assertEquals(0, runtime.session.observedMovements)
        }
    }

    @Test fun startingEvidencePreservesTimestampAndManualReferenceRequirements() {
        val top = pose(0.0, 170.0)
        val edge = rails[0][0].edge
        fun ready(frame: PoseResult, automatic: Boolean, reference: BarSegment? = edge) =
            WorkoutStartEvidence.hasStartingPose(frame, ExerciseCounter.Exercise.dip, automatic, ArmMeasurement.Side.left, reference)
        assertTrue(ready(top, true)); assertTrue(ready(top, false))
        for (automatic in listOf(false, true)) {
            assertFalse(ready(top, automatic, null))
            assertFalse(ready(top, automatic, BarSegment(Point2D(800.0, 800.0), Point2D(900.0, 800.0))))
        }
        assertTrue(ready(pose(0.0, 170.0, ArmMeasurement.Side.right), false))
        assertFalse(ready(pose(0.0, 170.0, ArmMeasurement.Side.right), true))
        for (automatic in listOf(false, true)) {
            assertFalse(ready(pose(0.0), automatic))
            assertFalse(ready(top.copy(timestamp = PresentationTime(0, 0)), automatic))
            assertFalse(ready(top.copy(people = top.people + top.people), automatic))
            val weak = top.copy(people = top.people.map { person ->
                PoseObservation(person.landmarks.map { it.copy(confidence = .1) })
            })
            assertFalse(ready(weak, automatic))
        }
    }

    @Test fun autoDipMovingOffTheAcquiredRailCannotSeedTheNextTopSupportDwell() {
        for (side in ArmMeasurement.Side.entries) {
            val runtime = WorkoutRuntime(side = side, automaticWorkouts = true)
            for (seconds in listOf(0.0, .2, .4, .6)) runtime.observe(input(seconds, 170.0))
            for (i in 4..10) runtime.observe(input(i * .2))
            val reference = assertNotNull(runtime.confirmedBar)
            for (seconds in listOf(2.1, 2.2, 2.4)) {
                val original = input(seconds, 170.0)
                val away = original.pose.copy(people = original.pose.people.map { person ->
                    PoseObservation(person.landmarks.map { it.copy(position = it.position.copy(x = it.position.x + 200)) })
                })
                assertEquals(ExerciseCounter.Exercise.dip, AutomaticExerciseDetection.candidate(away))
                val snapshot = runtime.observe(original.copy(pose = away))
                assertEquals(ExerciseCounter.Exercise.dip, snapshot.exercise)
                assertEquals(LiveSetSession.Phase.idle, snapshot.phase)
                assertEquals(reference, runtime.confirmedBar)
            }
            runtime.observe(input(2.5, 170.0))
            runtime.observe(input(2.619999, 170.0))
            assertEquals(LiveSetSession.Phase.idle, runtime.snapshot.phase)
            runtime.observe(input(2.62, 170.0))
            assertEquals(LiveSetSession.Phase.running, runtime.snapshot.phase)
            assertEquals(2.5, runtime.session.firstSourceSeconds)
            assertEquals(0, runtime.session.observedMovements)
        }
    }

    @Test fun automaticPullUpStillRequiresTheConfirmedReferenceAtTheCurrentHands() {
        val top = CounterFixtures.bilateral(0.0, 170.0, 170.0)
        val distant = BarSegment(Point2D(100.0, 100.0), Point2D(900.0, 100.0))
        for (automatic in listOf(false, true)) {
            assertFalse(WorkoutStartEvidence.hasStartingPose(top, ExerciseCounter.Exercise.pullUp, automatic,
                ArmMeasurement.Side.left, null))
            assertFalse(WorkoutStartEvidence.hasStartingPose(top, ExerciseCounter.Exercise.pullUp, automatic,
                ArmMeasurement.Side.left, distant))
            assertTrue(WorkoutStartEvidence.hasStartingPose(top, ExerciseCounter.Exercise.pullUp, automatic,
                ArmMeasurement.Side.left, CounterFixtures.edge))
        }
        val runtime = WorkoutRuntime(automaticWorkouts = true)
        fun frame(seconds: Double) = input(seconds).copy(pose = CounterFixtures.bilateral(seconds, 170.0, 170.0),
            candidates = emptyList())
        for (seconds in listOf(0.0, .2, .4, .6)) runtime.observe(frame(seconds))
        assertEquals(ExerciseCounter.Exercise.pullUp, runtime.resolvedExercise)
        val bar = ConfirmedBar(ConfirmedBar.Role.pullUpGrip, ConfirmedBar.Method.manualEdge, distant,
            null, top.imageSize, top.timestamp)
        assertTrue(runtime.confirmManualBar(bar, attitude, .6))
        for (i in 7..20) {
            assertEquals(LiveSetSession.Phase.idle, runtime.observe(frame(i * .1)).phase)
        }
        assertEquals(bar, runtime.confirmedBar)
        assertEquals(0, runtime.session.observedMovements)
    }
}
