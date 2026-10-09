package dev.hanginthere.android.session

import dev.hanginthere.core.LiveSetSession
import dev.hanginthere.core.WorkoutRuntime
import dev.hanginthere.core.WorkoutSetupGuidance

/** EOF is a decoder event, not proof that a counting session actually began. */
data class ReplayOutcome(val kind: Kind, val message: String? = null) {
    enum class Kind { complete, setupUnavailable, interrupted, analysisFailed }

    companion object {
        fun analysisFailure(runtime: WorkoutRuntime?, cause: String?): ReplayOutcome {
            runtime?.interrupt(LiveSetSession.EndReason.cameraFailure)
            return ReplayOutcome(Kind.analysisFailed, cause ?: "Video analysis failed")
        }
        fun atEnd(analyzedFrames: Int, acquired: Boolean, reachedSourceEnd: Boolean,
                  setupIssue: String?, endReason: LiveSetSession.EndReason?): ReplayOutcome {
            if (endReason?.isInterruption == true || !reachedSourceEnd) {
                val reason = when (endReason) {
                    LiveSetSession.EndReason.sceneShifted, LiveSetSession.EndReason.sceneScaled -> "camera view changed"
                    LiveSetSession.EndReason.phoneMoved -> "camera orientation changed"
                    LiveSetSession.EndReason.appInactive -> "app left the foreground"
                    LiveSetSession.EndReason.setupInvalidated -> "setup became unavailable"
                    else -> "evaluation stopped before the end"
                }
                return ReplayOutcome(Kind.interrupted, "Video evaluation incomplete · $reason")
            }
            if (analyzedFrames == 0) {
                val reason = when (setupIssue) {
                    "sceneStabilityNeeded", "sceneUnavailable" -> "stable background could not be confirmed"
                    "phoneStabilityNeeded" -> "camera stability could not be confirmed"
                    else -> setupIssue?.let(WorkoutSetupGuidance::message)?.replaceFirstChar { it.lowercase() }
                        ?: if (acquired) "counting could not start from this camera view" else "visible apparatus and starting support could not be confirmed"
                }
                return ReplayOutcome(Kind.setupUnavailable, "Video not evaluated · $reason")
            }
            return ReplayOutcome(Kind.complete)
        }
    }
}
