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
    @Serializable data class Step(val pose: PoseResult? = null, val interrupt: String? = null)
    @Serializable data class Input(val id: String, val exercise: ExerciseCounter.Exercise,
        val side: ArmMeasurement.Side, val referenceEdge: BarSegment? = null,
        val expectedMovements: Int, val steps: List<Step>)
    @Serializable data class Snapshot(val summary: ExerciseCounter.Summary,
        val event: ExerciseCounter.Event? = null, val pullUpProgress: PullUpProgress? = null,
        val dipProgress: DipProgress? = null)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private fun run(input: Input): Pair<List<Snapshot>, Snapshot> {
        val counter = ExerciseCounter(input.exercise, input.side)
        val snapshots = input.steps.map { step ->
            val event = step.interrupt?.let { counter.interrupt(it) }
                ?: step.pose?.let { counter.consume(it, input.referenceEdge) }
            Snapshot(counter.summary, event, counter.pullUpProgress, counter.dipProgress)
        }
        val event = counter.finish()
        assertEquals(input.expectedMovements, counter.observedMovements, input.id)
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
        var frames = 0
        for (case in cases) {
            val row=case.jsonObject; val input=json.decodeFromJsonElement<Input>(row.getValue("input"))
            val (snapshots, final)=run(input);frames+=snapshots.size
            equivalent(row.getValue("snapshots"),json.encodeToJsonElement(snapshots),input.id)
            equivalent(row.getValue("final"),json.encodeToJsonElement(final),input.id+".finish")
        }
        assertTrue(frames >= 180)
        val inputs=cases.map { json.decodeFromJsonElement<Input>(it.jsonObject.getValue("input")) }
        repeat(20) { inputs.forEach { run(it) } }
        val times=List(100) {
            val start=System.nanoTime();inputs.forEach { run(it) }
            (System.nanoTime()-start).toDouble()/frames
        }.sorted()
        val result=buildJsonObject {
            put("scope","same-host core only; includes counter initialization; excludes inference and camera")
            put("cases",cases.size);put("frames",frames);put("iterations",100)
            put("p50NanosecondsPerFrame",times[50]);put("p95NanosecondsPerFrame",times[95])
            put("swift",report.getValue("benchmark"))
        }
        val output=File(System.getenv("WORKOUT_PARITY_REPORT") ?: "../build/workout-oracle/kotlin-parity.json")
        output.parentFile.mkdirs();output.writeText(json.encodeToString(result))
    }
}
