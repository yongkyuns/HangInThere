package dev.hanginthere.core

import kotlin.math.*
import kotlin.test.*

class CurvedDipRailAcquisitionTests {
    private val size = ImageSize(720.0, 1280.0)
    private fun pose(time: PresentationTime = PresentationTime(0, 1000), elbows: Boolean = true): PoseResult {
        val frame = BilateralGripFixtures.frame(elbows = elbows)
        return frame.copy(timestamp = time, gripObservation = frame.gripObservation!!.copy(seconds = time.seconds))
    }
    private val candidates: List<List<ObservedRailCandidate>> by lazy {
        val search = CurvedDipRailAcquisition().search(pose())!!
        search.hands.mapIndexed { side, hand ->
            val curves = listOf(60.0, 68.0).map { radius -> RailContour((0..60).map {
                val t = -.6 + it * 1.2 / 60
                Point2D(hand.x - 50 + radius * cos(t), 700 + radius * sin(t))
            }, true) }
            listOf(ObservedRailFitter.candidates(curves, search.regions[side], size, hand).maxBy { it.supportArcPixels })
        }
    }
    private fun observe(engine: CurvedDipRailAcquisition, ticks: Long, values: List<List<ObservedRailCandidate>> = candidates,
                        generation: Long = engine.generation) = engine.observe(pose(PresentationTime(ticks, 1000)), values, generation)
    private fun finish(engine: CurvedDipRailAcquisition, offset: Long = 0): CurvedDipRailAcquisition.Reference? {
        var result: CurvedDipRailAcquisition.Reference? = null
        for (t in listOf(0L, 300L, 600L, 900L)) result = observe(engine, offset + t)
        return result
    }
    private fun shifted(c: ObservedRailCandidate, x: Double, hand: Point2D): ObservedRailCandidate {
        val a = ObservedCurve(c.firstBoundary.points.map { Point2D(it.x + x, it.y) })
        val b = ObservedCurve(c.secondBoundary.points.map { Point2D(it.x + x, it.y) })
        return c.copy(firstBoundary = a, secondBoundary = b,
            contactDistancePixels = min(ObservedCurveGeometry.distance(a, hand, size)!!, ObservedCurveGeometry.distance(b, hand, size)!!))
    }

    @Test fun fourCurrentObservationsYieldFiniteReferenceFromTheQualifyingFrame() {
        val engine = CurvedDipRailAcquisition()
        val result = assertNotNull(finish(engine))
        assertEquals(PresentationTime(900, 1000), result.sourceTime)
        assertEquals(4, engine.diagnostic.observations)
        assertEquals(CurvedDipRailAcquisition.Status.qualified, engine.diagnostic.status)
        assertEquals(candidates[0][0], result.left)
        assertTrue(result.left.firstBoundary.points.size > 2)
        assertEquals(listOf(Point2D(200.0, 640.0), Point2D(500.0, 640.0)), result.hands)
        assertNull(observe(engine, 1200))
        assertEquals(result, engine.reference)
    }

    @Test fun bothObservationCountAndExactSourceDwellAreRequired() {
        val engine = CurvedDipRailAcquisition()
        for (t in listOf(0L, 300000L, 600000L, 899999L))
            assertNull(engine.observe(pose(PresentationTime(t, 1_000_000)), candidates, engine.generation))
        assertNotNull(engine.observe(pose(PresentationTime(900000, 1_000_000)), candidates, engine.generation))
        val few = CurvedDipRailAcquisition()
        for (t in listOf(0L, 450L, 900L)) assertNull(observe(few, t))
        assertNotNull(observe(few, 1200))
    }

    @Test fun exactGapAllowedButOneMicrosecondOverRestartsDwell() {
        for ((second, ready) in listOf(800000L to true, 800001L to false)) {
            val engine = CurvedDipRailAcquisition()
            var result: CurvedDipRailAcquisition.Reference? = null
            for (t in listOf(0L, second, 1100000L, 1400000L))
                result = engine.observe(pose(PresentationTime(t, 1_000_000)), candidates, engine.generation)
            assertEquals(ready, result != null)
        }
    }

