import CoreGraphics
import Foundation
import ImageIO
import Vision

struct StaticSceneRegistrationReference: Sendable {
    struct Patch: Sendable {
        let rect: CGRect
        let image: CGImage
    }

    let imageSize: ImageSize
    let patches: [Patch]
}

// Vision's translational registration aligns a floating image to a fixed
// reference. We use four peripheral patches and leave robustness/thresholds to
// StaticSceneStability so one athlete-contaminated patch is an outlier.
actor VisionStaticSceneRegistrationWorker {
    enum RegistrationError: LocalizedError {
        case invalidGeometry
        case incompatibleGeometry

        var errorDescription: String? {
            switch self {
            case .invalidGeometry:
                "The calibration image could not produce usable background patches."
            case .incompatibleGeometry:
                "The current frame geometry no longer matches the calibration frame."
            }
        }
    }

    static let patchFraction = 0.25
    static let maximumPatchShiftFraction = 0.45

    static func makeReference(
        image: CGImage
    ) -> StaticSceneRegistrationReference? {
        let width = image.width
        let height = image.height
        guard width >= 64, height >= 64 else { return nil }

        let rects = cornerPatchRects(width: width, height: height)
        let patches = rects.compactMap { rect -> StaticSceneRegistrationReference.Patch? in
            guard let crop = image.cropping(to: rect) else { return nil }
            return .init(rect: rect, image: crop)
        }

        guard patches.count == 4 else { return nil }
        return StaticSceneRegistrationReference(
            imageSize: ImageSize(width: Double(width), height: Double(height)),
            patches: patches
        )
    }

    func measure(
        reference: StaticSceneRegistrationReference,
        image: CGImage
    ) throws -> [StaticSceneStability.PatchShift] {
        guard reference.imageSize.isValid, reference.patches.count >= 2 else {
            throw RegistrationError.invalidGeometry
        }
        guard image.width == Int(reference.imageSize.width),
              image.height == Int(reference.imageSize.height)
        else {
            throw RegistrationError.incompatibleGeometry
        }

        var shifts: [StaticSceneStability.PatchShift] = []
        shifts.reserveCapacity(reference.patches.count)

        for patch in reference.patches {
            guard let current = image.cropping(to: patch.rect) else { continue }

            let request = VNTranslationalImageRegistrationRequest(
                targetedCGImage: patch.image,
                orientation: .up,
                options: [:]
            )
            let handler = VNImageRequestHandler(
                cgImage: current,
                orientation: .up,
                options: [:]
            )
            try handler.perform([request])

            guard let observation = request.results?.first else { continue }
            let transform = observation.alignmentTransform
            let dx = Double(transform.tx)
            let dy = Double(transform.ty)
            guard dx.isFinite, dy.isFinite else { continue }

            let maxX = patch.rect.width * Self.maximumPatchShiftFraction
            let maxY = patch.rect.height * Self.maximumPatchShiftFraction
            guard abs(dx) <= maxX, abs(dy) <= maxY else { continue }

            shifts.append(.init(
                dxPixels: dx,
                dyPixels: dy,
                centerXFraction: patch.rect.midX / reference.imageSize.width,
                centerYFraction: patch.rect.midY / reference.imageSize.height
            ))
        }

        return shifts
    }

    private static func cornerPatchRects(
        width: Int,
        height: Int
    ) -> [CGRect] {
        let w = max(32, Int((Double(width) * patchFraction).rounded(.down)))
        let h = max(32, Int((Double(height) * patchFraction).rounded(.down)))
        let maxX = width - w
        let maxY = height - h

        return [
            CGRect(x: 0, y: 0, width: w, height: h),
            CGRect(x: maxX, y: 0, width: w, height: h),
            CGRect(x: 0, y: maxY, width: w, height: h),
            CGRect(x: maxX, y: maxY, width: w, height: h)
        ]
    }
}
