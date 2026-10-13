package dev.hanginthere.android.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import dev.hanginthere.android.BuildConfig
import dev.hanginthere.core.*
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.Executors

/** The caller owns the SDK handle and source bitmap. Each wrist gets at most
 * two crop calls and one result; already detected hands never use the fallback. */
internal fun inferGripHandCrops(bitmap:Bitmap,pose:PoseResult,hand:HandLandmarker):List<GripHandObservation> {
    val hands=mutableListOf<GripHandObservation>()
    for(candidates in WorkoutGripInference.handRegionCandidates(pose).take(2)) {
        for(region in candidates.take(2)) {
            val cropped=Bitmap.createBitmap(bitmap,region.left,region.top,region.width,region.height)
            // createBitmap may return its input for a full-image rectangle.
            val owned=if(cropped===bitmap)checkNotNull(cropped.copy(Bitmap.Config.ARGB_8888,false)) else cropped
            val image=try { BitmapImageBuilder(owned).build() } catch(error:Exception) { owned.recycle();throw error }
            val h=try { hand.detect(image) } finally { image.close() }
            val candidate=h.landmarks().mapIndexedNotNull { i,points ->
                if(points.size!=21 || h.worldLandmarks().getOrNull(i)?.size!=21)return@mapIndexedNotNull null
                val side=h.handedness().getOrNull(i)?.firstOrNull()
                GripHandObservation(GRIP_HAND_JOINTS.map { (joint,index) ->
                    val p=points[index];val w=h.worldLandmarks()[i][index]
                    GripHandPoint(joint,Point2D(region.left+p.x().toDouble()*region.width,region.top+p.y().toDouble()*region.height),
                        GripVector3(w.x().toDouble(),w.y().toDouble(),w.z().toDouble()))
                },side?.categoryName(),side?.score()?.toDouble(),sourceRegion=region)
            }.firstOrNull()
            if(candidate!=null) { hands+=candidate;break }
        }
    }
    return hands
}
private val GRIP_HAND_JOINTS=listOf(GripHandJoint.wrist to 0,GripHandJoint.indexMCP to 5,GripHandJoint.middleMCP to 9,GripHandJoint.pinkyMCP to 17,GripHandJoint.thumbCMC to 1,GripHandJoint.thumbTip to 4)

/** Optional, bounded secondary inference. Neither the body estimator nor the
 * workout actor waits for it. The serial executor owns and closes SDK handles. */
class GripInference(context: Context) : Closeable {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "workout-grip").apply { priority = Thread.MIN_PRIORITY } }
    private val lock = Any()
    private var busy = false
    private var closed = false
    private var generation = 0
    private var lastSeconds: Double? = null
    private var latest: GripObservation? = null
    private var hand: HandLandmarker? = null
    private var body: PoseLandmarker? = null
    private var unavailable = false
    private fun load() {
        if (hand != null || unavailable) return
        fun options(file: String, hash: String): BaseOptions {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(file).use { stream ->
                val block = ByteArray(65536)
                while(true) { val n=stream.read(block); if(n<0)break;digest.update(block,0,n) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Grip model checksum mismatch" }
            return BaseOptions.builder().setModelAssetPath(file).setDelegate(Delegate.CPU).build()
        }
        val h=HandLandmarker.createFromOptions(context,HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(options("hand_landmarker.task","fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"))
            .setRunningMode(RunningMode.IMAGE).setNumHands(1).setMinHandDetectionConfidence(.5f).setMinHandPresenceConfidence(.5f).build())
        try {
            val b=PoseLandmarker.createFromOptions(context,PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(options("pose_landmarker_lite.task","59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"))
                .setRunningMode(RunningMode.IMAGE).setNumPoses(2).setMinPoseDetectionConfidence(.5f).setMinPosePresenceConfidence(.5f).build())
            hand=h;body=b
        } catch(error:Exception) { h.close();throw error }
    }
    fun observe(bitmap: Bitmap, pose: PoseResult): GripObservation? = synchronized(lock) {
        if(closed)return@synchronized null
        val time=pose.timestamp.seconds
        if(lastSeconds?.let { time<it }==true) { generation++;lastSeconds=null;latest=null }
        val result=latest?.takeIf { it.imageSize==pose.imageSize && time>=it.seconds && time-it.seconds<=WorkoutGripGeometry.maximumAgeSeconds }
        if(!busy && time.isFinite() && pose.people.size==1 && (lastSeconds?.let { time-it>=WorkoutGripGeometry.samplingIntervalSeconds } ?: true)) {
            val pixels=bitmap.copy(Bitmap.Config.ARGB_8888,false) ?: return@synchronized result
            val token=generation;busy=true;lastSeconds=time
            executor.execute {
                val observation=try { estimate(pixels,pose) } finally { if(!pixels.isRecycled)pixels.recycle() }
                synchronized(lock) { busy=false;if(!closed && generation==token)latest=observation }
            }
        }
        result
    }
    /** IMAGE mode is independent of a prior frame; shared policy stabilizes source observations. */
    internal fun estimate(bitmap:Bitmap,pose:PoseResult):GripObservation {
        val tracked=pose.people.singleOrNull()?.landmarks?.mapNotNull { p ->
            GripBodyJoint.entries.firstOrNull { it.name==p.joint.name }?.let { GripTrackedPoint(it,p.position,p.confidence) }
        } ?: emptyList()
        val base=GripObservation(pose.timestamp.seconds,pose.imageSize,tracked,emptyList(),emptyList(),WorkoutGripInference.modelId,
            "MediaPipe Android ${BuildConfig.POSE_RUNTIME_VERSION} CPU IMAGE crops_v${WorkoutGripInference.cropPolicyVersion}")
        if(tracked.size !in 4..6)return base.copy(failure="bodyTrackingUnavailable")
        return try {
            load();val body=body ?: return base.copy(failure="gripModelUnavailable");val hand=hand!!
            val sdkPixels=checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888,false))
            val image=try { BitmapImageBuilder(sdkPixels).build() } catch(error:Exception) { sdkPixels.recycle();throw error }
            val b=try { body.detect(image) } finally { image.close() }
            if(b.landmarks().size!=1 || b.worldLandmarks().size!=1 || b.landmarks()[0].size!=33 || b.worldLandmarks()[0].size!=33)return base.copy(failure="bodyFrameUnavailable")
            val bodyPoints=BODY.map { (joint,index) ->
                val p=b.landmarks()[0][index];val w=b.worldLandmarks()[0][index]
                GripBodyPoint(joint,Point2D(p.x().toDouble()*bitmap.width,p.y().toDouble()*bitmap.height),
                    GripVector3(w.x().toDouble(),w.y().toDouble(),w.z().toDouble()),minOf(p.visibility().orElse(0f),p.presence().orElse(0f)).toDouble())
            }
            val hands=inferGripHandCrops(bitmap,pose,hand)
            base.copy(body=bodyPoints,hands=hands)
        } catch(error:Exception) { if(hand==null || body==null)unavailable=true;base.copy(failure="gripInferenceUnavailable") }
    }
    fun reset() = synchronized(lock) { generation++;latest=null;lastSeconds=null }
    override fun close() = synchronized(lock) {
        if(!closed) { closed=true;latest=null;executor.execute { hand?.close();body?.close();hand=null;body=null };executor.shutdown() }
    }
    private companion object {
        val BODY=listOf(GripBodyJoint.leftShoulder to 11,GripBodyJoint.rightShoulder to 12,GripBodyJoint.leftHip to 23,GripBodyJoint.rightHip to 24,GripBodyJoint.leftWrist to 15,GripBodyJoint.rightWrist to 16)
    }
}
