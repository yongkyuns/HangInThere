import SwiftUI
#if os(iOS)
import UIKit
#endif

struct WorkoutCoachHomeCard: View {
    var onOpen: () -> Void
    private var profile: WorkoutCoachProfile? { WorkoutCoachPreferences.shared.profile }
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                Image(systemName: profile == nil ? "sparkles" : "calendar.badge.clock").font(.title2)
                VStack(alignment: .leading, spacing: 4) {
                    Text(profile == nil ? "A little guidance?" : "Your plan, your pace").font(.headline)
                    Text(profile.map { "\($0.goal.title) · \($0.minutes) min" } ?? "Plan your week")
                        .font(.caption).foregroundStyle(HangPalette(scheme).secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "arrow.right")
            }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
                .foregroundStyle(HangPalette(scheme).foreground)
                .background(HangPalette(scheme).garden, in: RoundedRectangle(cornerRadius: 22))
        }.buttonStyle(.plain).accessibilityIdentifier("openWorkoutCoach")
    }
}

struct WorkoutCoachSetupView: View {
    @State var profile: WorkoutCoachProfile
    var onSave: (WorkoutCoachProfile) -> Void
    var onSkip: () -> Void
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    private var days: [Int] { (0..<7).map { (Calendar.current.firstWeekday - 1 + $0) % 7 + 1 } }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Image(systemName: "sparkles").font(.system(size: 35)).foregroundStyle(.teal)
                Text("Make room for you.").font(.largeTitle.bold())
                VStack(alignment: .leading, spacing: 10) {
                    Text("What brings you here?").font(.headline)
                    ForEach(WorkoutCoachProfile.Goal.allCases, id: \.rawValue) { goal in
                        Button { profile.goal = goal } label: {
                            HStack {
                                Label(goal.title, systemImage: goal.symbol)
                                Spacer()
                                Image(systemName: profile.goal == goal ? "checkmark.circle.fill" : "circle")
                            }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
                                .foregroundStyle(profile.goal == goal ? Color.teal : Color.primary)
                                .background(profile.goal == goal ? Color.teal.opacity(0.12) : Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 16))
                        }.buttonStyle(.plain).accessibilityAddTraits(profile.goal == goal ? [.isSelected] : [])
                    }
                }
                VStack(alignment: .leading, spacing: 10) {
                    Text("Days that usually work").font(.headline)
                    LazyVGrid(columns: dynamicTypeSize.isAccessibilitySize ? [GridItem(.adaptive(minimum: 64))] : Array(repeating: GridItem(.flexible(minimum: 0), spacing: 4), count: 7), spacing: 8) {
                        ForEach(days, id: \.self) { day in
                            Button {
                                if profile.weekdays.contains(day) { profile.weekdays.removeAll { $0 == day } }
                                else { profile.weekdays.append(day); profile.weekdays.sort() }
                            } label: {
                                Text(Calendar.current.shortWeekdaySymbols[day - 1]).font(.caption.bold())
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .foregroundStyle(profile.weekdays.contains(day) ? HangBrand.ink : Color.primary)
                                    .background(profile.weekdays.contains(day) ? HangBrand.lime : Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 12))
                            }.buttonStyle(.plain)
                                .accessibilityLabel(Calendar.current.weekdaySymbols[day - 1])
                                .accessibilityAddTraits(profile.weekdays.contains(day) ? [.isSelected] : [])
                        }
                    }
                }
                VStack(alignment: .leading, spacing: 10) {
                    Text("Time you have").font(.headline)
                    Picker("Session length", selection: $profile.minutes) {
                        ForEach([15, 25, 40], id: \.self) { Text("\($0) min").tag($0) }
                    }.pickerStyle(.segmented).labelsHidden()
                }
                DisclosureGroup("Exercises · \(profile.exercises.count) selected") {
                    ForEach(ExerciseCounter.Exercise.barExercises, id: \.rawValue) { exercise in
                        Toggle(exercise.title, isOn: Binding(get: { profile.exercises.contains(exercise) }, set: { selected in
                            if selected { profile.exercises.append(exercise) } else { profile.exercises.removeAll { $0 == exercise } }
                        })).padding(.top, 8)
                    }
                }.font(.subheadline)
            }.padding(22)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 10) {
                Button { onSave(profile) } label: {
                    Text("Make my plan").font(.headline).frame(maxWidth: .infinity).padding(16)
                        .foregroundStyle(HangBrand.ink).background(HangBrand.lime, in: RoundedRectangle(cornerRadius: 18))
                }.buttonStyle(.plain).disabled(!profile.isValid).opacity(profile.isValid ? 1 : 0.45)
                    .accessibilityIdentifier("saveCoachSetup")
                Button("Maybe later", action: onSkip).font(.subheadline).frame(maxWidth: .infinity, minHeight: 32)
            }.padding(.horizontal, 22).padding(.vertical, 12).background(.regularMaterial)
        }.hangTheme()
    }
}

