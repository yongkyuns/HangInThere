package dev.hanginthere.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class WorkoutAnalysisTests {
    private fun frame(time:Double, angle:Double?, usable:Boolean=true, released:Boolean=false) = MotionSample(time,angle,angle,angle?.div(1000),usable,released)
    private fun event(time:Double) = ExerciseCounter.Event(ExerciseCounter.Outcome.movement,time,"policy v8")
    @Test fun pullUpAcceptanceAndReturnAreDifferentMilestones() {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        a.observe(frame(0.0,170.0),ExerciseCounter.Phase.ready,null)
        a.observe(frame(.2,130.0),ExerciseCounter.Phase.outbound,null)
        a.observe(frame(.4,80.0),ExerciseCounter.Phase.returning,event(.4))
        assertTrue(a.activeAccepted);assertTrue(a.records.isEmpty())
        a.observe(frame(.6,170.0),ExerciseCounter.Phase.ready,null)
        val r=a.records.single()
        assertEquals(.4,r.acceptedAt);assertEquals(.6,r.returnedAt)
        assertEquals(.4,r.outboundSeconds);assertEquals(.2,r.returnSeconds!!,1e-10)
        assertEquals(1.0,r.left.coverage);assertEquals(76.0 / .6,r.left.mean!!,1e-10)
    }
    @Test fun acceptedPullUpSurvivesDismountWithoutInventingReturn() {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        a.observe(frame(0.0,170.0),ExerciseCounter.Phase.ready,null)
        a.observe(frame(.2,90.0),ExerciseCounter.Phase.outbound,null)
        a.observe(frame(.4,80.0),ExerciseCounter.Phase.returning,event(.4))
        a.finish("disengaged")
        val r=a.records.single();assertEquals("accepted",r.outcome)
        assertNull(r.returnedAt);assertNull(r.returnSeconds);assertEquals(.4,r.acceptedAt)
        a.finish("manual");assertEquals(1,a.records.size)
    }
    @Test fun actualExtremeCanFollowAcceptanceThreshold() {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        a.observe(frame(0.0,170.0),ExerciseCounter.Phase.ready,null)
        a.observe(frame(.2,130.0),ExerciseCounter.Phase.outbound,null)
        a.observe(frame(.4,95.0),ExerciseCounter.Phase.returning,event(.4))
        a.observe(frame(.6,70.0),ExerciseCounter.Phase.returning,null)
        a.observe(frame(.8,170.0),ExerciseCounter.Phase.ready,null)
        val r=a.records.single();assertEquals(.4,r.acceptedAt);assertEquals(.6,r.extremeAt)
        assertEquals(.6,r.outboundSeconds);assertEquals(.2,r.returnSeconds!!,1e-10)
    }
    @Test fun downsamplingPreservesBriefSingleArmOcclusion() {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        a.observe(frame(0.0,170.0),ExerciseCounter.Phase.ready,null)
        a.observe(frame(.05,120.0),ExerciseCounter.Phase.outbound,null)
        a.observe(frame(.06,110.0).copy(rightDegrees=null),ExerciseCounter.Phase.outbound,null)
        a.observe(frame(.07,100.0),ExerciseCounter.Phase.outbound,null)
        a.finish("manual")
        val r=a.records.single();assertTrue(r.samples.any{it.seconds==.06 && it.rightDegrees==null})
        assertTrue(r.samples.any{it.seconds==.07 && it.rightDegrees!=null})
        assertTrue(r.right.observedSeconds<r.left.observedSeconds)
    }
    @Test fun gapsAndBackwardsTimeDoNotFillMetricsOrAdvanceRecords() {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        a.observe(frame(0.0,170.0),ExerciseCounter.Phase.ready,null)
        a.observe(frame(.2,100.0),ExerciseCounter.Phase.outbound,null)
        a.observe(frame(.4,80.0),ExerciseCounter.Phase.returning,event(.4))
        a.observe(frame(1.0,170.0),ExerciseCounter.Phase.seekingStart,null)
        val r=a.records.single();assertNull(r.returnSeconds);assertEquals(.4,r.closedAt)
        a.observe(frame(.9,80.0),ExerciseCounter.Phase.returning,event(.9))
        assertEquals(1,a.records.size);assertFalse(a.activeAccepted)
    }
    @Test fun timeWeightedMeanIsInvariantToSamplingDensity() {
        fun run(times:List<Double>):RepRecord {
            val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
            a.observe(frame(0.0,170.0),ExerciseCounter.Phase.ready,null)
            times.forEach { a.observe(frame(it,170-100*it),ExerciseCounter.Phase.outbound,null) }
            a.finish("manual");return a.records.single()
        }
        val sparse=run(listOf(.2,.4,.6,.8,1.0))
        val dense=run((1..100).map{it/100.0})
        assertEquals(120.0,sparse.left.mean!!,1e-10)
        assertEquals(sparse.left.mean!!,dense.left.mean!!,1e-10)
    }
    @Test fun missingArmIsNullAndDoesNotBorrowOtherArm() {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        a.observe(frame(0.0,170.0).copy(rightDegrees=null),ExerciseCounter.Phase.ready,null)
        a.observe(frame(.2,100.0).copy(rightDegrees=null),ExerciseCounter.Phase.outbound,null)
        a.finish("manual");val r=a.records.single()
        assertNull(r.right.mean);assertNull(r.right.minimum);assertNull(r.bilateralExtremeOffsetSeconds)
    }
    @Test fun releaseNeedsContinuousPositiveEvidenceAndNotStillness() {
        val b=AutomaticSetBoundary()
        for(i in 0..500) assertNull(b.observe(frame(i*.1,170.0),true))
        assertNull(b.observe(frame(50.1,160.0,released=true),true))
        assertNull(b.observe(frame(50.8,160.0,released=true),true)) // gap resets dwell
        assertNull(b.observe(frame(50.9,null,usable=false),true))
        for(i in 0..9) assertNull(b.observe(frame(51.0+i*.1,160.0,released=true),true))
        assertEquals("disengaged",b.observe(frame(52.1,160.0,released=true),true))
    }
    @Test fun trackingOutageIsInterruptionNotSuccessfulRelease() {
        val b=AutomaticSetBoundary()
        for(i in 0..29) assertNull(b.observe(frame(i*.1,null,usable=false),true))
        assertEquals("trackingLost",b.observe(frame(3.1,null,usable=false),true))
    }
    @Test fun rejectedTimestampsCannotRebaseReleaseOrOutageDwell() {
        for (release in listOf(false, true)) {
            val boundary = AutomaticSetBoundary()
            assertNull(boundary.observe(frame(10.0,170.0),true))
            assertNull(boundary.observe(frame(Double.NaN,null,usable=false),true))
            for (i in 0..40) {
                assertNull(boundary.observe(frame(i*.1,160.0,usable=false,released=release),true))
            }
            val frames = if (release) 10 else 30
            for (i in 0 until frames) {
                assertNull(boundary.observe(frame(10.1+i*.1,160.0,usable=false,released=release),true))
            }
            assertEquals(if (release) "disengaged" else "trackingLost",
                boundary.observe(frame(10.1+frames*.1,160.0,usable=false,released=release),true))
        }
    }
    @Test fun interruptionBreaksReleaseContinuityWhileNewSetCanResetTheClock() {
        val boundary = AutomaticSetBoundary()
        assertNull(boundary.observe(frame(10.0,170.0),true))
        for (i in 1..9) assertNull(boundary.observe(frame(10.0+i*.1,160.0,released=true),true))
        boundary.breakContinuity()
        for (i in 0 until 10) assertNull(boundary.observe(frame(11.0+i*.1,160.0,released=true),true))
        assertEquals("disengaged",boundary.observe(frame(12.0,160.0,released=true),true))
        boundary.reset()
        assertNull(boundary.observe(frame(0.0,170.0),true))
        for (i in 1..10) assertNull(boundary.observe(frame(i*.1,160.0,released=true),true))
        assertEquals("disengaged",boundary.observe(frame(1.2,160.0,released=true),true))
    }
    @Test fun readinessCannotEarnTimeFromDuplicatesOrMissingEvidence() {
        val start=AutomaticWorkoutStart()
        assertFalse(start.observe(0.0,true));assertFalse(start.observe(0.0,true))
        assertFalse(start.observe(.2,false));assertFalse(start.observe(.3,true))
        assertTrue(start.observe(.45,true))
    }
    @Test fun boundedCurvesDoNotTruncateIntegratedMetrics() {
        val a=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        a.observe(frame(0.0,150.0),ExerciseCounter.Phase.ready,null)
        for(i in 1..4000) a.observe(frame(i*.1,150.0),ExerciseCounter.Phase.outbound,null)
        a.finish("manual");val r=a.records.single()
        assertTrue(r.samples.size<=256);assertTrue(r.curveTruncated)
        assertEquals(400.0,r.left.observedSeconds,1e-8);assertEquals(150.0,r.left.mean!!,1e-8)
        assertEquals(r,WorkoutAnalysis.json.decodeFromString<RepRecord>(WorkoutAnalysis.json.encodeToString(r)))
    }
    @Test fun sessionAnalyticsPreserveOriginalCountPolicy() {
        val s=LiveSetSession();s.start(ExerciseCounter.Exercise.pullUp,ArmMeasurement.Side.left)
        for((t,a) in listOf(0.0 to 170.0,.15 to 170.0,.3 to 80.0,.45 to 80.0,.6 to 170.0,.75 to 170.0))
            s.consume(CounterFixtures.bilateral(t,a,a),CounterFixtures.edge)
        s.finish();assertEquals(1,s.observedMovements)
        assertEquals(s.observedMovements,s.record.acceptedCount)
        assertEquals(.75,s.record.reps.single().returnedAt)
    }
    @Test fun analyticsExcludeUnfinishedLastRepForBothExercisesAndAllSetEndings() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            for (ending in listOf(LiveSetSession.EndReason.manual, LiveSetSession.EndReason.disengaged, LiveSetSession.EndReason.trackingLost)) {
                val session = LiveSetSession()
                session.start(exercise, ArmMeasurement.Side.left)
                for ((time, angle) in listOf(0.0 to 170.0, .15 to 170.0, .3 to 80.0,
                    .45 to 80.0, .6 to 170.0, .75 to 170.0, .9 to 80.0, 1.05 to 80.0)) {
                    session.consume(CounterFixtures.bilateral(time, angle, angle, exercise = exercise), CounterFixtures.edge)
                }
                session.finish(ending)
                val record = session.record
                assertEquals(2, record.reps.size, "Raw attempts remain recoverable")
                assertEquals(if (exercise == ExerciseCounter.Exercise.pullUp) 2 else 1, record.acceptedCount)
                assertEquals(1, record.completedCount)
                assertEquals(listOf(record.reps.first()), record.completedReps)
                assertTrue(record.reps.first().isCompleteForAnalytics)
                assertFalse(record.reps.last().isCompleteForAnalytics)
                assertNull(record.reps.last().returnedAt)
                val recovered = WorkoutRecordCodec.recover(WorkoutRecordCodec.summary(record))
                assertEquals(1, recovered.completedCount)
                assertEquals(record.completedReps.map { it.id }, recovered.completedReps.map { it.id })
                assertEquals(record.acceptedCount, recovered.acceptedCount)
            }
        }
    }
    @Test fun incompleteOnlySetHasNoAnalyticsEvenIfPullUpMilestoneWasAccepted() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val session = LiveSetSession()
            session.start(exercise, ArmMeasurement.Side.left)
            for ((time, angle) in listOf(0.0 to 170.0, .15 to 170.0, .3 to 80.0, .45 to 80.0)) {
                session.consume(CounterFixtures.bilateral(time, angle, angle, exercise = exercise), CounterFixtures.edge)
            }
            session.finish()
            assertEquals(1, session.record.reps.size)
            assertEquals(0, session.record.completedCount)
            assertTrue(session.record.completedReps.isEmpty())
        }
    }
    @Test fun completedAnalyticsRequireAcceptanceAndPositiveReturnEvidence() {
        assertTrue(WorkoutAnalytics.isCompletedRep(true, true, "returned"))
        for (reason in listOf("inProgress", "partial", "trackingLost", "disengaged", "manual")) {
            assertFalse(WorkoutAnalytics.isCompletedRep(true, true, reason))
        }
        assertFalse(WorkoutAnalytics.isCompletedRep(false, true, "returned"))
        assertFalse(WorkoutAnalytics.isCompletedRep(true, false, "returned"))
    }
    @Test fun rejectedFramesCannotMoveSetBoundsBehindRetainedRepEvidence() {
        for (exercise in ExerciseCounter.Exercise.entries) {
            val session = LiveSetSession()
            session.start(exercise, ArmMeasurement.Side.left)
            for ((time, angle) in listOf(0.0 to 170.0, .15 to 170.0, .3 to 80.0,
                .45 to 80.0, .6 to 170.0, .75 to 170.0, .9 to 80.0, 1.05 to 80.0)) {
                session.consume(CounterFixtures.bilateral(time, angle, angle, exercise = exercise), CounterFixtures.edge)
            }
            val accepted = session.observedMovements
            for (time in listOf(.2, 1.05, .1)) {
                session.consume(CounterFixtures.bilateral(time, 80.0, 80.0, exercise = exercise), CounterFixtures.edge)
                assertEquals(0.0, session.firstSourceSeconds)
                assertEquals(1.05, session.lastSourceSeconds)
                assertEquals(1.05, session.durationSeconds)
                assertTrue(session.analysis.records.all { it.closedAt <= session.record.endedAt!! })
                assertEquals(accepted, session.observedMovements)
                assertEquals(accepted, session.record.acceptedCount)
            }
            session.finish()
            assertEquals(1.05, session.record.endedAt)
            assertEquals("nonIncreasingTimestamp", session.record.reps.last().closeReason)
        }
    }
}

