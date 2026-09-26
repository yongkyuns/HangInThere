import Testing
@testable import HangInThere

struct ReplayTimelineTests {
    @Test func variableRateKeepsSourceTimesInsteadOfAssumingThirtyFPS() {
        var timeline = ReplayTimeline()
        for tick: Int64 in [500, 510, 533, 591] {
            let accepted1 = timeline.accept(PresentationTime(value: tick, timescale: 100))
            #expect(accepted1)
        }
        #expect(timeline.acceptedFrames == 4)
        #expect(abs(timeline.elapsed - 0.91) < 1e-10)
    }

    @Test func duplicatesAndBackwardFramesAreRejectedWithoutMutatingState() {
        var timeline = ReplayTimeline()
        let accepted2 = timeline.accept(PresentationTime(value: 1, timescale: 10))
        #expect(accepted2)
        let accepted3 = timeline.accept(PresentationTime(value: 10, timescale: 100))
        #expect(!accepted3)
        let accepted4 = timeline.accept(PresentationTime(value: 0, timescale: 100))
        #expect(!accepted4)
        #expect(timeline.acceptedFrames == 1)
        #expect(timeline.last == 0.1)
    }

    @Test func invalidAndNegativeTimesAreRejected() {
        var timeline = ReplayTimeline()
        let accepted5 = timeline.accept(PresentationTime(value: 0, timescale: 0))
        #expect(!accepted5)
        let accepted6 = timeline.accept(PresentationTime(value: 2, timescale: -1))
        #expect(!accepted6)
        let accepted7 = timeline.accept(PresentationTime(value: -1, timescale: 10))
        #expect(!accepted7)
        #expect(timeline.first == nil)
        #expect(timeline.acceptedFrames == 0)
    }

    @Test func restartAllowsFirstTimestampAgain() {
        var timeline = ReplayTimeline()
        let accepted8 = timeline.accept(PresentationTime(value: 2, timescale: 1))
        #expect(accepted8)
        timeline = ReplayTimeline()
        let accepted9 = timeline.accept(PresentationTime(value: 0, timescale: 1))
        #expect(accepted9)
        #expect(timeline.elapsed == 0)
    }

    @Test func processingTimeIsSubtractedFromPacingDelay() {
        #expect(abs(ReplayPacing.delay(sourceDelta: 0.1, wallDelta: 0.04) - 0.06) < 1e-12)
        #expect(ReplayPacing.delay(sourceDelta: 0.1, wallDelta: 0.2) == 0)
        #expect(ReplayPacing.delay(sourceDelta: .nan, wallDelta: 0.1) == 0)
        #expect(ReplayPacing.delay(sourceDelta: -0.1, wallDelta: 0.1) == 0)
    }
}