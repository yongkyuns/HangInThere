package dev.hanginthere.core

import kotlin.test.*

class PersonInferenceRegionTests {
    @Test fun observedRectanglesIntersectOnlyRealPixelsWithoutChangingTheirRawBounds() {
        val size = ImageSize(640.0, 360.0)
        val cases = listOf(
            PersonInferenceRegion(-4, 20, 104, 200) to PersonInferenceRegion(0, 20, 100, 200),
            PersonInferenceRegion(20, -3, 200, 103) to PersonInferenceRegion(20, 0, 200, 100),
            PersonInferenceRegion(600, 20, 44, 200) to PersonInferenceRegion(600, 20, 40, 200),
            PersonInferenceRegion(20, 300, 200, 63) to PersonInferenceRegion(20, 300, 200, 60),
            PersonInferenceRegion(-4, -3, 648, 366) to PersonInferenceRegion(0, 0, 640, 360)
        )
        for ((raw, expected) in cases) {
            val before = raw.copy()
            assertFalse(raw.isValidFor(size))
            assertEquals(expected, raw.clippedTo(size))
            assertTrue(assertNotNull(raw.clippedTo(size)).isValidFor(size))
            assertEquals(before, raw)
        }
        val inside = PersonInferenceRegion(10, 20, 100, 200)
        assertSame(inside, inside.clippedTo(size))
    }

    @Test fun emptyOffscreenAndMalformedRectanglesDoNotProduceVisibleRegions() {
        val size = ImageSize(640.0, 360.0)
        for (raw in listOf(PersonInferenceRegion(0, 0, 0, 20), PersonInferenceRegion(0, 0, 20, -1),
            PersonInferenceRegion(-20, 0, 20, 20), PersonInferenceRegion(640, 0, 20, 20),
            PersonInferenceRegion(0, -20, 20, 20), PersonInferenceRegion(0, 360, 20, 20)))
            assertNull(raw.clippedTo(size))
        for (invalid in listOf(ImageSize(0.0, 360.0), ImageSize(640.5, 360.0),
            ImageSize(Double.NaN, 360.0), ImageSize(Double.POSITIVE_INFINITY, 360.0),
            ImageSize(Int.MAX_VALUE.toDouble() + 1, 360.0)))
            assertNull(PersonInferenceRegion(0, 0, 10, 10).clippedTo(invalid))
    }

    @Test fun visibleIntersectionDoesNotOverflowAtIntegerCoordinateLimits() {
        val size = ImageSize(Int.MAX_VALUE.toDouble(), 360.0)
        assertEquals(PersonInferenceRegion(Int.MAX_VALUE - 10, 20, 10, 200),
            PersonInferenceRegion(Int.MAX_VALUE - 10, 20, Int.MAX_VALUE, 200).clippedTo(size))
        assertNull(PersonInferenceRegion(Int.MIN_VALUE, 20, Int.MAX_VALUE, 200).clippedTo(size))
        assertNull(PersonInferenceRegion(0, Int.MIN_VALUE, 20, Int.MAX_VALUE).clippedTo(size))
    }

    @Test fun rectangleIntersectionDoesNotImplicitlyClampCropsOrRestoreRawCropCoordinates() {
        val size = ImageSize(640.0, 360.0)
        val raw = PersonInferenceRegion(-1, 0, 201, 300)
        val visible = assertNotNull(raw.clippedTo(size))
        val point = Landmark(PoseJoint.leftWrist, Point2D(20.0, 30.0), .8)
        val rawPrediction = source().copy(imageSize = ImageSize(201.0, 300.0),
            people = listOf(PoseObservation(listOf(point))))
        assertFailsWith<IllegalArgumentException> { raw.restore(rawPrediction, size) }
        assertFailsWith<IllegalArgumentException> { visible.restore(rawPrediction, size) }
        val actualCrop = rawPrediction.copy(imageSize = ImageSize(200.0, 300.0))
        assertEquals(point, visible.restore(actualCrop, size).people.single().landmarks.single())
    }

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
