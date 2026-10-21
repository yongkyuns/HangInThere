plugins {
    kotlin("multiplatform") version "2.2.21" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
}

// Share the small source-observation fixture with both host and device tests.
// Keep evaluation media and unrelated fixtures out of the test APK.
tasks.register<Sync>("prepareEvaluationTestResources") {
    from(layout.projectDirectory.dir("Evaluation/fixtures")) {
        include("legacy-dip-search-context.json", "observed-wrist-source.json", "observed-hand-continuity-source.json.gz")
    }
    into(layout.buildDirectory.dir("generated/evaluation-test-resources"))
}
