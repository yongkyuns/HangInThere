import Foundation
import Testing
@testable import HangInThere

struct StaticSceneStabilityTests {
    @Test func stationaryPairHypothesesNeedEveryContributingCornerBelowTheExistingLimit() {
        let cases: [[(Double,Double)]] = [[(-1,1),(0,2),(-24,-5),(0,-5)],[(0,1),(1,1),(0,-4)]]
        for points in cases {
            var scene = StaticSceneStability()
            _ = scene.calibrate(imageShortSide:720)
            let patches = points.map { StaticSceneStability.PatchTranslation(dxPixels:$0.0,dyPixels:$0.1) }
            scene.observe(translations:patches,globalScaleFraction:nil,timestamp:1)
            #expect(!scene.isReady(at:1))
            scene.observe(translations:patches,globalScaleFraction:0,timestamp:1.1)
            #expect(scene.isReady(at:1.1))
            #expect(scene.latestConsensusPatches == 2)
        }
        let limit = StaticSceneStability.movementThresholdFraction * 720
        for shift in [limit,limit + 0.001] {
            var scene = StaticSceneStability()
            _ = scene.calibrate(imageShortSide:720)
            scene.observe(translations:[.init(dxPixels:-shift,dyPixels:0),.init(dxPixels:0,dyPixels:0),.init(dxPixels:1,dyPixels:0)],globalScaleFraction:0,timestamp:1)
            #expect(!scene.isReady(at:1))
            #expect(scene.latestShiftFraction == nil)
        }
    }