struct WorkoutCoachView: View {
    var onStart: (WorkoutCoachRecommendation) -> Void
    private let previewHistory: [WorkoutCoachSet]?
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var preferences = WorkoutCoachPreferences.shared
    @State private var history: [WorkoutCoachSet] = []
    @State private var loggedHistory: [LoggedWorkout] = []
    @State private var suggestions: [WorkoutCoachRecommendation] = []
    @State private var error: String?
    @State private var editing = false
    @State private var movingDay: Date?
    @State private var destination = Date()
    @State private var starting = false
    @State private var loggingWorkout = false
    init(preferences: WorkoutCoachPreferences = .shared, previewHistory: [WorkoutCoachSet]? = nil,
         onStart: @escaping (WorkoutCoachRecommendation) -> Void) {
        self.onStart = onStart; self.previewHistory = previewHistory
        _preferences = State(initialValue: preferences)
    }
    private var today: Date { Calendar.current.startOfDay(for: Date()) }
    var body: some View {
        Group {
            if let profile = preferences.profile {
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        Text("\(profile.goal.title) · \(profile.minutes) min").font(.title2.bold())
                        if let error { Text(error).foregroundStyle(.orange).accessibilityIdentifier("coachError") }
                        week(profile)
                        #if os(iOS)
                        WorkoutReminderControls(profile: profile)
                        #endif
                        if suggestions.isEmpty && error == nil { ProgressView("Loading your plan…") }
                        if !suggestions.isEmpty {
                            Button { loggingWorkout = true } label: {
                                Label("Log this workout", systemImage: "square.and.pencil").font(.headline).frame(maxWidth: .infinity, minHeight: 48)
                            }.buttonStyle(.bordered).tint(HangPalette(scheme).accent)
                        }
                        ForEach(Array(suggestions.enumerated()), id: \.element.id) { index, item in
                            VStack(alignment: .leading, spacing: 14) {
                                Label(item.exercise.title, systemImage: item.exercise == .pullUp ? "figure.strengthtraining.functional" : "figure.strengthtraining.traditional").font(.title2.bold())
                                if let setup = setupLabel(item) { Text(setup).font(.caption.bold()).foregroundStyle(.teal) }
                                if let reps = item.reps {
                                    Text("\(item.sets) sets · \(reps) \(reps == 1 ? "rep" : "reps")").font(.title3.bold())
                                } else {
                                    Text("\(item.sets) practice sets").font(.title3.bold())
                                }
                                Label(String(format: "%d:%02d rest", item.restSeconds / 60, item.restSeconds % 60), systemImage: "timer")
                                    .font(.subheadline).foregroundStyle(.teal)
                                DisclosureGroup("Workout details") {
                                    VStack(alignment: .leading, spacing: 12) {
                                        Text(item.reason).font(.subheadline).foregroundStyle(.secondary)
                                        if let reps = item.reps {
                                            Stepper("Reps per set: \(reps)", value: Binding(get: { suggestions[index].reps ?? 1 }, set: { suggestions[index].reps = $0 }), in: 1...1000)
                                        }
                                        Stepper("Sets: \(item.sets)", value: $suggestions[index].sets, in: 1...6)
                                        Stepper("Rest: \(item.restSeconds / 60):\(String(format: "%02d", item.restSeconds % 60))", value: $suggestions[index].restSeconds, in: 60...300, step: 30)
                                    }.padding(.top, 8)
                                }.font(.subheadline)
                                let count = recordedToday(exercise: item.exercise)
                                if count > 0 { Label("\(count) recorded \(count == 1 ? "set" : "sets") today", systemImage: "checkmark.circle").font(.caption).foregroundStyle(.teal) }
                                Button {
                                    starting = true
                                    Task {
                                        do { try await WorkoutRecordStore.shared.saveCoachPlan(suggestions, date: today) }
                                        catch { self.error = "Your plan couldn't be saved. \(error.localizedDescription)" }
                                        onStart(item) // Planning/storage can never block starting a workout.
                                        starting = false
                                    }
                                } label: {
                                    Label("Start \(item.exercise == .pullUp ? "pull-ups" : "dips")", systemImage: "play.fill")
                                        .font(.headline).frame(maxWidth: .infinity).padding(14)
                                        .foregroundStyle(HangBrand.ink).background(HangBrand.lime, in: RoundedRectangle(cornerRadius: 16))
                                }.buttonStyle(.plain).disabled(starting)
                            }.journeyCard()
                        }
                        schedule(profile)
                    }.padding(20)
                }.background(HangPalette(scheme).background)
                    .navigationTitle("Today")
                    #if os(iOS)
                    .navigationBarTitleDisplayMode(.inline)
                    #endif
                    .toolbar { ToolbarItem(placement: .primaryAction) { Button("Edit plan", systemImage: "slider.horizontal.3") { editing = true } } }
                    .task { await load() }
            } else {
                WorkoutCoachSetupView(profile: .init(), onSave: { value in save(value) }, onSkip: { dismiss() })
            }
        }
        .hangTheme()
        .sheet(isPresented: $loggingWorkout, onDismiss: { Task { await load() } }) {
            NavigationStack { WorkoutLogView(recommendations: suggestions) }
        }
        .sheet(isPresented: $editing) {
            NavigationStack { WorkoutCoachSetupView(profile: preferences.profile ?? .init(), onSave: { save($0); editing = false }, onSkip: { editing = false }) }
        }
        .sheet(isPresented: Binding(get: { movingDay != nil }, set: { if !$0 { movingDay = nil } })) {
            NavigationStack {
                Form {
                    DatePicker("Practice on", selection: $destination, in: today..., displayedComponents: .date)
                }.navigationTitle("Make it fit")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { movingDay = nil } }
                        ToolbarItem(placement: .confirmationAction) { Button("Move") {
                            if let profile = preferences.profile, let source = movingDay {
                                save(WorkoutCoach.move(profile, from: source, to: destination))
                            }
                            movingDay = nil
                        } }
                    }
            }.presentationDetents([.medium, .large])
        }
    }
    private func recordedToday(exercise: ExerciseCounter.Exercise) -> Int {
        history.filter { entry in
            guard entry.exercise == exercise, let time = entry.trackedAtEpochMilliseconds else { return false }
            return WorkoutCoach.dayKey(Date(timeIntervalSince1970: Double(time) / 1000)) == WorkoutCoach.dayKey(today)
        }.count + loggedHistory.filter { Calendar.current.isDateInToday($0.date) }.flatMap(\.sets)
            .filter { LoggedWorkout.cameraExercise($0.exercise) == exercise }.count
    }
    private func setupLabel(_ item: WorkoutCoachRecommendation) -> String? {
        guard let source = item.source else { return nil }
        var parts: [String] = []
        if !source.context.variation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { parts.append(source.context.variation) }
        else if let grip = source.gripIdentity {
            let names = ["overhand": "Overhand", "underhand": "Underhand", "neutral": "Neutral", "narrow": "Narrow grip", "shoulderWidth": "Medium grip", "wide": "Wide grip"]
            parts += grip.split(separator: ":").compactMap { names[String($0)] }
        }
        if source.context.assistance == "none", source.context.addedLoadKg == 0 { parts.append("Bodyweight") }
        else if !source.context.assistanceLabel.isEmpty { parts.append(source.context.assistanceLabel) }
        if let load = source.context.addedLoadKg, load > 0 { parts.append("+\(load.formatted()) kg") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
    private func save(_ value: WorkoutCoachProfile) {
        do { try preferences.save(value); Task { await load() } }
        catch { self.error = "Your preferences couldn't be saved. Please try again." }
    }
    private func load() async {
        do {
            if let previewHistory { history = previewHistory }
            else {
                history = try await WorkoutRecordStore.shared.coachingHistory()
                loggedHistory = try await WorkoutRecordStore.shared.workoutLogs()
            }
            if let profile = preferences.profile {
                suggestions = profile.exercises.map { WorkoutCoach.recommendation(exercise: $0, profile: profile, history: history, loggedHistory: loggedHistory) }
            }
            error = nil
        } catch { self.error = "Workout history couldn't be loaded. You can still start a quick workout from Home." }
    }
    private func week(_ profile: WorkoutCoachProfile) -> some View {
        let dates = (0..<7).compactMap { Calendar.current.date(byAdding: .day, value: $0, to: today) }
        let planned = Set(WorkoutCoach.schedule(profile: profile, from: today).map { WorkoutCoach.dayKey($0) })
        return VStack(alignment: .leading, spacing: 12) {
            Text("Your week").font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 38))], spacing: 8) {
                ForEach(dates, id: \.self) { date in
                    let selected = planned.contains(WorkoutCoach.dayKey(date))
                    VStack(spacing: 7) {
                        Text(date, format: .dateTime.weekday(.narrow)).font(.caption)
                        Text(date, format: .dateTime.day()).font(.headline)
                        Image(systemName: selected ? "circle.fill" : "minus").font(.system(size: 6))
                    }.frame(maxWidth: .infinity).padding(.vertical, 10)
                        .foregroundStyle(selected ? HangBrand.ink : Color.secondary)
                        .background(selected ? HangBrand.lime : Color.primary.opacity(0.04), in: Capsule())
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("\(date.formatted(date: .complete, time: .omitted)), \(selected ? "practice day" : "rest day")")
                }
            }
            if !planned.contains(WorkoutCoach.dayKey(today)) {
                Label("Rest day · practice is optional", systemImage: "leaf").font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
    private func schedule(_ profile: WorkoutCoachProfile) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Coming up").font(.title3.bold())
            if let missed = unrecordedDay(profile) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("No session recorded for \(missed.formatted(.dateTime.weekday(.wide))).").font(.subheadline).foregroundStyle(.secondary)
                    Button("Reschedule") { destination = today; movingDay = missed }.font(.subheadline.bold())
                }
            }
            ForEach(Array(WorkoutCoach.schedule(profile: profile, from: today).prefix(4)), id: \.self) { date in
                HStack(spacing: 12) {
                    Image(systemName: "calendar").foregroundStyle(.teal)
                    Text(date.formatted(.dateTime.weekday(.wide).month(.abbreviated).day())).font(.subheadline)
                    Spacer()
                    Button("Move") { destination = date; movingDay = date }.font(.subheadline.bold())
                }
            }
        }.journeyCard()
    }
    private func unrecordedDay(_ profile: WorkoutCoachProfile) -> Date? {
        let joined = Calendar.current.startOfDay(for: Date(timeIntervalSince1970: Double(profile.startedAtEpochMilliseconds) / 1000))
        let recorded = WorkoutCoach.recordedDays(history: history, loggedHistory: loggedHistory)
        return (1...7).compactMap { Calendar.current.date(byAdding: .day, value: -$0, to: today) }.first { day in
            day >= joined && !recorded.contains(WorkoutCoach.dayKey(day)) &&
            WorkoutCoach.schedule(profile: profile, from: day).contains(day)
        }
    }
}

