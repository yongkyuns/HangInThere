package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Independent JVM-only oracle; never linked into the shipping native framework. */
class SourceTimeBigIntegerTests {
    private fun oracle(end: PresentationTime, start: PresentationTime, limit: SourceTimeLimit): Int {
        fun big(value: Long) = BigInteger.valueOf(value)
        val numerator = big(end.value) * big(start.timescale.toLong()) - big(start.value) * big(end.timescale.toLong())
        val denominator = big(end.timescale.toLong()) * big(start.timescale.toLong())
        return (numerator * big(limit.denominator.toLong())).compareTo(denominator * big(limit.numerator.toLong()))
    }
    @Test fun fullSignedRangeAndMixedTimebasesAgreeWithArbitraryPrecisionCrossProducts() {
        val random = Random(6018)
        val scales = intArrayOf(1, 3, 5, 20, 60_000, 90_000, Int.MAX_VALUE - 1, Int.MAX_VALUE)
        val limits = listOf(SourceTimeLimit(0, 1), SourceTimeLimit(7, 20), SourceTimeLimit(3, 5), SourceTimeLimit(1, 1))
        repeat(2_000) {
            val start = PresentationTime(random.nextLong(), scales[random.nextInt(scales.size)])
            val end = PresentationTime(random.nextLong(), scales[random.nextInt(scales.size)])
            for (limit in limits) assertEquals(oracle(end, start, limit), SourceTimeComparison.compareElapsed(end, start, limit))
        }
    }
    @Test fun nearbyFractionsAtLargePositiveAndNegativeOriginsAgreeWithArbitraryPrecision() {
        val random = Random(3519)
        val scales = intArrayOf(1, 3, 5, 20, 60_000, 90_000, Int.MAX_VALUE - 1, Int.MAX_VALUE)
        repeat(2_000) {
            val base = random.nextLong(-1_000_000_000, 1_000_000_000)
            val a = scales[random.nextInt(scales.size)]; val b = scales[random.nextInt(scales.size)]
            val start = PresentationTime(base * a + random.nextLong(a.toLong()), a)
            val end = PresentationTime((base + random.nextInt(-1, 2)) * b + random.nextLong(b.toLong()), b)
            val denominator = random.nextInt(1, 1_000_000)
            val limits = listOf(SourceTimeLimit(0, 1), SourceTimeLimit(7, 20), SourceTimeLimit(3, 5),
                SourceTimeLimit(1, 1), SourceTimeLimit(random.nextInt(denominator + 1), denominator))
            for (limit in limits) assertEquals(oracle(end, start, limit), SourceTimeComparison.compareElapsed(end, start, limit))
        }
    }
}
