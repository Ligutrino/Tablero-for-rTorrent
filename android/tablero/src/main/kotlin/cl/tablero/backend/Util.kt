package cl.tablero.backend

import java.io.File
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/*
 * Las funciones sueltas de tablero.py, con los mismos nombres (en camelCase) y los mismos mensajes.
 */

const val TAGS_KEY = "tablero_tags"
const val OPTS_KEY = "tablero_opts"
const val POS_KEY = "tablero_pos"
/** Hasta 0.8 (como Mosaico) los mismos valores tenían estos nombres. */
val OLD_KEYS = linkedMapOf("mosaico_tags" to TAGS_KEY, "mosaico_opts" to OPTS_KEY, "mosaico_pos" to POS_KEY)
const val OLD_THROTTLE = "mosaico_"
val PEER_KEYS = listOf("peers_max", "peers_min", "uploads_max")

fun parseOpts(value: Any?): Obj {
    if (!truthy(value)) return linkedMapOf()
    return try {
        @Suppress("UNCHECKED_CAST")
        (Json.parse(value.toString()) as? Obj) ?: linkedMapOf()
    } catch (e: BadRequest) { linkedMapOf() }
}

/** Opciones de un torrent como las manda la interfaz. Velocidades en bytes/s, 0 = sin límite propio. */
fun cleanOpts(o: Map<String, Any?>): Obj {
    val out = obj()
    for (k in listOf("down_limit", "up_limit")) out[k] = maxOf(0L, pyInt(or(o[k], 0L)))
    out["stop_ratio"] = maxOf(0.0, pyRound(pyFloat(or(o["stop_ratio"], 0L)), 2))
    out["stop_hours"] = maxOf(0.0, pyRound(pyFloat(or(o["stop_hours"], 0L)), 1))
    out["seed_own"] = truthy(o["seed_own"])
    for (k in PEER_KEYS) if (o[k] != null) out[k] = maxOf(0L, pyInt(o[k]))
    if ("peers_min" in out && "peers_max" in out && (out["peers_min"] as Long) > (out["peers_max"] as Long))
        throw BadRequest("Los pares mínimos no pueden superar a los máximos")
    return out
}

/** Las opciones de antes de 0.11 no tenían seed_own: una regla puesta entonces era propia. */
fun seedOwn(opts: Map<String, Any?>): Boolean =
    if ("seed_own" in opts) truthy(opts["seed_own"]) else truthy(opts["stop_ratio"]) || truthy(opts["stop_hours"])

fun seedRule(opts: Map<String, Any?>, general: Map<String, Any?>?): Map<String, Any?> {
    val src = if (seedOwn(opts)) opts else (general ?: emptyMap())
    return mapOf("stop_ratio" to or(src["stop_ratio"], 0L), "stop_hours" to or(src["stop_hours"], 0L))
}

/** Si un torrent que siembra cumplió su regla de detención (la propia o la general). */
fun ruleReached(t: Map<String, Any?>, opts: Map<String, Any?>, now: Long, general: Map<String, Any?>?): Boolean {
    if (!(truthy(t["complete"]) && truthy(t["state"])) || truthy(opts["rule_done"])) return false
    val rule = seedRule(opts, general)
    if (truthy(rule["stop_ratio"]) && pyFloat(t["ratio"]) >= pyFloat(rule["stop_ratio"])) return true
    return truthy(rule["stop_hours"]) && truthy(t["finished"]) &&
        now - pyInt(t["finished"]) >= pyFloat(rule["stop_hours"]) * 3600
}

/** Conexiones de cada torrent según las generales: el mínimo es la mitad del máximo. */
fun generalPeers(general: Map<String, Any?>?): MutableMap<String, Long?> {
    val g = general ?: emptyMap()
    val mx = (g["peers_max"] as? Number)?.toLong()
    return linkedMapOf("peers_max" to mx, "peers_min" to mx?.let { Math.floorDiv(it, 2L) },
        "uploads_max" to (g["uploads_max"] as? Number)?.toLong())
}

