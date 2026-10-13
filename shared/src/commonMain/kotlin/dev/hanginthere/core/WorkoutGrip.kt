package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** Model-estimated camera axes: x right, y down, z away. Never substitute 0 for missing depth. */
@Serializable data class GripVector3(val x: Double, val y: Double, val z: Double) {
    val isFinite get() = x.isFinite() && y.isFinite() && z.isFinite()
    val length get() = sqrt(x*x+y*y+z*z)
    operator fun minus(other: GripVector3) = GripVector3(x-other.x,y-other.y,z-other.z)
    operator fun plus(other: GripVector3) = GripVector3(x+other.x,y+other.y,z+other.z)
    operator fun times(scale: Double) = GripVector3(x*scale,y*scale,z*scale)
    fun dot(other: GripVector3) = x*other.x+y*other.y+z*other.z
    fun cross(other: GripVector3) = GripVector3(y*other.z-z*other.y,z*other.x-x*other.z,x*other.y-y*other.x)
    fun unit(): GripVector3? = length.takeIf { isFinite && it > 1e-6 }?.let { this*(1/it) }
}
@Serializable enum class GripBodyJoint { leftShoulder,rightShoulder,leftHip,rightHip,leftWrist,rightWrist }
@Serializable enum class GripHandJoint { wrist,indexMCP,middleMCP,pinkyMCP,thumbCMC,thumbTip }
@Serializable data class GripTrackedPoint(val joint: GripBodyJoint, val position: Point2D, val score: Double)
@Serializable data class GripBodyPoint(val joint: GripBodyJoint, val position: Point2D, val world: GripVector3, val score: Double)
@Serializable data class GripHandPoint(val joint: GripHandJoint, val position: Point2D, val world: GripVector3? = null,
                                     val score: Double? = null)
@Serializable data class GripHandObservation(val points: List<GripHandPoint>, val sdkHandedness: String? = null,
                                           val sdkHandednessScore: Double? = null, val sourceRegion: PersonInferenceRegion? = null)
/** A separately scheduled observation, bound to the original body/pixel sample.
 * Handedness scores describe the SDK's side classification, not grip confidence.
 * Raw frames/hand points are not retained in workout history. */
@Serializable data class GripObservation(val seconds: Double, val imageSize: ImageSize,
    val trackedBody: List<GripTrackedPoint>, val body: List<GripBodyPoint>, val hands: List<GripHandObservation>,
    val modelId: String, val backend: String, val coordinateConvention: String = "cameraRightDownAway_v1",
    val failure: String? = null, val schemaVersion: Int = 1)

@Serializable enum class HandGrip { unknown,underhand,overhand,neutral }
@Serializable enum class GripOrientation { unknown,underhand,overhand,neutral,mixed,varied }
@Serializable enum class GripWidth { unknown,narrow,shoulderWidth,wide,varied }
@Serializable data class GripEstimate(val left: HandGrip = HandGrip.unknown, val right: HandGrip = HandGrip.unknown,
    val width: GripWidth = GripWidth.unknown, val projectedSpanRatio: Double? = null,
    val orientation: GripOrientation = combine(left,right), val reason: String? = null,
    val modelId: String? = null, val orientationCoverage: Double? = null, val widthCoverage: Double? = null,
    val policyVersion: Int = WorkoutGripGeometry.policyVersion, val inferenceBackend: String? = null) {
    val label get() = listOf(orientationTitle(orientation),widthTitle(width)).joinToString(" · ")
    companion object {
        fun combine(left: HandGrip, right: HandGrip): GripOrientation = when {
            left==HandGrip.unknown || right==HandGrip.unknown -> GripOrientation.unknown
            left!=right -> GripOrientation.mixed
            else -> GripOrientation.valueOf(left.name)
        }
        fun orientationTitle(value: GripOrientation) = when(value) {
            GripOrientation.unknown -> "Grip unknown"; GripOrientation.underhand -> "Underhand"
            GripOrientation.overhand -> "Overhand"; GripOrientation.neutral -> "Neutral"
            GripOrientation.mixed -> "Mixed grip"; GripOrientation.varied -> "Grip changed"
        }
        fun widthTitle(value: GripWidth) = when(value) {
            GripWidth.unknown -> "Width unknown"; GripWidth.narrow -> "Narrow"
            GripWidth.shoulderWidth -> "Shoulder-width"; GripWidth.wide -> "Wide"; GripWidth.varied -> "Width changed"
        }
    }
}
@Serializable data class GripOrientationSpan(val id: String, val startedAt: Double, val endedAt: Double,
    val left: HandGrip, val right: HandGrip, val observedSeconds: Double, val reason: String?, val modelId: String?, val inferenceBackend: String? = null)
