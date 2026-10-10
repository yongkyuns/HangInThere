import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
val poseRuntimeVersion = "0.10.32"
val poseRuntimeSha256 = "d6e69475707d07a24478e9ff00c437c48c9a834ffb04d4e6e0ba907defa87777"
val composeBom = "androidx.compose:compose-bom:2025.10.00"
android {
    namespace = "dev.hanginthere.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.hanginthere.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "POSE_RUNTIME_VERSION", "\"$poseRuntimeVersion\"")
        buildConfigField("String", "POSE_RUNTIME_AAR_SHA256", "\"$poseRuntimeSha256\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        // The APK gate verifies duplicate selection against the pinned NDK.
        // Preserve its bytes so AGP stripping cannot invalidate that check.
        jniLibs.pickFirsts += "**/libc++_shared.so"
        jniLibs.keepDebugSymbols += "**/libc++_shared.so"
    }
    testOptions { animationsDisabled = true; unitTests.isReturnDefaultValues = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
kotlin { jvmToolchain(17) }
val poseRuntimeVerification by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add(poseRuntimeVerification.name, "com.google.mediapipe:tasks-vision:$poseRuntimeVersion@aar")
    implementation(project(":shared"))
    implementation(platform(composeBom))
    androidTestImplementation(platform(composeBom))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("com.google.mediapipe:tasks-vision:$poseRuntimeVersion")
    implementation("org.opencv:opencv:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// Verify the actual upstream runtime artifact, including emulator JNI support, before packaging.
val verifyPoseRuntimeArtifact by tasks.registering {
    doLast {
        val artifact = poseRuntimeVerification.singleFile
        val digest = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { stream ->
            val block = ByteArray(65536)
            while (true) { val size = stream.read(block); if (size < 0) break; digest.update(block, 0, size) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == poseRuntimeSha256) { "MediaPipe runtime checksum mismatch: $actual" }
        ZipFile(artifact).use { archive ->
            listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64").forEach { abi ->
                check(archive.getEntry("jni/$abi/libmediapipe_tasks_vision_jni.so") != null) { "MediaPipe runtime lacks $abi JNI" }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyPoseRuntimeArtifact) }
