package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.abs

@Serializable data class WorkoutSessionSummary(val id: String, val setIds: List<String>, val startedAtEpochMilliseconds: Long?,
    val endedAtEpochMilliseconds: Long?, val exercises: List<ExerciseCounter.Exercise>, val measuredCompletedReps: Int,
    val userReportedCompletedReps: Int, val practiceAttempts: Int, val observedHoldSeconds: Double?,
    val restSeconds: List<Double>, val interruptedSets: Int, val startIntervalsSeconds: List<Double> = emptyList())
@Serializable data class WorkoutComparablePoint(val workoutId: String, val epochMilliseconds: Long,
    val longestHoldSeconds: Double?, val measuredCompletedReps: Int, val elbowExcursionDegrees: Double?,
    val meanReturnSeconds: Double?, val trackingCoverage: Double?)
@Serializable data class WorkoutWeeklyPractice(val firstDay: Long, val days: Int, val workouts: Int, val completedReps: Int, val observedHoldSeconds: Double)
@Serializable data class WorkoutPlanResult(val planId: String, val title: String, val status: String,
    val completedItems: Int, val totalItems: Int, val measuredValues: List<Double>, val workoutId: String?,
    val candidateWorkoutIds: List<String> = emptyList())
@Serializable data class WorkoutCoachFocus(val title: String, val message: String, val evidenceSetIds: List<String>,
    val metric: String?, val recentMedian: Double?, val sampleSessions: Int, val limitation: String)
@Serializable data class WorkoutLibraryInsights(val sessions: List<WorkoutSessionSummary>,
    val comparisonKeys: List<String>, val selectedComparisonKey: String?, val unclassifiedSets: Int,
    val trend: List<WorkoutComparablePoint>, val weekly: List<WorkoutWeeklyPractice>, val practiceDays: Int,
    val consistencyDaysLast28: Int, val plateau: Boolean?, val goal: WorkoutGoal?, val suggestedGoal: WorkoutGoal?,
    val goalBest: Double?, val goalAchieved: Boolean, val milestones: List<WorkoutMilestone>,
    val focus: WorkoutCoachFocus, val plans: List<WorkoutPlanResult>, val policyVersion: Int = 1)