class WorkoutSourceTimelineTests {
    @Test fun appleBridgeInterruptionCannotRebaseBoundaryWithOlderFrames() {
        val bridge = WorkoutAnalysisBridge("bounds", "pullUp", 0)
        fun observe(time: Double, usable: Boolean): String? {
            val pose = CounterFixtures.bilateral(time,170.0,170.0)
            val points = pose.people.single().landmarks
            return bridge.observe(time,pose.imageSize.width,pose.imageSize.height,1,
                points.map { PoseJoint.entries.indexOf(it.joint) }.toIntArray(),
                points.flatMap { listOf(it.position.x,it.position.y,it.confidence) }.toDoubleArray(),
                doubleArrayOf(CounterFixtures.edge.a.x,CounterFixtures.edge.a.y,CounterFixtures.edge.b.x,CounterFixtures.edge.b.y),
                if (usable) "ready" else "seekingStart",usable,pose.backend,pose.requestRevision,"left",
                null,0.0,null,true,true)
        }
        assertNull(observe(10.0,true)); assertNull(observe(10.15,true))
        bridge.interrupt("inferenceFailure")
        for (i in 2..42) assertNull(observe(i/10.0,false))
        for (i in 0 until 30) assertNull(observe(10.3+i*.1,false))
        assertEquals("trackingLost",observe(13.3,false))
    }
    @Test fun invalidDuplicateAndOlderTimesCannotChangeSourceBounds() {
        val timeline = WorkoutSourceTimeline()
        for (time in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) timeline.observe(time)
        assertNull(timeline.firstSeconds); assertNull(timeline.lastSeconds)
        timeline.observe(5.0); timeline.observe(5.5)
        for (time in listOf(1.0, 5.5, Double.NaN, Double.POSITIVE_INFINITY)) timeline.observe(time)
        assertEquals(5.0, timeline.firstSeconds); assertEquals(5.5, timeline.lastSeconds)
        timeline.observe(5.6)
        assertEquals(5.0, timeline.firstSeconds); assertEquals(5.6, timeline.lastSeconds)
    }
    @Test fun newTimelineCanStartEarlierAfterExplicitReset() {
        val timeline = WorkoutSourceTimeline()
        timeline.observe(100.0); timeline.reset()
        assertNull(timeline.firstSeconds); assertNull(timeline.lastSeconds)
        timeline.observe(0.0)
        assertEquals(0.0, timeline.firstSeconds); assertEquals(0.0, timeline.lastSeconds)
    }
}

