package dev.hanginthere.core

import kotlin.math.*
import kotlin.test.*

class ObservedRailFitterTests {
    private val size = ImageSize(720.0, 1280.0)
    private val region = BarRegion(100.0, 400.0, 650.0, 900.0)
    private val grip = Point2D(355.0, 480.0)
    private fun arc(radius: Double, low: Double = -.8, high: Double = .8,
                    center: Point2D = Point2D(300.0, 600.0)): List<Point2D> = (0..120).map {
        val theta = low + (high - low) * it / 120
        Point2D(center.x + radius * cos(theta), center.y + radius * sin(theta))
    }
    private fun fit(first: List<Point2D> = arc(80.0), second: List<Point2D> = arc(100.0),
                    secondDark: Boolean = true, contact: Point2D = grip) = ObservedRailFitter.candidates(
        listOf(RailContour(first, true), RailContour(second, secondDark)), region, size, contact)

    @Test fun pairedCurvesRetainObservedArcAndActualFiniteBoundaries() {
        val pair = fit().maxBy { it.supportArcPixels }
        assertEquals(0, pair.firstContour); assertEquals(1, pair.secondContour)
        assertTrue(pair.supportArcPixels >= 28.8)
        assertTrue(pair.minimumWidthPixels in 18.0..22.0)
        assertTrue(pair.maximumWidthPixels in 18.0..22.0)
        assertTrue(pair.contactDistancePixels in 40.0..80.0)
        assertTrue(ObservedCurveGeometry.isValid(pair.firstBoundary, size))
        assertTrue(ObservedCurveGeometry.isValid(pair.secondBoundary, size))
        // The middle is curved: preserving only a whole-curve chord would
        // remove over 15 pixels of real observed support near the bulge.
        val points = pair.firstBoundary.points
        val chord = BarSegment(points.first(), points.last())
        assertTrue(points.maxOf { chord.distance(it) } > 15)
    }

    @Test fun reversedContourOrderPreservesTheSameGeometricProposal() {
        val original = fit().maxBy { it.supportArcPixels }
        val reversed = fit(arc(80.0).reversed(), arc(100.0).reversed()).maxBy { it.supportArcPixels }
        assertEquals(original.supportArcPixels, reversed.supportArcPixels, 8.0)
        assertTrue(ObservedCurveGeometry.matches(original.firstBoundary, reversed.firstBoundary, size))
        assertTrue(ObservedCurveGeometry.matches(original.secondBoundary, reversed.secondBoundary, size))
    }

    @Test fun duplicateWideShortAndOppositeContrastCurvesCannotProposeARail() {
        assertTrue(fit(second = arc(80.0)).isEmpty())
        assertTrue(fit(second = arc(130.0)).isEmpty())
        assertTrue(fit(arc(80.0, -.05, .05), arc(100.0, -.05, .05)).isEmpty())
        assertTrue(fit(secondDark = false).isEmpty())
    }

    @Test fun straightEdgesRemainWithTheExistingStraightEdgeFitter() {
        assertTrue(fit(listOf(Point2D(300.0, 550.0), Point2D(300.0, 750.0)),
            listOf(Point2D(320.0, 550.0), Point2D(320.0, 750.0))).isEmpty())
    }

    @Test fun explicitFiniteEdgeModeRetainsStraightObservedBoundariesAndCurvedPaths() {
        val contours = listOf(listOf(Point2D(300.0, 550.0), Point2D(300.0, 750.0)),
            listOf(Point2D(320.0, 550.0), Point2D(320.0, 750.0)))
        val pairs = ObservedRailFitter.candidates(contours.map { RailContour(it, true) }, region, size, grip,
            ObservedRailFitter.Mode.finiteEdges)
        assertTrue(pairs.isNotEmpty())
        assertObservedSubpaths(pairs, contours)
        assertEquals(20.0, pairs.single().minimumWidthPixels, 1e-8)
        val curved = ObservedRailFitter.candidates(listOf(RailContour(arc(80.0), true), RailContour(arc(100.0), true)),
            region, size, grip, ObservedRailFitter.Mode.finiteEdges)
        assertEquals(fit(), curved)
    }

