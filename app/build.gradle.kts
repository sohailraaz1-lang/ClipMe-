plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// -PwithLlama=false builds without the native LLM engine (CI uses this as a fallback).
val withLlama = (project.findProperty("withLlama") as String?) != "false"
val llamaTag = (project.findProperty("llamaTag") as String?) ?: "b5000"

android {
    namespace = "com.clipme.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.clipme.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"
        ndk { abiFilters += listOf("arm64-v8a") }   // same as Clipper / DIGICLIPS: arm64 phones
        if (withLlama) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DLLAMA_TAG=$llamaTag")
                }
            }
        }
    }
    if (withLlama) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:[0.17.0,0.18.0)")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:[0.17.0,0.18.0)")
    implementation("org.opencv:opencv:4.9.0")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