class AutomaticWorkoutIntegrationTests {
    private val attitude=PhoneOrientationStability.Quaternion.create(0.0,0.0,0.0,1.0)!!
    private val patches=List(3){StaticSceneStability.PatchTranslation(0.0,0.0)}
    private fun input(time:Double,angle:Double=170.0,exercise:ExerciseCounter.Exercise=ExerciseCounter.Exercise.pullUp,release:Boolean=false,motion:Boolean=true):WorkoutRuntime.FrameInput {
        var pose=CounterFixtures.bilateral(time,angle,angle,exercise=exercise)
        if(release) pose=pose.copy(people=pose.people.map { person->PoseObservation(person.landmarks.map {it.copy(position=Point2D(it.position.x,it.position.y+200))}) })
        return WorkoutRuntime.FrameInput(pose,attitude=if(motion) attitude else null,motionTimestamp=if(motion) time else null,
            translations=patches,globalScaleFraction=0.0,sceneTimestamp=time)
    }
    private fun ready(exercise:ExerciseCounter.Exercise):WorkoutRuntime {
        val selection=if(exercise==ExerciseCounter.Exercise.pullUp) WorkoutExerciseSelection.pullUp else WorkoutExerciseSelection.dip
        val r=WorkoutRuntime(selection,automaticWorkouts=true)
        val role=if(exercise==ExerciseCounter.Exercise.pullUp) ConfirmedBar.Role.pullUpGrip else ConfirmedBar.Role.leftDipRail
        val bar=ConfirmedBar(role,ConfirmedBar.Method.manualEdge,CounterFixtures.edge,imageSize=ImageSize(1000.0,1000.0),sourceTime=PresentationTime(0,1000))
        assertTrue(r.confirmManualBar(bar,attitude,0.0))
        r.observe(input(.0,exercise=exercise));r.observe(input(.15,exercise=exercise))
        assertEquals(LiveSetSession.Phase.running,r.session.phase)
        assertEquals(ExerciseCounter.Phase.ready,r.session.counter.phase)
        return r
    }
    @Test fun automaticStartCountReleaseAndNextSetWorkForBothExercises() {
        for(exercise in ExerciseCounter.Exercise.entries) {
            val r=ready(exercise)
            for((t,a) in listOf(.3 to 80.0,.45 to 80.0,.6 to 170.0,.75 to 170.0)) r.observe(input(t,a,exercise))
            assertEquals(1,r.session.observedMovements)
            var releasedAt=.9
            for(i in 0..12){releasedAt=.9+i*.1;r.observe(input(releasedAt,160.0,exercise,release=true));if(r.latestCompletedSet!=null) break}
            assertEquals(LiveSetSession.EndReason.disengaged,r.session.endReason)
            val saved=assertNotNull(r.latestCompletedSet)
            assertEquals(1,saved.acceptedCount);assertEquals(1,saved.reps.size)
            r.observe(input(releasedAt+.15,exercise=exercise));r.observe(input(releasedAt+.3,exercise=exercise))
            assertEquals(LiveSetSession.Phase.running,r.session.phase)
            assertEquals(0,r.session.observedMovements);assertNotEquals(saved.id,r.session.setId)
            assertEquals(saved,r.latestCompletedSet)
        }
    }
    @Test fun acquiredStartWithoutAnyRepCanDisengageAndRearm() {
        val r=ready(ExerciseCounter.Exercise.pullUp)
        for(i in 0..12){r.observe(input(.3+i*.1,160.0,release=true));if(r.session.phase==LiveSetSession.Phase.finished) break}
        assertEquals(LiveSetSession.EndReason.disengaged,r.session.endReason)
        assertNull(r.latestCompletedSet)
        r.observe(input(1.7));r.observe(input(1.85))
        assertEquals(LiveSetSession.Phase.running,r.session.phase)
        assertEquals(0,r.session.observedMovements)
    }
    @Test fun missingPhoneMotionStillArchivesAcceptedPullUp() {
        val r=ready(ExerciseCounter.Exercise.pullUp)
        r.observe(input(.3,80.0));r.observe(input(.45,80.0))
        r.observe(input(.6,80.0,motion=false))
        assertEquals(LiveSetSession.EndReason.setupInvalidated,r.session.endReason)
        val saved=assertNotNull(r.latestCompletedSet)
        assertEquals(1,saved.acceptedCount);assertNull(saved.reps.single().returnedAt)
    }
    @Test fun watchdogArchivesInterruptionAndCannotInventCompletion() {
        val r=ready(ExerciseCounter.Exercise.pullUp)
        r.observe(input(.3,80.0));r.observe(input(.45,80.0));r.tick(4.0)
        val saved=assertNotNull(r.latestCompletedSet)
        assertEquals("trackingLost",saved.endReason);assertEquals(1,saved.acceptedCount)
        assertEquals(.45,saved.endedAt);assertNull(saved.reps.single().returnSeconds)
    }
    @Test fun staleSourceFrameCannotMakeWatchdogEndSetEarly() {
        val runtime = ready(ExerciseCounter.Exercise.pullUp)
        runtime.observe(input(.3,80.0)); runtime.observe(input(.45,80.0))
        runtime.observe(input(.2,80.0))
        runtime.tick(3.3) // 2.85 s since the latest source frame, not 3.1 s.
        assertEquals(LiveSetSession.Phase.running, runtime.session.phase)
        runtime.tick(3.46)
        assertEquals(LiveSetSession.EndReason.trackingLost, runtime.session.endReason)
        assertEquals(.45, assertNotNull(runtime.latestCompletedSet).endedAt)
        assertEquals(1, runtime.latestCompletedSet!!.acceptedCount)
    }
}

