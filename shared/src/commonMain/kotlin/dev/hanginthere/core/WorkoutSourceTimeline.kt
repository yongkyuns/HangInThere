package dev.hanginthere.core

/** Source bounds describe analyzed evidence, independent of counting readiness.
 * Rejected timestamps must neither shorten retained records nor move watchdogs
 * behind a newer frame. Callers still deliver them to the counter's error path. */
internal class WorkoutSourceTimeline {
    var firstSeconds: Double? = null; private set
    var lastSeconds: Double? = null; private set

    fun observe(seconds: Double) {
        if (!seconds.isFinite() || (lastSeconds != null && seconds <= lastSeconds!!)) return
        if (firstSeconds == null) firstSeconds = seconds
        lastSeconds = seconds
    }

    fun reset() { firstSeconds = null; lastSeconds = null }
}
