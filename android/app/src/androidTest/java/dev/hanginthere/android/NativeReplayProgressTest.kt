package dev.hanginthere.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Real decoded/model frames with an injected late failure; no workout-accuracy claim. */
@RunWith(AndroidJUnit4::class)
class NativeReplayProgressTest {
    @Test fun nativeFailureDuringAcquisitionKeepsOnlyVerifiedPrefix() {
        var seen = 0
        val row = replayUntil { acquire, _ -> if (acquire && ++seen == 3) throw InjectedFailure() }
        assertEquals("interrupted", row.getValue("status").jsonPrimitive.content)
        assertEquals(3, row.getValue("frames").jsonPrimitive.int)
        assertTrue(row.getValue("acceptance_seconds").jsonArray.isEmpty())
        assertTrue(row.getValue("completed_seconds").jsonArray.isEmpty())
        assertFalse(row.getValue("source_clock_verified").jsonPrimitive.boolean)
    }

    @Test fun nativeFailureAfterAcceptanceCannotInventAReturn() {
        var observedAcceptance = false
        var phaseAtFailure: ExerciseCounter.Phase? = null
        var completedAtFailure: Int? = null
        val row = replayUntil { acquire, runtime ->
            if (!acquire && runtime.session.observedMovements > 0) {
                observedAcceptance = true
                phaseAtFailure = runtime.session.counter.phase
                completedAtFailure = runtime.session.record.completedReps.size
                throw InjectedFailure()
            }
        }
        assertTrue("Required positive fixture never reached its actual counting acceptance", observedAcceptance)
        assertEquals(ExerciseCounter.Phase.returning, phaseAtFailure)
        assertEquals(0, completedAtFailure)
        assertEquals("interrupted", row.getValue("status").jsonPrimitive.content)
        assertFalse(row.getValue("source_clock_verified").jsonPrimitive.boolean)
        assertEquals(1, row.getValue("acceptance_seconds").jsonArray.size)
        assertTrue(row.getValue("completed_seconds").jsonArray.isEmpty())
    }

    private fun replayUntil(afterFrame: (Boolean, WorkoutRuntime) -> Unit): JsonObject {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val manifest = assets.open("generated/manifest.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val fixture = manifest.getValue("clips").jsonArray.map { it.jsonObject }.single {
            it["qualificationRole"]?.jsonPrimitive?.content == "positive"
        }
        val temporary = File.createTempFile("progress-regression-", "", app.cacheDir)
        check(temporary.delete() && temporary.mkdir())
        val file = File(temporary, "source.mp4")
        try {
            assets.open("generated/${fixture.getValue("video").jsonPrimitive.content}").use { input -> file.outputStream().use { input.copyTo(it) } }
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            check(hash == fixture.getValue("sha256").jsonPrimitive.content)
            val result = NativeReplayEvaluator(app).evaluateAutomatic("progress_regression", temporary, file,
                ExerciseCounter.Exercise.valueOf(fixture.getValue("exercise").jsonPrimitive.content),
                ArmMeasurement.Side.valueOf(fixture.getValue("side").jsonPrimitive.content),
                fixture.getValue("expectedMovements").jsonPrimitive.int,
                fixture.getValue("expectedFrames").jsonPrimitive.int,
                fixture.getValue("sourcePtsMicroseconds").jsonArray.map { it.jsonPrimitive.long }, afterFrame)
            assertFalse(result.getValue("functionalParity").jsonPrimitive.boolean)
            assertNotNull("Injected failure was not retained", result["error"])
            assertTrue("Native evaluation failed before the injected boundary",
                result.getValue("error").jsonPrimitive.content.startsWith(InjectedFailure::class.java.name))
            val row = result.getValue("publicProgress").jsonObject
            assertTrue(row.getValue("progress_evidence").jsonObject.getValue("source_prefix_verified").jsonPrimitive.boolean)
            assertTrue(row.getValue("frames").jsonPrimitive.int in 1 until fixture.getValue("expectedFrames").jsonPrimitive.int)
            return row
        } finally { temporary.deleteRecursively() }
    }

    private class InjectedFailure : RuntimeException("Injected public evaluator regression")
}
