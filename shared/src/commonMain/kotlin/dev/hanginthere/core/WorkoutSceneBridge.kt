package dev.hanginthere.core

/** Thin native boundary for the same scene gate used by [WorkoutRuntime].
 * Callers retain the real calibration pixels and own source/setup epochs.
 * Each observation is submitted once; cached measurements never receive a new
 * timestamp. A failed registration supplies no translations and no scale.
 */
class WorkoutSceneBridge {
    private val scene = StaticSceneStability()

    val state: String get() = scene.state.name
    val movementKind: String? get() = scene.movementKind?.name

    fun reset() = scene.reset()

    fun calibrate(width: Double, height: Double): Boolean =
        scene.calibrate(ImageSize(width, height))

    fun observe(seconds: Double, translations: DoubleArray, scaleFraction: Double): String {
        // Native registration has up to six peripheral patches. Malformed transport
        // must revoke availability, rather than inventing zero camera motion.
        val patches = if (translations.size <= StaticSceneStability.maximumRegistrationPatches * 2 && translations.size % 2 == 0) {
            translations.asList().chunked(2).map { StaticSceneStability.PatchTranslation(it[0], it[1]) }
        } else emptyList()
        scene.observe(patches, scaleFraction.takeIf { it.isFinite() && it >= 0 }, seconds)
        return state
    }

    fun isReady(seconds: Double): Boolean = scene.isReady(seconds)
}
