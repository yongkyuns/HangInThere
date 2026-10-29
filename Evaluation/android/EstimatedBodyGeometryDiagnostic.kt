package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Test-classpath tool: evaluates shared geometry on pinned saved observations.
 * It neither runs an SDK nor accepts a rep. Native/Python inference provenance
 * is retained from the input; this tool cannot upgrade it to qualification. */
object EstimatedBodyGeometryDiagnostic {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaPipeJoints = mapOf(PoseJoint.nose to 0, PoseJoint.leftEye to 2, PoseJoint.rightEye to 5,
        PoseJoint.leftEar to 7, PoseJoint.rightEar to 8, PoseJoint.leftShoulder to 11, PoseJoint.rightShoulder to 12,
        PoseJoint.leftElbow to 13, PoseJoint.rightElbow to 14, PoseJoint.leftWrist to 15, PoseJoint.rightWrist to 16,
        PoseJoint.leftHip to 23, PoseJoint.rightHip to 24, PoseJoint.leftKnee to 25, PoseJoint.rightKnee to 26,
        PoseJoint.leftAnkle to 27, PoseJoint.rightAnkle to 28)
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            val length = input.read(buffer); if (length < 0) break; digest.update(buffer, 0, length)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun pinned(spec: JsonObject, field: String): File {
        val file = File(spec.getValue(field).jsonPrimitive.content)
        require(sha(file) == spec.getValue(field + "Sha256").jsonPrimitive.content) { "Input pin mismatch: $field" }
        return file
    }
    private fun pose(row: JsonObject): PoseResult {
        val result = json.decodeFromJsonElement<PoseResult>(row)
        // Native app exports already use the common same-request data model.
        if ("worldPeople" !in row) return result
        require(result.estimatedWorldConvention == null && result.people.all { it.estimatedWorldLandmarks == null })
        val world = row.getValue("worldPeople").jsonArray
        require(world.size == result.people.size && world.all { it.jsonArray.size == 33 })
        return result.copy(estimatedWorldConvention = EstimatedBodyGeometry.convention,
            people = result.people.mapIndexed { index, person -> person.copy(estimatedWorldLandmarks = mediaPipeJoints.map { (joint, sdkIndex) ->
                val point = world[index].jsonArray[sdkIndex].jsonObject
                EstimatedWorldLandmark(joint, Point3D(point.getValue("x").jsonPrimitive.double,
                    point.getValue("y").jsonPrimitive.double, point.getValue("z").jsonPrimitive.double),
                    minOf(point.getValue("visibility").jsonPrimitive.double, point.getValue("presence").jsonPrimitive.double))
            }) })
    }
    private fun geometry(value: EstimatedBodyGeometry.Measurement?): JsonElement = value?.let {
        buildJsonObject { put("degrees", it.elbowDegrees); put("upper", it.upperArmToTorsoDegrees)
            put("arm", it.armLength); put("torso", it.torsoLength); put("height", -it.hipToWristDistance)
            put("axial", it.shoulderToWristAlongTorso) }
    } ?: JsonNull
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: EstimatedBodyGeometryDiagnostic pinned-inputs.json fresh-output-directory" }
        val manifest = File(args[0]); val input = json.parseToJsonElement(manifest.readText()).jsonObject
        require(input.getValue("schemaVersion").jsonPrimitive.int == 1)
        val output = File(args[1]); require(!output.exists()) { "Use a fresh diagnostic output directory" }; require(output.mkdirs())
        val ids = mutableSetOf<String>(); val summaries = mutableListOf<JsonObject>()
        for (element in input.getValue("clips").jsonArray) {
            val spec = element.jsonObject; val id = spec.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && ids.add(id))
            val raw = pinned(spec, "observations"); val metadata = pinned(spec, "metadata")
            val expected = spec.getValue("frames").jsonPrimitive.int; require(expected > 0)
            val frames = metadata.readLines(); var count = 0; var left = 0; var right = 0
            val backend = spec.getValue("backend").jsonPrimitive.content
            val model = spec.getValue("modelSha256").jsonPrimitive.content
            require(model.matches(Regex("[0-9a-f]{64}")))
            File(output, "$id.jsonl").bufferedWriter().use { writer -> raw.forEachLine { line ->
                require(count < frames.size && line.isNotBlank())
                val row = json.parseToJsonElement(line).jsonObject
                val meta = json.parseToJsonElement(frames[count]).jsonObject
                require(meta.getValue("frameIndex").jsonPrimitive.int == count)
                row["frameIndex"]?.let { require(it.jsonPrimitive.int == count) }
                require(json.decodeFromJsonElement<PresentationTime>(row.getValue("timestamp")) == json.decodeFromJsonElement<PresentationTime>(meta.getValue("timestamp")) &&
                    json.decodeFromJsonElement<ImageSize>(row.getValue("imageSize")) == json.decodeFromJsonElement<ImageSize>(meta.getValue("imageSize")))
                val pngSha = sha(File(meta.getValue("path").jsonPrimitive.content))
                val receipts = listOfNotNull(row["input_png_sha256"], meta["pngSha256"])
                require(receipts.isNotEmpty()) { "Every frame needs a pinned PNG receipt" }
                for (receipt in receipts) require(receipt.jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")) &&
                    receipt.jsonPrimitive.content == pngSha) { "Source pixel receipt mismatch: $id frame $count" }
                row["model_sha256"]?.let { require(it.jsonPrimitive.content == model) }
                require(row.getValue("backend").jsonPrimitive.content == backend)
                val pose = pose(row)
                val a = EstimatedBodyGeometry.measure(pose, ArmMeasurement.Side.left)
                val b = EstimatedBodyGeometry.measure(pose, ArmMeasurement.Side.right)
                if (a != null) left++; if (b != null) right++
                writer.write(buildJsonObject { put("frame", count); put("timestamp", row.getValue("timestamp"))
                    put("sourcePeople", pose.people.size); put("inputPngSha256", pngSha)
                    put("left", geometry(a)); put("right", geometry(b)) }.toString()); writer.newLine(); count++
            } }
            require(count == expected && frames.size == expected) { "Incomplete source coverage: $id $count/$expected" }
            summaries += buildJsonObject { put("id", id); put("frames", count); put("leftMeasuredFrames", left); put("rightMeasuredFrames", right)
                put("observationsSha256", sha(raw)); put("metadataSha256", sha(metadata)); put("modelSha256", model); put("backend", backend) }
        }
        require(summaries.isNotEmpty())
        File(output, "summary.json").writeText(buildJsonObject { put("schemaVersion", 1); put("inputManifestSha256", sha(manifest))
            put("scope", "saved-observation shared geometry; no new SDK execution or rep/form/load qualification")
            put("policyVersion", EstimatedBodyGeometry.policyVersion); put("clips", JsonArray(summaries)) }.toString() + "\n")
        summaries.forEach { println(it) }
    }
}
