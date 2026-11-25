// La interfaz Compose de Tablero (android/tablero/src/main/kotlin/cl/tablero/compose) en el escritorio,
// con Compose Multiplatform de JetBrains (los mismos paquetes androidx.compose que en Android). Sirve
// para compilarla y sacar capturas sin un teléfono ni el SDK de Android:
//   gradle -p tools/tablero-compose run --args="--shots DIR --scgi … --config … --archivos … --web …"
plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    application
}
sourceSets["main"].kotlin.srcDirs(
    "../../android/engine/src/main/kotlin",
    "../../android/tablero/src/main/kotlin/cl/tablero/backend",
    "../../android/tablero/src/main/kotlin/cl/tablero/compose",
    "../tablero-host/src/main/kotlin",
    "src/main/kotlin",
)
sourceSets["main"].kotlin.exclude("**/engine/android/**")
val compose = "1.7.3"
dependencies {
    implementation("org.jetbrains.compose.runtime:runtime-desktop:$compose")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:$compose")
    implementation("org.jetbrains.compose.material3:material3-desktop:1.7.3")
    implementation("org.jetbrains.compose.ui:ui-desktop:$compose")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    implementation("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.8.18")
}
// Sin acceso a Google Maven (androidx.collection, androidx.lifecycle...), -PandroidxShim=JAR los
// reemplaza por un .jar armado con las fuentes de Compose Multiplatform (solo en el contenedor de pruebas).
(findProperty("androidxShim") as String?)?.let { shim ->
    configurations.all {
        exclude(group = "androidx.collection"); exclude(group = "androidx.annotation")
        exclude(group = "androidx.lifecycle"); exclude(group = "androidx.arch.core")
    }
    dependencies { implementation(files(shim)) }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
application { mainClass.set("TableroComposeKt") }
