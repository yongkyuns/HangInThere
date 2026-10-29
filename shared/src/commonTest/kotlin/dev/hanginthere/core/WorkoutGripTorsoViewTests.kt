package dev.hanginthere.core

import kotlin.test.*

class WorkoutGripTorsoViewTests {
    private fun observation(hips: Boolean = false): GripObservation {
        val body = mutableListOf(
            GripBodyPoint(GripBodyJoint.leftShoulder, Point2D(600.0,300.0), GripVector3(.2,-.2,0.0),.9),
            GripBodyPoint(GripBodyJoint.rightShoulder, Point2D(400.0,300.0), GripVector3(-.2,-.2,0.0),.9))
        if (hips) body += listOf(
            GripBodyPoint(GripBodyJoint.leftHip, Point2D(600.0,600.0), GripVector3(.2,.2,0.0),.9),
            GripBodyPoint(GripBodyJoint.rightHip, Point2D(400.0,600.0), GripVector3(-.2,.2,0.0),.9))
        return GripObservation(0.0,ImageSize(1000.0,1000.0),body.map { GripTrackedPoint(it.joint,it.position,it.score) },
            body,emptyList(),"test","synthetic")
    }
    @Test fun viewDoesNotRequireHandsWristsOrHipsInUprightSetup() {
        val r = WorkoutGripTorsoView.assess(observation())
        assertEquals("front",r.view); assertTrue(r.supported); assertEquals("uprightShoulders",r.method)
        assertEquals("shouldersAndHips",WorkoutGripTorsoView.assess(observation(true)).method)
    }
    @Test fun rearAndSideAreDistinctWithoutFaceEvidence() {
        val o = observation()
        val rear = o.copy(body=o.body.map { it.copy(world=it.world.copy(x=-it.world.x)) })
        assertEquals("rear",WorkoutGripTorsoView.assess(rear).view)
        val side = o.copy(body=o.body.map { it.copy(world=GripVector3(0.0,it.world.y,it.world.x)) })
        assertFalse(WorkoutGripTorsoView.assess(side).supported)
    }
    @Test fun weakOrForeignShouldersCannotEstablishView() {
        val o = observation()
        assertFalse(WorkoutGripTorsoView.assess(o.copy(trackedBody=o.trackedBody.map { it.copy(score=.49) })).available)
        assertFalse(WorkoutGripTorsoView.assess(o.copy(body=o.body.map { it.copy(position=Point2D(1.0,1.0)) })).available)
    }
    @Test fun conflictingStrongHipsCannotBypassIdentity() {
        val o = observation(true)
        assertFalse(WorkoutGripTorsoView.assess(o.copy(trackedBody=o.trackedBody.map {
            if (it.joint==GripBodyJoint.leftHip)it.copy(position=Point2D(900.0,900.0)) else it
        })).available)
    }
    @Test fun weakHipsCanFallbackButRolledShoulderImageCannot() {
        val o = observation(true)
        val weak = o.copy(body=o.body.map { if (it.joint.name.endsWith("Hip"))it.copy(score=.1) else it })
        assertEquals("uprightShoulders",WorkoutGripTorsoView.assess(weak).method)
        val rolled = observation().copy(body=observation().body.map { it.copy(position=Point2D(500.0,if(it.joint==GripBodyJoint.leftShoulder)200.0 else 600.0)) })
        val matched = rolled.copy(trackedBody=rolled.body.map { GripTrackedPoint(it.joint,it.position,it.score) })
        assertFalse(WorkoutGripTorsoView.assess(matched).supported)
    }
}
