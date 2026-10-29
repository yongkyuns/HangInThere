package dev.hanginthere.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class WorkoutGripCaptureTests {
    private fun pose(span: Double = .24, torso: Double = .38, time: Double = 1.0): PoseResult {
        val body = listOf(
            GripBodyPoint(GripBodyJoint.leftShoulder,Point2D(500+span*500,400.0),GripVector3(.2,-.2,0.0),.9),
            GripBodyPoint(GripBodyJoint.rightShoulder,Point2D(500-span*500,400.0),GripVector3(-.2,-.2,0.0),.9),
            GripBodyPoint(GripBodyJoint.leftHip,Point2D(550.0,400+torso*1000),GripVector3(.2,.2,0.0),.9),
            GripBodyPoint(GripBodyJoint.rightHip,Point2D(450.0,400+torso*1000),GripVector3(-.2,.2,0.0),.9),
            GripBodyPoint(GripBodyJoint.leftWrist,Point2D(700.0,150.0),GripVector3(.3,-.4,0.0),.9),
            GripBodyPoint(GripBodyJoint.rightWrist,Point2D(300.0,150.0),GripVector3(-.3,-.4,0.0),.9))
        val tracked = body.map { GripTrackedPoint(it.joint,it.position,it.score) }
        fun hand(side: ArmMeasurement.Side): GripHandObservation {
            val x = if(side == ArmMeasurement.Side.left) 700.0 else 300.0
            return GripHandObservation(listOf(
                GripHandPoint(GripHandJoint.wrist,Point2D(x,150.0)),
                GripHandPoint(GripHandJoint.indexMCP,Point2D(x-12,130.0)),
                GripHandPoint(GripHandJoint.middleMCP,Point2D(x,125.0)),
                GripHandPoint(GripHandJoint.pinkyMCP,Point2D(x+12,130.0))), side.name,.95)
        }
        val observation = GripObservation(time,ImageSize(1000.0,1000.0),tracked,body,
            ArmMeasurement.Side.entries.map(::hand),"test","authored")
        return PoseResult(PresentationTime((time*1000).toLong(),1000),observation.imageSize,
            listOf(PoseObservation(tracked.map { Landmark(PoseJoint.valueOf(it.joint.name),it.position,it.score) })),
            "authored",1,observation)
    }
    private fun raw(pose: PoseResult) = WorkoutGripCapture.assess(pose)
    @Test fun bodyScaleWorksEvenWhenHandInferenceHasNoResult() {
        val p = pose(.12,.22).copy(gripObservation = null)
        val result = raw(p)
        assertEquals("moveCloser",result.state)
        assertEquals(.12,result.shoulderFraction!!,1e-10)
        assertEquals(.22,result.torsoFraction!!,1e-10)
        assertEquals("waiting",result.leftHand)
    }
    @Test fun shoulderForeshorteningOrTorsoTuckAloneDoesNotMeanTooFar() {
        assertNotEquals("moveCloser",raw(pose(.12,.38)).state)
        assertNotEquals("moveCloser",raw(pose(.25,.2)).state)
        assertEquals("handsDetected",raw(pose()).state)
        // Raising/lowering wrists cannot change the distance proxy.
        val p = pose(.12,.22)
        val raised = p.copy(people = p.people.map { person -> person.copy(landmarks = person.landmarks.map {
            if(it.joint in setOf(PoseJoint.leftWrist,PoseJoint.rightWrist)) it.copy(position=it.position.copy(y=70.0)) else it
        }) })
        assertEquals("moveCloser",raw(raised).state)
    }
    @Test fun missingHandsAndMissingBodyHaveDifferentAdvice() {
        val p = pose()
        assertEquals("showHands",raw(p.copy(gripObservation=p.gripObservation!!.copy(hands=emptyList()))).state)
        assertEquals("checking",raw(p.copy(gripObservation=null)).state)
        val hips = setOf(PoseJoint.leftHip,PoseJoint.rightHip)
        assertEquals("showUpperBody",raw(p.copy(gripObservation=null,people=p.people.map {
            it.copy(landmarks=it.landmarks.filter { l -> l.joint !in hips })
        })).state)
        val weakPrimaryHips = p.copy(people=p.people.map { it.copy(landmarks=it.landmarks.map { l ->
            if(l.joint in hips) l.copy(confidence=.1) else l
        }) })
        assertEquals("handsDetected",raw(weakPrimaryHips).state) // Valid associated auxiliary hips remain usable.
    }
    @Test fun staleFutureForeignAndFailedInferenceNeverShowsHandsDetected() {
        val p = pose();val o = p.gripObservation!!
        for(invalid in listOf(o.copy(seconds=0.0),o.copy(seconds=2.0),o.copy(failure="gripInferenceUnavailable"),
            o.copy(imageSize=ImageSize(720.0,1280.0)),o.copy(trackedBody=o.trackedBody.map {
                it.copy(position=Point2D(100.0,100.0))
            }))) assertNotEquals("handsDetected",raw(p.copy(gripObservation=invalid)).state)
        assertEquals("checking",raw(p.copy(gripObservation=o.copy(seconds=0.0))).state)
        assertEquals("staleObservation",raw(p.copy(gripObservation=o.copy(seconds=0.0))).bodyContext)
        assertEquals("analysisUnavailable",raw(p.copy(gripObservation=o.copy(failure="gripInferenceUnavailable"))).state)
    }
    @Test fun obliqueViewIsNotDiagnosedAsDistance() {
        val p = pose(.12,.22)
        val o = p.gripObservation!!
        val side = o.copy(body=o.body.map { it.copy(world=GripVector3(0.0,it.world.y,-it.world.x)) })
        assertEquals("frontRear",raw(p.copy(gripObservation=side)).state)
    }
    @Test fun handIdentityClippingAndMissingJointsCannotProduceGreen() {
        val p = pose();val o = p.gripObservation!!
        val bad = o.copy(hands=o.hands.map { it.copy(points=it.points.take(1)) })
        assertEquals("showHands",raw(p.copy(gripObservation=bad)).state)
        assertEquals("showHands",raw(p.copy(gripObservation=o.copy(hands=o.hands+o.hands))).state)
        val clipped = p.copy(people=p.people.map { it.copy(landmarks=it.landmarks.map { l ->
            if(l.joint==PoseJoint.leftWrist) l.copy(position=Point2D(700.0,2.0)) else l
        }) })
        assertEquals("handsClipped",raw(clipped).state)
        assertEquals("edge",raw(clipped).leftHand)
    }
    @Test fun resolutionDoesNotChangeBodyScale() {
        val p = pose(.12,.22)
        for(scale in listOf(.5,2.0)) {
            val changed = p.copy(imageSize=ImageSize(1000*scale,1000*scale),gripObservation=null,
                people=p.people.map { person -> person.copy(landmarks=person.landmarks.map {
                    it.copy(position=Point2D(it.position.x*scale,it.position.y*scale))
                }) })
            assertEquals(raw(p).shoulderFraction!!,raw(changed).shoulderFraction!!,1e-10)
            assertEquals("moveCloser",raw(changed).state)
        }
    }
    @Test fun noPersonMultipleAndInvalidValuesDoNotBecomeDistanceWarnings() {
        val p = pose()
        assertEquals("stepIntoFrame",raw(p.copy(people=emptyList())).state)
        assertEquals("oneAthlete",raw(p.copy(people=p.people+p.people)).state)
        assertEquals("unavailable",raw(p.copy(imageSize=ImageSize(Double.NaN,1000.0))).state)
        val duplicate = p.copy(people=p.people.map { it.copy(landmarks=it.landmarks+it.landmarks.first()) })
        assertEquals("unavailable",raw(duplicate).state)
    }
    @Test fun bridgeDebouncesRecoveryAndClearsGreenOnLostHandEvidence() {
        val bridge = WorkoutGripCaptureBridge()
        fun observe(p: PoseResult) = WorkoutRecordCodec.json.decodeFromString<GripCaptureAssessment>(
            bridge.observeJson(WorkoutRecordCodec.json.encodeToString(p)))
        assertEquals("moveCloser",observe(pose(.12,.22,time=1.0)).state)
        assertEquals("moveCloser",observe(pose(time=1.1)).state)
        assertEquals("moveCloser",observe(pose(time=1.3)).state)
        assertEquals("handsDetected",observe(pose(time=1.6)).state)
        assertEquals("checking",observe(pose(time=1.7).copy(gripObservation=null)).state)
        bridge.reset()
        assertEquals("handsDetected",observe(pose(time=2.0)).state)
        assertEquals("showHands",observe(pose(time=2.1).let { it.copy(gripObservation=it.gripObservation!!.copy(hands=emptyList())) }).state)
    }
}
