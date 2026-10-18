package dev.hanginthere.core

import kotlin.math.roundToLong
import kotlin.test.*

class DipContactAcquisitionTests {
    private fun pose(seconds: Double, deep: Boolean = false, rightHeight: Double = 0.0): PoseResult {
        val original = CounterFixtures.projectedDip(seconds, 170.0, 1.0)
        val landmarks = original.people.single().landmarks.map { point ->
            val side = ArmMeasurement.Side.entries.firstOrNull { point.joint in it.joints }
            val wristX = if (side == ArmMeasurement.Side.right) 700.0 else 300.0
            val position = if (deep) when (point.joint) {
                PoseJoint.leftShoulder, PoseJoint.rightShoulder -> Point2D(wristX, 480.0)
                PoseJoint.leftElbow, PoseJoint.rightElbow -> Point2D(wristX + 100.0, 400.0)
                else -> point.position
            } else point.position
            point.copy(position = position.copy(y = position.y + if (side == ArmMeasurement.Side.right) rightHeight else 0.0))
        }
        return original.copy(timestamp = PresentationTime((seconds * 1_000_000).roundToLong(), 1_000_000),
            people = listOf(PoseObservation(landmarks)))
    }
    private fun rails(rightHeight: Double = 0.0) = listOf(300.0, 700.0).mapIndexed { index, x ->
        val y = 540.0 + if (index == 1) rightHeight else 0.0
        listOf(x, x + 10).map { px -> BarLineCandidate(BarSegment(Point2D(px, y), Point2D(px, y + 120)), 120.0) }
    }
    private val shared = listOf(505.0, 525.0).map { y ->
        BarLineCandidate(BarSegment(Point2D(250.0, y), Point2D(750.0, y)), 500.0)
    }

    private fun flexedSupport(seconds: Double): PoseResult = pose(seconds).let { frame ->
        frame.copy(people = frame.people.map { person -> person.copy(landmarks = person.landmarks.map { point ->
            when (point.joint) {
                PoseJoint.leftElbow -> point.copy(position = Point2D(450.0, 350.0))
                PoseJoint.rightElbow -> point.copy(position = Point2D(850.0, 350.0))
                else -> point
            }
        }) })
    }

    @Test fun flexedSupportCanIdentifyDipsButCannotSupplyStartingExtension() {
        val identity = AutomaticExerciseDetection()
        for (time in listOf(0.0, .2, .4, .6)) {
            val frame = flexedSupport(time)
            assertNotNull(AutomaticDipRails.grip(frame))
            assertNull(AutomaticExerciseDetection.candidate(frame))
            val found = identity.observe(frame)
            assertEquals(if (time == .6) ExerciseCounter.Exercise.dip else null, found)
            for (side in ArmMeasurement.Side.entries) for (automatic in listOf(false, true))
                assertFalse(WorkoutStartEvidence.hasStartingPose(frame, ExerciseCounter.Exercise.dip,
                    automatic, side, rails()[if (side == ArmMeasurement.Side.left) 0 else 1][0].edge))
        }
    }

    @Test fun interruptedFlexedIdentityNeedsAnEntireFreshSupportDwell() {
        for (deepOnly in listOf(false, true)) {
            val identity = AutomaticExerciseDetection()
            for (time in listOf(0.0, .2, .4)) assertNull(identity.observe(flexedSupport(time)))
            val interrupted = if (deepOnly) pose(.5, deep = true) else flexedSupport(.5).let { frame ->
                frame.copy(people = frame.people.map { person -> person.copy(
                    landmarks = person.landmarks.filter { it.joint != PoseJoint.rightWrist }) })
            }
            assertNull(identity.observe(interrupted))
            for (time in listOf(.6, .8, 1.0, 1.199999)) assertNull(identity.observe(flexedSupport(time)))
            assertEquals(ExerciseCounter.Exercise.dip, identity.observe(flexedSupport(1.2)))
        }
    }

