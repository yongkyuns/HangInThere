package dev.hanginthere.core

import kotlinx.serialization.encodeToString
import kotlin.test.*

class ObservedHandContinuityTests {
    private fun frame(time: PresentationTime = PresentationTime(0, 1000), ordinal: Long = 0,
        generation: Long = 0): ObservedHandContinuity.Frame {
        val base = BilateralGripFixtures.frame()
        val pose = base.copy(timestamp = time, gripObservation = base.gripObservation!!.copy(seconds = time.seconds,
            hands = base.gripObservation.hands.mapIndexed { index, hand -> hand.copy(points = listOf(
                GripHandPoint(GripHandJoint.wrist, base.people[0].landmark(ArmMeasurement.Side.entries[index].joints[2])!!.position, score = .9))) }))
        return ObservedHandContinuity.Frame(generation, "source-epoch", ordinal, pose, time, ordinal)
    }
    private fun frame(ms: Long, ordinal: Long = ms) = frame(PresentationTime(ms, 1000), ordinal)
    private fun edit(input: ObservedHandContinuity.Frame, transform: (GripObservation) -> GripObservation) =
        input.copy(pose = input.pose.copy(gripObservation = transform(input.pose.gripObservation!!)))
    private fun move(input: ObservedHandContinuity.Frame, index: Int, x: Double) = edit(input) { hand ->
        hand.copy(hands = hand.hands.mapIndexed { i, value -> if (i != index) value else value.copy(points = value.points.map {
            it.copy(position = it.position.copy(x = x))
        }) })
    }
    private fun start(input: ObservedHandContinuity.Frame = frame(), indices: List<Int> = listOf(0, 1)) =
        assertNotNull(ObservedHandContinuity.start(ObservedHandContinuity.Start(input, indices)))
    private fun observe(tracker: ObservedHandContinuity, input: ObservedHandContinuity.Frame) = assertNotNull(tracker.observe(input))
    private fun one(tracker: ObservedHandContinuity, input: ObservedHandContinuity.Frame) = observe(tracker, input).tracks.single()

