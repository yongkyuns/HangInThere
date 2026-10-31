package dev.hanginthere.core

import kotlin.test.*

class FrontViewPushUpTests {
    private var ticks = 0L
    private val workout = SideViewPushUpWorkout("front-test", allowFrontView = true)
    private val writes = mutableListOf<SideViewPushUpWorkout.Write>()
    private fun pose(lowered: Boolean, missing: Set<PoseJoint>): PoseResult {
        val points = listOf(
            PoseJoint.leftShoulder to Point2D(if (lowered) 550.0 else 540.0, if (lowered) 320.0 else 160.0),
            PoseJoint.rightShoulder to Point2D(if (lowered) 300.0 else 340.0, if (lowered) 320.0 else 160.0),
            PoseJoint.leftElbow to Point2D(if (lowered) 690.0 else 610.0, if (lowered) 280.0 else 305.0),
            PoseJoint.rightElbow to Point2D(if (lowered) 210.0 else 280.0, if (lowered) 280.0 else 305.0),
            PoseJoint.leftWrist to Point2D(680.0, 450.0), PoseJoint.rightWrist to Point2D(220.0, 450.0),
            PoseJoint.leftHip to Point2D(520.0, 300.0), PoseJoint.rightHip to Point2D(420.0, 300.0))
        return PoseResult(PresentationTime(ticks, 1000), ImageSize(1000.0, 600.0),
            listOf(PoseObservation(points.filter { it.first !in missing }.map { Landmark(it.first, it.second, .95) })),
            "authored-front", 1)
    }
    private fun feed(count: Int, lowered: Boolean = false, missing: Set<PoseJoint> = emptySet(),
        camera: SideViewPushUpMotion.Camera = SideViewPushUpMotion.Camera.ready,
        transform: (PoseResult) -> PoseResult = { it }): SideViewPushUpMotion.Snapshot {
        var state: SideViewPushUpMotion.Snapshot? = null
        repeat(count) {
            state = workout.observe(transform(pose(lowered, missing)), camera)
            while (workout.nextWrite() != null) { writes += workout.nextWrite()!!; workout.acknowledgeWrite() }
            ticks += 20
        }
        return state!!
    }
    private fun finish(): WorkoutSetRecord {
        workout.finish("manual")
        while (workout.nextWrite() != null) { writes += workout.nextWrite()!!; workout.acknowledgeWrite() }
        return writes.last { it.finalized }.record
    }
    @Test fun frontCountsWithHiddenLegsAndMidRepHipLossAndKeepsItsOwnHistoryMethod() {
        val ready = feed(100)
        assertEquals(SideViewPushUpMotion.View.front, ready.view)
        assertEquals(SideViewPushUpMotion.Phase.ready, ready.phase)
        assertTrue(writes.isEmpty())
        val hips = setOf(PoseJoint.leftHip, PoseJoint.rightHip)
        feed(20, lowered = true, missing = hips)
        assertEquals(1, feed(20, missing = hips).cycles)
        val record = finish()
        assertEquals("frontViewPushUp", record.countMethod)
        assertEquals(1, record.countPolicyVersion)
        assertEquals(1, record.completedCount)
        assertEquals("unverified", record.reps.single().formVerification)
        assertNull(record.reps.single().right.maximum) // Angle metrics use the actual left arm, not a synthetic average.
        assertTrue(WorkoutEvidence.assess(record.reps.single()).explanation.contains("front-view"))
        assertEquals(listOf("Start", "Bottom", "Return"), WorkoutSetPresentation.keyframes(record, 0.0).map { it.phase })
        WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(), listOf(record)))
        assertEquals(1, WorkoutRecordCodec.recover(record).completedCount)
        assertTrue(WorkoutCountingMethod.isSupported(record.exercise, record.countMethod, 1))
        assertFalse(WorkoutCountingMethod.isSupported(record.exercise, record.countMethod, 2))
    }
    @Test fun noInitialPelvisNoCameraAndStaticPlanksCannotCreateHistory() {
        feed(100, missing = setOf(PoseJoint.leftHip, PoseJoint.rightHip))
        feed(20, lowered = true)
        assertEquals(0, feed(100, camera = SideViewPushUpMotion.Camera.unavailable).cycles)
        assertEquals(0, feed(200).cycles)
        assertTrue(writes.isEmpty())
    }
    @Test fun bothCurrentArmsAreRequiredAndAHiddenArmCannotBridgeARep() {
        feed(100); feed(20, lowered = true)
        val blocked = feed(25, missing = setOf(PoseJoint.rightElbow))
        assertNull(blocked.progress); assertFalse(blocked.observationUsable)
        assertEquals(0, feed(100).cycles)
        assertEquals(0, finish().completedCount)
    }
    @Test fun bentElbowsWithoutShoulderTravelAndMovingHandsDoNotCount() {
        feed(100)
        feed(30, lowered = true) { p -> p.copy(people = p.people.map { person -> person.copy(
            landmarks = person.landmarks.map { l -> if (l.joint in listOf(PoseJoint.leftShoulder, PoseJoint.rightShoulder))
                l.copy(position = l.position.copy(y = 190.0)) else l }) }) }
        assertEquals(0, feed(30).cycles)
        feed(20, lowered = true)
        feed(10) { p -> p.copy(people = p.people.map { person -> person.copy(landmarks = person.landmarks.map { l ->
            if (l.joint == PoseJoint.rightWrist) l.copy(position = l.position.copy(x = 60.0)) else l
        }) }) }
        assertEquals(0, feed(100).cycles)
        assertEquals(0, finish().completedCount)
    }
    @Test fun cameraMovementClockGapsAndOverlappingPeopleCancelThePendingReturn() {
        for (fault in 0..3) {
            val f = FrontViewPushUpTests()
            f.feed(100); f.feed(20, lowered = true)
            when (fault) {
                0 -> f.feed(5, camera = SideViewPushUpMotion.Camera.moved)
                1 -> { f.ticks += 1000; f.feed(5) }
                2 -> f.feed(25) { it.copy(people = it.people+it.people) }
                3 -> f.feed(5) { it.copy(backend = "new-source") }
            }
            assertEquals(0, f.feed(100).cycles)
            assertEquals(0, f.finish().completedCount)
        }
    }
}