    @Test fun duplicateReverseAndInvalidClocksCannotReuseEarlierCredit() {
        for (fault in listOf(PresentationTime(300, 1000), PresentationTime(100, 1000), PresentationTime(0, 0))) {
            val engine = CurvedDipRailAcquisition()
            observe(engine, 0); observe(engine, 300)
            assertNull(engine.observe(pose(fault), candidates, engine.generation))
            assertEquals(CurvedDipRailAcquisition.Status.sourceRegression, engine.diagnostic.status)
            assertNull(observe(engine, 600)); assertNull(observe(engine, 900)); assertNull(observe(engine, 1200))
            assertNotNull(observe(engine, 1500))
        }
    }

    @Test fun invalidatingEvidencePreservesClockAndCancelsOldGenerations() {
        val engine = CurvedDipRailAcquisition(); val old = engine.generation
        observe(engine, 1000); observe(engine, 1300)
        engine.invalidateEvidence()
        assertNull(observe(engine, 0))
        assertEquals(CurvedDipRailAcquisition.Status.sourceRegression, engine.diagnostic.status)
        observe(engine, 1600)
        val current = engine.diagnostic
        assertNull(observe(engine, 5000, generation = old))
        assertEquals(current, engine.diagnostic)
        for (t in listOf(1900L, 2200L)) assertNull(observe(engine, t))
        assertNotNull(observe(engine, 2500))
    }

    @Test fun explicitSourceActivationPermitsANewClockButOldCallbacksCannotAdvanceIt() {
        val engine = CurvedDipRailAcquisition(); val old = engine.generation
        observe(engine, 10000); engine.resetSource()
        observe(engine, 0)
        val current = engine.diagnostic
        assertNull(observe(engine, 11000, generation = old)); assertEquals(current, engine.diagnostic)
        for (t in listOf(300L, 600L)) assertNull(observe(engine, t))
        assertNotNull(observe(engine, 900))
    }

    @Test fun aShortMissingHandOrRailPausesButDoesNotCreditItsIntervals() {
        for (fault in listOf("hand", "left", "right")) {
            val engine = CurvedDipRailAcquisition()
            observe(engine, 0); observe(engine, 300)
            val frame = pose(PresentationTime(600, 1000))
            val changed = if (fault == "hand") frame.copy(gripObservation = frame.gripObservation!!.copy(hands = frame.gripObservation.hands.take(1))) else frame
            val rails = when (fault) { "left" -> listOf(emptyList(), candidates[1]); "right" -> listOf(candidates[0], emptyList()); else -> candidates }
            assertNull(engine.observe(changed, rails, engine.generation))
            assertEquals(.3, engine.diagnostic.stableSeconds)
            for (t in listOf(900L, 1200L)) assertNull(observe(engine, t))
            assertNotNull(observe(engine, 1500))
            assertEquals(2, engine.reference!!.acquisitionPolicyVersion)
        }
    }
    @Test fun alternatingKnownMissingFramesNeverAccrueQualificationTime() {
        val engine = CurvedDipRailAcquisition()
        for (i in 0L..30L) assertNull(observe(engine, i * 100,
            if (i % 2L == 0L) candidates else listOf(emptyList(), candidates[1])))
        assertEquals(0.0, engine.diagnostic.stableSeconds)
        assertEquals(16, engine.diagnostic.observations)
    }
    @Test fun observedCreditPolicyCanCombineMatchingSegmentsButNeverTheirKnownGaps() {
        val engine = CurvedDipRailAcquisition()
        for (time in listOf(0L, 300L)) assertNull(observe(engine, time))
        assertNull(observe(engine, 400, listOf(emptyList(), candidates[1])))
        for (time in listOf(900L, 1200L)) assertNull(observe(engine, time))
        assertNull(observe(engine, 1300, listOf(emptyList(), candidates[1])))
        assertNull(observe(engine, 1800))
        assertEquals(.6, engine.diagnostic.stableSeconds)
        assertNotNull(observe(engine, 2100))
        assertEquals(.9, engine.diagnostic.stableSeconds)
        assertEquals(2, engine.diagnostic.policyVersion)
    }
    @Test fun aTransientGripSpikeCannotReplaceTheAnchorOrCreditTheRejectedWindow() {
        val engine = CurvedDipRailAcquisition()
        observe(engine, 0); observe(engine, 300)
        val spike = BilateralGripFixtures.frame(ticks = 400, handShift = 20.0)
        val left = shifted(candidates[0].single(), 0.0, Point2D(220.0, 640.0))
        assertNull(engine.observe(spike, listOf(listOf(left), candidates[1]), engine.generation))
        assertEquals(CurvedDipRailAcquisition.Status.gripMoved, engine.diagnostic.status)
        assertEquals(.3, engine.diagnostic.stableSeconds)
        assertNull(observe(engine, 500)); assertNull(observe(engine, 800))
        val reference = assertNotNull(observe(engine, 1100))
        assertEquals(Point2D(200.0, 640.0), reference.hands.first())
    }
    @Test fun missingRailCannotHideAMalformedOrAmbiguousOtherSide() {
        for (bad in listOf(candidates[1].single().copy(firstContour = -1), candidates[1].single().copy(minimumWidthPixels = Double.NaN))) {
            val engine = CurvedDipRailAcquisition(); observe(engine, 0); observe(engine, 300)
            assertNull(observe(engine, 600, listOf(emptyList(), listOf(bad))))
            assertEquals(CurvedDipRailAcquisition.Status.invalidCandidates, engine.diagnostic.status)
            assertEquals(0.0, engine.diagnostic.stableSeconds)
        }
    }

