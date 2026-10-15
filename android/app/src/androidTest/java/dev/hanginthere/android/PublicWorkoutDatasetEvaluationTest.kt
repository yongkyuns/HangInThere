package dev.hanginthere.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.core.ArmMeasurement
import dev.hanginthere.core.AutomaticReferenceStability
import dev.hanginthere.core.AutomaticPullUpBar
import dev.hanginthere.core.ExerciseCounter
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Optional pinned public videos. Only the counts report is retained by CI. */
@RunWith(AndroidJUnit4::class)
class PublicWorkoutDatasetEvaluationTest {
    @Test fun publicVideosUseTheProductionReplayAndAnalysis() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val assetRoot = "generated/public-workout"
        assumeTrue("Public dataset batch is not configured", assets.list(assetRoot)?.contains("manifest.json") == true)
        val manifestBytes = assets.open("$assetRoot/manifest.json").use { it.readBytes() }
        val manifest = Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
        check(manifest.getValue("scope").jsonPrimitive.content == "public_repetition_diagnostic")
        val revision = InstrumentationRegistry.getArguments().getString("publicSourceRevision")
        check(revision != null && revision.matches(Regex("[a-f0-9]{40}")))
        val runId = InstrumentationRegistry.getArguments().getString("publicRunId")
        check(runId != null && runId.matches(Regex("[a-f0-9]{32}")))
        val output = File(requireNotNull(app.getExternalFilesDir("public-workout")), runId)
        check(output.mkdir()) { "Public audit directory already exists" }
        val evaluator = NativeReplayEvaluator(app)
        val reports = mutableListOf<JsonElement>()
        for (entry in manifest.getValue("cases").jsonArray) {
            val case = entry.jsonObject
            val id = case.getValue("id").jsonPrimitive.content
            check(id.matches(Regex("[A-Za-z0-9_-]+")))
            val media = case.getValue("media").jsonObject
            val path = media.getValue("path").jsonPrimitive.content
            check(path.matches(Regex("videos/[A-Za-z0-9_-]+\\.mp4")))
            val file = File(app.cacheDir, "public-$id.mp4")
            try {
                assets.open("$assetRoot/$path").use { input -> file.outputStream().use { input.copyTo(it) } }
                check(hash(file.readBytes()) == media.getValue("sha256").jsonPrimitive.content)
                val frames = case.getValue("frames").jsonPrimitive.int
                val pts = case.getValue("source_pts_microseconds").jsonArray.map { it.jsonPrimitive.long }
                val expected = case.getValue("labels").jsonObject.getValue("expected_count").jsonPrimitive.int
                val result = evaluator.evaluateAutomatic(id, output, file, ExerciseCounter.Exercise.pullUp,
                    ArmMeasurement.Side.left, expected, frames, pts)
                check(hash(file.readBytes()) == media.getValue("sha256").jsonPrimitive.content)
                val outcome = result["replayOutcome"]?.jsonPrimitive?.contentOrNull
                val complete = outcome == "complete" && result["countingSourceEnded"]?.jsonPrimitive?.boolean == true
                val status = if (complete) "processed" else if (outcome == "setupUnavailable") "setupUnavailable" else if (outcome == "interrupted") "interrupted" else "failed"
                if (complete || status == "setupUnavailable") {
                    val evidence = result.getValue("acquisitionEvidence").jsonObject
                    val acquisitionFrames = evidence.getValue("frames").jsonPrimitive.int
                    check(acquisitionFrames in 1..frames)
                    check(evidence.getValue("pullUpGripAssessmentVersion").jsonPrimitive.int == AutomaticPullUpBar.gripAssessmentVersion)
                    val grip = evidence.getValue("pullUpGripFrames").jsonObject
                    val gripKeys = AutomaticPullUpBar.GripRejection.entries.map { it.name }.toSet() + "eligible"
                    check(grip.keys.all { it in gripKeys })
                    check(grip.values.all { it.jsonPrimitive.int in 0..acquisitionFrames })
                    check(grip.values.sumOf { it.jsonPrimitive.int } == acquisitionFrames)
                    check((grip["eligible"]?.jsonPrimitive?.int ?: 0) <= evidence.getValue("bothArmFrames").jsonPrimitive.int)
                    for (key in listOf("setupIssueFrames", "acquisitionStateFrames")) {
                        check(evidence.getValue(key).jsonObject.values.sumOf { it.jsonPrimitive.int } == acquisitionFrames)
                    }
                    for (key in listOf("bothArmFrames", "referenceFrames", "contourSampleFrames", "contourEvidenceFrames", "contourErrorFrames")) {
                        check(evidence.getValue(key).jsonPrimitive.int in 0..acquisitionFrames)
                    }
                    val sampled = evidence.getValue("contourSampleFrames").jsonPrimitive.int
                    val stability = evidence.getValue("referenceStabilitySamples").jsonObject
                    check(stability.keys.all { key -> AutomaticReferenceStability.Status.entries.any { it.name == key } })
                    check(stability.values.all { it.jsonPrimitive.int >= 0 })
                    check(stability.values.sumOf { it.jsonPrimitive.int } in 0..sampled)
                    for (key in listOf("contourEvidenceFrames", "contourErrorFrames")) {
                        check(evidence.getValue(key).jsonPrimitive.int <= sampled)
                    }
                    check(evidence.getValue("resolvedExerciseFrames").jsonObject.values.sumOf { it.jsonPrimitive.int } in 0..acquisitionFrames)
                }
                val measured = complete || status == "interrupted"
                val accepted = if (measured) result.getValue("events").jsonArray.filter {
                    it.jsonObject.getValue("outcome").jsonPrimitive.content == "movement"
                }.map { it.jsonObject.getValue("sourceSeconds") } else emptyList()
                reports += buildJsonObject {
                    put("id", id); put("sha256", media.getValue("sha256")); put("status", status)
                    put("frames", if (complete || status == "setupUnavailable") frames else result["countingSourceFrames"]?.jsonPrimitive?.intOrNull ?: 0)
                    put("source_clock_verified", complete || status == "setupUnavailable")
                    put("selected_exercise", result["selectedExercise"] ?: JsonNull)
                    put("acceptance_seconds", JsonArray(accepted))
                    put("completed_seconds", if (measured) result.getValue("completedRepSeconds") else JsonArray(emptyList()))
                    put("has_native_error", "error" in result)
                    put("native", result.filterKeys { it in setOf("replayOutcome", "setupIssue", "countingSourceFrames", "decodedFrames", "singlePersonFrames", "multiplePersonFrames", "elapsedSeconds", "pipelineWithDisplayCopyLatencyMillis", "acquisitionEvidence") }.let(::JsonObject))
                }
            } catch (failure: Throwable) {
                // Preserve the failed case and continue. Raw error descriptions
                // can include partial serialized poses, so keep only its type.
                reports += buildJsonObject {
                    put("id", id); put("sha256", media.getValue("sha256")); put("status", "failed")
                    put("frames", 0); put("source_clock_verified", false); put("selected_exercise", JsonNull)
                    put("acceptance_seconds", JsonArray(emptyList())); put("completed_seconds", JsonArray(emptyList()))
                    put("failure_type", failure.javaClass.simpleName)
                }
            } finally { file.delete() }
        }
        val report = buildJsonObject {
            put("schema_version", 1); put("manifest_sha256", hash(manifestBytes)); put("source_revision", revision)
            put("run_id", runId)
            put("platform", "android_emulator"); put("backend", "production MediaPipe CPU/OpenCV native video replay")
            put("counter_policy_version", ExerciseCounter.policyVersion)
            put("exercise_selection", "production automatic exercise acquisition"); put("cases", JsonArray(reports))
        }.toString()
        Files.newBufferedWriter(File(output, "report.json").toPath(), Charsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { it.write(report) }
        assertTrue("Public source/model/decoder failures remain in report", reports.none { it.jsonObject.getValue("status").jsonPrimitive.content == "failed" })
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
