import AppKit
import SwiftUI

/// Export the native artwork used by the app, and review both system appearances.
@main struct BrandAssetRender {
    @MainActor static func main() throws {
        NSApplication.shared.setActivationPolicy(.prohibited)
        let assets = "ios/HangInThere/Assets.xcassets"
        let outputIndex = CommandLine.arguments.firstIndex(of: "--output")
        let output = outputIndex.map { CommandLine.arguments[$0 + 1] } ?? "build/counted-video-history-v122"
        try FileManager.default.createDirectory(atPath: output, withIntermediateDirectories: true)
        let brand = Image(nsImage: NSImage(contentsOfFile: "\(assets)/BrandIcon.imageset/forest-glow.png")!)
        try save(brand.resizable().frame(width: 1024, height: 1024),
            to: "\(assets)/AppIcon.appiconset/app-icon.png", opaque: true)
        let pullUp = Image(nsImage: NSImage(contentsOfFile: "\(assets)/PullUpIllustration.imageset/illustration.png")!)
        let dip = Image(nsImage: NSImage(contentsOfFile: "\(assets)/DipIllustration.imageset/illustration.png")!)
        for (scheme, name) in [(ColorScheme.light, "light"), (.dark, "dark")] {
            try save(HangLaunchLockup(pullUpArtwork: pullUp, dipArtwork: dip).environment(\.colorScheme, scheme),
                to: "\(assets)/LaunchLockup.imageset/launch-\(name).png", scale: 3)
            try save(BrandLaunchView(pullUpArtwork: pullUp, dipArtwork: dip).frame(width: 393, height: 852).environment(\.colorScheme, scheme),
                to: "\(output)/launch-\(name).png", scale: 2, opaque: true)
            if CommandLine.arguments.contains("--previews") {
                for (width, height, compact, accessible) in [(320.0, 640.0, false, false),
                        (393.0, 852.0, false, false), (844.0, 390.0, true, false), (393.0, 852.0, false, true)] {
                    let home = HomeScreenView(onLiveWorkout: {}, onStartExercise: { _ in }, onReviewVideo: {}, onHistory: {}, pullUpArtwork: pullUp, dipArtwork: dip, brandArtwork: brand)
                        .environment(\.verticalSizeClass, compact ? .compact : .regular)
                        .environment(\.dynamicTypeSize, accessible ? .accessibility5 : .large)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(width: width, height: height, alignment: .top)
                        .clipped()
                        .background(HangPalette(scheme).background)
                        .environment(\.colorScheme, scheme)
                    try save(home, to: "\(output)/home-\(name)-\(Int(width))\(accessible ? "-accessibility" : "").png", scale: 2, opaque: true)
                }
            }
        }
    }

    @MainActor private static func save<V: View>(_ view: V, to path: String, scale: Double = 1, opaque: Bool = false) throws {
        let renderer = ImageRenderer(content: view)
        renderer.scale = scale; renderer.isOpaque = opaque
        guard let image = renderer.cgImage,
              let png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) else {
            throw CocoaError(.fileWriteUnknown)
        }
        try png.write(to: URL(fileURLWithPath: path))
    }
}
