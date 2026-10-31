package dev.hanginthere.core

import kotlin.test.*

class SideViewPushUpMotionTests {
    private val ready = SideViewPushUpMotion.Camera.ready
    private var milliseconds = 0L
    private val motion = SideViewPushUpMotion()
    private val completed = linkedMapOf<Int, SideViewPushUpMotion.SetSummary>()
    private fun pose(lowered: Boolean = false, kneeling: Boolean = false,
        missing: Set<PoseJoint> = emptySet(), rightOnly: Boolean = false): PoseResult {
        val points = listOf(
            PoseJoint.leftShoulder to Point2D(160.0, if (lowered) 300.0 else 180.0),
            PoseJoint.leftElbow to Point2D(if (lowered) 260.0 else 160.0, if (lowered) 340.0 else 300.0),
            PoseJoint.leftWrist to Point2D(150.0, 420.0),
            PoseJoint.leftHip to Point2D(420.0, if (lowered) 280.0 else 220.0),
            PoseJoint.leftKnee to if (kneeling) Point2D(470.0, 380.0) else Point2D(620.0, if (lowered) 270.0 else 240.0),
            PoseJoint.leftAnkle to if (kneeling) Point2D(600.0, 300.0) else Point2D(820.0, 260.0))
        return PoseResult(PresentationTime(milliseconds, 1000), ImageSize(1000.0, 600.0),
            listOf(PoseObservation(points.filter { it.first !in missing }.map { (joint, p) ->
                Landmark(if (rightOnly) PoseJoint.valueOf(joint.name.replace("left", "right")) else joint, p, .95)
            })), "synthetic-side-profile", 1)
    }
    private fun feed(duration: Int = 300, camera: SideViewPushUpMotion.Camera = ready,
        transform: (PoseResult) -> PoseResult = { it }): SideViewPushUpMotion.Snapshot {
        var snapshot: SideViewPushUpMotion.Snapshot? = null
        repeat(duration/20) {
            snapshot = motion.observe(transform(pose()), camera)
            motion.latestCompletedSet?.let { completed[it.index] = it }
            milliseconds += 20
        }
        return checkNotNull(snapshot)
    }
    private fun top(duration: Int = 1800) = feed(duration)
    private fun lower(duration: Int = 300) = feed(duration) { pose(lowered = true) }

