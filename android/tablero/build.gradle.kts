// App con Tablero como interfaz: WebView + tablero.py (Python embebido con Chaquopy) + :engine.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Mismas ABIs que librtorrent.so (Chaquopy incluye un intérprete de Python por ABI).
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
        versionCode = 1
        versionName = "0.9.2-android.1"
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

chaquopy {
    defaultConfig {
        // Tablero se prueba con 3.12 y 3.13. 3.12 coincide con el python3 de la imagen Docker,
        // que Chaquopy usa para precompilar tablero.py.
        version = "3.12"
    }
}

dependencies {
    implementation(project(":engine"))
}
