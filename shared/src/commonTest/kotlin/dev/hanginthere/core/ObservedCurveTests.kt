package dev.hanginthere.core

import kotlin.math.*
import kotlin.test.*

class ObservedCurveTests {
    private val size = ImageSize(720.0, 1280.0)
    private fun curve(vararg points: Pair<Double, Double>) = ObservedCurve(points.map { Point2D(it.first, it.second) })
    private fun arc(radius: Double, lo: Double = -1.0, hi: Double = 1.0, ry: Double = radius) =
        ObservedCurve((0..100).map { val t = lo + (hi - lo) * it / 100; Point2D(200 + radius * cos(t), 600 + ry * sin(t)) })

    @Test fun identicalCurvesWithDifferentObservedEndpointsMatch() {
        for (radius in listOf(20.0, 25.0, 40.0, 80.0)) {
            val whole = arc(radius); val cropped = arc(radius, -.7, .7)
            assertTrue(ObservedCurveGeometry.matches(whole, cropped, size), "radius $radius")
            assertTrue(ObservedCurveGeometry.matches(cropped, whole, size))
        }
    }
    @Test fun endpointClippingIsIndependentOfDirectionAndOrder() {
        val a = arc(30.0, ry = 60.0); val b = arc(30.0, -.7, .7, ry = 60.0)
        assertTrue(ObservedCurveGeometry.matches(a, ObservedCurve(b.points.reversed()), size))
        assertTrue(ObservedCurveGeometry.matches(ObservedCurve(a.points.reversed()), b, size))
    }
    @Test fun aLineCanBePartiallyObservedWithoutExtendingIt() {
        assertTrue(ObservedCurveGeometry.matches(curve(200.0 to 400.0, 200.0 to 520.0),
            curve(200.0 to 420.0, 200.0 to 500.0), size))
    }
    @Test fun disjointPartsOfTheSameCurveDoNotMatch() {
        assertFalse(ObservedCurveGeometry.matches(arc(30.0, -1.0, -.2), arc(30.0, .2, 1.0), size))
    }
    @Test fun nearbyDisjointLineEndpointsDoNotProvideArcSupport() {
        assertFalse(ObservedCurveGeometry.matches(curve(200.0 to 400.0, 200.0 to 450.0),
            curve(200.0 to 451.0, 200.0 to 500.0), size))
    }
    @Test fun displacementBeyondOriginalToleranceIsRejected() {
        val a = arc(40.0); val moved = ObservedCurve(a.points.map { Point2D(it.x + 10, it.y) })
        assertFalse(ObservedCurveGeometry.matches(a, moved, size))
    }
    @Test fun localOrientationBeyondOriginalToleranceIsRejected() {
        val a = curve(200.0 to 400.0, 200.0 to 520.0); val t = 5 * PI / 180
        val rotated = ObservedCurve(a.points.map { Point2D(200 - (it.y - 460) * sin(t), 460 + (it.y - 460) * cos(t)) })
        assertFalse(ObservedCurveGeometry.matches(a, rotated, size))
    }
    @Test fun aNearbyCrossingIsNotSharedCurveSupport() {
        assertFalse(ObservedCurveGeometry.matches(curve(100.0 to 300.0, 300.0 to 500.0),
            curve(100.0 to 500.0, 300.0 to 300.0), size))
    }
    @Test fun distanceUsesFinitePolylineInsteadOfItsChordOrTangent() {
        val a = curve(100.0 to 100.0, 100.0 to 150.0, 150.0 to 150.0)
        assertEquals(50.0, ObservedCurveGeometry.distance(a, Point2D(150.0, 100.0), size)!!, 1e-8)
        assertEquals(50.0, ObservedCurveGeometry.distance(a, Point2D(100.0, 50.0), size)!!, 1e-8)
    }
    @Test fun invalidPointsAndImagesFailClosed() {
        val good = arc(40.0)
        for (bad in listOf(curve(), curve(10.0 to 10.0), curve(10.0 to 10.0, 10.0 to 10.0),
                curve(-1.0 to 10.0, 100.0 to 100.0), curve(10.0 to Double.NaN, 100.0 to 100.0),
                curve(10.0 to 10.0, 721.0 to 100.0))) {
            assertFalse(ObservedCurveGeometry.isValid(bad, size))
            assertFalse(ObservedCurveGeometry.matches(good, bad, size))
            assertNull(ObservedCurveGeometry.distance(bad, Point2D(10.0, 10.0), size))
        }
        assertFalse(ObservedCurveGeometry.matches(good, good, ImageSize(0.0, 1280.0)))
        assertNull(ObservedCurveGeometry.distance(good, Point2D(Double.POSITIVE_INFINITY, 10.0), size))
        assertNull(ObservedCurveGeometry.distance(curve(719.0 to 100.0, 719.0 to 200.0), Point2D(721.0, 150.0), size))
        assertNull(ObservedCurveGeometry.distance(good, Point2D(-1.0, 100.0), size))
    }
    @Test fun loopsBacktrackingAndIntersectingObservationsAreAmbiguous() {
        for (bad in listOf(curve(10.0 to 10.0, 20.0 to 20.0, 10.0 to 10.0),
                curve(10.0 to 10.0, 30.0 to 10.0, 20.0 to 10.0),
                curve(10.0 to 10.0, 30.0 to 30.0, 10.0 to 30.0, 30.0 to 10.0))) {
            assertFalse(ObservedCurveGeometry.isValid(bad, size))
        }
    }
    @Test fun retainedAndResampledWorkAreBothBounded() {
        assertFalse(ObservedCurveGeometry.isValid(ObservedCurve((0..256).map { Point2D(100.0, 100.0 + it) }), size))
        assertFalse(ObservedCurveGeometry.isValid(curve(100.0 to 100.0, 100.0 to 1200.0), size))
        assertTrue(ObservedCurveGeometry.isValid(curve(100.0 to 100.0, 100.0 to 1120.0), size))
    }
    @Test fun structurallyValidGeometryDoesNotEstablishUniquePhysicalIdentity() {
        val hairpin = curve(100.0 to 100.0, 200.0 to 100.0, 200.0 to 101.0, 100.0 to 101.0)
        assertTrue(ObservedCurveGeometry.isValid(hairpin, size))
        // The distance tolerance intentionally cannot distinguish two parallel
        // nearby objects. Acquisition must supply independent uniqueness.
        assertTrue(ObservedCurveGeometry.matches(curve(100.0 to 300.0, 100.0 to 500.0),
            curve(101.0 to 300.0, 101.0 to 500.0), size))
    }
}
