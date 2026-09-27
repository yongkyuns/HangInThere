import CoreGraphics
import Foundation
import Vision

// Setup only. No trained bar model, wrist-derived geometry or person association.
// The caller supplies a guided region; the result always requires confirmation.
actor VisionBarDetector {
    func detect(image: CGImage, region: BarRegion) throws -> [BarCandidate] {
        try Task.checkCancellation()
        let size = ImageSize(width: Double(image.width), height: Double(image.height))
        guard region.isValid(in: size) else { throw BarFitError.invalidRegion }
        // Integral crop offsets make the normalization transform explicit and testable.
        let rect = CGRect(x: floor(region.minX), y: floor(region.minY),
                          width: ceil(region.maxX)-floor(region.minX),
                          height: ceil(region.maxY)-floor(region.minY))
        guard let cropped = image.cropping(to: rect) else { throw BarFitError.invalidRegion }
        let cropRegion = BarRegion(Point2D(x: rect.minX, y: rect.minY), Point2D(x: rect.maxX, y: rect.maxY))
        return try autoreleasepool {
            var contours: [[Point2D]] = []
            var pointCount = 0
            // Support both bright metal and dark bars. Duplicate evidence is collapsed
            // by the pixel-space fitter. Do not infer which contrast means "bar".
            for darkOnLight in [true, false] {
                try Task.checkCancellation()
                let request = VNDetectContoursRequest()
                request.revision = VNDetectContourRequestRevision1
                request.maximumImageDimension = 1024
                request.detectsDarkOnLight = darkOnLight
                try VNImageRequestHandler(cgImage: cropped, orientation: .up, options: [:]).perform([request])
                for observation in request.results ?? [] {
                    for i in 0..<observation.contourCount {
                        let contour = try observation.contour(at: i)
                        pointCount += contour.pointCount
                        guard pointCount <= BarFitter.maximumPoints else { throw BarFitError.tooComplex }
                        contours.append(try contour.normalizedPoints.map {
                            guard let point = cropRegion.contourPoint(x: $0.x, y: $0.y) else {
                                throw BarFitError.invalidContour
                            }
                            return point
                        })
                    }
                }
            }
            try Task.checkCancellation()
            return try BarFitter.candidates(contours: contours, region: cropRegion, size: size)
        }
    }
}
