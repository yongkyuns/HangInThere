package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Current counter evidence, not form qualification. Only a counted state displays 100%. */
@Serializable
data class PullUpProgress(val stage: Stage, val repNumber: Int, val fraction: Double, val resetFraction: Double,
    val cue: Cue, val trackedArmCount: Int) {
    @Serializable enum class Stage { startingHang, ready, pulling, holdingTop, counted, paused, finished }
    @Serializable enum class Cue { hang, pull, pullHigher, finishPull, hold, lower, showArms, reacquireHang, setBar, finished }
    companion object { fun fraction(value: Double): Double = normalizedProgress(value) }
}

fun normalizedProgress(value: Double): Double = if (value.isFinite()) min(1.0, max(0.0, value)) else 0.0
