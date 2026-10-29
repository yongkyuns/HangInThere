package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class PublicReplayProgressTests {
    private val pts = listOf(12_000_000L, 12_150_000L, 12_300_000L, 12_450_000L, 12_600_000L, 12_750_000L, 12_900_000L)
    private fun prefix(size: Int) = PublicReplayProgress(pts.size, pts).also { progress -> pts.take(size).forEach(progress::observe) }
    private fun JsonObject.status() = getValue("status").jsonPrimitive.content
    private fun JsonObject.clock() = getValue("source_clock_verified").jsonPrimitive.boolean

    @Test fun lateFailureRetainsClosedReturnsWithoutClaimingEof() {
        val row = prefix(6).snapshot(null, listOf(12.45), listOf(12.75))
        assertEquals("interrupted", row.status()); assertFalse(row.clock())
        assertEquals(6, row.getValue("frames").jsonPrimitive.int)
        assertEquals(listOf(12.75), row.getValue("completed_seconds").jsonArray.map { it.jsonPrimitive.double })
        assertEquals(12.75, row.getValue("progress_evidence").jsonObject.getValue("observed_source_end_seconds").jsonPrimitive.double, 0.0)
    }
    @Test fun acceptedWithoutReturnRemainsIncomplete() {
        val row = prefix(4).snapshot("interrupted", listOf(12.45), emptyList())
        assertEquals("interrupted", row.status())
        assertEquals(1, row.getValue("acceptance_seconds").jsonArray.size)
        assertTrue(row.getValue("completed_seconds").jsonArray.isEmpty())
    }
    @Test fun fullRunAndUnavailableSetupKeepTheirOriginalOutcomes() {
        val progress = prefix(pts.size); progress.finishPass()
        assertEquals("processed", progress.snapshot("complete", listOf(12.45), listOf(12.75)).status())
        assertTrue(progress.snapshot("complete", emptyList(), emptyList()).clock())
        assertEquals("setupUnavailable", progress.snapshot("setupUnavailable", emptyList(), emptyList()).status())
        assertEquals("failed", progress.snapshot("setupUnavailable", listOf(12.45), emptyList()).status())
        assertEquals("interrupted", progress.snapshot(null, emptyList(), emptyList()).status())
        assertFalse(progress.snapshot(null, emptyList(), emptyList()).clock())
    }
    @Test fun badMissingOrChangedClocksCannotRetainMeasurements() {
        for (bad in listOf(emptyList(), pts.take(2), pts.reversed(), pts.toMutableList().also { it[2] = it[1] })) {
            assertEquals("failed", PublicReplayProgress(pts.size, bad).snapshot(null, emptyList(), emptyList()).status())
        }
        val mismatch = prefix(2)
        assertThrows(IllegalStateException::class.java) { mismatch.checkNext(pts[3]) }
        assertEquals("failed", mismatch.snapshot(null, emptyList(), emptyList()).status())
        val short = prefix(4)
        assertThrows(IllegalStateException::class.java) { short.finishPass() }
        assertEquals("failed", short.snapshot(null, listOf(12.45), emptyList()).status())
        assertEquals("failed", prefix(4).snapshot("complete", listOf(12.45), emptyList()).status())
        assertEquals("failed", prefix(6).snapshot(null, listOf(12.45), listOf(12.75), mediaVerified = false).status())
    }
    @Test fun rewindCannotCreditAnEarlierAcquisitionPass() {
        val progress = prefix(6); progress.beginPass(); pts.take(4).forEach(progress::observe)
        assertEquals("failed", progress.snapshot(null, listOf(12.45), listOf(12.75)).status())
        val row = progress.snapshot(null, listOf(12.45), emptyList())
        assertEquals(4, row.getValue("frames").jsonPrimitive.int)
    }
    @Test fun eventsMustBeFiniteOrderedAndInsideObservedPrefix() {
        for (events in listOf(listOf(11.9), listOf(12.9), listOf(Double.NaN), listOf(12.6, 12.45))) {
            assertEquals("failed", prefix(6).snapshot(null, events, emptyList()).status())
            assertEquals("failed", prefix(6).snapshot(null, emptyList(), events).status())
        }
    }
    @Test fun expectedClockIsOwnedAndUnrepresentableSecondClocksAreRejected() {
        val mutable = pts.toMutableList(); val progress = PublicReplayProgress(pts.size, mutable)
        mutable[0] = 1L; pts.take(4).forEach(progress::observe)
        assertEquals("interrupted", progress.snapshot(null, listOf(12.45), emptyList()).status())
        val aliased = PublicReplayProgress(2, listOf(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertThrows(IllegalStateException::class.java) { aliased.observe(Long.MAX_VALUE - 1) }
    }

    @Test fun failedBlockCapturesActualSharedReturnsBeforeCleanup() {
        val rows = mutableListOf<JsonObject>()
        for (returned in listOf(false, true)) {
            val runtime = WorkoutRuntime()
            runtime.session.start(ExerciseCounter.Exercise.pullUp, ArmMeasurement.Side.left)
            val progress = PublicReplayProgress(pts.size, pts)
            assertThrows(InjectedFailure::class.java) {
                progress.captureOnExit(runtime, { true }) {
                    listOf(170.0, 170.0, 80.0, 80.0, 170.0, 170.0).take(if (returned) 6 else 4).forEachIndexed { i, angle ->
                        runtime.session.consume(arm(pts[i], angle), BarSegment(Point2D(100.0, 500.0), Point2D(900.0, 500.0)))
                        progress.observe(pts[i])
                    }
                    throw InjectedFailure()
                }
            }
            // Destructive cleanup cannot erase the captured counter/analysis evidence.
            runtime.session.reset(ExerciseCounter.Exercise.pullUp, ArmMeasurement.Side.left)
            val row = progress.snapshot(null)
            assertEquals("interrupted", row.status()); assertFalse(row.clock())
            assertEquals(1, row.getValue("acceptance_seconds").jsonArray.size)
            assertEquals(if (returned) listOf(12.75) else emptyList<Double>(),
                row.getValue("completed_seconds").jsonArray.map { it.jsonPrimitive.double })
            rows += buildJsonObject { put("id", if (returned) "returned" else "accepted_unreturned"); put("progress", row) }
        }
        // Optional, authored JVM-to-Python wire-contract evidence; never a native accuracy report.
        System.getenv("PUBLIC_REPLAY_PROGRESS_REPORT")?.let { path -> File(path).writeText(JsonArray(rows).toString()) }
    }

    @Test fun acquisitionEventsCannotBecomeCountingTotals() {
        val runtime = WorkoutRuntime()
        runtime.session.start(ExerciseCounter.Exercise.pullUp, ArmMeasurement.Side.left)
        val progress = PublicReplayProgress(pts.size, pts)
        progress.captureOnExit(runtime, { false }) {
            listOf(170.0, 170.0, 80.0, 80.0).forEachIndexed { i, angle ->
                runtime.session.consume(arm(pts[i], angle), BarSegment(Point2D(100.0, 500.0), Point2D(900.0, 500.0)))
                progress.observe(pts[i])
            }
        }
        assertEquals(1, progress.events.count { it.outcome == ExerciseCounter.Outcome.movement })
        assertTrue(progress.snapshot(null).getValue("acceptance_seconds").jsonArray.isEmpty())
        pts.drop(4).forEach(progress::observe); progress.finishPass()
        assertEquals("failed", progress.snapshot("complete").status())
    }

    /** Authored analytic arm chain; exercises shipping counter/analysis, not pose-model accuracy. */
    private fun arm(time: Long, angle: Double): PoseResult {
        val half = angle * PI / 360; val rise = 120 * sin(half)
        val points = listOf(Point2D(500.0, 500 + 2 * rise), Point2D(500 + 120 * cos(half), 500 + rise), Point2D(500.0, 500.0))
        return PoseResult(PresentationTime(time, 1_000_000), ImageSize(1000.0, 1000.0),
            listOf(PoseObservation(ArmMeasurement.Side.left.joints.zip(points).map { (joint, point) -> Landmark(joint, point, 1.0) })),
            "authored source-prefix contract", 0)
    }
    private class InjectedFailure : RuntimeException()
}