/** Los valores de Conexiones que un torrent debería tener y no tiene. */
fun wantedPeers(t: Map<String, Any?>, general: Map<String, Any?>?): Map<String, Long> {
    @Suppress("UNCHECKED_CAST")
    val opts = (t["opts"] as? Map<String, Any?>) ?: emptyMap()
    val out = linkedMapOf<String, Long>()
    val gp = generalPeers(general)
    if ("peers_max" in opts && "peers_min" !in opts && gp["peers_min"] != null)
        gp["peers_min"] = minOf(gp["peers_min"]!!, pyInt(opts["peers_max"]))
    for (k in PEER_KEYS) {
        val want = if (k in opts) pyInt(opts[k]) else gp[k]
        if (want != null && t[k] != want) out[k] = want
    }
    return out
}

fun statusOf(t: Map<String, Any?>): String {
    if (truthy(t["hashing"])) return if (truthy(t.getOrElse("hash_checking") { 1L })) "checking" else "queued"
    val msg = t["message"] as? String ?: ""
    if (msg.isNotEmpty() && !truthy(t["active"]) && !msg.startsWith("Tracker:")) return "error"
    if (!truthy(t["state"])) return if (truthy(t["complete"])) "finished" else "stopped"
    if (!truthy(t["active"])) return "paused"
    return if (truthy(t["complete"])) "seeding" else "downloading"
}

/** El orden de los torrents que esperan su verificación, que rTorrent no informa. */
class CheckQueue {
    val seq = HashMap<String, Long>()
    private var next = 0L

    @Synchronized fun asked(hashes: List<String>) {
        for (h in hashes) if (h !in seq) seq[h] = next++
    }

    fun annotate(torrents: List<Obj>) {
        val waiting: List<Obj>
        synchronized(this) {
            for (t in torrents) if (!truthy(t["hashing"])) seq.remove(t["hash"])
            val w = torrents.filter { it["status"] == "queued" }.sortedBy { (it["pos"] as? Long) ?: 0L }
            for (t in w) { val h = t["hash"] as String; if (h !in seq) seq[h] = next++ }
            waiting = w.sortedBy { seq[it["hash"]] }
        }
        waiting.forEachIndexed { n, t -> t["queue_pos"] = (n + 1).toLong() }
    }
}

fun moveOrder(order: List<String>, chosenList: List<String>, where: String): List<String> {
    val chosen = chosenList.toSet()
    if (where == "top") return order.filter { it in chosen } + order.filter { it !in chosen }
    if (where == "bottom") return order.filter { it !in chosen } + order.filter { it in chosen }
    val out = order.toMutableList()
    when (where) {
        "up" -> for (i in 1 until out.size) if (out[i] in chosen && out[i - 1] !in chosen) { val x = out[i - 1]; out[i - 1] = out[i]; out[i] = x }
        "down" -> for (i in out.size - 2 downTo 0) if (out[i] in chosen && out[i + 1] !in chosen) { val x = out[i + 1]; out[i + 1] = out[i]; out[i] = x }
        else -> throw BadRequest("unknown move $where")
    }
    return out
}

/** urlsplit(url).hostname. */
fun urlHost(url: String): String? {
    val m = Regex("^[A-Za-z][A-Za-z0-9+.-]*://([^/?#]*)").find(url) ?: return null
    var net = m.groupValues[1]
    net = net.substring(net.lastIndexOf('@') + 1)
    val host = if (net.startsWith("[")) net.substring(1, net.indexOf(']').takeIf { it > 0 } ?: net.length)
               else net.substringBefore(':')
    return host.lowercase().ifEmpty { null }
}

fun trackerHost(url: String): String {
    val host = urlHost(url) ?: ""
    val parts = host.split(".")
    return if (parts.size > 2) parts.takeLast(2).joinToString(".") else host
}

/** Los traslados de datos en segundo plano, por hash. */
class Jobs {
    private class Job(val state: String, val error: String, val time: Double, val seq: Long)
    private val items = HashMap<String, Job>()
    private var seq = 0L
    private val stopped = HashSet<String>()