    @Test fun cachedHandsCannotQualifyANewerPrimaryPose() {
        val engine = CurvedDipRailAcquisition()
        val frame = pose(PresentationTime(300, 1000))
        assertNull(engine.observe(frame.copy(gripObservation = frame.gripObservation!!.copy(seconds = .2)), candidates, engine.generation))
        assertEquals(CurvedDipRailAcquisition.Status.unavailable, engine.diagnostic.status)
    }

    @Test fun backendAndHandModelChangesRequireAFreshDwell() {
        for (handModel in listOf(false, true)) {
            val engine = CurvedDipRailAcquisition()
            observe(engine, 0); observe(engine, 300)
            for (t in listOf(600L, 900L, 1200L)) {
                val frame = pose(PresentationTime(t, 1000))
                val changed = if (handModel) frame.copy(gripObservation = frame.gripObservation!!.copy(modelId = "changed")) else frame.copy(backend = "changed")
                assertNull(engine.observe(changed, candidates, engine.generation))
            }
            val frame = pose(PresentationTime(1500, 1000))
            assertNotNull(engine.observe(if (handModel) frame.copy(gripObservation = frame.gripObservation!!.copy(modelId = "changed")) else frame.copy(backend = "changed"), candidates, engine.generation))
        }
    }

    @Test fun aSecondIncompatibleProposalCannotBeHiddenByRankingOrInputOrder() {
        val other = shifted(candidates[0][0], 20.0, Point2D(200.0, 640.0)).copy(firstContour = 2, secondContour = 3)
        for (order in listOf(listOf(candidates[0][0], other), listOf(other, candidates[0][0]))) {
            val engine = CurvedDipRailAcquisition()
            for (t in listOf(0L, 300L, 600L, 900L)) assertNull(observe(engine, t, listOf(order, candidates[1])))
            assertEquals(CurvedDipRailAcquisition.Status.ambiguous, engine.diagnostic.status)
        }
    }

    @Test fun sourceRasterAliasesDoNotRequireAUserChoice() {
        val duplicate = candidates.map { it + it }
        val engine = CurvedDipRailAcquisition()
        var result: CurvedDipRailAcquisition.Reference? = null
        for (t in listOf(0L, 300L, 600L, 900L)) result = observe(engine, t, duplicate)
        assertNotNull(result)
    }

    @Test fun malformedAlternativeCannotHideBehindAValidProposal() {
        for (bad in listOf(candidates[0][0].copy(firstContour = -1), candidates[0][0].copy(minimumWidthPixels = Double.NaN),
            candidates[0][0].copy(contactDistancePixels = 0.0))) {
            val engine = CurvedDipRailAcquisition()
            assertNull(observe(engine, 0, listOf(candidates[0] + bad, candidates[1])))
            assertEquals(CurvedDipRailAcquisition.Status.invalidCandidates, engine.diagnostic.status)
        }
    }

