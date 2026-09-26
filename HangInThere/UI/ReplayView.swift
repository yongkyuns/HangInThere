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
                    Text("POSE REPLAY · P0")
                        .font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    preview
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
                    }
                    if let frame = model.frame { diagnostics(frame) }
                    Text("Import an MP4 or MOV from Files. Image and pose come from the same decoded frame; replay may slow down to keep them aligned. Audio is not played.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Text("Counting, form validation and live camera capture are not implemented in P0. This overlay is not an accuracy result.")
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