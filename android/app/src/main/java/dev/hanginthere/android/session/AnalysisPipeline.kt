package dev.hanginthere.android.session

import android.content.Context
import android.graphics.Bitmap
import dev.hanginthere.android.camera.PhoneAttitude
import dev.hanginthere.android.vision.*
import dev.hanginthere.core.*
import java.io.Closeable
import kotlinx.serialization.Serializable

/** One serial owner connects platform observations to the platform-independent workout policy. */
class AnalysisPipeline(private val context: Context, selection: WorkoutExerciseSelection, side: ArmMeasurement.Side) : Closeable {
    /** The exact bounded detector output consumed by policy, without retaining frame pixels. */
    @Serializable
    data class ApparatusObservation(
        val search: AutomaticApparatusReference.Search,
        val candidates: List<List<BarLineCandidate>>,
        val errors: List<String?>,
    )
    data class Result(val pose: PoseResult, val snapshot: WorkoutRuntime.Snapshot, val progress: Double, val cue: String,
                      val analysisMillis: Double, val apparatus: ApparatusObservation? = null)
    val runtime = WorkoutRuntime(selection, side)
    private val edges = ObservedEdges()
    private val scene = SceneRegistration()
    private var poses = PoseEstimator(context)
    private var reference: BarSegment? = null
    private var setupGeneration = runtime.snapshot.setupGeneration
    private var lastDetectionSeconds: Double? = null
    private var sceneEvidence = SceneRegistration.Evidence(emptyList(), null)
    private var lastSceneSeconds: Double? = null
    fun process(bitmap: Bitmap, ptsUs: Long, attitude: PhoneAttitude.Sample?, paused: Boolean = false): Result {
        val started = System.nanoTime()
        val pose = poses.estimate(bitmap, ptsUs)
        val seconds = pose.timestamp.seconds
        val search = runtime.search(pose)
        val detectionDue = lastDetectionSeconds?.let { seconds - it >= AutomaticPullUpBar.samplingInterval } ?: true
        val apparatus = if (search != null && detectionDue) {
            lastDetectionSeconds = seconds
            val detections = search.regions.map { region -> runCatching { edges.detect(bitmap, region) } }
            ApparatusObservation(search, detections.map { it.getOrElse { emptyList() } },
                detections.map { it.exceptionOrNull()?.let { error ->
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(500)
                } })
        } else null
        val candidates = apparatus?.candidates ?: emptyList()
        if (reference != null && (lastSceneSeconds?.let { seconds - it >= AutomaticPullUpBar.samplingInterval } ?: true)) {
            sceneEvidence = scene.observe(bitmap)
            lastSceneSeconds = seconds
        }
        val evidence = sceneEvidence
        val snapshot = runtime.observe(WorkoutRuntime.FrameInput(pose = pose, candidates = candidates,
            attitude = attitude?.quaternion, motionTimestamp = attitude?.seconds,
            translations = evidence.translations, globalScaleFraction = evidence.scaleFraction,
            sceneTimestamp = lastSceneSeconds, paused = paused))
        if (snapshot.setupGeneration != setupGeneration || snapshot.referenceEdge != reference) {
            setupGeneration = snapshot.setupGeneration
            reference = snapshot.referenceEdge
            sceneEvidence = SceneRegistration.Evidence(emptyList(), null); lastSceneSeconds = null
            if (reference != null) scene.freeze(bitmap) else scene.reset()
        }
        val counter = runtime.session.counter
        val progress = counter.dipProgress?.fraction ?: counter.pullUpProgress?.fraction ?: 0.0
        val cue = counter.dipProgress?.cue?.name?.let(::cueText) ?: counter.pullUpProgress?.cue?.name?.let(::cueText) ?: snapshot.setupIssue.orEmpty()
        return Result(pose, snapshot, progress, cue, (System.nanoTime() - started) / 1e6, apparatus)
    }
    private fun cueText(name: String): String = when(name) {
        "hang", "reacquireHang" -> "Hold a full hang"
        "support", "reacquireSupport" -> "Hold top support"
        "pull" -> "Pull up"; "pullHigher", "finishPull" -> "Keep pulling"
        "lower", "lowerFurther" -> "Lower with control"; "rise" -> "Push up"
        "bendArms" -> "Bend both arms"; "extendArms" -> "Extend your arms"
        "hold" -> "Hold briefly"; "readyNext" -> "Ready for next rep"
        "showArms" -> "Show both arms"; "reestablishGrip" -> "Return to your grip"
        "setRail", "setBar" -> "Finding apparatus"; "finished" -> "Set finished"
        else -> ""
    }
    fun rewindReplay(): Boolean {
        val identity = requireNotNull(PhoneOrientationStability.Quaternion.create(0.0, 0.0, 0.0, 1.0))
        if (!runtime.rewindReplay(identity, 0.0)) return false
        setupGeneration = runtime.snapshot.setupGeneration
        poses.close(); poses = PoseEstimator(context)
        lastDetectionSeconds = null; lastSceneSeconds = null; sceneEvidence = SceneRegistration.Evidence(emptyList(), null)
        // Preserve fixed reference grayscale pixels; never relabel the rewound camera as stable.
        return true
    }
    override fun close() { poses.close(); scene.close() }
}
