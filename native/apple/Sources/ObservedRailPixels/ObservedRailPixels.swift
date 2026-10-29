import CoreGraphics
import CObservedRailPixels

public struct RailPixelPoint: Equatable, Sendable { public let x: Double; public let y: Double }
public struct RailPixelContour: Sendable { public let points: [RailPixelPoint] }
public enum RailPixelError: Error, Equatable { case invalidRegion, tooComplex, allocationFailed, processingFailed, invalidBackendResult, incompatibleBackend }

/// Bounded image-only extraction. Call from one background acquisition owner.
/// These source contours do not establish apparatus, contact or workout evidence.
public enum ObservedRailPixels {
    public static var backendVersion: String { String(cString: HITRailPixelBackendVersion()) }
    public static func contours(image: CGImage, region: CGRect) throws -> [RailPixelContour] {
        guard [region.minX, region.minY, region.maxX, region.maxY].allSatisfy(\.isFinite),
              region.width > 0, region.height > 0, region.minX >= 0, region.minY >= 0,
              region.maxX <= CGFloat(image.width), region.maxY <= CGFloat(image.height),
              image.width <= Int(Int32.max), image.height <= Int(Int32.max) else { throw RailPixelError.invalidRegion }
        let crop = region.integral
        var result = HITRailPixelContours()
        let status = HITCopyRailPixelContours(image, Int32(crop.minX), Int32(crop.minY), Int32(crop.width), Int32(crop.height), &result)
        defer { HITFreeRailPixelContours(&result) }
        switch status {
        case 0: break
        case 1: throw RailPixelError.invalidRegion
        case 2: throw RailPixelError.tooComplex
        case 3: throw RailPixelError.allocationFailed
        case 5: throw RailPixelError.incompatibleBackend
        default: throw RailPixelError.processingFailed
        }
        guard result.contour_count <= 512, result.point_count <= 200000,
              let offsets = result.offsets, offsets[0] == 0, offsets[Int(result.contour_count)] == result.point_count
        else { throw RailPixelError.invalidBackendResult }
        if result.contour_count == 0 { return [] }
        guard let points = result.points else { throw RailPixelError.invalidBackendResult }
        return try (0..<Int(result.contour_count)).map { contour in
            let first = offsets[contour], end = offsets[contour + 1]
            guard first <= end, end <= result.point_count else { throw RailPixelError.invalidBackendResult }
            return RailPixelContour(points: (Int(first)..<Int(end)).map { index in
                let p = points[index]; return RailPixelPoint(x: p.x, y: p.y)
            })
        }
    }
}
