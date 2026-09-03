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
        versionCode = 20
        versionName = "0.2.20"
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
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}

val prepareVoskSpanishModel = tasks.register<Exec>("prepareVoskSpanishModel") {
    workingDir(projectDir)
    commandLine("python3", "prepare_vosk_model.py")
}

val validateJarvisFeatures = tasks.register<Exec>("validateJarvisFeatures") {
    workingDir(rootDir)
    commandLine("python3", "mobile/validate_required_features.py")
}

tasks.named("preBuild").configure {
    dependsOn(prepareVoskSpanishModel)
    dependsOn(validateJarvisFeatures)
}
