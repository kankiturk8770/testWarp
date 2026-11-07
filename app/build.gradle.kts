plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cafe.kankipro.warp"
    compileSdk = 36
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "cafe.kankipro.warp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Only arm64: building the Rust core takes ~40 minutes per ABI in CI.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=none" } }
    }

    // Personal test builds: one fixed key in the repository, so every new build installs
    // over the previous one. Not for public distribution.
    signingConfigs {
        create("kanki") {
            storeFile = file("kankiwarp.jks")
            storePassword = "kankiwarp"
            keyAlias = "kankiwarp"
            keyPassword = "kankiwarp"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("kanki") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kanki")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { t ->
                t.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                t.maxHeapSize = "3g"
            }
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // screenshots of the screens without a phone (ShotsTest, Robolectric); not inside the APK
    testImplementation(composeBom)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
