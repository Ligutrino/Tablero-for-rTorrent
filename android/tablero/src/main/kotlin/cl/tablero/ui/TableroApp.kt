package cl.tablero.ui

import android.app.Application
import io.github.ligutrino.rtorrent.engine.android.EngineProvider

class TableroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // tablero.py vive mientras vive el servicio del motor (reglas de siembra con la pantalla cerrada).
        EngineProvider.addServiceHook(TableroServidor)
    }
}
