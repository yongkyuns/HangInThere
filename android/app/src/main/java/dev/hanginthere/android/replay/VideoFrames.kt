package dev.hanginthere.android.replay

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.hanginthere.android.camera.CanonicalPixels
import java.io.Closeable

/** Streams every decodable source PTS exactly once; never approximates frames via seek sampling. */
class VideoFrames(context: Context, uri: Uri) : Closeable {
    data class Frame(val bitmap: Bitmap, val presentationTimeUs: Long) : Closeable { override fun close() = bitmap.recycle() }
    private val extractor = MediaExtractor()
    private var decoder: MediaCodec? = null
    private var inputDone = false
    private var outputDone = false
    private var lastPTS = -1L
    private val rotation: Int
    val durationUs: Long
    val sourceUprightWidth: Int
    val sourceUprightHeight: Int
    init {
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("No video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val encodedWidth = format.getInteger(MediaFormat.KEY_WIDTH); val encodedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            val quarterTurn = ((rotation % 360) + 360) % 360 in listOf(90, 270)
            sourceUprightWidth = if (quarterTurn) encodedHeight else encodedWidth
            sourceUprightHeight = if (quarterTurn) encodedWidth else encodedHeight
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            // Decode encoded sensor pixels; apply track rotation once ourselves.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME))).also { it.configure(format, null, null, 0); it.start() }
        } catch (failure: Throwable) { close(); throw failure }
    }
    fun next(cancelled: () -> Boolean = { false }): Frame? {
        val codec = decoder ?: return null
        val info = MediaCodec.BufferInfo()
        while (!outputDone && !cancelled()) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = requireNotNull(codec.getInputBuffer(index)); input.clear()
                    val count = extractor.readSampleData(input, 0)
                    if (count < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { codec.queueInputBuffer(index, 0, count, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index < 0) continue
            try {
                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size == 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) continue
                require(info.presentationTimeUs >= 0 && info.presentationTimeUs > lastPTS) { "Invalid/repeated video source PTS" }
                lastPTS = info.presentationTimeUs
                val image = codec.getOutputImage(index) ?: error("Decoder does not expose YUV images")
                val raw = image.use { CanonicalPixels.yuv(it) }
                val upright = try { CanonicalPixels.upright(raw, rotation) } finally { /* raw remains alive if canonical already correct */ }
                if (upright !== raw) raw.recycle()
                return Frame(upright, info.presentationTimeUs)
            } finally { codec.releaseOutputBuffer(index, false) }
        }
        return null
    }
    override fun close() {
        decoder?.let { runCatching { it.stop() }; it.release() }; decoder = null
        extractor.release()
    }
}
