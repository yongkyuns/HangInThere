package dev.hanginthere.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hanginthere.android.vision.SceneRegistration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class SceneRegistrationTest {
    @Test fun nativeBackgroundRequiresTextureAndMeasuresDisplacementWithoutFollowingTheAthlete() {
        val blank=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val textured=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888).apply {
            val random=Random(42);setPixels(IntArray(320*480) { val v=random.nextInt(256);Color.rgb(v,v,v) },0,320,0,0,320,480)
        }
        val center=textured.copy(Bitmap.Config.ARGB_8888,true).apply {
            Canvas(this).drawRect(110f,170f,210f,310f,Paint().apply { color=Color.BLACK })
        }
        val shifted=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888).apply { Canvas(this).drawBitmap(textured,9f,0f,null) }
        try {
            SceneRegistration().use { scene ->
                scene.freeze(blank)
                assertNull(scene.observe(blank).scaleFraction)
                scene.freeze(textured)
                val still=scene.observe(center)
                assertNotNull(still.scaleFraction);assertTrue(still.scaleFraction!!<.002)
                assertTrue(still.translations.size>=3)
                assertTrue(still.translations.all { abs(it.dxPixels)<.2 && abs(it.dyPixels)<.2 })
                val moved=scene.observe(shifted)
                assertNotNull(moved.scaleFraction);assertTrue(moved.translations.size>=3)
                assertTrue(moved.translations.all { abs(it.dxPixels-9)<.2 && abs(it.dyPixels)<.2 })
            }
        } finally { blank.recycle();textured.recycle();center.recycle();shifted.recycle() }
    }
}
