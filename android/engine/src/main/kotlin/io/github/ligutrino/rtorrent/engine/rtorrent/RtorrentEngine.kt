package io.github.ligutrino.rtorrent.engine.rtorrent

import io.github.ligutrino.rtorrent.engine.api.AddOptions
import io.github.ligutrino.rtorrent.engine.api.EngineException
import io.github.ligutrino.rtorrent.engine.api.EngineListener
import io.github.ligutrino.rtorrent.engine.api.EngineNotRunningException
import io.github.ligutrino.rtorrent.engine.api.EngineState
import io.github.ligutrino.rtorrent.engine.api.SessionStats
import io.github.ligutrino.rtorrent.engine.api.TorrentEngine
import io.github.ligutrino.rtorrent.engine.api.TorrentState
import io.github.ligutrino.rtorrent.engine.api.TorrentStatus
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Implementación de [TorrentEngine] sobre un daemon rtorrent controlado por XML-RPC/SCGI. */
class RtorrentEngine(private val daemon: RtorrentDaemon) : TorrentEngine {

    private val listeners = CopyOnWriteArrayList<EngineListener>()
    private val lock = Any()

    @Volatile override var state: EngineState = EngineState.Stopped
        private set

    val config: RtorrentConfig get() = daemon.config

    private fun setState(s: EngineState) {
        state = s
        listeners.forEach { runCatching { it.onStateChanged(s) } }
    }

    override fun addListener(listener: EngineListener) { listeners.add(listener) }
    override fun removeListener(listener: EngineListener) { listeners.remove(listener) }

    override fun start() = synchronized(lock) {
        if (state is EngineState.Running) return
        setState(EngineState.Starting)
        try {
            val started = daemon.start { code ->
                // Código 0 = apagado ordenado pedido por otro cliente (p. ej. "Apagar rTorrent" en Tablero):
                // no es un error, y el motor queda parado hasta que alguien llame a start().
                if (code == 0) setState(EngineState.Stopped)
                else setState(EngineState.Failed("rtorrent terminó inesperadamente (código $code)", daemon.logTail(8192)))
            }
            setState(EngineState.Running("rtorrent ${started.version}", started.pid))
        } catch (e: Exception) {
            setState(EngineState.Failed(e.message ?: e.toString(), daemon.logTail(8192)))
            throw if (e is EngineException) e else EngineException(e.message ?: e.toString(), e)
        }
    }

    override fun stop() = synchronized(lock) {
        if (state is EngineState.Stopped) return
        setState(EngineState.Stopping)
        try {
            daemon.stop()
        } finally {
            setState(EngineState.Stopped)
        }
    }

    private fun rpc(method: String, vararg params: Any?): Any? {
        if (state !is EngineState.Running) throw EngineNotRunningException("El motor no está corriendo (estado: $state)")
        return daemon.rpc.callList(method, params.toList())
    }

    override fun version(): String = "rtorrent " + rpc("system.client_version", "")

    override fun addUri(uri: String, options: AddOptions): String? {
        val trimmed = uri.trim()
        require(trimmed.startsWith("magnet:") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "Solo se aceptan enlaces magnet: o http(s)://"
        }
        rpc(if (options.start) "load.start" else "load.normal", "", trimmed, *postCommands(options))
        return Magnet.infoHash(trimmed)
    }

    override fun addTorrentFile(data: ByteArray, options: AddOptions): String {
        val hash = Bencode.infoHashV1(data)
        rpc(if (options.start) "load.raw_start" else "load.raw", "", data, *postCommands(options))
        return hash
    }

    private fun postCommands(options: AddOptions): Array<String> =
        options.directory?.let { arrayOf("d.directory.set=" + RtorrentConfig.q(it)) } ?: emptyArray()

    override fun torrents(): List<TorrentStatus> {
        val rows = rpc("d.multicall2", "", "main", *FIELDS.toTypedArray()) as? List<*> ?: return emptyList()
        return rows.mapNotNull { (it as? List<*>)?.let(::toStatus) }
    }

    override fun torrent(hash: String): TorrentStatus? = torrents().firstOrNull { it.hash.equals(hash, ignoreCase = true) }

    override fun pause(hash: String) { rpc("d.stop", h(hash)) }

    override fun resume(hash: String) { rpc("d.start", h(hash)) }

    override fun recheck(hash: String) { rpc("d.check_hash", h(hash)) }

    override fun remove(hash: String, deleteFiles: Boolean) {
        val hh = h(hash)
        var dataPath: File? = null
        if (deleteFiles) {
            val dir = rpc("d.directory", hh)?.toString().orEmpty()
            val name = rpc("d.name", hh)?.toString().orEmpty()
            val multi = (rpc("d.is_multi_file", hh) as? Long ?: 0L) != 0L
            if (dir.isNotEmpty()) dataPath = (if (multi) File(dir) else File(dir, name)).canonicalFile
            // Comprobar antes de quitar el torrent, para no dejarlo a medias.
            dataPath?.let(::checkInsideManagedDirs)
        }
        runCatching { rpc("d.stop", hh) }
        runCatching { rpc("d.close", hh) }
        rpc("d.erase", hh)
        dataPath?.let { t ->
            if (t.exists() && !t.deleteRecursively()) throw EngineException("No se pudieron borrar todos los archivos de $t")
        }
    }

