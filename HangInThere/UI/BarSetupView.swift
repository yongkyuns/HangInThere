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
    @State private var message = "Drag a box around one clear straight gripping edge."
    @State private var operation: Task<Void, Never>?
    @State private var requestID = 0
    private let detector = VisionBarDetector()

    private var selection: ConfirmedBar? {
        if manualMode, let manual {
            let value = ConfirmedBar(role: setup.role, method: .manualEdge, referenceEdge: manual.ordered,
                                     oppositeEdge: nil, imageSize: setup.frame.pose.imageSize, sourceTime: setup.frame.pose.timestamp)
            return value.isValid ? value : nil
        }
        guard candidates.indices.contains(selectedIndex) else { return nil }
        let proposal = candidates[selectedIndex]
        return ConfirmedBar(role: setup.role, method: .guidedContours, referenceEdge: proposal.edge,
                            oppositeEdge: nil, imageSize: setup.frame.pose.imageSize,
                            sourceTime: setup.frame.pose.timestamp)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(setup.role.title).font(.headline)
                    Text(message).font(.callout)
                    GeometryReader { proxy in
                        ZStack {
                            Color.black
                            Image(decorative: setup.frame.image, scale: 1, orientation: .up)
                                .resizable().scaledToFit()
                            BarOverlay(imageSize: setup.frame.pose.imageSize, bar: selection, region: region)
                        }
                        .contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 8).onEnded { gesture in
                            guard let fit = AspectFit(image: setup.frame.pose.imageSize,
                                viewport: ImageSize(width: proxy.size.width, height: proxy.size.height)) else { return }
                            let a = fit.imagePoint(Point2D(x: gesture.startLocation.x, y: gesture.startLocation.y))
                            let b = fit.imagePoint(Point2D(x: gesture.location.x, y: gesture.location.y))
                            let size = setup.frame.pose.imageSize
                            let bounds = BarRegion(Point2D(x: 0, y: 0), Point2D(x: size.width, y: size.height))
                            guard bounds.contains(a), bounds.contains(b) else {
                                message = "Draw inside the image, not its black margins."; return
                            }
                            clearProposal()
                            if manualMode {
                                manual = BarSegment(a: a, b: b)
                                region = nil
                                message = "Manual reference edge. This is not an automatic detection; bar thickness is unknown."
                            } else {
                                region = BarRegion(a, b)
                                message = "Tap Find edge. Keep the guide tight around one straight gripping edge and avoid rack supports."
                            }
                        })
                    }
                    .frame(height: 360)
                    .accessibilityLabel("Frozen setup frame. Drag a search box or manually mark the reference edge.")
                    Toggle("Mark edge manually", isOn: $manualMode)
                        .onChange(of: manualMode) { _, manualMode in
                            clearProposal(); region = nil
                            message = manualMode ? "Drag along the visible reference edge. For a pull-up, mark the upper silhouette edge."
                                : "Drag a box around a clear straight section of the gripping bar."
                        }
                    if !manualMode {
                        Button(busy ? "Finding edge…" : "Find edge") { detect() }
                            .buttonStyle(.bordered)
                            .disabled(busy || region?.isValid(in: setup.frame.pose.imageSize) != true)
                        if !candidates.isEmpty {
                            Stepper("Proposal \(selectedIndex + 1) of \(candidates.count)", value: $selectedIndex,
                                    in: 0...(candidates.count-1))
                            Text("Check the highlighted edge belongs to the gripping bar, not a rack beam. Ranking is observed line support, not semantic recognition.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Button("Confirm reference") {
                        guard let selection else { return }
                        if onConfirm(selection) { dismiss() }
                        else { message = "This frame is no longer current. Close setup and select the bar again." }
                    }
                    .buttonStyle(.borderedProminent).disabled(selection == nil || busy)
                    Text("One athlete and a fixed phone. Saved geometry is a reference from this frame, not ongoing tracking. The confirmed edge is used as a fixed movement/contact reference. It still does not verify chin clearance, dip depth or physical contact.")
                        .font(.footnote).foregroundStyle(.secondary)
                }.padding()
            }
            .navigationTitle("Bar setup")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .onDisappear { clearProposal() }
        }
    }

    private func clearProposal() {
        requestID += 1
        operation?.cancel(); operation = nil
        busy = false; candidates = []; selectedIndex = 0; manual = nil
    }

    private func detect() {
        guard let region else { return }
        clearProposal()
        let ticket = requestID
        busy = true
        operation = Task {
            do {
                let results = try await detector.detect(image: setup.frame.image, region: region)
                guard !Task.isCancelled, ticket == requestID else { return }
                candidates = results
                message = results.isEmpty ? "No supported edge. Try a smaller, clearer bar section, or mark the reference edge manually."
                    : "Confirm the correct gripping edge. Yellow is the observed reference edge."
            } catch {
                guard !Task.isCancelled, ticket == requestID else { return }
                message = "Could not propose a bar in this region. Use a smaller, clearer region or mark the edge manually."
            }
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
            guard let fit = AspectFit(image: imageSize, viewport: ImageSize(width: size.width, height: size.height)) else { return }
            func path(_ line: BarSegment) -> Path {
                let a = fit.displayPoint(line.a), b = fit.displayPoint(line.b)
                var p = Path(); p.move(to: CGPoint(x: a.x, y: a.y)); p.addLine(to: CGPoint(x: b.x, y: b.y)); return p
            }
            if let region {
                let a = fit.displayPoint(Point2D(x: region.minX, y: region.minY))
                let rect = CGRect(x: a.x, y: a.y, width: region.width*fit.scale, height: region.height*fit.scale)
                context.stroke(Path(rect), with: .color(.white), style: StrokeStyle(lineWidth: 1, dash: [5, 4]))
            }
            if let bar, bar.imageSize == imageSize {
                context.stroke(path(bar.referenceEdge), with: .color(.yellow), style: StrokeStyle(lineWidth: 3, dash: [7, 3]))
                if let edge = bar.oppositeEdge { context.stroke(path(edge), with: .color(.cyan), lineWidth: 2) }
            }
        }.allowsHitTesting(false).accessibilityHidden(true)
    }
}
