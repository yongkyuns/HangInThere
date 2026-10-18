import Testing
import Foundation
import CoreGraphics
@testable import HangInThere

struct PublicReplayMeasurementsTests {
    let source = [12.0, 12.15, 12.30, 12.45, 12.60, 12.75, 12.90]

    func measure(_ observed: [Double], finished: Bool = false, reference: Bool = true,
                 mediaVerified: Bool = true, displayedFrames: Int? = nil,
                 accepted: [Double] = [12.45], completed: [Double] = [12.75]) -> PublicReplayMeasurements {
        .init(expected: source, observed: observed, displayedFrames: displayedFrames ?? observed.count,
              finished: finished, hasReference: reference, mediaVerified: mediaVerified,
              acceptanceSeconds: accepted, completedSeconds: completed)
    }

    @Test func interruptionKeepsOnlyObservedMeasurementsAndNeverClaimsEOF() {
        let result = measure(Array(source.prefix(6)))
        #expect(result.status == "interrupted")
        #expect(result.frames == 6)
        #expect(result.sourcePrefixVerified)
        #expect(!result.sourceClockVerified)
        #expect(result.observedSourceEndSeconds == 12.75)
        #expect(result.acceptanceSeconds == [12.45])
        #expect(result.completedSeconds == [12.75])
    }

    @Test func acceptedButUnreturnedMovementStaysIncompleteAtInterruption() {
        let result = measure(Array(source.prefix(4)), completed: [])
        #expect(result.status == "interrupted")
        #expect(result.acceptanceSeconds == [12.45])
        #expect(result.completedSeconds.isEmpty)
    }

    @Test func completeSourceKeepsOriginalProcessedAndSetupOutcomes() {
        let complete = measure(source, finished: true)
        #expect(complete.status == "processed")
        #expect(complete.frames == source.count && complete.sourceClockVerified)
        #expect(complete.completedSeconds == [12.75])
        let unavailable = measure(source, finished: true, reference: false, accepted: [], completed: [])
        #expect(unavailable.status == "setupUnavailable")
        #expect(unavailable.sourceClockVerified && unavailable.frames == source.count)
        // A failure after the last measured frame still cannot claim EOF.
        #expect(measure(source).status == "interrupted")
        #expect(!measure(source).sourceClockVerified)
    }

    @Test func invalidOrMissingObservationClocksCannotPublishMeasurements() {
        for observed in [[], [12.0, 12.30], [12.0, 12.15, 12.15],
                         [12.15, 12.0], [12.0, .nan], source + [13.0]] {
            let result = measure(observed)
            #expect(result.status == "failed")
            #expect(result.frames == 0 && !result.sourcePrefixVerified)
            #expect(result.observedSourceEndSeconds == nil)
            #expect(result.acceptanceSeconds.isEmpty && result.completedSeconds.isEmpty)
        }
        #expect(measure(source, displayedFrames: 2).status == "failed")
        #expect(measure(Array(source.prefix(6)), finished: true).status == "failed")
        #expect(measure(source, mediaVerified: false).status == "failed")
        #expect(measure(source, reference: false).status == "failed")
    }

    @Test func eventsOutsideTheObservedPrefixCannotBeRetained() {
        for events in [[11.9], [12.9], [.nan], [12.6, 12.45]] {
            let result = measure(Array(source.prefix(6)), accepted: events, completed: [])
            #expect(result.status == "failed")
            #expect(result.acceptanceSeconds.isEmpty)
        }
        #expect(measure(Array(source.prefix(4)), completed: [12.75]).status == "failed")
    }
}

struct PublicReplayConfigurationTests {
    private func decode(_ fields: [String: Any] = [:]) throws -> PublicReplayManifest {
        var item: [String: Any] = ["id": "dip", "media": ["path": "videos/dip.mp4", "sha256": String(repeating: "a", count: 64)],
            "source_pts_seconds": [0.0, 0.1]]
        item.merge(fields) { _, value in value }
        let manifest: [String: Any] = ["schema_version": 1, "scope": "public_repetition_diagnostic",
            "held_out_qualification": false, "cases": [item]]
        let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(PublicReplayManifest.self, from: JSONSerialization.data(withJSONObject: manifest))
    }

    @Test func legacyPullupAndExplicitDipSelectTheirDeclaredExerciseAndSide() throws {
        let legacy = try decode().cases[0]
        #expect(legacy.selectedExercise == .pullUp && legacy.trackingSide == .left)
        let dip = try decode(["selected_exercise": "dip", "tracking_side": "right"]).cases[0]
        #expect(dip.selectedExercise == .dip && dip.trackingSide == .right)
    }

    @Test func invalidOrNullExplicitConfigurationCannotSilentlyBecomePullups() {
        for fields: [String: Any] in [["selected_exercise": "automatic"], ["selected_exercise": NSNull()],
                                     ["tracking_side": "nearest"], ["tracking_side": NSNull()]] {
            #expect(throws: DecodingError.self) { try decode(fields) }
        }
    }
}

