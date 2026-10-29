package dev.hanginthere.core

import kotlin.math.hypot

/** Shoulder-relative image geometry, independent of hand-surface inference.
 * The caller supplies a currently validated front/rear view and owns athlete
 * identity. Camera/source/athlete changes must interrupt this measurement.
 * Ratios are projected image distances, never physical centimetres. */
class WorkoutGripWidth {
    data class Measurement(val width: GripWidth = GripWidth.unknown,
        val projectedSpanRatio: Double? = null, val reason: String? = null)

    private var last: Double? = null
    private var lastSize: ImageSize? = null
    private var lastBar: BarSegment? = null
    private var shoulderSpan: Double? = null
    private var lastSupported: Double? = null
    private var lastWrists: List<Point2D>? = null
    private var lastBodyAnchors: List<Point2D>? = null
    private var calibrationPaused = false
    private var pausedWidth = GripWidth.unknown
    private var pausedWrists: List<Point2D>? = null
    private val calibration = mutableListOf<Pair<Double, Double>>()
    private val ratios = mutableListOf<Double>()
    private var candidate = GripWidth.unknown
    private var since: Double? = null
    private var count = 0
    private var stable = GripWidth.unknown

    fun observe(pose: PoseResult, bar: BarSegment?, phase: ExerciseCounter.Phase,
                usable: Boolean, supportedView: Boolean, seconds: Double = pose.timestamp.seconds,
                allowCalibrationPause: Boolean = false): Measurement {
        if (!seconds.isFinite() || last?.let { seconds <= it } == true) {
            interrupt()
            return Measurement(reason = "invalidSourceTime")
        }
        if (last?.let { seconds-it > ExerciseCounter.maximumGapSeconds } == true ||
            lastSize?.let { it != pose.imageSize } == true || lastBar?.let { it != bar } == true) interrupt()
        last = seconds; lastSize = pose.imageSize; lastBar = bar
        // An invalid frame never reports a band/ratio or earns observation time.
        // Keep a private placement witness only to revalidate fresh wrists.
        if (calibrationPaused && (lastSupported?.let { seconds-it<=maximumCalibrationPauseSeconds }!=true ||
                !bodyIsContinuous(pose))) clearBaseline()
        if (!usable || !supportedView || !pose.imageSize.isValid || pose.people.size != 1) {
            if (!usable && supportedView && allowCalibrationPause && shoulderSpan!=null &&
                lastSupported?.let { seconds-it<=maximumCalibrationPauseSeconds }==true &&
                canPauseForWristConfidence(pose,bar) && bodyIsContinuous(pose)) {
                if(!calibrationPaused) { pausedWidth=stable;pausedWrists=lastWrists }
                calibrationPaused=true; clearBand()
                return Measurement(reason="wristConfidencePause")
            }
            clearBaseline()
            return Measurement(reason = if (!usable) "bodyTrackingUnavailable" else "widthViewUnavailable")
        }
        val person = pose.people.single()
        val joints = setOf(PoseJoint.leftShoulder, PoseJoint.rightShoulder, PoseJoint.leftWrist, PoseJoint.rightWrist)
        if (person.landmarks.filter { it.joint in joints }.groupingBy { it.joint }.eachCount().any { it.value != 1 }) {
            clearBaseline()
            return Measurement(reason = "widthLandmarksUnavailable")
        }
        val left = person.landmark(PoseJoint.leftShoulder, .5)?.position
        val right = person.landmark(PoseJoint.rightShoulder, .5)?.position
        fun inside(p: Point2D) = p.isFinite && p.x in 0.0..pose.imageSize.width && p.y in 0.0..pose.imageSize.height
        val span = if (left != null && right != null && inside(left) && inside(right)) hypot(left.x-right.x, left.y-right.y) else null
        // Capture distance/view are owned by the caller. A projected shoulder
        // span is not a distance test: it contracts with normal rep posture.
        // Keep finite, nondegenerate geometry and the existing ratio bounds,
        // without erasing a validated hang baseline at a fixed pixel cutoff.
        if (span == null || span <= 0.0) {
            clearBaseline()
            return Measurement(reason = "widthViewUnavailable")
        }
        // Rep posture must not substitute today's compressed shoulders for an
        // already frozen starting-hang denominator, even as a validity veto.
        val currentRatio=WorkoutGripGeometry.projectedWidth(pose, bar, shoulderSpan ?: span)
        if (currentRatio == null) {
            calibration.clear(); clearBand(); clearPause()
            return Measurement(reason = "gripContactUnavailable")
        }
        val wrists=listOf(PoseJoint.leftWrist,PoseJoint.rightWrist).map { person.landmark(it,.5)!!.position }
        val samePlacement=calibrationPaused && pausedWidth!=GripWidth.unknown &&
            WorkoutGripGeometry.widthBand(currentRatio)==pausedWidth && bodyIsContinuous(pose) &&
            pausedWrists?.zip(wrists)?.all { (a,b) -> hypot(a.x-b.x,a.y-b.y)<=shoulderSpan!!*placementToleranceShoulders }==true
        if(samePlacement) {
            // The old confirmation is still about these same supported hands.
            // The returned ratio is newly measured, never copied from history.
            stable=pausedWidth;candidate=pausedWidth;since=seconds;count=0
        }
        lastSupported=seconds;lastWrists=wrists;lastBodyAnchors=bodyAnchors(pose);clearPause()
        // Observe a stable ready-hang baseline. Shoulder motion during a rep
        // cannot redefine its denominator or turn a fixed grip into a new band.
        val startingArms=if(phase==ExerciseCounter.Phase.seekingStart)PullUpArmMeasurement(pose) else null
        val acquiringHang=startingArms?.let { it.isExtended && it.arms.all { arm ->
            arm.shoulder.y-arm.wrist.y>minOf(pose.imageSize.width,pose.imageSize.height)*.08
        } }==true
        // Observe the existing starting-hang dwell instead of adding another
        // 0.4 seconds after the counter has already acquired that same hang.
        // Only the counter's ready phase can freeze/activate this baseline.
        if (shoulderSpan == null && (phase == ExerciseCounter.Phase.ready || acquiringHang)) {
            calibration += seconds to span
            while (calibration.size > 32 || calibration.firstOrNull()?.let { seconds-it.first > .6 } == true) calibration.removeAt(0)
            if (calibration.size >= 5 && seconds-calibration.first().first >= .4) {
                val values = calibration.map { it.second }.sorted()
                if (phase == ExerciseCounter.Phase.ready && values.last()/values.first() <= 1.15) shoulderSpan = values[values.size/2]
            }
        } else if (shoulderSpan == null) calibration.clear()
        val ratio = shoulderSpan?.let { WorkoutGripGeometry.projectedWidth(pose, bar, it) }
        if (ratio == null) {
            clearBand()
            return Measurement(reason = if (shoulderSpan == null) "waitingForStableHangBaseline" else "gripContactUnavailable")
        }
        ratios += ratio; if (ratios.size > 5) ratios.removeAt(0)
        val measured = ratios.sorted()[ratios.size/2]
        val band = when {
            stable == GripWidth.narrow && measured < .9 -> GripWidth.narrow
            stable == GripWidth.wide && measured > 1.25 -> GripWidth.wide
            stable == GripWidth.shoulderWidth && measured in .8..1.35 -> GripWidth.shoulderWidth
            else -> WorkoutGripGeometry.widthBand(measured)
        }
        if (candidate != band) { candidate = band; since = seconds; count = 0; stable = GripWidth.unknown }
        count++
        if (count >= 3 && seconds-(since ?: seconds) >= WorkoutGripAnalysis.confirmationSeconds) stable = band
        return Measurement(stable, ratio, if (stable == GripWidth.unknown) "confirmingWidth" else null)
    }

