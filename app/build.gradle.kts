import java.io.File
import java.net.URI
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.dancewithme"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.dancewithme"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        noCompress += listOf("task", "tflite", "bin", "mdl", "fst", "conf", "uuid", "int", "ivector", "dubm", "ie", "mat", "stats")
    }
}

val downloadEdgeAiModels = tasks.register("downloadEdgeAiModels") {
    description = "Descarcă și instalează modelele MediaPipe Pose Landmarker Lite și Vosk Small în app/src/main/assets/"
    val assetsDir = layout.projectDirectory.dir("src/main/assets").asFile
    val mpFile = File(assetsDir, "pose_landmarker_lite.task")
    val voskDir = File(assetsDir, "model-small")
    val voskUuidFile = File(voskDir, "uuid")

    outputs.file(mpFile)
    outputs.dir(voskDir)

    doLast {
        assetsDir.mkdirs()

        // 1. Download MediaPipe pose_landmarker_lite.task
        if (!mpFile.exists() || mpFile.length() < 100_000L) {
            val mpUrl = URI("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task").toURL()
            mpUrl.openStream().use { input ->
                mpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            println("Downloaded MediaPipe model: ${mpFile.length()} bytes")
        }

        // 2. Download & Extract Vosk vosk-model-small-en-us-0.15.zip
        val amDir = File(voskDir, "am")
        if (!amDir.exists()) {
            val tempZip = File(assetsDir, "vosk-temp.zip")
            val voskUrl = URI("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").toURL()
            voskUrl.openStream().use { input ->
                tempZip.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            println("Downloaded Vosk zip: ${tempZip.length()} bytes")

            ZipFile(tempZip).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val relativeName = entry.name.substringAfter("/", "")
                    if (relativeName.isEmpty()) continue
                    val outFile = File(voskDir, relativeName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { zIn ->
                            outFile.outputStream().use { fOut ->
                                zIn.copyTo(fOut)
                            }
                        }
                    }
                }
            }
            tempZip.delete()
        }

        // 3. Ensure uuid file exists for org.vosk.android.StorageService.unpack
        voskDir.mkdirs()
        voskUuidFile.writeText("vosk-model-small-en-us-0.15\n")
        println("Vosk model ready in: ${voskDir.absolutePath}")
    }
}

tasks.named("preBuild") {
    dependsOn(downloadEdgeAiModels)
}

dependencies {
    // UI & Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)

    // Lifecycle & Architecture (MVVM / Coroutines)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // AI / ML - Google MediaPipe Tasks Vision
    implementation(libs.mediapipe.tasks.vision)

    // Audio - Vosk Offline Speech-to-Text
    implementation(libs.vosk.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(variantOf(libs.jna) { artifactType("aar") })

    // Network & JSON Serialization (Retrofit & Kotlinx Serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
