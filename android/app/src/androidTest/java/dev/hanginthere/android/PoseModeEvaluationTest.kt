package dev.hanginthere.android

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.replay.VideoFrames
import dev.hanginthere.android.vision.PoseEstimator
import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Opt-in public diagnostic. Both models consume each decoded bitmap before it is released. */
@RunWith(AndroidJUnit4::class)
class PoseModeEvaluationTest {
    private class Mode(val estimator: PoseEstimator, exercise: ExerciseCounter.Exercise, side: ArmMeasurement.Side) {
        val counter = ExerciseCounter(exercise, side)
        val latency = mutableListOf<Double>()
        val usableArms = ArmMeasurement.Side.entries.associateWith { 0 }.toMutableMap()
        var singlePersonFrames = 0
        fun consume(bitmap: Bitmap, pts: Long, edge: BarSegment): JsonObject {
            val started = System.nanoTime()
            val pose = estimator.estimate(bitmap, pts)
            latency += (System.nanoTime() - started) / 1e6
            check(pose.timestamp == PresentationTime(pts, 1_000_000))
            check(pose.imageSize == ImageSize(bitmap.width.toDouble(), bitmap.height.toDouble()))
            if (pose.people.size == 1) singlePersonFrames++
            val arms = ArmMeasurement.Side.entries.map { ArmMeasurement(pose, it) }
            arms.filter { it.estimate != null }.forEach { usableArms[it.side] = usableArms.getValue(it.side) + 1 }
            counter.consume(pose, edge)
            return buildJsonObject {
                put("pose", Json.encodeToJsonElement(pose)); put("arms", Json.encodeToJsonElement(arms))
                put("counter", Json.encodeToJsonElement(counter.summary))
                put("inferenceMillis", latency.last())
            }
        }
        fun summary(): JsonObject {
            counter.finish()
            return buildJsonObject {
                put("observedMovements", counter.observedMovements)
                put("singlePersonFrames", singlePersonFrames)
                put("usableArmFrames", buildJsonObject { usableArms.forEach { (side, count) -> put(side.name, count) } })
                put("events", Json.encodeToJsonElement(counter.events))
                put("inferenceLatencyMillis", NativeEvaluationMetrics.latency(latency))
            }
        }
    }

