import Foundation
import Testing
@testable import HangInThere

struct PullUpProgressTests {
    private func pose(_ time: Double, left: Double?, right: Double?, offset: Double = 0) -> PoseResult {
        let base = ExerciseCounterTests.pose(time)
        var landmarks: [Landmark] = []
        if let left {
            landmarks += ExerciseCounterTests.pose(time, degrees: left, wristX: 300,
                                                    verticalOffset: offset).people[0].landmarks
        }
        if let right {
            landmarks += ExerciseCounterTests.pose(time, degrees: right, side: .right,
                                                    wristX: 700, verticalOffset: offset).people[0].landmarks
        }
        return PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
                          people: [.init(landmarks: landmarks)], backend: base.backend,
                          requestRevision: base.requestRevision)
    }
    private func feed(_ counter: inout ExerciseCounter, _ time: Double,
                      _ left: Double?, _ right: Double?, offset: Double = 0) {
        counter.consume(pose(time, left: left, right: right, offset: offset),
                        referenceEdge: ExerciseCounterTests.referenceEdge)
    }
    private func armed() -> ExerciseCounter {
        var counter = ExerciseCounter()
        feed(&counter, 0, 170, 170)
        feed(&counter, 0.15, 170, 170)
        return counter
    }

    @Test func hangAcquisitionDoesNotLookLikeACountedRep() throws {
        var c = ExerciseCounter()
        #expect(c.pullUpProgress?.stage == .startingHang)
        feed(&c, 0, 170, 170)
        feed(&c, 0.06, 170, 170)
        let holding = try #require(c.pullUpProgress)
        #expect(holding.fraction == 0)
        #expect(holding.resetFraction > 0.9)
        feed(&c, 0.15, 170, 170)
        #expect(c.pullUpProgress?.stage == .ready)
        #expect(c.pullUpProgress?.fraction == 0)
        #expect(c.pullUpProgress?.repNumber == 1)
        #expect(c.observedMovements == 0)
    }

    @Test func ascentIncreasesButCannotReachFullBeforeEndpointDwell() throws {
        var c = armed()
        feed(&c, 0.3, 130, 130)
        let earlier = try #require(c.pullUpProgress)
        feed(&c, 0.45, 110, 110)
        let later = try #require(c.pullUpProgress)
        #expect(later.fraction > earlier.fraction)
        feed(&c, 0.55, 80, 80)
        let top = try #require(c.pullUpProgress)
        #expect(top.stage == .holdingTop)
        #expect(top.cue == .hold)
        #expect(top.fraction == 0.9)
        feed(&c, 0.61, 80, 80)
        #expect((c.pullUpProgress?.fraction ?? 0) > top.fraction)
        #expect((c.pullUpProgress?.fraction ?? 1) < 1)
        #expect(c.observedMovements == 0)
        feed(&c, 0.70, 80, 80)
        #expect(c.observedMovements == 1)
        #expect(c.pullUpProgress?.stage == .counted)
        #expect(c.pullUpProgress?.fraction == 1)
        #expect(c.pullUpProgress?.repNumber == 1)
    }

    @Test func bendingWithoutShoulderTravelDoesNotFillMeter() {
        var c = armed()
        feed(&c, 0.3, 80, 80, offset: 100)
        feed(&c, 0.45, 80, 80, offset: 100)
        #expect(c.pullUpProgress?.fraction == 0)
        #expect(c.pullUpProgress?.cue == .pullHigher)
        #expect(c.observedMovements == 0)
    }

    @Test func shoulderTravelWithoutEnoughBendStillNeedsMorePull() throws {
        var c = armed()
        feed(&c, 0.3, 120, 120, offset: -70)
        let progress = try #require(c.pullUpProgress)
        #expect(progress.fraction > 0)
        #expect(progress.fraction < 0.9)
        #expect(progress.cue == .finishPull)
        #expect(c.observedMovements == 0)
    }

    @Test func laggingArmConstrainsProgressRatherThanAveraging() throws {
        var c = armed()
        feed(&c, 0.3, 80, 130)
        let progress = try #require(c.pullUpProgress)
        #expect(progress.fraction < 0.5)
        #expect(progress.stage == .pulling)
        #expect(progress.trackedArmCount == 2)
        feed(&c, 0.45, 80, 170)
        #expect(c.pullUpProgress?.fraction == 0)
        #expect(c.pullUpProgress?.stage == .pulling)
        #expect(c.observedMovements == 0)
    }

    @Test func obscuredArmFallsBackAndCompletePoseLossClearsGreen() {
        var c = armed()
        feed(&c, 0.3, 80, nil)
        feed(&c, 0.45, 80, nil)
        #expect(c.pullUpProgress?.stage == .counted)
        #expect(c.pullUpProgress?.trackedArmCount == 1)
        c.consume(ExerciseCounterTests.noPersonPose(0.6), referenceEdge: ExerciseCounterTests.referenceEdge)
        #expect(c.pullUpProgress?.stage == .paused)
        #expect(c.pullUpProgress?.fraction == 0)
        #expect(c.pullUpProgress?.trackedArmCount == 0)
        #expect(c.observedMovements == 1)
    }

    @Test func alternatingArmsResetHoldProgress() {
        var c = armed()
        feed(&c, 0.3, 80, nil)
        feed(&c, 0.36, nil, 80)
        #expect(c.pullUpProgress?.fraction == 0.9)
        feed(&c, 0.45, nil, 80)
        #expect((c.pullUpProgress?.fraction ?? 1) < 1)
        #expect(c.observedMovements == 0)
        feed(&c, 0.5, nil, 80)
        #expect(c.pullUpProgress?.stage == .counted)
    }

    @Test func returnFillsResetMeterThenStartsTheNextRepAtZero() throws {
        var c = armed()
        feed(&c, 0.3, 80, 80)
        feed(&c, 0.45, 80, 80)
        let top = try #require(c.pullUpProgress)
        feed(&c, 0.6, 130, 130)
        let lower = try #require(c.pullUpProgress)
        #expect(lower.fraction == 1)
        #expect(lower.cue == .lower)
        #expect(lower.resetFraction > top.resetFraction)
        feed(&c, 0.75, 170, 170)
        #expect(c.pullUpProgress?.repNumber == 1)
        #expect((c.pullUpProgress?.resetFraction ?? 1) < 1)
        feed(&c, 0.9, 170, 170)
        #expect(c.pullUpProgress?.stage == .ready)
        #expect(c.pullUpProgress?.repNumber == 2)
        #expect(c.pullUpProgress?.fraction == 0)
        #expect(c.observedMovements == 1)
    }

    @Test func partialAttemptResetsWithoutAdvancingRepNumber() {
        var c = armed()
        feed(&c, 0.3, 130, 130)
        feed(&c, 0.45, 170, 170)
        #expect(c.pullUpProgress?.stage == .startingHang)
        feed(&c, 0.6, 170, 170)
        #expect(c.partialAttempts == 1)
        #expect(c.pullUpProgress?.stage == .ready)
        #expect(c.pullUpProgress?.repNumber == 1)
        #expect(c.pullUpProgress?.fraction == 0)
    }

    @Test func missingBarAndSourceGapCannotKeepAStalePercentage() {
        var c = armed()
        feed(&c, 0.3, 130, 130)
        c.consume(pose(0.45, left: 80, right: 80), referenceEdge: nil)
        #expect(c.pullUpProgress?.cue == .setBar)
        #expect(c.pullUpProgress?.fraction == 0)
        c = armed()
        feed(&c, 0.3, 130, 130)
        feed(&c, 1, 80, 80)
        #expect(c.pullUpProgress?.stage == .startingHang)
        #expect(c.pullUpProgress?.fraction == 0)
        #expect(c.observedMovements == 0)
    }

    @Test func finishedAndDipStatesCannotDisplayPullUpCompletion() {
        var c = armed()
        c.finish()
        #expect(c.pullUpProgress?.stage == .finished)
        #expect(c.pullUpProgress?.fraction == 0)
        #expect(ExerciseCounter(exercise: .dip).pullUpProgress == nil)
    }
}
