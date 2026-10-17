package dev.hanginthere.android.replay

/** Platform-independent interpretation of MediaFormat color metadata (Android documented values). */
data class VideoColorMetadata(val standard: Int? = null, val range: Int? = null, val transfer: Int? = null,
                              val hasHdrMetadata: Boolean = false) {
    val isHdr get() = transfer == 6 || transfer == 7 || hasHdrMetadata
}

data class YuvColorEncoding(val standard: Standard = Standard.bt601, val range: Range = Range.limited) {
    enum class Standard { bt601, bt709 }
    enum class Range { limited, full }
    val description get() = "${standard.name}/${range.name}/8-bit SDR"
    /** RGB = matrix * [Y, U, V, 1]; limited luma below black is clamped first. */
    fun rgbMatrix(): DoubleArray {
        val kr = if (standard == Standard.bt709) .2126 else .299
        val kb = if (standard == Standard.bt709) .0722 else .114
        val kg = 1 - kr - kb
        val ys = if (range == Range.limited) 255.0 / 219 else 1.0
        val cs = if (range == Range.limited) 255.0 / 224 else 1.0
        val black = if (range == Range.limited) 16 else 0
        val rv = 2 * (1 - kr) * cs; val bu = 2 * (1 - kb) * cs
        val gu = -2 * kb * (1 - kb) / kg * cs; val gv = -2 * kr * (1 - kr) / kg * cs
        return doubleArrayOf(ys, 0.0, rv, -ys * black - rv * 128,
            ys, gu, gv, -ys * black - (gu + gv) * 128,
            ys, bu, 0.0, -ys * black - bu * 128)
    }
}

/** Missing legacy metadata retains the explicit BT.601/limited assumption; known HDR never does. */
class VideoColorPolicy(private val source: VideoColorMetadata, sdk: Int) {
    val requestSdrToneMapping = source.isHdr
    init {
        require(!requestSdrToneMapping || sdk >= 31) { HDR_UNSUPPORTED }
    }
    fun verifyToneMappingRequest(configuredTransfer: Int?) {
        // Android reports 0 when the requested conversion is unsupported. Some
        // implementations omit the key; those still require proof on every output.
        if (requestSdrToneMapping) require(configuredTransfer == null || configuredTransfer == 3) { HDR_UNSUPPORTED }
    }
    fun resolveOutput(output: VideoColorMetadata): YuvColorEncoding {
        if (source.isHdr) {
            // A request is not proof of conversion. Require the actual decoder output transfer.
            require(output.transfer == 3) { HDR_UNSUPPORTED }
            require(output.standard != null && output.standard != 0 && output.range != null && output.range != 0) { HDR_UNSUPPORTED }
        } else require(!output.isHdr) { HDR_UNSUPPORTED }
        val transfer = output.transfer?.takeUnless { it == 0 } ?: source.transfer?.takeUnless { it == 0 }
        require(transfer == null || transfer == 3) { "Unsupported video transfer function. Import an SDR BT.709 or BT.601 copy." }
        val standard = output.standard?.takeUnless { it == 0 } ?: source.standard?.takeUnless { it == 0 }
        val range = output.range?.takeUnless { it == 0 } ?: source.range?.takeUnless { it == 0 }
        return YuvColorEncoding(when (standard) {
            null, 2, 4 -> YuvColorEncoding.Standard.bt601
            1 -> YuvColorEncoding.Standard.bt709
            6 -> throw IllegalArgumentException("BT.2020 video needs color conversion. Import an SDR BT.709 or BT.601 copy.")
            else -> throw IllegalArgumentException("Unsupported video color standard. Import an SDR BT.709 or BT.601 copy.")
        }, when (range) {
            null, 2 -> YuvColorEncoding.Range.limited
            1 -> YuvColorEncoding.Range.full
            else -> throw IllegalArgumentException("Unsupported video color range. Import an SDR BT.709 or BT.601 copy.")
        })
    }
    companion object {
        const val HDR_UNSUPPORTED = "This decoder cannot provide verified SDR pixels for this HDR video. Import a tone-mapped SDR BT.709 or BT.601 copy."
    }
}
