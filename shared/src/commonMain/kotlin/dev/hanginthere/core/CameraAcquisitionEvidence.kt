package dev.hanginthere.core

/** Source-time evidence for a real native camera-reference epoch. Native capture
 * owns pixels, scene/phone verdicts and reference replacement. Historical epoch
 * qualification never substitutes for its current fresh scene verdict. */
class CameraAcquisitionEvidence {
    private var reference: PresentationTime? = null
    private var lastFrame: PresentationTime? = null
    private var lastMeasurement: PresentationTime? = null
    private var firstStationary: PresentationTime? = null
    private var unavailableSince: PresentationTime? = null
    var independentObservations = 0; private set
    var hasIndependentEvidence = false; private set

    fun reset() {
        reference = null; lastFrame = null; lastMeasurement = null
        firstStationary = null; unavailableSince = null
        independentObservations = 0; hasIndependentEvidence = false
    }
    fun beginReference(sourceValue: Long, sourceTimescale: Int): Boolean {
        reset()
        val time = PresentationTime(sourceValue, sourceTimescale)
        if(!valid(time)) return false
        reference = time; lastFrame = time
        return true
    }
    fun permitsRegistration(sourceValue: Long, sourceTimescale: Int): Boolean {
        val time = PresentationTime(sourceValue, sourceTimescale)
        val start = reference ?: return false
        return valid(time) && compare(time,start,zero) == 1
    }
    fun observeScene(sourceValue: Long, sourceTimescale: Int, stationary: Boolean) {
        val time = PresentationTime(sourceValue, sourceTimescale)
        if(!permitsRegistration(sourceValue,sourceTimescale)) return
        if(lastMeasurement?.let { compare(time,it,zero) != 1 } == true) {
            clearEvidence(); return
        }
        if(lastMeasurement?.let { compare(time,it,maximumGap) == 1 } == true && !hasIndependentEvidence)
            clearCandidate()
        lastMeasurement = time
        // Once established, epoch proof survives a temporarily unobservable
        // scene. The native current-scene gate still blocks every such frame.
        if(hasIndependentEvidence) return
        if(!stationary) { clearCandidate(); return }
        if(firstStationary == null) firstStationary = time
        independentObservations++
        if(independentObservations >= minimumIndependentObservations &&
            compare(time,firstStationary!!,stableDwell)?.let { it >= 0 } == true)
            hasIndependentEvidence = true
    }
    fun shouldReacquire(sourceValue: Long, sourceTimescale: Int, sceneReady: Boolean,
        idle: Boolean, eligible: Boolean): Boolean {
        val time = PresentationTime(sourceValue,sourceTimescale)
        if(reference == null) return false
        if(!valid(time) || lastFrame?.let { compare(time,it,zero) != 1 } == true) {
            unavailableSince = null; clearEvidence(); return false
        }
        if(lastFrame?.let { compare(time,it,maximumGap) == 1 } == true) unavailableSince = null
        lastFrame = time
        if(sceneReady || !idle || !eligible) { unavailableSince = null; return false }
        val since = unavailableSince
        if(since == null) { unavailableSince = time; return false }
        if(compare(time,since,maximumGap)?.let { it >= 0 } != true) return false
        unavailableSince = null
        return true
    }
    private fun clearCandidate() { firstStationary = null; independentObservations = 0 }
    private fun clearEvidence() { clearCandidate(); hasIndependentEvidence = false }
    private fun valid(time: PresentationTime) = time.value >= 0 && time.timescale > 0 && time.seconds.isFinite()
    private fun compare(end: PresentationTime,start: PresentationTime,limit: SourceTimeLimit) =
        SourceTimeComparison.compareElapsed(end,start,limit)
    companion object {
        const val policyVersion = 1
        const val minimumIndependentObservations = 2
        private val zero = SourceTimeLimit(0,1)
        private val stableDwell = SourceTimeLimit(1,4)
        private val maximumGap = SourceTimeLimit(4,5)
    }
}
