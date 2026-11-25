package cl.tablero.ui

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.github.ligutrino.rtorrent.engine.android.EngineProvider
import io.github.ligutrino.rtorrent.engine.android.EngineService
import io.github.ligutrino.rtorrent.engine.android.EngineServiceHook
import io.github.ligutrino.rtorrent.engine.rtorrent.RtorrentEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Corre tablero.py (Python embebido con Chaquopy) en un hilo propio, atado a la vida del servicio
 * en primer plano de :engine. Habla con rtorrent por el mismo socket Unix que usa :engine.
 * También pone la notificación del servicio (TableroAvisos), en lugar de la de :engine.
 */
object TableroServidor : EngineServiceHook {
    const val PUERTO = 8090
    val url get() = "http://127.0.0.1:$PUERTO/"
    private const val TAG = "Tablero"
    private var modulo: PyObject? = null
    private var hilo: Thread? = null
    private var avisos: TableroAvisos? = null

    override val ownsNotification: Boolean get() = avisos != null

    override fun onServiceStarted(context: Context, engine: RtorrentEngine) {
        iniciar(context, engine.config.socketFile.absolutePath, engine.config.downloadDir.absolutePath,
            engine.config.rcFile.absolutePath)
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

    @Synchronized
    fun iniciar(ctx: Context, socketScgi: String, carpetaDescargas: String, rc: String) {
        if (hilo?.isAlive == true) return
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
        val m = Python.getInstance().getModule("tablero").also { modulo = it }
        val conf = File(ctx.filesDir, "tablero").absolutePath
        val argv = listOf(
            "--android",
            "--scgi", socketScgi,
            "--config", conf,
            "--archivos", carpetaDescargas,
            "--port", PUERTO.toString(),
            // rtorrent corre con -o import=<rc>; así Tablero no tiene que buscarlo para "Cambiar puerto".
            "--rtorrent-rc", rc,
        )
        hilo = thread(name = "tablero", isDaemon = true) {
            try {
                m.callAttr("run", argv.toTypedArray())
            } catch (e: Throwable) {
                Log.e(TAG, "tablero.py terminó con error", e)
            }
        }
    }

    @Synchronized
    fun detener() {
        runCatching { modulo?.callAttr("stop") }.onFailure { Log.w(TAG, "stop", it) }
        hilo?.join(3000)
        hilo = null
    }

    /**
     * Salir (botón de la notificación): cierra la pantalla de Tablero, la quita de las apps recientes
     * y detiene tablero.py. Después el servicio de :engine se detiene solo (alSalir de TableroAvisos).
     */
    fun salir() {
        TableroActivity.cerrar()
        thread(name = "tablero-salir") { detener() }
    }

    /** true cuando el servidor ya contesta (para reintentar la carga de la WebView). */
    fun responde(): Boolean = try {
        (URL(url + "api/access").openConnection() as HttpURLConnection).run {
            connectTimeout = 500; readTimeout = 1500
            val ok = responseCode == 200
            disconnect(); ok
        }
    } catch (e: Exception) { false }
}
