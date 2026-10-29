package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable data class WorkoutLibraryEntry(val measured: WorkoutSetRecord)
@Serializable data class WorkoutDeletion(val setId: String, val deletedAtEpochMilliseconds: Long)
@Serializable data class WorkoutPlanItem(val id: String, val exercise: ExerciseCounter.Exercise,
    val metric: WorkoutGoalMetric, val target: Double, val sets: Int, val context: WorkoutContext,
    val countMethod: String? = null, val countPolicyVersion: Int? = null)
@Serializable data class WorkoutPlan(val id: String, val title: String, val scheduledDay: Long,
    val items: List<WorkoutPlanItem>, val workoutId: String? = null)
@Serializable data class WorkoutLibraryGoal(val comparisonKey: String, val goal: WorkoutGoal)
@Serializable data class WorkoutDefaultContext(val exercise: ExerciseCounter.Exercise, val context: WorkoutContext, val selectedAtEpochMilliseconds: Long)
@Serializable data class WorkoutSetMerge(val id: String, val setIds: List<String>)
@Serializable data class WorkoutMediaPolicy(val enabled: Boolean = false, val maximumClips: Int = 20,
    val maximumBytes: Long = 200L * 1024 * 1024, val maximumAgeDays: Int = 30) {
    fun validate() { require(maximumClips in 1..100 && maximumBytes in 1..(1024L * 1024 * 1024) && maximumAgeDays in 1..365) }
}
@Serializable data class WorkoutLocalMedia(val id: String, val setId: String, val bytes: Long,
    val attachedAtEpochMilliseconds: Long, val sourceStartSeconds: Double? = null, val sourceEndSeconds: Double? = null)
@Serializable data class WorkoutLibraryState(
    val entries: List<WorkoutLibraryEntry> = emptyList(),
    val reviews: WorkoutReviewLedger = WorkoutReviewLedger(),
    val deletions: List<WorkoutDeletion> = emptyList(),
    val goals: List<WorkoutLibraryGoal> = emptyList(),
    val plans: List<WorkoutPlan> = emptyList(),
    val mediaPolicy: WorkoutMediaPolicy = WorkoutMediaPolicy(),
    val media: List<WorkoutLocalMedia> = emptyList(),
    val diagnosticsConsent: Boolean = false,
    val merges: List<WorkoutSetMerge> = emptyList(),
    val defaultContexts: List<WorkoutDefaultContext> = emptyList(),
    val revision: Long = 0,
    val schemaVersion: Int = 1,
)
@Serializable data class WorkoutHistoryCursor(val epochMilliseconds: Long?, val id: String, val revision: Long)
@Serializable data class WorkoutHistoryPage(val sets: List<WorkoutReviewedSet>, val nextCursor: WorkoutHistoryCursor?, val revision: Long)
@Serializable data class WorkoutLibraryCommand(val action: String, val setId: String? = null,
    val correction: WorkoutReviewCorrection? = null, val goal: WorkoutLibraryGoal? = null,
    val plan: WorkoutPlan? = null, val id: String? = null, val media: WorkoutLocalMedia? = null,
    val mediaPolicy: WorkoutMediaPolicy? = null, val consent: Boolean? = null,
    val setIds: List<String> = emptyList(),
    val atEpochMilliseconds: Long)

