package dev.hanginthere.android

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.session.WorkoutViewModel
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises real Compose + ViewModel + production replay without needing camera permission/hardware. */
@RunWith(AndroidJUnit4::class)
class WorkoutUiEvaluationTest {
    @Test fun importedPublicVideoRendersCompletedWorkout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val testAssets = instrumentation.context.assets
        val manifest = testAssets.open("generated/manifest.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val fixture = manifest.getValue("clips").jsonArray.first().jsonObject
        val source = fixture.getValue("video").jsonPrimitive.content
        val file = File(app.cacheDir, "ui-evaluation.mp4")
        testAssets.open("generated/$source").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: WorkoutViewModel
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[WorkoutViewModel::class.java]
                    model.replay(Uri.fromFile(file))
                }
                val deadline = System.nanoTime() + 120_000_000_000L
                while (!model.state.value.finished && System.nanoTime() < deadline) Thread.sleep(100)
                assertTrue("Replay did not terminate", model.state.value.finished)
                assertNull(model.state.value.error)
                val expected = fixture.getValue("expectedMovements").jsonPrimitive.int
                assertEquals(expected, model.state.value.result?.snapshot?.observedMovements)
                instrumentation.waitForIdleSync()
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull("Rendered app screenshot unavailable", screenshot)
                val output = requireNotNull(app.getExternalFilesDir("evaluation"))
                File(output, "ui-evaluation.png").outputStream().use { requireNotNull(screenshot).compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot?.recycle()
                File(output, "ui-report.json").writeText(buildJsonObject {
                    put("fixture", fixture.getValue("id")); put("expectedMovements", expected)
                    put("observedMovements", model.state.value.result?.snapshot?.observedMovements ?: -1)
                    put("phase", model.state.value.result?.snapshot?.phase?.name ?: "unknown")
                    put("cameraPermissionRequired", false); put("screenshot", "ui-evaluation.png")
                }.toString())
            }
        } finally { file.delete() }
    }
}
