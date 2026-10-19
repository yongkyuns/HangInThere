package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.*

/** Source-clock acquisition of two stable curved image observations. This does
 * not prove mechanical apparatus identity, hand contact, body load or camera
 * stability, and does not start/count a workout. Native owners retain the
 * original pose/pixels together and capture [generation] before asynchronous
 * inference. Candidates must come from [ObservedRailFitter] on that same frame.
 * All methods require one serial native acquisition owner, never the UI thread.
 * Generation checks cancel serially delivered callbacks, not concurrent access.
 */
class CurvedDipRailAcquisition {
    @Serializable enum class Status {
        waiting, unavailable, invalidCandidates, ambiguous, sameSupport, tooComplex,
        sourceRegression, candidateStarted, contextChanged, sourceGap, shapeChanged,
        gripMoved, accumulating, qualified
    }
    @Serializable data class Diagnostic(val status: Status = Status.waiting,
        val observations: Int = 0, val stableSeconds: Double = 0.0,
        val sourceTime: PresentationTime? = null)
    @Serializable data class Context(val bodyBackend: String, val bodyRevision: Int,
        val handModel: String, val handBackend: String)
    @Serializable data class Search(val generation: Long, val sourceTime: PresentationTime,
        val imageSize: ImageSize, val hands: List<Point2D>, val regions: List<BarRegion>)
    @Serializable @ConsistentCopyVisibility
    data class Reference private constructor(val left: ObservedRailCandidate, val right: ObservedRailCandidate,
        val hands: List<Point2D>, val imageSize: ImageSize, val sourceTime: PresentationTime,
        val context: Context, val generation: Long) {
        companion object {
            internal fun create(rails: List<ObservedRailCandidate>, hands: List<Point2D>, pose: PoseResult,
                                context: Context, generation: Long) = Reference(snapshot(rails[0]), snapshot(rails[1]),
                hands.toList(), pose.imageSize, pose.timestamp, context, generation)
        }
    }
    var generation = 0L; private set
    var diagnostic = Diagnostic(); private set
    var reference: Reference? = null; private set
    private var highWater: PresentationTime? = null
    private var context: Context? = null
    private var size: ImageSize? = null
    private var anchors = emptyList<ObservedRailCandidate>()
    private var hands = emptyList<Point2D>()
    private var firstTime: PresentationTime? = null
    private var lastTime: PresentationTime? = null
    private var observations = 0

    /** Explicit activation of a new source permits a new timestamp epoch. */
    fun resetSource() { generation++; clear(); highWater = null; context = null; size = null }
    /** Cancels old work without allowing an older source clock to earn dwell. */
    fun invalidateEvidence() { generation++; clear() }
    private fun clear() {
        anchors = emptyList(); hands = emptyList(); firstTime = null; lastTime = null
        observations = 0; reference = null; diagnostic = Diagnostic()
    }
    private fun unavailable(status: Status, time: PresentationTime): Reference? {
        clear(); diagnostic = Diagnostic(status, sourceTime = time); return null
    }
    fun search(pose: PoseResult): Search? {
        val sample = BilateralGripDisplacement.Sample.create(pose) ?: return null
        if (abs(sample.left.wrist.x - sample.right.wrist.x) < min(pose.imageSize.width, pose.imageSize.height) * .12) return null
        val arms = listOf(sample.left, sample.right)
        val regions = arms.map { AutomaticDipRails.contactRegion(it.shoulder, it.grip, pose.imageSize) ?: return null }
        return Search(generation, pose.timestamp, pose.imageSize, arms.map { it.grip }, regions)
    }

