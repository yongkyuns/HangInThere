package dev.hanginthere.core

import kotlin.random.Random
import kotlin.test.*

class SourceTimeComparisonTests {
    private val gap = SourceTimeLimit(7, 20)
    private val zero = SourceTimeLimit(0, 1)

    @Test fun mixedTimescalesRetainExactEqualityAtLargeSourceOrigins() {
        val start = PresentationTime(10_000_000_000_000, 20)
        val end = 500_000_000_000_350L
        assertEquals(0, SourceTimeComparison.compareElapsed(PresentationTime(end, 1_000), start, gap))
        assertEquals(-1, SourceTimeComparison.compareElapsed(PresentationTime(end - 1, 1_000), start, gap))
        assertEquals(1, SourceTimeComparison.compareElapsed(PresentationTime(end + 1, 1_000), start, gap))
    }

    @Test fun independentlyReviewedOverlongIntervalCannotPassBecauseOfDoubleCancellation() {
        val start = PresentationTime(10_000_000_000_600_009, 1_000_000)
        val end = PresentationTime(10_000_010_000_950_010, 1_000_001)
        assertEquals(1, SourceTimeComparison.compareElapsed(end, start, gap))
        assertEquals(-1, SourceTimeComparison.compareElapsed(start, end, zero))
    }

    @Test fun gapsCloserThanADoubleUlpToTheLimitRemainDistinguishable() {
        val a = Int.MAX_VALUE.toLong(); val b = a - 1
        val denominator = a * b
        // First rational numerator strictly above 7/20, without overflowing 7*d.
        val numerator = denominator / 20 * 7 + denominator % 20 * 7 / 20 + 1
        val endRemainder = numerator % b // a == b+1, so a mod b == 1.
        var startRemainder = (endRemainder * a - numerator) / b
        val endWhole = if (startRemainder < 0) { startRemainder += a; 1L } else 0L
        val start = PresentationTime(startRemainder, a.toInt())
        val end = PresentationTime(endWhole * b + endRemainder, b.toInt())
        assertEquals(1, SourceTimeComparison.compareElapsed(end, start, gap))
    }

    @Test fun exactFractionComparisonAgreesWithIndependentBoundedIntegerCrossProducts() {
        val random = Random(2727)
        repeat(2_000) {
            val start = PresentationTime(random.nextLong(-100_000, 100_000), random.nextInt(1, 1_000))
            val end = PresentationTime(random.nextLong(-100_000, 100_000), random.nextInt(1, 1_000))
            for (limit in listOf(zero, gap, SourceTimeLimit(3, 5), SourceTimeLimit(1, 1))) {
                // These authored bounds keep this independent oracle in Long.
                val left = (end.value * start.timescale - start.value * end.timescale) * limit.denominator
                val right = end.timescale.toLong() * start.timescale * limit.numerator
                assertEquals(left.compareTo(right), SourceTimeComparison.compareElapsed(end, start, limit))
            }
        }
    }

    @Test fun extremaAndNegativeOriginsNeverOverflowOrReverseChronology() {
        assertEquals(1, SourceTimeComparison.compareElapsed(PresentationTime(Long.MAX_VALUE, 1), PresentationTime(Long.MIN_VALUE, 1), gap))
        assertEquals(-1, SourceTimeComparison.compareElapsed(PresentationTime(Long.MIN_VALUE, 1), PresentationTime(Long.MAX_VALUE, 1), zero))
        assertEquals(0, SourceTimeComparison.compareElapsed(PresentationTime(Long.MIN_VALUE, 1), PresentationTime(Long.MIN_VALUE, 1), zero))
        assertEquals(0, SourceTimeComparison.compareElapsed(PresentationTime(-13, 20), PresentationTime(-1, 1), gap))
        assertEquals(0, SourceTimeComparison.compareElapsed(PresentationTime(7, 20), PresentationTime(0, 1), gap))
    }

    @Test fun invalidClockAndUnsupportedBoundsCannotProduceAnElapsedMeasurement() {
        assertNull(SourceTimeComparison.compareElapsed(PresentationTime(1, 0), PresentationTime(0, 1), gap))
        assertNull(SourceTimeComparison.compareElapsed(PresentationTime(1, 1), PresentationTime(0, -1), gap))
        for ((numerator, denominator) in listOf(-1 to 1, 2 to 1, 1 to 0))
            assertFailsWith<IllegalArgumentException> { SourceTimeLimit(numerator, denominator) }
    }
}
