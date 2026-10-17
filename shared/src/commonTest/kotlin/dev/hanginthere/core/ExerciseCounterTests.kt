package dev.hanginthere.core

import kotlin.math.*
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Original analytic chains mirrored from the Swift regressions, not pose SDK ground truth. */
object CounterFixtures {
    val edge = BarSegment(Point2D(100.0, 500.0), Point2D(900.0, 500.0))
    fun pose(time: Double, degrees: Double = 170.0, exercise: ExerciseCounter.Exercise = ExerciseCounter.Exercise.pullUp,
        side: ArmMeasurement.Side = ArmMeasurement.Side.left, confidence: Double = 1.0, wristX: Double = 500.0,
        shoulderOverride: Double? = null, verticalOffset: Double = 0.0): PoseResult {
        val half = degrees * PI / 360
        val direction = if (exercise == ExerciseCounter.Exercise.pullUp) 1.0 else -1.0
        val y = 120 * sin(half) * direction
        val points = listOf(Point2D(wristX, (shoulderOverride ?: (500 + 2 * y)) + verticalOffset),
            Point2D(wristX + 120 * cos(half), 500 + y + verticalOffset), Point2D(wristX, 500 + verticalOffset))
        return PoseResult(PresentationTime((time * 1000).roundToLong(), 1000), ImageSize(1000.0, 1000.0),
            listOf(PoseObservation(side.joints.zip(points).map { (joint, point) -> Landmark(joint, point, confidence) })),
            "analytic test arm", 0)
    }
    fun bilateral(time: Double, left: Double?, right: Double?, rightOffset: Double = 0.0,
        exercise: ExerciseCounter.Exercise = ExerciseCounter.Exercise.pullUp): PoseResult {
        val base = pose(time)
        val landmarks = (left?.let { pose(time, it, exercise, wristX = 300.0).people[0].landmarks } ?: emptyList()) +
            (right?.let { pose(time, it, exercise, ArmMeasurement.Side.right, wristX = 700.0, verticalOffset = rightOffset).people[0].landmarks } ?: emptyList())
        return base.copy(people = listOf(PoseObservation(landmarks)))
    }
    fun noPerson(time: Double): PoseResult = pose(time).copy(people = emptyList())
    fun projectedDip(time: Double, degrees: Double, armScale: Double, otherDegrees: Double = degrees): PoseResult {
        val base = bilateral(time, degrees, otherDegrees, exercise = ExerciseCounter.Exercise.dip)
        val person = base.people[0]
        val landmarks = ArmMeasurement.Side.entries.flatMap { side ->
            val wrist = person.landmark(side.joints[2])!!.position
            side.joints.map { joint ->
                val point = person.landmark(joint)!!
                point.copy(position = Point2D(wrist.x + (point.position.x - wrist.x) * armScale,
                    wrist.y + (point.position.y - wrist.y) * armScale))
            }
        }
        return base.copy(people = listOf(PoseObservation(landmarks)))
    }
    fun lowJoint(time: Double, degrees: Double, exercise: ExerciseCounter.Exercise, side: ArmMeasurement.Side,
        jointIndex: Int): PoseResult {
        val base = pose(time, degrees, exercise, side)
        return base.copy(people = listOf(PoseObservation(base.people[0].landmarks.map {
            if (it.joint == side.joints[jointIndex]) it.copy(confidence = 0.1) else it
        })))
    }
    fun feed(counter: ExerciseCounter, vararg samples: Pair<Double, Double>) {
        samples.forEach { (time, angle) -> counter.consume(pose(time, angle, counter.exercise, counter.side), edge) }
    }
    fun arm(counter: ExerciseCounter) = feed(counter, 0.0 to 170.0, 0.15 to 170.0)
    fun feedBoth(counter: ExerciseCounter, time: Double, left: Double?, right: Double?, rightOffset: Double = 0.0) =
        counter.consume(bilateral(time, left, right, rightOffset, counter.exercise), edge)
}

