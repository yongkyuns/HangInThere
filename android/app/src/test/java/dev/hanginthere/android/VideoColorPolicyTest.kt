package dev.hanginthere.android

import dev.hanginthere.android.replay.*
import org.junit.Assert.*
import org.junit.Test

class VideoColorPolicyTest {
    @Test fun legacyMissingMetadataUsesDeclaredLimited601Assumption() {
        assertEquals(YuvColorEncoding(), VideoColorPolicy(VideoColorMetadata(), 29).resolveOutput(VideoColorMetadata()))
    }
    @Test fun outputColorOverridesSourceAndMissingFieldsInheritSource() {
        val policy = VideoColorPolicy(VideoColorMetadata(1, 1, 3), 29)
        assertEquals(YuvColorEncoding(YuvColorEncoding.Standard.bt709, YuvColorEncoding.Range.full), policy.resolveOutput(VideoColorMetadata()))
        assertEquals(YuvColorEncoding(), policy.resolveOutput(VideoColorMetadata(4, 2, 3)))
    }
    @Test fun hdrBeforeApi31IsActionableFailure() {
        listOf(6, 7).forEach { transfer ->
            val error = assertThrows(IllegalArgumentException::class.java) { VideoColorPolicy(VideoColorMetadata(6, 2, transfer), 29) }
            assertTrue(error.message!!.contains("tone-mapped SDR"))
        }
    }
    @Test fun hdrRequestNeedsActualCompleteSdrOutputMetadata() {
        val policy = VideoColorPolicy(VideoColorMetadata(6, 2, 7), 35)
        assertTrue(policy.requestSdrToneMapping)
        listOf(VideoColorMetadata(), VideoColorMetadata(6, 2, 7), VideoColorMetadata(1, 2, null), VideoColorMetadata(1, null, 3)).forEach { output ->
            assertThrows(IllegalArgumentException::class.java) { policy.resolveOutput(output) }
        }
        assertEquals(YuvColorEncoding(YuvColorEncoding.Standard.bt709), policy.resolveOutput(VideoColorMetadata(1, 2, 3)))
    }
    @Test fun hdrMetadataWithoutTransferStillRequiresVerifiedToneMapping() {
        val policy = VideoColorPolicy(VideoColorMetadata(hasHdrMetadata = true), 35)
        assertTrue(policy.requestSdrToneMapping)
        assertThrows(IllegalArgumentException::class.java) { policy.resolveOutput(VideoColorMetadata()) }
    }
    @Test fun knownUnsupportedGamutOrTransferNeverGets601Fallback() {
        listOf(VideoColorMetadata(6, 2, 3), VideoColorMetadata(1, 2, 2), VideoColorMetadata(99, 2, 3), VideoColorMetadata(1, 99, 3)).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { VideoColorPolicy(source, 35).resolveOutput(VideoColorMetadata()) }
        }
    }
    @Test fun unexpectedHdrOutputIsRejectedEvenForSdrSource() {
        assertThrows(IllegalArgumentException::class.java) { VideoColorPolicy(VideoColorMetadata(1, 2, 3), 35).resolveOutput(VideoColorMetadata(6, 2, 7)) }
    }
}
