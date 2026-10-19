package dev.hanginthere.core

import kotlin.math.max
import kotlin.math.min

/** Pixel search context only. A hand can hide most of a nearby rail inside the
 * original image-relative window. Preserve its body exclusion and vertical
 * extent, but allow a measured upper arm's length on the outward side. This
 * neither extends observed contours nor qualifies a support or repetition. */
internal object DipRailSearchGeometry {
    fun region(shoulder: Point2D, grip: Point2D, size: ImageSize, upperArmPixels: Double?): BarRegion? {
        if (!size.isValid || !shoulder.isFinite || !grip.isFinite ||
            listOf(shoulder, grip).any { it.x !in 0.0..size.width || it.y !in 0.0..size.height } ||
            upperArmPixels?.let { !it.isFinite() || it <= 0.0 } == true) return null
        val original = AutomaticDipRails.contactRegion(shoulder, grip, size) ?: return null
        // Missing elbows must not invent an arm length or shrink the old crop.
        val radius = upperArmPixels ?: return original
        val separation = min(size.width, size.height) * .04
        return when {
            grip.x - shoulder.x > separation -> original.copy(maxX = min(size.width, max(original.maxX, grip.x + radius)))
            shoulder.x - grip.x > separation -> original.copy(minX = max(0.0, min(original.minX, grip.x - radius)))
            // With no clear lateral body exclusion, retain the original window.
            else -> original
        }.takeIf { it.isValid(size) }
    }
}

/** Narrow Apple boundary to the same measured search geometry used on Android.
 * Inputs keep their original point roles: a body wrist or an independent hand
 * feature is never replaced here. A failed validation returns no crop, not a
 * default arm length. No apparatus, contact, identity or count is qualified. */
class DipRailSearchGeometryBridge {
    val policyVersion: Int get() = AutomaticDipRails.contactSearchPolicyVersion
    fun region(shoulderX: Double, shoulderY: Double, gripX: Double, gripY: Double,
               imageWidth: Double, imageHeight: Double, upperArmPixels: Double): BarRegion? =
        DipRailSearchGeometry.region(Point2D(shoulderX, shoulderY), Point2D(gripX, gripY),
            ImageSize(imageWidth, imageHeight), upperArmPixels)
}
