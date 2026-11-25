package cl.tablero.backend

import io.github.ligutrino.rtorrent.engine.rpc.ScgiConnector
import io.github.ligutrino.rtorrent.engine.rpc.XmlRpcFault
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/** Los campos de cada torrent en /api/torrents, en el orden de d.multicall2. */
val TORRENT_FIELDS = listOf(
    "hash" to "d.hash=", "name" to "d.name=", "size" to "d.size_bytes=", "done" to "d.completed_bytes=",
    "down_rate" to "d.down.rate=", "up_rate" to "d.up.rate=", "up_total" to "d.up.total=",
    "down_total" to "d.down.total=", "ratio" to "d.ratio=", "state" to "d.state=", "active" to "d.is_active=",
    "complete" to "d.complete=", "hashing" to "d.hashing=", "hash_checking" to "d.is_hash_checking=",
    "chunks_hashed" to "d.chunks_hashed=", "chunks" to "d.size_chunks=", "message" to "d.message=",
    "seeds" to "d.peers_complete=", "peers" to "d.peers_accounted=", "label" to "d.custom1=",
    "tags" to "d.custom=$TAGS_KEY", "addtime" to "d.custom=addtime", "started" to "d.timestamp.started=",
    "finished" to "d.timestamp.finished=", "created" to "d.creation_date=", "directory" to "d.directory=",
    "priority" to "d.priority=", "private" to "d.is_private=", "files" to "d.size_files=",
    "opts" to "d.custom=$OPTS_KEY", "peers_max" to "d.peers_max=", "peers_min" to "d.peers_min=",
    "uploads_max" to "d.uploads_max=", "pos" to "d.custom=$POS_KEY",
)

fun encodeTags(tags: List<String>) = tags.joinToString(",") { quote(it) }
fun decodeTags(value: String) = value.split(",").filter { it.isNotEmpty() }.map { unquote(it) }
fun cleanTags(tags: List<Any?>): List<String> {
    val seen = LinkedHashSet<String>()
    for (t in tags) { val s = pyStr(t).trim(); if (s.isNotEmpty()) seen.add(s) }
    return seen.toList()
}

private fun isConn(e: Throwable) = e is IOException || e is XmlRpcFault

/** RTorrentBackend de tablero.py: todo lo que la API le pide a rTorrent. */
class Backend(connector: ScgiConnector) {
    val rt = Rt(connector)
    private val trackers = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lock = Any()
    val jobs = Jobs()
    val checks = CheckQueue()
    private var incomingCount = 0L
    private var incomingSeen = 0L
    private var incomingChecked = 0.0
    private var lastPos = 0L
    @Volatile private var migrated = false
    @Volatile private var seq: Pair<Boolean, Long>? = null
    var general: General? = null

    /** Mosaico (hasta 0.8) guardaba etiquetas, opciones y posición con otros nombres: se renombran una vez. */
    private fun migrateOldKeys() {
        val old = OLD_KEYS.keys.toList()
        val fields = listOf("d.hash=", "d.throttle_name=", "d.state=") + old.map { "d.custom=$it" } + old.map { "d.custom=${OLD_KEYS[it]}" }
        val rows = rows(rt.callList("d.multicall2", listOf("", "main") + fields))
        val calls = ArrayList<Call>()
        val throttled = ArrayList<Triple<String, Any?, String>>()
        for (row in rows) {
            val h = row[0] as String; val throttle = row[1] as? String ?: ""; val state = row[2]
            val olds = row.subList(3, 3 + old.size); val news = row.subList(3 + old.size, row.size)
            for ((i, k) in old.withIndex()) {
                val ov = olds[i] as? String ?: ""; val nv = news[i] as? String ?: ""
                if (ov.isNotEmpty()) {
                    if (nv.isEmpty()) calls.add(call("d.custom.set", h, OLD_KEYS[k], ov))
                    calls.add(call("d.custom.set", h, k, ""))
                }
            }
            if (throttle.startsWith(OLD_THROTTLE)) {
                val i = old.indexOf("mosaico_opts")
                throttled.add(Triple(h, state, (olds[i] as? String).takeUnless { it.isNullOrEmpty() } ?: (news[i] as? String ?: "")))
            }
        }
        if (calls.isNotEmpty()) rt.multicall(calls)
        for ((h, started, opts) in throttled) {
            val o = parseOpts(opts)
            val name = if (truthy(o["down_limit"]) || truthy(o["up_limit"])) defineThrottle(h, o) else ""
            if (truthy(started)) rt.call("d.stop", h)
            try { rt.call("d.throttle_name.set", h, name) } finally { if (truthy(started)) rt.call("d.start", h) }
        }
        if (calls.isNotEmpty() || throttled.isNotEmpty())
            log("renamed the old keys in %d torrents".format((calls.map { it.second[0] } + throttled.map { it.first }).toSet().size))
    }

    private fun tagsOf(labelIn: Any?, tagsIn: Any?): List<String> {
        val label = unquote(labelIn as? String ?: "")
        var tags = decodeTags(tagsIn as? String ?: "")
        // La etiqueta se cambió en otro lado (ruTorrent): reemplaza a la primera.
        if (tags.isEmpty() || label != tags[0])
            tags = (if (label.isNotEmpty()) listOf(label) else emptyList()) + tags.drop(1).filter { it != label }
        return tags
    }

