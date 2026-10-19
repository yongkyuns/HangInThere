package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Source-pixel region geometry, independent of the pose SDK. Grip inference
 * reuses this type; the full-person [select] recipe remains experimental.
 * A region supplies no apparatus or athlete identity. */
@Serializable
data class PersonInferenceRegion(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun isValidFor(source: ImageSize) = integerImage(source) && left >= 0 && top >= 0 && width > 0 && height > 0 &&
        left.toLong() + width <= source.width.toLong() && top.toLong() + height <= source.height.toLong()

    /** Visible intersection of an observed native rectangle with its source.
     * Detectors can report a person extending past an image edge. This converts
     * that rectangle to real source pixels; it never clamps predicted joints or
     * changes their coordinate frame. Keep the original observation separately.
     * Malformed or wholly offscreen rectangles have no usable intersection. */
    fun clippedTo(source: ImageSize): PersonInferenceRegion? {
        if (!integerImage(source) || width <= 0 || height <= 0) return null
        if (isValidFor(source)) return this
        val visibleLeft = maxOf(0L, left.toLong())
        val visibleTop = maxOf(0L, top.toLong())
        val visibleRight = minOf(source.width.toLong(), left.toLong() + width)
        val visibleBottom = minOf(source.height.toLong(), top.toLong() + height)
        if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return null
        return PersonInferenceRegion(visibleLeft.toInt(), visibleTop.toInt(),
            (visibleRight - visibleLeft).toInt(), (visibleBottom - visibleTop).toInt())
    }

    /** Body-only predictions; auxiliary grip inference runs after restoration.
     * No scaling/reflection/clamping. Predictions outside the actual inference
     * input are unavailable, even if translation would put them in the source. */
    fun restore(prediction: PoseResult, source: ImageSize): PoseResult {
        require(isValidFor(source) && prediction.imageSize == ImageSize(width.toDouble(), height.toDouble()) &&
            prediction.gripObservation == null) { "Restore body-only crop predictions before grip inference" }
        // Pixel translation does not restore a crop model's camera calibration
        // or depth frame. Preserve raw crop output separately for diagnostics.
        return prediction.copy(imageSize = source, estimatedWorldConvention = null, people = prediction.people.map { person ->
            PoseObservation(person.landmarks.filter { point ->
                point.position.isFinite && point.position.x in 0.0..width.toDouble() && point.position.y in 0.0..height.toDouble()
            }.map { point -> point.copy(position = Point2D(point.position.x + left, point.position.y + top)) })
        })
    }

    companion object {
        const val recipeVersion = 1
        const val paddingFraction = .15
        const val minimumPaddingPixels = 16.0
        fun select(pose: PoseResult): PersonInferenceRegion? {
            if (!integerImage(pose.imageSize)) return null
            val person = pose.people.singleOrNull() ?: return null
            if (person.landmarks.map { it.joint }.distinct().size != person.landmarks.size) return null
            val points = person.landmarks.filter {
                it.isVisible() && it.position.x in 0.0..pose.imageSize.width && it.position.y in 0.0..pose.imageSize.height
            }
            val anchors = setOf(PoseJoint.leftShoulder, PoseJoint.rightShoulder, PoseJoint.leftHip, PoseJoint.rightHip)
            if (points.size < 8 || !points.map { it.joint }.containsAll(anchors)) return null
            val minX = points.minOf { it.position.x }; val maxX = points.maxOf { it.position.x }
            val minY = points.minOf { it.position.y }; val maxY = points.maxOf { it.position.y }
            if (maxX - minX < 16 || maxY - minY < 16) return null
            val padX = max(minimumPaddingPixels, (maxX - minX) * paddingFraction)
            val padY = max(minimumPaddingPixels, (maxY - minY) * paddingFraction)
            val left = floor(max(0.0, minX - padX)).toInt()
            val top = floor(max(0.0, minY - padY)).toInt()
            val right = ceil((maxX + padX).coerceAtMost(pose.imageSize.width)).toInt()
            val bottom = ceil((maxY + padY).coerceAtMost(pose.imageSize.height)).toInt()
            return PersonInferenceRegion(left, top, right - left, bottom - top).takeIf { it.isValidFor(pose.imageSize) }
        }

        private fun integerImage(size: ImageSize) = size.isValid && size.width <= Int.MAX_VALUE && size.height <= Int.MAX_VALUE &&
            size.width == floor(size.width) && size.height == floor(size.height)
    }
}
