import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: keystore.properties (not in git, see keystore.properties.example) or
// environment variables. Without it, release builds are signed with the debug key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

// Host (x86-64) native libraries for the JVM tests, see native/build-host-jni.sh and
// native/build-sherpa-onnx.sh host.
val hostNativeDirs = listOf(
    rootProject.file("native/build/host/jni"),
    rootProject.file("native/build/host/sherpa/lib"),
).joinToString(File.pathSeparator) { it.absolutePath }

android {
    namespace = "ch.madtreasures.fluency"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "ch.madtreasures.fluency"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                // no target list: the libggml-cpu-<variant>.so modules are not linked by anything,
                // they are loaded at runtime and must be built as part of "all"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "FLUENCY_KEYSTORE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "FLUENCY_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "FLUENCY_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "FLUENCY_KEY_PASSWORD")
            }
            enableV2Signing = true
            enableV3Signing = true // allows a later key rotation
        }
    }

    buildTypes {
        val release = signingConfigs.getByName("release")
        val hasReleaseKey = release.storeFile != null
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) release else signingConfigs.getByName("debug")
        }
        debug {
            // the native code is always built optimised: a debug llama.cpp is unusably slow
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
            // With the release key, "Run" in Android Studio updates the installed release app
            // instead of requiring an uninstall (which would delete the downloaded models).
            if (hasReleaseKey) signingConfig = release
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Compressed native libraries keep the APK below 30 MiB; they are extracted on install.
            useLegacyPackaging = true
            // Link-time stub only; the device's libOpenCL.so is used at runtime.
            excludes += "**/libOpenCL.so"
            // Java bindings of ONNX Runtime are not used (sherpa-onnx calls the C API).
            excludes += "**/libonnxruntime4j_jni.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { test ->
                test.jvmArgs(
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                )
                test.maxHeapSize = "4g"
                // one JVM per test class: native libraries (host JNI) and Robolectric sandboxes never mix
                test.forkEvery = 1
                test.systemProperty("java.library.path", hostNativeDirs)
                // real-model tests (integration/*) are skipped when the models are not there
                test.systemProperty("fluency.modelDir", System.getenv("FLUENCY_MODEL_DIR") ?: rootProject.file("test-models").absolutePath)
                test.systemProperty("fluency.screenshotDir", rootProject.file("docs/screenshots").absolutePath)
                test.testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
            }
        }
    }

    lint {
        // report, but never block a build on another machine/IDE version because of lint
        abortOnError = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // libonnxruntime.so for sherpa-onnx (libsherpa-onnx-jni.so is built against this exact version)
    implementation(libs.onnxruntime.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
