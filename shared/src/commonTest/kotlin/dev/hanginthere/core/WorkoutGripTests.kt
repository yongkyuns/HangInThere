package dev.hanginthere.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class WorkoutGripTests {
    private val size=ImageSize(1000.0,1000.0)
    private val bar=BarSegment(Point2D(200.0,100.0),Point2D(800.0,100.0))
    private fun observation(time:Double=0.0,left:HandGrip=HandGrip.underhand,right:HandGrip=HandGrip.underhand):GripObservation {
        val body=listOf(
            GripBodyPoint(GripBodyJoint.leftShoulder,Point2D(600.0,300.0),GripVector3(.2,-.2,0.0),.9),
            GripBodyPoint(GripBodyJoint.rightShoulder,Point2D(400.0,300.0),GripVector3(-.2,-.2,0.0),.9),
            GripBodyPoint(GripBodyJoint.leftHip,Point2D(600.0,700.0),GripVector3(.2,.2,0.0),.9),
            GripBodyPoint(GripBodyJoint.rightHip,Point2D(400.0,700.0),GripVector3(-.2,.2,0.0),.9),
            GripBodyPoint(GripBodyJoint.leftWrist,Point2D(700.0,100.0),GripVector3(.3,-.4,0.0),.9),
            GripBodyPoint(GripBodyJoint.rightWrist,Point2D(300.0,100.0),GripVector3(-.3,-.4,0.0),.9))
        fun hand(side:ArmMeasurement.Side,grip:HandGrip):GripHandObservation {
            val pair=when(side to grip) {
                ArmMeasurement.Side.left to HandGrip.overhand -> GripVector3(-.04,-.06,0.0) to GripVector3(.04,-.06,0.0)
                ArmMeasurement.Side.right to HandGrip.overhand -> GripVector3(.04,-.06,0.0) to GripVector3(-.04,-.06,0.0)
                ArmMeasurement.Side.left to HandGrip.underhand -> GripVector3(.04,-.06,0.0) to GripVector3(-.04,-.06,0.0)
                ArmMeasurement.Side.right to HandGrip.underhand -> GripVector3(-.04,-.06,0.0) to GripVector3(.04,-.06,0.0)
                ArmMeasurement.Side.left to HandGrip.neutral -> GripVector3(0.0,.07,0.0) to GripVector3(0.0,.04,.04)
                ArmMeasurement.Side.right to HandGrip.neutral -> GripVector3(0.0,.07,0.0) to GripVector3(0.0,.04,.04)
                else -> GripVector3(.02,0.0,.06) to GripVector3(-.02,0.0,.06)
            }
            val index=pair.first;val pinky=pair.second
            val middle=(index+pinky)*.55
            val position=body.single { it.joint==if(side==ArmMeasurement.Side.left)GripBodyJoint.leftWrist else GripBodyJoint.rightWrist }.position
            return GripHandObservation(listOf(GripHandPoint(GripHandJoint.wrist,position,GripVector3(0.0,0.0,0.0)),
                GripHandPoint(GripHandJoint.indexMCP,position,index),GripHandPoint(GripHandJoint.middleMCP,position,middle),GripHandPoint(GripHandJoint.pinkyMCP,position,pinky)),side.name,.95)
        }
        return GripObservation(time,size,body.map { GripTrackedPoint(it.joint,it.position,it.score) },body,
            listOf(hand(ArmMeasurement.Side.right,right),hand(ArmMeasurement.Side.left,left)),"test-model","synthetic")
    }
    private fun pose(time:Double,handTime:Double?=time):PoseResult {
        val o=observation(handTime?.let { (it*1000).toLong()/1000.0 } ?: (time*1000).toLong()/1000.0)
        return PoseResult(PresentationTime((time*1000).toLong(),1000),size,listOf(PoseObservation(o.trackedBody.map {
            Landmark(PoseJoint.valueOf(it.joint.name),it.position,it.score)
        })),"synthetic",1,handTime?.let { o })
    }
    @Test fun anatomicalAssociationDoesNotDependOnHandArrayOrder() {
        for(grip in listOf(HandGrip.underhand,HandGrip.overhand,HandGrip.neutral)) {
            val raw=observation(left=grip,right=grip)
            assertEquals(grip,WorkoutGripGeometry.orientation(raw).left)
            assertEquals(grip,WorkoutGripGeometry.orientation(raw.copy(hands=raw.hands.reversed())).right)
        }
        assertEquals(GripOrientation.mixed,WorkoutGripGeometry.orientation(observation(right=HandGrip.overhand)).orientation)
    }
    @Test fun missingFailedStaleAndSideViewGripNeverDelayAutomaticStartOrChangeARep() {
        val attitude=PhoneOrientationStability.Quaternion.create(0.0,0.0,0.0,1.0)!!
        val inputs=listOf<(Double)->GripObservation?>(
            { null },
            { observation(it).copy(hands=emptyList()) },
            { observation(it).copy(failure="gripInferenceUnavailable") },
            { observation(it-2.0) },
            { rotateTorso(observation(it),kotlin.math.PI/2) },
            { observation(it,left=HandGrip.overhand,right=HandGrip.overhand) },
            { observation(it,left=HandGrip.neutral,right=HandGrip.neutral) },
        )
        var baseline:List<RepRecord>?=null
        for(input in inputs) {
            val runtime=WorkoutRuntime(WorkoutExerciseSelection.pullUp,automaticWorkouts=true)
            assertTrue(runtime.confirmManualBar(ConfirmedBar(ConfirmedBar.Role.pullUpGrip,
                ConfirmedBar.Method.manualEdge,CounterFixtures.edge,imageSize=size,
                sourceTime=PresentationTime(0,1000)),attitude,0.0))
            for((time,angle) in listOf(0.0 to 170.0,.15 to 170.0,.3 to 170.0,.45 to 140.0,
                    .6 to 90.0,.75 to 80.0,.9 to 80.0,1.05 to 120.0,1.2 to 170.0,1.35 to 170.0)) {
                val p=CounterFixtures.bilateral(time,angle,angle).copy(gripObservation=input(time))
                val result=runtime.observe(WorkoutRuntime.FrameInput(p,attitude=attitude,motionTimestamp=time,
                    translations=List(3){StaticSceneStability.PatchTranslation(0.0,0.0)},
                    globalScaleFraction=0.0,sceneTimestamp=time))
                assertEquals(if(time==0.0)LiveSetSession.Phase.idle else LiveSetSession.Phase.running,result.phase)
                assertNull(result.setupIssue)
            }
            runtime.session.finish()
            assertEquals(1,runtime.session.observedMovements)
            val reps=runtime.session.record.reps.map { it.copy(id="rep",setId="set",grip=null) }
            assertEquals(1,reps.size)
            assertNotNull(reps.single().returnedAt)
            if(baseline==null)baseline=reps else assertEquals(baseline,reps)
        }
    }
    @Test fun wideSpacingDoesNotInventOverhandOrOverwriteDirectHandEvidence() {
        for(grip in listOf(HandGrip.underhand,HandGrip.overhand,HandGrip.neutral,HandGrip.unknown)) {
            val analysis=WorkoutGripAnalysis("wide")
            for(i in 0..60) {
                val t=i*.05
                analysis.observe(pose(t).copy(gripObservation=observation(t,left=grip,right=grip)),
                    bar,ExerciseCounter.Phase.ready,true)
            }
            assertEquals(GripWidth.wide,analysis.current.width)
            assertEquals(GripOrientation.valueOf(grip.name),analysis.current.orientation)
        }
    }
    @Test fun setGripUsesConfirmedExerciseEvidenceAndSurvivesTemporaryMissingHands() {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        for(i in 0..15)a.observeGrip(pose(i*.05,if(i%5==0)i*.05 else null),bar,ExerciseCounter.Phase.ready,true)
        assertEquals(GripOrientation.unknown,a.setGrip?.orientation)
        a.observeGrip(pose(.8,.8),bar,ExerciseCounter.Phase.outbound,true)
        assertEquals(GripOrientation.underhand,a.setGrip?.orientation)
        val confirmed=a.setGrip
        a.observeGrip(pose(.9,null),bar,ExerciseCounter.Phase.returning,false)
        assertEquals(GripOrientation.unknown,a.currentGrip?.orientation)
        assertEquals(confirmed,a.setGrip)
        a.interrupt("temporaryTrackingLoss")
        assertEquals(confirmed,a.setGrip)
        val next=WorkoutAnalysis("next",ExerciseCounter.Exercise.pullUp)
        assertNull(next.setGrip)
    }
    @Test fun contradictorySdkHandIdentityIsUnknown() {
        val o=observation()
        val contradictory=o.copy(hands=o.hands.map { it.copy(sdkHandedness=if(it.sdkHandedness=="left")"right" else "left",sdkHandednessScore=.99) })
        assertEquals(GripOrientation.unknown,WorkoutGripGeometry.orientation(contradictory).orientation)
    }
    @Test fun strongAuxiliaryHipsDoNotRequireWeakPrimaryHipIdentityEvidence() {
        val o=observation()
        val hips=setOf(GripBodyJoint.leftHip,GripBodyJoint.rightHip)
        val weakPrimary=o.copy(trackedBody=o.trackedBody.map { if(it.joint in hips)it.copy(score=.49) else it })
        assertEquals(GripOrientation.underhand,WorkoutGripGeometry.orientation(weakPrimary).orientation)
        assertNotNull(WorkoutGripGeometry.bodyBasis(o.copy(trackedBody=o.trackedBody.filter { it.joint !in hips })))
        assertNull(WorkoutGripGeometry.bodyBasis(o.copy(body=o.body.map { if(it.joint in hips)it.copy(score=.49) else it })))
        assertNull(WorkoutGripGeometry.bodyBasis(o.copy(trackedBody=o.trackedBody.map {
            if(it.joint==GripBodyJoint.leftHip)it.copy(position=Point2D(900.0,900.0)) else it
        })))
        assertNull(WorkoutGripGeometry.bodyBasis(o.copy(trackedBody=o.trackedBody.filter { it.joint!=GripBodyJoint.leftWrist })))
        assertNull(WorkoutGripGeometry.bodyBasis(o.copy(trackedBody=o.trackedBody.map {
            if(it.joint==GripBodyJoint.leftShoulder)it.copy(score=.49) else it
        })))
        assertNull(WorkoutGripGeometry.bodyBasis(o.copy(trackedBody=o.trackedBody.map {
            if(it.joint==GripBodyJoint.leftHip)it.copy(score=Double.NaN) else it
        })))
    }
    @Test fun historicalGripPolicyIsPreservedWhenReviewingOldRecords() {
        val old=WorkoutGripRecord(listOf(GripOrientationSpan("old",0.0,1.0,HandGrip.underhand,HandGrip.underhand,1.0,null,"model")),
            emptyList(),GripEstimate(left=HandGrip.underhand,right=HandGrip.underhand,policyVersion=1),policyVersion=1)
        val decoded=WorkoutRecordCodec.json.decodeFromString<WorkoutGripRecord>(WorkoutRecordCodec.json.encodeToString(old))
        assertEquals(old,decoded)
        assertEquals(1,WorkoutGripAnalysis.summary(decoded,0.0,1.0).policyVersion)
        assertEquals(1,WorkoutGripAnalysis.summary(decoded,1.0,1.0).policyVersion)
        assertEquals(GripOrientation.underhand,WorkoutGripAnalysis.summary(decoded,0.0,1.0).orientation)
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeGrip(decoded,decoded.copy(policyVersion=2)) }
    }
    @Test fun pendingPreInterruptionFrameCannotRestoreTheViewOrConfirmHands() {
        val a=WorkoutGripAnalysis("set")
        for(i in 0..9)a.observe(pose(i*.05,if(i%5==0)i*.05 else null),bar,ExerciseCounter.Phase.ready,true)
        a.observe(pose(.5,null),bar,ExerciseCounter.Phase.ready,true)
        a.interrupt()
        a.observe(pose(.55,.5),bar,ExerciseCounter.Phase.ready,true)
        assertEquals(GripOrientation.unknown,a.current.orientation)
        assertFalse(a.record.orientationSpans.any { it.startedAt==.5 })
        // A new reference invalidates evidence sampled before the current frame.
        a.observe(pose(.6,.58),BarSegment(Point2D(200.0,105.0),Point2D(800.0,105.0)),ExerciseCounter.Phase.ready,true)
        assertFalse(a.record.orientationSpans.any { it.startedAt==.58 })
        assertEquals(GripWidth.unknown,a.current.width)
    }
    @Test fun ordinaryBodyLossRejectsPendingPreLossHands() {
        val a=WorkoutGripAnalysis("set")
        for(i in 0..10)a.observe(pose(i*.05,if(i%5==0)i*.05 else null),bar,ExerciseCounter.Phase.ready,true)
        a.observe(pose(.6,null),bar,ExerciseCounter.Phase.ready,false)
        a.observe(pose(.65,.55),bar,ExerciseCounter.Phase.ready,true)
        assertFalse(a.record.orientationSpans.any { it.startedAt==.55 })
        assertEquals(GripWidth.unknown,a.current.width)
    }
    @Test fun sourceClockOverrideDoesNotUseTruncatedTransportTimestamps() {
        val a=WorkoutGripAnalysis("set")
        val exact=.5000007
        val p=pose(.5,null).copy(gripObservation=observation(exact))
        a.observe(p,bar,ExerciseCounter.Phase.ready,true,sourceSeconds=exact)
        assertEquals(exact,a.record.orientationSpans.first().startedAt)
        a.interrupt()
        a.observe(p.copy(timestamp=PresentationTime(600,1000)),bar,ExerciseCounter.Phase.ready,true,sourceSeconds=.6)
        assertEquals(1,a.record.orientationSpans.count { it.startedAt==exact && it.reason!="observationInterrupted" })
    }
    @Test fun sustainedBodyLossPreservesEarlierWidthHistoryWithoutCreditingTheOutage() {
        val a=WorkoutGripAnalysis("set")
        for(i in 0..40)a.observe(pose(i*.05,if(i%5==0)i*.05 else null),bar,ExerciseCounter.Phase.ready,true)
        val measured=a.record.widthSpans.filter { it.width!=GripWidth.unknown }
        assertTrue(measured.isNotEmpty())
        val spanCount=a.record.widthSpans.size
        for(i in 1..150)a.observe(pose(2.0+i/30.0,null),bar,ExerciseCounter.Phase.ready,false)
        assertEquals(measured,a.record.widthSpans.filter { it.width!=GripWidth.unknown })
        assertTrue(a.record.widthSpans.size<=spanCount+2)
        assertFalse(a.record.timelineTruncated)
        assertTrue(a.record.widthSpans.filter { it.width==GripWidth.unknown }.all { it.observedSeconds==0.0 })
        a.observe(pose(7.1,6.95),bar,ExerciseCounter.Phase.ready,true)
        assertFalse(a.record.orientationSpans.any { it.startedAt==6.95 })
        assertEquals(GripOrientation.unknown,a.current.orientation)
        assertEquals(GripWidth.unknown,a.current.width)
    }
    @Test fun freshBodyOnlyWidthDuringArmLossDoesNotCreditHandOrientation() {
        val a=WorkoutGripAnalysis("set")
        for(i in 0..60)a.observe(pose(i/30.0),bar,ExerciseCounter.Phase.ready,true)
        val hands=a.record.orientationSpans.filter { it.left!=HandGrip.unknown || it.right!=HandGrip.unknown }
        assertTrue(hands.isNotEmpty())
        for(i in 1..45) {
            a.observe(pose(2.0+i/30.0),bar,ExerciseCounter.Phase.outbound,false)
            assertEquals(GripOrientation.unknown,a.current.orientation)
            assertEquals(GripWidth.wide,a.current.width)
        }
        assertEquals(hands,a.record.orientationSpans.filter { it.left!=HandGrip.unknown || it.right!=HandGrip.unknown })
    }
    @Test fun globallyInsideButOutsideSourceCropCannotBecomeOrientation() {
        val o=observation()
        val cropped=o.copy(hands=o.hands.map { it.copy(sourceRegion=PersonInferenceRegion(400,200,200,200)) })
        assertEquals(GripOrientation.unknown,WorkoutGripGeometry.orientation(cropped).orientation)
        assertEquals(GripWidth.unknown,WorkoutGripGeometry.widthBand(Double.NaN))
        assertEquals(GripWidth.unknown,WorkoutGripGeometry.widthBand(0.0))
    }
    @Test fun absentOptionalInferenceDoesNotCreateGripHistory() {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        for(i in 0..100)a.observeGrip(pose(i*.1,null),bar,ExerciseCounter.Phase.ready,true)
        assertNull(a.gripRecord);assertNull(a.gripDelta)
    }
    @Test fun handCropsStayInsideSourceAndUseOnlyOneAthlete() {
        val p=pose(0.0)
        val regions=WorkoutGripInference.handRegions(p)
        assertEquals(2,regions.size);assertTrue(regions.all { it.isValidFor(size) })
        assertTrue(WorkoutGripInference.handRegions(p.copy(people=p.people+p.people)).isEmpty())
    }
    @Test fun missedHandsHaveABoundedWristCenteredContextFallback() {
        val p=pose(0.0)
        val candidates=WorkoutGripInference.handRegionCandidates(p)
        assertEquals(2,candidates.size)
        assertEquals(WorkoutGripInference.handRegions(p),candidates.map { it.first() })
        assertEquals(PersonInferenceRegion(560,0,280,240),candidates[0].last())
        assertEquals(PersonInferenceRegion(160,0,280,240),candidates[1].last())
        assertTrue(candidates.all { it.size<=2 && it.distinct()==it && it.all { region->region.isValidFor(size) } })
        val bridge=WorkoutGripInferenceBridge()
        assertEquals(candidates,WorkoutRecordCodec.json.decodeFromString<List<List<PersonInferenceRegion>>>(
            bridge.handRegionCandidatesJson(WorkoutRecordCodec.json.encodeToString(p))))
        assertEquals(3,bridge.cropPolicyVersion)
        assertEquals(WorkoutGripGeometry.orientation(observation()),WorkoutRecordCodec.json.decodeFromString<GripEstimate>(
            bridge.orientationJson(WorkoutRecordCodec.json.encodeToString(observation()))))
    }
    @Test fun fallbackCropsClipEdgesDeduplicateAndRejectAmbiguousSources() {
        val p=pose(0.0)
        val edge=p.copy(people=listOf(PoseObservation(listOf(
            Landmark(PoseJoint.leftWrist,Point2D(0.0,0.0),1.0),
            Landmark(PoseJoint.rightWrist,Point2D(1000.0,1000.0),1.0)))))
        val candidates=WorkoutGripInference.handRegionCandidates(edge)
        assertEquals(2,candidates.size)
        assertEquals(PersonInferenceRegion(0,0,140,140),candidates[0].last())
        assertEquals(PersonInferenceRegion(860,860,140,140),candidates[1].last())
        assertTrue(candidates.flatten().all { it.isValidFor(size) })
        val tiny=edge.copy(imageSize=ImageSize(1.0,1.0),people=listOf(PoseObservation(listOf(
            Landmark(PoseJoint.leftWrist,Point2D(.5,.5),1.0),
            Landmark(PoseJoint.rightWrist,Point2D(.5,.5),1.0)))))
        assertTrue(WorkoutGripInference.handRegionCandidates(tiny).all { it.size==1 })
        assertTrue(WorkoutGripInference.handRegionCandidates(p.copy(people=p.people+p.people)).isEmpty())
        assertTrue(WorkoutGripInference.handRegionCandidates(p.copy(imageSize=ImageSize(1000.5,1000.0))).isEmpty())
        val duplicate=p.copy(people=listOf(PoseObservation(p.people.single().landmarks+p.people.single().landmarks)))
        assertTrue(WorkoutGripInference.handRegionCandidates(duplicate).isEmpty())
    }
    @Test fun requestSideSurvivesMissingOtherWristAndJsonTransport() {
        val p=pose(0.0).let { it.copy(people=listOf(PoseObservation(it.people.single().landmarks.filter { p->p.joint!=PoseJoint.leftWrist }))) }
        val requests=WorkoutGripInference.handRequests(p)
        assertEquals(ArmMeasurement.Side.right,requests.single().side)
        assertEquals(requests,WorkoutRecordCodec.json.decodeFromString<List<GripHandCropRequest>>(
            WorkoutGripInferenceBridge().handRequestsJson(WorkoutRecordCodec.json.encodeToString(p))))
    }
    @Test fun handStageUsesExistingBodyValidationWithoutSearchingForAGrip() {
        val p=pose(0.0);val o=observation();val expected=WorkoutGripInference.handRequests(p)
        for(grip in listOf(HandGrip.underhand,HandGrip.overhand,HandGrip.neutral,HandGrip.unknown)) {
            assertEquals(expected,WorkoutGripInference.handStageRequests(p,observation(left=grip,right=grip)))
        }
        val hips=setOf(GripBodyJoint.leftHip,GripBodyJoint.rightHip)
        val weak=o.copy(trackedBody=o.trackedBody.map { if(it.joint in hips)it.copy(score=.49) else it })
        val weakPose=p.copy(people=listOf(PoseObservation(p.people.single().landmarks.map {
            if(it.joint.name in hips.map { h->h.name })it.copy(confidence=.49) else it
        })))
        assertEquals(expected,WorkoutGripInference.handStageRequests(weakPose,weak))
        val four=weak.copy(trackedBody=weak.trackedBody.filter { it.joint !in hips })
        val fourPose=weakPose.copy(people=listOf(PoseObservation(weakPose.people.single().landmarks.filter { it.joint.name !in hips.map { h->h.name } })))
        assertEquals(expected,WorkoutGripInference.handStageRequests(fourPose,four))
        val invalid=listOf(o.copy(body=o.body.map { if(it.joint==GripBodyJoint.leftHip)it.copy(score=.49) else it }),
            o.copy(body=o.body.map { it.copy(world=GripVector3(0.0,0.0,0.0)) }),
            o.copy(body=o.body.dropLast(1)+o.body.first()),o.copy(schemaVersion=2),
            o.copy(coordinateConvention="mirrored"),o.copy(failure="bodyFrameUnavailable"))
        for(context in invalid) {
            assertNull(WorkoutGripGeometry.bodyBasis(context))
            assertTrue(WorkoutGripInference.handStageRequests(p,context).isEmpty())
        }
        val bridge=WorkoutGripInferenceBridge()
        assertEquals(expected,WorkoutRecordCodec.json.decodeFromString<List<GripHandCropRequest>>(
            bridge.handStageRequestsJson(WorkoutRecordCodec.json.encodeToString(p),WorkoutRecordCodec.json.encodeToString(o))))
        assertEquals("[]",bridge.handStageRequestsJson(WorkoutRecordCodec.json.encodeToString(p),WorkoutRecordCodec.json.encodeToString(invalid.first())))
        assertEquals(2,bridge.bodyPreflightVersion)
    }
    private fun rotateTorso(o:GripObservation,angle:Double):GripObservation {
        val c=kotlin.math.cos(angle);val s=kotlin.math.sin(angle)
        return o.copy(body=o.body.map { p -> p.copy(world=GripVector3(p.world.x*c+p.world.z*s,p.world.y,-p.world.x*s+p.world.z*c)) })
    }
    @Test fun frontAndRearAreSupportedButSideNeverClassifiesEvenNeutral() {
        for(grip in listOf(HandGrip.underhand,HandGrip.overhand,HandGrip.neutral)) {
            val o=observation(left=grip,right=grip)
            assertTrue(WorkoutGripGeometry.supportsView(WorkoutGripGeometry.bodyBasis(o)!!))
            val rear=rotateTorso(o,kotlin.math.PI)
            assertTrue(WorkoutGripGeometry.supportsView(WorkoutGripGeometry.bodyBasis(rear)!!))
            val side=rotateTorso(o,kotlin.math.PI/2)
            assertNotNull(WorkoutGripGeometry.bodyBasis(side))
            assertEquals("gripViewUnsupported",WorkoutGripGeometry.orientation(side).reason)
            assertEquals(GripOrientation.unknown,WorkoutGripGeometry.orientation(side).orientation)
            val p=pose(0.0).copy(gripObservation=side)
            assertTrue(WorkoutGripInference.handStageRequests(p,side).isEmpty())
        }
    }
    @Test fun losingSupportedViewClearsConfirmationAndRequiresFreshSupport() {
        val a=WorkoutGripAnalysis("view")
        fun sample(t:Double,side:Boolean=false) {
            val p=pose(t)
            a.observe(if(side)p.copy(gripObservation=rotateTorso(p.gripObservation!!,kotlin.math.PI/2)) else p,
                bar,ExerciseCounter.Phase.ready,true)
        }
        for(i in 0..4)sample(i*.25)
        assertEquals(GripOrientation.underhand,a.current.orientation)
        sample(1.25,true)
        assertEquals(GripOrientation.unknown,a.current.orientation)
        assertEquals("gripViewUnsupported",a.current.reason)
        sample(1.5)
        assertEquals(GripOrientation.unknown,a.current.orientation)
        sample(1.75)
        assertEquals(GripOrientation.unknown,a.current.orientation)
        sample(2.0)
        assertEquals(GripOrientation.underhand,a.current.orientation)
        assertTrue(a.record.orientationSpans.filter { it.startedAt==1.25 }.all { it.observedSeconds==0.0 })
    }
    @Test fun handStageCannotUseACachedOrForeignPrimaryBodyContext() {
        val p=pose(0.0);val o=observation()
        assertTrue(WorkoutGripInference.handStageRequests(p,o.copy(seconds=.1)).isEmpty())
        assertTrue(WorkoutGripInference.handStageRequests(p,o.copy(imageSize=ImageSize(2000.0,2000.0))).isEmpty())
        val changed=p.copy(people=listOf(PoseObservation(p.people.single().landmarks.map {
            if(it.joint==PoseJoint.leftWrist)it.copy(position=Point2D(710.0,100.0)) else it
        })))
        assertNotNull(WorkoutGripGeometry.bodyBasis(o))
        assertTrue(WorkoutGripInference.handStageRequests(changed,o).isEmpty())
        assertTrue(WorkoutGripInference.handStageRequests(p.copy(people=p.people+p.people),o).isEmpty())
        assertEquals(WorkoutGripInference.handRequests(p),WorkoutGripInference.handStageRequests(p,o.copy(trackedBody=o.trackedBody.reversed(),hands=emptyList())))
    }
    @Test fun candidateIdentityRequiresActualWristAndConsistentSdkSide() {
        val o=observation();val right=o.hands.first()
        assertTrue(WorkoutGripGeometry.hasHandIdentity(o,right,ArmMeasurement.Side.right))
        assertFalse(WorkoutGripGeometry.hasHandIdentity(o,right,ArmMeasurement.Side.left))
        assertFalse(WorkoutGripGeometry.hasHandIdentity(o,right.copy(sdkHandedness="Left"),ArmMeasurement.Side.right))
        assertFalse(WorkoutGripGeometry.hasHandIdentity(o.copy(schemaVersion=2),right,ArmMeasurement.Side.right))
        assertFalse(WorkoutGripGeometry.hasHandIdentity(o.copy(coordinateConvention="mirrored"),right,ArmMeasurement.Side.right))
        assertFalse(WorkoutGripGeometry.hasHandIdentity(o.copy(seconds=Double.NaN),right,ArmMeasurement.Side.right))
        assertFalse(WorkoutGripGeometry.hasHandIdentity(o.copy(failure="bodyFrameUnavailable"),right,ArmMeasurement.Side.right))
        val ambiguous=right.copy(points=right.points.map { if(it.joint==GripHandJoint.wrist)it.copy(position=Point2D(500.0,100.0)) else it })
        assertFalse(WorkoutGripGeometry.hasHandIdentity(o,ambiguous,ArmMeasurement.Side.right))
        // Identity is not a preferred-grip or missing-depth filter.
        assertTrue(WorkoutGripGeometry.hasHandIdentity(o,right.copy(points=right.points.map { it.copy(world=null) }),ArmMeasurement.Side.right))
        assertTrue(WorkoutGripInferenceBridge().hasHandIdentityJson(WorkoutRecordCodec.json.encodeToString(o),WorkoutRecordCodec.json.encodeToString(right),"right"))
    }
    @Test fun briefUncertaintyReusesRecentSupportWithoutCreditingUnknownTime() {
        val a=WorkoutGripAnalysis("set")
        for(t in listOf(0.0,.25,.5))a.observe(pose(t),bar,ExerciseCounter.Phase.ready,true)
        assertEquals(GripOrientation.underhand,a.current.orientation)
        val missing=pose(.75).let { it.copy(gripObservation=it.gripObservation!!.copy(hands=emptyList())) }
        a.observe(missing,bar,ExerciseCounter.Phase.ready,true)
        assertEquals(GripOrientation.unknown,a.current.orientation)
        a.observe(pose(1.0),bar,ExerciseCounter.Phase.ready,true)
        assertEquals(GripOrientation.underhand,a.current.orientation)
        a.observe(pose(1.25),bar,ExerciseCounter.Phase.ready,true)
        assertTrue(a.record.orientationSpans.filter { it.left==HandGrip.unknown || it.right==HandGrip.unknown }.all { it.observedSeconds==0.0 })
        assertEquals(.25,a.record.orientationSpans.sumOf { it.observedSeconds },1e-9)
    }
    @Test fun longUncertaintyOrAContradictoryGripRequiresFreshConfirmation() {
        for(changed in listOf(false,true)) {
            val a=WorkoutGripAnalysis("set")
            for(t in listOf(0.0,.25,.5))a.observe(pose(t),bar,ExerciseCounter.Phase.ready,true)
            for(t in listOf(.75,1.0)) {
                val p=pose(t)
                val o=if(changed && t==.75)observation(t,right=HandGrip.overhand) else p.gripObservation!!.copy(hands=emptyList())
                a.observe(p.copy(gripObservation=o),bar,ExerciseCounter.Phase.ready,true)
                assertEquals(GripOrientation.unknown,a.current.orientation)
            }
            a.observe(pose(1.25),bar,ExerciseCounter.Phase.ready,true)
            assertEquals(GripOrientation.unknown,a.current.orientation)
            a.observe(pose(1.5),bar,ExerciseCounter.Phase.ready,true)
            assertEquals(GripOrientation.unknown,a.current.orientation)
            a.observe(pose(1.75),bar,ExerciseCounter.Phase.ready,true)
            assertEquals(GripOrientation.underhand,a.current.orientation)
        }
    }
    @Test fun missingDepthOrIdentityOrBodyContextIsUnknown() {
        val o=observation()
        assertEquals(GripOrientation.unknown,WorkoutGripGeometry.orientation(o.copy(hands=o.hands.map { h->h.copy(points=h.points.map { it.copy(world=null) }) })).orientation)
        assertEquals(GripOrientation.unknown,WorkoutGripGeometry.orientation(o.copy(hands=listOf(o.hands[0],o.hands[0]))).orientation)
        assertNull(WorkoutGripGeometry.bodyBasis(o.copy(body=o.body.drop(1))))
        assertNull(WorkoutGripGeometry.bodyBasis(o.copy(trackedBody=o.trackedBody.map { it.copy(position=Point2D(0.0,0.0)) })))
        assertNull(WorkoutGripGeometry.bodyBasis(o.copy(coordinateConvention="mirrored")))
    }
    @Test fun cachedResultsCannotConfirmOrEarnHandTime() {
        val a=WorkoutGripAnalysis("set")
        for(i in 0..10)a.observe(pose(i*.05,0.0),bar,ExerciseCounter.Phase.ready,true)
        assertEquals(GripOrientation.unknown,a.current.orientation)
        assertEquals(0.0,a.record.orientationSpans.sumOf { it.observedSeconds })
        for(i in 11..35)a.observe(pose(i*.05,if(i%5==0)i*.05 else null),bar,ExerciseCounter.Phase.ready,true)
        assertEquals(GripOrientation.underhand,a.current.orientation)
        assertEquals(GripWidth.wide,a.current.width)
        assertTrue(a.record.orientationSpans.sumOf { it.observedSeconds }<1.75)
    }
    @Test fun sourceGapsResetEvidenceAndBaseline() {
        val a=WorkoutGripAnalysis("set")
        for(i in 0..20)a.observe(pose(i*.1,if(i%3==0)i*.1 else null),bar,ExerciseCounter.Phase.ready,true)
        a.observe(pose(3.0,null),bar,ExerciseCounter.Phase.ready,true)
        assertEquals(GripOrientation.unknown,a.current.orientation)
        assertEquals(GripWidth.unknown,a.current.width)
        assertTrue(a.record.orientationSpans.none { it.startedAt<2 && it.endedAt>=3 })
    }
    @Test fun changeWithinRepIsVariedAndUnknownCoverageIsExplicit() {
        val record=WorkoutGripRecord(listOf(
            GripOrientationSpan("a",0.0,1.0,HandGrip.underhand,HandGrip.underhand,1.0,null,"model"),
            GripOrientationSpan("b",1.0,2.0,HandGrip.overhand,HandGrip.overhand,1.0,null,"model")),emptyList(),GripEstimate())
        assertEquals(GripOrientation.varied,WorkoutGripAnalysis.summary(record,0.0,2.0).orientation)
        assertEquals(GripOrientation.unknown,WorkoutGripAnalysis.summary(record,0.0,3.0).orientation)
        assertEquals(GripWidth.unknown,WorkoutGripAnalysis.summary(record,0.0,2.0).width)
    }
    @Test fun boundedJournalDeltasPreserveFullGripHistoryAndRejectChangedEvidence() {
        val a=WorkoutGripAnalysis("set")
        var journal:WorkoutGripRecord?=null
        for(i in 0..100) {
            a.observe(pose(i*.05,if(i%5==0)i*.05 else null),bar,ExerciseCounter.Phase.ready,true)
            journal=WorkoutRecordCodec.mergeGrip(journal,a.delta)
        }
        a.observe(pose(6.0,null),bar,ExerciseCounter.Phase.ready,false)
        journal=WorkoutRecordCodec.mergeGrip(journal,a.delta)
        for(i in 0..50) {
            val time=6.1+i*.05
            a.observe(pose(time,if(i%5==0)time else null),bar,ExerciseCounter.Phase.ready,true)
            journal=WorkoutRecordCodec.mergeGrip(journal,a.delta)
        }
        val merged=requireNotNull(journal)
        assertEquals(a.record,merged)
        assertEquals(merged,WorkoutRecordCodec.json.decodeFromString<WorkoutGripRecord>(WorkoutRecordCodec.json.encodeToString(merged)))
        val span=merged.orientationSpans.first()
        assertFailsWith<IllegalArgumentException> {
            WorkoutRecordCodec.mergeGrip(merged,merged.copy(orientationSpans=listOf(span.copy(modelId="changed-model"))))
        }
        assertFailsWith<IllegalArgumentException> {
            WorkoutRecordCodec.mergeGrip(merged,merged.copy(orientationSpans=listOf(span.copy(observedSeconds=1000.0))))
        }
    }
    private fun record():WorkoutSetRecord {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        a.observe(MotionSample(0.0,170.0,170.0,null,true,false),ExerciseCounter.Phase.ready,null)
        a.observe(MotionSample(.2,90.0,90.0,null,true,false),ExerciseCounter.Phase.outbound,null)
        a.observe(MotionSample(.4,80.0,80.0,null,true,false),ExerciseCounter.Phase.returning,ExerciseCounter.Event(ExerciseCounter.Outcome.movement,.4,"test"))
        a.observe(MotionSample(.6,170.0,170.0,null,true,false),ExerciseCounter.Phase.ready,null)
        return WorkoutSetRecord("set",ExerciseCounter.Exercise.pullUp,0.0,.6,"manual",a.records,"test",1)
    }
    @Test fun correctionsOverlayWithoutChangingMeasuredEvidenceAndResetToAuto() {
        val r=record();val original=WorkoutRecordCodec.json.encodeToString(r)
        var ledger=WorkoutGripReview.correct(r,GripCorrectionLedger(r.id),null,GripOrientation.underhand,GripWidth.wide,1)
        assertEquals("user",WorkoutGripReview.report(r,ledger).reps.single().orientationSource)
        assertEquals(1,WorkoutGripReview.report(r,ledger).totals.single().completedReps)
        ledger=WorkoutGripReview.correct(r,ledger,r.reps.single().id,null,null,2)
        assertEquals(GripOrientation.unknown,WorkoutGripReview.report(r,ledger).reps.single().orientation)
        assertEquals(original,WorkoutRecordCodec.json.encodeToString(r))
        assertFailsWith<IllegalArgumentException> { WorkoutGripReview.correct(r,ledger,"missing",null,null,3) }
        assertFailsWith<IllegalArgumentException> { WorkoutGripReview.report(r,ledger.copy(setId="other")) }
    }
    @Test fun legacyWorkoutDecodesWithoutGripAndIncompleteAttemptsDoNotEnterTotals() {
        val r=record();assertNull(WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(WorkoutRecordCodec.json.encodeToString(r)).grip)
        val incomplete=r.copy(reps=r.reps.map { it.copy(returnedAt=null,closeReason="manual") })
        assertTrue(WorkoutGripReview.report(incomplete).totals.isEmpty())
    }
}
