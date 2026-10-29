package dev.hanginthere.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class WorkoutEvidenceTests {
    private fun record(exercise: ExerciseCounter.Exercise = ExerciseCounter.Exercise.pullUp): RepRecord {
        val metrics = AngleMetrics(80.0, 170.0, 125.0, .6, 1.0)
        return RepRecord("set:rep:1", "set", 1, exercise, 0.0, .3, .3, .6, .6,
            "returned", "accepted", .3, .3, metrics, metrics, null, null, .6, 1.0, emptyList(), false)
    }

    @Test fun completedStatusUsesTheExistingEligibilityForBothExercises() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val rep = record(exercise)
            val evidence = WorkoutEvidence.assess(rep)
            assertEquals(WorkoutAttemptStatus.completed, evidence.status)
            assertTrue(rep.isCompleteForAnalytics)
            assertTrue(evidence.angleComparisonEligible)
            assertEquals(90.0, WorkoutEvidence.comparableElbowExcursion(rep))
        }
    }

    @Test fun acceptanceWithoutReturnNeverBecomesAPartialOrCompletedRep() {
        for (ending in listOf("disengaged", "manual", "trackingLost", "inferenceFailure", "sourceTimeGap")) {
            val rep = record().copy(returnedAt = null, returnSeconds = null, closeReason = ending)
            val evidence = WorkoutEvidence.assess(rep)
            assertEquals(WorkoutAttemptStatus.returnNotRecorded, evidence.status)
            assertFalse(rep.isCompleteForAnalytics)
            assertTrue(evidence.explanation.contains("cannot be confirmed"))
            assertNotNull(evidence.reason)
        }
    }

    @Test fun partialMovementIsExplicitCounterEvidence() {
        val rep = record().copy(acceptedAt = null, returnedAt = null, closeReason = "partial", outcome = "partial")
        val evidence = WorkoutEvidence.assess(rep)
        assertEquals(WorkoutAttemptStatus.partialMovement, evidence.status)
        assertTrue(evidence.explanation.contains("return was observed"))
        assertFalse(rep.isCompleteForAnalytics)
        assertTrue(evidence.angleComparisonEligible)
    }

    @Test fun interruptedObservationsCannotBecomeProgressRangeEvenWithFullPrefixCoverage() {
        for (reason in listOf("trackingLost", "sourceTimeGap", "invalidTimestamp", "nonIncreasingTimestamp",
            "inferenceFailure", "multiplePeople", "returnNotSustained", "dipReturnUnresolved", "futureInterruptionReason")) {
            for (accepted in listOf(false, true)) {
                val rep = record().copy(acceptedAt = .3.takeIf { accepted }, returnedAt = null, closeReason = reason, outcome = "interrupted")
                val evidence = WorkoutEvidence.assess(rep)
                assertEquals(if (accepted) WorkoutAttemptStatus.returnNotRecorded else WorkoutAttemptStatus.interrupted, evidence.status)
                assertEquals(1.0, evidence.durationCoverage)
                assertFalse(evidence.angleComparisonEligible)
                assertNull(WorkoutEvidence.comparableElbowExcursion(rep))
                assertFalse(rep.isCompleteForAnalytics)
            }
        }
    }

    @Test fun inProgressAndVoluntaryEndDoNotAssertTrackingFailure() {
        for (reason in listOf("inProgress", "manual", "disengaged", "endOfInput")) {
            val evidence = WorkoutEvidence.assess(record().copy(acceptedAt = null, returnedAt = null, closeReason = reason))
            assertEquals(if (reason == "inProgress") WorkoutAttemptStatus.inProgress else WorkoutAttemptStatus.ended, evidence.status)
            assertFalse(evidence.explanation.contains("was interrupted"))
        }
    }
    @Test fun unresolvedReturnDoesNotAssertAnotherDescent() {
        val assessment = WorkoutEvidence.assess(record().copy(acceptedAt = null, returnedAt = null,
            closeReason = "dipReturnUnresolved", outcome = "interrupted"))
        assertEquals("Return position could not be confirmed; reacquire top support", assessment.reason)
        assertEquals(WorkoutAttemptStatus.interrupted, assessment.status)
        assertFalse(assessment.angleComparisonEligible)
    }

    @Test fun missingOrInvalidCoverageRemainsUnavailableWithoutBorrowingTheOtherArm() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1)) {
            val rep = record().copy(durationCoverage = value, right = record().right.copy(coverage = value))
            val evidence = WorkoutEvidence.assess(rep)
            assertNull(evidence.durationCoverage)
            assertNull(evidence.rightCoverage)
            assertEquals(1.0, evidence.leftCoverage)
            assertFalse(evidence.angleComparisonEligible)
            assertTrue(evidence.notes.any { it.contains("unavailable") })
            assertEquals(WorkoutAttemptStatus.completed, evidence.status)
        }
    }

    @Test fun partialCoverageIsDescriptiveAndCannotChangeCountEligibility() {
        val rep = record().copy(durationCoverage = .6, left = record().left.copy(coverage = .7))
        val evidence = WorkoutEvidence.assess(rep)
        assertEquals(.6, evidence.durationCoverage)
        assertEquals(.7, evidence.leftCoverage)
        assertEquals(1.0, evidence.rightCoverage)
        assertTrue(evidence.notes.any { it.contains("not observed") })
        assertFalse(evidence.angleComparisonEligible)
        assertTrue(rep.isCompleteForAnalytics)
    }

    @Test fun rangeComparisonUsesBothArmsAndExistingCoverageThreshold() {
        for (coverage in listOf(.79, .8, 1.0)) {
            val rep = record().copy(right = record().right.copy(coverage = coverage))
            assertEquals(coverage >= .8, WorkoutEvidence.assess(rep).angleComparisonEligible)
        }
        for (bad in listOf(record().right.copy(minimum = null), record().right.copy(maximum = Double.NaN),
            record().right.copy(minimum = 175.0), record().right.copy(maximum = 181.0))) {
            assertNull(WorkoutEvidence.comparableElbowExcursion(record().copy(right = bad)))
        }
    }

    @Test fun retiredCurvesAndRecoveredSetsPreserveAssessmentAndEligibility() {
        val rep = record().copy(samples = listOf(MotionSample(.1, 90.0, null, null, false, false)))
        val set = WorkoutSetRecord("set", rep.exercise, 0.0, .6, "manual", listOf(rep), null, null)
        val recovered = WorkoutRecordCodec.recover(WorkoutRecordCodec.summary(set))
        assertEquals(WorkoutEvidence.assess(rep), WorkoutEvidence.assess(recovered.reps.single()))
        assertEquals(set.completedCount, recovered.completedCount)
        assertEquals(rep, set.reps.single(), "Assessment never mutates measurements")
    }

    @Test fun recoveredOrFinalizedActiveAttemptCannotClaimItIsStillBeingTracked() {
        for (accepted in listOf(false, true)) {
            val rep = record().copy(acceptedAt = .3.takeIf { accepted }, returnedAt = null, closeReason = "inProgress")
            val live = WorkoutSetRecord("set", rep.exercise, 0.0, .6, "running", listOf(rep), null, null)
            assertEquals(WorkoutAttemptStatus.inProgress, WorkoutEvidence.assess(rep, live.endReason).status)
            val recovered = WorkoutRecordCodec.recover(WorkoutRecordCodec.summary(live))
            assertEquals("inProgress", recovered.reps.single().closeReason, "Keep the raw journal unchanged")
            for (reason in listOf(recovered.endReason, "manual", "disengaged", "futureEndReason")) {
                val evidence = WorkoutEvidence.assess(rep, reason)
                assertEquals(if (accepted) WorkoutAttemptStatus.returnNotRecorded else WorkoutAttemptStatus.interrupted, evidence.status)
                assertFalse(evidence.explanation.contains("still being tracked"))
                assertFalse(evidence.angleComparisonEligible)
            }
            val json = WorkoutEvidenceBridge().assessmentJson(WorkoutRecordCodec.json.encodeToString(rep), recovered.endReason)
            assertEquals(WorkoutEvidence.assess(rep, recovered.endReason), WorkoutAnalysis.json.decodeFromString<WorkoutAttemptAssessment>(json))
        }
    }

    @Test fun appleBridgeUsesSamePolicyAndAcceptsOmittedNulls() {
        val rep = record().copy(returnedAt = null, closeReason = "sourceTimeGap", durationCoverage = null)
        val input = WorkoutRecordCodec.json.encodeToString(rep)
        val output = WorkoutEvidenceBridge().assessmentJson(input)
        assertEquals(WorkoutEvidence.assess(rep), WorkoutAnalysis.json.decodeFromString<WorkoutAttemptAssessment>(output))
    }

    @Test fun interruptedPrefixCannotBecomeBestRangeInProgressReport() {
        val rep = record().copy(acceptedAt = null, returnedAt = null, closeReason = "inferenceFailure", outcome = "interrupted")
        val set = WorkoutSetRecord("set", rep.exercise, 0.0, .6, "manual", listOf(rep), null, null)
        val evidence = WorkoutProgression.evidence(set)
        assertNull(evidence.elbowExcursionDegrees)
        assertEquals(1, evidence.incompleteAttempts)
        assertEquals(0, evidence.completedCount)
        val report = WorkoutProgression.report(listOf(set), set.exercise)
        assertNull(report.bestElbowExcursionDegrees)
        assertFalse(report.milestones.first { it.id == "reps:1" }.achieved)
    }
}
