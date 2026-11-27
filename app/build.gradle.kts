plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.mahendrabishnoi2.flyoff"
    compileSdk {
        // Compose 1.13 needs the Android 17 minor SDK 37.1 or later to compile against.
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.mahendrabishnoi2.flyoff"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // For testing only: `-Pflyoff.testSigning` signs the release build with
            // the debug key so it can be installed (CI uses this). Never publish such a build.
            if (providers.gradleProperty("flyoff.testSigning").isPresent) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    // MapLibre's native library is the bulk of the APK, so build one APK per
    // CPU type as well as a universal one. The size budget is per CPU type.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // The User-Agent of route map downloads names the app version.
        buildConfig = true
    }

    // MapLibre reads the bundled map with random byte-range access, which is
    // slow on a compressed asset. The tiles inside are already gzipped.
    androidResources {
        noCompress += "pmtiles"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
