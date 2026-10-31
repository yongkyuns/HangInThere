import SwiftUI
import Charts

/// Decorative movement cues keep the exercise name as the accessible label.
struct WorkoutExerciseIcon: View {
    let exercise: String
    var size: CGFloat = 44
    @Environment(\.colorScheme) private var scheme
    private var artwork: String {
        switch exercise.lowercased().filter({ $0.isLetter }) {
        case "pullup", "pullups", "chinup", "chinups": "ExercisePullUp"
        case "dip", "dips", "parallelbardip", "parallelbardips": "ExerciseDip"
        case "pushup", "pushups": "ExercisePushUp"
        case "bench", "benchpress", "barbellbenchpress", "dumbbellbenchpress": "ExerciseBench"
        case "ezcurl", "ezcurls", "curl", "curls", "bicepcurl", "bicepcurls", "barbellcurls", "dumbbellcurls", "hammercurls": "ExerciseCurl"
        case "squat", "squats", "backsquat", "frontsquat", "gobletsquat": "ExerciseSquat"
        case "row", "rows", "barbellrow", "barbellrows", "dumbbellrow", "dumbbellrows", "seatedrows", "bentoverrows": "ExerciseRow"
        default: "ExerciseOther"
        }
    }
    var body: some View {
        Image(artwork).resizable().scaledToFit().padding(size * 0.12)
            .frame(width: size, height: size).foregroundStyle(HangPalette(scheme).accent)
            .background(HangPalette(scheme).garden, in: RoundedRectangle(cornerRadius: size * 0.25))
            .accessibilityHidden(true)
    }
}

