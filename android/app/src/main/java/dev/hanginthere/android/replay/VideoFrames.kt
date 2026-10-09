package dev.hanginthere.android.replay

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
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
    private var colorPolicy: VideoColorPolicy? = null
    var decodedColorEncoding: YuvColorEncoding? = null; private set
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
            colorPolicy = VideoColorPolicy(colorMetadata(format), Build.VERSION.SDK_INT)
            rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            val encodedWidth = format.getInteger(MediaFormat.KEY_WIDTH); val encodedHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            val quarterTurn = ((rotation % 360) + 360) % 360 in listOf(90, 270)
            sourceUprightWidth = if (quarterTurn) encodedHeight else encodedWidth
            sourceUprightHeight = if (quarterTurn) encodedWidth else encodedHeight
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            // Decode encoded sensor pixels; apply track rotation once ourselves.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            if (Build.VERSION.SDK_INT >= 31 && colorPolicy?.requestSdrToneMapping == true) {
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec // Cleanup owns the codec even when configure/start rejects a format.
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (failure: Throwable) {
            close()
            if (colorPolicy?.requestSdrToneMapping == true) throw IllegalArgumentException(VideoColorPolicy.HDR_UNSUPPORTED, failure)
            throw failure
        }
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
                // Verify every buffer's actual negotiated format, including midstream changes.
                val encoding = requireNotNull(colorPolicy).resolveOutput(colorMetadata(codec.getOutputFormat(index)))
                decodedColorEncoding = encoding
                val image = codec.getOutputImage(index) ?: error("Decoder does not expose YUV images")
                val raw = image.use { CanonicalPixels.yuv(it, encoding) }
                val upright = try { CanonicalPixels.upright(raw, rotation) } catch (failure: Throwable) { raw.recycle(); throw failure }
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
    private fun colorMetadata(format: MediaFormat): VideoColorMetadata {
        fun integer(key: String) = if (format.containsKey(key)) format.getInteger(key) else null
        return VideoColorMetadata(integer(MediaFormat.KEY_COLOR_STANDARD), integer(MediaFormat.KEY_COLOR_RANGE),
            integer(MediaFormat.KEY_COLOR_TRANSFER), format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO) ||
                format.containsKey(MediaFormat.KEY_HDR10_PLUS_INFO) || format.getString(MediaFormat.KEY_MIME) == "video/dolby-vision")
    }
}
