package dev.hanginthere.android.replay

import kotlinx.serialization.Serializable

data class VideoDecoderRequirement(val mimeType: String, val profile: Int?)
data class VideoDecoderSelection(val requirement: VideoDecoderRequirement, val codecName: String)

/** Retained locally (or in encrypted evaluation reports), including constructor failures. */
@Serializable
data class VideoDecoderDiagnostics(
    val stage: String = "source",
    val sourceMimeType: String? = null,
    val requiredMimeType: String? = null,
    val requiredProfile: Int? = null,
    val codecName: String? = null,
    val requestedTransfer: Int? = null,
    val configuredTransfer: Int? = null,
)

class HdrVideoImportException(val diagnostics: VideoDecoderDiagnostics, cause: Throwable) :
    IllegalArgumentException(VideoColorPolicy.HDR_UNSUPPORTED, cause)

/** Decoder compatibility is separate from the proof that decoded pixels are SDR. */
object VideoDecoderPolicy {
    const val DOLBY_VISION_MIME = "video/dolby-vision"
    const val HEVC_MIME = "video/hevc"
    // MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt (profile 8).
    const val DOLBY_VISION_PROFILE_8 = 256
    // MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10.
    const val HEVC_PROFILE_MAIN10 = 2

    /**
     * Only the documented single-layer profile 8 HLG/BT.2020 combination may
     * use its HEVC base layer. Unknown profiles and non-compatible Dolby color
     * representations must never be guessed from the container MIME alone.
     */
    fun compatibleBaseLayer(mime: String, profile: Int?, color: VideoColorMetadata): VideoDecoderRequirement? =
        if (mime == DOLBY_VISION_MIME && profile == DOLBY_VISION_PROFILE_8 &&
            color.standard == 6 && color.transfer == 7) VideoDecoderRequirement(HEVC_MIME, HEVC_PROFILE_MAIN10) else null

    /** Lookup must check the complete native format; never fall back to a MIME-only decoder. */
    fun selectHdrDecoder(source: VideoDecoderRequirement, color: VideoColorMetadata,
                         findDecoder: (VideoDecoderRequirement) -> String?): VideoDecoderSelection {
        findDecoder(source)?.let { return VideoDecoderSelection(source, it) }
        compatibleBaseLayer(source.mimeType, source.profile, color)?.let { baseLayer ->
            findDecoder(baseLayer)?.let { return VideoDecoderSelection(baseLayer, it) }
        }
        throw IllegalArgumentException("No decoder supports the HDR format or its compatible Main10 base layer")
    }
}
