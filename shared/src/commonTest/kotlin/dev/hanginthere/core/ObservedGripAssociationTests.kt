package dev.hanginthere.core

import kotlinx.serialization.encodeToString
import kotlin.test.*

class ObservedGripAssociationTests {
    private fun singleHand(): PoseResult {
        val pose = BilateralGripFixtures.frame()
        return pose.copy(gripObservation = pose.gripObservation!!.copy(trackedBody = emptyList(), hands = pose.gripObservation.hands.take(1)))
    }
    private fun associate(pose: PoseResult, handTime: PresentationTime = pose.timestamp,
        correspondence: ObservedGripAssociation.Correspondence = ObservedGripAssociation.Correspondence.gripCentre) =
        ObservedGripAssociation.associate(ObservedGripAssociation.Frame(pose, handTime, correspondence))
    private fun associated(pose: PoseResult) = assertNotNull(associate(pose))
    private fun wristAssociated(pose: PoseResult) =
        associate(pose, correspondence = ObservedGripAssociation.Correspondence.observedWrist)
    private fun withHandWrists(pose: PoseResult, mcpDrop: Double = 0.0): PoseResult {
        val hand = pose.gripObservation!!
        return pose.copy(gripObservation = hand.copy(hands = hand.hands.mapIndexed { i, value ->
            val side = ArmMeasurement.Side.entries[i]
            val wrist = pose.people[0].landmark(side.joints[2])!!.position
            value.copy(points = value.points.map { p -> p.copy(position = Point2D(p.position.x, p.position.y + mcpDrop)) } +
                GripHandPoint(GripHandJoint.wrist, wrist, score = .9))
        }))
    }
    private fun input(pose: PoseResult) = WorkoutRecordCodec.json.encodeToString(ObservedGripAssociation.Frame(pose, pose.timestamp))

    @Test fun oneIndependentHandMeasuresOnlyItsOriginalArm() {
        val pose = singleHand()
        val sample = associated(pose)
        val arm = sample.arms.single()
        assertEquals(ArmMeasurement.Side.left, arm.side)
        assertEquals(pose.people[0].landmark(PoseJoint.leftWrist)!!.position, arm.wrist)
        assertEquals(Point2D(200.0, 640.0), arm.grip)
        assertNotEquals(arm.wrist, arm.grip)
        assertEquals(ArmMeasurement(pose, ArmMeasurement.Side.left).estimate, arm.measurement)
        assertEquals(pose.timestamp, sample.timestamp)
    }

    @Test fun anOccludedOppositeArmIsNotReconstructedOrRequiredByCorrespondence() {
        val pose = singleHand()
        val missing = pose.copy(people = listOf(PoseObservation(pose.people[0].landmarks.filter { !it.joint.name.startsWith("right") })))
        assertEquals(associated(pose).arms, associated(missing).arms)
        assertNull(BilateralGripDisplacement.Sample.create(missing))
    }

    @Test fun bothOriginalArmsAreRetainedAndHandOrderCannotChangeAnatomicalSides() {
        val pose = BilateralGripFixtures.frame()
        val expected = associated(pose)
        val reversed = associated(pose.copy(gripObservation = pose.gripObservation!!.copy(hands = pose.gripObservation.hands.reversed())))
        assertEquals(listOf(ArmMeasurement.Side.left, ArmMeasurement.Side.right), expected.arms.map { it.side })
        assertEquals(expected.arms.map { it.copy(handIndex = 0) }, reversed.arms.map { it.copy(handIndex = 0) })
        assertEquals(listOf(1, 0), reversed.arms.map { it.handIndex })
    }

    @Test fun uniqueWristCorrespondenceRetainsAllOriginalPeopleAndTheirSourceIndex() {
        val original = singleHand()
        val unrelated = PoseObservation(original.people[0].landmarks.map { it.copy(position = Point2D(it.position.x, it.position.y + 300)) })
        val pose = original.copy(people = listOf(unrelated, original.people[0]))
        val before = WorkoutRecordCodec.json.encodeToString(pose)
        assertEquals(1, associated(pose).originalPersonIndex)
        assertEquals(associated(original).arms, associated(pose).arms)
        assertEquals(before, WorkoutRecordCodec.json.encodeToString(pose))
        assertEquals(ArmMeasurement.UnavailableReason.multiplePeople, ArmMeasurement(pose, ArmMeasurement.Side.left).unavailableReason)
    }

    @Test fun ambiguousPeopleOrArmsCannotBeResolvedByScoreAngleOrOrder() {
        val pose = singleHand()
        val other = PoseObservation(pose.people[0].landmarks.map { p ->
            if (p.joint == PoseJoint.leftElbow) p.copy(position = Point2D(320.0, 400.0), confidence = 1.0) else p
        })
        for (people in listOf(listOf(pose.people[0], other), listOf(other, pose.people[0])))
            assertNull(associate(pose.copy(people = people)))
        val overlapping = PoseObservation(pose.people[0].landmarks.map { p ->
            if (p.joint == PoseJoint.rightWrist) p.copy(position = Point2D(200.0, 600.0)) else p
        })
        assertNull(associate(pose.copy(people = listOf(overlapping))))
    }

