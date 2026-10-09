package dev.hanginthere.android

import dev.hanginthere.android.ui.VideoTransform
import dev.hanginthere.core.Point2D
import org.junit.Assert.*
import org.junit.Test

class VideoTransformTest {
    @Test fun centerCropRoundTripsUprightPixelsWithoutMirroring() {
        val transform = VideoTransform(720.0, 1280.0, 390.0, 844.0)
        val left = Point2D(100.0, 700.0); val right = Point2D(620.0, 700.0)
        assertTrue(transform.display(left).x < transform.display(right).x)
        val restored = transform.image(transform.display(left))
        assertEquals(left.x, restored.x, 1e-9); assertEquals(left.y, restored.y, 1e-9)
        assertEquals(195.0, transform.display(Point2D(360.0, 640.0)).x, 1e-9)
        assertEquals(422.0, transform.display(Point2D(360.0, 640.0)).y, 1e-9)
    }
}
