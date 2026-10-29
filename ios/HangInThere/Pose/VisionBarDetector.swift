import CoreGraphics
import Foundation
import ObservedRailPixels

protocol BarEdgeDetector: Sendable {
    func detect(image: CGImage, region: BarRegion, minimumObservedLength: Double?) async throws -> [BarLineCandidate]
}
extension BarEdgeDetector {
    func detect(image: CGImage, region: BarRegion) async throws -> [BarLineCandidate] {
        try await detect(image: image, region: region, minimumObservedLength: nil)
    }
}

// Image-only setup extraction. The existing bounded native Canny backend reduces
// texture fragmentation before the unchanged observed-segment fitter. Neither
// pose nor image processing supplies an inferred bar or physical load evidence.
actor VisionBarDetector: BarEdgeDetector {
    static let extractionPolicyVersion = 2
    static var pixelBackendVersion: String { ObservedRailPixels.backendVersion }

    func detect(image: CGImage, region: BarRegion, minimumObservedLength: Double? = nil) throws -> [BarLineCandidate] {
        try Task.checkCancellation()
        let size = ImageSize(width: Double(image.width), height: Double(image.height))
        guard region.isValid(in: size) else { throw BarFitError.invalidRegion }
        let rect = CGRect(x: floor(region.minX), y: floor(region.minY),
                          width: ceil(region.maxX)-floor(region.minX),
                          height: ceil(region.maxY)-floor(region.minY))
        let cropRegion = BarRegion(Point2D(x: rect.minX, y: rect.minY),
                                   Point2D(x: rect.maxX, y: rect.maxY))
        return try autoreleasepool {
            let contours = try ObservedRailPixels.contours(image: image, region: rect).map { contour in
                contour.points.map { Point2D(x: $0.x, y: $0.y) }
            }
            try Task.checkCancellation()
            return try BarLineFitter.candidates(contours: contours, region: cropRegion, size: size,
                                              minimumObservedLength: minimumObservedLength)
        }
    }
}
