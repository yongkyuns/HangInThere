package dev.hanginthere.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.*
import kotlin.test.*

class EstimatedBodyGeometryTests {
    private fun pose(degrees: Double = 90.0, curl: Boolean = false,
                     transform: (Point3D) -> Point3D = { it }): PoseResult {
        val radians = (180 - degrees) * PI / 360
        val points = if (curl) listOf(Point3D(0.0, 0.0, 0.0), Point3D(0.0, .4, 0.0),
            Point3D(0.0, .4 + .4 * cos(PI - degrees * PI / 180), .4 * sin(PI - degrees * PI / 180)), Point3D(0.0, 1.0, 0.0))
        else listOf(Point3D(0.0, 0.0, 0.0), Point3D(0.0, .4 * cos(radians), .4 * sin(radians)),
            Point3D(0.0, .8 * cos(radians), 0.0), Point3D(0.0, 1.0, 0.0))
        val joints = listOf(PoseJoint.leftShoulder, PoseJoint.leftElbow, PoseJoint.leftWrist, PoseJoint.leftHip)
        return PoseResult(PresentationTime(123, 100), ImageSize(1000.0, 1000.0), listOf(PoseObservation(
            joints.map { Landmark(it, Point2D(500.0, 500.0), .9) },
            joints.zip(points).map { (joint, point) -> EstimatedWorldLandmark(joint, transform(point), .8) })),
            "authored world geometry", 1, estimatedWorldConvention = EstimatedBodyGeometry.convention)
    }
    private fun measure(pose: PoseResult) = EstimatedBodyGeometry.measure(pose, ArmMeasurement.Side.left)
    private fun editWorld(pose: PoseResult, edit: (List<EstimatedWorldLandmark>) -> List<EstimatedWorldLandmark>) =
        pose.copy(people = pose.people.map { it.copy(estimatedWorldLandmarks = edit(it.estimatedWorldLandmarks!!)) })
    private fun editImage(pose: PoseResult, edit: (List<Landmark>) -> List<Landmark>) =
        pose.copy(people = pose.people.map { it.copy(landmarks = edit(it.landmarks)) })

