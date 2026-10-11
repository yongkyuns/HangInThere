import AVFoundation
import CoreGraphics
import Dispatch
import Testing
@testable import HangInThere

private struct RaisedHandsEstimator: PoseEstimator {
    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        let points: [(PoseJoint, Double, Double)] = [
            (.leftWrist, 115, 25), (.leftElbow, 110, 45), (.leftShoulder, 95, 70),
            (.rightWrist, 45, 25), (.rightElbow, 50, 45), (.rightShoulder, 65, 70)
        ]
        return PoseResult(timestamp: timestamp, imageSize: .init(width: Double(image.width), height: Double(image.height)),
                          people: [.init(landmarks: points.map {
            Landmark(joint: $0.0, position: .init(x: $0.1, y: $0.2), confidence: 0.9)
        })], backend: "synthetic raised hands", requestRevision: 0)
    }
}

private actor TestBarDetector: BarEdgeDetector {
    private(set) var requests = 0
    let delay: Duration
    init(delay: Duration = .zero) { self.delay = delay }
    func detect(image: CGImage, region: BarRegion) async throws -> [BarLineCandidate] {
        requests += 1
        if delay > .zero { try await Task.sleep(for: delay) }
        return [.init(edge: .init(a: .init(x: 45, y: 20), b: .init(x: 115, y: 20)), geometryScore: 70)]
    }
}

private struct DipSupportEstimator: PoseEstimator {
    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        let points: [(PoseJoint,Double,Double)] = [
            (.leftWrist,115,60),(.leftElbow,113,40),(.leftShoulder,110,20),
            (.rightWrist,45,60),(.rightElbow,47,40),(.rightShoulder,50,20)
        ]
        return PoseResult(timestamp:timestamp,imageSize:.init(width:Double(image.width),height:Double(image.height)),
            people:[.init(landmarks:points.map {Landmark(joint:$0.0,position:.init(x:$0.1*Double(image.width)/160,y:$0.2*Double(image.height)/96),confidence:0.9)})],
            backend:"synthetic dip support",requestRevision:0)
    }
}
private actor TestDipRailDetector: BarEdgeDetector {
    private(set) var requests=0
    let delay:Duration
    let sharedBar:Bool
    init(delay:Duration = .zero,sharedBar:Bool = false){self.delay=delay;self.sharedBar=sharedBar}
    func detect(image:CGImage,region:BarRegion) async throws -> [BarLineCandidate] {
        requests+=1
        if delay > .zero {try await Task.sleep(for:delay)}
        let x=(region.minX+region.maxX)/2
        let scale=Double(image.height)/96
        if sharedBar {
            // Only the common crop observes this tube; independent local
            // handles are absent. Coordinates remain finite observed edges.
            guard region.maxX-region.minX > Double(image.width)*0.5 else {return []}
            let sx=Double(image.width)/160
            return [64.0,69.0].map { y in
                .init(edge:.init(a:.init(x:37.5*sx,y:y*scale),b:.init(x:122.5*sx,y:y*scale)),geometryScore:85*sx)
            }
        }
        return [x-4*scale,x+4*scale].map {
            .init(edge:.init(a:.init(x:$0,y:64*scale),b:.init(x:$0,y:78*scale)),geometryScore:28)
        }
    }
}

