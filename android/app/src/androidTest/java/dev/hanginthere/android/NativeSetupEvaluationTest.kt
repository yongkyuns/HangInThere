package dev.hanginthere.android

import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.camera.PhoneAttitude
import dev.hanginthere.android.replay.VideoFrames
import dev.hanginthere.android.session.AnalysisPipeline
import dev.hanginthere.android.vision.PoseEstimator
import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Authored held source pixels isolate native Auto + live dwell; never replay-review rewind. */
@RunWith(AndroidJUnit4::class)
class NativeSetupEvaluationTest {
    @Test fun heldPublicPixelsAcquireApparatusAndCompleteLiveCountdown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val output = requireNotNull(app.getExternalFilesDir("evaluation")); output.mkdirs()
        val json = Json { encodeDefaults = true }
        val fixtures = assets.open("generated/manifest.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject.getValue("setupClips").jsonArray
        }
        check(fixtures.map { it.jsonObject.getValue("exercise").jsonPrimitive.content }.toSet() == setOf("pullUp", "dip") && fixtures.size == 2)
        val reports = mutableListOf<JsonElement>()
        val failures = mutableListOf<String>()
        fixtures.forEach { entry ->
            val fixture = entry.jsonObject
            val asset = fixture.getValue("video").jsonPrimitive.content
            check(asset in setOf("pullup-held-start.mp4", "dip-held-start.mp4"))
            val expected = ExerciseCounter.Exercise.valueOf(fixture.getValue("exercise").jsonPrimitive.content)
            val expectedFrames = fixture.getValue("expectedFrames").jsonPrimitive.int
            val expectedPts = fixture.getValue("sourcePtsSeconds").jsonArray
            check(expectedFrames == 72 && expectedPts.size == expectedFrames)
            val id = fixture.getValue("id").jsonPrimitive.content
            val file = File(app.cacheDir, asset)
            var frames = 0
            var firstReference: Double? = null
            var firstCountdown: Double? = null
            var firstRunning: Double? = null
            var lastTime: Double? = null
            val countdownValues = linkedSetOf<Int>()
            var lastSnapshot: WorkoutRuntime.Snapshot? = null
            var sourceHash: String? = null
            var error: String? = null
            try {
                assets.open("generated/$asset").use { input -> file.outputStream().use { input.copyTo(it) } }
                sourceHash = file.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                check(sourceHash == fixture.getValue("sha256").jsonPrimitive.content) { "Authored fixture checksum changed" }
                File(output, "$id-setup-observations.jsonl").bufferedWriter().use { poses ->
                    File(output, "$id-setup-timeline.jsonl").bufferedWriter().use { timeline ->
                        AnalysisPipeline(app, WorkoutExerciseSelection.automatic, ArmMeasurement.Side.left).use { pipeline ->
                            VideoFrames(app, Uri.fromFile(file)).use { reader ->
                                while (true) {
                                    val frame = reader.next() ?: break
                                    frame.use {
                                        val time = it.presentationTimeUs / 1e6
                                        check(frames < expectedFrames && kotlin.math.abs(time - expectedPts[frames].jsonPrimitive.double) <= 1e-6) {
                                            "Source PTS changed at frame $frames: $time"
                                        }
                                        val sample = PhoneAttitude.Sample(requireNotNull(PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)), time)
                                        val result = pipeline.process(it.bitmap, it.presentationTimeUs, sample)
                                        val snapshot = result.snapshot
                                        check(!snapshot.isReplayReview) { "Live setup test must never bypass countdown through rewind" }
                                        if (snapshot.referenceEdge != null && firstReference == null) firstReference = time
                                        snapshot.countdownSeconds?.let { remaining ->
                                            if (firstCountdown == null) firstCountdown = time
                                            countdownValues += remaining
                                        }
                                        if (snapshot.phase == LiveSetSession.Phase.running && firstRunning == null) firstRunning = time
                                        poses.write(json.encodeToString(PoseResult.serializer(), result.pose)); poses.newLine()
                                        timeline.write(buildJsonObject {
                                            put("sourceSeconds", time); put("snapshot", json.encodeToJsonElement(WorkoutRuntime.Snapshot.serializer(), snapshot))
                                        }.toString()); timeline.newLine()
                                        lastSnapshot = snapshot; lastTime = time; frames++
                                    }
                                }
                            }
                            pipeline.runtime.stop() // Finish actual counter events before releasing native ownership.
                            check(pipeline.runtime.snapshot.observedMovements == 0) { "Held pixels produced a false movement after finishing" }
                        }
                    }
                }
            } catch (failure: Throwable) { error = failure.stackTraceToString() }
            finally { file.delete() }
            val countdownDuration = if (firstRunning != null && firstCountdown != null) firstRunning!! - firstCountdown!! else null
            val passed = error == null && frames == expectedFrames && lastSnapshot?.exercise == expected && firstReference != null
                && countdownValues.containsAll(listOf(3, 2, 1)) && countdownDuration?.let { it >= 3.0 - 1e-6 } == true
                && lastSnapshot?.phase == LiveSetSession.Phase.running && lastSnapshot?.observedMovements == 0
            if (!passed) failures += "$id: Auto/setup/countdown/no-false-count qualification failed; inspect setup timeline"
            reports += buildJsonObject {
                put("id", id); put("asset", asset); put("sha256", sourceHash?.let(::JsonPrimitive) ?: JsonNull)
                put("expectedExercise", expected.name); put("decodedFrames", frames); put("expectedFrames", expectedFrames)
                put("selectedExercise", lastSnapshot?.exercise?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("acquiredActualContours", firstReference != null); put("firstReferenceSeconds", firstReference?.let(::JsonPrimitive) ?: JsonNull)
                put("firstCountdownSeconds", firstCountdown?.let(::JsonPrimitive) ?: JsonNull)
                put("countdownValues", JsonArray(countdownValues.map(::JsonPrimitive)))
                put("firstRunningSeconds", firstRunning?.let(::JsonPrimitive) ?: JsonNull)
                put("countdownDurationSeconds", countdownDuration?.let(::JsonPrimitive) ?: JsonNull)
                put("lastSourceSeconds", lastTime?.let(::JsonPrimitive) ?: JsonNull)
                put("finalPhase", lastSnapshot?.phase?.name ?: "unknown")
                put("observedMovements", lastSnapshot?.observedMovements ?: -1)
                put("setupIssue", lastSnapshot?.setupIssue?.let(::JsonPrimitive) ?: JsonNull)
                put("error", error?.let(::JsonPrimitive) ?: JsonNull); put("pass", passed)
            }
        }
        File(output, "setup-report.json").writeText(buildJsonObject {
            put("schemaVersion", 1); put("scope", "Authored repeated public-source pixels: Auto apparatus acquisition, live 3-second countdown and zero false counts; no dynamic accuracy or naturally observed user hold claim")
            put("attitudeScope", "virtual fixed recording quaternion at exact source PTS; actual OpenCV scene registration required")
            put("rewindUsed", false); put("backend", PoseEstimator.ARTIFACT); put("backendAarSha256", PoseEstimator.ARTIFACT_SHA256)
            put("modelSha256", PoseEstimator.MODEL_SHA256); put("apiLevel", Build.VERSION.SDK_INT)
            put("deviceFingerprint", Build.FINGERPRINT); put("abis", JsonArray(Build.SUPPORTED_ABIS.map(::JsonPrimitive)))
            put("cases", JsonArray(reports)); put("failures", JsonArray(failures.map(::JsonPrimitive)))
        }.toString())
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
