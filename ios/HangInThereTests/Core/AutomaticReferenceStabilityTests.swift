import Foundation
import Testing
@testable import HangInThere

@Suite struct AutomaticReferenceStabilityTests {
    let edge = BarSegment(a: .init(x: 200, y: 100), b: .init(x: 800, y: 100))
    let hands = [Point2D(x: 300, y: 120), Point2D(x: 700, y: 120)]
    func pose(_ ticks: Int64) -> PoseResult {
        .init(timestamp: .init(value: ticks, timescale: 1_000_000),
              imageSize: .init(width: 1000, height: 1000), people: [], backend: "authored-stability", requestRevision: 1)
    }
    // Evaluate the mutating policy before Swift Testing inspects the result.
    func expectObservation(_ policy: inout AutomaticReferenceStability, ticks: Int64,
                           expected: Bool = false, edges: [BarSegment]? = nil,
                           grips: [Point2D]? = nil, frame: PoseResult? = nil) {
        let qualified = policy.observe(edges: edges ?? [edge], grips: grips ?? hands, pose: frame ?? pose(ticks))
        #expect(qualified == expected)
    }
    @Test func sourceTickSubtractionPreservesElapsedBoundariesAndRejectsOverflow() {
        #expect(PresentationTime(value: 3900, timescale: 1000).elapsedSeconds(since: .init(value: 3000, timescale: 1000)) == 0.9)
        #expect(PresentationTime(value: 3000, timescale: 1000).elapsedSeconds(since: .init(value: 3900, timescale: 1000)) == -0.9)
        #expect(abs(PresentationTime(value: 3900, timescale: 1000).elapsedSeconds(since: .init(value: 3, timescale: 1)) - 0.9) < 1e-12)
        for (time, start) in [(PresentationTime(value: 1, timescale: 0), PresentationTime(value: 0, timescale: 1)),
                              (.init(value: 1, timescale: 1), .init(value: 0, timescale: -1)),
                              (.init(value: .max, timescale: 1), .init(value: .min, timescale: 1)),
                              (.init(value: .min, timescale: 1), .init(value: .max, timescale: 1))] {
            #expect(time.elapsedSeconds(since: start).isNaN)
        }
    }
    @Test func fullPointNineSecondHoldQualifiesButShortHoldAndThreeSamplesDoNot() {
        var policy = AutomaticReferenceStability()
        for t: Int64 in [3_000_000, 3_300_000, 3_600_000] {
            expectObservation(&policy, ticks: t)
        }
        expectObservation(&policy, ticks: 3_900_000, expected: true)
        #expect(policy.diagnostic == .init(status: .qualified, observations: 4, stableSeconds: 0.9, sourceSeconds: 3.9))
        var short = AutomaticReferenceStability(), sparse = AutomaticReferenceStability()
        for t: Int64 in [3_000_000, 3_300_000, 3_600_000, 3_899_999] {
            expectObservation(&short, ticks: t)
        }
        #expect(short.diagnostic.stableSeconds == 0.899999)
        for t: Int64 in [3_000_000, 3_450_000, 3_900_000] {
            expectObservation(&sparse, ticks: t)
        }
        #expect(sparse.diagnostic.observations == 3)
        policy.reset(); #expect(policy.diagnostic == .init())
    }
    @Test func exactMaximumGapContinuesButOneMicrosecondMoreRestarts() {
        var exact = AutomaticReferenceStability(), beyond = AutomaticReferenceStability()
        for t: Int64 in [3_000_000, 3_300_000, 4_100_000] {
            expectObservation(&exact, ticks: t)
        }
        expectObservation(&exact, ticks: 4_400_000, expected: true)
        for t: Int64 in [3_000_000, 3_300_000, 4_100_001] {
            expectObservation(&beyond, ticks: t)
        }
        #expect(beyond.diagnostic.status == .sourceGap && beyond.diagnostic.observations == 1)
        expectObservation(&beyond, ticks: 4_400_000)
    }
    @Test func diagnosticsDescribeResetsWithoutCarryingOldDwell() {
        for reason in ["edgeChanged", "gripMoved", "contextChanged", "sourceGap", "duplicate", "reverse", "invalid"] {
            var policy = AutomaticReferenceStability()
            expectObservation(&policy, ticks: 0)
            #expect(policy.diagnostic.status == .candidateStarted)
            expectObservation(&policy, ticks: 300_000)
            #expect(policy.diagnostic.status == .accumulating)
            let ticks: Int64 = reason == "sourceGap" ? 1_100_001 : reason == "duplicate" ? 300_000 : reason == "reverse" ? 200_000 : 600_000
            let edges = reason == "invalid" ? [] : reason == "edgeChanged"
                ? [BarSegment(a: .init(x: 200, y: 125), b: .init(x: 800, y: 125))] : [edge]
            let moved = reason == "gripMoved" ? hands.map { Point2D(x: $0.x + 25, y: $0.y) } : hands
            let original = pose(ticks)
            let frame = reason == "contextChanged" ? PoseResult(timestamp: original.timestamp,
                imageSize: .init(width: 1100, height: 1000), people: [], backend: original.backend, requestRevision: 1) : original
            expectObservation(&policy, ticks: ticks, edges: edges, grips: moved, frame: frame)
            let expected: AutomaticReferenceStability.Status = reason == "duplicate" || reason == "reverse" ? .sourceRegression
                : reason == "invalid" ? .invalidEvidence : .init(rawValue: reason)!
            #expect(policy.diagnostic.status == expected)
            #expect(policy.diagnostic.observations == (["duplicate", "reverse", "invalid"].contains(reason) ? 0 : 1))
            #expect(policy.diagnostic.stableSeconds == 0)
        }
    }
    @Test func callerMutationCannotRewriteTheWitnessedBaseline() {
        var policy = AutomaticReferenceStability(), edges = [edge], points = hands
        expectObservation(&policy, ticks: 0, edges: edges, grips: points)
        edges[0] = .init(a: .init(x: 200, y: 140), b: .init(x: 800, y: 140)); points[0] = .init(x: 350, y: 120)
        expectObservation(&policy, ticks: 300_000, edges: edges, grips: points)
        #expect(policy.diagnostic.status == .edgeChanged && policy.diagnostic.observations == 1)
        for t: Int64 in [600_000, 900_000] { expectObservation(&policy, ticks: t, edges: edges, grips: points) }
    }
}