    /** Late results from a cancelled generation are ignored without disturbing
     * newer evidence. Within an active generation, duplicate/reversed/invalid
     * timestamps revoke dwell and never lower its high-water source clock. */
    fun observe(pose: PoseResult, candidates: List<List<ObservedRailCandidate>>, generation: Long): Reference? {
        if (generation != this.generation) return null
        val time = pose.timestamp
        if (!time.seconds.isFinite() || highWater?.let { SourceTimeComparison.compareElapsed(time, it, zero) != 1 } == true)
            return unavailable(Status.sourceRegression, time)
        highWater = time
        val sample = BilateralGripDisplacement.Sample.create(pose) ?: return unavailable(Status.unavailable, time)
        val search = search(pose) ?: return unavailable(Status.unavailable, time)
        val nextContext = Context(sample.bodyBackend, sample.bodyRevision, sample.handModel, sample.handBackend)
        var restart: Status? = null
        if (context != nextContext || size != pose.imageSize) {
            if (context != null) restart = Status.contextChanged
            clear(); context = nextContext; size = pose.imageSize
        }
        // A delivered reference owns its original source geometry. Consumers
        // independently monitor current camera/grip evidence during the set.
        if (reference != null) return null
        if (candidates.size != 2 || candidates.any { it.isEmpty() }) return unavailable(Status.unavailable, time)
        if (candidates.any { it.size > ObservedRailFitter.maximumCandidates }) return unavailable(Status.tooComplex, time)
        val budget = Budget()
        val selected: List<ObservedRailCandidate>
        try {
            selected = candidates.mapIndexed { side, values ->
                if (values.any { !valid(it, search.hands[side], search.regions[side], pose.imageSize) })
                    return unavailable(Status.invalidCandidates, time)
                // Compatibility is not transitive. Every proposal
                // must agree with every other one; ordering cannot hide clutter.
                // Even the same source contour pair can supply conflicting
                // fragments. Positional raster deduplication cannot erase them.
                for (i in values.indices) for (j in i + 1 until values.size)
                    if (!sameRail(values[i], values[j], pose.imageSize, budget)) return unavailable(Status.ambiguous, time)
                snapshot(values.first())
            }
            val centers = selected.map { rail ->
                val a = ObservedCurveGeometry.centroid(rail.firstBoundary, pose.imageSize) ?: return unavailable(Status.invalidCandidates, time)
                val b = ObservedCurveGeometry.centroid(rail.secondBoundary, pose.imageSize) ?: return unavailable(Status.invalidCandidates, time)
                Point2D(a.x / 2 + b.x / 2, a.y / 2 + b.y / 2)
            }
            if (distance(centers[0], centers[1]) < min(pose.imageSize.width, pose.imageSize.height) * .10 ||
                sameRail(selected[0], selected[1], pose.imageSize, budget)) return unavailable(Status.sameSupport, time)
            val previous = lastTime
            restart = restart ?: when {
                previous == null -> Status.candidateStarted
                SourceTimeComparison.compareElapsed(time, previous, gap) == 1 -> Status.sourceGap
                !anchors.zip(selected).all { sameRail(it.first, it.second, pose.imageSize, budget) } -> Status.shapeChanged
                !hands.zip(search.hands).all { distance(it.first, it.second) <= min(pose.imageSize.width, pose.imageSize.height) * .02 } -> Status.gripMoved
                else -> null
            }
        } catch (_: TooComplex) { return unavailable(Status.tooComplex, time) }
        if (restart != null) { anchors = selected.map(::snapshot); hands = search.hands.toList(); firstTime = time; observations = 0 }
        lastTime = time; observations++
        val first = firstTime ?: time
        val ready = observations >= AutomaticReferenceStability.minimumObservations &&
            SourceTimeComparison.compareElapsed(time, first, dwell)?.let { it >= 0 } == true
        // The exact comparison above owns qualification. This Double is only
        // a bounded diagnostic and cannot add source-time credit.
        val elapsed = time.elapsedSeconds(first).takeIf { it.isFinite() && it >= 0 } ?: 0.0
        diagnostic = Diagnostic(if (ready) Status.qualified else restart ?: Status.accumulating, observations, elapsed, time)
        if (!ready) return null
        return Reference.create(selected, search.hands, pose, nextContext, this.generation).also { reference = it }
    }

