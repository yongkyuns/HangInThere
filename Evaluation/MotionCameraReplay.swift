import AVFoundation
import CryptoKit
import Foundation
import WorkoutCore

/// Apple-host native full-source comparison. Uses the production decoder,
/// Vision body estimator, background sampler and iOS motion/analysis adapter.
/// Fixed recording-camera attitude is an explicit assumption, not phone sensor evidence.
@main enum MotionCameraReplay {
    @MainActor static func main() async throws {
        guard CommandLine.arguments.count == 4,
              let exercise = ExerciseCounter.Exercise(rawValue:CommandLine.arguments[3]) else { throw ReplayError.noFrames }
        let source = URL(fileURLWithPath:CommandLine.arguments[1])
        let output = URL(fileURLWithPath:CommandLine.arguments[2])
        try FileManager.default.createDirectory(at:output,withIntermediateDirectories:true)
        let target = output.appendingPathComponent("trace.jsonl")
        guard !FileManager.default.fileExists(atPath:target.path) else { throw CocoaError(.fileWriteFileExists) }
        FileManager.default.createFile(atPath:target.path,contents:nil)
        let trace = try FileHandle(forWritingTo:target); defer { try? trace.close() }
        let videoHash = try digest(source)
        let asset = AVURLAsset(url:source)
        let track = try await asset.loadTracks(withMediaType:.video).first!
        let inventory = try AVAssetReader(asset:asset)
        let samples = AVAssetReaderTrackOutput(track:track,outputSettings:[kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32BGRA])
        inventory.add(samples)
        guard inventory.startReading() else { throw ReplayError.noFrames }
        var expected:[PresentationTime] = []
        while let frame = samples.copyNextSampleBuffer() {
            let time = CMSampleBufferGetPresentationTimeStamp(frame)
            expected.append(.init(value:time.value,timescale:time.timescale))
        }
        guard inventory.status == .completed, !expected.isEmpty else { throw ReplayError.noFrames }
        let reader = VideoReplayReader(); _ = try await reader.open(source)
        let registration = VisionStaticSceneRegistrationWorker()
        let analysis = WorkoutAnalysisAdapter()
        let selection = BodyAthleteSelection()
        let epoch = CameraAcquisitionEvidence()
        var reference:StaticSceneRegistrationReference?
        var lastRegistration:Double?
        var scene = StaticSceneStability()
        var started = false, finished = false
        var records:[WorkoutSetRecord] = []
        var observed:[PresentationTime] = []
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        var generation = 0
        while let frame = try await reader.nextFrame() {
            let pose = frame.pose, seconds = pose.timestamp.seconds
            observed.append(pose.timestamp)
            let decision = try JSONDecoder().decode(BodyAthleteSelectionDecision.self,from:Data(selection.observeJson(poseJson:String(decoding:encoder.encode(pose),as:UTF8.self)).utf8))
            if finished {
                analysis.resetBodyDipPreparation(); analysis.resetBodyPullUpPreparation()
                started = false; finished = false
            }
            let eligible = decision.status == "associated" && PullUpArmMeasurement(pose:pose).arms.count == 2
            let reacquire = reference != nil && epoch.shouldReacquire(sourceValue:pose.timestamp.value,sourceTimescale:pose.timestamp.timescale,
                sceneReady:scene.isReady(at:seconds),idle:!started,eligible:eligible)
            if (reference == nil || reacquire), !started, eligible,
               let fresh = VisionStaticSceneRegistrationWorker.makeReference(image:frame.image) {
                reference = fresh; lastRegistration = nil; _ = scene.calibrate(imageSize:pose.imageSize)
                _ = epoch.beginReference(sourceValue:pose.timestamp.value,sourceTimescale:pose.timestamp.timescale)
                analysis.resetBodyDipPreparation(); analysis.resetBodyPullUpPreparation(); generation += 1
            }
            if let reference, epoch.permitsRegistration(sourceValue:pose.timestamp.value,sourceTimescale:pose.timestamp.timescale),
               lastRegistration.map({seconds-$0 >= AutomaticPullUpBar.samplingInterval}) ?? true {
                lastRegistration = seconds
                do {
                    let measured = try await registration.measure(reference:reference,image:frame.image)
                    scene.observe(translations:measured.translations,globalScaleFraction:measured.globalScaleFraction,timestamp:seconds)
                } catch { scene.observe(translations:[],globalScaleFraction:nil,timestamp:seconds) }
                epoch.observeScene(sourceValue:pose.timestamp.value,sourceTimescale:pose.timestamp.timescale,stationary:scene.isReady(at:seconds))
            }
            let camera = scene.state == .moved ? "moved" : reference != nil && scene.isReady(at:seconds) && epoch.hasIndependentEvidence ? "ready" : "unavailable"
            var state:Data
            var ending:String?
            if exercise == .pullUp {
                let snapshot = analysis.observeOverheadPullUp(pose,selection:selection,camera:camera,automaticStart:true,recordCalendarTime:false)!
                started = snapshot.started; ending = snapshot.endReason; state = try encoder.encode(snapshot)
            } else {
                let snapshot = analysis.observeBodyDip(pose,selection:selection,camera:camera,automaticStart:true,recordCalendarTime:false)!
                started = snapshot.started; ending = snapshot.endReason; state = try encoder.encode(snapshot)
            }
            guard analysis.error == nil else { throw ReplayError.decoding(analysis.error!) }
            if let ending {
                if let record = analysis.finish(reason:ending), record.hasPractice { records.append(record) }
                finished = true
            }
            let row:[String:Any] = ["pose":try JSONSerialization.jsonObject(with:encoder.encode(pose)),"camera":camera,
                "cameraGeneration":generation,"owner":try JSONSerialization.jsonObject(with:state),"sceneState":scene.state.rawValue]
            var data = try JSONSerialization.data(withJSONObject:row,options:[.sortedKeys]);data.append(10);try trace.write(contentsOf:data)
            if camera == "moved" { reference = nil;lastRegistration = nil;scene.reset();epoch.reset() }
            if observed.count%300 == 0 { print("\(observed.count)/\(expected.count) source frames");fflush(stdout) }
        }
        await reader.close()
        if !finished, let record = analysis.finish(reason:"endOfInput"), record.hasPractice { records.append(record) }
        guard observed == expected, try digest(source) == videoHash else { throw ReplayError.invalidTime }
        try encoder.encode(records).write(to:output.appendingPathComponent("records.json"))
        let result:[String:Any] = ["frames":observed.count,"completed":records.reduce(0){$0+$1.completedReps.count},"sets":records.count,
            "exercise":exercise.rawValue,"videoSHA256":videoHash,"sourceClockVerified":true,
            "scope":"Fresh native Mac Apple Vision + production iOS analysis adapter and pixel registration; selected exercise; fixed recorded-camera assumption; not physical iPhone scheduling/qualification"]
        try JSONSerialization.data(withJSONObject:result,options:[.prettyPrinted,.sortedKeys]).write(to:output.appendingPathComponent("result.json"))
        print(String(decoding:try JSONSerialization.data(withJSONObject:result),as:UTF8.self))
    }
    static func digest(_ source:URL) throws -> String {
        let file = try FileHandle(forReadingFrom:source);defer { try? file.close() }
        var hash = SHA256()
        while let data = try file.read(upToCount:1024*1024), !data.isEmpty { hash.update(data:data) }
        return hash.finalize().map{String(format:"%02x",$0)}.joined()
    }
}