@Serializable data class GripWidthSpan(val id: String, val startedAt: Double, val endedAt: Double,
    val width: GripWidth, val projectedSpanRatio: Double?, val observedSeconds: Double, val reason: String?)
@Serializable data class WorkoutGripRecord(val orientationSpans: List<GripOrientationSpan>, val widthSpans: List<GripWidthSpan>,
    val latest: GripEstimate, val timelineTruncated: Boolean = false, val policyVersion: Int = WorkoutGripGeometry.policyVersion)

/** Geometry only; stabilization, lifecycle and storage are independent consumers.
 * These are monocular estimates. No SDK landmark score is exposed as grip confidence. */
object WorkoutGripGeometry {
    const val policyVersion = 1
    const val maximumAgeSeconds = .5
    const val samplingIntervalSeconds = .25
    private fun score(value: Double?) = value==null || value.isFinite() && value in .5..1.0
    private fun inside(point: Point2D,size: ImageSize) = point.isFinite && size.isValid && point.x in 0.0..size.width && point.y in 0.0..size.height
    private fun inside(point: Point2D, observation: GripObservation, hand: GripHandObservation): Boolean {
        if(!inside(point,observation.imageSize))return false
        val region=hand.sourceRegion ?: return true
        return region.isValidFor(observation.imageSize) && point.x in region.left.toDouble()..(region.left+region.width).toDouble() &&
            point.y in region.top.toDouble()..(region.top+region.height).toDouble()
    }
    data class BodyBasis(val left: GripVector3, val facing: GripVector3)
    fun bodyBasis(observation: GripObservation): BodyBasis? {
        if(observation.failure!=null || observation.schemaVersion!=1 || observation.coordinateConvention!="cameraRightDownAway_v1" ||
            !observation.seconds.isFinite() || !observation.imageSize.isValid || observation.body.size!=6 || observation.trackedBody.size!=6 ||
            observation.body.map { it.joint }.toSet().size!=6 || observation.trackedBody.map { it.joint }.toSet().size!=6) return null
        val points=observation.body.associateBy { it.joint }
        val tracked=observation.trackedBody.associateBy { it.joint }
        val short=minOf(observation.imageSize.width,observation.imageSize.height)
        if(points.values.any { !score(it.score) || !it.world.isFinite || !inside(it.position,observation.imageSize) } ||
            tracked.values.any { !score(it.score) || !inside(it.position,observation.imageSize) } ||
            points.any { (joint,p)->p.position.distanceTo(tracked.getValue(joint).position)>short*.12 }) return null
        fun world(joint: GripBodyJoint)=points.getValue(joint).world
        val across=world(GripBodyJoint.leftShoulder)-world(GripBodyJoint.rightShoulder)
        val up=(world(GripBodyJoint.leftShoulder)+world(GripBodyJoint.rightShoulder)-world(GripBodyJoint.leftHip)-world(GripBodyJoint.rightHip))*.5
        if(across.length !in .08..1.0 || up.length !in .12..1.2) return null
        val left=across.unit() ?: return null
        val vertical=up.unit() ?: return null
        if(kotlin.math.abs(left.dot(vertical))>.6) return null
        return BodyBasis(left,left.cross(vertical).unit() ?: return null)
    }
    fun orientation(observation: GripObservation): GripEstimate {
        val basis=bodyBasis(observation) ?: return GripEstimate(reason=observation.failure ?: "bodyFrameUnavailable",modelId=observation.modelId)
        if(observation.hands.size>2) return GripEstimate(reason="handIdentityAmbiguous",modelId=observation.modelId)
        val body=observation.body.associateBy { it.joint }
        val assignments=mutableMapOf<ArmMeasurement.Side,GripHandObservation>()
        val short=minOf(observation.imageSize.width,observation.imageSize.height)
        for(hand in observation.hands) {
            if(hand.points.map { it.joint }.toSet().size!=hand.points.size) continue
            val wrist=hand.points.singleOrNull { it.joint==GripHandJoint.wrist && score(it.score) && inside(it.position,observation,hand) } ?: continue
            val left=wrist.position.distanceTo(body.getValue(GripBodyJoint.leftWrist).position)
            val right=wrist.position.distanceTo(body.getValue(GripBodyJoint.rightWrist).position)
            if(minOf(left,right)>short*.08 || kotlin.math.abs(left-right)<short*.04) continue
            val side=if(left<right)ArmMeasurement.Side.left else ArmMeasurement.Side.right
            if(assignments.containsKey(side)) return GripEstimate(reason="handIdentityAmbiguous",modelId=observation.modelId)
            assignments[side]=hand
        }
        fun classify(side: ArmMeasurement.Side): HandGrip {
            val hand=assignments[side] ?: return HandGrip.unknown
            // Task adapters receive upright, unmirrored pixels. A confident
            // contradictory side estimate makes the anatomical MCP ordering
            // ambiguous; never silently apply a body-derived chirality sign.
            val sdkSide=hand.sdkHandedness?.lowercase()
            val sdkScore=hand.sdkHandednessScore
            if(sdkSide!=null && sdkScore!=null && (!sdkScore.isFinite() || sdkScore !in 0.0..1.0 ||
                sdkScore>=.8 && sdkSide!=side.name))return HandGrip.unknown
            fun point(joint: GripHandJoint)=hand.points.singleOrNull { it.joint==joint && score(it.score) && inside(it.position,observation,hand) }?.world?.takeIf { it.isFinite }
            val wrist=point(GripHandJoint.wrist) ?: return HandGrip.unknown
            val index=point(GripHandJoint.indexMCP)?.minus(wrist) ?: return HandGrip.unknown
            val middle=point(GripHandJoint.middleMCP)?.minus(wrist) ?: return HandGrip.unknown
            val pinky=point(GripHandJoint.pinkyMCP)?.minus(wrist) ?: return HandGrip.unknown
            if(listOf(index,middle,pinky).any { it.length !in .02..0.25 }) return HandGrip.unknown
            val normal=index.cross(pinky)
            if(normal.length/(index.length*pinky.length)<.2) return HandGrip.unknown
            val n1=index.cross(middle).unit() ?: return HandGrip.unknown
            val n2=middle.cross(pinky).unit() ?: return HandGrip.unknown
            if(n1.dot(n2)<.6) return HandGrip.unknown
            val palm=(normal.unit() ?: return HandGrip.unknown)*(if(side==ArmMeasurement.Side.left)-1.0 else 1.0)
            val forward=palm.dot(basis.facing)
            return when {
                forward>=.7 -> HandGrip.overhand
                forward<=-.7 -> HandGrip.underhand
                kotlin.math.abs(forward)<.35 && palm.dot(basis.left)*(if(side==ArmMeasurement.Side.right)1 else -1)>=.7 -> HandGrip.neutral
                else -> HandGrip.unknown
            }
        }
        val left=classify(ArmMeasurement.Side.left);val right=classify(ArmMeasurement.Side.right)
        return GripEstimate(left,right,reason=if(left==HandGrip.unknown || right==HandGrip.unknown)"handOrientationAmbiguous" else null,modelId=observation.modelId)
    }
    fun projectedWidth(pose: PoseResult, bar: BarSegment?, shoulderSpan: Double): Double? {
        if(pose.people.size!=1 || bar?.isValid!=true || !shoulderSpan.isFinite() || shoulderSpan<=0) return null
        val person=pose.people.single()
        val left=person.landmark(PoseJoint.leftWrist,.5)?.position ?: return null
        val right=person.landmark(PoseJoint.rightWrist,.5)?.position ?: return null
        if(!inside(left,pose.imageSize) || !inside(right,pose.imageSize)) return null
        val short=minOf(pose.imageSize.width,pose.imageSize.height)
        if(bar.distance(left)>short*.085 || bar.distance(right)>short*.085) return null
        val dx=bar.b.x-bar.a.x;val dy=bar.b.y-bar.a.y
        val ratio=kotlin.math.abs((left.x-right.x)*dx+(left.y-right.y)*dy)/bar.length/shoulderSpan
        return ratio.takeIf { it.isFinite() && it in .1..4.0 }
    }
    fun widthBand(ratio: Double)=when { !ratio.isFinite() || ratio !in .1..4.0 -> GripWidth.unknown;ratio<.85 -> GripWidth.narrow;ratio>1.3 -> GripWidth.wide;else -> GripWidth.shoulderWidth }
}

private fun Point2D.distanceTo(other: Point2D)=kotlin.math.hypot(x-other.x,y-other.y)
