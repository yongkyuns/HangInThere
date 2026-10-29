package dev.hanginthere.core

import kotlinx.serialization.encodeToString
import kotlin.test.*

class BilateralGripDisplacementTests {
    private val size = ImageSize(720.0, 1280.0)
    private fun frame(ticks: Long = 0, leftDrop: Double = 0.0, rightDrop: Double = 0.0,
        handShift: Double = 0.0, elbows: Boolean = true, scoredHands: Boolean = true): PoseResult =
        BilateralGripFixtures.frame(ticks, leftDrop, rightDrop, handShift, elbows, scoredHands)
    private fun sample(pose: PoseResult) = assertNotNull(BilateralGripDisplacement.Sample.create(pose))
    private fun anchor(pose: PoseResult = frame()) = assertNotNull(BilateralGripDisplacement.Anchor.create(sample(pose)))
    private fun point(pose: PoseResult, joint: PoseJoint, position: Point2D): PoseResult {
        val changed = pose.people.single().landmarks.map { if (it.joint == joint) it.copy(position = position) else it }
        val hands = pose.gripObservation!!
        return pose.copy(people = listOf(PoseObservation(changed)), gripObservation = hands.copy(trackedBody = hands.trackedBody.map {
            if (it.joint.name == joint.name) it.copy(position = position) else it
        }))
    }

