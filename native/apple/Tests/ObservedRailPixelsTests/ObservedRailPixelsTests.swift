import CoreGraphics
import Foundation
import Testing
@testable import ObservedRailPixels

struct ObservedRailPixelsTests {
    private func image(width: Int = 96, height: Int = 128, rectangle: Bool = false, white: Bool = true) throws -> CGImage {
        var bytes = [UInt8](repeating: white ? 255 : 0, count: width * height * 4)
        for i in stride(from: 3, to: bytes.count, by: 4) { bytes[i] = 255 }
        if rectangle {
            for y in 20..<61 { for x in 16..<41 {
                let i = (y * width + x) * 4
                bytes[i] = 0; bytes[i + 1] = 0; bytes[i + 2] = 0
            } }
        }
        let provider = try #require(CGDataProvider(data: Data(bytes) as CFData))
        return try #require(CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent))
    }
    private func keys(_ contours: [RailPixelContour]) -> Set<String> {
        Set(contours.flatMap(\.points).map { "\($0.x),\($0.y)" })
    }

    @Test func pinnedBackendAndBlankImagesSupplyNoFeatures() throws {
        #expect(ObservedRailPixels.backendVersion == "4.11.0")
        for white in [true, false] {
            let blank = try image(white: white)
            for _ in 0..<3 {
                #expect(try ObservedRailPixels.contours(image: blank, region: CGRect(x: 0, y: 0, width: 96, height: 128)).isEmpty)
            }
        }
    }

    @Test func asymmetricSourceRemainsUprightAndUsesActualPixelCenters() throws {
        let source = try image(rectangle: true)
        let contours = try ObservedRailPixels.contours(image: source, region: CGRect(x: 0, y: 0, width: 96, height: 128))
        let points = contours.flatMap(\.points)
        #expect(!points.isEmpty)
        #expect(points.allSatisfy { $0.x >= 14 && $0.x <= 43 && $0.y >= 18 && $0.y <= 63 })
        #expect(points.allSatisfy { $0.x.truncatingRemainder(dividingBy: 1) == 0.5 && $0.y.truncatingRemainder(dividingBy: 1) == 0.5 })
        for contour in contours {
            #expect(zip(contour.points, contour.points.dropFirst()).allSatisfy { a, b in
                max(abs(a.x - b.x), abs(a.y - b.y)) <= 1
            })
        }
    }

    @Test func integralAndFractionalCropsRetainAbsoluteSourceCoordinates() throws {
        let source = try image(rectangle: true)
        let full = try ObservedRailPixels.contours(image: source, region: CGRect(x: 0, y: 0, width: 96, height: 128))
        for region in [CGRect(x: 10, y: 10, width: 60, height: 70), CGRect(x: 10.2, y: 10.3, width: 60.1, height: 70.1)] {
            let cropped = try ObservedRailPixels.contours(image: source, region: region)
            #expect(keys(cropped) == keys(full))
        }
    }

    @Test func invalidAndNonfiniteRegionsThrowInsteadOfLookingLikeBlankFrames() throws {
        let source = try image()
        for region in [CGRect(x: -1, y: 0, width: 10, height: 10), CGRect(x: 0, y: 0, width: 97, height: 10),
                       CGRect(x: 0, y: 0, width: 0, height: 10), CGRect(x: CGFloat.nan, y: 0, width: 10, height: 10)] {
            #expect(throws: RailPixelError.invalidRegion) { try ObservedRailPixels.contours(image: source, region: region) }
        }
        #expect(try ObservedRailPixels.contours(image: source, region: CGRect(x: 0, y: 0, width: 96, height: 128)).isEmpty)
    }

    @Test func oversizedCropFailsBeforeNativeProcessing() throws {
        let source = try image(width: 2048, height: 1024)
        #expect(throws: RailPixelError.tooComplex) {
            try ObservedRailPixels.contours(image: source, region: CGRect(x: 0, y: 0, width: 2048, height: 1024))
        }
    }
}
