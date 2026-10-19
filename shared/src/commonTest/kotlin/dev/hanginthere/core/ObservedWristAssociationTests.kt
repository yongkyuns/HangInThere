package dev.hanginthere.core

import kotlinx.serialization.encodeToString
import kotlin.test.*

class ObservedWristAssociationTests {
    private fun frame(elbows: Boolean = true, fingers: Boolean = false): PoseResult {
        val pose = BilateralGripFixtures.frame(elbows = elbows)
        val hands = pose.gripObservation!!
        return pose.copy(gripObservation = hands.copy(hands = hands.hands.mapIndexed { index, hand ->
            val wrist = pose.people[0].landmark(ArmMeasurement.Side.entries[index].joints[2])!!.position
            hand.copy(points = (if (fingers) hand.points else emptyList()) + GripHandPoint(GripHandJoint.wrist, wrist, score = .9))
        }))
    }
    private fun associate(pose: PoseResult, scored: Boolean = true, time: PresentationTime = pose.timestamp) =
        ObservedWristAssociation.associate(ObservedWristAssociation.Frame(pose, time, scored))
    private fun handPoints(pose: PoseResult, edit: (GripHandPoint) -> GripHandPoint): PoseResult =
        pose.copy(gripObservation = pose.gripObservation!!.copy(hands = pose.gripObservation.hands.map { hand ->
            hand.copy(points = hand.points.map(edit))
        }))

    @Test fun missingFingersAndElbowsDoNotEraseCurrentWrists() {
        val pose = frame(elbows = false)
        val before = WorkoutRecordCodec.json.encodeToString(pose)
        val result = assertNotNull(associate(pose))
        assertEquals(listOf(ArmMeasurement.Side.left, ArmMeasurement.Side.right), result.matches.map { it.bodyWrists.single().side })
        assertTrue(result.unresolvedHands.isEmpty())
        assertEquals(before, WorkoutRecordCodec.json.encodeToString(pose))
        assertNull(ObservedGripAssociation.associate(ObservedGripAssociation.Frame(pose, pose.timestamp)))
    }

    @Test fun correspondencePreservesBothIndependentCoordinates() {
        val pose = handPoints(frame()) { it.copy(position = Point2D(it.position.x + 5, it.position.y + 6)) }
        val result = assertNotNull(associate(pose))
        for (match in result.matches) {
            val original = pose.people[match.originalPersonIndex].landmark(match.bodyWrists.single().side.joints[2])!!.position
            assertEquals(original, match.bodyWrists.single().position)
            assertEquals(Point2D(original.x + 5, original.y + 6), match.observedWrist)
            assertNotEquals(original, match.observedWrist)
        }
    }

    @Test fun anUncorroboratedHipWristDoesNotCancelTheOtherHand() {
        val pose = frame()
        val person = PoseObservation(pose.people[0].landmarks.map {
            if (it.joint == PoseJoint.rightWrist) it.copy(position = Point2D(500.0, 1000.0), confidence = .99) else it
        })
        val changed = pose.copy(people = listOf(person), gripObservation = pose.gripObservation!!.copy(trackedBody = emptyList()))
        val result = assertNotNull(associate(changed))
        assertEquals(ArmMeasurement.Side.left, result.matches.single().bodyWrists.single().side)
        assertEquals(listOf(ObservedWristAssociation.UnresolvedHand(1, emptyList())), result.unresolvedHands)
        assertEquals(Point2D(500.0, 1000.0), changed.people[0].landmark(PoseJoint.rightWrist)!!.position)
    }

