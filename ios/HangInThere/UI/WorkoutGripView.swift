import SwiftUI

struct WorkoutGripView: View {
    let set: WorkoutSetRecord
    var allowsCorrections = true
    var initiallyExpanded = false
    @State private var attemptsExpanded = false
    @State private var report: WorkoutGripVariationReport?
    @State private var error: String?
    @State private var editing = false
    @State private var target = "all"
    @State private var orientation = "auto"
    @State private var width = "auto"
    @State private var saving = false
    private var reportKey: String {
        self.set.id + ":" + set.endReason + ":" + String(set.reps.count) + ":" +
            (set.setGrip?.orientation ?? "") + ":" + (set.setGrip?.reason ?? "")
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Grip variations").font(.title3.bold())
            Text("Grip width is hand spacing ÷ shoulder width, measured from a stable starting hang. Narrow, medium and wide are relative to your shoulders.")
                .font(.caption).foregroundStyle(.secondary)
            if let report {
                if let metadata = report.setGrip {
                    Text("Set grip · " + metadata.title).font(.headline)
                    Text(metadata.isConfirmed ? "Identified during this set and applied to its reps."
                         : metadata.reason == "conflictingSetGripEvidence" ? "Grip estimates disagree within this set."
                         : "Waiting for a clear grip view during this set.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                ForEach(Array(report.totals.enumerated()), id: \.offset) { _, total in
                    HStack { Text(total.label); Spacer(); Text("\(total.completedReps) \(total.completedReps == 1 ? "rep" : "reps")").monospacedDigit() }
                }
                DisclosureGroup("Per attempt", isExpanded: $attemptsExpanded) {
                    ForEach(report.reps) { rep in
                        VStack(alignment: .leading, spacing: 3) {
                            Text("\(rep.ordinal) · \(rep.label)").font(.subheadline)
                            Text(rep.orientationSource == "user" || rep.widthSource == "user" ? "Includes your correction"
                                 : rep.orientationSource == "setEstimated" ? (report.setGrip?.isConfirmed == true ? "Grip inferred from this set" : "Set grip unknown") : "Automatic estimate")
                                .font(.caption).foregroundStyle(.secondary)
                            if let coverage = rep.measured?.orientationCoverage {
                                Text("Both hands classified for \(Int(coverage * 100))% of this attempt").font(.caption).foregroundStyle(.secondary)
                            }
                            if rep.widthSource != "user", let measured = rep.measured {
                                if let ratio = measured.shoulderRatioLabel {
                                    Text("Hand spacing · " + ratio).font(.subheadline.monospacedDigit())
                                }
                                if let coverage = measured.widthCoverage {
                                    Text("Width measured for \(Int(coverage * 100))% of this attempt")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }.padding(.vertical, 4)
                    }
                }
                if allowsCorrections && set.endReason != "running" && !report.reps.isEmpty {
                    Button("Correct grip labels") { target = "all"; orientation = "auto"; width = "auto"; editing = true }
                }
            }
            if let error { Text(error).font(.caption).foregroundStyle(.orange) }
        }
        .onAppear { attemptsExpanded = initiallyExpanded }
        .task(id: reportKey) { await refresh() }
        .sheet(isPresented: $editing) {
            NavigationStack {
                Form {
                    Picker("Apply to", selection: $target) {
                        Text("Whole set").tag("all")
                        ForEach(report?.reps ?? []) { Text("Attempt \($0.ordinal)").tag($0.repId) }
                    }
                    Picker("Orientation", selection: $orientation) {
                        Text("Automatic estimate").tag("auto"); Text("Underhand / chin-up").tag("underhand")
                        Text("Overhand").tag("overhand"); Text("Neutral").tag("neutral")
                        Text("Mixed").tag("mixed"); Text("Unknown").tag("unknown")
                    }
                    Picker("Width", selection: $width) {
                        Text("Automatic estimate").tag("auto"); Text("Narrow").tag("narrow")
                        Text("Medium (shoulder-width)").tag("shoulderWidth"); Text("Wide").tag("wide"); Text("Unknown").tag("unknown")
                    }
                    Text("Saving replaces labels for the selected attempts. Automatic estimate restores the original measurement. Rep counts and motion analytics are preserved.")
                        .font(.caption).foregroundStyle(.secondary)
                    if let error { Text(error).foregroundStyle(.orange) }
                }.navigationTitle("Correct grip")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { editing = false }.disabled(saving) }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Save") {
                                saving = true
                                Task {
                                    do {
                                        try await WorkoutGripCorrectionStore.shared.correct(set, repId: target == "all" ? nil : target,
                                            orientation: orientation == "auto" ? nil : orientation, width: width == "auto" ? nil : width)
                                        await refresh(); editing = false
                                    } catch { self.error = error.localizedDescription }
                                    saving = false
                                }
                            }.disabled(saving)
                        }
                    }
            }
        }
    }
    private func refresh() async {
        do { report = try await WorkoutGripCorrectionStore.shared.report(set, useCorrections: allowsCorrections); error = nil }
        catch { self.error = "Grip labels could not be loaded: \(error.localizedDescription)" }
    }
}
