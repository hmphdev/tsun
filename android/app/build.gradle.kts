plugins {
    id("com.android.application") version "8.9.2"
    id("org.jetbrains.kotlin.android") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

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
