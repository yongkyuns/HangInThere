package dev.hanginthere.core

import kotlin.math.*

/** Absolute camera tilt; native capture supplies gravity in device coordinates.
 * Camera height cannot be measured from gravity: chest/shoulder placement remains guidance. */
object WorkoutCameraPlacement {
    const val maximumTiltDegrees = 20.0
    fun tiltDegrees(x: Double, y: Double, z: Double): Double? {
        if (!listOf(x,y,z).all { it.isFinite() }) return null
        val magnitude = sqrt(x*x+y*y+z*z)
        if (!magnitude.isFinite() || magnitude !in .8..1.2) return null
        return asin((abs(z)/magnitude).coerceIn(0.0,1.0))*180/PI
    }
    fun isUsable(tiltDegrees: Double?): Boolean =
        tiltDegrees != null && tiltDegrees.isFinite() && tiltDegrees in 0.0..maximumTiltDegrees
    fun attitudeTiltDegrees(quaternion: PhoneOrientationStability.Quaternion): Double =
        asin(abs(1-2*(quaternion.x*quaternion.x+quaternion.y*quaternion.y)).coerceIn(0.0,1.0))*180/PI
}

class WorkoutCameraPlacementBridge {
    fun tiltDegrees(x: Double, y: Double, z: Double): Double? = WorkoutCameraPlacement.tiltDegrees(x,y,z)
    fun isUsable(tiltDegrees: Double): Boolean = WorkoutCameraPlacement.isUsable(tiltDegrees)
}
