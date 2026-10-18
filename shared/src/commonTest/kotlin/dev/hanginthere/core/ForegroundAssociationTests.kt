package dev.hanginthere.core

import kotlin.test.*

class ForegroundAssociationTests {
    private val source = ForegroundAssociation.Source(7, ImageSize(1_000.0, 1_000.0), "native rectangles fixture", 2)
    private val athlete = PersonInferenceRegion(300, 100, 300, 600)
    private val spectator = PersonInferenceRegion(20, 300, 80, 200)
    private fun time(ticks: Long) = PresentationTime(ticks, 1_000_000)
    private fun acquire(tracker: ForegroundAssociation, origin: Long = 0) {
        for (ticks in listOf(0L, 200_000, 400_000)) {
            val result = tracker.observe(source, time(origin + ticks), listOf(athlete, spectator))
            assertEquals(ForegroundAssociation.Status.acquiring, result.status)
            assertNull(result.candidateIndex)
        }
        val result = tracker.observe(source, time(origin + 600_000), listOf(athlete, spectator))
        assertEquals(ForegroundAssociation.Status.associated, result.status)
        assertEquals(0, result.candidateIndex)
        assertEquals(source.generation, result.generation)
        assertEquals(ForegroundAssociation.policyVersion, result.policyVersion)
        assertEquals(source, result.source)
        assertEquals(time(origin + 600_000), result.timestamp)
        assertEquals(athlete, result.region)
    }

    @Test fun elapsedTimeAloneCannotReplaceTheMinimumObservationCount() {
        val tracker = ForegroundAssociation(source)
        for (ticks in listOf(0L, 300_000, 600_000))
            assertNull(tracker.observe(source, time(ticks), listOf(athlete)).candidateIndex)
        assertEquals(0, tracker.observe(source, time(700_000), listOf(athlete)).candidateIndex)
    }

    @Test fun sourceTimeDwellRequiresFreshObservationsAtDifferentSourceOrigins() {
        for (origin in listOf(0L, 10_000_000_000_000_000L)) acquire(ForegroundAssociation(source), origin)
        val tracker = ForegroundAssociation(source)
        for (ticks in listOf(0L, 200_000, 400_000, 599_999))
            assertNull(tracker.observe(source, time(ticks), listOf(athlete)).candidateIndex)
        assertEquals(0, tracker.observe(source, time(600_000), listOf(athlete)).candidateIndex)
    }

    @Test fun resultOrderAndLargerDistantNewcomersDoNotChooseANewSubject() {
        val tracker = ForegroundAssociation(source); acquire(tracker)
        assertEquals(1, tracker.observe(source, time(700_000), listOf(spectator, athlete)).candidateIndex)
        val newcomer = PersonInferenceRegion(650, 0, 350, 1_000)
        assertEquals(1, tracker.observe(source, time(800_000), listOf(newcomer, athlete, spectator)).candidateIndex)
    }

    @Test fun disappearanceCannotFallBackToTheRemainingSpectatorOrReacquireAfterExpiry() {
        val tracker = ForegroundAssociation(source); acquire(tracker)
        val unavailable = tracker.observe(source, time(800_000), listOf(spectator))
        assertNull(unavailable.candidateIndex)
        assertEquals(ForegroundAssociation.Status.ambiguous, unavailable.status)
        val expired = tracker.observe(source, time(950_001), listOf(athlete, spectator))
        assertEquals(ForegroundAssociation.Reason.continuityExpired, expired.reason)
        assertEquals(ForegroundAssociation.Status.lost, expired.status)
        assertNull(expired.candidateIndex)
        assertEquals(ForegroundAssociation.Reason.resetRequired,
            tracker.observe(source, time(1_000_000), listOf(athlete)).reason)
    }

    @Test fun missingObservationsDoNotRenewTheLastMatchedDeadline() {
        val tracker = ForegroundAssociation(source); acquire(tracker)
        for (ticks in listOf(700_000L, 800_000, 900_000))
            assertNull(tracker.observe(source, time(ticks), emptyList()).candidateIndex)
        assertEquals(ForegroundAssociation.Status.lost,
            tracker.observe(source, time(950_001), listOf(athlete)).status)
    }

    @Test fun exactGapBoundarySurvivesButOneExtraTickRequiresReset() {
        val boundary = ForegroundAssociation(source); acquire(boundary)
        assertEquals(0, boundary.observe(source, time(950_000), listOf(athlete)).candidateIndex)
        val exceeded = ForegroundAssociation(source); acquire(exceeded)
        assertEquals(ForegroundAssociation.Status.lost,
            exceeded.observe(source, time(950_001), listOf(athlete)).status)
    }

    @Test fun crossingAndOverlappingCandidatesSupplyNoSelection() {
        val tracker = ForegroundAssociation(source); acquire(tracker)
        val overlap = athlete.copy(left = athlete.left + 10)
        assertNull(tracker.observe(source, time(700_000), listOf(athlete, overlap)).candidateIndex)
        // Ambiguity does not move the anchor to either candidate.
        assertEquals(1, tracker.observe(source, time(800_000), listOf(spectator, athlete)).candidateIndex)
        val largerOverlap = PersonInferenceRegion(250, 50, 450, 800)
        assertNull(tracker.observe(source, time(900_000), listOf(athlete, largerOverlap)).candidateIndex)
    }

    @Test fun acquisitionRejectsTiesInsufficientSizeAndOverlappingDominance() {
        val other = athlete.copy(left = 650)
        for (candidates in listOf(listOf(athlete, other), listOf(spectator),
            listOf(athlete, PersonInferenceRegion(350, 200, 160, 400)))) {
            val tracker = ForegroundAssociation(source)
            for (ticks in listOf(0L, 200_000, 400_000, 600_000, 800_000))
                assertNull(tracker.observe(source, time(ticks), candidates).candidateIndex)
        }
    }

    @Test fun invalidCompetingRectanglesAreNotSilentlyRemovedAsSpectators() {
        val invalid = PersonInferenceRegion(-1, 0, 40, 40)
        val tracker = ForegroundAssociation(source)
        val result = tracker.observe(source, time(0), listOf(athlete, invalid))
        assertEquals(ForegroundAssociation.Reason.invalidCandidate, result.reason)
        assertNull(result.candidateIndex)
        assertEquals(ForegroundAssociation.Reason.invalidCandidate,
            tracker.observe(source, time(100_000), List(65) { athlete }).reason)
    }

    @Test fun stableAcquisitionCannotDriftIncrementallyToADifferentRegion() {
        val tracker = ForegroundAssociation(source)
        for ((i, left) in listOf(300, 340, 380, 420, 460).withIndex())
            assertNull(tracker.observe(source, time(i * 200_000L), listOf(athlete.copy(left = left))).candidateIndex)
    }

    @Test fun progressiveShrinkingCannotTransferAssociationToSpectatorSizedGeometry() {
        val tracker = ForegroundAssociation(source); acquire(tracker)
        fun centered(width: Int, height: Int) = PersonInferenceRegion(450 - width / 2, 400 - height / 2, width, height)
        assertEquals(0, tracker.observe(source, time(700_000), listOf(centered(225, 450))).candidateIndex)
        for ((i, size) in listOf(170 to 340, 128 to 256, 96 to 192).withIndex())
            assertNull(tracker.observe(source, time(800_000L + i * 100_000), listOf(centered(size.first, size.second))).candidateIndex)
    }

    @Test fun accumulatedDisplacementRemainsBoundedByTheAcquiredRegion() {
        val tracker = ForegroundAssociation(source); acquire(tracker)
        for ((i, left) in listOf(340, 380, 420).withIndex())
            assertEquals(0, tracker.observe(source, time(700_000L + i * 100_000), listOf(athlete.copy(left = left))).candidateIndex)
        assertNull(tracker.observe(source, time(1_000_000), listOf(athlete.copy(left = 460))).candidateIndex)
    }

    @Test fun acquiredScaleEnvelopeAppliesEvenWhenEveryRegionStillMeetsTheSizeFloor() {
        val tracker = ForegroundAssociation(source)
        val original = PersonInferenceRegion(100, 100, 800, 800)
        for (ticks in listOf(0L, 200_000, 400_000, 600_000))
            tracker.observe(source, time(ticks), listOf(original))
        assertEquals(0, tracker.observe(source, time(700_000), listOf(PersonInferenceRegion(200, 200, 600, 600))).candidateIndex)
        assertNull(tracker.observe(source, time(800_000), listOf(PersonInferenceRegion(275, 275, 450, 450))).candidateIndex)
    }

    @Test fun timebaseChangesRequireANewEpochEvenDuringAcquisition() {
        val tracker = ForegroundAssociation(source)
        tracker.observe(source, time(0), listOf(athlete))
        assertEquals(ForegroundAssociation.Status.lost,
            tracker.observe(source, PresentationTime(200_000, 2_000_000), listOf(athlete)).status)
        val next = source.copy(generation = 8); tracker.reset(next)
        for (ticks in listOf(0L, 400_000, 800_000))
            assertNull(tracker.observe(next, PresentationTime(ticks, 2_000_000), listOf(athlete)).candidateIndex)
        assertEquals(0, tracker.observe(next, PresentationTime(1_200_000, 2_000_000), listOf(athlete)).candidateIndex)
    }

