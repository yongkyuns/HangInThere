import SwiftUI
import Charts
import WorkoutCore

/// Summaries first; motion curves and recording evidence live on rep routes.
/// Opening analysis never pauses, configures or resets the camera.
struct WorkoutReviewView: View {
    let sets: [WorkoutSetRecord]
    let currentReps: [WorkoutRepRecord]
    let storageError: String?
    let currentSet: WorkoutSetRecord?
    let goalsEnabled: Bool
    let showsLibraryLink: Bool
    let selectedRepId: String?
    private let suppliedSummary: JourneySetSummary?
    @State private var summary: JourneySetSummary?
    @State private var summaryError: String?
    @State private var showsRating = false
    @ScaledMetric(relativeTo: .largeTitle) private var resultSize = 60.0

    init(sets: [WorkoutSetRecord], currentReps: [WorkoutRepRecord], storageError: String?, selectedRepId: String? = nil,
         currentSet: WorkoutSetRecord? = nil, goalsEnabled: Bool = true, showsLibraryLink: Bool = true,
         summary: JourneySetSummary? = nil) {
        self.sets = sets; self.currentReps = currentReps; self.storageError = storageError
        self.currentSet = currentSet; self.goalsEnabled = goalsEnabled; self.showsLibraryLink = showsLibraryLink
        self.selectedRepId = selectedRepId; self.suppliedSummary = summary
        _summary = State(initialValue: summary)
    }
    private var record: WorkoutSetRecord? { currentSet ?? sets.first { set in set.reps.contains { $0.id == selectedRepId } } ?? sets.first }
    private var sourceReps: [WorkoutRepRecord] { currentSet.map { $0.reps } ?? (sets.isEmpty ? currentReps : sets.flatMap(\.reps)) }
    private var summaryKey: String { record.map { $0.id + $0.endReason + String($0.reps.count) + String($0.reps.last?.closedAt ?? 0) } ?? "empty" }
    private var completed: [JourneyRepSummary] { summary?.reps.filter { $0.status == "completed" } ?? [] }
    private var practice: [JourneyRepSummary] { summary?.reps.filter { $0.status != "completed" && $0.status != "excluded" } ?? [] }
    private var maximumRepSeconds: Double { max(1, completed.compactMap(\.totalSeconds).max() ?? 0) }
    private var showsSourcePhases: Bool {
        guard let record, let summary else { return false }
        return suppliedSummary == nil || summary.id == record.id && summary.exercise == record.exercise &&
            summary.completedReps == record.completedCount && summary.reportedReps == 0 &&
            summary.practiceAttempts == record.reps.count - record.completedCount
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 24) {
                if let storageError { Text(storageError).font(.caption).foregroundStyle(.orange) }
                if let requested = sourceReps.first(where: { $0.id == selectedRepId }) {
                    WorkoutRepDetailView(rep: requested, endReason: record?.endReason)
                } else if let summary {
                    headline(summary)
                    JourneyCoachingCard(coaching: summary.coaching)
                    if !completed.isEmpty {
                        VStack(alignment: .leading, spacing: 14) {
                            HStack { Text("Your reps").font(.title3.bold()); Spacer(); Text("Tap to explore").font(.caption).foregroundStyle(.secondary) }
                            HStack(spacing: 14) {
                                phaseLegend(summary.exercise == .pullUp ? "Pull" : "Lower", color: .blue)
                                phaseLegend(summary.exercise == .pullUp ? "Lower" : "Press", color: Color.blue.opacity(0.5))
                            }.font(.caption)
                            ForEach(completed) { rep in repLink(rep) }
                        }
                    }
                    if !practice.isEmpty {
                        DisclosureGroup("Other attempts · \(practice.count)") {
                            VStack(spacing: 12) { ForEach(practice) { rep in repLink(rep) } }.padding(.top, 12)
                        }
                    }
                    if let record, record.activity != nil, showsSourcePhases {
                        DisclosureGroup("Phase breakdown") { WorkoutPracticeView(set: record).padding(.top, 12) }
                    }
                    if let record, record.exercise == .pullUp, showsSourcePhases {
                        DisclosureGroup("Grip and variation") { WorkoutGripView(set: record, allowsCorrections: goalsEnabled).padding(.top, 12) }
                    }
                } else if let summaryError {
                    ContentUnavailableView("Summary unavailable", systemImage: "exclamationmark.circle", description: Text(summaryError))
                } else if record != nil { ProgressView() }
                else { ContentUnavailableView("Your next workout starts here", systemImage: "figure.strengthtraining.traditional") }
            }.padding()
        }.navigationTitle(selectedRepId == nil ? "Set summary" : "Rep details")
            .toolbar {
                if showsLibraryLink && goalsEnabled && selectedRepId == nil {
                    ToolbarItem(placement: .primaryAction) {
                        NavigationLink { WorkoutLibraryView() } label: { Label("History", systemImage: "calendar") }
                    }
                }
            }
            .task(id: summaryKey) {
                guard suppliedSummary == nil, let record else { return }
                do {
                    let input = String(decoding: try JSONEncoder().encode(record.progressInput), as: UTF8.self)
                    let json = try WorkoutJourneyBridge().setJson(recordJson: input)
                    try Task.checkCancellation()
                    summary = try JSONDecoder().decode(JourneySetSummary.self, from: Data(json.utf8))
                    summaryError = nil
                } catch is CancellationError { } catch { summaryError = error.localizedDescription }
            }
    }
    private func headline(_ set: JourneySetSummary) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .top) {
                Text(set.exercise.title).font(.subheadline.weight(.medium)).foregroundStyle(.secondary)
                Spacer(minLength: 8)
                Button { showsRating = true } label: { JourneyQualityBadge(quality: set.quality) }.buttonStyle(.plain)
                    .popover(isPresented: $showsRating) { Text(set.quality.explanation).font(.subheadline).padding().frame(idealWidth: 280) }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(set.completedReps > 0 ? "\(set.completedReps)" : set.holdSeconds.flatMap { $0 > 0 ? seconds($0) : nil } ?? "Practice")
                    .font(.system(size: resultSize, weight: .semibold, design: .rounded)).monospacedDigit()
                Text(set.completedReps > 0 ? (set.completedReps == 1 ? "completed rep" : "completed reps") :
                    ((set.holdSeconds ?? 0) > 0 ? (set.exercise == .pullUp ? "Longest hang" : "Support hold") : "Holds and attempts"))
                    .font(.headline).foregroundStyle(.secondary)
            }.accessibilityElement(children: .combine)
            if set.completedReps > 0 && set.holdSeconds != nil || set.outwardSeconds != nil || set.returnSeconds != nil {
                HStack(alignment: .top, spacing: 12) {
                    if set.completedReps > 0, let value = set.holdSeconds { JourneyMetric(value: seconds(value), label: set.exercise == .pullUp ? "Longest hang" : "Support hold") }
                    if let value = set.outwardSeconds { JourneyMetric(value: seconds(value), label: set.exercise == .pullUp ? "To peak" : "To bottom") }
                    if let value = set.returnSeconds { JourneyMetric(value: seconds(value), label: "Return") }
                }
            }
            if set.reportedReps > 0 { Text("\(set.reportedReps) user-reported \(set.reportedReps == 1 ? "rep" : "reps")").font(.caption).foregroundStyle(.secondary) }
        }.journeyCard()
    }
    @ViewBuilder private func repLink(_ item: JourneyRepSummary) -> some View {
        if let rep = sourceReps.first(where: { $0.id == item.id }) {
            NavigationLink {
                ScrollView { WorkoutRepDetailView(rep: rep, endReason: sets.first { $0.id == rep.setId }?.endReason ?? record?.endReason, status: item.status).padding() }
                    .navigationTitle(item.status == "completed" ? "Rep \(item.ordinal)" : "Attempt \(item.ordinal)")
            } label: {
                VStack(spacing: 10) {
                    HStack {
                        Label(item.status == "completed" ? "Rep \(item.ordinal)" : "Attempt \(item.ordinal)", systemImage: item.quality.symbol)
                            .font(.subheadline.weight(.semibold)).foregroundStyle(item.quality.color)
                        Spacer()
                        Text(item.status == "completed" ? item.totalSeconds.map(seconds) ?? "—" : item.status == "reported" ? "Reported" : item.status == "unobserved" ? "Unconfirmed" : "Practice")
                            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        Image(systemName: "chevron.right").font(.caption2.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                    if item.status == "completed", item.totalSeconds != nil {
                        GeometryReader { geometry in
                            HStack(spacing: 2) {
                                Capsule().fill(Color.blue).frame(width: max(0, geometry.size.width * (item.outwardSeconds ?? 0) / maximumRepSeconds - 1))
                                Capsule().fill(Color.blue.opacity(0.35)).frame(width: max(0, geometry.size.width * (item.returnSeconds ?? 0) / maximumRepSeconds - 1))
                                Spacer(minLength: 0)
                            }
                        }.frame(height: 6).accessibilityHidden(true)
                    }
                }.padding(14).background(Color.primary.opacity(0.035), in: RoundedRectangle(cornerRadius: 14))
            }.buttonStyle(.plain)
        }
    }
    private func phaseLegend(_ title: String, color: Color) -> some View {
        Label { Text(title).foregroundStyle(.secondary) } icon: { Image(systemName: "circle.fill").foregroundStyle(color) }
    }
    private func seconds(_ value: Double) -> String { String(format: "%.1fs", value) }
}

struct WorkoutRepDetailView: View {
    let rep: WorkoutRepRecord
    let endReason: String?
    var status: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text((status == "completed" || status == nil && rep.isCompleteForAnalytics) ? "Rep \(rep.ordinal)" : "Attempt \(rep.ordinal)").font(.title2.bold())
            if status != "completed", !rep.isCompleteForAnalytics,
               let assessment = try? rep.evidenceAssessment(setEndReason: endReason) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(assessment.title).font(.headline)
                    if let reason = assessment.reason { Text(reason).font(.subheadline).foregroundStyle(.secondary) }
                }
            }
            if !rep.samples.isEmpty { angleChart(rep) }
            else { ContentUnavailableView("Motion curve unavailable", systemImage: "chart.xyaxis.line", description: Text("The timing summary is saved.")) }
            HStack(alignment: .top, spacing: 16) {
                JourneyMetric(value: seconds(rep.outboundSeconds), label: rep.exercise == .pullUp ? "To peak" : "To bottom")
                JourneyMetric(value: seconds(rep.returnSeconds), label: "Return")
            }
            DisclosureGroup("Camera measurements") {
                Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 10) {
                    GridRow { Text("Left mean / range"); Text(angleText(rep.left)).monospacedDigit() }
                    GridRow { Text("Right mean / range"); Text(angleText(rep.right)).monospacedDigit() }
                }.font(.subheadline).padding(.top, 12)
            }
            DisclosureGroup("Original recording") {
                WorkoutEvidenceView(assessment: try? rep.evidenceAssessment(setEndReason: endReason)).padding(.top, 12)
            }
            if rep.curveTruncated && !rep.samples.isEmpty { Text("The curve shows recent motion; timing uses the full observation.").font(.caption).foregroundStyle(.secondary) }
        }
    }
    private struct AnglePoint: Identifiable {
        let id: Int; let time: Double; let value: Double; let arm: String; let segment: String
    }
    private func points(_ rep: WorkoutRepRecord) -> [AnglePoint] {
        var result: [AnglePoint] = []
        for arm in ["Left", "Right"] {
            var segment = 0; var previous: Double?
            for sample in rep.samples {
                let angle = arm == "Left" ? sample.leftDegrees : sample.rightDegrees
                guard sample.usable, let angle else { segment += 1; previous = nil; continue }
                if let previous, sample.seconds - previous > ExerciseCounter.maximumGapSeconds { segment += 1 }
                result.append(AnglePoint(id: result.count, time: sample.seconds - rep.startedAt,
                    value: angle, arm: arm, segment: "\(arm)-\(segment)"))
                previous = sample.seconds
            }
        }
        return result
    }
    private func angleChart(_ rep: WorkoutRepRecord) -> some View {
        Chart(points(rep)) { point in
            LineMark(x: .value("Seconds", point.time), y: .value("Elbow angle", point.value),
                series: .value("Segment", point.segment))
                .foregroundStyle(by: .value("Arm", point.arm))
                .accessibilityLabel("\(point.arm) elbow").accessibilityValue("\(Int(point.value)) degrees")
        }
        .chartYScale(domain: 0...180).chartXAxisLabel("Seconds from observed start")
        .chartYAxisLabel("Elbow angle (°)")
        .chartForegroundStyleScale(["Left": Color.cyan, "Right": Color.orange])
        .frame(minHeight: 220, maxHeight: 320)
    }
    private func seconds(_ value: Double?) -> String { value.map { String(format: "%.2f s", $0) } ?? "Unavailable" }
    private func angleText(_ metric: WorkoutAngleMetrics) -> String {
        guard let mean = metric.mean, let min = metric.minimum, let max = metric.maximum else { return "Unavailable" }
        return String(format: "%.0f° / %.0f–%.0f°", mean, min, max)
    }
}
