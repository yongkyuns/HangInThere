import SwiftUI

struct ActivityGoal: Codable, Equatable, Identifiable, Sendable {
    let exercise: String
    let target: Int
    var period = "week"
    var id: String { exercise }
}
struct ActivityGoalProgress: Codable, Equatable, Identifiable, Sendable {
    let goal: ActivityGoal
    let reps: Int; let cameraReps: Int; let loggedReps: Int; let remaining: Int
    let fraction: Double; let achieved: Bool
    var id: String { goal.id }
}
struct ActivityDay: Codable, Equatable, Identifiable, Sendable {
    let day: Int64; let reps: Int; let active: Bool; let future: Bool
    var id: Int64 { day }
    var label: String {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar.veryShortStandaloneWeekdaySymbols[calendar.component(.weekday, from: Date(timeIntervalSince1970: Double(day) * 86400)) - 1]
    }
}
struct ExerciseActivity: Codable, Equatable, Identifiable, Sendable {
    let exercise: String; let reps: Int; let sets: Int; let bestSet: Int
    var id: String { exercise }
}
struct ActivityReport: Codable, Equatable, Sendable {
    let reps: Int; let sets: Int; let activeDays: Int; let cameraReps: Int; let loggedReps: Int
    let previousReps: Int; let previousActiveDays: Int
    let days: [ActivityDay]; let goals: [ActivityGoalProgress]; let exercises: [ExerciseActivity]
    let excludedUndatedSets: Int; let headline: String; let comparison: String
}

