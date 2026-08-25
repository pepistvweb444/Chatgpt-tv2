plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarvis.mobile.stable"
        minSdk = 28
        targetSdk = 35
        versionCode = 8
        versionName = "0.2.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")

    // Google Home APIs Android SDK is distributed by Google as local AAR files,
    // not from Google Maven. Drop the authenticated SDK AARs in mobile/libs/.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}
