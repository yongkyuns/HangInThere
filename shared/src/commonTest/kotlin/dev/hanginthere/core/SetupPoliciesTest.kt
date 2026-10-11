package dev.hanginthere.core

import kotlin.test.*
import kotlin.math.*

class SetupPoliciesTest {
    private val size=ImageSize(1000.0,1000.0)
    private fun pose(seconds:Double,dip:Boolean=true):PoseResult {
        val shoulders=if(dip) listOf(Point2D(350.0,300.0),Point2D(650.0,300.0)) else listOf(Point2D(300.0,500.0),Point2D(700.0,500.0))
        val wrists=if(dip) listOf(Point2D(250.0,550.0),Point2D(750.0,550.0)) else listOf(Point2D(250.0,200.0),Point2D(750.0,200.0))
        val joints=listOf(PoseJoint.leftShoulder,PoseJoint.leftElbow,PoseJoint.leftWrist,PoseJoint.rightShoulder,PoseJoint.rightElbow,PoseJoint.rightWrist)
        val points=listOf(shoulders[0],Point2D((shoulders[0].x+wrists[0].x)/2,(shoulders[0].y+wrists[0].y)/2),wrists[0],shoulders[1],Point2D((shoulders[1].x+wrists[1].x)/2,(shoulders[1].y+wrists[1].y)/2),wrists[1])
        return PoseResult(PresentationTime((seconds*1000).roundToLong(),1000),size,listOf(PoseObservation(joints.zip(points).map{Landmark(it.first,it.second,1.0)})),"test",1)
    }
    private fun line(x:Double,y0:Double=590.0,y1:Double=700.0)=BarLineCandidate(BarSegment(Point2D(x,y0),Point2D(x,y1)),y1-y0)
    private fun rails()=listOf(listOf(line(250.0),line(258.0)),listOf(line(750.0),line(758.0)))
    // A high-scoring pair has an eligible center but an unreachable longer edge.
    private fun unreachableTube(hand:Point2D,reversed:Boolean=false,outward:Double=-1.0)=listOf(118.0 to 150.0,88.0 to 140.0).map { (offset,length) ->
        val a=Point2D(hand.x+outward*offset,hand.y+40);val b=Point2D(a.x,a.y+length)
        BarLineCandidate(BarSegment(if(reversed) b else a,if(reversed) a else b),length)
    }
    private fun transverseRails(slope:Double=0.0,rightOffset:Double=0.0,oppositeSilhouettes:Boolean=false,reversed:Boolean=false,rightSlope:Double?=null)=
        listOf(250.0,750.0).mapIndexed { index,x ->
            listOf(0.0,10.0).mapIndexed { edgeIndex,height ->
                val half=if(oppositeSilhouettes && index==edgeIndex) 45.0 else 40.0
                val direction=if(index==1) rightSlope ?: slope else slope
                fun point(px:Double)=Point2D(px,595+slope*(x-250)+direction*(px-x)+height+if(index==1) rightOffset else 0.0)
                val a=point(x-half);val b=point(x+half)
                BarLineCandidate(BarSegment(if(reversed) b else a,if(reversed) a else b),2*half)
            }
        }
    private val identity=PhoneOrientationStability.Quaternion.create(0.0,0.0,0.0,1.0)!!
    private val stablePatches=listOf(StaticSceneStability.PatchTranslation(0.0,0.0),StaticSceneStability.PatchTranslation(.1,.1),StaticSceneStability.PatchTranslation(-.1,-.1))
    private fun frame(seconds:Double,dip:Boolean=true,candidates:List<List<BarLineCandidate>> = rails(),paused:Boolean=false)=WorkoutRuntime.FrameInput(pose(seconds,dip),candidates,identity,seconds,stablePatches,0.0,seconds,paused)