struct WorkoutActivityPanel: View {
    let report: ActivityReport
    var onSave: (ActivityGoal) async -> Bool = { _ in false }
    var onRemove: (ActivityGoal) async -> Bool = { _ in false }
    @State private var editing: ActivityGoal?
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            VStack(alignment: .leading, spacing: 16) {
                HStack { Text("This week").font(.headline); Spacer(); Image(systemName: "chart.bar.fill").foregroundStyle(.teal) }
                HStack(alignment: .firstTextBaseline, spacing: 16) {
                    JourneyMetric(value: "\(report.reps)", label: "Reps")
                    JourneyMetric(value: "\(report.sets)", label: "Sets")
                    JourneyMetric(value: "\(report.activeDays)", label: "Active days")
                }
                HStack(alignment: .bottom, spacing: 10) {
                    ForEach(report.days) { day in
                        VStack(spacing: 6) {
                            Text(day.reps > 0 ? "\(day.reps)" : " ").font(.caption2).monospacedDigit()
                            RoundedRectangle(cornerRadius: 6)
                                .fill(day.active ? Color.teal : Color.primary.opacity(day.future ? 0.04 : 0.10))
                                .frame(height: max(6, 54 * CGFloat(day.reps) / CGFloat(max(1, report.days.map(\.reps).max() ?? 1))))
                            Text(day.label).font(.caption).foregroundStyle(.secondary)
                        }.frame(maxWidth: .infinity).accessibilityElement(children: .ignore)
                            .accessibilityLabel("\(day.label), \(day.reps) reps\(day.active ? ", active day" : "")")
                    }
                }.frame(height: report.reps == 0 ? 48 : 92, alignment: .bottom)
                Text(report.headline).font(.headline)
                Label(report.comparison, systemImage: report.reps > report.previousReps ? "arrow.up.right" : "calendar")
                    .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                if report.cameraReps > 0 || report.loggedReps > 0 {
                    Text("Camera \(report.cameraReps) · Logged \(report.loggedReps)").font(.caption).foregroundStyle(.secondary)
                }
            }.journeyCard().accessibilityIdentifier("weeklyWorkoutSummary")
            HStack {
                Text("Your rep goals").font(.title3.bold())
                Spacer()
                Button("Add goal", systemImage: "plus.circle") { editing = ActivityGoal(exercise: "Pull-ups", target: 0) }
                    .labelStyle(.iconOnly).accessibilityIdentifier("addActivityGoal")
            }
            if report.goals.isEmpty { Text("Choose a target. Every recorded rep adds up.").font(.subheadline).foregroundStyle(.secondary) }
            ForEach(report.goals) { progress in
                Button { editing = progress.goal } label: {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack {
                            WorkoutExerciseIcon(exercise: progress.goal.exercise, size: 40)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(progress.goal.exercise).font(.headline)
                                Text(progress.goal.period == "day" ? "Daily target" : "Weekly target").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text("\(progress.reps) / \(progress.goal.target)").font(.headline).monospacedDigit().lineLimit(1).minimumScaleFactor(0.7)
                            if progress.achieved { Image(systemName: "checkmark.circle.fill").foregroundStyle(.teal) }
                        }
                        ProgressView(value: progress.fraction).tint(.teal)
                        Text(progress.achieved ? "Goal reached · \(progress.reps) reps" : "\(progress.remaining) reps to go")
                            .font(.subheadline.weight(.semibold)).foregroundStyle(progress.achieved ? Color.teal : Color.primary)
                    }.journeyCard()
                }.buttonStyle(.plain).accessibilityIdentifier("activityGoal-\(progress.goal.exercise)")
            }
            #if os(iOS)
            if let profile = WorkoutCoachPreferences.shared.profile { WorkoutReminderControls(profile: profile) }
            #endif
            if !report.exercises.isEmpty {
                DisclosureGroup("This week by exercise") {
                    ForEach(report.exercises) { item in
                        HStack {
                            Text(item.exercise)
                            Spacer()
                            VStack(alignment: .trailing, spacing: 3) {
                                Text("\(item.reps) reps · \(item.sets) sets").font(.subheadline.bold())
                                Text("Best set · \(item.bestSet) reps").font(.caption).foregroundStyle(.secondary)
                            }
                        }.padding(.vertical, 6)
                    }
                }.journeyCard()
            }
            if report.excludedUndatedSets > 0 {
                Text("\(report.excludedUndatedSets) undated sets aren’t assigned to a week.").font(.caption).foregroundStyle(.secondary)
            }
        }
        .sheet(item: $editing) { goal in
            ActivityGoalEditor(goal: goal, exercises: report.exercises.map(\.exercise) + report.goals.map { $0.goal.exercise },
                onSave: onSave, onRemove: onRemove)
        }
    }
}
private struct ActivityGoalEditor: View {
    let goal: ActivityGoal
    let exercises: [String]
    let onSave: (ActivityGoal) async -> Bool
    let onRemove: (ActivityGoal) async -> Bool
    @Environment(\.dismiss) private var dismiss
    @State private var exercise: String
    @State private var target: String
    @State private var period: String
    @State private var saving = false
    @State private var failure = false
    init(goal: ActivityGoal, exercises: [String], onSave: @escaping (ActivityGoal) async -> Bool, onRemove: @escaping (ActivityGoal) async -> Bool) {
        self.goal = goal; self.exercises = exercises; self.onSave = onSave; self.onRemove = onRemove
        _exercise = State(initialValue: goal.exercise); _target = State(initialValue: goal.target > 0 ? String(goal.target) : ""); _period = State(initialValue: goal.period)
    }
    var body: some View {
        NavigationStack {
            Form {
                Picker("Exercise", selection: $exercise) {
                    ForEach(Array(Set(exercises + [goal.exercise, "Pull-ups", "Dips", "Push-ups", "Sit-ups", "Bench press", "EZ curls"])).sorted(), id: \.self) { Text($0).tag($0) }
                }.disabled(goal.target > 0)
                Picker("Target period", selection: $period) { Text("Daily").tag("day"); Text("Weekly").tag("week") }.pickerStyle(.segmented)
                TextField("Reps", text: $target).hangInput()
                    #if os(iOS)
                    .keyboardType(.numberPad)
                    #endif
                Text(period == "week" ? "Counts all recorded reps this week." : "Counts all recorded reps today.").font(.caption).foregroundStyle(.secondary)
                if failure { Text("Couldn’t save. Please try again.").foregroundStyle(.orange) }
                Button("Save goal") { Task {
                    guard let count = Int(target), (1...100000).contains(count) else { return }
                    saving = true
                    if await onSave(ActivityGoal(exercise: exercise, target: count, period: period)) { dismiss() } else { failure = true }
                    saving = false
                } }.disabled(Int(target).map { !(1...100000).contains($0) } ?? true)
                if goal.target > 0 { Button("Remove goal", role: .destructive) { Task { saving = true; if await onRemove(goal) { dismiss() } else { failure = true }; saving = false } } }
            }.disabled(saving).hangTheme().navigationTitle("Rep goal")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Back") { dismiss() }.disabled(saving) } }
        }
    }
}