    @Synchronized fun set(h: String, state: String, error: String = "") { seq++; items[h] = Job(state, error, nowSec(), seq) }
    @Synchronized fun done(h: String) { items.remove(h) }
    @Synchronized fun stop(h: String) {
        if (items[h]?.state == "waiting") items.remove(h)
        stopped.add(h)
    }
    @Synchronized fun resume(h: String) { stopped.remove(h) }
    @Synchronized fun take(h: String): Boolean {
        val j = items[h]
        if (j == null || j.state != "waiting") return false
        stopped.remove(h)
        return true
    }
    @Synchronized fun mayStart(h: String) = h !in stopped

    fun annotate(torrents: List<Obj>) {
        val waiting = ArrayList<Pair<Long, Obj>>()
        synchronized(this) {
            val now = nowSec()
            items.entries.removeIf { it.value.state == "error" && now - it.value.time > 60 }
            for (t in torrents) {
                val j = items[t["hash"]] ?: continue
                t["job"] = obj("state" to j.state, "error" to j.error)
                if (j.state == "moving" || j.state == "waiting") t["status"] = j.state
                if (j.state == "waiting") waiting.add(j.seq to t)
            }
        }
        waiting.sortedBy { it.first }.forEachIndexed { n, (_, t) -> t["queue_pos"] = (n + 1).toLong() }
    }
}

/** Bytes libres en el disco de una carpeta, o de la carpeta existente más cercana. */
fun freeSpace(p: String?): Obj? {
    var path = PyPath.realpath(PyPath.expanduser(if (p.isNullOrEmpty()) "~" else p))
    while (!PyPath.isdir(path)) {
        val up = PyPath.dirname(path)
        if (up == path || up.isEmpty()) return null
        path = up
    }
    return try { obj("path" to path, "free" to File(path).usableSpace) } catch (e: SecurityException) { null }
}

/** Un torrent que rTorrent no pudo abrir por falta de espacio queda iniciado e inactivo sin mensaje. */
fun markNoSpace(torrents: List<Obj>) {
    val free = HashMap<String, Obj?>()
    for (t in torrents) {
        if (t["status"] != "paused" || truthy(t["complete"])) continue
        val d = t["directory"] as? String ?: ""
        if (d !in free) free[d] = freeSpace(d)
        val need = pyInt(t["size"]) - pyInt(t["done"])
        val f = free[d] ?: continue
        if ((f["free"] as Long) < need) {
            t["status"] = "error"
            t["message"] = "Sin espacio en el disco"
            t["nospace"] = obj("need" to need, "free" to f["free"], "path" to f["path"])
        }
    }
}

fun listDirs(p: String?): Obj {
    var path = PyPath.realpath(PyPath.expanduser(if (p.isNullOrEmpty()) Tab.userFiles() else p))
    while (!PyPath.isdir(path)) {
        val up = PyPath.dirname(path)
        if (up == path || up.isEmpty()) break
        path = up
    }
    val names = (File(path).listFiles() ?: emptyArray())
        .filter { it.isDirectory && !it.name.startsWith(".") }.map { it.name }
        .sortedWith(compareBy<String> { it.lowercase() })
    val parent = PyPath.dirname(path)
    return obj("path" to path, "parent" to if (parent != path) parent else null, "dirs" to names)
}

/** Un .torrent de este equipo, para archivos que llegan como ruta. */
fun readTorrentFile(p: String): ByteArray {
    val path = PyPath.realpath(PyPath.expanduser(p))
    if (!path.lowercase().endsWith(".torrent") || !PyPath.isfile(path)) throw BadRequest("No encontré el archivo $path")
    if (File(path).length() > 20 * 1024 * 1024) throw BadRequest("El archivo $path es demasiado grande para ser un .torrent")
    return File(path).readBytes()
}

fun trackerUrlOk(url: String?): Boolean = url != null && Regex("^(https?|udp)://[^\\s/]+").containsMatchIn(url)

/** (info hash en hexadecimal mayúscula, nombre, rastreadores) de un .torrent o un magnet; null si no se lee. */
data class Identity(val hash: String, val name: String, val trackers: List<String>)

