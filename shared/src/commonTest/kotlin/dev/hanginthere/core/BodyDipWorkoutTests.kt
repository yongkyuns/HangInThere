package dev.hanginthere.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.math.PI
import kotlin.math.tan
import kotlin.test.*

/** Analytic movement/continuity controls, not camera-angle accuracy evidence. */
internal object BodyDipFixtures {
    fun pose(ms: Long, drop: Double = 0.0, degrees: Double = 170.0,
        rightDrop: Double = drop, rightDegrees: Double = degrees): PoseResult {
        val points = listOf(250.0, 650.0).flatMapIndexed { i, x ->
            val joints = ArmMeasurement.Side.entries[i].joints
            val d = if(i == 0)drop else rightDrop
            val a = if(i == 0)degrees else rightDegrees
            val height = 320.0 - d; val offset = height / (2 * tan(a * PI / 360))
            listOf(Landmark(joints[0], Point2D(x, 280+d), .9),
                Landmark(joints[1], Point2D(x + if(i == 0)-offset else offset, 600-height/2), .9),
                Landmark(joints[2], Point2D(x, 600.0), .9))
        }
        return PoseResult(PresentationTime(ms,1000), ImageSize(900.0,1400.0),
            listOf(PoseObservation(points)), "authored", 1)
    }
    fun cycle(offset: Long = 0) = (0L..400L step 40).map { pose(offset+it) } +
        (440L..680L step 40).map { pose(offset+it,100.0,110.0) } +
        (720L..1000L step 40).map { pose(offset+it) }
    fun shifted(pose: PoseResult, both: Boolean = true) = pose.copy(people = pose.people.map { person ->
        person.copy(landmarks = person.landmarks.map { p ->
            if(both || p.joint in ArmMeasurement.Side.left.joints) p.copy(position = Point2D(p.position.x,p.position.y+200)) else p
        })
    })
}