@MainActor
struct WorkoutLogView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var log: LoggedWorkout
    @State private var history: [LoggedWorkout] = []
    @State private var cameraSets: [JourneySetSummary]
    @State private var exercise: String
    @State private var reps = "5"
    @State private var load = ""
    @State private var unit = "lb"
    @State private var saving = false
    @State private var error: String?
    @State private var choosingExercise = false
    @State private var editingWeight = false
    @State private var editingSet: LoggedWorkoutSet?
    @State private var counter: LoggedWorkoutSet?
    @State private var showingCamera = false
    @State private var showingProgress = false
    private let recommendations: [WorkoutCoachRecommendation]
    private let preview: Bool
    init(initial: LoggedWorkout = .init(), cameraSets: [JourneySetSummary] = [],
         recommendations: [WorkoutCoachRecommendation] = [], preview: Bool = false) {
        _log = State(initialValue: initial); _cameraSets = State(initialValue: cameraSets)
        _exercise = State(initialValue: initial.sets.last?.exercise ?? recommendations.first?.exercise.title ?? "Pull-ups")
        self.recommendations = recommendations; self.preview = preview
    }
    private var palette: HangPalette { HangPalette(scheme) }
    private var parsedLoad: Double? { Double(load.replacingOccurrences(of: Locale.current.decimalSeparator ?? ".", with: ".")) }
    private var validForCounting: Bool {
        (load.isEmpty || parsedLoad != nil) && LoggedWorkout.validSet(exercise: exercise, reps: 0, load: parsedLoad, unit: unit)
    }
    private var valid: Bool {
        (load.isEmpty || parsedLoad != nil) && LoggedWorkout.validSet(exercise: exercise, reps: Int(reps), load: parsedLoad, unit: unit)
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                HStack {
                    DatePicker("Workout date", selection: Binding(get: { log.date }, set: { date in
                        var next = log; next.epochMilliseconds = Int64(date.timeIntervalSince1970 * 1000)
                        if log.sets.isEmpty { log = next } else { Task { await save(next) } }
                    }), in: ...Date(), displayedComponents: .date).labelsHidden()
                    Spacer()
                    Text("\(log.sets.count + cameraSets.count) sets · \(log.reps + cameraSets.reduce(0) { $0 + $1.completedReps }) reps")
                        .font(.subheadline.weight(.semibold)).monospacedDigit()
                }.disabled(saving || !cameraSets.isEmpty)
                if !recommendations.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack { ForEach(recommendations) { item in
                            Button { select(item.exercise.title) } label: {
                                HStack(spacing: 10) {
                                    WorkoutExerciseIcon(exercise: item.exercise.title, size: 36)
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(item.exercise.title).font(.subheadline.bold())
                                        Text("\(item.sets) sets" + (item.reps.map { " × \($0)" } ?? " · comfortable reps")).font(.caption)
                                    }
                                }.padding(12).background(palette.garden, in: RoundedRectangle(cornerRadius: 14))
                            }.buttonStyle(.plain)
                        } }
                    }
                }
                VStack(alignment: .leading, spacing: 16) {
                    Button { choosingExercise = true } label: {
                        HStack(spacing: 12) {
                            WorkoutExerciseIcon(exercise: exercise)
                            Text(exercise).font(.title2.bold()); Spacer()
                            Image(systemName: "chevron.up.chevron.down").font(.subheadline)
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityIdentifier("chooseLogExercise")
                    DisclosureGroup(isExpanded: $editingWeight) {
                        let fields = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(alignment: .leading, spacing: 14)) : AnyLayout(HStackLayout(alignment: .top, spacing: 14))
                        fields {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Weight (optional)").font(.caption).foregroundStyle(palette.secondary)
                                TextField("Optional", text: $load).workoutNumberKeyboard(decimal: true)
                                    .hangInput().accessibilityIdentifier("logWeight")
                            }
                            Picker("Weight unit", selection: Binding(get: { unit }, set: { next in
                                if let value = parsedLoad, value.isFinite, value >= 0, next != unit {
                                    load = LoggedWorkout.convertLoad(value, from: unit, to: next)
                                        .formatted(.number.grouping(.never).precision(.fractionLength(0...2)))
                                }
                                unit = next
                            })) { Text("lb").tag("lb"); Text("kg").tag("kg") }
                                .pickerStyle(.menu).labelsHidden().frame(width: typeSize.isAccessibilitySize ? 120 : 58)
                                .padding(.top, typeSize.isAccessibilitySize ? 0 : 20)
                        }
                    } label: {
                        Label(load.isEmpty ? "Add weight (optional)" : "\(load) \(unit)", systemImage: "scalemass")
                            .font(.subheadline.weight(.semibold))
                    }
                    Button {
                        counter = .init(exercise: exercise.trimmingCharacters(in: .whitespacesAndNewlines),
                            reps: 0, load: parsedLoad, unit: unit, method: "tap")
                    } label: {
                        Label("Count reps", systemImage: "plus.circle").font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .foregroundStyle(HangBrand.ink).background(HangBrand.lime, in: RoundedRectangle(cornerRadius: 14))
                    }.buttonStyle(.plain).accessibilityIdentifier("openTapCounter")
                        .disabled(!validForCounting || saving)
                    Divider()
                    Text("Log a finished set").font(.subheadline.weight(.semibold))
                    HStack {
                        Text("Reps").font(.subheadline)
                        Spacer()
                        TextField("5", text: $reps).workoutNumberKeyboard().hangInput()
                            .frame(maxWidth: 100).accessibilityIdentifier("logReps")
                    }
                    Button { Task { await add(entry()) } } label: {
                        Label("Log set", systemImage: "checkmark").font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(palette.garden, in: RoundedRectangle(cornerRadius: 14))
                    }.buttonStyle(.plain).accessibilityIdentifier("saveLoggedSet").disabled(!valid || saving)
                    #if os(iOS)
                    if LoggedWorkout.cameraExercise(exercise) != nil && Calendar.current.isDateInToday(log.date) {
                        Button { showingCamera = true } label: { Label("Count with camera", systemImage: "camera") }
                            .font(.subheadline.weight(.semibold)).disabled(saving)
                    }
                    #endif
                }.journeyCard().disabled(saving)
                if let error { Label(error, systemImage: "exclamationmark.circle").font(.callout).foregroundStyle(.orange) }
                if log.sets.isEmpty && cameraSets.isEmpty {
                    ContentUnavailableView("Your next set starts here", systemImage: "square.and.pencil",
                        description: Text("Add a result or open the tap counter."))
                }
                WorkoutLogTable(log: log) { editingSet = $0 }.disabled(saving)
                if !cameraSets.isEmpty {
                    Text("Camera sets").font(.headline)
                    ForEach(cameraSets) { set in
                        HStack { Label(set.exercise.title, systemImage: "camera"); Spacer(); Text("\(set.completedReps) reps").bold() }.journeyCard()
                    }
                }
            }.padding(20)
        }.hangTheme()
            .navigationTitle("Workout log")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() }.disabled(saving) }
                ToolbarItem(placement: .primaryAction) { Button("Exercise progress", systemImage: "chart.bar") { showingProgress = true } }
            }
            .interactiveDismissDisabled(saving)
            .task { if !preview { await reload() }; prefill() }
            .sheet(isPresented: $choosingExercise) {
                NavigationStack { WorkoutExercisePicker(names: Array(Set(history.flatMap(\.exercises) + log.exercises))) { select($0); choosingExercise = false } }
            }
            .sheet(item: $counter) { set in
                NavigationStack { WorkoutTapCounter(set: set) { result in
                    var next = log; next.sets.append(result)
                    try await persist(next); reps = String(result.reps); counter = nil
                } }
            }
            .sheet(item: $editingSet) { set in
                NavigationStack { WorkoutLoggedSetEditor(set: set, onSave: { result in
                    var next = log; if let index = next.sets.firstIndex(where: { $0.id == result.id }) { next.sets[index] = result }
                    try await persist(next); editingSet = nil
                }, onDelete: {
                    var next = log; next.sets.removeAll { $0.id == set.id }
                    try await persist(next); editingSet = nil
                }) }
            }
            .sheet(isPresented: $showingProgress) { NavigationStack { WorkoutLogProgressView(history: history) } }
            #if os(iOS)
            .fullScreenCover(isPresented: $showingCamera, onDismiss: { Task { await reload() } }) {
                LiveSetupView(initialExercise: LoggedWorkout.cameraExercise(exercise), onCompletedSet: { record in
                    Task {
                        do { try await WorkoutRecordStore.shared.linkCameraSet(record, to: log, load: parsedLoad, unit: unit); await reload() }
                        catch { self.error = "The camera set was saved, but couldn't be linked. \(error.localizedDescription)" }
                    }
                })
            }
            #endif
    }
    private func entry() -> LoggedWorkoutSet { .init(exercise: exercise.trimmingCharacters(in: .whitespacesAndNewlines), reps: Int(reps) ?? 0, load: parsedLoad, unit: unit) }
    private func select(_ name: String) {
        editingWeight = false
        exercise = (log.exercises + history.flatMap(\.exercises)).first { LoggedWorkout.exerciseKey($0) == LoggedWorkout.exerciseKey(name) } ?? name
        prefill()
    }
    private func prefill() {
        if let last = log.prefill(for: exercise, history: history) {
            unit = last.unit; load = last.load.map { $0.formatted(.number.grouping(.never).precision(.fractionLength(0...2))) } ?? ""; reps = String(last.reps)
        } else {
            load = ""; reps = String(recommendations.first(where: { $0.exercise.title == exercise })?.reps ?? 5)
        }
    }
    private func add(_ set: LoggedWorkoutSet) async { var next = log; next.sets.append(set); await save(next) }
    private func save(_ next: LoggedWorkout) async { do { try await persist(next) } catch { self.error = error.localizedDescription } }
    private func persist(_ value: LoggedWorkout) async throws {
        saving = true; defer { saving = false }
        let next = try await WorkoutRecordStore.shared.saveWorkoutLog(value, updatingTimestamp: true)
        log = next; history.removeAll { $0.id == next.id }; history.append(next)
        history.sort { $0.epochMilliseconds > $1.epochMilliseconds }; error = nil
    }
    private func reload() async {
        do {
            history = try await WorkoutRecordStore.shared.workoutLogs()
            if let saved = history.first(where: { $0.id == log.id }) { log = saved }
            cameraSets = try await WorkoutRecordStore.shared.workoutSummary(id: log.id)?.sets ?? cameraSets
        } catch { self.error = error.localizedDescription }
    }
}

