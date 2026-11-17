import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

val downloadFaceModels = tasks.register("downloadFaceModels") {
    description = "Downloads open-source TFLite face detection & MobileFaceNet embedding models into assets/models/"
    group = "ai-models"

    val modelsDir = layout.projectDirectory.dir("src/main/assets/models").asFile
    val models = mapOf(
        "blaze_face_short_range.tflite" to "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite",
        "mobilefacenet.tflite" to "https://github.com/MCarlomagno/FaceRecognitionAuth/raw/master/assets/mobilefacenet.tflite"
    )

    outputs.files(models.keys.map { File(modelsDir, it) })

    doLast {
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        models.forEach { (fileName, urlString) ->
            val target = File(modelsDir, fileName)
            if (target.exists() && target.length() > 100_000L) {
                logger.lifecycle("Model already present: ${target.name} (${target.length()} bytes)")
                return@forEach
            }
            logger.lifecycle("Downloading AI model $fileName from $urlString ...")
            var currentUrl = urlString
            var redirects = 0
            while (redirects < 6) {
                val connection = (URI.create(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "HyperGalleryAI-Gradle-Fetcher/1.0")
                }
                val code = connection.responseCode
                if (code in 301..308) {
                    val location = connection.getHeaderField("Location")
                        ?: error("Redirect without Location header for $currentUrl")
                    currentUrl = URI.create(currentUrl).resolve(location).toString()
                    connection.disconnect()
                    redirects++
                } else if (code == HttpURLConnection.HTTP_OK) {
                    val tempFile = File(modelsDir, "$fileName.part")
                    connection.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    connection.disconnect()
                    check(tempFile.length() > 100_000L) {
                        "Downloaded model $fileName is unexpectedly small (${tempFile.length()} bytes)"
                    }
                    val header = ByteArray(8)
                    tempFile.inputStream().use { it.read(header) }
                    val magic = String(header, 4, 4, Charsets.US_ASCII)
                    check(magic == "TFL3") {
                        "Invalid TFLite flatbuffer header '$magic' for $fileName"
                    }
                    if (target.exists()) target.delete()
                    check(tempFile.renameTo(target)) { "Failed to move $tempFile to $target" }
                    logger.lifecycle("Verified TFL3 model $fileName (${target.length()} bytes)")
                    break
                } else {
                    connection.disconnect()
                    error("HTTP $code while downloading $currentUrl")
                }
            }
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadFaceModels)
}

android {
    namespace = "com.hypergallery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hypergallery.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 100
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(System.getenv("KEYSTORE_PATH") ?: "keystore/hypergallery-release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "HyperGalleryRelease2026!"
            keyAlias = System.getenv("KEY_ALIAS") ?: "hypergallery"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "HyperGalleryRelease2026!"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    androidResources {
        noCompress += listOf("tflite")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val releaseDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        if (releaseDir.exists()) {
            val arm64Src = releaseDir.listFiles()?.firstOrNull {
                it.name.contains("arm64-v8a") && it.name.endsWith("-release.apk")
            }
            val universalSrc = releaseDir.listFiles()?.firstOrNull {
                it.name.contains("universal") && it.name.endsWith("-release.apk")
            }
            arm64Src?.copyTo(File(releaseDir, "HyperGallery-AI-arm64-v8a-release.apk"), overwrite = true)
            universalSrc?.copyTo(File(releaseDir, "HyperGallery-AI-universal-release.apk"), overwrite = true)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.print)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.profileinstaller)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.video)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)

    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    implementation(libs.tensorflow.lite.gpu.api)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
