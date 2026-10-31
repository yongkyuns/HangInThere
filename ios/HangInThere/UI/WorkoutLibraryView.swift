import SwiftUI
import Charts
import WorkoutCore
import UniformTypeIdentifiers
import AVKit

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
struct LibraryPlanItem: Decodable, Identifiable, Sendable { let id: String; let exercise: ExerciseCounter.Exercise; let metric: String; let target: Double; let sets: Int; let context: LibraryContext }
struct LibraryPlanResult: Decodable, Identifiable, Sendable { let planId: String; let title: String; let status: String; let completedItems: Int; let totalItems: Int; let workoutId: String?; let candidateWorkoutIds: [String]; let scheduledDay: Int64?; let items: [LibraryPlanItem]; let measuredValues: [Double]; var id: String { planId } }
struct LibraryInsights: Decodable, Sendable {
    let overview: JourneyOverview?
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
    private enum Tab: String, CaseIterable { case progress = "Progress", history = "History", measuredSets = "Measured sets", recordings = "Recordings", plans = "Plans", storage = "Storage", trends = "Trends" }
    @State private var tab = Tab.progress
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("recordCountedSets") private var recordsSetVideos = true
    @State private var showingWorkoutLog = false
    @State private var historyFilter = "all"
    @State private var organizingHistory = false
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
    @State private var mediaReview: WorkoutVideoReviewData?
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
            if tab != .progress && tab != .history && tab != .recordings {
                HStack { Button("Back to progress", systemImage: "chevron.left") { tab = .progress }; Spacer() }.padding()
            }
            if tab == .progress || tab == .history {
                Picker("Workout layout", selection: $tab) {
                    Text("Calendar").tag(Tab.progress)
                    Text("List").tag(Tab.history)
                }.pickerStyle(.segmented).labelsHidden().padding(.horizontal).padding(.vertical, 8)
                    .accessibilityIdentifier("workoutLibraryLayout")
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 20) {
                    if let error { Text(error).foregroundStyle(.orange).accessibilityLabel("Library error: \(error)") }
                    if let dashboard {
                        switch tab {
                        case .progress:
                            if let overview = dashboard.insights.overview { WorkoutJourneyView(overview: overview, revision: dashboard.revision, calendarPreview: previewMode ? overview.days : nil) }
                            else { ContentUnavailableView("Record your first workout", systemImage: "chart.bar") }
                        case .history:
                            if let overview = dashboard.insights.overview { WorkoutJourneyView(overview: overview, showsOverview: false, revision: dashboard.revision, calendarPreview: previewMode ? overview.days : nil) }
                        case .measuredSets: historyList(dashboard)
                        case .recordings: recordings(dashboard)
                        case .plans: plans(dashboard.insights)
                        case .storage: storage(dashboard)
                        case .trends: progress(dashboard.insights)
                        }
                    } else { ProgressView("Loading workout library…") }
                }.padding()
            }
        }.hangTheme()
        .navigationTitle(tab == .progress || tab == .history ? "Your workouts" : tab.rawValue)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Calendar") { tab = .progress }
                        Button("Goals and trends") { tab = .trends }
                        Button("Workout list") { tab = .history }
                        Button("Log workout", systemImage: "square.and.pencil") { showingWorkoutLog = true }
                        Button("Measured sets") { tab = .measuredSets }
                        Button("Recordings") { tab = .recordings }
                        Button("Training plans") { tab = .plans }
                        Button("Storage and export") { tab = .storage }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .task { if !previewMode { await load() } }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active && !previewMode { Task { await load() } }
            }
            .refreshable { if !previewMode { await load() } }
            .onReceive(NotificationCenter.default.publisher(for: .workoutVideoSaved).receive(on: RunLoop.main)) { _ in
                if !previewMode { Task { await load() } }
            }
            .sheet(isPresented: $showingWorkoutLog) { NavigationStack { WorkoutLogView() } }
            .onReceive(NotificationCenter.default.publisher(for: .workoutLogChanged).receive(on: RunLoop.main)) { _ in
                if !previewMode { Task { await load() } }
            }
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
            .sheet(isPresented: Binding(get: { mediaReview != nil }, set: { if !$0 { mediaReview = nil } })) {
                if let mediaReview { NavigationStack { WorkoutVideoReview(data: mediaReview).toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { self.mediaReview = nil } } } } }
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
            .alert("Delete this set?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                Button("Delete", role: .destructive) { if let deleting { Task { if await command(["action": "deleteSources", "setIds": deleting.sourceSetIds]) { self.deleting = nil } } } }
                Button("Cancel", role: .cancel) { deleting = nil }
            } message: { Text("This removes the set and its saved videos from your history." + (error.map { "\n" + $0 } ?? "")) }
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
                Text("Gaps = no measurement · angles don’t assess form").font(.caption)
            }
            if let key = report.selectedComparisonKey {
                Button("Use this context for future live sets") { Task { await command(["action": "defaultContext", "id": key]) } }
                Text("Saves assistance, load, equipment and camera setup.").font(.caption)
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
            Text("Holds and attempts count too.").font(.caption)
            Chart(report.weekly) { week in
                BarMark(x: .value("Week", Date(timeIntervalSince1970: Double(week.firstDay) * 86400)), y: .value("Practice days", week.days))
                    .accessibilityValue("\(week.days) practice days, \(week.workouts) workouts")
            }.chartYScale(domain: 0...7).frame(minHeight: 150, maxHeight: 220)
            DisclosureGroup("Milestone history") { ForEach(report.milestones.filter(\.achieved)) { item in Text(item.title + (item.achievedAtEpochMilliseconds.map { " · " + Date(timeIntervalSince1970: Double($0) / 1000).formatted(date: .abbreviated, time: .omitted) } ?? " · date unavailable")) } }
        }
    }
    private var historyPalette: HangPalette { HangPalette(colorScheme) }
    private var filteredHistory: [LibrarySet] {
        history.filter { historyFilter == "all" || $0.exercise.rawValue == historyFilter }
    }
    private var historyDays: [String] {
        var keys: [String] = []
        for set in filteredHistory { let key = historyDay(set); if !keys.contains(key) { keys.append(key) } }
        return keys
    }
    private func historyDay(_ set: LibrarySet) -> String {
        guard let time = set.trackedAtEpochMilliseconds else { return "Earlier workouts" }
        let date = Date(timeIntervalSince1970: Double(time) / 1000)
        if Calendar.current.isDateInToday(date) { return "Today" }
        if Calendar.current.isDateInYesterday(date) { return "Yesterday" }
        return date.formatted(.dateTime.month(.abbreviated).day().year())
    }
    private func historyList(_ dashboard: LibraryDashboard) -> some View {
        VStack(alignment: .leading, spacing: 22) {
            Text("Your effort, collected.").font(.system(.title2, design: .rounded, weight: .bold))
            HStack(spacing: 0) {
                historyStat(filteredHistory.count, title: "Sets", symbol: "square.stack")
                historyStat(filteredHistory.reduce(0) { $0 + $1.measuredCompletedReps }, title: "Reps", symbol: "arrow.up.arrow.down")
                historyStat(dashboard.media.filter { Set(filteredHistory.flatMap(\.sourceSetIds)).contains($0.setId) }.count, title: "Videos", symbol: "play.rectangle")
            }
            .padding(.vertical, 16)
            .background(historyPalette.garden, in: RoundedRectangle(cornerRadius: 22))
            HStack {
                Picker("Exercise", selection: $historyFilter) {
                    Text("All").tag("all")
                    Text("Pull-ups").tag(ExerciseCounter.Exercise.pullUp.rawValue)
                    Text("Dips").tag(ExerciseCounter.Exercise.dip.rawValue)
                }.pickerStyle(.segmented).labelsHidden()
                Button { organizingHistory.toggle(); if !organizingHistory { selectedForGrouping = [] } } label: {
                    Image(systemName: organizingHistory ? "checkmark.circle.fill" : "checklist")
                        .font(.title3)
                }.accessibilityLabel(organizingHistory ? "Finish organizing" : "Organize sets")
            }.buttonStyle(.plain)
            if history.isEmpty {
                ContentUnavailableView("Your first set belongs here", systemImage: "figure.strengthtraining.traditional",
                    description: Text("Record a workout to save your sets here."))
            } else if filteredHistory.isEmpty {
                ContentUnavailableView("No sets for this exercise yet", systemImage: "figure.arms.open")
            }
            ForEach(historyDays, id: \.self) { day in
                VStack(alignment: .leading, spacing: 12) {
                    Text(day).font(.headline).foregroundStyle(historyPalette.secondary)
                    ForEach(filteredHistory.filter { historyDay($0) == day }) { set in
                        historyCard(set, dashboard: dashboard)
                    }
                }
            }
            if dashboard.page.nextCursor != nil {
                Button("Show earlier sets", systemImage: "clock.arrow.circlepath") { Task { await load(more: true) } }
                    .frame(maxWidth: .infinity).padding(12)
            }
            if organizingHistory {
                Text("Select at least two sets to organize them.").font(.caption).foregroundStyle(historyPalette.secondary)
                Button("Group into a workout") { Task { await groupSelected() } }.disabled(selectedForGrouping.count < 2)
                Button("Merge comparable sets") { Task {
                    if await command(["action": "merge", "id": UUID().uuidString, "setIds": selectedForGrouping.sorted()]) { selectedForGrouping = [] }
                } }.disabled(selectedForGrouping.count < 2)
            }
            VStack(alignment: .leading, spacing: 8) {
                Toggle("Record counted sets", isOn: $recordsSetVideos).font(.subheadline.weight(.semibold))
                Text("Silent video · counted sets only")
                    .font(.caption).foregroundStyle(historyPalette.secondary)
            }
            .padding(16).background(historyPalette.surface, in: RoundedRectangle(cornerRadius: 22))
        }
        .foregroundStyle(historyPalette.foreground)
        .fontDesign(.rounded)
    }
    private func recordings(_ dashboard: LibraryDashboard) -> some View {
        LazyVStack(alignment: .leading, spacing: 20) {
            Text("Older videos clear automatically. Your workout history stays.")
                .font(.caption).foregroundStyle(historyPalette.secondary)
            if dashboard.media.isEmpty {
                ContentUnavailableView("No recordings yet", systemImage: "video",
                    description: Text("Your counted-set videos will appear here."))
            }
            ForEach(dashboard.media.sorted { $0.attachedAtEpochMilliseconds > $1.attachedAtEpochMilliseconds }) { media in
                VStack(alignment: .leading, spacing: 12) {
                    Button { playMedia(media) } label: {
                        WorkoutRecordingPoster(mediaId: media.id, loadsPreview: !previewMode)
                            .frame(height: 210).clipped().clipShape(RoundedRectangle(cornerRadius: 18))
                            .overlay { Image(systemName: "play.circle.fill").font(.system(size: 48))
                                .symbolRenderingMode(.palette).foregroundStyle(.white, .black.opacity(0.55)) }
                    }.buttonStyle(.plain).accessibilityLabel("Play workout recording")
                        .accessibilityIdentifier("playRecording-\(media.id)")
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Workout recording").font(.headline)
                            Text(Date(timeIntervalSince1970: Double(media.attachedAtEpochMilliseconds) / 1000), format: .dateTime.month(.abbreviated).day().hour().minute())
                                .font(.caption).foregroundStyle(historyPalette.secondary)
                        }
                        Spacer()
                        Menu {
                            Button("Delete recording", systemImage: "trash", role: .destructive) {
                                Task { await command(["action": "deleteMedia", "id": media.id]) }
                            }
                        } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
                            .accessibilityLabel("Recording options")
                    }
                }.padding(12).background(historyPalette.surface, in: RoundedRectangle(cornerRadius: 24))
            }
            Toggle("Record counted sets", isOn: $recordsSetVideos).font(.subheadline)
        }.fontDesign(.rounded).foregroundStyle(historyPalette.foreground)
    }

    private func historyStat(_ value: Int, title: String, symbol: String) -> some View {
        VStack(spacing: 5) {
            Image(systemName: symbol).font(.subheadline).foregroundStyle(historyPalette.secondary)
            Text(value.formatted()).font(.system(.title2, design: .rounded, weight: .bold)).monospacedDigit()
            Text(title).font(.caption).foregroundStyle(historyPalette.secondary)
        }.frame(maxWidth: .infinity)
    }
    private func historyCard(_ set: LibrarySet, dashboard: LibraryDashboard) -> some View {
        let clips = dashboard.media.filter { set.sourceSetIds.contains($0.setId) }
        return VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                if organizingHistory {
                    Button { if selectedForGrouping.contains(set.id) { selectedForGrouping.remove(set.id) } else { selectedForGrouping.insert(set.id) } } label: {
                        Image(systemName: selectedForGrouping.contains(set.id) ? "checkmark.circle.fill" : "circle").font(.title2)
                    }.accessibilityLabel("Select \(set.exercise.title) set")
                }
                Image(systemName: set.exercise == .pullUp ? "figure.strengthtraining.traditional" : "figure.gymnastics")
                    .font(.system(size: 24)).frame(width: 46, height: 46)
                    .background(historyPalette.garden, in: RoundedRectangle(cornerRadius: 15))
                VStack(alignment: .leading, spacing: 4) {
                    Text(set.exercise.title).font(.headline)
                    if let time = set.trackedAtEpochMilliseconds {
                        Text(Date(timeIntervalSince1970: Double(time) / 1000), format: .dateTime.hour().minute())
                            .font(.caption).foregroundStyle(historyPalette.secondary)
                    }
                }
                Spacer(minLength: 4)
                VStack(alignment: .trailing, spacing: 0) {
                    Text(set.measuredCompletedReps.formatted()).font(.system(size: 32, weight: .bold, design: .rounded)).monospacedDigit()
                    Text("reps").font(.caption).foregroundStyle(historyPalette.secondary)
                }.accessibilityElement(children: .ignore).accessibilityLabel("\(set.measuredCompletedReps) counted reps")
            }
            if set.userReportedCompletedReps > 0 || set.practiceAttempts > 0 {
                Text([set.userReportedCompletedReps > 0 ? "\(set.userReportedCompletedReps) user-reported" : nil,
                      set.practiceAttempts > 0 ? "\(set.practiceAttempts) practice attempts" : nil].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(historyPalette.secondary)
            }
            HStack {
                Button {
                    Task {
                        do { evidenceRecord = try await WorkoutRecordStore.shared.libraryRecord(id: set.sourceSetId) }
                        catch { self.error = error.localizedDescription }
                    }
                } label: {
                    Label("View set", systemImage: "chart.bar.xaxis").font(.subheadline.bold())
                }.accessibilityIdentifier("reviewHistorySet-\(set.id)")
                if let clip = clips.first {
                    Button { playMedia(clip) } label: {
                        Image(systemName: "play.circle").font(.title2)
                    }.accessibilityLabel("Play set recording").accessibilityIdentifier("playSetVideo-\(set.id)")
                }
                Spacer()
                Menu { historyActions(set, dashboard: dashboard) } label: {
                    Image(systemName: "ellipsis").font(.headline).frame(width: 44, height: 32)
                }.accessibilityLabel("Set options")
            }
        }
        .padding(16)
        .background(historyPalette.surface, in: RoundedRectangle(cornerRadius: 23))
        .buttonStyle(.plain)
        .tint(historyPalette.isDark ? HangBrand.lime : HangBrand.ink)
        .accessibilityElement(children: .contain)
    }
    private func playMedia(_ media: LibraryMedia) {
        Task {
            do {
                guard let review = try await WorkoutRecordStore.shared.libraryVideoReview(id: media.id) else { error = "This video is no longer available."; await load(); return }
                mediaReview = review
            } catch { self.error = error.localizedDescription }
        }
    }
    @ViewBuilder private func historyActions(_ set: LibrarySet, dashboard: LibraryDashboard) -> some View {
        ForEach(set.sourceSetIds, id: \.self) { sourceId in
            Section(set.sourceSetIds.count > 1 ? "Original \(sourceId.prefix(6))" : "This set") {
                Button("View measurements", systemImage: "waveform.path") { Task { do { evidenceRecord = try await WorkoutRecordStore.shared.libraryRecord(id: sourceId) } catch { self.error = error.localizedDescription } } }
                Button("Edit set", systemImage: "pencil") { Task { await edit(sourceId) } }
                Button("Undo edits", systemImage: "arrow.uturn.backward") { Task { await command(["action": "undo", "setId": sourceId]) } }
                if dashboard.mediaPolicy.enabled { Button("Attach video", systemImage: "paperclip") { mediaSetId = sourceId; importingMedia = true } }
                ForEach(dashboard.media.filter { $0.setId == sourceId }) { media in
                    Button("Play video", systemImage: "play") { playMedia(media) }
                    Button("Delete video", systemImage: "trash", role: .destructive) { Task { await command(["action": "deleteMedia", "id": media.id]) } }
                }
            }
        }
        if set.isMerged { Button("Unmerge sets") { Task { await command(["action": "unmerge", "id": set.id]) } } }
        Button("Delete set", systemImage: "trash", role: .destructive) { error = nil; deleting = set }
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
            Text("Link a recorded workout to complete a plan.").font(.caption)
            ForEach(report.plans) { plan in
                Text("\(plan.title) · \(planStatusTitle(plan.status))").font(.headline)
                if let day = plan.scheduledDay { Text("Planned for \(plannedDayTitle(day))").font(.subheadline) }
                ForEach(Array(plan.items.enumerated()), id: \.element.id) { index, item in
                    Text("\(item.exercise.title): \(item.sets) sets of \(item.target.formatted()) \(item.metric == "holdSeconds" ? "hold seconds" : "completed cycles") per set")
                    Text("\(Int(plan.measuredValues.indices.contains(index) ? plan.measuredValues[index] : 0))/\(item.sets) sets meet this target\(plan.workoutId == nil ? " · preview; link to confirm" : "")").font(.caption)
                    DisclosureGroup("Setup details") {
                        Text("\(item.context.assistance) \(item.context.assistanceLabel) · \(item.context.addedLoadKg.map { "\($0.formatted()) kg" } ?? "load unrecorded") · \(item.context.equipment) · \(item.context.cameraView) · \(item.context.intent) \(item.context.variation)").font(.caption)
                    }.font(.caption)
                }
                if !plan.candidateWorkoutIds.isEmpty {
                    Menu(plan.workoutId == nil ? "Link recorded workout" : "Change linked workout") {
                        ForEach(plan.candidateWorkoutIds, id: \.self) { id in
                            Button(workoutTitle(id, sessions: report.sessions)) { Task { await linkPlan(plan.planId, workoutId: id) } }
                        }
                    }
                } else { Text("No matching recording for this day.").font(.caption) }
                if plan.workoutId != nil { Button("Unlink workout") { Task { await linkPlan(plan.planId, workoutId: nil) } } }
                Button("Delete plan", role: .destructive) { Task { await command(["action": "deletePlan", "id": plan.planId]) } }
            }
            DatePicker("Practice date", selection: $planDate, displayedComponents: .date)
            Text("Match your setup to compare recordings.").font(.caption)
            Picker("Exercise", selection: $planExercise) { ForEach(ExerciseCounter.Exercise.barExercises, id: \.self) { Text($0.title).tag($0) } }
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
            Toggle("Keep saved workout videos", isOn: Binding(get: { dashboard.mediaPolicy.enabled }, set: { enabled in Task {
                if !enabled { recordsSetVideos = false }
                var policy = dashboard.mediaPolicy; policy.enabled = enabled
                await command(["action": "mediaPolicy", "mediaPolicy": jsonObject(policy)])
            } }))
            Text("Up to \(dashboard.mediaPolicy.maximumClips) videos, \(dashboard.mediaPolicy.maximumBytes / 1048576) MB and \(dashboard.mediaPolicy.maximumAgeDays) days. Turning this off deletes retained videos.").font(.caption)
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
    private func plannedDayTitle(_ day: Int64) -> String {
        let formatter = DateFormatter(); formatter.timeZone = TimeZone(secondsFromGMT: 0); formatter.dateStyle = .medium
        return formatter.string(from: Date(timeIntervalSince1970: Double(day) * 86400))
    }
    private func planStatusTitle(_ status: String) -> String {
        switch status {
        case "completed": "Completed"
        case "observed": "Recorded progress"
        case "linkedNotEligible": "Linked recording does not match"
        case "notRecorded": "No eligible recording"
        default: "Planned"
        }
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
