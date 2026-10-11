package dev.hanginthere.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BarLineFitterTests {
    private val size = ImageSize(1000.0, 1000.0)
    private val region = BarRegion(0.0, 0.0, 1000.0, 1000.0)
    private fun line(x: Double, y: Double, length: Double) =
        listOf(Point2D(x, y), Point2D(x + length, y))
    private val tube = listOf(line(100.0, 300.0, 800.0), line(100.0, 308.0, 800.0))

    // All fragments are eligible (>=2px), but localized texture cannot meet
    // the unchanged 250px proposal length. 2,400 exercises repeated trimming.
    private fun clutter() = List(2400) { index ->
        line(50.0 + index % 20, 50.0 + (index / 20) % 20, 4.0)
    }

    private fun candidates(contours: List<List<Point2D>>) =
        BarLineFitter.candidates(contours, region, size)

    @Test fun fragmentedLineGroupingIsUndirectedAcrossVerticalNoise() {
        val fragments=listOf(listOf(Point2D(100.0,20.0),Point2D(101.0,90.0)),listOf(Point2D(101.0,100.0),Point2D(100.0,180.0)))
        for(degrees in 0 until 360 step 45) {
            val angle=degrees*kotlin.math.PI/180
            val rotated=fragments.map{points -> points.map{p -> Point2D(100+(p.x-100)*kotlin.math.cos(angle)-(p.y-100)*kotlin.math.sin(angle),100+(p.x-100)*kotlin.math.sin(angle)+(p.y-100)*kotlin.math.cos(angle))}}
            for(contours in listOf(rotated,rotated.reversed().map{it.reversed()})) {
                val result=BarLineFitter.candidates(contours,BarRegion(0.0,0.0,200.0,200.0),ImageSize(200.0,200.0))
                assertEquals(1,result.size)
                assertTrue(result.single().edge.length>159)
            }
        }
    }

    @Test fun retainsActualTubeAmidEligibleClutterWithoutExtrapolation() {
        val expected = candidates(tube)
        val actual = candidates(clutter() + tube)
        assertEquals(expected, actual)
        assertEquals(2, actual.size)
        val pair = assertNotNull(BarFitter.pair(actual[0].edge, actual[1].edge, 24.0))
        assertEquals(BarSegment(Point2D(100.0, 304.0), Point2D(900.0, 304.0)), pair.centerline)
    }

    @Test fun overflowRankingIsIndependentOfContourOrderAndEndpointDirection() {
        val input = clutter() + tube
        val expected = candidates(input)
        assertEquals(expected, candidates(input.reversed().map { it.reversed() }))
        assertEquals(expected, candidates(tube + clutter().reversed()))

        // Equal-length lines straddle the retention boundary. Comparing with
        // the canonical first 512 protects the actual tie-selection decision,
        // rather than merely showing that two uniquely long edges survive.
        val ties = List(1100) { line(100.0, 20.0 + it * 0.8, 800.0) }
        val retained = candidates(ties.take(BarFitter.maximumSegments))
        assertTrue(retained.isNotEmpty())
        assertEquals(retained, candidates(ties))
        assertEquals(retained, candidates(ties.reversed().map { it.reversed() }))
    }

    @Test fun eligibleClutterAndDegenerateContoursCannotInventAnEdge() {
        assertTrue(candidates(clutter()).isEmpty())
        assertTrue(candidates(List(2400) { listOf(Point2D(100.0, 100.0), Point2D(100.0, 100.0)) }).isEmpty())
        // Wide image coverage alone is insufficient: sub-2px fragments are
        // already invalid under the original Swift and Kotlin geometry rules.
        val tiny = List(900) { line(20.0 + it, 600.0, 1.0) }
        assertTrue(candidates(tiny).isEmpty())
        assertEquals(candidates(tube), candidates(tiny + tube))
    }

    @Test fun quietFrameKeepsItsOriginalProposalOrdering() {
        val expected = listOf(
            BarLineCandidate(BarSegment(Point2D(100.0, 300.0), Point2D(900.0, 300.0)), 800.0),
            BarLineCandidate(BarSegment(Point2D(100.0, 308.0), Point2D(900.0, 308.0)), 800.0)
        )
        assertEquals(expected, candidates(tube.reversed()))
    }

    @Test fun pointComplexityGuardRemainsExplicit() {
        val error = assertFailsWith<BarFitException> {
            candidates(listOf(List(BarFitter.maximumPoints + 1) { Point2D(100.0, 100.0) }))
        }
        assertEquals("tooComplex", error.message)
    }

    @Test fun simplificationWorkBudgetStillRejectsPathologicalContours() {
        // RDP repeatedly splits this alternating chain, exceeding the existing
        // four-million distance checks before any grouping can begin.
        val zigzag = List(5000) {
            Point2D(20.0 + it * 0.1, if (it % 2 == 0) 100.0 else 200.0)
        }
        val error = assertFailsWith<BarFitException> { candidates(listOf(zigzag)) }
        assertEquals("tooComplex", error.message)
    }
}
