package dev.hanginthere.core

import kotlin.test.*

class PersonInferenceRegionTests {
    private fun source(): PoseResult {
        val joints = listOf(PoseJoint.nose,PoseJoint.leftShoulder,PoseJoint.rightShoulder,
            PoseJoint.leftElbow,PoseJoint.rightElbow,PoseJoint.leftWrist,PoseJoint.rightWrist,
            PoseJoint.leftHip,PoseJoint.rightHip,PoseJoint.leftAnkle,PoseJoint.rightAnkle)
        val points = listOf(200 to 40,170 to 100,230 to 100,150 to 150,250 to 150,140 to 200,260 to 200,
            180 to 230,220 to 230,180 to 320,220 to 320)
        return PoseResult(PresentationTime(123,100),ImageSize(640.0,360.0),
            listOf(PoseObservation(joints.zip(points).map { (joint,point) -> Landmark(joint,Point2D(point.first.toDouble(),point.second.toDouble()),.8) })),"fixture",1)
    }
    @Test fun regionContainsCurrentAthleteAndClipsPaddingToRealPixels() {
        val pose = source(); val region = assertNotNull(PersonInferenceRegion.select(pose))
        assertEquals(PersonInferenceRegion(122,0,156,360),region)
        assertTrue(region.isValidFor(pose.imageSize))
        assertEquals(region,PersonInferenceRegion.select(pose.copy(people=pose.people.map { it.copy(landmarks=it.landmarks.reversed()) })))
    }
    @Test fun crowdsMissingTorsoAndInvalidGeometryCannotSupplyACrop() {
        val pose = source()
        assertNull(PersonInferenceRegion.select(pose.copy(people=emptyList())))
        assertNull(PersonInferenceRegion.select(pose.copy(people=pose.people+pose.people)))
        assertNull(PersonInferenceRegion.select(pose.copy(imageSize=ImageSize(640.5,360.0))))
        for (joint in listOf(PoseJoint.leftShoulder,PoseJoint.rightHip)) {
            assertNull(PersonInferenceRegion.select(pose.copy(people=pose.people.map { it.copy(landmarks=it.landmarks.filter { point -> point.joint != joint }) })))
        }
        val duplicate = pose.people.single().landmarks + pose.people.single().landmarks.first()
        assertNull(PersonInferenceRegion.select(pose.copy(people=listOf(PoseObservation(duplicate)))))
        val outside = pose.people.single().landmarks.map { if(it.joint==PoseJoint.leftHip) it.copy(position=Point2D(-1.0,230.0)) else it }
        assertNull(PersonInferenceRegion.select(pose.copy(people=listOf(PoseObservation(outside)))))
    }
    @Test fun restoringPreservesOriginalGeometryScoresTimestampAndCrowdEvidence() {
        val region = PersonInferenceRegion(100,40,200,300)
        val original = source()
        val point = Landmark(PoseJoint.leftWrist,Point2D(20.25,30.5),.3)
        val prediction = original.copy(imageSize=ImageSize(200.0,300.0),people=List(2){PoseObservation(listOf(point))})
        val restored = region.restore(prediction,original.imageSize)
        assertEquals(original.imageSize,restored.imageSize); assertEquals(original.timestamp,restored.timestamp)
        assertEquals(prediction.backend,restored.backend); assertEquals(prediction.requestRevision,restored.requestRevision)
        assertEquals(2,restored.people.size)
        restored.people.forEach { assertEquals(point.copy(position=Point2D(120.25,70.5)),it.landmarks.single()) }
        assertFailsWith<IllegalArgumentException> { region.restore(prediction,ImageSize(250.0,360.0)) }
        assertFailsWith<IllegalArgumentException> { region.restore(prediction.copy(imageSize=ImageSize(201.0,300.0)),original.imageSize) }
    }
    @Test fun outOfCropPredictionCannotBecomeUsableAfterTranslation() {
        val region = PersonInferenceRegion(100,40,200,300)
        val prediction = source().copy(imageSize=ImageSize(200.0,300.0),people=listOf(PoseObservation(listOf(
            Landmark(PoseJoint.leftShoulder,Point2D(20.0,30.0),1.0),
            Landmark(PoseJoint.leftElbow,Point2D(30.0,60.0),1.0),
            Landmark(PoseJoint.leftWrist,Point2D(-1.0,100.0),1.0)))))
        val restored = region.restore(prediction,source().imageSize)
        assertEquals(ArmMeasurement.UnavailableReason.missingJoint,ArmMeasurement(restored,ArmMeasurement.Side.left).unavailableReason)
        assertEquals(listOf(PoseJoint.leftShoulder,PoseJoint.leftElbow),restored.people.single().landmarks.map { it.joint })
    }
    @Test fun auxiliaryGripCoordinatesCannotSilentlySurviveABodyCropTransform() {
        val region = PersonInferenceRegion(100,40,200,300)
        val prediction = source().copy(imageSize=ImageSize(200.0,300.0),
            gripObservation=GripObservation(1.23,ImageSize(200.0,300.0),emptyList(),emptyList(),emptyList(),
                "authored grip fixture","fixture"))
        assertFailsWith<IllegalArgumentException> { region.restore(prediction,source().imageSize) }
    }
}
