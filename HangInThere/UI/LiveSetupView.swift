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
            .task { await camera.start() }
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

            if camera.isReady {
                LiveCameraPreviewSurface(session: camera.session)
                    .clipShape(RoundedRectangle(cornerRadius: 20))

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

                Text("Keep athlete + apparatus inside the guide")
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

            setupRow("Rear camera active", detail: camera.isReady ? "Ready" : camera.state.title,
                     symbol: camera.isReady ? "checkmark.circle.fill" : "circle")
            setupRow("Selected arm", detail: guide.selectedArmText, symbol: "figure.arms.open")
            setupRow("Apparatus", detail: guide.apparatusText, symbol: "line.diagonal")
            setupRow("Body position", detail: guide.bodyText, symbol: "viewfinder")
            setupRow("Phone", detail: "Keep it stationary after bar calibration.", symbol: "iphone.gen3")
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var scopeNote: some View {
        Label(
            "This setup stage verifies camera availability and gives the framing target. Automatic athlete/apparatus readiness, bar calibration, and live counting are separate qualification steps.",
            systemImage: "info.circle"
        )
        .font(.footnote)
        .foregroundStyle(.secondary)
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
