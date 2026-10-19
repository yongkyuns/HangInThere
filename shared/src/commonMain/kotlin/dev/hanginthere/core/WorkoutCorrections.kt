package dev.hanginthere.core

import kotlinx.serialization.Serializable

@Serializable enum class WorkoutAttemptClassification { automatic, excluded, practice, userCompleted }
@Serializable data class WorkoutAttemptCorrection(val repId: String, val classification: WorkoutAttemptClassification)
@Serializable data class WorkoutSetPartition(val id: String, val repIds: List<String>, val ownsHold: Boolean)
@Serializable data class WorkoutReviewCorrection(
    val exercise: ExerciseCounter.Exercise? = null,
    val attempts: List<WorkoutAttemptCorrection> = emptyList(),
    val partitions: List<WorkoutSetPartition> = emptyList(),
    val context: WorkoutContext = WorkoutContext(),
    val workoutId: String? = null,
)
/** Complete replacement per event makes undo/reload deterministic. Originals are separate files. */
@Serializable data class WorkoutReviewEvent(val sequence: Int, val setId: String,
    val correction: WorkoutReviewCorrection, val recordedAtEpochMilliseconds: Long, val undoOfSequence: Int? = null)
@Serializable data class WorkoutReviewLedger(val events: List<WorkoutReviewEvent> = emptyList(), val schemaVersion: Int = 1)
@Serializable data class WorkoutReviewedAttempt(val id: String, val ordinal: Int, val classification: WorkoutAttemptClassification,
    val measuredCompleted: Boolean, val userReportedCompleted: Boolean, val metricsEligible: Boolean)
@Serializable data class WorkoutReviewedSet(val id: String, val sourceSetId: String, val exercise: ExerciseCounter.Exercise,
    val attemptIds: List<String>, val ownsHold: Boolean, val measuredCompletedReps: Int, val userReportedCompletedReps: Int,
    val practiceAttempts: Int, val excludedAttempts: Int, val holdSeconds: Double?, val totalHoldSeconds: Double?,
    val elbowExcursionDegrees: Double?, val meanReturnSeconds: Double?, val trackingCoverage: Double?,
    val trackedAtEpochMilliseconds: Long?, val endReason: String, val context: WorkoutContext,
    val comparisonKey: String?, val metricVersion: Int, val countPolicyVersion: Int, val workoutId: String?,
    val exerciseCorrected: Boolean, val attempts: List<WorkoutReviewedAttempt>, val sourceSetIds: List<String> = listOf(sourceSetId),
    val trackedEndedAtEpochMilliseconds: Long? = null, val isMerged: Boolean = false,
    val countMethod: String? = null)