    @Test fun oneVisibleArmCountsTwoSetsAndKneelingSeparatesThem() {
        repeat(2) {
            val prepared = top()
            assertEquals(SideViewPushUpMotion.Phase.ready, prepared.phase)
            assertFalse(prepared.setStarted); assertEquals(0.0, prepared.progress)
            lower(); assertEquals(it+1, feed().cycles)
            feed(800) { pose(kneeling = true) }
        }
        assertEquals(listOf(1, 1), completed.values.map { it.reps.size })
        assertTrue(completed.values.all { it.endReason == "postureReleased" })
        assertTrue(completed.values.all { r -> r.reps.all { it.startedAt <= it.deepestAt && it.deepestAt < it.returnedAt } })
    }
    @Test fun defaultUprightSelectionStillRejectsSingleShoulderAndStaticPlankCannotStartASet() {
        val upright = BodyAthleteSelection()
        repeat(100) {
            upright.observe(pose()); milliseconds += 20
        }
        assertNull(upright.selectedPose?.people?.singleOrNull())
        top(4000)
        assertNull(motion.currentSet)
        assertNull(motion.latestCompletedSet)
        feed(1000) { pose(kneeling = true) }
        assertEquals(0, top().cycles)
    }
    @Test fun aShallowSetupBobAndAnIncompleteReturnDoNotCount() {
        top()
        feed { p -> p.copy(people = p.people.map { person -> person.copy(landmarks = person.landmarks.map {
            if (it.joint == PoseJoint.leftShoulder) it.copy(position = Point2D(160.0, 200.0)) else it
        }) }) }
        assertEquals(0, top().cycles)
        lower(); assertEquals(0, lower(1000).cycles)
        feed(800) { pose(kneeling = true) }
        assertEquals(0, completed.values.sumOf { it.reps.size })
    }
    @Test fun shortAnkleLossPreservesOnlyPhaseAndLongLossCancelsTheAttempt() {
        top(); lower()
        val unknown = feed(200) { pose(missing = setOf(PoseJoint.leftAnkle)) }
        assertNull(unknown.progress); assertFalse(unknown.observationUsable)
        assertEquals(0, unknown.cycles)
        assertEquals(1, feed().cycles)
        lower()
        feed(1200) { pose(missing = setOf(PoseJoint.leftAnkle)) }
        assertEquals(1, top().cycles)
        assertEquals(1, completed.values.single().reps.size)
        assertEquals("bodyTrackingLost", completed.values.single().endReason)
    }
    @Test fun losingTheNearArmCannotBorrowAnOppositeArmReturn() {
        top(); lower()
        feed(800) { p -> p.copy(people = listOf(PoseObservation(
            pose(missing = setOf(PoseJoint.leftElbow)).people.single().landmarks +
                pose(rightOnly = true).people.single().landmarks))) }
        assertEquals(0, top().cycles)
        assertEquals(0, completed.values.sumOf { it.reps.size })
    }
    @Test fun sameArmPreservesAssociationThroughBriefHipLossWithoutSupplyingABodyEndpoint() {
        val hipMissing = setOf(PoseJoint.leftHip)
        val noAcquisition = feed(2000) { pose(missing = hipMissing) }
        assertEquals(BodyAthleteAssociation.Status.acquiring, noAcquisition.association?.status)
        // Torso association may precede the first fully visible arm.
        feed(2000) { pose(missing = setOf(PoseJoint.leftElbow)) }
        top(); lower()
        val unknown = feed(500) { pose(missing = hipMissing) }
        assertEquals(BodyAthleteAssociation.Status.associated, unknown.association?.status)
        assertEquals("matchedVisibleArm", unknown.association?.reason)
        assertEquals(0, unknown.cycles); assertNull(unknown.progress)
        assertEquals(1, feed().cycles)
        lower()
        feed(1500) { pose(missing = hipMissing) }
        assertEquals(1, top().cycles)
        assertEquals(1, completed.values.single().reps.size)
    }
    @Test fun noCameraCreditAndSourceGapsCannotCompletePendingReps() {
        feed(1800, SideViewPushUpMotion.Camera.unavailable)
        assertNull(motion.currentSet)
        top(); lower()
        feed(camera = SideViewPushUpMotion.Camera.moved)
        assertEquals(0, top().cycles)
        lower(); milliseconds += 2000
        assertEquals(0, top().cycles)
        lower()
        val duplicate = pose().copy(timestamp = PresentationTime(milliseconds-20, 1000))
        motion.observe(duplicate, ready)
        assertEquals(0, top().cycles)
    }
    @Test fun frontPortraitAndDistantFramingProduceShortGuidanceAndCannotCount() {
        fun transform(p: PoseResult, f: (Point2D) -> Point2D) = p.copy(people = p.people.map { person ->
            person.copy(landmarks = person.landmarks.map { it.copy(position = f(it.position)) }) })
        val distant = feed(3000) { p -> transform(p) { Point2D(it.x*.4+300, it.y*.4+150) } }
        assertEquals(SideViewPushUpMotion.Cue.closer, distant.cue)
        assertFalse(distant.setStarted)
        val front = feed(3000) { p -> transform(p) { Point2D(500+(it.y-300)*.4, it.x*.5+50) } }
        assertEquals(SideViewPushUpMotion.Cue.sidePlank, front.cue)
        val portrait = feed { it.copy(imageSize = ImageSize(600.0, 1000.0)) }
        assertEquals(SideViewPushUpMotion.Cue.landscape, portrait.cue)
        assertTrue(portrait.title.length < 35)
        assertEquals(0, portrait.cycles)
    }
    @Test fun overlapContextChangesAndHandRelocationCannotCompletePendingRep() {
        top(); lower()
        feed(800) { it.copy(people = it.people + it.people) }
        assertEquals(0, top().cycles)
        lower()
        feed { it.copy(backend = "new-model") }
        assertEquals(0, top().cycles)
        lower()
        feed { p -> p.copy(people = p.people.map { person -> person.copy(landmarks = person.landmarks.map {
            if (it.joint == PoseJoint.leftWrist) it.copy(position = Point2D(350.0, 420.0)) else it
        }) }) }
        assertEquals(0, top().cycles)
    }
}