class BodyDipMotionTests {
    private fun count(frames: List<PoseResult>): Int {
        val motion = BodyDipMotion()
        return frames.map { motion.observe(it,BodyDipMotion.Camera.ready) }.last().cycles
    }
    @Test fun measuredTopLowerAndReturnCountWithoutHandsRailsOrLegs() {
        assertEquals(1,count(BodyDipFixtures.cycle()))
        assertEquals(3,count((0..2).flatMap { BodyDipFixtures.cycle(it*1040L) }))
        assertTrue(BodyDipFixtures.cycle().all { it.gripObservation == null && it.people.single().landmarks.size == 6 })
    }
    @Test fun setupStaticHoldsShallowBobsAndDismountsDoNotCount() {
        assertEquals(0,count((0L..2000L step 40).map { BodyDipFixtures.pose(it) }))
        assertEquals(0,count(BodyDipFixtures.cycle().dropWhile { it.timestamp.value < 440 }))
        assertEquals(0,count(BodyDipFixtures.cycle().filter { it.timestamp.value <= 680 }))
        assertEquals(0,count(BodyDipFixtures.cycle().map {
            if(it.timestamp.value in 440..680)BodyDipFixtures.pose(it.timestamp.value,20.0,160.0) else it
        }))
    }
    @Test fun bendAndTravelMustAgreeForBothArms() {
        for(kind in listOf("onlyBend","onlyTravel","oneArm")) {
            assertEquals(0,count(BodyDipFixtures.cycle().map {
                if(it.timestamp.value !in 440..680)it else when(kind) {
                    "onlyBend" -> BodyDipFixtures.pose(it.timestamp.value,0.0,110.0)
                    "onlyTravel" -> BodyDipFixtures.pose(it.timestamp.value,100.0,170.0)
                    else -> BodyDipFixtures.pose(it.timestamp.value,100.0,110.0,rightDrop=0.0,rightDegrees=170.0)
                }
            }))
        }
    }
    @Test fun missingDuplicateLowConfidenceAndCompetingBodiesCannotCompleteAnAttempt() {
        for(kind in listOf("missing","duplicate","confidence","multiple")) {
            assertEquals(0,count(BodyDipFixtures.cycle().map { p ->
                if(p.timestamp.value !in 440..680)p else when(kind) {
                    "multiple" -> p.copy(people=p.people+p.people)
                    "missing" -> p.copy(people=p.people.map { it.copy(landmarks=it.landmarks.dropLast(1)) })
                    "duplicate" -> p.copy(people=p.people.map { it.copy(landmarks=it.landmarks+it.landmarks.first()) })
                    else -> p.copy(people=p.people.map { it.copy(landmarks=it.landmarks.map { it.copy(confidence=.1) }) })
                }
            }))
        }
    }
    @Test fun cameraLossAndMovementCannotDonateTopOrReturnEvidence() {
        for(camera in listOf(BodyDipMotion.Camera.unavailable,BodyDipMotion.Camera.moved)) {
            val motion=BodyDipMotion()
            val result=BodyDipFixtures.cycle().map {
                motion.observe(it,if(it.timestamp.value in 440..680)camera else BodyDipMotion.Camera.ready)
            }.last()
            assertEquals(0,result.cycles)
        }
    }
    @Test fun sourceGapRegressionAndContextChangesBreakAnAttempt() {
        for(kind in listOf("gap","regression","size","backend","revision")) {
            var frames=BodyDipFixtures.cycle()
            frames=if(kind=="gap")frames.filter { it.timestamp.value !in 440..680 } else frames.map {
                if(it.timestamp.value != 720L)it else when(kind) {
                    "regression" -> it.copy(timestamp=PresentationTime(100,1000))
                    "size" -> it.copy(imageSize=ImageSize(950.0,1400.0))
                    "backend" -> it.copy(backend="other")
                    else -> it.copy(requestRevision=2)
                }
            }
            assertEquals(0,count(frames))
        }
    }
    @Test fun rejectedTimesCannotResetTheHighWaterFenceOrBeSerializedAsNonFinite() {
        val motion=BodyDipMotion()
        BodyDipFixtures.cycle().takeWhile { it.timestamp.value <= 680 }.forEach { motion.observe(it,BodyDipMotion.Camera.ready) }
        val first=motion.observe(BodyDipFixtures.pose(100),BodyDipMotion.Camera.ready)
        assertEquals(1,first.interruptedAttempts)
        for(ms in 0L..640L step 40) {
            val state=motion.observe(BodyDipFixtures.pose(ms),BodyDipMotion.Camera.ready)
            assertFalse(state.sourceAccepted);assertEquals(BodyDipMotion.Phase.seekingTop,state.phase)
            assertNull(state.event);assertEquals(1,state.interruptedAttempts)
        }
        val invalid=motion.observe(BodyDipFixtures.pose(720).copy(timestamp=PresentationTime(0,0)),BodyDipMotion.Camera.ready)
        assertNull(invalid.seconds)
        assertTrue(WorkoutRecordCodec.json.encodeToString(invalid).contains("sourceRegression"))
    }
    @Test fun bothEndpointsNeedFreshObservedDwell() {
        assertEquals(0,count(BodyDipFixtures.cycle().filter { it.timestamp.value !in 480..680 }))
        assertEquals(0,count(BodyDipFixtures.cycle().filter { it.timestamp.value <= 720 }))
        val motion=BodyDipMotion()
        val prefix=BodyDipFixtures.cycle().filter { it.timestamp.value <= 720 }
        prefix.forEach { motion.observe(it,BodyDipMotion.Camera.ready) }
        val interrupted=motion.observe(BodyDipFixtures.pose(760,100.0,110.0),BodyDipMotion.Camera.ready)
        assertEquals("returnNotSustained",interrupted.reason);assertEquals(1,interrupted.interruptedAttempts)
        assertEquals(0,interrupted.cycles)
    }
    @Test fun topBottomAndReturnUseExactSourceDwellBoundaries() {
        val m=BodyDipMotion()
        fun observe(ticks: Long, lowered: Boolean=false): BodyDipMotion.Snapshot = m.observe(
            BodyDipFixtures.pose(ticks/1000,if(lowered)100.0 else 0.0,if(lowered)110.0 else 170.0)
                .copy(timestamp=PresentationTime(ticks,1_000_000)),BodyDipMotion.Camera.ready)
        observe(0);observe(40_000)
        assertEquals(BodyDipMotion.Phase.seekingTop,observe(289_999).phase)
        assertEquals(BodyDipMotion.Phase.ready,observe(290_000).phase)
        observe(440_000,true)
        assertEquals(BodyDipMotion.Phase.outbound,observe(559_999,true).phase)
        assertEquals(BodyDipMotion.Phase.returning,observe(560_000,true).phase)
        observe(720_000)
        assertEquals(0,observe(839_999).cycles)
        assertEquals(1,observe(840_000).cycles)
    }
}

