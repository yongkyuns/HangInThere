package dev.hanginthere.android.replay

/** Decoder compatibility is separate from the proof that decoded pixels are SDR. */
object VideoDecoderPolicy {
    const val DOLBY_VISION_MIME = "video/dolby-vision"
    const val HEVC_MIME = "video/hevc"
    // MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt (profile 8).
    const val DOLBY_VISION_PROFILE_8 = 256

    /**
     * Only the documented single-layer profile 8 HLG/BT.2020 combination may
     * use its HEVC base layer. Unknown profiles and non-compatible Dolby color
     * representations must never be guessed from the container MIME alone.
     */
    fun compatibleBaseLayer(mime: String, profile: Int?, color: VideoColorMetadata): String? =
        if (mime == DOLBY_VISION_MIME && profile == DOLBY_VISION_PROFILE_8 &&
            color.standard == 6 && color.transfer == 7) HEVC_MIME else null
}
