import Foundation
import AppKit
import SwiftUI
import Vision
import WorkoutCore

/// Render the shipping SwiftUI/Charts view; no screenshot of the user's desktop.
@main struct WorkoutReviewRender {
    @MainActor static func renderWorkoutLog() throws {
        var log = LoggedWorkout()
        let base = Int64(Date().addingTimeInterval(-3600).timeIntervalSince1970 * 1000)
        log.epochMilliseconds = base
        let bench = [4, 5, 5, 5, 4, 5], curls = [6, 7, 7, 7, 6, 7]
        for index in 0..<6 {
            for (name, reps, load) in [("Bench press", bench[index], 165.0), ("EZ curls", curls[index], 75.9)] {
                let start = base + Int64(log.sets.count) * 270_000
                log.sets.append(.init(exercise: name, reps: reps, load: load, method: "tap",
                    startedAtEpochMilliseconds: start, endedAtEpochMilliseconds: start + 30_000))
            }
        }
        for dark in [false, true] {
            for width in [320.0, 393.0] {
                let view = NavigationStack { WorkoutLogView(initial: log, preview: true) }
                    .environment(\.colorScheme, dark ? .dark : .light)
                _ = try render(view, width: width, height: 852, filename: "workout-log-\(dark ? "dark" : "light")-\(Int(width)).png")
            }
        }
        for dark in [false, true] {
            let scheme: ColorScheme = dark ? .dark : .light
            let picker = NavigationStack { WorkoutExercisePicker(names: ["Bench press", "EZ curls"]) { _ in } }.environment(\.colorScheme, scheme)
            _ = try render(picker, width: 393, height: 852, filename: "workout-log-picker-\(dark ? "dark" : "light").png")
            let editor = NavigationStack { WorkoutLoggedSetEditor(set: log.sets[0], onSave: { _ in }, onDelete: {}) }
                .frame(width: 393, height: 852).background(HangPalette(scheme).background).environment(\.colorScheme, scheme)
            _ = try render(editor, width: 393, height: 852, filename: "workout-log-editor-\(dark ? "dark" : "light").png")
            let counter = NavigationStack { WorkoutTapCounter(set: log.sets[0], onFinish: { _ in }) }.environment(\.colorScheme, scheme)
            _ = try render(counter, width: 393, height: 852, filename: "workout-tap-counter-\(dark ? "dark" : "light").png")
        }
        let accessible = NavigationStack { WorkoutLogView(initial: log, preview: true) }
            .environment(\.colorScheme, .light).environment(\.dynamicTypeSize, .accessibility3)
        _ = try render(accessible, width: 393, height: 1100, filename: "workout-log-large-text.png")
        let table = ScrollView { VStack { WorkoutLogTable(log: log) { _ in } }.padding(20) }
            .background(HangPalette(.light).background).environment(\.colorScheme, .light)
        _ = try render(table, width: 393, height: 1000, filename: "workout-log-table.png")
        let counter = NavigationStack { WorkoutTapCounter(set: log.sets[0], onFinish: { _ in }) }.environment(\.colorScheme, .light)
        _ = try render(counter, width: 393, height: 852, filename: "workout-tap-counter.png")
        var older = log; older.id = "older"; older.epochMilliseconds -= 7 * 86400000
        let progress = NavigationStack { WorkoutLogProgressView(history: [log, older]) }.environment(\.colorScheme, .light)
        _ = try render(progress, width: 393, height: 852, filename: "workout-log-progress.png")
        var state = try JSONSerialization.jsonObject(with: Data(WorkoutLibraryBridge().emptyJson().utf8)) as! [String: Any]
        state["workoutLogs"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode([log, older]))
        let stateJSON = String(decoding: try JSONSerialization.data(withJSONObject: state), as: UTF8.self)
        let summaryJSON = try WorkoutLibraryBridge().insightsJson(stateJson: stateJSON, comparisonKey: nil,
            nowEpochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000), utcOffsetMinutes: Int32(TimeZone.current.secondsFromGMT() / 60))
        let overview = try JSONDecoder().decode(LibraryInsights.self, from: Data(summaryJSON.utf8)).overview!
        let calendar = NavigationStack { ScrollView { WorkoutJourneyView(overview: overview, calendarPreview: overview.days).padding(20) } }
            .background(HangPalette(.light).background).environment(\.colorScheme, .light)
        _ = try render(calendar, width: 393, height: 1000, filename: "workout-log-calendar.png")
    }
    @MainActor static func renderCalendar() throws {
        let date = Calendar.current.date(from: DateComponents(year: 2024, month: 2, day: 29))!
        for mode in WorkoutCalendar.Mode.allCases {
            let days = WorkoutCalendar.days(containing: date, mode: mode)
            let activity = days.enumerated().map { index, day in
                JourneyPracticeDay(day: day.day, workoutIds: index % 3 == 0 ? ["fixture-\(index)"] : [],
                    completedReps: index % 3 == 0 ? 10 : 0, quality: nil, loggedReps: 0)
            }
            for dark in [false, true] {
                for width in [320.0, 393.0] {
                    let scheme: ColorScheme = dark ? .dark : .light
                    let view = ScrollView {
                        WorkoutCalendarView(date: .constant(date), mode: .constant(mode),
                            selected: WorkoutCalendar.day(containing: date), activity: activity,
                            onSelect: { _ in }, onShowAll: {}).padding(20)
                    }.frame(width: width, height: 650).background(HangPalette(scheme).background)
                        .foregroundStyle(HangPalette(scheme).foreground).environment(\.colorScheme, scheme)
                    _ = try render(view, width: width, height: 650,
                        filename: "calendar-\(mode.rawValue.lowercased())-\(dark ? "dark" : "light")-\(Int(width)).png")
                }
            }
        }
        let bridge = WorkoutLibraryBridge()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        var state = try JSONSerialization.jsonObject(with: Data(bridge.emptyJson().utf8)) as! [String: Any]
        let log = LoggedWorkout(epochMilliseconds: now - 3_600_000,
            sets: [.init(exercise: "Bench press", reps: 5, load: 165), .init(exercise: "EZ curls", reps: 7, load: 75.9)])
        state["workoutLogs"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode([log]))
        let stateJSON = String(decoding: try JSONSerialization.data(withJSONObject: state), as: UTF8.self)
        let insights = try bridge.insightsJson(stateJson: stateJSON, comparisonKey: nil,
            nowEpochMilliseconds: now, utcOffsetMinutes: Int32(TimeZone.current.secondsFromGMT() / 60))
        let envelope: [String: Any] = ["page": ["sets": [], "revision": 0],
            "insights": try JSONSerialization.jsonObject(with: Data(insights.utf8)),
            "revision": 0, "media": [], "diagnosticsConsent": false,
            "mediaPolicy": ["enabled": true, "maximumClips": 20, "maximumBytes": 209715200, "maximumAgeDays": 30]]
        let dashboard = try JSONDecoder().decode(LibraryDashboard.self, from: JSONSerialization.data(withJSONObject: envelope))
        for dark in [false, true] {
            for section in ["Progress", "History"] {
                let scheme: ColorScheme = dark ? .dark : .light
                let view = NavigationStack { WorkoutLibraryView(preview: dashboard, section: section) }
                    .frame(width: 393, height: 1000).background(HangPalette(scheme).background).environment(\.colorScheme, scheme)
                _ = try render(view, width: 393, height: 1000,
                    filename: "calendar-route-\(section.lowercased())-\(dark ? "dark" : "light").png")
            }
        }
    }
    @MainActor static func main() throws {
        let input = """
        {"id":"preview","exercise":"pullUp","startedAt":0,"endedAt":3,"endReason":"disengaged","backend":"synthetic render data","modelRevision":0,"schemaVersion":1,"lifecycleVersion":1,"reps":[{"id":"preview:rep:1","setId":"preview","ordinal":1,"exercise":"pullUp","startedAt":0,"extremeAt":1,"acceptedAt":1,"returnedAt":3,"closedAt":3,"closeReason":"returned","outcome":"accepted","outboundSeconds":1,"returnSeconds":2,"left":{"minimum":75,"maximum":170,"mean":125,"observedSeconds":3,"coverage":1},"right":{"minimum":80,"maximum":168,"mean":126,"observedSeconds":3,"coverage":1},"shoulderExcursion":0.15,"bilateralExtremeOffsetSeconds":0.1,"usableDurationSeconds":3,"durationCoverage":1,"samples":[{"seconds":0,"leftDegrees":170,"rightDegrees":168,"shoulderHeight":0.3,"usable":true,"released":false},{"seconds":0.3,"leftDegrees":140,"rightDegrees":142,"shoulderHeight":0.25,"usable":true,"released":false},{"seconds":0.6,"leftDegrees":110,"rightDegrees":115,"shoulderHeight":0.2,"usable":true,"released":false},{"seconds":0.9,"leftDegrees":75,"rightDegrees":80,"shoulderHeight":0.15,"usable":true,"released":false},{"seconds":1.2,"leftDegrees":90,"rightDegrees":95,"shoulderHeight":0.17,"usable":true,"released":false},{"seconds":1.5,"leftDegrees":105,"rightDegrees":110,"shoulderHeight":0.2,"usable":true,"released":false},{"seconds":1.8,"leftDegrees":120,"rightDegrees":125,"shoulderHeight":0.23,"usable":true,"released":false},{"seconds":2.1,"leftDegrees":140,"rightDegrees":145,"shoulderHeight":0.25,"usable":true,"released":false},{"seconds":2.4,"leftDegrees":150,"rightDegrees":152,"shoulderHeight":0.27,"usable":true,"released":false},{"seconds":2.7,"leftDegrees":160,"rightDegrees":162,"shoulderHeight":0.29,"usable":true,"released":false},{"seconds":3,"leftDegrees":170,"rightDegrees":168,"shoulderHeight":0.3,"usable":true,"released":false}],"curveTruncated":false,"countPolicyVersion":8,"metricVersion":1}]}
        """
        NSApplication.shared.setActivationPolicy(.prohibited)
        try FileManager.default.createDirectory(atPath: "build/workout-analysis/render", withIntermediateDirectories: true)
        if let flag = CommandLine.arguments.firstIndex(of: "--pose-bands"), CommandLine.arguments.count > flag+1 {
            struct Preview: Decodable { let id: String; let title: String; let imagePath: String; let pose: PoseResult; let progress: Double }
            let previews = try JSONDecoder().decode([Preview].self,
                from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[flag+1])))
            for preview in previews {
                let image = NSImage(contentsOfFile: preview.imagePath)!
                let view = ZStack(alignment: .topLeading) {
                    Color.black
                    Image(nsImage: image).resizable().scaledToFit()
                    PoseOverlay(result: preview.pose, usesBothArms: true, progress: preview.progress)
                    Text(preview.title + " · Overlay preview").font(.headline).foregroundStyle(.white)
                        .padding(12).background(.black.opacity(0.6), in: Capsule()).padding(16)
                }.frame(width: preview.pose.imageSize.width > preview.pose.imageSize.height ? 760 : 393,
                        height: preview.pose.imageSize.width > preview.pose.imageSize.height ? 430 : 760)
                _ = try render(view, width: preview.pose.imageSize.width > preview.pose.imageSize.height ? 760 : 393,
                    height: preview.pose.imageSize.width > preview.pose.imageSize.height ? 430 : 760,
                    filename: "pose-bands-\(preview.id).png")
            }
            return
        }
        if let flag = CommandLine.arguments.firstIndex(of: "--pushup-record"), CommandLine.arguments.count > flag+1 {
            let records = try JSONDecoder().decode([WorkoutSetRecord].self,
                from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[flag+1])))
            let record = records.first { $0.exercise == .pushUp && $0.completedCount > 0 }!
            let json = String(decoding: try JSONEncoder().encode(record.progressInput), as: UTF8.self)
            let summary = try JSONDecoder().decode(JourneySetSummary.self,
                from: Data(WorkoutJourneyBridge().setJson(recordJson: json).utf8))
            for dark in [false, true] {
                let scheme: ColorScheme = dark ? .dark : .light
                let view = NavigationStack {
                    WorkoutReviewView(sets: [record], currentReps: [], storageError: nil,
                        goalsEnabled: false, showsLibraryLink: false, summary: summary)
                }.frame(width: 393, height: 1000).background(HangPalette(scheme).background)
                    .environment(\.colorScheme, scheme)
                let name = "pushup-review-\(dark ? "dark" : "light").png"
                let text = try render(view, width: 393, height: 1000, filename: name).joined()
                verify(text.contains("pushup") && text.contains("preview") && text.contains("reprhythm"),
                    "Push-up timing review missing", filename: name)
                verify(!text.contains("effortestimate") && !text.contains("muscle"),
                    "Unvalidated push-up effort shown", filename: name)
            }
            return
        }
        if CommandLine.arguments.contains("--calendar-only") { try renderCalendar(); return }
        if CommandLine.arguments.contains("--workout-log-only") { try renderWorkoutLog(); return }
        if CommandLine.arguments.contains("--set-visuals-only") { try renderSetVisuals(input); return }
        if CommandLine.arguments.contains("--coach-only") { try renderCoach(); return }
        if CommandLine.arguments.contains("--target-meter-only") { try renderTargetMeters(); return }
        if CommandLine.arguments.contains("--history-refresh-only") { try renderHistory(); return }
        if CommandLine.arguments.contains("--compact-live-only") {
            let preview = ProcessInfo.processInfo.environment["COMPACT_CAMERA_PREVIEW"].flatMap { NSImage(contentsOfFile: $0) }
            for (width, height) in [(320.0,640.0), (393.0,852.0), (844.0,390.0)] {
                for state in ["ready", "angle", "arms", "started"] {
                    let running = state == "arms" || state == "started"
                    var session = LiveSetSession()
                    if running { session.start(exercise: .pullUp, side: .left) }
                    let placement = LiveCameraPlacement(tiltDegrees: state == "angle" ? 45 : 0)
                    let checklist = LiveReadinessChecklist(cameraReady: true, frameFresh: true,
                        framing: .init(state: .ready, usesBothArms: true), exerciseTitle: "Pull-ups", armDescription: "Both measurable",
                        barConfirmed: false, acquisition: .lookingForGrip, motionAvailable: true, phoneStable: true,
                        sceneStable: true, startingPose: true, paused: false, handsFree: true, setupBusy: false,
                        bodyCameraCalibrated: true, placement: placement, pullUpMotion: true)
                    let content = LiveWorkoutOverlay(session: session, liveCompletedReps: running ? 4 : 0,
                        showsSetStarted: state == "started", needsArmRepositioning: state == "arms",
                        gripCaptureFeedback: .init(state: "showHands", shoulderFraction: 0.24, torsoFraction: 0.38),
                        readiness: checklist, cameraPlacement: placement,
                        poseOverlayVisibility: .constant(state == "started"),
                        cameraReady: true, status: "Ready", statusSymbol: "viewfinder", needsAttention: false,
                        showsManualStart: false, canStart: true, canSwitchCamera: !running, nextCameraName: "front",
                        isRecording: false, countdown: nil, canClose: true,
                        onClose: {}, onOptions: {}, onSwitchCamera: {}, onStart: {}, onFinish: {}, onNextSet: {}, onResults: {})
                        .frame(width: width, height: height)
                        .background { ZStack {
                            Color.black
                            if let preview { Image(nsImage: preview).resizable().scaledToFit() }
                        } }
                    let filename = "compact-live-\(state)-\(Int(width)).png"
                    let text = try render(content, width: width, height: height, filename: filename).joined()
                    let expected = state == "ready" ? "ready" : state == "angle" ? "levelphone" : state == "arms" ? "showbotharms" : "setstarted"
                    verify(text.contains(expected), "Compact status missing", filename: filename)
                    verify(!text.contains("startandrepsdontwaitforgrip") && !text.contains("cameratilt"),
                        "Verbose diagnostics cover the preview", filename: filename)
                }
            }
            return
        }
        if CommandLine.arguments.contains("--body-dip-live-only") {
            for width in [320.0, 430.0] {
                var idle = LiveSetSession(); idle.reset(exercise: .dip, side: .left)
                let checklist = LiveReadinessChecklist(cameraReady:true,frameFresh:true,
                    framing:.init(state:.ready,usesBothArms:true),exerciseTitle:"Dips",armDescription:"Both measurable",
                    barConfirmed:false,acquisition:.lookingForGrip,motionAvailable:true,phoneStable:true,sceneStable:true,
                    startingPose:true,paused:false,handsFree:true,setupBusy:false,bodyCameraCalibrated:true)
                let waiting = LiveWorkoutOverlay(session:idle, liveCompletedReps: 0,readiness:checklist,cameraReady:true,
                    status:"Ready",statusSymbol:"checkmark.circle.fill",needsAttention:false,
                    showsManualStart:false,canStart:true,canSwitchCamera:true,nextCameraName:"front",isRecording:false,
                    countdown:nil,canClose:true,onClose:{},onOptions:{},onSwitchCamera:{},onStart:{},onFinish:{},onNextSet:{},onResults:{})
                    .frame(width:width,height:760).background(Color(white:0.18))
                let idleName = "body-dip-v35-ready-\(Int(width)).png"
                let readyText = try render(waiting,width:width,height:760,filename:idleName).joined()
                verify(readyText.contains("readytostart") && readyText.contains("lowertobegin"),"Rail-free start instruction missing",filename:idleName)
                for paused in [false,true] {
                    let payload: [String:Any] = ["motion":["seconds":5,"phase":paused ? "seekingTop":"returning",
                        "cycles":4,"partialAttempts":0,"interruptedAttempts":0,"reason":paused ? "bodyUnavailable":"tracking",
                        "observationUsable":!paused,"sourceAccepted":true,"progress":paused ? 0:0.65,
                        "elbowDegrees":paused ? []:[125.0,126.0],"travelArmFractions":[],"wristDriftArmFractions":[],"policyVersion":1],
                        "started":true,"analysisRevision":1,"analyzedFrames":100,"usableFrames":paused ? 99:100]
                    let snapshot = try JSONDecoder().decode(BodyDipWorkoutSnapshot.self,from:JSONSerialization.data(withJSONObject:payload))
                    var running = LiveSetSession(); running.beginBodyDip(snapshot,side:.left,startedAt:0)
                    let content = LiveWorkoutOverlay(session:running, liveCompletedReps: 0,cameraReady:true,status:"Tracking",statusSymbol:"viewfinder",needsAttention:paused,
                        showsManualStart:false,canStart:false,canSwitchCamera:false,nextCameraName:"front",isRecording:false,
                        countdown:nil,canClose:true,onClose:{},onOptions:{},onSwitchCamera:{},onStart:{},onFinish:{},onNextSet:{},onResults:{})
                        .frame(width:width,height:760).background(Color(white:0.18))
                    let name = "body-dip-v35-\(paused ? "paused":"tracking")-\(Int(width)).png"
                    let text = try render(content,width:width,height:760,filename:name).joined()
                    verify(text.contains("finishset") && !text.contains("settherail"),"Body-dip controls or guidance incorrect",filename:name)
                    verify(text.contains(paused ? "showbotharmsandhandsclearly":"pushbacktoyourtopsupport"),"Body-motion cue missing",filename:name)
                }
            }
            return
        }
        if CommandLine.arguments.contains("--set-grip-only") {
            var fixture = try JSONSerialization.jsonObject(with: Data(input.utf8)) as! [String: Any]
            let id = "set-grip-v31-preview"; fixture["id"] = id; fixture["endedAt"] = 6
            let base = (fixture["reps"] as! [[String: Any]])[0]
            fixture["reps"] = (0...1).map { index in
                var rep = base; rep["setId"] = id; rep["id"] = "\(id):\(index)"; rep["ordinal"] = index + 1
                let offset = Double(index * 3)
                for key in ["startedAt", "extremeAt", "acceptedAt", "returnedAt", "closedAt"] {
                    if let time = rep[key] as? Double { rep[key] = time + offset }
                }
                rep["samples"] = []
                rep["grip"] = ["left": index == 0 ? "unknown" : "underhand", "right": index == 0 ? "unknown" : "underhand",
                    "orientation": index == 0 ? "unknown" : "underhand", "orientationCoverage": index == 0 ? 0.0 : 0.95,
                    "width": "shoulderWidth", "projectedSpanRatio": 1.0, "widthCoverage": 0.95, "policyVersion": 4] as [String: Any]
                return rep
            }
            fixture["setGrip"] = ["setId":id,"orientation":"underhand","firstConfirmedAt":5.5,"lastConfirmedAt":5.8,
                "modelId":"synthetic render","inferenceBackend":"synthetic render","reason":"confirmedWithinSet","policyVersion":1] as [String: Any]
            let set = try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: fixture))
            for width in [320.0, 430.0] {
                let content = WorkoutGripView(set: set, allowsCorrections: false, initiallyExpanded: true)
                    .padding(16).frame(width: width, height: 1450, alignment: .topLeading).background(Color.white)
                    .environment(\.colorScheme, .light)
                let name = "set-grip-v31-\(Int(width)).png"
                let text = try render(content, width: width, height: 1450, filename: name).joined()
                verify(text.contains("setgrip") && text.contains("underhand"), "Set-level grip missing", filename: name)
                verify(text.contains("gripinferredfromthisset"), "Inherited label provenance missing", filename: name)
                verify(text.contains("classifiedfor0ofthisattempt"), "Original missing-hand coverage must stay visible", filename: name)
            }
            return
        }
        if CommandLine.arguments.contains("--arm-visibility-only") {
            for width in [320.0, 430.0] {
                var session = LiveSetSession(); session.start(exercise: .pullUp, side: .left)
                let content = LiveWorkoutOverlay(session: session, liveCompletedReps: 0, gripLabel: "Underhand · Narrow",
                    needsArmRepositioning: true,
                    gripCaptureFeedback: .init(state: "handsDetected", leftHand: "detected", rightHand: "detected"),
                    cameraReady: true, status: "Tracking", statusSymbol: "viewfinder", needsAttention: true,
                    showsManualStart: false, canStart: false, canSwitchCamera: false, nextCameraName: "front",
                    isRecording: false, countdown: nil, canClose: true,
                    onClose: {}, onOptions: {}, onSwitchCamera: {}, onStart: {}, onFinish: {}, onNextSet: {}, onResults: {})
                    .frame(width: width, height: 760).background(Color(white: 0.18))
                let filename = "arm-visibility-\(Int(width)).png"
                let text = try render(content, width: width, height: 760, filename: filename).joined()
                verify(text.contains("showyourarms") && text.contains("movecamera"), "Arm guidance missing", filename: filename)
                verify(text.contains("checkview"), "Header must agree with arm guidance", filename: filename)
                verify(!text.contains("reptrackingcancontinue") && !text.contains("estimated"),
                    "Grip advice must not obscure arm failure", filename: filename)
                verify(text.contains("finishset"), "Finish control missing", filename: filename)
            }
            return
        }
        if CommandLine.arguments.contains("--grip-width-only") {
            var fixture = try JSONSerialization.jsonObject(with: Data(input.utf8)) as! [String: Any]
            fixture["id"] = "grip-width-v20-preview"
            let base = (fixture["reps"] as! [[String: Any]])[0]
            fixture["reps"] = [("narrow", 0.7, "overhand"), ("shoulderWidth", 1.0, "underhand"), ("wide", 1.6, "unknown")].enumerated().map { index, item in
                var rep = base
                rep["setId"] = "grip-width-v20-preview"
                rep["id"] = "grip-width-v20-preview:\(index)"; rep["ordinal"] = index + 1
                rep["grip"] = ["left": item.2, "right": item.2, "orientation": item.2, "width": item.0,
                    "projectedSpanRatio": item.1, "orientationCoverage": item.2 == "unknown" ? 0.0 : 0.95,
                    "widthCoverage": 0.97, "widthPolicyVersion": 1, "policyVersion": 4] as [String: Any]
                return rep
            }
            let set = try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: fixture))
            for width in [320.0, 430.0] {
                let detail = WorkoutGripView(set: set, allowsCorrections: false, initiallyExpanded: true)
                    .padding(16).frame(width: width, height: 940, alignment: .topLeading).background(Color.white)
                    .environment(\.colorScheme, .light)
                let detailName = "grip-width-v20-review-\(Int(width)).png"
                let detailText = try render(detail, width: width, height: 940, filename: detailName).joined()
                verify(detailText.contains("medium") && detailText.contains("shoulders") && detailText.contains("97"),
                    "Width ratios and per-attempt coverage missing", filename: detailName)
                var session = LiveSetSession(); session.start(exercise: .pullUp, side: .left)
                let live = LiveWorkoutOverlay(session: session, liveCompletedReps: 0, gripLabel: "Underhand · Medium · 1.0× shoulders",
                    cameraReady: true, status: "Tracking", statusSymbol: "figure.strengthtraining.traditional", needsAttention: false,
                    showsManualStart: false, canStart: false, canSwitchCamera: false, nextCameraName: "front",
                    isRecording: false, countdown: nil, canClose: true,
                    onClose: {}, onOptions: {}, onSwitchCamera: {}, onStart: {}, onFinish: {}, onNextSet: {}, onResults: {})
                    .frame(width: width, height: 760).background(Color(white: 0.18))
                let liveName = "grip-width-v20-live-\(Int(width)).png"
                let liveText = try render(live, width: width, height: 760, filename: liveName).joined()
                verify(liveText.contains("medium") && liveText.contains("shoulders"), "Large live width label missing", filename: liveName)
            }
            return
        }
        if CommandLine.arguments.contains("--grip-capture-only") {
            let cases: [(String, WorkoutGripCaptureFeedback)] = [
                ("far", .init(state: "moveCloser", shoulderFraction: 0.12, torsoFraction: 0.22)),
                ("hidden", .init(state: "showHands", shoulderFraction: 0.24, torsoFraction: 0.38, leftHand: "detected", rightHand: "notDetected", observationAge: 0.2, bodyContext: "frameMatched")),
                ("seen", .init(state: "handsDetected", shoulderFraction: 0.24, torsoFraction: 0.38, leftHand: "detected", rightHand: "detected", observationAge: 0.1, bodyContext: "frameMatched")),
                ("edge", .init(state: "handsClipped", shoulderFraction: 0.32, torsoFraction: 0.46, leftHand: "edge", rightHand: "detected", observationAge: 0.2, bodyContext: "frameMatched")),
                ("view", .init(state: "frontRear", shoulderFraction: 0.12, torsoFraction: 0.38, bodyContext: "viewUnsupported"))]
            for (name, feedback) in cases {
                for running in [false, true] {
                    var session = LiveSetSession()
                    if running { session.start(exercise: .pullUp, side: .left) }
                    let readiness = LiveReadinessChecklist(cameraReady: true, frameFresh: true,
                        framing: LiveFramingAssessment(state: .ready, usesBothArms: true),
                        exerciseTitle: "Pull-ups", armDescription: "Both measurable", barConfirmed: true,
                        acquisition: .locked, motionAvailable: true, phoneStable: true,
                        sceneStable: true, startingPose: true, paused: false, handsFree: true, setupBusy: false)
                    for width in [320.0, 430.0] {
                        let content = LiveWorkoutOverlay(session: session, liveCompletedReps: 0, gripCaptureFeedback: feedback,
                            readiness: running ? nil : readiness,
                            cameraReady: true, status: "Hold a hang", statusSymbol: "viewfinder", needsAttention: false,
                            showsManualStart: false, canStart: true, canSwitchCamera: !running, nextCameraName: "front",
                            isRecording: false, countdown: nil, canClose: true,
                            onClose: {}, onOptions: {}, onSwitchCamera: {}, onStart: {}, onFinish: {}, onNextSet: {}, onResults: {})
                            .frame(width: width, height: 760).background(Color(white: 0.18))
                        let filename = "grip-capture-\(name)-\(running ? "running" : "idle")-\(Int(width)).png"
                        let text = try render(content, width: width, height: 760, filename: filename).joined()
                        verify(!text.contains("sizetarget") && !text.contains("torso"),
                            "Detailed grip text must stay out of the camera overlay", filename: filename)
                        if running { verify(text.contains("gripunknownoptional"), "Compact optional metadata missing", filename: filename) }
                        verify(text.contains(running ? "tracking" : "ready"),
                            "Workout status must remain visible beside optional grip guidance", filename: filename)
                    }
                }
            }
            let detail = LiveGripCaptureCard(feedback: cases[1].1, initiallyExpanded: true)
                .frame(width: 320).padding(16).background(Color(white: 0.18))
            let text = try render(detail, width: 352, height: 620, filename: "grip-capture-diagnostics.png").joined()
            verify(text.contains("sizetarget") && text.contains("handanalysisage"),
                "Expanded capture diagnostics missing", filename: "grip-capture-diagnostics.png")
            return
        }
        if CommandLine.arguments.contains("--grip-view-only") {
            for running in [false,true] {
                var session = LiveSetSession()
                if running { session.start(exercise: .pullUp,side: .left) }
                for width in [320.0,430.0] {
                    let content = LiveWorkoutOverlay(session: session, liveCompletedReps: 0,
                        gripViewPrompt: WorkoutGripViewFeedback.repositionPrompt,
                        cameraReady: true,status: "Hold a hang",statusSymbol: "viewfinder",needsAttention: false,
                        showsManualStart: false,canStart: true,canSwitchCamera: !running,nextCameraName: "front",
                        isRecording: false,countdown: nil,canClose: true,
                        onClose: {},onOptions: {},onSwitchCamera: {},onStart: {},onFinish: {},onNextSet: {},onResults: {})
                        .frame(width:width,height:760).background(Color(white:0.18))
                    let filename="grip-view-\(running ? "running" : "idle")-\(Int(width)).png"
                    let text=try render(content,width:width,height:760,filename:filename)
                    precondition(!text.contains { $0.contains("movecamera") },"Optional grip must not interrupt the camera preview")
                    if running { precondition(text.contains { $0.contains("gripunknownoptional") },"Optional grip indicator is missing") }
                }
            }
            return
        }
        if CommandLine.arguments.contains("--readiness-only") {
            for (name, fresh, bar, motion, starting, acquisition) in [
                ("rails", true, false, true, false, AutomaticPullUpBar.State.findingEdge),
                ("ambiguous", true, false, true, false, .ambiguous),
                ("stale", false, true, true, true, .locked),
                ("motion", true, false, false, false, .lookingForGrip),
                ("posture", true, true, true, false, .locked),
                ("ready", true, true, true, true, .locked)
            ] {
                let checklist = LiveReadinessChecklist(cameraReady: true, frameFresh: fresh,
                    framing: LiveFramingAssessment(state: .ready, usesBothArms: true),
                    exerciseTitle: "Dips", armDescription: "Both measurable", barConfirmed: bar,
                    acquisition: acquisition, motionAvailable: motion, phoneStable: true,
                    sceneStable: true, startingPose: starting, paused: false, handsFree: true, setupBusy: false)
                for width in [320.0, 393.0] {
                    for expanded in [false, true] {
                    let content = LiveWorkoutOverlay(session: LiveSetSession(), liveCompletedReps: 0, exerciseTitle: "Dips detected",
                        readiness: checklist,  cameraReady: true, status: "Tracking starts automatically",
                        statusSymbol: "viewfinder", needsAttention: !checklist.allPassed,
                        showsManualStart: false, canStart: checklist.allPassed, canSwitchCamera: true,
                        nextCameraName: "front", isRecording: false, countdown: nil, canClose: true,
                        onClose: {}, onOptions: {}, onSwitchCamera: {}, onStart: {}, onFinish: {}, onNextSet: {}, onResults: {})
                        .frame(width: width, height: 760).background(Color(white: 0.18))
                    let filename = "readiness-\(expanded ? "details" : "distance")-\(name)-\(Int(width)).png"
                    let text: [String]
                    if expanded {
                        text = try render(ScrollView { LiveReadinessChecklistView(checklist: checklist, initiallyExpanded: true).padding() }
                            .frame(width: width, height: 760).background(Color(white: 0.18)), width: width, height: 760, filename: filename)
                        verify(text.contains { $0.contains("cameraposefeed") }, "Detailed checks are missing", filename: filename)
                    } else {
                        text = try render(content, width: width, height: 760, filename: filename)
                        let title = name == "ready" ? "READY" : name == "posture" ? "HOLD START POSE" : checklist.actionTitle
                        let action = title.replacingOccurrences(of: "[^a-zA-Z0-9]", with: "", options: .regularExpression).lowercased()
                        verify(text.joined().contains(action), "Compact readiness action is missing", filename: filename)
                    }
                    if name == "stale" {
                        verify(!text.contains { $0.contains("referenceset") || $0.contains("qualified") },
                            "Stale frames display retained passes", filename: filename)
                    }
                    }
                }
            }
            return
        }
        if CommandLine.arguments.contains("--dip-progress-only") {
            for (name, phase, availability, fraction, cue, guidance) in [
                ("ready", "ready", "ready", 0.0, "lower", nil as String?),
                ("lowering", "outbound", "available", 0.3, "lower", nil),
                ("returning", "returning", "available", 0.8, "pressup", nil),
                ("missing-hands-legacy", "returning", "observationUnavailable", 0.0, "paused", nil),
                ("missing-hands", "seekingTop", "cameraUnavailable", 0.0, "paused", "Hand tracking unavailable · show both grips clearly"),
                ("missing-rails", "seekingTop", "cameraUnavailable", 0.0, "paused", "Looking for dip rails · keep grips and rails visible"),
                ("camera-check", "seekingTop", "cameraUnavailable", 0.0, "paused", "Camera stability unavailable · keep the phone steady"),
                ("missing-elbows", "seekingTop", "waitingForMeasuredTop", 0.0, "getready", "Keep shoulders, elbows and hands in frame"),
                ("edge-advice", "outbound", "available", 0.3, "lower", "Move phone back · leave room around arms and hands")
            ] {
                let motion = DipWorkoutSnapshot.Motion(phase: phase, imageCycles: 2, partialAttempts: 0,
                    interruptedAttempts: 0, availability: availability, progress: fraction, event: nil, policyVersion: 1)
                let snapshot = DipWorkoutSnapshot(motion: motion, analysisRevision: 1, analyzedFrames: 10, usableFrames: 10,
                    endReason: nil, observationGuidance: guidance)
                for width in [320.0, 430.0] {
                    let filename = "bilateral-dip-\(name)-\(Int(width)).png"
                    let content = BilateralDipProgressView(snapshot: snapshot)
                        .frame(width: width, height: 240).background(Color(white: 0.18))
                    let text = try render(content, width: width, height: 240, filename: filename)
                    verify(text.contains(cue), "Bilateral dip progress action is missing", filename: filename)
                    if name == "edge-advice" {
                        verify(text.contains("target") && text.contains("start"), "Framing advice hid the movement gauge", filename: filename)
                    }
                }
            }
            return
        }
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
        let journeyOnly = CommandLine.arguments.contains("--journey-only")
        for section in journeyOnly ? ["Progress"] : ["Progress", "History", "Plans", "Storage"] {
            for dark in [true, false] {
                for width in [320.0, 430.0] {
                    let content = NavigationStack { WorkoutLibraryView(preview: libraryDashboard, section: section) }
                        .environment(\.colorScheme, dark ? .dark : .light).frame(width: width, height: 1100)
                        .background(dark ? Color.black : Color.white)
                    let name = "library-\(section.lowercased())-\(dark ? "dark" : "light")-\(Int(width)).png"
                    let text = try render(content, width: width, height: 1100, filename: name)
                    let expected = ["Progress": "calendar", "History": "recentworkouts", "Plans": "planyourpractice", "Storage": "yourlocaldata"][section]!
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
                    let setSummary = try journeySummary(example)
                    let setContent = NavigationStack { WorkoutReviewView(sets: [example], currentReps: example.reps, storageError: nil,
                        goalsEnabled: false, summary: setSummary) }
                        .environment(\.colorScheme, dark ? .dark : .light).frame(width: width, height: 2000)
                        .background(dark ? Color.black : Color.white)
                    let filename = "journey-set-\(dark ? "dark" : "light")-\(Int(width)).png"
                    let text = try render(setContent, width: width, height: 2000, filename: filename)
                    verify(text.contains { $0.contains("consistent") }, "Set quality must be glanceable", filename: filename)
                    verify(text.contains { $0.contains("yourreps") }, "Rep drill-down must be visible", filename: filename)
                    verify(!text.contains { $0.contains("leftarmtime") || $0.contains("leftmeanrange") }, "Recording diagnostics must not crowd the set summary", filename: filename)
                    let workoutContent = NavigationStack { JourneyWorkoutView(workout: workout, refreshesFromStore: false) }.environment(\.colorScheme, dark ? .dark : .light)
                        .frame(width: width, height: 2000).background(dark ? Color.black : Color.white)
                    let workoutName = "journey-workout-\(dark ? "dark" : "light")-\(Int(width)).png"
                    let workoutText = try render(workoutContent, width: width, height: 2000, filename: workoutName)
                    verify(workoutText.contains { $0.contains("set1") }, "Workout must lead to its sets", filename: workoutName)
                }
            }
        }
        if journeyOnly { return }
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
            LiveWorkoutOverlay(session: LiveSetSession(), liveCompletedReps: 0, latestCompletedSet: set, afterSetReport: report,
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
                        .frame(width: width, height: 2000)
                        .background(dark ? Color.black : Color.white)
                    let text = try render(content, width: width, height: 2000,
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
        for exercise in ExerciseCounter.Exercise.barExercises {
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
                            .frame(width: width,height: 2000).background(dark ? Color.black : Color.white)
                        let text = try render(content,width: width,height: 2000,
                            filename: "practice-\(exercise.rawValue)-\(live ? "active" : "saved")-\(dark ? "dark" : "light")-\(Int(width)).png")
                        precondition(text.contains { $0.contains("phasebreakdown") },"Phase drill-down is missing")
                        verify(text.contains { $0.contains(exercise == .pullUp ? "longesthang" : "supporthold") },
                            "Hold-only practice must lead with its observed time", filename: "practice-\(exercise.rawValue)")
                        precondition(!text.contains { $0.contains("yourreps") || $0.contains("completedrep") },"Hang-only current set borrowed saved rep timing")
                        verify(text.contains { $0 == "80s" || $0 == "805" }, "Observed hold time missing", filename: "practice-\(exercise.rawValue)")
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
                let content = LiveWorkoutOverlay(session: LiveSetSession(), liveCompletedReps: 0, cameraReady: true,
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
        for exercise in ExerciseCounter.Exercise.barExercises {
            var running = LiveSetSession(); running.start(exercise: exercise, side: .left)
            var ended = running; ended.finish()
            var reacquiring = running; reacquiring.interrupt(reason: "sourceTimeGap")
            for (name, session, cue) in [("idle", LiveSetSession(), true), ("started", running, true),
                ("tracking", running, false), ("reacquiring", reacquiring, false), ("finished", ended, true)] {
                for width in [320.0, 430.0] {
                    let content = LiveWorkoutOverlay(session: session, liveCompletedReps: 0, showsSetStarted: cue,
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

    @MainActor private static func renderTargetMeters() throws {
        for dip in [false, true] {
            let phases: [(String, Double, Bool, Bool)] = [("Start", 0, false, true), ("Moving", 0.55, false, true),
                ("Target met", 1, true, true), ("Returning", 0.35, true, true), ("Tracking lost", 0, false, false)]
            var panels: [AnyView] = []
            for (label, value, reached, usable) in phases {
                var motion: [String: Any] = ["seconds": 2.0, "phase": label == "Start" ? "ready" : reached ? "returning" : "outbound", "cycles": 0,
                    "reason": "authored-preview", "observationUsable": usable, "sourceAccepted": true,
                    "progress": 0.3, "partialAttempts": 0, "interruptedAttempts": 0, "policyVersion": 1,
                    "targetProgress": value, "targetReached": reached]
                if dip { motion.merge(["elbowDegrees": [110,110], "travelArmFractions": [0.1,0.1], "wristDriftArmFractions": [0,0]]) { _,new in new } }
                else { motion.merge(["degrees": [110,110], "travel": [0.1,0.1], "drift": [0,0]]) { _,new in new } }
                let bytes = try JSONSerialization.data(withJSONObject: ["motion": motion, "started": false,
                    "analysisRevision": 1, "analyzedFrames": 30, "usableFrames": 30])
                let preparedDip = dip ? try JSONDecoder().decode(BodyDipWorkoutSnapshot.self, from: bytes) : nil
                let preparedPull = dip ? nil : try JSONDecoder().decode(BodyPullUpWorkoutSnapshot.self, from: bytes)
                let gauge: AnyView
                if let preparedDip { gauge = AnyView(BodyDipProgressView(snapshot: preparedDip)) }
                else { gauge = AnyView(BodyPullUpProgressView(snapshot: preparedPull!)) }
                if label == "Start" || label == "Moving" {
                    for (width,height) in [(320.0,640.0),(844.0,390.0)] {
                        let checklist = LiveReadinessChecklist(cameraReady: true, frameFresh: true,
                            framing: .init(state: .ready, usesBothArms: true), exerciseTitle: dip ? "Dips" : "Pull-ups",
                            armDescription: "Both measurable", barConfirmed: false, acquisition: .lookingForGrip,
                            motionAvailable: true, phoneStable: true, sceneStable: true, startingPose: label == "Start",
                            paused: false, handsFree: true, setupBusy: false, bodyCameraCalibrated: true,
                            placement: .init(tiltDegrees: 0), pullUpMotion: !dip)
                        let overlay = LiveWorkoutOverlay(session: LiveSetSession(), liveCompletedReps: 0,
                            preparedPullUp: preparedPull, preparedDip: preparedDip, exerciseTitle: dip ? "Dips" : "Pull-ups",
                            readiness: checklist, cameraReady: true, status: "Ready", statusSymbol: "viewfinder",
                            needsAttention: false, showsManualStart: false, canStart: label == "Start", canSwitchCamera: true,
                            nextCameraName: "front", isRecording: false, countdown: nil, canClose: true,
                            onClose: {}, onOptions: {}, onSwitchCamera: {}, onStart: {}, onFinish: {}, onNextSet: {}, onResults: {})
                            .frame(width: width,height: height).background(Color(white: 0.18))
                        _ = try render(overlay,width: width,height: height,
                            filename: "first-rep-\(dip ? "dip" : "pull-up")-\(label.lowercased())-\(Int(width)).png")
                    }
                }
                panels.append(AnyView(VStack { Text(label).font(.caption.bold()).foregroundStyle(.white); gauge }.frame(width: 136)))
            }
            let view = HStack(alignment: .top, spacing: 12) { ForEach(panels.indices, id: \.self) { panels[$0] } }
                .padding(20).background(Color(white: 0.17))
            _ = try render(view, width: 780, height: 285, filename: "target-meter-\(dip ? "dip" : "pull-up").png")
        }
    }

    @MainActor private static func renderCoach() throws {
        let name = "coaching-render-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let preferences = WorkoutCoachPreferences(defaults: defaults)
        var profile = WorkoutCoachProfile(); profile.goal = .moreReps
        try preferences.save(profile)
        let history = [1, 2].flatMap { day in (0..<2).map { ordinal in
            var context = LibraryContext(); context.effort = 3
            return WorkoutCoachSet(id: "preview-\(day)-\(ordinal)", sourceSetId: "preview-\(day)-\(ordinal)", exercise: .pullUp,
                measuredCompletedReps: 5, trackedAtEpochMilliseconds: Int64(Date().addingTimeInterval(Double(-day * 86400 + ordinal)).timeIntervalSince1970 * 1000),
                comparisonKey: "preview-same-setup", context: context, trackingCoverage: 1, endReason: "manual", elbowExcursionDegrees: 80,
                meanReturnSeconds: 1, exerciseCorrected: false, isMerged: false, countMethod: "overheadPullUpRelativeMotion", countPolicyVersion: 4, gripIdentity: "overhand:wide")
        } }
        for dark in [false, true] {
            for width in [320.0, 430.0] {
                let setup = NavigationStack { WorkoutCoachSetupView(profile: profile, onSave: { _ in }, onSkip: {}) }
                    .environment(\.colorScheme, dark ? .dark : .light)
                _ = try render(setup, width: width, height: 900, filename: "coach-setup-\(dark ? "dark" : "light")-\(Int(width)).png")
                let today = NavigationStack { WorkoutCoachView(preferences: preferences, previewHistory: history, onStart: { _ in }) }
                    .environment(\.colorScheme, dark ? .dark : .light)
                _ = try render(today, width: width, height: 900, filename: "coach-today-\(dark ? "dark" : "light")-\(Int(width)).png")
            }
        }
        let large = WorkoutCoachSetupView(profile: profile, onSave: { _ in }, onSkip: {})
            .environment(\.dynamicTypeSize, .accessibility3)
        _ = try render(large, width: 390, height: 900, filename: "coach-setup-large-text.png")
        let assets = "ios/HangInThere/Assets.xcassets"
        let brand = Image(nsImage: NSImage(contentsOfFile: "\(assets)/BrandIcon.imageset/forest-glow.png")!)
        let pullUp = Image(nsImage: NSImage(contentsOfFile: "\(assets)/PullUpIllustration.imageset/illustration.png")!)
        let dip = Image(nsImage: NSImage(contentsOfFile: "\(assets)/DipIllustration.imageset/illustration.png")!)
        for dark in [false, true] {
            for (width, accessible) in [(320.0, false), (430.0, false), (393.0, true)] {
                let home = ScrollView {
                    HomeScreenView(onLiveWorkout: {}, onStartExercise: { _ in }, onReviewVideo: {}, onHistory: {},
                        onCoach: {}, onLogWorkout: {}, pullUpArtwork: pullUp, dipArtwork: dip, brandArtwork: brand)
                }.environment(\.colorScheme, dark ? .dark : .light)
                    .environment(\.dynamicTypeSize, accessible ? .accessibility3 : .large)
                _ = try render(home, width: width, height: 852,
                    filename: "home-exercise-shortcuts-\(dark ? "dark" : "light")-\(Int(width))\(accessible ? "-large-text" : "").png")
            }
            for width in [320.0, 430.0] {
                let guide = NavigationStack { WorkoutCameraGuideView(onContinue: {}, onCancel: {}) }
                    .environment(\.colorScheme, dark ? .dark : .light)
                _ = try render(guide, width: width, height: 780,
                    filename: "camera-first-workout-\(dark ? "dark" : "light")-\(Int(width)).png")
            }
        }
    }

    @MainActor private static func renderSetVisuals(_ input: String) throws {
        let original = try JSONSerialization.jsonObject(with: Data(input.utf8)) as! [String: Any]
        for exercise in ExerciseCounter.Exercise.barExercises {
            var object = original; object["exercise"] = exercise.rawValue
            let source = (original["reps"] as! [[String: Any]])[0]
            object["reps"] = (0..<5).map { index -> [String: Any] in
                var rep = source
                rep["id"] = "preview-\(index)"; rep["ordinal"] = index + 1
                rep["exercise"] = exercise.rawValue
                rep["startedAt"] = Double(index * 5)
                let lifting = 1 + Double(index) * 0.12
                let turn = Double(index * 5) + (exercise == .pullUp ? lifting : 1.3)
                rep["extremeAt"] = turn
                rep["returnedAt"] = turn + (exercise == .pullUp ? 1.3 : lifting)
                return rep
            }
            var record = try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: object))
            let phases: [WorkoutPhaseSpan] = (0..<5).flatMap { index in
                let start = Double(index * 5)
                return [WorkoutPhaseSpan(id: "hold-\(index)", phase: exercise == .pullUp ? "hang" : "support", startedAt: start, endedAt: start + 1, observedSeconds: 1),
                        WorkoutPhaseSpan(id: "lift-\(index)", phase: exercise == .pullUp ? "pulling" : "lowering", startedAt: start + 1, endedAt: start + 3, observedSeconds: 2),
                        WorkoutPhaseSpan(id: "return-\(index)", phase: exercise == .pullUp ? "lowering" : "rising", startedAt: start + 3, endedAt: start + 5, observedSeconds: index == 2 ? 0 : 2)]
            }
            record.activity = WorkoutActivity(phases: phases, totals: [], longestHangSeconds: 1, longestSupportSeconds: 1,
                                              observedSeconds: 23, elapsedSeconds: 25, timelineTruncated: false, metricVersion: 1)
            for dark in [false, true] {
                for width in [320.0, 430.0] {
                    let view = VStack(alignment: .leading, spacing: 20) {
                        Text("Set review").font(.largeTitle.bold())
                        Text("\(exercise.title) · 5 reps").font(.headline)
                        WorkoutDemandCard(record: record)
                        WorkoutTempoCard(record: record)
                        WorkoutMuscleCard(exercise: exercise)
                    }.padding().frame(width: width, height: 1450, alignment: .top)
                        .background(HangPalette(dark ? .dark : .light).background)
                        .environment(\.colorScheme, dark ? .dark : .light)
                    _ = try render(view, width: width, height: 1450,
                        filename: "set-visuals-\(exercise.rawValue)-\(dark ? "dark" : "light")-\(Int(width)).png")
                }
            }
        }
    }

    /// Presentation fixtures only; no counting/accuracy evidence. Avoid Vision OCR
    /// here so a graphics review cannot stall in the platform's OCR service.
    @MainActor private static func renderHistory() throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let sets = [("demo-dip", ExerciseCounter.Exercise.dip, 5, 0), ("demo-pull", .pullUp, 8, 0), ("demo-practice", .pullUp, 0, 2)].enumerated().map { index, row in
            LibrarySet(id: row.0, sourceSetId: row.0, exercise: row.1, attemptIds: [], measuredCompletedReps: row.2,
                userReportedCompletedReps: 0, practiceAttempts: row.3, holdSeconds: nil, context: LibraryContext(),
                trackedAtEpochMilliseconds: now - Int64(index) * 86_400_000, comparisonKey: nil,
                exerciseCorrected: false, sourceSetIds: [row.0], isMerged: false)
        }
        let focus = LibraryFocus(title: "", message: "", evidenceSetIds: [], recentMedian: nil, sampleSessions: 0, limitation: "")
        let insights = LibraryInsights(overview: nil, sessions: [], comparisonKeys: [], selectedComparisonKey: nil,
            unclassifiedSets: 0, trend: [], weekly: [], practiceDays: 0, consistencyDaysLast28: 0, plateau: nil,
            goal: nil, suggestedGoal: nil, goalBest: nil, goalAchieved: false, milestones: [], focus: focus, plans: [])
        let policy = LibraryMediaPolicy(enabled: true, maximumClips: 20, maximumBytes: 209715200, maximumAgeDays: 30)
        let media = [LibraryMedia(id: "demo-video", setId: sets[0].id, bytes: 2048, attachedAtEpochMilliseconds: now)]
        for empty in [false, true] {
            let dashboard = LibraryDashboard(page: LibraryPage(sets: empty ? [] : sets, nextCursor: nil, revision: 1),
                insights: insights, mediaPolicy: policy, media: empty ? [] : media, diagnosticsConsent: false, revision: 1)
            for dark in [false, true] {
                for width in empty ? [320.0] : [320.0, 430.0] {
                    for section in ["History", "Recordings"] {
                        let view = NavigationStack { WorkoutLibraryView(preview: dashboard, section: section) }
                            .environment(\.colorScheme, dark ? .dark : .light).frame(width: width, height: 1100)
                        _ = try render(view, width: width, height: 1100,
                            filename: "library-\(section.lowercased())-\(empty ? "empty-" : "")\(dark ? "dark" : "light")-\(Int(width)).png")
                    }
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
        if CommandLine.arguments.contains("--calendar-only") || CommandLine.arguments.contains("--workout-log-only") || CommandLine.arguments.contains("--history-refresh-only") || CommandLine.arguments.contains("--set-visuals-only") || CommandLine.arguments.contains("--coach-only") || CommandLine.arguments.contains("--target-meter-only") {
            let path = "build/workout-analysis/render/\(filename)"
            try? FileManager.default.removeItem(atPath: path + ".ocr.json")
            try JSONSerialization.data(withJSONObject: ["file": filename, "review": "Native view pixels for manual visual review; OCR intentionally not run."]).write(to: URL(fileURLWithPath: path + ".visual.json"))
            return []
        }
        let request = VNRecognizeTextRequest()
        request.minimumTextHeight = 0.003
        request.recognitionLevel = .accurate; request.recognitionLanguages = ["en-US"]
        try VNImageRequestHandler(cgImage: image).perform([request])
        let recognized = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
        let normalized = recognized.map { $0.replacingOccurrences(of: "[^a-zA-Z0-9]", with: "", options: .regularExpression).lowercased() }
        let diagnostics = try JSONSerialization.data(withJSONObject: ["file": filename, "recognized": recognized, "normalized": normalized], options: [.prettyPrinted, .sortedKeys])
        try diagnostics.write(to: URL(fileURLWithPath: "build/workout-analysis/render/\(filename).ocr.json"))
        return normalized
    }
}
