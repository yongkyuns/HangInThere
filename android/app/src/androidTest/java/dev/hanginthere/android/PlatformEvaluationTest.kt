package dev.hanginthere.android

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.camera.CanonicalPixels
import dev.hanginthere.android.camera.PhoneAttitude
import dev.hanginthere.android.session.AnalysisPipeline
import dev.hanginthere.android.session.ReplayOutcome
import dev.hanginthere.android.replay.VideoFrames
import dev.hanginthere.android.vision.ObservedEdges
import dev.hanginthere.android.vision.PoseEstimator
import dev.hanginthere.android.vision.SceneRegistration
import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.nio.ByteBuffer

/** Device/emulator evaluation uses the exact production decoder, CPU model and observed-contour fitter. */
@RunWith(AndroidJUnit4::class)
class PlatformEvaluationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    @Test fun bulkYuvMatchesIndependentScalarColorsAcrossCropAndPlaneLayouts() {
        fun checkLayout(width: Int, height: Int, crop: Rect, yStride: Int, chromaStride: Int) {
            fun sample(plane: Int, x: Int, y: Int): Int = when (plane) {
                0 -> 16 + (x * 19 + y * 13) % 220
                1 -> 16 + (x * 37 + y * 29) % 225
                else -> 16 + (x * 41 + y * 17) % 225
            }
            val planes = (0..2).map { index ->
                val divisor = if (index == 0) 1 else 2
                val columns = (width + divisor - 1) / divisor; val rows = (height + divisor - 1) / divisor
                val pixelStride = if (index == 0) yStride else chromaStride
                val rowStride = columns * pixelStride + 7
                val start = 3 + index * 4
                // Last row has no trailing padding, matching camera/codec plane views.
                val buffer = ByteBuffer.allocate(start + (rows - 1) * rowStride + (columns - 1) * pixelStride + 1)
                for (y in 0 until rows) for (x in 0 until columns) buffer.put(start + y * rowStride + x * pixelStride, sample(index, x, y).toByte())
                buffer.position(start)
                CanonicalPixels.Plane(buffer, rowStride, pixelStride)
            }
            val positions = planes.map { it.buffer.position() }
            val bitmap = CanonicalPixels.yuv(width, height, crop, planes)
            try {
                for (y in crop.top until crop.bottom) for (x in crop.left until crop.right) {
                    val luminance = (sample(0, x, y) - 16).coerceAtLeast(0)
                    val u = sample(1, x / 2, y / 2) - 128; val v = sample(2, x / 2, y / 2) - 128
                    val expected = listOf(((298 * luminance + 409 * v + 128) shr 8).coerceIn(0, 255),
                        ((298 * luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255),
                        ((298 * luminance + 516 * u + 128) shr 8).coerceIn(0, 255))
                    val pixel = bitmap.getPixel(x - crop.left, y - crop.top)
                    val actual = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    expected.zip(actual).forEachIndexed { channel, (reference, observed) ->
                        assertTrue("YUV crop/stride color at $x,$y channel $channel: $reference vs $observed",
                            kotlin.math.abs(reference - observed) <= 2)
                    }
                    assertEquals(255, Color.alpha(pixel))
                }
                assertEquals(positions, planes.map { it.buffer.position() })
            } finally { bitmap.recycle() }
        }
        checkLayout(16, 12, Rect(2, 2, 14, 10), 1, 1)
        checkLayout(16, 12, Rect(2, 2, 14, 10), 1, 2)
        checkLayout(16, 12, Rect(2, 2, 14, 10), 2, 2)
        checkLayout(15, 11, Rect(3, 1, 13, 9), 1, 2) // Odd-origin scalar fallback.
    }
    @Test fun poseSdkDoesNotRecycleCallerPixels() {
        val source = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLACK)
        android.graphics.Canvas(source).drawRect(20f, 100f, 300f, 121f,
            android.graphics.Paint().apply { color = Color.WHITE })
        try {
            PoseEstimator(app).use { estimator ->
                estimator.estimate(source, 0)
                assertFalse("SDK must only recycle its own input copy", source.isRecycled)
                assertEquals(Color.WHITE, source.getPixel(50, 110))
                val contours = ObservedEdges().detect(source, BarRegion(0.0, 60.0, 320.0, 180.0))
                assertTrue("Caller pixels must remain available to actual OpenCV", contours.count { it.edge.length >= 200 } >= 2)
                source.copy(Bitmap.Config.ARGB_8888, false).let { display ->
                    assertEquals(Color.WHITE, display.getPixel(50, 110)); display.recycle()
                }
                estimator.estimate(source, 33_000)
                assertFalse("Second increasing-PTS inference must preserve the caller", source.isRecycled)
            }
        } finally { source.recycle() }
    }
    @Test fun publicVideoFunctionalAndPerformanceEvaluation() {
        val manifest = assets.open("generated/manifest.json").bufferedReader().use { json.parseToJsonElement(it.readText()) }
        val fixtures = if (manifest is JsonArray) manifest else manifest.jsonObject.getValue("clips").jsonArray
        assertTrue("No approved public video fixtures", fixtures.isNotEmpty())
        val reports = mutableListOf<JsonElement>()
        val failures = mutableListOf<String>()
        val accuracyFailures = mutableListOf<String>()
        val output = requireNotNull(app.getExternalFilesDir("evaluation")); output.mkdirs()
        fixtures.forEach { entry ->
            val fixture = entry.jsonObject
            val id = fixture.getValue("id").jsonPrimitive.content
            val role = fixture["qualificationRole"]?.jsonPrimitive?.content
            try {
                require(role in setOf("positive", "diagnostic")) { "Unknown qualification role for $id: $role" }
                val videoName = fixture.getValue("video").jsonPrimitive.content
                val file = File(app.cacheDir, "$id-evaluation.mp4")
                assets.open(if (videoName.startsWith("generated/")) videoName else "generated/$videoName").use { input -> file.outputStream().use { input.copyTo(it) } }
                val checksum = file.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                    while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                assertEquals("Fixture provenance $id", fixture.getValue("sha256").jsonPrimitive.content, checksum)
                val exercise = ExerciseCounter.Exercise.valueOf(fixture.getValue("exercise").jsonPrimitive.content)
                val side = ArmMeasurement.Side.valueOf(fixture.getValue("side").jsonPrimitive.content)
                val edge = json.decodeFromJsonElement<BarSegment>(fixture.getValue("referenceEdge"))
                val counter = ExerciseCounter(exercise, side)
                var canonicalEdge: BarSegment? = null
                val latencies = mutableListOf<Double>(); val decodeLatencies = mutableListOf<Double>(); val contourLatencies = mutableListOf<Double>(); val sceneLatencies = mutableListOf<Double>()
                var sceneValidFrames = 0; var sceneLastSeconds: Double? = null
                var frames = 0; var peopleFrames = 0; var multiplePeopleFrames = 0; var candidates = 0
                var peakPssKb = 0; var peakJavaBytes = 0L; var peakNativeBytes = 0L
                val started = System.nanoTime()
                val lines = File(output, "$id-observations.jsonl").bufferedWriter()
                try {
                    SceneRegistration().use { registration ->
                    PoseEstimator(app).use { estimator ->
                        val detector = ObservedEdges()
                        VideoFrames(app, Uri.fromFile(file)).use { reader ->
                            var previous = -1L
                            while (true) {
                                val decodeStart = System.nanoTime()
                                val frame = reader.next() ?: break
                                decodeLatencies += (System.nanoTime() - decodeStart) / 1e6
                                frame.use {
                                    assertTrue("Non-increasing source PTS", it.presentationTimeUs > previous); previous = it.presentationTimeUs
                                    val expectedPts = fixture.getValue("sourcePtsSeconds").jsonArray
                                    assertTrue("Decoded unexpected extra frame", frames < expectedPts.size)
                                    assertEquals("Source PTS at $frames", expectedPts[frames].jsonPrimitive.double, it.presentationTimeUs / 1e6, 1e-6)
                                    val poseStart = System.nanoTime(); val pose = estimator.estimate(it.bitmap, it.presentationTimeUs)
                                    latencies += (System.nanoTime() - poseStart) / 1e6
                                    assertEquals(it.bitmap.width.toDouble(), pose.imageSize.width, 0.0)
                                    assertEquals(it.bitmap.height.toDouble(), pose.imageSize.height, 0.0)
                                    if (pose.people.size == 1) peopleFrames++ else if (pose.people.size > 1) multiplePeopleFrames++
                                    if (frames == 0) registration.freeze(it.bitmap)
                                    if (sceneLastSeconds?.let { last -> pose.timestamp.seconds - last >= AutomaticPullUpBar.samplingInterval } != false) {
                                        val sceneStart = System.nanoTime(); val evidence = registration.observe(it.bitmap)
                                        sceneLatencies += (System.nanoTime() - sceneStart) / 1e6
                                        if (evidence.translations.size >= 2 && evidence.scaleFraction != null) sceneValidFrames++
                                        sceneLastSeconds = pose.timestamp.seconds
                                    }
                                    val crop = AutomaticApparatusReference.search(pose, exercise)
                                    if (crop != null) {
                                        val contourStart = System.nanoTime()
                                        candidates += crop.regions.sumOf { region -> detector.detect(it.bitmap, region).size }
                                        contourLatencies += (System.nanoTime() - contourStart) / 1e6
                                    }
                                    val sourceSize = fixture["referenceImageSize"]?.jsonObject
                                    val referenceWidth = sourceSize?.getValue("width")?.jsonPrimitive?.double ?: reader.sourceUprightWidth.toDouble()
                                    val referenceHeight = sourceSize?.getValue("height")?.jsonPrimitive?.double ?: reader.sourceUprightHeight.toDouble()
                                    fun scalePoint(point: Point2D) = Point2D(point.x * pose.imageSize.width / referenceWidth, point.y * pose.imageSize.height / referenceHeight)
                                    canonicalEdge = BarSegment(scalePoint(edge.a), scalePoint(edge.b))
                                    counter.consume(pose, canonicalEdge)
                                    lines.write(Json.encodeToString(PoseResult.serializer(), pose)); lines.newLine()
                                    frames++
                                    if (frames % 5 == 0) {
                                        val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
                                        peakPssKb = maxOf(peakPssKb, memory.totalPss)
                                        peakNativeBytes = maxOf(peakNativeBytes, Debug.getNativeHeapAllocatedSize())
                                        val heap = Runtime.getRuntime(); peakJavaBytes = maxOf(peakJavaBytes, heap.totalMemory() - heap.freeMemory())
                                    }
                                }
                            }
                        }
                    }
                    }
                } finally { lines.close() }
                counter.finish()
                val expected = fixture.getValue("expectedMovements").jsonPrimitive.int
                val expectedFrames = fixture.getValue("expectedFrames").jsonPrimitive.int
                if (frames != expectedFrames) failures += "$id expected $expectedFrames decoded frames, got $frames"
                val elapsed = (System.nanoTime() - started) / 1e9
                val production = evaluateProductionReplay(file, exercise, side, expected, expectedFrames, fixture.getValue("sourcePtsSeconds").jsonArray)
                file.delete()
                if (production["error"] != null) failures += "$id production native execution failed: ${production.getValue("error").jsonPrimitive.content}"
                else if (production.getValue("singlePersonFrames").jsonPrimitive.int == 0) failures += "$id production native execution produced no single-person poses"
                val fixedEdgeMatches = counter.observedMovements == expected
                val productionMatches = production.getValue("functionalParity").jsonPrimitive.boolean
                val caseAccuracyFailures = mutableListOf<String>()
                if (!fixedEdgeMatches) caseAccuracyFailures += "$id fixed edge expected $expected movements, got ${counter.observedMovements}"
                if (!productionMatches) caseAccuracyFailures += "$id production Auto replay did not match $expected movements: $production"
                accuracyFailures += caseAccuracyFailures
                if (role == "positive") failures += caseAccuracyFailures
                reports += buildJsonObject {
                    put("id", id); put("qualificationRole", requireNotNull(role)); put("sha256", checksum); put("exercise", exercise.name); put("side", side.name)
                    put("frames", frames); put("singlePersonFrames", peopleFrames); put("multiplePersonFrames", multiplePeopleFrames)
                    put("observedCandidates", candidates); put("expectedMovements", expected); put("observedMovements", counter.observedMovements)
                    put("functionalParity", fixedEdgeMatches && productionMatches); put("fixedEdgeMovementParity", fixedEdgeMatches)
                    put("accuracyFailures", Json.encodeToJsonElement(caseAccuracyFailures))
                    put("referenceEdge", Json.encodeToJsonElement(canonicalEdge)); put("productionReplay", production)
                    put("elapsedSeconds", elapsed); put("analysisFramesPerWallSecond", frames / elapsed)
                    put("poseLatencyMillis", latency(latencies)); put("decodeLatencyMillis", latency(decodeLatencies)); put("contourLatencyMillis", latency(contourLatencies)); put("sceneLatencyMillis", latency(sceneLatencies)); put("sceneMeasurementsWithTranslationAndScale", sceneValidFrames)
                    put("peakPssKb", peakPssKb); put("peakJavaAllocatedBytes", peakJavaBytes); put("peakNativeAllocatedBytes", peakNativeBytes)
                    put("events", Json.encodeToJsonElement(counter.events))
                }
                if (frames == 0 || peopleFrames == 0) failures += "$id native execution produced $peopleFrames/$frames single-person frames"
            } catch (error: Throwable) {
                reports += buildJsonObject { put("id", id); put("qualificationRole", role?.let(::JsonPrimitive) ?: JsonNull); put("error", error.stackTraceToString()); put("functionalParity", false) }
                failures += "$id: ${error.message}"
            }
        }
        if (reports.none { it.jsonObject["qualificationRole"]?.jsonPrimitive?.contentOrNull == "positive" }) failures += "No positive dynamic qualification case"
        val report = buildJsonObject {
            put("schemaVersion", 1); put("policyVersion", ExerciseCounter.policyVersion)
            put("package", app.packageName); put("sdk", Build.VERSION.SDK_INT); put("device", Build.DEVICE); put("model", Build.MODEL)
            put("fingerprint", Build.FINGERPRINT); put("abis", Json.encodeToJsonElement(Build.SUPPORTED_ABIS.toList()))
            put("delegate", "CPU"); put("poseArtifact", PoseEstimator.ARTIFACT); put("poseArtifactSha256", PoseEstimator.ARTIFACT_SHA256); put("opencvArtifact", "org.opencv:opencv:4.12.0")
            put("modelSha256", PoseEstimator.MODEL_SHA256); put("canonicalPixels", "upright unmirrored origin top-left max1280")
            put("confidence", "minimum of raw visibility and presence; threshold0.3; not cross-backend calibrated")
            put("benchmarkScope", "Emulator replay wall time, no physical camera/thermal/battery qualification; first inference retained separately")
            put("cases", JsonArray(reports)); put("failures", Json.encodeToJsonElement(failures))
            put("accuracyFailures", Json.encodeToJsonElement(accuracyFailures))
            put("qualificationScope", "Positive case accuracy is required; diagnostic accuracy mismatches remain explicit. Native execution, source integrity and usable pose output are required for every role.")
        }
        File(output, "report.json").writeText(json.encodeToString(JsonElement.serializer(), report))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
    private fun evaluateProductionReplay(file: File, expectedExercise: ExerciseCounter.Exercise, side: ArmMeasurement.Side, expected: Int,
                                         expectedFrames: Int, expectedPts: JsonArray): JsonObject {
        val times = mutableListOf<Double>(); var acquired = false; var acquisitionSeconds: Double? = null
        var movements = 0; var selected: ExerciseCounter.Exercise? = null; var firstCountSource: Double? = null
        var peakPss = 0; var peakNative = 0L; var peakJava = 0L
        var decodedFrames = 0; var peopleFrames = 0; var multiplePeopleFrames = 0
        var countingSourceFrames = 0; var countingSourceEnded = false; var countingPassStarted = false
        var outcome: ReplayOutcome? = null; var endReason: LiveSetSession.EndReason? = null
        var events: JsonElement = JsonArray(emptyList())
        val elapsedStart = System.nanoTime()
        try {
            AnalysisPipeline(app, WorkoutExerciseSelection.automatic, side).use { pipeline ->
                fun pass(acquire: Boolean): Boolean {
                    VideoFrames(app, Uri.fromFile(file)).use { reader ->
                        var passFrames = 0
                        while (true) {
                            // Match the ViewModel: an interrupted set cannot resume counting later pixels.
                            if (!acquire && pipeline.runtime.snapshot.phase == LiveSetSession.Phase.finished) {
                                countingSourceFrames = passFrames
                                return false
                            }
                            val started = System.nanoTime()
                            val frame = reader.next() ?: break
                            frame.use {
                                val time = it.presentationTimeUs / 1e6
                                check(passFrames < expectedPts.size && kotlin.math.abs(time - expectedPts[passFrames].jsonPrimitive.double) <= 1e-6) {
                                    "Production source PTS mismatch at $passFrames: $time"
                                }
                                val sample = PhoneAttitude.Sample(requireNotNull(PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)), time)
                                val result = pipeline.process(it.bitmap, it.presentationTimeUs, sample)
                                // Include the production UI ownership copy, never merely model inference.
                                it.bitmap.copy(Bitmap.Config.ARGB_8888, false).recycle()
                                times += (System.nanoTime() - started) / 1e6
                                passFrames++; decodedFrames++
                                if (result.pose.people.size == 1) peopleFrames++ else if (result.pose.people.size > 1) multiplePeopleFrames++
                                selected = result.snapshot.exercise
                                if (result.snapshot.referenceEdge != null) { acquired = true; if (acquisitionSeconds == null) acquisitionSeconds = time }
                                movements = result.snapshot.observedMovements
                                if (!acquire && result.snapshot.phase == LiveSetSession.Phase.running && firstCountSource == null) firstCountSource = time
                                val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory); peakPss = maxOf(peakPss, memory.totalPss)
                                peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                                val heap = Runtime.getRuntime(); peakJava = maxOf(peakJava, heap.totalMemory() - heap.freeMemory())
                            }
                            if (acquire && acquired) break
                        }
                        check((acquire && acquired) || passFrames == expectedFrames) { "Production decoded $passFrames/$expectedFrames source frames" }
                        if (!acquire) countingSourceFrames = passFrames
                        return !(acquire && acquired)
                    }
                }
                pass(true)
                if (acquired && pipeline.rewindReplay()) { countingPassStarted = true; countingSourceEnded = pass(false) }
                endReason = pipeline.runtime.session.endReason
                outcome = ReplayOutcome.atEnd(pipeline.runtime.session.analyzedFrames, acquired, !countingPassStarted || countingSourceEnded,
                    pipeline.runtime.snapshot.setupIssue, endReason)
                pipeline.runtime.stop()
                movements = pipeline.runtime.snapshot.observedMovements
                events = Json.encodeToJsonElement(pipeline.runtime.session.counter.events)
            }
            return buildJsonObject {
                put("acquiredActualContours", acquired); put("acquisitionSourceSeconds", acquisitionSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("selectedExercise", selected?.name?.let(::JsonPrimitive) ?: JsonNull); put("firstCountingSourceSeconds", firstCountSource?.let(::JsonPrimitive) ?: JsonNull)
                put("observedMovements", movements); put("expectedMovements", expected)
                put("functionalParity", acquired && selected == expectedExercise && movements == expected && countingPassStarted &&
                    countingSourceEnded && countingSourceFrames == expectedFrames && outcome?.kind == ReplayOutcome.Kind.complete)
                put("replayOutcome", outcome?.kind?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("outcomeMessage", outcome?.message?.let(::JsonPrimitive) ?: JsonNull)
                put("endReason", endReason?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("countingPassStarted", countingPassStarted); put("countingSourceEnded", countingSourceEnded)
                put("countingSourceFrames", countingSourceFrames); put("events", events)
                put("decodedFrames", decodedFrames); put("singlePersonFrames", peopleFrames); put("multiplePersonFrames", multiplePeopleFrames)
                put("pipelineWithDisplayCopyLatencyMillis", latency(times)); put("elapsedSeconds", (System.nanoTime() - elapsedStart) / 1e9)
                put("pipelineLatencyScope", "decode+canonical pixels+pose+actual contours/scene+runtime+display copy; excludes model initialization and EOF null read")
                put("decodePixelConversion", CanonicalPixels.YUV_CONVERSION)
                put("peakPssKb", peakPss); put("peakNativeAllocatedBytes", peakNative); put("peakJavaAllocatedBytes", peakJava)
                put("attitudeScope", "virtual fixed recording phone; actual image registration required")
            }
        } catch (error: Throwable) { return buildJsonObject { put("functionalParity", false); put("acquiredActualContours", acquired); put("error", error.stackTraceToString()) } }
    }
    private fun latency(samples: List<Double>): JsonObject = buildJsonObject {
        put("samples", samples.size)
        if (samples.isNotEmpty()) {
            val warm = samples.drop(1).sorted().ifEmpty { samples.sorted() }
            fun percentile(fraction: Double) = warm[((warm.size - 1) * fraction).toInt()]
            put("first", samples.first()); put("p50", percentile(0.5)); put("p95", percentile(0.95)); put("maximum", samples.max())
        }
    }
    @Test fun canonicalRotationAndObservedContours() {
        val raw = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        raw.eraseColor(Color.BLACK)
        for (y in 0 until 40) for (x in 0 until 20) raw.setPixel(x, y, Color.RED)
        val upright = CanonicalPixels.upright(raw, 90)
        assertEquals(40, upright.width); assertEquals(80, upright.height)
        assertEquals(Color.RED, upright.getPixel(20, 10)); assertEquals(Color.BLACK, upright.getPixel(20, 70))
        upright.recycle(); raw.recycle()
        val rail = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888); rail.eraseColor(Color.BLACK)
        for (y in 100..120) for (x in 20..300) rail.setPixel(x, y, Color.WHITE)
        val edges = ObservedEdges().detect(rail, BarRegion(0.0, 60.0, 320.0, 180.0))
        assertTrue("Observed metal silhouette edges were lost", edges.count { it.edge.length >= 200 } >= 2)
        val texture = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(7)
        for (y in 0 until 240) for (x in 0 until 320) { val value = random.nextInt(256); texture.setPixel(x, y, Color.rgb(value, value, value)) }
        SceneRegistration().use { scene ->
            scene.freeze(texture); val evidence = scene.observe(texture)
            assertTrue(evidence.translations.size >= 2); assertTrue(evidence.translations.all { kotlin.math.hypot(it.dxPixels, it.dyPixels) < 1 })
            val small = Bitmap.createScaledBitmap(texture, 304, 228, true)
            val contracted = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(contracted).drawBitmap(small, 8f, 6f, null)
            val contraction = scene.observe(contracted).scaleFraction
            assertNotNull("Contracting image must provide independent scale evidence", contraction)
            assertTrue("Contraction magnitude must be positive", requireNotNull(contraction) >= 0.03)
            small.recycle(); contracted.recycle()
        }
        texture.recycle()
        rail.recycle()
    }
}
