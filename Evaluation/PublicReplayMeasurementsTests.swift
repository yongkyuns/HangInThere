import Testing
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
