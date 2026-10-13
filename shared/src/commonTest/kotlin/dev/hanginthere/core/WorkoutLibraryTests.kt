package dev.hanginthere.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class WorkoutLibraryTests {
    private val context = WorkoutContext(assistance = WorkoutAssistance.none, addedLoadKg = 0.0, equipment = "parallel bar", cameraView = "front")
    private fun record(id: String, time: Long? = 100000, hold: Double = 8.0, complete: Boolean = true): WorkoutSetRecord {
        val angles = AngleMetrics(90.0, 170.0, 130.0, 2.0, 1.0)
        val rep = RepRecord("$id:rep:1", id, 1, ExerciseCounter.Exercise.pullUp, 0.0, 1.0, 1.0,
            if (complete) 2.0 else null, 2.0, if (complete) "returned" else "disengaged", "accepted", 1.0,
            if (complete) 1.0 else null, angles, angles, .2, .1, 2.0, 1.0, emptyList(), false)
        val duration = maxOf(10.0, hold)
        return WorkoutSetRecord(id, rep.exercise, 0.0, duration, "disengaged", listOf(rep), "authored", 1,
            trackedAtEpochMilliseconds = time, activity = WorkoutActivity(emptyList(), listOf(WorkoutPhaseTotal(WorkoutMotionPhase.hang, hold)), hold, 0.0, duration, duration, false, 2))
    }
    private fun state(vararg records: WorkoutSetRecord): WorkoutLibraryState = WorkoutLibrary.register(WorkoutLibraryState(), records.toList())
    private fun edit(state: WorkoutLibraryState, id: String, correction: WorkoutReviewCorrection) = WorkoutLibrary.apply(state,
        WorkoutLibraryCommand("review", setId = id, correction = correction, atEpochMilliseconds = 1000000))
    @Test fun correctionsAreSeparateAndDeterministicAfterReload() {
        val original = record("one"); val initial = state(original)
        val changed = edit(initial, "one", WorkoutReviewCorrection(attempts = listOf(WorkoutAttemptCorrection(original.reps[0].id, WorkoutAttemptClassification.practice))))
        assertEquals(initial.entries, changed.entries)
        assertEquals(0, WorkoutLibrary.reviewed(changed).single().measuredCompletedReps)
        assertEquals(1, WorkoutLibrary.reviewed(changed).single().practiceAttempts)
        assertEquals(changed, WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(WorkoutRecordCodec.json.encodeToString(changed)))
        val undone = WorkoutLibrary.apply(changed, WorkoutLibraryCommand("undo", setId = "one", atEpochMilliseconds = 1000001))
        assertEquals(1, WorkoutLibrary.reviewed(undone).single().measuredCompletedReps)
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.apply(undone, WorkoutLibraryCommand("undo", setId = "one", atEpochMilliseconds = 1000002)) }
    }
    @Test fun repeatedUndoWalksHistoryRatherThanToggling() {
        val initial = state(record("one"))
        val a = edit(initial, "one", WorkoutReviewCorrection(context = context))
        val b = edit(a, "one", WorkoutReviewCorrection(context = context.copy(addedLoadKg = 10.0)))
        val undo1 = WorkoutLibrary.apply(b, WorkoutLibraryCommand("undo", setId = "one", atEpochMilliseconds = 2000000))
        val undo2 = WorkoutLibrary.apply(undo1, WorkoutLibraryCommand("undo", setId = "one", atEpochMilliseconds = 2000001))
        assertEquals(context, WorkoutCorrections.current(undo1.reviews, "one").context)
        assertEquals(WorkoutContext(), WorkoutCorrections.current(undo2.reviews, "one").context)
    }
    @Test fun manualCompletionsCannotInventMeasurementOrMilestones() {
        val original = record("one", complete = false)
        val edited = edit(state(original), "one", WorkoutReviewCorrection(attempts = listOf(WorkoutAttemptCorrection(original.reps[0].id, WorkoutAttemptClassification.userCompleted))))
        val projected = WorkoutLibrary.reviewed(edited).single()
        assertEquals(0, projected.measuredCompletedReps); assertEquals(1, projected.userReportedCompletedReps)
        assertNull(projected.meanReturnSeconds)
        assertFalse(WorkoutInsights.report(edited, null, 200000, 0).milestones.single { it.id == "rep" }.achieved)
    }
    @Test fun exerciseCorrectionDoesNotReinterpretHoldOrRepGeometry() {
        val edited = edit(state(record("one")), "one", WorkoutReviewCorrection(exercise = ExerciseCounter.Exercise.dip, context = context))
        val set = WorkoutLibrary.reviewed(edited).single()
        assertEquals(ExerciseCounter.Exercise.dip, set.exercise); assertTrue(set.exerciseCorrected)
        assertEquals(0, set.measuredCompletedReps); assertNull(set.holdSeconds); assertNull(set.elbowExcursionDegrees); assertNull(set.comparisonKey)
    }
    @Test fun partitionsOwnEveryAttemptAndHoldExactlyOnce() {
        val first = record("one"); val second = first.reps[0].copy(id = "one:rep:2", ordinal = 2, startedAt = 2.0, extremeAt = 3.0, acceptedAt = 3.0, returnedAt = 4.0, closedAt = 4.0)
        val full = first.copy(reps = first.reps + second)
        val correction = WorkoutReviewCorrection(partitions = listOf(WorkoutSetPartition("part-1", listOf(first.reps[0].id), true), WorkoutSetPartition("part-2", listOf(second.id), false)))
        val split = WorkoutLibrary.reviewed(edit(state(full), "one", correction))
        assertEquals(2, split.sumOf { it.measuredCompletedReps }); assertEquals(8.0, split.mapNotNull { it.holdSeconds }.sum())
        for (bad in listOf(correction.copy(partitions = correction.partitions.map { it.copy(ownsHold = true) }),
            correction.copy(partitions = correction.partitions.map { it.copy(repIds = listOf(second.id)) }),
            correction.copy(partitions = correction.partitions.take(1))))
            assertFailsWith<IllegalArgumentException> { edit(state(full), "one", bad) }
    }
    @Test fun partitionsCannotCollideAcrossSourcesAndMergeRequiresContext() {
        val initial = state(record("one"), record("two"))
        assertFailsWith<IllegalArgumentException> { edit(initial, "one", WorkoutReviewCorrection(partitions = listOf(WorkoutSetPartition("two", listOf("one:rep:1"), true)))) }
        assertFalse(WorkoutCorrections.canMerge(WorkoutLibrary.reviewed(initial)))
        val ready = edit(edit(initial, "one", WorkoutReviewCorrection(context = context)), "two", WorkoutReviewCorrection(context = context))
        assertTrue(WorkoutCorrections.canMerge(WorkoutLibrary.reviewed(ready)))
    }
    @Test fun contextIsolationIncludesVersionsIntentAndLoad() {
        assertNull(WorkoutContext().comparisonKey(ExerciseCounter.Exercise.pullUp, 2, 8))
        val key = context.comparisonKey(ExerciseCounter.Exercise.pullUp, 2, 8)
        assertNotEquals(key, context.copy(intent = WorkoutIntent.negative).comparisonKey(ExerciseCounter.Exercise.pullUp, 2, 8))
        assertNotEquals(key, context.copy(addedLoadKg = 5.0).comparisonKey(ExerciseCounter.Exercise.pullUp, 2, 8))
        assertNotEquals(key, context.comparisonKey(ExerciseCounter.Exercise.pullUp, 1, 8))
        assertEquals(key, context.copy(effort = 8, readiness = WorkoutReadiness.low).comparisonKey(ExerciseCounter.Exercise.pullUp, 2, 8))
    }
    @Test fun recentSessionMedianIgnoresOldBestAndSuggestionsNeedThreeSessions() {
        var library = state(record("old", 100000, 100.0), record("a", 10000000, 8.0, false),
            record("b", 20000000, 9.0, false), record("c", 30000000, 8.0, false))
        library.entries.forEach { library = edit(library, it.measured.id, WorkoutReviewCorrection(context = context)) }
        val report = WorkoutInsights.report(library, null, 40000000, 0)
        assertEquals(4, report.focus.sampleSessions)
        // Old completed rep does not force a rep suggestion when the recent median is zero.
        assertEquals(WorkoutGoalMetric.holdSeconds, report.suggestedGoal?.metric)
        assertEquals(10.0, report.suggestedGoal?.target)
        val small = edit(state(record("a")), "a", WorkoutReviewCorrection(context = context))
        assertNull(WorkoutInsights.report(small, null, 200000, 0).suggestedGoal)
    }
    @Test fun sourceClocksStaySeparateAndUnknownCalendarNeverEarnsHabitDays() {
        val library = state(record("a", null), record("b", null))
        val report = WorkoutInsights.report(library, null, 200000, 0)
        assertEquals(2, report.sessions.size); assertEquals(0, report.practiceDays); assertTrue(report.trend.isEmpty())
        assertTrue(report.sessions.all { it.restSeconds.isEmpty() && it.startedAtEpochMilliseconds == null })
    }
    @Test fun splitSetsCannotInflatePracticeDaysOrIndependentSessions() {
        val initial = state(record("a", 100000), record("b", 200000))
        val report = WorkoutInsights.report(initial, null, 300000, 0)
        assertEquals(1, report.sessions.size); assertEquals(1, report.practiceDays); assertEquals(listOf(100.0), report.sessions.single().startIntervalsSeconds)
        assertTrue(report.sessions.single().restSeconds.isEmpty())
    }
    @Test fun liveBoundaryClocksProduceRestWithoutInventingLegacyFinishTimes() {
        val library = state(record("a", 100000).copy(trackedEndedAtEpochMilliseconds = 110000), record("b", 130000).copy(trackedEndedAtEpochMilliseconds = 140000))
        val session = WorkoutInsights.report(library, null, 200000, 0).sessions.single()
        assertEquals(listOf(20.0), session.restSeconds); assertEquals(140000, session.endedAtEpochMilliseconds)
        val overlapping = state(record("a", 100000).copy(trackedEndedAtEpochMilliseconds = 135000), record("b", 130000))
        assertTrue(WorkoutInsights.report(overlapping, null, 200000, 0).sessions.single().restSeconds.isEmpty())
    }
    @Test fun futureContextIsExplicitPerExerciseAndAfterSetGoalsRequireComparableEvidence() {
        var library = edit(state(record("a")), "a", WorkoutReviewCorrection(context = context.copy(effort = 9, readiness = WorkoutReadiness.low, recoveryNote = "tired")))
        val key = requireNotNull(WorkoutLibrary.sourceSets(library).single().comparisonKey)
        library = WorkoutLibrary.apply(library, WorkoutLibraryCommand("defaultContext", id = key, atEpochMilliseconds = 200000))
        assertNull(library.defaultContexts.single().context.effort)
        val goal = WorkoutLibraryGoal(key, WorkoutGoal(ExerciseCounter.Exercise.pullUp, WorkoutGoalMetric.holdSeconds, 10.0, 200000))
        library = WorkoutLibrary.apply(library, WorkoutLibraryCommand("goal", goal = goal, atEpochMilliseconds = 200000))
        library = WorkoutLibrary.register(library, listOf(record("b", 300000), record("replay", null)))
        assertEquals(context, WorkoutCorrections.current(library.reviews, "b").context)
        assertFalse(WorkoutCorrections.current(library.reviews, "replay").context.comparable)
        val feedback = WorkoutFeedback.summary(library, "b")
        assertEquals(10.0, feedback.goalTarget); assertEquals(8.0, feedback.goalValue)
        assertNull(WorkoutFeedback.summary(library, "replay").goalTarget)
        library = WorkoutLibrary.register(library, listOf(record("old-delayed", 100001), record("c", 400000, 12.0)))
        assertFalse(WorkoutCorrections.current(library.reviews, "old-delayed").context.comparable)
        assertEquals(12.0, WorkoutFeedback.summary(library, "c").goalValue)
        assertEquals(12.0, WorkoutInsights.report(library, key, 500000, 0).goalBest)
        val removed = WorkoutLibrary.apply(library, WorkoutLibraryCommand("delete", setId = "a", atEpochMilliseconds = 500000))
        assertNotNull(WorkoutInsights.report(removed, key, 500001, 0).selectedComparisonKey)
    }
    @Test fun gripCorrectionBackupsPreservePrefixesAndRejectDivergentHistories() {
        val measured = record("a")
        val ledger = WorkoutGripReview.correct(measured, GripCorrectionLedger("a"), null, GripOrientation.underhand, GripWidth.narrow, 200000)
        val backup = WorkoutLibraryBackup.export(state(measured), listOf(measured), listOf(ledger))
        assertEquals(ledger, WorkoutLibraryBackup.decode(backup).gripCorrections.single())
        assertEquals(ledger, WorkoutGripReview.mergeLedgers(measured, GripCorrectionLedger("a"), ledger))
        val different = ledger.copy(corrections = ledger.corrections.map { it.copy(orientation = GripOrientation.overhand) })
        assertFailsWith<IllegalArgumentException> { WorkoutGripReview.mergeLedgers(measured, ledger, different) }
    }
    @Test fun stablePagesUseCalendarAndIdAndRejectStaleCursors() {
        val library = state(*listOf("z", "b", "a", "c").map { record(it) }.toTypedArray())
        val first = WorkoutLibrary.page(library, limit = 2)
        assertEquals(listOf("a", "b"), first.sets.map { it.id })
        assertEquals(listOf("c", "z"), WorkoutLibrary.page(library, first.nextCursor, 2).sets.map { it.id })
        val changed = WorkoutLibrary.register(library, listOf(record("new", 200000)))
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.page(changed, first.nextCursor, 2) }
    }
    @Test fun tombstoneSurvivesRegistrationRestoreAndRepeatedDelete() {
        val original = state(record("a"))
        val deleted = WorkoutLibrary.apply(original, WorkoutLibraryCommand("delete", setId = "a", atEpochMilliseconds = 200000))
        assertTrue(WorkoutLibrary.register(deleted, listOf(record("a"))).entries.isEmpty())
        assertTrue(WorkoutLibraryBackup.restore(deleted, WorkoutLibraryBackup.export(original)).entries.isEmpty())
        assertEquals(deleted, WorkoutLibrary.apply(deleted, WorkoutLibraryCommand("delete", setId = "a", atEpochMilliseconds = 300000)))
    }
    @Test fun backupValidatesAllConflictsAndIsIdempotent() {
        val original = edit(state(record("a")), "a", WorkoutReviewCorrection(context = context))
        val backup = WorkoutLibraryBackup.export(original)
        val restored = WorkoutLibraryBackup.restore(WorkoutLibraryState(), backup)
        assertEquals(original.entries, restored.entries); assertEquals(original.reviews, restored.reviews)
        assertEquals(restored, WorkoutLibraryBackup.restore(restored, backup))
        val conflict = state(record("a", hold = 20.0))
        assertFailsWith<IllegalArgumentException> { WorkoutLibraryBackup.restore(conflict, backup) }
        assertEquals(20.0, conflict.entries.single().measured.activity?.longestHangSeconds)
        assertFailsWith<IllegalArgumentException> { WorkoutLibraryBackup.restore(original, backup.replace("hanginthere-workout-library", "foreign")) }
    }
    @Test fun consentAndMediaRetentionAreIndependentOfBackups() {
        var library = state(record("a"))
        val clip = WorkoutLocalMedia("clip", "a", 60, 100000)
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.apply(library, WorkoutLibraryCommand("attachMedia", media = clip, atEpochMilliseconds = 100000)) }
        library = WorkoutLibrary.apply(library, WorkoutLibraryCommand("mediaPolicy", mediaPolicy = WorkoutMediaPolicy(true, 2, 100, 1), atEpochMilliseconds = 100000))
        library = WorkoutLibrary.apply(library, WorkoutLibraryCommand("attachMedia", media = clip, atEpochMilliseconds = 100000))
        library = WorkoutLibrary.apply(library, WorkoutLibraryCommand("attachMedia", media = clip.copy(id = "new", attachedAtEpochMilliseconds = 100001), atEpochMilliseconds = 100001))
        assertEquals(listOf("new"), library.media.map { it.id })
        assertFailsWith<IllegalArgumentException> { WorkoutDiagnostics.export(library) }
        library = WorkoutLibrary.apply(library, WorkoutLibraryCommand("diagnosticsConsent", consent = true, atEpochMilliseconds = 100002))
        val diagnostic = WorkoutRecordCodec.json.encodeToString(WorkoutDiagnostics.export(library))
        assertFalse(diagnostic.contains("rep:1")); assertFalse(diagnostic.contains("100000"))
        val restored = WorkoutLibraryBackup.restore(WorkoutLibraryState(), WorkoutLibraryBackup.export(library))
        assertTrue(restored.media.isEmpty()); assertFalse(restored.mediaPolicy.enabled); assertFalse(restored.diagnosticsConsent)
        assertTrue(WorkoutLibrary.retainedMedia(library.mediaPolicy, library.media, 100002 + 86400000).isEmpty())
    }
    @Test fun profilesPreserveCountAndPhaseVersions() {
        assertEquals(2, WorkoutExerciseProfiles.all.size)
        assertTrue(WorkoutExerciseProfiles.all.all { it.countPolicyVersion == ExerciseCounter.policyVersion && it.phaseMetricVersion == 2 })
    }
    @Test fun liveRecordsCannotBeReviewedAndInvalidInputsFailClosed() {
        assertFailsWith<IllegalArgumentException> { WorkoutCorrections.project(record("a").copy(endReason = "running")) }
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.apply(state(record("a")), WorkoutLibraryCommand("delete", setId = "../a", atEpochMilliseconds = 100000)) }
        assertFailsWith<IllegalArgumentException> { context.copy(effort = 11).validate() }
        assertFailsWith<IllegalArgumentException> { context.copy(addedLoadKg = Double.NaN).validate() }
        assertFailsWith<IllegalArgumentException> { WorkoutInsights.report(state(record("a")), "foreign", 200000, 0) }
    }
    @Test fun appleBridgeUsesSameValidationProjectionAndRestore() {
        val bridge = WorkoutLibraryBridge()
        val registered = bridge.registerJson(bridge.emptyJson(), WorkoutRecordCodec.json.encodeToString(listOf(record("a"))))
        val command = WorkoutLibraryCommand("review", setId = "a", correction = WorkoutReviewCorrection(context = context), atEpochMilliseconds = 200000)
        val changed = bridge.applyJson(registered, WorkoutRecordCodec.json.encodeToString(command))
        assertEquals(1, WorkoutRecordCodec.json.decodeFromString<WorkoutHistoryPage>(bridge.pageJson(changed, null, 25)).sets.size)
        assertEquals(changed, bridge.validateJson(changed))
        assertTrue(bridge.insightsJson(changed, null, 300000, 0).contains("Build a repeatable baseline"))
    }
    @Test fun mergesKeepSourceIdentitiesAndCannotDuplicateHoldsOrAttemptMetrics() {
        var library = state(record("a", 100000), record("b", 200000))
        for (id in listOf("a", "b")) library = edit(library, id, WorkoutReviewCorrection(context = context))
        val merged = WorkoutLibrary.apply(library, WorkoutLibraryCommand("merge", id = "merged", setIds = listOf("a", "b"), atEpochMilliseconds = 300000))
        val set = WorkoutLibrary.page(merged).sets.single()
        assertEquals(listOf("a", "b"), set.sourceSetIds)
        assertEquals(2, set.measuredCompletedReps); assertEquals(16.0, set.totalHoldSeconds)
        assertEquals(8.0, set.holdSeconds); assertNull(set.meanReturnSeconds)
        val unmerged = WorkoutLibrary.apply(merged, WorkoutLibraryCommand("unmerge", id = "merged", atEpochMilliseconds = 300001))
        assertEquals(2, WorkoutLibrary.page(unmerged).sets.size)
        val deleted = WorkoutLibrary.apply(merged, WorkoutLibraryCommand("delete", setId = "a", atEpochMilliseconds = 300002))
        assertTrue(deleted.merges.isEmpty()); assertEquals(1, WorkoutLibrary.page(deleted).sets.size)
    }
    @Test fun batchWorkoutAssignmentIsAtomicAndPlansRequireExplicitLink() {
        var library = state(record("a", 100000), record("b", 200000))
        for (id in listOf("a", "b")) library = edit(library, id, WorkoutReviewCorrection(context = context))
        val grouped = WorkoutLibrary.apply(library, WorkoutLibraryCommand("assignWorkout", id = "workout", setIds = listOf("a", "b"), atEpochMilliseconds = 300000))
        assertEquals("workout", WorkoutInsights.report(grouped, null, 400000, 0).sessions.single().id)
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.apply(library, WorkoutLibraryCommand("assignWorkout", id = "workout", setIds = listOf("a", "absent"), atEpochMilliseconds = 300000)) }
        assertEquals(null, WorkoutCorrections.current(library.reviews, "a").workoutId)
        val plan = WorkoutPlan("plan", "My practice", 0, listOf(WorkoutPlanItem("item", ExerciseCounter.Exercise.pullUp, WorkoutGoalMetric.holdSeconds, 5.0, 2, context)))
        val planned = WorkoutLibrary.apply(grouped, WorkoutLibraryCommand("plan", plan = plan, atEpochMilliseconds = 300000))
        assertEquals("observed", WorkoutInsights.report(planned, null, 400000, 0).plans.single().status)
        val linked = WorkoutLibrary.apply(planned, WorkoutLibraryCommand("plan", plan = plan.copy(workoutId = "workout"), atEpochMilliseconds = 300001))
        assertEquals("completed", WorkoutInsights.report(linked, null, 400000, 0).plans.single().status)
    }
    @Test fun presentationMergesCannotChangeCalendarOrPerSetPlanEvidence() {
        var library = state(record("a", 86400000), record("b", 172800000))
        for (id in listOf("a", "b")) library = edit(library, id, WorkoutReviewCorrection(context = context))
        val before = WorkoutInsights.report(library, null, 259200000, 0)
        var merged = WorkoutLibrary.apply(library, WorkoutLibraryCommand("merge", id = "merged", setIds = listOf("a", "b"), atEpochMilliseconds = 259200000))
        val after = WorkoutInsights.report(merged, null, 259200000, 0)
        assertEquals(before, after)
        assertEquals(2, after.practiceDays); assertEquals(2, after.sessions.size)
        merged = WorkoutLibrary.apply(merged, WorkoutLibraryCommand("assignWorkout", id = "workout", setIds = listOf("a", "b"), atEpochMilliseconds = 259200000))
        val item = WorkoutPlanItem("item", ExerciseCounter.Exercise.pullUp, WorkoutGoalMetric.completedReps, 1.0, 2, context)
        val plan = WorkoutPlan("plan", "Two sets", 2, listOf(item), workoutId = "workout")
        val linked = WorkoutLibrary.apply(merged, WorkoutLibraryCommand("plan", plan = plan, atEpochMilliseconds = 259200000))
        assertEquals("completed", WorkoutInsights.report(linked, null, 259200000, 0).plans.single().status)
        val harder = WorkoutLibrary.apply(linked, WorkoutLibraryCommand("plan", plan = plan.copy(items = listOf(item.copy(target = 2.0, sets = 1))), atEpochMilliseconds = 259200001))
        assertEquals("observed", WorkoutInsights.report(harder, null, 259200001, 0).plans.single().status)
    }
    @Test fun benchmarkExportBindsSourceClocksAndExcludesIncompleteAttempts() {
        val measured = record("a", complete = false)
        val completed = record("b")
        val session = WorkoutBenchmarkExport.session("capture", "a".repeat(64), listOf(0.0, 30.0), listOf(measured, completed), listOf(0.0, 11.0), listOf(listOf(0.0, 10.0), listOf(11.0, 21.0)))
        assertTrue(session.sets[0].completedRepSeconds.isEmpty())
        assertEquals(listOf(13.0), session.sets[1].completedRepSeconds)
        assertEquals(listOf(11.0, 21.0), session.sets[1].seconds)
        assertFailsWith<IllegalArgumentException> { WorkoutBenchmarkExport.session("capture", "a".repeat(64), listOf(0.0, 30.0), listOf(completed), listOf(11.0), listOf(listOf(11.0, 12.0))) }
        assertFailsWith<IllegalArgumentException> { WorkoutBenchmarkExport.session("capture", "a".repeat(64), listOf(0.0, 30.0), listOf(completed.copy(activity = completed.activity!!.copy(timelineTruncated = true))), listOf(0.0), listOf(listOf(0.0, 10.0))) }
        val output = WorkoutRecordCodec.json.encodeToString(WorkoutBenchmarkExport.prediction("b".repeat(64), WorkoutBenchmarkRun("run", "ios", "authored", "c".repeat(40)), listOf(session)))
        assertTrue(output.contains("\"completed_cycle_definition\":\"start_extreme_return\"")); assertFalse(output.contains("trackedAtEpochMilliseconds"))
    }
    @Test fun benchmarkHoldCreditUsesExplicitSourceIntervalAndNeverInventsLegacyTiming() {
        val measured = record("a").let { it.copy(activity = it.activity!!.copy(phases = listOf(
            WorkoutPhaseSpan("hold", WorkoutMotionPhase.hang, 0.0, 10.0, 8.0, 2.0)))) }
        val output = WorkoutBenchmarkExport.session("capture", "a".repeat(64), listOf(0.0, 11.0), listOf(measured), listOf(0.0), listOf(listOf(0.0, 10.0)))
        assertEquals(listOf(2.0, 10.0), output.sets.single().phases.single().seconds)
        val legacy = measured.copy(activity = measured.activity!!.copy(phases = measured.activity!!.phases.map { it.copy(observedStartedAt = null) }))
        assertFailsWith<IllegalArgumentException> { WorkoutBenchmarkExport.session("capture", "a".repeat(64), listOf(0.0, 11.0), listOf(legacy), listOf(0.0), listOf(listOf(0.0, 10.0))) }
        val backup = WorkoutLibraryBackup.export(state(measured), listOf(measured))
        assertEquals(2.0, WorkoutLibraryBackup.decode(backup).measuredSummaries.single().activity!!.phases.single().observedStartedAt)
    }
    @Test fun replayBridgeDoesNotAssignAnEvaluationDateAsAWorkoutDate() {
        val bridge = WorkoutAnalysisBridge("replay", "pullUp", 0)
        val record = WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(bridge.setJson())
        assertNull(record.trackedAtEpochMilliseconds)
    }
    @Test fun goalsNeverChangeWithNewHistoryAndPlateauIsDescriptive() {
        var library = state(*(1..5).map { record("set-$it", it * 10000000L, 8.0, false) }.toTypedArray())
        for (id in library.entries.map { it.measured.id }) library = edit(library, id, WorkoutReviewCorrection(context = context))
        val report = WorkoutInsights.report(library, null, 60000000, 0)
        assertEquals(true, report.plateau); assertTrue(report.focus.limitation.contains("not a diagnosis"))
        val key = requireNotNull(report.selectedComparisonKey)
        val goal = WorkoutLibraryGoal(key, WorkoutGoal(ExerciseCounter.Exercise.pullUp, WorkoutGoalMetric.holdSeconds, 9.0, 60000000))
        library = WorkoutLibrary.apply(library, WorkoutLibraryCommand("goal", goal = goal, atEpochMilliseconds = 60000000))
        assertNull(WorkoutInsights.report(library, key, 70000000, 0).goalBest)
        library = edit(WorkoutLibrary.register(library, listOf(record("new", 80000000, 20.0, false))), "new", WorkoutReviewCorrection(context = context))
        val next = WorkoutInsights.report(library, key, 90000000, 0)
        assertEquals(goal.goal, next.goal); assertTrue(next.goalAchieved)
    }
    @Test fun realisticHistoryPaginationDoesNotLoadCurvesOrLoseOlderEvidence() {
        val history = (1..1000).map { record("set-$it", it * 100000L) }
        val library = state(*history.toTypedArray())
        var cursor: WorkoutHistoryCursor? = null; val ids = mutableSetOf<String>()
        do {
            val page = WorkoutLibrary.page(library, cursor, 100)
            assertTrue(page.sets.all { ids.add(it.id) })
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(1000, ids.size); assertTrue(library.entries.all { entry -> entry.measured.reps.all { it.samples.isEmpty() } })
    }
    @Test fun classificationDoesNotLeakIntoRangeProgressAndBackupsPreserveArchivalPhases() {
        val measured = record("one").copy(activity = record("one").activity!!.copy(phases = listOf(WorkoutPhaseSpan("phase", WorkoutMotionPhase.hang, 0.0, 8.0, 8.0))))
        val library = state(measured)
        val changed = edit(library, "one", WorkoutReviewCorrection(attempts = listOf(WorkoutAttemptCorrection("one:rep:1", WorkoutAttemptClassification.practice))))
        assertNull(WorkoutLibrary.reviewed(changed).single().elbowExcursionDegrees)
        val backup = WorkoutLibraryBackup.export(library, listOf(measured))
        assertEquals(measured.activity!!.phases, WorkoutLibraryBackup.decode(backup).measuredSummaries.single().activity!!.phases)
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.register(WorkoutLibraryState(), listOf(measured.copy(reps = listOf(measured.reps.single().copy(returnedAt = -1.0))))) }
    }
    @Test fun goalsMustMatchComparisonExerciseAndExpiredMediaIsMaintainedWithoutCommands() {
        val library = edit(state(record("one")), "one", WorkoutReviewCorrection(context = context))
        val key = requireNotNull(WorkoutLibrary.reviewed(library).single().comparisonKey)
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.apply(library, WorkoutLibraryCommand("goal", goal = WorkoutLibraryGoal(key,
            WorkoutGoal(ExerciseCounter.Exercise.dip, WorkoutGoalMetric.holdSeconds, 8.0, 200000)), atEpochMilliseconds = 200000)) }
        val media = WorkoutLocalMedia("clip", "one", 1, 100000)
        val state = library.copy(mediaPolicy = WorkoutMediaPolicy(true, 2, 100, 1), media = listOf(media))
        assertTrue(WorkoutLibrary.maintain(state, 86400000 + 100001).media.isEmpty())
    }
    @Test fun completionAndTrackingCuesNeverFabricateRepsOrChangePolicy() {
        val incomplete = WorkoutFeedback.summary(record("one", complete = false))
        assertEquals(0, incomplete.measuredCompletedReps); assertEquals(1, incomplete.practiceAttempts)
        assertTrue(incomplete.observation.contains("Unfinished returns"))
        val feedback = WorkoutTrackingFeedback()
        assertNull(feedback.observe("one", true, "lowConfidence", 0.0))
        assertNull(feedback.observe("one", true, "lowConfidence", .5))
        assertNotNull(feedback.observe("one", true, "lowConfidence", .6))
        assertNull(feedback.observe("one", true, "lowConfidence", 1.0))
        assertEquals("Tracking resumed", feedback.observe("one", true, null, 1.1))
        val complete = WorkoutCompletionFeedback()
        assertFalse(complete.begin("one", false, true)); assertTrue(complete.begin("one", true, true)); assertFalse(complete.begin("one", true, true))
    }
}
