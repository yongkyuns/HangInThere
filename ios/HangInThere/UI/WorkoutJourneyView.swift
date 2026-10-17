import SwiftUI

extension JourneyQuality {
    var color: Color {
        switch level {
        case "steady": .teal
        case "pace", "range": .orange
        case "practice": .indigo
        case "limited": .secondary
        case "tracking": .cyan
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
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "sparkle").font(.title3).foregroundStyle(.teal).padding(.top, 2)
            VStack(alignment: .leading, spacing: 5) {
                Text(coaching.title).font(.headline)
                Text(coaching.message).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }.journeyCard()
    }
}
private struct JourneyCard: ViewModifier {
    func body(content: Content) -> some View {
        content.padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 20))
            .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.primary.opacity(0.06), lineWidth: 1))
    }
}
extension View {
    func journeyCard() -> some View { modifier(JourneyCard()) }
}

/// A month of practice is the overview; workout, set and rep routes reveal detail.
struct WorkoutJourneyView: View {
    private struct Query: Equatable { let day: Int64?; let overview: JourneyOverview }
    let overview: JourneyOverview
    @State private var selectedDay: Int64?
    @State private var loadedWorkouts: [JourneyWorkoutSummary]?
    @State private var nextCursor: JourneyCursor?
    @State private var loading = false
    @State private var failure: String?
    @State private var requestID = UUID()
    private var visibleWorkouts: [JourneyWorkoutSummary] {
        if let loadedWorkouts { return loadedWorkouts }
        guard let selectedDay, let day = overview.days.first(where: { $0.day == selectedDay }) else { return overview.workouts }
        return overview.workouts.filter { day.workoutIds.contains($0.id) }
    }
    var body: some View {
        LazyVStack(alignment: .leading, spacing: 24) {
            VStack(alignment: .leading, spacing: 16) {
                Text("Last 28 days").font(.subheadline).foregroundStyle(.secondary)
                HStack(alignment: .top) {
                    JourneyMetric(value: "\(overview.completedReps)", label: "Completed reps")
                    JourneyMetric(value: "\(overview.workoutCount)", label: "Workouts")
                    JourneyMetric(value: "\(overview.practiceDays)", label: "Practice days")
                }
            }
            JourneyCoachingCard(coaching: overview.coaching)
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("Your practice").font(.headline)
                    Spacer()
                    if selectedDay != nil { Button("Show all") { selectedDay = nil }.font(.caption) }
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 7), spacing: 6) {
                    ForEach(overview.days) { day in
                        Button { selectedDay = selectedDay == day.day ? nil : day.day } label: {
                            VStack(spacing: 4) {
                                Text(dayLabel(day.day, format: "d")).font(.caption.monospacedDigit()).foregroundStyle(.primary)
                                Image(systemName: day.quality?.symbol ?? "minus").font(.caption2)
                            }.frame(maxWidth: .infinity, minHeight: 44)
                                .foregroundStyle(day.quality?.color ?? .secondary.opacity(0.5))
                                .background((day.quality?.color ?? Color.secondary).opacity(day.quality == nil ? 0.04 : 0.12), in: RoundedRectangle(cornerRadius: 9))
                                .overlay(RoundedRectangle(cornerRadius: 9).stroke(selectedDay == day.day ? Color.primary : Color.clear, lineWidth: 1.5))
                        }.buttonStyle(.plain)
                            .accessibilityLabel("\(dayLabel(day.day, format: "EEEE, MMMM d")), \(day.quality?.title ?? "Rest day"), \(day.completedReps) completed reps")
                    }
                }
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 14) { legend("Consistent", .teal); legend("Varied", .orange); legend("Recorded", .blue) }
                    HStack(spacing: 14) { legend("Practice", .indigo); legend("Limited view", .secondary) }
                }
            }
            Text(selectedDay.map { dayLabel($0, format: "MMMM d") } ?? "Recent workouts").font(.title3.bold())
            if visibleWorkouts.isEmpty {
                ContentUnavailableView(selectedDay == nil ? "Your first workout starts here" : "No recent workout on this day",
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
        }.task(id: Query(day: selectedDay, overview: overview)) {
            if selectedDay == nil { requestID = UUID(); loading = false; loadedWorkouts = nil; nextCursor = overview.nextCursor; failure = nil }
            else { await load(more: false) }
        }
    }
    private func load(more: Bool) async {
        let request = UUID(); requestID = request
        if !more { loadedWorkouts = nil; nextCursor = nil; failure = nil }
        loading = true
        defer { if requestID == request { loading = false } }
        do {
            let cursor = more ? (loadedWorkouts == nil && selectedDay == nil ? overview.nextCursor : nextCursor) : nil
            let input = try cursor.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) }
            let json = try await WorkoutRecordStore.shared.libraryJourneyPage(day: selectedDay, cursor: input)
            try Task.checkCancellation()
            guard requestID == request else { return }
            let page = try JSONDecoder().decode(JourneyPage.self, from: Data(json.utf8))
            loadedWorkouts = more ? (loadedWorkouts ?? overview.workouts) + page.workouts : page.workouts
            nextCursor = page.nextCursor; failure = nil
        } catch is CancellationError { } catch { if requestID == request { failure = error.localizedDescription } }
    }
    private func legend(_ title: String, _ color: Color) -> some View {
        HStack(spacing: 4) { Circle().fill(color).frame(width: 5, height: 5); Text(title).font(.caption2).foregroundStyle(.secondary) }
    }
    private func dayLabel(_ day: Int64, format: String) -> String {
        let formatter = DateFormatter(); formatter.timeZone = TimeZone(secondsFromGMT: 0); formatter.dateFormat = format
        return formatter.string(from: Date(timeIntervalSince1970: Double(day) * 86400))
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
                    Text(workout.exercises.map(\.title).joined(separator: " · ")).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 4)
                if showsDisclosure { Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary) }
            }
            HStack {
                Text("\(workout.completedReps) reps · \(workout.setCount) \(workout.setCount == 1 ? "set" : "sets")").font(.subheadline.weight(.medium))
                Spacer(minLength: 4)
                JourneyQualityBadge(quality: workout.quality)
            }
        }.journeyCard().accessibilityElement(children: .combine)
    }
}
struct JourneyWorkoutView: View {
    let workout: JourneyWorkoutSummary
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 22) {
                JourneyWorkoutRow(workout: workout, showsDisclosure: false)
                Text("Sets").font(.title2.bold())
                ForEach(Array(workout.sets.enumerated()), id: \.element.id) { index, set in
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
            }.padding()
        }.navigationTitle("Workout")
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
