package dev.hanginthere.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.test.*

class DipRailSearchGeometryTests {
    private val size = ImageSize(960.0, 540.0)
    // Actual dip_side source frame85 (PTS127628/90000), native full-image
    // independently observed MCP centre and original Lite arm measurement.
    // PNG SHA256: 7826dfb68c99a8487454139f3495b2f6b94cae9b257da6da5d967a7c33b7ddc5.
    private val shoulder = Point2D(580.6682968139648, 133.98951262235641)
    private val grip = Point2D(724.1508674621582, 232.88894534111023)
    private val upperArm = 181.8327173753968
    private fun original() = assertNotNull(AutomaticDipRails.contactRegion(shoulder, grip, size))
    private fun region(radius: Double? = upperArm) = assertNotNull(DipRailSearchGeometry.region(shoulder, grip, size, radius))

    @Test fun occludingHandCannotTruncateTheOutwardSourceRailBelowMinimumArc() {
        // Five actual fitted source vertices; no chord/extrapolation or image
        // pixels are included in this portable regression. Geometry alone does
        // not identify apparatus or establish physical contact/polarity.
        val paths = listOf(
            listOf(Point2D(781.5, 269.5), Point2D(786.5, 270.5), Point2D(901.3967838801681, 271.4820237938476)),
            listOf(Point2D(901.6873432894039, 237.48657291325483), Point2D(784.4259915660471, 236.61797030789666)))
        fun integral(r: BarRegion) = BarRegion(floor(r.minX), floor(r.minY), ceil(r.maxX), ceil(r.maxY))
        val narrow = integral(original()); val context = integral(region())
        assertEquals(789.0, narrow.maxX)
        assertTrue(paths.flatten().all(context::contains))
        // Exact finite intersections with the old crop's right edge. These
        // paths are monotone in x, so clipping cannot join disjoint support.
        fun clip(points: List<Point2D>): List<Point2D> = points.zipWithNext().flatMap { (a, b) ->
            fun intersection(): Point2D {
                val t = (narrow.maxX - a.x) / (b.x - a.x)
                return Point2D(narrow.maxX, a.y + t * (b.y - a.y))
            }
            when {
                a.x <= narrow.maxX && b.x <= narrow.maxX -> listOf(a, b)
                a.x <= narrow.maxX -> listOf(a, intersection())
                b.x <= narrow.maxX -> listOf(intersection(), b)
                else -> emptyList()
            }
        }.distinct()
        val clipped = paths.map(::clip)
        val lengths = clipped.map { p -> p.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) } }
        assertEquals(7.599110826119067, lengths[0], 1e-9)
        assertEquals(4.5741339194314605, lengths[1], 1e-9)
        assertTrue(lengths.all { it < AutomaticDipRails.minimumPairLength(size) })
        fun fit(p: List<List<Point2D>>, r: BarRegion) = ObservedRailFitter.candidates(
            p.map { RailContour(it, true) }, r, size, grip, ObservedRailFitter.Mode.finiteEdges,
            ObservedRailFitter.ContactRegion.aroundGrip)
        assertEquals(1, fit(paths, context).size)
        assertTrue(fit(clipped, narrow).isEmpty())
        // The current bilateral consumer additionally prunes by grip height.
        // A recovered geometric proposal does not imply acquisition succeeds.
        assertTrue(ObservedRailFitter.candidates(paths.map { RailContour(it, true) }, context, size, grip,
            ObservedRailFitter.Mode.finiteEdges).isEmpty())
    }

    @Test fun outwardContextPreservesBodyExclusionVerticalExtentAndOriginalSearch() {
        val old = original(); val next = region()
        assertEquals(old.minX, next.minX)
        assertEquals(old.minY, next.minY)
        assertEquals(old.maxY, next.maxY)
        assertEquals(grip.x + upperArm, next.maxX)
        assertTrue(next.contains(Point2D(old.minX, old.minY)) && next.contains(Point2D(old.maxX, old.maxY)))
        assertEquals(old, region(1.0))
    }

    @Test fun mirroringChangesOnlyTheCorrespondingOutwardEdge() {
        fun mirror(p: Point2D) = Point2D(size.width - p.x, p.y)
        val a = region()
        val b = assertNotNull(DipRailSearchGeometry.region(mirror(shoulder), mirror(grip), size, upperArm))
        assertEquals(size.width - a.maxX, b.minX, 1e-12)
        assertEquals(size.width - a.minX, b.maxX, 1e-12)
        assertEquals(a.minY, b.minY); assertEquals(a.maxY, b.maxY)
    }

    @Test fun missingArmLengthOrUnresolvedOutwardDirectionRetainsTheOriginalWindow() {
        assertEquals(original(), region(null))
        val aligned = Point2D(grip.x, shoulder.y)
        assertEquals(AutomaticDipRails.contactRegion(aligned, grip, size),
            DipRailSearchGeometry.region(aligned, grip, size, upperArm))
    }

    @Test fun contextRemainsInsideActualSourcePixelsAndScalesWithTheMeasuredArm() {
        val edgeGrip = Point2D(940.0, grip.y)
        assertEquals(size.width, assertNotNull(DipRailSearchGeometry.region(shoulder, edgeGrip, size, upperArm)).maxX)
        val factor = 2.0
        fun scaled(p: Point2D) = Point2D(p.x * factor, p.y * factor)
        val next = assertNotNull(DipRailSearchGeometry.region(scaled(shoulder), scaled(grip),
            ImageSize(size.width * factor, size.height * factor), upperArm * factor))
        val before = region()
        assertEquals(before.copy(minX = before.minX * factor, minY = before.minY * factor,
            maxX = before.maxX * factor, maxY = before.maxY * factor), next)
    }

    @Test fun malformedGeometryCannotRequestAnExpandedCrop() {
        for (radius in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0))
            assertNull(DipRailSearchGeometry.region(shoulder, grip, size, radius))
        for (point in listOf(Point2D(Double.NaN, grip.y), Point2D(-1.0, grip.y), Point2D(grip.x, 541.0))) {
            assertNull(DipRailSearchGeometry.region(point, grip, size, upperArm))
            assertNull(DipRailSearchGeometry.region(shoulder, point, size, upperArm))
        }
        assertNull(DipRailSearchGeometry.region(shoulder, grip, ImageSize(0.0, 0.0), upperArm))
    }

    @Test fun acquisitionAndAssociationTransportsUseTheSameOutwardContextWithoutChangingThePose() {
        fun frame(elbows: Boolean): PoseResult {
            val original = BilateralGripFixtures.frame(elbows = elbows)
            val points = original.people.single().landmarks.map { point -> when (point.joint) {
                PoseJoint.leftShoulder -> point.copy(position = Point2D(150.0, 200.0))
                PoseJoint.rightShoulder -> point.copy(position = Point2D(550.0, 200.0))
                else -> point
            } }
            val hand = original.gripObservation!!
            return original.copy(people = listOf(PoseObservation(points)), gripObservation = hand.copy(
                trackedBody = hand.trackedBody.map { tracked ->
                    tracked.copy(position = points.single { it.joint.name == tracked.joint.name }.position)
                }))
        }
        val pose = frame(true)
        val before = WorkoutRecordCodec.json.encodeToString(pose)
        val acquired = assertNotNull(CurvedDipRailAcquisition().search(pose))
        val input = WorkoutRecordCodec.json.encodeToString(ObservedGripAssociation.Frame(pose, pose.timestamp))
        val exported = WorkoutRecordCodec.json.parseToJsonElement(assertNotNull(
            ObservedGripAssociationBridge().dipSearchJson(input))).jsonObject
        assertEquals(2, exported.getValue("searchPolicyVersion").jsonPrimitive.int)
        assertEquals(acquired.regions, WorkoutRecordCodec.json.decodeFromJsonElement<List<BarRegion>>(exported.getValue("regions")))
        assertTrue(acquired.regions[0].maxX > 400 && acquired.regions[1].minX < 300)
        assertEquals(before, WorkoutRecordCodec.json.encodeToString(pose))
        val withoutElbows = frame(false)
        val missing = assertNotNull(CurvedDipRailAcquisition().search(withoutElbows))
        assertEquals(listOf(286.4, 525.0), missing.regions.map { it.maxX })
        assertEquals(listOf(175.0, 413.6), missing.regions.map { it.minX })
        val hand = pose.gripObservation!!
        val asymmetric = pose.copy(people = listOf(PoseObservation(pose.people.single().landmarks.map {
            if (it.joint == PoseJoint.rightElbow) it.copy(position = Point2D(500.0, 500.0)) else it
        })))
        val asymmetricSearch = assertNotNull(CurvedDipRailAcquisition().search(asymmetric))
        assertEquals(acquired.regions[0], asymmetricSearch.regions[0])
        assertTrue(asymmetricSearch.regions[1].minX < acquired.regions[1].minX - 90)
        assertEquals(asymmetricSearch, CurvedDipRailAcquisition().search(asymmetric.copy(
            gripObservation = hand.copy(hands = hand.hands.reversed()))))
        for (fault in listOf(Landmark(PoseJoint.leftElbow, Point2D(200.0, 400.0), .1),
            Landmark(PoseJoint.leftElbow, Point2D(Double.NaN, 400.0), .9))) {
            val bad = pose.copy(people = listOf(PoseObservation(pose.people.single().landmarks.map {
                if (it.joint == PoseJoint.leftElbow) fault else it
            })))
            assertEquals(missing.regions[0], assertNotNull(CurvedDipRailAcquisition().search(bad)).regions[0])
            assertNull(ObservedGripAssociation.associate(ObservedGripAssociation.Frame(bad, bad.timestamp)))
        }
    }
}
