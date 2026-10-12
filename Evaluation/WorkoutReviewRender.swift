import Foundation
import AppKit
import SwiftUI

/// Render the shipping SwiftUI/Charts view; no screenshot of the user's desktop.
@main struct WorkoutReviewRender {
    @MainActor static func main() throws {
        let input = """
        {"id":"preview","exercise":"pullUp","startedAt":0,"endedAt":3,"endReason":"disengaged","backend":"synthetic render data","modelRevision":0,"schemaVersion":1,"lifecycleVersion":1,"reps":[{"id":"preview:rep:1","setId":"preview","ordinal":1,"exercise":"pullUp","startedAt":0,"extremeAt":1,"acceptedAt":1,"returnedAt":3,"closedAt":3,"closeReason":"returned","outcome":"accepted","outboundSeconds":1,"returnSeconds":2,"left":{"minimum":75,"maximum":170,"mean":125,"observedSeconds":3,"coverage":1},"right":{"minimum":80,"maximum":168,"mean":126,"observedSeconds":3,"coverage":1},"shoulderExcursion":0.15,"bilateralExtremeOffsetSeconds":0.1,"usableDurationSeconds":3,"durationCoverage":1,"samples":[{"seconds":0,"leftDegrees":170,"rightDegrees":168,"shoulderHeight":0.3,"usable":true,"released":false},{"seconds":0.3,"leftDegrees":140,"rightDegrees":142,"shoulderHeight":0.25,"usable":true,"released":false},{"seconds":0.6,"leftDegrees":110,"rightDegrees":115,"shoulderHeight":0.2,"usable":true,"released":false},{"seconds":0.9,"leftDegrees":75,"rightDegrees":80,"shoulderHeight":0.15,"usable":true,"released":false},{"seconds":1.2,"leftDegrees":90,"rightDegrees":95,"shoulderHeight":0.17,"usable":true,"released":false},{"seconds":1.5,"leftDegrees":105,"rightDegrees":110,"shoulderHeight":0.2,"usable":true,"released":false},{"seconds":1.8,"leftDegrees":120,"rightDegrees":125,"shoulderHeight":0.23,"usable":true,"released":false},{"seconds":2.1,"leftDegrees":140,"rightDegrees":145,"shoulderHeight":0.25,"usable":true,"released":false},{"seconds":2.4,"leftDegrees":150,"rightDegrees":152,"shoulderHeight":0.27,"usable":true,"released":false},{"seconds":2.7,"leftDegrees":160,"rightDegrees":162,"shoulderHeight":0.29,"usable":true,"released":false},{"seconds":3,"leftDegrees":170,"rightDegrees":168,"shoulderHeight":0.3,"usable":true,"released":false}],"curveTruncated":false,"countPolicyVersion":8,"metricVersion":1}]}
        """
        NSApplication.shared.setActivationPolicy(.prohibited)
        var fixture = try JSONSerialization.jsonObject(with: Data(input.utf8)) as! [String: Any]
        fixture["trackedAtEpochMilliseconds"] = 1_791_784_800_000 as Int64
        let set = try JSONDecoder().decode(WorkoutSetRecord.self, from: JSONSerialization.data(withJSONObject: fixture))
        try FileManager.default.createDirectory(atPath: "build/workout-analysis/render", withIntermediateDirectories: true)
        for dark in [true, false] {
            for width in [320.0, 430.0] {
                let content = WorkoutReviewView(sets: [set], currentReps: set.reps, storageError: nil)
                    .environment(\.colorScheme, dark ? .dark : .light)
                    .frame(width: width, height: 1000)
                    .background(dark ? Color.black : Color.white)
                let host = NSHostingView(rootView: content)
                let window = NSWindow(contentRect: NSRect(x: -4000, y: -4000, width: width, height: 1000),
                    styleMask: [.borderless], backing: .buffered, defer: false)
                window.isReleasedWhenClosed = false; window.contentView = host; window.orderFront(nil)
                host.layoutSubtreeIfNeeded()
                RunLoop.current.run(until: Date().addingTimeInterval(0.3))
                guard let bitmap = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { fatalError("Chart rendering failed") }
                host.cacheDisplay(in: host.bounds, to: bitmap)
                guard let data = bitmap.representation(using: .png, properties: [:]) else { fatalError("Chart pixels failed") }
                window.close()
                try data.write(to: URL(fileURLWithPath: "build/workout-analysis/render/\(dark ? "dark" : "light")-\(Int(width)).png"))
            }
        }
    }
}