    @Test func sixRegionConsensusUsesAdditionalIndependentBackgroundWithoutRelaxingMotion() {
        let scenePoints:[(Double,Double)] = [(0,-5),(-1,-6),(-6,-5),(0,-6),(0,-5),(0,-5)]
        var scene = StaticSceneStability()
        _ = scene.calibrate(imageShortSide:720)
        scene.observe(translations:scenePoints.map { .init(dxPixels:$0.0,dyPixels:$0.1) },globalScaleFraction:0,timestamp:1)
        #expect(scene.isReady(at:1))
        #expect(scene.latestConsensusPatches == 5)
        #expect(abs((scene.latestShiftFraction ?? 1)-5.0/720) < 1e-12)
        let moving = Array(repeating:StaticSceneStability.PatchTranslation(dxPixels:12,dyPixels:0),count:6)
        scene.observe(translations:moving,globalScaleFraction:0,timestamp:1.1)
        scene.observe(translations:moving,globalScaleFraction:0,timestamp:1.35)
        #expect(scene.state == .moved)
        var contested = StaticSceneStability()
        _ = contested.calibrate(imageShortSide:720)
        contested.observe(translations:[0.0,0,0,20,20,20].map { .init(dxPixels:$0,dyPixels:0) },globalScaleFraction:0,timestamp:1)
        #expect(!contested.isReady(at:1))
    }

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
        #expect(monitor.state == .calibrating)
        #expect(!monitor.isReady(at: 10.2))
    }

    @Test func lostCurrentEvidenceCannotRetainReadinessOrLatestValues() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0, timestamp: 1)
        #expect(monitor.state == .stable)
        monitor.observe(translations: stableTranslations(), globalScaleFraction: nil, timestamp: 1.1)
        #expect(monitor.state == .calibrating)
        #expect(monitor.latestScaleFraction == nil)
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0, timestamp: 1.2)
        #expect(monitor.state == .stable)
        monitor.observe(translations: [], globalScaleFraction: 0, timestamp: 1.3)
        #expect(monitor.state == .calibrating)
        #expect(monitor.latestShiftFraction == nil)
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0, timestamp: 1.4)
        #expect(monitor.state == .stable)
    }

    @Test func sourceGapsCannotConfirmCameraMovement() {
        for scale in [false, true] {
            var monitor = StaticSceneStability()
            _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
            monitor.observe(translations: stableTranslations(), globalScaleFraction: 0, timestamp: 1)
            let patches = scale ? stableTranslations() : Array(repeating:
                StaticSceneStability.PatchTranslation(dxPixels: 12, dyPixels: 0), count: 3)
            let fraction = scale ? 0.03 : 0.0
            monitor.observe(translations: patches, globalScaleFraction: fraction, timestamp: 1.1)
            monitor.observe(translations: patches, globalScaleFraction: fraction, timestamp: 2)
            #expect(monitor.state != .moved)
            monitor.observe(translations: patches, globalScaleFraction: fraction, timestamp: 2.2)
            #expect(monitor.state != .moved)
            monitor.observe(translations: patches, globalScaleFraction: fraction, timestamp: 2.3)
            #expect(monitor.state == .moved)
        }
    }

    @Test func duplicateRewindAndInvalidClockInvalidateEvidenceAndDwell() {
        for badTime in [1.2, 0.9, Double.nan, Double.infinity] {
            var monitor = StaticSceneStability()
            _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
            let patches = Array(repeating: StaticSceneStability.PatchTranslation(
                dxPixels: 12, dyPixels: 0), count: 3)
            monitor.observe(translations: stableTranslations(), globalScaleFraction: 0, timestamp: 1)
            monitor.observe(translations: patches, globalScaleFraction: 0, timestamp: 1.1)
            monitor.observe(translations: patches, globalScaleFraction: 0, timestamp: 1.2)
            monitor.observe(translations: patches, globalScaleFraction: 0, timestamp: badTime)
            #expect(monitor.state == .calibrating)
            #expect(monitor.latestShiftFraction == nil)
            #expect(monitor.latestScaleFraction == nil)
            monitor.observe(translations: patches, globalScaleFraction: 0, timestamp: 1.4)
            #expect(monitor.state != .moved)
            monitor.observe(translations: patches, globalScaleFraction: 0, timestamp: 1.6)
            #expect(monitor.state != .moved)
            monitor.observe(translations: patches, globalScaleFraction: 0, timestamp: 1.7)
            #expect(monitor.state == .moved)
        }
    }

    @Test func missingSignalsBreakTheirOwnDwellAndMovementStaysLatched() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0, timestamp: 1)
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0.03, timestamp: 1.1)
        monitor.observe(translations: stableTranslations(), globalScaleFraction: .nan, timestamp: 1.2)
        #expect(monitor.state == .calibrating)
        #expect(monitor.latestScaleFraction == nil)
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0.03, timestamp: 1.4)
        #expect(monitor.state != .moved)
        monitor.observe(translations: [], globalScaleFraction: 0.03, timestamp: 1.7)
        #expect(monitor.state == .moved)
        #expect(monitor.movementKind == .scale)
        monitor.observe(translations: [], globalScaleFraction: nil, timestamp: .nan)
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0, timestamp: 2)
        #expect(monitor.state == .moved)
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0, timestamp: 2.1)
        #expect(monitor.state == .stable)
        #expect(monitor.movementKind == nil)
    }

    @Test func readinessAndReferenceExpireAtTheExistingSceneAge() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0, timestamp: 1)
        #expect(monitor.isReady(at: 1.8))
        #expect(!monitor.isReady(at: 1.800001))
        #expect(!monitor.isReady(at: nil))
        #expect(!monitor.isReady(at: .nan))
        #expect(!monitor.isReady(at: 0.94))
        #expect(monitor.isReady(at: 0.96))
        #expect(monitor.qualifiedReference(ExerciseCounterTests.referenceEdge, at: 1.1) != nil)
        #expect(monitor.qualifiedReference(ExerciseCounterTests.referenceEdge, at: 2) == nil)
        monitor.observe(translations: [], globalScaleFraction: nil, timestamp: 1.2)
        #expect(monitor.qualifiedReference(ExerciseCounterTests.referenceEdge, at: 1.2) == nil)
    }

    @Test func unqualifiedSceneFrameInterruptsTheCycleWithoutErasingTheBar() {
        var monitor = StaticSceneStability()
        _ = monitor.calibrate(imageSize: .init(width: 1000, height: 1000))
        var session = LiveSetSession()
        session.start(exercise: .pullUp, side: .left)
        for (time, angle) in [(1.0, 170.0), (1.15, 170.0), (1.3, 120.0), (1.45, 80.0)] {
            monitor.observe(translations: stableTranslations(),
                globalScaleFraction: time == 1.45 ? nil : 0, timestamp: time)
            session.consume(ExerciseCounterTests.pose(time, degrees: angle), referenceEdge:
                monitor.qualifiedReference(ExerciseCounterTests.referenceEdge, at: time))
        }
        #expect(session.phase == .running)
        #expect(session.observedMovements == 0)
        #expect(session.interruptedAttempts == 1)
        #expect(session.usableFrames == 3)
        #expect(session.trackingIssue == "barReferenceUnavailable")
        monitor.observe(translations: stableTranslations(), globalScaleFraction: 0, timestamp: 1.6)
        #expect(monitor.qualifiedReference(ExerciseCounterTests.referenceEdge, at: 1.6) != nil)
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
