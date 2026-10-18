package dev.hanginthere.core

import kotlin.test.*

/** A newly visible arm cannot inherit an unobserved starting endpoint. */
class DipArmVisibilityTests {
    private fun frame(time: Double, selected: Double, other: Double?, side: ArmMeasurement.Side, selectedElbowVisible: Boolean = true): PoseResult {
        val pose = CounterFixtures.bilateral(time, if (side == ArmMeasurement.Side.left) selected else other,
            if (side == ArmMeasurement.Side.right) selected else other, exercise = ExerciseCounter.Exercise.dip)
        return if (selectedElbowVisible) pose else pose.copy(people = pose.people.map { person ->
            person.copy(landmarks = person.landmarks.map { if (it.joint == side.joints[1]) it.copy(confidence = .1) else it })
        })
    }
    private fun rail(side: ArmMeasurement.Side): BarSegment {
        val x = if (side == ArmMeasurement.Side.left) 300.0 else 700.0
        return BarSegment(Point2D(x, 500.0), Point2D(x, 620.0))
    }
    @Test fun newlyVisibleArmRevokesUnwitnessedBaselineAndAllowsFreshBilateralRecovery() {
        for (side in ArmMeasurement.Side.entries) for (scenario in listOf("outbound", "return", "readyProjected", "readyExtended", "elbowOutage")) {
            val c = ExerciseCounter(ExerciseCounter.Exercise.dip, side)
            val top = if (scenario == "readyProjected") 140.0 else 170.0
            val samples = when (scenario) {
                "return" -> listOf(Triple(.3, 80.0, null), Triple(.45, 80.0, null), Triple(.6, 170.0, 80.0), Triple(.75, 170.0, 80.0))
                "readyProjected", "readyExtended" -> listOf(Triple(.2, top, 170.0), Triple(.35, 110.0, 170.0), Triple(.5, 80.0, 170.0), Triple(.65, top, 170.0), Triple(.8, top, 170.0))
                else -> listOf(Triple(.3, 110.0, null), Triple(.45, 80.0, 170.0), Triple(.6, 80.0, 170.0), Triple(.75, 170.0, 170.0), Triple(.9, 170.0, 170.0))
            }
            for (time in listOf(0.0, .15)) c.consume(frame(time, top, null, side), rail(side))
            for ((time, selected, other) in samples) {
                c.consume(frame(time, selected, other, side, scenario != "elbowOutage" || time != .45), rail(side))
                assertEquals(0, c.observedMovements, "$side/$scenario at $time")
            }
            for ((time, angle) in listOf(1.2 to 170.0, 1.35 to 170.0, 1.5 to 80.0, 1.65 to 80.0, 1.8 to 170.0, 1.95 to 170.0))
                c.consume(frame(time, angle, angle, side), rail(side))
            assertEquals(1, c.observedMovements, "$side/$scenario recovery")
            assertEquals(ExerciseCounter.Phase.ready, c.phase)
        }
    }
    @Test fun newlyVisibleArmDuringInitialDwellNeedsItsOwnFreshSupportInterval() {
        for (side in ArmMeasurement.Side.entries) {
            val c = ExerciseCounter(ExerciseCounter.Exercise.dip, side)
            for ((time, other) in listOf(0.0 to null, .1 to null, .15 to 170.0, .25 to 170.0)) {
                c.consume(frame(time, 170.0, other, side), rail(side))
                assertEquals(ExerciseCounter.Phase.seekingStart, c.phase)
            }
            c.consume(frame(.3, 170.0, 170.0, side), rail(side))
            assertEquals(ExerciseCounter.Phase.ready, c.phase)
        }
    }
    @Test fun previouslyWitnessedArmCanBeBrieflyHiddenWithoutDiscardingItsBaseline() {
        for (side in ArmMeasurement.Side.entries) {
            val c = ExerciseCounter(ExerciseCounter.Exercise.dip, side)
            for ((time, selected, other) in listOf(Triple(0.0, 170.0, 170.0), Triple(.15, 170.0, 170.0),
                    Triple(.3, 110.0, null), Triple(.45, 80.0, null), Triple(.6, 80.0, 80.0),
                    Triple(.75, 170.0, null), Triple(.9, 170.0, 170.0)))
                c.consume(frame(time, selected, other, side), rail(side))
            assertEquals(1, c.observedMovements)
            assertEquals(0, c.interruptedAttempts)
        }
    }
    @Test fun firstActualBilateralReturnCanSeedFreshDwellButCannotCompleteTheOldAttempt() {
        for (side in ArmMeasurement.Side.entries) {
            val c = ExerciseCounter(ExerciseCounter.Exercise.dip, side)
            for ((time, selected, other) in listOf(Triple(0.0,170.0,null), Triple(.15,170.0,null),
                    Triple(.3,80.0,null), Triple(.45,80.0,null), Triple(.6,170.0,170.0), Triple(.75,170.0,170.0)))
                c.consume(frame(time,selected,other,side),rail(side))
            assertEquals(0,c.observedMovements)
            assertEquals(1,c.interruptedAttempts)
            assertEquals(ExerciseCounter.Phase.ready,c.phase)
            for ((time, angle) in listOf(.9 to 80.0, 1.05 to 80.0, 1.2 to 170.0, 1.35 to 170.0))
                c.consume(frame(time,angle,angle,side),rail(side))
            assertEquals(1,c.observedMovements)
        }
    }
    @Test fun newBentArmDuringInitialAcquisitionMustFirstEstablishActualUpperSupport() {
        for (side in ArmMeasurement.Side.entries) {
            val c = ExerciseCounter(ExerciseCounter.Exercise.dip, side)
            for ((time, selected, other) in listOf(Triple(0.0,170.0,null), Triple(.05,170.0,80.0),
                    Triple(.2,170.0,80.0), Triple(.35,80.0,80.0), Triple(.5,80.0,80.0), Triple(.65,170.0,170.0))) {
                c.consume(frame(time,selected,other,side),rail(side))
                assertEquals(ExerciseCounter.Phase.seekingStart,c.phase)
                assertEquals(0,c.observedMovements)
            }
            c.consume(frame(.8,170.0,170.0,side),rail(side))
            assertEquals(ExerciseCounter.Phase.ready,c.phase)
            assertEquals(0,c.observedMovements)
            for ((time, angle) in listOf(.95 to 80.0, 1.1 to 80.0, 1.25 to 170.0, 1.4 to 170.0))
                c.consume(frame(time,angle,angle,side),rail(side))
            assertEquals(1,c.observedMovements)
        }
    }
}