fun torrentIdentity(data: ByteArray? = null, magnet: String? = null): Identity? = try {
    if (data != null) {
        val meta = Bencode.decode(data) as Map<*, *>
        val span = meta[Bencode.INFO_SPAN] as IntArray
        val urls = ArrayList<ByteArray>()
        (meta["announce"] as? ByteArray)?.let { urls.add(it) } ?: urls.add(ByteArray(0))
        for (tier in (meta["announce-list"] as? List<*> ?: emptyList<Any>())) for (u in tier as List<*>) urls.add(u as ByteArray)
        val info = meta["info"] as Map<*, *>
        val name = Bencode.text(info["name"] ?: ByteArray(0))
        Identity(hexOf(sha1(data, span[0], span[1] - span[0]), upper = true), name,
            urls.filter { it.isNotEmpty() }.map { String(it, Charsets.UTF_8) }.distinct())
    } else if (magnet != null) {
        val q = parseQs(magnet.substringAfter('?', "").substringBefore('#'))
        val xt = q["xt"].orEmpty().first { it.lowercase().startsWith("urn:btih:") }.substring(9)
        val h = if (xt.length == 32) hexOf(base32Decode(xt.uppercase())) else xt
        Identity(h.uppercase(), q["dn"]?.firstOrNull() ?: "", q["tr"].orEmpty().distinct())
    } else null
} catch (e: Exception) { null }

/** Dónde están los datos de un torrent en target o bajo él ("Los archivos ya están"). */
fun findData(target: String, name: String, multi: Boolean, first: String, firstSize: Long, current: String): String? {
    if (!multi) {
        val path = PyPath.join(target, name)
        return if (PyPath.isfile(path)) path else null
    }
    fun holds(folder: String, sized: Boolean): Boolean {
        val f = File(PyPath.join(folder, first))
        return try { f.isFile && (!sized || f.length() == firstSize) } catch (e: SecurityException) { false }
    }
    val named = PyPath.join(target, name)
    val candidates = mutableListOf(named, target, PyPath.join(target, PyPath.basename(current)))
    val key = name.lowercase()
    (File(target).listFiles() ?: emptyArray()).filter { it.isDirectory }.map { PyPath.join(target, it.name) }
        .sortedWith(compareBy<String>({ key !in PyPath.basename(it).lowercase() }, { it }))
        .let { candidates.addAll(it) }
    val unique = LinkedHashSet(candidates)
    for (sized in listOf(true, false)) for (c in unique) if (holds(c, sized)) return c
    return if (PyPath.isdir(named)) named else null
}

/** Dónde están los datos: su carpeta (varios archivos) o su archivo. Desde d.directory, no d.base_path. */
fun dataLocation(directory: String, name: String, multi: Any?): String {
    val d = directory.trimEnd('/').ifEmpty { "/" }
    return if (truthy(multi)) d else PyPath.join(d, name)
}

/** Nombre, info hash y archivos de un .torrent, en el orden de rTorrent (f0, f1...). */
fun torrentFiles(data: ByteArray): Obj {
    val ident = torrentIdentity(data) ?: throw BadRequest("No es un archivo .torrent válido")
    val info = (Bencode.decode(data) as Map<*, *>)["info"] as Map<*, *>
    val files = if ("files" in info) {
        (info["files"] as List<*>).map { f ->
            f as Map<*, *>
            val parts = (f["path.utf-8"] ?: f["path"] ?: emptyList<Any>()) as List<*>
            obj("path" to parts.joinToString("/") { Bencode.text(it) },
                "size" to ((f["length"] as? Long) ?: 0L),
                "pad" to ((f["attr"] as? ByteArray)?.contains('p'.code.toByte()) ?: false))
        }
    } else listOf(obj("path" to ident.name, "size" to ((info["length"] as? Long) ?: 0L), "pad" to false))
    return obj("hash" to ident.hash, "name" to ident.name, "files" to files)
}

/** Pregunta a ifconfig.co si llega a este equipo por el puerto. Solo se envía el número. */
fun testPort(port: Long): Obj {
    val data = try {
        val c = URL("https://ifconfig.co/port/$port").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Tablero/" + Tab.VERSION)
        try {
            if (c.responseCode >= 400) throw java.io.IOException("HTTP Error ${c.responseCode}: ${c.responseMessage}")
            asObj(Json.parse(c.inputStream.readBytes()))
        } finally { c.disconnect() }
    } catch (e: Exception) {
        throw BadRequest("No pude hacer la prueba con ifconfig.co: ${e.message ?: e}")
    }
    return obj("port" to port, "reachable" to truthy(data["reachable"]), "ip" to (data["ip"] ?: ""))
}

