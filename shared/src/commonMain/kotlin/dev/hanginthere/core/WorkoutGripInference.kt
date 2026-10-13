package dev.hanginthere.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** Shared crop policy. Translation only: no mirroring/rotation of hand world axes.
 * A crop is input framing, never evidence of hand identity or apparatus contact. */
object WorkoutGripInference {
    const val modelId = "mediapipe_hand_full_v1_pose_lite_v1"
    fun handRegions(pose:PoseResult):List<PersonInferenceRegion> {
        val size=pose.imageSize
        if(!size.isValid || size.width!=floor(size.width) || size.height!=floor(size.height))return emptyList()
        val person=pose.people.singleOrNull() ?: return emptyList()
        if(person.landmarks.map { it.joint }.distinct().size!=person.landmarks.size)return emptyList()
        val short=minOf(size.width,size.height)
        return listOf(PoseJoint.leftWrist to PoseJoint.leftElbow,PoseJoint.rightWrist to PoseJoint.rightElbow).mapNotNull { (wristJoint,elbowJoint) ->
            val wrist=person.landmark(wristJoint,.5)?.position ?: return@mapNotNull null
            if(wrist.x !in 0.0..size.width || wrist.y !in 0.0..size.height)return@mapNotNull null
            val elbow=person.landmark(elbowJoint,.5)?.position
            val side=(elbow?.let { hypot(wrist.x-it.x,wrist.y-it.y)*1.5 } ?: short*.2).coerceIn(short*.12,short*.4)
            val x=wrist.x+(elbow?.let { (wrist.x-it.x)*.15 } ?: 0.0)
            val y=wrist.y+(elbow?.let { (wrist.y-it.y)*.15 } ?: 0.0)
            val left=floor(maxOf(0.0,x-side*.5)).toInt();val top=floor(maxOf(0.0,y-side*.5)).toInt()
            val right=ceil(minOf(size.width,x+side*.5)).toInt();val bottom=ceil(minOf(size.height,y+side*.5)).toInt()
            PersonInferenceRegion(left,top,right-left,bottom-top).takeIf { it.isValidFor(size) }
        }
    }
}
class WorkoutGripInferenceBridge {
    @Throws(IllegalArgumentException::class)
    fun handRegionsJson(poseJson:String):String = WorkoutRecordCodec.json.encodeToString(WorkoutGripInference.handRegions(WorkoutRecordCodec.json.decodeFromString(poseJson)))
}