    @Test fun finiteStraightPairCannotExtendAnUnmatchedTailToTheGrip() {
        val contours = listOf(RailContour(listOf(Point2D(300.0, 550.0), Point2D(300.0, 800.0)), true),
            RailContour(listOf(Point2D(320.0, 700.0), Point2D(320.0, 850.0)), true))
        assertTrue(ObservedRailFitter.candidates(contours, region, size, Point2D(305.0, 550.0),
            ObservedRailFitter.Mode.finiteEdges).isEmpty())
    }

    @Test fun independentMCPsBelowATubeCanUseItsActualObservedSupport() {
        val contours = listOf(listOf(Point2D(300.0, 550.0), Point2D(450.0, 550.0)),
            listOf(Point2D(300.0, 570.0), Point2D(450.0, 570.0)))
        val hand = Point2D(355.0, 600.0)
        val values = contours.map { RailContour(it, true) }
        assertTrue(ObservedRailFitter.candidates(values, region, size, hand, ObservedRailFitter.Mode.finiteEdges).isEmpty())
        val actual = ObservedRailFitter.candidates(values, region, size, hand, ObservedRailFitter.Mode.finiteEdges,
            ObservedRailFitter.ContactRegion.aroundGrip)
        assertTrue(actual.isNotEmpty())
        assertObservedSubpaths(actual, contours)
        assertEquals(30.0, actual.single().contactDistancePixels, 1e-8)
        assertEquals(20.0, actual.single().minimumWidthPixels, 1e-8)
        // Around-grip proposals still cannot extend distant finite support.
        assertTrue(ObservedRailFitter.candidates(values, region, size, Point2D(355.0, 750.0),
            ObservedRailFitter.Mode.finiteEdges, ObservedRailFitter.ContactRegion.aroundGrip).isEmpty())
    }

    @Test fun finiteStraightPairKeepsGapsContrastBordersAndWidthConstraints() {
        fun line(x: Double, low: Double = 550.0, high: Double = 750.0) = listOf(Point2D(x, low), Point2D(x, high))
        for (contours in listOf(listOf(RailContour(line(300.0), true), RailContour(line(300.0), true)),
            listOf(RailContour(line(300.0), true), RailContour(line(350.0), true)),
            listOf(RailContour(line(300.0), true), RailContour(line(320.0), false)),
            listOf(RailContour(line(300.0, 550.0, 570.0), true), RailContour(line(320.0, 550.0, 570.0), true)),
            listOf(RailContour(line(region.minX + 1), true), RailContour(line(region.minX + 21), true)))) {
            assertTrue(ObservedRailFitter.candidates(contours, region, size, grip, ObservedRailFitter.Mode.finiteEdges).isEmpty())
        }
        val broken = (0..5).flatMap { i -> listOf(Point2D(300.0, 550.0+i*30), Point2D(300.0, 570.0+i*30), Point2D(300.0, 450.0)) }
        assertTrue(ObservedRailFitter.candidates(listOf(RailContour(broken, true), RailContour(line(320.0), true)),
            region, size, grip, ObservedRailFitter.Mode.finiteEdges).isEmpty())
    }

    @Test fun anUnmatchedTailNearTheGripCannotEstablishFinitePairedContact() {
        val high = Point2D(300.0, 700.0)
        assertTrue(fit(arc(150.0, -1.0, 1.0, high), arc(170.0, -.1, 1.0, high),
            contact = Point2D(381.0, 530.0)).isEmpty())
    }

    @Test fun removingPointsAboveTheGripDoesNotJoinTheirSurroundingPieces() {
        val broken = arc(80.0).toMutableList()
        // Every surviving observed section is shorter than the minimum pair;
        // no interpolation may bridge through the rejected point above grip.
        for (i in broken.indices step 10) broken[i] = Point2D(broken[i].x, 450.0)
        assertTrue(fit(first = broken).isEmpty())
    }

    @Test fun cropBordersAndUnreachableFiniteCurvesAreNotSupport() {
        val border = arc(80.0).map { Point2D(region.minX + 1, it.y) }
        assertTrue(fit(first = border).isEmpty())
        assertTrue(fit(contact = Point2D(100.0, 450.0)).isEmpty())
    }

