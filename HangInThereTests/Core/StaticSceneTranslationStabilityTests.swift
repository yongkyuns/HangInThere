import Foundation
import Testing
@testable import HangInThere

struct StaticSceneTranslationStabilityTests {
    @Test func smallConsensusShiftStaysStable() {
        var monitor = StaticSceneTranslationStability()
        let calibrated = monitor.calibrate(imageShortSide: 1000)
        #expect(calibrated)
        #expect(monitor.state == .calibrating)

        monitor.observe([
            .init(dxPixels: 2, dyPixels: 1),
            .init(dxPixels: 3, dyPixels: 1),
            .init(dxPixels: 2, dyPixels: 2),
            .init(dxPixels: 80, dyPixels: -60)
        ], timestamp: 0.1)

        #expect(monitor.state == .stable)
        #expect(monitor.latestConsensusPatches == 3)
        #expect((monitor.latestShiftFraction ?? 1) < StaticSceneTranslationStability.movementThresholdFraction)
    }

    @Test func movingAthleteOutlierDoesNotLookLikeCameraTranslation() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageShortSide: 1000)
        monitor.observe([
            .init(dxPixels: 1, dyPixels: 0),
            .init(dxPixels: 2, dyPixels: 1)
        ], timestamp: 0.05)
        #expect(monitor.state == .stable)

        monitor.observe([
            .init(dxPixels: 1, dyPixels: 0),
            .init(dxPixels: 2, dyPixels: 1),
            .init(dxPixels: 1, dyPixels: -1),
            .init(dxPixels: 140, dyPixels: 90)
        ], timestamp: 0.1)

        #expect(monitor.state == .stable)
        #expect(monitor.latestConsensusPatches == 3)
    }

    @Test func sustainedConsensusTranslationInvalidatesCalibration() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageShortSide: 1000)
        monitor.observe([
            .init(dxPixels: 1, dyPixels: 0),
            .init(dxPixels: 2, dyPixels: 1)
        ], timestamp: 0.05)
        #expect(monitor.state == .stable)

        let shifts = [
            StaticSceneTranslationStability.PatchShift(dxPixels: 12, dyPixels: 2),
            .init(dxPixels: 13, dyPixels: 1),
            .init(dxPixels: 11, dyPixels: 3),
            .init(dxPixels: 120, dyPixels: -80)
        ]

        monitor.observe(shifts, timestamp: 0.10)
        monitor.observe(shifts, timestamp: 0.25)
        #expect(monitor.state == .stable)

        monitor.observe(shifts, timestamp: 0.36)
        #expect(monitor.state == .moved)
        #expect((monitor.latestShiftFraction ?? 0) > StaticSceneTranslationStability.movementThresholdFraction)
    }

    @Test func briefTranslationRecoversBeforeDwell() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageShortSide: 1000)
        monitor.observe([
            .init(dxPixels: 1, dyPixels: 0),
            .init(dxPixels: 2, dyPixels: 1)
        ], timestamp: 0.05)
        #expect(monitor.state == .stable)

        monitor.observe([
            .init(dxPixels: 12, dyPixels: 0),
            .init(dxPixels: 11, dyPixels: 1)
        ], timestamp: 0.1)
        monitor.observe([
            .init(dxPixels: 1, dyPixels: 1),
            .init(dxPixels: 2, dyPixels: 0)
        ], timestamp: 0.2)
        monitor.observe([
            .init(dxPixels: 12, dyPixels: 0),
            .init(dxPixels: 11, dyPixels: 1)
        ], timestamp: 0.3)

        #expect(monitor.state == .stable)
    }

    @Test func inconsistentPatchesDoNotCreateFalseMovement() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageShortSide: 1000)

        monitor.observe([
            .init(dxPixels: 20, dyPixels: 0),
            .init(dxPixels: -20, dyPixels: 0),
            .init(dxPixels: 0, dyPixels: 20),
            .init(dxPixels: 0, dyPixels: -20)
        ], timestamp: 0.1)

        #expect(monitor.state == .calibrating)
        #expect(monitor.latestConsensusPatches < StaticSceneTranslationStability.minimumConsensusPatches)
    }

    @Test func staleTimestampCannotAdvanceMovementDwell() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageShortSide: 1000)
        monitor.observe([
            .init(dxPixels: 1, dyPixels: 0),
            .init(dxPixels: 2, dyPixels: 1)
        ], timestamp: 10.05)
        #expect(monitor.state == .stable)
        let shifts = [
            StaticSceneTranslationStability.PatchShift(dxPixels: 12, dyPixels: 0),
            .init(dxPixels: 12, dyPixels: 1)
        ]

        monitor.observe(shifts, timestamp: 10.1)
        monitor.observe(shifts, timestamp: 9)
        monitor.observe(shifts, timestamp: 10.2)
        #expect(monitor.state == .stable)
    }
}
