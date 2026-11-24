package io.github.ligutrino.rtorrent.engine.rtorrent

import java.io.File

/**
 * Configuración del daemon rtorrent. Se traduce a un rtorrent.rc que se regenera en cada arranque.
 *
 * Todas las rutas son rutas de archivo reales (rtorrent no entiende URIs SAF/content://).
 */
data class RtorrentConfig(
    /** Directorio privado del motor: sesión, socket, logs, rc. */
    val baseDir: File,
    /** Directorio de descargas predeterminado. */
    val downloadDir: File,
    val portRange: IntRange = 50000..50010,
    val dht: Boolean = true,
    val pex: Boolean = true,
    val encryption: String = "allow_incoming,try_outgoing,enable_retry",
    val maxPeersPerTorrent: Int = 60,
    val maxUploadsPerTorrent: Int = 15,
    /** Memoria máxima para piezas mapeadas (rtorrent acepta sufijos K/M/G). */
    val piecesMemoryMax: String = "256M",
    /** Límites iniciales en bytes/s (0 = sin límite). */
    val downloadRateLimit: Long = 0,
    val uploadRateLimit: Long = 0,
    /** `null` = detectar si el dispositivo puede abrir sockets IPv6. */
    val ipv6: Boolean? = null,
    /** Pausar torrents si queda menos que esto libre en el disco de descargas (MiB; 0 = no vigilar). */
    val minFreeSpaceMiB: Int = 500,
    /** Directorio con los certificados CA del sistema (formato hash de OpenSSL). */
    val caPath: String = "/system/etc/security/cacerts",
    /** Líneas adicionales de rtorrent.rc, se añaden al final. */
    val extraRcLines: List<String> = emptyList(),
) {
    val sessionDir: File get() = File(baseDir, "session")
    val logDir: File get() = File(baseDir, "log")
    val socketFile: File get() = File(baseDir, "rpc.sock")
    val rcFile: File get() = File(baseDir, "rtorrent.rc")
    val logFile: File get() = File(logDir, "rtorrent.log")
    val stdoutFile: File get() = File(logDir, "stdout.log")

    init {
        require(portRange.first in 1..65535 && portRange.last in portRange.first..65535) { "portRange inválido" }
    }

    /**
     * @param externalPortLine línea de puerto que otro cliente escribió en el rc anterior (Tablero la
     *   cambia desde "Estado del puerto → Cambiar puerto"); si no es `null`, tiene prioridad sobre [portRange].
     */
    fun render(ipv6Available: Boolean, externalPortLine: String? = null): String = buildString {
        fun line(s: String) { append(s).append('\n') }
        line("# Generado por rtorrent-android en cada arranque: no editar a mano (usa RtorrentConfig.extraRcLines).")
        line("system.daemon.set = true")
        line("session.path.set = ${q(sessionDir.absolutePath)}")
        line("directory.default.set = ${q(downloadDir.absolutePath)}")
        line("network.scgi.open_local = ${q(socketFile.absolutePath)}")
        line("system.umask.set = 0022")
        line("")
        line("log.open_file = \"main\", ${q(logFile.absolutePath)}")
        line("log.add_output = \"info\", \"main\"")
        line("")
        val ownPort = "${portRange.first}-${portRange.last}"
        line("$PORT_MARKER$ownPort")
        line(externalPortLine ?: "network.port_range.set = $ownPort")
        line("network.port_random.set = no")
        if (!ipv6Available) line("network.block.ipv6.set = true")
        line("network.http.capath.set = ${q(caPath)}")
        line("protocol.encryption.set = $encryption")
        line("protocol.pex.set = ${yesNo(pex)}")
        line("dht.mode.set = ${if (dht) "auto" else "disable"}")
        if (dht) {
            for (node in DHT_BOOTSTRAP) line("dht.add_node = ${q(node)}")
        }
        line("")
        line("throttle.max_peers.normal.set = $maxPeersPerTorrent")
        line("throttle.max_uploads.set = $maxUploadsPerTorrent")
        line("throttle.global_down.max_rate.set = $downloadRateLimit")
        line("throttle.global_up.max_rate.set = $uploadRateLimit")
        line("pieces.memory.max.set = $piecesMemoryMax")
        line("")
        // Reemplazan las tareas predeterminadas del mismo nombre (rtorrent guarda cada 20 min; en
        // Android el proceso puede morir sin aviso, así que guardamos cada 5).
        line("schedule = session_save,120,300,((session.save))")
        if (minFreeSpaceMiB > 0) line("schedule = low_diskspace,30,60,((close_low_diskspace,${minFreeSpaceMiB}M))")
        else line("schedule.remove = low_diskspace")
        if (extraRcLines.isNotEmpty()) {
            line("")
            line("# extraRcLines")
            extraRcLines.forEach { line(it) }
        }
    }

    private fun yesNo(b: Boolean) = if (b) "yes" else "no"

    companion object {
        private const val PORT_MARKER = "# puerto-de-RtorrentConfig: "
        private val PORT_LINE = Regex("""^\s*network\.(port_range|listen\.port\.range)\.set\s*=\s*(\S+)\s*$""")

        /**
         * Si en [previousRc] la línea de puerto ya no coincide con la que escribimos (marcada con
         * [PORT_MARKER]), alguien la editó: se devuelve para conservarla. Si no, `null`.
         */
        fun externalPortLine(previousRc: String?): String? {
            if (previousRc == null) return null
            val lines = previousRc.lines()
            val ours = lines.firstOrNull { it.startsWith(PORT_MARKER) }?.removePrefix(PORT_MARKER)?.trim()
            val portLine = lines.lastOrNull { PORT_LINE.matches(it) } ?: return null
            val value = PORT_LINE.find(portLine)!!.groupValues[2]
            return if (ours != null && value == ours) null else portLine.trim()
        }

        val DHT_BOOTSTRAP = listOf(
            "dht.transmissionbt.com:6881",
            "router.bittorrent.com:6881",
            "router.utorrent.com:6881",
        )

        /** Cadena entre comillas para rtorrent.rc. */
        fun q(s: String): String {
            require('\n' !in s && '\r' !in s) { "Ruta con salto de línea: $s" }
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }
}
