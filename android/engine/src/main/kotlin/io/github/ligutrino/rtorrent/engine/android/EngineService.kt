package io.github.ligutrino.rtorrent.engine.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.github.ligutrino.rtorrent.engine.api.EngineListener
import io.github.ligutrino.rtorrent.engine.api.EngineState
import io.github.ligutrino.rtorrent.engine.api.TorrentEngine

/**
 * Servicio en primer plano (tipo specialUse) que mantiene vivo el proceso de la app y, con él, el
 * daemon rtorrent. No tiene lógica de torrents: solo arranca/para el motor y muestra la notificación.
 */
class EngineService : Service() {

    companion object {
        const val ACTION_START = "io.github.ligutrino.rtorrent.engine.action.START"
        const val ACTION_STOP = "io.github.ligutrino.rtorrent.engine.action.STOP"
        const val CHANNEL_ID = "rtorrent_engine"
        const val NOTIFICATION_ID = 0x7274 // "rt"
        private const val TAG = "EngineService"
        private const val REFRESH_MS = 3_000L
    }

    private lateinit var engine: TorrentEngine
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopRequested = false

    private val listener = EngineListener { state ->
        if (state is EngineState.Failed && !stopRequested) {
            Log.w(TAG, "Motor falló: ${state.message}\n${state.logTail}")
            notifyText("Error del motor", state.message)
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val st = engine.state
            if (st is EngineState.Running) {
                runCatching { engine.sessionStats() }.onSuccess { s ->
                    notifyText(
                        "rTorrent activo · ${s.torrentCount} torrents",
                        "↓ ${rate(s.downloadRate)}   ↑ ${rate(s.uploadRate)}",
                    )
                }
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = EngineProvider.get(this)
        engine.addListener(listener)
        worker = HandlerThread("rtorrent-service").apply { start() }
        handler = Handler(worker.looper)
        val nm = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Motor de torrents", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene rTorrent funcionando mientras hay transferencias"
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Si nos arrancaron solo para parar, hay que cumplir igualmente con startForeground.
                goForeground("Deteniendo rTorrent…", "")
                stopRequested = true
                handler.removeCallbacks(refresh)
                handler.post {
                    runCatching { engine.stop() }.onFailure { Log.w(TAG, "stop", it) }
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            else -> { // ACTION_START o reinicio del sistema (intent null con START_STICKY)
                stopRequested = false
                goForeground("Iniciando rTorrent…", "")
                acquireWakeLock()
                handler.post {
                    try {
                        engine.start()
                        handler.removeCallbacks(refresh)
                        handler.post(refresh)
                    } catch (e: Exception) {
                        Log.e(TAG, "No arrancó el motor", e)
                        notifyText("rTorrent no pudo arrancar", e.message ?: e.toString())
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine.removeListener(listener)
        // Si el sistema destruye el servicio sin ACTION_STOP, intentar una parada ordenada.
        if (!stopRequested && engine.state !is EngineState.Stopped) {
            Thread({ runCatching { engine.stop() } }, "rtorrent-stop").start()
        }
        releaseWakeLock()
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground(title: String, text: String) {
        val n = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notifyText(title: String, text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, EngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launch)
            .addAction(Notification.Action.Builder(null, "Detener", stopIntent).build())
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rtorrent:engine")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun rate(bps: Long): String = when {
        bps >= 1024 * 1024 -> "%.1f MiB/s".format(bps / 1048576.0)
        bps >= 1024 -> "%.0f KiB/s".format(bps / 1024.0)
        else -> "$bps B/s"
    }
}
