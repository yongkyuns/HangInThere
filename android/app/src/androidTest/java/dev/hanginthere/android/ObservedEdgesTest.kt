package dev.hanginthere.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hanginthere.android.vision.ObservedEdges
import dev.hanginthere.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Synthetic pixels exercise native extraction, not pose-model or human accuracy. */
@RunWith(AndroidJUnit4::class)
class ObservedEdgesTest {
    @Test fun noisyThinAndWideRailsEarnBilateralDwellWithoutChangingInputPixels() {
        val detector = ObservedEdges()
        for (width in listOf(4, 12)) for (side in ArmMeasurement.Side.entries) {
            val policy = AutomaticDipRails()
            var reference: ConfirmedBar? = null
            for ((seed, milliseconds) in listOf(0L, 300L, 600L, 1000L).withIndex()) {
                val pose = pose(milliseconds)
                val grip = requireNotNull(AutomaticDipRails.grip(pose))
                val bitmap = noisyScene(seed, width, 600 until 710)
                try {
                    val originalGeneration = bitmap.generationId
                    val candidates = grip.regions.map { detector.detect(bitmap, it) }
                    reference = policy.observe(pose, side, candidates)
                    assertEquals("Contour extraction must preserve pose/display pixels", originalGeneration, bitmap.generationId)
                    if (milliseconds < 1000) assertNull("Noise must not bypass source-clock dwell", reference)
                } finally { bitmap.recycle() }
            }
            assertNotNull("Both observed rail silhouettes must survive changing pixel noise: width=$width side=$side", reference)
            val edge = requireNotNull(reference).referenceEdge
            val hand = requireNotNull(AutomaticDipRails.grip(pose(1000))).hands[if (side == ArmMeasurement.Side.left) 0 else 1]
            assertTrue(edge.isValid && edge.distance(hand) <= SIZE * .12)
            assertTrue(listOf(edge.a, edge.b).all { it.y >= 600 - 3 && it.y <= 710 + 3 })
            assertTrue(listOf(edge.a, edge.b).all { abs(it.x - hand.x) <= width + 3 })
        }
    }

    @Test fun noisyTextureAndForearmSilhouettesCannotAcquireDipRails() {
        val detector = ObservedEdges()
        // The forearm-like shapes cross the wrist height. Smoothing must not
        // turn a cropped body fragment into independently witnessed apparatus.
        for (verticalSupport in listOf(IntRange.EMPTY, 480 until 650)) for (side in ArmMeasurement.Side.entries) {
            val policy = AutomaticDipRails()
            for ((seed, milliseconds) in listOf(0L, 300L, 600L, 1000L).withIndex()) {
                val pose = pose(milliseconds)
                val grip = requireNotNull(AutomaticDipRails.grip(pose))
                val bitmap = noisyScene(seed, 12, verticalSupport)
                try {
                    val candidates = grip.regions.map { detector.detect(bitmap, it) }
                    assertNull(policy.observe(pose, side, candidates))
                    assertNotEquals(AutomaticPullUpBar.State.locked, policy.state)
                } finally { bitmap.recycle() }
            }
        }
    }

    private fun pose(milliseconds: Long): PoseResult {
        val joints = listOf(PoseJoint.leftShoulder, PoseJoint.leftElbow, PoseJoint.leftWrist,
            PoseJoint.rightShoulder, PoseJoint.rightElbow, PoseJoint.rightWrist)
        val points = listOf(Point2D(350.0, 300.0), Point2D(300.0, 425.0), Point2D(250.0, 550.0),
            Point2D(650.0, 300.0), Point2D(700.0, 425.0), Point2D(750.0, 550.0))
        return PoseResult(PresentationTime(milliseconds, 1000), ImageSize(SIZE.toDouble(), SIZE.toDouble()),
            listOf(PoseObservation(joints.zip(points).map { Landmark(it.first, it.second, 1.0) })), "synthetic", 1)
    }

    private fun noisyScene(seed: Int, railWidth: Int, verticalSupport: IntRange): Bitmap {
        val pixels = IntArray(SIZE * SIZE) { index ->
            val x = index % SIZE; val y = index / SIZE
            val hash = (x * 73856093) xor (y * 19349663) xor (seed * 83492791)
            // Reproducible +/-25 grayscale noise changes independently between
            // observations while leaving both rail silhouettes observable.
            val noise = ((hash xor (hash ushr 13)) and Int.MAX_VALUE) % 51 - 25
            val rail = y in verticalSupport && (x in 250 until 250 + railWidth || x in 750 until 750 + railWidth)
            val value = ((if (rail) 20 else 200) + noise).coerceIn(0, 255)
            Color.rgb(value, value, value)
        }
        return Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    private companion object { const val SIZE = 1000 }
}