class WorkoutGripReleaseTests {
    @Test fun separateDipRailsRequireBothHandsToLeaveTheirOwnStartingGrips() {
        val features=WorkoutMotionFeatures()
        val pose=CounterFixtures.bilateral(0.0,170.0,170.0,exercise=ExerciseCounter.Exercise.dip)
        val rail=BarSegment(Point2D(300.0,550.0),Point2D(300.0,850.0))
        assertFalse(features.extract(pose,rail,true,ExerciseCounter.Phase.ready).released)
        fun shifted(both:Boolean)=pose.copy(people=pose.people.map { person -> PoseObservation(person.landmarks.map { point ->
            if(both || point.joint in listOf(PoseJoint.leftShoulder,PoseJoint.leftElbow,PoseJoint.leftWrist)) point.copy(position=Point2D(point.position.x-200,point.position.y)) else point
        }) })
        assertFalse(features.extract(shifted(false),rail,true,ExerciseCounter.Phase.seekingStart).released)
        assertTrue(features.extract(shifted(true),rail,true,ExerciseCounter.Phase.seekingStart).released)
        val missing=shifted(true).copy(people=shifted(true).people.map { PoseObservation(it.landmarks.filter { point->point.joint!=PoseJoint.rightWrist }) })
        assertFalse(features.extract(missing,rail,false,ExerciseCounter.Phase.seekingStart).released)
        features.reset()
        assertFalse(features.extract(shifted(true),rail,true,ExerciseCounter.Phase.seekingStart).released)
    }
}