/** Shared application state changes are explicit commands outside the frame path. */
object WorkoutLibrary {
    const val maximumEntries = 100000
    const val maximumPlans = 256
    fun compact(record: WorkoutSetRecord): WorkoutSetRecord = WorkoutRecordCodec.summary(record).copy(
        activity = record.activity?.copy(phases = emptyList()), grip = null)
    fun validate(state: WorkoutLibraryState) {
        require(state.schemaVersion == 1 && state.revision >= 0)
        require(state.entries.size <= maximumEntries && state.deletions.size <= maximumEntries)
        require(state.entries.map { it.measured.id }.distinct().size == state.entries.size)
        require(state.deletions.map { it.setId }.distinct().size == state.deletions.size)
        require(state.deletions.all { WorkoutCorrections.safeId(it.setId) && it.deletedAtEpochMilliseconds > 0 })
        require(state.defaultContexts.size <= WorkoutExerciseProfiles.all.size && state.defaultContexts.map { it.exercise }.distinct().size == state.defaultContexts.size)
        state.defaultContexts.forEach { it.context.validate(); require(it.selectedAtEpochMilliseconds > 0 && it.context.comparable && it.context.effort == null && it.context.readiness == WorkoutReadiness.unrecorded && it.context.recoveryNote.isEmpty()) }
        val deletedIds = state.deletions.map { it.setId }.toSet()
        val entryIds = state.entries.map { it.measured.id }.toSet()
        val corrections = state.reviews.events.associate { it.setId to it.correction }
        require(state.entries.none { it.measured.id in deletedIds })
        state.entries.forEach { entry ->
            val record = entry.measured
            require(WorkoutCorrections.safeId(record.id) && record == compact(record))
            require(record.schemaVersion in 1..2 && record.lifecycleVersion == 1 && record.endReason != "running")
            require(WorkoutLifecycle.supportsBoundaryPolicy(record.exercise, record.countMethod, record.boundaryPolicyVersion, record.countPolicyVersion))
            if (record.schemaVersion == 1) require(record.countMethod == null && record.countPolicyVersion == null && record.reps.all { it.countMethod == null })
            else require(WorkoutCountingMethod.isSupported(record.exercise, record.countMethod, record.countPolicyVersion) &&
                record.reps.all { it.countMethod == record.countMethod && it.countPolicyVersion == record.countPolicyVersion })
            require(record.trackedAtEpochMilliseconds == null || record.trackedAtEpochMilliseconds > 0)
            require(record.trackedEndedAtEpochMilliseconds == null || record.trackedAtEpochMilliseconds != null && record.trackedEndedAtEpochMilliseconds >= record.trackedAtEpochMilliseconds)
            require(record.startedAt == null || record.startedAt.isFinite() && record.startedAt >= 0)
            require(record.endedAt == null || record.endedAt.isFinite() && record.endedAt >= (record.startedAt ?: 0.0))
            require(record.reps.size <= 1000 && record.reps.all { it.setId == record.id && it.startedAt.isFinite() &&
                it.closedAt.isFinite() && it.closedAt >= it.startedAt })
            record.reps.forEach { rep ->
                require(WorkoutCountingMethod.validCountConfirmation(rep,record.endedAt))
                require(rep.countMethod == null || WorkoutCountingMethod.isSupported(rep.exercise, rep.countMethod, rep.countPolicyVersion))
                require(rep.metricVersion in 1..WorkoutAnalysis.metricVersion)
                require(rep.ordinal in 1..1000 && rep.id.length in 1..128 && rep.startedAt >= 0)
                require(listOfNotNull(rep.extremeAt, rep.acceptedAt, rep.returnedAt).all { it.isFinite() && it in rep.startedAt..rep.closedAt })
                require(rep.returnedAt == null || rep.acceptedAt == null || rep.returnedAt >= rep.acceptedAt)
                require(listOfNotNull(rep.outboundSeconds, rep.returnSeconds, rep.shoulderExcursion, rep.bilateralExtremeOffsetSeconds).all { it.isFinite() && it >= 0 })
                require(rep.usableDurationSeconds.isFinite() && rep.usableDurationSeconds in 0.0..(rep.closedAt - rep.startedAt + 1e-6))
                require(rep.durationCoverage == null || rep.durationCoverage.isFinite() && rep.durationCoverage in 0.0..1.0)
                for (angle in listOf(rep.left, rep.right)) {
                    require(listOfNotNull(angle.minimum, angle.maximum, angle.mean).all { it.isFinite() && it in 0.0..180.0 })
                    require(angle.minimum == null || angle.maximum == null || angle.minimum <= angle.maximum)
                    require(angle.observedSeconds.isFinite() && angle.observedSeconds in 0.0..(rep.closedAt - rep.startedAt + 1e-6))
                    require(angle.coverage == null || angle.coverage.isFinite() && angle.coverage in 0.0..1.0)
                }
            }
            record.activity?.let { activity ->
                require(activity.metricVersion in 1..WorkoutPhaseAnalysis.metricVersion)
                require(listOf(activity.longestHangSeconds, activity.longestSupportSeconds, activity.observedSeconds, activity.elapsedSeconds).all { it.isFinite() && it >= 0 })
                require(activity.observedSeconds <= activity.elapsedSeconds + 1e-6)
                require(activity.totals.map { it.phase }.distinct().size == activity.totals.size && activity.totals.all { it.observedSeconds.isFinite() && it.observedSeconds >= 0 && it.observedSeconds <= activity.elapsedSeconds + 1e-6 })
            }
            WorkoutCorrections.validate(record, corrections[record.id] ?: WorkoutReviewCorrection())
        }
        WorkoutCorrections.validateLedger(state.reviews)
        require(state.reviews.events.all { it.setId in entryIds || it.setId in deletedIds })
        val partitions = rawReviewed(state)
        require(partitions.map { it.id }.distinct().size == partitions.size) { "Split IDs must be unique across the library" }
        require(state.merges.size <= 1000 && state.merges.map { it.id }.distinct().size == state.merges.size)
        require(state.merges.flatMap { it.setIds }.distinct().size == state.merges.sumOf { it.setIds.size })
        state.merges.forEach { group ->
            require(WorkoutCorrections.safeId(group.id) && partitions.none { it.id == group.id })
            val members = partitions.filter { it.id in group.setIds }
            require(members.size == group.setIds.size && WorkoutCorrections.canMerge(members))
        }
        require(state.goals.size <= 256 && state.goals.map { it.comparisonKey }.distinct().size == state.goals.size)
        state.goals.forEach {
            require(it.comparisonKey.length in 1..1024)
            val key = WorkoutComparisonKey.decode(it.comparisonKey)
            require(key[0] == it.goal.exercise.name) { "Goal exercise must match its comparison group" }
            require(if (key.size == 11) it.goal.countMethod == key[10] && it.goal.countPolicyVersion?.toString() == key[9]
                else it.goal.countMethod == null && it.goal.countPolicyVersion == null) { "Goal method must match its comparison group" }
            it.goal.validate(); require(it.goal.createdAtEpochMilliseconds != null && it.goal.createdAtEpochMilliseconds > 0)
        }
        require(state.plans.size <= maximumPlans && state.plans.map { it.id }.distinct().size == state.plans.size)
        state.plans.forEach { plan ->
            require(WorkoutCorrections.safeId(plan.id) && plan.title.length in 1..80 && plan.title.none(Char::isISOControl))
            require(plan.scheduledDay >= 0 && plan.items.size in 1..20 && plan.items.map { it.id }.distinct().size == plan.items.size)
            require(plan.workoutId == null || WorkoutCorrections.safeId(plan.workoutId))
            plan.items.forEach { item ->
                require(WorkoutCorrections.safeId(item.id) && item.sets in 1..100)
                require(item.countMethod == null && item.countPolicyVersion == null ||
                    WorkoutCountingMethod.isSupported(item.exercise, item.countMethod, item.countPolicyVersion))
                item.context.validate(); WorkoutGoal(item.exercise, item.metric, item.target).validate()
            }
        }
        state.mediaPolicy.validate()
        require(state.media.size <= 100 && state.media.map { it.id }.distinct().size == state.media.size)
        state.media.forEach { item ->
            require(WorkoutCorrections.safeId(item.id) && item.bytes > 0 && item.attachedAtEpochMilliseconds > 0)
            require(state.entries.any { it.measured.id == item.setId })
            require((item.sourceStartSeconds == null) == (item.sourceEndSeconds == null))
            require(item.sourceStartSeconds == null || item.sourceStartSeconds.isFinite() && item.sourceStartSeconds >= 0 &&
                item.sourceEndSeconds!!.isFinite() && item.sourceEndSeconds > item.sourceStartSeconds)
        }
    }
    fun register(state: WorkoutLibraryState, records: List<WorkoutSetRecord>): WorkoutLibraryState {
        validate(state)
        val entries = state.entries.associateBy { it.measured.id }.toMutableMap()
        var reviews = state.reviews
        for (record in records.filter { it.endReason != "running" && state.deletions.none { deletion -> deletion.setId == it.id } }) {
            val compact = compact(record)
            require(entries[record.id] == null || entries[record.id]!!.measured == compact) { "Immutable measured summary conflict" }
            if (entries[record.id] == null && record.trackedAtEpochMilliseconds != null) {
                state.defaultContexts.firstOrNull { it.exercise == record.exercise && record.trackedAtEpochMilliseconds >= it.selectedAtEpochMilliseconds }?.let { setting ->
                    reviews = WorkoutCorrections.append(compact, reviews, WorkoutReviewCorrection(context = setting.context), record.trackedAtEpochMilliseconds)
                }
            }
            entries[record.id] = WorkoutLibraryEntry(compact)
        }
        val next = state.copy(entries = entries.values.toList(), reviews = reviews)
        validate(next)
        return if (next == state) state else next.copy(revision = state.revision + 1)
    }
    private fun rawReviewed(state: WorkoutLibraryState): List<WorkoutReviewedSet> {
        val corrections = state.reviews.events.associate { it.setId to it.correction }
        return state.entries.flatMap { WorkoutCorrections.project(it.measured, corrections[it.measured.id] ?: WorkoutReviewCorrection()) }
    }
    /** Source partitions retain their own dates and per-set evidence, even after presentation merges. */
    fun sourceSets(state: WorkoutLibraryState): List<WorkoutReviewedSet> = rawReviewed(state)

