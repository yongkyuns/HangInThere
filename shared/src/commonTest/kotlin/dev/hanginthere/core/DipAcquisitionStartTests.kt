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

    private fun single(seconds: Double, side: ArmMeasurement.Side, degrees: Double = 170.0): PoseResult =
        pose(seconds, degrees, if (side == ArmMeasurement.Side.left) ArmMeasurement.Side.right else ArmMeasurement.Side.left)
    private fun observed(side: ArmMeasurement.Side) = rails[if (side == ArmMeasurement.Side.left) 0 else 1]

    @Test fun changingVisibleEdgeLengthsCannotSwitchTheSameSingleRailWitness() {
        for (side in ArmMeasurement.Side.entries) {
            val policy = AutomaticSingleDipRail()
            val observed = observed(side)
            var bar: ConfirmedBar? = null
            for ((index, time) in listOf(0.0, .3, .6, .9).withIndex()) {
                val edges = observed.mapIndexed { edgeIndex, candidate ->
                    val length = if ((index + edgeIndex) % 2 == 0) 120.0 else 100.0
                    candidate.copy(edge = candidate.edge.copy(b = candidate.edge.b.copy(y = candidate.edge.a.y + length)),
                        geometryScore = length)
                }
                bar = policy.observe(single(time, side), side, edges)
                if (time < .9) assertNull(bar)
            }
            val reference = assertNotNull(bar)
            assertEquals(observed[0].edge.a.x, reference.referenceEdge.a.x)
            assertEquals(100.0, reference.referenceEdge.length)
        }
    }

    @Test fun retainedSingleEdgeCannotBypassFreshPairingOrAmbiguity() {
        for (side in ArmMeasurement.Side.entries) for (ambiguous in listOf(false, true)) {
            val policy = AutomaticSingleDipRail()
            val observed = observed(side)
            for (time in listOf(0.0, .3, .6)) assertNull(policy.observe(single(time, side), side, observed))
            val extra = observed.map { candidate -> candidate.copy(edge = BarSegment(
                candidate.edge.a.copy(x = candidate.edge.a.x + 40), candidate.edge.b.copy(x = candidate.edge.b.x + 40))) }
            assertNull(policy.observe(single(.9, side), side, if (ambiguous) observed + extra else observed.take(1)))
            for (time in listOf(1.0, 1.3, 1.6)) assertNull(policy.observe(single(time, side), side, observed))
            assertNotNull(policy.observe(single(1.9, side), side, observed))
        }
    }

    @Test fun singleMeasuredArmCanAcquireItsRealRailAndStartAutomatically() {
        for (side in ArmMeasurement.Side.entries) {
            val runtime = WorkoutRuntime(side = side, automaticWorkouts = true)
            val search = assertNotNull(runtime.search(single(0.0, side)))
            assertEquals(listOf(side), search.gripSides)
            assertEquals(1, search.hands.size); assertEquals(1, search.regions.size)
            for (i in 0..10) {
                val time = i * .2
                runtime.observe(WorkoutRuntime.FrameInput(single(time, side), listOf(observed(side)),
                    attitude, time, patches, 0.0, time))
            }
            val bar = assertNotNull(runtime.confirmedBar)
            assertTrue(observed(side).any { it.edge.ordered == bar.referenceEdge })
            assertNull(bar.oppositeEdge)
            assertEquals(LiveSetSession.Phase.running, runtime.snapshot.phase)
            assertEquals(0, runtime.session.observedMovements)
        }
    }

    @Test fun singleRailStillRequiresApparatusPhoneAndActualSceneEvidence() {
        for (side in ArmMeasurement.Side.entries) for (fault in listOf("noEdges", "unpaired", "phone", "scene")) {
            val runtime = WorkoutRuntime(side = side, automaticWorkouts = true)
            for (i in 0..20) {
                val time = i * .2
                val edges = when (fault) { "noEdges" -> emptyList(); "unpaired" -> observed(side).take(1); else -> observed(side) }
                val snapshot = runtime.observe(WorkoutRuntime.FrameInput(single(time, side), listOf(edges),
                    attitude.takeUnless { fault == "phone" }, time,
                    if (fault == "scene") patches.take(1) else patches, 0.0, time))
                assertEquals(LiveSetSession.Phase.idle, snapshot.phase, fault)
                assertEquals(0, snapshot.observedMovements, fault)
            }
            assertEquals(fault == "scene", runtime.confirmedBar != null, fault)
        }
    }

    @Test fun singleFlexedContactCannotProvideStartingExtension() {
        for (side in ArmMeasurement.Side.entries) {
            val runtime = WorkoutRuntime(side = side, automaticWorkouts = true)
            fun frame(time: Double, angle: Double) = WorkoutRuntime.FrameInput(single(time, side, angle),
                listOf(observed(side)), attitude, time, patches, 0.0, time)
            for (i in 0..9) assertEquals(LiveSetSession.Phase.idle, runtime.observe(frame(i * .2, 100.0)).phase)
            assertNotNull(runtime.confirmedBar); assertEquals(ExerciseCounter.Exercise.dip, runtime.resolvedExercise)
            runtime.observe(frame(2.0, 170.0)); runtime.observe(frame(2.119999, 170.0))
            assertEquals(LiveSetSession.Phase.idle, runtime.snapshot.phase)
            runtime.observe(frame(2.12, 170.0))
            assertEquals(LiveSetSession.Phase.running, runtime.snapshot.phase)
            assertEquals(2.0, runtime.session.firstSourceSeconds)
            assertEquals(0, runtime.session.observedMovements)
        }
    }

    @Test fun singleModeCannotBypassBilateralAmbiguityOrUseAnUnselectedArm() {
        for (side in ArmMeasurement.Side.entries) {
            for (wrongSide in listOf(false, true)) {
                val policy = AutomaticSingleDipRail()
                for (time in listOf(0.0, .3, .6, .9, 1.2))
                    assertNull(policy.observe(if (wrongSide) single(time, side) else pose(time, 170.0),
                        if (wrongSide) if (side == ArmMeasurement.Side.left) ArmMeasurement.Side.right else ArmMeasurement.Side.left else side,
                        observed(side)))
                assertNotEquals(AutomaticPullUpBar.State.locked, policy.state)
            }
        }
    }

    @Test fun malformedHiddenEvidenceCannotMasqueradeAsOcclusion() {
        val base = single(0.0, ArmMeasurement.Side.left)
        val hidden = Landmark(PoseJoint.rightShoulder, Point2D(700.0, 300.0), .1)
        val invalid = listOf(hidden.copy(confidence = Double.NaN), hidden.copy(confidence = 1.01),
            hidden.copy(position = Point2D(-1.0, 300.0)), hidden.copy(position = Point2D(700.0, Double.NaN)))
        for (bad in invalid) {
            val frame = base.copy(people = base.people.map { it.copy(landmarks = it.landmarks + bad) })
            assertNull(AutomaticApparatusReference.search(frame, ExerciseCounter.Exercise.dip))
            assertNull(AutomaticExerciseDetection.apparatusCandidate(frame))
        }
        val duplicate = base.copy(people = base.people.map { it.copy(landmarks = it.landmarks + hidden + hidden) })
        assertNull(AutomaticApparatusReference.search(duplicate, ExerciseCounter.Exercise.dip))
    }

    @Test fun distinctEligibleTubesOrEdgesAboveTheGripCannotConfirmASingleRail() {
        val side = ArmMeasurement.Side.left
        val second = observed(side).map { it.copy(edge = BarSegment(it.edge.a.copy(x = it.edge.a.x + 50), it.edge.b.copy(x = it.edge.b.x + 50))) }
        val above = observed(side).map { it.copy(edge = BarSegment(it.edge.a.copy(y = 495.0), it.edge.b.copy(y = 615.0))) }
        for (candidates in listOf(observed(side) + second, above)) {
            val acquisition = AutomaticApparatusReference()
            for (time in listOf(0.0, .3, .6, .9, 1.2))
                assertNull(acquisition.observe(single(time, side), ExerciseCounter.Exercise.dip, side, listOf(candidates)))
            assertNotEquals(AutomaticPullUpBar.State.locked, acquisition.state)
        }
    }

    @Test fun changingWitnessModeCannotBorrowEarlierReferenceOrIdentityDwell() {
        val policy = AutomaticApparatusReference(); val identity = AutomaticExerciseDetection()
        for (time in listOf(0.0, .3, .6)) {
            assertNull(policy.observe(pose(time, 170.0), ExerciseCounter.Exercise.dip, ArmMeasurement.Side.left, rails))
            identity.observe(pose(time, 170.0))
        }
        for (time in listOf(.9, 1.1, 1.3, 1.5)) {
            assertNull(policy.observe(single(time, ArmMeasurement.Side.left), ExerciseCounter.Exercise.dip,
                ArmMeasurement.Side.left, listOf(observed(ArmMeasurement.Side.left))))
            val result = identity.observe(single(time, ArmMeasurement.Side.left))
            assertEquals(if (time == 1.5) ExerciseCounter.Exercise.dip else null, result)
        }
        assertNotNull(policy.observe(single(1.8, ArmMeasurement.Side.left), ExerciseCounter.Exercise.dip,
            ArmMeasurement.Side.left, listOf(observed(ArmMeasurement.Side.left))))
    }

    @Test fun singleReferenceClockRegressionCannotLowerItsHighWater() {
        val side = ArmMeasurement.Side.left; val policy = AutomaticSingleDipRail()
        for (time in listOf(1.0, 1.3, 1.6, .1, .4, .7, 1.0, 1.3, 1.6))
            assertNull(policy.observe(single(time, side), side, observed(side)))
        for (time in listOf(1.7, 2.0, 2.3)) assertNull(policy.observe(single(time, side), side, observed(side)))
        assertNotNull(policy.observe(single(2.6, side), side, observed(side)))
    }

    @Test fun singleReferenceNeedsFreshDwellAfterProviderOrRevisionChanges() {
        for (revisionChanged in listOf(false, true)) {
            val side = ArmMeasurement.Side.left; val policy = AutomaticSingleDipRail()
            for (time in listOf(0.0, .3, .6)) assertNull(policy.observe(single(time, side), side, observed(side)))
            for (time in listOf(.9, 1.2, 1.5, 1.8)) {
                val original = single(time, side)
                val changed = original.copy(backend = if (revisionChanged) original.backend else "new provider",
                    requestRevision = original.requestRevision + if (revisionChanged) 1 else 0)
                assertEquals(time == 1.8, policy.observe(changed, side, observed(side)) != null)
            }
        }
    }

    @Test fun acquisitionOwnerKeepsSourceClockAcrossModeAndProviderChanges() {
        for (changeProvider in listOf(false, true)) {
            val side = ArmMeasurement.Side.left; val policy = AutomaticApparatusReference()
            fun frame(time: Double, changed: Boolean = false) = single(time, side).let {
                if (changed) it.copy(backend = "new provider") else it
            }
            for (time in listOf(1.0, 1.3, 1.6))
                assertNull(policy.observe(frame(time), ExerciseCounter.Exercise.dip, side, listOf(observed(side))))
            assertNull(policy.observe(pose(1.7, 170.0), ExerciseCounter.Exercise.dip, side, rails))
            policy.invalidateEvidence() // Ordinary cancellation must retain the source clock.
            for (time in listOf(.1, .4, .7, 1.0, 1.3, 1.6))
                assertNull(policy.observe(frame(time, changeProvider), ExerciseCounter.Exercise.dip, side, listOf(observed(side))))
            for (time in listOf(1.8, 2.1, 2.4, 2.7))
                assertEquals(time == 2.7, policy.observe(frame(time, changeProvider), ExerciseCounter.Exercise.dip,
                    side, listOf(observed(side))) != null)
            policy.reset() // Explicit activation of a new source permits a fresh timestamp epoch.
            for (time in listOf(0.0, .3, .6, .9))
                assertEquals(time == .9, policy.observe(frame(time), ExerciseCounter.Exercise.dip,
                    side, listOf(observed(side))) != null)
        }
    }

    @Test fun missingOppositeElbowCannotHideClearlyOverheadContact() {
        for (side in ArmMeasurement.Side.entries) for (overhead in listOf(false, true)) {
            val opposite = if (side == ArmMeasurement.Side.left) ArmMeasurement.Side.right else ArmMeasurement.Side.left
            val runtime = WorkoutRuntime(side = side, automaticWorkouts = true)
            for (i in 0..20) {
                val time = i * .2
                val original = single(time, side)
                val partial = listOf(Landmark(opposite.joints[0], Point2D(600.0, 300.0), 1.0),
                    Landmark(opposite.joints[2], Point2D(600.0, if (overhead) 200.0 else 500.0), 1.0))
                val frame = original.copy(people = original.people.map { it.copy(landmarks = it.landmarks + partial) })
                assertEquals(!overhead, AutomaticSingleDipRail.contact(frame) != null)
                assertEquals(!overhead, AutomaticApparatusReference.search(frame, ExerciseCounter.Exercise.dip) != null)
                assertEquals(if (overhead) null else ExerciseCounter.Exercise.dip, AutomaticExerciseDetection.candidate(frame))
                runtime.observe(WorkoutRuntime.FrameInput(frame, listOf(observed(side)), attitude, time, patches, 0.0, time))
            }
            assertEquals(if (overhead) LiveSetSession.Phase.idle else LiveSetSession.Phase.running, runtime.snapshot.phase)
            assertEquals(0, runtime.session.observedMovements)
        }
    }

    @Test fun runtimeCancellationCannotTurnOldFramesIntoFreshSingleRailDwell() {
        val side = ArmMeasurement.Side.left; val runtime = WorkoutRuntime(side = side, automaticWorkouts = true)
        fun frame(time: Double, edges: Boolean = true) = WorkoutRuntime.FrameInput(single(time, side),
            if (edges) listOf(observed(side)) else emptyList(), attitude, time, patches, 0.0, time)
        for (time in listOf(1.0, 1.3, 1.6)) runtime.observe(frame(time))
        assertNull(runtime.confirmedBar)
        runtime.observe(frame(2.0, edges = false)) // Latest source frame need not have contour results.
        for (time in listOf(.1, .4, .7, 1.0, 1.3, 1.6, 1.9, 2.0)) {
            val snapshot = runtime.observe(frame(time))
            assertEquals("sourceTimestampInvalid", snapshot.setupIssue)
            assertNull(runtime.confirmedBar); assertNull(runtime.resolvedExercise)
            assertEquals(LiveSetSession.Phase.idle, snapshot.phase)
        }
        // Remain within identity continuity and above the .3s contour cadence.
        for (time in listOf(2.1, 2.41, 2.72, 3.03))
            assertEquals(time == 3.03, runtime.observe(frame(time)).referenceEdge != null)
        assertEquals(0, runtime.session.observedMovements)
    }

    @Test fun runtimeSourceFenceDistinguishesTicksWhoseAbsoluteSecondsCollide() {
        val runtime = WorkoutRuntime(selection = WorkoutExerciseSelection.dip)
        val first = PresentationTime(9_007_199_254_740_992, 1_000)
        val next = first.copy(value = first.value + 1)
        assertEquals(first.seconds, next.seconds)
        fun observe(time: PresentationTime) = runtime.observe(WorkoutRuntime.FrameInput(
            single(0.0, ArmMeasurement.Side.left).copy(timestamp = time)))
        assertNotEquals("sourceTimestampInvalid", observe(first).setupIssue)
        assertNotEquals("sourceTimestampInvalid", observe(next).setupIssue)
        assertEquals("sourceTimestampInvalid", observe(next).setupIssue)
        assertEquals("sourceTimestampInvalid", observe(first).setupIssue)
        assertNotEquals("sourceTimestampInvalid", observe(next.copy(value = next.value + 1)).setupIssue)
    }

    @Test fun asyncSearchCannotMatchAChangedAnatomicalSideAtTheSamePixels() {
        val frame = single(0.0, ArmMeasurement.Side.left)
        val first = assertNotNull(AutomaticApparatusReference.search(frame, ExerciseCounter.Exercise.dip))
        val changed = frame.copy(people = frame.people.map { person -> person.copy(landmarks = person.landmarks.map {
            val index = ArmMeasurement.Side.left.joints.indexOf(it.joint)
            it.copy(joint = ArmMeasurement.Side.right.joints[index])
        }) })
        val next = assertNotNull(AutomaticApparatusReference.search(changed, ExerciseCounter.Exercise.dip))
        assertEquals(first.hands, next.hands)
        assertFalse(first.matches(next, frame.imageSize))
    }

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
        assertTrue(ready(pose(0.0, 170.0, ArmMeasurement.Side.right), true))
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