    @Test fun twoHandsCannotCorrespondToDifferentPeopleOrTheSameArm() {
        val pose = BilateralGripFixtures.frame()
        val left = PoseObservation(pose.people[0].landmarks.filter { it.joint.name.startsWith("left") })
        val right = PoseObservation(pose.people[0].landmarks.filter { it.joint.name.startsWith("right") })
        assertNull(associate(pose.copy(people = listOf(left, right),
            gripObservation = pose.gripObservation!!.copy(trackedBody = emptyList()))))
        val one = singleHand(); val hand = one.gripObservation!!.hands.single()
        assertNull(associate(one.copy(gripObservation = one.gripObservation.copy(hands = listOf(hand, hand)))))
    }

    @Test fun missingWristElbowOrMeasuredScoresStayMissingDespiteAuxiliaryObservations() {
        val pose = singleHand()
        for (joint in ArmMeasurement.Side.left.joints) {
            val person = PoseObservation(pose.people[0].landmarks.filter { it.joint != joint })
            assertNull(associate(pose.copy(people = listOf(person))))
        }
        val unscored = BilateralGripFixtures.frame(scoredHands = false)
        assertNotNull(BilateralGripDisplacement.Sample.create(unscored), "Existing bilateral transport retains explicitly unscored MCP semantics")
        assertNull(associate(unscored))
    }

    @Test fun staleForeignMalformedAndOverBudgetObservationsCannotSupplyCorrespondence() {
        val pose = singleHand(); val hand = pose.gripObservation!!
        val faults = listOf(pose.copy(timestamp = PresentationTime(1, 1000)),
            pose.copy(gripObservation = hand.copy(imageSize = ImageSize(721.0, 1280.0))),
            pose.copy(gripObservation = hand.copy(coordinateConvention = "mirrored")),
            pose.copy(gripObservation = hand.copy(failure = "failed")),
            pose.copy(gripObservation = hand.copy(trackedBody = BilateralGripFixtures.frame().gripObservation!!.trackedBody.map { it.copy(score = .8) })),
            pose.copy(people = List(33) { pose.people[0] }),
            pose.copy(people = listOf(PoseObservation(pose.people[0].landmarks + pose.people[0].landmarks.first()))),
            pose.copy(gripObservation = hand.copy(hands = listOf(hand.hands.single().copy(sourceRegion = PersonInferenceRegion(0, 0, 10, 10))))))
        faults.forEach { assertNull(associate(it)) }
    }

    @Test fun nearbyRationalFramesWithTheSameDoubleTimeCannotReuseTheHandBinding() {
        val oldTime = PresentationTime(9_007_199_254_740_992, 1000)
        val nextTime = PresentationTime(oldTime.value + 1, oldTime.timescale)
        assertEquals(oldTime.seconds, nextTime.seconds)
        val pose = singleHand()
        val next = pose.copy(timestamp = nextTime, gripObservation = pose.gripObservation!!.copy(seconds = nextTime.seconds))
        assertNull(associate(next, oldTime))
        assertNotNull(associate(next, nextTime))
        // Equivalent floating seconds, including a normalized fraction, are
        // not the originating native request's exact source timestamp tuple.
        assertNull(associate(pose, PresentationTime(0, 1)))
    }

    @Test fun bridgePreservesMeasuredGeometryAndRejectsUnavailableEvidence() {
        val pose = singleHand()
        val json = ObservedGripAssociationBridge().associateJson(input(pose))
        assertEquals(associated(pose), WorkoutRecordCodec.json.decodeFromString<ObservedGripAssociation.Sample>(assertNotNull(json)))
        assertNull(ObservedGripAssociationBridge().associateJson(input(pose.copy(gripObservation = null))))
        val search = WorkoutRecordCodec.json.parseToJsonElement(assertNotNull(
            ObservedGripAssociationBridge().dipSearchJson(input(pose))))
        assertTrue(search.toString().contains("originalPersonIndex"))
        assertNull(ObservedGripAssociationBridge().dipSearchJson(input(pose.copy(gripObservation = null))))
    }

    @Test fun observedWristMatchesTheSameJointWhileTheKnuckleCentreRemainsSeparate() {
        val pose = withHandWrists(singleHand(), mcpDrop = 60.0)
        assertNull(associate(pose), "The original image-radius policy still rejects this wrist-to-knuckle offset")
        val sample = assertNotNull(wristAssociated(pose)); val arm = sample.arms.single()
        assertEquals(2, sample.policyVersion)
        assertEquals(Point2D(200.0, 600.0), arm.wrist)
        assertEquals(arm.wrist, arm.observedHandWrist)
        assertEquals(Point2D(200.0, 700.0), arm.grip)
        assertEquals(ArmMeasurement(pose, ArmMeasurement.Side.left).estimate, arm.measurement)
        assertEquals(1, associated(singleHand()).policyVersion)
        assertNull(associated(singleHand()).arms.single().observedHandWrist)
    }

