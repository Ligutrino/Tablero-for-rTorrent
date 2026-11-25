// App con Tablero como interfaz: WebView + backend de Tablero en Kotlin (cl.tablero.backend) + :engine.
// Rama kotlin: sin Python. La rama main sigue con tablero.py y Chaquopy.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        versionCode = 9
        versionName = "0.11-android.2-kotlin"
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

    // rtorrent se ejecuta desde nativeLibraryDir: los .so deben quedar extraídos.
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}


dependencies {
    implementation(project(":engine"))
}
