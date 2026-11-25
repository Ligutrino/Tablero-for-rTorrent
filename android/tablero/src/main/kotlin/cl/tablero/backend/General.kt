package cl.tablero.backend

import java.io.File

/**
 * Límites y conexiones para todos los torrents, en general.json, más Al sembrar para cada torrent
 * sin regla propia. Tablero los aplica él mismo (no toca rtorrent.rc). null deja el valor de
 * rTorrent, guardado en "orig" con el proceso de rTorrent del que se leyó.
 */
class General(val path: String) {
    companion object {
        val KEYS = listOf("max_sockets", "peers_max", "uploads_max", "stop_ratio", "stop_hours")
        val GLOBAL = linkedMapOf("max_sockets" to "network.max_open_sockets")
        /** rTorrent 0.16 rechaza network.max_open_sockets bajo 512 ("minimum is 512"). */
        const val MIN_SOCKETS = 512L
        val DEFAULTS: Map<String, Any?> = mapOf("max_sockets" to MIN_SOCKETS, "peers_max" to 50L, "uploads_max" to 4L,
            "stop_ratio" to 0L, "stop_hours" to 0L)
    }

    private val lock = Any()
    private var data: Obj = read()

    private fun read(): Obj {
        val d = try { Json.parse(File(path).readBytes()) as? Map<*, *> } catch (e: Exception) { null } ?: emptyMap<String, Any?>()
        val out = obj()
        for (k in KEYS + "orig") out[k] = if (d.containsKey(k)) d[k] else DEFAULTS[k]
        return out
    }

    private fun write() {
        File(path).parentFile?.mkdirs()
        val tmp = File("$path.tmp")
        tmp.writeText(Json.dumps(data))
        if (!tmp.renameTo(File(path))) throw java.io.IOException("No pude guardar $path")
    }

    fun reload() = synchronized(lock) { data = read() }

    fun get(): Obj = synchronized(lock) { KEYS.associateWithTo(linkedMapOf()) { data[it] } }

    fun save(o: Map<String, Any?>): Obj {
        val d = obj()
        for (k in listOf("max_sockets") + PEER_KEYS) {
            val v = o[k]
            d[k] = if (v == null || v == "") null else maxOf(0L, pyInt(v))
        }
        d.remove("peers_min")  // sigue a peers_max (la mitad), como los valores de rTorrent
        val ms = d["max_sockets"] as Long?
        if (ms != null && ms < MIN_SOCKETS)
            throw BadRequest("rTorrent pide al menos %d conexiones en total; usa %d o más, o deja el campo vacío".format(MIN_SOCKETS, MIN_SOCKETS))
        val pm = d["peers_max"] as Long?
        if (pm != null && pm < 1) throw BadRequest("Deja al menos 1 par por torrent, o el campo vacío")
        d["stop_ratio"] = maxOf(0.0, pyRound(pyFloat(or(o["stop_ratio"], 0L)), 2))
        d["stop_hours"] = maxOf(0.0, pyRound(pyFloat(or(o["stop_hours"], 0L)), 1))
        synchronized(lock) { data.putAll(d); write() }
        return d
    }

    /** Pone los totales en rTorrent; uno vacío vuelve al valor propio de rTorrent. */
    fun apply(rt: Rt): Obj {
        val pid = rt.call("system.pid")
        val names = GLOBAL.values.toList()
        val now = rt.multicall(names.map { call(it, "") })
        val want = linkedMapOf<String, Any?>()
        val orig: Map<*, *>
        synchronized(lock) {
            var o = data["orig"] as? Map<*, *> ?: emptyMap<String, Any?>()
            if (o["pid"] != pid) {
                val fresh = obj("pid" to pid)
                GLOBAL.keys.zip(now).forEach { (k, v) -> if (v is Long) fresh[k] = v }
                data["orig"] = fresh
                write()
                o = fresh
            }
            // Un valor guardado antes del mínimo de 512 (250 por defecto) sería rechazado: se sube.
            val ms = data["max_sockets"] as? Number
            if (ms != null && ms.toLong() < MIN_SOCKETS) { data["max_sockets"] = MIN_SOCKETS; write() }
            for (k in GLOBAL.keys) want[k] = data[k] ?: o[k]
            orig = o
        }
        val calls = GLOBAL.entries.zip(now).mapNotNull { (e, cur) ->
            val w = want[e.key]
            if (w != null && cur != w) call(e.value + ".set", "", (w as Number).toLong()) else null
        }
        if (calls.isNotEmpty()) rt.multicall(calls)
        return GLOBAL.keys.associateWithTo(linkedMapOf()) { orig[it] }
    }

    fun rtorrentValues(): Obj = synchronized(lock) {
        val o = data["orig"] as? Map<*, *> ?: emptyMap<String, Any?>()
        GLOBAL.keys.associateWithTo(linkedMapOf()) { o[it] }
    }
}
