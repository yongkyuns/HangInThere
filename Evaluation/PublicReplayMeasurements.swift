import Foundation
import os

/// Rewind begins a new pass. Acquisition frames cannot inflate the counting
/// prefix, and a canceled preparation cannot retain a prior pass's clock.
final class PublicReplayClock: Sendable {
    private let times = OSAllocatedUnfairLock(initialState: [Double]())
    func observe(_ trace: ReplayTrace) {
        times.withLock { values in
            if trace.stage == .preparation && trace.boundary == .began { values = [] }
            if trace.stage == .counting && trace.boundary == .ended, let time = trace.sourceSeconds {
                values.append(time)
            }
        }
    }
    var values: [Double] { times.withLock { $0 } }
}

/// Scalar evidence from one production replay pass. This validates observation
/// clocks only; completed cycles must come from the existing shared analytics.
struct PublicReplayMeasurements {
    let status: String
    let frames: Int
    let sourceClockVerified: Bool
    let sourcePrefixVerified: Bool
    let observedSourceEndSeconds: Double?
    let acceptanceSeconds: [Double]
    let completedSeconds: [Double]

    @MainActor
    init(model: ReplayController, expected: [Double], observed: [Double], finished: Bool, mediaVerified: Bool) {
        // An operational timeout can leave analysisRecord unset. Read only
        // already closed, shared-eligible returns from the current analysis.
        let completed = model.analysisRecord?.completedReps ?? model.repAnalysis.filter(\.isCompleteForAnalytics)
        self.init(expected: expected, observed: observed, displayedFrames: model.displayedFrames,
            finished: finished, hasReference: model.currentBar != nil, mediaVerified: mediaVerified,
            acceptanceSeconds: model.counter.events.filter { $0.outcome == .movement }.map(\.sourceSeconds),
            completedSeconds: completed.compactMap(\.returnedAt))
    }

    init(expected: [Double], observed: [Double], displayedFrames: Int,
         finished: Bool, hasReference: Bool, mediaVerified: Bool,
         acceptanceSeconds: [Double], completedSeconds: [Double]) {
        let orderedExpected = !expected.isEmpty && expected.allSatisfy(\.isFinite)
            && zip(expected, expected.dropFirst()).allSatisfy { $0.0 < $0.1 }
        let prefix = orderedExpected && !observed.isEmpty && observed.count <= expected.count
            && observed.count == displayedFrames && observed.allSatisfy(\.isFinite)
            && zip(observed, observed.dropFirst()).allSatisfy { $0.0 < $0.1 }
            && zip(observed, expected).allSatisfy { abs($0.0 - $0.1) < 0.000001 }
        func validEvents(_ events: [Double]) -> Bool {
            guard let first = observed.first, let last = observed.last else { return events.isEmpty }
            return events.allSatisfy { $0.isFinite && $0 >= first && $0 <= last }
                && zip(events, events.dropFirst()).allSatisfy { $0.0 <= $0.1 }
        }
        let valid = mediaVerified && prefix && (!finished || observed.count == expected.count)
            && validEvents(acceptanceSeconds) && validEvents(completedSeconds)
            && (hasReference || (acceptanceSeconds.isEmpty && completedSeconds.isEmpty))
        if valid {
            status = finished ? (hasReference ? "processed" : "setupUnavailable") : "interrupted"
            frames = observed.count
            sourceClockVerified = finished
            sourcePrefixVerified = true
            observedSourceEndSeconds = observed.last
            self.acceptanceSeconds = acceptanceSeconds
            self.completedSeconds = completedSeconds
        } else {
            status = "failed"
            frames = 0
            sourceClockVerified = false
            sourcePrefixVerified = false
            observedSourceEndSeconds = nil
            self.acceptanceSeconds = []
            self.completedSeconds = []
        }
    }

    var fields: [String: Any] {
        ["status": status, "frames": frames, "source_clock_verified": sourceClockVerified,
         "acceptance_seconds": acceptanceSeconds, "completed_seconds": completedSeconds,
         "progress_evidence": ["schema_version": 1, "source_prefix_verified": sourcePrefixVerified,
            "observed_source_end_seconds": observedSourceEndSeconds.map { $0 as Any } ?? NSNull()]]
    }
}
