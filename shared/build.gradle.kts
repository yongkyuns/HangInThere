plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library") apply false
}
val appleOnly = providers.gradleProperty("workout.appleOnly").orNull == "true"
if (!appleOnly) apply(plugin = "com.android.library")
kotlin {
    jvmToolchain(17)
    if (!appleOnly) androidTarget()
    jvm()
    iosArm64 { binaries.framework { baseName = "WorkoutCore"; isStatic = true } }
    iosX64 { binaries.framework { baseName = "WorkoutCore"; isStatic = true } }
    iosSimulatorArm64 { binaries.framework { baseName = "WorkoutCore"; isStatic = true } }
    macosArm64 { binaries.framework { baseName = "WorkoutCore"; isStatic = true } }
    macosX64 { binaries.framework { baseName = "WorkoutCore"; isStatic = true } }
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
        // Evaluation contracts compile into tests only, never the shipping framework.
        getByName("jvmTest").kotlin.srcDirs("../Evaluation/android", "../Evaluation/androidTests")
        getByName("jvmTest").resources.srcDir(rootProject.tasks.named("prepareEvaluationTestResources"))
    }
}
tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    // These tests read external Swift snapshots and export evidence. Gradle
    // otherwise considers a new oracle/report path identical to a prior run.
    for ((name, relative) in listOf("WORKOUT_ORACLE" to "build/workout-oracle/oracle.json",
            "SETUP_ORACLE" to "build/setup-oracle/oracle.json")) {
        inputs.file(providers.environmentVariable(name).orElse(rootProject.file(relative).absolutePath))
            .withPropertyName(name).withPathSensitivity(PathSensitivity.ABSOLUTE)
    }
    for ((name, relative) in listOf("WORKOUT_PARITY_REPORT" to "build/workout-oracle/kotlin-parity.json",
            "SETUP_PARITY_REPORT" to "build/setup-oracle/kotlin-parity.json")) {
        val path = providers.environmentVariable(name).orElse(rootProject.file(relative).absolutePath)
        inputs.property(name, path)
        outputs.file(path).withPropertyName(name)
    }
    // Missing evidence must rerun the exporting test even with cached test outputs.
    val progressReport = providers.environmentVariable("PUBLIC_REPLAY_PROGRESS_REPORT")
    inputs.property("PUBLIC_REPLAY_PROGRESS_REPORT", progressReport.orElse(""))
    progressReport.orNull?.let { outputs.file(it).withPropertyName("PUBLIC_REPLAY_PROGRESS_REPORT") }
    // Opt-in rail-free development replay binds its actual external observations
    // and scoring reference; changing the output path must also rerun the export.
    for (name in listOf("BODY_DIP_POSES", "BODY_DIP_REFERENCE")) {
        val value = providers.environmentVariable(name)
        inputs.property(name, value.orElse(""))
        value.orNull?.let { inputs.file(it).withPropertyName(name).withPathSensitivity(PathSensitivity.ABSOLUTE) }
    }
    val bodyDipReport = providers.environmentVariable("BODY_DIP_REPORT")
    inputs.property("BODY_DIP_REPORT", bodyDipReport.orElse(""))
    bodyDipReport.orNull?.let { outputs.file(it).withPropertyName("BODY_DIP_REPORT") }
    val pullUpMotionInput = providers.environmentVariable("PULLUP_MOTION_INPUT")
    inputs.property("PULLUP_MOTION_INPUT", pullUpMotionInput.orElse(""))
    pullUpMotionInput.orNull?.let { inputs.file(it).withPropertyName("PULLUP_MOTION_INPUT").withPathSensitivity(PathSensitivity.ABSOLUTE) }
    val pullUpMotionOutput = providers.environmentVariable("PULLUP_MOTION_OUTPUT")
    inputs.property("PULLUP_MOTION_OUTPUT", pullUpMotionOutput.orElse(""))
    pullUpMotionOutput.orNull?.let { outputs.file(it).withPropertyName("PULLUP_MOTION_OUTPUT") }
    val pullUpProductionReport = providers.environmentVariable("PULLUP_PRODUCTION_PARITY_REPORT")
    inputs.property("PULLUP_PRODUCTION_PARITY_REPORT", pullUpProductionReport.orElse(""))
    pullUpProductionReport.orNull?.let { outputs.file(it).withPropertyName("PULLUP_PRODUCTION_PARITY_REPORT") }
    val pullUpLowerRecoveryReport = providers.environmentVariable("PULLUP_LOWER_RECOVERY_REPORT")
    inputs.property("PULLUP_LOWER_RECOVERY_REPORT", pullUpLowerRecoveryReport.orElse(""))
    pullUpLowerRecoveryReport.orNull?.let { outputs.file(it).withPropertyName("PULLUP_LOWER_RECOVERY_REPORT") }
    val overheadPullUpInput = providers.environmentVariable("OVERHEAD_PULLUP_INPUT")
    inputs.property("OVERHEAD_PULLUP_INPUT", overheadPullUpInput.orElse(""))
    overheadPullUpInput.orNull?.let { inputs.file(it).withPropertyName("OVERHEAD_PULLUP_INPUT") }
    val overheadPullUpOutput = providers.environmentVariable("OVERHEAD_PULLUP_OUTPUT")
    inputs.property("OVERHEAD_PULLUP_OUTPUT", overheadPullUpOutput.orElse(""))
    overheadPullUpOutput.orNull?.let { outputs.dir(it).withPropertyName("OVERHEAD_PULLUP_OUTPUT") }
    val overheadOwnerOutput = providers.environmentVariable("OVERHEAD_OWNER_OUTPUT")
    inputs.property("OVERHEAD_OWNER_OUTPUT", overheadOwnerOutput.orElse(""))
    overheadOwnerOutput.orNull?.let { outputs.dir(it).withPropertyName("OVERHEAD_OWNER_OUTPUT") }
}
if (!appleOnly) extensions.configure<com.android.build.gradle.LibraryExtension> {
    namespace = "dev.hanginthere.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
