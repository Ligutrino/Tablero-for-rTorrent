package cl.tablero.backend

import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * Velocidades de la última hora, generales y por torrent, tomadas en segundo plano. La misma vuelta
 * vuelve a definir los límites con nombre cuando rTorrent es otro proceso, aplica Conexiones y
 * detiene los torrents que cumplieron su regla de siembra.
 */
class History(private val backend: Backend) {
    companion object {
        const val STEP = 2
        const val KEEP = 3600 / STEP
    }

    private val lock = Any()
    private val total = ArrayDeque<List<Any?>>()
    private val torrents = HashMap<String, ArrayDeque<List<Any?>>>()
    private var restored: Any? = false
    @Volatile private var running = true
    private val worker = thread(isDaemon = true, name = "tablero-historial") { run() }

    fun stop() { running = false; worker.interrupt() }

    private fun run() {
        while (running) {
            try { sample() } catch (e: InterruptedException) { return } catch (e: Exception) {
                log("historial: ${e.message ?: e}")
            }
            try { Thread.sleep(STEP * 1000L) } catch (e: InterruptedException) { return }
        }
    }

    private fun push(q: ArrayDeque<List<Any?>>, v: List<Any?>) {
        q.addLast(v)
        while (q.size > KEEP) q.removeFirst()
    }

    @Suppress("UNCHECKED_CAST")
    private fun sample() {
        val data = backend.list()
        val list = data["torrents"] as List<Obj>
        val now = System.currentTimeMillis() / 1000
        // Los límites con nombre viven en la memoria de rTorrent: se definen otra vez cuando es otro proceso.
        val instance = backend.instance()
        if (restored != instance) {
            backend.restoreOptions(list)
            backend.applyGlobal()  // rTorrent arranca otra vez con sus propios totales
            restored = instance
        }
        val general = backend.general?.get() ?: emptyMap()
        try { backend.applyPeers(list) } catch (e: Exception) { log("conexiones: ${e.message ?: e}") }
        for (t in list) {
            val opts = t["opts"] as Map<String, Any?>
            if (ruleReached(t, opts, now, general)) {
                backend.action("stop", listOf(t["hash"] as String), null, null)
                backend.markRuleDone(t["hash"] as String, opts)
                log("${t["name"]}: detenido por su regla de siembra")
            }
        }
        val st = data["stats"] as Map<String, Any?>
        synchronized(lock) {
            push(total, listOf(now, st["down_rate"], st["up_rate"], st["down_limit"], st["up_limit"]))
            val alive = HashSet<String>()
            for (t in list) {
                val h = t["hash"] as String
                alive.add(h)
                push(torrents.getOrPut(h) { ArrayDeque() }, listOf(now, t["down_rate"], t["up_rate"]))
            }
            torrents.keys.retainAll(alive)
        }
    }

    fun get(h: String?): Obj = synchronized(lock) {
        if (!h.isNullOrEmpty()) obj("step" to STEP.toLong(), "samples" to (torrents[h]?.toList() ?: emptyList()))
        else obj("step" to STEP.toLong(), "samples" to total.toList())
    }
}
