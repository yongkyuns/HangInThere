package dev.hanginthere.core

import kotlinx.serialization.Serializable

/** Reuses an owner's actual exercise phase. Current arm observations may revoke
 * metadata after a hand leaves support; they cannot start or count an exercise.
 * No image-contact score. The RGB research consumer uses this shared contract;
 * it does not replace the shipping geometric classifier. */
class WorkoutGripExerciseContext {
    @Serializable data class Snapshot(val eligible: Boolean, val startedAt: Double?,
        val epoch: Int, val reason: String, val policyVersion: Int = 2)
    private var last: Double? = null
    private var started: Double? = null
    private var epoch = 0
    private var handDeparture = false
    private var readyAfterDeparture = false

    /** The caller owns the athlete and reference. Only strong current wrist
     * evidence on an independently usable arm can witness departure. Missing or
     * weak joints never become a release gesture, and no counter state changes. */
    fun observe(pose: PoseResult, reference: BarSegment?, phase: ExerciseCounter.Phase,
                usable: Boolean, released: Boolean): Snapshot =
        observe(pose.timestamp.seconds, phase, usable, released, observedHandDeparture(pose, reference))

    fun observe(seconds: Double, phase: ExerciseCounter.Phase, usable: Boolean, released: Boolean,
                handDepartureObserved: Boolean = false): Snapshot {
        if (!seconds.isFinite() || last?.let { seconds <= it } == true) {
            interrupt()
            return snapshot("invalidSourceTime")
        }
        if (last?.let { seconds-it > ExerciseCounter.maximumGapSeconds } == true) interrupt()
        last = seconds
        if (handDepartureObserved) {
            interrupt()
            handDeparture = true
            readyAfterDeparture = false
            return snapshot("handLeftSupport")
        }
        if (!usable || released || phase == ExerciseCounter.Phase.seekingStart || phase == ExerciseCounter.Phase.finished) {
            interrupt()
            return snapshot(when {
                released -> "released"
                !usable -> "trackingUnavailable"
                phase == ExerciseCounter.Phase.finished -> "finished"
                else -> "waitingForExerciseMotion"
            })
        }
        // Regrasping while the old counter remains outbound/returning cannot
        // resurrect its exercise or its RGB bank. Require a real ready phase,
        // then a later departure; setup time still earns no hand evidence.
        if (handDeparture) {
            if (phase == ExerciseCounter.Phase.ready) readyAfterDeparture = true
            if (!readyAfterDeparture || phase != ExerciseCounter.Phase.outbound)
                return snapshot("waitingForNewDeparture")
            handDeparture = false
            readyAfterDeparture = false
        }
        if (started == null && phase in listOf(ExerciseCounter.Phase.outbound, ExerciseCounter.Phase.returning)) {
            started = seconds
        }
        return snapshot(if (started != null) "counterExerciseActive" else "waitingForExerciseMotion")
    }

    /** Preserve the source high-water fence; an owner constructs a new context
     * when switching source/athlete/reference. No pending old RGB earns credit. */
    fun interrupt() {
        if (started != null) epoch++
        started = null
        readyAfterDeparture = false
    }

    private fun snapshot(reason: String) = Snapshot(started != null, started, epoch, reason)

    companion object {
        internal fun observedHandDeparture(pose: PoseResult, reference: BarSegment?): Boolean {
            if (!pose.imageSize.isValid || pose.people.size != 1 || reference?.isValid != true) return false
            if (listOf(reference.a, reference.b).any {
                    it.x !in 0.0..pose.imageSize.width || it.y !in 0.0..pose.imageSize.height
                }) return false
            val short = minOf(pose.imageSize.width, pose.imageSize.height)
            return ArmMeasurement.Side.entries.any { side ->
                val arm = ArmMeasurement(pose, side).estimate ?: return@any false
                val wrists = pose.people.single().landmarks.filter { it.joint == side.joints[2] }
                val wrist = wrists.singleOrNull() ?: return@any false
                wrist.confidence.isFinite() && wrist.confidence in .5..1.0 && wrist.position.isFinite &&
                    wrist.position.x in 0.0..pose.imageSize.width && wrist.position.y in 0.0..pose.imageSize.height &&
                    // A wrist-seeded span does not locate the physical ends of
                    // the bar. Longitudinal movement cannot prove departure.
                    reference.perpendicularDistance(wrist.position) > maxOf(short * .12,
                        (arm.upperArmPixels + arm.forearmPixels) * .25)
            }
        }
    }
}
