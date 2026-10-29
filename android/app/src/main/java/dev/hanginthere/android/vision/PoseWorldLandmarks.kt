package dev.hanginthere.android.vision

import com.google.mediapipe.tasks.components.containers.Landmark as SdkLandmark
import dev.hanginthere.core.EstimatedWorldLandmark
import dev.hanginthere.core.Point3D
import dev.hanginthere.core.PoseJoint

/** Preserve the SDK's person ordering. A structurally incompatible result
 * cannot donate depth from a different body or a previous request. */
internal object PoseWorldLandmarks {
    fun map(peopleCount: Int, estimates: List<List<SdkLandmark>>,
            joints: List<Pair<PoseJoint, Int>>): List<List<EstimatedWorldLandmark>>? {
        if (estimates.size != peopleCount || estimates.any { it.size != 33 }) return null
        return estimates.map { positions -> joints.map { (joint, index) ->
            val point = positions[index]
            EstimatedWorldLandmark(joint, Point3D(point.x().toDouble(), point.y().toDouble(), point.z().toDouble()),
                minOf(point.visibility().orElse(0f), point.presence().orElse(0f)).toDouble())
        } }
    }
}
