package dev.hanginthere.android

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Receipt for actual Android bitmap storage, including row stride. This is
 * not a PNG hash or a cross-platform canonical color comparison. */
internal object NativeEvaluationPixels {
    fun sha256(bitmap: Bitmap): String {
        val buffer = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(buffer)
        return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it) }
    }
}
