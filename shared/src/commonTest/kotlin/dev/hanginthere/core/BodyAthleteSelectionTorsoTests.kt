package dev.hanginthere.core

import kotlin.test.*

class BodyAthleteSelectionTorsoTests {
    private fun pose(ms: Long,span: Double=50.0,torso: Double=150.0,x: Double=500.0,
        hipFault: String?=null): PoseResult {
        val points=listOf(
            PoseJoint.nose to Point2D(x,180.0),
            PoseJoint.leftShoulder to Point2D(x-span/2,250.0),
            PoseJoint.rightShoulder to Point2D(x+span/2,250.0),
            PoseJoint.leftHip to Point2D(x-25,250+torso),
            PoseJoint.rightHip to Point2D(x+25,250+torso),
            PoseJoint.leftAnkle to Point2D(x-20,900.0),
            PoseJoint.rightAnkle to Point2D(x+20,900.0))
        val landmarks=points.map { (joint,p) -> Landmark(joint,
            if(hipFault=="collapsed" && joint==PoseJoint.rightHip)Point2D(x-25,250+torso) else p,
            if(hipFault=="confidence" && joint==PoseJoint.leftHip).1 else .9)
        }.filterNot { hipFault=="missing" && it.joint==PoseJoint.leftHip }
        return PoseResult(PresentationTime(ms,1000),ImageSize(1000.0,1000.0),listOf(PoseObservation(landmarks)),"authored",1)
    }
    private fun acquire(): BodyAthleteSelection = BodyAthleteSelection().also { owner ->
        for(ms in listOf(0L,200,400,600))owner.observe(pose(ms))
        assertEquals(BodyAthleteAssociation.Status.associated,owner.frame!!.decision.status)
    }
    @Test fun aMeasuredUprightTorsoKeepsTheSameSubjectWhenTurningAfterASideOnSetup() {
        val owner=acquire();val epoch=owner.frame!!.decision.sourceGeneration
        for(ms in 640L..1040L step 40) {
            val d=owner.observe(pose(ms,span=140.0))
            assertEquals(BodyAthleteAssociation.Status.associated,d.status)
            assertEquals(epoch,d.sourceGeneration)
            assertEquals(0,d.candidateIndex)
        }
    }
    @Test fun aRealScaleJumpOrUnmeasuredTorsoCannotExcuseTheShoulderScaleJump() {
        for(fault in listOf("zoom","missing","confidence","collapsed")) {
            val owner=acquire()
            assertNotEquals(BodyAthleteAssociation.Status.associated,owner.observe(pose(640,span=140.0,
                torso=if(fault=="zoom")450.0 else 150.0,hipFault=fault)).status,fault)
        }
    }
    @Test fun turningDoesNotGrowTheReferenceMovementEnvelope() {
        val owner=acquire()
        owner.observe(pose(640,span=140.0))
        assertNotEquals(BodyAthleteAssociation.Status.associated,
            owner.observe(pose(680,span=140.0,x=620.0)).status)
    }
}
