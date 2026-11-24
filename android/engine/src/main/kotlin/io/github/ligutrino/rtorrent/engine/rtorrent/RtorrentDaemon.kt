package io.github.ligutrino.rtorrent.engine.rtorrent

import io.github.ligutrino.rtorrent.engine.api.EngineException
import io.github.ligutrino.rtorrent.engine.rpc.ScgiConnector
import io.github.ligutrino.rtorrent.engine.rpc.XmlRpcClient
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Lo que el daemon necesita de la plataforma (Android o, en pruebas, la JVM). */
interface DaemonPlatform {
    fun connector(socket: File): ScgiConnector
    fun kill(pid: Int, signal: Int)
    fun isAlive(pid: Int): Boolean = File("/proc/$pid").exists()
    fun ipv6Available(): Boolean
}

/**
 * Lanza y para el proceso rtorrent. No sabe nada de servicios ni de UI.
 *
 * El proceso hijo pertenece al mismo uid y grupo de procesos que la app: si Android mata la app,
 * también mata a rtorrent; por eso la sesión se guarda a menudo (ver [RtorrentConfig.render]).
 */
class RtorrentDaemon(
    val config: RtorrentConfig,
    private val executable: File,
    private val platform: DaemonPlatform,
) {
    val rpc = XmlRpcClient(platform.connector(config.socketFile))

    @Volatile private var process: Process? = null
    @Volatile var pid: Int = -1
        private set
    @Volatile private var stopping = false

    data class Started(val version: String, val pid: Int, val adopted: Boolean)

    /** Devuelve la versión si rtorrent responde en el socket, si no `null`. */
    fun ping(): String? = try {
        rpc.call("system.client_version")?.toString()
    } catch (e: Exception) {
        null
    }

    /**
     * Arranca rtorrent (o adopta uno que ya responde en el socket) y espera a que conteste.
     * [onUnexpectedExit] se llama desde otro hilo si el proceso termina sin que se haya pedido [stop].
     */
    fun start(timeoutMs: Long = 20_000, onUnexpectedExit: (exitCode: Int) -> Unit): Started {
        stopping = false
        ping()?.let { v ->
            val adoptedPid = (rpc.call("system.pid") as? Long)?.toInt() ?: -1
            pid = adoptedPid
            // Sin handle del proceso: vigilar /proc/<pid> para enterarse si termina.
            if (adoptedPid > 0) Thread({
                while (platform.isAlive(adoptedPid)) {
                    try { Thread.sleep(2000) } catch (e: InterruptedException) { return@Thread }
                }
                if (!stopping && pid == adoptedPid) { pid = -1; onUnexpectedExit(if (ping() == null) 0 else -1) }
            }, "rtorrent-watcher").apply { isDaemon = true; start() }
            return Started(v, adoptedPid, adopted = true)
        }

        if (!executable.exists()) {
            throw EngineException("No existe el binario ${executable.absolutePath}. ¿Se empaquetó librtorrent.so para esta ABI y con useLegacyPackaging = true?")
        }
        listOf(config.baseDir, config.sessionDir, config.logDir, config.downloadDir).forEach {
            if (!it.isDirectory && !it.mkdirs()) throw EngineException("No se pudo crear ${it.absolutePath}")
        }
        val sockPathLen = config.socketFile.absolutePath.toByteArray().size
        if (sockPathLen > 107) throw EngineException("Ruta del socket demasiado larga ($sockPathLen bytes): ${config.socketFile}")

        // Nadie responde: el socket y el lock (si quedaron de un proceso muerto) sobran.
        config.socketFile.delete()
        File(config.sessionDir, "rtorrent.lock").delete()
        rotate(config.logFile)
        rotate(config.stdoutFile)

        if (config.writeCaBundle() == 0) {
            config.logDir.resolve("stdout.log").appendText("AVISO: no se encontraron certificados CA en ${config.caCertDirs}; los trackers HTTPS fallarán\n")
        }
        val previousRc = if (config.rcFile.isFile) config.rcFile.readText(Charsets.UTF_8) else null
        config.rcFile.writeText(
            config.render(config.ipv6 ?: platform.ipv6Available(), RtorrentConfig.externalPortLine(previousRc)),
            Charsets.UTF_8,
        )

        val pb = ProcessBuilder(executable.absolutePath, "-n", "-o", "import=${config.rcFile.absolutePath}")
            .directory(config.baseDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(config.stdoutFile))
        pb.environment().apply {
            put("HOME", config.baseDir.absolutePath)
            put("TERM", "dumb")
            put("LANG", "C.UTF-8")
        }
        val p = try { pb.start() } catch (e: Exception) {
            throw EngineException("No se pudo ejecutar ${executable.absolutePath}: ${e.message}", e)
        }
        process = p

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var version: String? = null
        while (System.nanoTime() < deadline) {
            if (!p.isAlive) {
                process = null
                throw EngineException("rtorrent terminó al arrancar (código ${p.exitValue()}):\n${startupOutput()}")
            }
            version = ping()
            if (version != null) break
            Thread.sleep(150)
        }
        if (version == null) {
            p.destroyForcibly()
            process = null
            throw EngineException("rtorrent no respondió en ${timeoutMs / 1000} s:\n${startupOutput()}")
        }
        pid = (rpc.call("system.pid") as? Long)?.toInt() ?: -1

        Thread({
            val code = try { p.waitFor() } catch (e: InterruptedException) { return@Thread }
            if (process === p) { process = null; pid = -1 }
            if (!stopping) onUnexpectedExit(code)
        }, "rtorrent-watcher").apply { isDaemon = true; start() }

        return Started(version, pid, adopted = false)
    }

    /** Parada ordenada (rtorrent guarda la sesión y avisa a los trackers); si no termina a tiempo, se mata. */
    fun stop(timeoutMs: Long = 15_000) {
        stopping = true
        val p = process
        val knownPid = pid
        rpc.callIgnoringEmpty("system.shutdown.normal")

        if (p != null) {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy()
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly().waitFor(3, TimeUnit.SECONDS)
            }
        } else if (knownPid > 0) {
            // Proceso adoptado (de una instancia anterior de la app): no tenemos el handle.
            val deadline = System.currentTimeMillis() + timeoutMs
            while (platform.isAlive(knownPid) && System.currentTimeMillis() < deadline) Thread.sleep(200)
            if (platform.isAlive(knownPid)) platform.kill(knownPid, 9)
        }
        process = null
        pid = -1
        config.socketFile.delete()
    }

    fun logTail(maxBytes: Int): String {
        val parts = listOf(config.stdoutFile, config.logFile).filter { it.isFile && it.length() > 0 }
        return parts.joinToString("\n") { f -> "== ${f.name} ==\n" + tail(f, maxBytes / parts.size) }
    }

    private fun startupOutput(): String = logTail(4096).ifBlank { "(sin salida)" }

    private fun rotate(f: File) {
        if (f.isFile && f.length() > 2L * 1024 * 1024) f.renameTo(File(f.parentFile, f.name + ".1"))
    }

    private fun tail(f: File, maxBytes: Int): String = RandomAccessFile(f, "r").use { raf ->
        val len = raf.length()
        val start = (len - maxBytes).coerceAtLeast(0)
        raf.seek(start)
        val buf = ByteArray((len - start).toInt())
        raf.readFully(buf)
        String(buf, Charsets.UTF_8)
    }
}
