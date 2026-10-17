package dev.hanginthere.core

import kotlin.math.abs
import kotlin.math.max

internal enum class WorkoutMotionDirection { decreasing, stationary, increasing }

/** Bounded source-time direction estimation for descriptive phases only.
 * A time-weighted fit rejects frame-rate bias; separate enter/hold thresholds
 * and reversal evidence keep small landmark jitter from flipping direction.
 * Neither filtered angles nor this state are supplied to the rep counter. */
internal class WorkoutPhaseDirection {
    private data class Angle(val seconds: Double, val degrees: Double)
    private val samples = ArrayDeque<Angle>()
    private var direction: WorkoutMotionDirection? = null
    private var pending: WorkoutMotionDirection? = null
    private var pendingSince: Double? = null
    private var pendingAngle: Double? = null
    private var stableSince: Double? = null
    private var fittedStableSince: Double? = null
    var rateDegreesPerSecond: Double? = null; private set
    var restarted = false; private set

    private fun mask(sample: MotionSample) =
        (if (sample.leftDegrees?.isFinite() == true) 1 else 0) +
        (if (sample.rightDegrees?.isFinite() == true) 2 else 0)
    private fun angle(sample: MotionSample): Double? =
        listOfNotNull(sample.leftDegrees, sample.rightDegrees).filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.average()

    fun observe(sample: MotionSample, previous: MotionSample?): WorkoutMotionDirection? {
        restarted = false
        val value = angle(sample)
        val old = previous?.takeIf {
            it.usable && !it.released && sample.usable && !sample.released &&
                it.seconds.isFinite() && sample.seconds.isFinite() && sample.seconds > it.seconds &&
                sample.seconds - it.seconds <= ExerciseCounter.maximumGapSeconds &&
                mask(it) == mask(sample) && mask(sample) != 0
        }
        if (value == null || old == null) {
            reset(); restarted = true
            if (value != null && sample.seconds.isFinite() && sample.usable && !sample.released)
                samples.addLast(Angle(sample.seconds, value))
            return null
        }
        val priorAngle = angle(old)!!
        if (samples.lastOrNull()?.seconds != old.seconds) {
            reset(); restarted = true
            samples.addLast(Angle(old.seconds, priorAngle))
        }
        samples.addLast(Angle(sample.seconds, value))
        val cutoff = sample.seconds - windowSeconds
        // Retain the interval straddling the window boundary. Its clipped
        // integral uses observed adjacent samples, never an outage interval.
        while (samples.size > 2 && samples[1].seconds <= cutoff) samples.removeFirst()
        while (samples.size > maximumSamples) samples.removeFirst()

        val instantaneous = (value - priorAngle) / (sample.seconds - old.seconds)
        if (abs(instantaneous) <= holdSpeedDegreesPerSecond) {
            if (stableSince == null) stableSince = old.seconds
        } else stableSince = null
        val rate = fittedRate(sample.seconds) ?: return direction
        rateDegreesPerSecond = rate
        val span = sample.seconds - max(cutoff, samples.first().seconds)
        if (span >= confirmationSeconds && abs(rate) <= holdSpeedDegreesPerSecond) {
            if (fittedStableSince == null) fittedStableSince = sample.seconds
        } else fittedStableSince = null
        val stable = stableSince?.let { sample.seconds - it >= confirmationSeconds } == true ||
            fittedStableSince?.let { sample.seconds - it >= confirmationSeconds } == true
        if (stable) {
            direction = WorkoutMotionDirection.stationary; clearPending()
            return direction
        }
        val candidate = when {
            rate <= -movingSpeedDegreesPerSecond -> WorkoutMotionDirection.decreasing
            rate >= movingSpeedDegreesPerSecond -> WorkoutMotionDirection.increasing
            else -> null
        }
        if (candidate == null || candidate == direction) { clearPending(); return direction }
        // The candidate anchor is independent of the fitted window: slow
        // genuine motion may need longer than one window to travel 3 degrees.
        if (pending != candidate) {
            pending = candidate; pendingSince = sample.seconds; pendingAngle = priorAngle
        }
        val travel = if (candidate == WorkoutMotionDirection.increasing) value - pendingAngle!! else pendingAngle!! - value
        if (sample.seconds - pendingSince!! >= confirmationSeconds && travel >= minimumTransitionDegrees) {
            direction = candidate; clearPending()
        }
        return direction
    }

    /** Least-squares slope of the piecewise-linear observed angle over time.
     * Integrating segments instead of weighting frames equally makes an extra
     * sample on the same trajectory leave the estimate unchanged. Time is
     * relative to the latest source frame to avoid large-clock cancellation. */
    private fun fittedRate(now: Double): Double? {
        var weight = 0.0; var time = 0.0; var timeSquared = 0.0
        var angle = 0.0; var timeAngle = 0.0
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            val start = max(a.seconds, now - windowSeconds)
            val h = b.seconds - start
            if (h <= 0) continue
            val slope = (b.degrees - a.degrees) / (b.seconds - a.seconds)
            val x = start - now
            val y = a.degrees + slope * (start - a.seconds)
            val h2 = h * h; val h3 = h2 * h
            weight += h
            time += x * h + h2 / 2
            timeSquared += x * x * h + x * h2 + h3 / 3
            angle += y * h + slope * h2 / 2
            timeAngle += x * y * h + (x * slope + y) * h2 / 2 + slope * h3 / 3
        }
        if (weight <= 0) return null
        val variance = timeSquared - time * time / weight
        return if (variance > 1e-12) ((timeAngle - time * angle / weight) / variance).takeIf { it.isFinite() } else null
    }

    private fun clearPending() { pending = null; pendingSince = null; pendingAngle = null }
    fun reset() { samples.clear(); direction = null; stableSince = null; fittedStableSince = null; rateDegreesPerSecond = null; clearPending() }
    val retainedSamples get() = samples.size
    companion object {
        const val windowSeconds = .24
        const val confirmationSeconds = .08
        const val holdSpeedDegreesPerSecond = 5.0
        const val movingSpeedDegreesPerSecond = 8.0
        const val minimumTransitionDegrees = 3.0
        const val maximumSamples = 64
    }
}
