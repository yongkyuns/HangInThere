package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Read-only presentation policy shared by both apps. Ratings describe recorded
 * repeatability, never exercise form, strength, effort or model confidence. */
@Serializable data class WorkoutQuality(val level: String, val title: String, val explanation: String)
@Serializable data class WorkoutCoaching(val title: String, val message: String)
@Serializable data class WorkoutRepDigest(val id: String, val ordinal: Int, val status: String,
    val outwardSeconds: Double?, val returnSeconds: Double?, val quality: WorkoutQuality,
    val totalSeconds: Double? = if (outwardSeconds != null && returnSeconds != null)
        (outwardSeconds + returnSeconds).takeIf { it.isFinite() } else null)
@Serializable data class WorkoutSetDigest(val id: String, val sourceSetIds: List<String>,
    val exercise: ExerciseCounter.Exercise, val epochMilliseconds: Long?, val completedReps: Int,
    val reportedReps: Int, val practiceAttempts: Int, val holdSeconds: Double?,
    val outwardSeconds: Double?, val returnSeconds: Double?, val excursionDegrees: Double?,
    val coverage: Double?, val quality: WorkoutQuality, val coaching: WorkoutCoaching,
    val reps: List<WorkoutRepDigest> = emptyList())
@Serializable data class WorkoutSessionDigest(val id: String, val epochMilliseconds: Long?,
    val completedReps: Int, val setCount: Int, val exercises: List<ExerciseCounter.Exercise>,
    val quality: WorkoutQuality, val sets: List<WorkoutSetDigest>)
@Serializable data class WorkoutPracticeDay(val day: Long, val workoutIds: List<String>,
    val completedReps: Int, val quality: WorkoutQuality?)
@Serializable data class WorkoutJourneyCursor(val afterId: String, val revision: Long, val day: Long?)
@Serializable data class WorkoutJourneyPage(val workouts: List<WorkoutSessionDigest>, val nextCursor: WorkoutJourneyCursor?)
@Serializable data class WorkoutJourneyReport(val workouts: List<WorkoutSessionDigest>,
    val days: List<WorkoutPracticeDay>, val practiceDays: Int, val completedReps: Int,
    val workoutCount: Int, val coaching: WorkoutCoaching, val policyVersion: Int = 1,
    val nextCursor: WorkoutJourneyCursor? = null)

