package dev.hanginthere.core

import kotlin.test.*
import kotlinx.serialization.encodeToString

/** Feedback must describe the missing evidence without changing its eligibility. */
class BilateralDipObservationGuidanceTests {
    private val support by lazy { BilateralGripFixtures.curvedReference() }
    private fun observe(pose: PoseResult = BilateralGripFixtures.frame(1000),
        reference: CurvedDipRailAcquisition.Reference? = null,
        camera: BilateralDipMotion.Camera = BilateralDipMotion.Camera.unavailable) =
        BilateralDipWorkout("feedback").observe(pose, reference, camera, false)

    @Test fun missingAndCompetingBodyObservationsReuseSharedFramingAdvice() {
        val pose = BilateralGripFixtures.frame(1000)
        for (people in listOf(emptyList(), pose.people + pose.people)) {
            val input = pose.copy(people = people)
            val snapshot = observe(input)
            assertEquals(WorkoutFramingGuidance.message(input), snapshot.observationGuidance)
            assertNotNull(snapshot.observationGuidance)
            assertEquals(0, snapshot.motion.imageCycles)
            assertEquals(0, snapshot.analyzedFrames)
        }
    }

    @Test fun partialBodiesAreNotMergedToSupplyAnAthlete() {
        val pose = BilateralGripFixtures.frame(1000)
        val halves = ArmMeasurement.Side.entries.map { side ->
            pose.people.single().copy(landmarks = pose.people.single().landmarks.filter { it.joint in side.joints })
        }
        val snapshot = observe(pose.copy(people = halves))
        assertEquals("Keep one athlete in view", snapshot.observationGuidance)
        assertEquals(BilateralDipMotion.Phase.seekingTop, snapshot.motion.phase)
        assertEquals(0, snapshot.usableFrames)
    }

    @Test fun visibleBodyWristsDoNotReplaceIndependentHandObservations() {
        val pose = BilateralGripFixtures.frame(1000).copy(gripObservation = null)
        assertNull(WorkoutFramingGuidance.message(pose))
        assertEquals("Hand tracking unavailable · show both grips clearly", observe(pose).observationGuidance)
    }

    @Test fun missingRailsDoNotInventACameraFailure() {
        val snapshot = observe()
        assertEquals("Looking for dip rails · keep grips and rails visible", snapshot.observationGuidance)
        assertEquals("cameraUnavailable", snapshot.motion.availability)
        assertEquals(0, snapshot.motion.imageCycles)
    }

    @Test fun actualRetainedSupportStillRequiresCurrentCameraEvidence() {
        assertEquals("Camera stability unavailable · keep the phone steady",
            observe(reference = support).observationGuidance)
    }

    @Test fun invalidHandContextIsNotReportedAsReady() {
        val pose = BilateralGripFixtures.frame(1000)
        val stale = pose.copy(gripObservation = pose.gripObservation!!.copy(seconds = .9))
        assertEquals("Hand tracking unavailable · show both grips clearly",
            observe(stale, support, BilateralDipMotion.Camera.ready).observationGuidance)
    }

    @Test fun foreignOrFutureSupportDoesNotBecomeACameraOnlyFailure() {
        val pose = BilateralGripFixtures.frame(1000)
        for ((sourcePose, context) in listOf(
            BilateralGripFixtures.frame(2000) to support.context,
            pose to support.context.copy(bodyBackend = "foreign-body"))) {
            val reference = CurvedDipRailAcquisition.Reference.create(listOf(support.left, support.right),
                support.hands, sourcePose, context, 0)
            assertEquals("Support tracking unavailable · show both grips and rails",
                observe(pose, reference).observationGuidance)
        }
    }

