package dev.hanginthere.core

import kotlin.test.*

class WorkoutPhaseDirectionTests {
    private fun frame(time: Double, angle: Double?, right: Double? = angle, usable: Boolean = true, released: Boolean = false) =
        MotionSample(time, angle, right, null, usable, released)

    @Test fun noisyEndpointHoldDoesNotAlternateBetweenOutboundAndReturn() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val analysis = WorkoutPhaseAnalysis("noise", exercise)
            analysis.observe(frame(0.0, 170.0), ExerciseCounter.Phase.ready)
            for (i in 1..150) analysis.observe(frame(i / 30.0, 80.0 + if (i % 2 == 0) 1 else -1), ExerciseCounter.Phase.returning)
            val activity = analysis.activity
            val expected = if (exercise == ExerciseCounter.Exercise.pullUp) WorkoutMotionPhase.peakHold else WorkoutMotionPhase.bottomHold
            assertEquals(expected, activity.phases.last().phase)
            assertTrue(activity.phases.size < 8, "Small stationary jitter created a fragmented phase timeline")
            assertEquals(3, activity.metricVersion)
            assertEquals(0.0, activity.longestHangSeconds)
            assertEquals(0.0, activity.longestSupportSeconds)
        }
    }

    @Test fun genuineOutboundHoldAndReturnRemainVisible() {
        val direction = WorkoutPhaseDirection()
        var previous: MotionSample? = null
        fun observe(time: Double, angle: Double): WorkoutMotionDirection? {
            val next = frame(time, angle)
            return direction.observe(next, previous).also { previous = next }
        }
        observe(0.0, 160.0)
        assertNull(observe(.1, 155.0)) // One fitted observation cannot earn a dwell.
        for (i in 2..14) assertEquals(WorkoutMotionDirection.decreasing, observe(i * .1, 160.0 - i * 5))
        for (i in 15..19) observe(i * .1, 90.0)
        assertEquals(WorkoutMotionDirection.stationary, observe(2.0, 90.0))
        for (i in 1..4) observe(2.0 + i * .1, 90.0 + i * 5)
        assertEquals(WorkoutMotionDirection.increasing, observe(2.5, 115.0))
    }

    @Test fun oneFrameOppositeSpikeCannotFlipAnEstablishedDirection() {
        val direction = WorkoutPhaseDirection()
        var previous: MotionSample? = null
        val decisions = (0..70).map { i ->
            val next = frame(i * .02, 160.0 - i + if (i == 30) 20 else 0)
            direction.observe(next, previous).also { previous = next }
        }
        assertEquals(WorkoutMotionDirection.decreasing, decisions.last())
        assertFalse(decisions.contains(WorkoutMotionDirection.increasing))
    }

    @Test fun slowGenuineDirectionCanInitializeBeyondTheFittedWindow() {
        for (sign in listOf(-1, 1)) {
            val direction = WorkoutPhaseDirection()
            var previous: MotionSample? = null
            var decision: WorkoutMotionDirection? = null
            for (i in 0..60) {
                val next = frame(i / 30.0, 100.0 + sign * 10 * i / 30.0)
                decision = direction.observe(next, previous); previous = next
            }
            assertEquals(if (sign < 0) WorkoutMotionDirection.decreasing else WorkoutMotionDirection.increasing, decision)
            assertEquals(sign * 10.0, assertNotNull(direction.rateDegreesPerSecond), 1e-7)
        }
    }

    @Test fun oneLowRateFitCannotBackdateAStationaryDwellAtLowFrameRate() {
        val direction = WorkoutPhaseDirection()
        var previous: MotionSample? = null
        for (i in 0..5) {
            val next = frame(i * .1, 160.0 - i * 5)
            direction.observe(next, previous); previous = next
        }
        // This one-frame spike cancels the window's regression slope even
        // though its current observed interval is moving quickly upwards.
        val spike = frame(.6, 143.2923076923)
        assertEquals(WorkoutMotionDirection.decreasing, direction.observe(spike, previous))
        assertTrue(kotlin.math.abs(assertNotNull(direction.rateDegreesPerSecond)) < 5)
        assertEquals(WorkoutMotionDirection.decreasing, direction.observe(frame(.7, 125.0), spike))
    }

    @Test fun oneOppositeFitCannotBackdateAReversalDwellAtLowFrameRate() {
        val direction = WorkoutPhaseDirection()
        var previous: MotionSample? = null
        for (i in 0..5) {
            val next = frame(i * .1, 160.0 - i * 5)
            direction.observe(next, previous); previous = next
        }
        val spike = frame(.6, 155.0)
        assertEquals(WorkoutMotionDirection.decreasing, direction.observe(spike, previous))
        assertEquals(WorkoutMotionDirection.decreasing, direction.observe(frame(.7, 125.0), spike))
    }

    @Test fun linearRateDoesNotDependOnFrameDensityOrLargeSourceClock() {
        for (offset in listOf(0.0, 1_000_000_000.0)) {
            for (times in listOf(listOf(0.0, .1, .2, .3), listOf(0.0, .01, .08, .13, .17, .21, .25, .3))) {
                val direction = WorkoutPhaseDirection()
                var previous: MotionSample? = null
                for (relative in times) {
                    val time = offset + relative
                    val next = frame(time, 160.0 - 50 * (time - offset))
                    direction.observe(next, previous); previous = next
                }
                assertEquals(-50.0, assertNotNull(direction.rateDegreesPerSecond), 1e-7)
            }
        }
    }

    @Test fun armVisibilityChangesCannotMixDifferentAngleTrajectories() {
        val direction = WorkoutPhaseDirection()
        var previous = frame(0.0, 160.0, right = null)
        assertNull(direction.observe(previous, null))
        val descending = frame(.1, 140.0, right = null)
        assertNull(direction.observe(descending, previous))
        val confirmed = frame(.2, 120.0, right = null)
        assertEquals(WorkoutMotionDirection.decreasing, direction.observe(confirmed, descending))
        previous = confirmed
        val otherArm = frame(.3, null, right = 80.0)
        assertNull(direction.observe(otherArm, previous))
        assertTrue(direction.restarted)
        assertNull(direction.rateDegreesPerSecond)
        assertEquals(1, direction.retainedSamples)
        val both = frame(.4, 130.0, right = 80.0)
        assertNull(direction.observe(both, otherArm))
        assertTrue(direction.restarted)
    }

    @Test fun outageReleaseAndRejectedTimeEraseDirectionEvidence() {
        for (fault in listOf(frame(.2, null, usable = false), frame(.2, 80.0, released = true),
            frame(Double.NaN, 80.0), frame(.1, 80.0), frame(1.0, 80.0))) {
            val direction = WorkoutPhaseDirection()
            val a = frame(0.0, 160.0); val b = frame(.1, 140.0)
            direction.observe(a, null); direction.observe(b, a)
            assertNull(direction.observe(fault, b))
            assertTrue(direction.restarted)
            assertNull(direction.rateDegreesPerSecond)
        }
    }

    @Test fun stationaryFitAndBufferStayBoundedDuringDenseInput() {
        val direction = WorkoutPhaseDirection()
        var previous: MotionSample? = null
        for (i in 0..10_000) {
            val next = frame(i * .001, 120.0)
            direction.observe(next, previous); previous = next
            assertTrue(direction.retainedSamples <= WorkoutPhaseDirection.maximumSamples)
        }
        assertEquals(0.0, assertNotNull(direction.rateDegreesPerSecond), 1e-7)
        // Continuous raw stationarity is still observable even if the bounded
        // fit covers less than its desired window at an unrealistic 1000 Hz.
        assertEquals(WorkoutMotionDirection.stationary, direction.observe(frame(10.001, 120.0), previous))
    }

    @Test fun phaseStabilizationCannotChangeRawRepMeasurementsOrEligibility() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val session = LiveSetSession(); session.start(exercise, ArmMeasurement.Side.left)
            for ((time, angle) in listOf(0.0 to 170.0, .15 to 170.0, .3 to 80.0, .45 to 80.0, .6 to 170.0, .75 to 170.0))
                session.consume(CounterFixtures.bilateral(time, angle, angle, exercise = exercise), CounterFixtures.edge)
            session.finish()
            val rep = session.record.reps.single()
            assertEquals(1, session.observedMovements)
            assertEquals(1, session.record.completedCount)
            assertEquals(.75, rep.returnedAt)
            assertEquals(.3, rep.extremeAt)
            assertEquals(80.0, assertNotNull(rep.left.minimum), 1e-9)
            assertEquals(170.0, assertNotNull(rep.right.maximum), 1e-9)
            assertEquals(1.0, rep.durationCoverage)
            assertEquals(8, rep.countPolicyVersion)
            assertEquals(2, rep.metricVersion)
            assertEquals(3, session.record.activity!!.metricVersion)
        }
    }
}
