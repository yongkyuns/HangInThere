package dev.hanginthere.core

import kotlin.math.*
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class BilateralDipMotionTests {
    private fun frame(index: Int, left: Double = 0.0, right: Double = left) =
        BilateralGripFixtures.frame(1000L + index * 200L, left, right)
    private val reference by lazy {
        val pose = BilateralGripFixtures.frame()
        val search = CurvedDipRailAcquisition().search(pose)!!
        val rails = search.hands.mapIndexed { side, hand ->
            val contours = listOf(60.0, 68.0).map { radius -> RailContour((0..60).map {
                val t = -.6 + it * 1.2 / 60
                Point2D(hand.x - 50 + radius * cos(t), 700 + radius * sin(t))
            }, true) }
            ObservedRailFitter.candidates(contours, search.regions[side], pose.imageSize, hand).maxBy { it.supportArcPixels }
        }
        CurvedDipRailAcquisition.Reference.create(rails, search.hands, pose,
            CurvedDipRailAcquisition.Context("authored-body", 1, "authored-hand", "authored-hand-backend"), 0)
    }
    private fun observe(engine: BilateralDipMotion, pose: PoseResult, camera: BilateralDipMotion.Camera = BilateralDipMotion.Camera.ready,
                        support: CurvedDipRailAcquisition.Reference? = reference) = engine.observe(pose, support, camera)
    private fun run(drops: List<Double?>, mutate: (Int, PoseResult) -> PoseResult = { _, p -> p }): List<BilateralDipMotion.Snapshot> {
        val engine = BilateralDipMotion()
        return drops.mapIndexed { i, drop -> observe(engine,
            mutate(i, frame(i, drop ?: 0.0).let { if (drop == null) it.copy(gripObservation = null) else it })) }
    }
    private val cycle = listOf(0.0, 0.0, 100.0, 100.0, 0.0, 0.0)
    private fun bent(pose: PoseResult, side: PoseJoint = PoseJoint.rightElbow) = pose.copy(people = pose.people.map { person ->
        person.copy(landmarks = person.landmarks.map { point ->
            if (point.joint == side) point.copy(position = Point2D(point.position.x + 100, point.position.y)) else point
        })
    })

    @Test fun sustainedBilateralTravelAndMeasuredReturnProduceOnlyAnImageCycle() {
        val all = run(cycle); val last = all.last()
        assertEquals(1, last.imageCycles); assertEquals(0, last.partialAttempts)
        assertEquals(BilateralDipMotion.Outcome.imageCycle, last.event?.outcome)
        assertEquals("bilateralImageCycleDepthFormAndLoadUnverified", last.event?.reason)
        assertEquals(frame(2).timestamp, last.event?.departureTime)
        assertEquals(frame(2).timestamp, last.event?.bottomTime)
        assertEquals(frame(4).timestamp, last.event?.firstReturnTime)
        assertEquals(frame(5).timestamp, last.event?.sourceTime)
        assertEquals(1.0, last.progress)
        assertTrue(all.dropLast(1).all { it.imageCycles == 0 && it.progress < 1 })
    }
    @Test fun sustainedTopAndBottomDoNotDuplicateCounts() {
        val all = run(cycle + listOf(0.0, 0.0, 0.0))
        assertEquals(1, all.last().imageCycles)
        assertEquals(1, all.count { it.event?.outcome == BilateralDipMotion.Outcome.imageCycle })
        assertEquals(2, run(cycle + cycle.drop(2)).last().imageCycles)
    }
    @Test fun unfinishedAndShallowAttemptsNeverBecomeCycles() {
        assertEquals(0, run(listOf(0.0, 0.0, 100.0, 100.0)).last().imageCycles)
        val shallow = run(listOf(0.0, 0.0, 50.0, 50.0, 0.0, 0.0)).last()
        assertEquals(0, shallow.imageCycles); assertEquals(1, shallow.partialAttempts)
        assertEquals(BilateralDipMotion.Outcome.partial, shallow.event?.outcome)
    }
    @Test fun oneArmCannotSupplyTheOtherArmsTravelOrReturnProgress() {
        val outbound = run(cycle) { i, p -> if (i in 2..3) frame(i, 100.0, 0.0) else p }
        assertEquals(0, outbound.last().imageCycles)
        val returning = run(cycle) { i, p -> if (i >= 4) frame(i, 0.0, 100.0) else p }
        assertEquals(0, returning.last().imageCycles)
        assertEquals(.45, returning.last().progress, .000001)
    }
    @Test fun bothMeasuredStartingArmsAreRequired() {
        assertEquals(0, run(cycle) { _, p -> bent(p) }.last().imageCycles)
        assertEquals(0, run(cycle) { i, p -> if (i < 2) p.copy(people = p.people.map { person ->
            person.copy(landmarks = person.landmarks.filter { it.joint != PoseJoint.rightElbow })
        }) else p }.last().imageCycles)
    }
    @Test fun aVisibleFlexedReturnCannotReuseTheStartingAngle() {
        val all = run(cycle) { i, p -> if (i >= 4) bent(p) else p }
        assertEquals(0, all.last().imageCycles)
    }
    @Test fun missingElbowsMayMeasureDisplacementButCannotAcquireOrCompleteReturn() {
        val all = run(cycle) { i, p -> if (i >= 4) p.copy(people = p.people.map { person ->
            person.copy(landmarks = person.landmarks.filter { it.joint != PoseJoint.rightElbow })
        }) else p }
        assertEquals(0, all.last().imageCycles)
    }
    @Test fun aBriefReturnFollowedByAnotherDescentCannotMergeAttempts() {
        val all = run(listOf(0.0, 0.0, 100.0, 100.0, 0.0, 100.0, 100.0, 0.0, 0.0))
        assertEquals(0, all.last().imageCycles)
        assertEquals(1, all.last().interruptedAttempts)
        assertEquals("returnNotSustained", all[5].event?.reason)
    }
    @Test fun anAsymmetricReturnAndFreshDescentCannotMergeIntoAnotherArmsCycle() {
        val all = run(listOf(0.0, 0.0, 100.0, 100.0, 0.0, 100.0, 0.0, 0.0)) { i, p ->
            if (i == 4) frame(i, 0.0, 100.0) else p
        }
        assertEquals("returnNotSustained", all[5].event?.reason)
        assertEquals(0, all.last().imageCycles)
    }
    @Test fun movingCameraRequiresANewSupportObservedAfterTheMovement() {
        val engine = BilateralDipMotion()
        observe(engine, frame(0), BilateralDipMotion.Camera.moved)
        val later = cycle.mapIndexed { i, d -> observe(engine, frame(i + 1, d)) }
        assertEquals(0, later.last().imageCycles)
        assertEquals("supportReacquisitionRequired", later.first().availability)
    }
    @Test fun missingReturnAnglesStillProvideNegativeEvidenceAgainstMergedAttempts() {
        val all = run(listOf(0.0, 0.0, 100.0, 100.0, 0.0, 100.0, 100.0, 0.0, 0.0)) { i, p ->
            if (i == 4) p.copy(people = p.people.map { person -> person.copy(landmarks = person.landmarks.filter {
                it.joint != PoseJoint.rightElbow
            }) }) else p
        }
        assertEquals("returnNotSustained", all[5].event?.reason)
        assertEquals(0, all.last().imageCycles)
    }
    @Test fun missingBottomObservationsCannotDonateDwell() {
        assertEquals(0, run(listOf(0.0, 0.0, 100.0, null, 0.0, 0.0)).last().imageCycles)
    }
    @Test fun sourceRegressionNeverLowersTheHighWaterClock() {
        val engine = BilateralDipMotion()
        for (i in 0..3) observe(engine, frame(i, if (i > 1) 100.0 else 0.0))
        assertEquals("sourceRegression", observe(engine, frame(3, 100.0)).availability)
        assertEquals("sourceRegression", observe(engine, frame(2)).availability)
        val recovered = observe(engine, frame(4))
        assertEquals(0, recovered.imageCycles); assertEquals(1, recovered.interruptedAttempts)
    }
    @Test fun anOutageOrMovedCameraInterruptsOnlyOnce() {
        for (camera in listOf(BilateralDipMotion.Camera.moved, BilateralDipMotion.Camera.unavailable)) {
            val engine = BilateralDipMotion()
            for (i in 0..3) observe(engine, frame(i, if (i > 1) 100.0 else 0.0))
            val late = frame(9)
            observe(engine, late, camera)
            val next = observe(engine, frame(10), camera)
            assertEquals(0, next.imageCycles); assertEquals(1, next.interruptedAttempts)
        }
    }
    @Test fun cameraUnavailableAndMissingSupportsNeverMeanReady() {
        for (camera in listOf(BilateralDipMotion.Camera.unavailable, BilateralDipMotion.Camera.moved)) {
            val engine = BilateralDipMotion()
            cycle.forEachIndexed { i, d -> assertEquals(0, observe(engine, frame(i, d), camera).imageCycles) }
        }
        val engine = BilateralDipMotion()
        cycle.forEachIndexed { i, d -> assertEquals(0, observe(engine, frame(i, d), support = null).imageCycles) }
    }
    @Test fun exactSourceDwellIsRequiredAtAllThreeEndpoints() {
        val engine = BilateralDipMotion()
        fun at(ticks: Long, drop: Double = 0.0) = observe(engine, BilateralGripFixtures.frame(ticks, drop, drop))
        at(1000); assertEquals(BilateralDipMotion.Phase.seekingTop, at(1119).phase)
        assertEquals(BilateralDipMotion.Phase.ready, at(1120).phase)
        at(1240, 100.0); assertEquals(BilateralDipMotion.Phase.outbound, at(1359, 100.0).phase)
        assertEquals(BilateralDipMotion.Phase.returning, at(1360, 100.0).phase)
        at(1480); assertEquals(0, at(1599).imageCycles)
        assertEquals(1, at(1600).imageCycles)
    }
    @Test fun clocksNearLongMaxValueDoNotCollapseToDoubleTimestampOrdering() {
        val engine = BilateralDipMotion()
        val offset = Long.MAX_VALUE - 4000
        val all = cycle.mapIndexed { i, d -> observe(engine, BilateralGripFixtures.frame(offset + i * 200L, d, d)) }
        assertEquals(1, all.last().imageCycles)
    }
    @Test fun genuinelyNewSupportAfterMovementCanAcquireAnotherTop() {
        val engine = BilateralDipMotion()
        observe(engine, frame(0), BilateralDipMotion.Camera.moved)
        val acquired = BilateralGripFixtures.frame(3000)
        val fresh = CurvedDipRailAcquisition.Reference.create(listOf(reference.left, reference.right),
            reference.hands, acquired, reference.context, 1)
        val all = cycle.mapIndexed { i, d -> observe(engine, BilateralGripFixtures.frame(4000L + i * 200, d, d), support = fresh) }
        assertEquals(1, all.last().imageCycles)
    }
    @Test fun sourceResetAllowsANewClockAndClearsOldCounts() {
        val engine = BilateralDipMotion()
        cycle.forEachIndexed { i, d -> observe(engine, frame(i, d)) }
        engine.resetSource()
        val all = cycle.mapIndexed { i, d -> observe(engine, frame(i, d)) }
        assertEquals(1, all.last().imageCycles)
    }
    @Test fun staleHandsAndModelChangesCannotCompleteACycle() {
        for (change in listOf<(PoseResult) -> PoseResult>(
            { p -> p.copy(gripObservation = p.gripObservation!!.copy(seconds = 0.0)) },
            { p -> p.copy(backend = "different-body-model") },
            { p -> p.copy(gripObservation = p.gripObservation!!.copy(modelId = "different-hand-model")) }
        )) assertEquals(0, run(cycle) { i, p -> if (i >= 4) change(p) else p }.last().imageCycles)
    }
    @Test fun bridgeReturnsTheSameVersionedSnapshotAndDoesNotCallItAQualifiedRep() {
        val bridge = BilateralDipMotionBridge()
        var result: JsonObject? = null
        for ((i, drop) in cycle.withIndex()) {
            val json = """{"pose":${WorkoutRecordCodec.json.encodeToString(frame(i, drop))},"support":${WorkoutRecordCodec.json.encodeToString(reference)},"camera":"ready"}"""
            result = WorkoutRecordCodec.json.parseToJsonElement(bridge.observeJson(json)).jsonObject
        }
        assertEquals(1, result!!["imageCycles"]!!.jsonPrimitive.int)
        assertEquals(1, result["policyVersion"]!!.jsonPrimitive.int)
        assertFalse("observedMovements" in result)
    }
}
