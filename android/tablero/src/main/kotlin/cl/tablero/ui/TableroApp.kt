package cl.tablero.ui

import android.app.Application
import android.os.Environment
import io.github.ligutrino.rtorrent.engine.android.EngineProvider
import java.io.File

class TableroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Con "Acceso a todos los archivos" se descarga en Download/Tablero, visible para el usuario y otras apps.
        // Sin él, en la carpeta propia de la app (Android/data/...). Se decide al iniciar el proceso.
        val base = EngineProvider.defaultConfig(this)
        val config = if (Environment.isExternalStorageManager()) {
            base.copy(downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Tablero"))
        } else base
        EngineProvider.configure(config)
        // El backend de Tablero vive mientras vive el servicio del motor (reglas de siembra con la pantalla cerrada).
        EngineProvider.addServiceHook(TableroServidor)
    }
}
