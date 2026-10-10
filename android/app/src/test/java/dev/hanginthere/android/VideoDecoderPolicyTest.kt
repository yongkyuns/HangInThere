package dev.hanginthere.android

import dev.hanginthere.android.replay.*
import org.junit.Assert.*
import org.junit.Test

class VideoDecoderPolicyTest {
    private val hlg = VideoColorMetadata(standard = 6, range = 2, transfer = 7, hasHdrMetadata = true)

    @Test fun compatibleDolbyHlgUsesHevcWithoutClaimingSdrConversion() {
        assertEquals("video/hevc", VideoDecoderPolicy.compatibleBaseLayer(
            "video/dolby-vision", VideoDecoderPolicy.DOLBY_VISION_PROFILE_8, hlg))
        val colorPolicy = VideoColorPolicy(hlg, 35)
        assertTrue(colorPolicy.requestSdrToneMapping)
        assertThrows(IllegalArgumentException::class.java) { colorPolicy.resolveOutput(hlg) }
        assertThrows(IllegalArgumentException::class.java) { colorPolicy.resolveOutput(VideoColorMetadata()) }
        assertEquals(YuvColorEncoding(YuvColorEncoding.Standard.bt709),
            colorPolicy.resolveOutput(VideoColorMetadata(1, 2, 3)))
    }

    @Test fun unknownAndIncompatibleDolbyProfilesNeverGetHevcFallback() {
        // Includes profile 5 (32), deprecated profile 7 (128), and absent/invalid profiles.
        listOf(null, 0, 16, 32, 128, 512, -1).forEach { profile ->
            assertNull(VideoDecoderPolicy.compatibleBaseLayer("video/dolby-vision", profile, hlg))
        }
    }

    @Test fun missingOrNonHlgColorCannotEstablishBaseLayerCompatibility() {
        listOf(VideoColorMetadata(), hlg.copy(standard = null), hlg.copy(transfer = null),
            hlg.copy(standard = 1), hlg.copy(transfer = 3), hlg.copy(transfer = 6)).forEach { color ->
            assertNull(VideoDecoderPolicy.compatibleBaseLayer("video/dolby-vision", 256, color))
        }
    }

    @Test fun ordinarySdrAndHevcKeepTheirExistingDecoderPath() {
        listOf("video/avc", "video/hevc", "video/av01", "video/unknown").forEach { mime ->
            assertNull(VideoDecoderPolicy.compatibleBaseLayer(mime, 256, hlg))
            assertNull(VideoDecoderPolicy.compatibleBaseLayer(mime, null, VideoColorMetadata()))
        }
    }
}
