package dev.hanginthere.android

import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.replay.VideoFrames
import dev.hanginthere.android.replay.YuvColorEncoding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Authored Main10 sources, independently decoded with the FFmpeg CLI reference. */
@RunWith(AndroidJUnit4::class)
class HdrVideoFramesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val pts = listOf(0L, 110000L, 230000L, 400000L, 710000L, 960000L)
    private fun fixture(name: String): File {
        val file = File(app.cacheDir, "authored-$name.mp4")
        instrumentation.context.assets.open("hdr/$name.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val provenance = instrumentation.context.assets.open("hdr/$name.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals(provenance.getString("sha256"), MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) })
        return file
    }

    @Test fun hlgAndPqImportEveryVfrFrameInUprightVerifiedSdr() {
        listOf("hlg" to 125, "pq" to 153).forEach { (name, middle) ->
            val file = fixture(name)
            try {
                // Reopen from zero: buffered B-frames must drain and rewind completely.
                repeat(2) {
                    VideoFrames(app, Uri.fromFile(file)).use { reader ->
                        assertEquals("FFmpeg 8.1.3", reader.decoderDiagnostics.backend)
                        assertEquals(96, reader.sourceUprightWidth); assertEquals(128, reader.sourceUprightHeight)
                        pts.forEach { expected ->
                            requireNotNull(reader.next()).use { frame ->
                                assertEquals(expected, frame.presentationTimeUs)
                                assertEquals(96, frame.bitmap.width); assertEquals(128, frame.bitmap.height)
                                assertEquals(YuvColorEncoding(YuvColorEncoding.Standard.bt709), reader.decodedColorEncoding)
                                // Counterclockwise quarter-turn places original dark/mid/white panels bottom-to-top.
                                listOf(107 to 0, 63 to middle, 19 to 255).forEach { (y, value) ->
                                    val pixel = frame.bitmap.getPixel(48, y)
                                    listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)).forEach { actual ->
                                        assertTrue("$name tone-mapped neutral $value vs $actual", kotlin.math.abs(value - actual) <= 2)
                                    }
                                }
                            }
                        }
                        assertNull("No extra or repeated frame", reader.next())
                        assertNull(reader.next())
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun cancelledHdrDecodeDoesNotPublishPixelsAndCloseIsIdempotent() {
        val file = fixture("hlg")
        try {
            val reader = VideoFrames(app, Uri.fromFile(file))
            assertNull(reader.next { true })
            requireNotNull(reader.next()).use { assertEquals(0L, it.presentationTimeUs) }
            reader.close(); reader.close(); assertNull(reader.next())
            VideoFrames(app, Uri.fromFile(file)).use { active ->
                var checks = 0
                assertNull("Cancellation must reach packet scanning inside JNI", active.next { ++checks >= 4 })
                assertTrue("Native cancellation callback was not called", checks >= 4)
            }
        } finally { file.delete() }
    }

    @Test fun coloredBt2020PixelsResizeAndConvertToBt709BeforeRotation() {
        val file = fixture("color-hlg")
        try {
            VideoFrames(app, Uri.fromFile(file)).use { reader ->
                pts.forEach { expected ->
                    requireNotNull(reader.next()).use { frame ->
                        assertEquals(expected, frame.presentationTimeUs)
                        assertEquals(76, frame.bitmap.width); assertEquals(1280, frame.bitmap.height)
                        listOf(1079 to listOf(209, 1, 39), 639 to listOf(0, 166, 0), 199 to listOf(0, 27, 196)).forEach { (y, rgb) ->
                            val pixel = frame.bitmap.getPixel(38, y)
                            rgb.zip(listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))).forEach { (value, actual) ->
                                assertTrue("BT2020-to-BT709 color $value vs $actual", kotlin.math.abs(value - actual) <= 2)
                            }
                        }
                    }
                }
                assertNull(reader.next())
            }
        } finally { file.delete() }
    }
}
