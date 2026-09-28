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
        let guided = ConfirmedBar(role:.pullUpGrip,method:.guidedContours,referenceEdge:value.referenceEdge,
            oppositeEdge:nil,imageSize:size,sourceTime:value.sourceTime)
        #expect(guided.isValid)
        #expect(guided.oppositeEdge == nil)
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
    @Test func cropBorderRoundoffIsNormalizedButInvalidSamplesAreNotClamped() throws {
        let r = BarRegion(Point2D(x:365,y:90),Point2D(x:480,y:135))
        let epsilon = Float.ulpOfOne
        #expect(r.contourPoint(x:0.5,y:-epsilon) == Point2D(x:422.5,y:135))
        #expect(r.contourPoint(x:1+epsilon,y:1+epsilon) == Point2D(x:480,y:90))
        #expect(r.contourPoint(x:-0.01,y:0.5) == nil)
        #expect(r.contourPoint(x:0.5,y:1.01) == nil)
        #expect(r.contourPoint(x:.nan,y:0.5) == nil)
        #expect(r.contourPoint(x:0.5,y:.infinity) == nil)
        let polygon = [(Float(0),Float(0.3)), (1,0.3), (1,-epsilon), (0,-epsilon)]
        let points = try polygon.map { try #require(r.contourPoint(x:$0.0,y:$0.1)) }
        #expect(points.allSatisfy { r.contains($0) })
    }
    @Test func cropLengthIsNotAWholeBarAspectRatio() throws {
        // The same 20-pixel-wide rail seen through long/short setup windows.
        // Both contain enough independent edge support; neither needs extrapolation.
        for length in [Double(60),120,240] {
            let value = try #require(BarFitter.pair(line(50,100,50+length,100),
                line(50,120,50+length,120),minimumLength:24))
            #expect(value.centerline.length == length)
        }
        #expect(BarFitter.pair(line(50,100,75,100),line(50,130,75,130),minimumLength:24) == nil)
    }
    @Test func singleEdgeFitterMergesObservedCollinearFragmentsAcrossSmallGaps() throws {
        let contours = [
            [Point2D(x:80,y:100),Point2D(x:220,y:100)],
            [Point2D(x:245,y:101),Point2D(x:420,y:101)],
            [Point2D(x:440,y:100),Point2D(x:540,y:100)]
        ]
        let candidates = try BarLineFitter.candidates(contours:contours,region:region,size:size)
        let edge = try #require(candidates.first?.edge)
        #expect(edge.a.x >= 79 && edge.a.x <= 82)
        #expect(edge.b.x >= 538 && edge.b.x <= 541)
        #expect(abs(edge.midpoint.y - 100.4) < 2)
    }
    @Test func singleEdgeFitterDoesNotBridgeLargeUnobservedGap() throws {
        let contours = [
            [Point2D(x:80,y:100),Point2D(x:250,y:100)],
            [Point2D(x:350,y:100),Point2D(x:540,y:100)]
        ]
        let candidates = try BarLineFitter.candidates(contours:contours,region:region,size:size)
        #expect(candidates.count == 2)
        #expect(candidates.allSatisfy { $0.edge.length < 200 })
    }
    @Test func singleEdgeFitterRejectsCropBorderAndCollapsesDuplicateEvidence() throws {
        let border = [Point2D(x:20,y:40),Point2D(x:620,y:40)]
        let edge = [Point2D(x:80,y:100),Point2D(x:540,y:100)]
        let candidates = try BarLineFitter.candidates(
            contours:[border,edge,edge.reversed()],region:region,size:size)
        #expect(candidates.count == 1)
        #expect(abs(candidates[0].edge.midpoint.y - 100) < 1)
    }
    @Test func singleEdgeFitterKeepsDistinctRackLinesAsSeparateProposals() throws {
        let candidates = try BarLineFitter.candidates(
            contours:[
                [Point2D(x:80,y:85),Point2D(x:540,y:85)],
                [Point2D(x:80,y:175),Point2D(x:540,y:175)]
            ],region:region,size:size)
        #expect(candidates.count == 2)
        #expect(candidates[0].edge.midpoint.y == 85)
        #expect(candidates[1].edge.midpoint.y == 175)
    }
    @Test func screenGuideMapsBackToUnmirroredPixels() throws {
        let fit = try #require(AspectFit(image:size,viewport:ImageSize(width:320,height:400)))
        #expect(fit.imagePoint(fit.displayPoint(Point2D(x:50,y:100))) == Point2D(x:50,y:100))
    }
}