@MainActor @Suite(.serialized) struct AutomaticBarReplayTests {
    func video() async throws -> URL {
        try await VideoTestSupport.makeVideo(timestamps: [0, 35, 70, 105, 140, 175].map { CMTime(value: $0, timescale: 100) })
    }
    @Test func automaticLockRewindsOnceAndResumesWithoutManualSetup() async throws {
        // Native attachment can take seconds. Identify the real test process
        // before fixture creation, while keeping the replay's clock/deadline.
        print("Replay profile target: pid=\(ProcessInfo.processInfo.processIdentifier), " +
              "test=automaticLockRewindsOnceAndResumesWithoutManualSetup")
        let url = try await video()
        defer { try? FileManager.default.removeItem(at: url) }
        let detector = TestBarDetector()
        let started = ContinuousClock.now
        let traceOutput = DispatchQueue(label: "dev.hanginthere.tests.replay-trace")
        let processID = ProcessInfo.processInfo.processIdentifier
        let model = ReplayController(estimator: RaisedHandsEstimator(), barDetector: detector, trace: { event in
            // Snapshot on the native worker, publish in order elsewhere. A
            // slow test-log pipe must not block the stage being measured.
            let wallMilliseconds = ReplayTrace.milliseconds(since: started)
            traceOutput.async {
                print("Replay stage: pid=\(processID), " +
                      "wallMilliseconds=\(wallMilliseconds), " +
                      "stage=\(event.stage.rawValue), boundary=\(event.boundary.rawValue), " +
                      "sourcePTS=\(event.sourceSeconds?.description ?? "none"), " +
                      "stageMilliseconds=\(event.milliseconds?.description ?? "none")")
            }
        })
        // Success closes before draining the log; failures still clean up.
        defer { if model.phase != .idle { model.close() } }
        model.open(url)
        try await wait(model, for: "initial opening") { model.phase == .paused }
        #expect(model.bar == nil)
        model.play()
        try await wait(model, for: "playback completion") { model.phase == .finished }
        #expect(model.currentBar?.method == .automaticContours)
        #expect(model.currentBar?.sourceTime.seconds == 1.4)
        #expect(model.displayedFrames == 6)
        #expect(model.countingFrames == 6)
        #expect(await detector.requests == 4, "A locked bar is never redetected during counting.")
        model.close()
        #expect(model.bar == nil)
        #expect(model.automaticBarState == .lookingForGrip)
        // Preserve the successful test's complete stage log before its verdict,
        // without blocking the main actor or changing playback's deadline.
        await withCheckedContinuation { completion in
            traceOutput.async { completion.resume() }
        }
    }
    @Test func manualSetupCancelsAnAutomaticResultAlreadyInFlight() async throws {
        let url = try await video()
        defer { try? FileManager.default.removeItem(at: url) }
        let detector = TestBarDetector(delay: .milliseconds(300))
        let model = ReplayController(estimator: RaisedHandsEstimator(), barDetector: detector)
        defer { model.close() }
        model.open(url)
        try await wait(model, for: "initial opening") { model.phase == .paused }
        model.play()
        try await waitForRequest(detector)
        let setup = try #require(model.beginBarSetup())
        let manual = ConfirmedBar(role: .pullUpGrip, method: .manualEdge,
                                  referenceEdge: .init(a: .init(x: 30, y: 15), b: .init(x: 130, y: 15)),
                                  oppositeEdge: nil, imageSize: setup.frame.pose.imageSize, sourceTime: setup.frame.pose.timestamp)
        #expect(model.confirmBar(manual, for: setup))
        try await wait(model, for: "manual-reference rewind") { model.phase == .paused }
        #expect(model.currentBar == manual)
        model.play()
        try await wait(model, for: "playback completion") { model.phase == .finished }
        #expect(model.currentBar == manual)
        #expect(await detector.requests == 1)
    }
    @Test func replacingSourceCannotPublishOldAutomaticGeometry() async throws {
        let url = try await video()
        defer { try? FileManager.default.removeItem(at: url) }
        let detector = TestBarDetector(delay: .milliseconds(300))
        let model = ReplayController(estimator: RaisedHandsEstimator(), barDetector: detector)
        defer { model.close() }
        model.open(url)
        try await wait(model, for: "initial opening") { model.phase == .paused }
        model.play()
        try await waitForRequest(detector)
        model.open(url)
        try await wait(model, for: "replacement source opening") { model.phase == .paused }
        #expect(model.bar == nil)
        #expect(model.displayedFrames == 1)
        #expect(model.automaticBarState == .lookingForGrip)
    }
    @Test func raisedHandsCannotBeMisclassifiedAsDipSupport() async throws {
        let url = try await video()
        defer { try? FileManager.default.removeItem(at: url) }
        let detector = TestBarDetector()
        let model = ReplayController(estimator: RaisedHandsEstimator(), barDetector: detector)
        defer { model.close() }
        model.configureCounting(exercise: .dip, side: .left)
        model.open(url)
        try await wait(model, for: "initial opening") { model.phase == .paused }
        model.play()
        try await wait(model, for: "playback completion") { model.phase == .finished }
        #expect(model.bar == nil)
        #expect(await detector.requests == 0)
    }
    @Test func dipRailsAcquireAutomaticallyAndRewindWithFixedReference() async throws {
        let url=try await VideoTestSupport.makeVideo(timestamps:[0,35,70,105,140,175].map {CMTime(value:$0,timescale:100)},width:320,height:192)
        defer {try? FileManager.default.removeItem(at:url)}
        let detector=TestDipRailDetector()
        let model=ReplayController(estimator:DipSupportEstimator(),barDetector:detector)
        defer {model.close()}
        model.configureCounting(exercise:.dip,side:.right)
        model.open(url)
        try await wait(model, for: "initial opening") {model.phase == .paused}
        model.play()
        try await wait(model, for: "playback completion") {model.phase == .finished}
        #expect(model.currentBar?.method == .automaticContours)
        #expect(model.currentBar?.role == .rightDipRail)
        #expect(model.currentBar?.sourceTime.seconds == 1.4)
        #expect(model.countingFrames == 6)
        #expect(await detector.requests == 12, "Both local handles and shared-bar support are checked at each of four acquisition observations.")
    }
    @Test func sharedDipBarAcquiresAndRewindsForEitherTrackedHand() async throws {
        let url=try await VideoTestSupport.makeVideo(timestamps:[0,35,70,105,140,175].map {CMTime(value:$0,timescale:100)},width:320,height:192)
        defer {try? FileManager.default.removeItem(at:url)}
        for side in ArmMeasurement.Side.allCases {
            let detector=TestDipRailDetector(sharedBar:true)
            let model=ReplayController(estimator:DipSupportEstimator(),barDetector:detector)
            defer {model.close()}
            model.configureCounting(exercise:.dip,side:side)
            model.open(url)
            try await wait(model, for:"shared-bar opening") {model.phase == .paused}
            model.play()
            try await wait(model, for:"shared-bar playback completion") {model.phase == .finished}
            #expect(model.currentBar?.method == .automaticContours)
            #expect(model.currentBar?.role == (side == .left ? .leftDipRail : .rightDipRail))
            #expect(model.currentBar?.referenceEdge == .init(a:.init(x:75,y:128),b:.init(x:245,y:128)))
            #expect(model.currentBar?.sourceTime.seconds == 1.4)
            #expect(model.countingFrames == 6)
            #expect(await detector.requests == 12)
        }
    }