    @Test fun compareTrackingAndIndependentFrames() {
        assumeTrue("Explicit public pose-mode diagnostic only",
            InstrumentationRegistry.getArguments().getString("poseModeDiagnostic") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val manifest = assets.open("generated/manifest.json").bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        val output = requireNotNull(app.getExternalFilesDir("evaluation")); output.mkdirs()
        val reports = mutableListOf<JsonObject>(); val failures = mutableListOf<String>()
        for (entry in manifest.getValue("clips").jsonArray) {
            val fixture = entry.jsonObject
            val id = fixture.getValue("id").jsonPrimitive.content
            val file = File(app.cacheDir, "$id-pose-modes.mp4")
            try {
                assets.open("generated/${fixture.getValue("video").jsonPrimitive.content}").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                val hash = file.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                    digest.digest().hex()
                }
                check(hash == fixture.getValue("sha256").jsonPrimitive.content) { "Diagnostic fixture checksum mismatch" }
                val exercise = ExerciseCounter.Exercise.valueOf(fixture.getValue("exercise").jsonPrimitive.content)
                val side = ArmMeasurement.Side.valueOf(fixture.getValue("side").jsonPrimitive.content)
                val edge = json.decodeFromJsonElement<BarSegment>(fixture.getValue("referenceEdge"))
                val pts = fixture.getValue("sourcePtsMicroseconds").jsonArray.map { it.jsonPrimitive.long }
                val expectedFrames = fixture.getValue("expectedFrames").jsonPrimitive.int
                check(pts.size == expectedFrames)
                var frames = 0; var peakCombinedPss = 0
                var summaries: JsonObject? = null
                PoseEstimator(app).use { tracked ->
                    PoseEstimator.independentFramesForDiagnostic(app).use { redetected ->
                        val modes = linkedMapOf("video" to Mode(tracked, exercise, side), "image" to Mode(redetected, exercise, side))
                        File(output, "$id-pose-modes.jsonl").bufferedWriter().use { trace ->
                            VideoFrames(app, Uri.fromFile(file)).use { reader ->
                                val referenceSize = fixture["referenceImageSize"]?.let { json.decodeFromJsonElement<ImageSize>(it) }
                                    ?: ImageSize(reader.sourceUprightWidth.toDouble(), reader.sourceUprightHeight.toDouble())
                                check(referenceSize.isValid)
                                while (true) {
                                    val frame = reader.next() ?: break
                                    frame.use {
                                        check(frames < expectedFrames && it.presentationTimeUs == pts[frames]) { "Diagnostic source PTS mismatch" }
                                        fun scale(point: Point2D) = Point2D(point.x * it.bitmap.width / referenceSize.width,
                                            point.y * it.bitmap.height / referenceSize.height)
                                        val canonicalEdge = BarSegment(scale(edge.a), scale(edge.b))
                                        val inputHash = pixelsHash(it.bitmap)
                                        // Alternate order to avoid always assigning first-call effects to VIDEO.
                                        val order = if (frames % 2 == 0) modes.keys.toList() else modes.keys.reversed()
                                        val observations = buildJsonObject {
                                            for (name in order) {
                                                put(name, modes.getValue(name).consume(it.bitmap, it.presentationTimeUs, canonicalEdge))
                                                check(!it.bitmap.isRecycled && pixelsHash(it.bitmap) == inputHash) { "Model changed shared input pixels" }
                                            }
                                        }
                                        trace.write(buildJsonObject {
                                            put("frameIndex", frames); put("sourcePtsMicroseconds", it.presentationTimeUs)
                                            put("pixelSha256", inputHash); put("inferenceOrder", Json.encodeToJsonElement(order))
                                            put("referenceEdge", Json.encodeToJsonElement(canonicalEdge)); put("modes", observations)
                                        }.toString()); trace.newLine()
                                        frames++
                                        val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                                        peakCombinedPss = maxOf(peakCombinedPss, memory.totalPss)
                                    }
                                }
                            }
                        }
                        check(frames == expectedFrames) { "Incomplete diagnostic source: $frames/$expectedFrames" }
                        summaries = buildJsonObject { modes.forEach { (name, mode) -> put(name, mode.summary()) } }
                    }
                }
                reports += buildJsonObject {
                    put("id", id); put("sha256", hash); put("frames", frames)
                    put("expectedMovements", fixture.getValue("expectedMovements")); put("expectedFrames", expectedFrames)
                    put("sourcePtsMicroseconds", Json.encodeToJsonElement(pts)); put("exercise", exercise.name); put("side", side.name)
                    put("peakCombinedPssKb", peakCombinedPss); put("modes", requireNotNull(summaries))
                }
            } catch (failure: Throwable) {
                failures += "$id: ${failure.message}"
                reports += buildJsonObject { put("id", id); put("error", failure.stackTraceToString()) }
            } finally { file.delete() }
        }
        File(output, "pose-mode-report.json").writeText(buildJsonObject {
            put("schemaVersion", 1); put("policyVersion", ExerciseCounter.policyVersion); put("sdk", Build.VERSION.SDK_INT)
            put("modelSha256", PoseEstimator.MODEL_SHA256); put("poseArtifactSha256", PoseEstimator.ARTIFACT_SHA256)
            put("scope", "Fixed-edge diagnostic only; identical decoded pixels; unchanged model/confidence/geometry/count rules; no Auto or physical parity verdict")
            put("latencyScope", "Per-mode inference including SDK input copy/close; excludes decode/counter/hash/trace/model initialization. PSS retains both models; not production pipeline qualification")
            put("cases", JsonArray(reports)); put("failures", Json.encodeToJsonElement(failures))
        }.toString())
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun pixelsHash(bitmap: Bitmap): String {
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