    fun reviewed(state: WorkoutLibraryState): List<WorkoutReviewedSet> {
        val raw = rawReviewed(state)
        val grouped = state.merges.flatMap { it.setIds }.toSet()
        return raw.filter { it.id !in grouped } + state.merges.map { group ->
            val members = raw.filter { it.id in group.setIds }
            val first = members.first()
            first.copy(id = group.id, sourceSetIds = members.flatMap { it.sourceSetIds }.distinct(),
                attemptIds = members.flatMap { it.attemptIds }, attempts = members.flatMap { it.attempts },
                ownsHold = members.any { it.ownsHold }, measuredCompletedReps = members.sumOf { it.measuredCompletedReps },
                userReportedCompletedReps = members.sumOf { it.userReportedCompletedReps },
                practiceAttempts = members.sumOf { it.practiceAttempts }, excludedAttempts = members.sumOf { it.excludedAttempts },
                holdSeconds = members.mapNotNull { it.holdSeconds }.maxOrNull(),
                totalHoldSeconds = members.mapNotNull { it.totalHoldSeconds }.takeIf { it.isNotEmpty() }?.sum(),
                elbowExcursionDegrees = members.mapNotNull { it.elbowExcursionDegrees }.maxOrNull(),
                meanReturnSeconds = null, // Individual source records retain timing; no averaging unknown sample counts.
                trackingCoverage = members.mapNotNull { it.trackingCoverage }.minOrNull().takeIf { members.all { it.trackingCoverage != null } },
                trackedAtEpochMilliseconds = members.mapNotNull { it.trackedAtEpochMilliseconds }.minOrNull().takeIf { members.all { it.trackedAtEpochMilliseconds != null } },
                trackedEndedAtEpochMilliseconds = null, isMerged = true,
                workoutId = members.map { it.workoutId }.distinct().singleOrNull())
        }
    }
    private val ordering = compareByDescending<WorkoutReviewedSet> { it.trackedAtEpochMilliseconds ?: Long.MIN_VALUE }.thenBy { it.id }
    fun page(state: WorkoutLibraryState, cursor: WorkoutHistoryCursor? = null, limit: Int = 25): WorkoutHistoryPage {
        require(limit in 1..100)
        if (cursor != null) require(cursor.revision == state.revision) { "History changed; refresh the page" }
        val all = reviewed(state).sortedWith(ordering)
        val selected = all.filter { cursor == null ||
            (it.trackedAtEpochMilliseconds ?: Long.MIN_VALUE) < (cursor.epochMilliseconds ?: Long.MIN_VALUE) ||
            it.trackedAtEpochMilliseconds == cursor.epochMilliseconds && it.id > cursor.id }.take(limit + 1)
        val page = selected.take(limit)
        val next = page.lastOrNull()?.takeIf { selected.size > limit }?.let { WorkoutHistoryCursor(it.trackedAtEpochMilliseconds, it.id, state.revision) }
        return WorkoutHistoryPage(page, next, state.revision)
    }
    fun retainedMedia(policy: WorkoutMediaPolicy, media: List<WorkoutLocalMedia>, now: Long): List<WorkoutLocalMedia> {
        policy.validate(); require(now > 0)
        if (!policy.enabled) return emptyList()
        var bytes = 0L
        return media.filter { it.attachedAtEpochMilliseconds <= now && now - it.attachedAtEpochMilliseconds <= policy.maximumAgeDays * 86400000L }
            .sortedWith(compareByDescending<WorkoutLocalMedia> { it.attachedAtEpochMilliseconds }.thenBy { it.id })
            .take(policy.maximumClips).filter { item ->
                if (item.bytes > policy.maximumBytes - bytes) false else { bytes += item.bytes; true }
            }
    }
    fun maintain(state: WorkoutLibraryState, now: Long): WorkoutLibraryState {
        validate(state)
        val media = retainedMedia(state.mediaPolicy, state.media, now)
        return if (media == state.media) state else state.copy(media = media, revision = state.revision + 1)
    }
    private fun deleteSources(state: WorkoutLibraryState, sources: List<String>, at: Long): WorkoutLibraryState {
        require(sources.size in 1..1000 && sources.distinct().size == sources.size && sources.all(WorkoutCorrections::safeId))
        val ids = sources.toSet(); val alreadyDeleted = state.deletions.map { it.setId }.toSet()
        val affectedParts = rawReviewed(state).filter { it.sourceSetId in ids }.map { it.id }.toSet()
        return state.copy(entries = state.entries.filter { it.measured.id !in ids },
            deletions = state.deletions + sources.filter { it !in alreadyDeleted }.map { WorkoutDeletion(it, at) },
            media = state.media.filter { it.setId !in ids }, merges = state.merges.filter { group -> group.setIds.none { it in affectedParts } })
    }
    fun apply(state: WorkoutLibraryState, command: WorkoutLibraryCommand): WorkoutLibraryState {
        validate(state); require(command.atEpochMilliseconds > 0)
        val record = command.setId?.let { id -> state.entries.singleOrNull { it.measured.id == id }?.measured }
        val next = when (command.action) {
            "review" -> state.copy(reviews = WorkoutCorrections.append(requireNotNull(record), state.reviews, requireNotNull(command.correction), command.atEpochMilliseconds))
            "undo" -> state.copy(reviews = WorkoutCorrections.undo(requireNotNull(record), state.reviews, command.atEpochMilliseconds))
            "delete" -> deleteSources(state, listOf(requireNotNull(command.setId)), command.atEpochMilliseconds)
            "deleteSources" -> deleteSources(state, command.setIds, command.atEpochMilliseconds)
            "merge" -> {
                val id = requireNotNull(command.id); require(WorkoutCorrections.safeId(id))
                val members = rawReviewed(state).filter { it.id in command.setIds }
                require(members.size == command.setIds.size && WorkoutCorrections.canMerge(members)) { "Merge requires matching exercise and explicit comparable context" }
                require(state.merges.none { group -> group.setIds.any { it in command.setIds } })
                state.copy(merges = state.merges + WorkoutSetMerge(id, command.setIds))
            }
            "unmerge" -> state.copy(merges = state.merges.filter { it.id != command.id })
            "assignWorkout" -> {
                val id = requireNotNull(command.id); require(WorkoutCorrections.safeId(id))
                require(command.setIds.size >= 2 && command.setIds.distinct().size == command.setIds.size)
                var reviews = state.reviews
                command.setIds.forEach { source ->
                    val measured = requireNotNull(state.entries.firstOrNull { it.measured.id == source }?.measured)
                    reviews = WorkoutCorrections.append(measured, reviews, WorkoutCorrections.current(reviews, source).copy(workoutId = id), command.atEpochMilliseconds)
                }
                state.copy(reviews = reviews)
            }
            "goal" -> {
                val input = requireNotNull(command.goal)
                // The selected comparison group supplies this automatically;
                // no extra method/version setting is needed in a goal form.
                val goal = input.copy(goal = WorkoutComparisonKey.bindGoal(input.comparisonKey, input.goal))
                state.copy(goals = state.goals.filter { it.comparisonKey != goal.comparisonKey } + goal)
            }
            "plan" -> { val plan = requireNotNull(command.plan); state.copy(plans = state.plans.filter { it.id != plan.id } + plan) }
            "defaultContext" -> {
                val source = requireNotNull(rawReviewed(state).firstOrNull { it.comparisonKey == command.id }) { "Choose a comparable context" }
                val setting = WorkoutDefaultContext(source.exercise, source.context.copy(effort = null, readiness = WorkoutReadiness.unrecorded, recoveryNote = ""), command.atEpochMilliseconds)
                state.copy(defaultContexts = state.defaultContexts.filter { it.exercise != source.exercise } + setting)
            }
            "clearDefaultContexts" -> state.copy(defaultContexts = emptyList())
            "deletePlan" -> state.copy(plans = state.plans.filter { it.id != command.id })
            "mediaPolicy" -> state.copy(mediaPolicy = requireNotNull(command.mediaPolicy))
            "attachMedia" -> {
                require(state.mediaPolicy.enabled) { "Enable local review media before attaching a recording" }
                val media = requireNotNull(command.media)
                state.copy(media = state.media.filter { it.id != media.id } + media)
            }
            "deleteMedia" -> state.copy(media = state.media.filter { it.id != command.id })
            "diagnosticsConsent" -> state.copy(diagnosticsConsent = requireNotNull(command.consent))
            else -> throw IllegalArgumentException("Unknown library command")
        }
        val retained = next.copy(media = retainedMedia(next.mediaPolicy, next.media, command.atEpochMilliseconds))
        validate(retained)
        return if (retained == state) state else retained.copy(revision = state.revision + 1)
    }
}

