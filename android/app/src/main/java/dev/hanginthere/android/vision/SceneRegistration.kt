package dev.hanginthere.android.vision

import android.graphics.Bitmap
import dev.hanginthere.core.StaticSceneStability
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import kotlin.math.roundToInt

internal object BackgroundCameraNative {
    init { check(OpenCVLoader.initLocal()); System.loadLibrary("hanginthere_camera") }
    external fun measure(reference: Long, current: Long): DoubleArray?
}

/** Same reciprocal peripheral pixel sampler and geometry as iOS. Unknown stays unknown. */
class SceneRegistration : Closeable {
    data class Evidence(val translations: List<StaticSceneStability.PatchTranslation>, val scaleFraction: Double?)
    private var reference: Mat? = null
    private var width = 0; private var height = 0
    init { check(OpenCVLoader.initLocal()) }
    private fun gray(bitmap: Bitmap): Mat {
        val rgba=Mat(); val small=Mat(); val gray=Mat()
        try {
            Utils.bitmapToMat(bitmap,rgba)
            val scale=minOf(1.0,640.0/maxOf(bitmap.width,bitmap.height))
            Imgproc.resize(rgba,small,Size((bitmap.width*scale).roundToInt().toDouble(),(bitmap.height*scale).roundToInt().toDouble()),0.0,0.0,Imgproc.INTER_AREA)
            Imgproc.cvtColor(small,gray,Imgproc.COLOR_RGBA2GRAY)
            return gray
        } catch (error: Throwable) { gray.release(); throw error }
        finally { rgba.release(); small.release() }
    }
    fun freeze(bitmap: Bitmap) { reset();reference=gray(bitmap);width=bitmap.width;height=bitmap.height }
    fun observe(bitmap: Bitmap): Evidence {
        val frozen=reference ?: return Evidence(emptyList(),null)
        if(bitmap.width!=width || bitmap.height!=height) { reset();return Evidence(emptyList(),null) }
        val current=gray(bitmap)
        try {
            val values=BackgroundCameraNative.measure(frozen.nativeObjAddr,current.nativeObjAddr) ?: return Evidence(emptyList(),null)
            check(values.size in 7..13 && values.size%2==1 && values.all { it.isFinite() } && values[0]>=0)
            val sx=width.toDouble()/frozen.cols();val sy=height.toDouble()/frozen.rows()
            return Evidence((1 until values.size step 2).map { StaticSceneStability.PatchTranslation(values[it]*sx,values[it+1]*sy) },values[0])
        } finally { current.release() }
    }
    fun reset() { reference?.release();reference=null;width=0;height=0 }
    override fun close() = reset()
}