extension JourneyQuality {
    var color: Color {
        switch level {
        case "steady": .teal
        case "pace", "range": .orange
        case "practice": .indigo
        case "limited": .secondary
        case "tracking": .cyan
        case "logged": .blue
        default: .blue
        }
    }
    var symbol: String {
        switch level {
        case "steady": "checkmark.circle.fill"
        case "pace": "metronome"
        case "range": "arrow.up.and.down"
        case "practice": "sparkles"
        case "limited": "eye.slash"
        case "tracking": "record.circle"
        case "logged": "square.and.pencil"
        default: "checkmark.circle"
        }
    }
}
struct JourneyQualityBadge: View {
    let quality: JourneyQuality
    var body: some View {
        Label { Text(quality.title).foregroundStyle(.primary) } icon: { Image(systemName: quality.symbol).foregroundStyle(quality.color) }
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(quality.color.opacity(0.1), in: Capsule())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(quality.title).accessibilityValue(quality.explanation)
    }
}
struct JourneyMetric: View {
    let value: String
    let label: String
    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(value).font(.title2.weight(.semibold)).monospacedDigit()
            Text(label).font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
    }
}
struct JourneyCoachingCard: View {
    let coaching: JourneyCoaching
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "sparkle").font(.title3).foregroundStyle(HangPalette(scheme).accent).padding(.top, 2)
            DisclosureGroup {
                Text(coaching.message).font(.subheadline).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true).padding(.top, 6)
            } label: {
                Text(coaching.title).font(.headline)
            }
        }.journeyCard()
    }
}
private struct JourneyCard: ViewModifier {
    @Environment(\.colorScheme) private var scheme
    func body(content: Content) -> some View {
        content.padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .foregroundStyle(HangPalette(scheme).foreground)
            .background(HangPalette(scheme).surface, in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(HangPalette(scheme).border, lineWidth: 1))
    }
}
extension View {
    func journeyCard() -> some View { modifier(JourneyCard()) }
}