    @Test fun geometryPreservesObservedSupportAndRejectsCrossingEdges() {
        val p=BarSegment(Point2D(20.0,10.0),Point2D(100.0,10.0));val q=BarSegment(Point2D(40.0,16.0),Point2D(90.0,16.0))
        val pair=assertNotNull(BarFitter.pair(p,q,24.0))
        assertEquals(Point2D(40.0,10.0),pair.firstEdge.a);assertEquals(Point2D(90.0,10.0),pair.firstEdge.b)
        assertNull(BarFitter.pair(p,BarSegment(Point2D(20.0,7.0),Point2D(100.0,13.0)),24.0))
        assertEquals(50.0,p.perpendicularDistance(Point2D(150.0,60.0)))
        assertTrue(p.distance(Point2D(150.0,60.0))>50)
    }
    @Test fun lineFitterMergesFragmentsWithoutExtendingAndExcludesCropBorder() {
        val region=BarRegion(0.0,0.0,200.0,100.0)
        val contours=listOf(listOf(Point2D(20.0,25.0),Point2D(80.0,25.0)),listOf(Point2D(90.0,25.0),Point2D(170.0,25.0)),listOf(Point2D(0.0,0.0),Point2D(200.0,0.0)))
        val candidates=BarLineFitter.candidates(contours,region,ImageSize(200.0,100.0))
        val edge=assertNotNull(candidates.singleOrNull()).edge
        assertEquals(Point2D(20.0,25.0),edge.a);assertEquals(Point2D(170.0,25.0),edge.b)
        assertFailsWith<BarFitException>{BarLineFitter.candidates(contours,BarRegion(0.0,0.0,300.0,100.0),ImageSize(200.0,100.0))}
    }
    @Test fun tubePairingIsUndirectedAcrossVerticalEndpointOrder() {
        val edges=listOf(BarSegment(Point2D(100.0,80.0),Point2D(102.0,180.0)),BarSegment(Point2D(108.0,80.0),Point2D(106.0,180.0)))
        for(degrees in 0 until 360 step 45) {
            val angle=degrees*kotlin.math.PI/180
            fun rotate(p:Point2D)=Point2D(200+p.x*kotlin.math.cos(angle)-p.y*kotlin.math.sin(angle),200+p.x*kotlin.math.sin(angle)+p.y*kotlin.math.cos(angle))
            val rotated=edges.map{BarSegment(rotate(it.a),rotate(it.b))}
            for(swap in listOf(false,true)) for(reverseP in listOf(false,true)) for(reverseQ in listOf(false,true)) {
                val p=rotated[if(swap) 1 else 0];val q=rotated[if(swap) 0 else 1]
                val pair=assertNotNull(BarFitter.pair(if(reverseP) BarSegment(p.b,p.a) else p,if(reverseQ) BarSegment(q.b,q.a) else q,24.0))
                assertTrue(pair.geometryScore>98)
                for(point in listOf(pair.firstEdge.a,pair.firstEdge.b)) assertTrue(p.distance(point)<1e-8)
                for(point in listOf(pair.secondEdge.a,pair.secondEdge.b)) assertTrue(q.distance(point)<1e-8)
            }
        }
        assertNull(BarFitter.pair(edges[0],BarSegment(Point2D(104.0,80.0),Point2D(99.0,180.0)),24.0))
    }
    @Test fun dipRequiresBothActuallyObservedRailSilhouettes() {
        val policy=AutomaticDipRails()
        for(t in listOf(0.0,.3,.6,.9,1.2)) assertNull(policy.observe(pose(t),ArmMeasurement.Side.left,listOf(listOf(line(250.0)),listOf(line(750.0)))))
        for(t in listOf(1.5,1.8,2.1)) assertNull(policy.observe(pose(t),ArmMeasurement.Side.left,rails()))
        val locked=assertNotNull(policy.observe(pose(2.5),ArmMeasurement.Side.left,rails()))
        assertEquals(ConfirmedBar.Role.leftDipRail,locked.role);assertEquals(rails()[0][0].edge,locked.referenceEdge)
        assertNull(locked.oppositeEdge);assertEquals(AutomaticPullUpBar.State.locked,policy.state)
    }
    @Test fun ineligibleHigherRankedTubeCannotHideValidObservedRails() {
        for(reversed in listOf(false,true)) {
            val clutter=listOf(250.0,750.0).mapIndexed { index,x ->
                unreachableTube(Point2D(x,550.0),reversed,outward=if(index==0) -1.0 else 1.0)
            }
            for(side in ArmMeasurement.Side.entries) {
                val policy=AutomaticDipRails()
                val candidates=clutter.zip(rails()).map { (bad,good) -> if(reversed) good+bad else bad+good }
                for(time in listOf(0.0,.3,.6)) assertNull(policy.observe(pose(time),side,candidates))
                val bar=assertNotNull(policy.observe(pose(1.0),side,candidates))
                assertEquals(rails()[if(side==ArmMeasurement.Side.left) 0 else 1][0].edge,bar.referenceEdge)
                assertEquals(if(side==ArmMeasurement.Side.left) ConfirmedBar.Role.leftDipRail else ConfirmedBar.Role.rightDipRail,bar.role)
            }
        }
    }
    @Test fun unreachableSelectedSilhouetteCannotAcquireByItsCloserCenterline() {
        for(reversed in listOf(false,true)) {
            val clutter=unreachableTube(Point2D(250.0,550.0),reversed)
            assertNull(AutomaticDipRails.select(clutter,Point2D(250.0,550.0),size))
            val policy=AutomaticDipRails()
            for(time in listOf(0.0,.3,.6,1.0,1.3)) {
                assertNull(policy.observe(pose(time),ArmMeasurement.Side.left,listOf(clutter,rails()[1])))
                assertEquals(AutomaticPullUpBar.State.ambiguous,policy.state)
            }
        }
    }
    @Test fun wristAlignedRailOutranksLongerOffsetClutter() {
        // The visible handle starts lower after occlusion; an offset tube has
        // longer support and a closer endpoint, but poorer grasp alignment.
        val valid=listOf(250.0,750.0).map{x -> listOf(line(x-10,620.0,740.0),line(x,620.0,740.0))}
        val clutter=listOf(250.0,750.0).map{x -> listOf(line(x-40,590.0,740.0),line(x-30,590.0,740.0))}
        for(reversed in listOf(false,true)) {
            val candidates=clutter.zip(valid).map{(bad,good) ->
                val input=bad+good
                if(reversed) input.reversed().map{it.copy(edge=BarSegment(it.edge.b,it.edge.a))} else input
            }
            for(side in ArmMeasurement.Side.entries) {
                val index=if(side==ArmMeasurement.Side.left) 0 else 1
                val hand=Point2D(if(index==0) 250.0 else 750.0,550.0)
                assertEquals(valid[index][1].edge,AutomaticDipRails.select(candidates[index],hand,size))
                val policy=AutomaticDipRails()
                for(time in listOf(0.0,.3,.6)) assertNull(policy.observe(pose(time),side,candidates))
                val bar=assertNotNull(policy.observe(pose(1.0),side,candidates))
                assertEquals(valid[index][1].edge,bar.referenceEdge)
                assertEquals(if(index==0) ConfirmedBar.Role.leftDipRail else ConfirmedBar.Role.rightDipRail,bar.role)
            }
        }
    }
    @Test fun finiteOversizedGeometryCannotAcquireWithNonFiniteGripDistance() {
        val hand=Point2D(500.0,5e154)
        val oversized=listOf(line(490.0,1e155,2e155),line(500.0,1e155,2e155))
        assertTrue(oversized.all { it.edge.isValid && it.edge.distance(hand).isNaN() })
        assertNull(AutomaticDipRails.select(oversized,hand,ImageSize(1000.0,3e155)))
    }
    @Test fun separatedFragmentsOfOneTubeCannotBecomeTwoRails() {
        for(slope in listOf(0.0,.03)) for(opposite in listOf(false,true)) for(reversed in listOf(false,true)) for(side in ArmMeasurement.Side.entries) {
            val policy=AutomaticDipRails()
            val fragments=transverseRails(slope=slope,oppositeSilhouettes=opposite,reversed=reversed)
            for(time in listOf(0.0,.3,.6,1.0,1.3)) {
                assertNull(policy.observe(pose(time),side,fragments))
                assertEquals(AutomaticPullUpBar.State.ambiguous,policy.state)
            }
        }
    }
    @Test fun independentlyOffsetTransverseRailsStillAcquire() {
        for(slope in listOf(0.0,.03)) for(side in ArmMeasurement.Side.entries) {
            val policy=AutomaticDipRails()
            val independent=transverseRails(slope=slope,rightOffset=45.0,oppositeSilhouettes=true,rightSlope=if(slope==0.0) null else -.02)
            for(time in listOf(0.0,.3,.6)) assertNull(policy.observe(pose(time),side,independent))
            val bar=assertNotNull(policy.observe(pose(1.0),side,independent))
            assertTrue(independent[if(side==ArmMeasurement.Side.left) 0 else 1].any{it.edge.ordered==bar.referenceEdge})
            assertEquals(AutomaticPullUpBar.State.locked,policy.state)
        }
    }
    @Test fun freshSelectionCannotReplaceDistinctRailsWithOneTube() {
        for(side in ArmMeasurement.Side.entries) {
            val policy=AutomaticDipRails()
            policy.observe(pose(0.0),side,transverseRails(rightOffset=45.0))
            for(time in listOf(.3,.6,1.0,1.3,1.6)) {
                assertNull(policy.observe(pose(time),side,transverseRails(oppositeSilhouettes=true)))
                assertEquals(AutomaticPullUpBar.State.ambiguous,policy.state)
            }
        }
    }
    @Test fun losingEitherWitnessRequiresTwoFreshPairsAndRestartsDwell() {
        val policy=AutomaticDipRails()
        policy.observe(pose(0.0),ArmMeasurement.Side.left,rails())
        val changed=listOf(listOf(line(250.0)),listOf(line(770.0),line(778.0)))
        for(time in listOf(.3,.6,1.0)) {
            assertNull(policy.observe(pose(time),ArmMeasurement.Side.left,changed))
            assertEquals(AutomaticPullUpBar.State.ambiguous,policy.state)
        }
        for(time in listOf(1.3,1.6,1.9)) assertNull(policy.observe(pose(time),ArmMeasurement.Side.left,rails()))
        assertTrue(assertNotNull(policy.observe(pose(2.3),ArmMeasurement.Side.left,rails())).isValid)
    }
    @Test fun supportingLineIdentityIsSymmetricButTrackingStillRequiresOverlap() {
        val p=BarSegment(Point2D(100.0,100.0),Point2D(200.0,100.0))
        for(reversed in listOf(false,true)) {
            fun fragment(offset:Double):BarSegment {
                val a=Point2D(300.0,100+offset);val b=Point2D(400.0,100+offset)
                return BarSegment(if(reversed) b else a,if(reversed) a else b)
            }
            val boundary=fragment(5.0)
            assertTrue(AutomaticReferenceStability.sameSupportingLine(p,boundary,5.0))
            assertTrue(AutomaticReferenceStability.sameSupportingLine(boundary,p,5.0))
            assertFalse(AutomaticReferenceStability.sameSupportingLine(p,fragment(5.001),5.0))
            assertFalse(AutomaticReferenceStability.sameLine(p,fragment(0.0),5.0))
        }
        for(angle in listOf(4.99,5.01)) {
            val q=BarSegment(Point2D(300.0,100.0),Point2D(400.0,100+100*tan(angle*PI/180)))
            assertEquals(angle<5,AutomaticReferenceStability.sameSupportingLine(p,q,1000.0,5.0))
        }
    }
    @Test fun dipRejectsForearmsTorsoContoursAndOnlyOneHandle() {
        val policy=AutomaticDipRails()
        for(t in listOf(0.0,.3,.6,.9,1.2)) assertNull(policy.observe(pose(t),ArmMeasurement.Side.right,listOf(listOf(line(325.0),line(333.0)),rails()[1])))
        for(t in listOf(1.5,1.8,2.1,2.4)) assertNull(policy.observe(pose(t),ArmMeasurement.Side.right,listOf(listOf(line(250.0,520.0,700.0),line(258.0,520.0,700.0)),rails()[1])))
        assertNull(policy.observe(pose(2.7),ArmMeasurement.Side.left,listOf(rails()[0],emptyList())))
    }
    @Test fun railWitnessSurvivesPartialOcclusionButNotUnrelatedEdges() {
        val policy=AutomaticDipRails();assertNull(policy.observe(pose(0.0),ArmMeasurement.Side.right,rails()))
        val partial=listOf(listOf(line(250.0,600.0,690.0)),listOf(line(750.0,600.0,690.0)))
        assertNull(policy.observe(pose(.3),ArmMeasurement.Side.right,partial));assertNull(policy.observe(pose(.6),ArmMeasurement.Side.right,partial))
        assertNotNull(policy.observe(pose(.9),ArmMeasurement.Side.right,partial))
        policy.reset();assertNull(policy.observe(pose(1.2),ArmMeasurement.Side.left,partial))
    }
    @Test fun referenceDwellIsOrientationInvariantAndDoesNotEarnDuplicateTime() {
        val policy=AutomaticReferenceStability();val edge=rails()[0][0].edge;val reversed=BarSegment(edge.b,edge.a)
        assertTrue(AutomaticReferenceStability.sameLine(edge,reversed,1.0))
        val hand=Point2D(250.0,550.0)
        for(t in listOf(0.0,.3,.6)) assertFalse(policy.observe(listOf(edge),listOf(hand),pose(t)))
        assertFalse(policy.observe(listOf(reversed),listOf(hand),pose(.6)))
        for(t in listOf(.9,1.2,1.5)) assertFalse(policy.observe(listOf(edge),listOf(hand),pose(t)))
        assertTrue(policy.observe(listOf(edge),listOf(hand),pose(1.8)))
        assertFalse(policy.observe(listOf(edge),listOf(hand),pose(3.0)))
    }
    @Test fun pullupDistinguishesThinBarEdgesFromSeparateBars() {
        val grip=assertNotNull(AutomaticPullUpBar.grip(pose(0.0,false)))
        fun horizontal(y:Double)=BarLineCandidate(BarSegment(Point2D(200.0,y),Point2D(800.0,y)),600.0)
        assertEquals(horizontal(195.0).edge,AutomaticPullUpBar.select(listOf(horizontal(195.0),horizontal(200.0)),grip,size))
        assertNull(AutomaticPullUpBar.select(listOf(horizontal(150.0),horizontal(195.0)),grip,size))
    }
    @Test fun identityRequiresConsecutiveExtendedPoseAndLocksForSet() {
        val policy=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4)) assertNull(policy.observe(pose(t)))
        assertEquals(ExerciseCounter.Exercise.dip,policy.observe(pose(.6)))
        assertTrue(policy.lock());assertEquals(ExerciseCounter.Exercise.dip,policy.observe(pose(.8,false)))
        policy.reset();assertFalse(policy.lock());assertNull(policy.observe(pose(2.0)));assertNull(policy.observe(pose(2.6)))
    }
    @Test fun countdownPausesOnMissingFramesOrSettingsAndStartsOnlyOnce() {
        val policy=HandsFreeSetStart()
        for(i in 0..10) assertFalse(policy.observe(i*.2,true))
        assertFalse(policy.observe(2.2,true,paused=true));assertEquals(HandsFreeSetStart.Phase.waiting,policy.state.phase)
        for(i in 0..14) assertFalse(policy.observe(3+i*.2,true))
        assertTrue(policy.observe(6.0,true));assertFalse(policy.observe(6.2,true))
        policy.reset();policy.observe(0.0,true);policy.observe(.6,true);assertEquals(3,policy.state.seconds)
        policy.observe(.6,true);assertEquals(HandsFreeSetStart.Phase.waiting,policy.state.phase)
    }
    @Test fun orientationRotationDwellIsLatchedAndTimestampOrdered() {
        val policy=PhoneOrientationStability();assertTrue(policy.calibrate(identity,0.0))
        val rotated=PhoneOrientationStability.Quaternion.create(0.0,0.0,sin(PI/180),cos(PI/180))!!
        assertEquals(PhoneOrientationStability.State.stable,policy.observe(rotated,.1))
        assertEquals(PhoneOrientationStability.State.stable,policy.observe(rotated,.1))
        assertEquals(PhoneOrientationStability.State.moved,policy.observe(rotated,.4))
        assertEquals(PhoneOrientationStability.State.moved,policy.observe(identity,.5))
        assertNull(PhoneOrientationStability.Quaternion.create(0.0,0.0,0.0,0.0))
    }
    @Test fun staticSceneRequiresIndependentScaleAndTranslationAndRejectsOutlier() {
        val policy=StaticSceneStability();assertTrue(policy.calibrate(size))
        assertEquals(StaticSceneStability.State.calibrating,policy.observe(stablePatches,null,0.0))
        assertEquals(StaticSceneStability.State.stable,policy.observe(stablePatches+StaticSceneStability.PatchTranslation(900.0,900.0),0.0,.1))
        assertEquals(3,policy.latestConsensusPatches)
        val moved=List(3){StaticSceneStability.PatchTranslation(20.0,0.0)}
        assertEquals(StaticSceneStability.State.stable,policy.observe(moved,0.0,.2))
        assertEquals(StaticSceneStability.State.moved,policy.observe(moved,0.0,.5))
        assertEquals(StaticSceneStability.MovementKind.translation,policy.movementKind)
    }
    @Test fun runtimeNeverStartsFromPoseIdentityWithoutImageApparatus() {
        val runtime=WorkoutRuntime()
        for(i in 0..70) runtime.observe(frame(i*.1,candidates=emptyList()))
        assertEquals(ExerciseCounter.Exercise.dip,runtime.snapshot.exercise)
        assertNull(runtime.snapshot.referenceEdge);assertNull(runtime.snapshot.countdownSeconds)
        assertEquals(LiveSetSession.Phase.idle,runtime.snapshot.phase)
    }
    @Test fun idleAutoReportsMissingBilateralEvidenceAsFramingInsteadOfIdentity() {
        val runtime=WorkoutRuntime()
        for(i in 0..8) {
            val input=frame(i*.1,dip=false,candidates=emptyList())
            val person=input.pose.people.single()
            val oneArm=person.landmarks.filter { it.joint in ArmMeasurement.Side.left.joints }
            runtime.observe(input.copy(pose=input.pose.copy(people=listOf(PoseObservation(oneArm)))))
            assertNull(runtime.resolvedExercise)
            assertEquals("framingIncomplete",runtime.snapshot.setupIssue)
        }
        // Fresh, measurable chains need a stable identity before apparatus can
        // qualify. Neither message should claim the unseen bar was detected.
        runtime.observe(frame(.9,dip=false,candidates=emptyList()))
        assertEquals("exerciseIdentityNeeded",runtime.snapshot.setupIssue)
        for(i in 10..16) runtime.observe(frame(i*.1,dip=false,candidates=emptyList()))
        assertEquals(ExerciseCounter.Exercise.pullUp,runtime.resolvedExercise)
        assertEquals("barReferenceNeeded",runtime.snapshot.setupIssue)
        assertNull(runtime.confirmedBar)
    }
    @Test fun explicitExerciseAndRunningSetsRetainOneArmFallback() {
        val explicit=WorkoutRuntime(WorkoutExerciseSelection.pullUp)
        fun oneArmInput(seconds:Double):WorkoutRuntime.FrameInput {
            val input=frame(seconds,dip=false,candidates=emptyList())
            return input.copy(pose=input.pose.copy(people=input.pose.people.map { person ->
                PoseObservation(person.landmarks.filter { it.joint in ArmMeasurement.Side.left.joints })
            }))
        }
        explicit.observe(oneArmInput(0.0))
        assertEquals("barReferenceNeeded",explicit.snapshot.setupIssue)
        val automatic=WorkoutRuntime()
        for(i in 0..55) automatic.observe(frame(i*.1))
        assertEquals(LiveSetSession.Phase.running,automatic.session.phase)
        val input=frame(5.6)
        automatic.observe(input.copy(pose=input.pose.copy(people=input.pose.people.map { person ->
            PoseObservation(person.landmarks.filter { it.joint in ArmMeasurement.Side.left.joints })
        })))
        assertNull(automatic.snapshot.setupIssue)
        assertEquals(LiveSetSession.Phase.running,automatic.session.phase)
    }
    @Test fun setupGuidanceExplainsRequiredEvidenceWithoutMandatoryManualSetup() {
        assertEquals("Show both arms and hands clearly",WorkoutSetupGuidance.message("framingIncomplete"))
        assertTrue(assertNotNull(WorkoutSetupGuidance.message("exerciseIdentityNeeded")).contains("hang or top support"))
        val apparatus=assertNotNull(WorkoutSetupGuidance.message("barReferenceNeeded"))
        assertTrue(apparatus.contains("bar or rails"));assertTrue(apparatus.contains("beside your hands"))
        assertFalse(apparatus.contains("settings"));assertNull(WorkoutSetupGuidance.message("unknown"))
        assertEquals("Change the camera angle so both grip rails are clearly separated",WorkoutSetupGuidance.message("dipRailsUnclear"))
        assertTrue(assertNotNull(WorkoutSetupGuidance.message("pullUpBarUnclear")).contains("camera angle"))
    }
    @Test fun ambiguousDipApparatusReportsReframingAndCannotStartCountdown() {
        val runtime=WorkoutRuntime()
        for(i in 0..55) {
            val snapshot=runtime.observe(frame(i*.1,candidates=transverseRails()))
            if(snapshot.exercise!=null) assertEquals("dipRailsUnclear",snapshot.setupIssue)
            assertNull(snapshot.referenceEdge);assertNull(snapshot.countdownSeconds)
            assertEquals(0,snapshot.observedMovements)
            assertEquals(LiveSetSession.Phase.idle,snapshot.phase)
        }
        // A new, measurable pair must still earn fresh dwell and countdown.
        for(i in 56..110) runtime.observe(frame(i*.1))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        assertNull(runtime.snapshot.setupIssue)
    }
    @Test fun runtimeAcquiresAndStartsWithActualCameraEvidenceThenLocksIdentity() {
        val runtime=WorkoutRuntime()
        for(i in 0..55) runtime.observe(frame(i*.1))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        assertEquals(ExerciseCounter.Exercise.dip,runtime.snapshot.exercise)
        assertTrue(runtime.session.analyzedFrames>0)
        runtime.observe(frame(5.6,false));assertEquals(ExerciseCounter.Exercise.dip,runtime.snapshot.exercise)
        runtime.stop();runtime.nextSet();assertEquals(LiveSetSession.Phase.idle,runtime.snapshot.phase);assertNull(runtime.snapshot.exercise);assertNull(runtime.snapshot.referenceEdge)
    }
    @Test fun runtimeRejectsUnavailableCameraEvidenceAndInterruptsChangedGeometry() {
        val runtime=WorkoutRuntime()
        for(i in 0..50) runtime.observe(frame(i*.1).copy(globalScaleFraction=null))
        assertEquals(LiveSetSession.Phase.idle,runtime.snapshot.phase);assertNull(runtime.snapshot.countdownSeconds)
        for(i in 51..90) runtime.observe(frame(i*.1))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        runtime.observe(frame(9.1).copy(pose=pose(9.1).copy(imageSize=ImageSize(500.0,1000.0))))
        assertEquals(LiveSetSession.Phase.finished,runtime.snapshot.phase);assertEquals(LiveSetSession.EndReason.setupInvalidated,runtime.session.endReason)
    }
    @Test fun runtimeWatchdogAndStaleSensorCannotEarnCountdown() {
        val runtime=WorkoutRuntime()
        for(i in 0..20) runtime.observe(frame(i*.1))
        assertNotNull(runtime.snapshot.countdownSeconds)
        runtime.tick(2.6);assertNull(runtime.snapshot.countdownSeconds)
        runtime.observe(frame(2.7).copy(motionTimestamp=1.0));assertNull(runtime.snapshot.countdownSeconds)
        assertEquals(LiveSetSession.Phase.idle,runtime.snapshot.phase)
    }
    @Test fun replayConsumesSourceZeroAndNextFrameWithoutLiveCountdownOrQueuedSkip() {
        val runtime=WorkoutRuntime()
        for(i in 0..20) runtime.observe(frame(i*.1))
        assertTrue(runtime.rewindReplay(identity,0.0))
        assertTrue(runtime.snapshot.isReplayReview)
        runtime.observe(frame(0.0))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        assertEquals(0.0,runtime.session.firstSourceSeconds)
        assertEquals(1,runtime.session.analyzedFrames)
        assertNull(runtime.snapshot.countdownSeconds)
        runtime.observe(frame(.1));assertEquals(2,runtime.session.analyzedFrames)
        runtime.observe(frame(.2));assertEquals(3,runtime.session.analyzedFrames)
        assertEquals(.2,runtime.session.lastSourceSeconds)
    }
    @Test fun replayAcquisitionWaitsForStableIdentityAfterApparatusLock() {
        val runtime=WorkoutRuntime()
        val bar=BarLineCandidate(BarSegment(Point2D(200.0,195.0),Point2D(800.0,195.0)),600.0)
        fun pullFrame(seconds:Double,extended:Boolean):WorkoutRuntime.FrameInput {
            val input=frame(seconds,dip=false,candidates=listOf(listOf(bar)))
            if(extended) return input
            // Both observed chains remain usable with fixed overhead hands and
            // shoulders, but bent elbows cannot qualify the extended-hang identity.
            val person=input.pose.people.single()
            val landmarks=person.landmarks.map { landmark ->
                when(landmark.joint) {
                    PoseJoint.leftElbow -> landmark.copy(position=landmark.position.copy(x=landmark.position.x+140))
                    PoseJoint.rightElbow -> landmark.copy(position=landmark.position.copy(x=landmark.position.x-140))
                    else -> landmark
                }
            }
            return input.copy(pose=input.pose.copy(people=listOf(PoseObservation(landmarks))))
        }

        assertFalse(runtime.isReplayAcquisitionReady)
        assertFalse(runtime.rewindReplay(identity,0.0))
        for(seconds in listOf(0.0,.3,.6,.9)) runtime.observe(pullFrame(seconds,extended=false))
        val reference=assertNotNull(runtime.confirmedBar)
        assertEquals(ConfirmedBar.Method.automaticContours,reference.method)
        assertEquals(bar.edge,reference.referenceEdge)
        assertNull(runtime.resolvedExercise)
        assertFalse(runtime.isReplayAcquisitionReady)
        assertFalse(runtime.rewindReplay(identity,0.0))
        assertEquals(LiveSetSession.Phase.idle,runtime.session.phase)
        assertEquals(0,runtime.session.observedMovements)

        runtime.observe(pullFrame(1.0,extended=true))
        runtime.observe(pullFrame(1.2,extended=true))
        runtime.observe(pullFrame(1.4,extended=true))
        assertFalse(runtime.isReplayAcquisitionReady)
        runtime.observe(pullFrame(1.6,extended=true))
        assertEquals(ExerciseCounter.Exercise.pullUp,runtime.resolvedExercise)
        assertTrue(runtime.isReplayAcquisitionReady)
        assertEquals(reference,runtime.confirmedBar)
        assertEquals(LiveSetSession.Phase.idle,runtime.session.phase)
        // Recording evaluation rewinds as soon as acquisition and identity are
        // ready. It must not wait for the live three-second Go cue.
        assertTrue(runtime.rewindReplay(identity,0.0))
        runtime.observe(pullFrame(0.0,extended=true))
        assertEquals(LiveSetSession.Phase.running,runtime.session.phase)
        assertEquals(0.0,runtime.session.firstSourceSeconds)
        assertEquals(1,runtime.session.analyzedFrames)
        assertNull(runtime.snapshot.countdownSeconds)
        assertTrue(runtime.snapshot.isReplayReview)
        assertEquals(reference,runtime.confirmedBar)
    }
    @Test fun replayRewindRetainsObservedReferenceButRequiresFreshActualScene() {
        val runtime=WorkoutRuntime()
        for(i in 0..20) runtime.observe(frame(i*.1))
        val edge=assertNotNull(runtime.snapshot.referenceEdge)
        assertTrue(runtime.rewindReplay(identity,0.0))
        assertEquals(edge,runtime.snapshot.referenceEdge)
        runtime.observe(frame(0.0).copy(globalScaleFraction=null))
        assertNull(runtime.snapshot.countdownSeconds)
        runtime.observe(frame(.1))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        assertEquals(.1,runtime.session.firstSourceSeconds)
        assertEquals(1,runtime.session.analyzedFrames)
        assertNull(runtime.snapshot.countdownSeconds)
        runtime.observe(frame(.2));assertEquals(2,runtime.session.analyzedFrames)
        for(i in 3..45) runtime.observe(frame(i*.1))
        assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
        assertEquals(edge,runtime.snapshot.referenceEdge)
    }
}
