plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ligutrino.rtorrent.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ligutrino.rtorrent.demo"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Keystore de depuración incluido en el repo: así cada `docker build` firma igual
        // y `adb install -r` puede actualizar sin desinstalar. No usar para publicar.
        getByName("debug") {
            // android/debug.keystore no va en git: si existe se usa (APK instalables encima de los
            // anteriores); si no, Gradle usa la llave de depuración propia de cada equipo.
            val llave = rootProject.file("debug.keystore")
            if (llave.exists()) {
                storeFile = llave
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // IMPRESCINDIBLE: rtorrent se ejecuta desde nativeLibraryDir, así que los .so deben
    // instalarse extraídos en disco (no solo dentro del APK).
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
