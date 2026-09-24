plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "0.6.15"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
}
