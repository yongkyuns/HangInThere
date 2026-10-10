package dev.hanginthere.android.replay

import android.media.MediaFormat
import androidx.annotation.RequiresApi

/** Adapt only codec identity; source CSD, geometry, timestamps and HDR metadata stay intact. */
internal object VideoDecoderFormat {
    @RequiresApi(29)
    fun applyRequirement(format: MediaFormat, requirement: VideoDecoderRequirement) {
        val compatibleBaseLayer = format.getString(MediaFormat.KEY_MIME) == VideoDecoderPolicy.DOLBY_VISION_MIME &&
            requirement.mimeType == VideoDecoderPolicy.HEVC_MIME
        format.setString(MediaFormat.KEY_MIME, requirement.mimeType)
        requirement.profile?.let { format.setInteger(MediaFormat.KEY_PROFILE, it) }
        if (compatibleBaseLayer) format.removeKey(MediaFormat.KEY_LEVEL) // Dolby levels are not HEVC levels.
    }
}