    @Test fun flexedDipAcquisitionStillRequiresFreshTopBeforeLiveCounting() {
        val runtime = WorkoutRuntime(automaticWorkouts = true)
        val attitude = PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)!!
        fun input(time: Double, top: Boolean = false) = WorkoutRuntime.FrameInput(
            if (top) pose(time) else flexedSupport(time), rails(), attitude, time,
            List(3) { StaticSceneStability.PatchTranslation(0.0, 0.0) }, 0.0, time)
        for (i in 0..7) assertEquals(LiveSetSession.Phase.idle, runtime.observe(input(i * .2)).phase)
        assertEquals(ExerciseCounter.Exercise.dip, runtime.resolvedExercise)
        assertNotNull(runtime.confirmedBar)
        assertEquals(0, runtime.session.observedMovements)
        runtime.observe(input(1.6, top = true))
        runtime.observe(input(1.719999, top = true))
        assertEquals(LiveSetSession.Phase.idle, runtime.snapshot.phase)
        runtime.observe(input(1.72, top = true))
        assertEquals(LiveSetSession.Phase.running, runtime.snapshot.phase)
        assertEquals(1.6, runtime.session.firstSourceSeconds)
        assertEquals(0, runtime.session.observedMovements)
    }

    @Test fun observedContactCanAcquireThroughDeepShoulderMotionWithoutStartingASet() {
        for (side in ArmMeasurement.Side.entries) for (candidates in listOf(rails(), listOf(emptyList(), emptyList(), shared))) {
            val apparatus = AutomaticApparatusReference()
            var bar: ConfirmedBar? = null
            for (seconds in listOf(0.0, .3, .6, 1.0)) {
                val frame = pose(seconds, deep = true)
                assertEquals(2, PullUpArmMeasurement(frame).arms.size)
                assertNull(AutomaticDipRails.grip(frame), "Deep shoulder position cannot establish dip support")
                assertNotNull(AutomaticDipRails.apparatusGrip(frame))
                assertNotNull(AutomaticApparatusReference.search(frame, ExerciseCounter.Exercise.dip))
                assertNull(AutomaticExerciseDetection.candidate(frame))
                bar = apparatus.observe(frame, ExerciseCounter.Exercise.dip, side, candidates)
            }
            val reference = assertNotNull(bar)
            assertEquals(ConfirmedBar.Method.automaticContours, reference.method)
            val counter = ExerciseCounter(ExerciseCounter.Exercise.dip, side)
            for (seconds in listOf(1.1, 1.3, 1.5)) {
                val frame = pose(seconds, deep = true)
                for (automatic in listOf(false, true))
                    assertFalse(WorkoutStartEvidence.hasStartingPose(frame, ExerciseCounter.Exercise.dip, automatic, side, reference.referenceEdge))
                counter.consume(frame, reference.referenceEdge)
            }
            assertEquals(ExerciseCounter.Phase.seekingStart, counter.phase)
            assertTrue(counter.events.isEmpty())
        }
    }

    @Test fun independentRailsNeedActualCorrespondenceRatherThanEqualImageHandHeight() {
        for (side in ArmMeasurement.Side.entries) {
            val apparatus = AutomaticApparatusReference()
            var bar: ConfirmedBar? = null
            for (seconds in listOf(0.0, .3, .6, 1.0)) {
                val frame = pose(seconds, rightHeight = 150.0)
                assertTrue(AutomaticDipRails.isStartingPosition(frame))
                assertEquals(ExerciseCounter.Exercise.dip, AutomaticExerciseDetection.candidate(frame))
                bar = apparatus.observe(frame, ExerciseCounter.Exercise.dip, side, rails(150.0))
            }
            val reference = assertNotNull(bar)
            assertEquals(rails(150.0)[if (side == ArmMeasurement.Side.left) 0 else 1][0].edge, reference.referenceEdge)
            assertTrue(WorkoutStartEvidence.hasStartingPose(pose(1.1, rightHeight = 150.0),
                ExerciseCounter.Exercise.dip, true, side, reference.referenceEdge))
            val wrongHeight = AutomaticApparatusReference()
            for (seconds in listOf(0.0, .3, .6, 1.0, 1.3))
                assertNull(wrongHeight.observe(pose(seconds, rightHeight = 150.0), ExerciseCounter.Exercise.dip, side, rails()))
            assertNotEquals(AutomaticPullUpBar.State.locked, wrongHeight.state)
        }
    }

    @Test fun broaderContactSearchCannotTurnAnOverheadHangIntoDipIdentity() {
        for (seconds in listOf(0.0, .3, .6, 1.0)) {
            val hang = CounterFixtures.bilateral(seconds, 170.0, 170.0)
            assertNotNull(AutomaticApparatusReference.search(hang, ExerciseCounter.Exercise.dip))
            assertNull(AutomaticDipRails.grip(hang))
            assertEquals(ExerciseCounter.Exercise.pullUp, AutomaticExerciseDetection.apparatusCandidate(hang))
            assertEquals(ExerciseCounter.Exercise.pullUp, AutomaticExerciseDetection.candidate(hang))
            assertFalse(WorkoutStartEvidence.hasStartingPose(hang, ExerciseCounter.Exercise.dip, true,
                ArmMeasurement.Side.left, CounterFixtures.edge))
        }
    }

    @Test fun missingArmOrHandStillCannotProvideContactEvidence() {
        val full = pose(0.0, deep = true)
        for (joint in listOf(PoseJoint.leftWrist, PoseJoint.rightWrist, PoseJoint.leftElbow, PoseJoint.rightElbow)) {
            val missing = full.copy(people = full.people.map { PoseObservation(it.landmarks.filter { point -> point.joint != joint }) })
            assertNull(AutomaticDipRails.apparatusGrip(missing))
            assertNull(AutomaticApparatusReference.search(missing, ExerciseCounter.Exercise.dip))
        }
        assertNull(AutomaticDipRails.apparatusGrip(full.copy(people = full.people + full.people)))
        assertNull(AutomaticDipRails.apparatusGrip(full.copy(timestamp = PresentationTime(0, 0))))
    }

    @Test fun liveAutoRetainsObservedDipContextAndAcquiresAcrossChangingShoulderHeight() {
        val runtime = WorkoutRuntime(automaticWorkouts = true)
        val attitude = PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)!!
        val patches = List(3) { StaticSceneStability.PatchTranslation(0.0, 0.0) }
        fun frame(seconds: Double, deep: Boolean) = WorkoutRuntime.FrameInput(pose(seconds, deep), rails(),
            attitude, seconds, patches, 0.0, seconds)
        for (seconds in listOf(0.0, .2, .4, .6)) runtime.observe(frame(seconds, false))
        assertEquals(ExerciseCounter.Exercise.dip, runtime.resolvedExercise)
        for (i in 4..10) {
            val input = frame(i * .2, true)
            if (runtime.confirmedBar == null) repeat(3) { assertNotNull(runtime.search(input.pose)) }
            assertEquals(LiveSetSession.Phase.idle, runtime.observe(input).phase)
            assertEquals(ExerciseCounter.Exercise.dip, runtime.resolvedExercise)
        }
        assertNotNull(runtime.confirmedBar, "Deep motion cannot restart actual apparatus dwell")
        runtime.observe(frame(2.1, false))
        runtime.observe(frame(2.219999, false))
        assertEquals(LiveSetSession.Phase.idle, runtime.snapshot.phase)
        runtime.observe(frame(2.22, false))
        assertEquals(LiveSetSession.Phase.running, runtime.snapshot.phase)
        assertEquals(2.1, runtime.session.firstSourceSeconds)
        assertEquals(0, runtime.session.observedMovements)
    }

    @Test fun deepContactCannotDiscoverIdentityOrCarryAnExpiredOrContradictedContext() {
        val empty = AutomaticExerciseDetection()
        for (seconds in listOf(0.0, .2, .4, .6, .8, 1.0)) {
            repeat(3) { assertNull(empty.searchCandidate(pose(seconds, true))) }
            assertNull(empty.observe(pose(seconds, true)))
            assertNull(empty.apparatusCandidate)
        }
        for (fault in listOf("gap", "reverse", "duplicate", "missing", "overhead", "size")) {
            val identity = AutomaticExerciseDetection()
            for (seconds in listOf(0.0, .2, .4, .6)) identity.observe(pose(seconds))
            assertEquals(ExerciseCounter.Exercise.dip, identity.exercise)
            val deep = pose(.8, true)
            val invalid = when (fault) {
                "gap" -> pose(1.0, true)
                "reverse" -> pose(.5, true)
                "duplicate" -> pose(.6, true)
                "missing" -> deep.copy(people = deep.people.map { PoseObservation(it.landmarks.filter { p -> p.joint != PoseJoint.rightWrist }) })
                "overhead" -> CounterFixtures.bilateral(.8, 90.0, 90.0)
                else -> deep.copy(imageSize = ImageSize(1100.0, 1100.0))
            }
            assertNull(identity.observe(invalid), fault)
            assertNotEquals(ExerciseCounter.Exercise.dip, identity.apparatusCandidate, fault)
            assertNull(identity.searchCandidate(pose(1.1, true)), fault)
        }
    }

    @Test fun selectedModeShoulderTransitionsRetainOnlyFreshHandsAndObservedRails() {
        for (side in ArmMeasurement.Side.entries) for (fault in listOf("none", "missing-wrist", "missing-edge")) {
            val runtime = WorkoutRuntime(selection = WorkoutExerciseSelection.dip, side = side, automaticWorkouts = true)
            val attitude = PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)!!
            for (seconds in listOf(0.0, .3, .6, .9, 1.2)) {
                var frame = pose(seconds, deep = seconds >= .6)
                var edges = rails()
                if (seconds == .6 && fault == "missing-wrist") {
                    val other = if (side == ArmMeasurement.Side.left) PoseJoint.rightWrist else PoseJoint.leftWrist
                    frame = frame.copy(people = frame.people.map { PoseObservation(it.landmarks.filter { p -> p.joint != other }) })
                }
                if (seconds == .6 && fault == "missing-edge") edges = listOf(edges[0], emptyList())
                val input = WorkoutRuntime.FrameInput(frame, edges, attitude, seconds,
                    List(3) { StaticSceneStability.PatchTranslation(0.0, 0.0) }, 0.0, seconds)
                assertEquals(LiveSetSession.Phase.idle, runtime.observe(input).phase, fault)
            }
            assertEquals(fault == "none", runtime.confirmedBar != null, fault)
            assertEquals(0, runtime.session.observedMovements)
        }
    }
}
