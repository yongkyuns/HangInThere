package dev.hanginthere.android.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.media.Image
import java.nio.ByteBuffer
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/** Both camera and decoder use upright, unmirrored pixels. No front-camera anatomical swap. */
object CanonicalPixels {
    const val YUV_CONVERSION = "OpenCV I420 limited-range BT.601; scalar fallback for odd crop origin/dimensions"
    private val nativeConversionReady by lazy { check(OpenCVLoader.initLocal()) { "OpenCV could not load pixel conversion" }; true }
    fun upright(bitmap: Bitmap, rotationDegrees: Int, maximumDimension: Int = 1280): Bitmap {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        require(normalized % 90 == 0)
        val rotated = if (normalized == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(normalized.toFloat()) }, true)
        val ratio = minOf(1.0, maximumDimension.toDouble() / maxOf(rotated.width, rotated.height))
        val scaled = if (ratio == 1.0) rotated else Bitmap.createScaledBitmap(rotated, (rotated.width * ratio).roundToInt(), (rotated.height * ratio).roundToInt(), true)
        if (rotated !== bitmap && rotated !== scaled) rotated.recycle()
        return scaled
    }
    fun yuv(image: Image): Bitmap = yuv(image.width, image.height, image.cropRect, image.planes.map { Plane(it.buffer, it.rowStride, it.pixelStride) })
    data class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)
    fun yuv(width: Int, height: Int, crop: Rect, planes: List<Plane>): Bitmap {
        require(planes.size == 3 && crop.width() > 0 && crop.height() > 0)
        require(crop.left >= 0 && crop.top >= 0 && crop.right <= width && crop.bottom <= height)
        val buffers = planes.map { it.buffer.duplicate() }
        val starts = buffers.map { it.position() }
        // I420 chroma refers to even-aligned 2x2 blocks. Preserve exact sampling
        // semantics for odd origins/dimensions with the scalar fallback below.
        if (crop.left % 2 == 0 && crop.top % 2 == 0 && crop.width() % 2 == 0 && crop.height() % 2 == 0) {
            check(nativeConversionReady)
            val packed = ByteArray(crop.width() * crop.height() * 3 / 2)
            var destination = 0
            planes.forEachIndexed { index, plane ->
                val divisor = if (index == 0) 1 else 2
                val columns = crop.width() / divisor
                val rows = crop.height() / divisor
                val left = crop.left / divisor; val top = crop.top / divisor
                val span = (columns - 1) * plane.pixelStride + 1
                val scratch = if (plane.pixelStride == 1) null else ByteArray(span)
                for (row in 0 until rows) {
                    val source = buffers[index]
                    source.position(starts[index] + (top + row) * plane.rowStride + left * plane.pixelStride)
                    if (scratch == null) source.get(packed, destination, columns)
                    else {
                        source.get(scratch, 0, span)
                        for (column in 0 until columns) packed[destination + column] = scratch[column * plane.pixelStride]
                    }
                    destination += columns
                }
            }
            val i420 = Mat(crop.height() * 3 / 2, crop.width(), CvType.CV_8UC1)
            val rgba = Mat()
            try {
                i420.put(0, 0, packed)
                Imgproc.cvtColor(i420, rgba, Imgproc.COLOR_YUV2RGBA_I420)
                val bitmap = Bitmap.createBitmap(crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
                try { Utils.matToBitmap(rgba, bitmap); return bitmap }
                catch (error: Throwable) { bitmap.recycle(); throw error }
            } finally { rgba.release(); i420.release() }
        }
        fun sample(plane: Int, x: Int, y: Int): Int = buffers[plane].get(starts[plane] + y * planes[plane].rowStride + x * planes[plane].pixelStride).toInt() and 255
        val colors = IntArray(crop.width() * crop.height())
        var destination = 0
        for (y in crop.top until crop.bottom) for (x in crop.left until crop.right) {
            val luminance = (sample(0, x, y) - 16).coerceAtLeast(0)
            val u = sample(1, x / 2, y / 2) - 128; val v = sample(2, x / 2, y / 2) - 128
            val r = ((298 * luminance + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * luminance - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * luminance + 516 * u + 128) shr 8).coerceIn(0, 255)
            colors[destination++] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(colors, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
    }
}
