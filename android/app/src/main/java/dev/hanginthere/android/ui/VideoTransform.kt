package dev.hanginthere.android.ui

import dev.hanginthere.core.Point2D
import kotlin.math.max

/** Same center-crop transform draws pixels, poses, apparatus, and maps manual taps. */
data class VideoTransform(val imageWidth: Double, val imageHeight: Double, val viewWidth: Double, val viewHeight: Double) {
    val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
    val offsetX = (viewWidth - imageWidth * scale) / 2
    val offsetY = (viewHeight - imageHeight * scale) / 2
    fun display(point: Point2D) = Point2D(point.x * scale + offsetX, point.y * scale + offsetY)
    fun image(point: Point2D) = Point2D((point.x - offsetX) / scale, (point.y - offsetY) / scale)
}
