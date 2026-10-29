package dev.hanginthere.core

import kotlin.test.*

class RelativeDipWorkoutTests {
    @Test fun partialOppositeArmStillCommitsAndPersistsMeasuredBodyCycle() {
        val run = Run()
        for (pose in frames()) {
            val current = if (pose.timestamp.value < 1080) pose else pose.copy(people = pose.people.map { person ->
                person.copy(landmarks = person.landmarks.filterNot { it.joint == PoseJoint.rightWrist })
            })
            val snapshot = run.observe(current)
            if (pose.timestamp.value >= 1080) {
                assertEquals(listOf(0), snapshot.motion.currentArmSlots)
                assertNull(snapshot.motion.elbowDegrees[1])
                assertNotNull(snapshot.motion.elbowDegrees[0])
            }
            assertEquals(snapshot.motion.cycles, run.workout.record(true).completedCount)
        }
        assertTrue(run.workout.hasStarted)
        assertEquals(1, run.workout.record().completedCount)
        run.workout.finish("userStopped")
        val bridge = WorkoutLibraryBridge()
        val state = bridge.registerJson(bridge.emptyJson(), WorkoutRecordCodec.json.encodeToString(listOf(run.workout.record())))
        val restored = bridge.restoreJson(bridge.emptyJson(), bridge.exportJson(state, null, null))
        assertEquals(1, WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(restored).entries.single().measured.completedCount)
    }
    private fun frames() = (0L..600L step 40).map { BodyDipFixtures.pose(it) } + BodyDipFixtures.cycle(640)
    private class Run {
        val selection = BodyAthleteSelection()
        val workout = RelativeDipWorkout("relative")
        fun observe(p: PoseResult, camera: DipCycleMotion.Camera = DipCycleMotion.Camera.ready,
            automaticStart: Boolean = true): RelativeDipWorkout.Snapshot {
            selection.observe(p)
            return workout.observe(selection, camera, automaticStart = automaticStart)
        }
    }
    @Test fun oneOwnerCommitsARealExcursionAndKeepsLiveStoredCountsEqual() {
        val run = Run()
        for(p in frames()) {
            val s = run.observe(p)
            if(p.timestamp.value < 1080) assertFalse(s.started)
            assertEquals(s.motion.cycles, run.workout.record(true).completedCount)
        }
        assertTrue(run.workout.hasStarted)
        assertEquals(1, run.workout.record().completedCount)
        assertEquals(BilateralTorsoDipMotion.countMethod, run.workout.record().countMethod)
        assertNotEquals(BodyDipMotion.countMethod, run.workout.record().countMethod)
    }
    @Test fun staticShallowAndHorizontalSetupCannotPublishAnAutomaticSet() {
        for(kind in listOf("static", "shallow", "horizontal")) {
            val run = Run()
            for(p in frames()) {
                val lower = p.timestamp.value in 1080..1320
                val value = when(kind) {
                    "static" -> BodyDipFixtures.pose(p.timestamp.value)
                    "shallow" -> BodyDipFixtures.pose(p.timestamp.value, if(lower)40.0 else 0.0, if(lower)150.0 else 170.0)
                    else -> p.copy(people = p.people.map { person -> person.copy(landmarks = person.landmarks + listOf(
                        Landmark(PoseJoint.leftHip, Point2D(800.0, 300.0), .9),
                        Landmark(PoseJoint.rightHip, Point2D(880.0, 300.0), .9))) })
                }
                assertFalse(run.observe(value).started, kind)
                assertNull(run.workout.draftDeltaJson(), kind)
            }
            assertEquals(0, run.workout.record(true).completedCount, kind)
        }
    }
    @Test fun manualPreparationCannotBankRepsBeforeTheTap() {
        val run = Run()
        for(p in frames().filter { it.timestamp.value <= 1040 }) run.observe(p, automaticStart = false)
        assertFalse(run.workout.hasStarted)
        assertTrue(run.workout.startManually())
        assertNotNull(run.workout.draftDeltaJson())
        assertEquals(0, run.workout.record(true).completedCount)
        for(p in frames().filter { it.timestamp.value > 1040 }) run.observe(p, automaticStart = false)
        assertEquals(1, run.workout.record().completedCount)
        val preview = Run()
        frames().forEach { preview.observe(it, automaticStart = false) }
        assertFalse(preview.workout.hasStarted); assertEquals(0, preview.workout.record(true).completedCount)
    }
    @Test fun naturalReleaseRegistersTheNewMethodAndPreservesLegacyRecords() {
        val run = Run(); frames().forEach { run.observe(it) }
        for(ms in 1680L..2680L step 40) {
            if(run.workout.isFinished)break
            run.observe(BodyDipFixtures.shifted(BodyDipFixtures.pose(ms)))
        }
        val current = run.workout.record()
        assertEquals("disengaged", current.endReason); assertEquals(1, current.completedCount)
        val old = BodyDipWorkout("legacy")
        BodyDipFixtures.cycle().forEach { old.observe(it, BodyDipMotion.Camera.ready) }; old.finish("userStopped")
        WorkoutLibrary.validate(WorkoutLibraryState(entries = listOf(
            WorkoutLibraryEntry(WorkoutLibrary.compact(current)), WorkoutLibraryEntry(WorkoutLibrary.compact(old.record())))))
        assertEquals(4, current.boundaryPolicyVersion)
        assertTrue(current.reps.all { it.countMethod == current.countMethod })
        assertTrue(WorkoutCountingMethod.isSupportedDip(current.countMethod, current.countPolicyVersion))
        assertFalse(WorkoutCountingMethod.isSupportedDip(current.countMethod, 3))
        assertTrue(WorkoutCountingMethod.isSupportedDip(current.countMethod, 1))
        assertFalse(WorkoutCountingMethod.isSupportedDip("experimentalBilateralTorsoDipMotion", 3))
        val previousRelative = current.copy(id = "previous-relative", boundaryPolicyVersion = 2,
            countMethod = DipCycleMotion.countMethod, countPolicyVersion = DipCycleMotion.policyVersion,
            reps = current.reps.map { it.copy(id = "previous-relative-${it.ordinal}", setId = "previous-relative",
                countMethod = DipCycleMotion.countMethod, countPolicyVersion = DipCycleMotion.policyVersion) })
        val previousTorso = current.copy(id = "previous-torso", countPolicyVersion = 1, boundaryPolicyVersion = 3,
            reps = current.reps.map { it.copy(id = "previous-torso-${it.ordinal}", setId = "previous-torso", countPolicyVersion = 1) })
        val previousBoundary=current.copy(id="previous-boundary",boundaryPolicyVersion=3,
            reps=current.reps.map { it.copy(id="previous-boundary-${it.ordinal}",setId="previous-boundary") })
        val records = listOf(current, previousRelative, old.record(), previousTorso,previousBoundary)
        val bridge = WorkoutLibraryBridge()
        val state = bridge.registerJson(bridge.emptyJson(), WorkoutRecordCodec.json.encodeToString(records))
        val restored = bridge.restoreJson(bridge.emptyJson(), bridge.exportJson(state, null, null))
        assertEquals(listOf(1, 2, 3, 3, 4), WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(restored)
            .entries.map { it.measured.boundaryPolicyVersion }.sorted())
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(previousRelative,
            previousRelative.copy(boundaryPolicyVersion = 3)) }
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(previousBoundary,
            previousBoundary.copy(boundaryPolicyVersion=4)) }
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(null,
            previousTorso.copy(boundaryPolicyVersion=4)) }
        assertTrue(WorkoutLifecycle.supportsBoundaryPolicy(ExerciseCounter.Exercise.dip,DipCycleMotion.countMethod,3,1))
        assertFalse(WorkoutLifecycle.supportsBoundaryPolicy(ExerciseCounter.Exercise.dip,DipCycleMotion.countMethod,4,1))
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(previousTorso,
            previousTorso.copy(countPolicyVersion = 2, reps = previousTorso.reps.map { it.copy(countPolicyVersion = 2) })) }
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(previousRelative, current.copy(id = previousRelative.id)) }
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(null,
            old.record().copy(boundaryPolicyVersion = 3)) }
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(null,
            current.copy(exercise = ExerciseCounter.Exercise.pullUp, countMethod = PullUpCycleMotion.countMethod)) }
    }
    @Test fun positiveReleaseNeedsCurrentShouldersAndWristsButNoElbowAngle() {
        for(kind in listOf("missingElbows", "weakElbow", "shortForearms")) {
            val run = Run(); frames().forEach { run.observe(it) }
            for(ms in 1680L..2680L step 40) {
                if(run.workout.isFinished) break
                val pose = releasePose(ms, kind)
                assertNull(DipCycleMotion.sample(pose), kind)
                run.observe(pose)
                if(ms < 2680) assertFalse(run.workout.isFinished, kind)
            }
            assertEquals("disengaged", run.workout.record().endReason, kind)
            assertEquals(1, run.workout.record().completedCount, kind)
        }
    }
    @Test fun missingHandsRegripsAndCameraOrIdentityFaultsCannotSupplyRelease() {
        for(kind in listOf("missingWrist", "weakWrist", "overlappingWrists", "oneHand", "wristsOnly", "cameraUnavailable", "cameraMoved", "competingBodies")) {
            val run = Run(); frames().forEach { run.observe(it) }
            for(ms in 1680L..2680L step 40) {
                if(run.workout.isFinished) break
                val camera = when(kind) {
                    "cameraUnavailable" -> DipCycleMotion.Camera.unavailable
                    "cameraMoved" -> DipCycleMotion.Camera.moved
                    else -> DipCycleMotion.Camera.ready
                }
                run.observe(releasePose(ms, kind), camera)
            }
            assertNotEquals("disengaged", run.workout.record().endReason, kind)
            assertEquals(1, run.workout.record().completedCount, kind)
        }
    }
    @Test fun unavailableCameraCannotJoinTwoShortReleaseWitnesses() {
        val run = Run(); frames().forEach { run.observe(it) }
        for(ms in 1680L..2280L step 40) run.observe(releasePose(ms, "missingElbows"))
        run.observe(releasePose(2320, "missingElbows"), DipCycleMotion.Camera.unavailable)
        for(ms in 2360L..2960L step 40) run.observe(releasePose(ms, "missingElbows"))
        assertFalse(run.workout.isFinished)
        assertEquals(1, run.workout.record().completedCount)
        for(ms in 3000L..3360L step 40) {
            if(run.workout.isFinished) break
            run.observe(releasePose(ms, "missingElbows"))
        }
        assertEquals("disengaged", run.workout.record().endReason)
    }
    @Test fun releaseCannotStartASetOrCarryEvidenceAcrossClockAndSourceFaults() {
        val setup = Run()
        for(ms in 0L..1640L step 40) setup.observe(BodyDipFixtures.pose(ms))
        for(ms in 1680L..2680L step 40) setup.observe(releasePose(ms, "missingElbows"))
        assertFalse(setup.workout.hasStarted)
        assertNull(setup.workout.draftDeltaJson())
        for(fault in listOf("duplicate", "gap", "context")) {
            val run = Run(); frames().forEach { run.observe(it) }
            for(ms in 1680L..2280L step 40) run.observe(releasePose(ms, "missingElbows"))
            when(fault) {
                "duplicate" -> run.observe(releasePose(2280, "missingElbows"))
                "gap" -> run.observe(releasePose(2680, "missingElbows"))
                else -> run.observe(releasePose(2320, "missingElbows").copy(backend = "changed"))
            }
            if(!run.workout.isFinished) {
                val firstFresh = if(fault == "gap") 2720L else if(fault == "context") 2360L else 2320L
                for(ms in firstFresh..3400L step 40) {
                    if(run.workout.isFinished) break
                    run.observe(releasePose(ms, "missingElbows"))
                }
            }
            assertNotEquals("disengaged", run.workout.record().endReason, fault)
            assertEquals(1, run.workout.record().completedCount, fault)
        }
    }

    private fun releasePose(ms: Long, kind: String, wristShift: Double = 70.0): PoseResult {
        val original = BodyDipFixtures.pose(ms)
        // Existing fallback tests retain a positive but smaller hand departure:
        // >.12 of the measured arm, <.35. A large exit is tested separately.
        val shifted = BodyDipFixtures.shifted(original).let { p -> p.copy(people=p.people.map { person ->
            person.copy(landmarks=person.landmarks.map { point ->
                if(point.joint in listOf(PoseJoint.leftWrist,PoseJoint.rightWrist))
                    point.copy(position=Point2D(point.position.x,600+wristShift)) else point
            })
        }) }
        val points = shifted.people.single().landmarks.mapNotNull { p ->
            when {
                kind == "missingElbows" && p.joint in listOf(PoseJoint.leftElbow, PoseJoint.rightElbow) -> null
                kind == "missingWrist" && p.joint == PoseJoint.leftWrist -> null
                kind == "weakElbow" && p.joint == PoseJoint.leftElbow -> p.copy(confidence = .2)
                kind == "weakWrist" && p.joint == PoseJoint.leftWrist -> p.copy(confidence = .2)
                kind == "overlappingWrists" && p.joint in listOf(PoseJoint.leftWrist, PoseJoint.rightWrist) -> p.copy(position = Point2D(450.0, 800.0))
                kind == "shortForearms" && p.joint in listOf(PoseJoint.leftElbow, PoseJoint.rightElbow) -> {
                    val wrist = shifted.people.single().landmark(if(p.joint == PoseJoint.leftElbow) PoseJoint.leftWrist else PoseJoint.rightWrist)!!.position
                    p.copy(position = Point2D(wrist.x + 1, wrist.y + 1))
                }
                kind == "oneHand" && p.joint == PoseJoint.rightWrist ||
                    kind == "wristsOnly" && p.joint !in listOf(PoseJoint.leftWrist, PoseJoint.rightWrist) -> original.people.single().landmark(p.joint)
                else -> p
            }
        }
        val person = PoseObservation(points)
        return shifted.copy(people = if(kind == "competingBodies") listOf(person, person) else listOf(person))
    }

    private fun completedAndHolding() = Run().also { run ->
        frames().forEach { run.observe(it) }
        for(ms in 1680L..1960L step 40)run.observe(BodyDipFixtures.pose(ms))
        assertEquals(1,run.workout.record().completedCount)
    }
    @Test fun aLargeObservedTwoHandExitEndsAtTheCategoricalSourceDwellWithoutElbowInference() {
        val run=completedAndHolding()
        for(ms in listOf(2000L,2100,2200,2249,2250)) {
            run.observe(releasePose(ms,"missingElbows",wristShift=200.0))
            assertEquals(ms==2250L,run.workout.isFinished,"at $ms")
        }
        assertEquals("disengaged",run.workout.record().endReason)
        assertEquals(1,run.workout.record().completedCount)
    }
    @Test fun aWeakTwoHandDepartureStillNeedsOneSecondAndNoNewRep() {
        val run=completedAndHolding()
        for(ms in listOf(2000L,2100,2200,2300,2400,2500,2600,2700,2800,2900,2999,3000)) {
            run.observe(releasePose(ms,"missingElbows"))
            assertEquals(ms==3000L,run.workout.isFinished,"at $ms")
        }
        assertEquals(1,run.workout.record().completedCount)
    }
    @Test fun largeRegripsAndHeldBottomPositionsCannotBecomeARelease() {
        for(kind in listOf("wristsOnly","oneHand","bottom","missingWrist","weakWrist","competingBodies")) {
            val run=completedAndHolding()
            for(ms in 2000L..2600L step 40) {
                if(run.workout.isFinished)break
                run.observe(if(kind=="bottom")BodyDipFixtures.pose(ms,180.0,90.0)
                    else releasePose(ms,kind,wristShift=200.0))
            }
            assertNotEquals("disengaged",run.workout.record().endReason,kind)
            assertEquals(1,run.workout.record().completedCount,kind)
        }
    }
    @Test fun aCameraInterruptionCannotJoinTwoLargeExitWitnesses() {
        val run=completedAndHolding()
        for(ms in listOf(2000L,2100,2200))run.observe(releasePose(ms,"missingElbows",200.0))
        run.observe(releasePose(2240,"missingElbows",200.0),DipCycleMotion.Camera.unavailable)
        for(ms in listOf(2280L,2380,2480,2529,2530)) {
            run.observe(releasePose(ms,"missingElbows",200.0))
            assertEquals(ms==2530L,run.workout.isFinished,"at $ms")
        }
    }
    @Test fun largeExitCannotSurviveClockContextOrAthleteEpochFaults() {
        for(fault in listOf("duplicate","gap","context","epoch")) {
            val run=completedAndHolding()
            for(ms in listOf(2000L,2100))run.observe(releasePose(ms,"missingElbows",200.0))
            when(fault) {
                "duplicate" -> run.observe(releasePose(2100,"missingElbows",200.0))
                "gap" -> run.observe(releasePose(2460,"missingElbows",200.0))
                "context" -> run.observe(releasePose(2140,"missingElbows",200.0).copy(backend="changed"))
                else -> {run.selection.reset();run.observe(releasePose(2140,"missingElbows",200.0))}
            }
            for(ms in 2500L..2900L step 40) {
                if(run.workout.isFinished)break
                run.observe(releasePose(ms,"missingElbows",200.0))
            }
            assertNotEquals("disengaged",run.workout.record().endReason,fault)
            assertEquals(1,run.workout.record().completedCount,fault)
        }
    }
    @Test fun returningEitherHandBreaksTheLargeExitDwellAndStaticSetupCannotStartASet() {
        val run=completedAndHolding()
        for(ms in listOf(2000L,2100))run.observe(releasePose(ms,"missingElbows",200.0))
        run.observe(releasePose(2200,"oneHand",200.0))
        for(ms in listOf(2240L,2340,2440,2489,2490)) {
            run.observe(releasePose(ms,"missingElbows",200.0))
            assertEquals(ms==2490L,run.workout.isFinished,"at $ms")
        }
        val setup=Run()
        for(ms in 0L..1960L step 40)setup.observe(BodyDipFixtures.pose(ms))
        for(ms in 2000L..2400L step 40)setup.observe(releasePose(ms,"missingElbows",200.0))
        assertFalse(setup.workout.hasStarted)
        assertNull(setup.workout.draftDeltaJson())
        assertEquals(0,setup.workout.record(true).completedCount)
    }

    @Test fun cameraMovementAndIdentityLossCannotFinishAPendingRep() {
        val moved = Run()
        frames().takeWhile { it.timestamp.value <= 1200 }.forEach { moved.observe(it) }
        assertEquals("setupInvalidated", moved.observe(BodyDipFixtures.pose(1240), DipCycleMotion.Camera.moved).endReason)
        assertEquals(0, moved.workout.record().completedCount)
        val lost = Run()
        frames().takeWhile { it.timestamp.value <= 1200 }.forEach { lost.observe(it) }
        var final: RelativeDipWorkout.Snapshot? = null
        for(ms in 1240L..1600L step 40) {
            if(lost.workout.isFinished) break
            val p = BodyDipFixtures.pose(ms)
            final = lost.observe(p.copy(people = p.people + p.people))
        }
        assertEquals("trackingLost", final?.endReason)
        assertEquals(0, lost.workout.record().completedCount)
    }
    @Test fun bridgeRejectsASelectionFromAnotherFrameWithoutConsumingIt() {
        val selection = BodyAthleteSelection(); selection.observe(BodyDipFixtures.pose(40))
        val bridge = RelativeDipWorkoutBridge("bound")
        assertFailsWith<IllegalArgumentException> { bridge.observeSelectedJson(selection, 0, 1000, "ready", true, true) }
        assertFalse(bridge.hasStarted)
        bridge.observeSelectedJson(selection, 40, 1000, "ready", true, true)
    }
    @Test fun sourceClockFaultCannotCreateANewAssociationEpochOrRewindCounting() {
        val run = Run(); frames().takeWhile { it.timestamp.value <= 1200 }.forEach { run.observe(it) }
        val generation = run.selection.frame!!.decision.sourceGeneration
        for(ms in listOf(1000L, 1040L, 1160L)) {
            val result = run.observe(BodyDipFixtures.pose(ms))
            assertFalse(result.motion.sourceAccepted)
            assertEquals(generation, run.selection.frame!!.decision.sourceGeneration)
            assertEquals(0, run.workout.record(true).completedCount)
        }
    }
}
