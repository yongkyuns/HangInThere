package dev.hanginthere.android

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Debug
import dev.hanginthere.android.camera.CanonicalPixels
import dev.hanginthere.android.camera.PhoneAttitude
import dev.hanginthere.android.replay.VideoFrames
import dev.hanginthere.android.session.AnalysisPipeline
import dev.hanginthere.android.session.ReplayOutcome
import dev.hanginthere.android.vision.PoseEstimator
import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import java.io.File

/** Actual production replay evaluation; callers own provenance, output privacy and grading. */
internal class NativeReplayEvaluator(private val app: Context) {
    /** Frozen, source-reviewed edge diagnostic: measures poses/counter separately from Auto setup. */
    fun evaluateFixedReference(id: String, output: File, file: File, exercise: ExerciseCounter.Exercise,
                               side: ArmMeasurement.Side, expected: Int, expectedFrames: Int, expectedPts: List<Long>,
                               sourceEdge: BarSegment, referenceSize: ImageSize): JsonObject {
        val counter = ExerciseCounter(exercise, side)
        var frames = 0; var peopleFrames = 0; var multiplePeopleFrames = 0
        var canonicalEdge: BarSegment? = null; var error: String? = null
        val latencies = mutableListOf<Double>()
        val colorEncodings = linkedSetOf<String>()
        var peakPss = 0
        try {
            check(referenceSize.isValid && sourceEdge.isValid && expectedPts.size == expectedFrames)
            File(output, "$id-fixed-${side.name}-observations.jsonl").bufferedWriter().use { trace ->
                PoseEstimator(app).use { estimator ->
                    VideoFrames(app, Uri.fromFile(file)).use { reader ->
                        while (true) {
                            val started = System.nanoTime()
                            val frame = reader.next() ?: break
                            frame.use {
                                check(frames < expectedFrames && it.presentationTimeUs == expectedPts[frames]) { "Fixed reference source integrity failure" }
                                val pose = estimator.estimate(it.bitmap, it.presentationTimeUs)
                                reader.decodedColorEncoding?.let { encoding -> colorEncodings += encoding.description }
                                fun scale(point: Point2D) = Point2D(point.x * pose.imageSize.width / referenceSize.width,
                                    point.y * pose.imageSize.height / referenceSize.height)
                                canonicalEdge = BarSegment(scale(sourceEdge.a), scale(sourceEdge.b))
                                counter.consume(pose, canonicalEdge)
                                it.bitmap.copy(Bitmap.Config.ARGB_8888, false).recycle()
                                latencies += (System.nanoTime() - started) / 1e6
                                trace.write(Json.encodeToString(PoseResult.serializer(), pose)); trace.newLine()
                                if (pose.people.size == 1) peopleFrames++ else if (pose.people.size > 1) multiplePeopleFrames++
                                frames++
                                if (frames % 5 == 0) {
                                    val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory); peakPss = maxOf(peakPss, memory.totalPss)
                                }
                            }
                        }
                    }
                }
            }
            check(frames == expectedFrames && peopleFrames > 0) { "Fixed reference native coverage failure" }
        } catch (failure: Throwable) { error = failure.stackTraceToString() }
        finally { counter.finish() }
        return buildJsonObject {
            put("scope", "Manual fixed edge counter diagnostic; full source native poses, no Auto acquisition or UI parity claim")
            put("side", side.name); put("exercise", exercise.name); put("frames", frames); put("expectedFrames", expectedFrames)
            put("singlePersonFrames", peopleFrames); put("multiplePersonFrames", multiplePeopleFrames)
            put("expectedMovements", expected); put("observedMovements", counter.observedMovements)
            put("functionalParity", error == null && counter.observedMovements == expected)
            put("referenceEdge", canonicalEdge?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
            put("events", Json.encodeToJsonElement(counter.events)); put("pipelineWithDisplayCopyLatencyMillis", NativeEvaluationMetrics.latency(latencies))
            put("peakPssKb", peakPss); put("error", error?.let(::JsonPrimitive) ?: JsonNull)
            put("decodedColorEncodings", JsonArray(colorEncodings.map(::JsonPrimitive)))
        }
    }
    fun evaluateAutomatic(id: String, output: File, file: File, expectedExercise: ExerciseCounter.Exercise, side: ArmMeasurement.Side, expected: Int,
                                         expectedFrames: Int, expectedPts: List<Long>): JsonObject {
        val times = mutableListOf<Double>(); var acquired = false; var acquisitionSeconds: Double? = null
        var acquisitionReady = false; var acquisitionReadySeconds: Double? = null
        var movements = 0; var selected: ExerciseCounter.Exercise? = null; var firstCountSource: Double? = null
        var peakPss = 0; var peakNative = 0L; var peakJava = 0L
        var decodedFrames = 0; var peopleFrames = 0; var multiplePeopleFrames = 0
        var countingSourceFrames = 0; var countingSourceEnded = false; var countingPassStarted = false
        var outcome: ReplayOutcome? = null; var endReason: LiveSetSession.EndReason? = null
        var finalSetupIssue: String? = null
        var events: JsonElement = JsonArray(emptyList())
        val colorEncodings = linkedSetOf<String>()
        val elapsedStart = System.nanoTime()
        try {
            AnalysisPipeline(app, WorkoutExerciseSelection.automatic, side).use { pipeline ->
                fun pass(acquire: Boolean): Boolean {
                    File(output, "$id-production-${if (acquire) "acquisition" else "counting"}.jsonl").bufferedWriter().use { trace ->
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
                                    check(passFrames < expectedPts.size && it.presentationTimeUs == expectedPts[passFrames]) {
                                        "Production source PTS mismatch at $passFrames: ${it.presentationTimeUs}"
                                    }
                                    val sample = PhoneAttitude.Sample(requireNotNull(PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0)), time)
                                    val result = pipeline.process(it.bitmap, it.presentationTimeUs, sample)
                                    reader.decodedColorEncoding?.let { encoding -> colorEncodings += encoding.description }
                                    // Include the production UI ownership copy, never merely model inference.
                                    it.bitmap.copy(Bitmap.Config.ARGB_8888, false).recycle()
                                    times += (System.nanoTime() - started) / 1e6
                                    // Diagnostic serialization is intentionally outside the measured native path.
                                    trace.write(buildJsonObject {
                                        put("frameIndex", passFrames); put("sourcePtsMicroseconds", it.presentationTimeUs)
                                        put("pose", Json.encodeToJsonElement(result.pose))
                                        put("snapshot", Json.encodeToJsonElement(result.snapshot))
                                    }.toString()); trace.newLine()
                                    passFrames++; decodedFrames++
                                    if (result.pose.people.size == 1) peopleFrames++ else if (result.pose.people.size > 1) multiplePeopleFrames++
                                    selected = result.snapshot.exercise
                                    finalSetupIssue = result.snapshot.setupIssue
                                    if (result.snapshot.referenceEdge != null) { acquired = true; if (acquisitionSeconds == null) acquisitionSeconds = time }
                                    if (acquire && pipeline.runtime.isReplayAcquisitionReady) {
                                        acquisitionReady = true; acquisitionReadySeconds = time
                                    }
                                    movements = result.snapshot.observedMovements
                                    if (!acquire && result.snapshot.phase == LiveSetSession.Phase.running && firstCountSource == null) firstCountSource = time
                                    val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory); peakPss = maxOf(peakPss, memory.totalPss)
                                    peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                                    val heap = Runtime.getRuntime(); peakJava = maxOf(peakJava, heap.totalMemory() - heap.freeMemory())
                                }
                                if (acquire && acquisitionReady) break
                            }
                            check((acquire && acquisitionReady) || passFrames == expectedFrames) { "Production decoded $passFrames/$expectedFrames source frames" }
                            if (!acquire) countingSourceFrames = passFrames
                            return !(acquire && acquisitionReady)
                        }
                    }
                }
                pass(true)
                if (acquisitionReady && pipeline.rewindReplay()) { countingPassStarted = true; countingSourceEnded = pass(false) }
                endReason = pipeline.runtime.session.endReason
                finalSetupIssue = pipeline.runtime.snapshot.setupIssue
                outcome = ReplayOutcome.atEnd(pipeline.runtime.session.analyzedFrames, acquired, !countingPassStarted || countingSourceEnded,
                    pipeline.runtime.snapshot.setupIssue, endReason)
                pipeline.runtime.stop()
                movements = pipeline.runtime.snapshot.observedMovements
                events = Json.encodeToJsonElement(pipeline.runtime.session.counter.events)
            }
            return buildJsonObject {
                put("acquiredActualContours", acquired); put("acquisitionSourceSeconds", acquisitionSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("acquisitionReady", acquisitionReady); put("acquisitionReadySourceSeconds", acquisitionReadySeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("selectedExercise", selected?.name?.let(::JsonPrimitive) ?: JsonNull); put("firstCountingSourceSeconds", firstCountSource?.let(::JsonPrimitive) ?: JsonNull)
                put("observedMovements", movements); put("expectedMovements", expected)
                put("functionalParity", acquired && selected == expectedExercise && movements == expected && countingPassStarted &&
                    countingSourceEnded && countingSourceFrames == expectedFrames && outcome?.kind == ReplayOutcome.Kind.complete)
                put("replayOutcome", outcome?.kind?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("outcomeMessage", outcome?.message?.let(::JsonPrimitive) ?: JsonNull)
                put("setupIssue", finalSetupIssue?.let(::JsonPrimitive) ?: JsonNull)
                put("endReason", endReason?.name?.let(::JsonPrimitive) ?: JsonNull)
                put("countingPassStarted", countingPassStarted); put("countingSourceEnded", countingSourceEnded)
                put("countingSourceFrames", countingSourceFrames); put("events", events)
                put("decodedFrames", decodedFrames); put("singlePersonFrames", peopleFrames); put("multiplePersonFrames", multiplePeopleFrames)
                put("pipelineWithDisplayCopyLatencyMillis", NativeEvaluationMetrics.latency(times)); put("elapsedSeconds", (System.nanoTime() - elapsedStart) / 1e9)
                put("pipelineLatencyScope", "decode+canonical pixels+pose+actual contours/scene+runtime+display copy; excludes model initialization and EOF null read")
                put("decodePixelConversion", CanonicalPixels.YUV_CONVERSION)
                put("decodedColorEncodings", JsonArray(colorEncodings.map(::JsonPrimitive)))
                put("peakPssKb", peakPss); put("peakNativeAllocatedBytes", peakNative); put("peakJavaAllocatedBytes", peakJava)
                put("attitudeScope", "virtual fixed recording phone; actual image registration required")
            }
        } catch (error: Throwable) { return buildJsonObject {
            put("functionalParity", false); put("acquiredActualContours", acquired)
            put("setupIssue", finalSetupIssue?.let(::JsonPrimitive) ?: JsonNull); put("error", error.stackTraceToString())
        } }
    }

}

internal object NativeEvaluationMetrics {
    fun latency(samples: List<Double>): JsonObject = buildJsonObject {
        put("samples", samples.size)
        if (samples.isNotEmpty()) {
            val warm = samples.drop(1).sorted().ifEmpty { samples.sorted() }
            fun percentile(fraction: Double) = warm[((warm.size - 1) * fraction).toInt()]
            put("first", samples.first()); put("p50", percentile(0.5)); put("p95", percentile(0.95)); put("maximum", samples.max())
        }
    }
}