    @Test fun wristModeNeverFallsBackToValidKnucklesForMissingOrWeakWristEvidence() {
        val base = singleHand(); val good = withHandWrists(base); val hand = good.gripObservation!!
        assertNull(wristAssociated(base))
        for (score in listOf(null, .299)) {
            val unavailable = good.copy(gripObservation = hand.copy(hands = hand.hands.map { value ->
                value.copy(points = value.points.map { if (it.joint == GripHandJoint.wrist) it.copy(score = score) else it })
            }))
            assertNotNull(associate(unavailable), "Legacy three-MCP behavior is preserved")
            assertNull(wristAssociated(unavailable))
        }
        val wrongWrist = good.copy(gripObservation = hand.copy(hands = hand.hands.map { value ->
            value.copy(points = value.points.map { if (it.joint == GripHandJoint.wrist) it.copy(position = Point2D(200.0, 1000.0)) else it })
        }))
        assertNotNull(associate(wrongWrist))
        assertNull(wristAssociated(wrongWrist), "Knuckles cannot overrule a contradictory observed wrist")
    }

    @Test fun observedWristCannotReplaceMissingPrimaryWristOrSelectBetweenPeople() {
        val pose = withHandWrists(singleHand(), mcpDrop = 60.0)
        val missing = pose.copy(people = listOf(PoseObservation(pose.people[0].landmarks.filter { it.joint != PoseJoint.leftWrist })))
        assertNull(wristAssociated(missing))
        val other = PoseObservation(pose.people[0].landmarks.map { p ->
            if (p.joint == PoseJoint.leftWrist) p.copy(position = Point2D(200.0, 620.0), confidence = 1.0) else p
        })
        for (people in listOf(listOf(pose.people[0], other), listOf(other, pose.people[0])))
            assertNull(wristAssociated(pose.copy(people = people)), "No score, hand order or knuckle distance ranks original people")
    }

    @Test fun bothObservedWristsPreserveAllSidesAndHandOrderWithoutSharingOneBodyWrist() {
        val pose = withHandWrists(BilateralGripFixtures.frame())
        val sample = assertNotNull(wristAssociated(pose))
        val reversed = assertNotNull(wristAssociated(pose.copy(gripObservation = pose.gripObservation!!.copy(hands = pose.gripObservation.hands.reversed()))))
        assertEquals(listOf(ArmMeasurement.Side.left, ArmMeasurement.Side.right), sample.arms.map { it.side })
        assertEquals(sample.arms.map { it.copy(handIndex = 0) }, reversed.arms.map { it.copy(handIndex = 0) })
        val wrong = pose.copy(gripObservation = pose.gripObservation!!.copy(hands = pose.gripObservation.hands.map { value ->
            value.copy(points = value.points.map { if (it.joint == GripHandJoint.wrist) it.copy(position = sample.arms[0].wrist) else it })
        }))
        assertNull(wristAssociated(wrong), "Distinct MCPs cannot donate two matches to one original arm")
    }

    @Test fun observedWristMustBelongToTheActualCropAndExactHandRequestTime() {
        val pose = withHandWrists(singleHand()); val hand = pose.gripObservation!!
        val outside = pose.copy(gripObservation = hand.copy(hands = hand.hands.map {
            it.copy(sourceRegion = PersonInferenceRegion(180, 630, 40, 30))
        }))
        assertNotNull(associate(outside), "The original transport requires only its three MCPs in the crop")
        assertNull(wristAssociated(outside))
        assertNull(associate(pose, PresentationTime(1, 1000), ObservedGripAssociation.Correspondence.observedWrist))
    }

    @Test fun explicitWristBridgePreservesVersionAndLegacyMissingVersionDefaultsToOne() {
        val pose = withHandWrists(singleHand())
        val frame = ObservedGripAssociation.Frame(pose, pose.timestamp, ObservedGripAssociation.Correspondence.observedWrist)
        val encoded = assertNotNull(ObservedGripAssociationBridge().associateJson(WorkoutRecordCodec.json.encodeToString(frame)))
        assertEquals(wristAssociated(pose), WorkoutRecordCodec.json.decodeFromString<ObservedGripAssociation.Sample>(encoded))
        val legacy = assertNotNull(ObservedGripAssociationBridge().associateJson(input(singleHand())))
        val minimal = legacy.replace(",\"policyVersion\":1", "")
        assertNotEquals(legacy, minimal)
        assertEquals(1, WorkoutRecordCodec.json.decodeFromString<ObservedGripAssociation.Sample>(minimal).policyVersion)
    }
}
