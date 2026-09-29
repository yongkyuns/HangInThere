import Foundation

struct LiveDeviceQualificationRecorder: Sendable {
    enum ThermalLevel: String, Codable, CaseIterable, Sendable {
        case nominal, fair, serious, critical, unknown

        var severity: Int {
            switch self {
            case .nominal: 0
            case .fair: 1
            case .serious: 2
            case .critical: 3
            case .unknown: -1
            }
        }
    }

    struct Sample: Codable, Equatable, Sendable {
        let elapsedSeconds: Double
        let visionProcessingMilliseconds: Double?
        let sceneRegistrationMilliseconds: Double?
        let analyzedFrames: Int
        let droppedFrames: Int
        let analysisFailures: Int
        let sceneRegistrationFailures: Int
        let orientationDeltaDegrees: Double?
        let sceneShiftFraction: Double?
        let sceneScaleFraction: Double?
        let sceneTranslationConsensusPatches: Int
        let sceneScaleMeasurementAvailable: Bool
        let thermalLevel: ThermalLevel
        let setPhase: String
    }

    struct LatencySummary: Codable, Equatable, Sendable {
        let sampleCount: Int
        let medianMilliseconds: Double?
        let p95Milliseconds: Double?
        let maximumMilliseconds: Double?
    }

    struct RuntimeSummary: Codable, Equatable, Sendable {
        let durationSeconds: Double
        let analyzedFrames: Int
        let droppedFrames: Int
        let analysisFailures: Int
        let sceneRegistrationFailures: Int
        let effectiveAnalyzedFPS: Double
        let dropFraction: Double?
    }

    struct StabilitySummary: Codable, Equatable, Sendable {
        let maximumOrientationDeltaDegrees: Double?
        let maximumSceneShiftFraction: Double?
        let maximumSceneScaleFraction: Double?
        let minimumTranslationConsensusPatches: Int?
        let sceneScaleMeasurementSamples: Int
        let maximumThermalLevel: ThermalLevel
    }

    struct Thresholds: Codable, Equatable, Sendable {
        let orientationDegrees: Double
        let orientationDwellSeconds: Double
        let sceneTranslationFraction: Double
        let sceneScaleFraction: Double
        let sceneMovementDwellSeconds: Double
        let minimumTranslationConsensusPatches: Int
    }

    struct Report: Codable, Equatable, Sendable {
        let schemaVersion: Int
        let runtime: RuntimeSummary
        let visionLatency: LatencySummary
        let sceneRegistrationLatency: LatencySummary
        let stability: StabilitySummary
        let thresholds: Thresholds
        let observedMovements: Int
        let trackingCoverage: Double?
        let setPhase: String
        let setEndReason: String?
        let omittedSamples: Int
        let samples: [Sample]
    }

    static let minimumSampleIntervalSeconds = 1.0
    static let maximumSamples = 900

    private(set) var startedUptimeSeconds: Double?
    private(set) var samples: [Sample] = []
    private(set) var omittedSamples = 0
    private var lastSampleUptimeSeconds: Double?

    mutating func reset(startUptimeSeconds: Double) {
        self = Self()
        if startUptimeSeconds.isFinite {
            startedUptimeSeconds = startUptimeSeconds
        }
    }

    mutating func record(
        uptimeSeconds: Double,
        visionProcessingMilliseconds: Double?,
        sceneRegistrationMilliseconds: Double?,
        analyzedFrames: Int,
        droppedFrames: Int,
        analysisFailures: Int,
        sceneRegistrationFailures: Int,
        orientationDeltaDegrees: Double?,
        sceneShiftFraction: Double?,
        sceneScaleFraction: Double?,
        sceneTranslationConsensusPatches: Int,
        sceneScaleMeasurementAvailable: Bool,
        thermalLevel: ThermalLevel,
        setPhase: String
    ) {
        guard uptimeSeconds.isFinite,
              analyzedFrames >= 0,
              droppedFrames >= 0,
              analysisFailures >= 0,
              sceneRegistrationFailures >= 0
        else { return }

        if startedUptimeSeconds == nil {
            startedUptimeSeconds = uptimeSeconds
        }
        if let lastSampleUptimeSeconds,
           uptimeSeconds - lastSampleUptimeSeconds < Self.minimumSampleIntervalSeconds {
            return
        }

        self.lastSampleUptimeSeconds = uptimeSeconds
        guard samples.count < Self.maximumSamples else {
            omittedSamples += 1
            return
        }

        let elapsed = max(0, uptimeSeconds - (startedUptimeSeconds ?? uptimeSeconds))
        samples.append(Sample(
            elapsedSeconds: elapsed,
            visionProcessingMilliseconds: sanitizedNonnegative(visionProcessingMilliseconds),
            sceneRegistrationMilliseconds: sanitizedNonnegative(sceneRegistrationMilliseconds),
            analyzedFrames: analyzedFrames,
            droppedFrames: droppedFrames,
            analysisFailures: analysisFailures,
            sceneRegistrationFailures: sceneRegistrationFailures,
            orientationDeltaDegrees: sanitizedNonnegative(orientationDeltaDegrees),
            sceneShiftFraction: sanitizedNonnegative(sceneShiftFraction),
            sceneScaleFraction: sanitizedNonnegative(sceneScaleFraction),
            sceneTranslationConsensusPatches: max(0, sceneTranslationConsensusPatches),
            sceneScaleMeasurementAvailable: sceneScaleMeasurementAvailable,
            thermalLevel: thermalLevel,
            setPhase: setPhase
        ))
    }

