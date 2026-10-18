package dev.hanginthere.core

import kotlin.test.*

class WorkoutSceneBridgeTest {
    private fun ready() = WorkoutSceneBridge().also {
        assertTrue(it.calibrate(960.0, 540.0))
        assertEquals("stable", it.observe(0.0, doubleArrayOf(0.0, 0.0, 0.0, 0.0), 0.0))
    }

    @Test fun readinessUsesObservationSourceAgeWithoutRetimestamping() {
        val bridge = ready()
        assertTrue(bridge.isReady(0.0))
        assertTrue(bridge.isReady(StaticSceneStability.maximumObservationGapSeconds))
        assertFalse(bridge.isReady(StaticSceneStability.maximumObservationGapSeconds + .001))
        assertFalse(bridge.isReady(Double.NaN))
        assertFalse(bridge.isReady(-.1))
    }

    @Test fun failedOrMalformedRegistrationRevokesOldAvailability() {
        for (transport in listOf(doubleArrayOf(), doubleArrayOf(0.0), DoubleArray(10),
            doubleArrayOf(Double.NaN, 0.0, Double.NaN, 0.0))) {
            val bridge = ready()
            assertEquals("calibrating", bridge.observe(.3, transport, 0.0))
            assertFalse(bridge.isReady(.3))
        }
        for (scale in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.1)) {
            val bridge = ready()
            bridge.observe(.3, doubleArrayOf(0.0, 0.0, 0.0, 0.0), scale)
            assertFalse(bridge.isReady(.3))
        }
    }

    @Test fun confirmedMovementLatchesButPreservesExistingConfirmationDwell() {
        for (scaleMotion in listOf(false, true)) {
            val bridge = ready()
            val patches = if (scaleMotion) DoubleArray(4) else doubleArrayOf(20.0, 0.0, 20.0, 0.0)
            val scale = if (scaleMotion) .1 else 0.0
            assertEquals("stable", bridge.observe(.3, patches, scale))
            assertTrue(bridge.isReady(.3)) // Existing .25s movement-confirmation policy.
            assertEquals("moved", bridge.observe(.6, patches, scale))
            assertEquals(if (scaleMotion) "scale" else "translation", bridge.movementKind)
            assertFalse(bridge.isReady(.6))
            bridge.observe(.9, DoubleArray(4), 0.0)
            assertEquals("moved", bridge.state)
            assertFalse(bridge.isReady(.9))
        }
    }

    @Test fun newCalibrationClearsClockAndMovementEvidence() {
        val bridge = ready()
        bridge.observe(.3, doubleArrayOf(20.0, 0.0, 20.0, 0.0), 0.0)
        bridge.observe(.6, doubleArrayOf(20.0, 0.0, 20.0, 0.0), 0.0)
        assertTrue(bridge.calibrate(960.0, 540.0))
        assertEquals("calibrating", bridge.state)
        assertNull(bridge.movementKind)
        assertFalse(bridge.isReady(0.0))
        assertEquals("stable", bridge.observe(0.0, DoubleArray(4), 0.0))
        bridge.reset()
        assertEquals("uncalibrated", bridge.state)
        assertFalse(bridge.isReady(0.0))
        assertFalse(bridge.calibrate(Double.NaN, 540.0))
    }

    @Test fun delegatesTheExistingPolicyWithoutChangingItsDecisions() {
        val bridge = WorkoutSceneBridge()
        val direct = StaticSceneStability()
        bridge.calibrate(960.0, 540.0); direct.calibrate(ImageSize(960.0, 540.0))
        val observations = listOf(
            Triple(0.0, doubleArrayOf(0.0, 0.0, 0.0, 0.0), 0.0),
            Triple(.3, doubleArrayOf(2.0, -1.0, 2.5, -1.0, 200.0, 200.0), .001),
            Triple(.6, doubleArrayOf(), Double.NaN),
            Triple(.9, doubleArrayOf(0.0, 0.0, 0.0, 0.0), 0.0),
            Triple(1.2, doubleArrayOf(15.0, 0.0, 15.0, 0.0), 0.0),
            Triple(1.5, doubleArrayOf(15.0, 0.0, 15.0, 0.0), 0.0))
        for ((seconds, raw, scale) in observations) {
            val patches = raw.asList().chunked(2).map { StaticSceneStability.PatchTranslation(it[0], it[1]) }
            assertEquals(direct.observe(patches, scale.takeIf { it.isFinite() }, seconds).name,
                bridge.observe(seconds, raw, scale))
            assertEquals(direct.isReady(seconds), bridge.isReady(seconds))
        }
    }
}