    @Test fun shapeDriftCannotAccumulateOldDwell() {
        val engine = CurvedDipRailAcquisition()
        observe(engine, 0); observe(engine, 300)
        val changed = listOf(listOf(shifted(candidates[0][0], 8.0, Point2D(200.0, 640.0))), candidates[1])
        assertNull(observe(engine, 600, changed))
        assertEquals(CurvedDipRailAcquisition.Status.shapeChanged, engine.diagnostic.status)
        assertNull(observe(engine, 900, changed)); assertNull(observe(engine, 1200, changed)); assertNotNull(observe(engine, 1500, changed))
    }

    @Test fun callerMutationCannotRewriteTheWitnessedAnchor() {
        val engine = CurvedDipRailAcquisition()
        val points = candidates[0][0].firstBoundary.points.toMutableList()
        val modified = candidates[0][0].copy(firstBoundary = ObservedCurve(points))
        observe(engine, 0, listOf(listOf(modified), candidates[1]))
        points[0] = Point2D(0.0, 0.0)
        assertNull(observe(engine, 300)); assertNull(observe(engine, 600)); assertNotNull(observe(engine, 900))
    }

    @Test fun missingElbowsMayLocateRailsButDoNotSupplyAStartingTop() {
        val engine = CurvedDipRailAcquisition()
        var result: CurvedDipRailAcquisition.Reference? = null
        for (t in listOf(0L, 300L, 600L, 900L)) result = engine.observe(pose(PresentationTime(t, 1000), elbows = false), candidates, engine.generation)
        assertNotNull(result)
        assertFalse(BilateralGripDisplacement.Sample.create(pose(elbows = false))!!.isStartingTop)
    }

    @Test fun smallSuccessiveHandMovementsCannotAccumulateBeyondTheOriginalAnchor() {
        val engine = CurvedDipRailAcquisition()
        for ((ticks, shift) in listOf(0L to 0.0, 200L to 5.0, 400L to 10.0, 600L to 15.0)) {
            val frame = BilateralGripFixtures.frame(ticks = ticks, handShift = shift)
            val left = shifted(candidates[0].single(), 0.0, Point2D(200.0 + shift, 640.0))
            assertNull(engine.observe(frame, listOf(listOf(left), candidates[1]), engine.generation))
        }
        assertEquals(CurvedDipRailAcquisition.Status.gripMoved, engine.diagnostic.status)
        for (ticks in listOf(900L, 1200L, 1500L, 1800L, 2100L, 2400L)) {
            val frame = BilateralGripFixtures.frame(ticks = ticks, handShift = 15.0)
            val left = shifted(candidates[0].single(), 0.0, Point2D(215.0, 640.0))
            val result = engine.observe(frame, listOf(listOf(left), candidates[1]), engine.generation)
            assertEquals(ticks == 2400L, result != null)
        }
    }

    @Test fun agreementWithOneMiddleProposalCannotHideIncompatibleExtremes() {
        val middle = candidates[0].single()
        val a = shifted(middle, -4.0, Point2D(200.0, 640.0)).copy(firstContour = 2, secondContour = 3)
        val b = shifted(middle, 4.0, Point2D(200.0, 640.0)).copy(firstContour = 4, secondContour = 5)
        assertTrue(ObservedCurveGeometry.matches(middle.firstBoundary, a.firstBoundary, size))
        assertTrue(ObservedCurveGeometry.matches(middle.firstBoundary, b.firstBoundary, size))
        assertFalse(ObservedCurveGeometry.matches(a.firstBoundary, b.firstBoundary, size))
        for (order in listOf(listOf(middle, a, b), listOf(a, middle, b), listOf(b, a, middle))) {
            val engine = CurvedDipRailAcquisition()
            assertNull(observe(engine, 0, listOf(order, candidates[1])))
            assertEquals(CurvedDipRailAcquisition.Status.ambiguous, engine.diagnostic.status)
        }
    }

