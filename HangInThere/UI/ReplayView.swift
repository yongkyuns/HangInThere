import SwiftUI
import UniformTypeIdentifiers

@MainActor
struct ReplayView: View {
    @State private var model = ReplayController()
    @State private var importing = false
    @State private var barSetup: BarSetupFrame?
    @State private var showPoseOverlay = true
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if model.frame == nil {
                        emptyState
                    } else {
                        sessionHeader
                        preview
                        playbackControls
                        movementCard
                        setupCard
                        if let message = model.errorMessage {
                            errorCard(message)
                        }
                        technicalDetails
                    }
                }
                .padding()
            }
            .navigationTitle(model.frame == nil ? "HangInThere" : "Workout Review")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if model.frame != nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Close video", systemImage: "xmark") { model.close() }
                            .accessibilityIdentifier("closeVideo")
                    }
                }
            }
            .fileImporter(
                isPresented: $importing,
                allowedContentTypes: [.movie],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first { model.open(url) }
                case .failure(let error):
                    model.reportImportFailure(error)
                }
            }
            .sheet(item: $barSetup) { setup in
                BarSetupView(setup: setup) { bar in
                    model.confirmBar(bar, for: setup)
                }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase != .active { model.pause() }
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 22) {
            Spacer(minLength: 32)

            ZStack {
                Circle()
                    .fill(.thinMaterial)
                    .frame(width: 96, height: 96)
                Image(systemName: "figure.strengthtraining.traditional")
                    .font(.system(size: 42, weight: .semibold))
                    .accessibilityHidden(true)
            }

            VStack(spacing: 8) {
                Text("Review your workout")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                Text("Import a pull-up or parallel-bar dip video to see joint tracking, set the bar reference, and count movement cycles.")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 520)
            }

            Button {
                importing = true
            } label: {
                Label("Choose workout video", systemImage: "video.badge.plus")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityIdentifier("importVideo")

            Label("Video analysis stays on this device.", systemImage: "lock.fill")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Spacer(minLength: 32)
        }
        .frame(maxWidth: 620)
        .frame(maxWidth: .infinity)
    }

    private var sessionHeader: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(model.counter.exercise.title)
                    .font(.title2.bold())
                Text("Video analysis")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Label(trackingStatusTitle, systemImage: trackingStatusIcon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(trackingStatusColor)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(.thinMaterial, in: Capsule())
                .accessibilityLabel(trackingStatusAccessibility)
        }
    }

    private var preview: some View {
        ZStack {
            Color.black

            if let frame = model.frame {
                Image(decorative: frame.image, scale: 1, orientation: .up)
                    .resizable()
                    .scaledToFit()

                if showPoseOverlay {
                    PoseOverlay(result: frame.pose, selectedSide: model.counter.side)
                }

                BarOverlay(imageSize: frame.pose.imageSize, bar: model.currentBar)

                VStack {
                    HStack(alignment: .top) {
                        Text(model.counter.exercise.title)
                            .font(.caption.weight(.semibold))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(.ultraThinMaterial, in: Capsule())

                        Spacer()

                        VStack(alignment: .trailing, spacing: 0) {
                            Text("\(model.counter.observedMovements)")
                                .font(.system(size: 42, weight: .bold, design: .rounded))
                                .monospacedDigit()
                                .contentTransition(.numericText())
                            Text("movements")
                                .font(.caption.weight(.semibold))
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
                    }

                    Spacer()

                    HStack {
                        if model.currentBar != nil {
                            Label("Bar reference set", systemImage: "checkmark.circle.fill")
                                .foregroundStyle(.white)
                        } else {
                            Label("Set bar to count", systemImage: "line.diagonal")
                                .foregroundStyle(.white)
                        }

                        Spacer()

                        Button {
                            showPoseOverlay.toggle()
                        } label: {
                            Image(systemName: showPoseOverlay ? "eye" : "eye.slash")
                                .frame(width: 32, height: 32)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.white)
                        .accessibilityLabel(showPoseOverlay ? "Hide joint overlay" : "Show joint overlay")
                    }
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 8)
                    .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 12))
                }
                .padding(10)
            } else if model.phase == .loading {
                ProgressView()
                    .tint(.white)
            }
        }
        .aspectRatio(previewAspectRatio, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .frame(maxHeight: 560)
        .clipShape(RoundedRectangle(cornerRadius: 20))
        .overlay {
            RoundedRectangle(cornerRadius: 20)
                .stroke(.white.opacity(0.08), lineWidth: 1)
        }
        .accessibilityLabel("Workout video with body joint and bar overlays")
        .accessibilityIdentifier("posePreview")
    }

    private var playbackControls: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                Button {
                    model.restart()
                } label: {
                    Label("Restart", systemImage: "backward.end.fill")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .disabled(!model.canRestart)
                .accessibilityIdentifier("restartReplay")

                Button {
                    if model.phase == .playing { model.pause() }
                    else { model.play() }
                } label: {
                    Label(primaryPlaybackTitle, systemImage: primaryPlaybackIcon)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!model.canPlay && model.phase != .playing)
                .accessibilityIdentifier("playPause")

                Button {
                    model.pause()
                    importing = true
                } label: {
                    Label("Replace", systemImage: "folder")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("importVideo")
            }
            .controlSize(.large)

            HStack {
                Text(model.phase.rawValue)
                Spacer()
                Text(String(format: "%.1f / %.1f s", model.elapsed, model.durationSeconds))
                    .monospacedDigit()
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            ProgressView(value: model.progress)
                .accessibilityLabel("Replay progress")
        }
    }

    private var movementCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Movement count")
                        .font(.headline)
                    Text(model.currentBar == nil ? "Set the bar reference to enable counting." : model.counter.phase.title)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text("\(model.counter.observedMovements)")
                    .font(.system(size: 48, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .contentTransition(.numericText())
                    .accessibilityIdentifier("movementCount")
            }

            Divider()

            HStack(spacing: 16) {
                metric("Partial", value: model.counter.partialAttempts)
                metric("Interrupted", value: model.counter.interruptedAttempts)
                Spacer()
                Text("MOVEMENT ONLY")
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 9)
                    .padding(.vertical, 6)
                    .background(.thinMaterial, in: Capsule())
            }

            if let issue = model.counter.trackingIssue, model.currentBar != nil {
                Label(trackingIssueMessage(issue), systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
            }

            Text("Form scoring is not enabled yet. Chin clearance and strict dip depth are not verified.")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var setupCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Workout setup")
                        .font(.headline)
                    Text("Choose the exercise and the athlete’s clearest visible arm.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: model.currentBar == nil ? "1.circle.fill" : "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(model.currentBar == nil ? .orange : .green)
                    .accessibilityHidden(true)
            }

            Picker(
                "Exercise",
                selection: Binding(
                    get: { model.counter.exercise },
                    set: { model.configureCounting(exercise: $0, side: model.counter.side) }
                )
            ) {
                ForEach(ExerciseCounter.Exercise.allCases, id: \.rawValue) { exercise in
                    Text(exercise.title).tag(exercise)
                }
            }
            .pickerStyle(.segmented)

            Picker(
                "Tracking side",
                selection: Binding(
                    get: { model.counter.side },
                    set: { model.configureCounting(exercise: model.counter.exercise, side: $0) }
                )
            ) {
                ForEach(ArmMeasurement.Side.allCases, id: \.rawValue) { side in
                    Text(side.rawValue.capitalized).tag(side)
                }
            }
            .pickerStyle(.segmented)

            Divider()

            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Label(
                        model.currentBar == nil ? "Bar reference needed" : model.barRole.title,
                        systemImage: model.currentBar == nil ? "line.diagonal" : "checkmark.circle.fill"
                    )
                    .font(.subheadline.weight(.semibold))

                    Text(barSetupHelp)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Button(model.currentBar == nil ? "Set bar" : "Adjust") {
                    barSetup = model.beginBarSetup()
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.frame == nil || model.phase == .loading || model.phase == .failed)
                .accessibilityIdentifier("setupBar")
            }

            if model.currentBar != nil {
                Button("Clear bar reference", role: .destructive) {
                    model.clearBar()
                }
                .font(.caption)
            }
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
        .disabled(model.phase == .loading)
    }

    private var technicalDetails: some View {
        DisclosureGroup {
            VStack(alignment: .leading, spacing: 16) {
                if let name = model.sourceName {
                    LabeledContent("Video", value: name)
                        .font(.caption)
                }

                if let frame = model.frame {
                    elbowMeasurements(frame.pose)
                    diagnostics(frame)
                }

                Text("The skeleton is a per-frame 2D Vision estimate. Perspective and occlusion can make a confident joint estimate wrong.")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Text("Imported video remains local to the app. Audio is not played.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(.top, 10)
        } label: {
            Label("Tracking details", systemImage: "waveform.path.ecg.rectangle")
                .font(.subheadline.weight(.semibold))
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private func errorCard(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .font(.callout.weight(.semibold))
                .foregroundStyle(.red)
                .accessibilityIdentifier("replayError")

            if let report = model.failureReport {
                ShareLink(item: report) {
                    Label("Share technical details", systemImage: "square.and.arrow.up")
                }
                .font(.callout)
                .accessibilityIdentifier("shareReplayFailure")

                Text("The shared report excludes the video and filename.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private func metric(_ title: String, value: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)")
                .font(.title3.bold())
                .monospacedDigit()
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private func elbowMeasurements(_ pose: PoseResult) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("2D elbow estimates")
                .font(.subheadline.weight(.semibold))

            ForEach(ArmMeasurement.Side.allCases, id: \.rawValue) { side in
                let measurement = ArmMeasurement(pose: pose, side: side)
                HStack {
                    Text("\(side.rawValue.capitalized) elbow")
                    Spacer()
                    if let estimate = measurement.estimate {
                        Text(String(format: "%.0f°", estimate.elbowDegrees))
                            .monospacedDigit()
                    } else {
                        Text(unavailableMessage(measurement.unavailableReason))
                            .foregroundStyle(.secondary)
                    }
                }
                .font(.subheadline)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("\(side.rawValue)ElbowMeasurement")
            }
        }
    }

    private func diagnostics(_ frame: ProcessedFrame) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            let people = frame.pose.people.filter { $0.visibleLandmarkCount > 0 }
            LabeledContent("Skeletons", value: "\(people.count)")
            LabeledContent(
                "Visible joints",
                value: "\(people.reduce(0) { $0 + $1.visibleLandmarkCount })"
            )
            LabeledContent(
                "Pose backend",
                value: "\(frame.pose.backend) · rev \(frame.pose.requestRevision)"
            )
            LabeledContent("Frame", value: "\(model.displayedFrames)")
            LabeledContent(
                "Processing",
                value: String(format: "%.1f ms", frame.processingMilliseconds)
            )
            LabeledContent(
                "Source time",
                value: String(format: "%.3f s", frame.pose.timestamp.seconds)
            )
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .monospacedDigit()
    }

    private func unavailableMessage(_ reason: ArmMeasurement.UnavailableReason?) -> String {
        switch reason {
        case .noPerson: "No person"
        case .multiplePeople: "Multiple people"
        case .invalidImageSize: "Invalid image"
        case .missingJoint: "Joint missing"
        case .duplicateJoint, .invalidJoint: "Unusable joint"
        case .lowConfidence: "Low confidence"
        case .shortProjectedSegment: "Too small"
        case nil: "Unavailable"
        }
    }

    private func trackingIssueMessage(_ issue: String) -> String {
        switch issue {
        case "barReferenceUnavailable": "Bar reference unavailable. Set the bar again."
        case "lowConfidence": "Selected arm is not clear enough in this frame."
        case "missingJoint": "A required arm joint is hidden."
        case "multiplePeople": "More than one person is visible."
        case "sourceTimeGap": "Tracking was interrupted by a timing gap."
        default: "Tracking paused. Re-establish the starting position."
        }
    }

    private var previewAspectRatio: CGFloat {
        guard let size = model.frame?.pose.imageSize, size.isValid else { return 4.0 / 3.0 }
        return CGFloat(size.width / size.height)
    }

    private var primaryPlaybackTitle: String {
        if model.phase == .playing { return "Pause" }
        return model.currentBar == nil ? "Preview" : "Analyze"
    }

    private var primaryPlaybackIcon: String {
        model.phase == .playing ? "pause.fill" : "play.fill"
    }

    private var barSetupHelp: String {
        if let bar = model.currentBar {
            return "Fixed from source \(bar.sourceTime.seconds, specifier: "%.2f") s. Replay restarts so every counted frame uses this reference."
        }
        return "Preview to a clear frame, pause, then mark the gripping bar or selected dip rail."
    }

    private var trackingStatusTitle: String {
        if model.phase == .finished { return "Complete" }
        if model.currentBar == nil { return "Setup needed" }
        if model.counter.trackingIssue != nil { return "Tracking paused" }
        return "Ready"
    }

    private var trackingStatusIcon: String {
        if model.phase == .finished { return "checkmark.circle.fill" }
        if model.currentBar == nil { return "wrench.and.screwdriver.fill" }
        if model.counter.trackingIssue != nil { return "exclamationmark.triangle.fill" }
        return "checkmark.circle.fill"
    }

    private var trackingStatusColor: Color {
        if model.phase == .finished { return .green }
        if model.currentBar == nil { return .orange }
        if model.counter.trackingIssue != nil { return .orange }
        return .green
    }

    private var trackingStatusAccessibility: String {
        switch trackingStatusTitle {
        case "Setup needed": "Workout setup needed. Set the bar reference before counting."
        case "Tracking paused": "Tracking paused because required measurements are unavailable."
        case "Complete": "Workout replay complete."
        default: "Workout is ready for analysis."
        }
    }
}
