package dev.hanginthere.android

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.session.WorkoutGripCorrectionStore
import dev.hanginthere.android.session.WorkoutRecordStore
import dev.hanginthere.android.vision.GripInference
import dev.hanginthere.android.vision.inferGripHandCrops
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import dev.hanginthere.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkoutGripTest {
    @Test fun primaryAndFallbackCropsDoNotInventHandsOnBlankPixels() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val pixels=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        val pose=PoseResult(PresentationTime(0,1000),ImageSize(256.0,256.0),listOf(PoseObservation(listOf(
            Landmark(PoseJoint.leftWrist,Point2D(64.0,64.0),1.0),
            Landmark(PoseJoint.rightWrist,Point2D(192.0,64.0),1.0)))),"authored blank hand crops",0)
        val hand=HandLandmarker.createFromOptions(context,HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
            .setRunningMode(RunningMode.IMAGE).setNumHands(1).setMinHandDetectionConfidence(.5f).setMinHandPresenceConfidence(.5f).build())
        try {
            assertTrue(inferGripHandCrops(pixels,pose,hand).isEmpty())
            assertFalse("Crop ownership must not recycle the caller's frame",pixels.isRecycled)
        } finally { hand.close();pixels.recycle() }
    }
    @Test fun pinnedNativeModelsRunAndMissingBodyDoesNotBecomeGripEvidence() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val pixels=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        val pose=PoseResult(PresentationTime(0,1000),ImageSize(64.0,64.0),listOf(PoseObservation(GripBodyJoint.entries.map {
            Landmark(PoseJoint.valueOf(it.name),Point2D(32.0,32.0),1.0)
        })),"authored SDK smoke",0)
        val worker=GripInference(context)
        try {
            var result=worker.observe(pixels,pose)
            val deadline=SystemClock.elapsedRealtime()+60_000
            while(result==null && SystemClock.elapsedRealtime()<deadline) { SystemClock.sleep(10);result=worker.observe(pixels,pose) }
            assertNotNull("Native models must return within the smoke-test timeout",result)
            assertEquals("bodyFrameUnavailable",result!!.failure)
            assertTrue(result.hands.isEmpty())
            assertEquals(pose.imageSize,result.imageSize)
            assertEquals(6,result.trackedBody.size)
            assertEquals(GripOrientation.unknown,WorkoutGripGeometry.orientation(result).orientation)
        } finally { worker.close();pixels.recycle() }
    }
    @Test fun annotationsSurviveReloadAndDoNotAlterTheMeasuredArchive() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"grip-store-${UUID.randomUUID()}")
        val measured=WorkoutRecordStore(context,File(root,"workouts"))
        val annotations=WorkoutGripCorrectionStore(context,File(root,"corrections"))
        val id=UUID.randomUUID().toString()
        val a=WorkoutAnalysis(id,ExerciseCounter.Exercise.pullUp)
        fun frame(time:Double,angle:Double)=MotionSample(time,angle,angle,null,true,false)
        a.observe(frame(0.0,170.0),ExerciseCounter.Phase.ready,null)
        a.observe(frame(.2,90.0),ExerciseCounter.Phase.outbound,null)
        a.observe(frame(.4,80.0),ExerciseCounter.Phase.returning,ExerciseCounter.Event(ExerciseCounter.Outcome.movement,.4,"test"))
        a.observe(frame(.6,170.0),ExerciseCounter.Phase.ready,null)
        val set=WorkoutSetRecord(id,ExerciseCounter.Exercise.pullUp,0.0,.6,"manual",a.records,"test",0)
        try {
            measured.save(set)
            val original=File(root,"workouts/$id.json").readBytes()
            annotations.correct(set,null,GripOrientation.underhand,GripWidth.narrow)
            val loaded=WorkoutGripCorrectionStore(context,File(root,"corrections")).report(set)
            assertEquals(GripOrientation.underhand,loaded.reps.single().orientation)
            assertEquals("user",loaded.reps.single().orientationSource)
            assertEquals(1,loaded.totals.single().completedReps)
            assertArrayEquals(original,File(root,"workouts/$id.json").readBytes())
            assertEquals(1,measured.recent().size)
            annotations.correct(set,set.reps.single().id,null,null)
            assertEquals(GripOrientation.unknown,annotations.report(set).reps.single().orientation)
        } finally { root.deleteRecursively() }
    }
}
