package dev.hanginthere.core

import kotlinx.serialization.Serializable

/** Descent earns the first half; recovery earns the second. A movement event alone earns 100%. */
@Serializable
data class DipProgress(val stage: Stage, val repNumber: Int, val fraction: Double, val cue: Cue, val trackedArmCount: Int) {
    @Serializable enum class Stage { startingSupport, ready, lowering, holdingBottom, rising, holdingTop, counted, paused, finished }
    @Serializable enum class Cue { support, lower, lowerFurther, bendArms, rise, extendArms, hold, readyNext,
        showArms, reacquireSupport, reestablishGrip, setRail, finished }
    companion object { const val countConfirmationSeconds = 0.65 }
}
