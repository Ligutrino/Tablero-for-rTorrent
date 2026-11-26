package cl.tablero.backend

import io.github.ligutrino.rtorrent.engine.rpc.ScgiConnector
import io.github.ligutrino.rtorrent.engine.rpc.XmlRpcFault
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Las variables globales de tablero.py (PLATFORM, USER_FILES, RTORRENT_RC...). */
object Tab {
    const val VERSION = "0.11-android.28-kotlin"
    @Volatile var platform = "desktop"
    @Volatile var userFilesDir: String? = null
    @Volatile var rtorrentRc: String? = null
    const val ANDROID_STORAGE = "/storage/emulated/0"

    /** Donde están los archivos del usuario: ~ en un PC; en Android el almacenamiento compartido. */
    fun userFiles(): String {
        userFilesDir?.let { return it }
        val s = File(ANDROID_STORAGE)
        if (platform == "android" && s.canRead() && s.canWrite() && s.canExecute()) return ANDROID_STORAGE
        return PyPath.expanduser("~")
    }

    fun hostname(): String = try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "localhost" }
}

fun reason(code: Int) = when (code) {
    200 -> "OK"; 206 -> "Partial Content"; 301 -> "Moved Permanently"; 400 -> "Bad Request"; 401 -> "Unauthorized"
    403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Request Entity Too Large"; 416 -> "Requested Range Not Satisfiable"
    500 -> "Internal Server Error"; 501 -> "Not Implemented"; 502 -> "Bad Gateway"; else -> "Unknown"
}

/** De dónde salen los archivos de la interfaz (web/): una carpeta, o los assets de la app. */
/** Un error de la API con su código HTTP: 400 pedido malo, 404, 502 rTorrent caído o con error. */
class ApiError(val code: Int, override val message: String) : Exception(message)

fun interface WebRoot {
    /** El archivo en [path] (relativo, sin / al comienzo), o null si no existe. */
    fun open(path: String): ByteArray?
}

class DirWebRoot(private val dir: File) : WebRoot {
    override fun open(path: String): ByteArray? {
        val f = File(dir, path)
        val root = dir.canonicalPath
        if (!f.canonicalPath.startsWith("$root/") || !f.isFile) return null
        return f.readBytes()
    }
}

class Options(
    val scgi: ScgiConnector,
    val scgiName: String,
    val web: WebRoot,
    val configDir: String,
    val port: Int = 8090,
    val bind: String? = null,
    val android: Boolean = true,
    val userFiles: String? = null,
    val rtorrentRc: String? = null,
    val home: String? = null,
)

/**
 * Tablero entero: el backend, el historial, las etiquetas, los respaldos y el servidor HTTP
 * con la misma API que tablero.py. [run] bloquea hasta [stop].
 */