object WorkoutJourney {
    const val minimumCoverage = .8
    private const val maximumWorkouts = 100
    private fun quality(level: String) = when (level) {
        "steady" -> WorkoutQuality(level, "Consistent", "Recorded reps have similar timing and projected range. Exercise form is not graded.")
        "pace" -> WorkoutQuality(level, "Varying pace", "Recorded rep durations differ by more than 35% of their median. This is a timing observation, not a form grade.")
        "range" -> WorkoutQuality(level, "Varying range", "Recorded elbow excursions differ by more than 20% of their median. These are camera-plane measurements.")
        "limited" -> WorkoutQuality(level, "Limited view", "Too little observed time to assess consistency. This is a recording issue, not a poor workout rating.")
        "practice" -> WorkoutQuality(level, "Practice", "Holds and incomplete attempts are progress to track. They are not completed reps.")
        "tracking" -> WorkoutQuality(level, "In progress", "The set is still being recorded. Its rating is not final.")
        else -> WorkoutQuality("recorded", "Recorded", "At least two completed reps with sufficient bilateral coverage and timing are needed to assess consistency. Exercise form is not graded.")
    }
    private fun finite(value: Double?) = value?.takeIf { it.isFinite() && it >= 0 }
    private fun coverage(value: Double?) = value?.takeIf { it.isFinite() && it in 0.0..1.0 }
    private fun median(values: List<Double>): Double? {
        val sorted = values.filter { it.isFinite() && it >= 0 }.sorted()
        if (sorted.isEmpty()) return null
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
    private fun varies(values: List<Double>, fraction: Double): Boolean {
        val middle = median(values) ?: return false
        return values.size >= 2 && middle > 0 && (values.max() - values.min()) / middle > fraction
    }
    private fun aggregate(values: List<WorkoutQuality>): WorkoutQuality = when {
        values.any { it.level == "tracking" } -> quality("tracking")
        values.any { it.level == "limited" } -> quality("limited")
        values.any { it.level == "range" } -> quality("range")
        values.any { it.level == "pace" } -> quality("pace")
        values.isNotEmpty() && values.all { it.level == "steady" } -> quality("steady")
        values.isNotEmpty() && values.all { it.level == "practice" } -> quality("practice")
        else -> quality("recorded")
    }
    private fun coaching(exercise: ExerciseCounter.Exercise, count: Int, hold: Double?, rating: WorkoutQuality): WorkoutCoaching {
        val holdName = if (exercise == ExerciseCounter.Exercise.pullUp) "hang" else "support hold"
        return when (rating.level) {
            "tracking" -> WorkoutCoaching("Keep going", "Your summary will update when this set ends.")
            "limited" -> WorkoutCoaching("Keep both arms in view", "Include your hands and equipment so the next recording can show your whole movement.")
            "pace" -> WorkoutCoaching("Find a repeatable rhythm", "Use your first completed rep's pace as a reference. Tap a rep to compare its phases.")
            "range" -> WorkoutCoaching("Match your first rep's range", "Your recorded range changed across the set. Compare the rep curves before adjusting your movement.")
            else -> if (count > 0) WorkoutCoaching("Next time: match $count ${if (count == 1) "rep" else "reps"}",
                "Repeat your recorded result with a complete return. Build repeatability before increasing your target.")
            else if (hold != null && hold > 0) WorkoutCoaching(if (hold >= 1) "Build on your ${hold.toInt()}s $holdName" else "Build on your $holdName",
                "Try matching this observed hold next time. A full rep can come later.")
            else WorkoutCoaching("Start with a $holdName", "Point the camera at the equipment. Holds and attempts are tracked automatically.")
        }
    }
    fun set(record: WorkoutSetRecord): WorkoutSetDigest = summarize(record.id, listOf(record.id), record.exercise,
        record.trackedAtEpochMilliseconds, record.reps, record.reps.map { rep ->
            WorkoutReviewedAttempt(rep.id, rep.ordinal, WorkoutAttemptClassification.automatic,
                rep.isCompleteForAnalytics, false, rep.isCompleteForAnalytics && WorkoutEvidence.assess(rep, record.endReason).angleComparisonEligible)
        }, record.activity?.let { if (record.exercise == ExerciseCounter.Exercise.pullUp) it.longestHangSeconds else it.longestSupportSeconds },
        record.activity?.let { if (it.elapsedSeconds > 0) coverage(it.observedSeconds / it.elapsedSeconds) else null },
        record.endReason == "running", true)

    private fun summarize(id: String, sourceIds: List<String>, exercise: ExerciseCounter.Exercise, at: Long?,
        reps: List<RepRecord>, attempts: List<WorkoutReviewedAttempt>, hold: Double?, recordedCoverage: Double?,
        running: Boolean, includeReps: Boolean): WorkoutSetDigest {
        val indexed = attempts.associateBy { it.id }
        val completed = reps.filter { indexed[it.id]?.measuredCompleted == true }
        // Missing duration coverage is unknown, never a zero score or full coverage.
        val timed = completed.filter { indexed[it.id]?.metricsEligible == true &&
            (coverage(it.durationCoverage) ?: -1.0) >= minimumCoverage &&
            finite(it.outboundSeconds)?.let { seconds -> seconds > 0 } == true &&
            finite(it.returnSeconds)?.let { seconds -> seconds > 0 } == true }
        val observedCoverage = recordedCoverage ?: completed.takeIf { it.isNotEmpty() && it.all { rep -> coverage(rep.durationCoverage) != null } }
            ?.let { rows -> rows.minOf { it.durationCoverage!! } }
        val rating = when {
            running -> quality("tracking")
            observedCoverage != null && observedCoverage < minimumCoverage -> quality("limited")
            completed.isEmpty() && (finite(hold)?.let { it > 0 } == true || attempts.any { !it.measuredCompleted && !it.userReportedCompleted && it.classification != WorkoutAttemptClassification.excluded }) -> quality("practice")
            timed.size < 2 || timed.size != completed.size -> quality("recorded")
            varies(timed.mapNotNull(WorkoutEvidence::comparableElbowExcursion), .2) -> quality("range")
            varies(timed.map { it.outboundSeconds!! + it.returnSeconds!! }, .35) -> quality("pace")
            else -> quality("steady")
        }
        val digests = if (!includeReps) emptyList() else reps.mapNotNull { rep -> indexed[rep.id]?.let { attempt ->
            val status = when {
                attempt.classification == WorkoutAttemptClassification.excluded -> "excluded"
                attempt.userReportedCompleted -> "reported"
                attempt.measuredCompleted -> "completed"
                WorkoutEvidence.assess(rep).status == WorkoutAttemptStatus.partialMovement -> "practice"
                else -> "unobserved"
            }
            WorkoutRepDigest(rep.id, rep.ordinal, status, finite(rep.outboundSeconds).takeIf { attempt.measuredCompleted },
                finite(rep.returnSeconds).takeIf { attempt.measuredCompleted },
                quality(if (attempt.measuredCompleted) if ((coverage(rep.durationCoverage) ?: 0.0) < minimumCoverage) "limited" else "recorded" else if (status == "practice") "practice" else "limited"))
        } }
        return WorkoutSetDigest(id, sourceIds, exercise, at, completed.size, attempts.count { it.userReportedCompleted },
            attempts.count { !it.measuredCompleted && !it.userReportedCompleted && it.classification != WorkoutAttemptClassification.excluded },
            finite(hold), median(timed.mapNotNull { it.outboundSeconds }), median(timed.mapNotNull { it.returnSeconds }),
            median(timed.mapNotNull(WorkoutEvidence::comparableElbowExcursion)), observedCoverage, rating,
            coaching(exercise, completed.size, finite(hold), rating), digests)
    }

    fun report(state: WorkoutLibraryState, now: Long, utcOffsetMinutes: Int): WorkoutJourneyReport {
        WorkoutLibrary.validate(state)
        val today = WorkoutInsights.localDay(now, utcOffsetMinutes)
        val records = state.entries.associate { it.measured.id to it.measured }
        // Presentation merges must not move source recordings between dates,
        // duplicate practice or reinterpret the number of sets in a workout.
        val reviewed = WorkoutLibrary.sourceSets(state).filter { it.trackedAtEpochMilliseconds?.let { at -> at <= now } != false }
        val sessions = WorkoutInsights.sessions(reviewed)
        val digests = reviewed.associate { it.id to reviewedDigest(it, records, false) }
        val workouts = sessions.take(maximumWorkouts).map { session ->
            val sets = session.setIds.mapNotNull { digests[it] }
            sessionDigest(session, sets)
        }
        val sessionBySet = buildMap { sessions.forEach { session -> session.setIds.forEach { put(it, session.id) } } }
        val sessionOrder = sessions.mapIndexed { index, session -> session.id to index }.toMap()
        val setsByDay = reviewed.filter { it.trackedAtEpochMilliseconds != null }.groupBy {
            WorkoutInsights.localDay(it.trackedAtEpochMilliseconds!!, utcOffsetMinutes)
        }
        val days = (today - 27..today).map { day ->
            val rows = setsByDay[day].orEmpty().filter {
                it.measuredCompletedReps > 0 || it.practiceAttempts > 0 || (it.totalHoldSeconds ?: 0.0) > 0
            }
            WorkoutPracticeDay(day, rows.map { sessionBySet.getValue(it.id) }.distinct().sortedBy { sessionOrder.getValue(it) }, rows.sumOf { it.measuredCompletedReps },
                rows.takeIf { it.isNotEmpty() }?.let { aggregate(it.map { row -> digests.getValue(row.id).quality }) })
        }
        val latest = workouts.firstOrNull()?.sets?.lastOrNull()
        return WorkoutJourneyReport(workouts, days, days.count { it.workoutIds.isNotEmpty() },
            days.sumOf { it.completedReps }, days.flatMap { it.workoutIds }.distinct().size,
            latest?.coaching ?: WorkoutCoaching("Your progress starts here", "Point the camera and start moving. Your sets, holds and completed reps appear here automatically."),
            nextCursor = workouts.lastOrNull()?.takeIf { sessions.size > maximumWorkouts }?.let { WorkoutJourneyCursor(it.id, state.revision, null) })
    }
    /** Independent day filtering and revision-bound cursors keep every colored
     * calendar day navigable, even beyond the initial overview window. */
    fun page(state: WorkoutLibraryState, now: Long, utcOffsetMinutes: Int, day: Long? = null,
        cursor: WorkoutJourneyCursor? = null, limit: Int = 25): WorkoutJourneyPage {
        WorkoutLibrary.validate(state)
        WorkoutInsights.localDay(now, utcOffsetMinutes)
        require(limit in 1..100)
        require(cursor == null || cursor.revision == state.revision && cursor.day == day) { "History changed; refresh your progress" }
        val views = WorkoutLibrary.sourceSets(state).filter { it.trackedAtEpochMilliseconds?.let { at -> at <= now } != false }
        val byId = views.associateBy { it.id }
        val sessions = WorkoutInsights.sessions(views).filter { session -> day == null || session.setIds.any { id ->
            byId.getValue(id).trackedAtEpochMilliseconds?.let { WorkoutInsights.localDay(it, utcOffsetMinutes) == day } == true
        } }
        val after = cursor?.let { value -> sessions.indexOfFirst { it.id == value.afterId }.also { require(it >= 0) { "History changed; refresh your progress" } } } ?: -1
        val selected = sessions.drop(after + 1).take(limit + 1)
        val records = state.entries.associate { it.measured.id to it.measured }
        val rows = selected.take(limit).map { session ->
            val sets = session.setIds.map { reviewedDigest(byId.getValue(it), records, false) }
            sessionDigest(session, sets)
        }
        return WorkoutJourneyPage(rows, rows.lastOrNull()?.takeIf { selected.size > limit }?.let { WorkoutJourneyCursor(it.id, state.revision, day) })
    }
    private fun sessionDigest(session: WorkoutSessionSummary, sets: List<WorkoutSetDigest>) =
        WorkoutSessionDigest(session.id, session.startedAtEpochMilliseconds, session.measuredCompletedReps,
            sets.size, session.exercises, aggregate(sets.map { it.quality }), sets)
    private fun reviewedDigest(view: WorkoutReviewedSet, records: Map<String, WorkoutSetRecord>, includeReps: Boolean): WorkoutSetDigest {
        val allowed = view.attemptIds.toSet()
        val reps = view.sourceSetIds.flatMap { records[it]?.reps.orEmpty() }.filter { it.id in allowed }
        return summarize(view.id, view.sourceSetIds, view.exercise, view.trackedAtEpochMilliseconds, reps,
            view.attempts, view.holdSeconds, coverage(view.trackingCoverage), false, includeReps)
    }
    fun reviewedSet(state: WorkoutLibraryState, id: String): WorkoutSetDigest {
        WorkoutLibrary.validate(state)
        val view = requireNotNull(WorkoutLibrary.sourceSets(state).firstOrNull { it.id == id }
            ?: WorkoutLibrary.reviewed(state).firstOrNull { it.id == id }) { "Set is no longer available" }
        return reviewedDigest(view, state.entries.associate { it.measured.id to it.measured }, true)
    }
}

/** Summaries cross the Apple ABI only when review is opened, never per frame. */
class WorkoutJourneyBridge {
    @Throws(IllegalArgumentException::class)
    fun setJson(recordJson: String): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutJourney.set(WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(recordJson)))
    @Throws(IllegalArgumentException::class)
    fun reviewedSetJson(stateJson: String, setId: String): String = WorkoutRecordCodec.json.encodeToString(
        WorkoutJourney.reviewedSet(WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(stateJson), setId))
    @Throws(IllegalArgumentException::class)
    fun pageJson(stateJson: String, nowEpochMilliseconds: Long, utcOffsetMinutes: Int, day: Long?, cursorJson: String?): String =
        WorkoutRecordCodec.json.encodeToString(WorkoutJourney.page(WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(stateJson),
            nowEpochMilliseconds, utcOffsetMinutes, day, cursorJson?.let { WorkoutRecordCodec.json.decodeFromString(it) }))
}