struct WorkoutLogTable: View {
    let log: LoggedWorkout
    var onEdit: (LoggedWorkoutSet) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize
    var body: some View {
        ForEach(log.exercises, id: \.self) { exercise in
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 10) {
                    WorkoutExerciseIcon(exercise: exercise, size: 36)
                    Text(exercise).font(.headline)
                }
                if !typeSize.isAccessibilitySize {
                    HStack { Text("Set").frame(width: 44, alignment: .leading); Text("Weight").frame(maxWidth: .infinity, alignment: .leading); Text("Reps").frame(width: 42); Text("Rest").frame(width: 48) }
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(Array(log.sets.filter { $0.exercise == exercise }.enumerated()), id: \.element.id) { index, set in
                    Button { onEdit(set) } label: {
                        if typeSize.isAccessibilitySize {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Set \(index + 1) · \(set.reps) reps").bold()
                                Text(set.loadLabel)
                                if let rest = log.restBefore(set) { Text("\(Int(rest))s rest").foregroundStyle(.secondary) }
                            }.frame(maxWidth: .infinity, minHeight: 44, alignment: .leading).padding(.vertical, 8)
                        } else {
                        HStack {
                            Text("\(index + 1)").frame(width: 44, alignment: .leading)
                            Text(set.loadLabel).frame(maxWidth: .infinity, alignment: .leading)
                            Text("\(set.reps)").bold().frame(width: 42)
                            Text(log.restBefore(set).map { String(format: "%d:%02d", Int($0) / 60, Int($0) % 60) } ?? "—").frame(width: 48)
                        }.font(.subheadline).monospacedDigit().frame(minHeight: 44).contentShape(Rectangle())
                        }
                    }.buttonStyle(.plain).accessibilityLabel("\(exercise), set \(index + 1), \(set.loadLabel), \(set.reps) reps, edit")
                }
            }.journeyCard()
        }
    }
}

