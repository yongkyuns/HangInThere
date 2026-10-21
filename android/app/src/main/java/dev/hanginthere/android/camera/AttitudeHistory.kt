package dev.hanginthere.android.camera

import dev.hanginthere.core.PhoneOrientationStability
import java.util.ArrayDeque

/** Bounded capture-time lookup: callback latency must never pair a frame with future motion. */
class AttitudeHistory(private val capacity: Int = 256, private val retentionSeconds: Double = 2.0) {
    data class Entry(val quaternion: PhoneOrientationStability.Quaternion, val seconds: Double)
    private val entries = ArrayDeque<Entry>()
    init { require(capacity > 0 && retentionSeconds > 0) }
    @Synchronized fun add(entry: Entry) {
        if (!entry.seconds.isFinite() || entries.peekLast()?.let { entry.seconds <= it.seconds } == true) return
        entries.addLast(entry)
        while (entries.size > capacity || entries.peekFirst()?.let { entry.seconds - it.seconds > retentionSeconds } == true) entries.removeFirst()
    }
    @Synchronized fun atOrBefore(seconds: Double, maximumAgeSeconds: Double = 0.5): Entry? {
        if (!seconds.isFinite()) return null
        return entries.descendingIterator().asSequence().firstOrNull { it.seconds <= seconds }
            ?.takeIf { seconds - it.seconds <= maximumAgeSeconds }
    }
    @Synchronized fun clear() = entries.clear()
}