/// Browse workout days, then drill into individual sets and reps.
struct WorkoutJourneyView: View {
    private struct Query: Equatable { let day: WorkoutCalendarDay?; let overview: JourneyOverview; let revision: Int64? }
    private struct CalendarQuery: Equatable { let days: [WorkoutCalendarDay]; let overview: JourneyOverview; let revision: Int64? }
    let overview: JourneyOverview
    var showsOverview = true
    var activity: ActivityReport? = nil
    var onSaveActivityGoal: (ActivityGoal) async -> Bool = { _ in false }
    var onRemoveActivityGoal: (ActivityGoal) async -> Bool = { _ in false }
    var revision: Int64? = nil
    var calendarPreview: [JourneyPracticeDay]? = nil
    @State private var selectedDay: WorkoutCalendarDay?
    @State private var calendarDate = Date()
    @State private var calendarMode = WorkoutCalendar.Mode.month
    @State private var calendarActivity: [JourneyPracticeDay]?
    @State private var calendarFailure: String?
    @State private var loadedWorkouts: [JourneyWorkoutSummary]?
    @State private var nextCursor: JourneyCursor?
    @State private var loading = false
    @State private var failure: String?
    @State private var requestID = UUID()
    private var visibleWorkouts: [JourneyWorkoutSummary] {
        if let loadedWorkouts { return loadedWorkouts }
        guard let selectedDay else { return overview.workouts }
        guard let day = overview.days.first(where: { $0.day == selectedDay.day }) else { return [] }
        return overview.workouts.filter { day.workoutIds.contains($0.id) }
    }
    var body: some View {
        LazyVStack(alignment: .leading, spacing: 24) {
            if showsOverview {
                if selectedDay == nil, let activity { WorkoutActivityPanel(report: activity, onSave: onSaveActivityGoal, onRemove: onRemoveActivityGoal) }
                if selectedDay == nil { WorkoutTrainingTipCard() }
                WorkoutCalendarView(date: $calendarDate, mode: $calendarMode, selected: selectedDay,
                    activity: calendarPreview ?? calendarActivity ?? overview.days,
                    loading: calendarPreview == nil && calendarActivity == nil && calendarFailure == nil,
                    onSelect: { day in selectedDay = day },
                    onShowAll: { selectedDay = nil })
                if let calendarFailure { Text(calendarFailure).font(.caption).foregroundStyle(.secondary) }
                if selectedDay == nil && activity == nil {
                    VStack(alignment: .leading, spacing: 16) {
                        Text("Last 28 days").font(.subheadline).foregroundStyle(.secondary)
                        HStack(alignment: .top) {
                            JourneyMetric(value: "\(overview.totalReps)", label: "Reps")
                            JourneyMetric(value: "\(overview.workoutCount)", label: "Workouts")
                            JourneyMetric(value: "\(overview.practiceDays)", label: "Practice days")
                        }
                    }
                    JourneyCoachingCard(coaching: overview.coaching)
                }
            }
            Text(selectedDay.map { $0.date.formatted(.dateTime.weekday(.wide).month(.wide).day().year()) } ?? "Recent workouts").font(.title3.bold())
            if visibleWorkouts.isEmpty && !loading {
                ContentUnavailableView(selectedDay == nil ? "Your first workout starts here" : "No workouts on this day",
                    systemImage: "figure.strengthtraining.traditional")
            }
            ForEach(visibleWorkouts) { workout in
                NavigationLink { JourneyWorkoutView(workout: workout) } label: {
                    JourneyWorkoutRow(workout: workout)
                }.buttonStyle(.plain)
            }
            if let failure { Text(failure).font(.caption).foregroundStyle(.secondary) }
            if loading { ProgressView() }
            if (loadedWorkouts == nil && selectedDay == nil ? overview.nextCursor : nextCursor) != nil {
                Button("Earlier workouts") { Task { await load(more: true) } }.disabled(loading)
            }
        }.task(id: Query(day: selectedDay, overview: overview, revision: revision)) {
            if selectedDay == nil { requestID = UUID(); loading = false; loadedWorkouts = nil; nextCursor = overview.nextCursor; failure = nil }
            else { await load(more: false) }
        }
        .task(id: CalendarQuery(days: calendarDays, overview: overview, revision: revision)) {
            guard showsOverview, calendarPreview == nil else { return }
            calendarActivity = nil; calendarFailure = nil
            do {
                let activity = try await WorkoutRecordStore.shared.libraryCalendarDays(calendarDays)
                try Task.checkCancellation()
                calendarActivity = activity
            } catch is CancellationError { } catch { calendarFailure = "Calendar couldn't load. Pull to refresh." }
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)) { _ in
            calendarDate = Date(); selectedDay = nil
        }
    }
    private var calendarDays: [WorkoutCalendarDay] { WorkoutCalendar.days(containing: calendarDate, mode: calendarMode) }
    private func load(more: Bool) async {
        let request = UUID(); requestID = request
        if !more { loadedWorkouts = nil; nextCursor = nil; failure = nil }
        loading = true
        defer { if requestID == request { loading = false } }
        do {
            let cursor = more ? (loadedWorkouts == nil && selectedDay == nil ? overview.nextCursor : nextCursor) : nil
            let input = try cursor.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) }
            let json = try await WorkoutRecordStore.shared.libraryJourneyPage(day: selectedDay?.day, cursor: input, range: selectedDay)
            try Task.checkCancellation()
            guard requestID == request else { return }
            let page = try JSONDecoder().decode(JourneyPage.self, from: Data(json.utf8))
            loadedWorkouts = more ? (loadedWorkouts ?? overview.workouts) + page.workouts : page.workouts
            nextCursor = page.nextCursor; failure = nil
        } catch is CancellationError { } catch { if requestID == request { failure = error.localizedDescription } }
    }
}
struct WorkoutCalendarView: View {
    @Binding var date: Date
    @Binding var mode: WorkoutCalendar.Mode
    let selected: WorkoutCalendarDay?
    let activity: [JourneyPracticeDay]
    var loading = false
    var onSelect: (WorkoutCalendarDay) -> Void
    var onShowAll: () -> Void
    @Environment(\.colorScheme) private var scheme
    private var calendar: Calendar { .current }
    private var days: [WorkoutCalendarDay] { WorkoutCalendar.days(containing: date, mode: mode, calendar: calendar) }
    private var palette: HangPalette { HangPalette(scheme) }
    private var title: String {
        if mode == .month { return date.formatted(.dateTime.month(.wide).year()) }
        let formatter = DateIntervalFormatter(); formatter.dateStyle = .medium; formatter.timeStyle = .none
        return formatter.string(from: days.first!.date, to: days.last!.date)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("Calendar").font(.headline)
                Spacer()
                Button("Today") { date = Date(); onSelect(WorkoutCalendar.day(containing: date)) }
                    .font(.subheadline.weight(.semibold)).frame(minHeight: 44)
            }
            Picker("Calendar view", selection: $mode) {
                ForEach(WorkoutCalendar.Mode.allCases, id: \.rawValue) { Text($0.rawValue).tag($0) }
            }.pickerStyle(.segmented).labelsHidden().accessibilityIdentifier("workoutCalendarMode")
            HStack(spacing: 8) {
                Button { move(-1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                    .accessibilityLabel("Previous \(mode.rawValue.lowercased())")
                Text(title).font(.subheadline.bold()).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                Button { move(1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
                    .accessibilityLabel("Next \(mode.rawValue.lowercased())")
            }.buttonStyle(.plain)
            let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 7)
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(Array(WorkoutCalendar.weekdays(calendar: calendar).enumerated()), id: \.offset) { _, name in
                    Text(name).font(.caption.weight(.semibold)).foregroundStyle(palette.secondary)
                        .lineLimit(1).minimumScaleFactor(0.6).frame(maxWidth: .infinity).accessibilityHidden(true)
                }
                ForEach(days) { day in
                    let item = activity.first { $0.day == day.day }
                    let hasWorkout = item?.workoutIds.isEmpty == false
                    let isSelected = selected?.day == day.day
                    let isToday = calendar.isDateInToday(day.date)
                    let inMonth = mode == .week || calendar.isDate(day.date, equalTo: date, toGranularity: .month)
                    Button {
                        date = day.date
                        onSelect(day)
                    } label: {
                        VStack(spacing: 5) {
                            Text(day.date.formatted(.dateTime.day())).font(.callout.weight(isToday || isSelected ? .bold : .regular))
                            Circle().fill(hasWorkout ? palette.accent : .clear).frame(width: 5, height: 5)
                        }.frame(maxWidth: .infinity, minHeight: 46)
                            .foregroundStyle(isToday ? palette.accent : palette.foreground)
                            .opacity(inMonth ? 1 : 0.4)
                            .background(isSelected ? palette.garden : palette.surface, in: RoundedRectangle(cornerRadius: 11))
                            .overlay(RoundedRectangle(cornerRadius: 11).stroke(isSelected || isToday ? palette.accent : .clear,
                                lineWidth: isSelected ? 2 : 1))
                    }.buttonStyle(.plain)
                        .accessibilityLabel(day.date.formatted(date: .complete, time: .omitted))
                        .accessibilityValue(hasWorkout ? "\(item!.workoutIds.count) workouts, \(item!.totalReps) reps" : loading ? "Loading activity" : "No workouts")
                        .accessibilityAddTraits(isSelected ? .isSelected : [])
                        .accessibilityIdentifier("workoutCalendarDay-\(day.day)")
                }
            }.dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            HStack {
                Label { Text("Workout") } icon: { Circle().fill(palette.accent).frame(width: 5, height: 5) }
                    .font(.caption).foregroundStyle(palette.secondary)
                if loading { ProgressView().controlSize(.mini) }
                Spacer()
                if selected != nil { Button("All workouts", action: onShowAll).font(.caption.weight(.semibold)).frame(minHeight: 44) }
            }
        }.buttonStyle(.plain).tint(palette.accent)
    }
    private func move(_ amount: Int) {
        date = WorkoutCalendar.move(date, by: amount, mode: mode, calendar: calendar)
        onSelect(WorkoutCalendar.day(containing: date, calendar: calendar))
    }
}