class ExerciseCounterTests {
    @Test fun projectedDipsAboveSupportAngleStillCompleteThreeRelativeCycles() {
        for (side in ArmMeasurement.Side.entries) {
            val c = ExerciseCounter(ExerciseCounter.Exercise.dip, side)
            val x = if(side==ArmMeasurement.Side.left) 300.0 else 700.0
            val rail = BarSegment(Point2D(x,500.0),Point2D(x,620.0))
            repeat(3) { rep ->
                val origin = rep.toDouble()
                for ((offset,degrees,scale) in listOf(Triple(0.0,170.0,1.0), Triple(.15,170.0,1.0),
                        Triple(.3,156.0,.95), Triple(.45,127.0,.7), Triple(.6,127.0,.7)))
                    c.consume(CounterFixtures.projectedDip(origin+offset,degrees,scale),rail)
                assertEquals(rep,c.observedMovements)
                assertEquals(ExerciseCounter.Phase.returning,c.phase)
                for (offset in listOf(.75,.9))
                    c.consume(CounterFixtures.projectedDip(origin+offset,170.0,1.0),rail)
                assertEquals(rep+1,c.observedMovements)
            }
            for (time in listOf(3.0,3.15))
                c.consume(CounterFixtures.projectedDip(time,125.0,.7),rail)
            c.finish()
            assertEquals(3,c.observedMovements)
            assertEquals(1,c.interruptedAttempts)
        }
    }
    @Test fun projectedDipStillRequiresTravelAndOppositeArmBending() {
        for (side in ArmMeasurement.Side.entries) for (missingTravel in listOf(true,false)) {
            val c = ExerciseCounter(ExerciseCounter.Exercise.dip, side)
            val x = if(side==ArmMeasurement.Side.left) 300.0 else 700.0
            val rail = BarSegment(Point2D(x,500.0),Point2D(x,620.0))
            for (time in listOf(0.0,.15))
                c.consume(CounterFixtures.projectedDip(time,170.0,1.0),rail)
            for (time in listOf(.3,.45)) {
                val left = if(!missingTravel && side==ArmMeasurement.Side.right) 170.0 else 125.0
                val right = if(!missingTravel && side==ArmMeasurement.Side.left) 170.0 else 125.0
                c.consume(CounterFixtures.projectedDip(time,left,if(missingTravel) 1.0 else .7,right),rail)
            }
            for (time in listOf(.6,.75))
                c.consume(CounterFixtures.projectedDip(time,170.0,1.0),rail)
            assertEquals(0,c.observedMovements)
            assertEquals(1,c.partialAttempts)
        }
    }
    private fun pullUp() = ExerciseCounter().also(CounterFixtures::arm)
    private fun dip(side: ArmMeasurement.Side = ArmMeasurement.Side.left) = ExerciseCounter(ExerciseCounter.Exercise.dip, side)

    // Keep geometry independent of the exact source clock under test.
    private fun clockPose(ticks: Long, degrees: Double, exercise: ExerciseCounter.Exercise,
                          side: ArmMeasurement.Side, timescale: Int = 1_000_000) = CounterFixtures.pose(0.0, degrees, exercise, side)
        .copy(timestamp = PresentationTime(ticks, timescale))

    @Test fun endpointDwellUsesSourceTicksAtEveryOrigin() {
        for (exercise in ExerciseCounter.Exercise.entries) for (side in ArmMeasurement.Side.entries)
            for (origin in listOf(0L, 1_100_000L, 9_007_199_254_740_992L)) {
                val c = ExerciseCounter(exercise, side)
                for ((offset, degrees) in listOf(0L to 170.0,120_000L to 170.0,240_000L to 80.0,
                    360_000L to 80.0,480_000L to 170.0,600_000L to 170.0)) {
                    c.consume(clockPose(origin + offset, degrees, exercise, side), CounterFixtures.edge)
                    if (offset == 120_000L) assertEquals(ExerciseCounter.Phase.ready, c.phase)
                    if (offset == 360_000L) assertEquals(ExerciseCounter.Phase.returning, c.phase)
                }
                assertEquals(ExerciseCounter.Phase.ready, c.phase)
                assertEquals(1, c.observedMovements); assertEquals(0, c.partialAttempts); assertEquals(0, c.interruptedAttempts)
            }
    }

    @Test fun endpointDwellNeverRoundsUpAOneTickShortHold() {
        for (exercise in ExerciseCounter.Exercise.entries) for (side in ArmMeasurement.Side.entries)
            for (origin in listOf(1_100_000L, 9_007_199_254_740_992L)) {
                val c = ExerciseCounter(exercise, side)
                fun frame(offset: Long, degrees: Double) = clockPose(origin + offset, degrees, exercise, side)
                c.consume(frame(0,170.0), CounterFixtures.edge); c.consume(frame(119_999,170.0), CounterFixtures.edge)
                assertEquals(ExerciseCounter.Phase.seekingStart, c.phase)
                c.consume(frame(120_000,170.0), CounterFixtures.edge); assertEquals(ExerciseCounter.Phase.ready, c.phase)
                c.consume(frame(240_000,80.0), CounterFixtures.edge); c.consume(frame(359_999,80.0), CounterFixtures.edge)
                assertEquals(ExerciseCounter.Phase.outbound, c.phase); assertEquals(0, c.observedMovements)
                c.consume(frame(360_000,80.0), CounterFixtures.edge); assertEquals(ExerciseCounter.Phase.returning, c.phase)
                c.consume(frame(480_000,170.0), CounterFixtures.edge); c.consume(frame(599_999,170.0), CounterFixtures.edge)
                assertEquals(ExerciseCounter.Phase.returning, c.phase)
                c.consume(frame(600_000,170.0), CounterFixtures.edge); assertEquals(ExerciseCounter.Phase.ready, c.phase)
                assertEquals(1, c.observedMovements); assertEquals(0, c.interruptedAttempts)
            }
    }

