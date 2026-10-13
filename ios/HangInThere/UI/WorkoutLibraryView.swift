import SwiftUI
import Charts
import WorkoutCore
import UniformTypeIdentifiers
import AVKit

struct LibraryContext: Codable, Equatable, Sendable {
    var intent = "ordinary"
    var assistance = "unknown"
    var assistanceLabel = ""
    var addedLoadKg: Double?
    var equipment = ""
    var cameraView = ""
    var variation = ""
    var effort: Int?
    var readiness = "unrecorded"
    var recoveryNote = ""
}
struct LibraryAttemptEdit: Codable, Sendable { var repId: String; var classification: String }
struct LibraryPartition: Codable, Sendable { let id: String; let repIds: [String]; let ownsHold: Bool }
struct LibraryCorrection: Codable, Sendable {
    var exercise: ExerciseCounter.Exercise?
    var attempts: [LibraryAttemptEdit] = []
    var partitions: [LibraryPartition] = []
    var context = LibraryContext()
    var workoutId: String?
}
struct LibrarySet: Decodable, Identifiable, Sendable {
    let id: String; let sourceSetId: String; let exercise: ExerciseCounter.Exercise
    let attemptIds: [String]; let measuredCompletedReps: Int; let userReportedCompletedReps: Int
    let practiceAttempts: Int; let holdSeconds: Double?; let context: LibraryContext
    let trackedAtEpochMilliseconds: Int64?; let comparisonKey: String?; let exerciseCorrected: Bool
    let sourceSetIds: [String]
    let isMerged: Bool
}
struct LibraryCursor: Codable, Sendable { let epochMilliseconds: Int64?; let id: String; let revision: Int64 }
struct LibraryPage: Decodable, Sendable { let sets: [LibrarySet]; let nextCursor: LibraryCursor?; let revision: Int64 }
struct LibraryFocus: Decodable, Sendable { let title: String; let message: String; let evidenceSetIds: [String]; let recentMedian: Double?; let sampleSessions: Int; let limitation: String }
struct LibraryTrend: Decodable, Identifiable, Sendable {
    let workoutId: String; let epochMilliseconds: Int64; let longestHoldSeconds: Double?; let measuredCompletedReps: Int
    let elbowExcursionDegrees: Double?; let meanReturnSeconds: Double?
    var id: String { workoutId }; var date: Date { Date(timeIntervalSince1970: Double(epochMilliseconds) / 1000) }
}
private struct LibraryMetricPoint: Identifiable { let id: Int; let date: Date; let value: Double; let segment: Int }
struct LibraryWeek: Decodable, Identifiable, Sendable { let firstDay: Int64; let days: Int; let workouts: Int; let completedReps: Int; var id: Int64 { firstDay } }
struct LibrarySession: Decodable, Identifiable, Sendable {
    let id: String; let setIds: [String]; let measuredCompletedReps: Int; let userReportedCompletedReps: Int
    let startedAtEpochMilliseconds: Int64?
    let exercises: [ExerciseCounter.Exercise]; let restSeconds: [Double]; let interruptedSets: Int
    let startIntervalsSeconds: [Double]
}
struct LibraryPlanResult: Decodable, Identifiable, Sendable { let planId: String; let title: String; let status: String; let completedItems: Int; let totalItems: Int; let workoutId: String?; let candidateWorkoutIds: [String]; var id: String { planId } }
struct LibraryInsights: Decodable, Sendable {
    let sessions: [LibrarySession]; let comparisonKeys: [String]; let selectedComparisonKey: String?
    let unclassifiedSets: Int; let trend: [LibraryTrend]; let weekly: [LibraryWeek]; let practiceDays: Int
    let consistencyDaysLast28: Int; let plateau: Bool?; let goal: WorkoutGoalRecord?; let suggestedGoal: WorkoutGoalRecord?
    let goalBest: Double?; let goalAchieved: Bool; let milestones: [WorkoutMilestoneRecord]; let focus: LibraryFocus; let plans: [LibraryPlanResult]
}
struct LibraryMedia: Decodable, Identifiable, Sendable { let id: String; let setId: String; let bytes: Int64; let attachedAtEpochMilliseconds: Int64 }
struct LibraryMediaPolicy: Codable, Sendable { var enabled: Bool; var maximumClips: Int; var maximumBytes: Int64; var maximumAgeDays: Int }
struct LibraryDashboard: Decodable, Sendable { let page: LibraryPage; let insights: LibraryInsights; let mediaPolicy: LibraryMediaPolicy; let media: [LibraryMedia]; let diagnosticsConsent: Bool; let revision: Int64 }
struct WorkoutLibraryDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents, data.count <= 32 * 1024 * 1024, let text = String(data: data, encoding: .utf8) else { throw CocoaError(.fileReadCorruptFile) }
        self.text = text
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

