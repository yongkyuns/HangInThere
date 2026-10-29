package dev.hanginthere.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** Shared crop policy. Translation only: no mirroring/rotation of hand world axes.
 * A crop is input framing, never evidence of hand identity or apparatus contact. */
@Serializable data class GripHandCropRequest(val side:ArmMeasurement.Side,val regions:List<PersonInferenceRegion>)
object WorkoutGripInference {
    const val modelId = "mediapipe_hand_full_v1_pose_lite_v1"
    const val cropPolicyVersion = 3
    const val bodyPreflightVersion = 2
    /** Gate the expensive hand stage using the same body frame required by
     * classification. Bind it to this primary pose; a cached/foreign context
     * cannot authorize crops from another source observation. Hands and their
     * orientation never affect eligibility. */
    fun handStageRequests(pose:PoseResult,context:GripObservation):List<GripHandCropRequest> {
        if(context.seconds!=pose.timestamp.seconds || context.imageSize!=pose.imageSize)return emptyList()
        val basis=WorkoutGripGeometry.bodyBasis(context) ?: return emptyList()
        if(!WorkoutGripGeometry.supportsView(basis))return emptyList()
        val person=pose.people.singleOrNull() ?: return emptyList()
        val tracked=person.landmarks.mapNotNull { point ->
            GripBodyJoint.entries.firstOrNull { it.name==point.joint.name }?.let { GripTrackedPoint(it,point.position,point.confidence) }
        }
        if(tracked.size!=context.trackedBody.size || tracked.toSet()!=context.trackedBody.toSet())return emptyList()
        return handRequests(pose)
    }
    /** First preserve the forearm-scaled crop; retry a missing or identity-invalid
     * hand once with more wrist-centered context. Native adapters stop at the
     * first identity-valid result, independent of its grip orientation. */
    fun handRequests(pose:PoseResult):List<GripHandCropRequest> {
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
            val regions=listOfNotNull(region(size,x,y,side),region(size,wrist.x,wrist.y,short*.28)).distinct()
            regions.takeIf { it.isNotEmpty() }?.let { GripHandCropRequest(if(wristJoint==PoseJoint.leftWrist)ArmMeasurement.Side.left else ArmMeasurement.Side.right,it) }
        }
    }
    fun handRegionCandidates(pose:PoseResult):List<List<PersonInferenceRegion>> = handRequests(pose).map { it.regions }
    fun handRegions(pose:PoseResult):List<PersonInferenceRegion> = handRegionCandidates(pose).map { it.first() }
    private fun region(size:ImageSize,x:Double,y:Double,side:Double):PersonInferenceRegion? {
        val left=floor(maxOf(0.0,x-side*.5)).toInt();val top=floor(maxOf(0.0,y-side*.5)).toInt()
        val right=ceil(minOf(size.width,x+side*.5)).toInt();val bottom=ceil(minOf(size.height,y+side*.5)).toInt()
        return PersonInferenceRegion(left,top,right-left,bottom-top).takeIf { it.isValidFor(size) }
    }
}
class WorkoutGripInferenceBridge {
    val cropPolicyVersion get() = WorkoutGripInference.cropPolicyVersion
    val bodyPreflightVersion get() = WorkoutGripInference.bodyPreflightVersion
    @Throws(IllegalArgumentException::class)
    fun handStageRequestsJson(poseJson:String,observationJson:String):String = WorkoutRecordCodec.json.encodeToString(
        WorkoutGripInference.handStageRequests(WorkoutRecordCodec.json.decodeFromString(poseJson),WorkoutRecordCodec.json.decodeFromString(observationJson)))
    @Throws(IllegalArgumentException::class)
    fun handRequestsJson(poseJson:String):String = WorkoutRecordCodec.json.encodeToString(WorkoutGripInference.handRequests(WorkoutRecordCodec.json.decodeFromString(poseJson)))
    @Throws(IllegalArgumentException::class)
    fun hasHandIdentityJson(observationJson:String,handJson:String,expectedSide:String):Boolean = WorkoutGripGeometry.hasHandIdentity(
        WorkoutRecordCodec.json.decodeFromString(observationJson),WorkoutRecordCodec.json.decodeFromString(handJson),ArmMeasurement.Side.valueOf(expectedSide))
    @Throws(IllegalArgumentException::class)
    fun handRegionsJson(poseJson:String):String = WorkoutRecordCodec.json.encodeToString(WorkoutGripInference.handRegions(WorkoutRecordCodec.json.decodeFromString(poseJson)))
    @Throws(IllegalArgumentException::class)
    fun handRegionCandidatesJson(poseJson:String):String = WorkoutRecordCodec.json.encodeToString(WorkoutGripInference.handRegionCandidates(WorkoutRecordCodec.json.decodeFromString(poseJson)))
    /** SDK-free geometric inspection for offline/native inference evaluation.
     * Live labels still require WorkoutGripAnalysis source-time confirmation. */
    @Throws(IllegalArgumentException::class)
    fun orientationJson(observationJson:String):String = WorkoutRecordCodec.json.encodeToString(WorkoutGripGeometry.orientation(WorkoutRecordCodec.json.decodeFromString(observationJson)))
}
