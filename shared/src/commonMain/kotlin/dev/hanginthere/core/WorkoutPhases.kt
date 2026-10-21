package dev.hanginthere.core

import kotlinx.serialization.Serializable

/** Descriptive camera phases, separate from the counter's acceptance gates. */
@Serializable enum class WorkoutMotionPhase(val title: String) {
    hang("Hang"), support("Top support"), pulling("Pulling"), lowering("Lowering"),
    rising("Rising"), peakHold("Top hold"), bottomHold("Bottom hold"), effortHold("Mid-range hold"), release("Released"), unobserved("Tracking gap"), acquiring("Acquiring position")
}
@Serializable data class WorkoutPhaseSpan(val id: String, val phase: WorkoutMotionPhase,
    val startedAt: Double, val endedAt: Double, val observedSeconds: Double,
    /** Credited suffix starts after any uncredited first hold interval. Legacy spans may omit this. */
    val observedStartedAt: Double? = null)
@Serializable data class WorkoutPhaseTotal(val phase: WorkoutMotionPhase, val observedSeconds: Double)
@Serializable data class WorkoutActivity(val phases: List<WorkoutPhaseSpan>, val totals: List<WorkoutPhaseTotal>,
    val longestHangSeconds: Double, val longestSupportSeconds: Double, val observedSeconds: Double,
    val elapsedSeconds: Double, val timelineTruncated: Boolean, val metricVersion: Int = 1) {
    fun seconds(phase: WorkoutMotionPhase) = totals.firstOrNull { it.phase == phase }?.observedSeconds ?: 0.0
    val hasPractice get() = observedSeconds > 0.0
}

/** Constant-space integrals plus a bounded display timeline. Only consecutive,
 * fresh observations earn time. Gaps and single-arm hold evidence split a hold. */
