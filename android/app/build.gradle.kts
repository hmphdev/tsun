plugins {
    id("com.android.application") version "8.9.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

// CI decodes a pinned keystore into this file so every build can update the
// previous install in place. Local builds without it fall back to the default
// debug key.
val pinnedKeystore = System.getenv("TSUN_KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }

android {
    namespace = "dev.tsun.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tsun.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    buildFeatures { compose = true }

    signingConfigs {
        if (pinnedKeystore != null) {
            create("pinned") {
                storeFile = pinnedKeystore
                storePassword = System.getenv("TSUN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TSUN_KEY_ALIAS")
                keyPassword = System.getenv("TSUN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Keep one key across CI runs: a new debug key per run made every APK
            // a fresh install that dropped the pairing. Without the secret,
            // fall back to the debug key so a sideload still works.
            signingConfig = if (pinnedKeystore != null) signingConfigs.getByName("pinned")
                else signingConfigs.getByName("debug")
        }
        debug {
            if (pinnedKeystore != null) signingConfig = signingConfigs.getByName("pinned")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
}
