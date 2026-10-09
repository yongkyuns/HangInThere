package dev.hanginthere.core

import kotlin.math.*

data class Affine2D(
    val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0,
    val d: Double = 1.0, val tx: Double = 0.0, val ty: Double = 0.0,
) {
    fun apply(point: Point2D): Point2D = Point2D(a * point.x + c * point.y + tx, b * point.x + d * point.y + ty)
    val isFinite: Boolean get() = listOf(a, b, c, d, tx, ty).all { it.isFinite() }
}

@ConsistentCopyVisibility
data class OrientedGeometry private constructor(
    val sourceSize: ImageSize,
    val outputSize: ImageSize,
    val topLeftTransform: Affine2D,
    val coreImageTransform: Affine2D,
) {
    companion object {
        fun create(sourceSize: ImageSize, preferredTransform: Affine2D): OrientedGeometry? {
            val t = preferredTransform
            if (!sourceSize.isValid || !t.isFinite || abs(t.a * t.d - t.b * t.c) <= 1e-9) return null
            val corners = listOf(Point2D(0.0, 0.0), Point2D(sourceSize.width, 0.0),
                Point2D(0.0, sourceSize.height), Point2D(sourceSize.width, sourceSize.height)).map(t::apply)
            if (!corners.all { it.isFinite }) return null
            val minX = corners.minOf { it.x }; val maxX = corners.maxOf { it.x }
            val minY = corners.minOf { it.y }; val maxY = corners.maxOf { it.y }
            val size = ImageSize(maxX - minX, maxY - minY)
            if (!size.isValid) return null
            return OrientedGeometry(sourceSize, size,
                Affine2D(t.a, t.b, t.c, t.d, t.tx - minX, t.ty - minY),
                Affine2D(t.a, -t.b, -t.c, t.d, t.c * sourceSize.height + t.tx - minX,
                    size.height - t.d * sourceSize.height - t.ty + minY))
        }
    }
}

object PoseGeometry {
    fun visionPoint(point: Point2D, imageSize: ImageSize): Point2D? {
        if (!point.isFinite || !imageSize.isValid || point.x !in 0.0..1.0 || point.y !in 0.0..1.0) return null
        return Point2D(point.x * imageSize.width, (1 - point.y) * imageSize.height)
    }
    /** Interior image-plane angle; straight arm = 180 degrees, not an anatomical 3D angle. */
    fun elbowAngle(shoulder: Point2D, elbow: Point2D, wrist: Point2D): Double? {
        if (!listOf(shoulder, elbow, wrist).all { it.isFinite }) return null
        val ux = shoulder.x - elbow.x; val uy = shoulder.y - elbow.y
        val vx = wrist.x - elbow.x; val vy = wrist.y - elbow.y
        val u = hypot(ux, uy); val v = hypot(vx, vy)
        if (u <= 1e-6 || v <= 1e-6 || !u.isFinite() || !v.isFinite()) return null
        val cosine = (ux / u) * (vx / v) + (uy / u) * (vy / v)
        return acos(min(1.0, max(-1.0, cosine))) * 180 / PI
    }
}

@ConsistentCopyVisibility
data class AspectFit private constructor(val scale: Double, val offset: Point2D) {
    companion object {
        fun create(image: ImageSize, viewport: ImageSize): AspectFit? {
            if (!image.isValid || !viewport.isValid) return null
            val scale = min(viewport.width / image.width, viewport.height / image.height)
            return AspectFit(scale, Point2D((viewport.width - image.width * scale) / 2,
                (viewport.height - image.height * scale) / 2))
        }
    }
    fun displayPoint(point: Point2D): Point2D = Point2D(offset.x + point.x * scale, offset.y + point.y * scale)
    fun imagePoint(point: Point2D): Point2D = Point2D((point.x - offset.x) / scale, (point.y - offset.y) / scale)
}
