package dev.hanginthere.android.vision

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import dev.hanginthere.core.*
import dev.hanginthere.android.BuildConfig
import java.io.Closeable
import java.security.MessageDigest

/** Own on one inference executor; VIDEO tracking timestamps are monotonic source milliseconds. */
class PoseEstimator private constructor(context: Context, private val mode: RunningMode) : Closeable {
    constructor(context: Context) : this(context, RunningMode.VIDEO)
    private val detector: PoseLandmarker
    private var lastTimestampMs = -1L
    init {
        val hash = context.assets.open(MODEL).use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val block = ByteArray(65536)
            while (true) { val count = stream.read(block); if (count < 0) break; digest.update(block, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        check(hash == MODEL_SHA256) { "Pose model checksum mismatch" }
        detector = PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
            .setRunningMode(mode).setNumPoses(2)
            .setMinPoseDetectionConfidence(0.5f).setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f).build())
    }
    fun estimate(bitmap: Bitmap, presentationTimeUs: Long): PoseResult {
        val milliseconds = presentationTimeUs / 1000
        require(milliseconds > lastTimestampMs) { "MediaPipe requires strictly increasing millisecond timestamps" }
        lastTimestampMs = milliseconds
        // BitmapImageContainer.close() recycles its input. The caller retains the
        // canonical pixels for contours, registration and display after inference.
        val sdkBitmap = checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) { "Unable to allocate pose input" }
        val image = try { BitmapImageBuilder(sdkBitmap).build() }
        catch (error: Throwable) { sdkBitmap.recycle(); throw error }
        val result = try {
            if (mode == RunningMode.IMAGE) detector.detect(image) else detector.detectForVideo(image, milliseconds)
        } finally { image.close() }
        val world = PoseWorldLandmarks.map(result.landmarks().size, result.worldLandmarks(), JOINTS)
        val people = result.landmarks().mapIndexed { personIndex, points ->
            val landmarks = JOINTS.map { (joint, index) ->
                val point = points[index]
                // Raw backend visibility/presence scores, not Apple Vision probabilities.
                Landmark(joint, Point2D(point.x().toDouble() * bitmap.width, point.y().toDouble() * bitmap.height),
                    minOf(point.visibility().orElse(0f), point.presence().orElse(0f)).toDouble())
            }.toMutableList()
            fun middle(joint: PoseJoint, left: PoseJoint, right: PoseJoint) {
                val a = landmarks.first { it.joint == left }; val b = landmarks.first { it.joint == right }
                landmarks += Landmark(joint, Point2D((a.position.x + b.position.x) / 2, (a.position.y + b.position.y) / 2), minOf(a.confidence, b.confidence))
            }
            middle(PoseJoint.neck, PoseJoint.leftShoulder, PoseJoint.rightShoulder)
            middle(PoseJoint.root, PoseJoint.leftHip, PoseJoint.rightHip)
            PoseObservation(landmarks, world?.get(personIndex))
        }
        val backend = if (mode == RunningMode.IMAGE) "MediaPipe Heavy CPU IMAGE visibility/presence minimum"
            else "MediaPipe Heavy CPU visibility/presence minimum"
        return PoseResult(PresentationTime(presentationTimeUs, 1_000_000), ImageSize(bitmap.width.toDouble(), bitmap.height.toDouble()), people, backend, 1,
            estimatedWorldConvention = EstimatedBodyGeometry.convention.takeIf { world != null && people.isNotEmpty() })
    }
    override fun close() = detector.close()
    companion object {
        /** Controlled comparison only; production keeps VIDEO tracking until native accuracy/latency are qualified. */
        internal fun independentFramesForDiagnostic(context: Context) = PoseEstimator(context, RunningMode.IMAGE)
        const val ARTIFACT = "com.google.mediapipe:tasks-vision:${BuildConfig.POSE_RUNTIME_VERSION}"
        const val ARTIFACT_SHA256 = BuildConfig.POSE_RUNTIME_AAR_SHA256
        const val MODEL = "pose_landmarker_heavy.task"
        const val MODEL_SHA256 = "64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b"
        private val JOINTS = listOf(PoseJoint.nose to 0, PoseJoint.leftEye to 2, PoseJoint.rightEye to 5,
            PoseJoint.leftEar to 7, PoseJoint.rightEar to 8, PoseJoint.leftShoulder to 11, PoseJoint.rightShoulder to 12,
            PoseJoint.leftElbow to 13, PoseJoint.rightElbow to 14, PoseJoint.leftWrist to 15, PoseJoint.rightWrist to 16,
            PoseJoint.leftHip to 23, PoseJoint.rightHip to 24, PoseJoint.leftKnee to 25, PoseJoint.rightKnee to 26,
            PoseJoint.leftAnkle to 27, PoseJoint.rightAnkle to 28)
    }
}
