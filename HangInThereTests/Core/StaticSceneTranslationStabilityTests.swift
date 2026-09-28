import Foundation
import Testing
@testable import HangInThere

struct StaticSceneTranslationStabilityTests {
    @Test func smallConsensusShiftStaysStable() {
        var monitor = StaticSceneTranslationStability()
        let calibrated = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        #expect(calibrated)
        #expect(monitor.state == .calibrating)

        monitor.observe(cornerShifts(
            topLeft: (2, 1),
            topRight: (3, 1),
            bottomLeft: (2, 2),
            bottomRight: (80, -60)
        ), timestamp: 0.1)

        #expect(monitor.state == .stable)
        #expect(monitor.latestConsensusPatches == 3)
        #expect(monitor.latestScaleConsensusPatches >= 3)
        #expect((monitor.latestShiftFraction ?? 1) < StaticSceneTranslationStability.movementThresholdFraction)
        #expect((monitor.latestScaleFraction ?? 1) < StaticSceneTranslationStability.scaleThresholdFraction)
    }

    @Test func movingAthleteOutlierDoesNotLookLikeCameraMovement() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(cornerShifts(
            topLeft: (1, 0),
            topRight: (2, 1),
            bottomLeft: (1, -1),
            bottomRight: (1, 0)
        ), timestamp: 0.05)
        #expect(monitor.state == .stable)

        monitor.observe(cornerShifts(
            topLeft: (1, 0),
            topRight: (2, 1),
            bottomLeft: (1, -1),
            bottomRight: (140, 90)
        ), timestamp: 0.1)

        #expect(monitor.state == .stable)
        #expect(monitor.latestConsensusPatches == 3)
        #expect(monitor.latestScaleConsensusPatches >= 3)
    }

    @Test func sustainedConsensusTranslationInvalidatesCalibration() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(cornerShifts(all: (1, 0)), timestamp: 0.05)
        #expect(monitor.state == .stable)

        let shifts = cornerShifts(all: (12, 2))
        monitor.observe(shifts, timestamp: 0.10)
        monitor.observe(shifts, timestamp: 0.25)
        #expect(monitor.state == .stable)

        monitor.observe(shifts, timestamp: 0.36)
        #expect(monitor.state == .moved)
        #expect(monitor.movementKind == .translation)
        #expect((monitor.latestShiftFraction ?? 0) > StaticSceneTranslationStability.movementThresholdFraction)
    }

    @Test func sustainedRadialScaleInvalidatesCalibration() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(cornerShifts(all: (0, 0)), timestamp: 0.05)
        #expect(monitor.state == .stable)

        let radial = radialScaleShifts(fraction: 0.03)
        monitor.observe(radial, timestamp: 0.10)
        monitor.observe(radial, timestamp: 0.25)
        #expect(monitor.state == .stable)
        #expect((monitor.latestScaleFraction ?? 0) > StaticSceneTranslationStability.scaleThresholdFraction)

        monitor.observe(radial, timestamp: 0.36)
        #expect(monitor.state == .moved)
        #expect(monitor.movementKind == .scale)
    }

    @Test func translationDoesNotMasqueradeAsScale() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(cornerShifts(all: (2, -3)), timestamp: 0.1)

        #expect(monitor.state == .stable)
        #expect((monitor.latestScaleFraction ?? 1) < 0.002)
    }

    @Test func briefTranslationRecoversBeforeDwell() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(cornerShifts(all: (1, 0)), timestamp: 0.05)
        #expect(monitor.state == .stable)

        monitor.observe(cornerShifts(all: (12, 0)), timestamp: 0.1)
        monitor.observe(cornerShifts(all: (1, 1)), timestamp: 0.2)
        monitor.observe(cornerShifts(all: (12, 0)), timestamp: 0.3)

        #expect(monitor.state == .stable)
    }

    @Test func briefScaleRecoversBeforeDwell() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(cornerShifts(all: (0, 0)), timestamp: 0.05)
        #expect(monitor.state == .stable)

        monitor.observe(radialScaleShifts(fraction: 0.03), timestamp: 0.1)
        monitor.observe(radialScaleShifts(fraction: 0.002), timestamp: 0.2)
        monitor.observe(radialScaleShifts(fraction: 0.03), timestamp: 0.3)

        #expect(monitor.state == .stable)
    }

    @Test func inconsistentPatchesDoNotCreateFalseMovement() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))

        monitor.observe(cornerShifts(
            topLeft: (20, 0),
            topRight: (-20, 0),
            bottomLeft: (0, 20),
            bottomRight: (0, -20)
        ), timestamp: 0.1)

        #expect(monitor.state == .calibrating)
        #expect(monitor.latestConsensusPatches < StaticSceneTranslationStability.minimumConsensusPatches)
    }

    @Test func staleTimestampCannotAdvanceMovementDwell() {
        var monitor = StaticSceneTranslationStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(cornerShifts(all: (1, 0)), timestamp: 10.05)
        #expect(monitor.state == .stable)

        let shifts = cornerShifts(all: (12, 0))
        monitor.observe(shifts, timestamp: 10.1)
        monitor.observe(shifts, timestamp: 9)
        monitor.observe(shifts, timestamp: 10.2)
        #expect(monitor.state == .stable)
    }

    private func cornerShifts(
        all shift: (Double, Double)
    ) -> [StaticSceneTranslationStability.PatchShift] {
        cornerShifts(
            topLeft: shift,
            topRight: shift,
            bottomLeft: shift,
            bottomRight: shift
        )
    }

    private func cornerShifts(
        topLeft: (Double, Double),
        topRight: (Double, Double),
        bottomLeft: (Double, Double),
        bottomRight: (Double, Double)
    ) -> [StaticSceneTranslationStability.PatchShift] {
        [
            .init(dxPixels: topLeft.0, dyPixels: topLeft.1, centerXFraction: 0.125, centerYFraction: 0.125),
            .init(dxPixels: topRight.0, dyPixels: topRight.1, centerXFraction: 0.875, centerYFraction: 0.125),
            .init(dxPixels: bottomLeft.0, dyPixels: bottomLeft.1, centerXFraction: 0.125, centerYFraction: 0.875),
            .init(dxPixels: bottomRight.0, dyPixels: bottomRight.1, centerXFraction: 0.875, centerYFraction: 0.875)
        ]
    }

    private func radialScaleShifts(
        fraction: Double
    ) -> [StaticSceneTranslationStability.PatchShift] {
        let centers = [
            (0.125, 0.125),
            (0.875, 0.125),
            (0.125, 0.875),
            (0.875, 0.875)
        ]
        return centers.map { x, y in
            let rx = (x - 0.5) * 1000
            let ry = (y - 0.5) * 1000
            return .init(
                dxPixels: fraction * rx,
                dyPixels: fraction * ry,
                centerXFraction: x,
                centerYFraction: y
            )
        }
    }
}
