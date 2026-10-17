package dev.hanginthere.core

import kotlinx.serialization.Serializable

/** Unmirrored upright image pixels, origin at the top left; independent of any pose SDK. */
@Serializable
data class Point2D(val x: Double, val y: Double) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()
}

@Serializable
data class ImageSize(val width: Double, val height: Double) {
    val isValid: Boolean get() = width.isFinite() && height.isFinite() && width > 0 && height > 0
}

@Serializable
enum class PoseJoint {
    nose, neck, root, leftEye, rightEye, leftEar, rightEar,
    leftShoulder, rightShoulder, leftElbow, rightElbow, leftWrist, rightWrist,
    leftHip, rightHip, leftKnee, rightKnee, leftAnkle, rightAnkle
}

@Serializable
data class Landmark(val joint: PoseJoint, val position: Point2D, val confidence: Double) {
    // SDK display threshold, not a calibrated probability or form decision.
    fun isVisible(minimumConfidence: Double = 0.3): Boolean =
        position.isFinite && confidence.isFinite() && confidence > 0 &&
            confidence >= minimumConfidence && confidence <= 1
}

@Serializable
data class PoseObservation(val landmarks: List<Landmark>) {
    fun landmark(joint: PoseJoint, minimumConfidence: Double = 0.3): Landmark? =
        landmarks.firstOrNull { it.joint == joint && it.isVisible(minimumConfidence) }
    val visibleLandmarkCount: Int get() = landmarks.count { it.isVisible() }
}

@Serializable
data class PresentationTime(val value: Long, val timescale: Int) {
    val seconds: Double get() = if (timescale > 0) value.toDouble() / timescale else Double.NaN

    /** Subtract source ticks before conversion, preserving exact same-clock boundaries. */
    fun elapsedSeconds(since: PresentationTime): Double {
        if (timescale <= 0 || since.timescale <= 0) return Double.NaN
        if (timescale != since.timescale) return seconds - since.seconds
        val ticks = value - since.value
        if (((value xor since.value) and (value xor ticks)) < 0) return Double.NaN
        return ticks.toDouble() / timescale
    }
}

@Serializable
data class PoseResult(
    val timestamp: PresentationTime,
    val imageSize: ImageSize,
    val people: List<PoseObservation>,
    val backend: String,
    val requestRevision: Int,
    val gripObservation: GripObservation? = null,
)