class BodyDipWorkoutTests {
    private fun workout(id: String="set") = BodyDipWorkout(id).also { w ->
        BodyDipFixtures.cycle().forEach { w.observe(it,BodyDipMotion.Camera.ready) }
    }
    private fun leaving(ms: Long, torsoDrop: Double = 200.0, leftHandBack: Boolean = false): PoseResult =
        BodyDipFixtures.pose(ms).let { pose -> pose.copy(people=pose.people.map { person ->
            person.copy(landmarks=person.landmarks.map { point ->
                val wrist=point.joint in listOf(PoseJoint.leftWrist,PoseJoint.rightWrist)
                val drop=if(wrist)if(leftHandBack && point.joint==PoseJoint.leftWrist)0.0 else 200.0 else torsoDrop
                point.copy(position=Point2D(point.position.x,point.position.y+drop))
            })
        }) }
    @Test fun aWitnessedTorsoExitCanFinishWhileWalkingAwayRaisesItsImageProjection() {
        val w=workout()
        for(ms in 1040L..2000L step 40) {
            val state=w.observe(leaving(ms,if(ms<=1440)200.0 else 20.0),BodyDipMotion.Camera.ready)
            assertNull(state.endReason)
        }
        assertEquals("disengaged",w.observe(leaving(2040,20.0),BodyDipMotion.Camera.ready).endReason)
        assertEquals(1,w.record().completedCount)
    }
    @Test fun returningEitherWristRevokesTheRememberedTorsoExit() {
        val w=workout()
        for(ms in 1040L..1440L step 40)w.observe(leaving(ms),BodyDipMotion.Camera.ready)
        w.observe(leaving(1480,20.0,leftHandBack=true),BodyDipMotion.Camera.ready)
        for(ms in 1520L..3200L step 40) {
            assertNull(w.observe(leaving(ms,20.0),BodyDipMotion.Camera.ready).endReason)
        }
        assertEquals(1,w.record().completedCount)
    }
    @Test fun unavailableCameraOrMissingBodyRevokesTheRememberedTorsoExit() {
        for(kind in listOf("camera","body")) {
            val w=workout()
            for(ms in 1040L..1440L step 40)w.observe(leaving(ms),BodyDipMotion.Camera.ready)
            w.observe(leaving(1480,20.0).let { if(kind=="body")it.copy(people=emptyList()) else it },
                if(kind=="camera")BodyDipMotion.Camera.unavailable else BodyDipMotion.Camera.ready)
            for(ms in 1520L..3200L step 40) {
                assertNull(w.observe(leaving(ms,20.0),BodyDipMotion.Camera.ready).endReason)
            }
        }
    }
    @Test fun aSourceGapCannotCarryTorsoExitEvidenceIntoANewReleaseDwell() {
        val w=workout()
        for(ms in 1040L..1440L step 40)w.observe(leaving(ms),BodyDipMotion.Camera.ready)
        for(ms in 1840L..3200L step 40) {
            assertNull(w.observe(leaving(ms,20.0),BodyDipMotion.Camera.ready).endReason)
        }
    }
    @Test fun disabledAutomaticStartCannotAccumulateCyclesBeforeAnExplicitTap() {
        val w=BodyDipWorkout("manual",1234)
        assertFalse(w.startManually())
        BodyDipFixtures.cycle().forEach {
            val state=w.observe(it,BodyDipMotion.Camera.ready,automaticStart=false)
            assertFalse(state.started);assertEquals(0,state.motion.cycles)
        }
        for(ms in 1040L..1400L step 40)w.observe(BodyDipFixtures.pose(ms),BodyDipMotion.Camera.ready,automaticStart=false)
        assertTrue(w.startManually());assertFalse(w.startManually())
        assertEquals(1400,w.record().trackedAtEpochMilliseconds!!-1234)
        BodyDipFixtures.cycle(1500).forEach { w.observe(it,BodyDipMotion.Camera.ready,automaticStart=false) }
        assertEquals(1,w.record().completedCount);assertEquals(1.4,w.record().startedAt)
    }
    @Test fun lostCameraOrInvalidSourceRevokesAManualPreparation() {
        for(kind in listOf("camera","source")) {
            val w=BodyDipWorkout("manual")
            for(ms in 0L..400L step 40)w.observe(BodyDipFixtures.pose(ms),BodyDipMotion.Camera.ready,automaticStart=false)
            w.observe(BodyDipFixtures.pose(if(kind=="source")100 else 440),
                if(kind=="camera")BodyDipMotion.Camera.unavailable else BodyDipMotion.Camera.ready,automaticStart=false)
            assertFalse(w.startManually());assertFalse(w.hasStarted)
        }
    }
    @Test fun calendarStartAdvancesPastPreparationWhileSourceStartRemainsOriginal() {
        val w=BodyDipWorkout("calendar",1000)
        for(ms in 0L..10000L step 40)w.observe(BodyDipFixtures.pose(ms),BodyDipMotion.Camera.ready)
        BodyDipFixtures.cycle(10040).forEach { w.observe(it,BodyDipMotion.Camera.ready) }
        assertEquals(1,w.record().completedCount)
        assertEquals(1000+(w.record().startedAt!!*1000).toLong(),w.record().trackedAtEpochMilliseconds)
    }
    @Test fun aStaticArmsDownStanceMayPrepareButCannotStartAnAutomaticWorkout() {
        val workout=BodyDipWorkout("standing")
        for(ms in 0L..10000L step 40) {
            val state=workout.observe(BodyDipFixtures.pose(ms),BodyDipMotion.Camera.ready)
            assertFalse(state.started);assertNull(state.endReason)
        }
        assertEquals(0,workout.record().completedCount)
        assertNull(workout.draftDeltaJson())
    }
    @Test fun completeRecordsKeepTheirOwnMethodAndSurviveHistoryValidation() {
        val workout=workout();workout.finish("manual")
        val record=workout.record();assertEquals(1,record.completedCount)
        assertEquals(BodyDipMotion.countMethod,record.countMethod);assertEquals(2,record.schemaVersion)
        assertNull(record.referenceEdge);assertNull(record.grip)
        assertEquals(BodyDipMotion.countMethod,record.reps.single().countMethod)
        assertNotNull(record.reps.single().extremeAt)
        val state=WorkoutLibraryState(entries=listOf(WorkoutLibraryEntry(WorkoutLibrary.compact(record))))
        WorkoutLibrary.validate(state)
        assertEquals(state,WorkoutRecordCodec.json.decodeFromString<WorkoutLibraryState>(WorkoutRecordCodec.json.encodeToString(state)))
        assertEquals("Observed image cycle",WorkoutEvidence.assess(record.reps.single()).title)
        val legacy=record.copy(schemaVersion=1,countMethod=null,countPolicyVersion=null,
            reps=record.reps.map { it.copy(countMethod=null) })
        val context=WorkoutContext(assistance=WorkoutAssistance.none,addedLoadKg=0.0,equipment="dip bars",cameraView="front")
        val correction=WorkoutReviewCorrection(context=context)
        val newKey=WorkoutCorrections.project(record,correction).single().comparisonKey
        val oldKey=WorkoutCorrections.project(legacy,correction).single().comparisonKey
        assertNotNull(newKey);assertNotEquals(newKey,oldKey)
        WorkoutComparisonKey.decode(newKey)
        val unknownMethod = WorkoutLibraryEntry(WorkoutLibrary.compact(record.copy(countMethod="unregistered")))
        assertFailsWith<IllegalArgumentException> { WorkoutLibrary.validate(state.copy(entries=listOf(unknownMethod))) }
    }
    @Test fun onlyPositiveBilateralReleaseEndsASetAndTheNextOwnerStartsEmpty() {
        val workout=workout()
        for(ms in 1040L..2400L step 40) {
            val state=workout.observe(BodyDipFixtures.shifted(BodyDipFixtures.pose(ms)),BodyDipMotion.Camera.ready)
            if(state.endReason != null)break
        }
        assertEquals("disengaged",workout.record().endReason);assertEquals(1,workout.record().completedCount)
        val next=BodyDipWorkout("next")
        assertFalse(next.hasStarted);assertEquals(0,next.record().completedCount)
        BodyDipFixtures.cycle(3000).forEach { next.observe(it,BodyDipMotion.Camera.ready) }
        assertEquals(1,next.record().completedCount)
        assertTrue(next.record().reps.all { it.setId=="next" })
    }
    @Test fun goalsPlansAndProgressKeepTheNewMethodSeparateFromLegacy() {
        val w=workout();w.finish("manual")
        val current=w.record().copy(trackedAtEpochMilliseconds=2000)
        val legacy=current.copy(id="old",schemaVersion=1,countMethod=null,countPolicyVersion=null,
            trackedAtEpochMilliseconds=1000,reps=(1..10).map { n -> current.reps.single().copy(
                id="old:$n",setId="old",ordinal=n,countMethod=null) })
        val report=WorkoutProgression.report(listOf(legacy,current),ExerciseCounter.Exercise.dip)
        assertEquals(1,report.completedReps);assertEquals(2.0,report.goal.target)
        assertEquals(BodyDipMotion.countMethod,report.goal.countMethod);report.goal.validate()
        assertEquals(10,WorkoutProgression.report(listOf(legacy,current),ExerciseCounter.Exercise.dip,
            WorkoutGoal(ExerciseCounter.Exercise.dip,WorkoutGoalMetric.completedReps,11.0)).completedReps)
        val context=WorkoutContext(assistance=WorkoutAssistance.none,addedLoadKg=0.0,equipment="bars",cameraView="front")
        val key=context.comparisonKey(ExerciseCounter.Exercise.dip,WorkoutAnalysis.metricVersion,
            BodyDipMotion.policyVersion,BodyDipMotion.countMethod)!!
        val state=WorkoutLibraryState(entries=listOf(WorkoutLibraryEntry(WorkoutLibrary.compact(current))),
            goals=listOf(WorkoutLibraryGoal(key,report.goal.copy(createdAtEpochMilliseconds=2000))),
            plans=listOf(WorkoutPlan("body-plan","Body dips",0,listOf(WorkoutPlanItem("body-item",
                ExerciseCounter.Exercise.dip,WorkoutGoalMetric.completedReps,1.0,1,context,
                BodyDipMotion.countMethod,BodyDipMotion.policyVersion)))))
        WorkoutLibrary.validate(state)
        assertFailsWith<IllegalArgumentException> { WorkoutComparisonKey.decode(key.replace("\"1\",\"${BodyDipMotion.countMethod}","\"01\",\"${BodyDipMotion.countMethod}")) }
    }
    @Test fun oneHandAdjustmentAndLongSupportHoldsCannotEndASet() {
        for(bothStill in listOf(true,false)) {
            val w=workout()
            for(ms in 1040L..8000L step 40) {
                val p=BodyDipFixtures.pose(ms).let { if(bothStill)it else BodyDipFixtures.shifted(it,false) }
                assertNull(w.observe(p,BodyDipMotion.Camera.ready).endReason)
            }
            assertEquals(1,w.record().completedCount)
        }
    }
    @Test fun handsMovingWhileTorsoStaysAtTheTopCannotSupplyRelease() {
        val w=workout()
        for(ms in 1040L..4000L step 40) {
            val p=BodyDipFixtures.pose(ms).let { pose -> pose.copy(people=pose.people.map { person ->
                person.copy(landmarks=person.landmarks.map { point ->
                    if(point.joint in listOf(PoseJoint.leftWrist,PoseJoint.rightWrist))
                        point.copy(position=Point2D(point.position.x+100,point.position.y)) else point
                })
            }) }
            assertNull(w.observe(p,BodyDipMotion.Camera.ready).endReason)
        }
    }
    @Test fun aLongBottomHoldWithStationaryHandsCannotSupplyRelease() {
        val w=workout()
        for(ms in 1040L..4000L step 40) {
            assertNull(w.observe(BodyDipFixtures.pose(ms,100.0,110.0),BodyDipMotion.Camera.ready).endReason)
        }
        assertEquals(1,w.record().completedCount)
    }
    @Test fun continuousMissingBodiesEndAsTrackingLostWithoutInventingRelease() {
        val w=workout()
        for(ms in 1040L..4400L step 40) {
            val state=w.observe(BodyDipFixtures.pose(ms).copy(people=emptyList()),BodyDipMotion.Camera.ready)
            if(state.endReason != null)break
        }
        assertEquals("trackingLost",w.record().endReason);assertEquals(1,w.record().completedCount)
    }
    @Test fun actualCameraMovementImmediatelyEndsTheCurrentCameraEpoch() {
        val w=workout()
        val state=w.observe(BodyDipFixtures.pose(1040),BodyDipMotion.Camera.moved,false)
        assertEquals("setupInvalidated",state.endReason)
        assertEquals(1,w.record().completedCount)
        assertFailsWith<IllegalStateException> { w.observe(BodyDipFixtures.pose(1080),BodyDipMotion.Camera.ready) }
    }
    @Test fun briefUnavailableCameraRetainsOnlyReleaseLocationsAndCannotDonateReleaseDwell() {
        val w=workout()
        w.observe(BodyDipFixtures.pose(1040),BodyDipMotion.Camera.unavailable)
        for(ms in 1080L..2520L step 40) {
            val state=w.observe(BodyDipFixtures.shifted(BodyDipFixtures.pose(ms)),
                if(ms==1520L)BodyDipMotion.Camera.unavailable else BodyDipMotion.Camera.ready)
            assertNull(state.endReason)
            assertEquals(1,state.motion.cycles)
        }
        val end=w.observe(BodyDipFixtures.shifted(BodyDipFixtures.pose(2560)),BodyDipMotion.Camera.ready)
        assertEquals("disengaged",end.endReason)
        assertEquals(1,w.record().completedCount)
    }
    @Test fun interruptedAndManuallyEndedAttemptsDoNotInventCompletion() {
        for(reason in listOf("manual","cameraMoved")) {
            val w=BodyDipWorkout("set")
            BodyDipFixtures.cycle().filter { it.timestamp.value <= 680 }.forEach { w.observe(it,BodyDipMotion.Camera.ready) }
            w.finish(reason);w.finish("ignored")
            assertEquals(reason,w.record().endReason);assertEquals(0,w.record().completedCount)
            assertEquals(1,w.record().reps.size)
            assertEquals(if(reason=="manual")1 else 0,WorkoutFeedback.summary(w.record()).practiceAttempts)
            assertFailsWith<IllegalStateException> { w.observe(BodyDipFixtures.pose(900),BodyDipMotion.Camera.ready) }
        }
    }
    @Test fun bridgeDeltasPreserveCountsMethodAndStableClosedRepIdentifiers() {
        val b=BodyDipWorkoutBridge("bridge")
        var draft: WorkoutSetRecord?=null
        BodyDipFixtures.cycle().forEach { p ->
            val raw=b.observeJson(WorkoutRecordCodec.json.encodeToString(p),"ready",true)
            assertEquals(b.hasStarted,WorkoutRecordCodec.json.decodeFromString<BodyDipWorkout.Snapshot>(raw).started)
            b.draftDeltaJson()?.let { draft=WorkoutRecordCodec.mergeDraft(draft,WorkoutRecordCodec.json.decodeFromString(it)) }
        }
        b.finish("manual")
        val set=WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(b.setJson())
        assertEquals(1,b.closedRecordCount);assertEquals(WorkoutRecordCodec.summary(set).reps,draft?.reps)
        assertEquals(set.reps.single(),WorkoutRecordCodec.json.decodeFromString<RepRecord>(b.lastClosedRecordJson()!!))
    }
}
