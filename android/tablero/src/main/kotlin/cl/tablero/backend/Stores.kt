package cl.tablero.backend

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private fun writeAtomic(path: String, text: String) {
    File(path).parentFile?.mkdirs()
    val tmp = File("$path.tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (!tmp.renameTo(File(path))) throw IOException("No pude guardar $path")
}

/** Colores, íconos y orden de las etiquetas, en tags.json. */
class TagStore(val path: String) {
    companion object {
        /** Las etiquetas de una instalación nueva (sin tags.json), en este orden. */
        val DEFAULTS: LinkedHashMap<String, Pair<String, String>> = linkedMapOf(
            // Contenido
            "Películas" to ("#e53935" to "lucide:clapperboard"), "Series" to ("#3949ab" to "lucide:tv"),
            "Documentales" to ("#fb8c00" to "lucide:globe"), "Música" to ("#00897b" to "lucide:music"),
            "Audiolibros" to ("#6d4c41" to "lucide:book-headphones"), "Libros" to ("#ffb300" to "lucide:book-open"),
            "Juegos" to ("#43a047" to "lucide:gamepad-2"), "Aplicaciones" to ("#757575" to "lucide:app-window"),
            "Linux" to ("#fdd835" to "lucide:terminal"), "Windows" to ("#00acc1" to "lucide:monitor"),
            // Formatos físicos y digitales de video
            "Blu-ray" to ("#039be5" to "lucide:disc"), "Blu-ray UHD" to ("#1e88e5" to "lucide:disc-3"),
            "Blu-ray Remux" to ("#039be5" to "lucide:hard-drive"), "Blu-ray UHD Remux" to ("#1e88e5" to "lucide:hard-drive"),
            "Web-DL" to ("#8e24aa" to "lucide:cloud"), "DVD" to ("#757575" to "lucide:disc"),
            "DVD Rip" to ("#757575" to "lucide:film"),
            // Formatos físicos y digitales de audio
            "CD" to ("#757575" to "lucide:disc"), "SACD" to ("#ffb300" to "lucide:disc-3"),
            "MP3" to ("#7cb342" to "lucide:headphones"), "FLAC" to ("#43a047" to "lucide:audio-lines"),
            "DSD" to ("#00897b" to "lucide:audio-lines"), "WAV" to ("#00acc1" to "lucide:music-2"),
            // Uso
            "Favoritos" to ("#fdd835" to "lucide:star"), "Colección" to ("#ffb300" to "lucide:crown"),
            "Ver después" to ("#5e35b1" to "lucide:bookmark"), "Visto / Escuchado" to ("#7cb342" to "lucide:check"),
            "Conservar" to ("#3949ab" to "lucide:shield"), "Sembrar siempre" to ("#f4511e" to "lucide:flame"),
            "Temporal" to ("#757575" to "lucide:clock"), "Por revisar" to ("#fb8c00" to "lucide:search"),
            "Privado" to ("#d81b60" to "lucide:lock"),
            // Idioma
            "Latino" to ("#43a047" to "lucide:languages"), "Castellano" to ("#e53935" to "lucide:languages"),
            "Inglés" to ("#1e88e5" to "lucide:languages"), "Subtitulado" to ("#757575" to "lucide:captions"),
            "Dual" to ("#8e24aa" to "lucide:languages"),
            // Imagen y sonido
            "HDR" to ("#ffb300" to "lucide:sun"), "Dolby Vision" to ("#5e35b1" to "lucide:eye"),
            "Atmos" to ("#00acc1" to "lucide:speaker"),
        )
        const val LOGO_MAX = 1024 * 1024
    }

    private val lock = Any()
    @Volatile private var data: Any? = try { Json.parse(File(path).readBytes()) } catch (e: Exception) {
        obj("tags" to DEFAULTS.entries.associateTo(linkedMapOf()) { (k, v) -> k to obj("color" to v.first, "icon" to v.second) },
            "order" to DEFAULTS.keys.toList())
    }

    fun get(): Any? = data

    fun save(body: Map<String, Any?>): Any? = synchronized(lock) {
        val d = obj("tags" to (if (body.containsKey("tags")) body["tags"] else obj()),
            "order" to (if (body.containsKey("order")) body["order"] else emptyList<Any?>()))
        data = d
        writeAtomic(path, Json.dumps(d, indent = 1))
        cleanLogos()
        d
    }

    // Logos: imágenes chicas en logos/ junto a tags.json, con nombre según su contenido.
    fun logoDir(): String = PyPath.join(PyPath.dirname(path), "logos")

    fun addLogo(b: ByteArray): Obj {
        if (b.size > LOGO_MAX) throw BadRequest("La imagen pesa más de 1 MB")
        fun starts(vararg m: Int) = b.size >= m.size && m.indices.all { (b[it].toInt() and 0xff) == m[it] }
        var ext: String? = when {
            starts(0x89, 'P'.code, 'N'.code, 'G'.code) -> "png"
            starts(0xff, 0xd8, 0xff) -> "jpg"
            starts('G'.code, 'I'.code, 'F'.code, '8'.code) -> "gif"
            starts('R'.code, 'I'.code, 'F'.code, 'F'.code) -> "webp"
            else -> null
        }
        if (ext == "webp" && !(b.size >= 12 && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP")) ext = null
        if (ext == null) throw BadRequest("Formato no admitido: usa PNG, JPEG, WebP o GIF")
        val name = "%s.%s".format(hexOf(sha1(b)).take(16), ext)
        File(logoDir()).mkdirs()
        File(PyPath.join(logoDir(), name)).writeBytes(b)
        return obj("url" to "/logos/$name")
    }

    private fun cleanLogos() {
        val d = File(PyPath.dirname(path))
        val used = HashSet<String>()
        for (name in d.list() ?: emptyArray()) {
            if (!(name.startsWith("tags") && name.endsWith(".json"))) continue
            val tags = try { (Json.parse(File(d, name).readBytes()) as Map<*, *>)["tags"] as? Map<*, *> ?: emptyMap<Any, Any>() }
                       catch (e: Exception) { return }  // un archivo de etiquetas ilegible: se guardan todos los logos
            for (t in tags.values) if (t is Map<*, *>) used.add((t["logo"] as? String ?: "").substringAfterLast('/'))
        }
        for (f in File(logoDir()).listFiles() ?: emptyArray()) {
            if (f.name !in used && System.currentTimeMillis() - f.lastModified() > 3600_000) f.delete()
        }
    }
}

/** Los respaldos se llaman así; los importados terminan en -importado (-importado-2…). */
val BACKUP_NAME = Regex("^tablero-respaldo-\\d{4}-\\d{2}-\\d{2}-\\d{4}(-importado(-\\d+)?)?\\.zip$")
const val BACKUP_IMPORT_MAX = 1024L * 1024 * 1024
val BACKUP_EVERY = mapOf("daily" to 86400L, "weekly" to 7 * 86400L)

/**
 * Respaldos para reinstalar u otro equipo: un .zip con lo de Tablero, la sesión de rTorrent y
 * rtorrent.rc. No los archivos bajados. A mano o programados, en una carpeta (backup.json).
 */
class Backups(val dir: String, private val backend: Backend, private val tagstore: TagStore, private val access: Access) {
    private val path = PyPath.join(dir, "backup.json")
    private val lock = Any()

    fun settings(): Obj {
        val conf = try { Json.parse(File(path).readBytes()) as? Map<*, *> } catch (e: Exception) { null } ?: emptyMap<String, Any?>()
        val folder = conf["folder"] as? String
        return obj("folder" to (folder.takeUnless { it.isNullOrEmpty() } ?: PyPath.join(Tab.userFiles(), "Respaldos de Tablero")),
            "chosen" to !folder.isNullOrEmpty(), "every" to (conf["every"] ?: "never"),
            "keep" to pyInt(conf["keep"] ?: 5L), "last" to (conf["last"] ?: 0L), "last_file" to (conf["last_file"] ?: ""))
    }

    private fun saveSettings(conf: Map<String, Any?>) {
        File(dir).mkdirs()
        writeAtomic(path, Json.dumps(conf))
    }

    fun setSettings(folderIn: Any?, every: Any?, keep: Any?): Obj {
        val folder = if (truthy(folderIn)) checkTarget(folderIn) else ""
        if (every !in listOf("never", "daily", "weekly")) throw BadRequest("Frecuencia no válida")
        if (every != "never" && folder.isEmpty()) throw BadRequest("Elige la carpeta de los respaldos automáticos")
        val conf = settings()
        conf["folder"] = folder; conf["every"] = every
        conf["keep"] = maxOf(1L, minOf(100L, pyInt(or(keep, 5L))))
        saveSettings(conf)
        return conf
    }

    fun list(folderIn: String?): Obj {
        val conf = settings()
        val folder = folderIn.takeUnless { it.isNullOrEmpty() } ?: conf["folder"] as String
        val files = ArrayList<Obj>()
        if (folder.isNotEmpty() && PyPath.isdir(folder)) {
            for (f in File(folder).listFiles() ?: emptyArray()) {
                if (BACKUP_NAME.matches(f.name) && f.isFile)
                    files.add(obj("name" to f.name, "path" to PyPath.join(folder, f.name), "size" to f.length(), "time" to f.lastModified() / 1000))
            }
        }
        files.sortByDescending { it["name"] as String }
        return conf.apply { put("folder", folder); put("files", files) }
    }

    fun loadPrefs(): Obj = try { obj("prefs" to Json.parse(File(PyPath.join(dir, "prefs.json")).readBytes())) }
                           catch (e: Exception) { obj("prefs" to obj()) }

    /** Las preferencias de la interfaz, que la página guarda en el navegador: también aquí, para los respaldos. */
    fun savePrefs(prefs: Any?): Obj {
        File(dir).mkdirs()
        File(PyPath.join(dir, "prefs.json")).writeText(Json.dumps(prefs), Charsets.UTF_8)
        return obj()
    }

    fun create(folderIn: Any? = null, auto: Boolean = false): Obj {
        val conf = settings()
        val folder = checkTarget(or(or(folderIn, conf["folder"]), ""))
        if (!File(folder).isDirectory && !File(folder).mkdirs()) throw BadRequest("No pude crear la carpeta $folder: no se pudo crear")
        val path: String
        var count = 0
        synchronized(lock) {
            val name = SimpleDateFormat("'tablero-respaldo-'yyyy-MM-dd-HHmm'.zip'").format(Date())
            path = PyPath.join(folder, name)
            val tmp = File("$path.tmp")
            val torrents = ArrayList<Obj>()
            try {
                ZipOutputStream(tmp.outputStream().buffered()).use { z ->
                    fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
                    // Tablero: todo lo de su carpeta menos los temporales y lo del modo demo.
                    val root = File(dir)
                    root.walkTopDown().onEnter { it == root || !(it.parentFile == root && it.name == "demo") }.forEach { f ->
                        if (!f.isFile) return@forEach
                        val n = f.name
                        if (n.endsWith(".tmp") || "-demo." in n || n == "backup.json") return@forEach
                        put("tablero/" + f.relativeTo(root).path, f.readBytes())
                    }
                    val (session, rc, entries) = backend.sessionFiles()
                    for ((h, files) in entries) {
                        for ((ext, data) in files) put("rtorrent/session/$h$ext", data)
                        val info = obj("hash" to h)
                        try {
                            val m = files[".torrent.rtorrent"]?.let { Bencode.decode(it) as Map<*, *> } ?: emptyMap<String, Any>()
                            val t = (Bencode.decode(files.getValue(".torrent")) as Map<*, *>)["info"] as Map<*, *>
                            info["name"] = Bencode.text(t["name"] as ByteArray)
                            info["directory"] = Bencode.text(m["directory"] ?: ByteArray(0))
                            info["multi"] = "files" in t
                        } catch (e: Exception) { /* sin nombre */ }
                        torrents.add(info)
                    }
                    if (rc != null && PyPath.isfile(rc)) put("rtorrent/rtorrent.rc", File(rc).readBytes())
                    put("manifest.json", Json.dumps(obj("app" to "Tablero", "version" to Tab.VERSION,
                        "created" to System.currentTimeMillis() / 1000, "host" to Tab.hostname(), "session" to session,
                        "rc" to (rc ?: ""), "torrents" to torrents), indent = 1).toByteArray(Charsets.UTF_8))
                }
                if (!tmp.renameTo(File(path))) throw IOException("no se pudo renombrar")
            } catch (e: IOException) {
                tmp.delete()
                throw BadRequest("No pude crear el respaldo en $folder: ${e.message}")
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            count = torrents.size
            conf["last"] = System.currentTimeMillis() / 1000; conf["last_file"] = path
            if (!truthy(conf["folder"])) conf["folder"] = folder
            saveSettings(conf)
            if (auto) {
                val keep = pyInt(conf["keep"]).toInt()
                val old = (File(folder).list() ?: emptyArray()).filter { BACKUP_NAME.matches(it) && "-importado" !in it }.sorted()
                old.dropLast(keep).forEach { File(folder, it).delete() }
            }
        }
        return obj("path" to path, "torrents" to count.toLong())
    }

    /** Cada media hora: un respaldo cuando el último es más viejo que el período elegido. */
    fun runSchedule(running: () -> Boolean) {
        while (running()) {
            val conf = settings()
            val period = BACKUP_EVERY[conf["every"]]
            if (period != null && truthy(conf["folder"]) && System.currentTimeMillis() / 1000 - pyInt(conf["last"] ?: 0L) >= period) {
                try { create(auto = true) } catch (e: Exception) { /* se intenta en la próxima vuelta */ }
            }
            try { Thread.sleep(1800_000) } catch (e: InterruptedException) { return }
        }
    }

    /** Un respaldo que Tablero puede entregar: con nombre de respaldo, y que de verdad lo es. */
    fun backupPath(p: String?): String {
        val path = PyPath.realpath(p ?: "")
        if (!BACKUP_NAME.matches(PyPath.basename(path))) throw BadRequest("No es un respaldo de Tablero")
        open(path).first.close()
        return path
    }

    /** Un .zip que llega de otro lado (Importar): a la carpeta de respaldos, revisado antes de guardarlo. */
    fun importFile(input: InputStream, size: Long, folderIn: String, name: String): Obj {
        if (size <= 0) throw BadRequest("El archivo está vacío")
        if (size > BACKUP_IMPORT_MAX) throw BadRequest("El archivo es demasiado grande para ser un respaldo de Tablero")
        val folder = checkTarget(folderIn.ifEmpty { settings()["folder"] as String })
        if (!File(folder).isDirectory && !File(folder).mkdirs()) throw BadRequest("No pude crear la carpeta $folder")
        val tmp = File(folder, ".importando-%d.tmp".format(ProcessHandleCompat.pid()))
        try {
            tmp.outputStream().use { out ->
                var left = size
                val buf = ByteArray(1 shl 20)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                    if (n <= 0) throw BadRequest("El archivo no llegó completo")
                    out.write(buf, 0, n); left -= n
                }
            }
            val man = try { open(tmp.path).let { (z, m) -> z.close(); m } }
                      catch (e: BadRequest) { throw BadRequest("${name.ifEmpty { "El archivo" }} no es un respaldo de Tablero") }
            val created = (man["created"] as? Number)?.toLong()?.takeIf { it != 0L } ?: (System.currentTimeMillis() / 1000)
            val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm").format(Date(created * 1000))
            val ntor = ((man["torrents"] as? List<*>)?.size ?: 0).toLong()
            var n = 1
            while (true) {
                val path = File(folder, "tablero-respaldo-%s-importado%s.zip".format(stamp, if (n > 1) "-$n" else ""))
                if (!path.exists()) {
                    if (!tmp.renameTo(path)) throw IOException("no se pudo renombrar")
                    return obj("path" to path.path, "torrents" to ntor)
                }
                if (path.length() == size) return obj("path" to path.path, "torrents" to ntor)  // el mismo, otra vez
                n++
            }
        } catch (e: IOException) {
            throw BadRequest("No pude guardar el respaldo en $folder: ${e.message}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun open(path: String): Pair<ZipFile, Map<String, Any?>> {
        if (path.isEmpty() || !PyPath.isfile(path)) throw BadRequest("No encontré el respaldo $path")
        val z = try { ZipFile(path) } catch (e: Exception) { throw BadRequest("${PyPath.basename(path)} no es un respaldo de Tablero") }
        try {
            val e = z.getEntry("manifest.json") ?: throw IOException("sin manifest.json")
            @Suppress("UNCHECKED_CAST")
            val m = Json.parse(z.getInputStream(e).readBytes()) as? Map<String, Any?> ?: throw IOException("manifest.json inválido")
            return z to m
        } catch (e: Exception) {
            z.close()
            throw BadRequest("${PyPath.basename(path)} no es un respaldo de Tablero")
        }
    }

    private fun ZipFile.names(): List<String> = entries().toList().map { it.name }
    private fun ZipFile.read(n: String): ByteArray = getInputStream(getEntry(n)).use { it.readBytes() }

    /** Qué tiene un respaldo, y dónde se buscarían aquí los datos de sus torrents. */
    fun inspect(path: String, old: String, new: String): Obj {
        val (z, man) = open(path)
        val names = z.use { it.names().toSet() }
        val have = backend.hashes().toSet()
        val folders = LinkedHashMap<String, Obj>()
        val torrents = (man["torrents"] as? List<*> ?: emptyList<Any?>()).filterIsInstance<Map<*, *>>()
        for (t in torrents) {
            val d = t["directory"] as? String ?: ""
            if (d.isEmpty()) continue
            val multi = truthy(t["multi"])
            val parent = if (multi) PyPath.dirname(d) else d
            val data = if (multi) d else PyPath.join(d, t["name"] as? String ?: "")
            val f = folders.getOrPut(parent) { obj("from" to parent, "to" to remapPath(parent, old, new), "count" to 0L, "found" to 0L) }
            f["count"] = (f["count"] as Long) + 1
            if (PyPath.exists(remapPath(data, old, new))) f["found"] = (f["found"] as Long) + 1
        }
        return obj("created" to man["created"], "host" to man["host"], "version" to man["version"],
            "torrents" to torrents.size.toLong(),
            "present" to torrents.count { it["hash"] in have }.toLong(),
            "folders" to folders.values.sortedBy { it["from"] as String },
            "has" to obj("tags" to ("tablero/tags.json" in names), "prefs" to ("tablero/prefs.json" in names),
                "access" to ("tablero/access.json" in names), "rc" to ("rtorrent/rtorrent.rc" in names)))
    }

    fun restore(path: String, parts: Map<String, Any?>, old: String, new: String): Obj {
        val (z, _) = open(path)
        val res = obj()
        z.use { zf ->
            val names = zf.names()
            if (truthy(parts["tags"]) && "tablero/tags.json" in names) {
                val tags = Json.parse(zf.read("tablero/tags.json"))
                val logos = tagstore.logoDir()
                File(logos).mkdirs()
                for (n in names) {
                    val base = PyPath.basename(n)
                    if (n.startsWith("tablero/logos/") && base.isNotEmpty() && !base.startsWith("."))
                        File(logos, base).writeBytes(zf.read(n))
                }
                tagstore.save(asObj(tags))
                res["tags"] = ((if (tags is Map<*, *>) (tags["tags"] ?: tags) else emptyList<Any>()).let {
                    when (it) { is Map<*, *> -> it.size; is List<*> -> it.size; else -> 0 } }).toLong()
            }
            if (truthy(parts["prefs"]) && "tablero/prefs.json" in names) res["prefs"] = Json.parse(zf.read("tablero/prefs.json"))
            val general = backend.general
            if (truthy(parts["prefs"]) && "tablero/general.json" in names && general != null) {
                File(general.path).writeBytes(zf.read("tablero/general.json"))
                general.reload()
            }
            if (truthy(parts["access"]) && "tablero/access.json" in names) {
                File(access.path).writeBytes(zf.read("tablero/access.json"))
                res["access"] = true
            }
            if (truthy(parts["rc"]) && "rtorrent/rtorrent.rc" in names) res["rc"] = backend.restoreRc(zf.read("rtorrent/rtorrent.rc"))
            if (truthy(parts["torrents"])) {
                val entries = LinkedHashMap<String, MutableMap<String, ByteArray>>()
                val re = Regex("^rtorrent/session/([0-9A-F]{40})(\\.torrent(?:\\.libtorrent_resume|\\.rtorrent)?)$")
                for (n in names) {
                    val m = re.matchEntire(n) ?: continue
                    entries.getOrPut(m.groupValues[1]) { linkedMapOf() }[m.groupValues[2]] = zf.read(n)
                }
                res["torrents"] = backend.restoreTorrents(entries.filterValues { ".torrent" in it }, old, new)
            }
        }
        return res
    }
}

/** El pid, también en Android (ProcessHandle no existe allí). */
object ProcessHandleCompat {
    fun pid(): Long = try { File("/proc/self").canonicalFile.name.toLong() } catch (e: Exception) { 0L }
}

/**
 * Abrir Tablero desde otros equipos de la red (⚙ Opciones), en access.json. Con eso, Tablero escucha
 * en todas las direcciones, y quien no esté en este equipo debe dar la contraseña (HTTP Basic).
 */
class Access(val path: String, private val bindArg: String?, val port: Int) {
    companion object { const val ROUNDS = 200000 }

    private val lock = Any()
    private val ok = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var data: Map<String, Any?> = try {
        @Suppress("UNCHECKED_CAST")
        Json.parse(File(path).readBytes()) as? Map<String, Any?> ?: emptyMap()
    } catch (e: Exception) { emptyMap() }
    @Volatile var listening: String = wantedBind()

    fun wantedBind(): String = bindArg ?: if (truthy(data["lan"]) && truthy(data["hash"])) "0.0.0.0" else "127.0.0.1"

    fun info(): Obj = obj("lan" to truthy(data["lan"]), "password" to truthy(data["hash"]), "fixed" to (bindArg ?: ""),
        "listening" to (listening !in listOf("127.0.0.1", "localhost", "::1")), "ip" to localIp(), "port" to port.toLong())

    /** hashlib.pbkdf2_hmac("sha256", ...), escrito aquí para que dé lo mismo en Android y en la JVM. */
    private fun hash(password: String, salt: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val s = fromHex(salt)
        mac.update(s); mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val t = u.copyOf()
        for (i in 1 until ROUNDS) {
            u = mac.doFinal(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        return hexOf(t)
    }

    fun save(lan: Any?, password: String): Obj = synchronized(lock) {
        val d = LinkedHashMap(data)
        d["lan"] = truthy(lan)
        if (password.isNotEmpty()) {
            if (password.length < 6) throw BadRequest("La contraseña debe tener al menos 6 caracteres")
            d["salt"] = hexOf(ByteArray(16).also { SecureRandom().nextBytes(it) })
            d["hash"] = hash(password, d["salt"] as String)
        }
        if (truthy(d["lan"]) && !truthy(d["hash"])) throw BadRequest("Elige una contraseña para abrir Tablero desde la red")
        File(path).parentFile?.mkdirs()
        val tmp = File("$path.tmp")
        tmp.writeText(Json.dumps(d))
        runCatching { tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(false, false); tmp.setWritable(true, true) }
        if (!tmp.renameTo(File(path))) throw IOException("No pude guardar $path")
        data = d
        ok.clear()
        info().apply { put("restart", wantedBind() != listening) }
    }

    /** Este equipo siempre entra; los demás necesitan la contraseña, si hay una. */
    fun allowed(local: Boolean, header: String?): Boolean {
        if (local || !truthy(data["hash"])) return true
        if (header == null || !header.startsWith("Basic ")) return false
        if (header in ok) return true
        val password = try {
            String(java.util.Base64.getDecoder().decode(header.substring(6)), Charsets.UTF_8).let {
                if (':' !in it) return false
                it.substringAfter(':')
            }
        } catch (e: IllegalArgumentException) { return false }
        if (java.security.MessageDigest.isEqual(hash(password, data["salt"] as? String ?: "").toByteArray(),
                (data["hash"] as? String ?: "").toByteArray())) {
            ok.add(header)
            return true
        }
        Thread.sleep(1000)  // hace lento adivinar
        return false
    }
}