    @Test fun bothShouldersHaveIndependentSignedDisplacement() {
        val measurement = assertNotNull(anchor().measure(sample(frame(100, 100.0, 70.0))))
        assertEquals(100.0, measurement.left.axialPixels, 1e-9)
        assertEquals(70.0, measurement.right.axialPixels, 1e-9)
        assertEquals(0.0, measurement.left.lateralPixels, 1e-9)
        assertEquals(400.0, measurement.left.baselineArmPixels, 1e-9)
        assertTrue(measurement.left.retainsGrip && measurement.right.retainsGrip)
        assertEquals(PresentationTime(100, 1000), measurement.timestamp)
    }
    @Test fun anObliqueAnchorUsesItsOwnFixedAxis() {
        var top = point(frame(), PoseJoint.leftShoulder, Point2D(300.0, 200.0))
        top = point(top, PoseJoint.leftElbow, Point2D(180.0, 340.0))
        top = point(top, PoseJoint.leftWrist, Point2D(60.0, 480.0))
        val grip = top.gripObservation!!
        top = top.copy(gripObservation = grip.copy(hands = listOf(grip.hands[0].copy(points = grip.hands[0].points.map {
            it.copy(position = Point2D(it.position.x - 140, 520.0))
        }), grip.hands[1])))
        val later = point(top.copy(timestamp = PresentationTime(100, 1000),
            gripObservation = top.gripObservation!!.copy(seconds = .1)), PoseJoint.leftShoulder, Point2D(300.0, 300.0))
        val result = assertNotNull(anchor(top).measure(sample(later)))
        assertEquals(80.0, result.left.axialPixels, 1e-9)
        assertEquals(60.0, result.left.lateralPixels, 1e-9)
    }
    @Test fun missingElbowsDoNotEraseObservedShoulderMotionOrCreateATop() {
        val current = sample(frame(100, 100.0, 80.0, elbows = false))
        assertNull(current.left.elbowDegrees)
        assertNull(current.right.projectedArmPixels)
        assertFalse(current.isStartingTop)
        assertNull(BilateralGripDisplacement.Anchor.create(current))
        assertEquals(100.0, assertNotNull(anchor().measure(current)).left.axialPixels, 1e-9)
    }
    @Test fun oneShoulderOrWristCannotBecomeBilateralEvidence() {
        for (joint in listOf(PoseJoint.leftShoulder, PoseJoint.rightShoulder, PoseJoint.leftWrist, PoseJoint.rightWrist)) {
            val pose = frame()
            assertNull(BilateralGripDisplacement.Sample.create(pose.copy(people = listOf(PoseObservation(pose.people[0].landmarks.filter { it.joint != joint })))))
        }
    }
    @Test fun primaryBodyScoreBoundaryIsUnchanged() {
        for ((score, available) in listOf(.3 to true, .299 to false)) {
            val pose = frame()
            val changed = pose.people[0].landmarks.map { if (it.joint == PoseJoint.rightShoulder) it.copy(confidence = score) else it }
            val grip = pose.gripObservation!!
            val result = BilateralGripDisplacement.Sample.create(pose.copy(people = listOf(PoseObservation(changed)), gripObservation = grip.copy(trackedBody = grip.trackedBody.map {
                if (it.joint == GripBodyJoint.rightShoulder) it.copy(score = score) else it
            })))
            assertEquals(available, result != null)
        }
    }
    @Test fun aCachedHandResultCannotBeRetimestamped() {
        val pose = frame(100)
        assertNull(BilateralGripDisplacement.Sample.create(pose.copy(gripObservation = pose.gripObservation!!.copy(seconds = .099))))
        assertNull(BilateralGripDisplacement.Sample.create(pose.copy(gripObservation = pose.gripObservation!!.copy(imageSize = ImageSize(721.0, 1280.0)))))
    }
    @Test fun originatingBodyPositionsAndScoresMustMatchExactly() {
        val pose = frame(); val grip = pose.gripObservation!!; val first = grip.trackedBody.first()
        for (changed in listOf(first.copy(position = Point2D(first.position.x + 1, first.position.y)), first.copy(score = .91))) {
            assertNull(BilateralGripDisplacement.Sample.create(pose.copy(gripObservation = grip.copy(trackedBody = listOf(changed) + grip.trackedBody.drop(1)))))
        }
    }
    @Test fun trackedSubsetDoesNotClaimAFullPoseOriginOrRepairElbows() {
        val changed = point(frame(), PoseJoint.leftElbow, Point2D(400.0, 400.0))
        val observed = sample(changed)
        assertFalse(observed.isStartingTop)
        assertNotEquals(sample(frame()).left.elbowDegrees, observed.left.elbowDegrees)
        // GripObservation has no primary elbow snapshot. Its unchanged tracked
        // subset permits image association, not proof of the whole-pose origin.
        assertEquals(frame().gripObservation, changed.gripObservation)
    }
    @Test fun handOrderingCannotSwapTheArms() {
        val pose = frame(); val grip = pose.gripObservation!!
        val a = sample(pose); val b = sample(pose.copy(gripObservation = grip.copy(hands = grip.hands.reversed())))
        assertEquals(a.left, b.left); assertEquals(a.right, b.right)
    }
    @Test fun ambiguousHandAssociationsFailClosed() {
        var pose = point(frame(), PoseJoint.leftWrist, Point2D(300.0, 600.0))
        pose = point(pose, PoseJoint.rightWrist, Point2D(400.0, 600.0))
        val grip = pose.gripObservation!!
        val hands = grip.hands.map { h -> h.copy(points = h.points.map { it.copy(position = Point2D(350.0, 640.0)) }) }
        assertNull(BilateralGripDisplacement.Sample.create(pose.copy(gripObservation = grip.copy(hands = hands))))
    }
    @Test fun missingHandScoresStayUnscored() {
        val top = sample(frame(scoredHands = false))
        assertFalse(top.handPointsHaveScores)
        val result = assertNotNull(assertNotNull(BilateralGripDisplacement.Anchor.create(top)).measure(sample(frame(100))))
        assertFalse(result.handPointsHaveScores)
    }
    @Test fun weakMalformedOrDuplicateHandPointsCannotSupplyCentres() {
        val pose = frame(); val grip = pose.gripObservation!!; val hand = grip.hands[0]; val p = hand.points[0]
        val alternatives = listOf(hand.points.drop(1), hand.points + p,
            listOf(p.copy(score = .299)) + hand.points.drop(1), listOf(p.copy(score = Double.NaN)) + hand.points.drop(1),
            listOf(p.copy(position = Point2D(-1.0, 640.0))) + hand.points.drop(1))
        for (points in alternatives) assertNull(BilateralGripDisplacement.Sample.create(pose.copy(gripObservation = grip.copy(hands = listOf(hand.copy(points = points), grip.hands[1])))))
    }
    @Test fun requiredHandPointsMustStayInsideTheirSourceCrop() {
        val pose = frame(); val grip = pose.gripObservation!!; val hand = grip.hands[0]
        assertNull(BilateralGripDisplacement.Sample.create(pose.copy(gripObservation = grip.copy(hands = listOf(
            hand.copy(sourceRegion = PersonInferenceRegion(198, 630, 4, 20)), grip.hands[1])))))
    }
    @Test fun ongoingRetentionUsesTheArmRuleRatherThanSetupPointRigidity() {
        assertTrue(assertNotNull(anchor().measure(sample(frame(100, handShift = 25.0)))).left.retainsGrip)
        assertTrue(assertNotNull(anchor().measure(sample(frame(100, handShift = 48.0)))).left.retainsGrip)
        assertFalse(assertNotNull(anchor().measure(sample(frame(100, handShift = 48.001)))).left.retainsGrip)
    }
    @Test fun cameraMotionIsNotCertifiedByHandRetention() {
        var later = frame(100)
        for (p in later.people[0].landmarks) later = point(later, p.joint, Point2D(p.position.x, p.position.y + 10))
        val grip = later.gripObservation!!
        later = later.copy(gripObservation = grip.copy(hands = grip.hands.map { h -> h.copy(points = h.points.map { it.copy(position = Point2D(it.position.x, it.position.y + 10)) }) }))
        val result = assertNotNull(anchor().measure(sample(later)))
        assertEquals(10.0, result.left.axialPixels, 1e-9)
        assertTrue(result.left.retainsGrip) // An independent camera gate is essential.
    }
    @Test fun clockSourceImageAndModelChangesInvalidateAnAnchor() {
        val pose = frame(100); val grip = pose.gripObservation!!
        val changed = listOf(frame(-1), pose.copy(backend = "other"), pose.copy(requestRevision = 2),
            pose.copy(imageSize = ImageSize(721.0, 1280.0), gripObservation = grip.copy(imageSize = ImageSize(721.0, 1280.0))),
            pose.copy(gripObservation = grip.copy(modelId = "other")), pose.copy(gripObservation = grip.copy(backend = "other")))
        for (other in changed) assertNull(anchor().measure(sample(other)))
    }
    @Test fun equalTimesAndRationalAliasesCannotBecomeNewMotion() {
        val initial = frame(100)
        assertNull(anchor(initial).measure(sample(frame(100, 50.0))))
        val alias = initial.copy(timestamp = PresentationTime(1, 10))
        assertNull(anchor(initial).measure(sample(alias)))
        assertNotNull(anchor(alias).measure(sample(frame(101))))
        assertNull(anchor(alias).measure(sample(frame(99))))
        val huge = 9_007_199_254_740_992L
        assertEquals(frame(huge).timestamp.seconds, frame(huge + 1).timestamp.seconds)
        assertNotNull(anchor(frame(huge)).measure(sample(frame(huge + 1))))
        assertNull(anchor(frame(huge + 1)).measure(sample(frame(huge))))
    }
    @Test fun invalidContextsAndAmbiguousBodiesFailClosed() {
        val pose = frame(); val grip = pose.gripObservation!!
        for (other in listOf(pose.copy(people = emptyList()), pose.copy(people = pose.people + pose.people),
                pose.copy(people = listOf(PoseObservation(pose.people[0].landmarks + pose.people[0].landmarks[0]))),
                pose.copy(gripObservation = null), pose.copy(gripObservation = grip.copy(failure = "unavailable")),
                pose.copy(gripObservation = grip.copy(schemaVersion = 2)), pose.copy(gripObservation = grip.copy(coordinateConvention = "other")),
                pose.copy(gripObservation = grip.copy(modelId = "")), pose.copy(gripObservation = grip.copy(hands = grip.hands.take(1))))) {
            assertNull(BilateralGripDisplacement.Sample.create(other))
        }
    }
    @Test fun anAnchorOwnsValuesRatherThanMutableInputLists() {
        val pose = frame(); val grip = pose.gripObservation!!; val points = grip.hands[0].points.toMutableList()
        val initial = sample(pose.copy(gripObservation = grip.copy(hands = listOf(grip.hands[0].copy(points = points), grip.hands[1]))))
        val anchor = assertNotNull(BilateralGripDisplacement.Anchor.create(initial))
        points[0] = points[0].copy(position = Point2D(300.0, 700.0))
        assertEquals(0.0, assertNotNull(anchor.measure(sample(frame(100)))).left.gripDriftPixels, 1e-9)
    }
    @Test fun jsonTransportUsesTheSameGeometryAndPreservesFailure() {
        val bridge = BilateralGripDisplacementBridge()
        val first = WorkoutRecordCodec.json.encodeToString(frame())
        val next = WorkoutRecordCodec.json.encodeToString(frame(100, 100.0, 80.0))
        val value = assertNotNull(bridge.measureJson("{\"anchor\":$first,\"current\":$next}"))
        val decoded = WorkoutRecordCodec.json.decodeFromString<BilateralGripDisplacement.Measurement>(value)
        assertEquals(100.0, decoded.left.axialPixels, 1e-9)
        val missing = WorkoutRecordCodec.json.encodeToString(frame().copy(gripObservation = null))
        assertNull(bridge.measureJson("{\"anchor\":$missing,\"current\":$next}"))
        assertFailsWith<IllegalArgumentException> { bridge.measureJson("{}") }
    }
}
