plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val media3 = "1.11.1"

android {
    namespace = "com.cutviet.editor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cutviet.editor"
        minSdk = 24
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk {
            // Native code (whisper.cpp) is built for 64-bit ARM only: the owner's OnePlus 13 and
            // every current phone. Other devices still run the app, without offline dubbing.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Fixed key committed with the source so every build can be installed over the previous one.
        create("app") {
            storeFile = file("cutviet.keystore")
            storePassword = "cutviet123"
            keyAlias = "cutviet"
            keyPassword = "cutviet123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("app")
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // On-device LLM (Gemma 3n) for offline translation.
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    // Voice / background separation model (UVR MDX-Net) on the CPU.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    // WebSocket client for the online (Gemini Live) branch.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}
