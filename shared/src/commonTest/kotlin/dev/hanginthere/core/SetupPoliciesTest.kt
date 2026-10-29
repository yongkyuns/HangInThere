package dev.hanginthere.core

import kotlin.test.*
import kotlin.math.*

class SetupPoliciesTest {
    @Test fun narrowMeasuredHandsCanAcquireARealBarWithoutStartingFromStaticPose() {
        fun frame(t: Double) = PoseResult(PresentationTime((t*1000).roundToLong(),1000),
            ImageSize(900.0,1400.0), listOf(PoseObservation(listOf(
                Landmark(PoseJoint.leftShoulder,Point2D(250.0,600.0),.9),
                Landmark(PoseJoint.leftElbow,Point2D(360.0,425.0),.9),
                Landmark(PoseJoint.leftWrist,Point2D(470.0,250.0),.9),
                Landmark(PoseJoint.rightShoulder,Point2D(650.0,600.0),.9),
                Landmark(PoseJoint.rightElbow,Point2D(590.0,425.0),.9),
                Landmark(PoseJoint.rightWrist,Point2D(530.0,250.0),.9)))),"authored",1)
        assertNotNull(AutomaticPullUpBar.grip(frame(0.0)))
        val policy = AutomaticPullUpBar()
        val identity = AutomaticExerciseDetection()
        val owner = BodyPullUpWorkout("narrow-static")
        val bar = BarSegment(Point2D(350.0,245.0),Point2D(650.0,245.0))
        for (t in listOf(0.0,.3,.6)) assertNull(policy.observe(frame(t),listOf(BarLineCandidate(bar,300.0))))
        assertEquals(bar,assertNotNull(policy.observe(frame(.9),listOf(BarLineCandidate(bar,300.0)))).referenceEdge)
        for (t in listOf(1.0,1.2,1.4,1.6)) {
            assertEquals(if(t==1.6)ExerciseCounter.Exercise.pullUp else null,identity.observe(frame(t),bar))
            assertFalse(owner.observe(frame(t),bar,PullUpCycleMotion.Camera.ready).started)
        }
        assertEquals(0,owner.record().completedCount)
    }

