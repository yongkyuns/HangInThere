package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Evaluation-only adapter for skeletal observations, not biometric identity.
 * Source-time, unique-match, dominance and overlap rules follow the existing
 * spatial-association contract. Skeletal point bounds are not native full-person
 * rectangles: acquisition requires measurable torso geometry instead of applying
 * the latter's absolute 8% image-area rule to a different measurement. Identity
 * uses the current shoulder pair; moving arms/ankles cannot resize its anchor.
 * No exercise, arm angle, grip, reference event or count enters selection.
 */
class BodyAthleteAssociationExperiment(private val source: ForegroundAssociation.Source) {
    @Serializable enum class Status { acquiring, associated, ambiguous, lost }
    @Serializable data class Decision(val status: Status, val reason: String,
        val candidateIndex: Int? = null, val seconds: Double? = null,
        val sourceGeneration: Long, val policyVersion: Int = version)
    private data class Region(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right-left
        val height get() = bottom-top
        val area get() = width*height
        val x get() = (left+right)/2
        val y get() = (top+bottom)/2
        fun contains(p: Point2D) = p.x in left..right && p.y in top..bottom
    }
    private data class BodyAnchor(val x: Double,val y: Double,val span: Double)
    private data class Candidate(val region: Region, val body: BodyAnchor?, val points: List<Point2D>)
    private var highWater: PresentationTime? = null
    private var matchedAt: PresentationTime? = null
    private var anchor: BodyAnchor? = null
    private var origin: BodyAnchor? = null
    private var first: BodyAnchor? = null
    private var firstTime: PresentationTime? = null
    private var observations = 0
    private var acquired = false
    private var lost = false

    fun observe(frameSource: ForegroundAssociation.Source, time: PresentationTime,
        people: List<PoseObservation>): Decision {
        fun decision(status: Status, reason: String, index: Int? = null) =
            Decision(status, reason, index, time.seconds.takeIf { it.isFinite() }, source.generation)
        fun lose(reason: String): Decision {
            lost = true; anchor = null; origin = null; clearAcquisition()
            return decision(Status.lost, reason)
        }
        fun unavailable(reason: String): Decision {
            clearAcquisition()
            return decision(if (acquired) Status.ambiguous else Status.acquiring, reason)
        }
        if (frameSource.generation != source.generation)
            return decision(if (lost) Status.lost else Status.ambiguous, "sourceMismatch")
        if (frameSource != source) return lose("sourceChanged")
        if (lost) return decision(Status.lost, "resetRequired")
        val previous = highWater
        if (!time.seconds.isFinite() || time.seconds < 0 ||
            previous?.let { compare(time, it, zero) != 1 } == true)
            return unavailable("clockFault")
        val exceeded = previous?.let { compare(time, it, gap) == 1 } == true
        highWater = time
        if (acquired && (exceeded || matchedAt?.let { compare(time, it, gap) == 1 } == true))
            return lose("continuityExpired")
        if (exceeded) clearAcquisition()
        if (people.size > 64) return unavailable("invalidCandidates")
        if (people.isEmpty()) return unavailable("missingCandidates")
        // Every raw competitor is retained. A malformed/unlocated alternative
        // cannot be silently removed to make a preferred candidate win.
        val candidates = people.map { candidate(it) ?: return unavailable("invalidCandidate") }
        val selected: Int
        if (acquired) {
            val elapsed = matchedAt?.let { time.elapsedSeconds(since = it) } ?: 0.0
            val matches = candidates.indices.filter { i ->
                val r = candidates[i].region
                val body=candidates[i].body
                // A continuously matched athlete may walk between the rest
                // location and the bars. Do not pin that person to the initial
                // image coordinates. Reference scale, bounded current motion,
                // unique matching and competitor overlap remain mandatory.
                measurable(r) && body!=null && compatible(anchor!!, body, origin!!, elapsed)
            }
            if (matches.size != 1) return unavailable("ambiguousCandidates")
            selected = matches.single()
        } else {
            val ranked = candidates.indices.sortedByDescending { candidates[it].region.area }
            selected = ranked.first()
            val c = candidates[selected]
            if (c.body==null || !measurable(c.region) ||
                ranked.size > 1 && c.region.area < 1.8*candidates[ranked[1]].region.area)
                return unavailable("ambiguousCandidates")
        }
        val region = candidates[selected].region
        if (candidates.indices.any { i -> i != selected && overlaps(region, candidates[i]) })
            return unavailable("overlappingCandidates")
        val body=candidates[selected].body!!
        if (acquired) {
            anchor = body; matchedAt = time
            return decision(Status.associated, "matched", selected)
        }
        val dt = previous?.let { time.elapsedSeconds(since = it) } ?: 0.0
        if (anchor == null || !compatible(anchor!!, body, first!!, dt) || !withinOrigin(first!!, body)) {
            clearAcquisition(); first = body; firstTime = time
        }
        anchor = body; observations++
        if (observations >= 4 && compare(time, firstTime!!, acquisition)?.let { it >= 0 } == true) {
            acquired = true; origin = first; matchedAt = time; clearAcquisition()
            return decision(Status.associated, "matched", selected)
        }
        return decision(Status.acquiring, "acquiring")
    }