    fun list(): Obj {
        if (!migrated) synchronized(lock) { if (!migrated) { migrateOldKeys(); migrated = true } }
        val fields = TORRENT_FIELDS.map { it.second }.toMutableList()
        val seqOk = sequentialSupported()
        if (seqOk) fields.add("d.sequential=")
        val rowsList = rows(rt.callList("d.multicall2", listOf("", "main") + fields))
        val out = ArrayList<Obj>()
        for (row in rowsList) {
            val t = obj()
            TORRENT_FIELDS.forEachIndexed { i, (k, _) -> t[k] = row.getOrNull(i) }
            // null: este rTorrent no baja en orden (solo el de rtorrent-android tiene d.sequential).
            t["sequential"] = if (seqOk) row.lastOrNull() else null
            t["tags"] = tagsOf(t.remove("label"), t["tags"])
            t["opts"] = parseOpts(t["opts"])
            t["status"] = statusOf(t)
            t["ratio"] = pyInt(t["ratio"] ?: 0L) / 1000.0
            val size = pyInt(t["size"] ?: 0L); val done = pyInt(t["done"] ?: 0L); val rate = pyInt(t["down_rate"] ?: 0L)
            t["eta"] = if (rate != 0L) ((size - done).toDouble() / rate).toLong() else -1L
            val addtime = t.remove("addtime")
            val a = (addtime as? String)?.let { if (it.isEmpty()) 0L else it.trim().toLongOrNull() }
            t["added"] = if (a == null) or(t["started"], t["created"]) else or(or(a, t["started"]), t["created"])
            out.add(t)
        }
        markNoSpace(out)
        fillPositions(out)
        fillTrackers(out)
        checks.annotate(out)
        jobs.annotate(out)
        countIncoming(out)
        val g = rt.multicall(listOf(
            call("throttle.global_down.rate"), call("throttle.global_up.rate"),
            call("throttle.global_down.max_rate"), call("throttle.global_up.max_rate"),
            call("system.client_version"), call("network.listen.port"), call(rt.portRangeMethod()),
        )).map { ok(it) }
        val stats = obj()
        listOf("down_rate", "up_rate", "down_limit", "up_limit", "version", "port").forEachIndexed { i, k -> stats[k] = g.getOrNull(i) }
        // rTorrent escucha solo cuando abrió su puerto (network.port_open ya no existe en 0.16).
        stats["port_open"] = if (truthy(g.getOrNull(5))) 1L else 0L
        val (first, last) = portRange(g.getOrNull(6))
        val port = (g.getOrNull(5) as? Long)
        stats["port_next"] = if (first <= (port ?: 0L) && (port ?: 0L) <= last) g[5] else first
        stats["incoming"] = incomingCount
        stats["incoming_seen"] = incomingSeen
        return obj("torrents" to out, "stats" to stats)
    }

    /** Pares que se conectaron a nosotros prueban que el puerto llega desde afuera. Cada 30 s. */
    private fun countIncoming(torrents: List<Obj>) {
        val now = nowSec()
        if (now - incomingChecked < 30) return
        incomingChecked = now
        val active = torrents.filter { truthy(it["active"]) }.map { it["hash"] as String }
        val res = if (active.isNotEmpty()) rt.multicall(active.map { call("p.multicall", it, "", "p.is_incoming=") }) else emptyList()
        var n = 0L
        for (r in res) if (r !is Throwable) for (p in rows(r)) n += pyInt(p.firstOrNull() ?: 0L)
        incomingCount = n
        if (n != 0L) incomingSeen = now.toLong()
    }

    fun portTest(): Obj = testPort(pyInt(rt.call("network.listen.port")))

    /** El puerto nuevo se escribe en la configuración de rTorrent y se aplica al reiniciarlo. */
    fun setPort(portIn: Any?): Obj {
        val port = checkPort(portIn)
        val pid = rt.call("system.pid")
        val home = rt.call("system.env", "", "HOME") as? String
        val rc = rtorrentRc(pid, home)
        val cmd = rt.portRangeMethod() + ".set"
        val res = obj("port" to port, "rc" to rc, "line" to "$cmd = $port-$port")
        if (rc != null && PyPath.canWrite(rc)) {
            res["saved"] = setPortInRc(rc, port, cmd)
            rt.call(cmd, "", "$port-$port")
        }
        return res
    }

    fun hashes(): List<String> = (rt.call("download_list") as? List<*>)?.map { it.toString() } ?: emptyList()

    private fun rc(): String? = Tab.rtorrentRc ?: rtorrentRc(rt.call("system.pid"), rt.call("system.env", "", "HOME") as? String)

    /** (carpeta de sesión, rtorrent.rc, {hash: {".torrent": bytes, ...}}) tras guardar la sesión. */
    fun sessionFiles(): Triple<String, String?, Map<String, Map<String, ByteArray>>> {
        rt.call("session.save")
        val sp = rt.call("session.path") as? String ?: ""
        if (sp.isEmpty()) throw BadRequest("rTorrent no guarda sesión (session.path vacío): no hay torrents que respaldar")
        val out = LinkedHashMap<String, MutableMap<String, ByteArray>>()
        for (h in hashes()) for (ext in listOf(".torrent", ".torrent.libtorrent_resume", ".torrent.rtorrent")) {
            val f = File(PyPath.join(sp, h + ext))
            if (!f.exists()) continue
            if (!f.canRead()) throw BadRequest("No tengo permiso para leer la sesión de rTorrent en $sp")
            out.getOrPut(h) { linkedMapOf() }[ext] = f.readBytes()
        }
        return Triple(sp, rc(), out)
    }

