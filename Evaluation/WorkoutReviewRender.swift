import Foundation
import AppKit
import SwiftUI
import Vision
import WorkoutCore

/// Render the shipping SwiftUI/Charts view; no screenshot of the user's desktop.
@main struct WorkoutReviewRender {
    @MainActor static func main() throws {
        let input = """
        {"id":"preview","exercise":"pullUp","startedAt":0,"endedAt":3,"endReason":"disengaged","backend":"synthetic render data","modelRevision":0,"schemaVersion":1,"lifecycleVersion":1,"reps":[{"id":"preview:rep:1","setId":"preview","ordinal":1,"exercise":"pullUp","startedAt":0,"extremeAt":1,"acceptedAt":1,"returnedAt":3,"closedAt":3,"closeReason":"returned","outcome":"accepted","outboundSeconds":1,"returnSeconds":2,"left":{"minimum":75,"maximum":170,"mean":125,"observedSeconds":3,"coverage":1},"right":{"minimum":80,"maximum":168,"mean":126,"observedSeconds":3,"coverage":1},"shoulderExcursion":0.15,"bilateralExtremeOffsetSeconds":0.1,"usableDurationSeconds":3,"durationCoverage":1,"samples":[{"seconds":0,"leftDegrees":170,"rightDegrees":168,"shoulderHeight":0.3,"usable":true,"released":false},{"seconds":0.3,"leftDegrees":140,"rightDegrees":142,"shoulderHeight":0.25,"usable":true,"released":false},{"seconds":0.6,"leftDegrees":110,"rightDegrees":115,"shoulderHeight":0.2,"usable":true,"released":false},{"seconds":0.9,"leftDegrees":75,"rightDegrees":80,"shoulderHeight":0.15,"usable":true,"released":false},{"seconds":1.2,"leftDegrees":90,"rightDegrees":95,"shoulderHeight":0.17,"usable":true,"released":false},{"seconds":1.5,"leftDegrees":105,"rightDegrees":110,"shoulderHeight":0.2,"usable":true,"released":false},{"seconds":1.8,"leftDegrees":120,"rightDegrees":125,"shoulderHeight":0.23,"usable":true,"released":false},{"seconds":2.1,"leftDegrees":140,"rightDegrees":145,"shoulderHeight":0.25,"usable":true,"released":false},{"seconds":2.4,"leftDegrees":150,"rightDegrees":152,"shoulderHeight":0.27,"usable":true,"released":false},{"seconds":2.7,"leftDegrees":160,"rightDegrees":162,"shoulderHeight":0.29,"usable":true,"released":false},{"seconds":3,"leftDegrees":170,"rightDegrees":168,"shoulderHeight":0.3,"usable":true,"released":false}],"curveTruncated":false,"countPolicyVersion":8,"metricVersion":1}]}
        """
        NSApplication.shared.setActivationPolicy(.prohibited)
        try FileManager.default.createDirectory(atPath: "build/workout-analysis/render", withIntermediateDirectories: true)
        var fixture = try JSONSerialization.jsonObject(with: Data(input.utf8)) as! [String: Any]
        fixture["trackedAtEpochMilliseconds"] = 1_791_784_800_000 as Int64
        let completed = (fixture["reps"] as! [[String: Any]])[0]
        // Exercise the shipping context-aware library with authored data only.
        let libraryBridge = WorkoutLibraryBridge()
        var library = libraryBridge.emptyJson()
        var libraryRecords: [WorkoutSetRecord] = []
        for index in 0..<3 {
            var row = fixture
            let id = "library-preview-\(index)"
            row["id"] = id
            row["trackedAtEpochMilliseconds"] = Int64(Date().timeIntervalSince1970 * 1000) - Int64(3 - index) * 86_400_000
            var examples: [[String: Any]] = []
            var cursor = 0.0
            for ordinal in 1...3 {
                let duration = index == 1 && ordinal == 3 ? 6.0 : 3.0
                var rep = completed; rep["id"] = id + ":rep:\(ordinal)"; rep["setId"] = id; rep["ordinal"] = ordinal
                rep["startedAt"] = cursor; rep["extremeAt"] = cursor + duration / 3
                rep["acceptedAt"] = cursor + duration / 3; rep["returnedAt"] = cursor + duration; rep["closedAt"] = cursor + duration
                rep["outboundSeconds"] = duration / 3; rep["returnSeconds"] = duration * 2 / 3; rep["usableDurationSeconds"] = duration
                rep["samples"] = (completed["samples"] as! [[String: Any]]).map { point in
                    var value = point; value["seconds"] = cursor + (point["seconds"] as! Double) * duration / 3; return value
                }
                for side in ["left", "right"] { var angle = rep[side] as! [String: Any]; angle["observedSeconds"] = duration; rep[side] = angle }
                examples.append(rep); cursor += duration
            }
            row["reps"] = examples; row["endedAt"] = cursor
            row["activity"] = ["phases": [], "totals": [["phase": "hang", "observedSeconds": 1.0]],
                "longestHangSeconds": 1.0, "longestSupportSeconds": 0.0, "observedSeconds": cursor,
                "elapsedSeconds": cursor, "timelineTruncated": false, "metricVersion": 2] as [String: Any]
            libraryRecords.append(try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: row)))
        }
        library = try libraryBridge.registerJson(stateJson: library, recordsJson: String(decoding: JSONEncoder().encode(libraryRecords), as: UTF8.self))
        for record in libraryRecords {
            let command: [String: Any] = ["action": "review", "setId": record.id, "atEpochMilliseconds": Int64(Date().timeIntervalSince1970 * 1000),
                "correction": ["context": ["assistance": "none", "addedLoadKg": 0, "equipment": "Gym bar", "cameraView": "front"]]]
            library = try libraryBridge.applyJson(stateJson: library, commandJson: String(decoding: JSONSerialization.data(withJSONObject: command), as: UTF8.self))
        }
        let plan: [String: Any] = ["action": "plan", "atEpochMilliseconds": Int64(Date().timeIntervalSince1970 * 1000),
            "plan": ["id": "plan-preview", "title": "Pull-up practice", "scheduledDay": libraryRecords.last!.trackedAtEpochMilliseconds! / 86_400_000,
                "items": [["id": "item-preview", "exercise": "pullUp", "metric": "holdSeconds", "target": 1.0, "sets": 2,
                    "context": ["assistance": "none", "addedLoadKg": 0, "equipment": "Gym bar", "cameraView": "front"]]]]]
        library = try libraryBridge.applyJson(stateJson: library, commandJson: String(decoding: JSONSerialization.data(withJSONObject: plan), as: UTF8.self))
        let dashboardJSON: [String: Any] = ["page": try JSONSerialization.jsonObject(with: Data(libraryBridge.pageJson(stateJson: library, cursorJson: nil, limit: 25).utf8)),
            "insights": try JSONSerialization.jsonObject(with: Data(libraryBridge.insightsJson(stateJson: library, comparisonKey: nil, nowEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000), utcOffsetMinutes: 0).utf8)),
            "mediaPolicy": ["enabled": false, "maximumClips": 20, "maximumBytes": 209715200, "maximumAgeDays": 30], "media": [], "diagnosticsConsent": false, "revision": 5]
        let libraryDashboard = try JSONDecoder().decode(LibraryDashboard.self, from: JSONSerialization.data(withJSONObject: dashboardJSON))
        for section in ["Progress", "History", "Plans", "Storage"] {
            for dark in [true, false] {
                for width in [320.0, 430.0] {
                    let content = WorkoutLibraryView(preview: libraryDashboard, section: section)
                        .environment(\.colorScheme, dark ? .dark : .light).frame(width: width, height: 1100)
                        .background(dark ? Color.black : Color.white)
                    let name = "library-\(section.lowercased())-\(dark ? "dark" : "light")-\(Int(width)).png"
                    let text = try render(content, width: width, height: 1100, filename: name)
                    let expected = ["Progress": "yourpractice", "History": "reviewandcorrect", "Plans": "planyourpractice", "Storage": "yourlocaldata"][section]!
                    verify(text.contains { $0.contains(expected) }, "Workout library section missing: \(section)", filename: name)
                    if section == "Plans" {
                        verify(text.contains { $0.contains("setsmeetthistarget") }, "Saved plan progress is missing", filename: name)
                        verify(text.contains { $0.contains("plannedfor") }, "Saved plan date is missing", filename: name)
                    }
                }
            }
        }
        if let workout = libraryDashboard.insights.overview?.workouts.first, let example = libraryRecords.last {
            for dark in [true, false] {
                for width in [320.0, 430.0] {
                    let setContent = WorkoutReviewView(sets: [example], currentReps: example.reps, storageError: nil,
                        goalsEnabled: false, summary: try journeySummary(example))
                        .environment(\.colorScheme, dark ? .dark : .light).frame(width: width, height: 1000)
                        .background(dark ? Color.black : Color.white)
                    let filename = "journey-set-\(dark ? "dark" : "light")-\(Int(width)).png"
                    let text = try render(setContent, width: width, height: 1000, filename: filename)
                    verify(text.contains { $0.contains("consistent") }, "Set quality must be glanceable", filename: filename)
                    verify(text.contains { $0.contains("yourreps") }, "Rep drill-down must be visible", filename: filename)
                    verify(!text.contains { $0.contains("leftarmtime") || $0.contains("leftmeanrange") }, "Recording diagnostics must not crowd the set summary", filename: filename)
                    let workoutContent = JourneyWorkoutView(workout: workout).environment(\.colorScheme, dark ? .dark : .light)
                        .frame(width: width, height: 1000).background(dark ? Color.black : Color.white)
                    let workoutName = "journey-workout-\(dark ? "dark" : "light")-\(Int(width)).png"
                    let workoutText = try render(workoutContent, width: width, height: 1000, filename: workoutName)
                    verify(workoutText.contains { $0.contains("set1") }, "Workout must lead to its sets", filename: workoutName)
                }
            }
        }
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
        precondition(set.acceptedCount > set.completedCount, "Display regression needs an accepted attempt without an observed return")
        var feedbackLibrary = try libraryBridge.registerJson(stateJson: libraryBridge.emptyJson(), recordsJson: String(decoding: JSONEncoder().encode([set]), as: UTF8.self))
        let correction: [String: Any] = ["action": "review", "setId": set.id, "atEpochMilliseconds": Int64(Date().timeIntervalSince1970 * 1000),
            "correction": ["attempts": [["repId": set.completedReps.first!.id, "classification": "practice"]]]]
        feedbackLibrary = try libraryBridge.applyJson(stateJson: feedbackLibrary, commandJson: String(decoding: JSONSerialization.data(withJSONObject: correction), as: UTF8.self))
        let reviewedFeedback = try JSONDecoder().decode(WorkoutAfterSetReport.self, from: Data(try WorkoutFeedbackBridge().librarySummaryJson(stateJson: feedbackLibrary, setId: set.id).utf8))
        func completedOverlay(_ report: WorkoutAfterSetReport?) -> LiveWorkoutOverlay {
            LiveWorkoutOverlay(session: LiveSetSession(), latestCompletedSet: set, afterSetReport: report,
                cameraReady: true, status: "Ready", statusSymbol: "checkmark", needsAttention: false,
                showsManualStart: false, canStart: true, canSwitchCamera: true, nextCameraName: "front",
                isRecording: false, countdown: nil, canClose: true, onClose: {}, onOptions: {},
                onSwitchCamera: {}, onStart: {}, onFinish: {}, onNextSet: {}, onResults: {})
        }
        verify(completedOverlay(nil).displayedCount == set.completedCount, "Finished overlay must exclude an unrecorded return", filename: "live-count-contract")
        verify(reviewedFeedback.measuredCompletedReps == 0 && completedOverlay(reviewedFeedback).displayedCount == 0,
            "Finished overlay must apply reviewed completion", filename: "live-count-contract")
        var interrupted = partial
        interrupted["closeReason"] = "inferenceFailure"; interrupted["outcome"] = "interrupted"
        for (name, data, expected) in [("completed", completed, "completedcycle"),
            ("return", unfinished, "returnnotrecorded"), ("partial", partial, "partialmovementobserved"),
            ("interrupted", interrupted, "observationinterrupted")] {
            let rep = try JSONDecoder().decode(WorkoutRepRecord.self,from: JSONSerialization.data(withJSONObject: data))
            let assessment = try rep.evidenceAssessment(setEndReason: "manual")
            for dark in [false,true] {
                for width in [320.0,430.0] {
                    let content = WorkoutEvidenceView(assessment: assessment).padding()
                        .environment(\.colorScheme,dark ? .dark : .light)
                        .frame(width: width,height: 560).background(dark ? Color.black : Color.white)
                    let text = try render(content,width: width,height: 560,
                        filename: "evidence-\(name)-\(dark ? "dark" : "light")-\(Int(width)).png")
                    let filename = "evidence-\(name)-\(dark ? "dark" : "light")-\(Int(width)).png"
                    verify(text.contains { $0.contains(expected) },"Attempt evidence title missing",filename: filename)
                    verify(text.contains { $0.contains("leftarmtime") } && text.contains { $0.contains("rightarmtime") },"Independent arm coverage missing",filename: filename)
                    verify(!text.contains { $0.contains("failedrep") },"Tracking failure became a strength judgment",filename: filename)
                }
            }
        }
        for (prefix, records, current) in [("", [set], set.reps),
            ("unfinished-", [emptySet], emptySet.reps), ("history-", [set], emptySet.reps)] {
            for dark in [true, false] {
                for width in [320.0, 430.0] {
                    let content = WorkoutReviewView(sets: records, currentReps: current, storageError: nil,
                        selectedRepId: prefix == "history-" ? set.completedReps.first?.id : nil, goalsEnabled: false,
                        summary: try journeySummary(records.first!))
                        .environment(\.colorScheme, dark ? .dark : .light)
                        .frame(width: width, height: 1000)
                        .background(dark ? Color.black : Color.white)
                    let text = try render(content, width: width, height: 1000,
                        filename: "\(prefix)\(dark ? "dark" : "light")-\(Int(width)).png")
                    precondition(!text.contains { $0.contains("rep2") || $0.contains("rep3") }, "Unfinished reps appeared in analytics")
                    if prefix == "history-" {
                        precondition(text.contains { $0 == "rep1" || $0.contains("rep1") }, "Completed history selection must reveal its details independently of the current timing chart")
                    }
                }
            }
        }
        var gripFixture = fixture
        var gripReps = [completed]
        gripReps[0]["grip"] = ["left":"underhand","right":"underhand","orientation":"underhand","width":"wide",
            "orientationCoverage":0.95,"widthCoverage":0.97,"policyVersion":1,"inferenceBackend":"authored render"] as [String:Any]
        gripFixture["reps"] = gripReps
        let labelledSet = try JSONDecoder().decode(WorkoutSetRecord.self,from: JSONSerialization.data(withJSONObject: gripFixture))
        for dark in [true,false] {
            for width in [320.0,430.0] {
                let content = WorkoutGripView(set: labelledSet,allowsCorrections: false)
                    .environment(\.colorScheme,dark ? .dark : .light)
                    .frame(width: width,height: 350).padding().background(dark ? Color.black : Color.white)
                let text = try render(content,width: width,height: 380,filename: "grip-\(dark ? "dark" : "light")-\(Int(width)).png")
                verify(text.contains { $0.contains("underhand") && $0.contains("wide") },"Grip variation labels missing",filename: "grip")
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
                            currentSet: live ? practiceSet : nil,goalsEnabled: false, summary: try journeySummary(practiceSet))
                            .environment(\.colorScheme,dark ? .dark : .light)
                            .frame(width: width,height: 1100).background(dark ? Color.black : Color.white)
                        let text = try render(content,width: width,height: 1100,
                            filename: "practice-\(exercise.rawValue)-\(live ? "active" : "saved")-\(dark ? "dark" : "light")-\(Int(width)).png")
                        precondition(text.contains { $0.contains("phasebreakdown") },"Phase drill-down is missing")
                        precondition(text.contains { $0.contains("secondsofpractice") },"Hold-only practice must lead with its observed time")
                        precondition(!text.contains { $0.contains("yourreps") || $0.contains("completedrep") },"Hang-only current set borrowed saved rep timing")
                        precondition(text.contains { $0.contains("80s") },"Observed hold time missing")
                    }
                }
            }
        }
        for (name, message, token) in [
            ("clipped", "Move phone back · keep shoulders, elbows and hands in frame", "movephoneback"),
            ("margin", "Move phone back · leave room around arms and hands", "movephoneback"),
            ("angle", "Angle camera so both arms and hands are clear", "anglecamera"),
            ("selected", "Adjust the view so the selected arm and hand are clear", "adjusttheview"),
            ("visibility", "Improve lighting · keep arms and hands clear", "improvelighting"),
            ("visibility-selected", "Improve lighting · keep the selected arm and hand clear", "improvelighting"),
            ("unavailable", "Pose unavailable · retry the camera", "poseunavailable"),
            ("overhead", "Leave room above your hands for the bar", "leaveroom"),
            ("grip-visibility", "Keep both hands and shoulders clearly visible", "keepbothhands"),
            ("acquisition", "Hold your start position · keep the phone steady", "holdyourstart")
        ] {
            for width in [320.0, 430.0] {
                let content = LiveWorkoutOverlay(session: LiveSetSession(), cameraReady: true,
                    status: message, statusSymbol: "viewfinder", needsAttention: true,
                    showsManualStart: false, canStart: false, canSwitchCamera: true, nextCameraName: "front",
                    isRecording: false, countdown: nil, canClose: true,
                    onClose: {}, onOptions: {}, onSwitchCamera: {}, onStart: {}, onFinish: {}, onNextSet: {}, onResults: {})
                    .frame(width: width, height: 760).background(Color(white: 0.18))
                let filename = "framing-\(name)-\(Int(width)).png"
                let text = try render(content, width: width, height: 760, filename: filename)
                verify(text.contains { $0.contains(token) }, "Actionable framing advice is missing", filename: filename)
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

    @MainActor private static func journeySummary(_ record: WorkoutSetRecord) throws -> JourneySetSummary {
        let input = String(decoding: try JSONEncoder().encode(record.progressInput), as: UTF8.self)
        return try JSONDecoder().decode(JourneySetSummary.self, from: Data(WorkoutJourneyBridge().setJson(recordJson: input).utf8))
    }

    /// Optimized preconditions trap without a useful CI message. Print the
    /// failing artifact before retaining the same fatal verification boundary.
    private static func verify(_ condition: Bool, _ message: String, filename: String) {
        guard condition else {
            FileHandle.standardError.write(Data("Render verification failed [\(filename)]: \(message)\n".utf8))
            preconditionFailure(message)
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
        let recognized = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
        let normalized = recognized.map { $0.replacingOccurrences(of: "[^a-zA-Z0-9]", with: "", options: .regularExpression).lowercased() }
        let diagnostics = try JSONSerialization.data(withJSONObject: ["file": filename, "recognized": recognized, "normalized": normalized], options: [.prettyPrinted, .sortedKeys])
        try diagnostics.write(to: URL(fileURLWithPath: "build/workout-analysis/render/\(filename).ocr.json"))
        return normalized
    }
}