    @Test fun observedBarSurvivesFirstHighPullBeforeStaticIdentityHasQualified() {
        val bar=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        fun high(t:Double)=mapLandmarks(pose(t,false)) { p ->
            if(p.joint in listOf(PoseJoint.leftShoulder,PoseJoint.rightShoulder))p.copy(position=p.position.copy(y=220.0)) else p
        }
        val known=AutomaticExerciseDetection();val unknown=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4,.6)) {
            assertNull(AutomaticExerciseDetection.apparatusCandidate(high(t)))
            assertEquals(if(t==.6)ExerciseCounter.Exercise.pullUp else null,known.observe(high(t),bar))
            assertEquals(ExerciseCounter.Exercise.pullUp,known.apparatusCandidate)
            assertNull(unknown.observe(high(t)))
        }
    }
    @Test fun weakerCurrentHandsCanPreserveReferenceSearchButCannotEarnIdentityDwell() {
        val bar=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0));val policy=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4,.6,.8)) {
            val p=mapLandmarks(bentPullUpPose(t)) { if(it.joint==PoseJoint.rightWrist)it.copy(confidence=.49) else it }
            assertNull(policy.observe(p,bar));assertEquals(ExerciseCounter.Exercise.pullUp,policy.apparatusCandidate)
        }
        assertFalse(policy.lock())
    }
    @Test fun observedBarArrivalDoesNotRestartAlreadyMeasuredHangIdentity() {
        val identity=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4)) assertNull(identity.observe(pose(t,false)))
        val reference=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        assertEquals(ExerciseCounter.Exercise.pullUp,identity.observe(bentPullUpPose(.6),reference))
        assertFalse(WorkoutStartEvidence.hasStartingPose(bentPullUpPose(.6),ExerciseCounter.Exercise.pullUp,true,ArmMeasurement.Side.left,reference))
    }

    @Test fun confirmedPullupSupportIdentifiesFlexedPostureWithoutStartingOrCounting() {
        val identity=AutomaticExerciseDetection()
        val reference=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        for(ticks in listOf(0L,200_000,400_000,599_999,600_000)) {
            val p=bentPullUpPose(ticks/1e6).copy(timestamp=PresentationTime(ticks,1_000_000))
            assertNull(AutomaticExerciseDetection.candidate(p))
            assertEquals(if(ticks==600_000L) ExerciseCounter.Exercise.pullUp else null,identity.observe(p,reference))
            assertFalse(WorkoutStartEvidence.hasStartingPose(p,ExerciseCounter.Exercise.pullUp,true,ArmMeasurement.Side.left,reference))
        }
        val start=AutomaticWorkoutStart()
        for(ticks in listOf(650_000L,700_000,769_999,770_000)) {
            val p=pose(ticks/1e6,false).copy(timestamp=PresentationTime(ticks,1_000_000))
            assertEquals(ExerciseCounter.Exercise.pullUp,identity.observe(p,reference))
            val eligible=WorkoutStartEvidence.hasStartingPose(p,ExerciseCounter.Exercise.pullUp,true,ArmMeasurement.Side.left,reference)
            assertEquals(ticks==770_000L,start.observe(p.timestamp,eligible))
        }
    }

    @Test fun flexedSupportIdentityCannotBorrowDwellAcrossChangedOrMissingReferences() {
        val a=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        val b=BarSegment(Point2D(220.0,180.0),Point2D(780.0,180.0))
        for(missing in listOf(false,true)) {
            val identity=AutomaticExerciseDetection()
            for(t in listOf(0.0,.2,.4)) assertNull(identity.observe(bentPullUpPose(t),a))
            assertNull(identity.observe(bentPullUpPose(.6),if(missing)null else b))
            for(t in listOf(.8,1.0,1.2)) {
                val expected=if(!missing && t==1.2) ExerciseCounter.Exercise.pullUp else null
                assertEquals(expected,identity.observe(bentPullUpPose(t),b))
            }
            if(missing) assertEquals(ExerciseCounter.Exercise.pullUp,identity.observe(bentPullUpPose(1.4),b))
        }
        val reversed=BarSegment(a.b,a.a)
        val identity=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4)) assertNull(identity.observe(bentPullUpPose(t),a))
        assertEquals(ExerciseCounter.Exercise.pullUp,identity.observe(bentPullUpPose(.6),reversed))
    }

    @Test fun committedOverheadIdentityNeedsCurrentArmsAndSameSourceContextWithoutAnImageReference() {
        val identity=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4,.6)) assertNull(identity.observe(bentPullUpPose(t)))
        assertNull(identity.exercise)
        assertTrue(identity.confirmObservedOverheadPullUpMovement(highPull(.8)))
        assertEquals(ExerciseCounter.Exercise.pullUp,identity.exercise)
        assertTrue(identity.lock())
        assertFalse(identity.confirmObservedDipMovement(pose(1.0,true)))
        for(fault in listOf("missing","crowd","backend","revision","gap","regression")) {
            val detector=AutomaticExerciseDetection()
            for(t in listOf(0.0,.2,.4,.6))detector.observe(bentPullUpPose(t))
            val frame=highPull(.8)
            val changed=when(fault) {
                "missing" -> frame.copy(people=frame.people.map { p -> p.copy(landmarks=p.landmarks.filterNot { it.joint==PoseJoint.rightWrist }) })
                "crowd" -> frame.copy(people=frame.people+frame.people)
                "backend" -> frame.copy(backend="changed")
                "revision" -> frame.copy(requestRevision=2)
                "gap" -> frame.copy(timestamp=PresentationTime(2000,1000))
                else -> frame.copy(timestamp=PresentationTime(400,1000))
            }
            assertFalse(detector.confirmObservedOverheadPullUpMovement(changed),fault)
        }
    }

    @Test fun flexedIdentityStillNeedsValidSupportAndFullDiscoveryJointQuality() {
        val reference=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        for(fault in listOf("none","distant","outOfBounds","weakWrist","missingArm","crowd")) {
            val identity=AutomaticExerciseDetection()
            for(t in listOf(0.0,.2,.4,.6,.8)) {
                val original=bentPullUpPose(t)
                val p=when(fault) {
                    "weakWrist" -> mapLandmarks(original){if(it.joint==PoseJoint.rightWrist) it.copy(confidence=.49) else it}
                    "missingArm" -> original.copy(people=original.people.map{p->PoseObservation(p.landmarks.filter{it.joint!=PoseJoint.rightElbow})})
                    "crowd" -> original.copy(people=original.people+original.people)
                    else -> original
                }
                val ref=when(fault){"none"->null;"distant"->BarSegment(Point2D(220.0,20.0),Point2D(780.0,20.0));"outOfBounds"->BarSegment(Point2D(-1.0,190.0),Point2D(780.0,190.0));else->reference}
                assertNull(identity.observe(p,ref),fault)
            }
        }
    }

    @Test fun knownReferenceStartUsesFreshBilateralMeasurementWithoutRepeatingDiscovery() {
        val reference=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        val frame=mapLandmarks(pose(.8,false)) { if(it.joint==PoseJoint.rightWrist) it.copy(confidence=.39) else it }
        assertEquals(2,PullUpArmMeasurement(frame).arms.size)
        assertTrue(PullUpArmMeasurement(frame).isExtended)
        assertNull(AutomaticExerciseDetection.candidate(frame))
        assertTrue(WorkoutStartEvidence.hasStartingPose(frame,ExerciseCounter.Exercise.pullUp,true,ArmMeasurement.Side.left,reference))
        assertFalse(WorkoutStartEvidence.hasStartingPose(frame,ExerciseCounter.Exercise.pullUp,true,ArmMeasurement.Side.left,null))
        assertFalse(WorkoutStartEvidence.hasStartingPose(frame,ExerciseCounter.Exercise.pullUp,true,ArmMeasurement.Side.left,
            BarSegment(Point2D(220.0,50.0),Point2D(780.0,50.0))))
    }
    @Test fun confirmedReferenceCannotSupplyMissingBentOrUnmeasurableStartingArms() {
        val reference=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        val original=pose(.8,false)
        val faults=listOf(original.copy(people=original.people.map{p->PoseObservation(p.landmarks.filter{it.joint!=PoseJoint.rightWrist})}),
            mapLandmarks(original){if(it.joint==PoseJoint.rightWrist) it.copy(confidence=.29) else it},
            highPull(.8),original.copy(people=original.people+original.people),
            original.copy(timestamp=PresentationTime(1,0)))
        for(frame in faults) assertFalse(WorkoutStartEvidence.hasStartingPose(frame,ExerciseCounter.Exercise.pullUp,true,ArmMeasurement.Side.left,reference))
    }
    private fun highPull(seconds:Double)=mapLandmarks(pose(seconds,false)) { point ->
        val position=when(point.joint) {
            PoseJoint.leftShoulder -> Point2D(300.0,210.0)
            PoseJoint.rightShoulder -> Point2D(700.0,210.0)
            PoseJoint.leftElbow -> Point2D(200.0,260.0)
            PoseJoint.rightElbow -> Point2D(800.0,260.0)
            else -> point.position
        }
        point.copy(position=position)
    }
    @Test fun confirmedReferenceRetainsHighPullIdentityButCannotEarnStartingPose() {
        val identity=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4,.6)) identity.observe(pose(t,false))
        val reference=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        for(t in listOf(.8,1.0)) {
            val frame=mapLandmarks(highPull(t)) { point ->
                if(t==1.0 && point.joint in listOf(PoseJoint.leftShoulder,PoseJoint.rightShoulder))
                    point.copy(position=Point2D(point.position.x,150.0)) else point
            }
            assertNull(AutomaticExerciseDetection.candidate(frame))
            assertFalse(WorkoutStartEvidence.hasStartingPose(frame,ExerciseCounter.Exercise.pullUp,true,ArmMeasurement.Side.left,reference))
            assertEquals(ExerciseCounter.Exercise.pullUp,identity.observe(frame,reference))
        }
        assertFalse(identity.isLocked)
    }
    @Test fun confirmedHangRetentionRequiresWitnessAndFreshUnchangedSource() {
        val reference=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        for(fault in listOf("invalidReference","distantReference","missingWrist","backend","revision","gap","notWitnessed")) {
            val identity=AutomaticExerciseDetection()
            if(fault!="notWitnessed") for(t in listOf(0.0,.2,.4,.6)) identity.observe(pose(t,false))
            val base=highPull(if(fault=="gap") 1.0 else .8)
            val frame=base.copy(people=base.people.map { p -> PoseObservation(p.landmarks.filter { fault!="missingWrist" || it.joint!=PoseJoint.rightWrist }) },
                backend=if(fault=="backend") "changed" else base.backend,requestRevision=if(fault=="revision") 2 else base.requestRevision)
            val supplied=when(fault) { "invalidReference" -> BarSegment(Point2D(0.0,0.0),Point2D(0.0,0.0));"distantReference" -> BarSegment(Point2D(220.0,50.0),Point2D(780.0,50.0));else -> reference }
            assertNull(identity.observe(frame,supplied),fault)
        }
    }
    @Test fun qualifiedHangCanSurviveHighPullBeforeBarLockButCannotSupplyStartOrReference() {
        val identity=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4,.6)) identity.observe(pose(t,false))
        val reference=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        for(t in listOf(.8,1.0,1.2)) {
            val frame=highPull(t)
            assertNull(AutomaticExerciseDetection.candidate(frame))
            assertEquals(ExerciseCounter.Exercise.pullUp,identity.observe(frame))
            assertFalse(identity.isLocked)
            assertFalse(WorkoutStartEvidence.hasStartingPose(frame,ExerciseCounter.Exercise.pullUp,true,ArmMeasurement.Side.left,reference))
        }
        val unknown=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4,.6,.8)) assertNull(unknown.observe(highPull(t)))
        assertFalse(unknown.lock())
    }
    @Test fun prelockHangRetentionCannotFollowIncrementalHandDriftOrWeakWrists() {
        for(weak in listOf(false,true)) {
            val identity=AutomaticExerciseDetection()
            for(t in listOf(0.0,.2,.4,.6)) identity.observe(pose(t,false))
            for((t,offset) in listOf(.8 to .019,1.0 to .038)) {
                val frame=mapLandmarks(highPull(t)) { p -> p.copy(position=Point2D(p.position.x+1000*offset,p.position.y),
                    confidence=if(weak && p.joint in listOf(PoseJoint.leftWrist,PoseJoint.rightWrist)) .4 else p.confidence) }
                assertEquals(if(!weak && t==.8) ExerciseCounter.Exercise.pullUp else null,identity.observe(frame))
            }
        }
    }
    @Test fun measuredArmsPreserveConfirmedIdentityButDoNotEarnNewDiscoveryOrStart() {
        val reference=BarSegment(Point2D(220.0,190.0),Point2D(780.0,190.0))
        for(confidence in listOf(.4,.2)) {
            val identity=AutomaticExerciseDetection()
            for(t in listOf(0.0,.2,.4,.6)) identity.observe(pose(t,false))
            val frame=mapLandmarks(highPull(.8)) { it.copy(confidence=confidence) }
            assertNull(AutomaticExerciseDetection.candidate(frame))
            assertFalse(WorkoutStartEvidence.hasStartingPose(frame,ExerciseCounter.Exercise.pullUp,true,ArmMeasurement.Side.left,reference))
            assertEquals(if(confidence==.4) ExerciseCounter.Exercise.pullUp else null,identity.observe(frame,reference))
            assertNull(AutomaticExerciseDetection().observe(frame,reference))
        }
    }
    @Test fun slopedPullUpEdgeIsComparedAtEachHandInsteadOfItsMidpoint() {
        val size=ImageSize(720.0,1280.0)
        for(slope in listOf(-tan(10*PI/180),tan(10*PI/180))) {
            fun edge(offset:Double)=BarSegment(Point2D(210.0,300+slope*(210-350)+offset),
                Point2D(490.0,300+slope*(490-350)+offset))
            val left=Point2D(520.0,300+slope*(520-350)+3)
            val right=Point2D(180.0,300+slope*(180-350)+3)
            val grip=AutomaticPullUpBar.Grip(left,right,BarRegion(Point2D(100.0,200.0),Point2D(600.0,400.0)))
            val observed=BarLineCandidate(edge(0.0),280.0)
            assertEquals(observed.edge,AutomaticPullUpBar.select(listOf(observed),grip,size))
            // Preserve the same physical side tolerance and finite-edge checks.
            assertNull(AutomaticPullUpBar.select(listOf(BarLineCandidate(edge(28.0),280.0)),grip,size))
            val reversed=BarLineCandidate(BarSegment(observed.edge.b,observed.edge.a),280.0)
            assertEquals(observed.edge,AutomaticPullUpBar.select(listOf(reversed),grip,size))
        }
    }
    private val size=ImageSize(1000.0,1000.0)
    private fun pose(seconds:Double,dip:Boolean=true):PoseResult {
        val shoulders=if(dip) listOf(Point2D(350.0,300.0),Point2D(650.0,300.0)) else listOf(Point2D(300.0,500.0),Point2D(700.0,500.0))
        val wrists=if(dip) listOf(Point2D(250.0,550.0),Point2D(750.0,550.0)) else listOf(Point2D(250.0,200.0),Point2D(750.0,200.0))
        val joints=listOf(PoseJoint.leftShoulder,PoseJoint.leftElbow,PoseJoint.leftWrist,PoseJoint.rightShoulder,PoseJoint.rightElbow,PoseJoint.rightWrist)
        val points=listOf(shoulders[0],Point2D((shoulders[0].x+wrists[0].x)/2,(shoulders[0].y+wrists[0].y)/2),wrists[0],shoulders[1],Point2D((shoulders[1].x+wrists[1].x)/2,(shoulders[1].y+wrists[1].y)/2),wrists[1])
        return PoseResult(PresentationTime((seconds*1000).roundToLong(),1000),size,listOf(PoseObservation(joints.zip(points).map{Landmark(it.first,it.second,1.0)})),"test",1)
    }
    private fun mapLandmarks(p:PoseResult,change:(Landmark)->Landmark) = p.copy(
        people=p.people.map { person -> PoseObservation(person.landmarks.map(change)) })
    @Test fun dipSearchPreservesShortObservedSegmentsOfCurvedHandles() {
        val frame=pose(0.0)
        val search=assertNotNull(AutomaticApparatusReference.search(frame,ExerciseCounter.Exercise.dip))
        val minimum=assertNotNull(search.minimumObservedLength)
        assertEquals(40.0,minimum)
        assertNull(assertNotNull(AutomaticApparatusReference.search(pose(0.0,false),ExerciseCounter.Exercise.pullUp)).minimumObservedLength)
        val candidates=listOf(250.0,750.0).zip(search.regions).map { (x,region) ->
            val contours=listOf(0.0,10.0).map { offset -> listOf(
                Point2D(x-10+offset,555.0),Point2D(x-10+offset,600.0),
                Point2D(x+offset,640.0),Point2D(x+25+offset,675.0)) }
            assertTrue(BarLineFitter.candidates(contours,region,size).isEmpty())
            BarLineFitter.candidates(contours,region,size,minimum)
        }
        for(side in ArmMeasurement.Side.entries) {
            val adapter=AutomaticApparatusReference()
            for(time in listOf(0.0,.3,.6)) assertNull(adapter.observe(pose(time),ExerciseCounter.Exercise.dip,side,candidates))
            val bar=assertNotNull(adapter.observe(pose(1.0),ExerciseCounter.Exercise.dip,side,candidates))
            val x=if(side==ArmMeasurement.Side.left) 250.0 else 750.0
            assertEquals(x,bar.referenceEdge.a.x,1e-8);assertEquals(x,bar.referenceEdge.b.x,1e-8)
            assertEquals(555.0,bar.referenceEdge.a.y,1e-8);assertEquals(600.0,bar.referenceEdge.b.y,1e-8)
        }
    }
    @Test fun smallCropKeepsFreshPartialSilhouettesAfterPairedSupport() {
        fun frame(time:Double)=mapLandmarks(pose(time)) { landmark ->
            landmark.copy(position=Point2D(landmark.position.x*.192,landmark.position.y*.192))
        }.copy(imageSize=ImageSize(192.0,192.0))
        val search=assertNotNull(AutomaticApparatusReference.search(frame(0.0),ExerciseCounter.Exercise.dip))
        val minimum=assertNotNull(search.minimumObservedLength)
        assertEquals(24.0,minimum)
        fun observed(partial:Boolean)=listOf(48.0,144.0).zip(search.regions).map { (x,region) ->
            val contours=(if(partial) listOf(x) else listOf(x-3,x)).map { px ->
                listOf(Point2D(px,110.6),Point2D(px,if(partial)130.6 else if(px==x)140.6 else 139.6)) }
            BarLineFitter.candidates(contours,region,frame(0.0).imageSize,minimum).also {
                assertEquals(if(partial)1 else 2,it.size)
            }
        }
        val paired=observed(false);val fresh=observed(true)
        for(side in ArmMeasurement.Side.entries) {
            val adapter=AutomaticApparatusReference()
            assertNull(adapter.observe(frame(0.0),ExerciseCounter.Exercise.dip,side,paired))
            for(time in listOf(.3,.6)) {
                assertNull(adapter.observe(frame(time),ExerciseCounter.Exercise.dip,side,fresh))
                assertEquals(AutomaticPullUpBar.State.holding,adapter.state)
            }
            // Short fresh evidence sustains dwell, but the confirmed edge must
            // still satisfy the unchanged 24px validity requirement.
            val bar=assertNotNull(adapter.observe(frame(1.0),ExerciseCounter.Exercise.dip,side,paired))
            assertEquals(30.0,bar.referenceEdge.length,1e-8)
        }
    }
    @Test fun gripAssessmentPreservesObservedCropAndSuccessType() {
        val p=pose(0.0,false)
        val grip=assertIs<AutomaticPullUpBar.Grip>(AutomaticPullUpBar.assessGrip(p))
        assertEquals(Point2D(250.0,200.0),grip.left)
        assertEquals(Point2D(750.0,200.0),grip.right)
        assertEquals(BarRegion(Point2D(130.0,80.0),Point2D(870.0,240.0)),grip.region)
        assertEquals(grip,AutomaticPullUpBar.grip(p))
    }
    @Test fun gripAssessmentPreservesFirstContextAndIdentityFailure() {
        val p=pose(0.0,false)
        val cases=listOf(
            p.copy(imageSize=ImageSize(Double.NaN,1000.0)) to AutomaticPullUpBar.GripRejection.invalidContext,
            p.copy(timestamp=PresentationTime(0,0)) to AutomaticPullUpBar.GripRejection.invalidContext,
            p.copy(people=emptyList()) to AutomaticPullUpBar.GripRejection.noPerson,
            p.copy(people=p.people+p.people) to AutomaticPullUpBar.GripRejection.multiplePeople)
        for((input,reason) in cases) {
            assertEquals(reason,AutomaticPullUpBar.assessGrip(input));assertNull(AutomaticPullUpBar.grip(input))
        }
    }
    @Test fun measurableArmsDoNotGuaranteeGripLandmarkConfidence() {
        val p=pose(0.0,false)
        for((joint,reason) in listOf(PoseJoint.leftWrist to AutomaticPullUpBar.GripRejection.leftArmUnavailable,
                PoseJoint.rightWrist to AutomaticPullUpBar.GripRejection.rightArmUnavailable)) {
            val weak=mapLandmarks(p){if(it.joint==joint) it.copy(confidence=.29) else it}
            assertEquals(reason,AutomaticPullUpBar.assessGrip(weak));assertNull(AutomaticPullUpBar.grip(weak))
        }
        val marginal=mapLandmarks(p){if(it.joint==PoseJoint.rightWrist) it.copy(confidence=.4) else it}
        assertNotNull(ArmMeasurement(marginal,ArmMeasurement.Side.left).estimate)
        assertNotNull(ArmMeasurement(marginal,ArmMeasurement.Side.right).estimate)
        assertEquals(AutomaticPullUpBar.GripRejection.gripLandmarksUnavailable,AutomaticPullUpBar.assessGrip(marginal))
        assertNull(AutomaticPullUpBar.grip(marginal))
        assertIs<AutomaticPullUpBar.Grip>(AutomaticPullUpBar.assessGrip(
            mapLandmarks(p){if(it.joint==PoseJoint.rightWrist) it.copy(confidence=.5) else it}))
    }
    @Test fun gripAssessmentRetainsProjectedGeometryBoundaries() {
        val p=pose(0.0,false)
        fun shoulder(y:Double)=mapLandmarks(p){if(it.joint==PoseJoint.leftShoulder) it.copy(position=it.position.copy(y=y)) else it}
        assertEquals(AutomaticPullUpBar.GripRejection.handsNotAboveShoulders,AutomaticPullUpBar.assessGrip(shoulder(280.0)))
        assertIs<AutomaticPullUpBar.Grip>(AutomaticPullUpBar.assessGrip(shoulder(280.000001)))
        fun span(width:Double)=mapLandmarks(p){if(it.joint==PoseJoint.rightWrist) it.copy(position=it.position.copy(x=250+width)) else it}
        assertIs<AutomaticPullUpBar.Grip>(AutomaticPullUpBar.assessGrip(span(40.0)))
        assertEquals(AutomaticPullUpBar.GripRejection.handSpanTooSmall,AutomaticPullUpBar.assessGrip(span(39.999999)))
        fun wrist(y:Double)=mapLandmarks(p){if(it.joint==PoseJoint.rightWrist) it.copy(position=it.position.copy(y=y)) else it}
        assertIs<AutomaticPullUpBar.Grip>(AutomaticPullUpBar.assessGrip(wrist(300.0)))
        assertEquals(AutomaticPullUpBar.GripRejection.handsNotLevel,AutomaticPullUpBar.assessGrip(wrist(300.000001)))
    }
    @Test fun narrowSupportDoesNotPermitCollapsedOrCrossedArmChains() {
        val original=pose(0.0,false)
        for (fault in listOf("elbows", "shoulders", "crossedShoulders")) {
            val frame=mapLandmarks(original) { point ->
                when {
                    fault=="elbows" && point.joint in listOf(PoseJoint.leftElbow,PoseJoint.rightElbow) -> point.copy(position=Point2D(500.0,350.0))
                    fault=="shoulders" && point.joint in listOf(PoseJoint.leftShoulder,PoseJoint.rightShoulder) -> point.copy(position=Point2D(500.0,500.0))
                    fault=="crossedShoulders" && point.joint==PoseJoint.leftShoulder -> point.copy(position=Point2D(700.0,500.0))
                    fault=="crossedShoulders" && point.joint==PoseJoint.rightShoulder -> point.copy(position=Point2D(300.0,500.0))
                    else -> point
                }
            }
            assertEquals(2,PullUpArmMeasurement(frame).arms.size)
            assertEquals(AutomaticPullUpBar.GripRejection.armChainsNotDistinct,AutomaticPullUpBar.assessGrip(frame),fault)
            val identity=AutomaticExerciseDetection()
            assertFalse(identity.confirmObservedPullUpMovement(frame,BarSegment(Point2D(200.0,200.0),Point2D(800.0,200.0))),fault)
        }
    }
    @Test fun gripAssessmentCannotAdvanceOrResetApparatusDwell() {
        val policy=AutomaticPullUpBar()
        val candidates=listOf(BarLineCandidate(BarSegment(Point2D(200.0,200.0),Point2D(800.0,200.0)),600.0))
        assertNull(policy.observe(pose(0.0,false),candidates))
        val prior=policy.referenceStabilityDiagnostic
        repeat(20) {
            AutomaticPullUpBar.assessGrip(pose(10.0,false))
            AutomaticPullUpBar.assessGrip(pose(10.0,false).copy(people=emptyList()))
        }
        assertEquals(prior,policy.referenceStabilityDiagnostic)
        assertEquals(AutomaticPullUpBar.State.holding,policy.state)
        for(time in listOf(.3,.6)) assertNull(policy.observe(pose(time,false),candidates))
        assertNotNull(policy.observe(pose(.9,false),candidates))
    }
    private fun line(x:Double,y0:Double=590.0,y1:Double=700.0)=BarLineCandidate(BarSegment(Point2D(x,y0),Point2D(x,y1)),y1-y0)
    private fun rails()=listOf(listOf(line(250.0),line(258.0)),listOf(line(750.0),line(758.0)))
    @Test fun observedHandlesMayBeginAtTheGripWithoutAnArtificialGap() {
        for(offset in listOf(0.0,10.0,39.0)) for(reversed in listOf(false,true)) {
            val handles=listOf(listOf(240.0,250.0),listOf(740.0,750.0)).map { xs ->
                xs.map { x ->
                    val edge=line(x,550+offset,670+offset)
                    if(reversed) edge.copy(edge=BarSegment(edge.edge.b,edge.edge.a)) else edge
                }
            }
            for(side in ArmMeasurement.Side.entries) {
                val policy=AutomaticDipRails()
                assertNull(policy.observe(pose(0.0),side,handles))
                val silhouettes=handles.map{listOf(it[1])}
                for(time in listOf(.3,.6)) assertNull(policy.observe(pose(time),side,silhouettes))
                val bar=assertNotNull(policy.observe(pose(1.0),side,silhouettes))
                assertEquals(handles[if(side==ArmMeasurement.Side.left) 0 else 1][1].edge.ordered,bar.referenceEdge)
                assertEquals(AutomaticPullUpBar.State.locked,policy.state)
            }
        }
    }
    @Test fun evenASmallExtensionAboveTheGripCannotBecomeARail() {
        val forearms=listOf(listOf(240.0,250.0),listOf(740.0,750.0)).map { xs ->
            xs.map{line(it,549.999,669.999)}
        }
        for(side in ArmMeasurement.Side.entries) {
            val policy=AutomaticDipRails()
            for(time in listOf(0.0,.3,.6,1.0)) assertNull(policy.observe(pose(time),side,forearms))
            assertNotEquals(AutomaticPullUpBar.State.locked,policy.state)
        }
    }
    @Test fun aWitnessedHandleStillNeedsFreshEvidenceBelowTheGrip() {
        val paired=listOf(listOf(240.0,250.0),listOf(740.0,750.0)).map { xs ->
            xs.map{line(it,550.0,670.0)}
        }
        for(side in ArmMeasurement.Side.entries) {
            val policy=AutomaticDipRails()
            policy.observe(pose(0.0),side,paired)
            for(time in listOf(.3,.6,1.0)) assertNull(policy.observe(pose(time),side,
                listOf(listOf(line(250.0,549.999,669.999)),listOf(line(750.0,549.999,669.999)))))
            assertNotEquals(AutomaticPullUpBar.State.locked,policy.state)
        }
    }
    private fun sharedBar() = listOf(555.0,575.0).map { y ->
        BarLineCandidate(BarSegment(Point2D(200.0,y),Point2D(800.0,y)),600.0)
    }
    @Test fun sharedDipBarAcquiresThroughCommonAdapterForEitherHand() {
        for (side in ArmMeasurement.Side.entries) {
            val adapter = AutomaticApparatusReference()
            assertEquals(3, assertNotNull(AutomaticApparatusReference.search(pose(0.0),ExerciseCounter.Exercise.dip)).regions.size)
            for (time in listOf(0.0,.3,.6)) assertNull(adapter.observe(pose(time),ExerciseCounter.Exercise.dip,side,listOf(emptyList(),emptyList(),sharedBar())))
            val bar = assertNotNull(adapter.observe(pose(1.0),ExerciseCounter.Exercise.dip,side,listOf(emptyList(),emptyList(),sharedBar())))
            assertEquals(sharedBar()[0].edge,bar.referenceEdge)
            assertEquals(if(side==ArmMeasurement.Side.left) ConfirmedBar.Role.leftDipRail else ConfirmedBar.Role.rightDipRail,bar.role)
            assertEquals(AutomaticPullUpBar.State.locked,adapter.state)
        }
    }
    @Test fun sharedDipBarRequiresFreshPairAndFiniteBilateralSupport() {
        val shortened = listOf(sharedBar()[0],BarLineCandidate(BarSegment(Point2D(280.0,575.0),Point2D(400.0,575.0)),120.0))
        val distant = sharedBar().map { BarLineCandidate(BarSegment(Point2D(it.edge.a.x,it.edge.a.y+150),Point2D(it.edge.b.x,it.edge.b.y+150)),600.0) }
        val ambiguous = sharedBar() + sharedBar().map { BarLineCandidate(BarSegment(Point2D(it.edge.a.x,it.edge.a.y+50),Point2D(it.edge.b.x,it.edge.b.y+50)),600.0) }
        val aboveGrip = sharedBar().map { BarLineCandidate(BarSegment(Point2D(it.edge.a.x,it.edge.a.y-65),Point2D(it.edge.b.x,it.edge.b.y-65)),600.0) }
        for (candidates in listOf(listOf(sharedBar()[0]),shortened,distant,ambiguous,aboveGrip,listOf(line(250.0),line(260.0)))) {
            val policy = AutomaticDipBar()
            for (time in listOf(0.0,.3,.6,1.0,1.3)) assertNull(policy.observe(pose(time),ArmMeasurement.Side.left,candidates))
            assertNotEquals(AutomaticPullUpBar.State.locked,policy.state)
        }
        val policy = AutomaticDipBar()
        for (time in listOf(0.0,.3,.6)) assertNull(policy.observe(pose(time),ArmMeasurement.Side.left,sharedBar()))
        assertNull(policy.observe(pose(.9),ArmMeasurement.Side.left,listOf(sharedBar()[0])))
        for (time in listOf(1.2,1.5,1.8)) assertNull(policy.observe(pose(time),ArmMeasurement.Side.left,sharedBar()))
        assertEquals(sharedBar()[0].edge,assertNotNull(policy.observe(pose(2.2),ArmMeasurement.Side.left,sharedBar())).referenceEdge)
    }
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

    private fun bentPullUpPose(seconds:Double):PoseResult {
        val original=pose(seconds,dip=false)
        return original.copy(people=original.people.map { person ->
            PoseObservation(person.landmarks.map { landmark ->
                when(landmark.joint) {
                    PoseJoint.leftElbow -> landmark.copy(position=landmark.position.copy(x=landmark.position.x+140))
                    PoseJoint.rightElbow -> landmark.copy(position=landmark.position.copy(x=landmark.position.x-140))
                    else -> landmark
                }
            })
        })
    }

    @Test fun parallelDipAutomaticallyAcquiresStartsCompletesAndRearmsWithoutManualSetup() {
        for (side in ArmMeasurement.Side.entries) {
            val runtime = WorkoutRuntime(side = side, automaticWorkouts = true)
            var time = 0.0
            fun observe(angle: Double = 170.0, releaseLeft: Boolean = false, releaseRight: Boolean = false) {
                val raw = CounterFixtures.bilateral(time, angle, angle, exercise = ExerciseCounter.Exercise.dip)
                val adjusted = raw.copy(people = raw.people.map { person ->
                    PoseObservation(person.landmarks.map { landmark ->
                        val left = landmark.joint in ArmMeasurement.Side.left.joints
                        val release = if (left) releaseLeft else releaseRight
                        val x = (if (left) -50.0 else 50.0) + if (release) (if (left) -150.0 else 150.0) else 0.0
                        landmark.copy(position = Point2D(landmark.position.x + x, landmark.position.y + 50.0))
                    })
                })
                assertEquals(2, PullUpArmMeasurement(adjusted).arms.size, "Release requires both arms to remain visible")
                runtime.observe(WorkoutRuntime.FrameInput(adjusted, rails(), identity, time, stablePatches, 0.0, time))
                time += .15
            }
            repeat(35) { if (runtime.session.phase != LiveSetSession.Phase.running) observe() }
            assertEquals(ExerciseCounter.Exercise.dip, runtime.snapshot.exercise)
            assertEquals(ConfirmedBar.Method.automaticContours, runtime.confirmedBar?.method)
            assertEquals(if (side == ArmMeasurement.Side.left) ConfirmedBar.Role.leftDipRail else ConfirmedBar.Role.rightDipRail, runtime.confirmedBar?.role)
            assertEquals(LiveSetSession.Phase.running, runtime.session.phase)
            assertEquals(ExerciseCounter.Phase.ready, runtime.session.counter.phase)
            for (angle in listOf(80.0, 80.0, 170.0, 170.0, 80.0, 80.0)) observe(angle)
            assertEquals(1, runtime.session.observedMovements)
            repeat(9) { observe(80.0, releaseLeft = true) }
            assertEquals(LiveSetSession.Phase.running, runtime.session.phase, "One hand still holding its rail cannot end a dip set")
            assertEquals(1, runtime.session.observedMovements)
            repeat(9) { if (runtime.latestCompletedSet == null) observe(80.0, releaseLeft = true, releaseRight = true) }
            val saved = assertNotNull(runtime.latestCompletedSet)
            assertEquals("disengaged", saved.endReason)
            assertEquals(1, saved.completedCount)
            assertEquals(2, saved.reps.size)
            assertFalse(saved.reps.last().isCompleteForAnalytics)
            repeat(35) { if (runtime.session.phase != LiveSetSession.Phase.running) observe() }
            assertEquals(LiveSetSession.Phase.running, runtime.session.phase)
            assertNotEquals(saved.id, runtime.session.setId)
            assertEquals(0, runtime.session.observedMovements)
            assertEquals(saved, runtime.latestCompletedSet)
        }
    }

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
    private fun identityPose(ticks:Long,dip:Boolean,timescale:Int=1_000_000)=
        pose(0.0,dip).copy(timestamp=PresentationTime(ticks,timescale))
    @Test fun identitySourceDwellIsExactButCannotRoundUpOrUseThreeSamples() {
        for(dip in listOf(false,true)) {
            val exercise=if(dip) ExerciseCounter.Exercise.dip else ExerciseCounter.Exercise.pullUp
            for(origin in listOf(0L,1_100_000L,60_100_000L,9_007_199_254_740_990L)) {
                val policy=AutomaticExerciseDetection()
                for(delta in listOf(0L,200_000L,400_000L,599_999L))
                    assertNull(policy.observe(identityPose(origin+delta,dip)))
                assertEquals(exercise,policy.observe(identityPose(origin+600_000,dip)))
                assertFalse(policy.isLocked)
            }
            val exact=AutomaticExerciseDetection()
            for(t in listOf(1_100_000L,1_300_000L,1_500_000L)) assertNull(exact.observe(identityPose(t,dip)))
            assertEquals(exercise,exact.observe(identityPose(1_700_000,dip)))
            val sparse=AutomaticExerciseDetection()
            for(t in listOf(1_100_000L,1_400_000L,1_700_000L)) assertNull(sparse.observe(identityPose(t,dip)))
            assertEquals(exercise,sparse.observe(identityPose(1_700_001,dip)))
        }
    }
    @Test fun identitySourceGapAcceptsExactLimitAndRejectsOneTickMore() {
        for(dip in listOf(false,true)) {
            val exercise=if(dip) ExerciseCounter.Exercise.dip else ExerciseCounter.Exercise.pullUp
            for(extra in listOf(0L,1L)) {
                val policy=AutomaticExerciseDetection()
                for(t in listOf(1_000_000L,1_200_000L,1_550_000L+extra)) assertNull(policy.observe(identityPose(t,dip)))
                assertEquals(if(extra==0L) exercise else null,policy.observe(identityPose(1_650_000,dip)))
            }
        }
    }
    @Test fun identitySourceClockFaultsClearDwellAndMixedScalesRemainSupported() {
        for(dip in listOf(false,true)) {
            val exercise=if(dip) ExerciseCounter.Exercise.dip else ExerciseCounter.Exercise.pullUp
            for(fault in listOf(PresentationTime(1_500_000,1_000_000),PresentationTime(1_499_999,1_000_000),
                PresentationTime(0,0),PresentationTime(1,Int.MIN_VALUE),PresentationTime(Long.MAX_VALUE,1_000_000))) {
                val policy=AutomaticExerciseDetection()
                for(t in listOf(1_100_000L,1_300_000L,1_500_000L)) policy.observe(identityPose(t,dip))
                assertNull(policy.observe(identityPose(0,dip).copy(timestamp=fault)))
                assertNull(policy.exercise);assertNull(policy.apparatusCandidate)
                assertNull(policy.observe(identityPose(1_700_000,dip)))
            }
            val overflow=AutomaticExerciseDetection()
            assertNull(overflow.observe(identityPose(Long.MIN_VALUE,dip)))
            assertNull(overflow.observe(identityPose(Long.MAX_VALUE,dip)));assertNull(overflow.apparatusCandidate)
            val mixed=AutomaticExerciseDetection()
            for(t in listOf(PresentationTime(0,1000),PresentationTime(200_000,1_000_000),PresentationTime(400,1000)))
                assertNull(mixed.observe(identityPose(0,dip).copy(timestamp=t)))
            assertEquals(exercise,mixed.observe(identityPose(600_000,dip)))
        }
    }
    @Test fun identitySourceDwellInRuntimeCannotInventApparatusCountdownOrReps() {
        for(dip in listOf(false,true)) {
            val runtime=WorkoutRuntime()
            for(ticks in listOf(1_100_000L,1_300_000L,1_500_000L,1_700_000L)) {
                val p=identityPose(ticks,dip)
                val s=runtime.observe(frame(p.timestamp.seconds,dip,candidates=emptyList()).copy(pose=p))
                assertNull(s.referenceEdge);assertNull(s.countdownSeconds);assertEquals(0,s.observedMovements)
                assertEquals(LiveSetSession.Phase.idle,s.phase)
            }
            assertEquals(if(dip) ExerciseCounter.Exercise.dip else ExerciseCounter.Exercise.pullUp,runtime.resolvedExercise)
            assertEquals("barReferenceNeeded",runtime.snapshot.setupIssue)
        }
    }
    @Test fun qualifiedIdentitySurvivesFlexionWithFreshBilateralGrip() {
        val policy=AutomaticExerciseDetection()
        for(t in listOf(0.0,.2,.4,.7)) policy.observe(pose(t,dip=false))
        assertEquals(ExerciseCounter.Exercise.pullUp,policy.exercise)
        for(t in listOf(.9,1.1,1.3,1.5)) {
            val bent=bentPullUpPose(t)
            assertNull(AutomaticExerciseDetection.candidate(bent))
            assertEquals(ExerciseCounter.Exercise.pullUp,policy.observe(bent))
            assertFalse(policy.isLocked)
            assertFalse(HandsFreeSetStart.isStartingPosition(bent,BarSegment(Point2D(200.0,195.0),Point2D(800.0,195.0))))
        }
    }
    @Test fun unlockedFlexionIdentityNeverBridgesLostGripOrSourceContext() {
        for(fault in listOf("missingHand","lowConfidence","crowd","gap","duplicate","reverse","resize","invalidTime")) {
            val policy=AutomaticExerciseDetection()
            for(t in listOf(0.0,.2,.4,.7)) policy.observe(pose(t,dip=false))
            val original=bentPullUpPose(.9)
            val invalid=when(fault) {
                "gap" -> bentPullUpPose(1.5)
                "duplicate" -> bentPullUpPose(.7)
                "reverse" -> bentPullUpPose(.6)
                "resize" -> original.copy(imageSize=ImageSize(800.0,1400.0))
                "invalidTime" -> original.copy(timestamp=PresentationTime(0,0))
                "crowd" -> original.copy(people=original.people+original.people)
                else -> original.copy(people=original.people.map { person -> PoseObservation(person.landmarks
                    .filter { fault!="missingHand" || it.joint!=PoseJoint.rightWrist }
                    .map { if(fault=="lowConfidence") it.copy(confidence=.1) else it }) })
            }
            assertNull(policy.observe(invalid),fault)
            assertNull(policy.exercise,fault)
            assertNull(policy.observe(bentPullUpPose(1.7)),fault)
        }
    }
    @Test fun firstPullDoesNotResetAcquisitionButCannotStartUntilFreshHang() {
        val runtime=WorkoutRuntime(automaticWorkouts=true)
        val bar=BarLineCandidate(BarSegment(Point2D(200.0,195.0),Point2D(800.0,195.0)),600.0)
        fun input(t:Double,bent:Boolean=false):WorkoutRuntime.FrameInput {
            val original=frame(t,dip=false,candidates=listOf(listOf(bar)))
            return if(bent) original.copy(pose=bentPullUpPose(t)) else original
        }
        for(t in listOf(0.0,.2,.4,.6)) runtime.observe(input(t))
        assertEquals(ExerciseCounter.Exercise.pullUp,runtime.resolvedExercise)
        for(i in 4..15) runtime.observe(input(i*.2,bent=true))
        val reference=assertNotNull(runtime.confirmedBar)
        assertEquals(bar.edge,reference.referenceEdge)
        assertTrue(runtime.isReplayAcquisitionReady)
        assertEquals(LiveSetSession.Phase.idle,runtime.session.phase)
        assertEquals(0,runtime.session.observedMovements)
        for(i in 16..35) runtime.observe(input(i*.2))
        assertEquals(reference,runtime.confirmedBar)
        assertEquals(LiveSetSession.Phase.running,runtime.session.phase)
        assertEquals(0,runtime.session.observedMovements)
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
        val identity=assertNotNull(WorkoutSetupGuidance.message("exerciseIdentityNeeded"))
        assertTrue(identity.contains("steady"));assertTrue(identity.contains("support"))
        val apparatus=assertNotNull(WorkoutSetupGuidance.message("barReferenceNeeded"))
        assertTrue(apparatus.contains("bar or rails"));assertTrue(apparatus.contains("beside your hands"))
        assertFalse(apparatus.contains("settings"));assertNull(WorkoutSetupGuidance.message("unknown"))
        assertEquals("Show the bar at both hands, or two clearly separated rails",WorkoutSetupGuidance.message("dipRailsUnclear"))
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
    @Test fun sharedDipBarStartsRuntimeWithCompleteCountdownForEitherHand() {
        for(side in ArmMeasurement.Side.entries) {
            val runtime=WorkoutRuntime(side=side)
            val countdown=mutableSetOf<Int>()
            for(i in 0..55) {
                val snapshot=runtime.observe(frame(i*.1,candidates=listOf(emptyList(),emptyList(),sharedBar())))
                snapshot.countdownSeconds?.let { countdown+=it }
                assertEquals(0,snapshot.observedMovements)
            }
            assertEquals(setOf(3,2,1),countdown)
            assertEquals(LiveSetSession.Phase.running,runtime.snapshot.phase)
            assertEquals(sharedBar()[0].edge,runtime.snapshot.referenceEdge)
            assertNull(runtime.snapshot.setupIssue)
        }
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
            return input.copy(pose=bentPullUpPose(seconds))
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
