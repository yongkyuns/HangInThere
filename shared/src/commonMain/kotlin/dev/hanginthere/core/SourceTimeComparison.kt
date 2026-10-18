package dev.hanginthere.core

/** Exact comparison with a source-duration bound in [0, 1] seconds. Native
 * decoders can legitimately change rational timescales between source frames.
 * Neither absolute Double seconds nor overflowing cross-products are needed. */
internal data class SourceTimeLimit(val numerator: Int, val denominator: Int) {
    init { require(denominator > 0 && numerator in 0..denominator) }
    val seconds: Double get() = numerator.toDouble() / denominator
}

internal object SourceTimeComparison {
    private data class Parts(val whole: Long, val remainder: Long, val scale: Long)

    /** Null means an invalid clock, not an elapsed interval. */
    fun compareElapsed(end: PresentationTime, start: PresentationTime, limit: SourceTimeLimit): Int? {
        if (end.timescale <= 0 || start.timescale <= 0) return null
        val a = parts(end); val b = parts(start)
        if (a.whole < b.whole) return -1
        val whole = when {
            a.whole == b.whole -> 0
            // a.whole cannot be MIN_VALUE here, so subtracting one is safe.
            a.whole - 1 == b.whole -> 1
            else -> return 1 // At least two whole seconds: interval > 1 second.
        }
        // Positive Int timescales bound each product below 2^62. Even twice
        // Int.MAX_VALUE squared fits Long, including the one-second carry.
        val denominator = a.scale * b.scale
        val numerator = a.remainder * b.scale - b.remainder * a.scale + if (whole == 1) denominator else 0
        if (numerator < 0) return -1
        return compareFractions(numerator, denominator, limit.numerator.toLong(), limit.denominator.toLong())
    }

    private fun parts(time: PresentationTime): Parts {
        val scale = time.timescale.toLong()
        val whole = time.value / scale
        val remainder = time.value % scale
        return if (remainder < 0) Parts(whole - 1, remainder + scale, scale) else Parts(whole, remainder, scale)
    }

    /** Compare nonnegative fractions by continued division. Cross-multiplying
     * the duration by the limit denominator could overflow even for tiny gaps. */
    private fun compareFractions(firstNumerator: Long, firstDenominator: Long,
                                 secondNumerator: Long, secondDenominator: Long): Int {
        var a = firstNumerator; var b = firstDenominator
        var c = secondNumerator; var d = secondDenominator
        var direction = 1
        while (true) {
            val whole = (a / b).compareTo(c / d)
            if (whole != 0) return whole * direction
            val first = a % b; val second = c % d
            if (first == 0L || second == 0L) return when {
                first == second -> 0
                first == 0L -> -direction
                else -> direction
            }
            a = b; b = first; c = d; d = second
            direction = -direction
        }
    }
}
