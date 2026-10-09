import Foundation
import Testing
@testable import HangInThere

@Suite struct DipSupportTests {
    static let verticalRail = BarSegment(a: .init(x: 500, y: 480), b: .init(x: 500, y: 620))

    static func pose(_ time: Double, degrees: Double = 170, scale: Double = 1,
                     shift: Point2D = .init(x: 0, y: 0), otherDegrees: Double? = nil) -> PoseResult {
        let base = ExerciseCounterTests.pose(time, degrees: degrees, exercise: .dip)
        var landmarks = base.people[0].landmarks
        if let otherDegrees {
            landmarks += ExerciseCounterTests.pose(time, degrees: otherDegrees, exercise: .dip,
                                                  side: .right, wristX: 700).people[0].landmarks
        }
        return PoseResult(timestamp: base.timestamp,
            imageSize: .init(width: 1000 * scale, height: 1000 * scale),
            people: [.init(landmarks: landmarks.map {
                .init(joint: $0.joint,
                      position: .init(x: ($0.position.x + shift.x) * scale,
                                      y: ($0.position.y + shift.y) * scale), confidence: $0.confidence)
            })], backend: base.backend, requestRevision: base.requestRevision)
    }

    @Test func verticalRailCountsFullCycleAndScalesWithAthlete() {
        for scale in [0.5, 1.0, 1.5] {
            var counter = ExerciseCounter(exercise: .dip)
            let rail = BarSegment(a: .init(x: 500 * scale, y: 480 * scale),
                                  b: .init(x: 500 * scale, y: 620 * scale))
            for (time, degrees) in [(0.0,170.0),(0.15,170),(0.3,80),(0.45,80)] {
                counter.consume(Self.pose(time, degrees: degrees, scale: scale), referenceEdge: rail)
            }
            #expect(counter.phase == .returning)
            #expect(counter.observedMovements == 0)
            for (time, degrees) in [(0.6,110.0),(0.75,110)] {
                counter.consume(Self.pose(time, degrees: degrees, scale: scale), referenceEdge: rail)
            }
            #expect(counter.observedMovements == 0, "Partial recovery must not count as reaching the top.")
            for (time, degrees) in [(0.9,170.0),(1.05,170)] {
                counter.consume(Self.pose(time, degrees: degrees, scale: scale), referenceEdge: rail)
            }
            #expect(counter.observedMovements == 1)
        }
    }

    @Test func movingGripInterruptsWithoutCountingDismount() {
        var counter = ExerciseCounter(exercise: .dip)
        for (time, degrees) in [(0.0,170.0),(0.15,170),(0.3,80),(0.45,80)] {
            counter.consume(Self.pose(time, degrees: degrees), referenceEdge: Self.verticalRail)
        }
        counter.consume(Self.pose(0.6, shift: .init(x: 45, y: 0)), referenceEdge: Self.verticalRail)
        #expect(counter.observedMovements == 0)
        #expect(counter.interruptedAttempts == 1)
        #expect(counter.trackingIssue == "dipGripMoved")
    }

    @Test func visibleOppositeArmMustAlsoBendAndReturn() {
        var counter = ExerciseCounter(exercise: .dip)
        for (time, degrees, other) in [(0.0,170.0,170.0),(0.15,170,170),
                                      (0.3,80,170),(0.45,80,170)] {
            counter.consume(Self.pose(time, degrees: degrees, otherDegrees: other), referenceEdge: Self.verticalRail)
        }
        #expect(counter.phase != .returning)
        #expect(counter.observedMovements == 0)
        for time in [0.6,0.75] {
            counter.consume(Self.pose(time, degrees: 80, otherDegrees: 80), referenceEdge: Self.verticalRail)
        }
        #expect(counter.phase == .returning)
        for time in [0.9,1.05] {
            counter.consume(Self.pose(time, otherDegrees: 80), referenceEdge: Self.verticalRail)
        }
        #expect(counter.observedMovements == 0)
        for time in [1.2,1.35] {
            counter.consume(Self.pose(time, otherDegrees: 170), referenceEdge: Self.verticalRail)
        }
        #expect(counter.observedMovements == 1)
    }

    @Test func crossingLegsIsNotRequiredForHandsFreeDipStart() {
        var start = HandsFreeSetStart()
        // Fixtures intentionally contain only arm joints: no knee/ankle evidence.
        for tick in 0..<30 {
            let began = start.observeDip(pose: Self.pose(Double(tick) / 10), side: .left,
                referenceEdge: Self.verticalRail, eligible: true)
            #expect(!began)
        }
        let began = start.observeDip(pose: Self.pose(3), side: .left,
            referenceEdge: Self.verticalRail, eligible: true)
        #expect(began)
        let repeated = start.observeDip(pose: Self.pose(3.1), side: .left,
            referenceEdge: Self.verticalRail, eligible: true)
        #expect(!repeated)
    }

    @Test func brieflyHiddenOppositeArmRetainsItsStartingBaseline() {
        var counter = ExerciseCounter(exercise: .dip)
        for time in [0.0,0.15] {
            counter.consume(Self.pose(time, otherDegrees: 170), referenceEdge: Self.verticalRail)
        }
        counter.consume(Self.pose(0.25), referenceEdge: Self.verticalRail)
        // The opposite arm reappears still extended while the selected arm
        // bends. Its earlier top must survive the observation hole.
        for time in [0.4,0.55] {
            counter.consume(Self.pose(time, degrees: 80, otherDegrees: 170), referenceEdge: Self.verticalRail)
        }
        #expect(counter.phase != .returning)
        for time in [0.7,0.85] {
            counter.consume(Self.pose(time, otherDegrees: 170), referenceEdge: Self.verticalRail)
        }
        #expect(counter.observedMovements == 0)
        #expect(counter.partialAttempts == 1)
    }

