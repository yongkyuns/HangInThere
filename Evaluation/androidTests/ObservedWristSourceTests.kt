package dev.hanginthere.evaluation

import dev.hanginthere.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Original public SDK values exercise partial-hand correspondence on both
 * host JVM and actual Android instrumentation. No new SDK inference here. */
class ObservedWristSourceTests {
    private val json = WorkoutRecordCodec.json

    @Test fun independentSourceWristsNeedNeitherFingersNorElbowAngles() {
        val source = checkNotNull(javaClass.getResourceAsStream("/observed-wrist-source.json"))
            .bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
        val cases = source.getValue("cases").jsonArray
        assertEquals(4, cases.size)
        for (value in cases) {
            val case = value.jsonObject
            val pose = json.decodeFromJsonElement<PoseResult>(case.getValue("pose"))
            val handObservation = checkNotNull(pose.gripObservation)
            val before = json.encodeToString(pose)
            val expected = case.getValue("expectedSides").jsonArray.map { it.jsonPrimitive.content }
            val original = ObservedWristAssociation.associate(ObservedWristAssociation.Frame(pose, pose.timestamp))!!
            assertEquals(expected, original.matches.map { it.bodyWrists.single().side.name })
            assertTrue(original.matches.all { it.originalPersonIndex == 0 })
            assertTrue(original.unresolvedHands.isEmpty())
            assertEquals(original.matches, original.uniqueArmMatches())
            val frame = ObservedWristAssociation.Frame(pose, pose.timestamp)
            val selectedJson = ObservedWristAssociationBridge().uniqueArmMatchesJson(json.encodeToString(frame))!!
            assertEquals(original.uniqueArmMatches(), json.decodeFromString<List<ObservedWristAssociation.Match>>(selectedJson))
            val partial = pose.copy(people = pose.people.map { person ->
                person.copy(landmarks = person.landmarks.filter { it.joint !in listOf(PoseJoint.leftElbow, PoseJoint.rightElbow) })
            }, gripObservation = handObservation.copy(hands = handObservation.hands.map { hand ->
                hand.copy(points = hand.points.filter { it.joint == GripHandJoint.wrist })
            }))
            val actual = ObservedWristAssociation.associate(ObservedWristAssociation.Frame(partial, partial.timestamp))!!
            assertEquals(original, actual)
            assertEquals(original.uniqueArmMatches(), actual.uniqueArmMatches())
            assertEquals(before, json.encodeToString(pose))
        }
    }
}
