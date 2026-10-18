package dev.hanginthere.core

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The oracle is built from production Swift, never from expected Kotlin output. */
class SwiftParityTest {
    @Serializable data class Step(val pose: PoseResult? = null, val interrupt: String? = null,
        val expectedPhase: ExerciseCounter.Phase? = null, val expectedMovements: Int? = null)
    @Serializable data class Input(val id: String, val exercise: ExerciseCounter.Exercise,
        val side: ArmMeasurement.Side, val referenceEdge: BarSegment? = null,
        val expectedMovements: Int, val steps: List<Step>, val expectedPartialAttempts: Int? = null)
    @Serializable data class Snapshot(val summary: ExerciseCounter.Summary,
        val event: ExerciseCounter.Event? = null, val pullUpProgress: PullUpProgress? = null,
        val dipProgress: DipProgress? = null)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private fun run(input: Input): Pair<List<Snapshot>, Snapshot> {
        val counter = ExerciseCounter(input.exercise, input.side)
        val snapshots = input.steps.mapIndexed { index, step ->
            val event = step.interrupt?.let { counter.interrupt(it) }
                ?: step.pose?.let { counter.consume(it, input.referenceEdge) }
            step.expectedPhase?.let { assertEquals(it, counter.phase, "${input.id} step $index phase") }
            step.expectedMovements?.let { assertEquals(it, counter.observedMovements, "${input.id} step $index movements") }
            Snapshot(counter.summary, event, counter.pullUpProgress, counter.dipProgress)
        }
        val event = counter.finish()
        assertEquals(input.expectedMovements, counter.observedMovements, input.id)
        input.expectedPartialAttempts?.let { assertEquals(it, counter.partialAttempts, input.id) }
        return snapshots to Snapshot(counter.summary, event, counter.pullUpProgress, counter.dipProgress)
    }

    private fun equivalent(expected: JsonElement, actual: JsonElement, path: String) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                val e = expected.filterValues { it != JsonNull }; val a = actual.filterValues { it != JsonNull }
                assertEquals(e.keys, a.keys, "$path keys")
                for (key in e.keys) equivalent(e.getValue(key), a.getValue(key), "$path.$key")
            }
            expected is JsonArray && actual is JsonArray -> {
                assertEquals(expected.size, actual.size, "$path length")
                expected.indices.forEach { equivalent(expected[it], actual[it], "$path[$it]") }
            }
            expected is JsonPrimitive && actual is JsonPrimitive &&
                !expected.isString && !actual.isString && expected.doubleOrNull != null && actual.doubleOrNull != null ->
                assertTrue(abs(expected.double-actual.double) <= 1e-9, "$path: $expected != $actual")
            else -> assertEquals(expected, actual, path)
        }
    }

    @Test fun everyFrameMatchesSwiftPolicyAndExplicitMovementLabels() {
        val path = System.getenv("WORKOUT_ORACLE") ?: "../build/workout-oracle/oracle.json"
        val report = json.parseToJsonElement(File(path).readText()).jsonObject
        assertEquals(8, report.getValue("policyVersion").jsonPrimitive.int)
        val cases = report.getValue("cases").jsonArray
        assertTrue(cases.size >= 25, "Parity fixture unexpectedly empty or reduced")
        val ids = cases.map { it.jsonObject.getValue("input").jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        for(side in ArmMeasurement.Side.entries)
            for(kind in listOf("three-and-incomplete","insufficient-travel","opposite-still"))
                assertTrue("dip-projected-$kind-$side" in ids,"Missing projected dip regression")
        for (side in ArmMeasurement.Side.entries) for (kind in listOf("cycle", "partial"))
            for (ending in listOf("witness", "contradicted", "reacquired"))
                assertTrue("dip-return-$kind-$side-$ending" in ids, "Missing dip return witness regression")
        for (side in ArmMeasurement.Side.entries) for (prefix in listOf(
            "dip-unconfirmed-return-reacquisition", "dip-unconfirmed-return-partial-reacquisition",
            "dip-unconfirmed-return-upper-jitter", "dip-unconfirmed-return-sub-top-bounce",
            "dip-return-witness-same-height", "dip-return-witness-higher-angle-lower", "dip-return-witness-higher-angle-first",
            "dip-return-no-synthetic-maxima", "dip-return-bounded-history", "dip-return-source-reset", "dip-departure-plateau",
            "dip-outbound-low-witness", "dip-outbound-no-combined-lows"))
            assertTrue("$prefix-$side" in ids, "Missing unconfirmed dip return regression")
        for (side in ArmMeasurement.Side.entries) {
            for (kind in listOf("outbound", "return", "readyProjected", "readyExtended", "elbowOutage"))
                for (ending in listOf("", "-recovery"))
                    assertTrue("dip-new-arm-$kind$ending-$side" in ids, "Missing new dip arm baseline regression")
            for (prefix in listOf("dip-new-arm-dwell", "dip-anchored-arm-brief-outage", "dip-new-arm-current-return-dwell", "dip-new-arm-initial-bent"))
                assertTrue("$prefix-$side" in ids, "Missing dip visibility dwell regression")
        }
        for (exercise in ExerciseCounter.Exercise.entries) for (side in ArmMeasurement.Side.entries) {
            for (origin in listOf("ordinary", "large")) for (kind in listOf("exact", "short"))
                assertTrue("source-clock-$exercise-$side-$origin-$kind" in ids, "Missing exact source dwell regression")
            for (kind in listOf("gap", "gap-over"))
                assertTrue("source-clock-$exercise-$side-$kind" in ids, "Missing exact source gap regression")
        }
        var frames = 0
        for (case in cases) {
            val row=case.jsonObject; val input=json.decodeFromJsonElement<Input>(row.getValue("input"))
            val (snapshots, final)=run(input);frames+=snapshots.size
            equivalent(row.getValue("snapshots"),json.encodeToJsonElement(snapshots),input.id)
            equivalent(row.getValue("final"),json.encodeToJsonElement(final),input.id+".finish")
        }
        assertTrue(frames >= 180)
        val inputs=cases.map { json.decodeFromJsonElement<Input>(it.jsonObject.getValue("input")) }
        repeat(1000) { inputs.forEach { run(it) } }
        var checksum=0.0
        val times=List(100) {
            val start=System.nanoTime()
            inputs.forEach { input ->
                run(input).first.forEach { snapshot -> checksum += snapshot.summary.observedMovements.toDouble() +
                    (snapshot.pullUpProgress?.fraction ?: 0.0) + (snapshot.dipProgress?.fraction ?: 0.0) }
            }
            (System.nanoTime()-start).toDouble()/frames
        }.sorted()
        val result=buildJsonObject {
            put("scope","same-host core only; includes counter initialization; excludes inference and camera")
            put("cases",cases.size);put("frames",frames);put("iterations",100)
            put("p50NanosecondsPerFrame",times[50]);put("p95NanosecondsPerFrame",times[95])
            put("swift",report.getValue("benchmark"))
            put("checksum",checksum)
        }
        assertTrue(abs(report.getValue("benchmark").jsonObject.getValue("checksum").jsonPrimitive.double-checksum)<1e-7,
            "Both benchmark loops must consume matching movement and progress outputs")
        val output=File(System.getenv("WORKOUT_PARITY_REPORT") ?: "../build/workout-oracle/kotlin-parity.json")
        output.parentFile.mkdirs();output.writeText(json.encodeToString(result))
    }
}