    @Test fun currentSampleUsesOnlyCurrentIndependentCoordinatesAndSource() {
        val tracker = start(indices = listOf(0))
        val result = one(tracker, move(frame(100), 0, 210.0))
        assertEquals(ObservedHandContinuity.State.fresh, result.state)
        assertEquals(Point2D(210.0, 600.0), result.currentSample!!.position)
        assertEquals(PresentationTime(100, 1000), result.currentSample.sourceTime)
        assertEquals(100L, result.currentSample.sourceFrameOrdinal)
        assertEquals(Point2D(200.0, 600.0), result.seed.position)
        assertEquals(result.lastObservedSample, result.currentSample)
    }
    @Test fun absentInferenceRetainsLastSampleWithoutCurrentSample() {
        val tracker = start(indices = listOf(0)); val initial = tracker.snapshot().tracks.single()
        val input = frame(100).let { it.copy(pose = it.pose.copy(gripObservation = null), handSourceTime = null, handSourceFrameOrdinal = null) }
        val result = one(tracker, input)
        assertEquals(ObservedHandContinuity.State.unavailable, result.state)
        assertNull(result.currentSample)
        assertEquals(initial.lastObservedSample, result.lastObservedSample)
        assertEquals(ObservedHandContinuity.State.fresh, one(tracker, frame(200)).state)
    }
    @Test fun sdkResultOrderDoesNotChooseContinuity() {
        val tracker = start()
        val result = observe(tracker, edit(frame(100)) { it.copy(hands = it.hands.reversed()) })
        assertEquals(listOf(1, 0), result.tracks.map { it.currentSample!!.originalHandIndex })
        assertEquals(listOf(Point2D(200.0, 600.0), Point2D(500.0, 600.0)), result.tracks.map { it.currentSample!!.position })
    }
    @Test fun competingCurrentHandsCannotBeRankedByScoreOrHandedness() {
        val tracker = start(indices = listOf(0))
        val input = edit(move(frame(100), 1, 210.0)) { it.copy(hands = it.hands.mapIndexed { i, h ->
            h.copy(sdkHandedness = if (i == 0) "left" else "right", sdkHandednessScore = 1.0,
                points = h.points.map { p -> p.copy(score = if (i == 0) .9 else 1.0) }) }) }
        val result = one(tracker, input)
        assertEquals(ObservedHandContinuity.State.ambiguous, result.state)
        assertNull(result.currentSample)
        assertEquals(PresentationTime(0, 1000), result.lastObservedSample.sourceTime)
    }
    @Test fun oneCurrentHandCannotContinueTwoSeedTracks() {
        val tracker = start(move(frame(), 1, 300.0))
        val result = observe(tracker, edit(move(frame(100), 0, 250.0)) { it.copy(hands = it.hands.take(1)) })
        assertTrue(result.tracks.all { it.state == ObservedHandContinuity.State.ambiguous && it.currentSample == null })
    }
    @Test fun invalidCompetitorCannotBeErasedToObtainUniqueMatch() {
        val tracker = start(indices = listOf(0))
        val result = one(tracker, edit(frame(100)) { it.copy(hands = it.hands.mapIndexed { i, h ->
            if (i == 0) h else h.copy(points = h.points.map { p -> p.copy(score = null) }) }) })
        assertEquals(ObservedHandContinuity.Reason.invalidCompetitor, result.reason)
        assertNull(result.currentSample)
        assertEquals(PresentationTime(0, 1000), result.lastObservedSample.sourceTime)
    }
    @Test fun invalidCompetitorAlsoPreventsStart() {
        val input = edit(frame()) { it.copy(hands = it.hands.mapIndexed { i, h -> if (i == 0) h else h.copy(points = emptyList()) }) }
        assertNull(ObservedHandContinuity.start(ObservedHandContinuity.Start(input, listOf(0))))
        // Existing stateless association continues to retain usable hands.
        assertEquals(1, assertNotNull(ObservedWristAssociation.associate(ObservedWristAssociation.Frame(input.pose, input.pose.timestamp))).matches.size)
    }
    @Test fun exactOutageBoundaryAllowsFreshReacquisition() {
        val result = one(start(indices = listOf(0)), frame(PresentationTime(3, 4), 1))
        assertEquals(ObservedHandContinuity.State.fresh, result.state)
        assertEquals(PresentationTime(3, 4), result.currentSample!!.sourceTime)
    }
    @Test fun afterOutageOriginalAncestryCannotRevive() {
        val tracker = start(indices = listOf(0))
        val expired = one(tracker, frame(751))
        assertEquals(ObservedHandContinuity.Reason.observationOutage, expired.reason)
        assertNull(expired.currentSample)
        assertEquals(expired, one(tracker, frame(800)))
    }
    @Test fun lastAndImmutableSeedEnvelopesBothConstrainDrift() {
        val tracker = start(indices = listOf(0))
        assertEquals(ObservedHandContinuity.State.fresh, one(tracker, move(frame(100), 0, 270.0)).state)
        val escaped = one(tracker, move(frame(200), 0, 300.0))
        assertEquals(ObservedHandContinuity.State.unavailable, escaped.state)
        assertEquals(Point2D(270.0, 600.0), escaped.lastObservedSample.position)
    }
    @Test fun emptyDetectionIsUnavailableAndDoesNotRefreshExpiry() {
        val tracker = start(indices = listOf(0))
        assertNull(one(tracker, edit(frame(500)) { it.copy(hands = emptyList()) }).currentSample)
        assertEquals(ObservedHandContinuity.State.terminated, one(tracker, frame(751)).state)
    }
    @Test fun failedInferenceDoesNotRefreshLastSample() {
        val result = one(start(indices = listOf(0)), edit(frame(100)) { it.copy(failure = "unavailable") })
        assertEquals(ObservedHandContinuity.Reason.invalidObservation, result.reason)
        assertNull(result.currentSample)
        assertEquals(PresentationTime(0, 1000), result.lastObservedSample.sourceTime)
    }
    @Test fun staleHandClockOrOrdinalCannotDonateCurrentPoint() {
        for (input in listOf(frame(100).copy(handSourceTime = PresentationTime(0, 1000)), frame(100).copy(handSourceFrameOrdinal = 0))) {
            val tracker = start(indices = listOf(0)); val result = one(tracker, input)
            assertEquals(ObservedHandContinuity.Reason.sourceProvenanceMismatch, result.reason)
            assertNull(result.currentSample)
            assertEquals(PresentationTime(0, 1000), result.lastObservedSample.sourceTime)
            // The valid source frame's high-water mark still advanced.
            assertEquals(ObservedHandContinuity.Reason.sourceRegression, one(tracker, frame(100)).reason)
        }
    }
    @Test fun absentInferenceWithClaimedHandProvenanceIsNotFresh() {
        val input = frame(100).let { it.copy(pose = it.pose.copy(gripObservation = null)) }
        assertEquals(ObservedHandContinuity.Reason.sourceProvenanceMismatch, one(start(indices = listOf(0)), input).reason)
    }
    @Test fun clockOrOrdinalRegressionTerminatesLifetime() {
        for (input in listOf(frame(), frame(100).copy(sourceFrameOrdinal = 0, handSourceFrameOrdinal = 0),
            frame(PresentationTime(0, 0), 1), frame(-1, 1))) {
            val tracker = start(indices = listOf(0))
            assertEquals(ObservedHandContinuity.Reason.sourceRegression, one(tracker, input).reason)
            assertNull(one(tracker, frame(200)).currentSample)
        }
    }
    @Test fun contextChangesTerminateWithoutReseeding() {
        val input = frame(100)
        val cases = listOf(input.copy(sourceEpoch = "other"), input.copy(requiresJointConfidence = false),
            input.copy(pose = input.pose.copy(backend = "other")), input.copy(pose = input.pose.copy(requestRevision = 9)),
            input.copy(pose = input.pose.copy(imageSize = ImageSize(800.0, 1280.0))),
            edit(input) { it.copy(modelId = "other") }, edit(input) { it.copy(backend = "other") },
            edit(input) { it.copy(schemaVersion = 2) }, edit(input) { it.copy(coordinateConvention = "mirrored") })
        for (changed in cases) {
            val tracker = start(indices = listOf(0))
            assertEquals(ObservedHandContinuity.Reason.sourceContextChanged, one(tracker, changed).reason)
            assertNull(one(tracker, frame(200)).currentSample)
        }
    }
    @Test fun wrongGenerationIsRejectedWithoutAdvancingCurrentLifetime() {
        val tracker = start(indices = listOf(0)); val initial = tracker.snapshot()
        assertNull(tracker.observe(frame(100).copy(generation = 1)))
        assertEquals(initial, tracker.snapshot())
        assertEquals(ObservedHandContinuity.State.fresh, one(tracker, frame(100)).state)
    }
    @Test fun bodylessHandFrameIsNotBodyCorrespondence() {
        val raw = frame(); val input = raw.copy(pose = raw.pose.copy(people = emptyList(), gripObservation = raw.pose.gripObservation!!.copy(trackedBody = emptyList())))
        assertEquals(2, start(input).snapshot().tracks.size)
        assertNull(ObservedWristAssociation.associate(ObservedWristAssociation.Frame(input.pose, input.pose.timestamp)))
        assertNull(ObservedHandContinuity.start(ObservedHandContinuity.Start(input.copy(pose = input.pose.copy(gripObservation = raw.pose.gripObservation)), listOf(0))))
    }
    @Test fun foreignTrackedBodyBindingRemainsUnavailable() {
        val result = one(start(indices = listOf(0)), edit(frame(100)) { it.copy(trackedBody = it.trackedBody.map { p -> p.copy(score = .1) }) })
        assertEquals(ObservedHandContinuity.Reason.invalidObservation, result.reason)
        assertNull(result.currentSample)
    }
    @Test fun nullableScoreRequiresCapabilityAndReportedLowScoreIsNeverIgnored() {
        val raw = edit(frame()) { it.copy(hands = it.hands.map { h -> h.copy(points = h.points.map { p -> p.copy(score = null) }) }) }
        assertNull(ObservedHandContinuity.start(ObservedHandContinuity.Start(raw, listOf(0))))
        val unscored = start(raw.copy(requiresJointConfidence = false), listOf(0))
        assertNull(unscored.snapshot().tracks.single().seed.jointConfidence)
        val low = edit(frame(100).copy(requiresJointConfidence = false)) { it.copy(hands = it.hands.map { h -> h.copy(points = h.points.map { p -> p.copy(score = .29) }) }) }
        assertEquals(ObservedHandContinuity.Reason.invalidCompetitor, one(unscored, low).reason)
    }
    @Test fun invalidCropOrNonfinitePointCannotBeDroppedAsCompetitor() {
        for (input in listOf(edit(frame(100)) { it.copy(hands = it.hands.map { h -> h.copy(sourceRegion = PersonInferenceRegion(0, 0, 1, 1)) }) },
            edit(frame(100)) { it.copy(hands = it.hands.map { h -> h.copy(points = h.points.map { p -> p.copy(position = Point2D(Double.NaN, 0.0)) }) }) })) {
            val result = one(start(indices = listOf(0)), input)
            assertEquals(ObservedHandContinuity.Reason.invalidCompetitor, result.reason)
            assertNull(result.currentSample)
        }
    }
    @Test fun originalFrameAndPreviouslyReturnedSnapshotRemainImmutable() {
        val initial = frame(); val before = WorkoutRecordCodec.json.encodeToString(initial)
        val tracker = start(initial, listOf(0)); val snapshot = tracker.snapshot()
        one(tracker, move(frame(100), 0, 210.0))
        assertEquals(before, WorkoutRecordCodec.json.encodeToString(initial))
        assertEquals(Point2D(200.0, 600.0), snapshot.tracks.single().currentSample!!.position)
        assertEquals(PresentationTime(0, 1000), snapshot.tracks.single().lastObservedSample.sourceTime)
    }
    @Test fun hugeAbsoluteTimestampsDoNotRoundAwayExactOutage() {
        val origin = Long.MAX_VALUE - 10000
        for ((ticks, expected) in listOf(750L to ObservedHandContinuity.State.fresh, 751L to ObservedHandContinuity.State.terminated)) {
            val tracker = start(frame(PresentationTime(origin, 1000), 0), listOf(0))
            assertEquals(expected, one(tracker, frame(PresentationTime(origin + ticks, 1000), 1)).state)
        }
    }
    @Test fun mixedRationalClocksPreserveMonotonicityAndBoundary() {
        val tracker = start(frame(PresentationTime(1, 3), 0), listOf(0))
        assertEquals(ObservedHandContinuity.State.fresh, one(tracker, frame(PresentationTime(13, 12), 1)).state)
        assertEquals(ObservedHandContinuity.Reason.sourceRegression, one(tracker, frame(PresentationTime(26, 24), 2)).reason)
    }
    @Test fun invalidSeedSelectionAndSourceCannotStart() {
        for (indices in listOf(emptyList(), listOf(0, 0), listOf(0, 1, 2), listOf(-1), listOf(2)))
            assertNull(ObservedHandContinuity.start(ObservedHandContinuity.Start(frame(), indices)))
        for (input in listOf(frame().copy(sourceEpoch = ""), frame().copy(sourceFrameOrdinal = -1),
            frame().copy(generation = -1), frame().copy(handSourceTime = PresentationTime(1, 1000))))
            assertNull(ObservedHandContinuity.start(ObservedHandContinuity.Start(input, listOf(0))))
    }
    @Test fun bridgeRequiresExplicitResetAndRejectsDelayedPriorGeneration() {
        val bridge = ObservedHandContinuityBridge()
        val first = ObservedHandContinuity.Start(frame(), listOf(0))
        assertNotNull(bridge.startJson(WorkoutRecordCodec.json.encodeToString(first)))
        assertNull(bridge.startJson(WorkoutRecordCodec.json.encodeToString(first)))
        assertEquals(1L, bridge.reset())
        assertNull(bridge.startJson(WorkoutRecordCodec.json.encodeToString(first)))
        val next = first.copy(frame = first.frame.copy(generation = 1))
        assertNotNull(bridge.startJson(WorkoutRecordCodec.json.encodeToString(next)))
        assertNull(bridge.observeJson(WorkoutRecordCodec.json.encodeToString(frame(100))))
        val json = assertNotNull(bridge.observeJson(WorkoutRecordCodec.json.encodeToString(frame(100).copy(generation = 1))))
        val result = WorkoutRecordCodec.json.decodeFromString<ObservedHandContinuity.Snapshot>(json)
        assertEquals(ObservedHandContinuity.State.fresh, result.tracks.single().state)
        assertEquals(100L, result.tracks.single().currentSample!!.sourceFrameOrdinal)
    }
}
