package dev.hanginthere.android

import dev.hanginthere.android.replay.*
import org.junit.Assert.*
import org.junit.Test

class VideoDecoderPolicyTest {
    private val hlg = VideoColorMetadata(standard = 6, range = 2, transfer = 7, hasHdrMetadata = true)

    @Test fun softwareHdrRequiresCompleteCompatibleColorAndProfile() {
        assertTrue(VideoDecoderPolicy.supportsSoftwareHdr(VideoDecoderRequirement("video/dolby-vision", 256), hlg))
        val hevc = VideoDecoderRequirement("video/hevc", 2)
        assertTrue(VideoDecoderPolicy.supportsSoftwareHdr(hevc, hlg))
        assertTrue(VideoDecoderPolicy.supportsSoftwareHdr(hevc, hlg.copy(transfer = 6, range = 1)))
        listOf(hevc.copy(profile = 1), hevc.copy(profile = null), hevc.copy(mimeType = "video/avc"),
            VideoDecoderRequirement("video/dolby-vision", 32)).forEach { assertFalse(VideoDecoderPolicy.supportsSoftwareHdr(it, hlg)) }
        listOf(hlg.copy(transfer = 3), hlg.copy(transfer = null), hlg.copy(standard = 1),
            hlg.copy(range = null), hlg.copy(range = 0)).forEach { assertFalse(VideoDecoderPolicy.supportsSoftwareHdr(hevc, it)) }
        assertFalse(VideoDecoderPolicy.supportsSoftwareHdr(VideoDecoderRequirement("video/dolby-vision", 256), hlg.copy(transfer = 6)))
    }

    @Test fun compatibleDolbyHlgUsesHevcWithoutClaimingSdrConversion() {
        assertEquals(VideoDecoderRequirement("video/hevc", 2), VideoDecoderPolicy.compatibleBaseLayer(
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

    @Test fun main8DefaultCannotDisplaceTheDecoderThatMatchesMain10() {
        val queries = mutableListOf<VideoDecoderRequirement>()
        val selection = VideoDecoderPolicy.selectHdrDecoder(VideoDecoderRequirement("video/dolby-vision", 256), hlg) { requirement ->
            queries += requirement
            // A MIME-only factory would choose the first HEVC decoder here.
            listOf(Triple("main8-default", "video/hevc", 1), Triple("main10-match", "video/hevc", 2))
                .firstOrNull { it.second == requirement.mimeType && it.third == requirement.profile }?.first
        }
        assertEquals(listOf(VideoDecoderRequirement("video/dolby-vision", 256), VideoDecoderRequirement("video/hevc", 2)), queries)
        assertEquals("main10-match", selection.codecName)
        assertEquals(VideoDecoderRequirement("video/hevc", 2), selection.requirement)
    }

    @Test fun matchingDolbyDecoderRetainsItsProfileAndNeedsNoBaseLayerQuery() {
        val source = VideoDecoderRequirement("video/dolby-vision", 256)
        var queries = 0
        val selection = VideoDecoderPolicy.selectHdrDecoder(source, hlg) { requirement ->
            queries++; assertEquals(source, requirement); "dolby-match"
        }
        assertEquals(1, queries); assertEquals(VideoDecoderSelection(source, "dolby-match"), selection)
    }

    @Test fun main8OnlyDeviceFailsSelectionWithoutUnqualifiedMimeFallback() {
        val queries = mutableListOf<VideoDecoderRequirement>()
        assertThrows(IllegalArgumentException::class.java) {
            VideoDecoderPolicy.selectHdrDecoder(VideoDecoderRequirement("video/dolby-vision", 256), hlg) {
                queries += it
                if (it.mimeType == "video/hevc" && it.profile == 1) "main8-only" else null
            }
        }
        assertEquals(listOf(VideoDecoderRequirement("video/dolby-vision", 256), VideoDecoderRequirement("video/hevc", 2)), queries)
    }

    @Test fun unsupportedProfileNeverQueriesAnInventedHevcRequirement() {
        val queries = mutableListOf<VideoDecoderRequirement>()
        val source = VideoDecoderRequirement("video/dolby-vision", 32)
        assertThrows(IllegalArgumentException::class.java) {
            VideoDecoderPolicy.selectHdrDecoder(source, hlg) { queries += it; null }
        }
        assertEquals(listOf(source), queries)
    }
}
