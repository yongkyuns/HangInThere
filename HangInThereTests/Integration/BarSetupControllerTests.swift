import AVFoundation
import Foundation
import Testing
@testable import HangInThere

@MainActor @Suite(.serialized)
struct BarSetupControllerTests {
    @Test func confirmedBarIsSessionBoundAndLifecycleClearsIt() async throws {
        let url = try await VideoTestSupport.makeVideo(timestamps: [CMTime(value:0,timescale:30),CMTime(value:1,timescale:30)])
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: TestPoseEstimator())
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        let setup = try #require(model.beginBarSetup())
        let bar = ConfirmedBar(role:.pullUpGrip,method:.manualEdge,
            referenceEdge:BarSegment(a:Point2D(x:20,y:20),b:Point2D(x:100,y:20)),oppositeEdge:nil,
            imageSize:setup.frame.pose.imageSize,sourceTime:setup.frame.pose.timestamp)
        #expect(model.confirmBar(bar,for:setup))
        #expect(model.currentBar == bar)
        #expect(model.counter.observedMovements == 0)
        #expect(model.counter.summary.referenceMode == "confirmedBar")
        model.clearBar()
        #expect(model.currentBar == nil)
        #expect(model.counter.summary.referenceMode == "fixedCameraOnly")
        #expect(model.confirmBar(bar,for:setup))
        model.restart()
        #expect(model.currentBar == nil)
        try await wait { model.phase == .paused || model.phase == .failed }
        // Same PTS and pixels after rewind do not authorize an old setup session.
        #expect(!model.confirmBar(bar,for:setup))
        let next = try #require(model.beginBarSetup())
        #expect(model.confirmBar(bar,for:next))
        model.open(url)
        #expect(model.currentBar == nil)
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(!model.confirmBar(bar,for:next))
        model.close()
        #expect(model.bar == nil)
    }

    @Test func barConfirmationRejectsWrongRoleAndUnobservedGeometry() async throws {
        let url = try await VideoTestSupport.makeVideo(timestamps: [CMTime(value:0,timescale:30),CMTime(value:1,timescale:30)])
        defer { try? FileManager.default.removeItem(at: url) }
        let model = ReplayController(estimator: TestPoseEstimator())
        defer { model.close() }
        model.open(url)
        try await wait { model.phase == .paused || model.phase == .failed }
        let setup = try #require(model.beginBarSetup())
        let wrong = ConfirmedBar(role:.rightDipRail,method:.manualEdge,
            referenceEdge:BarSegment(a:Point2D(x:20,y:20),b:Point2D(x:100,y:20)),oppositeEdge:nil,
            imageSize:setup.frame.pose.imageSize,sourceTime:setup.frame.pose.timestamp)
        #expect(!model.confirmBar(wrong,for:setup))
        #expect(model.bar == nil)
        model.configureCounting(exercise:.dip,side:.left)
        try await wait { model.phase == .paused || model.phase == .failed }
        #expect(model.barRole == .leftDipRail)
        #expect(model.beginBarSetup()?.role == .leftDipRail)
    }

    private func wait(until predicate: () -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(60))
        while !predicate() {
            guard ContinuousClock.now < deadline else { throw FixtureError.failed("Bar setup lifecycle timeout") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}
