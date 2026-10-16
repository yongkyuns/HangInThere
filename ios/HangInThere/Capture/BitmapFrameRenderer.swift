import CoreGraphics
import CoreVideo
import Foundation

enum FrameRenderingBackend: String, Sendable {
    case bitmap
    case coreImage
}

// Lossless spatial normalization for an opaque, unscaled SDR decoder surface.
// ColorSync converts its declared RGB space to the same sRGB output contract
// as Core Image. Resampling, HDR and unsupported surfaces retain the CI path.
// The returned image owns its pixels; it never borrows a decoder's next frame.
enum BitmapFrameRenderer {
    static func supports(_ buffer: CVPixelBuffer, geometry: OrientedGeometry,
                         colorSpace: CGColorSpace?, maximumDimension: Double,
                         isolation: isolated (any Actor)? = #isolation) -> Bool {
        guard maximumDimension.isFinite, maximumDimension > 0,
              CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA,
              !CVPixelBufferIsPlanar(buffer),
              geometry.sourceSize.width == Double(CVPixelBufferGetWidth(buffer)),
              geometry.sourceSize.height == Double(CVPixelBufferGetHeight(buffer)),
              max(geometry.outputSize.width, geometry.outputSize.height) <= maximumDimension,
              let colorSpace, colorSpace.model == .rgb,
              !CGColorSpaceUsesExtendedRange(colorSpace), !colorSpace.isHDR() else { return false }
        let transfer = CVBufferCopyAttachment(buffer, kCVImageBufferTransferFunctionKey, nil)
        if let transfer,
           CFEqual(transfer, kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ)
            || CFEqual(transfer, kCVImageBufferTransferFunction_ITU_R_2100_HLG) { return false }
        let t = geometry.coreImageTransform
        // Integer quarter-turns/reflections preserve pixel centers. Do not
        // approximate arbitrary track transforms or change interpolation.
        return [t.a, t.b, t.c, t.d].allSatisfy { [-1.0, 0, 1].contains($0) }
            && abs(t.a) + abs(t.c) == 1 && abs(t.b) + abs(t.d) == 1
            && abs(t.a) + abs(t.b) == 1 && abs(t.c) + abs(t.d) == 1
            && t.tx == t.tx.rounded() && t.ty == t.ty.rounded()
    }

    static func render(_ buffer: CVPixelBuffer, geometry: OrientedGeometry,
                       colorSpace: CGColorSpace?, maximumDimension: Double,
                       isolation: isolated (any Actor)? = #isolation) -> CGImage? {
        guard supports(buffer, geometry: geometry, colorSpace: colorSpace,
                       maximumDimension: maximumDimension), let colorSpace,
              let pixels = opaquePixels(buffer),
              let provider = CGDataProvider(data: pixels as CFData),
              let source = CGImage(width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer),
                  bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: CVPixelBufferGetWidth(buffer) * 4,
                  space: colorSpace,
                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue
                      | CGBitmapInfo.byteOrder32Little.rawValue),
                  provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent) else { return nil }
        // A canonical identity frame needs only its owned copy, not another
        // bitmap allocation, color conversion or draw.
        if geometry.coreImageTransform == .init(), colorSpace.name == CGColorSpace.sRGB { return source }
        guard let outputSpace = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: Int(geometry.outputSize.width),
                  height: Int(geometry.outputSize.height), bitsPerComponent: 8, bytesPerRow: 0,
                  space: outputSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.setBlendMode(.copy)
        context.interpolationQuality = .none
        context.setShouldAntialias(false)
        let t = geometry.coreImageTransform
        context.concatenate(CGAffineTransform(a: t.a, b: t.b, c: t.c, d: t.d, tx: t.tx, ty: t.ty))
        context.draw(source, in: CGRect(x: 0, y: 0, width: source.width, height: source.height))
        return context.makeImage()
    }

    private static func opaquePixels(_ buffer: CVPixelBuffer) -> Data? {
        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
        let packed = width.multipliedReportingOverflow(by: 4)
        let count = height.multipliedReportingOverflow(by: packed.partialValue)
        let stride = CVPixelBufferGetBytesPerRow(buffer)
        let stored = height.multipliedReportingOverflow(by: stride)
        guard width > 0, height > 0, !packed.overflow, !count.overflow, !stored.overflow,
              stride >= packed.partialValue, CVPixelBufferGetDataSize(buffer) >= stored.partialValue,
              CVPixelBufferLockBaseAddress(buffer, .readOnly) == kCVReturnSuccess else { return nil }
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return nil }
        var data = Data(count: count.partialValue)
        let opaque = data.withUnsafeMutableBytes { destination in
            guard let target = destination.baseAddress else { return false }
            for y in 0..<height {
                let row = base.advanced(by: y * stride)
                for x in 0..<width where row.load(fromByteOffset: x * 4 + 3, as: UInt8.self) != 255 {
                    return false
                }
                // Exclude decoder row padding from the owned image storage.
                target.advanced(by: y * packed.partialValue).copyMemory(from: row, byteCount: packed.partialValue)
            }
            return true
        }
        return opaque ? data : nil
    }
}
