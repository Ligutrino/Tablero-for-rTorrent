// App con Tablero como interfaz: Compose (cl.tablero.compose) + backend de Tablero en Kotlin
// (cl.tablero.backend) + :engine. La web/ queda para el acceso desde la red.
// Rama kotlin: sin Python. La rama main sigue con tablero.py, Chaquopy y la WebView.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Mismas ABIs que librtorrent.so.
// El Dockerfile pasa -Pabis=arm64-v8a,x86_64,... según ABIS.
val abis = (findProperty("abis") as String?)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

android {
    namespace = "cl.tablero.ui"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.tablero.android"
        minSdk = 28
        targetSdk = 35
        versionCode = 38
        versionName = "0.11-android.31-kotlin"
        ndk { abiFilters += abis }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // rtorrent se ejecuta desde nativeLibraryDir: los .so deben quedar extraídos.
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}


dependencies {
    implementation(project(":engine"))
    // Compose 1.7 y Material 3 1.3: las mismas APIs que Compose Multiplatform 1.7.3, con el que
    // tools/tablero-compose compila y prueba la interfaz en el escritorio.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