    /** Agrega los torrents de un respaldo que rTorrent no tiene. */
    fun restoreTorrents(entries: Map<String, Map<String, ByteArray>>, old: String, new: String): Obj {
        val have = hashes().toSet()
        val todo = entries.filterKeys { it !in have }
        val res = obj("skipped" to (entries.size - todo.size).toLong(), "written" to 0L, "loaded" to 0L)
        val sp = rt.call("session.path") as? String ?: ""
        for ((h, e) in todo) {
            var meta: MutableMap<String, Any> = linkedMapOf()
            e[".torrent.rtorrent"]?.let { b -> meta = try { Bencode.dict(Bencode.decode(b)) ?: linkedMapOf() } catch (x: Exception) { linkedMapOf() } }
            val dir = meta["directory"]
            if (dir is ByteArray && dir.isNotEmpty())
                meta["directory"] = remapPath(String(dir, Charsets.UTF_8), old, new).toByteArray(Charsets.UTF_8)
            renameOldMeta(meta)
            // Sin atarse a un .torrent del equipo anterior.
            for (k in listOf("tied_to_file", "loaded_file")) if (k in meta) meta[k] = ByteArray(0)
            if (sp.isNotEmpty() && PyPath.canWrite(sp)) {
                File(PyPath.join(sp, "$h.torrent")).writeBytes(e.getValue(".torrent"))
                e[".torrent.libtorrent_resume"]?.let { File(PyPath.join(sp, "$h.torrent.libtorrent_resume")).writeBytes(it) }
                if (meta.isNotEmpty()) File(PyPath.join(sp, "$h.torrent.rtorrent")).writeBytes(Bencode.encode(meta))
                res["written"] = (res["written"] as Long) + 1
            } else {
                loadRestored(h, e.getValue(".torrent"), meta)
                res["loaded"] = (res["loaded"] as Long) + 1
            }
        }
        res["restart"] = (res["written"] as Long) > 0
        return res
    }

    private fun loadRestored(h: String, data: ByteArray, meta: Map<String, Any>) {
        rt.call("load.raw", "", data)
        for (i in 0 until 50) { if (h in hashes()) break; Thread.sleep(100) }
        val calls = ArrayList<Call>()
        val d = Bencode.text(meta["directory"])
        if (d.isNotEmpty()) calls.add(call("d.directory_base.set", h, d))
        for ((k, v) in Bencode.dict(meta["custom"]) ?: emptyMap()) calls.add(call("d.custom.set", h, Bencode.keyText(k), Bencode.text(v)))
        for (i in 1..5) { val v = meta["custom$i"]; if (truthy(v)) calls.add(call("d.custom$i.set", h, Bencode.text(v))) }
        (meta["priority"] as? Long)?.let { calls.add(call("d.priority.set", h, it)) }
        calls.add(call("d.check_hash", h))
        rt.multicall(calls)
        startAfterCheck(h, meta["state"] == 1L)
    }

    fun restoreRc(data: ByteArray): String {
        val rc = rc() ?: PyPath.join((rt.call("system.env", "", "HOME") as? String).takeUnless { it.isNullOrEmpty() } ?: PyPath.home, ".rtorrent.rc")
        try {
            if (PyPath.isfile(rc)) File(rc).copyTo(File("$rc.antes-de-tablero"), overwrite = true)
            File(rc).writeBytes(data)
        } catch (e: IOException) {
            throw BadRequest("No pude escribir $rc: ${e.message}")
        }
        return rc
    }

    /** Cada torrent tiene una posición (d.custom "tablero_pos"), el orden en que se agregó; quedan 1..N. */
    private fun fillPositions(torrents: List<Obj>) {
        for (t in torrents) t["pos"] = (t["pos"] as? String)?.trim()?.toLongOrNull() ?: (t["pos"] as? Long ?: 0L)
        synchronized(lock) {
            val missing = torrents.filter { it["pos"] == 0L }
                .sortedWith(compareBy<Obj>({ pyInt(it["added"] ?: 0L) }, { it["name"] as? String ?: "" }))
            var nxt = ((torrents.map { it["pos"] as Long }) + lastPos).max() + 1
            for (t in missing) t["pos"] = nxt++
            val calls = missing.map { call("d.custom.set", it["hash"], POS_KEY, it["pos"].toString()) }.toMutableList()
            torrents.sortedBy { it["pos"] as Long }.forEachIndexed { i, t ->
                val n = (i + 1).toLong()
                if (t["pos"] != n) { t["pos"] = n; calls.add(call("d.custom.set", t["hash"], POS_KEY, n.toString())) }
            }
            lastPos = torrents.size.toLong()
            if (calls.isNotEmpty()) rt.multicall(calls)
        }
    }

    fun movePos(hashes: List<String>, where: String): Obj {
        @Suppress("UNCHECKED_CAST")
        val torrents = list()["torrents"] as List<Obj>
        val order = torrents.sortedBy { it["pos"] as Long }.map { it["hash"] as String }
        val pos = torrents.associate { it["hash"] as String to it["pos"] }
        val calls = moveOrder(order, hashes, where).mapIndexedNotNull { i, h ->
            val n = (i + 1).toLong(); if (pos[h] != n) call("d.custom.set", h, POS_KEY, n.toString()) else null
        }
        if (calls.isNotEmpty()) synchronized(lock) { rt.multicall(calls) }
        return obj("moved" to calls.size.toLong())
    }

    private fun nextPos(): Long = synchronized(lock) {
        val used = rows(rt.call("d.multicall2", "", "main", "d.custom=$POS_KEY"))
        val nums = used.mapNotNull { (it.firstOrNull() as? String)?.takeIf { s -> s.isNotEmpty() && s.all(Char::isDigit) }?.toLong() }
        lastPos = (nums + (used.size - 1).toLong() + 0L).max() + 1
        lastPos
    }

    private fun fillTrackers(torrents: List<Obj>) {
        val missing = torrents.map { it["hash"] as String }.filter { !trackers.containsKey(it) }
        if (missing.isNotEmpty()) {
            val res = rt.multicall(missing.map { call("t.multicall", it, "", "t.url=") })
            missing.zip(res).forEach { (h, r) ->
                val urls = (if (r is Throwable) emptyList() else rows(r).map { it.firstOrNull() as? String ?: "" })
                    .filter { !it.startsWith("dht://") }
                trackers[h] = if (urls.isNotEmpty()) trackerHost(urls[0]) else ""
            }
        }
        for (t in torrents) t["tracker"] = trackers[t["hash"]] ?: ""
    }