    @Test fun sourceGapUsesExactAllowedBoundaryWithoutExtraTolerance() {
        for (exercise in ExerciseCounter.Exercise.entries) for (side in ArmMeasurement.Side.entries) for (excess in listOf(0L,1L)) {
            val c = ExerciseCounter(exercise, side)
            for ((offset, degrees) in listOf(0L to 170.0,150_000L to 170.0,300_000L to 80.0,(650_000L + excess) to 80.0))
                c.consume(clockPose(offset, degrees, exercise, side), CounterFixtures.edge)
            if (excess == 0L) {
                assertEquals(ExerciseCounter.Phase.returning, c.phase); assertEquals(0, c.interruptedAttempts)
            } else {
                assertEquals(ExerciseCounter.Phase.seekingStart, c.phase); assertEquals(0, c.observedMovements)
                assertEquals(1, c.interruptedAttempts); assertEquals("sourceTimeGap", c.lastEvent?.reason)
            }
        }
    }

    @Test fun rejectedSourceClocksCannotPoisonCounterRecovery() {
        data class Fault(val origin: Long, val badTicks: Long, val scale: Int, val reason: String)
        val faults = listOf(Fault(0,240_000,1_000_000,"nonIncreasingTimestamp"),
            Fault(0,239_999,1_000_000,"nonIncreasingTimestamp"),Fault(0,400_000,0,"invalidTimestamp"),
            Fault(Long.MIN_VALUE,Long.MAX_VALUE,1_000_000,"invalidTimestamp"))
        for (exercise in ExerciseCounter.Exercise.entries) for (side in ArmMeasurement.Side.entries) for (fault in faults) {
            val c = ExerciseCounter(exercise, side)
            for ((offset, degrees) in listOf(0L to 170.0,120_000L to 170.0,240_000L to 80.0))
                c.consume(clockPose(fault.origin + offset, degrees, exercise, side), CounterFixtures.edge)
            assertEquals(ExerciseCounter.Phase.outbound, c.phase)
            c.consume(clockPose(fault.badTicks,80.0,exercise,side,fault.scale), CounterFixtures.edge)
            assertEquals(ExerciseCounter.Phase.seekingStart, c.phase); assertEquals(0, c.observedMovements)
            assertEquals(1, c.interruptedAttempts); assertEquals(fault.reason, c.lastEvent?.reason)
            for (offset in listOf(360_000L,480_000L))
                c.consume(clockPose(fault.origin + offset,170.0,exercise,side), CounterFixtures.edge)
            assertEquals(ExerciseCounter.Phase.ready, c.phase); assertEquals(1, c.interruptedAttempts)
        }
    }

    @Test fun mixedTimescalesRetainNonBoundaryCounterCycles() {
        for (exercise in ExerciseCounter.Exercise.entries) for (side in ArmMeasurement.Side.entries) {
            val c = ExerciseCounter(exercise, side)
            val frames = listOf(Triple(1_100L,1_000,170.0),Triple(1_250_000L,1_000_000,170.0),
                Triple(1_400L,1_000,80.0),Triple(1_550_000L,1_000_000,80.0),
                Triple(1_700L,1_000,170.0),Triple(1_850_000L,1_000_000,170.0))
            for ((ticks, scale, degrees) in frames)
                c.consume(clockPose(ticks,degrees,exercise,side,scale), CounterFixtures.edge)
            assertEquals(ExerciseCounter.Phase.ready, c.phase); assertEquals(1, c.observedMovements)
            assertEquals(0, c.interruptedAttempts)
        }
    }

