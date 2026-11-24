package io.github.ligutrino.rtorrent.engine.android

import android.content.Context
import android.content.Intent
import android.os.Environment
import io.github.ligutrino.rtorrent.engine.api.TorrentEngine
import io.github.ligutrino.rtorrent.engine.rtorrent.RtorrentConfig
import io.github.ligutrino.rtorrent.engine.rtorrent.RtorrentDaemon
import io.github.ligutrino.rtorrent.engine.rtorrent.RtorrentEngine
import java.io.File

/**
 * Punto de entrada para la UI (cualquier UI): da el motor único del proceso y controla el servicio.
 *
 * ```
 * EngineProvider.startService(context)            // arranca rtorrent en primer plano
 * val engine = EngineProvider.get(context)        // TorrentEngine
 * engine.addUri("magnet:?xt=urn:btih:...")         // desde un hilo de fondo
 * ```
 */
object EngineProvider {
    @Volatile private var engine: RtorrentEngine? = null
    @Volatile private var pendingConfig: RtorrentConfig? = null

    /** Opcional: cambia la configuración. Debe llamarse antes del primer [get]. */
    @JvmStatic
    fun configure(config: RtorrentConfig) {
        check(engine == null) { "EngineProvider.configure() debe llamarse antes de get()" }
        pendingConfig = config
    }

    @JvmStatic
    fun defaultConfig(context: Context): RtorrentConfig {
        val app = context.applicationContext
        // Directorio externo propio de la app: visible por USB/gestores de archivos y escribible con rutas
        // normales (sin SAF). Si no hay almacenamiento externo, se usa el interno.
        val downloads = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "downloads")
        return RtorrentConfig(baseDir = File(app.filesDir, "rtorrent"), downloadDir = downloads)
    }

    @JvmStatic
    fun get(context: Context): TorrentEngine = rtorrent(context)

    /** Igual que [get] pero con el tipo concreto (para [RtorrentEngine.rawCall] o leer la config). */
    @JvmStatic
    fun rtorrent(context: Context): RtorrentEngine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val app = context.applicationContext
            val config = pendingConfig ?: defaultConfig(app)
            val exe = File(app.applicationInfo.nativeLibraryDir, "librtorrent.so")
            return RtorrentEngine(RtorrentDaemon(config, exe, AndroidPlatform)).also { engine = it }
        }
    }

    /** Arranca el servicio en primer plano, que a su vez arranca el motor. Llamar desde la UI (app visible). */
    @JvmStatic
    fun startService(context: Context) {
        context.startForegroundService(Intent(context, EngineService::class.java).setAction(EngineService.ACTION_START))
    }

    /** Para el motor de forma ordenada y luego el servicio. */
    @JvmStatic
    fun stopService(context: Context) {
        context.startService(Intent(context, EngineService::class.java).setAction(EngineService.ACTION_STOP))
    }
}