    fun details(h: String): Obj {
        val res = rt.multicall(listOf(
            call("f.multicall", h, "", "f.path=", "f.size_bytes=", "f.completed_chunks=", "f.size_chunks=",
                "f.priority=", "f.range_first=", "f.range_second="),
            call("p.multicall", h, "", "p.address=", "p.port=", "p.client_version=", "p.completed_percent=",
                "p.down_rate=", "p.up_rate=", "p.is_encrypted=", "p.is_incoming="),
            call("t.multicall", h, "", "t.url=", "t.is_enabled=", "t.scrape_complete=", "t.scrape_incomplete=",
                "t.scrape_downloaded=", "t.type="),
            call("d.directory", h), call("d.chunk_size", h), call("d.size_chunks", h), call("d.connection_current", h),
            call("d.bitfield", h), call("d.chunks_seen", h), call("d.name", h), call("d.is_multi_file", h),
            call("d.peers_complete", h), call("d.is_active", h),
        ))
        for (r in res.subList(0, 3)) if (r is Throwable) throw XmlRpcFault(-1, r.message ?: "error")
        val info = res.subList(3, res.size).map { ok(it) }
        val dir = info[0] as? String
        return withAvailability(obj(
            "files" to rows(res[0]).map { f ->
                val sc = pyInt(f[3] ?: 0L)
                obj("path" to f[0], "size" to f[1], "progress" to if (sc != 0L) pyInt(f[2] ?: 0L).toDouble() / sc else 1.0,
                    "priority" to f[4], "range" to listOf(f[5], f[6]))
            },
            "peers" to rows(res[1]).map { p ->
                obj("address" to p[0], "port" to p[1], "client" to p[2], "progress" to pyInt(p[3] ?: 0L) / 100.0,
                    "down_rate" to p[4], "up_rate" to p[5], "encrypted" to truthy(p[6]), "incoming" to truthy(p[7]))
            },
            "trackers" to rows(res[2]).map { t ->
                obj("url" to t[0], "enabled" to truthy(t[1]), "seeds" to t[2], "leechers" to t[3], "downloaded" to t[4])
            },
            "base_path" to if (truthy(dir) && truthy(info[6])) dataLocation(dir!!, info[6] as String, info[7]) else null,
            "chunk_size" to info[1], "chunks" to info[2],
            "connection" to info[3], "bitfield" to (info[4] ?: ""),
            // Detenido o en pausa, d.chunks_seen queda en ceros: no se sabe quién tiene cada pieza.
            "seen" to if (truthy(info[9])) info[5] else "",
            "seen_complete" to (info[8] ?: 0L),
        ))
    }

    fun action(action: String, hashes: List<String>, data: Any?, value: Any?): Any? {
        var calls = ArrayList<Call>()
        when (action) {
            "start" -> {
                for (h in hashes) jobs.resume(h)
                // Uno detenido se detiene y cierra otra vez antes: tras una verificación con piezas malas
                // rTorrent ignora d.start hasta que se hagan las dos cosas.
                val states = rt.multicall(hashes.map { call("d.state", it) })
                hashes.zip(states).forEach { (h, st) ->
                    if (st == 0L) { calls.add(call("d.stop", h)); calls.add(call("d.close", h)) }
                    calls.add(call("d.open", h)); calls.add(call("d.start", h)); calls.add(call("d.resume", h))
                }
            }
            "stop" -> for (h in hashes) { jobs.stop(h); calls.add(call("d.stop", h)); calls.add(call("d.close", h)) }
            "pause" -> hashes.forEach { calls.add(call("d.pause", it)) }
            "recheck" -> {
                checks.asked(hashes)
                // rTorrent deja el resultado de una verificación vieja en d.message: se borra antes.
                for (h in hashes) { calls.add(call("d.message.set", h, "")); calls.add(call("d.check_hash", h)) }
            }
            "priority" -> { val v = pyInt(value); hashes.forEach { calls.add(call("d.priority.set", it, v)) } }
            "sequential" -> hashes.forEach { calls.add(call("d.sequential.set", it, if (truthy(value)) 1L else 0L)) }
            "remove" -> {
                val paths = ArrayList<String>()
                val folders = ArrayList<Pair<String, List<String>>>()
                if (truthy(data)) {
                    val res = rt.multicall(hashes.flatMap { h ->
                        listOf(call("d.directory", h), call("d.name", h), call("d.is_multi_file", h), call("f.multicall", h, "", "f.path="))
                    })
                    for (i in res.indices step 4) {
                        val d = res[i]; val n = res[i + 1]; val m = res[i + 2]; val fl = res[i + 3]
                        if (!(d is String && d.isNotEmpty() && n is String && n.isNotEmpty()) || m is Throwable) continue
                        if (!truthy(m)) paths.add(dataLocation(d, n, m))
                        else if (fl is List<*>) folders.add(d to rows(fl).mapNotNull { it.firstOrNull() as? String })
                    }
                    for (h in hashes) { calls.add(call("d.stop", h)); calls.add(call("d.close", h)) }
                }
                hashes.forEach { calls.add(call("d.erase", it)) }
                val res = rt.multicall(calls)
                paths.forEach { removePath(it) }
                for ((d, files) in folders) removeTorrentFiles(d, files)
                return res
            }
            else -> throw BadRequest("unknown action $action")
        }
        return rt.multicall(calls)
    }

    private fun tagCalls(h: String, tags: List<String>): List<Call> =
        listOf(call("d.custom.set", h, TAGS_KEY, encodeTags(tags)), call("d.custom1.set", h, if (tags.isNotEmpty()) quote(tags[0]) else ""))

    fun setTags(hashes: List<String>, tagsIn: List<Any?>): Any? {
        val tags = cleanTags(tagsIn)
        return rt.multicall(hashes.flatMap { tagCalls(it, tags) })
    }

