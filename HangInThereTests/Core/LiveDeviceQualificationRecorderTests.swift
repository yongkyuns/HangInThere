import Foundation
import Testing
@testable import HangInThere

struct LiveDeviceQualificationRecorderTests {
    @Test func recorderThrottlesToOneSnapshotPerSecond() {
        var recorder = LiveDeviceQualificationRecorder()
        recorder.reset(startUptimeSeconds: 10)

        record(&recorder, uptime: 10, vision: 20, scene: 10, analyzed: 1)
        record(&recorder, uptime: 10.5, vision: 30, scene: 15, analyzed: 2)
        record(&recorder, uptime: 11, vision: 40, scene: 20, analyzed: 3)

        #expect(recorder.samples.count == 2)
        #expect(recorder.samples.map(\.elapsedSeconds) == [0, 1])
    }

    @Test func reportSummarizesRuntimeLatencyAndStability() {
        var recorder = LiveDeviceQualificationRecorder()
        recorder.reset(startUptimeSeconds: 100)

        let latencies = [10.0, 20.0, 30.0, 40.0, 100.0]
        for (index, latency) in latencies.enumerated() {
            recorder.record(
                uptimeSeconds: 100 + Double(index),
                visionProcessingMilliseconds: latency,
                sceneRegistrationMilliseconds: latency / 2,
                analyzedFrames: (index + 1) * 10,
                droppedFrames: index,
                analysisFailures: index == 4 ? 1 : 0,
                sceneRegistrationFailures: index == 3 ? 1 : 0,
                orientationDeltaDegrees: Double(index) * 0.25,
                sceneShiftFraction: Double(index) * 0.001,
                sceneScaleFraction: Double(index) * 0.002,
                sceneTranslationConsensusPatches: 4 - min(index, 1),
                sceneScaleMeasurementAvailable: index != 1,
                thermalLevel: index < 3 ? .nominal : .serious,
                setPhase: index < 2 ? "idle" : "running"
            )
        }

        let report = recorder.makeReport(
            uptimeSeconds: 105,
            analyzedFrames: 50,
            droppedFrames: 4,
            analysisFailures: 1,
            sceneRegistrationFailures: 1,
            observedMovements: 7,
            trackingCoverage: 0.92,
            setPhase: "finished",
            setEndReason: "manual"
        )

        #expect(report.runtime.durationSeconds == 5)
        #expect(report.runtime.effectiveAnalyzedFPS == 10)
        #expect(report.runtime.sceneRegistrationFailures == 1)
        #expect(abs((report.runtime.dropFraction ?? 0) - (4.0 / 54.0)) < 1e-12)
        #expect(report.visionLatency.sampleCount == 5)
        #expect(report.visionLatency.medianMilliseconds == 30)
        #expect(report.visionLatency.p95Milliseconds == 100)
        #expect(report.sceneRegistrationLatency.sampleCount == 5)
        #expect(report.sceneRegistrationLatency.medianMilliseconds == 15)
        #expect(report.sceneRegistrationLatency.p95Milliseconds == 50)
        #expect(report.stability.maximumOrientationDeltaDegrees == 1)
        #expect(report.stability.maximumSceneShiftFraction == 0.004)
        #expect(report.stability.maximumSceneScaleFraction == 0.008)
        #expect(report.stability.minimumTranslationConsensusPatches == 3)
        #expect(report.stability.sceneScaleMeasurementSamples == 4)
        #expect(report.stability.maximumThermalLevel == .serious)
        #expect(report.observedMovements == 7)
    }

    @Test func reportContainsCurrentThresholds() {
        var recorder = LiveDeviceQualificationRecorder()
        recorder.reset(startUptimeSeconds: 0)
        let report = recorder.makeReport(
            uptimeSeconds: 1,
            analyzedFrames: 0,
            droppedFrames: 0,
            analysisFailures: 0,
            sceneRegistrationFailures: 0,
            observedMovements: 0,
            trackingCoverage: nil,
            setPhase: "idle",
            setEndReason: nil
        )

        #expect(report.thresholds.orientationDegrees == PhoneOrientationStability.movementThresholdDegrees)
        #expect(report.thresholds.sceneTranslationFraction == StaticSceneStability.movementThresholdFraction)
        #expect(report.thresholds.sceneScaleFraction == StaticSceneStability.scaleThresholdFraction)
    }

    @Test func invalidValuesAreNotExportedAsMetrics() {
        var recorder = LiveDeviceQualificationRecorder()
        recorder.record(
            uptimeSeconds: 1,
            visionProcessingMilliseconds: .nan,
            sceneRegistrationMilliseconds: .nan,
            analyzedFrames: 1,
            droppedFrames: 0,
            analysisFailures: 0,
            sceneRegistrationFailures: 0,
            orientationDeltaDegrees: -.infinity,
            sceneShiftFraction: .infinity,
            sceneScaleFraction: -.infinity,
            sceneTranslationConsensusPatches: -2,
            sceneScaleMeasurementAvailable: false,
            thermalLevel: .unknown,
            setPhase: "idle"
        )

        let sample = recorder.samples[0]
        #expect(sample.visionProcessingMilliseconds == nil)
        #expect(sample.sceneRegistrationMilliseconds == nil)
        #expect(sample.orientationDeltaDegrees == nil)
        #expect(sample.sceneShiftFraction == nil)
        #expect(sample.sceneScaleFraction == nil)
        #expect(sample.sceneTranslationConsensusPatches == 0)
    }

    private func record(
        _ recorder: inout LiveDeviceQualificationRecorder,
        uptime: Double,
        vision: Double,
        scene: Double,
        analyzed: Int
    ) {
        recorder.record(
            uptimeSeconds: uptime,
            visionProcessingMilliseconds: vision,
            sceneRegistrationMilliseconds: scene,
            analyzedFrames: analyzed,
            droppedFrames: 0,
            analysisFailures: 0,
            sceneRegistrationFailures: 0,
            orientationDeltaDegrees: 0.2,
            sceneShiftFraction: 0.001,
            sceneScaleFraction: 0.002,
            sceneTranslationConsensusPatches: 4,
            sceneScaleMeasurementAvailable: true,
            thermalLevel: .nominal,
            setPhase: "idle"
        )
    }
}