    @Test fun malformedCoordinatesAndComplexityFailExplicitly() {
        for (bad in listOf(Point2D(Double.NaN, 500.0), Point2D(50.0, 500.0))) {
            assertFailsWith<BarFitException> { fit(first = arc(80.0) + bad) }
        }
        assertFailsWith<BarFitException> {
            ObservedRailFitter.candidates(List(513) { RailContour(emptyList(), true) }, region, size, grip)
        }
        assertFailsWith<BarFitException> { fit(contact = Point2D(-1.0, 500.0)) }
    }

    @Test fun allGeometricAlternativesRemainAvailableForAmbiguityChecks() {
        val pairs = ObservedRailFitter.candidates(listOf(RailContour(arc(80.0), true),
            RailContour(arc(100.0), true), RailContour(arc(115.0), true)), region, size, grip)
        assertTrue(pairs.any { it.firstContour == 0 && it.secondContour == 1 })
        assertTrue(pairs.any { it.firstContour == 0 && it.secondContour == 2 })
        assertTrue(pairs.any { it.firstContour == 1 && it.secondContour == 2 })
    }

    @Test fun emittedBoundariesRetainSourceVerticesInsteadOfJoiningIntersectionChords() {
        val contours = listOf(arc(80.0), arc(100.0))
        val pairs = fit()
        assertTrue(pairs.isNotEmpty())
        assertObservedSubpaths(pairs, contours)
        val pair = pairs.maxBy { it.supportArcPixels }
        assertTrue(Point2D(380.0, 600.0) in pair.firstBoundary.points)
        assertTrue(Point2D(400.0, 600.0) in pair.secondBoundary.points)
    }

    @Test fun aFoldedSimpleContourCannotSupplyAShortcutAcrossItsInterior() {
        val first = (0..120).map { i ->
            val y = 550.0 + i * 1.5
            Point2D(350 + 5 * sin((y - 550) * PI / 200), y)
        }
        val folded = (0..100).map { Point2D(380.0, 550.0 + it * 2) } +
            (1..60).map { val t = PI - it * PI / 60; Point2D(400 + 20 * cos(t), 750 + 20 * sin(t)) } +
            (1..100).map { Point2D(420.0, 750.0 - it * 2) }
        // A folded observation may yield an actual local leg, or fail closed;
        // it may never replace the path around the turn with a gap-crossing chord.
        assertObservedSubpaths(fit(first, folded), listOf(first, folded))
    }

    @Test fun validButExcessiveGeometryWorkFailsWithoutTruncatingAlternatives() {
        assertFailsWith<BarFitException> {
            ObservedRailFitter.candidates(List(512) { RailContour(arc(if (it % 2 == 0) 80.0 else 100.0), true) }, region, size, grip)
        }
        assertFailsWith<BarFitException> { fit(first = List(200_001) { Point2D(300.0, 550.0) }) }
    }

    @Test fun aSourceVertexAlmostOnTheSamplingGridDoesNotBecomeAStalledMatch() {
        fun halfArc(radius: Double): Double {
            val points = BarFitter.simplify(arc(radius), 1.0) {}
            val center = points.indexOf(Point2D(300 + radius, 600.0))
            assertTrue(center > 0)
            return points.take(center + 1).zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        }
        val initial = halfArc(100.0)
        val radius = 100 * ((ceil(initial / 4) * 4 + 1e-9) / initial)
        val actual = halfArc(radius)
        val offset = abs(actual - round(actual / 4) * 4)
        assertTrue(offset > 0 && offset < 1e-8)
        assertTrue(fit(second = arc(radius)).isNotEmpty())
    }

    private fun assertObservedSubpaths(pairs: List<ObservedRailCandidate>, contours: List<List<Point2D>>) {
        val simplified = contours.map { ObservedCurve(BarFitter.simplify(it, 1.0) {}) }
        for (pair in pairs) for ((boundary, source) in listOf(pair.firstBoundary to pair.firstContour, pair.secondBoundary to pair.secondContour)) {
            for ((a, b) in boundary.points.zipWithNext()) {
                val midpoint = Point2D((a.x + b.x) / 2, (a.y + b.y) / 2)
                assertEquals(0.0, ObservedCurveGeometry.distance(simplified[source], midpoint, size)!!, 1e-7)
            }
        }
    }
}
