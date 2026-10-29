package dev.hanginthere.core

import kotlin.math.*
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class MeasurementTests {
    @Test fun originalAnalyticChainsHaveExpectedImagePlaneAngles() {
        for (side in ArmMeasurement.Side.entries) for (degrees in listOf(70.0, 100.0, 140.0, 170.0)) {
            val m = ArmMeasurement(CounterFixtures.pose(0.0, degrees, side = side), side)
            assertNull(m.unavailableReason)
            assertEquals(degrees, assertNotNull(m.estimate).elbowDegrees, 1e-9)
            assertEquals(120.0, m.estimate?.upperArmPixels ?: 0.0, 1e-9)
            assertEquals(120.0, m.estimate?.forearmPixels ?: 0.0, 1e-9)
        }
    }
    @Test fun malformedChainsCannotProduceAngles() {
        val base = CounterFixtures.pose(0.0)
        val people = base.people
        fun reason(pose: PoseResult) = ArmMeasurement(pose, ArmMeasurement.Side.left).unavailableReason
        assertEquals(ArmMeasurement.UnavailableReason.noPerson, reason(base.copy(people = emptyList())))
        assertEquals(ArmMeasurement.UnavailableReason.multiplePeople, reason(base.copy(people = people + people)))
        assertEquals(ArmMeasurement.UnavailableReason.invalidImageSize, reason(base.copy(imageSize = ImageSize(-1.0, 1.0))))
        assertEquals(ArmMeasurement.UnavailableReason.missingJoint, reason(base.copy(people = listOf(PoseObservation(people[0].landmarks.drop(1))))))
        assertEquals(ArmMeasurement.UnavailableReason.duplicateJoint, reason(base.copy(people = listOf(PoseObservation(people[0].landmarks + people[0].landmarks[0])))))
        assertEquals(ArmMeasurement.UnavailableReason.lowConfidence, reason(CounterFixtures.pose(0.0, confidence = 0.1)))
        for (confidence in listOf(-1.0, 1.1, Double.NaN))
            assertEquals(ArmMeasurement.UnavailableReason.invalidJoint, reason(CounterFixtures.pose(0.0, confidence = confidence)))
    }
    @Test fun outOfImageAndNonFiniteJointsAreRejected() {
        val base = CounterFixtures.pose(0.0)
        for (point in listOf(Point2D(-1.0, 0.0), Point2D(0.0, 1001.0), Point2D(Double.NaN, 1.0))) {
            val landmarks = base.people[0].landmarks.mapIndexed { i, it -> if (i == 0) it.copy(position = point) else it }
            val m = ArmMeasurement(base.copy(people = listOf(PoseObservation(landmarks))), ArmMeasurement.Side.left)
            assertEquals(ArmMeasurement.UnavailableReason.invalidJoint, m.unavailableReason)
        }
    }
    @Test fun segmentFloorScalesWithImage() {
        val base = CounterFixtures.pose(0.0, 140.0)
        for (scale in listOf(0.5, 1.0, 2.0)) {
            val pose = base.copy(imageSize = ImageSize(1000 * scale, 1000 * scale), people = base.people.map { person ->
                PoseObservation(person.landmarks.map { it.copy(position = Point2D(it.position.x * scale, it.position.y * scale)) })
            })
            assertEquals(140.0, assertNotNull(ArmMeasurement(pose, ArmMeasurement.Side.left).estimate).elbowDegrees, 1e-9)
        }
        val short = base.copy(people = listOf(PoseObservation(base.people[0].landmarks.map { it.copy(position = Point2D(500 + (it.position.x - 500) * 0.01,
            500 + (it.position.y - 500) * 0.01)) })))
        assertEquals(ArmMeasurement.UnavailableReason.shortProjectedSegment, ArmMeasurement(short, ArmMeasurement.Side.left).unavailableReason)
    }
    @Test fun landmarkVisibilityDoesNotTreatConfidenceAsProbability() {
        for (value in listOf(0.0, -1.0, 1.1, Double.NaN, 0.29))
            assertFalse(Landmark(PoseJoint.nose, Point2D(0.0, 0.0), value).isVisible())
        assertTrue(Landmark(PoseJoint.nose, Point2D(0.0, 0.0), 0.3).isVisible())
    }
    @Test fun canonicalModelsRoundTripSwiftCompatibleJSON() {
        val pose = CounterFixtures.bilateral(1.05, 80.0, 170.0)
        val decoded = Json.decodeFromString<PoseResult>(Json.encodeToString(pose))
        assertEquals(pose, decoded)
        val json = Json.encodeToString(pose)
        assertTrue(json.contains("\"leftShoulder\"")); assertTrue(json.contains("\"timescale\":1000"))
    }
    @Test fun presentationTimeRejectsInvalidTimescale() {
        assertTrue(PresentationTime(1, 0).seconds.isNaN()); assertTrue(PresentationTime(1, -1).seconds.isNaN())
        assertEquals(1.25, PresentationTime(1250, 1000).seconds)
    }
    @Test fun visionCoordinateConversionUsesUprightTopLeftPixels() {
        assertEquals(Point2D(100.0, 150.0), PoseGeometry.visionPoint(Point2D(0.25, 0.75), ImageSize(400.0, 600.0)))
        assertNull(PoseGeometry.visionPoint(Point2D(1.01, 0.0), ImageSize(400.0, 600.0)))
        assertNull(PoseGeometry.elbowAngle(Point2D(0.0, 0.0), Point2D(0.0, 0.0), Point2D(1.0, 1.0)))
    }
    @Test fun aspectFitInverseIncludesLetterboxing() {
        val fit = assertNotNull(AspectFit.create(ImageSize(1000.0, 500.0), ImageSize(400.0, 400.0)))
        assertEquals(0.4, fit.scale); assertEquals(Point2D(0.0, 100.0), fit.offset)
        val point = Point2D(50.0, 400.0)
        assertEquals(point, fit.imagePoint(fit.displayPoint(point)))
        assertNull(AspectFit.create(ImageSize(0.0, 1.0), ImageSize(1.0, 1.0)))
    }
    @Test fun trackRotationUsesIndependentTopLeftAndBottomLeftTransforms() {
        val geometry = assertNotNull(OrientedGeometry.create(ImageSize(1920.0, 1080.0), Affine2D(0.0, 1.0, -1.0, 0.0, 1080.0, 0.0)))
        assertEquals(ImageSize(1080.0, 1920.0), geometry.outputSize)
        assertEquals(Point2D(980.0, 50.0), geometry.topLeftTransform.apply(Point2D(50.0, 100.0)))
        assertEquals(Point2D(980.0, 1870.0), geometry.coreImageTransform.apply(Point2D(50.0, 980.0)))
        assertNull(OrientedGeometry.create(ImageSize(1.0, 1.0), Affine2D(0.0, 0.0, 0.0, 0.0)))
    }
    @Test fun dipSupportRequiresSelectedWristNearRealRail() {
        val p = CounterFixtures.pose(0.0, 140.0, ExerciseCounter.Exercise.dip)
        val support = assertNotNull(DipSupportMeasurement.create(p, ArmMeasurement.Side.left, CounterFixtures.edge))
        assertTrue(support.isStartingSupport)
        assertNull(DipSupportMeasurement.create(p, ArmMeasurement.Side.left, BarSegment(Point2D(0.0, 10.0), Point2D(1000.0, 10.0))))
        assertFalse(assertNotNull(DipSupportMeasurement.create(CounterFixtures.pose(0.0, 70.0, ExerciseCounter.Exercise.dip),
            ArmMeasurement.Side.left, CounterFixtures.edge)).isStartingSupport)
    }
    @Test fun dipAxisIsFrozenToSupportAndIndependentOfRailDirection() {
        val p = CounterFixtures.pose(0.0, 170.0, ExerciseCounter.Exercise.dip)
        val support = assertNotNull(DipSupportMeasurement.create(p, ArmMeasurement.Side.left, CounterFixtures.edge))
        val axis = assertNotNull(DipSupportMeasurement.Anchor.create(support))
        val lower = assertNotNull(DipSupportMeasurement.create(CounterFixtures.pose(0.3, 80.0, ExerciseCounter.Exercise.dip),
            ArmMeasurement.Side.left, CounterFixtures.edge))
        assertTrue(axis.height(lower.shoulder) < axis.height(support.shoulder))
        assertTrue(axis.retainsGrip(lower)); assertFalse(axis.isStableStart(lower))
        val shifted = assertNotNull(DipSupportMeasurement.create(CounterFixtures.pose(0.6, 170.0, ExerciseCounter.Exercise.dip,
            verticalOffset = 40.0), ArmMeasurement.Side.left, CounterFixtures.edge))
        assertFalse(axis.retainsGrip(shifted))
    }
}