    @Test fun changedTimebaseCannotRoundAnOverlongGapIntoTheAllowedDeadline() {
        val tracker = ForegroundAssociation(source)
        val last = 10_000_000_000_600_009L
        acquire(tracker, last - 600_000)
        val overlong = PresentationTime(10_000_010_000_950_010L, 1_000_001)
        val decision = tracker.observe(source, overlong, listOf(athlete))
        assertEquals(ForegroundAssociation.Status.lost, decision.status)
        assertEquals(ForegroundAssociation.Reason.clockFault, decision.reason)
        assertNull(decision.candidateIndex)
    }

    @Test fun duplicateAndRegressingFramesCannotEarnDwellOrMoveTheAnchor() {
        val tracker = ForegroundAssociation(source)
        for (ticks in listOf(0L, 200_000, 400_000)) tracker.observe(source, time(ticks), listOf(athlete))
        assertNull(tracker.observe(source, time(200_000), listOf(athlete.copy(left = 700))).candidateIndex)
        for (ticks in listOf(600_000L, 800_000, 1_000_000))
            assertNull(tracker.observe(source, time(ticks), listOf(athlete)).candidateIndex)
        assertEquals(0, tracker.observe(source, time(1_200_000), listOf(athlete)).candidateIndex)
        assertNull(tracker.observe(source, time(1_200_000), listOf(spectator)).candidateIndex)
        assertEquals(0, tracker.observe(source, time(1_300_000), listOf(athlete)).candidateIndex)
    }

    @Test fun clockOverflowAndInvalidTimescaleCannotContinueAnAcquiredSubject() {
        val overflow = ForegroundAssociation(source); acquire(overflow)
        val result = overflow.observe(source, PresentationTime(Long.MIN_VALUE, 1_000_000), listOf(athlete))
        assertNull(result.candidateIndex)
        val invalid = ForegroundAssociation(source); acquire(invalid)
        assertEquals(ForegroundAssociation.Status.lost,
            invalid.observe(source, PresentationTime(700_000, 0), listOf(athlete)).status)
        val wrapping = ForegroundAssociation(source)
        acquire(wrapping, Long.MIN_VALUE + 1_000_000)
        assertEquals(ForegroundAssociation.Status.lost,
            wrapping.observe(source, PresentationTime(Long.MAX_VALUE, 1_000_000), listOf(athlete)).status)
    }

    @Test fun explicitNewEpochRejectsLateCallbacksWithoutResettingFreshAcquisition() {
        val tracker = ForegroundAssociation(source); acquire(tracker)
        val next = source.copy(generation = 8)
        tracker.reset(next)
        tracker.observe(next, time(0), listOf(athlete))
        val stale = tracker.observe(source, time(9_000_000), listOf(spectator))
        assertEquals(ForegroundAssociation.Reason.sourceMismatch, stale.reason)
        assertEquals(8L, stale.generation)
        assertNull(stale.timestamp)
        assertNull(stale.region)
        for (ticks in listOf(200_000L, 400_000))
            assertNull(tracker.observe(next, time(ticks), listOf(athlete)).candidateIndex)
        assertEquals(0, tracker.observe(next, time(600_000), listOf(athlete)).candidateIndex)
        assertFailsWith<IllegalArgumentException> { tracker.reset(next) }
        assertFailsWith<IllegalArgumentException> { tracker.reset(source) }
    }

    @Test fun imageOrBackendChangesWithinAnEpochInvalidateAssociation() {
        for (changed in listOf(source.copy(imageSize = ImageSize(900.0, 1_000.0)),
            source.copy(backend = "different native model"), source.copy(revision = 3))) {
            val tracker = ForegroundAssociation(source); acquire(tracker)
            assertEquals(ForegroundAssociation.Reason.sourceChanged,
                tracker.observe(changed, time(700_000), listOf(athlete)).reason)
            assertEquals(ForegroundAssociation.Status.lost,
                tracker.observe(source, time(800_000), listOf(athlete)).status)
        }
    }

    @Test fun invalidSourceGeometryAndIdentifiersAreRejected() {
        assertFailsWith<IllegalArgumentException> { source.copy(generation = -1) }
        assertFailsWith<IllegalArgumentException> { source.copy(backend = " ") }
        assertFailsWith<IllegalArgumentException> { source.copy(revision = 0) }
        for (size in listOf(ImageSize(Double.NaN, 10.0), ImageSize(10.5, 10.0), ImageSize(0.0, 10.0),
            ImageSize(Int.MAX_VALUE.toDouble() + 1, 10.0)))
            assertFailsWith<IllegalArgumentException> { source.copy(imageSize = size) }
    }
}