class WorkoutRecoveryTests {
    @Test fun calendarTimestampIsStableAndIndependentOfSourceTime() {
        val session=LiveSetSession()
        val epoch=1_790_000_000_000L
        session.start(ExerciseCounter.Exercise.pullUp,ArmMeasurement.Side.left,epoch)
        session.consume(CounterFixtures.bilateral(.0,170.0,170.0),CounterFixtures.edge)
        session.consume(CounterFixtures.bilateral(.15,170.0,170.0),CounterFixtures.edge)
        assertEquals(epoch,session.record.trackedAtEpochMilliseconds)
        assertEquals(.15,session.record.endedAt)
        val serialized=WorkoutRecordCodec.json.encodeToString(WorkoutSetRecord.serializer(),session.record)
        assertEquals(epoch,WorkoutRecordCodec.json.decodeFromString(WorkoutSetRecord.serializer(),serialized).trackedAtEpochMilliseconds)
        val oldFields=(WorkoutRecordCodec.json.parseToJsonElement(serialized) as kotlinx.serialization.json.JsonObject).filterKeys{it!="trackedAtEpochMilliseconds"}
        assertNull(WorkoutRecordCodec.json.decodeFromString(WorkoutSetRecord.serializer(),kotlinx.serialization.json.JsonObject(oldFields).toString()).trackedAtEpochMilliseconds)
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(session.record,session.record.copy(trackedAtEpochMilliseconds=epoch+1)) }
        session.finish();session.prepareNextSet()
        assertNull(session.record.trackedAtEpochMilliseconds)
        session.start(ExerciseCounter.Exercise.dip,ArmMeasurement.Side.left,epoch+60_000)
        assertEquals(epoch+60_000,session.record.trackedAtEpochMilliseconds)
    }
    private fun record(): WorkoutSetRecord {
        val session=LiveSetSession();session.start(ExerciseCounter.Exercise.pullUp,ArmMeasurement.Side.left)
        for((t,a) in listOf(0.0 to 170.0,.15 to 170.0,.3 to 80.0,.45 to 80.0)) session.consume(CounterFixtures.bilateral(t,a,a),CounterFixtures.edge)
        return assertNotNull(session.draftDelta)
    }
    @Test fun reverseOrderDeltasUnionDistinctRepIdsAndKeepNewestMetadata() {
        val first=record()
        val second=first.copy(endedAt=2.0,reps=first.reps.map{it.copy(id="second",ordinal=2,closedAt=2.0)})
        val merged=WorkoutRecordCodec.mergeDraft(WorkoutRecordCodec.mergeDraft(null,second),first)
        assertEquals(2,merged.acceptedCount);assertEquals(2.0,merged.endedAt)
        assertEquals(listOf(1,2),merged.reps.map{it.ordinal})
    }
    @Test fun terminalEvidenceWinsOverEqualTimeOrLaterInProgressDelta() {
        val active=record()
        val closed=active.copy(reps=active.reps.map{it.copy(closeReason="trackingLost")})
        val terminal=WorkoutRecordCodec.mergeDraft(null,closed)
        assertEquals(terminal,WorkoutRecordCodec.mergeDraft(terminal,active))
        assertEquals(terminal.reps,WorkoutRecordCodec.mergeDraft(terminal,active.copy(reps=active.reps.map{it.copy(closedAt=1.0)})).reps)
    }
    @Test fun outboxRetainsFailedWritesAcrossRearmingAndAcknowledgesOnlyExactDelivery() {
        val queue=WorkoutWriteQueue();val first=record()
        queue.enqueue(first,false)
        val failed=assertNotNull(queue.next()) // Native writer fails; no acknowledgement.
        queue.enqueue(first.copy(endReason="trackingLost"),true)
        queue.acknowledge(failed.token) // A late draft acknowledgement cannot remove final evidence.
        assertTrue(assertNotNull(queue.next()).finalized)
        val next=first.copy(id="next-set",reps=first.reps.map{it.copy(setId="next-set")})
        queue.enqueue(next,true)
        queue.acknowledge(assertNotNull(queue.next()).token)
        assertEquals("next-set",assertNotNull(queue.next()).record.id)
        queue.acknowledge(assertNotNull(queue.next()).token);assertNull(queue.next())
    }
    @Test fun acceptedRepJournalSurvivesCrashAndMergeCompletesItsReturnWithoutDuplication() {
        val session=LiveSetSession();session.start(ExerciseCounter.Exercise.pullUp,ArmMeasurement.Side.left)
        for((t,a) in listOf(0.0 to 170.0,.15 to 170.0,.3 to 80.0,.45 to 80.0)) session.consume(CounterFixtures.bilateral(t,a,a),CounterFixtures.edge)
        val first=WorkoutRecordCodec.mergeDraft(null,assertNotNull(session.draftDelta))
        assertEquals(1,first.acceptedCount);assertNull(first.reps.single().returnedAt)
        assertTrue(first.reps.single().samples.isEmpty())
        val recovery=WorkoutRecordCodec.recover(first)
        assertEquals("recoveredInterruption",recovery.endReason);assertEquals(1,recovery.acceptedCount)
        for((t,a) in listOf(.6 to 170.0,.75 to 170.0)) session.consume(CounterFixtures.bilateral(t,a,a),CounterFixtures.edge)
        val next=WorkoutRecordCodec.mergeDraft(first,assertNotNull(session.draftDelta))
        assertEquals(1,next.reps.size);assertEquals(.75,next.reps.single().returnedAt)
        assertEquals(first,WorkoutRecordCodec.mergeDraft(first,first.copy(endedAt=.3)))
        assertFailsWith<IllegalArgumentException> { WorkoutRecordCodec.mergeDraft(first,first.copy(reps=first.reps.map{it.copy(acceptedAt=null)})) }
    }
}