object WorkoutCorrections {
    const val maximumEvents = 65536
    fun safeId(id: String): Boolean = id.length in 1..64 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' }
    fun validate(record: WorkoutSetRecord, correction: WorkoutReviewCorrection) {
        require(record.endReason != "running") { "Finish the set before reviewing it" }
        require(record.reps.map { it.id }.distinct().size == record.reps.size)
        correction.context.validate()
        require(correction.workoutId == null || safeId(correction.workoutId))
        val ids = record.reps.map { it.id }.toSet()
        require(correction.attempts.size <= ids.size && correction.attempts.map { it.repId }.distinct().size == correction.attempts.size)
        require(correction.attempts.all { it.repId in ids })
        if (correction.partitions.isNotEmpty()) {
            require(correction.partitions.size <= maxOf(1, ids.size))
            require(correction.partitions.map { it.id }.distinct().size == correction.partitions.size)
            require(correction.partitions.all { safeId(it.id) && (it.repIds.isNotEmpty() || ids.isEmpty()) })
            require(correction.partitions.count { it.ownsHold } == 1) { "Exactly one split owns the observed hold totals" }
            val assigned = correction.partitions.flatMap { it.repIds }
            require(assigned.size == ids.size && assigned.toSet() == ids) { "Each observed attempt belongs to exactly one split" }
        }
    }
    fun validateLedger(ledger: WorkoutReviewLedger) {
        require(ledger.schemaVersion == 1 && ledger.events.size <= maximumEvents)
        require(ledger.events.withIndex().all { (index, event) -> event.sequence == index + 1 && safeId(event.setId) && event.recordedAtEpochMilliseconds > 0 })
        val active = mutableMapOf<String, MutableList<WorkoutReviewEvent>>()
        ledger.events.forEach { event ->
            val stack = active.getOrPut(event.setId) { mutableListOf() }
            if (event.undoOfSequence == null) stack += event else {
                require(stack.lastOrNull()?.sequence == event.undoOfSequence) { "Invalid undo history" }
                stack.removeAt(stack.lastIndex)
                require(event.correction == (stack.lastOrNull()?.correction ?: WorkoutReviewCorrection()))
            }
        }
    }
    private fun activeEvents(ledger: WorkoutReviewLedger, setId: String): MutableList<WorkoutReviewEvent> {
        val active = mutableListOf<WorkoutReviewEvent>()
        ledger.events.filter { it.setId == setId }.forEach { event ->
            if (event.undoOfSequence == null) active += event else {
                require(active.lastOrNull()?.sequence == event.undoOfSequence) { "Invalid undo history" }
                active.removeAt(active.lastIndex)
                require(event.correction == (active.lastOrNull()?.correction ?: WorkoutReviewCorrection()))
            }
        }
        return active
    }
    fun current(ledger: WorkoutReviewLedger, setId: String): WorkoutReviewCorrection =
        ledger.events.lastOrNull { it.setId == setId }?.correction ?: WorkoutReviewCorrection()
    fun append(record: WorkoutSetRecord, ledger: WorkoutReviewLedger, correction: WorkoutReviewCorrection, at: Long): WorkoutReviewLedger {
        validateLedger(ledger); validate(record, correction)
        require(at > 0 && ledger.events.size < maximumEvents) { "Review ledger is full; export before creating a new library" }
        return ledger.copy(events = ledger.events + WorkoutReviewEvent(ledger.events.size + 1, record.id, correction, at))
    }
    fun undo(record: WorkoutSetRecord, ledger: WorkoutReviewLedger, at: Long): WorkoutReviewLedger {
        validateLedger(ledger)
        val active = activeEvents(ledger, record.id)
        val target = requireNotNull(active.lastOrNull()) { "No edit to undo" }
        val correction = active.dropLast(1).lastOrNull()?.correction ?: WorkoutReviewCorrection()
        val next = append(record, ledger, correction, at)
        return next.copy(events = next.events.dropLast(1) + next.events.last().copy(undoOfSequence = target.sequence))
    }
    fun project(record: WorkoutSetRecord, correction: WorkoutReviewCorrection = WorkoutReviewCorrection()): List<WorkoutReviewedSet> {
        validate(record, correction)
        val exercise = correction.exercise ?: record.exercise
        val correctedExercise = exercise != record.exercise
        val partitions = correction.partitions.ifEmpty { listOf(WorkoutSetPartition(record.id, record.reps.map { it.id }, true)) }
        return partitions.map { part ->
            val reps = record.reps.filter { it.id in part.repIds }
            val attempts = reps.map { rep ->
                val classification = correction.attempts.firstOrNull { it.repId == rep.id }?.classification ?: WorkoutAttemptClassification.automatic
                val included = classification == WorkoutAttemptClassification.automatic
                WorkoutReviewedAttempt(rep.id, rep.ordinal, classification,
                    rep.isCompleteForAnalytics && included && !correctedExercise,
                    classification == WorkoutAttemptClassification.userCompleted,
                    rep.isCompleteForAnalytics && included && !correctedExercise && WorkoutEvidence.assess(rep, record.endReason).angleComparisonEligible)
            }
            val metricReps = reps.filter { rep -> attempts.single { it.id == rep.id }.metricsEligible }
            val phase = WorkoutExerciseProfiles.profile(record.exercise).holdPhase
            val activity = record.activity.takeIf { part.ownsHold && !correctedExercise }
            val hold = activity?.let { if (exercise == ExerciseCounter.Exercise.pullUp) it.longestHangSeconds else it.longestSupportSeconds }
            val metricVersion = record.activity?.metricVersion ?: 0
            val countVersion = reps.map { it.countPolicyVersion }.distinct().singleOrNull() ?: record.countPolicyVersion ?: ExerciseCounter.policyVersion
            val methods = (reps.map { it.countMethod to it.countPolicyVersion } +
                listOfNotNull(record.countMethod?.let { it to record.countPolicyVersion })).distinct()
            val method = methods.singleOrNull()?.first
            val key = correction.context.comparisonKey(exercise, metricVersion, countVersion, method)
                .takeIf { !correctedExercise && methods.size <= 1 }
            WorkoutReviewedSet(part.id, record.id, exercise, part.repIds, part.ownsHold,
                attempts.count { it.measuredCompleted }, attempts.count { it.userReportedCompleted && !it.measuredCompleted },
                attempts.count { attempt -> !attempt.measuredCompleted && !attempt.userReportedCompleted &&
                    attempt.classification != WorkoutAttemptClassification.excluded &&
                    (attempt.classification != WorkoutAttemptClassification.automatic ||
                        reps.single { it.id == attempt.id }.isPracticeForAnalytics) },
                attempts.count { it.classification == WorkoutAttemptClassification.excluded }, hold, activity?.seconds(phase),
                reps.filter { rep -> attempts.single { it.id == rep.id }.let { it.classification == WorkoutAttemptClassification.automatic || it.metricsEligible } }
                    .mapNotNull(WorkoutEvidence::comparableElbowExcursion).maxOrNull().takeIf { !correctedExercise },
                metricReps.mapNotNull { it.returnSeconds }.takeIf { it.isNotEmpty() }?.average(),
                record.activity?.let { if (it.elapsedSeconds > 0) (it.observedSeconds / it.elapsedSeconds).coerceIn(0.0, 1.0) else null },
                record.trackedAtEpochMilliseconds, record.endReason, correction.context, key, metricVersion, countVersion,
                correction.workoutId, correctedExercise, attempts, trackedEndedAtEpochMilliseconds = record.trackedEndedAtEpochMilliseconds,
                countMethod = method)
        }
    }
    /** Merge is a presentation grouping, never source-time concatenation. */
    fun canMerge(sets: List<WorkoutReviewedSet>): Boolean = sets.size >= 2 && sets.map { it.exercise }.distinct().size == 1 &&
        sets.all { it.comparisonKey != null } && sets.map { it.comparisonKey }.distinct().size == 1 &&
        sets.map { it.id }.distinct().size == sets.size
}
