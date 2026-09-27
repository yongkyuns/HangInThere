import Foundation
import Testing
@testable import HangInThere

@Suite struct BarGeometryTests {
    let size = ImageSize(width: 640, height: 360)
    var region: BarRegion { BarRegion(Point2D(x: 20, y: 40), Point2D(x: 620, y: 220)) }
    func line(_ ax: Double, _ ay: Double, _ bx: Double, _ by: Double) -> BarSegment {
        BarSegment(a: Point2D(x: ax, y: ay), b: Point2D(x: bx, y: by))
    }
    func box(_ x: Double, _ y: Double, _ w: Double, _ h: Double) -> [Point2D] {
        [Point2D(x:x,y:y), Point2D(x:x+w,y:y), Point2D(x:x+w,y:y+h), Point2D(x:x,y:y+h)]
    }
    @Test func fitsBothVisibleEdgesWithoutExtrapolation() throws {
        let value = try #require(BarFitter.pair(line(50,100,550,100), line(75,112,500,112), minimumLength:24))
        #expect(value.centerline == line(75,106,500,106))
        #expect(value.upperImageEdge == line(75,100,500,100))
    }
    @Test func reversedEndpointsDoNotReverseEdgeIdentity() throws {
        let p = line(50,100,550,100), q = line(75,112,500,112)
        #expect(BarFitter.pair(p,q,minimumLength:24) == BarFitter.pair(line(550,100,50,100),q,minimumLength:24))
    }
    @Test func rejectsCrossingAndWideAndNonoverlappingEdges() {
        #expect(BarFitter.pair(line(50,100,550,100), line(50,90,550,110),minimumLength:24) == nil)
        #expect(BarFitter.pair(line(50,100,550,100), line(50,180,550,180),minimumLength:24) == nil)
        #expect(BarFitter.pair(line(50,100,150,100), line(170,112,270,112),minimumLength:24) == nil)
        #expect(BarFitter.pair(line(50,100,550,100), line(50,101,550,101),minimumLength:24) == nil)
    }
    @Test func supportsObliqueRailsAndImageVerticals() throws {
        let angled = try #require(BarFitter.pair(line(100,80,500,180),line(98,88,498,188),minimumLength:24))
        #expect(angled.centerline.length > 390)
        let vertical = try #require(BarFitter.pair(line(100,50,100,250),line(112,50,112,250),minimumLength:24))
        #expect(vertical.upperImageEdge == nil)
    }
    @Test func perspectiveAllowsMildNonparallelEdgesButNotAnIntersection() {
        #expect(BarFitter.pair(line(50,100,550,100),line(50,110,550,116),minimumLength:24) != nil)
        #expect(BarFitter.pair(line(50,100,550,100),line(50,110,550,90),minimumLength:24) == nil)
    }
    @Test func invalidSegmentsNeverProduceGeometry() {
        #expect(BarFitter.pair(line(0,0,0,0),line(10,10,100,10),minimumLength:24) == nil)
        #expect(BarFitter.pair(line(.nan,0,100,0),line(10,10,100,10),minimumLength:24) == nil)
        #expect(BarFitter.pair(line(0,0,100,0),line(0,10,100,10),minimumLength:.nan) == nil)
    }
    @Test func simplifiesClosedPixelContours() throws {
        let candidates = try BarFitter.candidates(contours:[box(80,100,460,12)],region:region,size:size)
        #expect(candidates.count == 1)
        #expect(candidates.first?.centerline == line(80,106,540,106))
    }
    @Test func duplicateContrastContoursDoNotMultiplyProposals() throws {
        let points = box(80,100,460,12)
        let candidates = try BarFitter.candidates(contours:[points,points.reversed()],region:region,size:size)
        #expect(candidates.count == 1)
    }
    @Test func cropBordersAndSingleEdgesAreNotBars() throws {
        #expect(try BarFitter.candidates(contours:[box(20,40,600,180)],region:region,size:size).isEmpty)
        #expect(try BarFitter.candidates(contours:[[Point2D(x:50,y:100),Point2D(x:300,y:100),Point2D(x:500,y:100)]],region:region,size:size).isEmpty)
    }
    @Test func unrelatedRackBarsRemainSeparateCandidates() throws {
        let candidates = try BarFitter.candidates(contours:[box(80,80,460,12),box(80,170,460,12)],region:region,size:size)
        #expect(candidates.count == 2)
        #expect(candidates[0].centerline.midpoint.y == 86)
        #expect(candidates[1].centerline.midpoint.y == 176)
    }
    @Test func invalidGuideAndExcessComplexityFailExplicitly() {
        #expect(throws: BarFitError.self) { try BarFitter.candidates(contours:[],region:BarRegion(Point2D(x:-1,y:0),Point2D(x:30,y:30)),size:size) }
        #expect(throws: BarFitError.self) { try BarFitter.candidates(contours:[Array(repeating:Point2D(x:100,y:100),count:BarFitter.maximumPoints+1)],region:region,size:size) }
    }
    @Test func manualReferenceNeverInventsAnOppositeEdge() {
        let value = ConfirmedBar(role:.pullUpGrip,method:.manualEdge,referenceEdge:line(50,100,550,100),
            oppositeEdge:nil,imageSize:size,sourceTime:PresentationTime(value:0,timescale:30))
        #expect(value.isValid)
        #expect(value.oppositeEdge == nil)
        let invalid = ConfirmedBar(role:.pullUpGrip,method:.guidedContours,referenceEdge:value.referenceEdge,
            oppositeEdge:nil,imageSize:size,sourceTime:value.sourceTime)
        #expect(!invalid.isValid)
    }
    @Test func referencesValidateBothEdgesAndCoordinates() {
        for edge in [line(0,0,0,0),line(-1,10,200,10),line(10,.nan,100,10)] {
            let value = ConfirmedBar(role:.leftDipRail,method:.manualEdge,referenceEdge:edge,
                oppositeEdge:nil,imageSize:size,sourceTime:PresentationTime(value:0,timescale:30))
            #expect(!value.isValid)
        }
        let crossed = ConfirmedBar(role:.leftDipRail,method:.guidedContours,referenceEdge:line(50,100,550,100),
            oppositeEdge:line(50,90,550,110),imageSize:size,sourceTime:PresentationTime(value:0,timescale:30))
        #expect(!crossed.isValid)
    }
    @Test func screenGuideMapsBackToUnmirroredPixels() throws {
        let fit = try #require(AspectFit(image:size,viewport:ImageSize(width:320,height:400)))
        #expect(fit.imagePoint(fit.displayPoint(Point2D(x:50,y:100))) == Point2D(x:50,y:100))
    }
}
