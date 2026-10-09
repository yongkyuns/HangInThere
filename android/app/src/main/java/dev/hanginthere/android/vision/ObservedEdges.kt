package dev.hanginthere.android.vision

import android.graphics.Bitmap
import dev.hanginthere.core.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor

/** Image-only silhouette contours. Geometry ranking never claims semantic bar confidence. */
class ObservedEdges {
    init { check(OpenCVLoader.initLocal()) { "OpenCV could not load" } }
    fun detect(bitmap: Bitmap, region: BarRegion): List<BarLineCandidate> {
        val size = ImageSize(bitmap.width.toDouble(), bitmap.height.toDouble())
        require(region.isValid(size))
        val left = floor(region.minX).toInt(); val top = floor(region.minY).toInt()
        val width = ceil(region.maxX).toInt() - left; val height = ceil(region.maxY).toInt() - top
        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
        val pixels = Mat(); val gray = Mat(); val edges = Mat(); val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Utils.bitmapToMat(crop, pixels); Imgproc.cvtColor(pixels, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.Canny(gray, edges, 40.0, 120.0)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
            require(contours.sumOf { it.rows() } <= 80_000) { "Contour complexity limit" }
            val observed = contours.map { contour -> contour.toArray().map { Point2D(it.x + left, it.y + top) } }
            return BarLineFitter.candidates(observed, BarRegion(Point2D(left.toDouble(), top.toDouble()), Point2D((left + width).toDouble(), (top + height).toDouble())), size)
        } finally {
            contours.forEach { it.release() }; hierarchy.release(); edges.release(); gray.release(); pixels.release()
            if (crop !== bitmap) crop.recycle()
        }
    }
}
