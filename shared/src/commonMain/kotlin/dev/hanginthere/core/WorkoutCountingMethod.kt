package dev.hanginthere.core

/** Explicitly registered methods. Unknown methods cannot enter history or
 * comparison cohorts; matching version numbers do not make methods comparable. */
internal object WorkoutCountingMethod {
    fun isSupported(exercise: ExerciseCounter.Exercise, method: String?, version: Int?): Boolean =
        if (exercise == ExerciseCounter.Exercise.pullUp) when(method) {
            PullUpCycleMotion.countMethod -> version==PullUpCycleMotion.policyVersion
            OverheadPullUpMotion.countMethod -> version != null && version in 1..OverheadPullUpMotion.policyVersion
            else -> false
        }
        else isSupportedDip(method, version)
    fun isSupportedDip(method: String?, version: Int?): Boolean = when(method) {
        BilateralDipWorkout.countMethod -> version == BilateralDipMotion.policyVersion
        BodyDipMotion.countMethod -> version == BodyDipMotion.policyVersion
        DipCycleMotion.countMethod -> version == DipCycleMotion.policyVersion
        BilateralTorsoDipMotion.countMethod -> version != null && version in 1..BilateralTorsoDipMotion.policyVersion
        else -> false
    }
    fun isImageMotion(method: String?): Boolean = method in listOf(BilateralDipWorkout.countMethod, BodyDipMotion.countMethod, DipCycleMotion.countMethod, BilateralTorsoDipMotion.countMethod, PullUpCycleMotion.countMethod,OverheadPullUpMotion.countMethod)
    fun validCountConfirmation(rep: RepRecord,sourceEnd: Double?): Boolean {
        if(rep.countMethod!=OverheadPullUpMotion.countMethod)return rep.countConfirmedAt==null
        if(!rep.isCompleteForAnalytics)return rep.countConfirmedAt==null
        val confirmed=rep.countConfirmedAt ?: return false
        return confirmed.isFinite() && confirmed>=(rep.returnedAt ?: return false) &&
            sourceEnd?.let { it.isFinite() && confirmed<=it }==true
    }
}
