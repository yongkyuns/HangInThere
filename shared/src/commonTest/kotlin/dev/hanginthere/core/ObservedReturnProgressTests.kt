package dev.hanginthere.core

import kotlin.math.PI
import kotlin.math.tan
import kotlin.test.*

class ObservedReturnProgressTests {
    private data class State(val progress: Double, val cycles: Int, val counted: Boolean, val phase: String,
        val target: Double?, val targetReached: Boolean)

    @Test fun displayFollowsObservedReturnBeforeCountingReversalDwellFinishes() {
        for (dip in listOf(false, true)) {
            val states = replay(dip, 140.0)
            val earlyReturn = states.getValue(2080)
            assertEquals("outbound", earlyReturn.phase)
            assertEquals(.5 + .45 * .05, earlyReturn.progress, 1e-9)
            assertEquals(0, earlyReturn.cycles)
            assertFalse(earlyReturn.counted)
            assertEquals(.5, states.getValue(2000).progress, 1e-9)
        }
    }

    @Test fun returnProgressUsesEachAttemptsObservedRangeForBothExercises() {
        for (dip in listOf(false, true)) for (depth in listOf(140.0, 210.0)) {
            val states = replay(dip, depth)
            for ((ms, fractionReturned) in listOf(2400L to .25, 2800L to .5, 3200L to .75)) {
                val state = states.getValue(ms)
                assertEquals(.5 + .45 * fractionReturned, state.progress, 1e-9, "dip=$dip depth=$depth at=$ms")
                assertEquals(0, state.cycles)
            }
            assertEquals(1, states.values.last().cycles)
            assertTrue(states.values.any { it.counted })
            assertTrue(states.values.all { (it.progress == 1.0) == it.counted })
        }
    }

    @Test fun missingObservationOrCameraLossCannotSupplyReturnProgressOrCompletion() {
        for (dip in listOf(false, true)) for (fault in listOf("body", "camera")) {
            val states = replay(dip, 140.0, fault)
            assertEquals(0.0, states.getValue(2800).progress)
            assertEquals(0, states.values.last().cycles)
            assertFalse(states.values.any { it.counted || it.progress == 1.0 })
        }
    }

    @Test fun movementTargetFillsThenFallsWithThePoseWithoutPredictingACount() {
        for (dip in listOf(false, true)) {
            val states = replay(dip, 140.0)
            assertEquals(0.0, states.getValue(1000).target)
            assertTrue(checkNotNull(states.getValue(1280).target) > 0.0)
            assertEquals(1.0, states.getValue(1800).target)
            assertTrue(states.getValue(1800).targetReached)
            assertEquals(0, states.getValue(1800).cycles)
            assertTrue(checkNotNull(states.getValue(3200).target) < checkNotNull(states.getValue(2800).target))
            assertTrue(checkNotNull(states.getValue(3480).target) < checkNotNull(states.getValue(3200).target))
            assertEquals(1, states.values.last().cycles)
            assertEquals(0.0, states.values.last().target)
        }
    }

    @Test fun unavailableBodyOrCameraNeverShowsATargetReached() {
        for (dip in listOf(false, true)) for (fault in listOf("body", "camera")) {
            val paused = replay(dip, 140.0, fault).getValue(2800)
            assertNull(paused.target)
            assertFalse(paused.targetReached)
            assertEquals(0, paused.cycles)
        }
    }

    private fun replay(dip: Boolean, depth: Double, fault: String? = null): Map<Long, State> {
        val selection = BodyAthleteSelection()
        val pull = OverheadPullUpMotion()
        val dips = BilateralTorsoDipMotion()
        return (0L..4000L step 40).associateWith { ms ->
            val fraction = when {
                ms <= 1000 -> 0.0
                ms <= 1600 -> (ms - 1000) / 600.0
                ms <= 2000 -> 1.0
                else -> ((3600 - ms) / 1600.0).coerceAtLeast(0.0)
            }
            val invalid = fault != null && ms in 2600L..3040L
            val pose = pose(ms, dip, depth * fraction, 170 - 70 * fraction)
            selection.observe(if (invalid && fault == "body") pose.copy(people = emptyList()) else pose)
            if (dip) {
                val state = dips.observe(selection, !(invalid && fault == "camera"))
                State(state.progress, state.cycles, state.event?.outcome == "imageCycle", state.phase.name, state.targetProgress, state.targetReached)
            } else {
                val state = pull.observe(selection, "progress", !(invalid && fault == "camera"))
                State(state.progress, state.cycles, state.event?.outcome == "imageCycle", state.phase.name, state.targetProgress, state.targetReached)
            }
        }
    }

    private fun pose(ms: Long, dip: Boolean, travel: Double, degrees: Double): PoseResult {
        val shoulder = if (dip) 280 + travel else 600 - travel
        val wrist = if (dip) 600.0 else 250.0
        val offset = kotlin.math.abs(wrist - shoulder) / (2 * tan(degrees * PI / 360))
        val points = listOf(250.0, 650.0).flatMapIndexed { i, x ->
            val joints = ArmMeasurement.Side.entries[i].joints
            listOf(Landmark(joints[0], Point2D(x, shoulder), .9),
                Landmark(joints[1], Point2D(x + if (i == 0) -offset else offset, (shoulder + wrist) / 2), .9),
                Landmark(joints[2], Point2D(x, wrist), .9))
        } + listOf(Landmark(PoseJoint.leftHip, Point2D(250.0, shoulder + 500), .9),
            Landmark(PoseJoint.rightHip, Point2D(650.0, shoulder + 500), .9))
        return PoseResult(PresentationTime(ms, 1000), ImageSize(1600.0, 1400.0),
            listOf(PoseObservation(points)), "authored-progress", 1)
    }
}
