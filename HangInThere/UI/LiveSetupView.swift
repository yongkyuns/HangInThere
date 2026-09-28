import SwiftUI

struct LiveSetupGuide: Equatable, Sendable {
    let exercise: ExerciseCounter.Exercise
    let side: ArmMeasurement.Side

    var selectedArmText: String {
        "\(side.rawValue.capitalized) shoulder, elbow, and wrist"
    }

    var apparatusText: String {
        switch exercise {
        case .pullUp:
            "Keep the gripping bar visible across the selected hand."
        case .dip:
            "Keep the selected dip rail and selected hand visible."
        }
    }

    var bodyText: String {
        switch exercise {
        case .pullUp:
            "Keep the selected arm, torso, hips, and as much of the legs as practical in frame."
        case .dip:
            "Use a side-oriented view with the selected arm, torso, hips, and rail clearly visible."
        }
    }
}

@MainActor
struct LiveSetupView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var camera = LiveCameraPreviewController()
    @State private var exercise: ExerciseCounter.Exercise = .pullUp
    @State private var side: ArmMeasurement.Side = .left
    @State private var barSetup: BarSetupFrame?

    private var guide: LiveSetupGuide {
        LiveSetupGuide(exercise: exercise, side: side)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    intro
                    cameraSurface
                    workoutSelection
                    framingChecklist
                    barCalibrationCard
                    readyStateCard
                    scopeNote
                }
                .padding()
            }
            .navigationTitle("Camera setup")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .sheet(item: $barSetup) { setup in
                BarSetupView(setup: setup) { bar in
                    camera.confirmBar(bar, for: setup)
                }
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
            .task {
                camera.configureWorkout(exercise: exercise, side: side)
                await camera.start()
            }
            .onChange(of: exercise) { _, newExercise in
                camera.configureWorkout(exercise: newExercise, side: side)
                barSetup = nil
            }
            .onChange(of: side) { _, newSide in
                camera.configureWorkout(exercise: exercise, side: newSide)
                barSetup = nil
            }
            .onDisappear { camera.stop() }
        }
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Position the phone")
                .font(.title2.bold())
            Text("Use the rear camera and keep the phone fixed for the full set. Live setup does not use the microphone.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
    }

    private var cameraSurface: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 20)
                .fill(.black)

            if camera.isCameraReady {
                LiveCameraPreviewSurface(session: camera.session)
                    .clipShape(RoundedRectangle(cornerRadius: 20))

                if let imageSize = camera.latestImageSize {
                    BarOverlay(imageSize: imageSize, bar: camera.currentBar)
                }

                framingGuide
            } else {
                cameraStatus
                    .padding(24)
            }
        }
        .aspectRatio(9.0 / 16.0, contentMode: .fit)
        .frame(maxWidth: 620)
        .frame(maxWidth: .infinity)
        .overlay {
            RoundedRectangle(cornerRadius: 20)
                .stroke(.white.opacity(0.08), lineWidth: 1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Rear camera setup preview")
    }

    private var framingGuide: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 28)
                .strokeBorder(
                    .white.opacity(0.82),
                    style: StrokeStyle(lineWidth: 2, dash: [9, 7])
                )
                .padding(.horizontal, 34)
                .padding(.vertical, 26)

            VStack {
                HStack {
                    Label(exercise.title, systemImage: "figure.strengthtraining.traditional")
                    Spacer()
                    Text(side.rawValue.capitalized + " arm")
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .padding(10)
                .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 12))

                Spacer()

                Label(camera.framing.title, systemImage: framingStatusSymbol)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.black.opacity(0.55), in: Capsule())
            }
            .padding(12)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private var cameraStatus: some View {
        VStack(spacing: 12) {
            if camera.state == .requestingPermission || camera.state == .starting {
                ProgressView()
                    .tint(.white)
            } else {
                Image(systemName: camera.state == .denied ? "video.slash.fill" : "video.fill")
                    .font(.system(size: 32, weight: .semibold))
                    .foregroundStyle(.white)
            }

            Text(camera.state.title)
                .font(.headline)
                .foregroundStyle(.white)

            Text(camera.state.detail)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.72))
                .multilineTextAlignment(.center)

            if camera.state == .denied {
                Button("Open Settings") { camera.openSettings() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .frame(maxWidth: 420)
    }

    private var workoutSelection: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Workout")
                .font(.headline)

            Picker("Exercise", selection: $exercise) {
                ForEach(ExerciseCounter.Exercise.allCases, id: \.rawValue) { exercise in
                    Text(exercise.title).tag(exercise)
                }
            }
            .pickerStyle(.segmented)

            Picker("Tracking side", selection: $side) {
                ForEach(ArmMeasurement.Side.allCases, id: \.rawValue) { side in
                    Text(side.rawValue.capitalized).tag(side)
                }
            }
            .pickerStyle(.segmented)
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var framingChecklist: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Framing")
                .font(.headline)

            setupRow(
                "Rear camera active",
                detail: camera.isCameraReady ? "Ready" : camera.state.title,
                symbol: camera.isCameraReady ? "checkmark.circle.fill" : "circle"
            )
            setupRow(
                "Athlete + selected arm",
                detail: camera.framing.detail,
                symbol: framingStatusSymbol
            )
            setupRow("Selected arm", detail: guide.selectedArmText, symbol: "figure.arms.open")
            setupRow(
                "Apparatus",
                detail: "Visual check only. " + guide.apparatusText,
                symbol: "line.diagonal"
            )
            setupRow("Body position", detail: guide.bodyText, symbol: "viewfinder")
            setupRow(
                "Phone",
                detail: "Visual check only. Keep it stationary after bar calibration.",
                symbol: "iphone.gen3"
            )
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var barCalibrationCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Bar reference")
                        .font(.headline)
                    Text(barCalibrationDetail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Image(systemName: camera.currentBar == nil ? "line.diagonal" : "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundStyle(camera.currentBar == nil ? .orange : .green)
                    .accessibilityHidden(true)
            }

            Button {
                barSetup = camera.beginBarSetup()
            } label: {
                Label(
                    camera.currentBar == nil ? "Set bar" : "Adjust bar",
                    systemImage: camera.currentBar == nil ? "viewfinder" : "slider.horizontal.3"
                )
                .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!camera.isCameraReady || !camera.framing.state.isReady)
            .accessibilityIdentifier("liveSetupBar")

            if camera.currentBar != nil {
                Button("Clear bar reference", role: .destructive) {
                    camera.clearBar()
                }
                .font(.caption)
            }
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var readyStateCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: camera.isReadyToStart ? "checkmark.circle.fill" : "circle.dashed")
                .font(.title2)
                .foregroundStyle(camera.isReadyToStart ? .green : .secondary)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text(camera.isReadyToStart ? "Ready to start" : "Setup not complete")
                    .font(.headline)
                Text(
                    camera.isReadyToStart
                        ? "Camera, selected arm, and fixed bar reference are ready. Live set counting is the next implementation step."
                        : readyStateHelp
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
        .accessibilityIdentifier("liveSetupReadiness")
    }

    private var barCalibrationDetail: String {
        if camera.currentBar != nil {
            return "\(camera.barRole.title) confirmed from a frozen analyzed frame."
        }
        if !camera.framing.state.isReady {
            return "Make the selected arm measurable, then freeze a frame and mark the gripping edge."
        }
        return "Freeze the current analyzed frame and confirm the gripping bar or selected dip rail."
    }

    private var readyStateHelp: String {
        if !camera.isCameraReady { return "Start the rear camera." }
        if !camera.framing.state.isReady { return camera.framing.detail }
        if camera.currentBar == nil { return "Confirm the fixed bar reference." }
        return "Complete the remaining setup checks."
    }

    private var scopeNote: some View {
        Label(
            "Athlete and selected-arm visibility are checked from live Apple Vision frames. Bar calibration uses one frozen analyzed frame and explicit user confirmation. Automatic apparatus identity, phone-motion detection, and live counting remain separate qualification steps.",
            systemImage: "info.circle"
        )
        .font(.footnote)
        .foregroundStyle(.secondary)
    }

    private var framingStatusSymbol: String {
        switch camera.framing.state {
        case .ready: "checkmark.circle.fill"
        case .waitingForFrame: "hourglass"
        case .noPerson: "person.crop.circle.badge.questionmark"
        case .multiplePeople: "person.2.fill"
        case .selectedArmHidden, .selectedArmUnclear: "figure.arms.open"
        case .analysisUnavailable: "exclamationmark.triangle.fill"
        }
    }

    private func setupRow(_ title: String, detail: String, symbol: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .frame(width: 22)
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
    }
}
