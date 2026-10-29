package dev.hanginthere.core

import kotlin.test.*

class CameraAcquisitionEvidenceTests {
    @Test fun selfRegistrationAndOneLaterFrameCannotQualifyAnEpoch() {
        val evidence = CameraAcquisitionEvidence()
        assertTrue(evidence.beginReference(0,1000))
        assertFalse(evidence.permitsRegistration(0,1000))
        evidence.observeScene(0,1000,true)
        assertEquals(0,evidence.independentObservations)
        evidence.observeScene(40,1000,true)
        assertFalse(evidence.hasIndependentEvidence)
        evidence.observeScene(289999,1000000,true)
        assertFalse(evidence.hasIndependentEvidence)
        evidence.observeScene(290000,1000000,true)
        assertTrue(evidence.hasIndependentEvidence)
    }
    @Test fun unknownOrMovingInitialMeasurementsCannotEarnStableDwell() {
        val evidence = CameraAcquisitionEvidence();evidence.beginReference(0,1000)
        evidence.observeScene(40,1000,true)
        evidence.observeScene(400,1000,false)
        evidence.observeScene(720,1000,true)
        assertFalse(evidence.hasIndependentEvidence)
        evidence.observeScene(1040,1000,true)
        assertTrue(evidence.hasIndependentEvidence)
        // Historical epoch qualification is not current scene availability.
        // Native capture must combine it with a fresh scene/phone verdict.
        evidence.observeScene(1400,1000,false)
        assertTrue(evidence.hasIndependentEvidence)
    }
    @Test fun newReferenceCannotBorrowOldSamplesOrDelayedCallbacks() {
        val evidence = CameraAcquisitionEvidence();evidence.beginReference(0,1000)
        evidence.observeScene(40,1000,true);evidence.observeScene(400,1000,true)
        assertTrue(evidence.hasIndependentEvidence)
        evidence.beginReference(600,1000)
        evidence.observeScene(400,1000,true)
        assertFalse(evidence.hasIndependentEvidence)
        evidence.observeScene(640,1000,true)
        assertFalse(evidence.hasIndependentEvidence)
        evidence.observeScene(1000,1000,true)
        assertTrue(evidence.hasIndependentEvidence)
    }
    @Test fun retryCannotBridgeRunningOrIneligibleFramesOrAClockFault() {
        for(fault in listOf("running","ineligible","duplicate")) {
            val evidence = CameraAcquisitionEvidence();evidence.beginReference(0,1000)
            assertFalse(evidence.shouldReacquire(40,1000,false,true,true))
            assertFalse(evidence.shouldReacquire(400,1000,false,true,true))
            assertFalse(evidence.shouldReacquire(if(fault=="duplicate")400 else 800,1000,false,
                idle=fault!="running",eligible=fault!="ineligible"))
            assertFalse(evidence.shouldReacquire(840,1000,false,true,true))
            assertFalse(evidence.shouldReacquire(1200,1000,false,true,true))
            assertFalse(evidence.shouldReacquire(1639999,1000000,false,true,true))
            assertTrue(evidence.shouldReacquire(1640000,1000000,false,true,true))
        }
    }
    @Test fun sourceGapAndAValidSceneResetTheUnknownReferenceTimer() {
        val evidence = CameraAcquisitionEvidence();evidence.beginReference(0,1000)
        assertFalse(evidence.shouldReacquire(40,1000,false,true,true))
        assertFalse(evidence.shouldReacquire(841,1000,false,true,true))
        assertFalse(evidence.shouldReacquire(1200,1000,true,true,true))
        assertFalse(evidence.shouldReacquire(1240,1000,false,true,true))
        assertFalse(evidence.shouldReacquire(1600,1000,false,true,true))
        assertTrue(evidence.shouldReacquire(2040,1000,false,true,true))
    }
    @Test fun hugeMixedClocksKeepTheExactQuarterSecondBoundary() {
        val evidence = CameraAcquisitionEvidence()
        val origin = 900_000_000_000_000_000L
        evidence.beginReference(origin,1_000_000_000)
        evidence.observeScene(origin+40_000_000,1_000_000_000,true)
        evidence.observeScene(900_000_000_289_999,1_000_000,true)
        assertFalse(evidence.hasIndependentEvidence)
        evidence.observeScene(origin+290_000_000,1_000_000_000,true)
        assertTrue(evidence.hasIndependentEvidence)
        evidence.observeScene(origin+290_000_000,1_000_000_000,true)
        assertFalse(evidence.hasIndependentEvidence)
    }
}