    fun editTags(hashes: List<String>, add: List<Any?>, remove: List<Any?>): Any? {
        val cur = rt.multicall(hashes.flatMap { listOf(call("d.custom", it, TAGS_KEY), call("d.custom1", it)) })
        val rm = remove.map { pyStr(it) }.toSet()
        val calls = hashes.flatMapIndexed { i, h ->
            val tags = cleanTags(tagsOf(cur[2 * i + 1], cur[2 * i]).filter { it !in rm } + add)
            tagCalls(h, tags)
        }
        return rt.multicall(calls)
    }

    fun renameTag(old: String, new: String): Long {
        @Suppress("UNCHECKED_CAST")
        val data = list()["torrents"] as List<Obj>
        var n = 0L
        for (t in data) {
            @Suppress("UNCHECKED_CAST")
            val tags = t["tags"] as List<String>
            if (old in tags) {
                n++
                setTags(listOf(t["hash"] as String), if (new.isNotEmpty()) tags.map { if (it == old) new else it } else tags.filter { it != old })
            }
        }
        return n
    }

    fun filePriority(h: String, indexes: List<Any?>, prio: Any?): Any? {
        val p = pyInt(prio)
        val calls = indexes.map { call("f.priority.set", "%s:f%d".format(h, pyInt(it)), p) } + call("d.update_priorities", h)
        return rt.multicall(calls)
    }

    // ---- descarga en orden y Reproducir (rtorrent-android: d.sequential, d.sequential_from)

    /** Si este rTorrent tiene d.sequential (se vuelve a preguntar cada minuto). */
    fun sequentialSupported(): Boolean {
        val now = System.nanoTime() / 1_000_000_000L
        val s = seq
        if (s == null || now - s.second > 60) {
            val ok = try {
                (rt.call("system.listMethods") as? List<*>)?.contains("d.sequential.set") == true
            } catch (e: Exception) { if (isConn(e)) false else throw e }
            seq = ok to now
            return ok
        }
        return s.first
    }

    class StreamInfo(val path: String, val size: Long, val offset: Long, val chunk: Long, val chunks: Long, val name: String)

    /** Un archivo para reproducir mientras baja: activa la descarga en orden, y el archivo si estaba en No bajar. */
    fun streamOpen(h: String, idx: Int): StreamInfo {
        if (!sequentialSupported()) throw NotFound("Este rTorrent no puede descargar en orden (falta d.sequential)")
        val files = rows(rt.call("f.multicall", h, "", "f.path=", "f.size_bytes=", "f.offset=", "f.priority="))
        if (idx !in files.indices) throw NotFound("El torrent no tiene el archivo $idx")
        val (path, size, offset, prio) = files[idx]
        val r = rt.multicall(listOf(call("d.directory", h), call("d.chunk_size", h), call("d.size_chunks", h)))
        r.forEach { if (it is Throwable) throw XmlRpcFault(-1, it.message ?: "error") }
        val calls = mutableListOf(call("d.sequential.set", h, 1L))
        if (prio == 0L) { calls.add(call("f.priority.set", "%s:f%d".format(h, idx), 1L)); calls.add(call("d.update_priorities", h)) }
        rt.multicall(calls)
        return StreamInfo(PyPath.join(r[0] as String, path as String), pyInt(size), pyInt(offset), pyInt(r[1]), pyInt(r[2]), PyPath.basename(path))
    }

    fun completedPieces(h: String): ByteArray = fromHex(rt.call("d.bitfield", h) as? String ?: "")

    fun streamFrom(h: String, piece: Long) { rt.call("d.sequential_from.set", h, piece) }

    fun add(magnet: String?, data: ByteArray?, directory: String, tagsIn: List<Any?>, start: Boolean, skipIn: List<Any?>, sequential: Boolean = false): Any? {
        duplicate(magnet, data)?.let { return it }
        val skip = skipIn.map { pyInt(it) }.toSortedSet()
        val cmds = ArrayList<String>()
        if (directory.isNotEmpty()) cmds.add("d.directory.set=\"%s\"".format(directory.replace("\"", "\\\"")))
        val tags = cleanTags(tagsIn)
        if (tags.isNotEmpty()) {
            // Entre comillas: la lista codificada tiene comas, que separan argumentos.
            cmds.add("d.custom.set=%s,\"%s\"".format(TAGS_KEY, encodeTags(tags)))
            cmds.add("d.custom1.set=%s".format(quote(tags[0])))
        }
        cmds.add("d.custom.set=addtime,%d".format(System.currentTimeMillis() / 1000))
        cmds.add("d.custom.set=%s,%d".format(POS_KEY, nextPos()))
        // Descargar en orden desde el principio (solo el rTorrent de rtorrent-android tiene d.sequential).
        if (sequential && sequentialSupported()) cmds.add("d.sequential.set=1")
        if (data != null && skip.isNotEmpty()) {
            // Con archivos fuera: se carga detenido, esos archivos en "No bajar" y después se inicia.
            val h = torrentFiles(data)["hash"] as String
            rt.callList("load.raw", listOf("", data) + cmds)
            var loaded = false
            for (i in 0 until 50) {
                if (h in ((rt.call("download_list", "") as? List<*>) ?: emptyList<Any>())) { loaded = true; break }
                Thread.sleep(100)
            }
            if (!loaded) throw BadRequest("rTorrent no cargó el torrent")
            rt.multicall(skip.map { call("f.priority.set", "%s:f%d".format(h, it), 0L) } + call("d.update_priorities", h) +
                (if (start) listOf(call("d.start", h)) else emptyList()))
            return obj("hash" to h, "skipped" to skip.size.toLong())
        }
        if (data != null) return rt.callList(if (start) "load.raw_start" else "load.raw", listOf("", data) + cmds)
        return rt.callList(if (start) "load.start" else "load.normal", listOf("", magnet) + cmds)
    }