    private fun valid(candidate: ObservedRailCandidate, hand: Point2D, region: BarRegion, size: ImageSize): Boolean {
        if (candidate.firstContour !in 0 until BarFitter.maximumSegments || candidate.secondContour !in 0 until BarFitter.maximumSegments ||
            !candidate.supportArcPixels.isFinite() || !candidate.minimumWidthPixels.isFinite() || !candidate.maximumWidthPixels.isFinite() ||
            !candidate.contactDistancePixels.isFinite() || candidate.minimumWidthPixels !in 2.0..40.0 ||
            candidate.maximumWidthPixels !in candidate.minimumWidthPixels..40.0 || candidate.minimumWidthPixels < .4 * candidate.maximumWidthPixels) return false
        val boundaries = listOf(candidate.firstBoundary, candidate.secondBoundary)
        val lengths = boundaries.map { curve ->
            if (curve.points.size !in 2..ObservedCurveGeometry.maximumPoints || curve.points.any { !region.contains(it) || it.y < hand.y }) return false
            ObservedCurveGeometry.sampling(curve, size)?.arc?.last() ?: return false
        }
        val minimum = AutomaticDipRails.minimumPairLength(size)
        if (lengths.any { it + epsilon < minimum } || abs(lengths[0] - candidate.supportArcPixels) > epsilon) return false
        val actualDistance = boundaries.minOf { ObservedCurveGeometry.distance(it, hand, size) ?: return false }
        return actualDistance <= min(size.width, size.height) * .12 && abs(actualDistance - candidate.contactDistancePixels) <= epsilon
    }
    private fun sameRail(a: ObservedRailCandidate, b: ObservedRailCandidate, size: ImageSize, budget: Budget) =
        compareBoundaries(a, b) { p, q -> budget.spend(); ObservedCurveGeometry.matches(p, q, size) }
    private fun compareBoundaries(a: ObservedRailCandidate, b: ObservedRailCandidate, matches: (ObservedCurve, ObservedCurve) -> Boolean) =
        matches(a.firstBoundary, b.firstBoundary) && matches(a.secondBoundary, b.secondBoundary) ||
            matches(a.firstBoundary, b.secondBoundary) && matches(a.secondBoundary, b.firstBoundary)
    private class TooComplex : RuntimeException()
    private class Budget {
        var remaining = ObservedRailFitter.maximumCandidates * 2
        fun spend() { if (--remaining < 0) throw TooComplex() }
    }
    companion object {
        private const val epsilon = 1e-6
        private val zero = SourceTimeLimit(0, 1)
        private val gap = SourceTimeLimit(4, 5)
        private val dwell = SourceTimeLimit(9, 10)
        private fun distance(a: Point2D, b: Point2D) = hypot(a.x - b.x, a.y - b.y)
        private fun snapshot(c: ObservedRailCandidate) = c.copy(firstBoundary = ObservedCurve(c.firstBoundary.points.toList()),
            secondBoundary = ObservedCurve(c.secondBoundary.points.toList()))
    }
}

/** Stateful acquisition transport; native owners capture generation before work. */
class CurvedDipRailAcquisitionBridge {
    private val engine = CurvedDipRailAcquisition()
    @Serializable private data class Input(val pose: PoseResult, val candidates: List<List<ObservedRailCandidate>>, val generation: Long)
    @Serializable private data class Output(val diagnostic: CurvedDipRailAcquisition.Diagnostic,
        val reference: CurvedDipRailAcquisition.Reference? = null)
    val generation get() = engine.generation
    fun resetSource() = engine.resetSource()
    fun invalidateEvidence() = engine.invalidateEvidence()
    @Throws(IllegalArgumentException::class)
    fun observeJson(input: String): String {
        val frame = WorkoutRecordCodec.json.decodeFromString<Input>(input)
        val reference = engine.observe(frame.pose, frame.candidates, frame.generation)
        return WorkoutRecordCodec.json.encodeToString(Output(engine.diagnostic, reference))
    }
    @Throws(IllegalArgumentException::class)
    fun searchJson(input: String): String? = engine.search(WorkoutRecordCodec.json.decodeFromString<PoseResult>(input))
        ?.let { WorkoutRecordCodec.json.encodeToString(it) }
}