    @Test func startingSupportRequiresActualGripAndAllVisibleArmsSupported() {
        for (degrees, other, rail) in [(80.0,170.0,Self.verticalRail),
                                       (170,80,Self.verticalRail),
                                       (170,170,BarSegment(a: .init(x: 900,y: 480),b: .init(x: 900,y: 620)))] {
            var start = HandsFreeSetStart()
            for tick in 0...40 {
                let began = start.observeDip(pose: Self.pose(Double(tick) / 10, degrees: degrees, otherDegrees: other),
                    side: .left, referenceEdge: rail, eligible: true)
                #expect(!began)
            }
            #expect(start.state == .waiting)
        }
    }

    @Test func loweringOrMovingHandsRestartsWholeDipCountdown() {
        for moving in [Self.pose(2.9, degrees: 80), Self.pose(2.9, shift: .init(x: 40,y: 0))] {
            var start = HandsFreeSetStart()
            for tick in 0..<29 {
                _ = start.observeDip(pose: Self.pose(Double(tick) / 10), side: .left,
                    referenceEdge: Self.verticalRail, eligible: true)
            }
            let began = start.observeDip(pose: moving, side: .left, referenceEdge: Self.verticalRail, eligible: true)
            #expect(!began)
            let early = start.observeDip(pose: Self.pose(3), side: .left, referenceEdge: Self.verticalRail, eligible: true)
            #expect(!early)
            #expect(start.state != .started)
        }
    }

    @Test func pausedUnavailableAndStaleDipFramesCannotEarnCountdownTime() {
        var start = HandsFreeSetStart()
        for tick in 0..<20 {
            _ = start.observeDip(pose: Self.pose(Double(tick) / 10), side: .left,
                referenceEdge: Self.verticalRail, eligible: true)
        }
        let stale = start.observeDip(pose: Self.pose(5), side: .left, referenceEdge: Self.verticalRail, eligible: true)
        #expect(!stale)
        #expect(start.state == .countdown(3))
        let paused = start.observeDip(pose: Self.pose(5.1), side: .left,
            referenceEdge: Self.verticalRail, eligible: true, paused: true)
        #expect(!paused)
        #expect(start.state == .waiting)
        for tick in 52...85 {
            let unavailable = start.observeDip(pose: Self.pose(Double(tick) / 10), side: .left,
                referenceEdge: nil, eligible: true)
            #expect(!unavailable)
        }
        #expect(start.state == .waiting)
    }

    @Test func unclearOrDuplicateWristCannotStartCountdownOrCompleteDip() {
        for duplicate in [false, true] {
            func unclear(_ time: Double) -> PoseResult {
                let base = Self.pose(time)
                var points = base.people[0].landmarks.map {
                    Landmark(joint: $0.joint, position: $0.position,
                             confidence: !duplicate && $0.joint == .leftWrist ? 0.1 : $0.confidence)
                }
                if duplicate, let wrist = points.first(where: { $0.joint == .leftWrist }) { points.append(wrist) }
                return PoseResult(timestamp: base.timestamp, imageSize: base.imageSize,
                    people: [.init(landmarks: points)], backend: base.backend, requestRevision: base.requestRevision)
            }
            var start = HandsFreeSetStart()
            for tick in 0...40 {
                let began = start.observeDip(pose: unclear(Double(tick) / 10), side: .left,
                    referenceEdge: Self.verticalRail, eligible: true)
                #expect(!began)
            }
            #expect(start.state == .waiting)
            var counter = ExerciseCounter(exercise: .dip)
            for (time, degrees) in [(0.0,170.0),(0.15,170),(0.3,80),(0.45,80)] {
                counter.consume(Self.pose(time, degrees: degrees), referenceEdge: Self.verticalRail)
            }
            for time in [0.6,0.75,0.9,1.05] { counter.consume(unclear(time), referenceEdge: Self.verticalRail) }
            #expect(counter.observedMovements == 0)
        }
    }

    @Test func smallLoweringCannotCountAndOutageRequiresFreshStartingSupport() {
        var counter = ExerciseCounter(exercise: .dip)
        for (time, degrees) in [(0.0,170.0),(0.15,170),(0.3,135),(0.45,135),(0.6,170),(0.75,170)] {
            counter.consume(Self.pose(time, degrees: degrees), referenceEdge: Self.verticalRail)
        }
        #expect(counter.observedMovements == 0)
        for (time, degrees) in [(0.9,80.0),(1.05,80)] {
            counter.consume(Self.pose(time, degrees: degrees), referenceEdge: Self.verticalRail)
        }
        counter.consume(ExerciseCounterTests.noPersonPose(1.3), referenceEdge: Self.verticalRail)
        counter.consume(ExerciseCounterTests.noPersonPose(1.6), referenceEdge: Self.verticalRail)
        counter.consume(ExerciseCounterTests.noPersonPose(1.85), referenceEdge: Self.verticalRail)
        #expect(counter.interruptedAttempts == 1)
        counter.consume(Self.pose(2, degrees: 80), referenceEdge: Self.verticalRail)
        #expect(counter.phase == .seekingStart)
        #expect(counter.observedMovements == 0)
    }
}