fun portRange(r: Any?): Pair<Long, Long> {
    val s = pyStr(r)
    val first = s.substringBefore('-').trim().toLongOrNull() ?: return 0L to 0L
    val lastS = if ('-' in s) s.substringAfter('-').trim() else ""
    if (lastS.isEmpty()) return first to first
    val last = lastS.toLongOrNull() ?: return 0L to 0L
    return first to last
}

fun checkPort(port: Any?): Long {
    val p = try { pyInt(port) } catch (e: BadRequest) { throw BadRequest("Escribe un número de puerto") }
    if (p !in 1024..65535) throw BadRequest("El puerto debe estar entre 1024 y 65535")
    return p
}

private val PORT_LINE = Regex("^(\\s*)(network\\.listen\\.port\\.range\\.set|network\\.port_range\\.set|port_range)(\\s*=\\s*)(\\S+)(.*)$", RegexOption.MULTILINE)

/** El archivo de configuración que leyó rTorrent al arrancar. */
fun rtorrentRc(pid: Any?, home: String?): String? {
    val args = try {
        File("/proc/${pyInt(pid)}/cmdline").readBytes().let { String(it, Charsets.UTF_8) }.split('\u0000').filter { it.isNotEmpty() }
    } catch (e: Exception) { emptyList() }
    val imp = Regex("^\\s*(try_)?import\\s*=\\s*(.+)", RegexOption.DOT_MATCHES_ALL)
    for (a in args) for (part in a.split(",")) {
        val m = imp.find(part) ?: continue
        val path = PyPath.expanduser(m.groupValues[2].trim().trim('"', '\''))
        return if (PyPath.isabs(path)) path else PyPath.join(home.takeUnless { it.isNullOrEmpty() } ?: "/", path)
    }
    if ("-n" in args || home.isNullOrEmpty()) return null
    for (path in listOf(PyPath.join(home, ".rtorrent.rc"), PyPath.join(home, ".config", "rtorrent", "rtorrent.rc")))
        if (PyPath.isfile(path)) return path
    return null
}

/** Cambia la línea del puerto (o agrega una) y guarda una copia del archivo como estaba. */
fun setPortInRc(rc: String, port: Long, cmd: String = "network.port_range.set"): Boolean {
    val text = String(File(rc).readBytes(), Charsets.ISO_8859_1)
    File("$rc.antes-de-tablero").writeBytes(text.toByteArray(Charsets.ISO_8859_1))
    var n = 0
    var out = PORT_LINE.replace(text) { m ->
        n++
        "${m.groupValues[1]}$cmd${m.groupValues[3]}$port-$port${m.groupValues[5]}"
    }
    if (n == 0) out = text + (if (text.endsWith("\n") || text.isEmpty()) "" else "\n") +
        "# Puerto de entrada (lo cambió Tablero)\n$cmd = $port-$port\n"
    File(rc).writeBytes(out.toByteArray(Charsets.ISO_8859_1))
    return true
}

/** La dirección de este equipo en la red local (no se envía ningún paquete). */
fun localIp(): String = try {
    DatagramSocket().use { s -> s.connect(InetAddress.getByName("192.0.2.1"), 9); s.localAddress.hostAddress ?: "" }
        .let { if (it == "0.0.0.0" || it == "::") "" else it }
} catch (e: Exception) { "" }

fun checkFolderName(n: Any?): String {
    val name = (n as? String ?: "").trim()
    if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name)
        throw BadRequest("Escribe un nombre de carpeta válido, sin /")
    if (name.toByteArray(Charsets.UTF_8).size > 255) throw BadRequest("El nombre es demasiado largo")
    return name
}

fun checkTarget(t: Any?): String {
    val target = PyPath.expanduser((t as? String ?: "").trim())
    if (!PyPath.isabs(target)) throw BadRequest("Escribe la ruta completa de la carpeta (por ejemplo %s)".format(
        if (Tab.platform == "android") "/storage/emulated/0/Download/Torrents" else "/home/usuario/Torrents"))
    return PyPath.realpath(target)
}