    @Test fun overlappingWristsRetainBothSideCandidatesWithoutSdkSideSelection() {
        val pose = frame()
        val person = PoseObservation(pose.people[0].landmarks.map {
            if (it.joint == PoseJoint.rightWrist) it.copy(position = Point2D(200.0, 600.0)) else it
        })
        val hand = pose.gripObservation!!.hands[0].copy(sdkHandedness = "right", sdkHandednessScore = 1.0)
        val result = assertNotNull(associate(pose.copy(people = listOf(person),
            gripObservation = pose.gripObservation.copy(hands = listOf(hand), trackedBody = emptyList()))))
        assertEquals(ArmMeasurement.Side.entries, result.matches.single().bodyWrists.map { it.side })
    }

    @Test fun competingPeopleStayUnresolvedInEitherOrder() {
        val pose = frame()
        val other = PoseObservation(pose.people[0].landmarks.map { it.copy(confidence = .99) })
        for (people in listOf(listOf(pose.people[0], other), listOf(other, pose.people[0]))) {
            val result = assertNotNull(associate(pose.copy(people = people)))
            assertTrue(result.matches.isEmpty())
            assertEquals(listOf(listOf(0, 1), listOf(0, 1)), result.unresolvedHands.map { it.candidatePersonIndices })
        }
    }

    @Test fun duplicateHandsRemainAnExplicitCompetitionForTheSameBodyWrist() {
        val pose = frame(); val observation = pose.gripObservation!!
        val duplicate = pose.copy(gripObservation = observation.copy(hands = List(2) { observation.hands[0] }))
        val result = assertNotNull(associate(duplicate))
        assertEquals(2, result.matches.size)
        assertEquals(listOf(ObservedWristAssociation.Competition(0, ArmMeasurement.Side.left, listOf(0, 1))), result.competingBodyWrists)
        assertTrue(result.unresolvedHands.isEmpty())
    }

    @Test fun sourceBindingIsCheckedEvenWhenAllHandsAreUnresolved() {
        val pose = frame(); val observation = pose.gripObservation!!
        val missing = observation.hands.map { it.copy(points = emptyList()) }
        assertNull(associate(pose.copy(gripObservation = observation.copy(hands = missing,
            trackedBody = observation.trackedBody.map { it.copy(score = .8) }))))
        assertTrue(assertNotNull(associate(pose.copy(gripObservation = observation.copy(hands = missing)))).matches.isEmpty())
    }

    @Test fun aCroppedOutHipRemainsRawContextWithoutBecomingWristEvidence() {
        val pose = frame(); val hip = Landmark(PoseJoint.leftHip, Point2D(200.0, 1400.0), .2)
        val current = pose.copy(people = listOf(PoseObservation(pose.people[0].landmarks + hip)),
            gripObservation = pose.gripObservation!!.copy(trackedBody = pose.gripObservation.trackedBody +
                GripTrackedPoint(GripBodyJoint.leftHip, hip.position, hip.confidence)))
        assertEquals(assertNotNull(associate(pose)).matches, assertNotNull(associate(current)).matches)
        assertEquals(hip, current.people[0].landmarks.last())
    }

    @Test fun distinctSubjectsAreNotMergedIntoABilateralAthlete() {
        val pose = frame()
        val people = ArmMeasurement.Side.entries.map { side -> PoseObservation(pose.people[0].landmarks.filter { it.joint.name.startsWith(side.name) }) }
        val result = assertNotNull(associate(pose.copy(people = people, gripObservation = pose.gripObservation!!.copy(trackedBody = emptyList()))))
        assertEquals(listOf(0, 1), result.matches.map { it.originalPersonIndex })
    }

    @Test fun unscoredPositionsNeedExplicitProviderCapabilityAndNeverGainScores() {
        val pose = handPoints(frame()) { it.copy(score = null) }
        assertTrue(assertNotNull(associate(pose)).matches.isEmpty())
        val unscored = assertNotNull(associate(pose, scored = false))
        assertEquals(2, unscored.matches.size)
        assertTrue(unscored.matches.all { it.jointConfidence == null })
        assertTrue(assertNotNull(associate(handPoints(pose) { it.copy(score = .29) }, scored = false)).matches.isEmpty())
    }

