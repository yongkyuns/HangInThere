package dev.hanginthere.android

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hanginthere.android.session.WorkoutViewModel
import dev.hanginthere.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Compose + ViewModel + production replay, without camera permission/hardware. */
@RunWith(AndroidJUnit4::class)
class WorkoutUiEvaluationTest {
    @Test fun importedPublicVideoRendersCompletedWorkout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val testAssets = instrumentation.context.assets
        val manifest = testAssets.open("generated/manifest.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val fixture = manifest.getValue("clips").jsonArray.first().jsonObject
        val source = fixture.getValue("video").jsonPrimitive.content
        val expected = fixture.getValue("expectedMovements").jsonPrimitive.int
        val file = File(app.cacheDir, "ui-evaluation.mp4")
        testAssets.open("generated/$source").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model: WorkoutViewModel
                scenario.onActivity { activity ->
                    model = ViewModelProvider(activity)[WorkoutViewModel::class.java]
                    model.replay(Uri.fromFile(file))
                }
                fun awaitCompletion() {
                    val deadline = System.nanoTime() + 120_000_000_000L
                    while (!model.state.value.finished && System.nanoTime() < deadline) Thread.sleep(100)
                }
                fun recordUi(stage: String): Boolean {
                    instrumentation.waitForIdleSync()
                    val captured = instrumentation.uiAutomation.takeScreenshot()
                    val output = requireNotNull(app.getExternalFilesDir("evaluation")); output.mkdirs()
                    val filename = if (stage == "automatic") "ui-evaluation.png" else "ui-selected-evaluation.png"
                    if (captured != null) {
                        File(output, filename).outputStream().use { captured.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        captured.recycle()
                    }
                    val state = model.state.value
                    val report = buildJsonObject {
                        put("fixture", fixture.getValue("id")); put("expectedMovements", expected)
                        put("observedMovements", state.result?.snapshot?.observedMovements ?: -1)
                        put("phase", state.result?.snapshot?.phase?.name ?: "unknown")
                        put("selection", state.selection.name); put("side", state.side.name); put("finished", state.finished)
                        put("error", state.error?.let(::JsonPrimitive) ?: JsonNull)
                        put("cameraPermissionRequired", false); put("replaySettingsApplied", stage == "selected")
                        put("screenshotAvailable", captured != null); put("screenshot", filename)
                    }
                    File(output, if (stage == "automatic") "ui-report.json" else "ui-selected-report.json").writeText(report.toString())
                    return captured != null
                }
                awaitCompletion()
                val initialScreenshot = recordUi("automatic") // Evidence survives a functional failure.
                assertTrue("Replay did not terminate", model.state.value.finished)
                assertNull(model.state.value.error)
                assertEquals(expected, model.state.value.result?.snapshot?.observedMovements)
                assertTrue("Rendered app screenshot unavailable", initialScreenshot)
                // Replay preferences restart the full cached source URI on a new generation.
                scenario.onActivity { model.changeReplaySelection(WorkoutExerciseSelection.pullUp, ArmMeasurement.Side.right) }
                awaitCompletion()
                val selectedScreenshot = recordUi("selected")
                assertTrue("Changed replay selection did not terminate", model.state.value.finished)
                assertEquals(WorkoutExerciseSelection.pullUp, model.state.value.selection)
                assertEquals(ArmMeasurement.Side.right, model.state.value.side)
                assertNull(model.state.value.error)
                assertEquals(expected, model.state.value.result?.snapshot?.observedMovements)
                assertTrue("Selected-exercise screenshot unavailable", selectedScreenshot)
            }
        } finally { file.delete() }
    }
}
