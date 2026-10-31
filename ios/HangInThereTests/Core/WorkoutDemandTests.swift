import XCTest
@testable import HangInThere

final class WorkoutDemandTests: XCTestCase {
    private func span(_ phase: String, _ start: Double, _ end: Double, observed: Double? = nil) -> WorkoutPhaseSpan {
        WorkoutPhaseSpan(id: "\(phase)-\(start)", phase: phase, startedAt: start, endedAt: end,
                         observedSeconds: observed ?? end - start)
    }
    private func activity(_ phases: [WorkoutPhaseSpan], truncated: Bool = false) -> WorkoutActivity {
        WorkoutActivity(phases: phases, totals: [], longestHangSeconds: 0, longestSupportSeconds: 0,
                        observedSeconds: 0, elapsedSeconds: 0, timelineTruncated: truncated, metricVersion: 1)
    }

    func testPullAndDipUseTheSamePhaseWeights() {
        let pull = WorkoutDemand(activity: activity([span("hang", 0, 4), span("pulling", 4, 6), span("lowering", 6, 8)]))
        let dip = WorkoutDemand(activity: activity([span("support", 0, 4), span("lowering", 4, 6), span("rising", 6, 8)]))
        XCTAssertEqual(pull.units, 4)
        XCTAssertEqual(pull.units, dip.units)
        XCTAssertFalse(pull.hasGaps)
        XCTAssertEqual(WorkoutDemand.weight(for: "effortHold"), 1)
    }

    func testSlowLoweringAddsMoreDemandWithoutClaimingMoreInstantaneousLoad() {
        let quick = WorkoutDemand(activity: activity([span("lowering", 0, 1)]))
        let slow = WorkoutDemand(activity: activity([span("lowering", 0, 4)]))
        XCTAssertEqual(quick.units, 0.5)
        XCTAssertEqual(slow.units, 2)
        XCTAssertEqual(slow.recovery.first?.units, slow.units)
        XCTAssertEqual(slow.recovery.last!.units, 2 * exp(-0.5), accuracy: 0.000001)
        XCTAssertEqual(WorkoutDemand.recovered(10, after: 60), 10 / exp(1), accuracy: 0.000001)
    }

    func testMissingTrackingAndReleaseDoNotInventActivityOrRest() {
        let demand = WorkoutDemand(activity: activity([
            span("pulling", 0, 2), span("unobserved", 2, 10, observed: 0),
            span("lowering", 10, 12), span("release", 12, 90, observed: 0)
        ]))
        XCTAssertEqual(demand.units, 3)
        XCTAssertTrue(demand.hasGaps)
        XCTAssertEqual(demand.points.map(\.seconds), [0, 2, 10, 12])
        XCTAssertNotEqual(demand.points[1].segment, demand.points[2].segment)
        XCTAssertEqual(demand.recovery.first?.seconds, 90)
        XCTAssertEqual(demand.recovery.first?.units, 3)
    }

    func testOnlyCreditedTimeCountsAndOverlapsAreNotDoubleCounted() {
        let demand = WorkoutDemand(activity: activity([
            span("pulling", 0, 5, observed: 2), span("pulling", 4, 6),
            span("lowering", 6, 8, observed: .nan), span("acquiring", 8, 10)
        ], truncated: true))
        XCTAssertEqual(demand.points.map(\.seconds), [3, 5, 5, 6])
        XCTAssertEqual(demand.units, 3)
        XCTAssertTrue(demand.isPartial)
        XCTAssertTrue(demand.hasGaps)
    }

    func testNonzeroCameraClockDoesNotChangeEstimate() {
        let zero = WorkoutDemand(activity: activity([span("hang", 0, 4), span("pulling", 4, 6)]))
        let camera = WorkoutDemand(activity: activity([span("hang", 9000, 9004), span("pulling", 9004, 9006)]))
        XCTAssertEqual(camera.units, zero.units)
        XCTAssertEqual(camera.points.map(\.seconds), zero.points.map(\.seconds))
    }

    func testOldOrInvalidHistoryHasNoEstimate() {
        XCTAssertTrue(WorkoutDemand(activity: nil).points.isEmpty)
        let invalid = WorkoutDemand(activity: activity([
            span("pulling", 0, .infinity), span("pulling", 5, 2), span("hang", 0, 1, observed: -1)
        ]))
        XCTAssertTrue(invalid.points.isEmpty)
        XCTAssertTrue(invalid.recovery.isEmpty)
        XCTAssertEqual(invalid.units, 0)
    }
}
