package dev.hanginthere.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class WorkoutReliabilityTests {
    @Test fun acquiredPullupStartsThroughMeasuredDiscoveryScoreDipWithoutLosingReference() {
        val exercise=ExerciseCounter.Exercise.pullUp
        val runtime=acquired(exercise)
        val bar=runtime.confirmedBar
        runtime.observe(input(pose(1_000_000,exercise),exercise).copy(paused=true))
        fun measured(t:Long)=pose(t,exercise).let { p -> p.copy(people=p.people.map { person ->
            PoseObservation(person.landmarks.map { if(it.joint==PoseJoint.rightWrist) it.copy(confidence=.39) else it })
        }) }
        for(t in listOf(1_100_000L,1_150_000,1_219_999,1_220_000)) {
            val p=measured(t)
            assertNull(AutomaticExerciseDetection.candidate(p))
            val snapshot=runtime.observe(input(p,exercise))
            assertEquals(bar,runtime.confirmedBar)
            assertEquals(exercise,snapshot.exercise)
            assertEquals(if(t==1_220_000L) LiveSetSession.Phase.running else LiveSetSession.Phase.idle,snapshot.phase)
            assertEquals(0,runtime.session.record.completedCount)
        }
    }
    @Test fun measurableArmsCannotDiscoverOrStartAnUnknownWorkoutWithoutQualifiedReference() {
        val exercise=ExerciseCounter.Exercise.pullUp
        val runtime=WorkoutRuntime(automaticWorkouts=true)
        for(t in 0L..2_000_000L step 100_000L) {
            val original=pose(t,exercise)
            val p=original.copy(people=original.people.map { person -> PoseObservation(person.landmarks.map {
                if(it.joint==PoseJoint.rightWrist) it.copy(confidence=.39) else it
            }) })
            runtime.observe(input(p,exercise))
            assertNull(runtime.resolvedExercise)
            assertNull(runtime.confirmedBar)
            assertEquals(LiveSetSession.Phase.idle,runtime.session.phase)
            assertEquals(0,runtime.session.record.completedCount)
        }
    }
    private val attitude = PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)!!
    private val patches = List(3) { StaticSceneStability.PatchTranslation(0.0, 0.0) }
    private fun pose(ticks: Long, exercise: ExerciseCounter.Exercise, missing: ArmMeasurement.Side? = null): PoseResult =
        CounterFixtures.bilateral(ticks / 1e6, 170.0, 170.0, exercise = exercise).let { original ->
            original.copy(timestamp = PresentationTime(ticks, 1_000_000), people = original.people.map { person ->
                PoseObservation(person.landmarks.filter { missing == null || it.joint !in missing.joints })
            })
        }
    private fun candidates(exercise: ExerciseCounter.Exercise) = if (exercise == ExerciseCounter.Exercise.pullUp)
        listOf(listOf(BarLineCandidate(CounterFixtures.edge, 800.0)))
    else listOf(300.0, 700.0).map { x -> listOf(x - 10, x).map {
        BarLineCandidate(BarSegment(Point2D(it, 500.0), Point2D(it, 620.0)), 120.0)
    } }
    private fun input(pose: PoseResult, exercise: ExerciseCounter.Exercise) = WorkoutRuntime.FrameInput(
        pose, candidates(exercise), attitude, pose.timestamp.seconds, patches, 0.0, pose.timestamp.seconds)
    private fun acquired(exercise: ExerciseCounter.Exercise): WorkoutRuntime {
        val runtime = WorkoutRuntime(automaticWorkouts = true)
        for (ticks in listOf(0L, 300_000, 600_000, 900_000)) runtime.observe(input(pose(ticks, exercise), exercise))
        assertNotNull(runtime.confirmedBar)
        assertEquals(exercise, runtime.resolvedExercise)
        assertEquals(LiveSetSession.Phase.idle, runtime.snapshot.phase)
        return runtime
    }

    @Test fun briefOneArmLossKeepsObservedSetupButCannotStartOrEarnReadiness() {
        for (exercise in ExerciseCounter.Exercise.entries) for (missing in ArmMeasurement.Side.entries) {
            val runtime = acquired(exercise)
            val original = runtime.confirmedBar
            val paused = runtime.observe(input(pose(1_000_000, exercise, missing), exercise))
            assertEquals(original, runtime.confirmedBar)
            assertEquals(exercise, paused.exercise)
            assertEquals(if (exercise == ExerciseCounter.Exercise.dip && missing == ArmMeasurement.Side.right) null
                else "framingIncomplete", paused.setupIssue)
            assertEquals(LiveSetSession.Phase.idle, paused.phase)
            assertEquals(0, paused.observedMovements)
            assertEquals(LiveSetSession.Phase.idle, runtime.observe(input(pose(1_100_000, exercise), exercise)).phase)
            assertEquals(LiveSetSession.Phase.idle, runtime.observe(input(pose(1_219_999, exercise), exercise)).phase)
            assertEquals(LiveSetSession.Phase.running, runtime.observe(input(pose(1_220_000, exercise), exercise)).phase)
            assertEquals(0, runtime.session.record.completedCount)
        }
    }

    @Test fun recoveryExpiresFromLastBilateralEvidenceAndCannotRenewItself() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val runtime = acquired(exercise)
            for (ticks in listOf(1_000_000L, 1_100_000, 1_250_000)) {
                runtime.observe(input(pose(ticks, exercise, ArmMeasurement.Side.right), exercise))
                assertNotNull(runtime.confirmedBar)
            }
            runtime.observe(input(pose(1_250_001, exercise, ArmMeasurement.Side.right), exercise))
            assertNull(runtime.confirmedBar)
            assertNull(runtime.resolvedExercise)
            assertEquals(LiveSetSession.Phase.idle, runtime.snapshot.phase)
        }
    }

    @Test fun unqualifiedIdentityCannotEarnDwellThroughAnOccludedFrame() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val runtime = WorkoutRuntime(automaticWorkouts = true)
            for (ticks in listOf(0L, 200_000, 400_000)) runtime.observe(input(pose(ticks, exercise), exercise))
            runtime.observe(input(pose(500_000, exercise, ArmMeasurement.Side.right), exercise))
            runtime.observe(input(pose(600_000, exercise), exercise))
            assertNull(runtime.resolvedExercise)
            assertNull(runtime.confirmedBar)
            assertEquals(LiveSetSession.Phase.idle, runtime.snapshot.phase)
        }
    }

    @Test fun qualifiedRecoveryPreservesApparatusProgressWithoutAddingAnObservation() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val runtime = WorkoutRuntime(automaticWorkouts = true)
            for (ticks in listOf(0L, 200_000, 400_000, 600_000)) runtime.observe(input(pose(ticks, exercise), exercise))
            assertEquals(exercise, runtime.resolvedExercise)
            assertNull(runtime.confirmedBar)
            val before = assertNotNull(runtime.referenceStabilityDiagnostic)
            runtime.observe(input(pose(700_000, exercise, ArmMeasurement.Side.right), exercise))
            assertEquals(before, runtime.referenceStabilityDiagnostic)
            assertEquals(LiveSetSession.Phase.idle, runtime.snapshot.phase)
            for (ticks in listOf(800_000L, 1_100_000)) runtime.observe(input(pose(ticks, exercise), exercise))
            assertNotNull(runtime.confirmedBar)
            assertEquals(4, runtime.referenceStabilityDiagnostic!!.observations)
            assertEquals(0, runtime.session.record.completedCount)
        }
    }

    @Test fun recoveryCannotBridgeCrowdsEmptyScenesMovedGripOrSourceFaults() {
        for (exercise in ExerciseCounter.Exercise.entries) for (fault in 0..6) {
            val runtime = acquired(exercise)
            val oneArm = pose(1_000_000, exercise, ArmMeasurement.Side.right)
            val invalid = when (fault) {
                0 -> oneArm.copy(people = emptyList())
                1 -> oneArm.copy(people = oneArm.people + oneArm.people)
                2 -> oneArm.copy(imageSize = ImageSize(900.0, 1000.0))
                3 -> oneArm.copy(timestamp = PresentationTime(900_000, 1_000_000))
                4 -> oneArm.copy(timestamp = PresentationTime(1, 0))
                5 -> oneArm.copy(people = oneArm.people.map { person -> PoseObservation(person.landmarks.map {
                    it.copy(position = it.position.copy(x = it.position.x + 100))
                }) })
                else -> pose(1_000_000, if (exercise == ExerciseCounter.Exercise.pullUp) ExerciseCounter.Exercise.dip else ExerciseCounter.Exercise.pullUp,
                    ArmMeasurement.Side.right)
            }
            runtime.observe(input(invalid, exercise))
            assertNull(runtime.confirmedBar, "$exercise fault $fault")
            assertEquals(0, runtime.session.record.completedCount)
        }
    }

    private fun sample(time: Double, left: Double?, right: Double? = left) = MotionSample(time, left, right, null, true, false)

    @Test fun pullupReleaseCannotCompleteAnAcceptedAttemptDuringBoundaryDwell() {
        for (returnOnReleaseFrame in listOf(false, true)) {
            val analysis = WorkoutAnalysis("released-return", ExerciseCounter.Exercise.pullUp)
            analysis.observe(sample(0.0, 170.0), ExerciseCounter.Phase.ready, null)
            analysis.observe(sample(.1, 130.0), ExerciseCounter.Phase.outbound, null)
            analysis.observe(sample(.25, 80.0), ExerciseCounter.Phase.returning,
                ExerciseCounter.Event(ExerciseCounter.Outcome.movement, .25, "observedTop"))
            analysis.observe(sample(.4, 130.0).copy(released = true, postureReleased = true),
                if (returnOnReleaseFrame) ExerciseCounter.Phase.ready else ExerciseCounter.Phase.returning, null)
            // Even a subsequent transient false release flag cannot make the
            // old accepted attempt complete when standing with straight arms.
            analysis.observe(sample(.55, 170.0), ExerciseCounter.Phase.ready, null)
            val rep = analysis.records.single()
            assertEquals(.25, rep.acceptedAt)
            assertNull(rep.returnedAt)
            assertFalse(rep.isCompleteForAnalytics)
            assertEquals("disengaged", rep.closeReason)
        }
    }

    @Test fun pullupHeldReturnRemainsCompleteWhenReleaseFollows() {
        val analysis = WorkoutAnalysis("held-return", ExerciseCounter.Exercise.pullUp)
        analysis.observe(sample(0.0, 170.0), ExerciseCounter.Phase.ready, null)
        analysis.observe(sample(.1, 130.0), ExerciseCounter.Phase.outbound, null)
        analysis.observe(sample(.25, 80.0), ExerciseCounter.Phase.returning,
            ExerciseCounter.Event(ExerciseCounter.Outcome.movement, .25, "observedTop"))
        analysis.observe(sample(.4, 170.0), ExerciseCounter.Phase.ready, null)
        analysis.observe(sample(.55, 170.0).copy(released = true), ExerciseCounter.Phase.ready, null)
        val rep = analysis.records.single()
        assertTrue(rep.isCompleteForAnalytics)
        assertEquals(.4, rep.returnedAt)
        assertEquals("returned", rep.closeReason)
    }

    @Test fun missingPeakArmCannotTurnLaterReturnIntoPreciseWholeRepTiming() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val analysis = WorkoutAnalysis("occluded-peak", exercise)
            analysis.observe(sample(0.0, 170.0), ExerciseCounter.Phase.ready, null)
            analysis.observe(sample(.1, 130.0), ExerciseCounter.Phase.outbound, null)
            analysis.observe(sample(.2, 80.0, null), ExerciseCounter.Phase.returning, null)
            analysis.observe(sample(.35, 80.0, null), ExerciseCounter.Phase.returning,
                if (exercise == ExerciseCounter.Exercise.pullUp) ExerciseCounter.Event(ExerciseCounter.Outcome.movement, .35, "observed") else null)
            analysis.observe(sample(.5, 120.0), ExerciseCounter.Phase.returning, null)
            analysis.observe(sample(.65, 170.0), ExerciseCounter.Phase.returning, null)
            analysis.observe(sample(.8, 170.0), ExerciseCounter.Phase.ready,
                if (exercise == ExerciseCounter.Exercise.dip) ExerciseCounter.Event(ExerciseCounter.Outcome.movement, .8, "observed") else null)
            val rep = analysis.records.single()
            assertTrue(rep.isCompleteForAnalytics)
            assertNull(rep.extremeAt)
            assertNull(rep.outboundSeconds)
            assertNull(rep.returnSeconds)
            assertEquals(80.0, rep.left.minimum)
            assertEquals(120.0, rep.right.minimum)
            assertEquals(.8, rep.returnedAt)
        }
    }

    @Test fun highAverageCoverageCannotInventBilateralPeakOffsetAcrossABriefOcclusion() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val analysis = WorkoutAnalysis("brief-peak-gap", exercise)
            analysis.observe(sample(0.0, 170.0), ExerciseCounter.Phase.ready, null)
            for (i in 1..1000) {
                val time = i / 1000.0
                val angle = 80.0 + kotlin.math.abs(time - .5) * 180
                val phase = if (i == 1000) ExerciseCounter.Phase.ready else if (i < 550) ExerciseCounter.Phase.outbound else ExerciseCounter.Phase.returning
                val accepted = if (exercise == ExerciseCounter.Exercise.pullUp) i == 550 else i == 1000
                analysis.observe(sample(time, angle, angle.takeUnless { i == 500 }), phase,
                    if (accepted) ExerciseCounter.Event(ExerciseCounter.Outcome.movement, time, "observed") else null)
            }
            val rep = analysis.records.single()
            assertTrue(rep.isCompleteForAnalytics)
            assertTrue(rep.left.coverage!! > .99 && rep.right.coverage!! > .99)
            assertNull(rep.extremeAt)
            assertNull(rep.bilateralExtremeOffsetSeconds)
        }
    }

    @Test fun losingAnArmMidReturnCannotRelabelMidrangeAsEndpointHold() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val analysis = WorkoutPhaseAnalysis("mid-return", exercise)
            analysis.observe(sample(0.0, 170.0), ExerciseCounter.Phase.ready)
            analysis.observe(sample(.1, 130.0), ExerciseCounter.Phase.outbound)
            analysis.observe(sample(.2, 80.0), ExerciseCounter.Phase.returning)
            analysis.observe(sample(.3, 100.0), ExerciseCounter.Phase.returning)
            analysis.observe(sample(.4, 130.0), ExerciseCounter.Phase.returning)
            for (i in 5..9) analysis.observe(sample(i * .1, 130.0, null), ExerciseCounter.Phase.returning)
            assertEquals(WorkoutMotionPhase.effortHold, analysis.activity.phases.last().phase)
            assertEquals(0.0, analysis.activity.seconds(if (exercise == ExerciseCounter.Exercise.pullUp)
                WorkoutMotionPhase.peakHold else WorkoutMotionPhase.bottomHold))
            for (i in 10..14) analysis.observe(sample(i * .1, 130.0), ExerciseCounter.Phase.returning)
            assertEquals(WorkoutMotionPhase.effortHold, analysis.activity.phases.last().phase)
            for (i in 15..19) analysis.observe(sample(i * .1, 80.0), ExerciseCounter.Phase.returning)
            assertEquals(if (exercise == ExerciseCounter.Exercise.pullUp) WorkoutMotionPhase.peakHold else WorkoutMotionPhase.bottomHold,
                analysis.activity.phases.last().phase)
        }
    }

    @Test fun unseenEndpointCannotBeEstablishedByReappearingArmsMidReturn() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val analysis = WorkoutPhaseAnalysis("unseen-peak", exercise)
            analysis.observe(sample(0.0, 170.0), ExerciseCounter.Phase.ready)
            analysis.observe(sample(.1, 130.0), ExerciseCounter.Phase.outbound)
            analysis.observe(sample(.2, 80.0, null), ExerciseCounter.Phase.returning)
            for (i in 3..9) analysis.observe(sample(i * .1, 120.0), ExerciseCounter.Phase.returning)
            assertEquals(WorkoutMotionPhase.effortHold, analysis.activity.phases.last().phase)
        }
    }

    @Test fun endpointDwellCannotForgetTheDeeperObservedOutboundExtreme() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val analysis = WorkoutPhaseAnalysis("outbound-peak", exercise)
            analysis.observe(sample(0.0, 170.0), ExerciseCounter.Phase.ready)
            analysis.observe(sample(.1, 130.0), ExerciseCounter.Phase.outbound)
            analysis.observe(sample(.2, 60.0), ExerciseCounter.Phase.outbound)
            for (i in 3..9) analysis.observe(sample(i * .1, 80.0), ExerciseCounter.Phase.returning)
            assertEquals(WorkoutMotionPhase.effortHold, analysis.activity.phases.last().phase)
        }
    }

    @Test fun retainedSetupCannotOverrideIndependentPhoneMovementEvidence() {
        val moved = PhoneOrientationStability.Quaternion.create(.1, 0.0, 0.0, 1.0)!!
        for (exercise in ExerciseCounter.Exercise.entries) {
            val runtime = acquired(exercise)
            for (ticks in listOf(1_000_000L, 1_250_000)) runtime.observe(
                input(pose(ticks, exercise, ArmMeasurement.Side.right), exercise).copy(attitude = moved))
            assertNull(runtime.confirmedBar)
            assertEquals("phoneMoved", runtime.snapshot.setupIssue)
            assertEquals(0, runtime.session.record.completedCount)
        }
    }

    private fun measuredRecord(): WorkoutSetRecord {
        val session = LiveSetSession(); session.start(ExerciseCounter.Exercise.pullUp, ArmMeasurement.Side.left)
        for ((time, angle) in listOf(0.0 to 170.0, .15 to 170.0, .3 to 80.0, .45 to 80.0, .6 to 170.0, .75 to 170.0))
            session.consume(CounterFixtures.bilateral(time, angle, angle), CounterFixtures.edge)
        session.finish()
        return session.record
    }

    @Test fun measuredCurrentAndLegacyMetricsSurviveLibraryPersistenceAndBackup() {
        val current = measuredRecord()
        val activity = assertNotNull(current.activity)
        assertEquals(3, activity.metricVersion)
        assertEquals(2, current.reps.single().metricVersion)
        val legacy = (1..2).map { version -> current.copy(id = "legacy-$version",
            activity = activity.copy(metricVersion = version), reps = current.reps.map {
                it.copy(id = "legacy-$version:rep:1", setId = "legacy-$version", metricVersion = 1)
            }) }
        val records = legacy + current
        val library = WorkoutLibrary.register(WorkoutLibraryState(), records)
        val reloaded = WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(WorkoutRecordCodec.json.encodeToString(library))
        WorkoutLibrary.validate(reloaded)
        assertEquals(3, reloaded.entries.sumOf { it.measured.completedCount })
        val backup = WorkoutLibraryBackup.decode(WorkoutLibraryBackup.export(reloaded, records.map { WorkoutRecordCodec.summary(it) }))
        assertEquals(listOf(1, 2, 3), backup.measuredSummaries.map { it.activity!!.metricVersion }.sorted())
        assertEquals(legacy.map { it.activity }, backup.measuredSummaries.filter { it.id.startsWith("legacy") }.sortedBy { it.id }.map { it.activity })
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.register(WorkoutLibraryState(), listOf(current.copy(activity = activity.copy(metricVersion = 4)))) }
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.register(WorkoutLibraryState(), listOf(current.copy(reps = current.reps.map { it.copy(metricVersion = 3) }))) }
    }

    @Test fun benchmarkVersionsIdentifyCurrentMetricsAndPreserveExplicitLegacyRuns() {
        val current = WorkoutBenchmarkRun("current", "ios", "authored", "a".repeat(40))
        assertEquals(3, current.activityMetricVersion); assertEquals(2, current.repMetricVersion)
        for ((activity, rep) in listOf(1 to 1, 2 to 1, 3 to 2)) {
            val run = current.copy(activityMetricVersion = activity, repMetricVersion = rep)
            val prediction = WorkoutBenchmarkExport.prediction("b".repeat(64), run, emptyList())
            assertEquals(run, WorkoutRecordCodec.json.decodeFromString<WorkoutBenchmarkPrediction>(WorkoutRecordCodec.json.encodeToString(prediction)).run)
        }
        for ((activity, rep) in listOf(3 to 1, 2 to 2, 4 to 2, 3 to 3)) assertFailsWith<IllegalArgumentException> {
            WorkoutBenchmarkExport.prediction("b".repeat(64), current.copy(activityMetricVersion = activity, repMetricVersion = rep), emptyList())
        }
        val measured = measuredRecord()
        val legacy = measured.copy(activity = measured.activity!!.copy(metricVersion = 2), reps = measured.reps.map { it.copy(metricVersion = 1) })
        fun session(record: WorkoutSetRecord) = WorkoutBenchmarkExport.session("source", "c".repeat(64), listOf(0.0, 1.0),
            listOf(record), listOf(0.0), listOf(listOf(0.0, .75)))
        val currentSession = session(measured)
        val legacySession = session(legacy)
        val legacyRun = current.copy(activityMetricVersion = 2, repMetricVersion = 1)
        WorkoutBenchmarkExport.prediction("b".repeat(64), current, listOf(currentSession))
        WorkoutBenchmarkExport.prediction("b".repeat(64), legacyRun, listOf(legacySession))
        assertFailsWith<IllegalArgumentException> { WorkoutBenchmarkExport.prediction("b".repeat(64), current, listOf(legacySession)) }
        assertFailsWith<IllegalArgumentException> { WorkoutBenchmarkExport.prediction("b".repeat(64), legacyRun, listOf(currentSession)) }
        assertFailsWith<IllegalArgumentException> { WorkoutBenchmarkExport.session("mixed", "c".repeat(64), listOf(0.0, 2.0),
            listOf(measured, legacy), listOf(0.0, 1.0), listOf(listOf(0.0, .75), listOf(1.0, 1.75))) }
        val unbound = WorkoutRecordCodec.json.decodeFromString<WorkoutBenchmarkSession>(WorkoutRecordCodec.json.encodeToString(currentSession))
        assertFailsWith<IllegalArgumentException> { WorkoutBenchmarkExport.prediction("b".repeat(64), current, listOf(unbound)) }
    }
}