    fun defaultDir(): String = rt.call("directory.default") as? String ?: ""

    /** rTorrent ignora sin avisar un torrent que ya tiene: se dice, con los rastreadores nuevos que traería. */
    fun duplicate(magnet: String?, data: ByteArray?): Obj? {
        val ident = torrentIdentity(data, magnet) ?: return null
        val h = ident.hash
        if (h !in ((rt.call("download_list", "") as? List<*>) ?: emptyList<Any>())) return null
        val (name, have) = rt.multicall(listOf(call("d.name", h), call("t.multicall", h, "", "t.url=")))
        val haveSet = rows(have).map { it.firstOrNull() }.toSet()
        return obj("duplicate" to obj("hash" to h, "name" to name,
            "trackers" to ident.trackers.filter { it !in haveSet && trackerUrlOk(it) }))
    }

    fun addTrackers(h: String, urlsIn: List<Any?>): Obj {
        val urls = urlsIn.map { pyStr(it).trim() }.filter { it.isNotEmpty() }
        urls.firstOrNull { !trackerUrlOk(it) }?.let { throw BadRequest("Dirección de rastreador no válida: $it") }
        val r = rows(rt.call("t.multicall", h, "", "t.url=", "t.group="))
        val have = r.map { it[0] }.toSet()
        val group = (r.map { pyInt(it[1] ?: 0L) } + (-1L)).max() + 1
        val new = urls.distinct().filter { it !in have }
        // Cada uno en un grupo propio después de los que hay, así se anuncia a todos.
        rt.multicall(new.mapIndexed { n, u -> call("d.tracker.insert", h, (group + n).toString(), u) })
        return obj("added" to new.size.toLong())
    }

    /** Dónde están los datos de un torrent (o uno de sus archivos), también si está cerrado. */
    fun dataPath(h: String, index: Any?, sub: Any?): String {
        val (directory, name, multi) = rt.multicall(listOf(call("d.directory", h), call("d.name", h), call("d.is_multi_file", h)))
        if (directory !is String || name !is String) throw XmlRpcFault(-1, (directory as? Throwable ?: name as? Throwable)?.message ?: "error")
        var path = dataLocation(directory, name, multi)
        if (index != null && truthy(multi)) {
            path = PyPath.join(path, rt.call("f.path", "%s:f%d".format(h, pyInt(index))) as String)
        } else if (truthy(sub) && truthy(multi)) {
            val parts = pyStr(sub).split("/").filter { it != "" && it != "." }
            if (".." in parts) throw BadRequest("Carpeta no válida")
            path = PyPath.join(path, *parts.toTypedArray())
        }
        return path
    }

    fun openFolder(h: String, index: Any?, sub: Any?): Obj {
        val path = dataPath(h, index, sub)
        if (Tab.platform == "android") {
            // Aquí no hay explorador de archivos: lo abre la app (window.TableroAndroid).
            val folder = if (PyPath.isdir(path)) path else PyPath.dirname(path)
            return obj("folder" to folder, "path" to path, "android" to true)
        }
        throw BadRequest("Abrir la carpeta solo funciona en la app de Android")
    }

    fun throttleOf(h: String) = "tablero_" + h.take(12).lowercase()

    private fun defineThrottle(h: String, opts: Map<String, Any?>): String {
        val name = throttleOf(h)
        // Los límites con nombre van en KiB/s; 0 sería sin límite.
        rt.multicall(listOf(
            call("throttle.down", "", name, Math.floorDiv(pyInt(opts["down_limit"] ?: 0L), 1024L).toString()),
            call("throttle.up", "", name, Math.floorDiv(pyInt(opts["up_limit"] ?: 0L), 1024L).toString())))
        return name
    }

    fun setOptions(h: String, o: Map<String, Any?>) {
        val opts = cleanOpts(o)
        val calls = mutableListOf(call("d.custom.set", h, OPTS_KEY, Json.dumps(opts)))
        if ("priority" in o) calls.add(call("d.priority.set", h, maxOf(0L, minOf(3L, pyInt(o["priority"])))))
        var defaults: Map<String, Long>? = null
        val gp = generalPeers(general?.get() ?: emptyMap())
        for (k in PEER_KEYS) {
            var want: Long? = if (k in opts) opts[k] as Long else gp[k]
            if (want == null && k in o) {  // vuelve al valor propio de rTorrent
                defaults = defaults ?: peerDefaults(truthy(rt.call("d.complete", h)))
                want = defaults[k]
            }
            if (want != null) calls.add(call("d.$k.set", h, want))
        }
        rt.multicall(calls)
        val limited = truthy(opts["down_limit"]) || truthy(opts["up_limit"])
        val want = if (limited) defineThrottle(h, opts) else ""
        if (rt.call("d.throttle_name", h) != want) {
            // rTorrent solo cambia el límite de un torrent detenido.
            val started = truthy(rt.call("d.state", h))
            if (started) rt.call("d.stop", h)
            try { rt.call("d.throttle_name.set", h, want) } finally { if (started) rt.call("d.start", h) }
        }
    }

    /** Los valores de Conexiones de rTorrent para un torrent (rtorrent.rc). */
    fun peerDefaults(complete: Boolean = false): Map<String, Long> {
        val r = rt.multicall(listOf("throttle.max_peers.normal", "throttle.min_peers.normal", "throttle.max_peers.seed",
            "throttle.min_peers.seed", "throttle.max_uploads").map { call(it, "") }).map { it as? Long ?: -1L }
        return linkedMapOf("peers_max" to if (complete && r[2] >= 0) r[2] else r[0],
            "peers_min" to if (complete && r[3] >= 0) r[3] else r[1], "uploads_max" to maxOf(0L, r[4]))
    }

