package dev.hanginthere.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.session.WorkoutRecordStore
import dev.hanginthere.android.session.AnalysisPipeline
import dev.hanginthere.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class WorkoutRecordStoreTest {
    private fun record(): WorkoutSetRecord {
        val id=UUID.randomUUID().toString()
        val analysis=WorkoutAnalysis(id,ExerciseCounter.Exercise.pullUp)
        analysis.observe(MotionSample(0.0,170.0,170.0,.2,true,false),ExerciseCounter.Phase.ready,null)
        analysis.observe(MotionSample(.2,80.0,80.0,.1,true,false),ExerciseCounter.Phase.outbound,null)
        analysis.observe(MotionSample(.4,80.0,80.0,.1,true,false),ExerciseCounter.Phase.returning,ExerciseCounter.Event(ExerciseCounter.Outcome.movement,.4,"authored test"))
        analysis.finish("trackingLost")
        return WorkoutSetRecord(id,ExerciseCounter.Exercise.pullUp,0.0,.4,"trackingLost",analysis.records,"authored storage test",0)
    }
    @Test fun backupOnlyCurrentJournalRecoversBeforeHistoryPagination() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"backup-page-test-${UUID.randomUUID()}")
        val store=WorkoutRecordStore(context,directory)
        try {
            repeat(50) { index ->
                val old=record();store.save(old)
                assertTrue(File(directory,"${old.id}.json").setLastModified(index.toLong()+1))
            }
            val current=record();store.saveDraft(current)
            val pending=File(directory,"${current.id}.pending.json")
            assertTrue(pending.setLastModified(1000))
            assertTrue(pending.renameTo(File(pending.path+".bak"))) // Power loss before replacement base creation.
            val recovered=store.recent()
            assertEquals(50,recovered.size);assertEquals(current.id,recovered.first().id)
            assertEquals(1,recovered.first().acceptedCount)
            assertEquals("recoveredInterruption",recovered.first().endReason)
        } finally { directory.deleteRecursively() }
    }
    @Test fun backupOnlyJournalMergePreservesPreviouslyAcceptedReps() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"backup-merge-test-${UUID.randomUUID()}")
        val store=WorkoutRecordStore(context,directory)
        try {
            val first=record();store.saveDraft(first)
            val pending=File(directory,"${first.id}.pending.json")
            assertTrue(pending.renameTo(File(pending.path+".bak")))
            val second=first.copy(endedAt=.8,reps=first.reps.map{it.copy(id="${first.id}:rep:2",ordinal=2,startedAt=.6,acceptedAt=.8,closedAt=.8)})
            store.saveDraft(second)
            assertEquals(2,store.recent().single().acceptedCount)
            assertFalse(File(pending.path+".bak").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun overlappingSerialOwnersCannotLoseQueuedWrites() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"concurrent-test-${UUID.randomUUID()}")
        val store=WorkoutRecordStore(context,directory)
        val records=List(16){record()}
        val workers=Executors.newFixedThreadPool(4)
        try {
            workers.invokeAll(records.map { record -> Callable {
                store.enqueue(record,false);store.enqueue(record,true);store.flushPending();store.recent()
            } }).forEach{it.get()}
            assertEquals(records.map{it.id}.toSet(),WorkoutRecordStore(context,directory).recent().map{it.id}.toSet())
        } finally { workers.shutdown();directory.deleteRecursively() }
    }
    @Test fun retryAfterSummaryCommitStillCompletesCleanup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"cleanup-test-${UUID.randomUUID()}")
        val store=WorkoutRecordStore(context,directory)
        val record=record()
        val pending=File(directory,"${record.id}.pending.json")
        pending.mkdirs();File(pending,"block-deletion").writeText("authored failure")
        try {
            store.enqueue(record,true)
            try { store.flushPending();fail("Expected post-commit cleanup failure") } catch (_:IllegalStateException) { }
            assertTrue(File(directory,"${record.id}.json").exists())
            assertTrue(pending.deleteRecursively());store.flushPending()
            assertEquals(listOf(record),WorkoutRecordStore(context,directory).recent())
            assertFalse(pending.exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun productionWatchdogSavesAndPublishesInterruptionWithoutAnotherFrame() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"watchdog-test-${UUID.randomUUID()}")
        val store=WorkoutRecordStore(context,directory)
        val edge=BarSegment(Point2D(100.0,500.0),Point2D(900.0,500.0))
        val attitude=PhoneOrientationStability.Quaternion.create(0.0,0.0,0.0,1.0)!!
        fun input(time:Double,angle:Double):WorkoutRuntime.FrameInput {
            val half=angle*Math.PI/360
            val y=120*kotlin.math.sin(half)
            val landmarks=listOf(300.0 to listOf(PoseJoint.leftShoulder,PoseJoint.leftElbow,PoseJoint.leftWrist),700.0 to listOf(PoseJoint.rightShoulder,PoseJoint.rightElbow,PoseJoint.rightWrist)).flatMap { (x,joints) ->
                joints.zip(listOf(Point2D(x,500+2*y),Point2D(x+120*kotlin.math.cos(half),500+y),Point2D(x,500.0))).map{(joint,point)->Landmark(joint,point,1.0)}
            }
            val pose=PoseResult(PresentationTime((time*1000).toLong(),1000),ImageSize(1000.0,1000.0),listOf(PoseObservation(landmarks)),"authored watchdog test",0)
            return WorkoutRuntime.FrameInput(pose,attitude=attitude,motionTimestamp=time,translations=List(3){StaticSceneStability.PatchTranslation(0.0,0.0)},globalScaleFraction=0.0,sceneTimestamp=time)
        }
        try {
            AnalysisPipeline(context,WorkoutExerciseSelection.pullUp,ArmMeasurement.Side.left,recordStoreOverride=store).use { pipeline ->
                assertTrue(pipeline.runtime.confirmManualBar(ConfirmedBar(ConfirmedBar.Role.pullUpGrip,ConfirmedBar.Method.manualEdge,edge,imageSize=ImageSize(1000.0,1000.0),sourceTime=PresentationTime(0,1000)),attitude,0.0))
                for((time,angle) in listOf(0.0 to 170.0,.15 to 170.0,.3 to 80.0,.45 to 80.0)) pipeline.runtime.observe(input(time,angle))
                val result=AnalysisPipeline.Result(input(.45,80.0).pose,pipeline.runtime.snapshot,0.0,"",0.0)
                val ended=pipeline.tick(4.0,result)
                assertEquals(LiveSetSession.Phase.finished,ended.snapshot.phase)
                assertEquals(1,ended.history.single().acceptedCount)
                assertEquals("trackingLost",WorkoutRecordStore(context,directory).recent().single().endReason)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedFirstWriteRetriesFinalEvidenceWithoutAnotherCameraFrame() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"retry-test-${UUID.randomUUID()}")
        directory.writeText("blocking file")
        val record=record()
        val store=WorkoutRecordStore(context,directory)
        try {
            store.enqueue(record,false);store.enqueue(record,true)
            try { store.flushPending();fail("Expected blocked-directory failure") } catch (_:IllegalStateException) { }
            assertTrue(directory.delete());store.flushPending()
            assertEquals(listOf(record),WorkoutRecordStore(context,directory).recent())
            store.saveDraft(record) // Final records dominate late queued drafts.
            assertFalse(File(directory,"${record.id}.pending.json").exists())
        } finally { directory.deleteRecursively() }
    }
    @Test fun retentionExpiresMotionWithoutDeletingSummariesAndRecoversRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "store-test-${UUID.randomUUID()}")
        val store = WorkoutRecordStore(context, directory)
        try {
            val records = (0..54).map { index ->
                val id = UUID.randomUUID().toString()
                val a = WorkoutAnalysis(id, ExerciseCounter.Exercise.pullUp)
                a.observe(MotionSample(0.0,170.0,170.0,0.2,true,false),ExerciseCounter.Phase.ready,null)
                a.observe(MotionSample(.2,80.0,80.0,0.1,true,false),ExerciseCounter.Phase.outbound,null)
                a.observe(MotionSample(.4,80.0,80.0,0.1,true,false),ExerciseCounter.Phase.returning,
                    ExerciseCounter.Event(ExerciseCounter.Outcome.movement,.4,"authored test"))
                a.finish("manual")
                WorkoutSetRecord(id,ExerciseCounter.Exercise.pullUp,0.0,.4,"manual",a.records,"authored storage test",0)
                    .also { record -> store.save(record); store.save(record); File(directory,"$id.json").setLastModified(index.toLong()+1) }
            }
            assertEquals(50,directory.listFiles()!!.count {it.name.endsWith(".motion.json")})
            assertEquals(55,directory.listFiles()!!.count {it.extension=="json" && !it.name.endsWith(".motion.json")})
            assertEquals(50,store.recent().size);assertTrue(store.recent().all {it.acceptedCount==1})
            directory.listFiles()!!.filter{it.name.endsWith(".motion.json")}.forEach { assertTrue(it.delete()) }
            assertTrue(store.recent().all{it.reps.single().samples.isEmpty() && it.reps.single().curveTruncated})
            store.save(records.last()) // Summary idempotence after expiry.
            try { store.save(records.last().copy(endReason="trackingLost"));fail("Conflicting records must not overwrite") }
            catch (_:IllegalStateException) { }
        } finally { directory.deleteRecursively() }
    }
}
