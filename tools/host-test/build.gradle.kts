// Prueba de integración del motor en la JVM contra un rtorrent compilado para Linux (native/build.sh host).
// Compila el mismo código Kotlin del módulo :engine, excepto la parte que depende de Android.
plugins {
    kotlin("jvm") version "2.1.20"
    application
}
sourceSets["main"].kotlin.srcDirs("../../android/engine/src/main/kotlin", "src/main/kotlin")
sourceSets["main"].kotlin.exclude("**/engine/android/**")
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application { mainClass.set("HostTestKt") }
