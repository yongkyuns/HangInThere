package dev.hanginthere.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlin.math.abs
import kotlin.math.exp

/** Small post-hand-detection learned classifier. No model is bundled/enabled by
 * default. Source freshness, bilateral confirmation and history coverage remain
 * the caller's responsibility. Scores are not calibrated probabilities.
 */
class BinaryGripClassifier(private val model: Model) {
    @Serializable data class Model(val schemaVersion: Int = 1, val featureVersion: Int = 1,
        val labels: List<String>, val mean: List<Double>, val scale: List<Double>,
        val hiddenWeights: List<List<Double>>, val hiddenBias: List<Double>,
        val outputWeights: List<Double>, val outputBias: Double,
        val acceptanceScore: Double, val trainingSHA256: String,
        val qualification: String = "developmentOnly")
    @Serializable data class Decision(val grip: HandGrip, val underhandScore: Double?, val reason: String?)
    init {
        require(model.schemaVersion == 1 && model.featureVersion == BinaryGripFeatures.version)
        require(model.labels == listOf("overhand", "underhand"))
        require(model.mean.size == BinaryGripFeatures.count && model.scale.size == BinaryGripFeatures.count)
        require(model.hiddenWeights.size in 1..32 && model.hiddenWeights.all { it.size == BinaryGripFeatures.count })
        require(model.hiddenBias.size == model.hiddenWeights.size && model.outputWeights.size == model.hiddenWeights.size)
        require(model.mean.all { it.isFinite() && abs(it) <= 8 } && model.scale.all { it.isFinite() && it in .001..100.0 })
        require((model.hiddenWeights.flatten() + model.hiddenBias + model.outputWeights + model.outputBias).all { it.isFinite() && abs(it) <= 1000 })
        require(model.acceptanceScore.isFinite() && model.acceptanceScore in .5..1.0 && model.acceptanceScore > .5)
        require(model.trainingSHA256.matches(Regex("[0-9a-f]{64}")))
        require(model.qualification == "developmentOnly") // A promotion contract is deliberately not implemented yet.
    }
    fun classify(observation: GripObservation, side: ArmMeasurement.Side): Decision {
        val basis=WorkoutGripGeometry.bodyBasis(observation)
        if(basis!=null && !WorkoutGripGeometry.supportsView(basis))
            return Decision(HandGrip.unknown,null,"gripViewUnsupported")
        val features = BinaryGripFeatures.extract(observation, side)
            ?: return Decision(HandGrip.unknown, null, "handFeaturesUnavailable")
        return classifyFeatures(features)
    }
    /** Also used for Python/Kotlin parity. Native callers should use classify
     * with original observations, not features supplied by an unrelated source. */
    fun classifyFeatures(features: List<Double>): Decision {
        if (features.size != BinaryGripFeatures.count || features.any { !it.isFinite() })
            return Decision(HandGrip.unknown, null, "invalidHandFeatures")
        val x = features.indices.map { (features[it] - model.mean[it]) / model.scale[it] }
        if (x.any { !it.isFinite() || abs(it) > 8 }) return Decision(HandGrip.unknown, null, "outsideTrainingRange")
        val hidden = model.hiddenWeights.indices.map { i ->
            maxOf(0.0, model.hiddenWeights[i].indices.sumOf { j -> model.hiddenWeights[i][j] * x[j] } + model.hiddenBias[i])
        }
        val logit = hidden.indices.sumOf { hidden[it] * model.outputWeights[it] } + model.outputBias
        val score = if (logit >= 0) 1 / (1 + exp(-logit)) else exp(logit) / (1 + exp(logit))
        if (!score.isFinite()) return Decision(HandGrip.unknown, null, "invalidModelOutput")
        val grip = when {
            score >= model.acceptanceScore -> HandGrip.underhand
            1 - score >= model.acceptanceScore -> HandGrip.overhand
            else -> HandGrip.unknown
        }
        return Decision(grip, score, if (grip == HandGrip.unknown) "classifierUncertain" else null)
    }
}

/** Features reuse the six hand points already emitted by both native adapters.
 * Express wrist-relative hand vectors in the athlete's body frame so camera
 * facing does not become the label. No exercise/grip labels enter extraction.
 */
object BinaryGripFeatures {
    const val version = 1
    const val count = 19
    fun extract(observation: GripObservation, side: ArmMeasurement.Side): List<Double>? {
        val basis = WorkoutGripGeometry.bodyBasis(observation) ?: return null
        if (observation.hands.size !in 1..2) return null
        val hand = observation.hands.filter { WorkoutGripGeometry.hasHandIdentity(observation, it, side) }.singleOrNull() ?: return null
        fun point(joint: GripHandJoint): GripVector3? = hand.points.singleOrNull { it.joint == joint }?.takeIf { p ->
            (p.score == null || p.score.isFinite() && p.score in .5..1.0) && p.position.isFinite &&
                p.position.x in 0.0..observation.imageSize.width && p.position.y in 0.0..observation.imageSize.height &&
                hand.sourceRegion?.let { r -> r.isValidFor(observation.imageSize) && p.position.x in r.left.toDouble()..(r.left+r.width).toDouble() &&
                    p.position.y in r.top.toDouble()..(r.top+r.height).toDouble() } != false
        }?.world?.takeIf { it.isFinite }
        val wrist = point(GripHandJoint.wrist) ?: return null
        val joints = listOf(GripHandJoint.indexMCP, GripHandJoint.middleMCP, GripHandJoint.pinkyMCP, GripHandJoint.thumbCMC, GripHandJoint.thumbTip)
        val vectors = joints.map { (point(it) ?: return null) - wrist }
        if (vectors.any { it.length !in .005..0.3 }) return null
        val span = (vectors[0] - vectors[2]).length
        if (span !in .01..0.15) return null
        val normal = vectors[0].cross(vectors[2]).unit() ?: return null
        val vertical = basis.facing.cross(basis.left).unit() ?: return null
        fun coordinates(v: GripVector3) = listOf(v.dot(basis.left), v.dot(vertical), v.dot(basis.facing))
        val values = vectors.flatMap { coordinates(it * (1 / span)) } + coordinates(normal) +
            if (side == ArmMeasurement.Side.left) -1.0 else 1.0
        return values.takeIf { it.size == count && it.all { value -> value.isFinite() && abs(value) <= 8 } }
    }
}

/** Opt-in Apple transport. Loading a model does not enable it in workout
 * analysis. Callers verify the artifact checksum and own its lifecycle. */
class BinaryGripClassifierBridge @Throws(IllegalArgumentException::class) constructor(modelJson: String) {
    private val classifier: BinaryGripClassifier
    init {
        require(modelJson.length <= 65536)
        classifier = BinaryGripClassifier(WorkoutRecordCodec.json.decodeFromString<BinaryGripClassifier.Model>(modelJson))
    }
    @Throws(IllegalArgumentException::class)
    fun classifyJson(observationJson: String, side: String): String {
        require(observationJson.length <= 65536)
        val observation = WorkoutRecordCodec.json.decodeFromString<GripObservation>(observationJson)
        return WorkoutRecordCodec.json.encodeToString(classifier.classify(observation, ArmMeasurement.Side.valueOf(side)))
    }
}
