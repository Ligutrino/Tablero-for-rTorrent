package io.github.ligutrino.rtorrent.engine.api

/**
 * API neutral del motor de torrents. No depende de ninguna UI ni de rtorrent:
 * una interfaz nueva o un adaptador para LibreTorrent (TorrentSession/TorrentDownload)
 * solo deberían hablar con esto.
 *
 * Todos los métodos son **bloqueantes** (hacen E/S por socket o lanzan procesos):
 * llámalos desde un hilo de fondo (Dispatchers.IO, un Executor, Schedulers.io() de RxJava...).
 * Son seguros para usar desde varios hilos a la vez.
 *
 * Errores: lanzan [EngineException] (o una subclase).
 */
interface TorrentEngine {

    /** Estado actual del motor (no bloquea). */
    val state: EngineState

    /** Los listeners se invocan desde hilos del motor, no desde el hilo principal. */
    fun addListener(listener: EngineListener)
    fun removeListener(listener: EngineListener)

    /** Arranca el motor y espera a que responda. Si ya está corriendo, no hace nada. */
    fun start()

    /** Para el motor de forma ordenada (guarda la sesión). Si ya está parado, no hace nada. */
    fun stop()

    /** Versión del motor, p. ej. "rtorrent 0.16.17". */
    fun version(): String

    /**
     * Añade un enlace magnet o una URL http(s) a un .torrent.
     * @return el infohash en hexadecimal (mayúsculas) si se puede deducir del enlace, si no `null`.
     */
    fun addUri(uri: String, options: AddOptions = AddOptions()): String?

    /**
     * Añade un .torrent a partir de su contenido.
     * @return el infohash v1 en hexadecimal (mayúsculas).
     */
    fun addTorrentFile(data: ByteArray, options: AddOptions = AddOptions()): String

    fun torrents(): List<TorrentStatus>

    fun torrent(hash: String): TorrentStatus?

    fun pause(hash: String)

    fun resume(hash: String)

    /** Vuelve a verificar los datos en disco. */
    fun recheck(hash: String)

    /** Quita el torrent; con [deleteFiles] también borra lo descargado. */
    fun remove(hash: String, deleteFiles: Boolean)

    /** Límites globales en bytes/s; 0 = sin límite. */
    fun setRateLimits(downloadBytesPerSecond: Long, uploadBytesPerSecond: Long)

    fun sessionStats(): SessionStats

    /** Últimas líneas del log del motor, para diagnóstico. */
    fun logTail(maxBytes: Int = 16 * 1024): String
}

data class AddOptions(
    /** Directorio de descarga; `null` = el predeterminado del motor. Debe ser una ruta de archivo, no un URI SAF. */
    val directory: String? = null,
    /** `false` = añadir en pausa. */
    val start: Boolean = true,
)

enum class TorrentState {
    /** Esperando los metadatos de un magnet. */
    METADATA,
    CHECKING,
    DOWNLOADING,
    SEEDING,
    PAUSED,
    /** Completo y parado. */
    FINISHED,
}

data class TorrentStatus(
    /** Infohash en hexadecimal mayúsculas: identifica al torrent en toda la API. */
    val hash: String,
    val name: String,
    val state: TorrentState,
    val totalBytes: Long,
    val completedBytes: Long,
    val downloadRate: Long,
    val uploadRate: Long,
    val downloadedTotal: Long,
    val uploadedTotal: Long,
    val peersConnected: Int,
    val seedsConnected: Int,
    /** Ratio subida/bajada (1.0 = 100 %). */
    val ratio: Double,
    /** Ruta de los datos en disco (archivo, o directorio si es multiarchivo). */
    val path: String,
    /** Último mensaje del motor/tracker; vacío si no hay. */
    val message: String,
    val addedEpochSeconds: Long,
) {
    val progress: Float get() = if (totalBytes <= 0) 0f else (completedBytes.toDouble() / totalBytes).toFloat()
}

data class SessionStats(
    val downloadRate: Long,
    val uploadRate: Long,
    val downloadedTotal: Long,
    val uploadedTotal: Long,
    val downloadLimit: Long,
    val uploadLimit: Long,
    val listenPort: Int,
    val torrentCount: Int,
)

sealed class EngineState {
    object Stopped : EngineState() { override fun toString() = "Stopped" }
    object Starting : EngineState() { override fun toString() = "Starting" }
    data class Running(val version: String, val pid: Int) : EngineState()
    object Stopping : EngineState() { override fun toString() = "Stopping" }
    /** El motor terminó de forma inesperada o no pudo arrancar. */
    data class Failed(val message: String, val logTail: String = "") : EngineState()

    val isRunning: Boolean get() = this is Running
}

fun interface EngineListener {
    fun onStateChanged(state: EngineState)
}

open class EngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** El motor no está corriendo o no responde. */
class EngineNotRunningException(message: String, cause: Throwable? = null) : EngineException(message, cause)
