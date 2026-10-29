package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.File

/** Opt-in local data bridge. The original poses and pixels remain native-owned;
 * Python only owns SDK execution and training. Shared code chooses crops,
 * anatomical identity and classifier features for both mobile platforms.
 */
class BinaryGripLearningPipelineTests {
    @Test fun processAuthorizedLocalObservations() {
        val input = System.getenv("BINARY_GRIP_INPUT") ?: return
        val output = checkNotNull(System.getenv("BINARY_GRIP_OUTPUT"))
        val mode = checkNotNull(System.getenv("BINARY_GRIP_MODE"))
        val json = WorkoutRecordCodec.json
        val model = System.getenv("BINARY_GRIP_MODEL")?.let { BinaryGripClassifier(json.decodeFromString<BinaryGripClassifier.Model>(File(it).readText())) }
        File(output).bufferedWriter().use { writer ->
            File(input).forEachLine { line ->
                val row = json.parseToJsonElement(line).jsonObject
                val observation = json.decodeFromJsonElement<GripObservation>(row.getValue("observation"))
                val pose = json.decodeFromJsonElement<PoseResult>(row.getValue("pose"))
                val result = buildJsonObject {
                    row.forEach { (key, value) -> put(key, value) }
                    when (mode) {
                        "plan" -> put("requests", json.encodeToJsonElement(WorkoutGripInference.handStageRequests(pose, observation)))
                        "features" -> {
                            val hands = row.getValue("candidates").jsonArray.map { it.jsonObject }
                            val selected = ArmMeasurement.Side.entries.mapNotNull { side -> hands
                                .filter { it.getValue("side").jsonPrimitive.content == side.name }
                                .flatMap { it.getValue("hands").jsonArray }
                                .map { json.decodeFromJsonElement<GripHandObservation>(it) }
                                .firstOrNull { WorkoutGripGeometry.hasHandIdentity(observation, it, side) } }
                            val current = observation.copy(hands = selected)
                            put("observation", json.encodeToJsonElement(current))
                            put("features", buildJsonObject {
                                ArmMeasurement.Side.entries.forEach { side ->
                                    BinaryGripFeatures.extract(current, side)?.let { put(side.name, json.encodeToJsonElement(it)) }
                                }
                            })
                            put("legacy", json.encodeToJsonElement(WorkoutGripGeometry.orientation(current)))
                        }
                        "score" -> {
                            checkNotNull(model)
                            put("decisions", buildJsonObject { ArmMeasurement.Side.entries.forEach { side ->
                                put(side.name, json.encodeToJsonElement(model.classify(observation, side)))
                            } })
                        }
                        else -> error("Unknown local bridge mode")
                    }
                }
                writer.write(result.toString()); writer.newLine()
            }
        }
    }
}