    fun interrupt() { clearBaseline() }
    private fun clearBaseline() { shoulderSpan = null; lastSupported=null;lastWrists=null;lastBodyAnchors=null;clearPause();calibration.clear();clearBand() }
    private fun clearPause() { calibrationPaused=false;pausedWidth=GripWidth.unknown;pausedWrists=null }
    private fun bodyIsContinuous(pose:PoseResult):Boolean {
        val previous=lastBodyAnchors ?: return false
        val current=bodyAnchors(pose) ?: return false
        return previous.zip(current).all { (a,b) -> hypot(a.x-b.x,a.y-b.y)<=minOf(pose.imageSize.width,pose.imageSize.height)*.12 }
    }
    private fun clearBand() { stable = GripWidth.unknown; candidate = GripWidth.unknown; since = null; count = 0; ratios.clear() }

    companion object {
        const val policyVersion = 5
        const val maximumCalibrationPauseSeconds = ExerciseCounter.maximumGapSeconds
        const val placementToleranceShoulders = .15

        /** Width needs current shoulders and supported wrists, not elbow angles.
         * This cannot establish athlete identity, camera view, or a ready hang. */
        fun hasCurrentSupport(pose: PoseResult, bar: BarSegment?): Boolean {
            if (!pose.imageSize.isValid || pose.people.size != 1 || bar?.isValid != true) return false
            val person = pose.people.single()
            val joints = setOf(PoseJoint.leftShoulder, PoseJoint.rightShoulder, PoseJoint.leftWrist, PoseJoint.rightWrist)
            if (person.landmarks.filter { it.joint in joints }.groupingBy { it.joint }.eachCount().any { it.value != 1 }) return false
            fun point(joint: PoseJoint) = person.landmark(joint,.5)?.position?.takeIf {
                it.isFinite && it.x in 0.0..pose.imageSize.width && it.y in 0.0..pose.imageSize.height
            }
            val left = point(PoseJoint.leftShoulder) ?: return false
            val right = point(PoseJoint.rightShoulder) ?: return false
            if (point(PoseJoint.leftWrist) == null || point(PoseJoint.rightWrist) == null) return false
            val span = hypot(left.x-right.x,left.y-right.y)
            return span > 0.0 &&
                WorkoutGripGeometry.projectedWristSpan(pose,bar) != null
        }

        private fun bodyAnchors(pose:PoseResult):List<Point2D>? {
            val person=pose.people.singleOrNull() ?: return null
            val names=listOf(PoseJoint.leftShoulder,PoseJoint.rightShoulder,PoseJoint.leftHip,PoseJoint.rightHip)
            if(person.landmarks.filter { it.joint in names }.groupingBy { it.joint }.eachCount().any { it.value!=1 }) return null
            return names.map { name ->
                val p=person.landmark(name,.5)?.position ?: return null
                p.takeIf { it.x in 0.0..pose.imageSize.width && it.y in 0.0..pose.imageSize.height } ?: return null
            }
        }
        /** A lone low-confidence wrist is unmeasured, including an outlier raw
         * location. It cannot establish support or positively prove a release.
         * The other strong wrist must stay supported, with strong continuous
         * torso anchors and the caller's identity/exercise/view qualification. */
        internal fun canPauseForWristConfidence(pose: PoseResult, bar: BarSegment?): Boolean {
            if (!pose.imageSize.isValid || pose.people.size!=1 || bar?.isValid!=true) return false
            val names=setOf(PoseJoint.leftShoulder,PoseJoint.rightShoulder,PoseJoint.leftWrist,PoseJoint.rightWrist)
            val points=pose.people.single().landmarks.filter { it.joint in names }
            if(points.size!=4 || points.map { it.joint }.toSet()!=names) return false
            if(points.any { !it.confidence.isFinite() || it.confidence !in 0.0..1.0 || !it.position.isFinite ||
                    it.position.x !in 0.0..pose.imageSize.width || it.position.y !in 0.0..pose.imageSize.height }) return false
            val shoulders=points.filter { it.joint==PoseJoint.leftShoulder || it.joint==PoseJoint.rightShoulder }
            if(shoulders.any { it.confidence<.5 } || shoulders[0].position==shoulders[1].position) return false
            val wrists=points.filter { it.joint==PoseJoint.leftWrist || it.joint==PoseJoint.rightWrist }
            return wrists.count { it.confidence<.5 }==1 &&
                wrists.filter { it.confidence>=.5 }.all { bar.distance(it.position)<=minOf(pose.imageSize.width,pose.imageSize.height)*.085 }
        }
    }
}