class Tablero(private val o: Options) {
    val backend = Backend(o.scgi)
    private val history: History
    private val tagstore: TagStore
    private val access: Access
    private val backups: Backups
    @Volatile private var server: ServerSocket? = null
    @Volatile private var restart = false
    @Volatile private var running = true
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "tablero-http").apply { isDaemon = true } }

    init {
        o.home?.let { PyPath.home = it }
        o.rtorrentRc?.let { Tab.rtorrentRc = PyPath.abspath(PyPath.expanduser(it)) }
        o.userFiles?.let { Tab.userFilesDir = PyPath.abspath(PyPath.expanduser(it)) }
        if (o.android) Tab.platform = "android"
        File(o.configDir).mkdirs()
        backend.general = General(PyPath.join(o.configDir, "general.json"))
        history = History(backend)
        tagstore = TagStore(PyPath.join(o.configDir, "tags.json"))
        access = Access(PyPath.join(o.configDir, "access.json"), o.bind, o.port)
        backups = Backups(o.configDir, backend, tagstore, access)
        thread(isDaemon = true, name = "tablero-respaldos") { backups.runSchedule { running } }
    }

    fun run() {
        while (running) {
            restart = false
            val bind = access.wantedBind()
            access.listening = bind
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(InetAddress.getByName(bind), o.port), 64)
            server = ss
            log("Tablero %s en http://%s:%d  (rTorrent %s%s)".format(Tab.VERSION, bind, o.port, o.scgiName,
                if (Tab.platform == "android") ", Android" else ""))
            try {
                while (true) {
                    val s = try { ss.accept() } catch (e: SocketException) { break }
                    pool.execute { serve(s) }
                }
            } finally { runCatching { ss.close() } }
            if (!restart) break
        }
        history.stop()
    }

    /** Detiene [run] desde otro hilo (la app, cuando su servicio termina). */
    fun stop() {
        running = false
        restart = false
        runCatching { server?.close() }
        history.stop()
    }

    /** Escuchar en otra dirección (Acceso desde la red): el servidor se abre otra vez. */
    private fun restartSelf() {
        restart = true
        runCatching { server?.close() }
    }

    // ---------------------------------------------------------------- en el mismo proceso

    /**
     * La API sin HTTP, para la interfaz Compose de la app: las mismas rutas y respuestas que
     * GET/POST /api/..., siempre como pedido local. Lanza [ApiError] donde HTTP respondería un error.
     */
    fun get(path: String, query: Map<String, String> = emptyMap()): Any? {
        val fn = getRoute(path, { query[it] }, true) ?: throw ApiError(404, "not found")
        return if (path in unguarded) fn() else guarded(fn)
    }

    fun post(path: String, body: Any? = obj()): Any? {
        val fn = postRoute(path, body ?: obj(), true) ?: throw ApiError(404, "not found")
        return guarded { val r = fn(); if (r is Map<*, *>) r else obj("ok" to true) }
    }

    /** POST /api/backup-import: el .zip llega como flujo. */
    fun importBackup(input: InputStream, size: Long, folder: String, name: String): Any? =
        guarded { backups.importFile(input, size, folder, name) }

    /** GET /api/backup-file: la ruta del respaldo, para copiarlo donde elija el usuario. */
    fun backupFilePath(p: String): String = try { backups.backupPath(p) } catch (e: Exception) { throw ApiError(404, e.message ?: e.toString()) }

    /** GET /logos/<nombre>: los bytes del logo de una etiqueta, o null. */
    fun logoBytes(url: String): ByteArray? {
        val m = logoRe.matchEntire(url) ?: return null
        return try { File(PyPath.join(tagstore.logoDir(), m.groupValues[1])).readBytes() } catch (e: IOException) { null }
    }

    /** La dirección de reproducción de un archivo, servida por este mismo servidor. */
    fun streamUrl(hash: String, index: Int, name: String): String = "http://127.0.0.1:${o.port}/stream/$hash/$index/${quote(name)}"

    // ---------------------------------------------------------------- HTTP

    private class Req(val method: String, val target: String, val headers: Map<String, String>, val input: InputStream,
                      val client: InetAddress, val serverPort: Int) {
        val path: String = target.substringBefore('#').substringBefore('?')
        val query: String = target.substringBefore('#').substringAfter('?', "")
        fun header(k: String) = headers[k.lowercase()]
        val local: Boolean get() = client.hostAddress == "127.0.0.1" || (client is Inet6Address && client.isLoopbackAddress) ||
            client.hostAddress == "::ffff:127.0.0.1"
    }

    private class Res(val out: OutputStream, val req: Req?) {
        var status = 0
        fun start(code: Int, headers: List<Pair<String, String>>) {
            status = code
            val sb = StringBuilder("HTTP/1.0 $code ${reason(code)}\r\n")
            sb.append("Server: Tablero/${Tab.VERSION}\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            // Los logos se llaman según su contenido: nunca cambian.
            val cache = if (req?.path?.startsWith("/logos/") == true && code == 200) "max-age=31536000, immutable" else "no-store"
            sb.append("Cache-Control: ").append(cache).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
        }
        fun send(code: Int, type: String, body: ByteArray, extra: List<Pair<String, String>> = emptyList(), head: Boolean = false) {
            start(code, listOf("Content-Type" to type, "Content-Length" to body.size.toString()) + extra)
            if (!head) out.write(body)
        }
        fun json(obj: Any?, code: Int = 200) = send(code, "application/json; charset=utf-8", Json.dumps(obj).toByteArray(Charsets.UTF_8))
    }

    private fun serve(s: Socket) {
        s.use { sock ->
            sock.soTimeout = 60_000
            try {
                val input = BufferedInputStream(sock.getInputStream())
                val out = sock.getOutputStream().buffered(65536)
                val req = readRequest(input, sock) ?: return
                val res = Res(out, req)
                try {
                    handle(req, res)
                } catch (e: IOException) {
                    // el cliente cerró (un reproductor que salta abre otro pedido)
                } catch (e: Exception) {
                    log("error en ${req.method} ${req.path}: $e")
                    if (res.status == 0) runCatching { res.json(obj("error" to (e.message ?: e.toString())), 500) }
                }
                runCatching { out.flush() }
            } catch (e: IOException) { /* conexión cortada */ }
        }
    }

    private fun readLine(input: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            if (b.size() > 65536) throw IOException("línea demasiado larga")
            b.write(c)
        }
    }

    private fun readRequest(input: InputStream, sock: Socket): Req? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = String(h.substring(i + 1).trim().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
        }
        return Req(parts[0], parts[1], headers, input, sock.inetAddress, sock.localPort)
    }

    private fun handle(req: Req, res: Res) {
        when (req.method) {
            "GET" -> { if (authorized(req, res)) doGet(req, res) }
            "HEAD" -> { if (authorized(req, res)) doHead(req, res) }
            "POST" -> { if (authorized(req, res)) doPost(req, res) }
            else -> res.send(501, "text/html;charset=utf-8", errorPage(501, "Unsupported method ('${req.method}')"))
        }
    }

    /** Desde este equipo, la dirección debe nombrar a este equipo (contra DNS rebinding). */
    private fun hostOk(req: Req): Boolean {
        if (!req.local) return true
        val host = (req.header("Host") ?: "").trim().lowercase()
        val name = if (host.startsWith("[")) host.substring(1, host.indexOf(']').takeIf { it > 0 } ?: host.length)
                   else if (':' in host) host.substringBeforeLast(':') else host
        return name in listOf("", "localhost", "::1", Tab.hostname().lowercase(), localIp()) || name.endsWith(".localhost") ||
            Regex("127(\\.\\d{1,3}){3}").matches(name)
    }

    private fun authorized(req: Req, res: Res): Boolean {
        if (!hostOk(req)) {
            res.json(obj("error" to "Dirección no permitida: abre Tablero en http://localhost:${o.port}"), 403)
            return false
        }
        if (access.allowed(req.local, req.header("Authorization"))) return true
        res.send(401, "text/plain; charset=utf-8", "Tablero pide la contraseña de Acceso desde la red.".toByteArray(),
            listOf("WWW-Authenticate" to "Basic realm=\"Tablero\", charset=\"UTF-8\""))
        return false
    }

    /** Los errores como en _guard de tablero.py: rTorrent caído o con error 502, pedido malo 400. */
    private fun guard(res: Res, fn: () -> Any?) {
        val r = try { guarded(fn) } catch (e: ApiError) { return res.json(obj("error" to e.message), e.code) }
        res.json(r)
    }

    /** El mismo mapeo de errores, como [ApiError] con su código HTTP. */
    private fun guarded(fn: () -> Any?): Any? = try { fn() } catch (e: Exception) {
        throw when (e) {
            is XmlRpcFault -> ApiError(502, "rTorrent: ${e.faultString}")
            is IOException -> ApiError(502, "No se pudo conectar con rTorrent: ${e.message ?: e}")
            is BadRequest -> ApiError(400, e.message ?: "")
            is NumberFormatException, is ClassCastException, is IndexOutOfBoundsException,
            is NoSuchElementException, is NullPointerException, is IllegalArgumentException ->
                ApiError(400, e.message ?: e.toString())
            else -> e
        }
    }

    private val streamRe = Regex("/stream/([0-9A-Fa-f]{40})/(\\d+)(?:/[^/]*)?")
    private val logoRe = Regex("/logos/([0-9a-f]{16}\\.(png|jpg|gif|webp))")

    private fun q(req: Req, k: String): String? = parseQs(req.query)[k]?.firstOrNull()

    private fun doHead(req: Req, res: Res) {
        val m = streamRe.matchEntire(req.path)
        if (m != null) return stream(req, res, m.groupValues[1].uppercase(), m.groupValues[2].toInt(), head = true)
        staticFile(req, res, head = true)
    }

    private fun doGet(req: Req, res: Res) {
        if (req.path == "/api/details" && parseQs(req.query)["hash"] == null)
            return res.send(500, "text/plain", "missing hash".toByteArray())  // tablero.py se cae aquí
        if (req.path == "/api/backup-file") return backupFile(res, q(req, "path") ?: "")
        getRoute(req.path, { q(req, it) }, req.local)?.let { fn ->
            return if (req.path in unguarded) res.json(fn()) else guard(res, fn)
        }
        logoRe.matchEntire(req.path)?.let { return logo(res, it.groupValues[1], it.groupValues[2]) }
        streamRe.matchEntire(req.path)?.let { return stream(req, res, it.groupValues[1].uppercase(), it.groupValues[2].toInt(), head = false) }
        staticFile(req, res, head = false)
    }

    /** Las rutas GET de la API que no pasan por _guard en tablero.py. */
    private val unguarded = setOf("/api/access", "/api/dirs", "/api/history", "/api/tags", "/api/general", "/api/local-ip")

    private fun getRoute(path: String, q: (String) -> String?, local: Boolean): (() -> Any?)? {
        val b = backend
        return when (path) {
            "/api/access" -> { { access.info().apply { put("local", local); put("platform", Tab.platform) } } }
            "/api/torrents" -> { { b.list().apply { put("tablero", Tab.VERSION); put("platform", Tab.platform) } } }
            "/api/backups" -> { { backups.list(q("folder") ?: "") } }
            "/api/prefs" -> { { backups.loadPrefs() } }
            "/api/details" -> { { b.details(q("hash") ?: throw BadRequest("'hash'")) } }
            "/api/dirs" -> { { listDirs(q("path") ?: "") } }
            "/api/free-space" -> { { freeSpace(q("path").takeUnless { it.isNullOrEmpty() } ?: b.defaultDir()) } }
            "/api/history" -> { { history.get(q("hash")) } }
            "/api/tags" -> { { tagstore.get() } }
            "/api/general" -> { { general() } }
            "/api/local-ip" -> { { obj("ip" to localIp()) } }
            else -> null
        }
    }

    private fun errorPage(code: Int, msg: String) = ("<!DOCTYPE HTML>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n" +
        "<title>Error response</title>\n</head>\n<body>\n<h1>Error response</h1>\n<p>Error code: $code</p>\n" +
        "<p>Message: $msg.</p>\n</body>\n</html>\n").toByteArray()

    /** Los archivos de web/, como SimpleHTTPRequestHandler. */
    private fun staticFile(req: Req, res: Res, head: Boolean) {
        var p = unquote(req.path)
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        p = parts.joinToString("/")
        if (p.isEmpty() || req.path.endsWith("/")) p = if (p.isEmpty()) "index.html" else "$p/index.html"
        val data = o.web.open(p)
            ?: return res.send(404, "text/html;charset=utf-8", errorPage(404, "File not found"), head = head)
        res.send(200, guessType(p) ?: "application/octet-stream", data, head = head)
    }

    private fun logo(res: Res, name: String, ext: String) {
        val f = File(PyPath.join(tagstore.logoDir(), name))
        val data = try { f.readBytes() } catch (e: IOException) { return res.json(obj("error" to "not found"), 404) }
        res.send(200, "image/" + (if (ext == "jpg") "jpeg" else ext), data, listOf("X-Content-Type-Options" to "nosniff"))
    }

    private fun backupFile(res: Res, p: String) {
        val path: String
        val size: Long
        try { path = backups.backupPath(p); size = File(path).length() }
        catch (e: Exception) { return res.json(obj("error" to (e.message ?: e.toString())), 404) }
        File(path).inputStream().use { f ->
            res.start(200, listOf("Content-Type" to "application/zip", "Content-Length" to size.toString(),
                "Content-Disposition" to "attachment; filename=\"${PyPath.basename(path)}\""))
            f.copyTo(res.out)
        }
    }

    private val streamWait = 120.0       // segundos sin una pieza nueva antes de dejar a un reproductor
    private val streamBlock = 256 * 1024

    /**
     * Reproducir mientras baja: HTTP con Range para cualquier reproductor, que solo envía bytes de piezas
     * completas y espera la siguiente. Donde pide (o salta) el reproductor sigue la descarga en orden.
     */
    private fun stream(req: Req, res: Res, h: String, idx: Int, head: Boolean) {
        val b = backend
        val info = try { b.streamOpen(h, idx) } catch (e: NotFound) {
            return res.json(obj("error" to e.message), 404)
        } catch (e: XmlRpcFault) {
            return res.json(obj("error" to "rTorrent: ${e.faultString}"), 502)
        } catch (e: IOException) {
            return res.json(obj("error" to "rTorrent: ${e.message}"), 502)
        }
        val size = info.size; val chunk = info.chunk; val base = info.offset
        var start = 0L; var end = size - 1; var partial = false
        val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire((req.header("Range") ?: "").trim())
        if (m != null && (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty())) {
            partial = true
            if (m.groupValues[1].isNotEmpty()) {
                start = m.groupValues[1].toLong()
                end = if (m.groupValues[2].isNotEmpty()) minOf(m.groupValues[2].toLong(), size - 1) else size - 1
            } else start = maxOf(0L, size - m.groupValues[2].toLong())
            if (start >= size || start > end) {
                res.start(416, listOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
                return
            }
        }
        val hs = mutableListOf("Content-Type" to (guessType(info.name) ?: "application/octet-stream"),
            "Accept-Ranges" to "bytes", "Content-Length" to (end - start + 1).toString())
        if (partial) hs.add("Content-Range" to "bytes $start-$end/$size")
        res.start(if (partial) 206 else 200, hs)
        if (head || size == 0L) return
        res.out.flush()
        var pos = start; var asked: Long? = null; var idle = 0.0
        var f: RandomAccessFile? = null
        try {
            while (pos <= end) {
                val have = try { b.completedPieces(h) } catch (e: Exception) { ByteArray(0) }
                val piece = (base + pos) / chunk
                var c = piece
                while (c < info.chunks && c / 8 < have.size && (have[(c / 8).toInt()].toInt() and (0x80 shr (c % 8).toInt())) != 0 &&
                    c * chunk - base <= end) c++
                val ready = minOf(end + 1, c * chunk - base)  // los bytes [pos, ready) están en piezas completas
                if (ready <= pos || (f == null && !File(info.path).isFile)) {
                    if (asked != piece) {
                        asked = piece
                        try { b.streamFrom(h, piece) } catch (e: Exception) { /* se intenta otra vez */ }
                    }
                    if (idle >= streamWait) return
                    Thread.sleep(500); idle += 0.5
                    continue
                }
                idle = 0.0
                if (f == null) f = RandomAccessFile(info.path, "r")
                f.seek(pos)
                val buf = ByteArray(streamBlock)
                while (pos < ready) {
                    val n = f.read(buf, 0, minOf(streamBlock.toLong(), ready - pos).toInt())
                    if (n <= 0) return
                    res.out.write(buf, 0, n)
                    pos += n
                }
                res.out.flush()
            }
        } catch (e: IOException) {
            // el reproductor lo cerró (saltar abre otro pedido)
        } finally { f?.close() }
    }

    private fun setGeneral(body: Map<String, Any?>): Obj {
        val g = backend.general!!
        val old = generalPeers(g.get())
        val new = generalPeers(g.save(body))
        try {
            backend.applyGlobal()
            @Suppress("UNCHECKED_CAST") val torrents = backend.list()["torrents"] as List<Obj>
            // Un valor vaciado vuelve al de rTorrent en los torrents que lo seguían.
            val cleared = PEER_KEYS.filter { old[it] != null && new[it] == null }
            if (cleared.isNotEmpty()) backend.resetPeers(torrents, cleared)
            backend.applyPeers(torrents)
        } catch (e: Exception) { if (!(e is IOException || e is XmlRpcFault)) throw e }  // se aplica cuando rTorrent conteste
        return general()
    }

    /** Las opciones generales, con los valores de rTorrent (que se ven cuando un campo está vacío). */
    private fun general(): Obj {
        val out = obj("general" to backend.general!!.get(), "rtorrent" to obj(), "torrent" to obj())
        try {
            out["rtorrent"] = backend.applyGlobal()
            out["torrent"] = backend.peerDefaults()
        } catch (e: Exception) { if (!(e is IOException || e is XmlRpcFault)) throw e }
        return out
    }

    private fun setAccess(local: Boolean, body: Map<String, Any?>): Obj {
        if (!local) throw BadRequest("El acceso desde la red solo se cambia en %s donde corre Tablero".format(
            if (Tab.platform == "android") "el teléfono/tableta" else "el equipo"))
        val r = access.save(body["lan"], body["password"] as? String ?: "")
        if (r["restart"] == true) thread(isDaemon = true) { Thread.sleep(500); restartSelf() }
        return r
    }

    private fun doPost(req: Req, res: Res) {
        // Una cabecera propia obliga a una consulta CORS previa: otras páginas no pueden usar la API con un formulario.
        if (req.header("X-Tablero") != "1") return res.json(obj("error" to "forbidden"), 403)
        val n = req.header("Content-Length")?.trim()?.toLongOrNull() ?: 0L
        if (req.path == "/api/backup-import") {  // el .zip mismo, no JSON
            return guard(res) { backups.importFile(req.input, n, q(req, "folder") ?: "", q(req, "name") ?: "") }
        }
        if (n > 8 * 1024 * 1024) return res.json(obj("error" to "Demasiado grande"), 413)
        val raw = ByteArray(n.toInt())
        var got = 0
        while (got < raw.size) { val k = req.input.read(raw, got, raw.size - got); if (k < 0) break; got += k }
        val bodyAny = try { if (got == 0) obj() else Json.parse(raw.copyOf(got)) } catch (e: BadRequest) {
            return res.json(obj("error" to "JSON inválido"), 400)
        }
        val fn = postRoute(req.path, bodyAny, req.local) ?: return res.json(obj("error" to "not found"), 404)
        guard(res) { val r = fn(); if (r is Map<*, *>) r else obj("ok" to true) }
    }

    private fun postRoute(path: String, bodyAny: Any?, local: Boolean): (() -> Any?)? {
        val b = backend
        fun body() = asObj(bodyAny)
        fun bytesOf(v: Any?): ByteArray = try { Base64.getMimeDecoder().decode(asStr(v)) } catch (e: IllegalArgumentException) {
            throw BadRequest("Incorrect padding")
        }
        val routes: Map<String, () -> Any?> = mapOf(
            "/api/action" to { val o = body(); b.action(asStr(o.need("action")), strList(o.need("hashes")), o["data"], o["value"]) },
            "/api/set-tags" to { val o = body(); b.setTags(strList(o.need("hashes")), asList(o.need("tags"))) },
            "/api/edit-tags" to { val o = body(); b.editTags(strList(o.need("hashes")), asList(o["add"] ?: emptyList<Any>()), asList(o["remove"] ?: emptyList<Any>())) },
            "/api/rename-tag" to { val o = body(); obj("count" to b.renameTag(asStr(o.need("old")), o["new"] as? String ?: "")) },
            "/api/file-priority" to { val o = body(); b.filePriority(asStr(o.need("hash")), asList(o.need("indexes")), o.need("priority")) },
            "/api/add" to {
                val o = body()
                val data = if (truthy(o["file"])) bytesOf(o["file"]) else if (truthy(o["path"])) readTorrentFile(asStr(o["path"])) else null
                b.add(o["magnet"] as? String, data, o["directory"] as? String ?: "", asList(o["tags"] ?: emptyList<Any>()),
                    if (o.containsKey("start")) truthy(o["start"]) else true, asList(o["skip"] ?: emptyList<Any>()), truthy(o["sequential"]))
            },
            "/api/torrent-info" to {
                val o = body()
                torrentFiles(if (truthy(o["file"])) bytesOf(o["file"]) else readTorrentFile(asStr(o.need("path"))))
            },
            "/api/limits" to { val o = body(); b.limits(o["down"], o["up"]) },
            "/api/shutdown" to { b.shutdown() },
            "/api/port-test" to { b.portTest().apply { put("local_ip", localIp()) } },
            "/api/port" to { b.setPort(body()["port"]) },
            "/api/add-trackers" to { val o = body(); b.addTrackers(asStr(o.need("hash")), asList(o["urls"] ?: emptyList<Any>())) },
            "/api/open-folder" to { val o = body(); b.openFolder(asStr(o.need("hash")), o["index"], o["dir"]) },
            "/api/move" to { val o = body(); b.movePos(strList(o.need("hashes")), asStr(o.need("where"))) },
            "/api/options" to { val o = body(); b.setOptions(asStr(o.need("hash")), asObj(o.need("options"))) },
            "/api/general" to { setGeneral(body()) },
            "/api/rename-folder" to { val o = body(); b.renameFolder(asStr(o.need("hash")), o["name"]) },
            "/api/relocate" to { val o = body(); b.relocate(strList(o.need("hashes")), o.need("target"), truthy(o["move"]), truthy(o["check"])) },
            "/api/tags" to { tagstore.save(body()) },
            "/api/logo" to { tagstore.addLogo(bytesOf(body().need("data"))) },
            "/api/access" to { setAccess(local, body()) },
            "/api/prefs" to { backups.savePrefs(or(body()["prefs"], obj())) },
            "/api/backup-settings" to { val o = body(); backups.setSettings(o["folder"] ?: "", o["every"] ?: "never", o["keep"] ?: 5L) },
            "/api/backup" to { backups.create(body()["folder"]) },
            "/api/restore-inspect" to { val o = body(); backups.inspect(asStr(o.need("path")), o["from"] as? String ?: "", o["to"] as? String ?: "") },
            "/api/restore" to { val o = body(); backups.restore(asStr(o.need("path")), asObj(or(o["parts"], obj())), o["from"] as? String ?: "", o["to"] as? String ?: "") },
        )
        return routes[path]
    }
}
