import Foundation
import Testing
@testable import HangInThere

struct StaticSceneStabilityTests {
    @Test func smallConsensusShiftAndScaleStayStable() {
        var monitor = StaticSceneStability()
        let calibrated = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        #expect(calibrated)
        #expect(monitor.state == .calibrating)

        monitor.observe(
            translations: [
                .init(dxPixels: 2, dyPixels: 1),
                .init(dxPixels: 3, dyPixels: 1),
                .init(dxPixels: 2, dyPixels: 2),
                .init(dxPixels: 80, dyPixels: -60)
            ],
            globalScaleFraction: 0.003,
            timestamp: 0.1
        )

        #expect(monitor.state == .stable)
        #expect(monitor.latestConsensusPatches == 3)
        #expect(monitor.latestScaleMeasurementAvailable)
        #expect((monitor.latestShiftFraction ?? 1) < StaticSceneStability.movementThresholdFraction)
        #expect((monitor.latestScaleFraction ?? 1) < StaticSceneStability.scaleThresholdFraction)
    }

    @Test func movingAthleteOutlierDoesNotLookLikeCameraTranslation() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))

        monitor.observe(
            translations: [
                .init(dxPixels: 1, dyPixels: 0),
                .init(dxPixels: 2, dyPixels: 1),
                .init(dxPixels: 1, dyPixels: -1),
                .init(dxPixels: 140, dyPixels: 90)
            ],
            globalScaleFraction: 0.002,
            timestamp: 0.1
        )

        #expect(monitor.state == .stable)
        #expect(monitor.latestConsensusPatches == 3)
    }

    @Test func sustainedConsensusTranslationInvalidatesCalibration() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(
            translations: stableTranslations(),
            globalScaleFraction: 0.002,
            timestamp: 0.05
        )
        #expect(monitor.state == .stable)

        let moved = Array(repeating: StaticSceneStability.PatchTranslation(
            dxPixels: 12,
            dyPixels: 2
        ), count: 4)

        monitor.observe(
            translations: moved,
            globalScaleFraction: 0.002,
            timestamp: 0.10
        )
        monitor.observe(
            translations: moved,
            globalScaleFraction: 0.002,
            timestamp: 0.25
        )
        #expect(monitor.state == .stable)

        monitor.observe(
            translations: moved,
            globalScaleFraction: 0.002,
            timestamp: 0.36
        )
        #expect(monitor.state == .moved)
        #expect(monitor.movementKind == .translation)
    }

    @Test func sustainedGlobalScaleInvalidatesCalibration() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(
            translations: stableTranslations(),
            globalScaleFraction: 0.002,
            timestamp: 0.05
        )
        #expect(monitor.state == .stable)

        monitor.observe(
            translations: stableTranslations(),
            globalScaleFraction: 0.03,
            timestamp: 0.10
        )
        monitor.observe(
            translations: stableTranslations(),
            globalScaleFraction: 0.03,
            timestamp: 0.25
        )
        #expect(monitor.state == .stable)

        monitor.observe(
            translations: stableTranslations(),
            globalScaleFraction: 0.03,
            timestamp: 0.36
        )
        #expect(monitor.state == .moved)
        #expect(monitor.movementKind == .scale)
    }

    @Test func startReadinessRequiresScaleMeasurement() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))

        monitor.observe(
            translations: stableTranslations(),
            globalScaleFraction: nil,
            timestamp: 0.1
        )
        #expect(monitor.state == .calibrating)
        #expect(!monitor.latestScaleMeasurementAvailable)

        monitor.observe(
            translations: stableTranslations(),
            globalScaleFraction: 0.002,
            timestamp: 0.2
        )
        #expect(monitor.state == .stable)
        #expect(monitor.latestScaleMeasurementAvailable)
    }

    @Test func commonTranslationDoesNotCreateScale() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))

        monitor.observe(
            translations: Array(repeating: .init(dxPixels: 12, dyPixels: -8), count: 4),
            globalScaleFraction: 0.002,
            timestamp: 0.1
        )

        #expect((monitor.latestScaleFraction ?? 1) < StaticSceneStability.scaleThresholdFraction)
    }

    @Test func briefTranslationRecoversBeforeDwell() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(
            translations: stableTranslations(),
            globalScaleFraction: 0.002,
            timestamp: 0.05
        )
        #expect(monitor.state == .stable)

        let moved = Array(repeating: StaticSceneStability.PatchTranslation(
            dxPixels: 12,
            dyPixels: 0
        ), count: 4)

        monitor.observe(translations: moved, globalScaleFraction: 0.002, timestamp: 0.1)
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0.002, timestamp: 0.2)
        monitor.observe(translations: moved, globalScaleFraction: 0.002, timestamp: 0.3)
        #expect(monitor.state == .stable)
    }

    @Test func briefScaleRecoversBeforeDwell() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(
            translations: stableTranslations(),
            globalScaleFraction: 0.002,
            timestamp: 0.05
        )
        #expect(monitor.state == .stable)

        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0.03, timestamp: 0.1)
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0.002, timestamp: 0.2)
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0.03, timestamp: 0.3)
        #expect(monitor.state == .stable)
    }

    @Test func inconsistentTranslationPatchesDoNotCreateFalseReadiness() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))

        monitor.observe(
            translations: [
                .init(dxPixels: 20, dyPixels: 0),
                .init(dxPixels: -20, dyPixels: 0),
                .init(dxPixels: 0, dyPixels: 20),
                .init(dxPixels: 0, dyPixels: -20)
            ],
            globalScaleFraction: 0.002,
            timestamp: 0.1
        )

        #expect(monitor.state == .calibrating)
        #expect(monitor.latestConsensusPatches < StaticSceneStability.minimumConsensusPatches)
    }

    @Test func staleTimestampCannotAdvanceMovementDwell() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(
            translations: stableTranslations(),
            globalScaleFraction: 0.002,
            timestamp: 10.05
        )
        #expect(monitor.state == .stable)

        let moved = Array(repeating: StaticSceneStability.PatchTranslation(
            dxPixels: 12,
            dyPixels: 0
        ), count: 4)

        monitor.observe(translations: moved, globalScaleFraction: 0.002, timestamp: 10.1)
        monitor.observe(translations: moved, globalScaleFraction: 0.002, timestamp: 9)
        monitor.observe(translations: moved, globalScaleFraction: 0.002, timestamp: 10.2)
        #expect(monitor.state == .stable)
    }

    private func stableTranslations() -> [StaticSceneStability.PatchTranslation] {
        [
            .init(dxPixels: 1, dyPixels: 0),
            .init(dxPixels: 2, dyPixels: 1),
            .init(dxPixels: 1, dyPixels: -1),
            .init(dxPixels: 2, dyPixels: 0)
        ]
    }
}
