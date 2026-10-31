package dev.hanginthere.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class SideViewPushUpWorkoutTests {
    private val epoch = 1_790_000_000_000L
    private val workout = SideViewPushUpWorkout("pushup-test", epoch)
    private var ticks = 0L
    private val writes = mutableListOf<SideViewPushUpWorkout.Write>()
    private fun drain() { while (workout.nextWrite() != null) {
        writes += checkNotNull(workout.nextWrite()); workout.acknowledgeWrite()
    } }
    private fun feed(count: Int, lowered: Boolean = false, missingArm: Boolean = false,
        camera: SideViewPushUpMotion.Camera = SideViewPushUpMotion.Camera.ready) {
        repeat(count) {
            val points = listOf(
                PoseJoint.leftShoulder to Point2D(160.0, if (lowered) 300.0 else 180.0),
                PoseJoint.leftElbow to Point2D(if (lowered) 260.0 else 160.0, if (lowered) 340.0 else 300.0),
                PoseJoint.leftWrist to Point2D(150.0, 420.0),
                PoseJoint.leftHip to Point2D(420.0, if (lowered) 280.0 else 220.0),
                PoseJoint.leftKnee to Point2D(620.0, if (lowered) 270.0 else 240.0),
                PoseJoint.leftAnkle to Point2D(820.0, 260.0))
            workout.observe(PoseResult(PresentationTime(ticks, 1000), ImageSize(1000.0, 600.0),
                listOf(PoseObservation(points.filter { !missingArm || it.first != PoseJoint.leftElbow }
                    .map { Landmark(it.first, it.second, .95) })), "authored-side", 1), camera)
            ticks += 20; drain()
        }
    }
    private fun rep() { feed(20, lowered = true); feed(20) }
    private fun finish(reason: String = "manual"): WorkoutSetRecord {
        workout.finish(reason); drain()
        return writes.last { it.finalized }.record
    }
    @Test fun setupIsPrivateAndCompletedTimingSurvivesStorageRecoveryAndHistory() {
        feed(100); assertTrue(writes.isEmpty())
        rep(); val record = finish()
        val r = record.completedReps.single()
        assertEquals(ExerciseCounter.Exercise.pushUp, record.exercise)
        assertEquals(r.extremeAt!!-r.startedAt, r.outboundSeconds)
        assertEquals(r.returnedAt!!-r.extremeAt, r.returnSeconds)
        assertEquals(epoch+(record.startedAt!!*1000).toLong(), record.trackedAtEpochMilliseconds)
        assertNull(record.activity); assertNull(record.grip)
        assertNull(r.right.maximum); assertNull(r.right.coverage)
        assertEquals(1.0, r.durationCoverage!!, 1e-9)
        assertTrue(WorkoutEvidence.assess(r).angleComparisonEligible)
        assertFalse(WorkoutEvidence.assess(r).explanation.contains("Both-arm"))
        var draft: WorkoutSetRecord? = null
        writes.filter { !it.finalized }.forEach { draft = WorkoutRecordCodec.mergeDraft(draft, it.record) }
        val recovered = WorkoutRecordCodec.recover(checkNotNull(draft))
        assertEquals(1, recovered.completedCount)
        val library = WorkoutLibrary.register(WorkoutLibraryState(), listOf(record))
        val reloaded = WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(WorkoutRecordCodec.json.encodeToString(library))
        WorkoutLibrary.validate(reloaded)
        val digest = WorkoutJourney.set(reloaded.entries.single().measured)
        assertEquals(1, digest.completedReps); assertNotNull(digest.outwardSeconds); assertNotNull(digest.returnSeconds)
        assertEquals(r.returnSeconds, WorkoutSetPresentation.tempo(record).reps.single().lifting)
        assertEquals(listOf("Start", "Bottom", "Return"), WorkoutSetPresentation.keyframes(record, 0.0).map { it.phase })
    }
    @Test fun interruptedDescentCannotBecomeACompletedRepAndTheNextSetHasANewIdentity() {
        feed(100); rep(); feed(20, lowered = true)
        val first = finish("sourceEnded")
        assertEquals(1, first.completedCount)
        assertEquals(2, first.reps.size); assertNull(first.reps.last().returnedAt)
        feed(100); rep(); val second = finish()
        assertEquals(1, second.completedCount); assertNotEquals(first.id, second.id)
        assertEquals(1, second.reps.single().ordinal)
        WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(), listOf(first, second)))
    }
    @Test fun trackingGapsDoNotAddObservedTimeOrBorrowTheHiddenArm() {
        feed(100); feed(10, lowered = true); feed(10, lowered = true, missingArm = true)
        feed(20)
        val record = finish()
        val rep = record.completedReps.single()
        assertTrue(rep.durationCoverage!! < 1.0)
        assertTrue(rep.samples.any { !it.usable })
        assertNull(rep.right.maximum)
        assertEquals(1, WorkoutRecordCodec.recover(record).completedCount)
    }
    @Test fun finishingBeforeTheFirstBottomDiscardsPrivatePreparation() {
        feed(100); feed(3, lowered = true)
        workout.finish("manual"); drain()
        assertTrue(writes.isEmpty())
        val restart = ticks/1000.0
        feed(100); rep()
        val record = finish()
        assertEquals(1, record.completedCount)
        assertTrue(record.startedAt!! >= restart)
    }
    @Test fun noCameraOrNoReturnProducesNoCompletedCount() {
        feed(100, camera = SideViewPushUpMotion.Camera.unavailable)
        feed(20, lowered = true, camera = SideViewPushUpMotion.Camera.unavailable)
        assertTrue(writes.isEmpty())
        feed(100); feed(20, lowered = true)
        assertEquals(0, finish().completedCount)
        assertFalse(WorkoutLifecycle.supportsBoundaryPolicy(ExerciseCounter.Exercise.pushUp,
            SideViewPushUpWorkout.countMethod, 1, SideViewPushUpMotion.policyVersion))
    }
}