    private fun clearAcquisition() {
        first = null; firstTime = null; observations = 0
        if (!acquired) anchor = null
    }
    private fun candidate(person: PoseObservation): Candidate? {
        if (person.landmarks.size > 64 || person.landmarks.map { it.joint }.distinct().size != person.landmarks.size)
            return null
        if (person.landmarks.any { !it.confidence.isFinite() || it.confidence !in 0.0..1.0 ||
                !it.position.isFinite || it.position.x !in 0.0..source.imageSize.width ||
                it.position.y !in 0.0..source.imageSize.height }) return null
        val points = person.landmarks.filter { it.confidence >= ArmMeasurement.confidenceThreshold }.map { it.position }
        if (points.isEmpty()) return null
        val region = Region(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
        val shoulders = listOf(PoseJoint.leftShoulder, PoseJoint.rightShoulder)
            .mapNotNull { person.landmark(it)?.position }
        val floor = ArmMeasurement.minimumSegmentFraction*min(source.imageSize.width, source.imageSize.height)
        val span=if(shoulders.size==2)hypot(shoulders[0].x-shoulders[1].x,shoulders[0].y-shoulders[1].y) else 0.0
        val body=if(span>=2*floor)BodyAnchor(shoulders.map{it.x}.average(),shoulders.map{it.y}.average(),span) else null
        return Candidate(region, body, points)
    }
    private fun measurable(r: Region): Boolean {
        val floor = 2*ArmMeasurement.minimumSegmentFraction*min(source.imageSize.width, source.imageSize.height)
        return r.width >= floor && r.height >= floor
    }
    private fun compatible(a: BodyAnchor, b: BodyAnchor, reference: BodyAnchor, elapsed: Double): Boolean {
        // Frame-rate-aware spatial envelope in measured shoulder spans. This
        // is association plausibility, never exercise/rep evidence. Reference
        // scale stays fixed for the epoch; repeated jitter cannot grow it.
        val movement = reference.span*(.5+4*elapsed.coerceIn(0.0,gap.seconds))
        return ratio(reference.span,b.span)<=2 && hypot(a.x-b.x,a.y-b.y)<=movement
    }
    private fun withinOrigin(a: BodyAnchor, b: BodyAnchor) = ratio(a.span,b.span)<=2 &&
        abs(a.x-b.x)<=2*a.span && abs(a.y-b.y)<=3*a.span
    private fun overlaps(selected: Region, other: Candidate): Boolean =
        if (other.region.area <= 0) other.points.any(selected::contains) else iou(selected, other.region) >= .3

    companion object {
        const val version = 3
        private val zero = SourceTimeLimit(0,1)
        private val gap = SourceTimeLimit(7,20)
        private val acquisition = SourceTimeLimit(3,5)
        private fun compare(end: PresentationTime, start: PresentationTime, limit: SourceTimeLimit) =
            SourceTimeComparison.compareElapsed(end,start,limit)
        private fun ratio(a: Double, b: Double) = if (a <= 0 || b <= 0) Double.POSITIVE_INFINITY else max(a/b,b/a)
        private fun iou(a: Region,b: Region): Double {
            val area = max(0.0,min(a.right,b.right)-max(a.left,b.left))*max(0.0,min(a.bottom,b.bottom)-max(a.top,b.top))
            return area/(a.area+b.area-area)
        }
    }
}