    /** Da a cada torrent las Conexiones que debe tener (las propias o las generales). */
    fun applyPeers(torrents: List<Obj>): Int {
        val g = general?.get() ?: emptyMap()
        val calls = torrents.flatMap { t -> wantedPeers(t, g).map { (k, v) -> call("d.$k.set", t["hash"], v) } }
        if (calls.isNotEmpty()) rt.multicall(calls)
        return calls.size
    }

    fun applyGlobal(): Obj = general?.apply(rt) ?: obj()

    /** De vuelta a los valores de rTorrent, en los torrents sin valores propios. */
    fun resetPeers(torrents: List<Obj>, keys: List<String>) {
        val d = mapOf(false to peerDefaults(false), true to peerDefaults(true))
        val calls = torrents.flatMap { t ->
            @Suppress("UNCHECKED_CAST") val opts = (t["opts"] as? Map<String, Any?>) ?: emptyMap()
            keys.filter { it !in opts }.map { k -> call("d.$k.set", t["hash"], d.getValue(truthy(t["complete"])).getValue(k)) }
        }
        if (calls.isNotEmpty()) rt.multicall(calls)
    }

    fun instance(): Any? = rt.call("system.pid")

    /** Los límites con nombre viven solo en memoria: se definen de nuevo tras reiniciar rTorrent o Tablero. */
    fun restoreOptions(torrents: List<Obj>) {
        for (t in torrents) {
            @Suppress("UNCHECKED_CAST") val o = t["opts"] as Map<String, Any?>
            if (truthy(o["down_limit"]) || truthy(o["up_limit"])) defineThrottle(t["hash"] as String, o)
        }
    }

    fun markRuleDone(h: String, opts: Map<String, Any?>) {
        rt.call("d.custom.set", h, OPTS_KEY, Json.dumps(LinkedHashMap(opts).apply { put("rule_done", true) }))
    }

    /** Mueve los datos a otra carpeta, o apunta el torrent a donde ya están. En segundo plano. */
    fun relocate(hashes: List<String>, targetIn: Any?, move: Boolean, check: Boolean) {
        val target = checkTarget(targetIn)
        if (!move && !PyPath.isdir(target)) throw BadRequest("La carpeta $target no existe")
        for (h in hashes) jobs.set(h, "waiting")
        thread(isDaemon = true, name = "tablero-reubicar") { relocateAll(hashes.toList(), target, move, check) }
    }

    private fun relocateAll(hashes: List<String>, target: String, move: Boolean, check: Boolean) {
        for (h in hashes) {
            if (!jobs.take(h)) continue  // detenido mientras esperaba su turno
            try { relocateOne(h, target, move, check); jobs.done(h) }
            catch (e: Exception) { jobs.set(h, "error", errorText(e)) }
        }
    }

    private fun errorText(e: Throwable): String = when (e) {
        is XmlRpcFault -> e.faultString
        else -> e.message ?: e.toString()
    }

    private fun relocateOne(h: String, target: String, move: Boolean, check: Boolean) {
        val r = rt.multicall(listOf(call("d.name", h), call("d.directory", h), call("d.is_multi_file", h), call("d.state", h),
            call("f.path", "$h:f0"), call("f.size_bytes", "$h:f0")))
        val name = r[0] as String; val directory = r[1] as String; val multi = truthy(r[2]); var started = truthy(r[3])
        val first = r[4] as? String ?: ""; val firstSize = r[5] as? Long ?: 0L
        val base = dataLocation(directory, name, multi)
        var found: String? = null
        if (!move) {
            // Se busca antes de detener: si los datos no están, el torrent sigue como estaba.
            found = findData(target, name, multi, first, firstSize, base)
                ?: throw BadRequest("No encontré los archivos de $name en $target ni en sus subcarpetas")
        }
        jobs.set(h, if (move) "moving" else "checking")
        rt.multicall(listOf(call("d.stop", h), call("d.close", h)))
        try {
            if (move) {
                if (multi) checkOwnFolder(h, base, name)
                val dest = PyPath.join(target, name)
                if (PyPath.realpath(base) == PyPath.realpath(dest)) throw BadRequest("Los datos ya están en esa carpeta")
                if (PyPath.exists(dest)) throw BadRequest("Ya existe $dest; no se sobrescribe")
                File(target).mkdirs()
                // La carpeta nueva se guarda antes de mover: si rTorrent se cierra entretanto, busca los datos donde quedan.
                rt.call("d.directory.set", h, target)
                rt.call("d.save_full_session", h)
                try {
                    if (PyPath.exists(base)) movePath(base, dest)
                } catch (e: Exception) {
                    try { rt.call("d.directory.set", h, PyPath.dirname(base)); rt.call("d.save_full_session", h) }
                    catch (x: Exception) { if (!isConn(x)) throw x }
                    throw e
                }
            } else if (multi) {
                rt.call("d.directory_base.set", h, found)
            } else {
                rt.call("d.directory.set", h, PyPath.dirname(found!!))
            }
            rt.call("d.save_full_session", h)
            if (check || !move) {
                // rTorrent verifica de a uno y encola el resto: este sigue después de su verificación.
                rt.call("d.check_hash", h)
                startAfterCheck(h, started)
                started = false
            }
        } finally {
            if (started && jobs.mayStart(h)) rt.multicall(listOf(call("d.open", h), call("d.start", h)))
        }
    }

    private fun startAfterCheck(h: String, started: Boolean) {
        if (!started) return
        thread(isDaemon = true, name = "tablero-tras-verificar") {
            try {
                Thread.sleep(2000)
                while (truthy(rt.call("d.hashing", h))) Thread.sleep(2000)
                if (jobs.mayStart(h)) rt.multicall(listOf(call("d.open", h), call("d.start", h)))
            } catch (e: Exception) { /* rTorrent no contesta */ }
        }
    }

