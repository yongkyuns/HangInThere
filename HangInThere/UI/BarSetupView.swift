import SwiftUI

// A frozen, explicitly chosen frame. Setup cannot leak across replay sessions.
@MainActor
struct BarSetupView: View {
    let setup: BarSetupFrame
    let onConfirm: (ConfirmedBar) -> Bool

    @Environment(\.dismiss) private var dismiss
    @State private var region: BarRegion?
    @State private var manual: BarSegment?
    @State private var candidates: [BarLineCandidate] = []
    @State private var selectedIndex = 0
    @State private var manualMode = false
    @State private var busy = false
    @State private var message: String?
    @State private var operation: Task<Void, Never>?
    @State private var requestID = 0

    private let detector = VisionBarDetector()

    private var selection: ConfirmedBar? {
        if manualMode, let manual {
            let value = ConfirmedBar(
                role: setup.role,
                method: .manualEdge,
                referenceEdge: manual.ordered,
                oppositeEdge: nil,
                imageSize: setup.frame.pose.imageSize,
                sourceTime: setup.frame.pose.timestamp
            )
            return value.isValid ? value : nil
        }

        guard candidates.indices.contains(selectedIndex) else { return nil }
        let proposal = candidates[selectedIndex]
        return ConfirmedBar(
            role: setup.role,
            method: .guidedContours,
            referenceEdge: proposal.edge,
            oppositeEdge: nil,
            imageSize: setup.frame.pose.imageSize,
            sourceTime: setup.frame.pose.timestamp
        )
    }

    private var stageTitle: String {
        if manualMode {
            return manual == nil ? "Draw along the bar" : "Check your bar line"
        }
        if busy { return "Finding the bar" }
        if selection != nil { return "Is this the gripping edge?" }
        return "Mark the gripping bar"
    }

    private var stageHelp: String {
        if manualMode {
            return manual == nil
                ? "Drag directly along one clear edge of the gripping bar or selected dip rail."
                : "The yellow line should follow the gripping edge. Redraw it if needed."
        }
        if busy {
            return "Looking for a straight observed edge inside your selection."
        }
        if selection != nil {
            return "The yellow line should be the edge you are gripping—not a rack support or background line."
        }
        return setup.role == .pullUpGrip
            ? "Drag a short box across a clear section of the pull-up bar. Detection starts automatically."
            : "Drag a short box across the rail used by your selected hand. Detection starts automatically."
    }

    private var stageLabel: String {
        selection == nil ? "STEP 1 OF 2" : "STEP 2 OF 2"
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(stageLabel)
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(.secondary)

                        Text(stageTitle)
                            .font(.title2.bold())

                        Text(stageHelp)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    setupPreview

                    if busy {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Finding a clear edge…")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }

                    if let message {
                        Label(message, systemImage: "exclamationmark.triangle.fill")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }

                    if !manualMode, candidates.count > 1 {
                        candidatePicker
                    }

                    actionArea

                    Divider()

                    Label(
                        "Keep the phone fixed after setup. This reference is used for movement counting, not yet for chin clearance or strict dip-depth scoring.",
                        systemImage: "iphone.gen3"
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }
                .padding()
            }
            .navigationTitle("Set the bar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onDisappear { clearProposal() }
        }
    }

    private var setupPreview: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black

                Image(decorative: setup.frame.image, scale: 1, orientation: .up)
                    .resizable()
                    .scaledToFit()

                BarOverlay(
                    imageSize: setup.frame.pose.imageSize,
                    bar: selection,
                    region: manualMode ? nil : region
                )

