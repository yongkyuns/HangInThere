package dev.hanginthere.evaluation

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.*
import kotlin.test.*

class EstimatedBodyGeometryDiagnosticTests {
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun fixture(hasReceipt: Boolean = true, test: (File, File, File) -> Unit) {
        val directory = Files.createTempDirectory("estimated-body-fixture").toFile()
        try {
            val png = File(directory, "frame.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val time = buildJsonObject { put("value", 0); put("timescale", 1000) }
            val size = buildJsonObject { put("width", 1); put("height", 1) }
            val metadata = File(directory, "frames.jsonl").apply { writeText(buildJsonObject {
                put("frameIndex", 0); put("timestamp", time); put("imageSize", size); put("path", png.absolutePath)
                if (hasReceipt) put("pngSha256", sha(png))
            }.toString() + "\n") }
            val observations = File(directory, "poses.jsonl").apply { writeText(buildJsonObject {
                put("timestamp", time); put("imageSize", size); put("backend", "authored receipt fixture")
                put("requestRevision", 1); put("people", JsonArray(emptyList()))
            }.toString() + "\n") }
            fun manifest() = buildJsonObject { put("schemaVersion", 1); put("clips", JsonArray(listOf(buildJsonObject {
                put("id", "receipt"); put("frames", 1); put("observations", observations.absolutePath); put("observationsSha256", sha(observations))
                put("metadata", metadata.absolutePath); put("metadataSha256", sha(metadata)); put("backend", "authored receipt fixture")
                put("modelSha256", "a".repeat(64))
            }))) }
            val input = File(directory, "input.json").apply { writeText(manifest().toString()) }
            test(input, png, File(directory, "output"))
        } finally { directory.deleteRecursively() }
    }
    @Test fun nativeFormatRequiresAndPreservesItsPinnedSourcePixelReceipt() = fixture { input, _, output ->
        EstimatedBodyGeometryDiagnostic.main(arrayOf(input.absolutePath, output.absolutePath))
        val summary = Json.parseToJsonElement(File(output, "summary.json").readText()).jsonObject
        assertEquals(1, summary.getValue("clips").jsonArray.single().jsonObject.getValue("frames").jsonPrimitive.int)
    }
    @Test fun replacingOnlyTheSourcePixelsFailsEvenWhenBothJsonPinsStillMatch() = fixture { input, png, output ->
        png.appendBytes(byteArrayOf(5))
        assertFailsWith<IllegalArgumentException> { EstimatedBodyGeometryDiagnostic.main(arrayOf(input.absolutePath, output.absolutePath)) }
        assertFalse(File(output, "summary.json").exists())
    }
    @Test fun missingPixelReceiptsCannotSilentlyBecomeNewPins() = fixture(hasReceipt = false) { input, _, output ->
        assertFailsWith<IllegalArgumentException> { EstimatedBodyGeometryDiagnostic.main(arrayOf(input.absolutePath, output.absolutePath)) }
        assertFalse(File(output, "summary.json").exists())
    }
    @Test fun modifiedObservationBytesFailTheDeclaredPin() = fixture { input, _, output ->
        File(input.parentFile, "poses.jsonl").appendText("\n")
        assertFailsWith<IllegalArgumentException> { EstimatedBodyGeometryDiagnostic.main(arrayOf(input.absolutePath, output.absolutePath)) }
    }
    @Test fun modifiedMetadataBytesFailTheDeclaredPin() = fixture { input, _, output ->
        File(input.parentFile, "frames.jsonl").appendText("\n")
        assertFailsWith<IllegalArgumentException> { EstimatedBodyGeometryDiagnostic.main(arrayOf(input.absolutePath, output.absolutePath)) }
    }
    @Test fun existingOutputsCannotHideAnEarlierPartialRun() = fixture { input, _, output ->
        assertTrue(output.mkdir())
        assertFailsWith<IllegalArgumentException> { EstimatedBodyGeometryDiagnostic.main(arrayOf(input.absolutePath, output.absolutePath)) }
    }
}