    @Test fun fingersCannotSubstituteForAnAbsentOrInvalidWrist() {
        val pose = frame(fingers = true)
        val noWrists = pose.copy(gripObservation = pose.gripObservation!!.copy(hands = pose.gripObservation.hands.map {
            it.copy(points = it.points.filter { point -> point.joint != GripHandJoint.wrist })
        }))
        assertTrue(assertNotNull(associate(noWrists)).matches.isEmpty())
        for (score in listOf(-1.0, .29, 1.01, Double.NaN, Double.POSITIVE_INFINITY))
            assertTrue(assertNotNull(associate(handPoints(pose) { if (it.joint == GripHandJoint.wrist) it.copy(score = score) else it })).matches.isEmpty())
        assertEquals(2, assertNotNull(associate(handPoints(pose) { it.copy(score = ArmMeasurement.confidenceThreshold) })).matches.size)
    }

    @Test fun actualWristMustBeInsideItsSourceAndCrop() {
        val pose = frame()
        val hand = pose.gripObservation!!
        val cropped = pose.copy(gripObservation = hand.copy(hands = hand.hands.map { it.copy(sourceRegion = PersonInferenceRegion(0, 0, 20, 20)) }))
        assertTrue(assertNotNull(associate(cropped)).matches.isEmpty())
        for (point in listOf(Point2D(Double.NaN, 600.0), Point2D(-1.0, 600.0), Point2D(721.0, 600.0)))
            assertTrue(assertNotNull(associate(handPoints(pose) { it.copy(position = point) })).matches.isEmpty())
    }

    @Test fun badMetadataAndOriginBindingCannotDonateWristCorrespondence() {
        val pose = frame(); val hand = pose.gripObservation!!
        val faults = listOf(pose.copy(timestamp = PresentationTime(1, 1000)),
            pose.copy(gripObservation = hand.copy(imageSize = ImageSize(721.0, 1280.0))),
            pose.copy(gripObservation = hand.copy(schemaVersion = 2)),
            pose.copy(gripObservation = hand.copy(coordinateConvention = "mirrored")),
            pose.copy(gripObservation = hand.copy(failure = "failed")),
            pose.copy(gripObservation = hand.copy(backend = "")),
            pose.copy(gripObservation = hand.copy(trackedBody = hand.trackedBody.map { it.copy(score = .8) })),
            pose.copy(people = List(33) { pose.people[0] }),
            pose.copy(people = listOf(PoseObservation(pose.people[0].landmarks + pose.people[0].landmarks.first()))))
        faults.forEach { assertNull(associate(it)) }
    }

    @Test fun rationalSourceBoundaryIsNotReconstructedFromFloatingSeconds() {
        val pose = frame(); val old = PresentationTime(9_007_199_254_740_992, 1000); val next = old.copy(value = old.value + 1)
        assertEquals(old.seconds, next.seconds)
        val current = pose.copy(timestamp = next, gripObservation = pose.gripObservation!!.copy(seconds = next.seconds))
        assertNull(associate(current, time = old))
        assertNotNull(associate(current, time = next))
        assertNull(associate(pose, time = PresentationTime(0, 1)))
    }

    @Test fun bridgePreservesRawJointRolesAndNullableConfidence() {
        val pose = handPoints(frame()) { it.copy(score = null) }
        val frame = ObservedWristAssociation.Frame(pose, pose.timestamp, requiresJointConfidence = false)
        val bridge = ObservedWristAssociationBridge()
        val result = assertNotNull(bridge.associateJson(WorkoutRecordCodec.json.encodeToString(frame)))
        assertEquals(associate(pose, scored = false), WorkoutRecordCodec.json.decodeFromString<ObservedWristAssociation.Result>(result))
        assertNull(bridge.associateJson(WorkoutRecordCodec.json.encodeToString(frame.copy(pose = pose.copy(gripObservation = null)))))
    }
}
