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
class PoseEstimator(context: Context) : Closeable {
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
            .setRunningMode(RunningMode.VIDEO).setNumPoses(2)
            .setMinPoseDetectionConfidence(0.5f).setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f).build())
    }
    fun estimate(bitmap: Bitmap, presentationTimeUs: Long): PoseResult {
        val milliseconds = presentationTimeUs / 1000
        require(milliseconds > lastTimestampMs) { "MediaPipe requires strictly increasing millisecond timestamps" }
        lastTimestampMs = milliseconds
        val image = BitmapImageBuilder(bitmap).build()
        val result = try { detector.detectForVideo(image, milliseconds) } finally { image.close() }
        val people = result.landmarks().map { points ->
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
            PoseObservation(landmarks)
        }
        return PoseResult(PresentationTime(presentationTimeUs, 1_000_000), ImageSize(bitmap.width.toDouble(), bitmap.height.toDouble()), people, "MediaPipe Heavy CPU visibility/presence minimum", 1)
    }
    override fun close() = detector.close()
    companion object {
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