/** Descriptive camera evidence. No fitness/medical prescription or anatomical validity claim. */
object WorkoutInsights {
    const val restGapMilliseconds = 45 * 60 * 1000L
    private const val dayMilliseconds = 86400000L
    fun localDay(epoch: Long, offsetMinutes: Int): Long {
        require(epoch > 0 && offsetMinutes in -840..840)
        return floor((epoch.toDouble() + offsetMinutes * 60000.0) / dayMilliseconds).toLong()
    }
    fun sessions(sets: List<WorkoutReviewedSet>): List<WorkoutSessionSummary> {
        val groups = linkedMapOf<String, MutableList<WorkoutReviewedSet>>()
        var current: String? = null
        var previous: Long? = null
        for (set in sets.sortedWith(compareBy<WorkoutReviewedSet> { it.trackedAtEpochMilliseconds ?: Long.MAX_VALUE }.thenBy { it.id })) {
            val time = set.trackedAtEpochMilliseconds
            val id = set.workoutId ?: if (time == null) "undated-${set.id}" else {
                if (current == null || previous == null || time - previous > restGapMilliseconds) current = "session-${set.sourceSetId}"
                current!!
            }
            groups.getOrPut(id) { mutableListOf() }.add(set)
            if (set.workoutId == null && time != null) previous = time
        }
        return groups.map { (id, members) ->
            val sources = members.distinctBy { it.sourceSetId }
            val dates = sources.mapNotNull { it.trackedAtEpochMilliseconds }.sorted()
            val ordered = sources.sortedBy { it.trackedAtEpochMilliseconds ?: Long.MAX_VALUE }
            val rests = ordered.zipWithNext().mapNotNull { (a, b) ->
                val end = a.trackedEndedAtEpochMilliseconds; val start = b.trackedAtEpochMilliseconds
                if (end != null && start != null && start >= end) (start - end) / 1000.0 else null
            }
            val knownEnd = sources.mapNotNull { it.trackedEndedAtEpochMilliseconds }.maxOrNull().takeIf { sources.all { it.trackedEndedAtEpochMilliseconds != null } }
            WorkoutSessionSummary(id, members.map { it.id }, dates.minOrNull(), knownEnd, members.map { it.exercise }.distinct(),
                members.sumOf { it.measuredCompletedReps }, members.sumOf { it.userReportedCompletedReps }, members.sumOf { it.practiceAttempts },
                members.mapNotNull { it.totalHoldSeconds }.takeIf { it.isNotEmpty() }?.sum(),
                rests, sources.count { it.endReason !in listOf("manual", "disengaged", "sourceEnded") }, dates.zipWithNext { a, b -> (b - a) / 1000.0 })
        }.sortedByDescending { it.startedAtEpochMilliseconds ?: Long.MIN_VALUE }
    }
    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted(); val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
    fun goalValue(goal: WorkoutGoal, sets: List<WorkoutReviewedSet>): Double? {
        val eligible = sets.filter { (it.trackingCoverage ?: 0.0) >= .8 && it.trackedAtEpochMilliseconds?.let { time -> time >= (goal.createdAtEpochMilliseconds ?: Long.MAX_VALUE) } == true }
        if (eligible.isEmpty()) return null
        return if (goal.metric == WorkoutGoalMetric.completedReps) eligible.sumOf { it.measuredCompletedReps }.toDouble() else eligible.mapNotNull { it.holdSeconds }.maxOrNull()
    }
    fun report(state: WorkoutLibraryState, key: String?, now: Long, offset: Int): WorkoutLibraryInsights {
        WorkoutLibrary.validate(state); require(now > 0 && offset in -840..840)
        val sets = WorkoutLibrary.sourceSets(state)
        val sessions = sessions(sets)
        val sessionBySetId = buildMap { sessions.forEach { session -> session.setIds.forEach { put(it, session.id) } } }
        val keys = sets.mapNotNull { it.comparisonKey }.distinct().sorted()
        if (key != null) {
            require(key.length <= 1024)
            val parts = WorkoutRecordCodec.json.decodeFromString<List<String>>(key)
            require(parts.size == 10 && parts[0] in ExerciseCounter.Exercise.entries.map { it.name })
        }
        val selected = key?.takeIf { it in keys } ?: keys.firstOrNull()
        val comparable = sets.filter { selected != null && it.comparisonKey == selected &&
            it.trackedAtEpochMilliseconds != null && it.trackedAtEpochMilliseconds <= now && (it.trackingCoverage ?: 0.0) >= .8 }
        val comparableBySession = comparable.groupBy { sessionBySetId.getValue(it.id) }
        val points = sessions.mapNotNull { session ->
            val members = comparableBySession[session.id].orEmpty()
            if (members.isEmpty() || session.startedAtEpochMilliseconds == null) null else WorkoutComparablePoint(session.id,
                members.minOf { it.trackedAtEpochMilliseconds!! }, members.mapNotNull { it.holdSeconds }.maxOrNull(),
                members.sumOf { it.measuredCompletedReps }, members.mapNotNull { it.elbowExcursionDegrees }.maxOrNull(),
                members.mapNotNull { it.meanReturnSeconds }.takeIf { it.isNotEmpty() }?.average(),
                members.mapNotNull { it.trackingCoverage }.minOrNull())
        }.sortedBy { it.epochMilliseconds }.takeLast(100)
        val latest = points.takeLast(5)
        val repsMedian = median(latest.map { it.measuredCompletedReps.toDouble() })
        val holdMedian = median(latest.mapNotNull { it.longestHoldSeconds })
        val exercise = comparable.firstOrNull()?.exercise
        val metric = if ((repsMedian ?: 0.0) > 0) WorkoutGoalMetric.completedReps else WorkoutGoalMetric.holdSeconds
        val baseline = if (metric == WorkoutGoalMetric.completedReps) repsMedian else holdMedian
        val suggestion = if (exercise != null && latest.size >= 3 && baseline != null && baseline > 0) WorkoutGoal(exercise, metric,
            if (metric == WorkoutGoalMetric.completedReps) ceil(baseline + 1).coerceAtMost(1000.0) else ceil(baseline + maxOf(1.0, baseline * .1)).coerceAtMost(3600.0), now) else null
        val chosen = state.goals.firstOrNull { it.comparisonKey == selected }?.goal
        val goalValues = comparableBySession.values.mapNotNull { members -> chosen?.let { goal -> goalValue(goal, members) } }
        val goalBest = goalValues.maxOrNull()
        val plateau = if (latest.size >= 5 && baseline != null && baseline > 0) {
            val values = if (metric == WorkoutGoalMetric.completedReps) latest.map { it.measuredCompletedReps.toDouble() } else latest.mapNotNull { it.longestHoldSeconds }
            (values.size == 5 && values.all { abs(it - baseline) <= maxOf(if (metric == WorkoutGoalMetric.completedReps) 0.0 else .5, baseline * .05) })
        } else null
        val evidence = comparable.sortedBy { it.trackedAtEpochMilliseconds }.takeLast(10).map { it.sourceSetId }.distinct()
        val focus = when {
            sets.isEmpty() -> WorkoutCoachFocus("Record your baseline", "A visible hang or support hold is useful practice, even before your first completed rep.", emptyList(), null, null, 0, "Camera measurements do not verify exercise form.")
            selected == null -> WorkoutCoachFocus("Add comparison context", "Record assistance, load, equipment and camera view to compare like sessions.", emptyList(), null, null, 0, "Unclassified sets remain in history but do not establish a comparable baseline.")
            latest.isEmpty() -> WorkoutCoachFocus("Improve recording coverage", "Keep both arms and the apparatus visible for another comparable recording.", sets.filter { it.comparisonKey == selected }.map { it.sourceSetId }.distinct().takeLast(5), null, null, 0, "Missing footage cannot establish a movement weakness.")
            latest.size < 3 -> WorkoutCoachFocus("Build a repeatable baseline", "Review another comparable session before choosing a suggestion from recent results.", evidence, metric.name, baseline, latest.size, "At least three observed sessions are required for a suggestion.")
            chosen != null && goalBest != null && goalBest >= chosen.target -> WorkoutCoachFocus("Review your reached goal", "Your chosen target is reached. Keep it or explicitly choose a new target after reviewing recent sessions.", evidence, chosen.metric.name, baseline, latest.size, "Targets are user decisions; app suggestions are descriptive defaults.")
            plateau == true -> WorkoutCoachFocus("Review a stable trend", "The last five comparable sessions show similar observed results. Review context and recording quality before changing a goal.", evidence, metric.name, baseline, latest.size, "A stable camera metric is not a diagnosis of strength, fatigue or training needs.")
            metric == WorkoutGoalMetric.holdSeconds -> WorkoutCoachFocus("Track your observed hold", "Use the recent comparable hold baseline to choose an achievable tracking target. Partial attempts remain practice.", evidence, metric.name, baseline, latest.size, "Hold duration is observed time; gaps and unrecorded intervals are excluded.")
            else -> WorkoutCoachFocus("Compare completed cycles", "Review completed cycles and return timing across your recent comparable sessions.", evidence, metric.name, baseline, latest.size, "Camera counts do not verify depth, lockout or chin clearance.")
        }
        val today = localDay(now, offset)
        val dated = sets.filter { it.trackedAtEpochMilliseconds != null && it.trackedAtEpochMilliseconds <= now &&
            (it.totalHoldSeconds ?: 0.0) > 0 || it.trackedAtEpochMilliseconds != null && it.trackedAtEpochMilliseconds <= now &&
            (it.measuredCompletedReps > 0 || it.practiceAttempts > 0) }
        val days = dated.map { localDay(it.trackedAtEpochMilliseconds!!, offset) }.toSet()
        val weekly = dated.groupBy { localDay(it.trackedAtEpochMilliseconds!!, offset).let { day -> day - ((day + 3) % 7) } }
            .map { (day, members) -> WorkoutWeeklyPractice(day, members.map { localDay(it.trackedAtEpochMilliseconds!!, offset) }.distinct().size,
                sessions.count { session -> session.setIds.any { id -> members.any { it.id == id } } }, members.sumOf { it.measuredCompletedReps }, members.sumOf { it.totalHoldSeconds ?: 0.0 }) }
            .sortedBy { it.firstDay }.takeLast(12)
        fun milestone(id: String, title: String, test: (WorkoutReviewedSet) -> Boolean): WorkoutMilestone {
            val matches = sets.filter(test)
            return WorkoutMilestone(id, title, matches.isNotEmpty(), if (matches.any { it.trackedAtEpochMilliseconds == null }) null else matches.mapNotNull { it.trackedAtEpochMilliseconds }.minOrNull())
        }
        val milestones = listOf(milestone("practice", "First recorded practice") { (it.totalHoldSeconds ?: 0.0) > 0 || it.practiceAttempts > 0 || it.measuredCompletedReps > 0 },
            milestone("rep", "First measured completed cycle") { it.measuredCompletedReps > 0 }) +
            listOf(5, 10, 20, 30).map { value -> milestone("hold-$value", "$value seconds of observed hold") { (it.holdSeconds ?: 0.0) >= value } }
        val sessionsById = sessions.associateBy { it.id }
        val sessionOrder = sessions.mapIndexed { index, session -> session.id to index }.toMap()
        val eligibleByDay = sets.filter { (it.trackingCoverage ?: 0.0) >= .8 && it.trackedAtEpochMilliseconds?.let { time -> time <= now } == true }
            .groupBy { localDay(it.trackedAtEpochMilliseconds!!, offset) }
        val plans = state.plans.map { plan ->
            fun matches(set: WorkoutReviewedSet, item: WorkoutPlanItem): Boolean =
                set.comparisonKey != null && set.comparisonKey == item.context.comparisonKey(item.exercise, set.metricVersion, set.countPolicyVersion)
            val eligibleBySession = eligibleByDay[plan.scheduledDay].orEmpty().filter { set -> plan.items.any { matches(set, it) } }
                .groupBy { sessionBySetId.getValue(it.id) }
            val candidateSessions = eligibleBySession.keys.sortedBy { sessionOrder.getValue(it) }.map { sessionsById.getValue(it) }
            // A workout may satisfy one plan only when explicitly linked; unlinked plans remain descriptive previews.
            val session = if (plan.workoutId != null) candidateSessions.firstOrNull { it.id == plan.workoutId }
                else candidateSessions.firstOrNull()
            val members = eligibleBySession[session?.id].orEmpty()
            val values = plan.items.map { item ->
                val matching = members.filter { matches(it, item) }
                matching.count { (if (item.metric == WorkoutGoalMetric.completedReps) it.measuredCompletedReps.toDouble() else it.holdSeconds ?: 0.0) >= item.target }.toDouble()
            }
            val completed = values.zip(plan.items).count { (value, item) -> value >= item.sets }
            WorkoutPlanResult(plan.id, plan.title,
                if (plan.workoutId != null && completed == plan.items.size) "completed" else if (session != null) "observed"
                else if (plan.workoutId != null) "linkedNotEligible" else if (plan.scheduledDay < today) "notRecorded" else "planned",
                completed, plan.items.size, values, plan.workoutId, candidateSessions.take(100).map { it.id })
        }
        return WorkoutLibraryInsights(sessions.take(100), keys, selected, sets.count { it.comparisonKey == null }, points, weekly, days.size,
            days.count { it in (today - 27)..today }, plateau, chosen, suggestion, goalBest, chosen != null && goalBest != null && goalBest >= chosen.target,
            milestones, focus, plans)
    }
}
