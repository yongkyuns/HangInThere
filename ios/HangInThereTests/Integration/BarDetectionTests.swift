import Foundation
import CoreGraphics
import Testing
@testable import HangInThere

// Real Apple contour requests on original analytic pixels, independent of the
// human-pose model and its known simulator asset failure. Not real-bar accuracy.
@Suite struct BarDetectionTests {
    @Test func shortHandleExtractionUsesRequestedObservedLength() async throws {
        let image = try makeImage(width: 320, height: 320) { x, y in
            (100...112).contains(x) && (120...167).contains(y)
        }
        let region = BarRegion(Point2D(x: 60, y: 80), Point2D(x: 260, y: 280))
        let detector = VisionBarDetector()
        #expect(try await detector.detect(image: image, region: region).isEmpty)
        let edges = try await detector.detect(image: image, region: region, minimumObservedLength: 40)
        try #require(edges.count == 2)
        let pair = try #require(BarFitter.pair(edges[0].edge, edges[1].edge, minimumLength: 40))
        #expect(pair.firstEdge.length < 50 && pair.secondEdge.length < 50)
    }
    @Test(arguments: [false, true])
    func actualContoursLocalizeBothPolaritiesInOffsetCrop(bright: Bool) async throws {
        let image = try makeImage(width:640,height:360,bright:bright) { x,y in
            x >= 80 && x <= 550 && y >= 95 && y <= 107
        }
        let region = BarRegion(Point2D(x:40,y:60),Point2D(x:600,y:160))
        let candidates = try await VisionBarDetector().detect(image:image,region:region)
        let candidate = try #require(candidates.first)
        #expect(abs(candidate.edge.midpoint.y-95) < 4)
        #expect(candidate.edge.length > 440)
    }
    @Test func actualContoursPreserveObliqueImageGeometry() async throws {
        let image = try makeImage(width:640,height:360) { x,y in
            x >= 90 && x <= 540 && Double(y) >= 65+0.2*Double(x) && Double(y) <= 77+0.2*Double(x)
        }
        let candidates = try await VisionBarDetector().detect(image:image,
            region:BarRegion(Point2D(x:50,y:50),Point2D(x:600,y:230)))
        let candidate = try #require(candidates.first)
        #expect(abs(candidate.edge.midpoint.y - (65+0.2*candidate.edge.midpoint.x)) < 4)
        #expect(candidate.edge.length > 420)
    }
    @Test func barCanContinueThroughBothCropBorders() async throws {
        let image = try makeImage(width:640,height:360) { _,y in y >= 95 && y <= 107 }
        let candidates = try await VisionBarDetector().detect(image:image,
            region:BarRegion(Point2D(x:70,y:60),Point2D(x:230,y:150)))
        let candidate = try #require(candidates.first)
        #expect(abs(candidate.edge.midpoint.y-95) < 4)
        #expect(candidate.edge.length > 150)
    }
    @Test func actualContoursMergeShortOcclusionWithoutExtendingPastObservedSupport() async throws {
        let image = try makeImage(width:640,height:360) { x,y in
            x >= 80 && x <= 550 && !(x >= 300 && x <= 325) && y >= 95 && y <= 107
        }
        let candidates = try await VisionBarDetector().detect(image:image,
            region:BarRegion(Point2D(x:40,y:60),Point2D(x:600,y:160)))
        let edge = try #require(candidates.first?.edge)
        #expect(edge.a.x >= 75 && edge.a.x <= 85)
        #expect(edge.b.x >= 545 && edge.b.x <= 555)
        #expect(abs(edge.midpoint.y-95) < 4)
    }
    @Test func blankImageDoesNotProduceBar() async throws {
        let image = try makeImage(width:160,height:100) { _,_ in false }
        #expect(try await VisionBarDetector().detect(image:image,
            region:BarRegion(Point2D(x:10,y:10),Point2D(x:150,y:90))).isEmpty)
    }
    @Test func lowLightTexturedBackgroundStillPreservesObservedBarEdge() async throws {
        let width = 640, height = 360
        var bytes = [UInt8](repeating: 255, count: width * height * 4)
        var seed: UInt32 = 17
        for y in 0..<height { for x in 0..<width {
            seed = seed &* 1664525 &+ 1013904223
            let noise = Int(seed >> 24) % 17 - 8
            let inside = (80...550).contains(x) && (95...107).contains(y)
            let value = UInt8((inside ? 35 : 150) + noise)
            let i = (y * width + x) * 4
            bytes[i] = value; bytes[i + 1] = value; bytes[i + 2] = value
        } }
        let provider = try #require(CGDataProvider(data: Data(bytes) as CFData))
        let image = try #require(CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent))
        let candidates = try await VisionBarDetector().detect(image: image,
            region: BarRegion(Point2D(x: 40, y: 60), Point2D(x: 600, y: 160)))
        #expect(candidates.contains { abs($0.edge.midpoint.y - 95) < 4 && $0.edge.length > 440 })
    }
    private func makeImage(width:Int,height:Int,bright:Bool=false, inside:(Int,Int)->Bool) throws -> CGImage {
        var bytes = [UInt8](repeating:255,count:width*height*4)
        for y in 0..<height { for x in 0..<width {
            let v:UInt8 = inside(x,y) != bright ? 0 : 255
            let i = (y*width+x)*4
            bytes[i]=v;bytes[i+1]=v;bytes[i+2]=v
        } }
        let provider = try #require(CGDataProvider(data:Data(bytes) as CFData))
        return try #require(CGImage(width:width,height:height,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:width*4,
            space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.noneSkipLast.rawValue),
            provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent))
    }
}