/** Stable FFI; parsing/validation is never on the camera frame path. */
class WorkoutLibraryBridge {
    fun emptyJson(): String = WorkoutRecordCodec.json.encodeToString(WorkoutLibraryState())
    @Throws(IllegalArgumentException::class) fun validateJson(input: String): String {
        val state = WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(input)
        WorkoutLibrary.validate(state); return WorkoutRecordCodec.json.encodeToString(state)
    }
    @Throws(IllegalArgumentException::class) fun registerJson(stateJson: String, recordsJson: String): String =
        WorkoutRecordCodec.json.encodeToString(WorkoutLibrary.register(WorkoutRecordCodec.json.decodeFromString(stateJson), WorkoutRecordCodec.json.decodeFromString(recordsJson)))
    @Throws(IllegalArgumentException::class) fun applyJson(stateJson: String, commandJson: String): String =
        WorkoutRecordCodec.json.encodeToString(WorkoutLibrary.apply(WorkoutRecordCodec.json.decodeFromString(stateJson), WorkoutRecordCodec.json.decodeFromString(commandJson)))
    @Throws(IllegalArgumentException::class) fun pageJson(stateJson: String, cursorJson: String?, limit: Int): String =
        WorkoutRecordCodec.json.encodeToString(WorkoutLibrary.page(WorkoutRecordCodec.json.decodeFromString(stateJson), cursorJson?.let { WorkoutRecordCodec.json.decodeFromString(it) }, limit))
    @Throws(IllegalArgumentException::class) fun insightsJson(stateJson: String, comparisonKey: String?, nowEpochMilliseconds: Long, utcOffsetMinutes: Int): String =
        WorkoutRecordCodec.json.encodeToString(WorkoutInsights.report(WorkoutRecordCodec.json.decodeFromString(stateJson), comparisonKey, nowEpochMilliseconds, utcOffsetMinutes))
    @Throws(IllegalArgumentException::class) fun exportJson(stateJson: String, summariesJson: String? = null, gripCorrectionsJson: String? = null): String = WorkoutLibraryBackup.export(
        WorkoutRecordCodec.json.decodeFromString(stateJson), summariesJson?.let { WorkoutRecordCodec.json.decodeFromString(it) },
        gripCorrectionsJson?.let { WorkoutRecordCodec.json.decodeFromString(it) } ?: emptyList())
    @Throws(IllegalArgumentException::class) fun backupGripCorrectionsJson(backupJson: String): String = WorkoutRecordCodec.json.encodeToString(WorkoutLibraryBackup.decode(backupJson).gripCorrections)
    @Throws(IllegalArgumentException::class) fun maintainJson(stateJson: String, nowEpochMilliseconds: Long): String =
        WorkoutRecordCodec.json.encodeToString(WorkoutLibrary.maintain(WorkoutRecordCodec.json.decodeFromString(stateJson), nowEpochMilliseconds))
    @Throws(IllegalArgumentException::class) fun diagnosticsJson(stateJson: String): String =
        WorkoutRecordCodec.json.encodeToString(WorkoutDiagnostics.export(WorkoutRecordCodec.json.decodeFromString(stateJson)))
    @Throws(IllegalArgumentException::class) fun restoreJson(stateJson: String, backupJson: String): String =
        WorkoutRecordCodec.json.encodeToString(WorkoutLibraryBackup.restore(WorkoutRecordCodec.json.decodeFromString(stateJson), backupJson))
}
