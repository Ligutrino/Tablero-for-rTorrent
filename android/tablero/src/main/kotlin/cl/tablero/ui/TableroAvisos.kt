package cl.tablero.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import io.github.ligutrino.rtorrent.engine.android.EngineService
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Lo que en escritorio hace el ícono de la bandeja, en la notificación del servicio en
 * primer plano de :engine:
 * - velocidades de bajada y subida, y cuántos torrents tienen error;
 * - el ícono normal, con error o sin conexión (res/drawable de icono-notificacion/);
 * - botones Pausar todo, Reanudar todo y Salir; tocarla abre Tablero;
 * - aviso "Descarga completa" por cada torrent que termina de bajar.
 *
 * TableroServidor la inicia y la detiene junto con el servicio de :engine. Usa el mismo ID que la
 * notificación del servicio, así que la reemplaza (EngineServiceHook.ownsNotification = true).
 * Usa Notification.Builder de la plataforma: el proyecto no depende de AndroidX.
 */
class TableroAvisos(
    private val ctx: Context,
    private val idNotificacion: Int,
    private val alSalir: () -> Unit,
) {
    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    @Volatile private var corriendo = false
    @Volatile private var vueltas = 0
    private var completos: Set<String>? = null      // los completos en la última mirada; null antes de la primera
    private var bajando = emptySet<String>()        // incompletos y sin verificar en la última mirada
    private var torrents = JSONArray()

    init {
        nm.createNotificationChannel(NotificationChannel(CANAL_ESTADO, "Estado de Tablero", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CANAL_DESCARGAS, "Descargas completas", NotificationManager.IMPORTANCE_DEFAULT))
        instancia = this
    }

    fun iniciar() {
        if (corriendo) return
        corriendo = true
        val vuelta = ++vueltas   // si se detiene y se reinicia rápido, el hilo anterior se retira
        thread(name = "tablero-avisos", isDaemon = true) {
            while (corriendo && vuelta == vueltas) {
                mirar()
                // Cada 3 s con la pantalla encendida; con la pantalla apagada solo hacen falta los avisos.
                val encendida = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                Thread.sleep(if (encendida) 3_000 else 30_000)
            }
        }
    }

    fun detener() { corriendo = false; if (instancia === this) instancia = null }

    private fun mirar() {
        val datos = try {
            JSONObject(pedir("api/torrents"))
        } catch (e: java.io.IOException) {
            val sinRt = (e.message ?: "").contains("502")
            return mostrar(if (sinRt) "Sin conexión con rTorrent" else "Sin conexión con Tablero", R.drawable.ic_stat_tablero_sinconexion, encender = sinRt)
        } catch (e: Exception) {
            return mostrar("Sin conexión con Tablero", R.drawable.ic_stat_tablero_sinconexion)
        }
        val ts = datos.getJSONArray("torrents")
        val st = datos.getJSONObject("stats")
        torrents = ts
        var errores = 0
        val hechos = mutableSetOf<String>()
        val incompletos = mutableSetOf<String>()
        for (i in 0 until ts.length()) {
            val t = ts.getJSONObject(i)
            val estado = t.optString("status")
            if (estado == "error") errores++
            if (t.optInt("complete") == 1) hechos += t.getString("hash")
            else if (estado in listOf("downloading", "paused", "stopped")) incompletos += t.getString("hash")
        }
        var texto = "↓ ${ritmo(st.optLong("down_rate"))}   ↑ ${ritmo(st.optLong("up_rate"))}"
        if (errores > 0) texto += " · $errores con error"
        mostrar(texto, if (errores > 0) R.drawable.ic_stat_tablero_error else R.drawable.ic_stat_tablero)
        // No cuando se agrega un torrent ya completo, ni cuando una verificación lo encuentra completo.
        completos?.let { antes ->
            for (i in 0 until ts.length()) {
                val t = ts.getJSONObject(i)
                val h = t.getString("hash")
                if (h in hechos && h !in antes && h in bajando) avisarCompleto(h, t.optString("name"))
            }
        }
        completos = hechos
        bajando = incompletos
    }

    private fun abrirTablero(): PendingIntent = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, TableroActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun accion(que: String, cod: Int): PendingIntent = PendingIntent.getBroadcast(ctx, cod,
        Intent(ctx, TableroAcciones::class.java).setAction(que), PendingIntent.FLAG_IMMUTABLE)

    private fun boton(texto: String, pi: PendingIntent) = Notification.Action.Builder(null, texto, pi).build()

    /** rTorrent apagado (p. ej. desde Tablero): "Encender" lo arranca de nuevo por el servicio. */
    private fun encenderRtorrent(): PendingIntent = PendingIntent.getService(ctx, 4,
        Intent(ctx, EngineService::class.java).setAction(EngineService.ACTION_START),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun mostrar(texto: String, icono: Int, encender: Boolean = false) {
        if (!corriendo) return   // tras detener(): no reponer la notificación que el servicio quita
        val b = Notification.Builder(ctx, CANAL_ESTADO)
            .setSmallIcon(icono)
            .setContentTitle("Tablero para rTorrent")
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(abrirTablero())
        if (encender) b.addAction(boton("Encender", encenderRtorrent()))
        else b.addAction(boton("Pausar todo", accion(PAUSAR, 1))).addAction(boton("Reanudar todo", accion(REANUDAR, 2)))
        b.addAction(boton("Salir", accion(SALIR, 3)))
        nm.notify(idNotificacion, b.build())
    }

    private fun avisarCompleto(hash: String, nombre: String) {
        val n = Notification.Builder(ctx, CANAL_DESCARGAS)
            .setSmallIcon(R.drawable.ic_stat_tablero)
            .setContentTitle("Descarga completa")
            .setContentText(nombre)
            .setAutoCancel(true)
            .setContentIntent(abrirTablero())
            .build()
        nm.notify("done-$hash", 1, n)
    }

    /** Pausar todo: los que bajan o suben. Reanudar todo: los pausados. Como en la bandeja. */
    internal fun accionTodos(que: String) = thread {
        val estados = if (que == PAUSAR) listOf("downloading", "seeding") else listOf("paused")
        val hs = JSONArray()
        for (i in 0 until torrents.length()) {
            val t = torrents.getJSONObject(i)
            if (t.optString("status") in estados) hs.put(t.getString("hash"))
        }
        if (hs.length() == 0) return@thread
        try {
            pedir("api/action", JSONObject().put("action", if (que == PAUSAR) "pause" else "start").put("hashes", hs).toString())
        } catch (e: Exception) { }
        mirar()
    }

    /** Salir: cierra Tablero (pantalla y servidor), deja de mirar y detiene el servicio. */
    internal fun salir() {
        detener()
        TableroServidor.salir()
        alSalir()
    }

    companion object {
        const val CANAL_ESTADO = "tablero-estado"
        const val CANAL_DESCARGAS = "tablero-descargas"
        const val PAUSAR = "cl.tablero.ui.PAUSAR_TODO"
        const val REANUDAR = "cl.tablero.ui.REANUDAR_TODO"
        const val SALIR = "cl.tablero.ui.SALIR"
        @Volatile internal var instancia: TableroAvisos? = null

        /** GET (o POST con cuerpo JSON) a Tablero. Un error HTTP sale como IOException con su código. */
        internal fun pedir(ruta: String, cuerpo: String? = null): String {
            val c = URL(TableroServidor.url + ruta).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 2000; c.readTimeout = 5000
                if (cuerpo != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("X-Tablero", "1")
                    c.outputStream.use { it.write(cuerpo.toByteArray()) }
                }
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally { c.disconnect() }
        }

        /** Bytes/s como en Tablero (unidades binarias). */
        fun ritmo(b: Long): String {
            val u = listOf("B/s", "KiB/s", "MiB/s", "GiB/s")
            var v = b.toDouble(); var i = 0
            while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
            return if (i == 0) "${b} B/s" else String.format("%.1f %s", v, u[i]).replace('.', ',')
        }
    }
}

/** Los botones de la notificación (en el manifiesto, exported="false"). */
class TableroAcciones : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val a = TableroAvisos.instancia ?: return
        when (i.action) {
            TableroAvisos.PAUSAR, TableroAvisos.REANUDAR -> a.accionTodos(i.action!!)
            TableroAvisos.SALIR -> a.salir()
        }
    }
}
