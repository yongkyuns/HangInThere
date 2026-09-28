import Foundation
import Testing
@testable import HangInThere

struct ExerciseCounterTests {
    // Original analytic arm chains, not predictions or anatomical ground truth.
    // Wrist remains fixed, segments have equal length; shoulder moves with angle.
    static func pose(_ time: Double, degrees: Double = 170,
                     exercise: ExerciseCounter.Exercise = .pullUp,
                     side: ArmMeasurement.Side = .left,
                     confidence: Double = 1, wristX: Double = 500,
                     shoulderOverride: Double? = nil,
                     verticalOffset: Double = 0) -> PoseResult {
        let half = degrees * .pi / 360
        let direction = exercise == .pullUp ? 1.0 : -1.0
        let y = 120 * sin(half) * direction
        let points = [Point2D(x: wristX, y: (shoulderOverride ?? 500 + 2 * y) + verticalOffset),
                      Point2D(x: wristX + 120 * cos(half), y: 500 + y + verticalOffset),
                      Point2D(x: wristX, y: 500 + verticalOffset)]
        return PoseResult(timestamp: PresentationTime(value: Int64((time * 1000).rounded()), timescale: 1000),
                          imageSize: ImageSize(width: 1000, height: 1000),
                          people: [PoseObservation(landmarks: zip(side.joints, points).map {
                              Landmark(joint: $0.0, position: $0.1, confidence: confidence)
                          })], backend: "analytic test arm", requestRevision: 0)
    }
    static let referenceEdge = BarSegment(a: Point2D(x: 100, y: 500), b: Point2D(x: 900, y: 500))

    private func feed(_ counter: inout ExerciseCounter, _ samples: [(Double, Double)]) {
        for (time, angle) in samples {
            counter.consume(Self.pose(time, degrees: angle, exercise: counter.exercise, side: counter.side),
                            referenceEdge: Self.referenceEdge)
        }
    }
    private func arm(_ counter: inout ExerciseCounter) { feed(&counter, [(0,170),(0.15,170)]) }

