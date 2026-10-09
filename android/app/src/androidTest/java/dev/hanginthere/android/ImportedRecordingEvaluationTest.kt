package dev.hanginthere.android

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.vision.PoseEstimator
import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in imported media. Detailed evidence must be encrypted by its authorized caller before upload. */
@RunWith(AndroidJUnit4::class)
class ImportedRecordingEvaluationTest {
    @Test fun neutralNamesAcceptPreparedAssetsAndRejectPaths() {
        assertTrue(ImportedRecordingNames.isAsset("recording_a.mp4"))
        assertTrue(ImportedRecordingNames.isAsset("recording_b.mp4"))
        assertTrue(ImportedRecordingNames.isId("recording_a_left"))
        listOf("../recording_a.mp4", "folder/recording_a.mp4", "recording_a\\.mp4", "recording_aXmp4",
            "/recording_a.mp4", "recording_a.mov", "personal_video.mp4").forEach { name ->
            assertTrue("Unsafe/non-neutral test asset name accepted", !ImportedRecordingNames.isAsset(name))
        }
    }
    @Test fun importedRecordingsUseProductionAutoAndSeparateManualDiagnostics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val assetRoot = "generated/private-evaluation"
        assumeTrue("Optional imported recording evaluation is not configured",
            assets.list(assetRoot)?.contains("manifest.json") == true)
        val output = requireNotNull(app.getExternalFilesDir("private-evaluation")); output.mkdirs()
        val temporary = File(app.cacheDir, "private-evaluation"); temporary.mkdirs()
        val reports = mutableListOf<JsonElement>()
        val failures = mutableListOf<String>()
        var manifest: JsonObject? = null
        var manifestError: String? = null
        try {
            manifest = assets.open("$assetRoot/manifest.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            check(manifest.getValue("schemaVersion").jsonPrimitive.int == 1) { "Unsupported imported evaluation schema" }
            val cases = manifest.getValue("cases").jsonArray
            check(cases.isNotEmpty()) { "No imported evaluation cases" }
            val seen = mutableSetOf<String>()
            val evaluator = NativeReplayEvaluator(app)
            cases.forEach { element ->
                // A neutral identifier is the only permitted case name in console/JUnit messages.
                var id = "invalid_case"
                try {
                    val fixture = element.jsonObject
                    val requestedId = fixture.getValue("id").jsonPrimitive.content
                    check(ImportedRecordingNames.isId(requestedId) && seen.add(requestedId)) { "Invalid imported case identifier" }
                    id = requestedId
                    val asset = fixture.getValue("asset").jsonPrimitive.content
                    check(ImportedRecordingNames.isAsset(asset)) { "Invalid neutral media asset name" }
                    val file = File(temporary, asset)
                    try {
                        assets.open("$assetRoot/$asset").use { input -> file.outputStream().use { input.copyTo(it) } }
                        val checksum = file.inputStream().use { input ->
                            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
                            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                            digest.digest().joinToString("") { "%02x".format(it) }
                        }
                        check(checksum == fixture.getValue("sha256").jsonPrimitive.content) { "Imported media integrity failure" }
                        val exercise = ExerciseCounter.Exercise.valueOf(fixture.getValue("exercise").jsonPrimitive.content)
                        val side = ArmMeasurement.Side.valueOf(fixture.getValue("side").jsonPrimitive.content)
                        val expected = fixture.getValue("expectedMovements").jsonPrimitive.int
                        val expectedFrames = fixture.getValue("frames").jsonPrimitive.int
                        val pts = fixture.getValue("sourcePtsMicroseconds").jsonArray.map { it.jsonPrimitive.long }
                        check(expected >= 0 && expectedFrames > 0 && pts.size == expectedFrames && pts.first() >= 0 &&
                            pts.zipWithNext().all { (a, b) -> b > a }) { "Imported media clock integrity failure" }
                        val edge = Json.decodeFromJsonElement<BarSegment>(fixture.getValue("referenceEdge"))
                        val referenceSize = Json.decodeFromJsonElement<ImageSize>(fixture.getValue("referenceImageSize"))
                        val automatic = evaluator.evaluateAutomatic(id, output, file, exercise, side, expected, expectedFrames, pts)
                        // Always run the diagnostic even when Auto cannot acquire, preserving causal separation.
                        val fixed = evaluator.evaluateFixedReference(id, output, file, exercise, side, expected, expectedFrames, pts, edge, referenceSize)
                        val automaticPass = automatic.getValue("functionalParity").jsonPrimitive.boolean
                        val fixedPass = fixed.getValue("functionalParity").jsonPrimitive.boolean
                        reports += buildJsonObject {
                            put("id", id); put("exercise", exercise.name); put("side", side.name); put("sha256", checksum)
                            put("expectedMovements", expected); put("frames", expectedFrames)
                            put("automatic", automatic); put("manualFixedEdgeCounter", fixed)
                            put("functionalParity", automaticPass && fixedPass)
                        }
                        // Counts and neutral status are permitted; raw errors, poses and source metadata stay in the private report.
                        if (!automaticPass) failures += "$id Auto expected $expected got ${automatic["observedMovements"]?.jsonPrimitive?.intOrNull ?: -1}; incomplete or mismatch"
                        if (!fixedPass) failures += "$id manual diagnostic expected $expected got ${fixed.getValue("observedMovements").jsonPrimitive.int}; incomplete or mismatch"
                    } finally { file.delete() }
                } catch (failure: Throwable) {
                    reports += buildJsonObject { put("id", id); put("error", failure.stackTraceToString()); put("functionalParity", false) }
                    failures += "$id native or source integrity failure"
                }
            }
        } catch (failure: Throwable) { manifestError = failure.stackTraceToString() }
        finally { temporary.deleteRecursively() }
        File(output, "report.json").writeText(buildJsonObject {
            put("schemaVersion", 1); put("policyVersion", ExerciseCounter.policyVersion)
            put("privacy", "Private source and derived evidence: authorized encrypted transport only; not for public artifacts")
            put("scope", "Full production Auto acquisition/review replay plus separate source-reviewed fixed-edge counter diagnostics; no physical Android camera, strict form or manual UI parity claim")
            put("sdk", Build.VERSION.SDK_INT); put("deviceFingerprint", Build.FINGERPRINT)
            put("poseArtifact", PoseEstimator.ARTIFACT); put("poseArtifactSha256", PoseEstimator.ARTIFACT_SHA256)
            put("modelSha256", PoseEstimator.MODEL_SHA256); put("opencvArtifact", "org.opencv:opencv:4.12.0")
            put("manifest", manifest ?: JsonNull); put("cases", JsonArray(reports)); put("failures", JsonArray(failures.map(::JsonPrimitive)))
            put("manifestError", manifestError?.let(::JsonPrimitive) ?: JsonNull)
        }.toString())
        assertTrue(if (manifestError != null) "Imported evaluation manifest integrity failed" else failures.joinToString("\n"),
            manifestError == null && failures.isEmpty())
    }
}

internal object ImportedRecordingNames {
    private val id = Regex("""recording_[a-z0-9_]{1,48}""")
    private val asset = Regex("""recording_[a-z0-9_]{1,48}\.mp4""")
    fun isId(value: String) = id.matches(value)
    fun isAsset(value: String) = asset.matches(value)
}