    /** Una carpeta propia compartida con otros torrents no se mueve ni se renombra. */
    private fun checkOwnFolder(h: String, folder: String, name: String) {
        if (PyPath.basename(folder.trimEnd('/')) == name || !PyPath.isdir(folder)) return
        val own = rows(rt.call("f.multicall", h, "", "f.path=")).mapNotNull { (it.firstOrNull() as? String)?.takeIf { s -> s.isNotEmpty() }?.substringBefore('/') }.toSet()
        val others = ((File(folder).list() ?: emptyArray()).toSet() - own).sorted()
        if (others.isNotEmpty())
            throw BadRequest("La carpeta %s no es solo de este torrent (también tiene %s%s); no la muevo ni le cambio el nombre"
                .format(folder, others[0], if (others.size > 1) " y otros" else ""))
    }

    /** Renombra la carpeta propia del torrent en el disco y apunta el torrent a ella. */
    fun renameFolder(h: String, newIn: Any?): Obj {
        val new = checkFolderName(newIn)
        val r = rt.multicall(listOf(call("d.directory", h), call("d.name", h), call("d.is_multi_file", h), call("d.state", h)))
        val directory = r[0] as String; val name = r[1] as String; val multi = truthy(r[2]); val started = truthy(r[3])
        if (!multi) throw BadRequest("Este torrent es un solo archivo, sin carpeta propia")
        val old = dataLocation(directory, name, multi)
        val dest = PyPath.join(PyPath.dirname(old), new)
        if (dest == old) throw BadRequest("La carpeta ya tiene ese nombre")
        if (PyPath.lexists(dest)) throw BadRequest("Ya existe $dest; no se sobrescribe")
        checkOwnFolder(h, old, name)
        rt.multicall(listOf(call("d.stop", h), call("d.close", h)))
        try {
            if (PyPath.exists(old)) {
                try { java.nio.file.Files.move(File(old).toPath(), File(dest).toPath()) }
                catch (e: IOException) { throw BadRequest("No pude renombrar $old: ${e.message}") }
            }
            rt.call("d.directory_base.set", h, dest)
            rt.call("d.save_full_session", h)
        } finally {
            if (started) rt.multicall(listOf(call("d.open", h), call("d.start", h)))
        }
        return obj("folder" to dest)
    }

    fun shutdown(): Obj {
        // Un apagado normal: rTorrent guarda su sesión y avisa a los rastreadores.
        val session = try { rt.call("session.path") as? String ?: "" } catch (e: Exception) { "" }
        try { rt.call("system.shutdown.normal") } catch (e: RtConnectionError) { /* ya se está apagando */ }
        if (session.isNotEmpty()) thread(isDaemon = true, name = "tablero-limpiar-sesion") { cleanSession(session) }
        return obj()
    }

    /** Las claves vaciadas de Mosaico quedan en la sesión: se sacan de los archivos cuando rTorrent cerró. */
    private fun cleanSession(session: String) {
        var quit = false
        for (i in 0 until 120) {
            Thread.sleep(500)
            try { rt.call("system.pid") } catch (e: Exception) { quit = true; break }
        }
        if (!quit) return
        var unlocked = false
        for (i in 0 until 20) {
            if (!PyPath.exists(PyPath.join(session, "rtorrent.lock"))) { unlocked = true; break }
            Thread.sleep(500)
        }
        if (!unlocked || !PyPath.canWrite(session)) return
        var cleaned = 0
        for (name in File(session).list() ?: emptyArray()) {
            if (!name.endsWith(".torrent.rtorrent")) continue
            val path = PyPath.join(session, name)
            try {
                val meta = Bencode.dict(Bencode.decode(File(path).readBytes())) ?: continue
                val custom = Bencode.dict(meta["custom"]) ?: continue
                val old = custom.keys.filter { Bencode.keyText(it) in OLD_KEYS && !truthy(custom[it]) }
                if (old.isEmpty()) continue
                old.forEach { custom.remove(it) }
                File("$path.tmp").writeBytes(Bencode.encode(meta))
                File("$path.tmp").renameTo(File(path))
                cleaned++
            } catch (e: Exception) { continue }
        }
        if (cleaned > 0) log("took the old keys out of $cleaned session files")
    }

    fun limits(down: Any?, up: Any?): Any? {
        val calls = ArrayList<Call>()
        if (down != null) calls.add(call("throttle.global_down.max_rate.set", "", pyInt(down)))
        if (up != null) calls.add(call("throttle.global_up.max_rate.set", "", pyInt(up)))
        return rt.multicall(calls)
    }
}

/**
 * Disponibilidad (copias distribuidas), como la muestra BiglyBT: la menor cantidad de copias de una
 * pieza entre los pares conectados, más la fracción de piezas con más copias que esa.
 */
fun withAvailability(d: Obj): Obj {
    val seen = d["seen"]
    val extra = pyInt(or(d.remove("seen_complete"), 0L))
    if (seen is String && seen.isNotEmpty()) {
        // Las semillas conectadas no están en d.chunks_seen, que cuenta solo pares parciales.
        val counts = (0 until seen.length step 2).map { i -> minOf(255L, seen.substring(i, minOf(i + 2, seen.length)).toLong(16) + extra) }
        if (extra != 0L) d["seen"] = counts.joinToString("") { "%02X".format(it) }
        val low = counts.min()
        d["availability"] = low + counts.count { it > low }.toDouble() / counts.size
        d["availability_estimated"] = false
        return d
    }
    d["seen"] = ""
    @Suppress("UNCHECKED_CAST") val peers = d["peers"] as List<Map<String, Any?>>
    val seeds = peers.count { (it["progress"] as Double) >= 1 }
    d["availability"] = seeds + peers.filter { (it["progress"] as Double) < 1 }.sumOf { it["progress"] as Double }
    d["availability_estimated"] = true
    return d
}
