package dev.hanginthere.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Experimental spatial association, not biometric identity or rep evidence.
 * Native adapters propose measured person rectangles and retain raw observations.
 * This policy deliberately cannot inspect arms, exercise, confidence or counts.
 * Own on one serial source executor. It is not connected to production pose
 * selection pending full-video validation; overlapping people can share geometry. */
class ForegroundAssociation(initialSource: Source) {
    data class Source(val generation: Long, val imageSize: ImageSize, val backend: String, val revision: Int) {
        init {
            require(generation >= 0 && backend.isNotBlank() && revision > 0)
            require(imageSize.isValid && imageSize.width <= Int.MAX_VALUE && imageSize.height <= Int.MAX_VALUE &&
                imageSize.width == imageSize.width.toInt().toDouble() && imageSize.height == imageSize.height.toInt().toDouble())
        }
    }
    enum class Status { acquiring, associated, ambiguous, lost }
    enum class Reason {
        acquiring, matched, missingCandidates, ambiguousCandidates, invalidCandidate,
        clockFault, continuityExpired, sourceMismatch, sourceChanged, resetRequired,
    }
    /** Index into the supplied native candidate list, never a fabricated pose.
     * Only an associated observation supplies a timestamp/region for a crop.
     * Adapters must retain this immutable request context and reject stale replies. */
    data class Decision(val source: Source, val status: Status, val reason: Reason, val candidateIndex: Int?,
                        val timestamp: PresentationTime?, val region: PersonInferenceRegion?,
                        val policyVersion: Int = ForegroundAssociation.policyVersion) {
        val generation: Long get() = source.generation
    }

    private var source = initialSource
    private var timescale: Int? = null
    private var lastTime: PresentationTime? = null
    private var matchedAt: PresentationTime? = null
    private var anchor: PersonInferenceRegion? = null
    private var acquisitionRegion: PersonInferenceRegion? = null
    private var firstRegion: PersonInferenceRegion? = null
    private var firstTime: PresentationTime? = null
    private var observations = 0
    private var acquired = false
    private var lost = false

    /** The owner coordinates this new epoch with setup, counters and grip caches.
     * Reusing an epoch would admit an old asynchronous crop/pose completion. */
    fun reset(nextSource: Source) {
        require(nextSource.generation > source.generation)
        source = nextSource
        lastTime = null; matchedAt = null; anchor = null; acquisitionRegion = null; timescale = null
        clearAcquisition(); acquired = false; lost = false
    }

    fun observe(frameSource: Source, time: PresentationTime, candidates: List<PersonInferenceRegion>): Decision {
        // A stale callback cannot reset or advance the current source's anchors.
        if (frameSource.generation != source.generation)
            return decision(if (lost) Status.lost else Status.ambiguous, Reason.sourceMismatch)
        if (frameSource != source) return lose(Reason.sourceChanged)
        if (lost) return decision(Status.lost, Reason.resetRequired)
        // Mixed timebases use a Double fallback in PresentationTime. Do not let
        // absolute-time rounding extend the exact continuity/acquisition bounds.
        if (timescale != null && time.timescale != timescale) return lose(Reason.clockFault)
        val gap = lastTime?.let { time.elapsedSeconds(it) }
        if (!time.seconds.isFinite() || gap?.isFinite() == false) {
            clearAcquisition()
            return if (acquired) lose(Reason.clockFault) else decision(Status.acquiring, Reason.clockFault)
        }
        if (gap != null && gap <= 0) {
            clearAcquisition()
            return decision(if (acquired) Status.ambiguous else Status.acquiring, Reason.clockFault)
        }
        timescale = time.timescale
        lastTime = time
        val sinceMatch = matchedAt?.let { time.elapsedSeconds(it) }
        if (acquired && (gap!! > maximumFrameGap || sinceMatch == null ||
                !sinceMatch.isFinite() || sinceMatch > maximumFrameGap)) return lose(Reason.continuityExpired)
        if (gap != null && gap > maximumFrameGap) clearAcquisition()
        if (candidates.size > maximumCandidates || candidates.any { !it.isValidFor(source.imageSize) })
            return unavailable(Reason.invalidCandidate)
        if (candidates.isEmpty()) return unavailable(Reason.missingCandidates)

        val selected: Int
        if (acquired) {
            val matches = candidates.indices.filter {
                largeEnough(candidates[it]) && compatible(anchor!!, candidates[it]) &&
                    withinEnvelope(acquisitionRegion!!, candidates[it], maximumOriginScaleRatio, maximumOriginDisplacementFraction)
            }
            if (matches.size != 1) return unavailable(Reason.ambiguousCandidates)
            selected = matches.single()
        } else {
            val ranked = candidates.indices.sortedByDescending { area(candidates[it]) }
            selected = ranked.first()
            val region = candidates[selected]
            if (!largeEnough(region) || (ranked.size > 1 &&
                    area(region) < area(candidates[ranked[1]]) * minimumDominance))
                return unavailable(Reason.ambiguousCandidates)
        }
        val region = candidates[selected]
        if (candidates.indices.any { it != selected && overlap(region, candidates[it]) >= ambiguousOverlap })
            return unavailable(Reason.ambiguousCandidates)
        if (acquired) {
            anchor = region; matchedAt = time
            return decision(Status.associated, Reason.matched, selected, time, region)
        }
        if (anchor == null || !compatible(anchor!!, region) || !compatible(firstRegion!!, region)) {
            clearAcquisition(); firstRegion = region; firstTime = time
        }
        anchor = region
        observations++
        if (observations >= minimumObservations && time.elapsedSeconds(firstTime!!) >= acquisitionSeconds) {
            acquired = true; matchedAt = time; acquisitionRegion = firstRegion
            clearAcquisition()
            return decision(Status.associated, Reason.matched, selected, time, region)
        }
        return decision(Status.acquiring, Reason.acquiring)
    }

