import SwiftUI

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
                WorkoutCalendarView(date: $calendarDate, mode: $calendarMode, selected: selectedDay,
                    activity: calendarPreview ?? calendarActivity ?? overview.days,
                    loading: calendarPreview == nil && calendarActivity == nil && calendarFailure == nil,
                    onSelect: { day in selectedDay = day },
                    onShowAll: { selectedDay = nil })
                if let calendarFailure { Text(calendarFailure).font(.caption).foregroundStyle(.secondary) }
                if selectedDay == nil {
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