struct JourneyWorkoutRow: View {
    let workout: JourneyWorkoutSummary
    var showsDisclosure = true
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(workout.epochMilliseconds.map { Date(timeIntervalSince1970: Double($0)/1000).formatted(date: .abbreviated, time: .shortened) } ?? "Undated workout")
                        .font(.headline)
                    Text(workout.exerciseNames.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 4)
                if showsDisclosure { Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary) }
            }
            HStack {
                Text("\(workout.totalReps) reps · \(workout.setCount) \(workout.setCount == 1 ? "set" : "sets")").font(.subheadline.weight(.medium))
                Spacer(minLength: 4)
                JourneyQualityBadge(quality: workout.quality)
            }
        }.journeyCard().accessibilityElement(children: .combine)
    }
}
struct JourneyWorkoutView: View {
    let workout: JourneyWorkoutSummary
    var refreshesFromStore = true
    @State private var refreshed: JourneyWorkoutSummary?
    @State private var unavailable = false
    @State private var editing = false
    private var current: JourneyWorkoutSummary { refreshed ?? workout }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                if unavailable {
                    ContentUnavailableView("No sets left in this workout", systemImage: "square.and.pencil")
                } else {
                JourneyWorkoutRow(workout: current, showsDisclosure: false)
                if let progress = current.progress, current.totalReps > 0 {
                    VStack(alignment: .leading, spacing: 12) {
                        Label(progress.headline, systemImage: "chart.line.uptrend.xyaxis").font(.headline)
                        HStack {
                            JourneyMetric(value: "\(progress.bestSet)", label: "Most reps in a set")
                            if let previous = progress.previousReps { JourneyMetric(value: "\(previous)", label: "Previous workout reps") }
                        }
                        if progress.previousReps != nil { Text("Recorded volume · same exercises").font(.caption).foregroundStyle(.secondary) }
                    }.journeyCard()
                }
                if let log = current.workoutLog {
                    WorkoutLogTable(log: log) { _ in editing = true }
                    Button("Continue / edit workout", systemImage: "square.and.pencil") { editing = true }
                }
                if !current.sets.isEmpty { Text("Camera sets").font(.title2.bold()) }
                ForEach(Array(current.sets.enumerated()), id: \.element.id) { index, set in
                    NavigationLink { JourneySetDestination(summary: set) } label: {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                Text("Set \(index + 1) · \(set.exercise.title)").font(.headline)
                                Spacer()
                                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                            }
                            HStack {
                                Text(set.completedReps > 0 ? "\(set.completedReps) completed reps" : set.holdSeconds.map { String(format: "%.1fs hold", $0) } ?? "Practice")
                                Spacer()
                                JourneyQualityBadge(quality: set.quality)
                            }.font(.subheadline)
                        }.journeyCard()
                    }.buttonStyle(.plain)
                }
                }
            }.padding()
        }.hangTheme().navigationTitle("Workout")
            .sheet(isPresented: $editing) {
                if let log = current.workoutLog { NavigationStack { WorkoutLogView(initial: log, cameraSets: current.sets) } }
            }
            .task { await refresh() }
            .onReceive(NotificationCenter.default.publisher(for: .workoutLogChanged).receive(on: RunLoop.main)) { _ in
                Task { await refresh() }
            }
    }
    private func refresh() async {
        guard refreshesFromStore else { return }
        do {
            refreshed = try await WorkoutRecordStore.shared.workoutSummary(id: workout.id)
            unavailable = refreshed == nil
        } catch { } // Preserve the displayed workout on a transient read failure.
    }
}
private struct JourneySetDestination: View {
    let summary: JourneySetSummary
    @State private var records: [WorkoutSetRecord] = []
    @State private var detail: JourneySetSummary?
    @State private var failure: String?
    var body: some View {
        Group {
            if let detail {
                WorkoutReviewView(sets: records, currentReps: records.flatMap(\.reps), storageError: nil,
                    showsLibraryLink: false, summary: detail)
            } else if let failure { ContentUnavailableView("Set unavailable", systemImage: "exclamationmark.circle", description: Text(failure)) }
            else { ProgressView() }
        }.task(id: summary.id) {
            do {
                let json = try await WorkoutRecordStore.shared.libraryJourneySet(id: summary.id)
                let next = try JSONDecoder().decode(JourneySetSummary.self, from: Data(json.utf8))
                var sources: [WorkoutSetRecord] = []
                for id in next.sourceSetIds {
                    guard let record = try await WorkoutRecordStore.shared.libraryRecord(id: id) else { throw CocoaError(.fileNoSuchFile) }
                    sources.append(record)
                }
                try Task.checkCancellation()
                records = sources; detail = next
            } catch is CancellationError { } catch { failure = error.localizedDescription }
        }
    }
}
