import Foundation
import AppKit
import SwiftUI
import Vision

/// Render the shipping SwiftUI/Charts view; no screenshot of the user's desktop.
@main struct WorkoutReviewRender {
    @MainActor static func main() throws {
        let input = """
        {"id":"preview","exercise":"pullUp","startedAt":0,"endedAt":3,"endReason":"disengaged","backend":"synthetic render data","modelRevision":0,"schemaVersion":1,"lifecycleVersion":1,"reps":[{"id":"preview:rep:1","setId":"preview","ordinal":1,"exercise":"pullUp","startedAt":0,"extremeAt":1,"acceptedAt":1,"returnedAt":3,"closedAt":3,"closeReason":"returned","outcome":"accepted","outboundSeconds":1,"returnSeconds":2,"left":{"minimum":75,"maximum":170,"mean":125,"observedSeconds":3,"coverage":1},"right":{"minimum":80,"maximum":168,"mean":126,"observedSeconds":3,"coverage":1},"shoulderExcursion":0.15,"bilateralExtremeOffsetSeconds":0.1,"usableDurationSeconds":3,"durationCoverage":1,"samples":[{"seconds":0,"leftDegrees":170,"rightDegrees":168,"shoulderHeight":0.3,"usable":true,"released":false},{"seconds":0.3,"leftDegrees":140,"rightDegrees":142,"shoulderHeight":0.25,"usable":true,"released":false},{"seconds":0.6,"leftDegrees":110,"rightDegrees":115,"shoulderHeight":0.2,"usable":true,"released":false},{"seconds":0.9,"leftDegrees":75,"rightDegrees":80,"shoulderHeight":0.15,"usable":true,"released":false},{"seconds":1.2,"leftDegrees":90,"rightDegrees":95,"shoulderHeight":0.17,"usable":true,"released":false},{"seconds":1.5,"leftDegrees":105,"rightDegrees":110,"shoulderHeight":0.2,"usable":true,"released":false},{"seconds":1.8,"leftDegrees":120,"rightDegrees":125,"shoulderHeight":0.23,"usable":true,"released":false},{"seconds":2.1,"leftDegrees":140,"rightDegrees":145,"shoulderHeight":0.25,"usable":true,"released":false},{"seconds":2.4,"leftDegrees":150,"rightDegrees":152,"shoulderHeight":0.27,"usable":true,"released":false},{"seconds":2.7,"leftDegrees":160,"rightDegrees":162,"shoulderHeight":0.29,"usable":true,"released":false},{"seconds":3,"leftDegrees":170,"rightDegrees":168,"shoulderHeight":0.3,"usable":true,"released":false}],"curveTruncated":false,"countPolicyVersion":8,"metricVersion":1}]}
        """
        NSApplication.shared.setActivationPolicy(.prohibited)
        var fixture = try JSONSerialization.jsonObject(with: Data(input.utf8)) as! [String: Any]
        fixture["trackedAtEpochMilliseconds"] = 1_791_784_800_000 as Int64
        let completed = (fixture["reps"] as! [[String: Any]])[0]
        var unfinished = completed
        unfinished["id"] = "preview:rep:2"; unfinished["ordinal"] = 2
        unfinished["returnedAt"] = NSNull(); unfinished["returnSeconds"] = NSNull()
        unfinished["closeReason"] = "disengaged"
        var partial = unfinished
        partial["id"] = "preview:rep:3"; partial["ordinal"] = 3
        partial["acceptedAt"] = NSNull(); partial["outcome"] = "partial"
        partial["closeReason"] = "partial"
        fixture["reps"] = [completed, unfinished, partial]
        let set = try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: fixture))
        precondition(set.reps.count == 3 && set.acceptedCount == 2 && set.completedCount == 1)
        fixture["reps"] = [unfinished, partial]
        let emptySet = try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: fixture))
        precondition(emptySet.completedReps.isEmpty)
        try FileManager.default.createDirectory(atPath: "build/workout-analysis/render", withIntermediateDirectories: true)
        for (prefix, records, current) in [("", [set], set.reps),
            ("unfinished-", [emptySet], emptySet.reps), ("history-", [set], emptySet.reps)] {
            for dark in [true, false] {
                for width in [320.0, 430.0] {
                    let content = WorkoutReviewView(sets: records, currentReps: current, storageError: nil,
                        selectedRepId: prefix == "history-" ? set.completedReps.first?.id : nil, goalsEnabled: false)
                        .environment(\.colorScheme, dark ? .dark : .light)
                        .frame(width: width, height: 1000)
                        .background(dark ? Color.black : Color.white)
                    let text = try render(content, width: width, height: 1000,
                        filename: "\(prefix)\(dark ? "dark" : "light")-\(Int(width)).png")
                    precondition(!text.contains { $0.contains("rep2") || $0.contains("rep3") }, "Unfinished reps appeared in analytics")
                    if prefix == "history-" {
                        precondition(text.contains { $0.contains("rep1accepted") }, "Completed history selection must reveal its details independently of the current timing chart")
                    }
                }
            }
        }
        // Beginner analytics must remain useful with zero completed reps.
        for exercise in ExerciseCounter.Exercise.allCases {
            let phase = exercise == .pullUp ? "hang" : "support"
            var practiceFixture = fixture
            practiceFixture["exercise"] = exercise.rawValue
            practiceFixture["reps"] = []
            practiceFixture["endedAt"] = 8.0
            practiceFixture["activity"] = ["phases": [["id":"preview:phase:1","phase":phase,"startedAt":0,"endedAt":8,"observedSeconds":8]],
                "totals":[["phase":phase,"observedSeconds":8]],"longestHangSeconds":exercise == .pullUp ? 8 : 0,
                "longestSupportSeconds":exercise == .dip ? 8 : 0,"observedSeconds":8,"elapsedSeconds":8,
                "timelineTruncated":false,"metricVersion":1] as [String:Any]
            let practiceSet = try JSONDecoder().decode(WorkoutSetRecord.self,from: JSONSerialization.data(withJSONObject: practiceFixture))
            precondition(practiceSet.hasPractice && practiceSet.completedCount == 0)
            for live in [false,true] {
                for dark in [true,false] {
                    for width in [320.0,430.0] {
                        let content = WorkoutReviewView(sets: live ? [set] : [practiceSet],currentReps: [],storageError: nil,
                            currentSet: live ? practiceSet : nil,goalsEnabled: false)
                            .environment(\.colorScheme,dark ? .dark : .light)
                            .frame(width: width,height: 1100).background(dark ? Color.black : Color.white)
                        let text = try render(content,width: width,height: 1100,
                            filename: "practice-\(exercise.rawValue)-\(live ? "active" : "saved")-\(dark ? "dark" : "light")-\(Int(width)).png")
                        precondition(text.contains { $0.contains("setphases") },"Beginner set phases missing")
                        precondition(text.contains { $0.contains("0completedreps") },"Zero-rep practice borrowed another set's reps")
                        precondition(!text.contains { $0.contains("reptiming") || $0.contains("rep1accepted") },"Hang-only current set borrowed saved rep timing")
                        precondition(text.contains { $0.contains("80s") },"Observed hold time missing")
                    }
                }
            }
        }
        for exercise in ExerciseCounter.Exercise.allCases {
            var running = LiveSetSession(); running.start(exercise: exercise, side: .left)
            var ended = running; ended.finish()
            var reacquiring = running; reacquiring.interrupt(reason: "sourceTimeGap")
            for (name, session, cue) in [("idle", LiveSetSession(), true), ("started", running, true),
                ("tracking", running, false), ("reacquiring", reacquiring, false), ("finished", ended, true)] {
                for width in [320.0, 430.0] {
                    let content = LiveWorkoutOverlay(session: session, showsSetStarted: cue,
                        cameraReady: true, status: "Hold a hang or top support", statusSymbol: "figure.strengthtraining.traditional",
                        needsAttention: false, showsManualStart: false, canStart: true, canSwitchCamera: true,
                        nextCameraName: "front", isRecording: false, countdown: nil, canClose: true,
                        onClose: {}, onOptions: {}, onSwitchCamera: {}, onStart: {}, onFinish: {}, onNextSet: {}, onResults: {})
                        .frame(width: width, height: 760).background(Color(white: 0.18))
                    let text = try render(content, width: width, height: 760,
                        filename: "live-\(exercise.rawValue)-\(name)-\(Int(width)).png")
                    precondition(text.contains { $0.contains("setstarted") } == (name == "started"), "Live start cue does not match the actual running set")
                    // Match the header badge, rather than the separate progress
                    // guidance (which can correctly say "Tracking paused").
                    precondition(text.contains("tracking") == (session.phase == .running && session.trackingIssue == nil), "Persistent live tracking state is unavailable or stale")
                    precondition(text.contains("paused") == (name == "reacquiring"), "Counter interruption is not reflected in the live indicator")
                }
            }
        }
    }

    @MainActor private static func render<V: View>(_ content: V, width: Double, height: Double, filename: String) throws -> [String] {
        let host = NSHostingView(rootView: content)
        let window = NSWindow(contentRect: NSRect(x: -4000, y: -4000, width: width, height: height),
            styleMask: [.borderless], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false; window.contentView = host; window.orderFront(nil)
        defer { window.close() }
        host.layoutSubtreeIfNeeded()
        RunLoop.current.run(until: Date().addingTimeInterval(0.3))
        guard let bitmap = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { fatalError("View rendering failed") }
        host.cacheDisplay(in: host.bounds, to: bitmap)
        guard let image = bitmap.cgImage, let data = bitmap.representation(using: .png, properties: [:]) else { fatalError("View pixels unavailable") }
        try data.write(to: URL(fileURLWithPath: "build/workout-analysis/render/\(filename)"))
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate; request.recognitionLanguages = ["en-US"]
        try VNImageRequestHandler(cgImage: image).perform([request])
        return (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
            .map { $0.replacingOccurrences(of: "[^a-zA-Z0-9]", with: "", options: .regularExpression).lowercased() }
    }
}
