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
    func detect(image: CGImage, region: BarRegion, minimumObservedLength: Double?) async throws -> [BarLineCandidate] {
        requests += 1
        if delay > .zero { try await Task.sleep(for: delay) }
        return [.init(edge: .init(a: .init(x: 45, y: 20), b: .init(x: 115, y: 20)), geometryScore: 70)]
    }
}

private struct DipSupportEstimator: PoseEstimator {
    var missingSide: ArmMeasurement.Side? = nil
    var invalidTimestampAt: Double? = nil
    func estimate(image: CGImage, timestamp: PresentationTime) throws -> PoseResult {
        let points: [(PoseJoint,Double,Double)] = [
            (.leftWrist,115,60),(.leftElbow,113,40),(.leftShoulder,110,20),
            (.rightWrist,45,60),(.rightElbow,47,40),(.rightShoulder,50,20)
        ]
        let sourceTime = timestamp.seconds == invalidTimestampAt ? PresentationTime(value: 0, timescale: 0) : timestamp
        return PoseResult(timestamp:sourceTime,imageSize:.init(width:Double(image.width),height:Double(image.height)),
            people:[.init(landmarks:points.filter { missingSide?.joints.contains($0.0) != true }.map {Landmark(joint:$0.0,position:.init(x:$0.1*Double(image.width)/160,y:$0.2*Double(image.height)/96),confidence:0.9)})],
            backend:"synthetic dip support",requestRevision:0)
    }
}
private actor TestDipRailDetector: BarEdgeDetector {
    private(set) var requests=0
    private(set) var requestedMinimumLengths: [Double?] = []
    let delay:Duration
    let sharedBar:Bool
    init(delay:Duration = .zero,sharedBar:Bool = false){self.delay=delay;self.sharedBar=sharedBar}
    func detect(image:CGImage,region:BarRegion,minimumObservedLength:Double?) async throws -> [BarLineCandidate] {
        requests+=1
        requestedMinimumLengths.append(minimumObservedLength)
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

// Plausible finite handle contours beneath overhead hands. This deliberately
// allows equipment acquisition while leaving dip support/start unqualified.
private actor OverheadContactDetector: BarEdgeDetector {
    private(set) var requests = 0
    func detect(image: CGImage, region: BarRegion, minimumObservedLength: Double?) async throws -> [BarLineCandidate] {
        requests += 1
        let short = Double(min(image.width, image.height))
        let x = (region.minX + region.maxX) / 2
        let y = 25 + short * 0.04
        return [x - 2, x + 2].map {
            .init(edge: .init(a: .init(x: $0, y: y),
                             b: .init(x: $0, y: y + max(24, short * 0.04))), geometryScore: 30)
        }
    }
}

@MainActor @Suite(.serialized) struct AutomaticBarReplayTests {
    @Test func automaticCalibrationPreservesLockFramePixelsAcrossRewind() async throws {
        let times = (0...9).map { CMTime(value: Int64($0 * 16), timescale: 100) }
        let url = try await VideoTestSupport.makeVideo(timestamps: times, cyclingQuadrants: true)
        defer { try? FileManager.default.removeItem(at: url) }
        let worker = ControlledSceneWorker()
        let model = ReplayController(estimator: RaisedHandsEstimator(), barDetector: TestBarDetector(), sceneWorker: worker)
        defer { model.close() }
        model.open(url)
        try await wait(model, for: "initial opening") { model.phase == .paused }
        let firstColor = VideoTestSupport.rgb(try #require(model.frame).image,
            normalizedTopLeft: .init(x: 0.25, y: 0.25))
        model.play()
        try await wait(model, for: "automatic setup and replay") { model.phase == .finished }
        #expect(model.currentBar?.sourceTime.seconds == 1.12)
        let referenceColors = await worker.referenceColors
        #expect(!referenceColors.isEmpty)
        #expect(referenceColors.allSatisfy { $0 == referenceColors.first })
        #expect(referenceColors.first != firstColor, "The rewound first frame cannot replace the lock-frame camera reference.")
        #expect(model.cameraTrackingIssue == nil && model.displayedFrames == times.count)
    }

    func video() async throws -> URL {
        try await VideoTestSupport.makeVideo(timestamps: [0, 35, 70, 105, 140, 175].map { CMTime(value: $0, timescale: 100) })
    }
    @Test func automaticLockRewindsOnceAndResumesWithoutManualSetup() async throws {
        try await withNativeTrace(test: "automaticLockRewindsOnceAndResumesWithoutManualSetup") { trace in
            let url = try await video()
            defer { try? FileManager.default.removeItem(at: url) }
            let detector = TestBarDetector()
            let model = ReplayController(estimator: RaisedHandsEstimator(), barDetector: detector, trace: trace)
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
        }
    }
    @Test func manualSetupCancelsAnAutomaticResultAlreadyInFlight() async throws {
        try await withNativeTrace(test: "manualSetupCancelsAnAutomaticResultAlreadyInFlight") { trace in
            let url = try await video()
            defer { try? FileManager.default.removeItem(at: url) }
            let detector = TestBarDetector(delay: .milliseconds(300))
            let (events, continuation) = AsyncStream.makeStream(of: ReplayTrace.self)
            defer { continuation.finish() }
            let model = ReplayController(estimator: RaisedHandsEstimator(), barDetector: detector, trace: {
                continuation.yield($0)
                trace($0)
            })
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
            // Protect attribution through the real cancellation and native
            // rewind path, including the reused context and source-time reset.
            continuation.finish()
            var recorded: [ReplayTrace] = []
            for await event in events { recorded.append(event) }
            // An old native read may end while the controller waits for its
            // cancelled operation. Validate that wait separately from the
            // reader sequence, which starts only after the old task returns.
            let waitSpan = recorded.filter { $0.stage == .operationWait }
            try #require(waitSpan.count == 2)
            #expect(recorded.filter { $0.stage == .operationWait || $0.stage == .rewindCancellation }
                .map(\.stage) == [.operationWait, .operationWait, .rewindCancellation, .rewindCancellation])
            let rewind = Array(recorded.drop(while: { $0.stage != .rewindCancellation }))
            try #require(rewind.count == 14, "Each of the seven reader/counting stages must close before publishing its first frame.")
            #expect(rewind.filter { $0.boundary == .began }.map(\.stage) == [
                .rewindCancellation, .preparation, .decoding,
                .geometry, .rendering, .estimation, .counting
            ])
            #expect(rewind.filter { $0.stage == .estimation }.map(\.sourceSeconds) == [0, 0])
            let spans = waitSpan + rewind
            for index in stride(from: 0, to: spans.count - 1, by: 2) {
                let begin = spans[index], end = spans[index + 1]
                #expect(begin.stage == end.stage && begin.boundary == .began && end.boundary == .ended)
                let elapsed = try #require(end.milliseconds)
                #expect(elapsed.isFinite && elapsed >= 0)
            }
            model.play()
            try await wait(model, for: "playback completion") { model.phase == .finished }
            #expect(model.currentBar == manual)
            #expect(await detector.requests == 1)
        }
    }

    // Identify the actual test process before native fixture creation. Stage
    // logs include source wall-clock timestamps and an explicit completion
    // marker. Log delivery/sampler startup can lag; don't infer capture scope
    // from a report's finalization timestamp. Drain on success and failure.
    private func withNativeTrace(test: String, _ work: (@escaping ReplayTrace.Handler) async throws -> Void) async throws {
        let processID = ProcessInfo.processInfo.processIdentifier
        // Bypass buffered stdout: attaching after a failed test returns would
        // sample an adjacent test instead of the operation being diagnosed.
        FileHandle.standardOutput.write(Data("Replay profile target: pid=\(processID), test=\(test)\n".utf8))
        let started = ContinuousClock.now
        FileHandle.standardOutput.write(Data(("Replay profile entry: pid=\(processID), test=\(test), " +
            "unixSeconds=\(Date().timeIntervalSince1970)\n").utf8))
        let traceOutput = DispatchQueue(label: "dev.hanginthere.tests.replay-trace")
        let handler: ReplayTrace.Handler = { event in
            let wallMilliseconds = ReplayTrace.milliseconds(since: started)
            let unixSeconds = Date().timeIntervalSince1970
            traceOutput.async {
                let line = "Replay stage: pid=\(processID), test=\(test), " +
                      "wallMilliseconds=\(wallMilliseconds), " +
                      "unixSeconds=\(unixSeconds), " +
                      "stage=\(event.stage.rawValue), boundary=\(event.boundary.rawValue), " +
                      "sourcePTS=\(event.sourceSeconds?.description ?? "none"), " +
                      "stageMilliseconds=\(event.milliseconds?.description ?? "none"), " +
                      "renderingBackend=\(event.renderingBackend?.rawValue ?? "none")\n"
                FileHandle.standardOutput.write(Data(line.utf8))
            }
        }
        func drain() async {
            let unixSeconds = Date().timeIntervalSince1970
            let wallMilliseconds = ReplayTrace.milliseconds(since: started)
            await withCheckedContinuation { completion in
                traceOutput.async {
                    let line = "Replay profile complete: pid=\(processID), test=\(test), " +
                        "unixSeconds=\(unixSeconds), wallMilliseconds=\(wallMilliseconds)\n"
                    FileHandle.standardOutput.write(Data(line.utf8))
                    completion.resume()
                }
            }
        }
        do {
            try await work(handler)
        } catch {
            await drain()
            throw error
        }
        await drain()
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
    @Test(arguments: ArmMeasurement.Side.allCases)
    func raisedHandsCannotBeMisclassifiedAsDipSupport(side: ArmMeasurement.Side) async throws {
        let url = try await video()
        defer { try? FileManager.default.removeItem(at: url) }
        let detector = TestBarDetector()
        let model = ReplayController(estimator: RaisedHandsEstimator(), barDetector: detector)
        defer { model.close() }
        model.configureCounting(exercise: .dip, side: side)
        model.open(url)
        try await wait(model, for: "initial opening") { model.phase == .paused }
        model.play()
        try await wait(model, for: "playback completion") { model.phase == .finished }
        #expect(model.bar == nil)
        // Contact search is independent of starting pose. The first displayed
        // frame is not searched; each remaining frame has three bounded crops.
        #expect(await detector.requests == 15)
        #expect(model.countingFrames == 0)
        #expect(model.analyzableFrames == 0)
        #expect(model.counter.observedMovements == 0)
        #expect(model.repAnalysis.isEmpty)
        #expect(model.analysisRecord?.completedCount == 0)
    }
    @Test(arguments: ArmMeasurement.Side.allCases)
    func overheadEquipmentCannotQualifyADipStartOrCompletedAnalytics(side: ArmMeasurement.Side) async throws {
        let url = try await VideoTestSupport.makeVideo(
            timestamps: [0, 35, 70, 105, 140, 175].map { CMTime(value: $0, timescale: 100) },
            width: 320, height: 192)
        defer { try? FileManager.default.removeItem(at: url) }
        let detector = OverheadContactDetector()
        let model = ReplayController(estimator: RaisedHandsEstimator(), barDetector: detector)
        defer { model.close() }
        model.configureCounting(exercise: .dip, side: side)
        model.open(url)
        try await wait(model, for: "overhead contact opening") { model.phase == .paused }
        model.play()
        try await wait(model, for: "overhead contact completion") { model.phase == .finished }
        let bar = try #require(model.currentBar)
        #expect(bar.role == (side == .left ? .leftDipRail : .rightDipRail))
        #expect(await detector.requests == 12, "Four bilateral acquisition samples, then no redetection after rewind.")
        #expect(model.countingFrames == 6)
        #expect(model.counter.observedMovements == 0)
        #expect(model.counter.events.isEmpty)
        #expect(model.repAnalysis.isEmpty)
        #expect(model.analysisRecord?.completedCount == 0)
        let pose = try #require(model.frame?.pose)
        #expect(!AutomaticDipRails.isStartingPosition(pose))
        #expect(DipSupportMeasurement(pose: pose, side: side, referenceEdge: bar.referenceEdge)?.isStartingSupport == false)
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
        let requestedLengths = await detector.requestedMinimumLengths
        #expect(requestedLengths == Array(repeating: 24.0, count: 12))
    }
    @Test(arguments: ArmMeasurement.Side.allCases)
    func singleDipContactAcquiresOnlyItsObservedRailAndRewinds(side: ArmMeasurement.Side) async throws {
        let url = try await VideoTestSupport.makeVideo(timestamps: [0,35,70,105,140,175].map {
            CMTime(value: $0, timescale: 100)
        }, width: 320, height: 192)
        defer { try? FileManager.default.removeItem(at: url) }
        let other: ArmMeasurement.Side = side == .left ? .right : .left
        let detector = TestDipRailDetector()
        let model = ReplayController(estimator: DipSupportEstimator(missingSide: other), barDetector: detector)
        defer { model.close() }
        model.configureCounting(exercise: .dip, side: side)
        model.open(url)
        try await wait(model, for: "single-contact opening") { model.phase == .paused }
        model.play()
        try await wait(model, for: "single-contact completion") { model.phase == .finished }
        let bar = try #require(model.currentBar)
        #expect(bar.role == (side == .left ? .leftDipRail : .rightDipRail))
        #expect(bar.method == .automaticContours && bar.oppositeEdge == nil)
        #expect(bar.sourceTime.seconds == 1.4)
        #expect(await detector.requests == 4, "Only the actual hand's contour region is observed.")
        #expect(model.countingFrames == 6)
        #expect(model.counter.observedMovements == 0)
        #expect(model.analysisRecord?.completedCount == 0)
        let finalPose = try #require(model.frame?.pose)
        #expect(PullUpArmMeasurement(pose: finalPose).arms.map(\.side) == [side])
    }

    @Test func invalidPoseClockRevokesPendingSingleRailDwell() async throws {
        let url = try await VideoTestSupport.makeVideo(timestamps: [0,35,70,105,140,175].map {
            CMTime(value: $0, timescale: 100)
        }, width: 320, height: 192)
        defer { try? FileManager.default.removeItem(at: url) }
        let detector = TestDipRailDetector()
        let model = ReplayController(estimator: DipSupportEstimator(missingSide: .right, invalidTimestampAt: 1.4),
            barDetector: detector)
        defer { model.close() }
        model.configureCounting(exercise: .dip, side: .left)
        model.open(url)
        try await wait(model, for: "clock-fault opening") { model.phase == .paused }
        model.play()
        try await wait(model, for: "clock-fault completion") { model.phase == .finished }
        #expect(await detector.requests == 4)
        #expect(model.currentBar == nil)
        #expect(model.countingFrames == 0)
        #expect(model.counter.observedMovements == 0)
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