    @Test func changingDipSideCannotPublishAnOldRailAcquisition() async throws {
        let url=try await VideoTestSupport.makeVideo(timestamps:[0,35,70,105,140,175].map {CMTime(value:$0,timescale:100)},width:320,height:192)
        defer {try? FileManager.default.removeItem(at:url)}
        let detector=TestDipRailDetector(delay:.milliseconds(150))
        let model=ReplayController(estimator:DipSupportEstimator(),barDetector:detector)
        defer {model.close()}
        model.configureCounting(exercise:.dip,side:.left)
        model.open(url)
        try await wait(model, for: "initial opening") {model.phase == .paused}
        model.play()
        try await wait(model, for: "dip grip holding") {model.automaticBarState == .holding}
        model.configureCounting(exercise:.dip,side:.right)
        try await wait(model, for: "changed-side rewind") {model.phase == .paused}
        #expect(model.currentBar == nil)
        model.play()
        try await wait(model, for: "playback completion") {model.phase == .finished}
        #expect(model.currentBar?.role == .rightDipRail)
    }

    @Test func failedSourcePreservesOpeningErrorInsteadOfTimingOut() async throws {
        let model = ReplayController(estimator: RaisedHandsEstimator(), barDetector: TestBarDetector())
        defer { model.close() }
        model.open(FileManager.default.temporaryDirectory.appendingPathComponent("missing-\(UUID().uuidString).mov"))
        do {
            try await wait(model, for: "opening missing source") { model.phase == .paused }
            Issue.record("Opening a missing source must fail.")
        } catch FixtureError.failed(let message) {
            #expect(model.phase == .failed)
            let underlyingError = try #require(model.errorMessage)
            #expect(message.contains("Failed while waiting for opening missing source"))
            #expect(message.contains(underlyingError))
            #expect(message.contains("displayedFrames=0, countingFrames=0"))
        }
    }

    private func wait(_ model: ReplayController, for transition: String, _ predicate: () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(20))
        while !predicate() {
            if model.phase == .failed {
                throw FixtureError.failed("Failed while waiting for \(transition): \(diagnostics(model))")
            }
            guard ContinuousClock.now < deadline else {
                throw FixtureError.failed("Timed out waiting for \(transition): \(diagnostics(model))")
            }
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    private func diagnostics(_ model: ReplayController) -> String {
        "phase=\(model.phase), error=\(model.errorMessage ?? "none"), failureReport=\(model.failureReport ?? "none"), " +
        "lastSourcePTS=\(model.frame?.pose.timestamp.seconds.description ?? "none"), " +
        "displayedFrames=\(model.displayedFrames), countingFrames=\(model.countingFrames), " +
        "automaticBarState=\(model.automaticBarState), referencePTS=\(model.bar?.sourceTime.seconds.description ?? "none"), " +
        "processingMilliseconds=\(model.frame?.processingMilliseconds.description ?? "none")"
    }
    private func waitForRequest(_ detector: TestBarDetector) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(20))
        while await detector.requests == 0 {
            guard ContinuousClock.now < deadline else { throw FixtureError.failed("Automatic bar request timeout") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}
