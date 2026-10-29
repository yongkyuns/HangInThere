package dev.hanginthere.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class WorkoutGripWidthTests {
    private val bar=BarSegment(Point2D(0.0,100.0),Point2D(1000.0,100.0))
    private fun pose(t:Double,ratio:Double=1.0,shoulders:Double=200.0,context:Boolean=false):PoseResult {
        val timestamp=PresentationTime((t*1000).toLong(),1000)
        val positions=listOf(
            PoseJoint.leftShoulder to Point2D(500+shoulders/2,400.0),
            PoseJoint.rightShoulder to Point2D(500-shoulders/2,400.0),
            PoseJoint.leftWrist to Point2D(500+ratio*100,100.0),
            PoseJoint.rightWrist to Point2D(500-ratio*100,100.0),
            PoseJoint.leftHip to Point2D(600.0,700.0), PoseJoint.rightHip to Point2D(400.0,700.0))
        val points=positions.map { Landmark(it.first,it.second,.9) }
        val observation=if(context) {
            val body=positions.map { (joint,point) ->
                GripBodyPoint(GripBodyJoint.valueOf(joint.name),point,GripVector3(
                    if(joint.name.startsWith("left")) .2 else -.2,
                    when(joint) { PoseJoint.leftHip,PoseJoint.rightHip -> .2;PoseJoint.leftWrist,PoseJoint.rightWrist -> -.5;else -> -.2 },0.0),.9)
            }
            GripObservation(timestamp.seconds,ImageSize(1000.0,1000.0),body.map { GripTrackedPoint(it.joint,it.position,it.score) },body,emptyList(),"body-only","test")
        } else null
        return PoseResult(timestamp,ImageSize(1000.0,1000.0),listOf(PoseObservation(points)),"test",1,observation)
    }
    private fun settle(width:WorkoutGripWidth,ratio:Double,start:Double=0.0):WorkoutGripWidth.Measurement {
        var result=WorkoutGripWidth.Measurement()
        for(i in 0..24)result=width.observe(pose(start+i*.05,ratio),bar,ExerciseCounter.Phase.ready,true,true)
        return result
    }
    private fun weakWrist(p:PoseResult,mode:String="weak"):PoseResult {
        val points=p.people.single().landmarks
        val changed=when(mode) {
            "missing" -> points.filter { it.joint!=PoseJoint.rightWrist }
            "duplicate" -> points+points.first { it.joint==PoseJoint.rightWrist }
            else -> points.map {
                when {
                    mode=="weakShoulder" && it.joint==PoseJoint.leftShoulder -> it.copy(confidence=.49)
                    mode=="bothWeak" && it.joint==PoseJoint.leftWrist -> it.copy(confidence=.49)
                    it.joint==PoseJoint.rightWrist -> it.copy(confidence=if(mode=="offSupport") .9 else .49,
                        position=if(mode in listOf("offSupport","weakOffSupport"))Point2D(400.0,500.0) else it.position)
                    else -> it
                }
            }
        }
        return p.copy(people=listOf(PoseObservation(changed)))
    }
    @Test fun measuresNarrowMediumWideRelativeToShoulderBaseline() {
        for((ratio,band) in listOf(.7 to GripWidth.narrow,1.0 to GripWidth.shoulderWidth,1.6 to GripWidth.wide)) {
            val result=settle(WorkoutGripWidth(),ratio)
            assertEquals(band,result.width);assertEquals(ratio,result.projectedSpanRatio!!,1e-9)
        }
        assertEquals(GripWidth.shoulderWidth,WorkoutGripGeometry.widthBand(.85))
        assertEquals(GripWidth.shoulderWidth,WorkoutGripGeometry.widthBand(1.3))
    }
    @Test fun preservesLegacySerializedMiddleBandWhileDisplayingMedium() {
        assertEquals("Medium",GripEstimate.widthTitle(GripWidth.shoulderWidth))
        val legacy="""{"width":"shoulderWidth","projectedSpanRatio":1.0}"""
        val decoded=WorkoutRecordCodec.json.decodeFromString<GripEstimate>(legacy)
        assertEquals(GripWidth.shoulderWidth,decoded.width);assertNull(decoded.widthPolicyVersion)
        val current=decoded.copy(widthPolicyVersion=WorkoutGripWidth.policyVersion)
        assertEquals("1.0× shoulders",current.widthRatioLabel)
        assertEquals("Grip unknown · Medium · 1.0× shoulders",current.label)
        assertNull(current.copy(width=GripWidth.varied).widthRatioLabel)
        assertEquals(current,WorkoutRecordCodec.json.decodeFromString<GripEstimate>(WorkoutRecordCodec.json.encodeToString(current)))
    }
    @Test fun frozenShoulderBaselineDoesNotFollowRepPosture() {
        val width=WorkoutGripWidth();settle(width,1.0)
        for(i in 1..20) {
            val result=width.observe(pose(1.2+i*.05,1.0,240.0),bar,ExerciseCounter.Phase.outbound,true,true)
            assertEquals(GripWidth.shoulderWidth,result.width)
            assertEquals(1.0,result.projectedSpanRatio!!,1e-9)
        }
    }
    @Test fun shoulderProjectionContractionDoesNotEraseAnAcquiredHangBaseline() {
        val width=WorkoutGripWidth();settle(width,1.0)
        // Supported wrists stay fixed while current shoulder projection falls
        // below the former 8%-of-image cutoff, then recovers during the rep.
        for((i,span) in listOf(200.0,79.9,79.8,100.0,240.0).withIndex()) {
            val p=pose(1.25+i*.05,shoulders=span)
            val usable=WorkoutGripWidth.hasCurrentSupport(p,bar)
            assertTrue(usable)
            val result=width.observe(p,bar,ExerciseCounter.Phase.outbound,usable,true)
            assertEquals(GripWidth.shoulderWidth,result.width)
            assertEquals(1.0,result.projectedSpanRatio!!,1e-9)
        }
    }
    @Test fun visibleSupportedSceneScaleDoesNotChangeTheWidthBand() {
        // The caller has already qualified capture/view and the starting hang.
        // Width geometry must not independently substitute a pixel-size veto.
        for((ratio,band) in listOf(.7 to GripWidth.narrow,1.0 to GripWidth.shoulderWidth,1.6 to GripWidth.wide)) {
            for(scale in listOf(1.0,.3)) {
                val width=WorkoutGripWidth();var result=WorkoutGripWidth.Measurement()
                for(i in 0..24) {
                    val original=pose(i*.05,ratio)
                    val scaled=original.copy(people=listOf(PoseObservation(original.people.single().landmarks.map {
                        it.copy(position=Point2D(500+(it.position.x-500)*scale,100+(it.position.y-100)*scale))
                    })))
                    val usable=WorkoutGripWidth.hasCurrentSupport(scaled,bar)
                    assertTrue(usable)
                    result=width.observe(scaled,bar,ExerciseCounter.Phase.ready,usable,true)
                }
                assertEquals(band,result.width)
                assertEquals(ratio,result.projectedSpanRatio!!,1e-9)
            }
        }
    }
    @Test fun removingPixelCutoffDoesNotAdmitDegenerateOrUnstableCalibration() {
        for(mode in listOf("coincidentShoulders","unstableShoulders","unsupportedView")) {
            val width=WorkoutGripWidth()
            for(i in 0..40) {
                val p=pose(i*.05,shoulders=if(mode=="coincidentShoulders")0.0 else if(i%2==0)200.0 else 300.0)
                val usable=WorkoutGripWidth.hasCurrentSupport(p,bar)
                if(mode=="coincidentShoulders")assertFalse(usable)
                val result=width.observe(p,bar,ExerciseCounter.Phase.ready,usable,mode!="unsupportedView")
                assertEquals(GripWidth.unknown,result.width)
                assertNull(result.projectedSpanRatio)
            }
        }
    }
    @Test fun freshSupportedBodyMaintainsLiveWidthCoverageThroughShoulderContraction() {
        val analysis=WorkoutGripAnalysis("set")
        for(i in 0..50)analysis.observe(pose(i*.05,context=true),bar,ExerciseCounter.Phase.ready,true)
        for(i in 1..30) {
            analysis.observe(pose(2.5+i*.05,shoulders=if(i%2==0)75.0 else 200.0,context=true),
                bar,ExerciseCounter.Phase.outbound,true)
            assertEquals(GripWidth.shoulderWidth,analysis.current.width)
            assertEquals(1.0,analysis.current.projectedSpanRatio!!,1e-9)
        }
        val summary=analysis.summarize(2.7,3.9)
        assertEquals(GripWidth.shoulderWidth,summary.width)
        assertEquals(1.0,summary.widthCoverage!!,1e-9)
        assertEquals(WorkoutGripWidth.policyVersion,summary.widthPolicyVersion)
    }
    @Test fun currentShoulderCompressionCannotVetoTheFrozenRatioButCannotAcquireAnImplausibleOne() {
        val width=WorkoutGripWidth();settle(width,1.0)
        val compressed=pose(1.25,shoulders=40.0)
        assertNull(WorkoutGripGeometry.projectedWidth(compressed,bar,40.0))
        assertTrue(WorkoutGripWidth.hasCurrentSupport(compressed,bar))
        val measured=width.observe(compressed,bar,ExerciseCounter.Phase.outbound,true,true)
        assertEquals(GripWidth.shoulderWidth,measured.width)
        assertEquals(1.0,measured.projectedSpanRatio)
        val uncalibrated=WorkoutGripWidth()
        for(i in 0..30) {
            val result=uncalibrated.observe(pose(i*.05,shoulders=40.0),bar,ExerciseCounter.Phase.ready,true,true)
            assertEquals(GripWidth.unknown,result.width);assertNull(result.projectedSpanRatio)
        }
    }
    @Test fun changedPlacementAfterConfidencePauseRequiresFreshBandConfirmation() {
        val width=WorkoutGripWidth();settle(width,.7)
        val weak=weakWrist(pose(1.25,.7))
        assertFalse(WorkoutGripWidth.hasCurrentSupport(weak,bar))
        val paused=width.observe(weak,bar,ExerciseCounter.Phase.outbound,false,true,allowCalibrationPause=true)
        assertEquals(GripWidth.unknown,paused.width);assertNull(paused.projectedSpanRatio)
        assertEquals("wristConfidencePause",paused.reason)
        var result=WorkoutGripWidth.Measurement()
        // A different fresh wrist spacing must earn Wide, never reuse Narrow.
        for(i in 0..11) {
            result=width.observe(pose(1.3+i*.05,1.6),bar,ExerciseCounter.Phase.outbound,true,true,allowCalibrationPause=true)
            if(i<9)assertEquals(GripWidth.unknown,result.width)
        }
        assertEquals(GripWidth.wide,result.width);assertEquals(1.6,result.projectedSpanRatio)
    }
    @Test fun continuingWeakFramesDoNotExtendTheCalibrationPauseDeadline() {
        val width=WorkoutGripWidth();settle(width,1.0)
        for(i in 1..6) {
            val result=width.observe(weakWrist(pose(1.2+i*.05)),bar,ExerciseCounter.Phase.outbound,false,true,allowCalibrationPause=true)
            assertEquals("wristConfidencePause",result.reason)
            assertEquals(GripWidth.unknown,result.width);assertNull(result.projectedSpanRatio)
        }
        // Last strong support was 1.2, not the last weak frame at 1.5.
        for(i in 0..15)assertNull(width.observe(pose(1.6+i*.05),bar,ExerciseCounter.Phase.outbound,true,true).projectedSpanRatio)
    }
    @Test fun aConfidencePauseCannotCreateACalibrationOrSurviveHardLosses() {
        for(mode in listOf("uncalibrated","missing","duplicate","offSupport","weakShoulder","bothWeak","viewLost","callerLost","interrupted")) {
            val width=WorkoutGripWidth()
            if(mode!="uncalibrated")settle(width,1.0)
            if(mode=="interrupted")width.interrupt()
            val p=weakWrist(pose(1.25),mode)
            val result=width.observe(p,bar,ExerciseCounter.Phase.outbound,false,mode!="viewLost",
                allowCalibrationPause=mode!="callerLost")
            assertEquals(GripWidth.unknown,result.width);assertNull(result.projectedSpanRatio)
            assertNotEquals("wristConfidencePause",result.reason,mode)
            for(i in 0..15)assertNull(width.observe(pose(1.3+i*.05),bar,ExerciseCounter.Phase.outbound,true,true).projectedSpanRatio,mode)
        }
    }
    @Test fun aPausedCalibrationCannotCrossAReferenceSizeOrClockBoundary() {
        for(mode in listOf("reference","size","clock")) {
            val width=WorkoutGripWidth();settle(width,1.0)
            width.observe(weakWrist(pose(1.25)),bar,ExerciseCounter.Phase.outbound,false,true,allowCalibrationPause=true)
            val next=if(mode=="size")pose(1.3).copy(imageSize=ImageSize(1100.0,1000.0)) else pose(if(mode=="clock")1.2 else 1.3)
            val reference=if(mode=="reference")BarSegment(Point2D(0.0,105.0),Point2D(1000.0,105.0)) else bar
            assertNull(width.observe(next,reference,ExerciseCounter.Phase.outbound,true,true).projectedSpanRatio)
        }
    }
    @Test fun liveConfidencePausesEarnNoWidthOrHandCreditAndRecoverWithoutANewReadyHang() {
        val analysis=WorkoutGripAnalysis("set")
        for(i in 0..50)analysis.observe(pose(i*.05,context=true),bar,ExerciseCounter.Phase.ready,true)
        analysis.observe(weakWrist(pose(2.55,context=true)),bar,ExerciseCounter.Phase.outbound,true)
        assertEquals("wristConfidencePause",analysis.current.widthReason)
        assertEquals(GripWidth.unknown,analysis.current.width);assertNull(analysis.current.projectedSpanRatio)
        assertEquals(0.0,analysis.record.widthSpans.last().observedSeconds)
        for(i in 0..40)analysis.observe(pose(2.6+i*.05,context=true),bar,ExerciseCounter.Phase.outbound,true)
        assertEquals(GripWidth.shoulderWidth,analysis.current.width)
        assertTrue(analysis.summarize(2.4,4.5).widthCoverage!!<1.0)
        assertEquals(GripWidth.shoulderWidth,analysis.summarize(3.3,4.5).width)
        assertEquals(GripOrientation.unknown,analysis.current.orientation)
        assertTrue(analysis.record.orientationSpans.all { it.observedSeconds==0.0 })
    }
    @Test fun aWeakWristOutlierIsUnmeasuredAndFreshMatchingHandsCanResumeAConfirmedWidth() {
        val width=WorkoutGripWidth();settle(width,1.6)
        val paused=width.observe(weakWrist(pose(1.25,1.6),"weakOffSupport"),bar,
            ExerciseCounter.Phase.outbound,false,true,allowCalibrationPause=true)
        assertEquals("wristConfidencePause",paused.reason)
        assertEquals(GripWidth.unknown,paused.width);assertNull(paused.projectedSpanRatio)
        val recovered=width.observe(pose(1.3,1.62),bar,ExerciseCounter.Phase.outbound,true,true)
        assertEquals(GripWidth.wide,recovered.width)
        // Fresh coordinates, not the last ratio of 1.6, supply the measurement.
        assertEquals(1.62,recovered.projectedSpanRatio)
    }
    @Test fun theSameBandAtADifferentHandPlacementStillNeedsConfirmation() {
        val width=WorkoutGripWidth();settle(width,1.6)
        width.observe(weakWrist(pose(1.25,1.6)),bar,ExerciseCounter.Phase.outbound,false,true,allowCalibrationPause=true)
        fun moved(t:Double):PoseResult {
            val p=pose(t,1.6)
            return p.copy(people=listOf(PoseObservation(p.people.single().landmarks.map {
                if(it.joint in listOf(PoseJoint.leftWrist,PoseJoint.rightWrist))it.copy(position=Point2D(it.position.x+50,it.position.y)) else it
            })))
        }
        var result=width.observe(moved(1.3),bar,ExerciseCounter.Phase.outbound,true,true)
        assertEquals(GripWidth.unknown,result.width)
        for(i in 1..11)result=width.observe(moved(1.3+i*.05),bar,ExerciseCounter.Phase.outbound,true,true)
        assertEquals(GripWidth.wide,result.width)
    }
    @Test fun weakOrMissingTorsoAnchorsAndForeignBodiesCannotPreserveCalibration() {
        for(mode in listOf("weakHip","missingHip","duplicateHip","bodyJump","jumpOnReturn")) {
            val width=WorkoutGripWidth();settle(width,1.0)
            var p=weakWrist(pose(1.25),"weakOffSupport")
            fun changeBody(p:PoseResult):PoseResult {
                val points=p.people.single().landmarks
                val changed=when(mode) {
                    "missingHip" -> points.filter { it.joint!=PoseJoint.leftHip }
                    "duplicateHip" -> points+points.first { it.joint==PoseJoint.leftHip }
                    else -> points.map { l -> when {
                        mode=="weakHip" && l.joint==PoseJoint.leftHip -> l.copy(confidence=.49)
                        mode in listOf("bodyJump","jumpOnReturn") && l.joint in listOf(PoseJoint.leftShoulder,PoseJoint.rightShoulder,PoseJoint.leftHip,PoseJoint.rightHip) ->
                            l.copy(position=Point2D(l.position.x+200,l.position.y))
                        else -> l
                    } }
                }
                return p.copy(people=listOf(PoseObservation(changed)))
            }
            if(mode!="jumpOnReturn")p=changeBody(p)
            val paused=width.observe(p,bar,ExerciseCounter.Phase.outbound,false,true,allowCalibrationPause=true)
            if(mode=="jumpOnReturn")assertEquals("wristConfidencePause",paused.reason)
            else assertNotEquals("wristConfidencePause",paused.reason)
            val next=if(mode=="jumpOnReturn")changeBody(pose(1.3)) else pose(1.3)
            assertNull(width.observe(next,bar,ExerciseCounter.Phase.outbound,true,true).projectedSpanRatio,mode)
        }
    }
    @Test fun aPauseBeforeTheFirstBandConfirmationCannotCreateAConfirmedWidth() {
        val width=WorkoutGripWidth()
        for(i in 0..10)width.observe(pose(i*.05),bar,ExerciseCounter.Phase.ready,true,true)
        val paused=width.observe(weakWrist(pose(.55)),bar,ExerciseCounter.Phase.outbound,false,true,allowCalibrationPause=true)
        assertEquals("wristConfidencePause",paused.reason)
        val resumed=width.observe(pose(.6),bar,ExerciseCounter.Phase.outbound,true,true)
        assertEquals(GripWidth.unknown,resumed.width)
        assertEquals("confirmingWidth",resumed.reason)
    }
    @Test fun positiveSupportLossClearsEvenAnOutlierPauseAndMatchingPlacementWitness() {
        val width=WorkoutGripWidth();settle(width,1.0)
        width.observe(weakWrist(pose(1.25),"weakOffSupport"),bar,ExerciseCounter.Phase.outbound,false,true,allowCalibrationPause=true)
        val released=width.observe(weakWrist(pose(1.3),"offSupport"),bar,ExerciseCounter.Phase.outbound,false,true,allowCalibrationPause=true)
        assertEquals(GripWidth.unknown,released.width)
        for(i in 0..15)assertNull(width.observe(pose(1.35+i*.05),bar,ExerciseCounter.Phase.outbound,true,true).projectedSpanRatio)
    }
    @Test fun liveOutlierRecoveryCreditsOnlyFreshMeasurementsAfterTheGap() {
        val analysis=WorkoutGripAnalysis("set")
        for(i in 0..50)analysis.observe(pose(i*.05,context=true),bar,ExerciseCounter.Phase.ready,true)
        analysis.observe(weakWrist(pose(2.55,context=true),"weakOffSupport"),bar,ExerciseCounter.Phase.outbound,true)
        assertEquals(GripWidth.unknown,analysis.current.width)
        assertNull(analysis.current.projectedSpanRatio)
        analysis.observe(pose(2.6,context=true),bar,ExerciseCounter.Phase.outbound,true)
        assertEquals(GripWidth.shoulderWidth,analysis.current.width)
        assertEquals(0.0,analysis.record.widthSpans.last().observedSeconds)
        analysis.observe(pose(2.65,context=true),bar,ExerciseCounter.Phase.outbound,true)
        assertEquals(.05,analysis.record.widthSpans.last().observedSeconds,1e-9)
        assertTrue(analysis.record.widthSpans.filter { it.width==GripWidth.unknown }.all { it.observedSeconds==0.0 })
    }
    @Test fun calibrationObservesTheCountersHangDwellButCannotActivateBeforeReady() {
        fun hang(t:Double,bent:Boolean=false):PoseResult {
            val p=pose(t)
            val elbows=listOf(PoseJoint.leftElbow to Point2D(if(bent)800.0 else 600.0,250.0),
                PoseJoint.rightElbow to Point2D(if(bent)200.0 else 400.0,250.0))
            return p.copy(people=listOf(PoseObservation(p.people.single().landmarks+elbows.map { Landmark(it.first,it.second,.9) })))
        }
        for(bent in listOf(false,true)) {
            val width=WorkoutGripWidth()
            for(i in 0..8) {
                val result=width.observe(hang(i*.05,bent),bar,ExerciseCounter.Phase.seekingStart,true,true)
                assertEquals(GripWidth.unknown,result.width)
                assertNull(result.projectedSpanRatio)
            }
            val acquired=width.observe(hang(.45),bar,ExerciseCounter.Phase.ready,true,true)
            if(bent)assertNull(acquired.projectedSpanRatio) else assertEquals(1.0,acquired.projectedSpanRatio)
            if(!bent) {
                var result=acquired
                for(i in 1..12)result=width.observe(hang(.45+i*.05),bar,ExerciseCounter.Phase.outbound,true,true)
                assertEquals(GripWidth.shoulderWidth,result.width)
            }
        }
    }
    @Test fun unknownHandOrientationDoesNotBlockLiveWidthOrRepSummary() {
        val analysis=WorkoutGripAnalysis("set")
        for(i in 0..50)analysis.observe(pose(i*.05,1.0,context=true),bar,ExerciseCounter.Phase.ready,true)
        assertEquals(GripOrientation.unknown,analysis.current.orientation)
        assertEquals(GripWidth.shoulderWidth,analysis.current.width)
        val summary=analysis.summarize(1.3,2.4)
        assertEquals(GripOrientation.unknown,summary.orientation)
        assertEquals(GripWidth.shoulderWidth,summary.width)
        assertEquals(1.0,summary.widthCoverage!!,1e-9)
        assertEquals(WorkoutGripWidth.policyVersion,summary.widthPolicyVersion)
    }
    @Test fun currentSupportedWristsAndShouldersRetainWidthWhenArmMeasurementIsUnavailable() {
        val analysis=WorkoutGripAnalysis("set")
        for(i in 0..50)analysis.observe(pose(i*.05,context=true),bar,ExerciseCounter.Phase.ready,true)
        for(i in 1..30) {
            // These body observations contain no elbows. Their wrists and
            // shoulders are still current; the counter cannot measure an arm.
            val current=pose(2.5+i*.05,context=true)
            assertNull(ArmMeasurement(current,ArmMeasurement.Side.left).estimate)
            analysis.observe(current,bar,ExerciseCounter.Phase.outbound,false)
            assertEquals(GripWidth.shoulderWidth,analysis.current.width)
            assertEquals(GripOrientation.unknown,analysis.current.orientation)
        }
        assertEquals(GripWidth.shoulderWidth,analysis.summarize(2.7,3.9).width)
        assertEquals(WorkoutGripWidth.policyVersion,analysis.current.widthPolicyVersion)
    }
    @Test fun actualWristLossStillInvalidatesWidthDuringAnArmMeasurementOutage() {
        val analysis=WorkoutGripAnalysis("set")
        for(i in 0..50)analysis.observe(pose(i*.05,context=true),bar,ExerciseCounter.Phase.ready,true)
        val missing=pose(2.55,context=true).let { p -> p.copy(people=listOf(PoseObservation(
            p.people.single().landmarks.filter { it.joint!=PoseJoint.rightWrist }))) }
        analysis.observe(missing,bar,ExerciseCounter.Phase.outbound,false)
        assertEquals(GripWidth.unknown,analysis.current.width)
        for(i in 1..30) {
            analysis.observe(pose(2.55+i*.05,context=true),bar,ExerciseCounter.Phase.outbound,true)
            assertEquals(GripWidth.unknown,analysis.current.width)
        }
    }
    @Test fun armOutageCannotPreserveWidthAcrossExplicitInterruptionOrStaleView() {
        for(explicit in listOf(false,true)) {
            val analysis=WorkoutGripAnalysis("set")
            for(i in 0..50)analysis.observe(pose(i*.05,context=true),bar,ExerciseCounter.Phase.ready,true)
            if(explicit)analysis.interrupt()
            for(i in 1..30)analysis.observe(pose(2.5+i*.05,context=!explicit && i<5),bar,ExerciseCounter.Phase.outbound,false)
            assertEquals(GripWidth.unknown,analysis.current.width)
        }
    }
    @Test fun widthSupportChecksKeepExistingConfidenceAndBilateralRequirements() {
        assertTrue(WorkoutGripWidth.hasCurrentSupport(pose(0.0),bar))
        for(joint in listOf(PoseJoint.leftShoulder,PoseJoint.rightShoulder,PoseJoint.leftWrist,PoseJoint.rightWrist)) {
            val p=pose(0.0)
            val weak=p.copy(people=listOf(PoseObservation(p.people.single().landmarks.map {
                if(it.joint==joint)it.copy(confidence=.49) else it })))
            assertFalse(WorkoutGripWidth.hasCurrentSupport(weak,bar))
        }
        assertFalse(WorkoutGripWidth.hasCurrentSupport(pose(0.0).copy(people=emptyList()),bar))
        assertFalse(WorkoutGripWidth.hasCurrentSupport(pose(0.0),null))
    }
    @Test fun unsupportedViewClearsBaselineAndRequiresANewReadyHang() {
        val width=WorkoutGripWidth();settle(width,1.0)
        assertEquals(GripWidth.unknown,width.observe(pose(1.3),bar,ExerciseCounter.Phase.ready,true,false).width)
        for(i in 1..20)assertEquals(GripWidth.unknown,width.observe(pose(1.3+i*.05),bar,ExerciseCounter.Phase.outbound,true,true).width)
        assertEquals(GripWidth.wide,settle(width,1.6,2.4).width)
    }
    @Test fun sourceGapsAndRegressedClocksCannotReuseBaseline() {
        val width=WorkoutGripWidth();settle(width,.7)
        assertEquals(GripWidth.unknown,width.observe(pose(3.0,.7),bar,ExerciseCounter.Phase.outbound,true,true).width)
        settle(width,.7,3.1)
        assertEquals(GripWidth.unknown,width.observe(pose(2.0,.7),bar,ExerciseCounter.Phase.ready,true,true).width)
        assertEquals(GripWidth.unknown,width.observe(pose(4.4,.7),bar,ExerciseCounter.Phase.outbound,true,true).width)
    }
    @Test fun missingOrDuplicateWristsCannotAcquireOrReportWidth() {
        for(mode in listOf("missing","duplicate","offSupport")) {
            val width=WorkoutGripWidth()
            for(i in 0..24) {
                val p=pose(i*.05)
                val landmarks=p.people.single().landmarks
                val modified=when(mode) {
                    "missing" -> landmarks.filter { it.joint!=PoseJoint.rightWrist }
                    "duplicate" -> landmarks+landmarks.first { it.joint==PoseJoint.rightWrist }
                    else -> landmarks.map { if(it.joint==PoseJoint.rightWrist)it.copy(position=Point2D(400.0,500.0)) else it }
                }
                assertEquals(GripWidth.unknown,width.observe(p.copy(people=listOf(PoseObservation(modified))),bar,ExerciseCounter.Phase.ready,true,true).width)
            }
            assertEquals(GripWidth.unknown,width.observe(pose(1.3),bar,ExerciseCounter.Phase.outbound,true,true).width)
        }
    }
    @Test fun alternatingSetsAcquireTheirOwnMeasurements() {
        val first=WorkoutGripWidth();val second=WorkoutGripWidth()
        assertEquals(GripWidth.narrow,settle(first,.7).width)
        assertEquals(GripWidth.unknown,second.observe(pose(0.0,1.6),bar,ExerciseCounter.Phase.ready,true,true).width)
        assertEquals(GripWidth.wide,settle(second,1.6,.05).width)
        assertEquals(GripWidth.narrow,first.observe(pose(1.25,.7),bar,ExerciseCounter.Phase.outbound,true,true).width)
    }
    @Test fun aWidthChangeWithinOneRepIsPreservedInsteadOfAveragedIntoMedium() {
        val record=WorkoutGripRecord(emptyList(),listOf(
            GripWidthSpan("first",0.0,1.0,GripWidth.narrow,.7,1.0,null),
            GripWidthSpan("second",1.0,2.0,GripWidth.wide,1.6,1.0,null)),
            GripEstimate(widthPolicyVersion=1))
        val estimate=WorkoutGripAnalysis.summary(record,0.0,2.0)
        assertEquals(GripWidth.varied,estimate.width)
        assertEquals(1.0,estimate.widthCoverage)
        assertNull(estimate.widthRatioLabel)
        assertEquals(GripOrientation.unknown,estimate.orientation)
    }
    @Test fun perRepMetadataAndTotalsPreserveChangesWithoutGuessingAlternation() {
        val analysis=WorkoutAnalysis("set",ExerciseCounter.Exercise.pullUp)
        analysis.observe(MotionSample(0.0,170.0,170.0,null,true,false),ExerciseCounter.Phase.ready,null)
        analysis.observe(MotionSample(.2,90.0,90.0,null,true,false),ExerciseCounter.Phase.outbound,null)
        analysis.observe(MotionSample(.4,80.0,80.0,null,true,false),ExerciseCounter.Phase.returning,ExerciseCounter.Event(ExerciseCounter.Outcome.movement,.4,"test"))
        analysis.observe(MotionSample(.6,170.0,170.0,null,true,false),ExerciseCounter.Phase.ready,null)
        val base=analysis.records.single()
        val types=listOf(GripOrientation.overhand to GripWidth.narrow,GripOrientation.underhand to GripWidth.wide,
            GripOrientation.unknown to GripWidth.shoulderWidth,GripOrientation.overhand to GripWidth.narrow)
        val reps=types.mapIndexed { i,(orientation,width) -> base.copy(id="rep-$i",ordinal=i+1,
            grip=GripEstimate(width=width,orientation=orientation,projectedSpanRatio=if(width==GripWidth.narrow).7 else if(width==GripWidth.wide)1.6 else 1.0,widthPolicyVersion=1)) }
        val record=WorkoutSetRecord("set",ExerciseCounter.Exercise.pullUp,0.0,.6,"manual",reps+base.copy(id="unfinished",ordinal=5,returnedAt=null,closeReason="manual"),"test",1)
        val decoded=WorkoutRecordCodec.json.decodeFromString<WorkoutSetRecord>(WorkoutRecordCodec.json.encodeToString(record))
        val report=WorkoutGripReview.report(decoded)
        assertEquals(types,report.reps.take(4).map { it.orientation to it.width })
        assertEquals(listOf(.7,1.6,1.0,.7),report.reps.take(4).map { it.measured!!.projectedSpanRatio })
        assertEquals(4,report.totals.sumOf { it.completedReps })
        assertEquals(2,report.totals.single { it.label=="Overhand · Narrow" }.completedReps)
        assertEquals(1,report.totals.single { it.label=="Grip unknown · Medium" }.completedReps)
    }
}
