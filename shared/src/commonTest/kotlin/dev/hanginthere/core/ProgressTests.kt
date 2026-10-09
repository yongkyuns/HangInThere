package dev.hanginthere.core

import kotlin.test.*

class ProgressTests {
    private fun bilateralArmed(): ExerciseCounter = ExerciseCounter().also {
        CounterFixtures.feedBoth(it, 0.0, 170.0, 170.0); CounterFixtures.feedBoth(it, 0.15, 170.0, 170.0)
    }
    private fun dipArmed(): ExerciseCounter = ExerciseCounter(ExerciseCounter.Exercise.dip).also(CounterFixtures::arm)
    private fun dipBottom(): ExerciseCounter = dipArmed().also { CounterFixtures.feed(it, 0.3 to 80.0, 0.45 to 80.0) }
    private fun dipCounted(): ExerciseCounter = dipBottom().also { CounterFixtures.feed(it, 0.6 to 170.0, 0.75 to 170.0) }

    @Test fun hangAcquisitionShowsResetWithoutRepCompletion() {
        val c = ExerciseCounter()
        assertEquals(PullUpProgress.Stage.startingHang, c.pullUpProgress?.stage)
        CounterFixtures.feedBoth(c, 0.0, 170.0, 170.0); CounterFixtures.feedBoth(c, 0.06, 170.0, 170.0)
        val holding = assertNotNull(c.pullUpProgress)
        assertEquals(0.0, holding.fraction); assertTrue(holding.resetFraction > 0.9)
        CounterFixtures.feedBoth(c, 0.15, 170.0, 170.0)
        assertEquals(PullUpProgress.Stage.ready, c.pullUpProgress?.stage); assertEquals(1, c.pullUpProgress?.repNumber)
    }
    @Test fun pullUpProgressReservesCompletionForEndpointDwell() {
        val c = bilateralArmed()
        CounterFixtures.feedBoth(c, 0.3, 130.0, 130.0)
        val earlier = assertNotNull(c.pullUpProgress)
        CounterFixtures.feedBoth(c, 0.45, 110.0, 110.0)
        assertTrue(assertNotNull(c.pullUpProgress).fraction > earlier.fraction)
        CounterFixtures.feedBoth(c, 0.55, 80.0, 80.0)
        assertEquals(PullUpProgress.Stage.holdingTop, c.pullUpProgress?.stage); assertEquals(0.9, c.pullUpProgress?.fraction)
        CounterFixtures.feedBoth(c, 0.61, 80.0, 80.0)
        assertTrue(assertNotNull(c.pullUpProgress).fraction in 0.900001..0.999999)
        assertEquals(0, c.observedMovements)
        CounterFixtures.feedBoth(c, 0.7, 80.0, 80.0)
        assertEquals(1, c.observedMovements); assertEquals(PullUpProgress.Stage.counted, c.pullUpProgress?.stage)
        assertEquals(1.0, c.pullUpProgress?.fraction)
    }
    @Test fun pullUpBendWithoutTravelDoesNotFillMeter() {
        val c = bilateralArmed()
        for (time in listOf(0.3, 0.45)) {
            val base = CounterFixtures.bilateral(time, 80.0, 80.0)
            c.consume(base.copy(people = base.people.map { person -> PoseObservation(person.landmarks.map {
                it.copy(position = it.position.copy(y = it.position.y + 100))
            }) }), CounterFixtures.edge)
        }
        assertEquals(0.0, c.pullUpProgress?.fraction); assertEquals(PullUpProgress.Cue.pullHigher, c.pullUpProgress?.cue)
    }
    @Test fun laggingPullUpArmConstrainsProgress() {
        val c = bilateralArmed()
        CounterFixtures.feedBoth(c, 0.3, 80.0, 130.0)
        val progress = assertNotNull(c.pullUpProgress)
        assertTrue(progress.fraction < 0.5); assertEquals(2, progress.trackedArmCount)
        CounterFixtures.feedBoth(c, 0.45, 80.0, 170.0)
        assertEquals(0.0, c.pullUpProgress?.fraction); assertEquals(0, c.observedMovements)
    }
    @Test fun poseLossClearsEarnedGreenWithoutErasingCount() {
        val c = bilateralArmed()
        CounterFixtures.feedBoth(c, 0.3, 80.0, null); CounterFixtures.feedBoth(c, 0.45, 80.0, null)
        assertEquals(PullUpProgress.Stage.counted, c.pullUpProgress?.stage); assertEquals(1, c.pullUpProgress?.trackedArmCount)
        c.consume(CounterFixtures.noPerson(0.6), CounterFixtures.edge)
        assertEquals(PullUpProgress.Stage.paused, c.pullUpProgress?.stage); assertEquals(0.0, c.pullUpProgress?.fraction)
        assertEquals(0, c.pullUpProgress?.trackedArmCount); assertEquals(1, c.observedMovements)
    }
    @Test fun alternatingArmsResetHoldProgress() {
        val c = bilateralArmed()
        CounterFixtures.feedBoth(c, 0.3, 80.0, null); CounterFixtures.feedBoth(c, 0.36, null, 80.0)
        assertEquals(0.9, c.pullUpProgress?.fraction)
        CounterFixtures.feedBoth(c, 0.45, null, 80.0); assertEquals(0, c.observedMovements)
        CounterFixtures.feedBoth(c, 0.5, null, 80.0); assertEquals(PullUpProgress.Stage.counted, c.pullUpProgress?.stage)
    }
    @Test fun pullUpResetMeterCompletesBeforeNextRep() {
        val c = bilateralArmed()
        CounterFixtures.feedBoth(c, 0.3, 80.0, 80.0); CounterFixtures.feedBoth(c, 0.45, 80.0, 80.0)
        val top = assertNotNull(c.pullUpProgress)
        CounterFixtures.feedBoth(c, 0.6, 130.0, 130.0)
        val lower = assertNotNull(c.pullUpProgress)
        assertEquals(1.0, lower.fraction); assertTrue(lower.resetFraction > top.resetFraction)
        CounterFixtures.feedBoth(c, 0.75, 170.0, 170.0); assertEquals(1, c.pullUpProgress?.repNumber)
        CounterFixtures.feedBoth(c, 0.9, 170.0, 170.0)
        assertEquals(PullUpProgress.Stage.ready, c.pullUpProgress?.stage); assertEquals(2, c.pullUpProgress?.repNumber)
        assertEquals(0.0, c.pullUpProgress?.fraction)
    }
    @Test fun partialPullUpResetsWithoutIncrementingRepNumber() {
        val c = bilateralArmed()
        CounterFixtures.feedBoth(c, 0.3, 130.0, 130.0); CounterFixtures.feedBoth(c, 0.45, 170.0, 170.0)
        assertEquals(PullUpProgress.Stage.startingHang, c.pullUpProgress?.stage)
        CounterFixtures.feedBoth(c, 0.6, 170.0, 170.0)
        assertEquals(1, c.partialAttempts); assertEquals(1, c.pullUpProgress?.repNumber); assertEquals(0.0, c.pullUpProgress?.fraction)
    }
    @Test fun dipAcquisitionCannotImplyCompletion() {
        val c = ExerciseCounter(ExerciseCounter.Exercise.dip)
        assertEquals(DipProgress.Stage.startingSupport, c.dipProgress?.stage)
        CounterFixtures.feed(c, 0.0 to 170.0, 0.06 to 170.0)
        assertEquals(0.0, c.dipProgress?.fraction)
        CounterFixtures.feed(c, 0.15 to 170.0)
        assertEquals(DipProgress.Stage.ready, c.dipProgress?.stage); assertEquals(1, c.dipProgress?.repNumber)
    }
    @Test fun dipDescentAndRecoveryReserveCompletionForCount() {
        val c = dipArmed()
        CounterFixtures.feed(c, 0.3 to 110.0)
        assertEquals(DipProgress.Stage.lowering, c.dipProgress?.stage)
        assertTrue(assertNotNull(c.dipProgress).fraction in 0.000001..0.449999)
        CounterFixtures.feed(c, 0.45 to 80.0)
        assertEquals(DipProgress.Stage.holdingBottom, c.dipProgress?.stage); assertEquals(0.45, c.dipProgress?.fraction)
        CounterFixtures.feed(c, 0.51 to 80.0)
        assertTrue(assertNotNull(c.dipProgress).fraction in 0.450001..0.499999)
        CounterFixtures.feed(c, 0.6 to 80.0)
        assertEquals(DipProgress.Stage.rising, c.dipProgress?.stage); assertEquals(0.5, c.dipProgress?.fraction)
        CounterFixtures.feed(c, 0.75 to 110.0)
        assertTrue(assertNotNull(c.dipProgress).fraction in 0.500001..0.899999)
        CounterFixtures.feed(c, 0.9 to 170.0)
        assertEquals(DipProgress.Stage.holdingTop, c.dipProgress?.stage); assertEquals(0.9, c.dipProgress?.fraction)
        CounterFixtures.feed(c, 0.96 to 170.0)
        assertTrue(assertNotNull(c.dipProgress).fraction in 0.900001..0.999999); assertEquals(0, c.observedMovements)
        CounterFixtures.feed(c, 1.05 to 170.0)
        assertEquals(DipProgress.Stage.counted, c.dipProgress?.stage); assertEquals(1.0, c.dipProgress?.fraction)
        assertEquals(1, c.observedMovements)
    }
    @Test fun nextDescentClearsDipConfirmationAndBeginsNextRep() {
        val c = dipCounted(); CounterFixtures.feed(c, 0.85 to 170.0)
        assertEquals(DipProgress.Stage.counted, c.dipProgress?.stage)
        CounterFixtures.feed(c, 1.0 to 110.0)
        assertEquals(DipProgress.Stage.lowering, c.dipProgress?.stage); assertEquals(2, c.dipProgress?.repNumber)
        assertTrue(assertNotNull(c.dipProgress).fraction < 0.5)
    }
    @Test fun dipConfirmationExpiresOnlyOnSourceFrames() {
        val c = dipCounted(); val earned = c.dipProgress
        repeat(20) { assertEquals(earned, c.dipProgress) }
        CounterFixtures.feed(c, 0.9 to 170.0, 1.1 to 170.0, 1.3 to 170.0, 1.5 to 170.0)
        assertEquals(DipProgress.Stage.ready, c.dipProgress?.stage); assertEquals(2, c.dipProgress?.repNumber)
        assertEquals(0.0, c.dipProgress?.fraction)
    }
    @Test fun shallowDipReturnEarnsNoSecondHalf() {
        val c = dipArmed(); CounterFixtures.feed(c, 0.3 to 110.0, 0.45 to 170.0)
        assertEquals(DipProgress.Stage.startingSupport, c.dipProgress?.stage); assertEquals(0.0, c.dipProgress?.fraction)
        CounterFixtures.feed(c, 0.6 to 170.0)
        assertEquals(1, c.partialAttempts); assertEquals(DipProgress.Stage.ready, c.dipProgress?.stage)
        assertEquals(1, c.dipProgress?.repNumber); assertEquals(0, c.observedMovements)
    }
    @Test fun oppositeDipArmConstrainsBothHalves() {
        val c = ExerciseCounter(ExerciseCounter.Exercise.dip)
        CounterFixtures.feedBoth(c, 0.0, 170.0, 170.0); CounterFixtures.feedBoth(c, 0.15, 170.0, 170.0)
        CounterFixtures.feedBoth(c, 0.3, 80.0, 170.0); CounterFixtures.feedBoth(c, 0.45, 80.0, 170.0)
        assertEquals(0.0, c.dipProgress?.fraction); assertEquals(2, c.dipProgress?.trackedArmCount)
        CounterFixtures.feedBoth(c, 0.6, 80.0, 80.0); CounterFixtures.feedBoth(c, 0.75, 80.0, 80.0)
        CounterFixtures.feedBoth(c, 0.9, 170.0, 80.0); CounterFixtures.feedBoth(c, 1.05, 170.0, 80.0)
        assertEquals(0.5, c.dipProgress?.fraction); assertEquals(DipProgress.Cue.extendArms, c.dipProgress?.cue)
        CounterFixtures.feedBoth(c, 1.2, 170.0, 170.0); CounterFixtures.feedBoth(c, 1.35, 170.0, 170.0)
        assertEquals(DipProgress.Stage.counted, c.dipProgress?.stage)
    }
    @Test fun missingAnglesCannotShowUnobservedTopCompletion() {
        val c = dipBottom()
        c.consume(CounterFixtures.lowJoint(0.6, 170.0, c.exercise, c.side, 1), CounterFixtures.edge)
        assertEquals(DipProgress.Stage.paused, c.dipProgress?.stage); assertEquals(0.0, c.dipProgress?.fraction)
        assertEquals(0, c.observedMovements)
    }
    @Test fun dipMissingEvidenceClearsDisplayWithoutErasingCount() {
        for (c in listOf(dipBottom(), dipCounted())) {
            val count = c.observedMovements
            c.consume(CounterFixtures.noPerson(0.85), CounterFixtures.edge)
            assertEquals(DipProgress.Stage.paused, c.dipProgress?.stage); assertEquals(0.0, c.dipProgress?.fraction)
            assertEquals(0, c.dipProgress?.trackedArmCount); assertEquals(count, c.observedMovements)
        }
    }
    @Test fun referenceTimestampAndGripFailuresClearProgress() {
        val noRail = dipBottom(); noRail.consume(CounterFixtures.pose(0.6, exercise = noRail.exercise), null)
        assertEquals(DipProgress.Cue.setRail, noRail.dipProgress?.cue); assertEquals(0.0, noRail.dipProgress?.fraction)
        val gap = dipBottom(); CounterFixtures.feed(gap, 1.0 to 80.0)
        assertEquals(DipProgress.Cue.reacquireSupport, gap.dipProgress?.cue)
        val repeated = dipBottom(); CounterFixtures.feed(repeated, 0.45 to 80.0)
        assertEquals(DipProgress.Cue.reacquireSupport, repeated.dipProgress?.cue)
        val moved = dipBottom(); moved.consume(CounterFixtures.pose(0.6, 170.0, moved.exercise, wristX = 545.0), CounterFixtures.edge)
        assertEquals(DipProgress.Cue.reestablishGrip, moved.dipProgress?.cue)
        assertEquals(0.0, moved.dipProgress?.fraction)
    }
    @Test fun finishAndResetCannotRetainCompletion() {
        val c = dipCounted(); c.finish()
        assertEquals(DipProgress.Stage.finished, c.dipProgress?.stage); assertEquals(0.0, c.dipProgress?.fraction)
        assertEquals(1, c.dipProgress?.repNumber); c.reset()
        assertEquals(DipProgress.Stage.startingSupport, c.dipProgress?.stage); assertEquals(1, c.dipProgress?.repNumber)
        assertNull(c.pullUpProgress); assertNull(ExerciseCounter().dipProgress)
        val p = bilateralArmed(); p.finish()
        assertEquals(PullUpProgress.Stage.finished, p.pullUpProgress?.stage); assertEquals(0.0, p.pullUpProgress?.fraction)
    }
    @Test fun normalizationRejectsNonFiniteValues() {
        assertEquals(0.0, normalizedProgress(Double.NaN)); assertEquals(0.0, normalizedProgress(Double.POSITIVE_INFINITY))
        assertEquals(0.0, normalizedProgress(-1.0)); assertEquals(1.0, normalizedProgress(2.0))
    }
}