struct WorkoutCoachFeedbackView: View {
    let record: WorkoutSetRecord
    var planned: WorkoutCoachRecommendation? = nil
    var compact = false
    var onSaved: (() -> Void)? = nil
    @State private var selected: WorkoutCoachEffort?
    @State private var suggestion: WorkoutCoachRecommendation?
    @State private var error: String?
    @State private var saving = false
    var body: some View {
        Group {
            if compact { content } else { content.journeyCard() }
        }
            .task(id: record.id) {
                do {
                    selected = WorkoutCoachEffort(rating: try await WorkoutRecordStore.shared.coachingEffort(id: record.id))
                    if !compact, selected != nil { try await refreshSuggestion() }
                }
                catch { self.error = "Set feedback isn't available yet. Try again once the set is saved." }
            }
    }
    private var content: some View {
        VStack(alignment: .leading, spacing: compact ? 10 : 16) {
            Text("How did it feel?").font(compact ? .headline : .title3.bold())
            ViewThatFits(in: .horizontal) {
                HStack(spacing: compact ? 6 : 8) { choices }.fixedSize(horizontal: true, vertical: false)
                VStack(alignment: .leading, spacing: 8) { choices }
            }
            if let error { Text(error).font(.caption).foregroundStyle(.orange) }
            if !compact, let suggestion {
                Text(suggestion.reps.map { "Next set · try \($0) \($0 == 1 ? "rep" : "reps")" } ?? "Next set · practice").font(.headline)
                DisclosureGroup("Why this target?") {
                    Text(suggestion.reason).font(.subheadline).foregroundStyle(.secondary).padding(.top, 6)
                }.font(.subheadline)
            }
            if !compact, selected != nil {
                let duration = max(suggestion?.restSeconds ?? 120, planned?.restSeconds ?? 120)
                TimelineView(.periodic(from: .now, by: 1)) { clock in
                    let remaining = WorkoutCoachPreferences.shared.restingSetId == record.id ? WorkoutCoachPreferences.shared.restUntil.map { max(0, Int(ceil($0.timeIntervalSince(clock.date)))) } : nil
                    Button { WorkoutCoachPreferences.shared.startRest(setId: record.id, seconds: duration) } label: {
                        Label(remaining.map { $0 > 0 ? String(format: "Rest · %d:%02d", $0 / 60, $0 % 60) : "Rest done · ready when you are" } ?? String(format: "Rest for %d:%02d", duration / 60, duration % 60), systemImage: "timer")
                            .font(.subheadline.bold()).monospacedDigit()
                    }
                }
            }
        }
    }
    private var choices: some View {
        ForEach(WorkoutCoachEffort.allCases, id: \.rawValue) { effort in
            Button {
                saving = true
                Task {
                    do {
                        try await WorkoutRecordStore.shared.saveCoachingEffort(id: record.id, effort: effort)
                        selected = effort; error = nil
                        if !compact { try await refreshSuggestion() }
                        onSaved?()
                    } catch { self.error = "Feedback couldn't be saved. Please try again." }
                    saving = false
                }
            } label: {
                VStack(spacing: 6) {
                    Image(systemName: effort.symbol).font(.title3)
                    Text(effort.title).font(.caption.weight(.semibold)).multilineTextAlignment(.center)
                }.frame(minWidth: compact ? 56 : 64, minHeight: 44)
                    .padding(compact ? 6 : 11)
                    .foregroundStyle(selected == effort ? (compact ? Color.mint : Color.teal) : (compact ? Color.white : Color.primary))
                    .background(selected == effort ? Color.teal.opacity(compact ? 0.3 : 0.15) : Color.primary.opacity(compact ? 0.1 : 0.05), in: Capsule())
            }.buttonStyle(.plain).disabled(saving).accessibilityAddTraits(selected == effort ? [.isSelected] : [])
                .accessibilityIdentifier("coachEffort-\(effort.rawValue)")
        }
    }
    private func refreshSuggestion() async throws {
        if let profile = WorkoutCoachPreferences.shared.profile {
            let history = try await WorkoutRecordStore.shared.coachingHistory()
            suggestion = WorkoutCoach.recommendation(exercise: record.exercise, profile: profile, history: history)
        }
    }
}