    @Test func pullUpCountsAtTopAndRequiresReturnBeforeNext() {
        var c = ExerciseCounter()
        arm(&c)
        feed(&c, [(0.3,80),(0.45,80)])
        #expect(c.observedMovements == 1)
        #expect(c.lastEvent?.sourceSeconds == 0.45)
        #expect(c.lastEvent?.reason == "barReferencedTop;chinClearanceNotMeasured")
        feed(&c, [(0.6,80),(0.75,80),(0.9,170),(1.05,170),(1.2,80),(1.35,80)])
        #expect(c.observedMovements == 2)
        #expect(c.events.filter { $0.outcome == .movement }.map(\.sourceSeconds) == [0.45, 1.35])
        #expect(c.summary.formVerification == "unverified")
    }
    @Test func dipCountsOnlyAfterReturnToTop() {
        var c = ExerciseCounter(exercise: .dip, side: .right)
        arm(&c)
        feed(&c, [(0.3,80),(0.45,80)])
        #expect(c.observedMovements == 0)
        #expect(c.phase == .returning)
        feed(&c, [(0.6,170),(0.75,170)])
        #expect(c.observedMovements == 1)
        #expect(c.lastEvent?.reason == "barReferencedCycle;dipDepthAndFormNotQualified")
    }
    @Test func initialMidRepOrBentHoldCannotCount() {
        var c = ExerciseCounter()
        feed(&c, [(0,80),(0.15,80),(0.3,100),(0.45,80)])
        #expect(c.observedMovements == 0)
        #expect(c.phase == .seekingStart)
    }
    @Test func finalPullUpHoldDoesNotNeedDescentToKeepCount() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,80),(0.45,80)])
        c.finish(); c.finish()
        #expect(c.observedMovements == 1)
        #expect(c.interruptedAttempts == 0)
    }
    @Test func incompleteDipAtEOFIsNotARep() {
        var c = ExerciseCounter(exercise: .dip); arm(&c)
        feed(&c, [(0.3,80),(0.45,80)])
        c.finish(); c.finish()
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 1)
        #expect(c.lastEvent?.reason == "endOfInput")
    }
    @Test func shortAttemptReturningBeforeEndpointIsPartialOnly() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,130),(0.45,125),(0.6,170),(0.75,170)])
        #expect(c.partialAttempts == 1)
        #expect(c.observedMovements == 0)
        #expect(c.phase == .ready)
    }
    @Test func jitterDoesNotCreateEndpointsOrRepeatedCounts() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,99),(0.36,101),(0.42,99),(0.48,101),(0.54,99),(0.60,101)])
        #expect(c.observedMovements == 0)
        #expect(c.phase == .outbound)
    }
    @Test func endpointKindsDoNotShareDwell() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,80),(0.45,170)])
        #expect(c.partialAttempts == 0, "Bent dwell must not count as extended dwell.")
        c.consume(Self.pose(0.6), referenceEdge: Self.referenceEdge)
        #expect(c.partialAttempts == 1)
    }
    @Test func slowHoldCountsOnce() {
        var c = ExerciseCounter(); arm(&c)
        for i in 2...50 { c.consume(Self.pose(Double(i) * 0.15, degrees: 80), referenceEdge: Self.referenceEdge) }
        #expect(c.observedMovements == 1)
    }
    @Test func missingJointCannotBorrowOtherArmOrPreviousFrame() {
        var c = ExerciseCounter(); arm(&c)
        c.consume(Self.pose(0.3, degrees: 120), referenceEdge: Self.referenceEdge)
        c.consume(Self.pose(0.45, degrees: 80, side: .right), referenceEdge: Self.referenceEdge)
        feed(&c, [(0.6,80),(0.75,80)])
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 1)
        #expect(c.phase == .seekingStart)
    }
    @Test func lowConfidenceInterruptsAndRequiresFreshStart() {
        var c = ExerciseCounter(); arm(&c)
        c.consume(Self.pose(0.3, degrees: 120), referenceEdge: Self.referenceEdge)
        c.consume(Self.pose(0.45, degrees: 80, confidence: 0.1), referenceEdge: Self.referenceEdge)
        #expect(c.trackingIssue == "lowConfidence")
        feed(&c, [(0.6,170),(0.75,170),(0.9,80),(1.05,80)])
        #expect(c.observedMovements == 1)
        #expect(c.interruptedAttempts == 1)
    }
    @Test func multiplePeopleInterruptRatherThanSelectConvenientSkeleton() {
        var c = ExerciseCounter(); arm(&c)
        c.consume(Self.pose(0.3, degrees: 120), referenceEdge: Self.referenceEdge)
        let p = Self.pose(0.45, degrees: 80)
        c.consume(PoseResult(timestamp: p.timestamp, imageSize: p.imageSize,
                             people: p.people + p.people, backend: p.backend, requestRevision: 0),
                  referenceEdge: Self.referenceEdge)
        #expect(c.trackingIssue == "multiplePeople")
        #expect(c.interruptedAttempts == 1)
    }
    @Test func gapCannotBridgeMissingTopEvidence() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,80),(1,80),(1.15,80)])
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 1)
        #expect(c.lastEvent?.reason == "sourceTimeGap")
    }
    @Test func duplicateAndOutOfOrderTimesNeverCount() {
        var c = ExerciseCounter(); arm(&c)
        feed(&c, [(0.3,80),(0.3,80),(0.2,80),(0.45,80)])
        #expect(c.observedMovements == 0)
        #expect(c.interruptedAttempts == 1)
    }
    @Test func invalidTimesAreNotReplacedWithFrameRate() throws {
        var c = ExerciseCounter(); arm(&c)
        c.consume(Self.pose(0.3, degrees: 120), referenceEdge: Self.referenceEdge)
        let p = Self.pose(0.45, degrees: 80)
        c.consume(PoseResult(timestamp: PresentationTime(value: 1, timescale: 0),
                             imageSize: p.imageSize, people: p.people, backend: p.backend, requestRevision: 0),
                  referenceEdge: Self.referenceEdge)
        #expect(c.trackingIssue == "invalidTimestamp")
        #expect(c.interruptedAttempts == 1)
        _ = try JSONEncoder().encode(c.summary)
    }
    @Test func wristScreenDriftNoLongerActsAsAContactGuard() {
        var c = ExerciseCounter(); arm(&c)
        // Horizontal screen translation of the whole analytic chain does not
        // change shoulder-to-bar distance and must not be treated as contact loss.
        c.consume(Self.pose(0.3, degrees: 80, wristX: 650), referenceEdge: Self.referenceEdge)
        #expect(c.trackingIssue == nil)
        #expect(c.observedMovements == 0)
        #expect(c.phase == .outbound)
    }
    @Test func bentAngleWithoutTravelTowardBarDoesNotReachEndpoint() {
        var c = ExerciseCounter(); arm(&c)
        c.consume(Self.pose(0.3, degrees: 80, verticalOffset: 220), referenceEdge: Self.referenceEdge)
        c.consume(Self.pose(0.45, degrees: 80, verticalOffset: 220), referenceEdge: Self.referenceEdge)
        #expect(c.observedMovements == 0)
        #expect(c.phase == .outbound)
    }
    @Test func missingBarReferenceCannotCount() {
        var c = ExerciseCounter()
        c.consume(Self.pose(0))
        c.consume(Self.pose(0.15))
        c.consume(Self.pose(0.3, degrees: 80))
        c.consume(Self.pose(0.45, degrees: 80))
        #expect(c.observedMovements == 0)
        #expect(c.trackingIssue == "barReferenceUnavailable")
        #expect(c.phase == .seekingStart)
    }
    @Test func obliqueReferenceUsesPerpendicularDistance() {
        let edge = BarSegment(a: Point2D(x: 100, y: 400), b: Point2D(x: 900, y: 600))
        #expect(abs(edge.perpendicularDistance(to: Point2D(x: 500, y: 500))) < 1e-9)
        #expect(edge.perpendicularDistance(to: Point2D(x: 500, y: 600)) > 90)
    }
    @Test func sourceTimeOffsetDoesNotChangeCount() {
        var c = ExerciseCounter()
        feed(&c, [(10,170),(10.15,170),(10.3,80),(10.45,80)])
        #expect(c.observedMovements == 1)
        #expect(c.lastEvent?.sourceSeconds == 10.45)
    }
    @Test func resetClearsEverythingButExerciseAndArm() {
        var c = ExerciseCounter(exercise: .dip, side: .right); arm(&c)
        feed(&c, [(0.3,80),(0.45,80),(0.6,170),(0.75,170)])
        c.reset()
        #expect(c.observedMovements == 0)
        #expect(c.lastEvent == nil)
        #expect(c.events.isEmpty)
        #expect(c.phase == .seekingStart)
        #expect(c.exercise == .dip && c.side == .right)
        arm(&c)
        #expect(c.phase == .ready)
    }
    @Test func finishFreezesStateUntilReset() {
        var c = ExerciseCounter(); c.finish()
        feed(&c, [(0,170),(0.15,170),(0.3,80),(0.45,80)])
        #expect(c.observedMovements == 0)
        #expect(c.phase == .finished)
    }
}