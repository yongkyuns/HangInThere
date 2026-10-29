import CoreGraphics
import CObservedRailPixels

public struct BackgroundCameraTranslation: Sendable {
    public let referenceX: Double
    public let referenceY: Double
    public let currentX: Double
    public let currentY: Double
    public let region: Int
    public var dxPixels: Double { currentX - referenceX }
    public var dyPixels: Double { currentY - referenceY }
}

public struct BackgroundCameraMeasurement: Sendable {
    public let scaleFraction: Double?
    public let reason: String
    public let referenceFeatures: Int
    public let correspondences: Int
    public let inliers: Int
    public let translations: [BackgroundCameraTranslation]
}

/// Independent image measurement, not a camera-ready or workout verdict.
public enum BackgroundCameraPixels {
    public static let policyVersion = 2
    public static func measure(reference: CGImage, current: CGImage) throws -> BackgroundCameraMeasurement {
        var value = HITBackgroundScaleMeasurement()
        var points = [HITBackgroundPointTranslation](repeating: .init(), count: Int(HITBackgroundMaximumTranslations))
        let status = points.withUnsafeMutableBufferPointer {
            HITMeasureBackgroundGeometry(reference, current, &value, $0.baseAddress)
        }
        switch status {
        case 0: break
        case 1: throw RailPixelError.invalidRegion
        case 2: throw RailPixelError.tooComplex
        case 3: throw RailPixelError.allocationFailed
        case 5: throw RailPixelError.incompatibleBackend
        default: throw RailPixelError.processingFailed
        }
        guard value.reference_features >= 0, value.reference_features <= 64,
              value.correspondences >= 0, value.correspondences <= value.reference_features,
              value.inliers >= 0, value.inliers <= value.correspondences,
              value.supported == 0 || value.supported == 1,
              value.supported == 0 || (value.reason == 0 && value.inliers >= 8),
              value.translation_count >= 0, value.translation_count <= HITBackgroundMaximumTranslations,
              (value.supported == 0 ? value.translation_count == 0 : value.translation_count >= 3),
              value.scale_fraction.isFinite, value.scale_fraction >= 0 else {
            throw RailPixelError.invalidBackendResult
        }
        let reasons = ["supported", "insufficientCorrespondences", "noCoherentTransform", "weakConsensus",
                       "competingTransforms", "fitResidual", "insufficientSpatialSupport", "invalidCorrespondence"]
        var regions = Set<Int32>()
        let translations = try points.prefix(Int(value.translation_count)).map { point in
            guard point.reference_x.isFinite, point.reference_y.isFinite,
                  point.current_x.isFinite, point.current_y.isFinite,
                  point.reference_x >= 0, point.reference_x < Double(reference.width),
                  point.reference_y >= 0, point.reference_y < Double(reference.height),
                  point.current_x >= 0, point.current_x < Double(current.width),
                  point.current_y >= 0, point.current_y < Double(current.height),
                  (0..<8).contains(point.region), regions.insert(point.region).inserted else {
                throw RailPixelError.invalidBackendResult
            }
            return BackgroundCameraTranslation(referenceX: point.reference_x, referenceY: point.reference_y,
                currentX: point.current_x, currentY: point.current_y, region: Int(point.region))
        }
        return BackgroundCameraMeasurement(
            scaleFraction: value.supported == 1 ? value.scale_fraction : nil,
            reason: reasons.indices.contains(Int(value.reason)) ? reasons[Int(value.reason)] : "unsupportedTransform",
            referenceFeatures: Int(value.reference_features), correspondences: Int(value.correspondences),
            inliers: Int(value.inliers), translations: translations)
    }
}
