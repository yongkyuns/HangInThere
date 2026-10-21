package dev.hanginthere.android

import com.google.mediapipe.tasks.components.containers.Landmark
import dev.hanginthere.android.vision.PoseWorldLandmarks
import dev.hanginthere.core.PoseJoint
import java.util.Optional
import org.junit.Assert.*
import org.junit.Test

class PoseWorldLandmarksTest {
    private val joints = listOf(PoseJoint.leftShoulder to 11, PoseJoint.rightShoulder to 12)
    private fun person(offset: Int) = List(33) { index -> Landmark.create((offset + index).toFloat(),
        (offset - index).toFloat(), -index.toFloat(), Optional.of(.75f), Optional.of(.5f)) }

    @Test fun distinctBodiesRetainTheirCorrespondingWorldCoordinates() {
        val raw = listOf(person(100), person(200))
        val result = requireNotNull(PoseWorldLandmarks.map(2, raw, joints))
        for (person in raw.indices) for ((position, joint) in joints.withIndex()) {
            val point = result[person][position]
            assertEquals(joint.first, point.joint)
            assertEquals(raw[person][joint.second].x().toDouble(), point.position.x, 0.0)
            assertEquals(raw[person][joint.second].y().toDouble(), point.position.y, 0.0)
            assertEquals(raw[person][joint.second].z().toDouble(), point.position.z, 0.0)
            assertEquals(.5, point.confidence, 0.0)
        }
        val reordered = requireNotNull(PoseWorldLandmarks.map(2, raw.reversed(), joints))
        assertEquals(result.reversed(), reordered)
    }
    @Test fun incompatiblePersonCountsAndPointListsCannotSupplyPartialDepth() {
        assertNull(PoseWorldLandmarks.map(2, listOf(person(100)), joints))
        assertNull(PoseWorldLandmarks.map(1, listOf(person(100), person(200)), joints))
        for (size in listOf(0, 32, 34))
            assertNull(PoseWorldLandmarks.map(2, listOf(person(100), List(size) { person(200)[0] }), joints))
        assertEquals(emptyList<List<Any>>(), PoseWorldLandmarks.map(0, emptyList(), joints))
    }
    @Test fun absentWorldVisibilityRemainsUnavailableAndIsNeverInferredFromImageScores() {
        val noScores = List(33) { Landmark.create(1f, 2f, 3f) }
        val result = requireNotNull(PoseWorldLandmarks.map(1, listOf(noScores), joints))
        assertTrue(result.single().all { it.confidence == 0.0 && !it.isVisible() })
    }
}
