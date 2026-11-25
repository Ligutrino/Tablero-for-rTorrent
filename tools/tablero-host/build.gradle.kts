// El backend de Tablero (android/tablero/src/main/kotlin/cl/tablero/backend) en la JVM, para probarlo
// contra un rtorrent de Linux con las mismas pruebas que tablero.py (tools/tablero-smoke.py y sequential-test.py).
// No usa nada de Android: el mismo código va en el APK.
plugins {
    kotlin("jvm") version "2.1.20"
    application
}
sourceSets["main"].kotlin.srcDirs(
    "../../android/engine/src/main/kotlin",
    "../../android/tablero/src/main/kotlin/cl/tablero/backend",
    "src/main/kotlin",
)
sourceSets["main"].kotlin.exclude("**/engine/android/**")
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application {
    mainClass.set("TableroHostKt")
    applicationName = "tablero"
}