#if os(iOS)
private struct WorkoutReminderControls: View {
    let profile: WorkoutCoachProfile
    @State private var reminders = WorkoutReminders.shared
    @State private var changing = false
    @Environment(\.openURL) private var openURL
    private var time: Binding<Date> {
        Binding(get: {
            Calendar.current.date(bySettingHour: reminders.settings.hour, minute: reminders.settings.minute, second: 0, of: Date()) ?? Date()
        }, set: { date in
            let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
            Task { await reminders.setTime(hour: parts.hour ?? 18, minute: parts.minute ?? 0, profile: profile) }
        })
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle(isOn: Binding(get: { reminders.settings.enabled }, set: { enabled in
                changing = true
                Task { await reminders.setEnabled(enabled, profile: profile); changing = false }
            })) {
                Label("Workout reminders", systemImage: "bell").font(.headline)
            }.disabled(changing || reminders.isUpdating).accessibilityIdentifier("workoutReminders")
            if reminders.settings.enabled {
                DatePicker("Time", selection: time, displayedComponents: .hourAndMinute)
                    .disabled(changing).accessibilityIdentifier("workoutReminderTime")
                if let next = reminders.nextReminder {
                    Text("Next · \(next.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day().hour().minute()))")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            if let error = reminders.error {
                Text(error).font(.caption).foregroundStyle(.orange)
                if reminders.authorization == .denied {
                    Button("Open Settings") {
                        if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
                    }.font(.subheadline)
                } else {
                    Button("Try again") {
                        Task {
                            if reminders.settings.enabled { await reminders.refresh(profile: profile) }
                            else { await reminders.setEnabled(true, profile: profile) }
                        }
                    }.font(.subheadline)
                }
            }
        }.journeyCard()
    }
}
#endif