    @Test fun bilateralPullUpsRequireBothEndpointsRegardlessOfSide() {
        for (side in ArmMeasurement.Side.entries) {
            val c = ExerciseCounter(side = side)
            CounterFixtures.feedBoth(c, 0.0, 170.0, 170.0); CounterFixtures.feedBoth(c, 0.15, 170.0, 170.0)
            CounterFixtures.feedBoth(c, 0.3, 80.0, 170.0); CounterFixtures.feedBoth(c, 0.45, 80.0, 170.0)
            assertEquals(ExerciseCounter.Phase.ready, c.phase); assertEquals(0, c.observedMovements)
            CounterFixtures.feedBoth(c, 0.6, 80.0, 80.0); CounterFixtures.feedBoth(c, 0.75, 80.0, 80.0)
            assertEquals(1, c.observedMovements)
            CounterFixtures.feedBoth(c, 0.9, 170.0, 80.0); CounterFixtures.feedBoth(c, 1.05, 170.0, 80.0)
            assertEquals(ExerciseCounter.Phase.returning, c.phase)
            CounterFixtures.feedBoth(c, 1.2, 170.0, 170.0); CounterFixtures.feedBoth(c, 1.35, 170.0, 170.0)
            CounterFixtures.feedBoth(c, 1.5, 80.0, 80.0); CounterFixtures.feedBoth(c, 1.65, 80.0, 80.0)
            assertEquals(2, c.observedMovements)
        }
    }
    @Test fun bothBentArmsMeetTravelIndividually() {
        val c = ExerciseCounter()
        CounterFixtures.feedBoth(c, 0.0, 170.0, 170.0); CounterFixtures.feedBoth(c, 0.15, 170.0, 170.0)
        CounterFixtures.feedBoth(c, 0.3, 80.0, 80.0, 90.0); CounterFixtures.feedBoth(c, 0.45, 80.0, 80.0, 90.0)
        assertEquals(ExerciseCounter.Phase.outbound, c.phase); assertEquals(0, c.observedMovements)
        CounterFixtures.feedBoth(c, 0.6, 80.0, 80.0); CounterFixtures.feedBoth(c, 0.75, 80.0, 80.0)
        assertEquals(1, c.observedMovements)
    }
    @Test fun anchoredArmCanContinueWhenOtherIsObscured() {
        for (side in ArmMeasurement.Side.entries) {
            val c = ExerciseCounter()
            CounterFixtures.feedBoth(c, 0.0, 170.0, 170.0); CounterFixtures.feedBoth(c, 0.15, 170.0, 170.0)
            for (time in listOf(0.3, 0.45)) CounterFixtures.feedBoth(c, time,
                if (side == ArmMeasurement.Side.left) 80.0 else null, if (side == ArmMeasurement.Side.right) 80.0 else null)
            assertEquals(1, c.observedMovements); assertEquals(0, c.interruptedAttempts)
        }
    }
    @Test fun newlyVisibleBentArmCannotBorrowBaseline() {
        val c = ExerciseCounter()
        CounterFixtures.feedBoth(c, 0.0, 170.0, null); CounterFixtures.feedBoth(c, 0.15, 170.0, null)
        CounterFixtures.feedBoth(c, 0.3, 80.0, null); CounterFixtures.feedBoth(c, 0.45, 80.0, 80.0)
        assertEquals(0, c.observedMovements); assertEquals(1, c.interruptedAttempts)
        assertEquals("armBaselineUnavailable", c.trackingIssue)
    }
    @Test fun alternatingArmGlimpsesCannotCompleteDwell() {
        val c = ExerciseCounter()
        CounterFixtures.feedBoth(c, 0.0, 170.0, 170.0); CounterFixtures.feedBoth(c, 0.15, 170.0, 170.0)
        CounterFixtures.feedBoth(c, 0.3, 80.0, null); CounterFixtures.feedBoth(c, 0.45, null, 80.0)
        assertEquals(0, c.observedMovements)
        CounterFixtures.feedBoth(c, 0.6, null, 80.0); assertEquals(1, c.observedMovements)
    }
    @Test fun continuousArmMaintainsDwell() {
        val c = ExerciseCounter()
        CounterFixtures.feedBoth(c, 0.0, 170.0, 170.0); CounterFixtures.feedBoth(c, 0.15, 170.0, 170.0)
        CounterFixtures.feedBoth(c, 0.3, 80.0, 80.0); CounterFixtures.feedBoth(c, 0.45, null, 80.0)
        assertEquals(1, c.observedMovements)
    }
    @Test fun armsRetainIndependentShoulderBaselines() {
        val c = ExerciseCounter()
        CounterFixtures.feedBoth(c, 0.0, 170.0, 170.0, 100.0); CounterFixtures.feedBoth(c, 0.15, 170.0, 170.0, 100.0)
        CounterFixtures.feedBoth(c, 0.3, 80.0, 80.0, 100.0); CounterFixtures.feedBoth(c, 0.45, 80.0, 80.0, 100.0)
        assertEquals(1, c.observedMovements)
    }
    @Test fun partialReturnRefreshesBaseline() {
        val c = ExerciseCounter()
        CounterFixtures.feedBoth(c, 0.0, 170.0, 170.0); CounterFixtures.feedBoth(c, 0.15, 170.0, 170.0)
        CounterFixtures.feedBoth(c, 0.3, 130.0, 130.0)
        CounterFixtures.feedBoth(c, 0.45, 170.0, 170.0, -70.0); CounterFixtures.feedBoth(c, 0.6, 170.0, 170.0, -70.0)
        assertEquals(1, c.partialAttempts)
        CounterFixtures.feedBoth(c, 0.75, 80.0, 80.0); CounterFixtures.feedBoth(c, 0.9, 80.0, 80.0)
        assertEquals(0, c.observedMovements)
    }
    @Test fun pullUpCountsAtTopThenRequiresReturn() {
        val c = pullUp()
        CounterFixtures.feed(c, 0.3 to 80.0, 0.45 to 80.0)
        assertEquals(1, c.observedMovements); assertEquals(0.45, c.lastEvent?.sourceSeconds)
        assertEquals("barReferencedTop;chinClearanceNotMeasured", c.lastEvent?.reason)
        CounterFixtures.feed(c, 0.6 to 80.0, 0.75 to 80.0, 0.9 to 170.0, 1.05 to 170.0, 1.2 to 80.0, 1.35 to 80.0)
        assertEquals(listOf(0.45, 1.35), c.events.filter { it.outcome == ExerciseCounter.Outcome.movement }.map { it.sourceSeconds })
        assertEquals("unverified", c.summary.formVerification)
    }
    @Test fun dipCountsOnlyAfterReturnNearTop() {
        val c = dip(ArmMeasurement.Side.right)
        CounterFixtures.arm(c); CounterFixtures.feed(c, 0.3 to 80.0, 0.45 to 80.0)
        assertEquals(0, c.observedMovements); assertEquals(ExerciseCounter.Phase.returning, c.phase)
        CounterFixtures.feed(c, 0.6 to 170.0, 0.75 to 170.0)
        assertEquals(1, c.observedMovements); assertEquals("barReferencedCycle;dipDepthAndFormNotQualified", c.lastEvent?.reason)
    }
    @Test fun projectedDipTopDoesNotRequirePullUpLockout() {
        val c = dip(ArmMeasurement.Side.right)
        CounterFixtures.feed(c, 0.0 to 140.0, 0.15 to 140.0, 0.3 to 70.0, 0.45 to 70.0, 0.6 to 140.0, 0.75 to 140.0)
        assertEquals(1, c.observedMovements); assertEquals(0, c.interruptedAttempts)
    }
    @Test fun dipStartCanSurviveShortPersonDropout() {
        val c = dip()
        CounterFixtures.feed(c, 0.0 to 140.0)
        c.consume(CounterFixtures.noPerson(0.125), CounterFixtures.edge)
        CounterFixtures.feed(c, 0.25 to 140.0)
        assertEquals(ExerciseCounter.Phase.ready, c.phase); assertEquals(0, c.interruptedAttempts)
    }
    @Test fun dipStartRecoversAfterExpiredElbowConfidenceHole() {
        for (side in ArmMeasurement.Side.entries) {
            val c = dip(side)
            CounterFixtures.feed(c, 0.0 to 140.0)
            for (tick in 1..9) {
                c.consume(CounterFixtures.lowJoint(tick / 10.0, 140.0, c.exercise, side, 1), CounterFixtures.edge)
                assertEquals(ExerciseCounter.Phase.seekingStart, c.phase)
                assertEquals(0, c.observedMovements)
            }
            CounterFixtures.feed(c, 1.0 to 140.0)
            assertEquals(ExerciseCounter.Phase.seekingStart, c.phase)
            CounterFixtures.feed(c, 1.15 to 140.0)
            assertEquals(ExerciseCounter.Phase.ready, c.phase)
            CounterFixtures.feed(c, 1.3 to 70.0, 1.45 to 70.0, 1.6 to 140.0, 1.75 to 140.0)
            assertEquals(1, c.observedMovements)
            assertEquals(1.75, c.lastEvent?.sourceSeconds)
            assertEquals(0, c.interruptedAttempts)
        }
    }
    @Test fun expiredDipStartCannotReuseItsOldExtensionWitness() {
        for (side in ArmMeasurement.Side.entries) {
            val c = dip(side)
            CounterFixtures.feed(c, 0.0 to 140.0)
            for (tick in 1..9)
                c.consume(CounterFixtures.lowJoint(tick / 10.0, 140.0, c.exercise, side, 1), CounterFixtures.edge)
            CounterFixtures.feed(c, 1.0 to 130.0, 1.15 to 130.0, 1.3 to 130.0)
            assertEquals(ExerciseCounter.Phase.seekingStart, c.phase)
            assertEquals(0, c.observedMovements)
            CounterFixtures.feed(c, 1.45 to 140.0, 1.6 to 140.0)
            assertEquals(ExerciseCounter.Phase.ready, c.phase)
            assertTrue(c.events.isEmpty())
        }
    }
    @Test fun dipStartKeepsItsWitnessAtTheAllowedOutageBoundary() {
        for (side in ArmMeasurement.Side.entries) {
            val c = dip(side)
            CounterFixtures.feed(c, 0.0 to 140.0)
            for (tick in 1..7)
                c.consume(CounterFixtures.lowJoint(tick / 10.0, 140.0, c.exercise, side, 1), CounterFixtures.edge)
            CounterFixtures.feed(c, 0.75 to 140.0)
            assertEquals(ExerciseCounter.Phase.ready, c.phase)
            assertEquals(0, c.observedMovements)
            assertTrue(c.events.isEmpty())
        }
    }
    @Test fun partialDipRecoveryCannotCount() {
        val c = dip()
        CounterFixtures.feed(c, 0.0 to 140.0, 0.15 to 140.0, 0.3 to 70.0, 0.45 to 70.0, 0.6 to 110.0, 0.75 to 110.0)
        assertEquals(0, c.observedMovements); assertEquals(ExerciseCounter.Phase.returning, c.phase)
        CounterFixtures.feed(c, 0.9 to 140.0, 1.05 to 140.0)
        assertEquals(1, c.observedMovements); assertEquals(0, c.partialAttempts)
    }
    @Test fun confidenceHoleCannotAcquireDipTopButCanSustainWitnessedTop() {
        val c = dip(ArmMeasurement.Side.right)
        CounterFixtures.feed(c, 0.0 to 140.0, 0.15 to 140.0, 0.3 to 70.0, 0.45 to 70.0, 0.6 to 110.0)
        c.consume(CounterFixtures.lowJoint(0.75, 140.0, c.exercise, c.side, 1), CounterFixtures.edge)
        assertEquals(0, c.observedMovements)
        CounterFixtures.feed(c, 0.9 to 140.0)
        c.consume(CounterFixtures.lowJoint(1.05, 140.0, c.exercise, c.side, 1), CounterFixtures.edge)
        assertEquals(1, c.observedMovements)
        assertEquals(DipProgress.Stage.counted, c.dipProgress?.stage)
        assertEquals(1.0, c.dipProgress?.fraction)
    }
    @Test fun unreliableShoulderCannotSustainReturn() {
        val c = dip()
        CounterFixtures.feed(c, 0.0 to 140.0, 0.15 to 140.0, 0.3 to 70.0, 0.45 to 70.0, 0.6 to 110.0)
        c.consume(CounterFixtures.lowJoint(0.75, 140.0, c.exercise, c.side, 0), CounterFixtures.edge)
        assertEquals(0, c.observedMovements)
        CounterFixtures.feed(c, 0.9 to 140.0, 1.05 to 140.0)
        assertEquals(1, c.observedMovements)
    }

