package dev.hanginthere.core

import org.junit.Assert.*
import org.junit.Test

class BodyAthleteAssociationExperimentTests {
    private val source = ForegroundAssociation.Source(7, ImageSize(1000.0,1000.0), "authored body",1)
    private fun time(ms: Long) = PresentationTime(ms,1000)
    private fun person(x: Double = 500.0,y: Double = 250.0,scale: Double = 1.0): PoseObservation {
        val points = listOf(PoseJoint.nose to Point2D(x,y-70*scale),
            PoseJoint.leftShoulder to Point2D(x-40*scale,y), PoseJoint.rightShoulder to Point2D(x+40*scale,y),
            PoseJoint.leftHip to Point2D(x-25*scale,y+150*scale),PoseJoint.rightHip to Point2D(x+25*scale,y+150*scale),
            PoseJoint.leftAnkle to Point2D(x-20*scale,y+400*scale),PoseJoint.rightAnkle to Point2D(x+20*scale,y+400*scale))
        return PoseObservation(points.map { Landmark(it.first,it.second,.9) })
    }
    private val athlete get() = person()
    private val spectator get() = person(100.0,400.0,.3)
    private fun acquire(model: BodyAthleteAssociationExperiment, origin: Long = 0) {
        for (ms in listOf(0L,200,400)) assertNull(model.observe(source,time(origin+ms),listOf(athlete,spectator)).candidateIndex)
        assertEquals(0,model.observe(source,time(origin+600),listOf(athlete,spectator)).candidateIndex)
    }
    @Test fun sourceDwellAndMinimumFourObservationsAreIndependent() {
        val model = BodyAthleteAssociationExperiment(source)
        for (ms in listOf(0L,300,600)) assertNull(model.observe(source,time(ms),listOf(athlete)).candidateIndex)
        assertEquals(0,model.observe(source,time(700),listOf(athlete)).candidateIndex)
        val boundary = BodyAthleteAssociationExperiment(source)
        for (ms in listOf(0L,200,400,599)) assertNull(boundary.observe(source,time(ms),listOf(athlete)).candidateIndex)
        assertEquals(0,boundary.observe(source,time(600),listOf(athlete)).candidateIndex)
    }
    @Test fun aClearSkeletalSubjectDoesNotNeedEightPercentFullRectangleArea() {
        // Its trusted point bounds occupy3.76%, with clearly measurable torso.
        val model = BodyAthleteAssociationExperiment(source)
        acquire(model)
    }
    @Test fun reorderingAndADistantLargerNewcomerCannotStealTheSubject() {
        val model = BodyAthleteAssociationExperiment(source);acquire(model)
        assertEquals(1,model.observe(source,time(700),listOf(spectator,athlete)).candidateIndex)
        val bigger = person(820.0,200.0,1.3)
        assertEquals(1,model.observe(source,time(800),listOf(bigger,athlete,spectator)).candidateIndex)
    }
    @Test fun backgroundRemainingAfterAthleteDisappearsCannotBecomeTheAthlete() {
        val model = BodyAthleteAssociationExperiment(source);acquire(model)
        assertNull(model.observe(source,time(800),listOf(spectator)).candidateIndex)
        val lost = model.observe(source,time(951),listOf(spectator,athlete))
        assertEquals(BodyAthleteAssociationExperiment.Status.lost,lost.status)
        assertNull(lost.candidateIndex)
        assertEquals("resetRequired",model.observe(source,time(1000),listOf(athlete)).reason)
    }
    @Test fun equalForegroundsOverlapsAndUnlocatedCompetitorsAreAmbiguous() {
        for (others in listOf(listOf(athlete,person(800.0)),listOf(athlete,person(510.0)),
            listOf(athlete,PoseObservation(emptyList())))) {
            val model=BodyAthleteAssociationExperiment(source)
            for(ms in listOf(0L,200,400,600,800)) assertNull(model.observe(source,time(ms),others).candidateIndex)
        }
        val model=BodyAthleteAssociationExperiment(source);acquire(model)
        assertNull(model.observe(source,time(700),listOf(athlete,person(510.0))).candidateIndex)
        assertEquals(1,model.observe(source,time(800),listOf(spectator,athlete)).candidateIndex)
    }
    @Test fun partialCompetitorAtTheSubjectIsNotSilentlyErased() {
        val model=BodyAthleteAssociationExperiment(source);acquire(model)
        val partial=PoseObservation(listOf(Landmark(PoseJoint.nose,Point2D(500.0,400.0),.9)))
        assertNull(model.observe(source,time(700),listOf(athlete,partial)).candidateIndex)
    }
    @Test fun malformedCompetitorsNeverMakeSelectionEasier() {
        val invalid = spectator.copy(landmarks=spectator.landmarks+spectator.landmarks.first())
        val model=BodyAthleteAssociationExperiment(source);acquire(model)
        assertNull(model.observe(source,time(700),listOf(athlete,invalid)).candidateIndex)
        assertEquals("invalidCandidate",model.observe(source,time(800),listOf(athlete,invalid)).reason)
        assertNull(model.observe(source,time(900),List(65){athlete}).candidateIndex)
    }
    @Test fun normalVerticalExerciseIsNotConfusedWithHorizontalSubjectReplacement() {
        val model=BodyAthleteAssociationExperiment(source);acquire(model)
        for(i in 1..10) assertEquals(0,model.observe(source,time(600+i*100L),listOf(person(y=250+i*10.0),spectator)).candidateIndex)
        assertNull(model.observe(source,time(1700),listOf(person(x=800.0,y=350.0),spectator)).candidateIndex)
    }
    @Test fun sourceFaultsCannotRewindTheClockOrBridgeExpiredIdentity() {
        val model=BodyAthleteAssociationExperiment(source);acquire(model)
        assertEquals("clockFault",model.observe(source,time(500),listOf(athlete)).reason)
        assertEquals(0,model.observe(source,time(700),listOf(athlete)).candidateIndex)
        assertEquals(0,model.observe(source,time(1050),listOf(athlete)).candidateIndex)
        assertEquals(BodyAthleteAssociationExperiment.Status.lost,model.observe(source,time(1401),listOf(athlete)).status)
    }
    @Test fun staleSourceCannotAdvanceOrResetTheCurrentAssociation() {
        val model=BodyAthleteAssociationExperiment(source);acquire(model)
        assertEquals("sourceMismatch",model.observe(source.copy(generation=6),time(800),listOf(spectator)).reason)
        assertEquals(0,model.observe(source,time(700),listOf(athlete)).candidateIndex)
        assertEquals("sourceChanged",model.observe(source.copy(revision=2),time(800),listOf(athlete)).reason)
    }
    @Test fun returnedIndexAndImmutableOriginalObservationsArePreserved() {
        val model=BodyAthleteAssociationExperiment(source);val raw=listOf(spectator,athlete)
        for(ms in listOf(0L,200,400,600)) {
            val before=raw.toList();val d=model.observe(source,time(ms),raw)
            assertEquals(before,raw)
            if(ms==600L) {assertEquals(1,d.candidateIndex);assertEquals(7L,d.sourceGeneration)}
        }
    }
    @Test fun movingArmsAndAnklesCannotResizeTheShoulderIdentityAnchor() {
        val model=BodyAthleteAssociationExperiment(source);acquire(model)
        val armsWide=athlete.copy(landmarks=athlete.landmarks+listOf(
            Landmark(PoseJoint.leftWrist,Point2D(300.0,400.0),.9),
            Landmark(PoseJoint.rightWrist,Point2D(700.0,400.0),.9)))
        assertEquals(0,model.observe(source,time(700),listOf(armsWide,spectator)).candidateIndex)
        assertEquals(0,model.observe(source,time(800),listOf(athlete,spectator)).candidateIndex)
    }
    @Test fun hipsAreNotRequiredToAssociateAClearUpperBody() {
        val upper=athlete.copy(landmarks=athlete.landmarks.filter { it.joint !in listOf(PoseJoint.leftHip,PoseJoint.rightHip) })
        val model=BodyAthleteAssociationExperiment(source)
        for(ms in listOf(0L,200,400))assertNull(model.observe(source,time(ms),listOf(upper,spectator)).candidateIndex)
        assertEquals(0,model.observe(source,time(600),listOf(upper,spectator)).candidateIndex)
    }
    @Test fun continuousRestWalkingDoesNotPinTheAthleteToTheAcquisitionSpot() {
        val model=BodyAthleteAssociationExperiment(source);acquire(model)
        for(i in 1..12) assertEquals(0,model.observe(source,time(600+i*100L),
            listOf(person(x=500+i*20.0),spectator)).candidateIndex)
        // This continuity is positive current matching, not a fallback when
        // the tracked subject disappears or a distant body remains.
        assertNull(model.observe(source,time(1900),listOf(spectator)).candidateIndex)
        assertEquals(BodyAthleteAssociationExperiment.Status.lost,
            model.observe(source,time(2151),listOf(spectator)).status)
    }
}
