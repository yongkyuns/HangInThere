import AVFoundation
import CryptoKit
import Foundation

/// Native component development replay: real decoder, Apple body inference and
/// background registration, production identity/analysis/presentation owners.
/// Serially awaits registration; does not reproduce iOS callback scheduling.
/// The phone attitude is an explicit fixed-camera assumption, not sensor data.
@main enum BodyDipCameraReplay {
    @MainActor static func main() async throws {
        guard CommandLine.arguments.count == 3 else { throw ReplayError.noFrames }
        let source = URL(fileURLWithPath:CommandLine.arguments[1])
        let output = URL(fileURLWithPath:CommandLine.arguments[2])
        guard !FileManager.default.fileExists(atPath:output.path) else { throw CocoaError(.fileWriteFileExists) }
        let videoHash = try digest(source)
        let asset = AVURLAsset(url:source)
        guard let track = try await asset.loadTracks(withMediaType:.video).first else { throw ReplayError.noFrames }
        let inventory = try AVAssetReader(asset:asset)
        let samples = AVAssetReaderTrackOutput(track:track,outputSettings:[kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32BGRA])
        inventory.add(samples)
        guard inventory.startReading() else { throw ReplayError.noFrames }
        var expected:[PresentationTime] = []
        while let sample = samples.copyNextSampleBuffer() {
            let time = CMSampleBufferGetPresentationTimeStamp(sample)
            expected.append(.init(value:time.value,timescale:time.timescale))
        }
        guard inventory.status == .completed, !expected.isEmpty else { throw ReplayError.noFrames }

        let reader = VideoReplayReader()
        _ = try await reader.open(source)
        let registration = VisionStaticSceneRegistrationWorker(includeDiagnosticHomography:true)
        let analysis = WorkoutAnalysisAdapter()
        var identity = AutomaticExerciseDetection()
        var session = LiveSetSession()
        var reference:StaticSceneRegistrationReference?
        var lastRegistration:Double?
        var scene = StaticSceneStability()
        var phone = PhoneOrientationStability()
        let attitude = PhoneOrientationStability.Quaternion(x:0,y:0,z:0,w:1)!
        var seen:[PresentationTime] = []
        var records:[WorkoutSetRecord] = []
        var rows:[[String:Any]] = []
        let encoder = JSONEncoder()
        func save(_ reason:String) {
            session.finish(reason:LiveSetSession.EndReason(rawValue:reason) ?? .setupInvalidated)
            if let record = analysis.finish(reason:reason), record.hasPractice { records.append(record) }
        }
        while let frame = try await reader.nextFrame() {
            let pose = frame.pose, seconds = frame.pose.timestamp.seconds
            seen.append(pose.timestamp)
            if session.phase == .finished, analysis.shouldResume(reason:session.endReason) {
                session.prepareNextSet(); identity.reset(); analysis.resetBodyDipPreparation()
                reference = nil; lastRegistration = nil; scene.reset(); phone.reset()
            }
            if session.phase == .idle { _ = identity.observe(pose) }
            var measurement:StaticSceneRegistrationMeasurement?
            var snapshot:BodyDipWorkoutSnapshot?
            if identity.exercise == .dip, session.phase != .finished {
                if reference == nil, PullUpArmMeasurement(pose:pose).arms.count == 2,
                   let fresh = VisionStaticSceneRegistrationWorker.makeReference(image:frame.image),
                   fresh.imageSize == pose.imageSize,
                   phone.calibrate(attitude,timestamp:seconds), scene.calibrate(imageSize:fresh.imageSize) {
                    reference = fresh; lastRegistration = nil; analysis.resetBodyDipPreparation()
                }
                if let fixed = reference, lastRegistration.map({ seconds-$0 >= 0.33 }) ?? true {
                    lastRegistration = seconds
                    do {
                        let measured = try await registration.measure(reference:fixed,image:frame.image)
                        measurement = measured
                        scene.observe(translations:measured.translations,globalScaleFraction:measured.globalScaleFraction,timestamp:seconds)
                    } catch { scene.observe(translations:[],globalScaleFraction:nil,timestamp:seconds) }
                }
                if scene.state == .moved {
                    if session.phase == .running { save(scene.movementKind == .scale ? "sceneScaled":"sceneShifted") }
                    reference = nil; lastRegistration = nil; scene.reset(); phone.reset()
                    if session.phase == .idle { analysis.resetBodyDipPreparation() }
                } else {
                    _ = phone.observe(attitude,timestamp:seconds)
                    let qualified = reference != nil && phone.state.allowsLiveSet && scene.isReady(at:seconds)
                    snapshot = analysis.observeBodyDip(pose,camera:qualified ? "ready":"unavailable",automaticStart:true,recordCalendarTime:false)
                    if let state = snapshot {
                        if session.phase == .idle, state.started, qualified, identity.lock() {
                            session.beginBodyDip(state,side:.left,startedAt:analysis.currentRecord?.startedAt)
                        } else if session.phase == .running { session.consumeBodyDip(state) }
                        if session.phase == .running, let reason = state.endReason { save(reason) }
                    }
                }
            }
            var row:[String:Any] = ["seconds":seconds,"sourceValue":pose.timestamp.value,"sourceTimescale":pose.timestamp.timescale,
                "exercise":identity.exercise?.rawValue ?? "unknown","sessionPhase":session.phase.rawValue,
                "count":session.observedMovements,"scene":scene.state.rawValue,"sceneReady":scene.isReady(at:seconds),
                "hasCameraReference":reference != nil,"pose":try JSONSerialization.jsonObject(with:encoder.encode(pose))]
            if let state = snapshot { row["bodyDip"] = try JSONSerialization.jsonObject(with:encoder.encode(state)) }
            if let measurement {
                row["registration"] = ["translations":measurement.translations.map { [$0.dxPixels,$0.dyPixels] },
                    "scaleFraction":measurement.globalScaleFraction as Any? ?? NSNull(),
                    "homography":measurement.diagnosticHomography as Any? ?? NSNull()]
            }
            rows.append(row)
            if seen.count % 150 == 0 { print("Processed \(seen.count) frames; \(session.observedMovements) cycles; \(scene.state.rawValue)"); fflush(stdout) }
        }
        await reader.close()
        if session.phase == .running { save("sourceEnded") }
        guard seen == expected, try digest(source) == videoHash else { throw ReplayError.invalidTime }
        let report:[String:Any] = ["scope":"Exposed development video through native Mac components. Real body inference and image registration; authored fixed-phone attitude, serial registration, no iOS sensor/scheduling or production-accuracy qualification.",
            "videoSHA256":videoHash,"sourceClockVerified":true,"frames":seen.count,"completedCycles":records.reduce(0) { $0+$1.completedCount },
            "records":try records.map { try JSONSerialization.jsonObject(with:encoder.encode($0)) },"rows":rows]
        try JSONSerialization.data(withJSONObject:report,options:[.sortedKeys]).write(to:output,options:.withoutOverwriting)
        print("Native camera component replay: \(records.reduce(0) { $0+$1.completedCount }) cycles in \(records.count) sets; \(seen.count) verified frames")
    }
    static func digest(_ url:URL) throws -> String {
        let file = try FileHandle(forReadingFrom:url); defer { try? file.close() }
        var hash = SHA256()
        while let data = try file.read(upToCount:1024*1024), !data.isEmpty { hash.update(data:data) }
        return hash.finalize().map { String(format:"%02x",$0) }.joined()
    }
}
