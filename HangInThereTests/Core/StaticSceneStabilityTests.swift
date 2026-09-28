import Foundation
import Testing
@testable import HangInThere

struct StaticSceneStabilityTests {
    @Test func smallConsensusShiftStaysStable() {
        var monitor = StaticSceneStability()
        let calibrated = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        #expect(calibrated)
        #expect(monitor.state == .calibrating)

        monitor.observe(motions(
            topLeft: (2, 1),
            topRight: (3, 1),
            bottomLeft: (2, 2),
            bottomRight: (80, -60),
            scaleFractions: [0.002, 0.003, 0.002, 0.08]
        ), timestamp: 0.1)

        #expect(monitor.state == .stable)
        #expect(monitor.latestConsensusPatches == 3)
        #expect(monitor.latestScaleConsensusPatches == 3)
        #expect((monitor.latestShiftFraction ?? 1) < StaticSceneStability.movementThresholdFraction)
        #expect((monitor.latestScaleFraction ?? 1) < StaticSceneStability.scaleThresholdFraction)
    }

    @Test func movingAthleteOutlierDoesNotLookLikeCameraMovement() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(motions(all: (1, 0), scaleFraction: 0.002), timestamp: 0.05)
        #expect(monitor.state == .stable)

        monitor.observe(motions(
            topLeft: (1, 0),
            topRight: (2, 1),
            bottomLeft: (1, -1),
            bottomRight: (140, 90),
            scaleFractions: [0.002, 0.003, 0.002, 0.2]
        ), timestamp: 0.1)

        #expect(monitor.state == .stable)
        #expect(monitor.latestConsensusPatches == 3)
        #expect(monitor.latestScaleConsensusPatches == 3)
    }

    @Test func sustainedConsensusTranslationInvalidatesCalibration() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(motions(all: (1, 0), scaleFraction: 0.002), timestamp: 0.05)
        #expect(monitor.state == .stable)

        let moved = motions(all: (12, 2), scaleFraction: 0.002)
        monitor.observe(moved, timestamp: 0.10)
        monitor.observe(moved, timestamp: 0.25)
        #expect(monitor.state == .stable)

        monitor.observe(moved, timestamp: 0.36)
        #expect(monitor.state == .moved)
        #expect(monitor.movementKind == .translation)
        #expect((monitor.latestShiftFraction ?? 0) > StaticSceneStability.movementThresholdFraction)
    }

    @Test func sustainedHomographicScaleInvalidatesCalibration() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(motions(all: (0, 0), scaleFraction: 0.002), timestamp: 0.05)
        #expect(monitor.state == .stable)

        let scaled = motions(all: (0, 0), scaleFraction: 0.03)
        monitor.observe(scaled, timestamp: 0.10)
        monitor.observe(scaled, timestamp: 0.25)
        #expect(monitor.state == .stable)
        #expect((monitor.latestScaleFraction ?? 0) > StaticSceneStability.scaleThresholdFraction)

        monitor.observe(scaled, timestamp: 0.36)
        #expect(monitor.state == .moved)
        #expect(monitor.movementKind == .scale)
    }

    @Test func translationDoesNotMasqueradeAsScale() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(motions(all: (12, -8), scaleFraction: 0.002), timestamp: 0.1)

        #expect((monitor.latestScaleFraction ?? 1) < StaticSceneStability.scaleThresholdFraction)
    }

    @Test func briefTranslationRecoversBeforeDwell() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(motions(all: (1, 0), scaleFraction: 0.002), timestamp: 0.05)
        #expect(monitor.state == .stable)

        monitor.observe(motions(all: (12, 0), scaleFraction: 0.002), timestamp: 0.1)
        monitor.observe(motions(all: (1, 1), scaleFraction: 0.002), timestamp: 0.2)
        monitor.observe(motions(all: (12, 0), scaleFraction: 0.002), timestamp: 0.3)

        #expect(monitor.state == .stable)
    }

    @Test func briefScaleRecoversBeforeDwell() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(motions(all: (0, 0), scaleFraction: 0.002), timestamp: 0.05)
        #expect(monitor.state == .stable)

        monitor.observe(motions(all: (0, 0), scaleFraction: 0.03), timestamp: 0.1)
        monitor.observe(motions(all: (0, 0), scaleFraction: 0.002), timestamp: 0.2)
        monitor.observe(motions(all: (0, 0), scaleFraction: 0.03), timestamp: 0.3)

        #expect(monitor.state == .stable)
    }

    @Test func inconsistentTranslationPatchesDoNotCreateFalseMovement() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))

        monitor.observe(motions(
            topLeft: (20, 0),
            topRight: (-20, 0),
            bottomLeft: (0, 20),
            bottomRight: (0, -20),
            scaleFractions: [0.002, 0.002, 0.002, 0.002]
        ), timestamp: 0.1)

        #expect(monitor.state == .calibrating)
        #expect(monitor.latestConsensusPatches < StaticSceneStability.minimumConsensusPatches)
        #expect(monitor.latestScaleConsensusPatches == 4)
    }

    @Test func inconsistentScalePatchesDoNotCreateFalseMovement() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))

        monitor.observe(motions(
            topLeft: (0, 0),
            topRight: (0, 0),
            bottomLeft: (0, 0),
            bottomRight: (0, 0),
            scaleFractions: [0.01, 0.04, 0.08, 0.15]
        ), timestamp: 0.1)

        #expect(monitor.state == .calibrating)
        #expect(monitor.latestScaleConsensusPatches < StaticSceneStability.minimumScaleConsensusPatches)
    }

    @Test func staleTimestampCannotAdvanceMovementDwell() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(motions(all: (1, 0), scaleFraction: 0.002), timestamp: 10.05)
        #expect(monitor.state == .stable)

        let moved = motions(all: (12, 0), scaleFraction: 0.002)
        monitor.observe(moved, timestamp: 10.1)
        monitor.observe(moved, timestamp: 9)
        monitor.observe(moved, timestamp: 10.2)
        #expect(monitor.state == .stable)
    }

    private func motions(
        all shift: (Double, Double),
        scaleFraction: Double
    ) -> [StaticSceneStability.PatchMotion] {
        motions(
            topLeft: shift,
            topRight: shift,
            bottomLeft: shift,
            bottomRight: shift,
            scaleFractions: Array(repeating: scaleFraction, count: 4)
        )
    }

    private func motions(
        topLeft: (Double, Double),
        topRight: (Double, Double),
        bottomLeft: (Double, Double),
        bottomRight: (Double, Double),
        scaleFractions: [Double]
    ) -> [StaticSceneStability.PatchMotion] {
        let shifts = [topLeft, topRight, bottomLeft, bottomRight]
        return shifts.enumerated().map { index, shift in
            .init(
                dxPixels: shift.0,
                dyPixels: shift.1,
                scaleFraction: scaleFractions[index]
            )
        }
    }
}