    // Vary the observed elbow while preserving independent top geometry.
    private fun dipTopPose(time: Double, side: ArmMeasurement.Side, degrees: Double = 170.0,
                           elbowVisible: Boolean = true): PoseResult {
        val base = CounterFixtures.pose(time, exercise = ExerciseCounter.Exercise.dip, side = side)
        val points = base.people.single().landmarks
        val shoulder = points[0].position; val wrist = points[2].position
        val elbow = Point2D(wrist.x + (wrist.y - shoulder.y) / (2 * tan(degrees * PI / 360)),
            (shoulder.y + wrist.y) / 2)
        return base.copy(people = listOf(PoseObservation(points.map {
            if (it.joint == side.joints[1]) it.copy(position = elbow, confidence = if (elbowVisible) 1.0 else .1) else it
        })))
    }
    private fun verifyDipReturnWitness(fullCycle: Boolean) {
        for (side in ArmMeasurement.Side.entries) for (contradicted in listOf(false, true)) {
            val c = dip(side)
            // 110° starts an attempt without enough shoulder travel for bottom.
            val bottom = if (fullCycle) 80.0 else 110.0
            CounterFixtures.feed(c, 0.0 to 170.0, .15 to 170.0, .3 to bottom, .45 to bottom)
            val activePhase = if (fullCycle) ExerciseCounter.Phase.returning else ExerciseCounter.Phase.outbound
            assertEquals(activePhase, c.phase)
            c.consume(dipTopPose(.6, side), CounterFixtures.edge)
            if (contradicted) c.consume(dipTopPose(.65, side, if (fullCycle) 140.0 else 110.0), CounterFixtures.edge)
            c.consume(dipTopPose(.8, side, elbowVisible = false), CounterFixtures.edge)
            if (contradicted) {
                c.consume(dipTopPose(.95, side, elbowVisible = false), CounterFixtures.edge)
                assertEquals(activePhase, c.phase)
                assertEquals(0, c.observedMovements); assertEquals(0, c.partialAttempts); assertTrue(c.events.isEmpty())
                c.consume(dipTopPose(1.1, side), CounterFixtures.edge)
                assertEquals(0, c.observedMovements); assertEquals(0, c.partialAttempts)
                c.consume(dipTopPose(1.25, side, elbowVisible = false), CounterFixtures.edge)
            }
            assertEquals(if (fullCycle) 1 else 0, c.observedMovements)
            assertEquals(if (fullCycle) 0 else 1, c.partialAttempts)
            assertEquals(0, c.interruptedAttempts)
            assertEquals(if (contradicted) 1.25 else .8, c.lastEvent?.sourceSeconds)
        }
    }
    @Test fun contradictedDipReturnCannotReuseTopWitnessAcrossElbowOutage() {
        verifyDipReturnWitness(fullCycle = true)
    }
    @Test fun contradictedPartialDipReturnNeedsFreshTopWitness() {
        verifyDipReturnWitness(fullCycle = false)
    }
    @Test fun lowConfidenceShoulderCannotAcquireStart() {
        val c = dip()
        for (time in listOf(0.0, 0.15)) c.consume(CounterFixtures.lowJoint(time, 140.0, c.exercise, c.side, 0), CounterFixtures.edge)
        assertEquals(ExerciseCounter.Phase.seekingStart, c.phase); assertEquals(0, c.observedMovements)
    }
    @Test fun movedGripCannotBecomeBaselineDrift() {
        val c = dip()
        CounterFixtures.feed(c, 0.0 to 140.0, 0.15 to 140.0, 0.3 to 70.0, 0.45 to 70.0)
        for (time in listOf(0.6, 0.75)) c.consume(CounterFixtures.pose(time, 140.0, c.exercise, c.side, verticalOffset = 40.0), CounterFixtures.edge)
        assertEquals(0, c.observedMovements); assertEquals(1, c.interruptedAttempts)
    }
    @Test fun bentHoldsCannotAcquireStartingPosition() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val c = ExerciseCounter(exercise)
            CounterFixtures.feed(c, 0.0 to 70.0, 0.15 to 70.0, 0.3 to 75.0, 0.45 to 70.0)
            assertEquals(ExerciseCounter.Phase.seekingStart, c.phase); assertEquals(0, c.observedMovements)
        }
    }
    @Test fun shortDipOutagePausesWithoutErasingAttempt() {
        val c = dip()
        CounterFixtures.feed(c, 0.0 to 140.0, 0.15 to 140.0, 0.3 to 70.0)
        c.consume(CounterFixtures.noPerson(0.425), CounterFixtures.edge)
        assertEquals(DipProgress.Stage.paused, c.dipProgress?.stage)
        CounterFixtures.feed(c, 0.55 to 70.0, 0.7 to 70.0, 0.85 to 140.0, 1.0 to 140.0)
        assertEquals(1, c.observedMovements); assertEquals(0, c.interruptedAttempts)
    }
    @Test fun longDipOutageInterruptsAttempt() {
        val c = dip()
        CounterFixtures.feed(c, 0.0 to 140.0, 0.15 to 140.0, 0.3 to 70.0)
        for (time in listOf(0.45, 0.8, 1.1)) c.consume(CounterFixtures.noPerson(time), CounterFixtures.edge)
        assertEquals(1, c.interruptedAttempts); assertEquals(ExerciseCounter.Phase.seekingStart, c.phase)
    }
    @Test fun finishCountsNoIncompleteDipAndKeepsCompletedPullUp() {
        val p = pullUp(); CounterFixtures.feed(p, 0.3 to 80.0, 0.45 to 80.0); p.finish(); p.finish()
        assertEquals(1, p.observedMovements); assertEquals(0, p.interruptedAttempts)
        val d = dip(); CounterFixtures.arm(d); CounterFixtures.feed(d, 0.3 to 80.0, 0.45 to 80.0); d.finish(); d.finish()
        assertEquals(0, d.observedMovements); assertEquals(1, d.interruptedAttempts); assertEquals("endOfInput", d.lastEvent?.reason)
    }
    @Test fun shortAttemptIsPartialOnly() {
        val c = pullUp()
        CounterFixtures.feed(c, 0.3 to 130.0, 0.45 to 125.0, 0.6 to 170.0, 0.75 to 170.0)
        assertEquals(1, c.partialAttempts); assertEquals(0, c.observedMovements); assertEquals(ExerciseCounter.Phase.ready, c.phase)
    }
    @Test fun jitterCannotCreateEndpointDwell() {
        val c = pullUp()
        CounterFixtures.feed(c, 0.3 to 99.0, 0.36 to 101.0, 0.42 to 99.0, 0.48 to 101.0, 0.54 to 99.0, 0.60 to 101.0)
        assertEquals(0, c.observedMovements); assertEquals(ExerciseCounter.Phase.outbound, c.phase)
    }
    @Test fun endpointKindsCannotShareDwell() {
        val c = pullUp(); CounterFixtures.feed(c, 0.3 to 80.0, 0.45 to 170.0)
        assertEquals(0, c.partialAttempts)
        CounterFixtures.feed(c, 0.6 to 170.0); assertEquals(1, c.partialAttempts)
    }
    @Test fun slowBentHoldCountsExactlyOnce() {
        val c = pullUp()
        for (i in 2..50) CounterFixtures.feed(c, i * 0.15 to 80.0)
        assertEquals(1, c.observedMovements)
    }
    @Test fun missingJointCannotBorrowOtherArmOrOldPose() {
        val c = pullUp(); CounterFixtures.feed(c, 0.3 to 120.0)
        c.consume(CounterFixtures.pose(0.45, 80.0, side = ArmMeasurement.Side.right), CounterFixtures.edge)
        CounterFixtures.feed(c, 0.6 to 80.0, 0.75 to 80.0)
        assertEquals(0, c.observedMovements); assertEquals(1, c.interruptedAttempts)
    }
    @Test fun lowConfidenceRequiresFreshStart() {
        val c = pullUp(); CounterFixtures.feed(c, 0.3 to 120.0)
        c.consume(CounterFixtures.pose(0.45, 80.0, confidence = 0.1), CounterFixtures.edge)
        assertEquals("lowConfidence", c.trackingIssue)
        CounterFixtures.feed(c, 0.6 to 170.0, 0.75 to 170.0, 0.9 to 80.0, 1.05 to 80.0)
        assertEquals(1, c.observedMovements); assertEquals(1, c.interruptedAttempts)
    }
    @Test fun multiplePeopleCannotSelectConvenientSkeleton() {
        val c = pullUp(); CounterFixtures.feed(c, 0.3 to 120.0)
        val p = CounterFixtures.pose(0.45, 80.0)
        c.consume(p.copy(people = p.people + p.people), CounterFixtures.edge)
        assertEquals("multiplePeople", c.trackingIssue); assertEquals(1, c.interruptedAttempts)
    }
    @Test fun sourceGapCannotBridgeMissingTop() {
        val c = pullUp(); CounterFixtures.feed(c, 0.3 to 80.0, 1.0 to 80.0, 1.15 to 80.0)
        assertEquals(0, c.observedMovements); assertEquals(1, c.interruptedAttempts); assertEquals("sourceTimeGap", c.lastEvent?.reason)
    }
    @Test fun duplicateOrBackwardsTimestampsCannotCount() {
        val c = pullUp(); CounterFixtures.feed(c, 0.3 to 80.0, 0.3 to 80.0, 0.2 to 80.0, 0.45 to 80.0)
        assertEquals(0, c.observedMovements); assertEquals(1, c.interruptedAttempts)
    }
    @Test fun invalidTimestampNotReplacedWithAssumedFrameRate() {
        val c = pullUp(); CounterFixtures.feed(c, 0.3 to 120.0)
        val p = CounterFixtures.pose(0.45, 80.0)
        c.consume(p.copy(timestamp = PresentationTime(1, 0)), CounterFixtures.edge)
        assertEquals("invalidTimestamp", c.trackingIssue); assertEquals(1, c.interruptedAttempts)
        Json.encodeToString(c.summary)
    }
    @Test fun wristScreenDriftAloneDoesNotBreakPullUpContact() {
        val c = pullUp()
        c.consume(CounterFixtures.pose(0.3, 80.0, wristX = 650.0), CounterFixtures.edge)
        assertNull(c.trackingIssue); assertEquals(ExerciseCounter.Phase.outbound, c.phase)
    }
    @Test fun bentAngleWithoutTravelCannotReachEndpoint() {
        val c = pullUp()
        for (time in listOf(0.3, 0.45)) c.consume(CounterFixtures.pose(time, 80.0, verticalOffset = 220.0), CounterFixtures.edge)
        assertEquals(0, c.observedMovements); assertEquals(ExerciseCounter.Phase.outbound, c.phase)
    }
    @Test fun missingOrInvalidBarCannotCount() {
        for (edge in listOf(null, BarSegment(Point2D(0.0, 0.0), Point2D(0.0, 0.0)),
            BarSegment(Point2D(-1.0, 500.0), Point2D(900.0, 500.0)))) {
            val c = ExerciseCounter()
            for ((time, angle) in listOf(0.0 to 170.0, 0.15 to 170.0, 0.3 to 80.0, 0.45 to 80.0))
                c.consume(CounterFixtures.pose(time, angle), edge)
            assertEquals(0, c.observedMovements); assertEquals("barReferenceUnavailable", c.trackingIssue)
        }
    }
    @Test fun sourceTimeOffsetDoesNotChangeCount() {
        val c = ExerciseCounter(); CounterFixtures.feed(c, 10.0 to 170.0, 10.15 to 170.0, 10.3 to 80.0, 10.45 to 80.0)
        assertEquals(1, c.observedMovements); assertEquals(10.45, c.lastEvent?.sourceSeconds)
    }
    @Test fun resetClearsAllEvidenceRetainingExerciseAndSide() {
        val c = dip(ArmMeasurement.Side.right); CounterFixtures.arm(c)
        CounterFixtures.feed(c, 0.3 to 80.0, 0.45 to 80.0, 0.6 to 170.0, 0.75 to 170.0)
        c.reset()
        assertEquals(0, c.observedMovements); assertNull(c.lastEvent); assertTrue(c.events.isEmpty())
        assertEquals(ExerciseCounter.Exercise.dip, c.exercise); assertEquals(ArmMeasurement.Side.right, c.side)
        CounterFixtures.arm(c); assertEquals(ExerciseCounter.Phase.ready, c.phase)
    }
    @Test fun finishFreezesUntilReset() {
        val c = ExerciseCounter(); c.finish()
        CounterFixtures.feed(c, 0.0 to 170.0, 0.15 to 170.0, 0.3 to 80.0, 0.45 to 80.0)
        assertEquals(ExerciseCounter.Phase.finished, c.phase); assertEquals(0, c.observedMovements)
        c.reset(); CounterFixtures.arm(c); assertEquals(ExerciseCounter.Phase.ready, c.phase)
    }
    @Test fun historicalSummaryWithoutArmTrackingDecodes() {
        val c = ExerciseCounter()
        assertEquals("bothUsableArms", c.summary.armTracking)
        assertEquals("selectedGripWithAvailableArms", dip().summary.armTracking)
        val encoded = Json.encodeToString(c.summary)
        val historical = encoded.replace("\"armTracking\":\"bothUsableArms\",", "")
        val decoded = Json.decodeFromString<ExerciseCounter.Summary>(historical)
        assertNull(decoded.armTracking); assertEquals(0, decoded.observedMovements)
    }
}
