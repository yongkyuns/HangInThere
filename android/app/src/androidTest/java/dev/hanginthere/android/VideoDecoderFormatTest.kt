package dev.hanginthere.android

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hanginthere.android.replay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class VideoDecoderFormatTest {
    @Test fun compatibleMain10FormatPreservesSourceColorCsdAndGeometry() {
        val csd = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))
        val hdr = ByteBuffer.wrap(byteArrayOf(5, 6, 7))
        val format = MediaFormat.createVideoFormat("video/dolby-vision", 1920, 1080).apply {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelFhd30)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            if (Build.VERSION.SDK_INT >= 31) setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setLong(MediaFormat.KEY_DURATION, 23_456_789)
            setInteger(MediaFormat.KEY_ROTATION, 0)
            setByteBuffer("csd-0", csd); setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdr)
        }
        val requirement = requireNotNull(VideoDecoderPolicy.compatibleBaseLayer("video/dolby-vision", 256, VideoColorMetadata(6, 2, 7)))
        VideoDecoderFormat.applyRequirement(format, requirement)
        assertEquals("video/hevc", format.getString(MediaFormat.KEY_MIME))
        assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, format.getInteger(MediaFormat.KEY_PROFILE))
        assertFalse("Dolby level must not become a HEVC level", format.containsKey(MediaFormat.KEY_LEVEL))
        assertSame(csd, format.getByteBuffer("csd-0")); assertSame(hdr, format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO))
        assertEquals(1920, format.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(1080, format.getInteger(MediaFormat.KEY_HEIGHT))
        assertEquals(23_456_789L, format.getLong(MediaFormat.KEY_DURATION)); assertEquals(0, format.getInteger(MediaFormat.KEY_ROTATION))
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, format.getInteger(MediaFormat.KEY_COLOR_STANDARD))
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, format.getInteger(MediaFormat.KEY_COLOR_RANGE))
        assertEquals(MediaFormat.COLOR_TRANSFER_HLG, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER))
        if (Build.VERSION.SDK_INT >= 31) assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, format.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST))
    }

    @Test fun directDolbyRequirementRetainsItsNativeProfileAndLevel() {
        val format = MediaFormat.createVideoFormat("video/dolby-vision", 1920, 1080).apply {
            setInteger(MediaFormat.KEY_PROFILE, 256); setInteger(MediaFormat.KEY_LEVEL, 4)
        }
        VideoDecoderFormat.applyRequirement(format, VideoDecoderRequirement("video/dolby-vision", 256))
        assertEquals(256, format.getInteger(MediaFormat.KEY_PROFILE)); assertEquals(4, format.getInteger(MediaFormat.KEY_LEVEL))
    }
}
