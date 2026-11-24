plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ligutrino.rtorrent.engine"
    compileSdk = 35

    defaultConfig {
        // posix_spawn (usado por rtorrent) existe en bionic desde API 28.
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // El binario va como engine/src/main/jniLibs/<abi>/librtorrent.so (lo pone native/build.sh).
    // La app que use este módulo DEBE empaquetar los .so sin comprimir y extraídos
    // (packaging.jniLibs.useLegacyPackaging = true): ver app/build.gradle.kts.
    packaging { jniLibs { useLegacyPackaging = true } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Falla temprano y con un mensaje claro si no se ha copiado el binario nativo.
val checkNativeBinary by tasks.registering {
    val dir = layout.projectDirectory.dir("src/main/jniLibs")
    doLast {
        val bins = dir.asFile.walkTopDown().filter { it.name == "librtorrent.so" }.toList()
        if (bins.isEmpty()) {
            throw GradleException(
                "No hay engine/src/main/jniLibs/<abi>/librtorrent.so. " +
                "Compílalo con `docker build -o out .` o `native/build.sh <abi>` y cópialo ahí."
            )
        }
        bins.forEach { logger.lifecycle("rtorrent nativo: ${it.relativeTo(dir.asFile)} (${it.length() / 1024} KiB)") }
    }
}
tasks.named("preBuild") { dependsOn(checkNativeBinary) }