                if !busy, selection == nil {
                    VStack {
                        Spacer()
                        Text(manualMode ? "Drag along the edge" : "Drag over the bar")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(.black.opacity(0.55), in: Capsule())
                            .padding(.bottom, 12)
                    }
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 8)
                    .onEnded { gesture in
                        handleDrag(gesture, viewport: proxy.size)
                    }
            )
        }
        .frame(height: 390)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay {
            RoundedRectangle(cornerRadius: 18)
                .stroke(.white.opacity(0.08), lineWidth: 1)
        }
        .accessibilityLabel(
            manualMode
                ? "Frozen workout frame. Drag along the gripping bar edge."
                : "Frozen workout frame. Drag a box over the gripping bar edge."
        )
    }

    private var candidatePicker: some View {
        HStack {
            Button {
                selectedIndex = (selectedIndex - 1 + candidates.count) % candidates.count
            } label: {
                Label("Previous", systemImage: "chevron.left")
            }
            .buttonStyle(.bordered)

            Spacer()

            Text("Match \(selectedIndex + 1) of \(candidates.count)")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            Spacer()

            Button {
                selectedIndex = (selectedIndex + 1) % candidates.count
            } label: {
                Label("Next", systemImage: "chevron.right")
                    .labelStyle(.titleAndIcon)
            }
            .buttonStyle(.bordered)
        }
    }

    @ViewBuilder
    private var actionArea: some View {
        if selection != nil {
            Button {
                confirmSelection()
            } label: {
                Label("Use this bar", systemImage: "checkmark")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(busy)
            .accessibilityIdentifier("confirmBar")

            Button("Try again") {
                resetForCurrentMode()
            }
            .frame(maxWidth: .infinity)
            .disabled(busy)
        } else if !manualMode {
            Button {
                manualMode = true
                resetForCurrentMode()
            } label: {
                Label("Can’t find it? Mark the edge manually", systemImage: "pencil.tip")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .disabled(busy)
        } else {
            Button {
                manualMode = false
                resetForCurrentMode()
            } label: {
                Label("Use automatic detection", systemImage: "viewfinder")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
        }
    }

    private func handleDrag(_ gesture: DragGesture.Value, viewport: CGSize) {
        guard let fit = AspectFit(
            image: setup.frame.pose.imageSize,
            viewport: ImageSize(width: viewport.width, height: viewport.height)
        ) else { return }

        let a = fit.imagePoint(
            Point2D(x: gesture.startLocation.x, y: gesture.startLocation.y)
        )
        let b = fit.imagePoint(
            Point2D(x: gesture.location.x, y: gesture.location.y)
        )
        let size = setup.frame.pose.imageSize
        let bounds = BarRegion(
            Point2D(x: 0, y: 0),
            Point2D(x: size.width, y: size.height)
        )

        guard bounds.contains(a), bounds.contains(b) else {
            message = "Start and finish inside the video image."
            return
        }

        clearProposal()
        message = nil

        if manualMode {
            let edge = BarSegment(a: a, b: b)
            guard edge.isValid, edge.length >= 24 else {
                message = "Draw a longer line along the gripping edge."
                return
            }
            manual = edge
        } else {
            let next = BarRegion(a, b)
            guard next.isValid(in: size) else {
                message = "Use a slightly larger box around the gripping edge."
                return
            }
            region = next
            detect()
        }
    }

    private func confirmSelection() {
        guard let selection else { return }
        if onConfirm(selection) {
            dismiss()
        } else {
            message = "This setup frame is no longer current. Close setup and choose the bar again."
        }
    }

    private func resetForCurrentMode() {
        clearProposal()
        region = nil
        manual = nil
        message = nil
    }

    private func clearProposal() {
        requestID += 1
        operation?.cancel()
        operation = nil
        busy = false
        candidates = []
        selectedIndex = 0
        manual = nil
    }

    private func detect() {
        guard let region else { return }

        let previous = operation
        previous?.cancel()
        requestID += 1
        let ticket = requestID
        busy = true
        candidates = []
        selectedIndex = 0

        operation = Task {
            do {
                let results = try await detector.detect(
                    image: setup.frame.image,
                    region: region
                )
                guard !Task.isCancelled, ticket == requestID else { return }

                candidates = results
                if results.isEmpty {
                    message = "No clear edge found there. Try a tighter selection or mark it manually."
                } else {
                    message = nil
                }
            } catch {
                guard !Task.isCancelled, ticket == requestID else { return }
                message = "Couldn’t find a stable edge there. Try a tighter selection or mark it manually."
            }

            guard ticket == requestID else { return }
            busy = false
            operation = nil
        }
    }
}

struct BarOverlay: View {
    let imageSize: ImageSize
    let bar: ConfirmedBar?
    var region: BarRegion? = nil

    var body: some View {
        Canvas { context, size in
            guard let fit = AspectFit(
                image: imageSize,
                viewport: ImageSize(width: size.width, height: size.height)
            ) else { return }

            func path(_ line: BarSegment) -> Path {
                let a = fit.displayPoint(line.a)
                let b = fit.displayPoint(line.b)
                var path = Path()
                path.move(to: CGPoint(x: a.x, y: a.y))
                path.addLine(to: CGPoint(x: b.x, y: b.y))
                return path
            }

            if let region {
                let a = fit.displayPoint(Point2D(x: region.minX, y: region.minY))
                let rect = CGRect(
                    x: a.x,
                    y: a.y,
                    width: region.width * fit.scale,
                    height: region.height * fit.scale
                )
                context.stroke(
                    Path(rect),
                    with: .color(.white),
                    style: StrokeStyle(lineWidth: 1.5, dash: [6, 4])
                )
            }

            if let bar, bar.imageSize == imageSize {
                context.stroke(
                    path(bar.referenceEdge),
                    with: .color(.yellow),
                    style: StrokeStyle(lineWidth: 4, lineCap: .round)
                )
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
