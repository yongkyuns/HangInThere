package dev.hanginthere.android

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.camera.PhoneAttitude
import dev.hanginthere.android.replay.VideoFrames
import dev.hanginthere.android.session.AnalysisPipeline
import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in private, full-source native replay. No private clips enter the APK/repository. */
@RunWith(AndroidJUnit4::class)
class MotionPipelineReplayTest {
    @Test fun fullVideosThroughTheProductionMotionPipeline() {
        val spec = InstrumentationRegistry.getArguments().getString("motionManifest") ?: return
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = File(spec)
        val clips = Json.parseToJsonElement(manifest.readText()).jsonObject.getValue("clips").jsonArray
        val output = File(app.filesDir, "motion-parity").apply { mkdirs() }
        val results = mutableListOf<JsonObject>()
        for (clip in clips.map { it.jsonObject }) {
            val id = clip.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val file = File(clip.getValue("path").jsonPrimitive.content)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val bytes=ByteArray(65536); while(true) { val n=input.read(bytes); if(n<0)break;digest.update(bytes,0,n) } }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            assertEquals(clip.getValue("sha256").jsonPrimitive.content, sha)
            var frames = 0
            var latest: AnalysisPipeline.Result? = null
            val records = linkedMapOf<String,WorkoutSetRecord>()
            val issues = linkedMapOf<String,Int>()
            var failure: String? = null
            try {
                AnalysisPipeline(app, WorkoutExerciseSelection.automatic, ArmMeasurement.Side.left,
                    persistWorkouts=false, replay=true).use { pipeline ->
                    File(output,"$id-trace.jsonl").bufferedWriter().use { trace ->
                        VideoFrames(app,Uri.fromFile(file)).use { reader ->
                            while(true) {
                                val frame = reader.next() ?: break
                                frame.use {
                                    val fixed = PhoneAttitude.Sample(requireNotNull(PhoneOrientationStability.Quaternion.create(0.0,0.0,0.0,1.0)),it.presentationTimeUs/1e6)
                                    val result = pipeline.process(it.bitmap,it.presentationTimeUs,fixed)
                                    latest = result; frames++
                                    result.history.forEach { r -> records[r.id]=r }
                                    result.snapshot.setupIssue?.let { issue -> issues[issue]=(issues[issue] ?: 0)+1 }
                                    trace.write(buildJsonObject {
                                        put("pose",WorkoutRecordCodec.json.encodeToJsonElement(result.rawPose))
                                        put("camera",result.camera.name)
                                        put("cameraGeneration",result.snapshot.setupGeneration)
                                        put("motion",WorkoutRecordCodec.json.encodeToJsonElement(result.motion))
                                        put("sceneState",result.snapshot.sceneState.name)
                                        put("issue",result.snapshot.setupIssue?.let(::JsonPrimitive) ?: JsonNull)
                                    }.toString()); trace.newLine()
                                }
                            }
                        }
                        pipeline.stop("endOfInput")
                        latest?.let { pipeline.refreshRecords(it).history.forEach { r -> records[r.id]=r } }
                    }
                }
            } catch (error: Throwable) { failure=error.stackTraceToString() }
            File(output,"$id-records.json").writeText(WorkoutRecordCodec.json.encodeToString(records.values.toList()))
            results += buildJsonObject {
                put("id",id);put("videoSHA256",sha);put("frames",frames)
                put("expected",clip.getValue("expected"));put("completed",records.values.sumOf { it.completedCount })
                put("sets",records.size);put("issues",JsonObject(issues.mapValues { JsonPrimitive(it.value) }))
                put("error",failure?.let(::JsonPrimitive) ?: JsonNull)
            }
            File(output,"results.json").writeText(buildJsonObject {
                put("scope","Android emulator CPU native MediaPipe Heavy, production Auto motion pipeline; fixed recorded-camera assumption with actual OpenCV registration; not physical Android qualification")
                put("openCvOptimizedDispatch",true)
                put("clips",JsonArray(results))
            }.toString())
        }
        assertTrue("Native replay failed: $results",results.all { it["error"]==JsonNull })
        assertTrue("Native counts differ; see retained traces: $results",results.all { it.getValue("expected")==it.getValue("completed") })
    }
}