    @Test fun measurableEdgeAdviceDoesNotMakeMotionUnavailable() {
        fun edgePose(ticks: Long, drop: Double = 0.0): PoseResult {
            val pose = BilateralGripFixtures.frame(ticks, drop, drop)
            fun point(joint: String, position: Point2D) = when (joint) {
                "leftShoulder" -> Point2D(15.0, position.y)
                "leftElbow" -> Point2D(107.5, position.y)
                else -> position
            }
            return pose.copy(people = pose.people.map { person -> person.copy(landmarks = person.landmarks.map {
                it.copy(position = point(it.joint.name, it.position))
            }) }, gripObservation = pose.gripObservation!!.copy(trackedBody = pose.gripObservation.trackedBody.map {
                it.copy(position = point(it.joint.name, it.position))
            }))
        }
        val workout = BilateralDipWorkout("edge")
        workout.observe(edgePose(1000), support, BilateralDipMotion.Camera.ready, false)
        workout.observe(edgePose(1200), support, BilateralDipMotion.Camera.ready, false)
        val snapshot = workout.observe(edgePose(1400, 100.0), support, BilateralDipMotion.Camera.ready, false)
        assertEquals("available", snapshot.motion.availability)
        assertEquals(BilateralDipMotion.Phase.outbound, snapshot.motion.phase)
        assertTrue(snapshot.motion.progress > 0)
        assertEquals("Move phone back · leave room around arms and hands", snapshot.observationGuidance)
    }

    @Test fun missingElbowsHaveActionableAdviceEvenWithCurrentShouldersAndHands() {
        val pose = BilateralGripFixtures.frame(1000, elbows = false)
        val snapshot = observe(pose, support, BilateralDipMotion.Camera.ready)
        assertEquals("waitingForMeasuredTop", snapshot.motion.availability)
        assertEquals(WorkoutFramingGuidance.message(pose), snapshot.observationGuidance)
        assertNotNull(snapshot.observationGuidance)
        assertEquals(0, snapshot.motion.imageCycles)
    }

    @Test fun sourceAndCameraInvalidationTakePrecedenceOverFramingAdvice() {
        val workout = BilateralDipWorkout("source")
        val pose = BilateralGripFixtures.frame(1000)
        workout.observe(pose, support, BilateralDipMotion.Camera.ready, false)
        val regressed = workout.observe(pose.copy(people = emptyList()), support, BilateralDipMotion.Camera.ready, false)
        assertEquals("Video timing interrupted · restart playback", regressed.observationGuidance)
        assertEquals("Camera view changed · find both rail grips again",
            observe(pose.copy(people = emptyList()), support, BilateralDipMotion.Camera.moved).observationGuidance)
    }

    @Test fun feedbackCannotModifyMotionEventsOrCompletedAnalytics() {
        val workout = BilateralDipWorkout("invariance")
        val motion = BilateralDipMotion()
        for ((i, drop) in listOf(0.0, 0.0, 100.0, 120.0, 0.0, 0.0).withIndex()) {
            val pose = BilateralGripFixtures.frame(1000L + i * 200, drop, drop)
            val snapshot = workout.observe(pose, support, BilateralDipMotion.Camera.ready, false)
            assertEquals(motion.observe(pose, support, BilateralDipMotion.Camera.ready), snapshot.motion)
            assertNull(snapshot.observationGuidance)
        }
        workout.finish("sourceEnded")
        assertEquals(1, workout.record().completedCount)
        assertEquals(0, WorkoutFeedback.summary(workout.record()).practiceAttempts)
    }

    @Test fun jsonAddsGuidanceWithoutRequiringItInOlderSnapshots() {
        val snapshot = observe()
        val json = WorkoutRecordCodec.json.encodeToString(snapshot)
        assertEquals(snapshot, WorkoutRecordCodec.json.decodeFromString<BilateralDipWorkout.Snapshot>(json))
        val old = """{"motion":{"phase":"seekingTop","imageCycles":0,"partialAttempts":0,"interruptedAttempts":0,"availability":"cameraUnavailable","progress":0.0},"analysisRevision":0,"analyzedFrames":0,"usableFrames":0,"endReason":null}"""
        assertNull(WorkoutRecordCodec.json.decodeFromString<BilateralDipWorkout.Snapshot>(old).observationGuidance)
    }
}
