package dev.hanginthere.android.vision

import android.graphics.Bitmap
import dev.hanginthere.core.StaticSceneStability
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import kotlin.math.hypot

/** Historical apparatus diagnostic only; production uses reciprocal background matches. */
class ReferenceSceneRegistration : Closeable {
    data class Evidence(val translations: List<StaticSceneStability.PatchTranslation>, val scaleFraction: Double?)
    private var reference: Mat? = null
    private val loaded = OpenCVLoader.initLocal().also { check(it) }
    private val features = ORB.create(800)
    private val matcher = BFMatcher.create(Core.NORM_HAMMING, false)
    private var referencePoints: MatOfKeyPoint? = null
    private var referenceDescriptors: Mat? = null

    private fun gray(bitmap: Bitmap): Mat {
        val rgba = Mat(); val gray = Mat()
        try { Utils.bitmapToMat(bitmap, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); return gray }
        finally { rgba.release() }
    }
    fun freeze(bitmap: Bitmap) {
        reset()
        val image = gray(bitmap)
        val points = MatOfKeyPoint(); val descriptors = Mat(); val mask = Mat()
        try { features.detectAndCompute(image, mask, points, descriptors) } finally { mask.release() }
        reference = image; referencePoints = points; referenceDescriptors = descriptors
    }
    fun observe(bitmap: Bitmap): Evidence {
        val frozen = reference ?: return Evidence(emptyList(), null)
        if (frozen.cols() != bitmap.width || frozen.rows() != bitmap.height) { reset(); return Evidence(emptyList(), null) }
        val current = gray(bitmap)
        try {
            val patchWidth = (bitmap.width * 0.22).toInt().coerceAtLeast(16)
            val patchHeight = (bitmap.height * 0.22).toInt().coerceAtLeast(16)
            val origins = listOf(0 to 0, bitmap.width - patchWidth to 0, 0 to bitmap.height - patchHeight, bitmap.width - patchWidth to bitmap.height - patchHeight)
            val translations = origins.mapNotNull { (x, y) ->
                val first = frozen.submat(Rect(x, y, patchWidth, patchHeight)); val second = current.submat(Rect(x, y, patchWidth, patchHeight))
                val a = Mat(); val b = Mat(); val window = Mat(); val response = DoubleArray(1)
                try {
                    first.convertTo(a, CvType.CV_32F); second.convertTo(b, CvType.CV_32F)
                    val shift = Imgproc.phaseCorrelate(a, b, window, response)
                    if (response[0] >= 0.15 && shift.x.isFinite() && shift.y.isFinite()) StaticSceneStability.PatchTranslation(shift.x, shift.y) else null
                } finally { first.release(); second.release(); a.release(); b.release(); window.release() }
            }
            return Evidence(translations, scale(current))
        } finally { current.release() }
    }
    private fun scale(current: Mat): Double? {
        val originalDescriptors = referenceDescriptors ?: return null
        if (originalDescriptors.empty()) return null
        val points = MatOfKeyPoint(); val descriptors = Mat(); val mask = Mat()
        val matches = ArrayList<MatOfDMatch>()
        try {
            features.detectAndCompute(current, mask, points, descriptors)
            if (descriptors.empty()) return null
            matcher.knnMatch(originalDescriptors, descriptors, matches, 2)
            val good = matches.mapNotNull { it.toArray().takeIf { row -> row.size == 2 && row[0].distance < row[1].distance * 0.7f }?.first() }
            if (good.size < 10) return null
            val original = requireNotNull(referencePoints).toArray(); val now = points.toArray()
            val source = MatOfPoint2f(*good.map { original[it.queryIdx].pt }.toTypedArray())
            val target = MatOfPoint2f(*good.map { now[it.trainIdx].pt }.toTypedArray()); val inliers = Mat()
            val affine = try { Calib3d.estimateAffinePartial2D(source, target, inliers, Calib3d.RANSAC, 3.0, 2000, 0.99, 10) } finally { source.release(); target.release() }
            try {
                if (affine.empty() || Core.countNonZero(inliers) < 8) return null
                val scale = hypot(affine.get(0, 0)[0], affine.get(1, 0)[0])
                return kotlin.math.abs(scale - 1).takeIf { it.isFinite() }
            } finally { affine.release(); inliers.release() }
        } finally { matches.forEach { it.release() }; points.release(); descriptors.release(); mask.release() }
    }
    fun reset() { reference?.release(); reference = null; referencePoints?.release(); referencePoints = null; referenceDescriptors?.release(); referenceDescriptors = null }
    override fun close() { reset(); features.clear(); matcher.clear() }
}
