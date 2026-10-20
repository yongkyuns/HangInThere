package dev.hanginthere.core

/** Raw-input validation, separate from body correspondence and tracking. */
internal object ObservedHandValidation {
    data class Validated(val pose: PoseResult, val hand: GripObservation) {
        fun boundTo(person: PoseObservation) = hand.trackedBody.all { tracked ->
            val point = person.landmarks.singleOrNull { it.joint.name == tracked.joint.name }
            point != null && point.position == tracked.position && point.confidence == tracked.score
        }
    }

    fun validate(pose: PoseResult, handSourceTime: PresentationTime, allowBodyless: Boolean = false): Validated? {
        val hand = pose.gripObservation ?: return null
        val size = pose.imageSize
        if (handSourceTime != pose.timestamp || !size.isValid || !pose.timestamp.seconds.isFinite() ||
            pose.backend.isBlank() || pose.people.size !in (if (allowBodyless) 0..32 else 1..32) || hand.schemaVersion != 1 ||
            hand.failure != null || hand.coordinateConvention != "cameraRightDownAway_v1" ||
            hand.seconds != pose.timestamp.seconds || hand.imageSize != size || hand.modelId.isBlank() ||
            hand.backend.isBlank() || hand.hands.size > 2 || hand.trackedBody.size > 6 ||
            hand.trackedBody.map { it.joint }.distinct().size != hand.trackedBody.size ||
            hand.trackedBody.any { !it.position.isFinite || !it.score.isFinite() || it.score !in 0.0..1.0 } ||
            pose.people.any { it.landmarks.size > 64 || it.landmarks.map { p -> p.joint }.distinct().size != it.landmarks.size })
            return null
        val result = Validated(pose, hand)
        // Cropped-out raw context remains origin binding, never positive pose.
        return result.takeIf { pose.people.any(result::boundTo) || allowBodyless && pose.people.isEmpty() && hand.trackedBody.isEmpty() }
    }

    fun wrist(hand: GripHandObservation, size: ImageSize, requiresScore: Boolean): GripHandPoint? {
        if (hand.points.size !in 1..6 || hand.points.map { it.joint }.distinct().size != hand.points.size) return null
        val point = hand.points.singleOrNull { it.joint == GripHandJoint.wrist } ?: return null
        val score = point.score
        if (requiresScore && score == null || score != null && (!score.isFinite() || score !in ArmMeasurement.confidenceThreshold..1.0) ||
            !point.position.isFinite || point.position.x !in 0.0..size.width || point.position.y !in 0.0..size.height) return null
        hand.sourceRegion?.let { region ->
            if (!region.isValidFor(size) || point.position.x !in region.left.toDouble()..(region.left.toDouble() + region.width) ||
                point.position.y !in region.top.toDouble()..(region.top.toDouble() + region.height)) return null
        }
        return point
    }
}
