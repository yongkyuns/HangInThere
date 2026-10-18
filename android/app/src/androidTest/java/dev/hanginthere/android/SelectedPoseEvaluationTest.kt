package dev.hanginthere.android

import android.net.Uri
import android.os.Build
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
import java.security.MessageDigest

/** Opt-in measurement export on the full frozen corpus. No labels, apparatus
 * annotations, confidence changes, side selection or rep logic enter this run. */
@RunWith(AndroidJUnit4::class)
class SelectedPoseEvaluationTest {
    @Test fun exportEverySelectedPublicSourceObservation() {
        assumeTrue("Explicit full public source pose diagnostic only",
            InstrumentationRegistry.getArguments().getString("selectedPoseDiagnostic") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val output = requireNotNull(app.getExternalFilesDir("evaluation")); check(output.isDirectory || output.mkdirs())
        // Invalidate completion before any manifest read can fail on a rerun.
        val reportFile = File(output, "selected-pose-report.json"); check(!reportFile.exists() || reportFile.delete())
        val assets = instrumentation.context.assets
        val json = Json { encodeDefaults = true }
        val manifestBytes = assets.open("generated/selected-poses/manifest.json").use { it.readBytes() }
        val manifest = json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
        assertEquals(1, manifest.getValue("schemaVersion").jsonPrimitive.int)
        val fixtures = manifest.getValue("clips").jsonArray
        assertEquals(listOf("pullup_censored", "dip_rear", "dip_side", "dip_controlled_pexels"),
            fixtures.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        File(output, "selected-pose-manifest.json").writeBytes(manifestBytes)
        val summaries = mutableListOf<JsonObject>()
        for (entry in fixtures) {
            val fixture = entry.jsonObject
            val id = fixture.getValue("id").jsonPrimitive.content
            val video = fixture.getValue("video").jsonPrimitive.content
            check(video == "$id.mp4")
            val file = File(app.cacheDir, "$id-selected-pose.mp4")
            try {
                assets.open("generated/selected-poses/$video").use { input -> file.outputStream().use { input.copyTo(it) } }
                check(sha256(file) == fixture.getValue("sha256").jsonPrimitive.content) { "Selected source bytes changed: $id" }
                val pts = fixture.getValue("sourcePtsMicroseconds").jsonArray.map { it.jsonPrimitive.long }
                val expected = fixture.getValue("expectedFrames").jsonPrimitive.int
                check(expected > 0 && pts.size == expected && pts.first() >= 0 && pts.zipWithNext().all { it.first < it.second })
                val size = json.decodeFromJsonElement<ImageSize>(fixture.getValue("imageSize"))
                val traceFile = File(output, "selected-pose-$id-observations.jsonl")
                val latencies = mutableListOf<Double>()
                var frames = 0; var single = 0; var world = 0
                var decodedSize: ImageSize? = null
                val usable = ArmMeasurement.Side.entries.associateWith { 0 }.toMutableMap()
                PoseEstimator(app).use { estimator ->
                    traceFile.bufferedWriter().use { trace ->
                        VideoFrames(app, Uri.fromFile(file)).use { reader ->
                            assertEquals(size, ImageSize(reader.sourceUprightWidth.toDouble(), reader.sourceUprightHeight.toDouble()))
                            while (true) {
                                val frame = reader.next() ?: break
                                frame.use {
                                    check(frames < expected && it.presentationTimeUs == pts[frames]) { "Selected source PTS mismatch: $id/$frames" }
                                    val actualSize = ImageSize(it.bitmap.width.toDouble(), it.bitmap.height.toDouble())
                                    if (decodedSize == null) decodedSize = actualSize
                                    assertEquals(decodedSize, actualSize)
                                    val pixels = NativeEvaluationPixels.sha256(it.bitmap)
                                    val started = System.nanoTime()
                                    val pose = estimator.estimate(it.bitmap, it.presentationTimeUs)
                                    val milliseconds = (System.nanoTime() - started) / 1e6
                                    latencies += milliseconds
                                    assertEquals(PresentationTime(it.presentationTimeUs, 1_000_000), pose.timestamp)
                                    assertEquals(actualSize, pose.imageSize)
                                    check(!it.bitmap.isRecycled && pixels == NativeEvaluationPixels.sha256(it.bitmap))
                                    if (pose.people.size == 1) single++
                                    if (pose.people.isNotEmpty()) {
                                        assertEquals(EstimatedBodyGeometry.convention, pose.estimatedWorldConvention)
                                        pose.people.forEach { person ->
                                            check(requireNotNull(person.estimatedWorldLandmarks).all { point -> point.position.isFinite })
                                        }
                                        world++
                                    }
                                    ArmMeasurement.Side.entries.forEach { side ->
                                        if (EstimatedBodyGeometry.measure(pose, side) != null) usable[side] = usable.getValue(side) + 1
                                    }
                                    trace.write(JsonObject(json.encodeToJsonElement(pose).jsonObject + buildJsonObject {
                                        put("frameIndex", frames); put("pixelSha256", pixels); put("inferenceMillis", milliseconds)
                                    }).toString()); trace.newLine(); frames++
                                }
                            }
                        }
                    }
                }
                check(frames == expected) { "Incomplete selected source: $id $frames/$expected" }
                summaries += buildJsonObject {
                    put("id", id); put("frames", frames); put("singlePersonFrames", single); put("estimatedWorldFrames", world)
                    put("sourceSha256", fixture.getValue("sha256")); put("observations", traceFile.name)
                    put("observationsSha256", sha256(traceFile))
                    put("decodedImageSize", json.encodeToJsonElement(requireNotNull(decodedSize)))
                    put("estimatedBodyGeometryFrames", buildJsonObject { usable.forEach { (side, count) -> put(side.name, count) } })
                    put("inferenceLatencyMillis", NativeEvaluationMetrics.latency(latencies))
                }
            } finally { check(!file.exists() || file.delete()) }
        }
        reportFile.writeText(buildJsonObject {
            put("schemaVersion", 1); put("complete", true); put("sdk", Build.VERSION.SDK_INT)
            put("manifestSha256", MessageDigest.getInstance("SHA-256").digest(manifestBytes).hex())
            put("modelSha256", PoseEstimator.MODEL_SHA256); put("poseArtifact", PoseEstimator.ARTIFACT)
            put("poseArtifactSha256", PoseEstimator.ARTIFACT_SHA256)
            put("pixelReceiptEncoding", "Android Bitmap.copyPixelsToBuffer storage including row stride; not a PNG or cross-platform color hash")
            put("scope", "Whole-source native SDK measurement export; no event, load, apparatus or form qualification")
            put("latencyScope", "SDK inference/copy/close only; excludes decoder, bitmap receipts, serialization, geometry queries and model initialization; emulator only")
            put("clips", JsonArray(summaries))
        }.toString() + "\n")
    }

    private fun sha256(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256"); val block = ByteArray(65536)
        while (true) { val count = stream.read(block); if (count < 0) break; digest.update(block, 0, count) }
        digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
