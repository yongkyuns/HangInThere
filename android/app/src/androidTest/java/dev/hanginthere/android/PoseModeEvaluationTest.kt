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

/** Opt-in public diagnostic. All models share one decoded source before release. */
@RunWith(AndroidJUnit4::class)
class PoseModeEvaluationTest {
    private class Mode(val estimator: PoseEstimator, exercise: ExerciseCounter.Exercise, side: ArmMeasurement.Side) {
        val counter = ExerciseCounter(exercise, side)
        val latency = mutableListOf<Double>()
        val usableArms = ArmMeasurement.Side.entries.associateWith { 0 }.toMutableMap()
        var singlePersonFrames = 0
        var lastPose: PoseResult? = null
        var skippedFrames = 0
        fun consume(bitmap: Bitmap, pts: Long, edge: BarSegment): JsonObject {
            val started = System.nanoTime()
            val pose = estimator.estimate(bitmap, pts)
            latency += (System.nanoTime() - started) / 1e6
            return observe(pose,edge,latency.last())
        }
        fun consumeCrop(bitmap: Bitmap, pts: Long, edge: BarSegment, region: PersonInferenceRegion?): JsonObject {
            var raw: PoseResult? = null; var cropHash: String? = null; var milliseconds: Double? = null
            val pose = if (region == null) {
                skippedFrames++
                PoseResult(PresentationTime(pts,1_000_000),ImageSize(bitmap.width.toDouble(),bitmap.height.toDouble()),
                    emptyList(),"Diagnostic crop unavailable",1)
            } else {
                val crop = Bitmap.createBitmap(bitmap,region.left,region.top,region.width,region.height)
                try {
                    // Verify the actual SDK input is the exact finite source
                    // rectangle, without padding/resizing or caller mutation.
                    val expected = IntArray(region.width*region.height)
                    val actual = IntArray(expected.size)
                    bitmap.getPixels(expected,0,region.width,region.left,region.top,region.width,region.height)
                    crop.getPixels(actual,0,region.width,0,0,region.width,region.height)
                    check(expected.contentEquals(actual)) { "Crop pixels differ from canonical source" }
                    cropHash = pixelsHash(crop)
                    val started = System.nanoTime()
                    raw = estimator.estimate(crop,pts)
                    val restored = region.restore(requireNotNull(raw),ImageSize(bitmap.width.toDouble(),bitmap.height.toDouble()))
                    milliseconds = (System.nanoTime()-started)/1e6
                    latency += requireNotNull(milliseconds)
                    restored.copy(backend="MediaPipe Heavy CPU IMAGE person crop visibility/presence minimum")
                } finally { if(crop !== bitmap) crop.recycle() }
            }
            return JsonObject(observe(pose,edge,milliseconds) + buildJsonObject {
                put("region",region?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
                put("rawPose",raw?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
                put("cropPixelSha256",cropHash?.let(::JsonPrimitive) ?: JsonNull)
                put("inferencePerformed",region!=null)
            })
        }
        private fun observe(pose: PoseResult, edge: BarSegment, milliseconds: Double?): JsonObject {
            lastPose = pose
            check(pose.timestamp.timescale == 1_000_000)
            if (pose.people.size == 1) singlePersonFrames++
            val arms = ArmMeasurement.Side.entries.map { ArmMeasurement(pose, it) }
            arms.filter { it.estimate != null }.forEach { usableArms[it.side] = usableArms.getValue(it.side) + 1 }
            counter.consume(pose, edge)
            return buildJsonObject {
                put("pose", Json.encodeToJsonElement(pose)); put("arms", Json.encodeToJsonElement(arms))
                put("counter", Json.encodeToJsonElement(counter.summary))
                put("inferenceMillis", milliseconds?.let(::JsonPrimitive) ?: JsonNull)
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
                put("skippedFrames",skippedFrames)
            }
        }
    }

    @Test fun compareTrackingAndIndependentFrames() {
        val cropDiagnostic = InstrumentationRegistry.getArguments().getString("poseCropDiagnostic") == "true"
        assumeTrue("Explicit public pose input diagnostic only", cropDiagnostic ||
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
                val cropEstimator = if(cropDiagnostic) PoseEstimator.independentFramesForDiagnostic(app) else null
                try {
                    PoseEstimator(app).use { tracked ->
                    PoseEstimator.independentFramesForDiagnostic(app).use { redetected ->
                        val modes = linkedMapOf("video" to Mode(tracked, exercise, side), "image" to Mode(redetected, exercise, side))
                        cropEstimator?.let { modes["crop"] = Mode(it,exercise,side) }
                        File(output, "$id-${if(cropDiagnostic) "pose-crops" else "pose-modes"}.jsonl").bufferedWriter().use { trace ->
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
                                        val order = (if (frames % 2 == 0) listOf("video","image") else listOf("image","video")) +
                                            if(cropDiagnostic) listOf("crop") else emptyList()
                                        val observations = buildJsonObject {
                                            for (name in order) {
                                                val mode = modes.getValue(name)
                                                val observation = if(name=="crop") mode.consumeCrop(it.bitmap,it.presentationTimeUs,canonicalEdge,
                                                    PersonInferenceRegion.select(requireNotNull(modes.getValue("video").lastPose)))
                                                    else mode.consume(it.bitmap, it.presentationTimeUs, canonicalEdge)
                                                check(mode.lastPose!!.timestamp==PresentationTime(it.presentationTimeUs,1_000_000))
                                                check(mode.lastPose!!.imageSize==ImageSize(it.bitmap.width.toDouble(),it.bitmap.height.toDouble()))
                                                put(name,observation)
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
                } finally { cropEstimator?.close() }
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
        File(output, if(cropDiagnostic) "pose-crop-report.json" else "pose-mode-report.json").writeText(buildJsonObject {
            put("schemaVersion", if(cropDiagnostic) 2 else 1); put("policyVersion", ExerciseCounter.policyVersion); put("sdk", Build.VERSION.SDK_INT)
            put("modelSha256", PoseEstimator.MODEL_SHA256); put("poseArtifactSha256", PoseEstimator.ARTIFACT_SHA256)
            put("scope", "Fixed-edge diagnostic only; unchanged source/model/confidence/geometry/count rules; no Auto, athlete identity, form or physical parity verdict")
            put("latencyScope", "Per-mode SDK inference/copy/close; crop includes coordinate restoration. Excludes crop allocation/verification/hash, decode/counter/trace/model initialization. Crop runs after its VIDEO region witness; timing is not randomized. PSS retains all diagnostic models; not production performance")
            if(cropDiagnostic) { put("regionRecipeVersion",PersonInferenceRegion.recipeVersion); put("paddingFraction",PersonInferenceRegion.paddingFraction); put("minimumPaddingPixels",PersonInferenceRegion.minimumPaddingPixels) }
            put("cases", JsonArray(reports)); put("failures", Json.encodeToJsonElement(failures))
        }.toString())
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun cropInferenceKeepsCanonicalPixelsForPartialAndFullFrameRegions() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.BLACK)
        android.graphics.Canvas(source).drawRect(20f,100f,300f,121f,
            android.graphics.Paint().apply { color=android.graphics.Color.WHITE })
        val before = pixelsHash(source)
        try {
            PoseEstimator.independentFramesForDiagnostic(app).use { estimator ->
                val mode=Mode(estimator,ExerciseCounter.Exercise.pullUp,ArmMeasurement.Side.left)
                val edge=BarSegment(Point2D(20.0,100.0),Point2D(300.0,100.0))
                for((index,region) in listOf(PersonInferenceRegion(20,20,160,100),PersonInferenceRegion(0,0,320,240)).withIndex()) {
                    val observation=mode.consumeCrop(source,index*10_000L,edge,region)
                    assertTrue(observation.getValue("inferencePerformed").jsonPrimitive.boolean)
                    assertEquals(PresentationTime(index*10_000L,1_000_000),mode.lastPose!!.timestamp)
                    assertEquals(ImageSize(320.0,240.0),mode.lastPose!!.imageSize)
                    assertFalse(source.isRecycled); assertEquals(before,pixelsHash(source))
                }
            }
        } finally { source.recycle() }
    }

    companion object {
        private fun pixelsHash(bitmap: Bitmap): String {
            val buffer = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buffer)
            return MessageDigest.getInstance("SHA-256").digest(buffer.array()).hex()
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
