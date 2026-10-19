package dev.hanginthere.core

import kotlin.test.*
import kotlinx.serialization.encodeToString

class BilateralDipWorkoutTests {
    private val support by lazy { BilateralGripFixtures.curvedReference() }
    private val cycle = listOf(0.0, 0.0, 100.0, 120.0, 0.0, 0.0)
    private fun pose(i: Int, drop: Double = 0.0) = BilateralGripFixtures.frame(1000L + i * 200L, drop, drop)
    private fun feed(workout: BilateralDipWorkout, values: List<Double> = cycle) = values.mapIndexed { i, drop ->
        workout.observe(pose(i, drop), support, BilateralDipMotion.Camera.ready, false)
    }
    @Test fun nativeFrameDeltasStayBoundedAndPreserveMethodIdentity() {
        val workout = BilateralDipWorkout("bounded")
        for (i in 0 until 40 * cycle.size) workout.observe(pose(i, cycle[i % cycle.size]), support, BilateralDipMotion.Camera.ready, false)
        assertEquals(40, workout.closedRecordCount)
        val draft = WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(workout.draftDeltaJson()!!)
        assertEquals(1, draft.reps.size)
        assertTrue(draft.activity!!.phases.size <= 3)
        assertEquals(BilateralDipWorkout.countMethod, draft.countMethod)
        assertEquals(1, draft.countPolicyVersion)
        assertEquals(workout.record().reps.last(), WorkoutRecordCodec.json.decodeFromString<RepRecord>(workout.lastClosedRecordJson()!!))
        val review = WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(workout.reviewDeltaJson())
        assertTrue(review.reps.isEmpty())
        assertEquals(workout.record().activity, review.activity)
        assertEquals(40, workout.record().completedCount)
    }
    @Test fun completedCyclesCarryDistinctPolicyAndObservedImagePeakTiming() {
        val workout = BilateralDipWorkout("set", 1234)
        val snapshots = feed(workout)
        assertEquals(1, snapshots.last().motion.imageCycles)
        workout.finish("sourceEnded")
        val set = workout.record()
        val rep = set.completedReps.single()
        assertEquals(BilateralDipWorkout.countMethod, rep.countMethod)
        assertEquals(BilateralDipMotion.policyVersion, rep.countPolicyVersion)
        assertEquals(pose(3).timestamp.seconds, rep.extremeAt)
        assertEquals(rep.closedAt - rep.startedAt, rep.outboundSeconds!! + rep.returnSeconds!!, 1e-9)
        assertEquals(180.0, rep.left.mean!!, 1e-9)
        assertEquals(180.0, rep.right.mean!!, 1e-9)
        assertEquals("unverified", rep.formVerification)
        assertEquals(1234L, set.trackedAtEpochMilliseconds)
        assertTrue(WorkoutEvidence.assess(rep).notes.any { it.contains("image-motion") })
        val state = WorkoutLibraryState(entries = listOf(WorkoutLibraryEntry(WorkoutLibrary.compact(set))))
        WorkoutLibrary.validate(state)
        assertEquals(set, WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(WorkoutRecordCodec.json.encodeToString(set)))
    }
    @Test fun sourceEndDoesNotPromoteAnUnreturnedAttempt() {
        val workout = BilateralDipWorkout("set")
        feed(workout, cycle.take(4)); workout.finish("endOfInput")
        val record = workout.record()
        assertEquals(0, record.completedCount)
        assertEquals(1, record.reps.size)
        assertNull(record.reps.single().acceptedAt)
        assertNull(record.reps.single().returnedAt)
        assertEquals(0, WorkoutFeedback.summary(record).measuredCompletedReps)
    }
    @Test fun shallowAttemptStaysPractice() {
        val workout = BilateralDipWorkout("set")
        feed(workout, listOf(0.0, 0.0, 50.0, 50.0, 0.0, 0.0)); workout.finish("sourceEnded")
        assertEquals(0, workout.record().completedCount)
        assertEquals("partial", workout.record().reps.single().closeReason)
        assertEquals(1, WorkoutFeedback.summary(workout.record()).practiceAttempts)
    }
    @Test fun trackingInterruptionIsNotAnAthleteUnfinishedAttempt() {
        val workout = BilateralDipWorkout("set")
        feed(workout, cycle.take(4))
        workout.observe(pose(4), support, BilateralDipMotion.Camera.moved, false)
        workout.finish("cameraMoved")
        val record = workout.record()
        assertEquals("cameraMoved", record.reps.single().closeReason)
        assertEquals(0, record.completedCount)
        assertEquals(0, WorkoutFeedback.summary(record).practiceAttempts)
        assertEquals(0, WorkoutProgression.evidence(record).incompleteAttempts)
        assertEquals(0, WorkoutCorrections.project(record, WorkoutReviewCorrection()).single().practiceAttempts)
        val correction = WorkoutReviewCorrection(attempts = listOf(WorkoutAttemptCorrection(record.reps.single().id, WorkoutAttemptClassification.practice)))
        assertEquals(1, WorkoutCorrections.project(record, correction).single().practiceAttempts)
    }
    @Test fun imageMotionCannotShareComparisonKeysWithHistoricalCounterVersions() {
        val workout = BilateralDipWorkout("set")
        feed(workout); workout.finish("sourceEnded")
        val current = workout.record()
        val legacy = current.copy(schemaVersion = 1, countMethod = null, countPolicyVersion = null,
            reps = current.reps.map { it.copy(countMethod = null) })
        val context = WorkoutContext(assistance = WorkoutAssistance.none, addedLoadKg = 0.0,
            equipment = "rails", cameraView = "rear")
        val correction = WorkoutReviewCorrection(context = context)
        val a = WorkoutCorrections.project(current, correction).single()
        val b = WorkoutCorrections.project(legacy, correction).single()
        assertNotNull(a.comparisonKey); assertNotEquals(a.comparisonKey, b.comparisonKey)
        val mixed = current.copy(reps = current.reps + legacy.reps.map { it.copy(id = "legacy", ordinal = 2) })
        assertNull(WorkoutCorrections.project(mixed, correction).single().comparisonKey)
    }
    @Test fun draftMergeCannotChangeTheDetectionMethod() {
        val workout = BilateralDipWorkout("set")
        feed(workout); val record = workout.record()
        val changed = record.copy(reps = record.reps.map { it.copy(countMethod = null) })
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(record, changed) }
    }
    @Test fun finishIsIdempotentAndSourceInstancesCannotBeReused() {
        val workout = BilateralDipWorkout("set")
        feed(workout); workout.finish("manual"); val first = workout.record()
        workout.finish("sourceEnded")
        assertEquals(first, workout.record())
        assertFailsWith<IllegalStateException> { workout.observe(pose(6), support, BilateralDipMotion.Camera.ready, false) }
    }
    @Test fun missingObservationsEndAsTrackingLostAndNeverInventEquipmentRelease() {
        val workout = BilateralDipWorkout("set")
        feed(workout)
        var last: BilateralDipWorkout.Snapshot? = null
        for (i in 6..22) {
            last = workout.observe(pose(i).copy(people = emptyList(), gripObservation = null),
                support, BilateralDipMotion.Camera.ready, true)
            if (last.endReason != null) break
        }
        assertEquals("trackingLost", last?.endReason)
        assertEquals(1, workout.record().completedCount)
        assertEquals(0, WorkoutFeedback.summary(workout.record()).practiceAttempts)
    }
    private fun releasedPose(i: Int, moveHands: Boolean = true): PoseResult {
        val original = pose(i)
        fun shifted(point: Point2D) = Point2D(point.x, point.y + 300)
        val hand = original.gripObservation!!
        return original.copy(people = original.people.map { person -> person.copy(landmarks = person.landmarks.map { it.copy(position = shifted(it.position)) }) },
            gripObservation = hand.copy(trackedBody = hand.trackedBody.map { it.copy(position = shifted(it.position)) },
                hands = if (moveHands) hand.hands.map { value -> value.copy(points = value.points.map { it.copy(position = shifted(it.position)) }) } else hand.hands))
    }
    @Test fun observedBilateralHandsAndBodyReleaseEndTheSetAfterRealDwell() {
        val workout = BilateralDipWorkout("set")
        feed(workout)
        var last: BilateralDipWorkout.Snapshot? = null
        for (i in 6..14) {
            last = workout.observe(releasedPose(i), support, BilateralDipMotion.Camera.ready, true)
            if (last.endReason != null) break
        }
        assertEquals("disengaged", last?.endReason)
        assertEquals(1, workout.record().completedCount)
        assertEquals(0, WorkoutFeedback.summary(workout.record()).practiceAttempts)
    }
    @Test fun driftingBodyWristsOrMissingHandsCannotEstablishRelease() {
        for (missing in listOf(false, true)) {
            val workout = BilateralDipWorkout("set")
            feed(workout)
            for (i in 6..12) {
                val current = releasedPose(i, moveHands = false).let { if (missing) it.copy(gripObservation = null) else it }
                assertNull(workout.observe(current, support, BilateralDipMotion.Camera.ready, true).endReason)
            }
        }
    }
    @Test fun subOutageSourceGapPreservesCycleIdentityButNeverAddsObservedTime() {
        val workout = BilateralDipWorkout("set")
        val rows = listOf(1000L to 0.0, 1200L to 0.0, 1400L to 100.0, 1800L to 120.0,
            2000L to 120.0, 2200L to 0.0, 2400L to 0.0)
        rows.forEach { (time, drop) -> workout.observe(BilateralGripFixtures.frame(time, drop, drop), support, BilateralDipMotion.Camera.ready, false) }
        workout.finish("sourceEnded")
        val record = workout.record()
        val rep = record.reps.single()
        assertTrue(rep.isCompleteForAnalytics)
        assertEquals(1.8, rep.extremeAt)
        assertTrue(rep.durationCoverage!! < 1)
        assertEquals(0, WorkoutFeedback.summary(record).practiceAttempts)
    }
    @Test fun nonLegacyCompletionRequiresAnExplicitCycleAndNeverBorrowsElbowPeak() {
        fun sample(time: Double, angle: Double) = MotionSample(time, angle, angle, .5, true, false)
        val unconfirmed = WorkoutAnalysis("set", ExerciseCounter.Exercise.dip, 1, BilateralDipWorkout.countMethod)
        unconfirmed.observeMotion(sample(1.0, 180.0), ExerciseCounter.Phase.outbound, null)
        unconfirmed.observeMotion(sample(1.2, 100.0), ExerciseCounter.Phase.returning,
            WorkoutAnalysis.MotionEvent(WorkoutAnalysis.MotionOutcome.accepted, 1.2, "milestone"))
        unconfirmed.observeMotion(sample(1.4, 180.0), ExerciseCounter.Phase.ready, null)
        assertFalse(unconfirmed.records.single().isCompleteForAnalytics)
        assertNull(unconfirmed.records.single().returnedAt)
        val completed = WorkoutAnalysis("set", ExerciseCounter.Exercise.dip, 1, BilateralDipWorkout.countMethod)
        completed.observeMotion(sample(1.0, 180.0), ExerciseCounter.Phase.outbound, null)
        completed.observeMotion(sample(1.2, 100.0), ExerciseCounter.Phase.returning, null)
        completed.observeMotion(sample(1.4, 180.0), ExerciseCounter.Phase.ready,
            WorkoutAnalysis.MotionEvent(WorkoutAnalysis.MotionOutcome.completed, 1.4, "cycle"))
        assertTrue(completed.records.single().isCompleteForAnalytics)
        assertNull(completed.records.single().extremeAt)
        assertNull(completed.records.single().outboundSeconds)
        assertNull(completed.records.single().returnSeconds)
    }
    @Test fun progressionAndSuggestedGoalsStayInsideTheLatestMethodCohort() {
        val workout = BilateralDipWorkout("set", 2000)
        feed(workout); workout.finish("sourceEnded")
        val current = workout.record()
        val legacy = current.copy(id = "old", schemaVersion = 1, countMethod = null, countPolicyVersion = null,
            trackedAtEpochMilliseconds = 1000, reps = (1..10).map { current.reps.single().copy(id = "old:$it", setId = "old", ordinal = it, countMethod = null) })
        val report = WorkoutProgression.report(listOf(legacy, current), ExerciseCounter.Exercise.dip)
        assertEquals(1, report.completedReps)
        assertEquals(2.0, report.goal.target)
        assertEquals(BilateralDipWorkout.countMethod, report.goal.countMethod)
        assertFalse(report.milestones.single { it.id == "reps:3" }.achieved)
        assertEquals(10, WorkoutProgression.report(listOf(legacy, current), ExerciseCounter.Exercise.dip,
            WorkoutGoal(ExerciseCounter.Exercise.dip, WorkoutGoalMetric.completedReps, 11.0)).completedReps)
    }
    @Test fun selectedKeysSavedGoalsAndPlansRespectMethodIdentity() {
        val workout = BilateralDipWorkout("set", 1234)
        feed(workout); workout.finish("sourceEnded")
        val record = workout.record()
        val context = WorkoutContext(assistance = WorkoutAssistance.none, addedLoadKg = 0.0, equipment = "rails", cameraView = "rear")
        val correction = WorkoutReviewCorrection(context = context)
        val key = WorkoutCorrections.project(record, correction).single().comparisonKey!!
        val legacyItem = WorkoutPlanItem("item", ExerciseCounter.Exercise.dip, WorkoutGoalMetric.completedReps, 1.0, 1, context)
        val newItem = legacyItem.copy(countMethod = BilateralDipWorkout.countMethod, countPolicyVersion = BilateralDipMotion.policyVersion)
        val state = WorkoutLibraryState(entries = listOf(WorkoutLibraryEntry(WorkoutLibrary.compact(record))),
            reviews = WorkoutReviewLedger(listOf(WorkoutReviewEvent(1, "set", correction, 1500))),
            goals = listOf(WorkoutLibraryGoal(key, WorkoutGoal(ExerciseCounter.Exercise.dip, WorkoutGoalMetric.completedReps, 2.0, 1500,
                BilateralDipWorkout.countMethod, BilateralDipMotion.policyVersion))),
            plans = listOf(WorkoutPlan("oldplan", "Old plan", 0, listOf(legacyItem)), WorkoutPlan("newplan", "New plan", 0, listOf(newItem))))
        val report = WorkoutInsights.report(state, key, 2000, 0)
        assertEquals(key, report.selectedComparisonKey)
        assertEquals(0, report.plans.single { it.planId == "oldplan" }.completedItems)
        assertEquals(1, report.plans.single { it.planId == "newplan" }.completedItems)
        val roundTrip = WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(WorkoutRecordCodec.json.encodeToString(state))
        WorkoutLibrary.validate(roundTrip)
        assertEquals(state, roundTrip)
        val mismatched = state.copy(goals = state.goals.map { it.copy(goal = it.goal.copy(countMethod = null, countPolicyVersion = null)) })
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.validate(mismatched) }
    }
    @Test fun cameraSetupFramesDoNotDiluteTheActualSetCoverage() {
        val workout = BilateralDipWorkout("set")
        for (i in 0..3) {
            val empty = BilateralGripFixtures.frame(i * 200L).copy(people = emptyList(), gripObservation = null)
            val snapshot = workout.observe(empty, support, BilateralDipMotion.Camera.ready, false)
            assertEquals(0, snapshot.analyzedFrames)
        }
        val snapshots = feed(workout)
        assertEquals(6, snapshots.last().analyzedFrames)
        assertEquals(6, snapshots.last().usableFrames)
        workout.finish("sourceEnded")
        assertEquals(1.0, workout.record().startedAt)
        assertEquals(1, workout.record().completedCount)
    }
    @Test fun cameraMovementRevokesOldSupportForAutomaticReleaseAsWellAsCounting() {
        val workout = BilateralDipWorkout("set")
        feed(workout)
        workout.observe(pose(6), support, BilateralDipMotion.Camera.moved, false)
        for (i in 7..13) {
            val result = workout.observe(releasedPose(i), support, BilateralDipMotion.Camera.ready, true)
            assertEquals("supportReacquisitionRequired", result.motion.availability)
            assertNull(result.endReason)
        }
        workout.finish("sourceEnded")
        assertEquals(1, workout.record().completedCount)
    }
    @Test fun librarySuggestionsAndAutomaticGoalCommandsRetainSelectedMethod() {
        val workout = BilateralDipWorkout("set", 1000)
        feed(workout); workout.finish("sourceEnded")
        val original = workout.record()
        val context = WorkoutContext(assistance = WorkoutAssistance.none, addedLoadKg = 0.0, equipment = "rails", cameraView = "rear")
        val correction = WorkoutReviewCorrection(context = context)
        val records = listOf(1000L, 3000000L, 6000000L).mapIndexed { i, date ->
            val id = "set$i"
            original.copy(id = id, trackedAtEpochMilliseconds = date,
                reps = original.reps.map { it.copy(setId = id, id = "$id:rep:1") })
        }
        val state = WorkoutLibraryState(entries = records.map { WorkoutLibraryEntry(WorkoutLibrary.compact(it)) },
            reviews = WorkoutReviewLedger(records.mapIndexed { i, record -> WorkoutReviewEvent(i + 1, record.id, correction, 9000000L + i) }))
        val key = WorkoutCorrections.project(records.first(), correction).single().comparisonKey!!
        val suggested = WorkoutInsights.report(state, key, 10000000, 0).suggestedGoal!!
        assertEquals(BilateralDipWorkout.countMethod, suggested.countMethod)
        assertEquals(BilateralDipMotion.policyVersion, suggested.countPolicyVersion)
        val command = WorkoutLibraryCommand("goal", goal = WorkoutLibraryGoal(key, suggested.copy(countMethod = null, countPolicyVersion = null)),
            atEpochMilliseconds = 10000000)
        val saved = WorkoutLibrary.apply(state, command)
        assertEquals(suggested, saved.goals.single().goal)
        WorkoutLibrary.validate(saved)
    }
}
