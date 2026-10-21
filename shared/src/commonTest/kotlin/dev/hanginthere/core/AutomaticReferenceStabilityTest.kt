package dev.hanginthere.core

import kotlin.math.abs
import kotlin.test.*

class AutomaticReferenceStabilityTest {
    private val edge=BarSegment(Point2D(200.0,100.0),Point2D(800.0,100.0))
    private val hands=listOf(Point2D(300.0,120.0),Point2D(700.0,120.0))
    private fun pose(ticks:Long)=PoseResult(PresentationTime(ticks,1_000_000),ImageSize(1000.0,1000.0),emptyList(),"authored-stability",1)
    private fun observe(policy:AutomaticReferenceStability,ticks:Long)=policy.observe(listOf(edge),hands,pose(ticks))

    @Test fun sourceTickSubtractionPreservesElapsedBoundariesAndRejectsOverflow() {
        assertEquals(.9,PresentationTime(3900,1000).elapsedSeconds(PresentationTime(3000,1000)))
        assertEquals(-.9,PresentationTime(3000,1000).elapsedSeconds(PresentationTime(3900,1000)))
        assertTrue(abs(PresentationTime(3900,1000).elapsedSeconds(PresentationTime(3,1))-.9)<1e-12)
        assertTrue(PresentationTime(1,0).elapsedSeconds(PresentationTime(0,1)).isNaN())
        assertTrue(PresentationTime(1,1).elapsedSeconds(PresentationTime(0,-1)).isNaN())
        assertTrue(PresentationTime(Long.MAX_VALUE,1).elapsedSeconds(PresentationTime(Long.MIN_VALUE,1)).isNaN())
        assertTrue(PresentationTime(Long.MIN_VALUE,1).elapsedSeconds(PresentationTime(Long.MAX_VALUE,1)).isNaN())
    }
    @Test fun fullPointNineSecondHoldQualifiesButShortHoldAndThreeSamplesDoNot() {
        val policy=AutomaticReferenceStability()
        for(t in listOf(3_000_000L,3_300_000L,3_600_000L)) assertFalse(observe(policy,t))
        assertTrue(observe(policy,3_900_000L))
        assertEquals(AutomaticReferenceStability.Diagnostic(AutomaticReferenceStability.Status.qualified,4,.9,3.9),policy.diagnostic)
        val short=AutomaticReferenceStability()
        for(t in listOf(3_000_000L,3_300_000L,3_600_000L,3_899_999L)) assertFalse(observe(short,t))
        assertEquals(.899999,short.diagnostic.stableSeconds)
        val sparse=AutomaticReferenceStability()
        for(t in listOf(3_000_000L,3_450_000L,3_900_000L)) assertFalse(observe(sparse,t))
        assertEquals(3,sparse.diagnostic.observations)
        policy.reset();assertEquals(AutomaticReferenceStability.Diagnostic(),policy.diagnostic)
    }
    @Test fun exactMaximumGapContinuesButOneMicrosecondMoreRestarts() {
        val exact=AutomaticReferenceStability()
        for(t in listOf(3_000_000L,3_300_000L,4_100_000L)) assertFalse(observe(exact,t))
        assertTrue(observe(exact,4_400_000L))
        val beyond=AutomaticReferenceStability()
        for(t in listOf(3_000_000L,3_300_000L,4_100_001L)) assertFalse(observe(beyond,t))
        assertEquals(AutomaticReferenceStability.Status.sourceGap,beyond.diagnostic.status)
        assertEquals(1,beyond.diagnostic.observations)
        assertFalse(observe(beyond,4_400_000L))
    }
    @Test fun diagnosticsDescribeResetsWithoutCarryingOldDwell() {
        for(reason in listOf("edgeChanged","gripMoved","contextChanged","sourceGap","duplicate","reverse","invalid")) {
            val policy=AutomaticReferenceStability()
            assertFalse(observe(policy,0));assertEquals(AutomaticReferenceStability.Status.candidateStarted,policy.diagnostic.status)
            assertFalse(observe(policy,300_000));assertEquals(AutomaticReferenceStability.Status.accumulating,policy.diagnostic.status)
            val ticks=when(reason){"sourceGap"->1_100_001L;"duplicate"->300_000L;"reverse"->200_000L;else->600_000L}
            val edges=if(reason=="invalid") emptyList() else if(reason=="edgeChanged") listOf(BarSegment(edge.a.copy(y=125.0),edge.b.copy(y=125.0))) else listOf(edge)
            val moved=if(reason=="gripMoved") hands.map{it.copy(x=it.x+25)} else hands
            val frame=if(reason=="contextChanged") pose(ticks).copy(imageSize=ImageSize(1100.0,1000.0)) else pose(ticks)
            assertFalse(policy.observe(edges,moved,frame),reason)
            val expected=when(reason){"duplicate","reverse"->AutomaticReferenceStability.Status.sourceRegression;"invalid"->AutomaticReferenceStability.Status.invalidEvidence;else->AutomaticReferenceStability.Status.valueOf(reason)}
            assertEquals(expected,policy.diagnostic.status,reason)
            assertEquals(if(reason in listOf("duplicate","reverse","invalid"))0 else 1,policy.diagnostic.observations,reason)
            assertEquals(0.0,policy.diagnostic.stableSeconds,reason)
        }
    }
    @Test fun callerMutationCannotRewriteTheWitnessedBaseline() {
        val policy=AutomaticReferenceStability();val mutableEdges=mutableListOf(edge);val mutableHands=hands.toMutableList()
        assertFalse(policy.observe(mutableEdges,mutableHands,pose(0)))
        mutableEdges[0]=BarSegment(edge.a.copy(y=140.0),edge.b.copy(y=140.0));mutableHands[0]=hands[0].copy(x=350.0)
        assertFalse(policy.observe(mutableEdges,mutableHands,pose(300_000)))
        assertEquals(AutomaticReferenceStability.Status.edgeChanged,policy.diagnostic.status)
        assertEquals(1,policy.diagnostic.observations)
        for(t in listOf(600_000L,900_000L)) assertFalse(policy.observe(mutableEdges,mutableHands,pose(t)))
    }
}
