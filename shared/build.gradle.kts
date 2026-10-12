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
    }
}
if (!appleOnly) extensions.configure<com.android.build.gradle.LibraryExtension> {
    namespace = "dev.hanginthere.core"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