struct PublicPoseQualityTests {
    private func support(_ ticks: Int64, missing: Bool = false, people: Int = 1) -> PoseResult {
        let original = AutomaticDipRailsTests().pose(Double(ticks) / 1e6)
        let person = PoseObservation(landmarks: original.people[0].landmarks.filter {
            !missing || ![PoseJoint.rightShoulder, .rightElbow, .rightWrist].contains($0.joint)
        })
        return PoseResult(timestamp: .init(value: ticks, timescale: 1_000_000), imageSize: original.imageSize,
            people: Array(repeating: person, count: people), backend: original.backend, requestRevision: original.requestRevision)
    }

    @Test func dipSearchAndStartingDiagnosticsUseDipGeometry() {
        let dip = PublicPoseQualityRecorder(exercise: .dip)
        let pullup = PublicPoseQualityRecorder(exercise: .pullUp)
        for ticks: Int64 in [0, 200_000, 400_000] { dip.observe(support(ticks)); pullup.observe(support(ticks)) }
        #expect(dip.summary["dip_search_frames"] as? Int == 3)
        #expect(dip.summary["starting_pose_candidate_frames"] as? Int == 3)
        #expect(dip.summary["longest_starting_pose_candidate_seconds"] as? Double == 0.4)
        #expect(dip.summary["pullup_search_frames"] == nil)
        #expect(pullup.summary["pullup_search_frames"] as? Int == 0)
        #expect(pullup.summary["starting_pose_candidate_frames"] as? Int == 0)
    }

    @Test func sourceGapBoundaryAndUnmeasurablePoseSplitSupportCandidates() {
        let boundary = PublicPoseQualityRecorder(exercise: .dip)
        for ticks: Int64 in [0, 350_000, 700_001] { boundary.observe(support(ticks)) }
        #expect(boundary.summary["longest_starting_pose_candidate_seconds"] as? Double == 0.35)
        let loss = PublicPoseQualityRecorder(exercise: .dip)
        for ticks: Int64 in [0, 200_000, 400_000, 600_000, 800_000] {
            loss.observe(support(ticks, people: ticks == 400_000 ? 0 : 1))
        }
        #expect(loss.summary["both_arm_frames"] as? Int == 4)
        #expect(loss.summary["starting_pose_candidate_frames"] as? Int == 4)
        #expect(loss.summary["longest_starting_pose_candidate_seconds"] as? Double == 0.2)
    }

    @Test func oneMeasuredDipContactRemainsARawStartingCandidate() {
        let quality = PublicPoseQualityRecorder(exercise: .dip)
        for ticks: Int64 in [0, 200_000, 400_000, 600_000, 800_000] {
            quality.observe(support(ticks, missing: ticks == 400_000))
        }
        #expect(quality.summary["single_person_frames"] as? Int == 5)
        #expect(quality.summary["both_arm_frames"] as? Int == 4)
        #expect(quality.summary["starting_pose_candidate_frames"] as? Int == 5)
        #expect(quality.summary["apparatus_search_frames"] as? Int == 5)
        #expect(quality.summary["right_arm_status_counts"] as? [String: Int] == ["available": 4, "missingJoint": 1])
        // This is raw current-frame geometry, not acquired equipment, retained
        // Auto identity, qualified readiness or credited workout duration.
        #expect(quality.summary["longest_starting_pose_candidate_seconds"] as? Double == 0.8)
    }

    @Test func rewindKeepsLatestRawObservationPerSourceTimeWithoutDoubleCredit() {
        let quality = PublicPoseQualityRecorder(exercise: .dip)
        for _ in 0..<2 { for ticks: Int64 in [0, 200_000, 400_000] { quality.observe(support(ticks)) } }
        quality.observe(support(200_000, missing: true))
        #expect(quality.summary["unique_source_frames"] as? Int == 3)
        #expect(quality.summary["both_arm_frames"] as? Int == 2)
        #expect(quality.summary["starting_pose_candidate_frames"] as? Int == 3)
        #expect(quality.summary["longest_starting_pose_candidate_seconds"] as? Double == 0.4)
        quality.observe(support(200_000, people: 0))
        #expect(quality.summary["unique_source_frames"] as? Int == 3)
        #expect(quality.summary["starting_pose_candidate_frames"] as? Int == 2)
        #expect(quality.summary["longest_starting_pose_candidate_seconds"] as? Double == 0)
        quality.observe(support(200_000))
        #expect(quality.summary["unique_source_frames"] as? Int == 3)
        #expect(quality.summary["both_arm_frames"] as? Int == 3)
        #expect(quality.summary["longest_starting_pose_candidate_seconds"] as? Double == 0.4)
    }

    @Test func instrumentationPreservesAllOriginalPeopleAndLandmarks() throws {
        struct Fixed: PoseEstimator {
            let pose: PoseResult
            func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult { pose }
        }
        let original = support(0, people: 2)
        let observed = PublicPoseQuality(exercise: .dip, estimator: Fixed(pose: original))
        let context = try #require(CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue))
        let image = try #require(context.makeImage())
        #expect(try observed.estimate(image: image, timestamp: original.timestamp) == original)
        #expect(observed.summary["people_count_histogram"] as? [String: Int] == ["2": 1])
        #expect(observed.summary["both_arm_frames"] as? Int == 0)
        #expect(observed.summary["left_arm_status_counts"] as? [String: Int] == ["multiplePeople": 1])
    }
}
