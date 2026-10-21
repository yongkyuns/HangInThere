package dev.hanginthere.core

import kotlin.test.*

class StaticSceneConsensusTests {
    private fun patches(vararg points: Pair<Double, Double>) = points.map {
        StaticSceneStability.PatchTranslation(it.first, it.second)
    }
    private fun monitor(points: List<StaticSceneStability.PatchTranslation>, scale: Double? = 0.0) =
        StaticSceneStability().also {
            assertTrue(it.calibrate(ImageSize(720.0, 1280.0)))
            it.observe(points, scale, 1.0)
        }
    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.isEmpty()) listOf(emptyList()) else items.indices.flatMap { i ->
            permutations(items.filterIndexed { j, _ -> i != j }).map { listOf(items[i]) + it }
        }

    @Test fun twoAgreeingCornersSurviveTwoUnrelatedOutliersInEveryOrder() {
        val points = patches(-54.0 to 57.0, -60.0 to 0.0, 1.0 to 0.0, 0.0 to 0.0)
        for (order in permutations(points)) {
            val scene = monitor(order)
            assertEquals(StaticSceneStability.State.stable, scene.state)
            assertEquals(2, scene.latestConsensusPatches)
            assertEquals(.5 / 720, scene.latestShiftFraction!!, 1e-12)
        }
    }

    @Test fun equallySupportedTranslationsAreUnavailableEvenWhenOneIsStationary() {
        val points = patches(-56.0 to 0.0, -53.0 to 0.0, 1.0 to 0.0, 0.0 to 0.0)
        for (order in permutations(points)) {
            val scene = monitor(order)
            assertEquals(StaticSceneStability.State.calibrating, scene.state)
            assertNull(scene.latestShiftFraction)
            assertFalse(scene.isReady(1.0))
            scene.observe(order, 0.0, 1.3)
            assertNotEquals(StaticSceneStability.State.moved, scene.state)
        }
    }

    @Test fun uniqueMovingConsensusIsNeverReplacedByASmallerStationaryCluster() {
        for (points in listOf(
                patches(12.0 to 0.0, 12.0 to 1.0, 13.0 to 0.0, 0.0 to 0.0),
                patches(12.0 to 0.0, 13.0 to 1.0, -60.0 to 50.0, 0.0 to 0.0))) {
            val scene = monitor(points)
            assertTrue(scene.latestShiftFraction!! >= StaticSceneStability.movementThresholdFraction)
            scene.observe(points, 0.0, 1.3)
            assertEquals(StaticSceneStability.State.moved, scene.state)
            assertEquals(StaticSceneStability.MovementKind.translation, scene.movementKind)
        }
    }

    @Test fun overlappingEqualSupportHypothesesAreAmbiguous() {
        // Either adjacent pair fits the existing 4.32px radius, all three do not.
        val scene = monitor(patches(0.0 to 0.0, 7.0 to 0.0, 14.0 to 0.0))
        assertEquals(StaticSceneStability.State.calibrating, scene.state)
        assertNull(scene.latestShiftFraction)
    }

    @Test fun missingScaleAndSinglePatchCannotBorrowNewConsensusReadiness() {
        val points = patches(-54.0 to 57.0, -60.0 to 0.0, 1.0 to 0.0, 0.0 to 0.0)
        assertEquals(StaticSceneStability.State.calibrating, monitor(points, null).state)
        assertEquals(StaticSceneStability.State.calibrating, monitor(patches(0.0 to 0.0)).state)
        val scene = monitor(points)
        scene.observe(patches(0.0 to 0.0), 0.0, 1.1)
        assertFalse(scene.isReady(1.1))
        assertNull(scene.latestShiftFraction)
    }

    @Test fun ambiguityBreaksMovementDwellAndCannotResetLatchedMotion() {
        val moving = patches(12.0 to 0.0, 13.0 to 0.0)
        val ambiguous = patches(12.0 to 0.0, 13.0 to 0.0, 0.0 to 0.0, 0.0 to 1.0)
        val scene = monitor(moving)
        scene.observe(ambiguous, 0.0, 1.1)
        scene.observe(moving, 0.0, 1.3)
        assertNotEquals(StaticSceneStability.State.moved, scene.state)
        scene.observe(moving, 0.0, 1.6)
        assertEquals(StaticSceneStability.State.moved, scene.state)
        scene.observe(ambiguous, 0.0, 1.7)
        assertEquals(StaticSceneStability.State.moved, scene.state)
    }

    @Test fun nativePatchBudgetIsBoundedAndNonfinitePatchesNeverVote() {
        val tooMany = List(5) { StaticSceneStability.PatchTranslation(0.0, 0.0) }
        assertEquals(StaticSceneStability.State.calibrating, monitor(tooMany).state)
        val scene = monitor(patches(0.0 to 0.0, 1.0 to 0.0, Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY))
        assertEquals(StaticSceneStability.State.stable, scene.state)
        assertEquals(2, scene.latestConsensusPatches)
    }

    @Test fun overlappingInlierSetsWithOneStationaryHypothesisDoNotPauseTracking() {
        // A near-boundary corner leaves two maximal three-patch sets. Their
        // medians differ 2px, both inside the original 4.32px agreement radius.
        for (order in permutations(patches(-6.0 to 0.0, -2.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0))) {
            val scene = monitor(order)
            assertEquals(StaticSceneStability.State.stable, scene.state)
            assertEquals(3, scene.latestConsensusPatches)
            assertEquals(2.0 / 720, scene.latestShiftFraction!!, 1e-12)
        }
    }

    @Test fun coherentMovingHypothesesKeepTheWorstDisplacementAndExistingDwell() {
        for (order in permutations(patches(14.0 to 0.0, 18.0 to 0.0, 20.0 to 0.0, 20.0 to 0.0))) {
            val scene = monitor(order)
            assertEquals(20.0 / 720, scene.latestShiftFraction!!, 1e-12)
            scene.observe(order, 0.0, 1.249999)
            assertNotEquals(StaticSceneStability.State.moved, scene.state)
            scene.observe(order, 0.0, 1.25)
            assertEquals(StaticSceneStability.State.moved, scene.state)
        }
    }

    @Test fun nearbyHypothesesOnOppositeSidesOfTheMotionLimitAreUnavailable() {
        for (order in permutations(patches(0.0 to 0.0, 4.0 to 0.0, 6.0 to 0.0, 6.0 to 0.0))) {
            val scene = monitor(order)
            assertEquals(StaticSceneStability.State.calibrating, scene.state)
            assertNull(scene.latestShiftFraction)
        }
    }

    @Test fun nearbyPairHypothesesCannotBorrowASecondSharedPatch() {
        // All three pair centers lie within 3.5px and below the movement limit,
        // but the pairs share only one original observation with each other.
        for (order in permutations(patches(-6.0 to 0.0, 0.0 to 0.0, 1.0 to 0.0))) {
            val scene = monitor(order)
            assertEquals(StaticSceneStability.State.calibrating, scene.state)
            assertNull(scene.latestShiftFraction)
        }
    }

    @Test fun coherentHypothesesCannotRelaxTheExactMovementBoundary() {
        val limit = StaticSceneStability.movementThresholdFraction * 720
        for (shift in listOf(limit - 1e-6, limit)) {
            val scene = monitor(patches(shift - 6 to 0.0, shift - 2 to 0.0, shift to 0.0, shift to 0.0))
            assertEquals(shift < limit, scene.isReady(1.0))
        }
    }

    @Test fun everyMaximumHypothesisMustAgreeNotJustWithAMiddleHypothesis() {
        for (order in permutations(patches(-6.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0, 6.0 to 0.0))) {
            val scene = monitor(order)
            assertEquals(StaticSceneStability.State.calibrating, scene.state)
            assertNull(scene.latestShiftFraction)
        }
    }

    @Test fun originalConsensusRadiusRemainsInclusiveAndLargeMediansStayFinite() {
        val radius = StaticSceneStability.consensusToleranceFraction * 720
        assertEquals(2, monitor(patches(-radius to 0.0, radius to 0.0)).latestConsensusPatches)
        assertNull(monitor(patches(-radius - .0001 to 0.0, radius + .0001 to 0.0)).latestShiftFraction)
        val huge = monitor(patches(Double.MAX_VALUE to 0.0, Double.MAX_VALUE to 0.0))
        assertTrue(huge.latestShiftFraction!!.isFinite())
        assertTrue(huge.latestShiftFraction!! > 0)
    }
}