    /** Solo se borra dentro de los directorios que gestiona el motor, nunca fuera. */
    private fun checkInsideManagedDirs(t: File) {
        val roots = listOf(config.downloadDir, config.sessionDir).map { it.canonicalFile }
        val allowed = roots.any { root -> t != root && t.path.startsWith(root.path + File.separator) }
        if (!allowed) throw EngineException("No se borran datos fuera del directorio de descargas: $t")
    }

    override fun setRateLimits(downloadBytesPerSecond: Long, uploadBytesPerSecond: Long) {
        rpc("throttle.global_down.max_rate.set", "", downloadBytesPerSecond.coerceAtLeast(0))
        rpc("throttle.global_up.max_rate.set", "", uploadBytesPerSecond.coerceAtLeast(0))
    }

    override fun sessionStats(): SessionStats {
        val r = rpc(
            "system.multicall",
            listOf(
                call("throttle.global_down.rate"),
                call("throttle.global_up.rate"),
                call("throttle.global_down.total"),
                call("throttle.global_up.total"),
                call("throttle.global_down.max_rate"),
                call("throttle.global_up.max_rate"),
                call("network.listen.port"),
                call("download_list"),
            ),
        ) as List<*>
        // system.multicall devuelve cada resultado envuelto en un array de un elemento.
        fun v(i: Int): Any? = (r[i] as? List<*>)?.firstOrNull()
        fun n(i: Int): Long = (v(i) as? Long) ?: 0L
        return SessionStats(
            downloadRate = n(0), uploadRate = n(1),
            downloadedTotal = n(2), uploadedTotal = n(3),
            downloadLimit = n(4), uploadLimit = n(5),
            listenPort = n(6).toInt(),
            torrentCount = (v(7) as? List<*>)?.size ?: 0,
        )
    }

    private fun call(method: String, vararg params: Any?): Map<String, Any?> =
        mapOf("methodName" to method, "params" to listOf("") + params.toList())

    override fun logTail(maxBytes: Int): String = daemon.logTail(maxBytes)

    /** Acceso directo a XML-RPC, para lo que la API neutral aún no cubre. */
    fun rawCall(method: String, vararg params: Any?): Any? = rpc(method, *params)

    private fun h(hash: String): String {
        require(hash.length == 40 && hash.all { it.isLetterOrDigit() }) { "Infohash inválido: $hash" }
        return hash.uppercase()
    }

    private fun toStatus(row: List<*>): TorrentStatus? {
        fun s(i: Int) = row.getOrNull(i)?.toString().orEmpty()
        fun n(i: Int) = (row.getOrNull(i) as? Long) ?: 0L
        val hash = s(0).ifEmpty { return null }
        val started = n(6) != 0L
        val active = n(7) != 0L
        val complete = n(8) != 0L
        val checking = n(9) != 0L || n(19) != 0L
        val isMeta = n(18) != 0L
        val state = when {
            checking -> TorrentState.CHECKING
            isMeta && started -> TorrentState.METADATA
            !started || !active -> if (complete) TorrentState.FINISHED else TorrentState.PAUSED
            complete -> TorrentState.SEEDING
            else -> TorrentState.DOWNLOADING
        }
        val dir = s(13)
        val name = s(1)
        val multi = n(14) != 0L
        return TorrentStatus(
            hash = hash,
            name = if (isMeta) name.removeSuffix(".meta") else name,
            state = state,
            totalBytes = n(2),
            completedBytes = n(3),
            downloadRate = n(4),
            uploadRate = n(5),
            downloadedTotal = n(17),
            uploadedTotal = n(16),
            peersConnected = n(11).toInt(),
            seedsConnected = n(20).toInt(),
            ratio = n(12) / 1000.0,
            path = if (multi || dir.isEmpty()) dir else "$dir/$name",
            message = s(10),
            addedEpochSeconds = n(15),
        )
    }

    companion object {
        /** El orden importa: [toStatus] lee por índice. */
        private val FIELDS = listOf(
            "d.hash=",              // 0
            "d.name=",              // 1
            "d.size_bytes=",        // 2
            "d.completed_bytes=",   // 3
            "d.down.rate=",         // 4
            "d.up.rate=",           // 5
            "d.state=",             // 6
            "d.is_active=",         // 7
            "d.complete=",          // 8
            "d.is_hash_checking=",  // 9
            "d.message=",           // 10
            "d.peers_connected=",   // 11
            "d.ratio=",             // 12 (x1000)
            "d.directory=",         // 13
            "d.is_multi_file=",     // 14
            "d.load_date=",         // 15 (cuándo se añadió)
            "d.up.total=",          // 16
            "d.down.total=",        // 17
            "d.is_meta=",           // 18
            "d.hashing=",           // 19
            "d.peers_complete=",    // 20
        )
    }
}