private fun refuse(path: String) = path == "/" || path == PyPath.expanduser("~") || path.count { it == '/' } < 2

fun removePath(p: String) {
    val path = PyPath.realpath(p)
    if (refuse(path)) { log("refusing to delete $path"); return }
    val f = File(path)
    try {
        if (f.isDirectory && !PyPath.islink(path)) {
            if (!f.deleteRecursively()) log("could not delete $path")
        } else if (f.exists() && !f.delete()) log("could not delete $path")
    } catch (e: SecurityException) { log("could not delete $path: $e") }
}

/** Borra los archivos de un torrent de varios archivos y luego las carpetas que quedan vacías. */
fun removeTorrentFiles(folderIn: String, files: List<String>) {
    val folder = PyPath.realpath(folderIn)
    if (refuse(folder)) { log("refusing to delete in $folder"); return }
    val dirs = HashSet<String>()
    for (rel in files) {
        val parts = rel.split("/").filter { it != "" && it != "." }
        if (parts.isEmpty() || ".." in parts) continue
        val full = PyPath.join(folder, *parts.toTypedArray())
        if (!PyPath.realpath(full).startsWith("$folder/")) continue
        var d = PyPath.dirname(full)
        while (d != folder && d.startsWith("$folder/")) { dirs.add(d); d = PyPath.dirname(d) }
        val f = File(full)
        if ((f.isFile || PyPath.islink(full)) && !f.delete()) log("could not delete $full")
    }
    for (d in dirs.sortedByDescending { it.length } + folder) File(d).delete()  // solo si quedó vacía
}

fun renameOldMeta(meta: MutableMap<String, Any>) {
    val custom = Bencode.dict(meta["custom"])
    if (custom != null) for ((k, new) in OLD_KEYS) {
        val v = custom.remove(k)
        if (truthy(v) && !truthy(custom[new])) custom[new] = v!!
    }
    val t = meta["throttle_name"]
    if (t is ByteArray && String(t, Charsets.ISO_8859_1).startsWith(OLD_THROTTLE))
        meta["throttle_name"] = "tablero_".toByteArray() + t.copyOfRange(OLD_THROTTLE.length, t.size)
}

/** path con su comienzo old cambiado por new (solo carpetas completas). */
fun remapPath(path: String, oldIn: String?, new: String?): String {
    val old = (oldIn ?: "").trimEnd('/')
    if (old.isEmpty() || new.isNullOrEmpty()) return path
    if (path == old || path.startsWith("$old/")) {
        val rest = path.substring(old.length)
        return if (new != "/") new.trimEnd('/') + rest else "/" + rest.trimStart('/')
    }
    return path
}

/** shutil.move: renombra, o copia y borra si es otro disco. */
fun movePath(src: String, dst: String) {
    val s = File(src); val d = File(dst)
    try {
        java.nio.file.Files.move(s.toPath(), d.toPath())
        return
    } catch (e: java.nio.file.FileAlreadyExistsException) {
        throw e
    } catch (e: java.io.IOException) {
        // otro sistema de archivos: copiar y borrar
    }
    if (!s.copyRecursively(d, overwrite = false)) throw java.io.IOException("No pude copiar $src a $dst")
    if (!s.deleteRecursively()) log("could not delete $src after copying it")
}

/** Para Reproducir: el tipo de archivo que espera un reproductor. */
fun guessType(name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return mapOf(
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "mov" to "video/quicktime", "ts" to "video/mp2t", "m2ts" to "video/mp2t",
        "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv",
        "3gp" to "video/3gpp", "ogv" to "video/ogg", "mp3" to "audio/mpeg", "flac" to "audio/flac",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "wav" to "audio/x-wav", "wma" to "audio/x-ms-wma", "html" to "text/html", "htm" to "text/html",
        "css" to "text/css", "js" to "text/javascript", "json" to "application/json", "svg" to "image/svg+xml",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "ico" to "image/vnd.microsoft.icon", "txt" to "text/plain", "zip" to "application/zip",
        "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf",
    )[ext]
}
