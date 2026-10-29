package dev.hanginthere.core

import kotlin.math.abs
import kotlin.test.*

class BinaryGripClassifierTests {
    private fun observation(): GripObservation {
        val body = listOf(
            GripBodyPoint(GripBodyJoint.leftShoulder, Point2D(600.0, 300.0), GripVector3(.2, -.2, 0.0), .9),
            GripBodyPoint(GripBodyJoint.rightShoulder, Point2D(400.0, 300.0), GripVector3(-.2, -.2, 0.0), .9),
            GripBodyPoint(GripBodyJoint.leftHip, Point2D(600.0, 700.0), GripVector3(.2, .2, 0.0), .9),
            GripBodyPoint(GripBodyJoint.rightHip, Point2D(400.0, 700.0), GripVector3(-.2, .2, 0.0), .9),
            GripBodyPoint(GripBodyJoint.leftWrist, Point2D(700.0, 100.0), GripVector3(.3, -.4, 0.0), .9),
            GripBodyPoint(GripBodyJoint.rightWrist, Point2D(300.0, 100.0), GripVector3(-.3, -.4, 0.0), .9))
        val vectors = listOf(GripVector3(0.0,0.0,0.0), GripVector3(.04,-.06,0.0), GripVector3(0.0,-.066,0.0),
            GripVector3(-.04,-.06,0.0), GripVector3(.035,-.01,.01), GripVector3(.07,-.04,.01))
        val joints = listOf(GripHandJoint.wrist, GripHandJoint.indexMCP, GripHandJoint.middleMCP, GripHandJoint.pinkyMCP, GripHandJoint.thumbCMC, GripHandJoint.thumbTip)
        val hand = GripHandObservation(joints.zip(vectors).map { (j,v) -> GripHandPoint(j, Point2D(700.0,100.0), v) }, "left", .95)
        return GripObservation(1.0, ImageSize(1000.0,1000.0), body.map { GripTrackedPoint(it.joint,it.position,it.score) }, body,
            listOf(hand), "authored", "authored")
    }
    // Constant toy models exercise inference contracts only, never training or accuracy.
    private fun toy(bias: Double = 0.0) = BinaryGripClassifier.Model(labels=listOf("overhand","underhand"),
        mean=List(19){0.0}, scale=List(19){1.0}, hiddenWeights=listOf(List(19){0.0}), hiddenBias=listOf(0.0),
        outputWeights=listOf(0.0), outputBias=bias, acceptanceScore=.9, trainingSHA256="0".repeat(64))
    @Test fun outputOrderThresholdAndAbstentionAreExplicit() {
        val f = List(19){0.0}
        assertEquals(HandGrip.underhand, BinaryGripClassifier(toy(10.0)).classifyFeatures(f).grip)
        assertEquals(HandGrip.overhand, BinaryGripClassifier(toy(-10.0)).classifyFeatures(f).grip)
        assertEquals(HandGrip.unknown, BinaryGripClassifier(toy()).classifyFeatures(f).grip)
    }
    @Test fun malformedArtifactsCannotBecomeModels() {
        for (bad in listOf(toy().copy(labels=listOf("underhand","overhand")), toy().copy(scale=List(19){0.0}),
            toy().copy(outputBias=Double.POSITIVE_INFINITY), toy().copy(hiddenWeights=listOf(List(18){0.0})),
            toy().copy(qualification="release"), toy().copy(featureVersion=2), toy().copy(trainingSHA256="unbound"))) {
            assertFailsWith<IllegalArgumentException> { BinaryGripClassifier(bad) }
        }
    }
    @Test fun malformedAndOutOfRangeFeaturesAbstain() {
        val model=BinaryGripClassifier(toy(10.0))
        for (f in listOf(emptyList(), List(19){Double.NaN}, List(19){9.0}))
            assertEquals(HandGrip.unknown, model.classifyFeatures(f).grip)
    }
    @Test fun originalHandsAndBodyIdentityAreRequired() {
        val o=observation(); val side=ArmMeasurement.Side.left
        assertEquals(19, assertNotNull(BinaryGripFeatures.extract(o,side)).size)
        assertNull(BinaryGripFeatures.extract(o,ArmMeasurement.Side.right))
        assertNull(BinaryGripFeatures.extract(o.copy(hands=o.hands+o.hands),side))
        assertNull(BinaryGripFeatures.extract(o.copy(hands=o.hands.map { it.copy(points=it.points.dropLast(1)) }),side))
        assertNull(BinaryGripFeatures.extract(o.copy(hands=o.hands.map { it.copy(points=it.points.map { it.copy(world=null) }) }),side))
        assertNull(BinaryGripFeatures.extract(o.copy(hands=o.hands.map { it.copy(sdkHandedness="right") }),side))
        assertNull(BinaryGripFeatures.extract(o.copy(body=o.body.dropLast(1)),side))
        assertEquals(HandGrip.unknown, BinaryGripClassifier(toy(10.0)).classify(o.copy(hands=emptyList()),side).grip)
    }
    @Test fun cameraRotationAndSeparateWorldOriginsPreserveFeatures() {
        val o=observation();val expected=BinaryGripFeatures.extract(o,ArmMeasurement.Side.left)!!
        fun rotate(v:GripVector3)=GripVector3(v.z,v.y,-v.x)
        val changed=o.copy(body=o.body.map { it.copy(world=rotate(it.world)+GripVector3(1.0,2.0,3.0)) },
            hands=o.hands.map { h -> h.copy(points=h.points.map { it.copy(world=rotate(it.world!!)+GripVector3(-2.0,4.0,1.0)) }) })
        val actual=BinaryGripFeatures.extract(changed,ArmMeasurement.Side.left)!!
        expected.zip(actual).forEach { (a,b) -> assertTrue(abs(a-b)<1e-10) }
    }
    @Test fun reorderingPointsAndChangingImageScaleDoNotChangeFeatures() {
        val o=observation();val expected=BinaryGripFeatures.extract(o,ArmMeasurement.Side.left)
        fun p(v:Point2D)=Point2D(v.x*.5,v.y*.5)
        val changed=o.copy(imageSize=ImageSize(500.0,500.0),trackedBody=o.trackedBody.reversed().map { it.copy(position=p(it.position)) },
            body=o.body.reversed().map { it.copy(position=p(it.position)) },
            hands=o.hands.map { h -> h.copy(points=h.points.reversed().map { it.copy(position=p(it.position)) }) })
        assertEquals(expected,BinaryGripFeatures.extract(changed,ArmMeasurement.Side.left))
    }
}