    private fun clearAcquisition() {
        firstRegion = null; firstTime = null; observations = 0
        if (!acquired) anchor = null
    }
    private fun unavailable(reason: Reason): Decision {
        clearAcquisition()
        return decision(if (acquired) Status.ambiguous else Status.acquiring, reason)
    }
    private fun lose(reason: Reason): Decision {
        lost = true; anchor = null; matchedAt = null; acquisitionRegion = null; clearAcquisition()
        return decision(Status.lost, reason)
    }
    private fun decision(status: Status, reason: Reason, index: Int? = null,
                         time: PresentationTime? = null, region: PersonInferenceRegion? = null) =
        Decision(source, status, reason, index, time, region)

    private fun largeEnough(region: PersonInferenceRegion): Boolean =
        min(region.width, region.height) >= min(source.imageSize.width, source.imageSize.height) * minimumSideFraction &&
            area(region) >= source.imageSize.width * source.imageSize.height * minimumAreaFraction

    private fun compatible(a: PersonInferenceRegion, b: PersonInferenceRegion): Boolean =
        withinEnvelope(a, b, maximumScaleRatio, maximumDisplacementFraction) && overlap(a, b) >= minimumOverlap

    private fun withinEnvelope(origin: PersonInferenceRegion, current: PersonInferenceRegion,
                               scaleLimit: Double, displacementLimit: Double): Boolean {
        val dx = current.left.toDouble() + current.width / 2.0 - origin.left - origin.width / 2.0
        val dy = current.top.toDouble() + current.height / 2.0 - origin.top - origin.height / 2.0
        return max(origin.width.toDouble() / current.width, current.width.toDouble() / origin.width) <= scaleLimit &&
            max(origin.height.toDouble() / current.height, current.height.toDouble() / origin.height) <= scaleLimit &&
            hypot(dx, dy) <= min(min(origin.width, origin.height), min(current.width, current.height)) * displacementLimit
    }

    companion object {
        const val policyVersion = 1
        const val acquisitionSeconds = .6
        const val minimumObservations = 4
        const val maximumFrameGap = HandsFreeSetStart.maximumFrameGap
        const val maximumCandidates = 64
        // Frozen engineering proposals, not calibrated identity/quality limits.
        const val minimumAreaFraction = .08
        const val minimumSideFraction = .12
        const val minimumDominance = 1.8
        const val maximumScaleRatio = 1.35
        const val maximumDisplacementFraction = .25
        const val minimumOverlap = .5
        const val ambiguousOverlap = .3
        const val maximumOriginScaleRatio = 1.5
        const val maximumOriginDisplacementFraction = .5

        private fun area(region: PersonInferenceRegion) = region.width.toDouble() * region.height
        private fun overlap(a: PersonInferenceRegion, b: PersonInferenceRegion): Double {
            val width = max(0L, min(a.left.toLong() + a.width, b.left.toLong() + b.width) - max(a.left, b.left))
            val height = max(0L, min(a.top.toLong() + a.height, b.top.toLong() + b.height) - max(a.top, b.top))
            val intersection = width.toDouble() * height
            return intersection / (area(a) + area(b) - intersection)
        }
    }
}
