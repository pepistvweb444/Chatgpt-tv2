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

    // Fully embedded/offline Spanish speech recognition. No Android/Google
    // SpeechRecognizer service and no paid transcription API are required.
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // Google Home APIs Android SDK is distributed by Google as local AAR files,
    // not from Google Maven. Drop the authenticated SDK AARs in mobile/libs/.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
}

// Bundle the lightweight Spanish Vosk model into the APK during CI. The model
// is Apache-2.0, about 39 MB, and runs locally on-device after installation.
val prepareVoskSpanishModel = tasks.register("prepareVoskSpanishModel") {
    val modelDir = file("src/main/assets/model-es")
    outputs.dir(modelDir)
    doLast {
        val marker = file("src/main/assets/model-es/am/final.mdl")
        if (!marker.exists()) {
            val zipFile = file("$buildDir/vosk-model-small-es-0.42.zip")
            zipFile.parentFile.mkdirs()
            java.net.URI("https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip")
                .toURL().openStream().use { input ->
                    zipFile.outputStream().use { output -> input.copyTo(output) }
                }
            val unpackDir = file("$buildDir/vosk-es-unpacked")
            delete(unpackDir)
            copy {
                from(zipTree(zipFile))
                into(unpackDir)
            }
            val sourceDir = file("$buildDir/vosk-es-unpacked/vosk-model-small-es-0.42")
            modelDir.mkdirs()
            copy {
                from(sourceDir)
                into(modelDir)
            }
            file("src/main/assets/model-es/uuid").writeText("jarvis-vosk-es-0.42")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareVoskSpanishModel)
}
