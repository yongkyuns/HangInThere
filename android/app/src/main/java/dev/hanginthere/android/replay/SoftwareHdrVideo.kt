package dev.hanginthere.android.replay

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.Keep
import dev.hanginthere.android.camera.CanonicalPixels
import java.io.Closeable
import java.nio.ByteBuffer

/** Bounded, serial MOV/HEVC Main10 decoding with a declared HDR-to-SDR mapping. */
@Keep
internal class SoftwareHdrVideo(context: Context, uri: Uri, private val rotation: Int, transfer: Int, cancelled: () -> Boolean) : Closeable {
    private var handle = 0L
    init {
        System.loadLibrary("hanginthere_hdr")
        require(transfer == 6 || transfer == 7)
        try {
        requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r")) { "Cannot open HDR video" }.use { asset ->
            // Native ownership duplicates the FD, including provider sub-file bounds.
            handle = nativeOpen(asset.parcelFileDescriptor.fd, asset.startOffset, asset.length, if (transfer == 7) 18 else 16, cancelled)
            check(handle != 0L) { "Cannot open HDR decoder" }
        }
        } catch (failure: Throwable) { close(); throw failure }
    }
    fun next(cancelled: () -> Boolean): VideoFrames.Frame? {
        if (handle == 0L || cancelled()) return null
        val frame = nativeNext(handle, cancelled) ?: return null
        if (cancelled()) return null
        val area = frame.width * frame.height
        val offsets = listOf(0, area, area * 5 / 4)
        val planes = offsets.mapIndexed { index, offset ->
            CanonicalPixels.Plane(ByteBuffer.wrap(frame.pixels, offset, if (index == 0) area else area / 4),
                frame.width / if (index == 0) 1 else 2, 1)
        }
        val raw = CanonicalPixels.yuv(frame.width, frame.height, Rect(0, 0, frame.width, frame.height), planes, ENCODING)
        val upright = try { CanonicalPixels.upright(raw, rotation) } catch (failure: Throwable) { raw.recycle(); throw failure }
        if (upright !== raw) raw.recycle()
        if (cancelled()) { upright.recycle(); return null }
        return VideoFrames.Frame(upright, frame.presentationTimeUs)
    }
    override fun close() {
        val owned = handle; handle = 0L
        if (owned != 0L) nativeClose(owned)
    }
    private external fun nativeOpen(fd: Int, offset: Long, length: Long, transfer: Int, cancelled: () -> Boolean): Long
    private external fun nativeNext(handle: Long, cancelled: () -> Boolean): SoftwareHdrFrame?
    private external fun nativeClose(handle: Long)
    companion object {
        val ENCODING = YuvColorEncoding(YuvColorEncoding.Standard.bt709, YuvColorEncoding.Range.limited)
        const val MAPPING = "Hable/100-nit reference/1000-nit input peak/BT.709 limited 8-bit"
    }
}

// JNI constructor signature is part of the adapter ABI; retained by R8 rules.
@Keep
internal data class SoftwareHdrFrame(val width: Int, val height: Int, val presentationTimeUs: Long, val pixels: ByteArray)