internal class WorkoutPhaseAnalysis(private val setId: String, private val exercise: ExerciseCounter.Exercise) {
    private var previous: MotionSample? = null
    private var previousKind: WorkoutMotionPhase? = null
    private var minimumMovementAngle: Double? = null
    private var witnessedEndpointAngle: Double? = null
    private var bilateralContinuity = false
    private val motionDirection = WorkoutPhaseDirection()
    private var first: Double? = null
    private var highWater: Double? = null
    private val spans = mutableListOf<WorkoutPhaseSpan>()
    private val totals = mutableMapOf<WorkoutMotionPhase, Double>()
    private var ordinal = 0
    private var truncated = false
    private var longestHang = 0.0
    private var longestSupport = 0.0
    private var revisionAt: Double? = null
    private var broken = false
    private fun append(phase: WorkoutMotionPhase, start: Double, end: Double, observed: Double, separate: Boolean = false) {
        val old = spans.lastOrNull()
        val next = if (!separate && old?.phase == phase && old.endedAt == start)
            old.copy(endedAt = end, observedSeconds = old.observedSeconds + observed,
                observedStartedAt = old.observedStartedAt ?: start.takeIf { observed > 0 })
        else WorkoutPhaseSpan("$setId:phase:${++ordinal}", phase, start, end, observed, start.takeIf { observed > 0 })
        if (old?.id == next.id) spans[spans.lastIndex] = next else {
            if (spans.size == maximumSpans) { spans.removeAt(0); truncated = true }
            spans += next
        }
        totals[phase] = (totals[phase] ?: 0.0) + observed
        if (phase == WorkoutMotionPhase.hang) longestHang = maxOf(longestHang, next.observedSeconds)
        if (phase == WorkoutMotionPhase.support) longestSupport = maxOf(longestSupport, next.observedSeconds)
    }
    fun observe(sample: MotionSample, phase: ExerciseCounter.Phase): Boolean {
        val time = sample.seconds
        if (!time.isFinite() || highWater?.let { time <= it } == true) { interrupt(); return false }
        val old = previous
        val lastTime = highWater
        highWater = time
        if (first == null) first = time
        val dt = old?.let { time - it.seconds }
        val contiguous = !broken && old != null && dt != null && dt > 0 && dt <= ExerciseCounter.maximumGapSeconds
        val kind = classify(sample, phase, old.takeIf { contiguous })
        var changed = spans.lastOrNull()?.phase != kind || broken
        if (lastTime != null && (!contiguous || !sample.usable || !old!!.usable || sample.released || old.released ||
            previousKind == WorkoutMotionPhase.unobserved || (previousKind == WorkoutMotionPhase.acquiring && kind != WorkoutMotionPhase.acquiring))) {
            // Keep the missing interval visible, but never credit it to practice.
            val before = ordinal
            append(if (sample.released && old?.released == true) WorkoutMotionPhase.release
                else if(previousKind==WorkoutMotionPhase.acquiring && contiguous && sample.usable && old?.usable==true && !sample.released)WorkoutMotionPhase.acquiring
                else WorkoutMotionPhase.unobserved,
                lastTime, time, 0.0, separate = broken)
            append(kind, time, time, 0.0)
            changed = changed || ordinal != before
        } else if (contiguous) {
            append(kind, old!!.seconds, time,
                dt!!.takeIf { kind != WorkoutMotionPhase.acquiring && kind != WorkoutMotionPhase.unobserved && kind != WorkoutMotionPhase.release &&
                    previousKind != WorkoutMotionPhase.unobserved && previousKind != WorkoutMotionPhase.acquiring &&
                    (kind !in listOf(WorkoutMotionPhase.hang, WorkoutMotionPhase.support) || previousKind == kind) } ?: 0.0)
        } else append(kind, time, time, 0.0)
        previous = sample; previousKind = kind; broken = false
        val checkpoint = revisionAt == null || time - revisionAt!! >= checkpointSeconds
        if (changed || checkpoint) revisionAt = time
        return changed || checkpoint
    }
    private fun classify(sample: MotionSample, phase: ExerciseCounter.Phase, old: MotionSample?): WorkoutMotionPhase {
        if (sample.released) { clearEndpoint(); motionDirection.reset(); return WorkoutMotionPhase.release }
        if (!sample.usable) { clearEndpoint(); motionDirection.reset(); return WorkoutMotionPhase.unobserved }
        val angles = listOfNotNull(sample.leftDegrees, sample.rightDegrees).filter { it.isFinite() }
        val threshold = if (exercise == ExerciseCounter.Exercise.pullUp) ExerciseCounter.extendedDegrees else ExerciseCounter.minimumDipSupportDegrees
        if (phase == ExerciseCounter.Phase.ready || phase == ExerciseCounter.Phase.seekingStart) {
            minimumMovementAngle = null
            witnessedEndpointAngle = null
            bilateralContinuity = angles.size == 2
            motionDirection.reset()
            // A counted hang/support duration requires both visible arm chains.
            if (angles.size == 2 && angles.all { it >= threshold })
                return if (exercise == ExerciseCounter.Exercise.pullUp) WorkoutMotionPhase.hang else WorkoutMotionPhase.support
            return if (phase == ExerciseCounter.Phase.ready && angles.size != 2) WorkoutMotionPhase.unobserved else WorkoutMotionPhase.acquiring
        }
        if (phase == ExerciseCounter.Phase.outbound || phase == ExerciseCounter.Phase.returning) {
            val mean = angles.takeIf { it.size == 2 }?.average()
            val direction = motionDirection.observe(sample, old)
            if (angles.size != 2) bilateralContinuity = false
            // Filter restarts must not establish a new endpoint at the current
            // return angle. Keep an already witnessed bilateral endpoint across
            // arm loss; an unseen endpoint remains unknown until a new cycle.
            if (old == null) clearEndpoint()
            if (mean != null) minimumMovementAngle = minOf(minimumMovementAngle ?: mean, mean)
            if (phase == ExerciseCounter.Phase.returning && mean != null && (bilateralContinuity || witnessedEndpointAngle != null))
                witnessedEndpointAngle = minOf(witnessedEndpointAngle ?: minimumMovementAngle ?: mean, mean)
            if (direction == WorkoutMotionDirection.stationary) return if(phase == ExerciseCounter.Phase.returning && mean != null &&
                witnessedEndpointAngle?.let { mean <= it + 3 } == true) {
                if(exercise == ExerciseCounter.Exercise.pullUp) WorkoutMotionPhase.peakHold else WorkoutMotionPhase.bottomHold
            } else WorkoutMotionPhase.effortHold
            if (direction != null) return if (direction == WorkoutMotionDirection.decreasing) {
                if (exercise == ExerciseCounter.Exercise.pullUp) WorkoutMotionPhase.pulling else WorkoutMotionPhase.lowering
            } else if (exercise == ExerciseCounter.Exercise.pullUp) WorkoutMotionPhase.lowering else WorkoutMotionPhase.rising
            return if (phase == ExerciseCounter.Phase.outbound) {
                if (exercise == ExerciseCounter.Exercise.pullUp) WorkoutMotionPhase.pulling else WorkoutMotionPhase.lowering
            } else if (exercise == ExerciseCounter.Exercise.pullUp) WorkoutMotionPhase.lowering else WorkoutMotionPhase.rising
        }
        return WorkoutMotionPhase.acquiring
    }
    private fun clearEndpoint() { minimumMovementAngle = null; witnessedEndpointAngle = null; bilateralContinuity = false }
    fun interrupt() { previous = null; previousKind = null; clearEndpoint(); motionDirection.reset(); broken = true }
    val activity get() = WorkoutActivity(spans.toList(), WorkoutMotionPhase.entries.map { WorkoutPhaseTotal(it, totals[it] ?: 0.0) },
        longestHang, longestSupport, totals.values.sum(),
        if (first != null && highWater != null) highWater!!-first!! else 0.0, truncated, metricVersion = metricVersion)
    companion object {
        const val metricVersion = 3
        const val maximumSpans = 256
        const val checkpointSeconds = 1.0
    }
}