/// Review/application actions never reconfigure or pause an active camera.
struct WorkoutLibraryView: View {
    private enum Tab: String, CaseIterable { case progress = "Progress", history = "History", plans = "Plans", storage = "Storage" }
    @State private var tab = Tab.progress
    @State private var dashboard: LibraryDashboard?
    @State private var history: [LibrarySet] = []
    @State private var comparison: String?
    @State private var error: String?
    @State private var editing: WorkoutSetRecord?
    @State private var evidenceRecord: WorkoutSetRecord?
    @State private var correction = LibraryCorrection()
    @State private var document: WorkoutLibraryDocument?
    @State private var exporting = false
    @State private var importing = false
    @State private var importingMedia = false
    @State private var mediaSetId: String?
    @State private var mediaURL: URL?
    @State private var deleting: LibrarySet?
    @State private var goalTarget = ""
    @State private var goalMetric = "holdSeconds"
    @State private var goalDraftKey: String?
    @State private var planMetric = "holdSeconds"
    @State private var planTarget = "5"
    @State private var planSets = 3
    @State private var planExercise = ExerciseCounter.Exercise.pullUp
    @State private var planDate = Date()
    @State private var planContext = LibraryContext()
    @State private var selectedForGrouping: Set<String> = []
    private let previewMode: Bool
    init(preview: LibraryDashboard? = nil, section: String = "Progress") {
        previewMode = preview != nil
        _dashboard = State(initialValue: preview)
        _history = State(initialValue: preview?.page.sets ?? [])
        _comparison = State(initialValue: preview?.insights.selectedComparisonKey)
        _goalDraftKey = State(initialValue: preview?.insights.selectedComparisonKey)
        _goalMetric = State(initialValue: preview?.insights.goal?.metric ?? "holdSeconds")
        _goalTarget = State(initialValue: preview?.insights.goal.map { String($0.target) } ?? "")
        _tab = State(initialValue: Tab(rawValue: section) ?? .progress)
    }
    var body: some View {
        VStack(spacing: 0) {
            Picker("Library section", selection: $tab) { ForEach(Tab.allCases, id: \.self) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented).padding()
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 20) {
                    if let error { Text(error).foregroundStyle(.orange).accessibilityLabel("Library error: \(error)") }
                    if let dashboard {
                        switch tab {
                        case .progress: progress(dashboard.insights)
                        case .history: historyList(dashboard)
                        case .plans: plans(dashboard.insights)
                        case .storage: storage(dashboard)
                        }
                    } else { ProgressView("Loading workout library…") }
                }.padding()
            }
        }.navigationTitle("Workout library")
            .task { if !previewMode { await load() } }
            .sheet(item: $editing) { record in
                NavigationStack {
                    LibraryCorrectionEditor(record: record, correction: correction, errorMessage: error) { next in
                        Task { if await review(record.id, next) { editing = nil } }
                    }
                }
            }
            .sheet(item: $evidenceRecord) { record in
                NavigationStack {
                    WorkoutReviewView(sets: [record], currentReps: [], storageError: nil, showsLibraryLink: false)
                        .navigationTitle("Original measurements")
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { evidenceRecord = nil } } }
                }
            }
            .sheet(isPresented: Binding(get: { mediaURL != nil }, set: { if !$0 { mediaURL = nil } })) {
                if let mediaURL { VideoPlayer(player: AVPlayer(url: mediaURL)).ignoresSafeArea() }
            }
            .fileExporter(isPresented: $exporting, document: document, contentType: .json, defaultFilename: "HangInThere-workouts") { result in
                if case .failure(let failure) = result { error = failure.localizedDescription }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
                Task {
                    do {
                        let url = try result.get(); let scoped = url.startAccessingSecurityScopedResource()
                        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                        try await WorkoutRecordStore.shared.libraryRestore(source: url); await load()
                    } catch { self.error = "Restore failed: \(error.localizedDescription)" }
                }
            }
            .fileImporter(isPresented: $importingMedia, allowedContentTypes: [.movie]) { result in
                Task {
                    do { if let mediaSetId { _ = try await WorkoutRecordStore.shared.attachLibraryMedia(source: result.get(), setId: mediaSetId); await load() } }
                    catch { self.error = "Video attachment failed: \(error.localizedDescription)" }
                }
            }
            .alert("Delete original recordings and their local review videos?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                Button("Delete", role: .destructive) { if let deleting { Task { if await command(["action": "deleteSources", "setIds": deleting.sourceSetIds]) { self.deleting = nil } } } }
                Button("Cancel", role: .cancel) { deleting = nil }
            } message: { Text("This removes \(deleting?.sourceSetIds.count ?? 0) original recording(s), all their splits and their contribution to progress. Backups cannot undo deletion." + (error.map { "\n" + $0 } ?? "")) }
    }
    private func progress(_ report: LibraryInsights) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(report.focus.title).font(.title2.bold())
            Text(report.focus.message)
            Text(report.focus.limitation).font(.caption).foregroundStyle(.secondary)
            Text("Based on \(report.focus.sampleSessions) comparable sessions · \(report.unclassifiedSets) sets need context").font(.caption)
            if let median = report.focus.recentMedian { Text("Recent measured median: \(median.formatted())").font(.caption) }
            if !report.focus.evidenceSetIds.isEmpty {
                DisclosureGroup("Measurements behind this focus") {
                    ForEach(Array(report.focus.evidenceSetIds.enumerated()), id: \.element) { index, id in
                        Button("Review source measurement \(index + 1)") { Task { do { evidenceRecord = try await WorkoutRecordStore.shared.libraryRecord(id: id) } catch { self.error = error.localizedDescription } } }
                    }
                }
            }
            if !report.comparisonKeys.isEmpty {
                Picker("Compare sessions", selection: Binding(get: { comparison ?? report.selectedComparisonKey ?? "" }, set: { value in comparison = value; Task { await load() } })) {
                    ForEach(report.comparisonKeys, id: \.self) { Text(comparisonTitle($0)).tag($0) }
                }
            }
            if !report.trend.isEmpty {
                trendPlot("Comparable hold time", unit: "seconds", points: report.trend, metric: \.longestHoldSeconds)
                Text("Measured completed cycles").font(.headline)
                Chart(report.trend) { point in
                    BarMark(x: .value("Session", point.date, unit: .day), y: .value("Cycles", point.measuredCompletedReps))
                        .accessibilityLabel(point.date.formatted(date: .abbreviated, time: .omitted)).accessibilityValue("\(point.measuredCompletedReps) measured completed cycles")
                }.chartYScale(domain: .automatic(includesZero: true)).frame(minHeight: 160, maxHeight: 240)
                trendPlot("Comparable elbow excursion", unit: "degrees", points: report.trend, metric: \.elbowExcursionDegrees)
                trendPlot("Mean measured return time", unit: "seconds", points: report.trend, metric: \.meanReturnSeconds)
                Text("Gaps indicate unavailable measurements. Angles describe the camera view; they do not verify exercise form.").font(.caption)
            }
            if let key = report.selectedComparisonKey {
                Button("Use this context for future live sets") { Task { await command(["action": "defaultContext", "id": key]) } }
                Text("Assistance, load, equipment and camera setup will carry forward. Review context when these change; effort and readiness remain per set.").font(.caption)
                Button("Clear future workout context") { Task { await command(["action": "clearDefaultContexts"]) } }
                Text("Your chosen goal").font(.headline)
                if let goal = report.goal { Text("\(goal.target.formatted()) \(goal.metric == "holdSeconds" ? "seconds" : "completed cycles") · \(report.goalAchieved ? "Reached" : "In progress")") }
                if let suggestion = report.suggestedGoal {
                    Text("Recent-session suggestion: \(suggestion.target.formatted()) \(suggestion.metric == "holdSeconds" ? "seconds" : "cycles")").font(.caption)
                    Button("Use recent suggestion") { goalTarget = String(suggestion.target); goalMetric = suggestion.metric }
                }
                if let goal = report.goal, let value = report.goalBest {
                    ProgressView(value: min(1, value / goal.target)) { Text("Observed progress: \(value.formatted()) / \(goal.target.formatted())") }
                }
                Picker("Goal metric", selection: $goalMetric) { Text("Hold seconds").tag("holdSeconds"); Text("Completed cycles").tag("completedReps") }
                TextField("Target", text: $goalTarget).textFieldStyle(.roundedBorder)
                Button("Save chosen goal") { Task {
                    guard let parts = try? JSONDecoder().decode([String].self, from: Data(key.utf8)), let name = parts.first, let exercise = ExerciseCounter.Exercise(rawValue: name) else { return }
                    await saveGoal(key: key, exercise: exercise)
                } }.disabled(Double(goalTarget) == nil || comparison != report.selectedComparisonKey)
            }
            Text("\(report.consistencyDaysLast28) practice days in the last 28 days").font(.headline)
            Text("Holds and attempts count as practice. Repeated sets on one day earn one practice day.").font(.caption)
            Chart(report.weekly) { week in
                BarMark(x: .value("Week", Date(timeIntervalSince1970: Double(week.firstDay) * 86400)), y: .value("Practice days", week.days))
                    .accessibilityValue("\(week.days) practice days, \(week.workouts) workouts")
            }.chartYScale(domain: 0...7).frame(minHeight: 150, maxHeight: 220)
            DisclosureGroup("Milestone history") { ForEach(report.milestones.filter(\.achieved)) { item in Text(item.title + (item.achievedAtEpochMilliseconds.map { " · " + Date(timeIntervalSince1970: Double($0) / 1000).formatted(date: .abbreviated, time: .omitted) } ?? " · date unavailable")) } }
        }
    }
    private func historyList(_ dashboard: LibraryDashboard) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Review and correct").font(.title2.bold())
            Text("Corrections retain original measurements. User-reported completions are separate from measured cycles.").font(.caption)
            ForEach(history) { set in
                VStack(alignment: .leading, spacing: 8) {
                    Text(set.exercise.title).font(.headline)
                    Text("\(set.measuredCompletedReps) measured cycles · \(set.userReportedCompletedReps) user-reported · \(set.practiceAttempts) practice attempts")
                    if let hold = set.holdSeconds { Text("Observed hold: \(hold.formatted()) s") }
                    if let time = set.trackedAtEpochMilliseconds { Text(Date(timeIntervalSince1970: Double(time) / 1000).formatted()).font(.caption) }
                    ForEach(set.sourceSetIds, id: \.self) { sourceId in
                        VStack(alignment: .leading, spacing: 8) {
                            if set.sourceSetIds.count > 1 { Text("Original recording · \(sourceId.prefix(8))").font(.subheadline.bold()) }
                            HStack {
                                Button("Measurements") { Task { do { evidenceRecord = try await WorkoutRecordStore.shared.libraryRecord(id: sourceId) } catch { self.error = error.localizedDescription } } }
                                Button("Edit") { Task { await edit(sourceId) } }
                                Button("Undo edit") { Task { await command(["action": "undo", "setId": sourceId]) } }
                            }
                            if dashboard.mediaPolicy.enabled { Button("Attach local review video") { mediaSetId = sourceId; importingMedia = true } }
                            ForEach(dashboard.media.filter { $0.setId == sourceId }) { media in
                                HStack {
                                    Button("Play review video") { Task { do { mediaURL = try await WorkoutRecordStore.shared.libraryMediaURL(id: media.id) } catch { self.error = error.localizedDescription } } }
                                    Button("Delete video", role: .destructive) { Task { await command(["action": "deleteMedia", "id": media.id]) } }
                                }
                            }
                        }
                    }
                    Button(set.sourceSetIds.count > 1 ? "Delete recordings" : "Delete source recording", role: .destructive) { error = nil; deleting = set }
                    if set.isMerged { Text("Merged group · \(set.sourceSetIds.count) original recording(s); source timing remains separate.").font(.caption) }
                    if set.isMerged { Button("Unmerge grouped set") { Task { await command(["action": "unmerge", "id": set.id]) } } }
                    Toggle("Select for grouping or merging", isOn: Binding(get: { selectedForGrouping.contains(set.id) }, set: { if $0 { selectedForGrouping.insert(set.id) } else { selectedForGrouping.remove(set.id) } }))
                }.padding().background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
            }
            if dashboard.page.nextCursor != nil { Button("Load more history") { Task { await load(more: true) } } }
            Button("Group selected sets into a workout") { Task { await groupSelected() } }.disabled(selectedForGrouping.count < 2)
            Button("Merge selected comparable sets") { Task { if await command(["action": "merge", "id": UUID().uuidString, "setIds": selectedForGrouping.sorted()]) { selectedForGrouping = [] } } }.disabled(selectedForGrouping.count < 2)
            DisclosureGroup("Workouts and exercise transitions") {
                ForEach(dashboard.insights.sessions) { session in
                    Text("\(session.exercises.map(\.title).joined(separator: " → ")) · \(session.setIds.count) sets · \(session.measuredCompletedReps) measured cycles")
                    if !session.restSeconds.isEmpty { Text("Between recorded set boundaries: \(session.restSeconds.map { "\(Int($0)) s" }.joined(separator: ", "))").font(.caption) }
                    else if !session.startIntervalsSeconds.isEmpty { Text("Finish clock unavailable. Intervals between starts: \(session.startIntervalsSeconds.map { "\(Int($0)) s" }.joined(separator: ", "))").font(.caption) }
                    if session.interruptedSets > 0 { Text("\(session.interruptedSets) interrupted sets retained").font(.caption) }
                }
            }
        }
    }
    private func trendPlot(_ title: String, unit: String, points: [LibraryTrend], metric: KeyPath<LibraryTrend, Double?>) -> some View {
        var segment = 0
        let values = points.enumerated().compactMap { index, point -> LibraryMetricPoint? in
            guard let value = point[keyPath: metric] else { segment += 1; return nil }
            return LibraryMetricPoint(id: index, date: point.date, value: value, segment: segment)
        }
        return VStack(alignment: .leading) {
            Text(title).font(.headline)
            if values.isEmpty { Text("Measurement unavailable").font(.caption) }
            else {
                Chart(values) { point in
                    LineMark(x: .value("Session", point.date), y: .value(unit, point.value), series: .value("Observed segment", point.segment)).foregroundStyle(.mint)
                    PointMark(x: .value("Session", point.date), y: .value(unit, point.value)).foregroundStyle(.mint)
                        .accessibilityLabel(point.date.formatted(date: .abbreviated, time: .omitted)).accessibilityValue("\(point.value.formatted()) \(unit)")
                }.chartLegend(.hidden).chartYScale(domain: .automatic(includesZero: true)).chartYAxisLabel(unit).frame(minHeight: 180, maxHeight: 240)
            }
        }
    }
    private func plans(_ report: LibraryInsights) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Plan your practice").font(.title2.bold())
            Text("Plans use your chosen targets. Link an observed workout to mark a plan completed; app suggestions are not training prescriptions.").font(.caption)
            ForEach(report.plans) { plan in
                Text("\(plan.title) · \(plan.status) · \(plan.completedItems)/\(plan.totalItems) items")
                if !plan.candidateWorkoutIds.isEmpty {
                    Menu(plan.workoutId == nil ? "Link recorded workout" : "Change linked workout") {
                        ForEach(plan.candidateWorkoutIds, id: \.self) { id in
                            Button(workoutTitle(id, sessions: report.sessions)) { Task { await linkPlan(plan.planId, workoutId: id) } }
                        }
                    }
                } else { Text("No comparable recording on the planned day with sufficient tracking coverage.").font(.caption) }
                if plan.workoutId != nil { Button("Unlink workout") { Task { await linkPlan(plan.planId, workoutId: nil) } } }
                Button("Delete plan", role: .destructive) { Task { await command(["action": "deletePlan", "id": plan.planId]) } }
            }
            DatePicker("Practice date", selection: $planDate, displayedComponents: .date)
            Picker("Exercise", selection: $planExercise) { ForEach(ExerciseCounter.Exercise.allCases, id: \.self) { Text($0.title).tag($0) } }
            Picker("Metric", selection: $planMetric) { Text("Hold seconds").tag("holdSeconds"); Text("Completed cycles").tag("completedReps") }
            TextField("Target per set", text: $planTarget).textFieldStyle(.roundedBorder)
            Stepper("\(planSets) sets", value: $planSets, in: 1...20)
            LibraryContextFields(context: $planContext)
            Button("Save practice plan") { Task { await savePlan() } }.disabled(Double(planTarget) == nil)
        }
    }
    private func storage(_ dashboard: LibraryDashboard) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Your local data").font(.title2.bold())
            Text("Backup contains measured summaries, corrections, goals and plans. Videos and diagnostic consent are excluded.")
            Button("Export backup / analysis") { Task { do { document = WorkoutLibraryDocument(text: try await WorkoutRecordStore.shared.libraryExport()); exporting = true } catch { self.error = error.localizedDescription } } }
            Button("Restore backup") { importing = true }
            Toggle("Keep explicitly attached review videos", isOn: Binding(get: { dashboard.mediaPolicy.enabled }, set: { enabled in Task {
                var policy = dashboard.mediaPolicy; policy.enabled = enabled
                await command(["action": "mediaPolicy", "mediaPolicy": jsonObject(policy)])
            } }))
            Text("Off by default. Up to \(dashboard.mediaPolicy.maximumClips) videos, \(dashboard.mediaPolicy.maximumBytes / 1048576) MB and \(dashboard.mediaPolicy.maximumAgeDays) days. Turning this off deletes retained videos.").font(.caption)
            DisclosureGroup("Video retention limits") {
                Stepper("Keep up to \(dashboard.mediaPolicy.maximumClips) videos", value: Binding(get: { dashboard.mediaPolicy.maximumClips }, set: { value in Task { var policy = dashboard.mediaPolicy; policy.maximumClips = value; await command(["action": "mediaPolicy", "mediaPolicy": jsonObject(policy)]) } }), in: 1...100)
                Stepper("Keep for \(dashboard.mediaPolicy.maximumAgeDays) days", value: Binding(get: { dashboard.mediaPolicy.maximumAgeDays }, set: { value in Task { var policy = dashboard.mediaPolicy; policy.maximumAgeDays = value; await command(["action": "mediaPolicy", "mediaPolicy": jsonObject(policy)]) } }), in: 1...365)
                Stepper("Video budget: \(dashboard.mediaPolicy.maximumBytes / 1048576) MB", value: Binding(get: { Int(dashboard.mediaPolicy.maximumBytes / 1048576) }, set: { value in Task { var policy = dashboard.mediaPolicy; policy.maximumBytes = Int64(value) * 1048576; await command(["action": "mediaPolicy", "mediaPolicy": jsonObject(policy)]) } }), in: 1...1024, step: 10)
            }
            Toggle("Allow content-free diagnostic export", isOn: Binding(get: { dashboard.diagnosticsConsent }, set: { enabled in Task { await command(["action": "diagnosticsConsent", "consent": enabled]) } }))
            if dashboard.diagnosticsConsent { Button("Export diagnostic summary") { Task { do { document = WorkoutLibraryDocument(text: try await WorkoutRecordStore.shared.libraryDiagnostics()); exporting = true } catch { self.error = error.localizedDescription } } } }
            Text("No upload runs automatically. Backup and video export do not grant training consent.").font(.caption)
        }
    }
    private func load(more: Bool = false) async {
        do {
            let cursor = more ? try dashboard?.page.nextCursor.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) } : nil
            let json = try await WorkoutRecordStore.shared.libraryDashboard(key: comparison, cursor: cursor, now: Int64(Date().timeIntervalSince1970 * 1000), offset: Int32(TimeZone.current.secondsFromGMT() / 60))
            let next = try JSONDecoder().decode(LibraryDashboard.self, from: Data(json.utf8))
            if goalDraftKey != next.insights.selectedComparisonKey {
                goalDraftKey = next.insights.selectedComparisonKey
                goalMetric = next.insights.goal?.metric ?? "holdSeconds"
                goalTarget = next.insights.goal.map { String($0.target) } ?? ""
            }
            dashboard = next; comparison = next.insights.selectedComparisonKey; history = more ? history + next.page.sets : next.page.sets; error = nil
        } catch { self.error = "Library could not be loaded: \(error.localizedDescription)" }
    }
    @discardableResult private func command(_ values: [String: Any]) async -> Bool {
        do {
            var values = values; values["atEpochMilliseconds"] = Int64(Date().timeIntervalSince1970 * 1000)
            let json = String(decoding: try JSONSerialization.data(withJSONObject: values), as: UTF8.self)
            _ = try await WorkoutRecordStore.shared.libraryCommand(json); await load(); return true
        } catch { self.error = "Change could not be saved: \(error.localizedDescription)"; return false }
    }
    private func edit(_ id: String) async {
        do {
            if let json = try await WorkoutRecordStore.shared.libraryCorrection(id: id) { correction = try JSONDecoder().decode(LibraryCorrection.self, from: Data(json.utf8)) } else { correction = LibraryCorrection() }
            editing = try await WorkoutRecordStore.shared.libraryRecord(id: id)
        } catch { self.error = error.localizedDescription }
    }
    private func review(_ id: String, _ correction: LibraryCorrection) async -> Bool { await command(["action": "review", "setId": id, "correction": jsonObject(correction)]) }
    private func saveGoal(key: String, exercise: ExerciseCounter.Exercise) async {
        guard let target = Double(goalTarget) else { return }
        await command(["action": "goal", "goal": ["comparisonKey": key, "goal": ["exercise": exercise.rawValue, "metric": goalMetric, "target": target, "createdAtEpochMilliseconds": Int64(Date().timeIntervalSince1970 * 1000)]]])
    }
    private func groupSelected() async {
        let sources = Set(history.filter { selectedForGrouping.contains($0.id) }.flatMap(\.sourceSetIds))
        if await command(["action": "assignWorkout", "id": UUID().uuidString, "setIds": sources.sorted()]) { selectedForGrouping = [] }
    }
    private func savePlan() async {
        guard let target = Double(planTarget) else { return }
        let day = Int64(floor((planDate.timeIntervalSince1970 + Double(TimeZone.current.secondsFromGMT(for: planDate))) / 86400))
        await command(["action": "plan", "plan": ["id": UUID().uuidString, "title": "\(planExercise.title) practice", "scheduledDay": day,
            "items": [["id": UUID().uuidString, "exercise": planExercise.rawValue, "metric": planMetric, "target": target, "sets": planSets, "context": jsonObject(planContext)]]]])
    }
    private func linkPlan(_ id: String, workoutId: String?) async {
        do {
            let object = try JSONSerialization.jsonObject(with: Data(try await WorkoutRecordStore.shared.libraryState().utf8)) as? [String: Any]
            guard var plan = (object?["plans"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) else { return }
            plan["workoutId"] = workoutId; await command(["action": "plan", "plan": plan])
        } catch { self.error = error.localizedDescription }
    }
    private func workoutTitle(_ id: String, sessions: [LibrarySession]) -> String {
        guard let session = sessions.first(where: { $0.id == id }) else { return "Recorded workout \(id.prefix(8))" }
        let date = session.startedAtEpochMilliseconds.map { Date(timeIntervalSince1970: Double($0) / 1000).formatted(date: .abbreviated, time: .shortened) } ?? "Date unavailable"
        return "\(date) · \(session.exercises.map(\.title).joined(separator: ", ")) · \(session.setIds.count) sets"
    }
    private func jsonObject<T: Encodable>(_ value: T) -> Any { (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(value))) ?? [:] }
    private func comparisonTitle(_ key: String) -> String {
        guard let parts = try? JSONDecoder().decode([String].self, from: Data(key.utf8)), parts.count >= 8 else { return "Comparison group" }
        return "\(parts[0]) · \(parts[2]) \(parts[3]) · \(parts[4]) kg · \(parts[5]) · \(parts[6])"
    }
}

