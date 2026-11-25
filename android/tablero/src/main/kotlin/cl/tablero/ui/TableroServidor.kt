package cl.tablero.ui

import android.content.Context
import android.util.Log
import cl.tablero.backend.Options
import cl.tablero.backend.Tablero
import cl.tablero.backend.WebRoot
import io.github.ligutrino.rtorrent.engine.android.EngineProvider
import io.github.ligutrino.rtorrent.engine.android.EngineService
import io.github.ligutrino.rtorrent.engine.android.EngineServiceHook
import io.github.ligutrino.rtorrent.engine.android.LocalSocketConnector
import io.github.ligutrino.rtorrent.engine.rtorrent.RtorrentEngine
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Corre el backend de Tablero (cl.tablero.backend, la misma API que tablero.py, en Kotlin) en un hilo
 * propio, atado a la vida del servicio en primer plano de :engine. Habla con rtorrent por el mismo
 * socket Unix que usa :engine y sirve la interfaz web/ desde los assets de la app (para el acceso desde
 * la red; en el teléfono la interfaz es Compose, que llama a la misma API sin HTTP).
 * También pone la notificación del servicio (TableroAvisos), en lugar de la de :engine.
 */
object TableroServidor : EngineServiceHook {
    const val PUERTO = 8090
    val url get() = "http://127.0.0.1:$PUERTO/"
    private const val TAG = "Tablero"
    private var tablero: Tablero? = null
    private var hilo: Thread? = null
    private var avisos: TableroAvisos? = null

    override val ownsNotification: Boolean get() = avisos != null

    override fun onServiceStarted(context: Context, engine: RtorrentEngine) {
        iniciar(context, engine.config.socketFile, engine.config.downloadDir.absolutePath, engine.config.rcFile.absolutePath)
        synchronized(this) {
            if (avisos == null) {
                val app = context.applicationContext
                avisos = TableroAvisos(app, EngineService.NOTIFICATION_ID) { EngineProvider.stopService(app) }
                    .also { it.iniciar() }
            }
        }
    }

    override fun onServiceStopping(context: Context) {
        synchronized(this) { avisos?.detener(); avisos = null }
        detener()
    }

    /** web/ va en los assets (src/main/assets/web), sin cambios respecto de Tablero. */
    private class Assets(ctx: Context) : WebRoot {
        private val am = ctx.applicationContext.assets
        override fun open(path: String): ByteArray? = try {
            am.open("web/$path").use { it.readBytes() }
        } catch (e: FileNotFoundException) { null }
    }

    @Synchronized
    fun iniciar(ctx: Context, socketScgi: File, carpetaDescargas: String, rc: String) {
        if (hilo?.isAlive == true) return
        val t = Tablero(Options(
            scgi = LocalSocketConnector(socketScgi, 15_000),
            scgiName = socketScgi.absolutePath,
            web = Assets(ctx),
            configDir = File(ctx.filesDir, "tablero").absolutePath,
            port = PUERTO,
            android = true,
            userFiles = carpetaDescargas,
            // rtorrent corre con -o import=<rc>; así Tablero no tiene que buscarlo para "Cambiar puerto".
            rtorrentRc = rc,
            home = ctx.filesDir.absolutePath,
        ))
        tablero = t
        hilo = thread(name = "tablero", isDaemon = true) {
            try { t.run() } catch (e: Throwable) { Log.e(TAG, "Tablero terminó con error", e) }
        }
    }

    @Synchronized
    fun detener() {
        runCatching { tablero?.stop() }.onFailure { Log.w(TAG, "stop", it) }
        hilo?.join(3000)
        hilo = null
        tablero = null
    }

    /**
     * Salir (botón de la notificación): cierra la pantalla de Tablero, la quita de las apps recientes
     * y detiene el servidor. Después el servicio de :engine se detiene solo (alSalir de TableroAvisos).
     */
    fun salir() {
        TableroActivity.cerrar()
        TableroWebActivity.cerrar()
        thread(name = "tablero-salir") { detener() }
    }

    /** El Tablero que corre ahora, para la interfaz Compose (en el mismo proceso, sin HTTP). */
    @Synchronized fun actual(): Tablero? = tablero

    /** true cuando el servidor ya contesta (para reintentar la carga de la WebView). */
    fun responde(): Boolean = try {
        (URL(url + "api/access").openConnection() as HttpURLConnection).run {
            connectTimeout = 500; readTimeout = 1500
            val ok = responseCode == 200
            disconnect(); ok
        }
    } catch (e: Exception) { false }
}
