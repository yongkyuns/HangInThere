import Foundation
import os
import CoreGraphics

/// Legacy repetition manifests select pull-ups. New diagnostic manifests can
/// explicitly select dips; this is replay configuration, never Auto identity.
struct PublicReplayManifest: Decodable {
    struct Case: Decodable {
        struct Media: Decodable { let path: String; let sha256: String }
        let id: String
        let media: Media
        let sourcePtsSeconds: [Double]
        let selectedExercise: ExerciseCounter.Exercise
        let trackingSide: ArmMeasurement.Side

        enum CodingKeys: String, CodingKey {
            case id, media, sourcePtsSeconds, selectedExercise, trackingSide
        }
        init(from decoder: Decoder) throws {
            let values = try decoder.container(keyedBy: CodingKeys.self)
            id = try values.decode(String.self, forKey: .id)
            media = try values.decode(Media.self, forKey: .media)
            sourcePtsSeconds = try values.decode([Double].self, forKey: .sourcePtsSeconds)
            selectedExercise = try values.contains(.selectedExercise)
                ? values.decode(ExerciseCounter.Exercise.self, forKey: .selectedExercise) : .pullUp
            trackingSide = try values.contains(.trackingSide)
                ? values.decode(ArmMeasurement.Side.self, forKey: .trackingSide) : .left
        }
    }
    let schemaVersion: Int
    let scope: String
    let heldOutQualification: Bool
    let cases: [Case]
}

/// Latest unchanged native observation for each source PTS, including acquisition
/// and rewind. These are input diagnostics, not athlete selection or rep credit.
final class PublicPoseQualityRecorder: Sendable {
    private struct Sample: Sendable {
        let timestamp: PresentationTime
        let people: Int
        let left: String
        let right: String
        let canSearch: Bool
        let startingPose: Bool
    }
    let exercise: ExerciseCounter.Exercise
    private let frames = OSAllocatedUnfairLock(initialState: [Double: Sample]())
    init(exercise: ExerciseCounter.Exercise) { self.exercise = exercise }

    func observe(_ pose: PoseResult) {
        guard pose.timestamp.seconds.isFinite else { return }
        let sample = Sample(timestamp: pose.timestamp, people: pose.people.count,
            left: ArmMeasurement(pose: pose, side: .left).unavailableReason?.rawValue ?? "available",
            right: ArmMeasurement(pose: pose, side: .right).unavailableReason?.rawValue ?? "available",
            canSearch: AutomaticApparatusReference.search(pose: pose, exercise: exercise) != nil,
            startingPose: AutomaticExerciseDetection.candidate(in: pose) == exercise)
        frames.withLock { $0[pose.timestamp.seconds] = sample }
    }

    var summary: [String: Any] {
        let samples = frames.withLock { Array($0.values) }.sorted { $0.timestamp.seconds < $1.timestamp.seconds }
        var first: PresentationTime?
        var last: PresentationTime?
        var longest = 0.0
        for sample in samples {
            let gap = last.map { sample.timestamp.elapsedSeconds(since: $0) }
            if !sample.startingPose { first = nil; last = nil; continue }
            if let gap, !gap.isFinite || gap <= 0 || gap > HandsFreeSetStart.maximumFrameGap { first = nil }
            if first == nil { first = sample.timestamp }
            longest = max(longest, sample.timestamp.elapsedSeconds(since: first!))
            last = sample.timestamp
        }
        var result: [String: Any] = ["schema_version": 1,
            "scope": "latest raw native observation per source PTS; not selected-athlete or labelled pose accuracy",
            "exercise": exercise.rawValue,
            "unique_source_frames": samples.count,
            "single_person_frames": samples.filter { $0.people == 1 }.count,
            "people_count_histogram": Dictionary(grouping: samples, by: { String($0.people) }).mapValues(\.count),
            "both_arm_frames": samples.filter { $0.left == "available" && $0.right == "available" }.count,
            "apparatus_search_frames": samples.filter(\.canSearch).count,
            "starting_pose_candidate_frames": samples.filter(\.startingPose).count,
            "longest_starting_pose_candidate_seconds": longest,
            "left_arm_status_counts": Dictionary(grouping: samples, by: \.left).mapValues(\.count),
            "right_arm_status_counts": Dictionary(grouping: samples, by: \.right).mapValues(\.count)]
        result[exercise == .pullUp ? "pullup_search_frames" : "dip_search_frames"] = samples.filter(\.canSearch).count
        return result
    }
}

/// Instrumentation returns the estimator's exact pose; it cannot remove people,
/// borrow joints, alter confidence, or select the arm that improves the count.
final class PublicPoseQuality: PoseEstimator {
    private let estimator: any PoseEstimator
    let recorder: PublicPoseQualityRecorder
    init(exercise: ExerciseCounter.Exercise, estimator: any PoseEstimator = VisionPoseEstimator()) {
        self.estimator = estimator
        recorder = PublicPoseQualityRecorder(exercise: exercise)
    }
    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        let pose = try estimator.estimate(image: image, timestamp: timestamp)
        recorder.observe(pose)
        return pose
    }
    var summary: [String: Any] { recorder.summary }
}

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
