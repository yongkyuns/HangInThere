import SwiftUI
import UniformTypeIdentifiers

@MainActor
struct ReplayView: View {
    @State private var model = ReplayController()
    @State private var importing = false
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("WORKOUT REPLAY · MOVEMENT COUNT")
                        .font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    countingSetup
                    preview
                    countingSummary
                    if let name = model.sourceName {
                        Text(name).font(.headline).lineLimit(2)
                    }
                    HStack {
                        Text(model.phase.rawValue)
                        Spacer()
                        if model.frame != nil {
                            Text(String(format: "%.2f / %.2f s", model.elapsed, model.durationSeconds))
                                .monospacedDigit()
                        }
                    }.font(.subheadline)
                    ProgressView(value: model.progress)
                        .accessibilityLabel("Replay progress")
                    ViewThatFits(in: .horizontal) {
                        controls.labelStyle(.titleAndIcon).fixedSize(horizontal: true, vertical: false)
                        controls.labelStyle(.iconOnly)
                    }
                    if let message = model.errorMessage {
                        Label(message, systemImage: "exclamationmark.triangle")
                            .font(.callout).foregroundStyle(.red)
                            .accessibilityIdentifier("replayError")
                        if let report = model.failureReport {
                            ShareLink(item: report) {
                                Label("Share failure details", systemImage: "square.and.arrow.up")
                            }
                            .font(.callout)
                            .accessibilityIdentifier("shareReplayFailure")
                            Text("Shares technical details only, not your video or filename.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    if let frame = model.frame {
                        elbowMeasurements(frame.pose)
                        diagnostics(frame)
                    }
                    Text("Import an MP4 or MOV from Files. Image and pose come from the same decoded frame; replay may slow down to keep them aligned. Audio is not played.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Text("Movement counting is provisional. Chin-over-bar, dip depth and form are not verified. Keep the camera fixed and the selected arm visible. Live capture is not implemented.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .padding()
            }
            .navigationTitle("HangInThere")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Close video", systemImage: "xmark") { model.close() }
                        .disabled(model.phase == .idle)
                        .accessibilityIdentifier("closeVideo")
                }
            }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.movie], allowsMultipleSelection: false) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first { model.open(url) }
                case .failure(let error): model.reportImportFailure(error)
                }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase != .active { model.pause() }
            }
        }
    }

    private var countingSetup: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("Exercise", selection: Binding(
                get: { model.counter.exercise },
                set: { model.configureCounting(exercise: $0, side: model.counter.side) })) {
                ForEach(ExerciseCounter.Exercise.allCases, id: \.rawValue) { exercise in
                    Text(exercise.title).tag(exercise)
                }
            }.pickerStyle(.segmented)
            Picker("Visible anatomical arm", selection: Binding(
                get: { model.counter.side },
                set: { model.configureCounting(exercise: model.counter.exercise, side: $0) })) {
                ForEach(ArmMeasurement.Side.allCases, id: \.rawValue) { side in
                    Text(side.rawValue.capitalized).tag(side)
                }
            }.pickerStyle(.segmented)
            Text("Choose the athlete’s visible left or right arm, not the screen side. Changing this restarts the video and clears counts.")
                .font(.caption).foregroundStyle(.secondary)
        }.disabled(model.phase == .loading)
    }

    private var countingSummary: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text("\(model.counter.observedMovements)")
                    .font(.largeTitle.bold()).monospacedDigit()
                    .accessibilityIdentifier("movementCount")
                Text("observed movements").font(.headline)
            }
            Text("Form unverified").font(.subheadline.weight(.semibold))
            Text(model.counter.phase.title).font(.subheadline)
            Text("Partial attempts: \(model.counter.partialAttempts) · Interrupted: \(model.counter.interruptedAttempts)")
                .font(.caption).monospacedDigit()
            if let issue = model.counter.trackingIssue {
                Text("Tracking unavailable (\(issue)). Re-establish the extended starting position.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }.accessibilityElement(children: .contain)
    }

    private var preview: some View {
        ZStack {
            Color.black
            if let frame = model.frame {
                Image(decorative: frame.image, scale: 1, orientation: .up)
                    .resizable().scaledToFit()
                PoseOverlay(result: frame.pose)
            } else if model.phase == .loading {
                ProgressView().tint(.white)
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "figure.strengthtraining.traditional").font(.largeTitle)
                    Text("Import a workout video").font(.headline)
                    Text("Your video stays on this device.").font(.subheadline)
                }.foregroundStyle(.white)
            }
        }
        .aspectRatio(4.0 / 3.0, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityLabel("Video with body landmark overlay")
        .accessibilityIdentifier("posePreview")
    }

    private var controls: some View {
        HStack(spacing: 12) {
            Button("Import", systemImage: "folder") {
                model.pause()
                importing = true
            }.buttonStyle(.bordered).accessibilityIdentifier("importVideo")
            Button(model.phase == .playing ? "Pause" : "Play",
                   systemImage: model.phase == .playing ? "pause.fill" : "play.fill") {
                if model.phase == .playing { model.pause() } else { model.play() }
            }
            .buttonStyle(.borderedProminent)
            .disabled(!model.canPlay && model.phase != .playing)
            .accessibilityIdentifier("playPause")
            Button("Restart", systemImage: "backward.end") { model.restart() }
                .buttonStyle(.bordered).disabled(!model.canRestart)
                .accessibilityIdentifier("restartReplay")
        }
    }

    private func elbowMeasurements(_ pose: PoseResult) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Elbow estimates · image plane").font(.subheadline.weight(.semibold))
            ForEach(ArmMeasurement.Side.allCases, id: \.rawValue) { side in
                let measurement = ArmMeasurement(pose: pose, side: side)
                HStack {
                    Text("\(side.rawValue.capitalized) elbow")
                    Spacer()
                    if let estimate = measurement.estimate {
                        Text(String(format: "%.0f°", estimate.elbowDegrees)).monospacedDigit()
                    } else {
                        Text(unavailableMessage(measurement.unavailableReason))
                            .foregroundStyle(.secondary)
                    }
                }
                .font(.subheadline)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("\(side.rawValue)ElbowMeasurement")
            }
            Text("Per-frame 2D estimates, not form verdicts. Perspective and hidden joints can still make a confident estimate wrong.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private func unavailableMessage(_ reason: ArmMeasurement.UnavailableReason?) -> String {
        switch reason {
        case .noPerson: "No person detected"
        case .multiplePeople: "Multiple people"
        case .invalidImageSize: "Invalid image geometry"
        case .missingJoint: "Required joint missing"
        case .duplicateJoint, .invalidJoint: "Unusable joint data"
        case .lowConfidence: "Low joint confidence"
        case .shortProjectedSegment: "Arm segment too small"
        case nil: "Unavailable"
        }
    }

    private func diagnostics(_ frame: ProcessedFrame) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            let people = frame.pose.people.filter { $0.visibleLandmarkCount > 0 }
            if people.isEmpty {
                Label("No confident body landmarks in this frame", systemImage: "person.crop.rectangle.badge.exclamationmark")
            } else {
                Text("\(people.count) skeleton(s) · \(people.reduce(0) { $0 + $1.visibleLandmarkCount }) visible landmarks")
            }
            Text("\(frame.pose.backend), revision \(frame.pose.requestRevision) · frame \(model.displayedFrames)")
            Text(String(format: "%.1f ms processing · source timestamp %.3f s",
                        frame.processingMilliseconds, frame.pose.timestamp.seconds))
            Text("Processing time is a local diagnostic, not a measured iPhone FPS claim.")
        }.font(.caption).foregroundStyle(.secondary).monospacedDigit()
    }
}
