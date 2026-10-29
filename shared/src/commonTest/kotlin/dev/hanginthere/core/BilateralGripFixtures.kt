package dev.hanginthere.core

import kotlin.math.cos
import kotlin.math.sin

internal object BilateralGripFixtures {
    fun curvedReference(): CurvedDipRailAcquisition.Reference {
        val pose = frame()
        val search = CurvedDipRailAcquisition().search(pose)!!
        val rails = search.hands.mapIndexed { side, hand ->
            val contours = listOf(60.0, 68.0).map { radius -> RailContour((0..60).map {
                val t = -.6 + it * 1.2 / 60
                Point2D(hand.x - 50 + radius * cos(t), 700 + radius * sin(t))
            }, true) }
            ObservedRailFitter.candidates(contours, search.regions[side], pose.imageSize, hand).maxBy { it.supportArcPixels }
        }
        return CurvedDipRailAcquisition.Reference.create(rails, search.hands, pose,
            CurvedDipRailAcquisition.Context("authored-body", 1, "authored-hand", "authored-hand-backend"), 0)
    }
    fun frame(ticks: Long = 0, leftDrop: Double = 0.0, rightDrop: Double = 0.0,
              handShift: Double = 0.0, elbows: Boolean = true, scoredHands: Boolean = true): PoseResult {
        val size = ImageSize(720.0, 1280.0)
        val points = listOf(200.0, 500.0).flatMapIndexed { i, x ->
            val joints = ArmMeasurement.Side.entries[i].joints
            listOf(Landmark(joints[0], Point2D(x, 200 + if (i == 0) leftDrop else rightDrop), .9),
                Landmark(joints[1], Point2D(x, 400.0), .9), Landmark(joints[2], Point2D(x, 600.0), .9))
        }.filter { elbows || it.joint !in listOf(PoseJoint.leftElbow, PoseJoint.rightElbow) }
        val body = points.filter { it.joint.name.endsWith("Shoulder") || it.joint.name.endsWith("Wrist") }.map {
            GripTrackedPoint(GripBodyJoint.valueOf(it.joint.name), it.position, it.confidence)
        }
        val hands = listOf(200.0 + handShift, 500.0).map { x ->
            GripHandObservation(listOf(GripHandJoint.indexMCP, GripHandJoint.middleMCP, GripHandJoint.pinkyMCP).mapIndexed { i, joint ->
                GripHandPoint(joint, Point2D(x + (i - 1) * 6, 640.0), score = .9.takeIf { scoredHands })
            })
        }
        val time = PresentationTime(ticks, 1000)
        return PoseResult(time, size, listOf(PoseObservation(points)), "authored-body", 1,
            GripObservation(time.seconds, size, body, emptyList(), hands, "authored-hand", "authored-hand-backend"))
    }
}