struct WorkoutExercisePicker: View {
    let names: [String]
    var onSelect: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var search = ""
    private var query: String { search.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var suggestions: [String] { Array(Set(names + LoggedWorkout.suggestedExercises)).sorted().filter { query.isEmpty || $0.localizedCaseInsensitiveContains(query) } }
    var body: some View {
        List {
            ForEach(suggestions, id: \.self) { name in
                Button { onSelect(name) } label: {
                    HStack(spacing: 12) {
                        WorkoutExerciseIcon(exercise: name)
                        Text(name).frame(maxWidth: .infinity, alignment: .leading)
                    }.frame(minHeight: 52).contentShape(Rectangle())
                }.buttonStyle(.plain).listRowBackground(HangPalette(scheme).surface)
            }
            if !query.isEmpty && query.count <= 80 && !suggestions.contains(where: { $0.caseInsensitiveCompare(query) == .orderedSame }) {
                Button("Use “\(query)”", systemImage: "plus") { onSelect(query) }.listRowBackground(HangPalette(scheme).surface)
            }
        }.hangTheme().searchable(text: $search, prompt: "Find or add an exercise").navigationTitle("Exercise")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Back") { dismiss() } } }
    }
}

struct WorkoutTapCounter: View {
    let set: LoggedWorkoutSet
    var onFinish: (LoggedWorkoutSet) async throws -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var count = 0
    @State private var started = Date()
    @State private var saving = false
    @State private var error: String?
    var body: some View {
        VStack(spacing: 24) {
            HStack(spacing: 16) {
                WorkoutExerciseIcon(exercise: set.exercise, size: 80)
                Text(set.exercise).font(.title2.bold())
                Spacer(minLength: 0)
            }.accessibilityElement(children: .combine)
            Spacer()
            Button { if count < 1000 { count += 1 } } label: {
                VStack(spacing: 8) {
                    Text("\(count)").font(.system(size: 100, weight: .bold, design: .rounded)).monospacedDigit().contentTransition(.numericText())
                    Label("Tap for a rep", systemImage: "plus").font(.headline)
                }.frame(maxWidth: .infinity, minHeight: 250).foregroundStyle(HangBrand.ink)
                    .background(HangBrand.lime, in: RoundedRectangle(cornerRadius: 32))
            }.buttonStyle(.plain).accessibilityLabel("Add rep").accessibilityValue("\(count)").accessibilityIdentifier("tapRep")
            Button("Undo rep", systemImage: "minus.circle") { count = max(0, count - 1) }.disabled(count == 0)
            Spacer()
            if let error { Text(error).foregroundStyle(.orange) }
            Button {
                var result = set; result.reps = count
                result.startedAtEpochMilliseconds = Int64(started.timeIntervalSince1970 * 1000)
                result.endedAtEpochMilliseconds = Int64(Date().timeIntervalSince1970 * 1000)
                saving = true
                Task { do { try await onFinish(result) } catch { self.error = error.localizedDescription }; saving = false }
            } label: { Text("Finish set").font(.headline).frame(maxWidth: .infinity, minHeight: 54) }
                .buttonStyle(.plain).foregroundStyle(HangBrand.ink).background(HangBrand.lime, in: RoundedRectangle(cornerRadius: 16))
                .accessibilityIdentifier("finishTappedSet")
        }.padding(24).hangTheme().navigationTitle("Count reps")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(count > 0 ? "Discard" : "Back") { dismiss() } } }
            .disabled(saving).interactiveDismissDisabled(count > 0 || saving)
    }
}

