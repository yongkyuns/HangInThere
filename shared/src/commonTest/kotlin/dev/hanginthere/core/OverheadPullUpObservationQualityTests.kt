package dev.hanginthere.core

import kotlin.test.*
import kotlin.math.*

class OverheadPullUpObservationQualityTests {
    private fun pose(ms:Long,rise:Double=0.0,degrees:Double=170.0,drop:Double=0.0):PoseResult {
        val height=350-rise;val offset=height/(2*tan(degrees*PI/360))
        val points=listOf(250.0,650.0).flatMapIndexed {i,x ->
            val names=ArmMeasurement.Side.entries[i].joints
            listOf(Landmark(names[0],Point2D(x,600-rise),.9),
                Landmark(names[1],Point2D(x+if(i==0)-offset else offset,(850-rise+drop)/2),.9),
                Landmark(names[2],Point2D(x,250+drop),.9))
        }+listOf(Landmark(PoseJoint.leftHip,Point2D(250.0,1000-rise),.9),Landmark(PoseJoint.rightHip,Point2D(650.0,1000-rise),.9))
        return PoseResult(PresentationTime(ms,1000),ImageSize(900.0,1400.0),listOf(PoseObservation(points)),"authored-quality",1)
    }
    private fun cycle()=(0L..1000L step 40).map {pose(it)}+
        (1040L..1360L step 40).map {pose(it,140.0,100.0)}+(1400L..1800L step 40).map {pose(it)}
    @Test fun collapsedWristsCannotCorroborateDepartureOrSupplyAnEndpoint() {
        val selection=BodyAthleteSelection();val owner=OverheadPullUpWorkout("quality")
        for(p in cycle()) {
            val measured=if(p.timestamp.value in 1120L..1160L) {
                val person=p.people.single()
                p.copy(people=listOf(person.copy(landmarks=person.landmarks.map { l ->
                    val side=ArmMeasurement.Side.entries.singleOrNull { it.joints[2]==l.joint }
                    if(side==null)l else l.copy(position=person.landmark(side.joints[1])!!.position.let {Point2D(it.x,it.y+25)})
                })))
            } else p
            selection.observe(measured);val state=owner.observe(selection,OverheadPullUpMotion.Camera.ready,automaticFinish=false)
            if(p.timestamp.value in 1120L..1160L)assertFalse(state.motion.observationUsable)
        }
        owner.finish("manual");val record=owner.record();assertEquals(1,record.completedCount)
        val rep=record.completedReps.single()
        assertFalse(rep.extremeAt in setOf(1.12,1.16));assertFalse(rep.returnedAt in setOf(1.12,1.16))
        WorkoutLibrary.validate(WorkoutLibrary.register(WorkoutLibraryState(),listOf(record)))
    }
    @Test fun shortButStableProjectedSegmentAtSupportIsNotGloballyRejected() {
        val selection=BodyAthleteSelection();val motion=OverheadPullUpMotion();var last:OverheadPullUpMotion.Snapshot?=null
        for(ms in 0L..1000L step 40) {
            val p=pose(ms);val measured=p.copy(people=listOf(p.people.single().copy(landmarks=p.people.single().landmarks.map {
                if(it.joint in setOf(PoseJoint.leftElbow,PoseJoint.rightElbow))it.copy(position=it.position.copy(y=280.0)) else it
            })))
            selection.observe(measured);last=motion.observe(selection,"quality",true)
        }
        assertEquals(OverheadPullUpMotion.Phase.ready,last!!.phase);assertTrue(last!!.observationUsable);assertEquals(0,last!!.cycles)
    }
    @Test fun actualDeparturesAndUnobservableSourceTimeStillCannotComplete() {
        for(kind in listOf("departure","gap","camera")) {
            val selection=BodyAthleteSelection();val motion=OverheadPullUpMotion();var last:OverheadPullUpMotion.Snapshot?=null
            for(p in cycle()) {
                if(kind=="gap" && p.timestamp.value in 1120L..1480L)continue
                val measured=if(kind=="departure" && p.timestamp.value in 1120L..1320L)pose(p.timestamp.value,140.0,100.0,140.0) else p
                selection.observe(measured);last=motion.observe(selection,"quality",kind!="camera" || p.timestamp.value !in 1120L..1480L)
            }
            assertEquals(0,last!!.cycles,kind)
        }
    }
}