    @Test fun actualThreeDimensionalJointsSupplyTheDeclaredMeasurements() {
        val measurement = assertNotNull(measure(pose()))
        assertEquals(90.0, measurement.elbowDegrees, 1e-10)
        assertEquals(45.0, measurement.upperArmToTorsoDegrees, 1e-10)
        assertEquals(.8, measurement.armLength, 1e-10)
        assertEquals(1.0, measurement.torsoLength, 1e-10)
        assertEquals(1 - .8 * cos(PI / 4), measurement.hipToWristDistance, 1e-10)
        assertEquals(.8 * cos(PI / 4), measurement.shoulderToWristAlongTorso, 1e-10)
    }
    @Test fun radialDistanceAndElbowAngleDoNotProveADip() {
        val top = assertNotNull(measure(pose(170.0, curl = true)))
        val bottom = assertNotNull(measure(pose(60.0, curl = true)))
        // A curl satisfies both old experimental movement cues. Its upper arm
        // remains fixed relative to its torso; the primitive reports that fact.
        assertTrue(bottom.hipToWristDistance - top.hipToWristDistance > .18 * top.armLength)
        assertTrue(top.elbowDegrees - bottom.elbowDegrees > 30)
        assertEquals(0.0, top.upperArmToTorsoDegrees, 1e-10)
        assertEquals(top.upperArmToTorsoDegrees, bottom.upperArmToTorsoDegrees, 1e-10)
    }
    @Test fun commonRigidCameraCoordinateChangesPreserveGeometryNotModelAccuracy() {
        val original = assertNotNull(measure(pose()))
        val radians = .71
        val moved = assertNotNull(measure(pose(transform = {
            Point3D(3 + it.x * cos(radians) - it.y * sin(radians),
                -2 + it.x * sin(radians) + it.y * cos(radians), 8 + it.z)
        })))
        assertMeasurement(original, moved)
        assertMeasurement(original, assertNotNull(measure(pose(transform = { Point3D(-it.x, it.y, it.z) }))))
    }
    private fun assertMeasurement(expected: EstimatedBodyGeometry.Measurement, actual: EstimatedBodyGeometry.Measurement) {
        assertEquals(expected.elbowDegrees, actual.elbowDegrees, 1e-10)
        assertEquals(expected.upperArmToTorsoDegrees, actual.upperArmToTorsoDegrees, 1e-10)
        assertEquals(expected.armLength, actual.armLength, 1e-10)
        assertEquals(expected.torsoLength, actual.torsoLength, 1e-10)
        assertEquals(expected.hipToWristDistance, actual.hipToWristDistance, 1e-10)
        assertEquals(expected.shoulderToWristAlongTorso, actual.shoulderToWristAlongTorso, 1e-10)
    }
    @Test fun scaleChangesLengthsWithoutChangingAngles() {
        val original = assertNotNull(measure(pose()))
        val scaled = assertNotNull(measure(pose(transform = { Point3D(it.x * 2, it.y * 2, it.z * 2) })))
        assertEquals(original.elbowDegrees, scaled.elbowDegrees, 1e-10)
        assertEquals(original.upperArmToTorsoDegrees, scaled.upperArmToTorsoDegrees, 1e-10)
        assertEquals(original.hipToWristDistance * 2, scaled.hipToWristDistance, 1e-10)
    }
    @Test fun finiteLargeCoordinatesCannotOverflowAngleIntermediates() {
        val original = assertNotNull(measure(pose()))
        val large = assertNotNull(measure(pose(transform = { Point3D(it.x * 1e100, it.y * 1e100, it.z * 1e100) })))
        assertEquals(original.elbowDegrees, large.elbowDegrees, 1e-10)
        assertEquals(original.upperArmToTorsoDegrees, large.upperArmToTorsoDegrees, 1e-10)
    }
    @Test fun oldPoseJsonRemainsTwoDimensionalAndDoesNotInventDepth() {
        val old = """{"timestamp":{"value":123,"timescale":100},"imageSize":{"width":1000,"height":1000},"people":[{"landmarks":[]}],"backend":"old Vision2D","requestRevision":1}"""
        val decoded = Json.decodeFromString<PoseResult>(old)
        assertNull(decoded.estimatedWorldConvention)
        assertNull(decoded.people.single().estimatedWorldLandmarks)
        assertNull(measure(decoded))
        assertEquals(decoded, Json.decodeFromString<PoseResult>(Json.encodeToString(decoded)))
    }
    @Test fun serializationPreservesSamePersonImageAndEstimatedWorldData() {
        val source = pose()
        val decoded = Json.decodeFromString<PoseResult>(Json.encodeToString(source))
        assertEquals(source, decoded)
        assertMeasurement(assertNotNull(measure(source)), assertNotNull(measure(decoded)))
    }
    @Test fun depthRequiresAnExplicitSupportedCoordinateConvention() {
        for (convention in listOf(null, "unknown", "imageNormalizedDepth", "cameraRightDownAway_hipOrigin_v2"))
            assertNull(measure(pose().copy(estimatedWorldConvention = convention)))
        assertNull(measure(pose().copy(people = pose().people.map { it.copy(estimatedWorldLandmarks = null) })))
    }
    @Test fun noCrowdOrOppositeSideFallback() {
        val source = pose()
        assertNull(measure(source.copy(people = emptyList())))
        assertNull(measure(source.copy(people = source.people + source.people)))
        assertNull(EstimatedBodyGeometry.measure(source, ArmMeasurement.Side.right))
    }
    @Test fun missingOrDuplicateJointsCannotSupplyAGeometryFrame() {
        for (joint in listOf(PoseJoint.leftShoulder, PoseJoint.leftElbow, PoseJoint.leftWrist, PoseJoint.leftHip)) {
            assertNull(measure(editWorld(pose()) { points -> points.filter { it.joint != joint } }))
            assertNull(measure(editImage(pose()) { points -> points.filter { it.joint != joint } }))
        }
        assertNull(measure(editWorld(pose()) { it + it.first() }))
        assertNull(measure(editImage(pose()) { it + it.first() }))
    }
    @Test fun rawScoresAreBoundedSdkVisibilityGates() {
        for (score in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.1, 0.0, .299, 1.01)) {
            assertNull(measure(editWorld(pose()) { points -> points.map { it.copy(confidence = score) } }))
            assertNull(measure(editImage(pose()) { points -> points.map { it.copy(confidence = score) } }))
        }
        assertNotNull(measure(editWorld(pose()) { points -> points.map { it.copy(confidence = .3) } }))
    }
    @Test fun finiteCoordinatesAndVisibleImageEvidenceAreBothRequired() {
        for (point in listOf(Point3D(Double.NaN, 0.0, 0.0), Point3D(0.0, Double.POSITIVE_INFINITY, 0.0), Point3D(0.0, 0.0, Double.NEGATIVE_INFINITY)))
            assertNull(measure(editWorld(pose()) { points -> points.map { it.copy(position = point) } }))
        for (point in listOf(Point2D(-1.0, 500.0), Point2D(500.0, 1001.0), Point2D(Double.NaN, 0.0)))
            assertNull(measure(editImage(pose()) { points -> points.map { it.copy(position = point) } }))
        assertMeasurement(assertNotNull(measure(pose())), assertNotNull(measure(editImage(pose()) { points -> points.map { it.copy(position = Point2D(123.0, 456.0)) } })))
    }
    @Test fun degenerateOverflowAndInvalidFrameGeometryAreUnavailable() {
        assertNull(measure(pose(transform = { Point3D(0.0, 0.0, 0.0) })))
        assertNull(measure(pose(transform = { Point3D(it.x * 1e308, it.y * 1e308, it.z * 1e308) })))
        assertNull(measure(pose().copy(imageSize = ImageSize(Double.NaN, 1000.0))))
        assertNull(measure(pose().copy(timestamp = PresentationTime(123, 0))))
    }
    @Test fun pixelCropRestorationDoesNotInventACameraOrDepthTransform() {
        val source = pose()
        val region = PersonInferenceRegion(100, 100, 800, 800)
        val crop = source.copy(imageSize = ImageSize(800.0, 800.0))
        val restored = region.restore(crop, source.imageSize)
        assertNull(restored.estimatedWorldConvention)
        assertNull(restored.people.single().estimatedWorldLandmarks)
        assertNull(measure(restored))
        assertNotNull(crop.people.single().estimatedWorldLandmarks)
    }
}