    func makeReport(
        uptimeSeconds: Double,
        analyzedFrames: Int,
        droppedFrames: Int,
        analysisFailures: Int,
        sceneRegistrationFailures: Int,
        observedMovements: Int,
        trackingCoverage: Double?,
        setPhase: String,
        setEndReason: String?
    ) -> Report {
        let duration = max(
            0,
            uptimeSeconds.isFinite
                ? uptimeSeconds - (startedUptimeSeconds ?? uptimeSeconds)
                : 0
        )
        let totalCaptureFrames = analyzedFrames + droppedFrames
        let dropFraction = totalCaptureFrames > 0
            ? Double(droppedFrames) / Double(totalCaptureFrames)
            : nil

        let visionLatencies = samples.compactMap(\.visionProcessingMilliseconds).sorted()
        let sceneLatencies = samples.compactMap(\.sceneRegistrationMilliseconds).sorted()
        let orientationValues = samples.compactMap(\.orientationDeltaDegrees)
        let sceneShiftValues = samples.compactMap(\.sceneShiftFraction)
        let sceneScaleValues = samples.compactMap(\.sceneScaleFraction)
        let translationConsensus = samples
            .map(\.sceneTranslationConsensusPatches)
            .filter { $0 > 0 }

        let maximumThermal = samples
            .map(\.thermalLevel)
            .max { $0.severity < $1.severity } ?? .unknown

        return Report(
            schemaVersion: 1,
            runtime: RuntimeSummary(
                durationSeconds: duration,
                analyzedFrames: max(0, analyzedFrames),
                droppedFrames: max(0, droppedFrames),
                analysisFailures: max(0, analysisFailures),
                sceneRegistrationFailures: max(0, sceneRegistrationFailures),
                effectiveAnalyzedFPS: duration > 0
                    ? Double(max(0, analyzedFrames)) / duration
                    : 0,
                dropFraction: dropFraction
            ),
            visionLatency: latencySummary(visionLatencies),
            sceneRegistrationLatency: latencySummary(sceneLatencies),
            stability: StabilitySummary(
                maximumOrientationDeltaDegrees: orientationValues.max(),
                maximumSceneShiftFraction: sceneShiftValues.max(),
                maximumSceneScaleFraction: sceneScaleValues.max(),
                minimumTranslationConsensusPatches: translationConsensus.min(),
                sceneScaleMeasurementSamples: samples.filter(\.sceneScaleMeasurementAvailable).count,
                maximumThermalLevel: maximumThermal
            ),
            thresholds: Thresholds(
                orientationDegrees: PhoneOrientationStability.movementThresholdDegrees,
                orientationDwellSeconds: PhoneOrientationStability.movementDwellSeconds,
                sceneTranslationFraction: StaticSceneStability.movementThresholdFraction,
                sceneScaleFraction: StaticSceneStability.scaleThresholdFraction,
                sceneMovementDwellSeconds: StaticSceneStability.movementDwellSeconds,
                minimumTranslationConsensusPatches: StaticSceneStability.minimumConsensusPatches
            ),
            observedMovements: max(0, observedMovements),
            trackingCoverage: trackingCoverage.flatMap(sanitizedUnitInterval),
            setPhase: setPhase,
            setEndReason: setEndReason,
            omittedSamples: omittedSamples,
            samples: samples
        )
    }

    private func latencySummary(_ values: [Double]) -> LatencySummary {
        LatencySummary(
            sampleCount: values.count,
            medianMilliseconds: percentile(values, fraction: 0.50),
            p95Milliseconds: percentile(values, fraction: 0.95),
            maximumMilliseconds: values.last
        )
    }

    private func percentile(_ sorted: [Double], fraction: Double) -> Double? {
        guard !sorted.isEmpty else { return nil }
        let clamped = min(1, max(0, fraction))
        let index = Int((Double(sorted.count - 1) * clamped).rounded())
        return sorted[index]
    }

    private func sanitizedNonnegative(_ value: Double?) -> Double? {
        guard let value, value.isFinite, value >= 0 else { return nil }
        return value
    }

    private func sanitizedUnitInterval(_ value: Double) -> Double? {
        guard value.isFinite, (0...1).contains(value) else { return nil }
        return value
    }
}