private struct LibraryContextFields: View {
    @Binding var context: LibraryContext
    var body: some View {
        Group {
            Picker("Motion context", selection: $context.intent) { ForEach(["ordinary", "assisted", "negative", "hold", "practice"], id: \.self) { Text($0.capitalized).tag($0) } }
            Picker("Assistance", selection: $context.assistance) { ForEach(["unknown", "none", "band", "machine", "partner", "footSupport"], id: \.self) { Text($0.capitalized).tag($0) } }
            if context.assistance != "none" { TextField("Assistance setting / band", text: $context.assistanceLabel) }
            TextField("Added load (kg; enter 0 for none)", value: $context.addedLoadKg, format: .number)
            TextField("Equipment / setup label", text: $context.equipment)
            TextField("Camera view (front, oblique, side)", text: $context.cameraView)
            TextField("Grip / variation label", text: $context.variation)
            Picker("Perceived effort", selection: Binding(get: { context.effort ?? -1 }, set: { context.effort = $0 == -1 ? nil : $0 })) { Text("Unrecorded").tag(-1); ForEach(0...10, id: \.self) { Text("\($0) / 10").tag($0) } }
            Picker("Readiness", selection: $context.readiness) { ForEach(["unrecorded", "low", "usual", "high"], id: \.self) { Text($0.capitalized).tag($0) } }
            TextField("Optional recovery note", text: $context.recoveryNote, axis: .vertical)
        }.onChange(of: context.assistance) { _, next in if next == "none" { context.assistanceLabel = "" } }
    }
}
private struct LibraryCorrectionEditor: View {
    @Environment(\.dismiss) private var dismiss
    let record: WorkoutSetRecord
    @State private var correction: LibraryCorrection
    @State private var splitOrdinal = 1
    let errorMessage: String?
    let save: (LibraryCorrection) -> Void
    init(record: WorkoutSetRecord, correction: LibraryCorrection, errorMessage: String?, save: @escaping (LibraryCorrection) -> Void) { self.record = record; _correction = State(initialValue: correction); self.errorMessage = errorMessage; self.save = save }
    var body: some View {
        Form {
            if let errorMessage { Text(errorMessage).foregroundStyle(.orange) }
            Section("Classification") {
                Picker("Exercise", selection: Binding(get: { correction.exercise ?? record.exercise }, set: { correction.exercise = $0 == record.exercise ? nil : $0 })) { ForEach(ExerciseCounter.Exercise.allCases, id: \.self) { Text($0.title).tag($0) } }
                Text("Changing exercise preserves raw evidence; it does not establish completed cycles or reinterpret hold metrics.").font(.caption)
                ForEach(record.reps) { rep in
                    Picker("Attempt \(rep.ordinal)", selection: Binding(get: { correction.attempts.first(where: { $0.repId == rep.id })?.classification ?? "automatic" }, set: { value in
                        correction.attempts.removeAll { $0.repId == rep.id }; if value != "automatic" { correction.attempts.append(LibraryAttemptEdit(repId: rep.id, classification: value)) }
                    })) { Text("Measured evidence").tag("automatic"); Text("Practice / incomplete").tag("practice"); Text("Exclude").tag("excluded"); Text("Completed — user reported").tag("userCompleted") }
                }
            }
            Section("Comparable workout context") { LibraryContextFields(context: $correction.context) }
            Section("Set boundaries") {
                if record.reps.count > 1 {
                    Stepper("Split after attempt \(splitOrdinal)", value: $splitOrdinal, in: 1...(record.reps.count - 1))
                    Button("Split into two sets") { correction.partitions = [LibraryPartition(id: UUID().uuidString, repIds: Array(record.reps.prefix(splitOrdinal)).map(\.id), ownsHold: true), LibraryPartition(id: UUID().uuidString, repIds: Array(record.reps.dropFirst(splitOrdinal)).map(\.id), ownsHold: false)] }
                }
                if !correction.partitions.isEmpty { Button("Merge this source's splits") { correction.partitions = [] } }
                Text("Only the first split owns the original hold totals. Separate recordings retain separate source clocks.").font(.caption)
            }
        }.navigationTitle("Review set").toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) { Button("Save") { save(correction) } }
        }
    }
}