    @Test fun twoHandsSeeingTheSameObservedSupportCannotEstablishTwoRails() {
        val original = pose()
        val points = original.people.single().landmarks.map {
            if (it.joint.name.startsWith("right")) it.copy(position = Point2D(it.position.x - 200, it.position.y)) else it
        }
        val observation = original.gripObservation!!
        val frame = original.copy(people = listOf(PoseObservation(points)), gripObservation = observation.copy(
            trackedBody = observation.trackedBody.map {
                if (it.joint.name.startsWith("right")) it.copy(position = Point2D(it.position.x - 200, it.position.y)) else it
            }, hands = observation.hands.mapIndexed { i, h -> if (i == 0) h else h.copy(points = h.points.map {
                it.copy(position = Point2D(it.position.x - 200, it.position.y))
            }) }))
        val shared = shifted(candidates[0].single(), 35.0, Point2D(200.0, 640.0))
        val right = shifted(shared, 0.0, Point2D(300.0, 640.0))
        val engine = CurvedDipRailAcquisition()
        assertNull(engine.observe(frame, listOf(listOf(shared), listOf(right)), engine.generation))
        assertEquals(CurvedDipRailAcquisition.Status.sameSupport, engine.diagnostic.status)
    }

    @Test fun largeMixedSourceClocksKeepSubMillisecondDwellBoundaries() {
        val engine = CurvedDipRailAcquisition()
        val start = 500_000_000_000L
        val clocks = listOf(PresentationTime(start * 20, 20), PresentationTime(start * 1000 + 300, 1000),
            PresentationTime(start * 1000 + 600, 1000), PresentationTime(start * 1_000_000 + 899999, 1_000_000))
        for (clock in clocks) assertNull(engine.observe(pose(clock), candidates, engine.generation))
        val end = PresentationTime(start * 1_000_000 + 900000, 1_000_000)
        assertEquals(end, assertNotNull(engine.observe(pose(end), candidates, engine.generation)).sourceTime)
    }

    @Test fun rasterAliasCannotHideIncompatibleDirectionsFromTheSameContourPair() {
        fun proposal(degrees: Double): ObservedRailCandidate {
            val radians = degrees * PI / 180
            fun curve(offset: Double) = ObservedCurve((-20..20).map { t ->
                val x = t.toDouble(); val y = .003 * t * t + offset
                Point2D(210 + x * cos(radians) - y * sin(radians), 700 + x * sin(radians) + y * cos(radians))
            })
            val first = curve(0.0); val second = curve(8.0)
            return ObservedRailCandidate(0, 1, first, second,
                first.points.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }, 8.0, 8.0,
                min(ObservedCurveGeometry.distance(first, Point2D(200.0, 640.0), size)!!,
                    ObservedCurveGeometry.distance(second, Point2D(200.0, 640.0), size)!!))
        }
        val middle = proposal(0.0); val a = proposal(-4.0); val b = proposal(4.0)
        // Every corresponding point is within the former 1.5px raster-alias
        // radius, but that does not establish matching local directions.
        for (other in listOf(a, b)) assertTrue(middle.firstBoundary.points.zip(other.firstBoundary.points).all {
            (p, q) -> hypot(p.x - q.x, p.y - q.y) < 1.5
        })
        assertFalse(ObservedCurveGeometry.matches(a.firstBoundary, b.firstBoundary, size))
        for (order in listOf(listOf(middle, a, b), listOf(a, middle, b), listOf(b, a, middle))) {
            val engine = CurvedDipRailAcquisition()
            for (ticks in listOf(0L, 300L, 600L, 900L)) assertNull(observe(engine, ticks, listOf(order, candidates[1])))
            assertEquals(CurvedDipRailAcquisition.Status.ambiguous, engine.diagnostic.status)
        }
    }

    @Test fun comparisonBudgetFailsClosedInsteadOfDroppingProposals() {
        val engine = CurvedDipRailAcquisition()
        assertNull(observe(engine, 0, candidates.map { values -> List(512) { values.single() } }))
        assertEquals(CurvedDipRailAcquisition.Status.tooComplex, engine.diagnostic.status)
    }
}
