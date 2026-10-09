import Testing
@testable import HangInThere

@Suite struct HandsFreeSetStartTests {
    @Test func openingDetailsCancelsCountdownAndPausedFramesCannotStartSet() {
        var start = HandsFreeSetStart()
        for tick in 0..<20 { _ = start.observe(timestamp: Double(tick) / 10, eligible: true) }
        #expect(start.state == .countdown(2))
        for tick in 20..<70 {
            let began = start.observe(timestamp: Double(tick) / 10, eligible: true, paused: true)
            #expect(!began)
            #expect(start.state == .waiting)
        }
        // Dismissing the panel starts a complete fresh countdown, even when
        // the athlete remained eligible throughout the time spent in details.
        for tick in 70..<100 {
            let began = start.observe(timestamp: Double(tick) / 10, eligible: true)
            #expect(!began)
        }
        let began = start.observe(timestamp: 10, eligible: true)
        #expect(began)
    }

    @Test func countdownUsesFreshEligibleFramesAndStartsOnlyOnce() {
        var start = HandsFreeSetStart()
        for tick in 0..<30 {
            let began = start.observe(timestamp: Double(tick) / 10, eligible: true)
            #expect(!began)
            #expect(start.state == .countdown(3 - tick / 10))
        }
        let began = start.observe(timestamp: 3, eligible: true)
        #expect(began)
        #expect(start.state == .started)
        expectNoStart(&start, timestamp: 3.1, eligible: true)
        start.reset()
        #expect(start.state == .waiting)
        expectNoStart(&start, timestamp: 5, eligible: true)
        #expect(start.state == .countdown(3))
    }
    @Test func losingSetupOrGripCancelsAndRequiresFullNewCountdown() {
        var start = HandsFreeSetStart()
        for tick in 0..<29 { _ = start.observe(timestamp: Double(tick) / 10, eligible: true) }
        expectNoStart(&start, timestamp: 2.9, eligible: false)
        #expect(start.state == .waiting)
        expectNoStart(&start, timestamp: 3, eligible: true)
        #expect(start.state == .countdown(3))
        for tick in 31..<60 { expectNoStart(&start, timestamp: Double(tick) / 10, eligible: true) }
        let began = start.observe(timestamp: 6, eligible: true)
        #expect(began)
    }
    @Test func frameOutageCannotAdvanceCountdown() {
        var start = HandsFreeSetStart()
        for tick in 0..<20 { _ = start.observe(timestamp: Double(tick) / 10, eligible: true) }
        expectNoStart(&start, timestamp: 5, eligible: true)
        #expect(start.state == .countdown(3))
    }
    @Test func duplicateReversedAndInvalidTimestampsNeverEarnTime() {
        for timestamp in [1.0, 0.5, .nan, .infinity] {
            var start = HandsFreeSetStart()
            _ = start.observe(timestamp: 1, eligible: true)
            expectNoStart(&start, timestamp: timestamp, eligible: true)
            #expect(start.state == .waiting)
        }
    }
    @Test func startingPositionRequiresRaisedHandsAndAllUsableArmsExtended() {
        let fixture = AutomaticPullUpBarTests()
        let pose = fixture.pose(0)
        let edge = fixture.candidate().edge
        #expect(HandsFreeSetStart.isStartingPosition(pose: pose, referenceEdge: edge))
        #expect(!HandsFreeSetStart.isStartingPosition(pose: pose, referenceEdge: fixture.candidate(370).edge))
        let bent = PoseResult(timestamp: pose.timestamp, imageSize: pose.imageSize,
            people: [.init(landmarks: pose.people[0].landmarks.map {
                $0.joint == .leftElbow
                    ? Landmark(joint: .leftElbow, position: .init(x: 320, y: 340), confidence: 0.9)
                    : $0
            })], backend: "synthetic", requestRevision: 1)
        #expect(!HandsFreeSetStart.isStartingPosition(pose: bent, referenceEdge: edge))
        let missing = PoseResult(timestamp: pose.timestamp, imageSize: pose.imageSize,
            people: [.init(landmarks: pose.people[0].landmarks.filter { $0.joint != .rightWrist })],
            backend: "synthetic", requestRevision: 1)
        #expect(HandsFreeSetStart.isStartingPosition(pose: missing, referenceEdge: edge))
    }
    private func expectNoStart(_ start: inout HandsFreeSetStart, timestamp: Double, eligible: Bool) {
        let began = start.observe(timestamp: timestamp, eligible: eligible)
        #expect(!began)
    }

}