struct WorkoutLoggedSetEditor: View {
    @State var set: LoggedWorkoutSet
    var onSave: (LoggedWorkoutSet) async throws -> Void
    var onDelete: () async throws -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var saving = false
    @State private var error: String?
    var body: some View {
        Form {
            Section(set.exercise) {
                LabeledContent("Reps") {
                    TextField("Reps", value: $set.reps, format: .number).labelsHidden().workoutNumberKeyboard().multilineTextAlignment(.trailing).frame(maxWidth: 140)
                }
                LabeledContent("Weight · \(set.unit)") {
                    TextField("Optional", value: $set.load, format: .number).labelsHidden().workoutNumberKeyboard(decimal: true).multilineTextAlignment(.trailing).frame(maxWidth: 140)
                }
                if set.load == nil { Text("No weight entered").foregroundStyle(.secondary) }
            }.listRowBackground(HangPalette(scheme).surface)
            if let error { Text(error).foregroundStyle(.orange) }
            Button("Delete set", role: .destructive) { perform { try await onDelete() } }
                .foregroundStyle(.red).listRowBackground(HangPalette(scheme).surface)
        }.hangTheme().navigationTitle("Edit set").disabled(saving).interactiveDismissDisabled(saving)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Back") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { perform { try await onSave(set) } }
                    .disabled(!LoggedWorkout.validSet(exercise: set.exercise, reps: set.reps, load: set.load, unit: set.unit)) }
            }
    }
    private func perform(_ action: @escaping () async throws -> Void) {
        saving = true
        Task { do { try await action() } catch { self.error = error.localizedDescription }; saving = false }
    }
}

struct WorkoutLogProgressView: View {
    let history: [LoggedWorkout]
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var exercise = ""
    private var names: [String] { Array(Set(history.flatMap(\.exercises))).sorted() }
    private var selected: String { names.contains(exercise) ? exercise : names.first ?? "" }
    var body: some View {
        ScrollView {
            let progress = LoggedWorkout.progress(history: history, exercise: selected)
            VStack(alignment: .leading, spacing: 24) {
                if names.isEmpty { ContentUnavailableView("Log your first set", systemImage: "chart.bar") }
                else {
                    Picker("Exercise", selection: Binding(get: { selected }, set: { exercise = $0 })) { ForEach(names, id: \.self) { Text($0).tag($0) } }
                    HStack {
                        JourneyMetric(value: "\(progress.setCount)", label: "Logged sets")
                        JourneyMetric(value: "\(progress.reps)", label: "Reps")
                        JourneyMetric(value: "\(progress.bestReps)", label: "Most reps / set")
                    }
                    Text("Reps per workout").font(.headline)
                    Chart(progress.workouts) { point in
                        BarMark(x: .value("Workout", point.date, unit: .day), y: .value("Reps", point.reps))
                            .foregroundStyle(HangPalette(scheme).accent)
                    }.frame(height: 200)
                    Text("Recent sets").font(.headline)
                    ForEach(progress.recentSets) { set in HStack { Text(set.loadLabel); Spacer(); Text("\(set.reps) reps").bold() } }
                }
            }.padding(20)
        }.hangTheme().navigationTitle("Logged progress")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
    }
}

private extension View {
    @ViewBuilder func workoutNumberKeyboard(decimal: Bool = false) -> some View {
        #if os(iOS)
        keyboardType(decimal ? .decimalPad : .numberPad)
        #else
        self
        #endif
    }
}
