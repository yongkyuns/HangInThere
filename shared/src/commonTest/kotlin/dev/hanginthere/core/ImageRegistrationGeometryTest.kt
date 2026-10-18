package dev.hanginthere.core

import kotlin.math.*
import kotlin.test.*

class ImageRegistrationGeometryTest {
    private val width = 960.0
    private val height = 540.0
    private fun scale(h: DoubleArray) = ImageRegistrationGeometry.areaScaleAtImageCenter(h, width, height)
    private fun centeredPerspective(zoom: Double, p: Double, q: Double): DoubleArray {
        val x = width/2; val y = height/2; val offset = p*x+q*y
        return doubleArrayOf(zoom+x*p, y*p, p, x*q, zoom+y*q, q,
            x*(1-zoom-offset), y*(1-zoom-offset), 1-offset)
    }

    @Test fun affineZoomIsIndependentOfRotationAndTranslation() {
        for (zoom in listOf(.75, 1.0, 1.05, 1.9)) for (rotation in listOf(-.6, 0.0, .8)) {
            val a = zoom * cos(rotation); val b = zoom * sin(rotation)
            assertEquals(zoom, assertNotNull(scale(doubleArrayOf(a, b, 0.0, -b, a, 0.0, 71.0, -39.0, 1.0))), 1e-12)
        }
    }

    @Test fun perspectiveDivideDoesNotInventScaleAtTheCenter() {
        // Perspective about the image center has unit derivative there. Its
        // raw upper-left block includes the projective terms and is not scale.
        val h = centeredPerspective(1.0, .00002, .00004)
        assertEquals(1.0, assertNotNull(scale(h)), 1e-12)
        val incorrectAffineScale = sqrt((h[0]*h[4]-h[1]*h[3])/(h[8]*h[8]))
        assertTrue(abs(incorrectAffineScale-1) > .02)
    }

    @Test fun perspectiveCorrectionPreservesGenuineZoom() {
        for (zoom in listOf(.8, 1.03, 1.08)) {
            assertEquals(zoom, assertNotNull(scale(centeredPerspective(zoom, .00002, -.00007))), 1e-12)
        }
    }

    @Test fun finiteCenterAreaDoesNotCertifyUniformScaleOrConditioning() {
        val anisotropic = doubleArrayOf(4.0,0.0,0.0,0.0,.25,0.0,0.0,0.0,1.0)
        assertEquals(1.0, assertNotNull(scale(anisotropic)), 1e-12)
        // The horizon is just outside the image. A center measurement remains
        // finite even though scale varies sharply toward that boundary.
        val nearBoundary = doubleArrayOf(1.0,0.0,-.999999/width,0.0,1.0,0.0,0.0,0.0,1.0)
        assertTrue(assertNotNull(scale(nearBoundary)).isFinite())
    }

    @Test fun scaleMatchesIndependentFiniteDifferenceOfProjectedPoints() {
        for (p in listOf(-.0001, .00007)) for (q in listOf(-.00008, .00004)) {
            val h = doubleArrayOf(1.04, .02, p, -.03, .98, q, 12.0, -9.0, 1.0)
            fun project(x: Double, y: Double): Pair<Double,Double> {
                val w = h[2]*x+h[5]*y+h[8]
                return (h[0]*x+h[3]*y+h[6])/w to (h[1]*x+h[4]*y+h[7])/w
            }
            val epsilon = .001
            val origin = project(width/2, height/2)
            val dx = project(width/2+epsilon, height/2)
            val dy = project(width/2, height/2+epsilon)
            val area = ((dx.first-origin.first)*(dy.second-origin.second) -
                (dy.first-origin.first)*(dx.second-origin.second))/(epsilon*epsilon)
            assertEquals(sqrt(area), assertNotNull(scale(h)), 1e-6)
        }
    }

    @Test fun homogeneousMatrixMagnitudeAndSignDoNotChangeScale() {
        val h = doubleArrayOf(1.05, .0, .0, .0, 1.05, .0, 12.0, -9.0, 1.0)
        for (factor in listOf(-1e100, -1e-100, 1e-100, 1e100)) {
            assertEquals(1.05, assertNotNull(scale(h.map { it*factor }.toDoubleArray())), 1e-12)
        }
    }

    @Test fun capturedProjectiveTraceDoesNotCreateSustainedScaleEvidence() {
        // Actual arm64 Vision output, commit1048b5d, CI run39162730645.
        // Native report SHA256 9cf6687993d1fc28d2857faa4c4c2da934f5a0537024203fc10dc753e72b7f8e.
        // These measured transforms have perspective terms; their raw affine
        // blocks falsely crossed the unchanged scale dwell at .6 and .9s.
        // This protects the arithmetic and gate boundary, not background-fit accuracy.
        val matrices = listOf(
            doubleArrayOf(1.0301060676574707, .012515325099229813, .00002728555045905523,
                -.01476475689560175, .9963067173957825, -.00003896873749908991,
                .7056699991226196, 2.867696762084961, 1.0208133459091187),
            doubleArrayOf(1.0095020532608032, .007711899932473898, .00001002176486508688,
                .004410670138895512, 1.0183058977127075, .000021950476366328076,
                -4.384067535400391, -10.059789657592773, .9881234169006348),
            doubleArrayOf(.9619085192680359, -.010948104783892632, -.000027373213015380315,
                .00479432288557291, .9909170866012573, .000007532275958510581,
                10.057558059692383, 2.89955997467041, .9955582618713379),
        )
        val corrected = StaticSceneStability(); val old = StaticSceneStability()
        assertTrue(corrected.calibrate(ImageSize(width, height)))
        assertTrue(old.calibrate(ImageSize(width, height)))
        matrices.forEachIndexed { index, h ->
            val translations = List(3) { StaticSceneStability.PatchTranslation(0.0, 0.0) } +
                if (index == 2) listOf(StaticSceneStability.PatchTranslation(-16.0, -1.0)) else emptyList()
            val time = (index+1)*.3
            corrected.observe(translations, abs(assertNotNull(scale(h))-1), time)
            val oldFraction = abs(sqrt(abs(h[0]*h[4]-h[1]*h[3]))/abs(h[8])-1)
            old.observe(translations, oldFraction, time)
        }
        assertEquals(StaticSceneStability.State.moved, old.state)
        assertEquals(StaticSceneStability.MovementKind.scale, old.movementKind)
        assertEquals(StaticSceneStability.State.stable, corrected.state)
        assertTrue(corrected.isReady(.9))
    }

    @Test fun invalidOrFoldedMappingsAreUnavailable() {
        val identity = doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        assertNull(scale(DoubleArray(8))); assertNull(scale(DoubleArray(10))); assertNull(scale(DoubleArray(9)))
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            for (index in 0..8) assertNull(scale(identity.copyOf().also { it[index] = invalid }))
            assertNull(ImageRegistrationGeometry.areaScaleAtImageCenter(identity, invalid, height))
        }
        assertNull(ImageRegistrationGeometry.areaScaleAtImageCenter(identity, 0.0, height))
        assertNull(ImageRegistrationGeometry.areaScaleAtImageCenter(identity, width, -height))
        assertNull(scale(identity.copyOf().also { it[0] = 0.0 }))
        assertNull(scale(identity.copyOf().also { it[0] = -1.0 }))
        assertNull(scale(identity.copyOf().also { it[2] = -2.0/width }))
        assertNull(scale(identity.copyOf().also { it[2] = -1.0/width }))
    }
}
