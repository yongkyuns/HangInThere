package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

/** Cached original public native observations, not new SDK/count evaluation.
 * The same source regression runs on JVM and Android instrumentation. */
class ObservedHandContinuitySourceTests {
    @Test fun actualNativePointFreshnessMatchesIndependentReferenceOnEveryCapturedFrame() {
        val json = WorkoutRecordCodec.json
        val stream = checkNotNull(javaClass.getResourceAsStream("/observed-hand-continuity-source.json.gz"))
        val source = GZIPInputStream(stream).bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        assertEquals(1, source.getValue("schemaVersion").jsonPrimitive.int)
        val windows = source.getValue("windows").jsonArray
        assertEquals(4, windows.size)
        val bridge = ObservedHandContinuityBridge()
        var totalFrames = 0
        var freshPoints = 0
        var unavailablePoints = 0
        for ((windowIndex, rawWindow) in windows.withIndex()) {
            val generation = if (windowIndex == 0) bridge.generation else bridge.reset()
            val window = rawWindow.jsonObject
            val epoch = window.getValue("clip").jsonPrimitive.content + ":" + window.getValue("seedFrame").jsonPrimitive.content
            val indices = window.getValue("seedHandIndices").jsonArray.map { it.jsonPrimitive.int }
            for ((frameIndex, rawFrame) in window.getValue("frames").jsonArray.withIndex()) {
                val value = rawFrame.jsonObject
                val pose = json.decodeFromJsonElement<PoseResult>(value.getValue("pose"))
                val original = json.encodeToString(pose)
                val ordinal = value.getValue("sourceFrameOrdinal").jsonPrimitive.long
                val frame = ObservedHandContinuity.Frame(generation, epoch, ordinal, pose, pose.timestamp, ordinal)
                val output = if (frameIndex == 0) bridge.startJson(json.encodeToString(ObservedHandContinuity.Start(frame, indices)))
                    else bridge.observeJson(json.encodeToString(frame))
                val result = json.decodeFromString<ObservedHandContinuity.Snapshot>(checkNotNull(output))
                assertEquals(pose.timestamp, result.highWaterSourceTime)
                assertEquals(ordinal, result.highWaterSourceFrameOrdinal)
                val expected = value.getValue("expectedTracks").jsonArray
                assertEquals(expected.size, result.tracks.size)
                for ((index, actual) in result.tracks.withIndex()) {
                    val track = expected[index].jsonObject
                    assertEquals(track.getValue("state").jsonPrimitive.content, actual.state.name)
                    assertEquals(json.decodeFromJsonElement<ObservedHandContinuity.Sample>(track.getValue("seed")), actual.seed)
                    assertEquals(json.decodeFromJsonElement<ObservedHandContinuity.Sample>(track.getValue("lastObservedSample")), actual.lastObservedSample)
                    val current = track["currentSample"].takeUnless { it == null || it == JsonNull }
                        ?.let { json.decodeFromJsonElement<ObservedHandContinuity.Sample>(it) }
                    assertEquals(current, actual.currentSample)
                    if (actual.currentSample != null) freshPoints++ else unavailablePoints++
                }
                assertEquals(original, json.encodeToString(pose))
                totalFrames++
            }
        }
        assertEquals(128, totalFrames)
        assertEquals(96, freshPoints)
        assertEquals(32, unavailablePoints)
    }
}
