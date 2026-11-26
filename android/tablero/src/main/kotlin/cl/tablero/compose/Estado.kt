package cl.tablero.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

const val NO_TAG = "\u0000none"

val PALETTE = listOf(
    "#e53935", "#d81b60", "#8e24aa", "#5e35b1", "#3949ab", "#1e88e5",
    "#039be5", "#00acc1", "#00897b", "#43a047", "#7cb342", "#c0ca33",
    "#fdd835", "#ffb300", "#fb8c00", "#f4511e", "#6d4c41", "#757575",
)

/** El orden de la lista: (columna, 1 ascendente / -1 descendente), como prefs.sort de la web. */
data class Orden(val col: String = "added", val dir: Int = -1)

/**
 * Las preferencias de la interfaz con los mismos nombres que la web (prefs de app.js), guardadas en el
 * servidor con /api/prefs para que vayan en los respaldos. [raw] conserva las que solo usa la web.
 */
data class Prefs(
    val raw: Map<String, Any?> = emptyMap(),
    val sort: Orden = Orden(),
    val theme: String = "dark",
    val tint: Boolean = true,
    val tagbar: Boolean = true,
    val tagMode: String = "any",
    val interval: Int = 2,
    val lastDir: String = "",
    val filesTree: Boolean = true,
    val hideSkipped: Boolean = false,
    val speedOf: String = "torrent",
    val speedRange: Int = 300,
    val units: String = "bin",
    val tab: String = "general",
    val backupAsked: Boolean = false,
    /** El orden de las tablas del detalle (files, peers, trackers): prefs.tableSort de la web. */
    val tableSort: Map<String, Orden> = emptyMap(),
    /** Solo de esta app: los datos que muestra cada fila de la lista, además del nombre. */
    val fields: List<String> = DEFAULT_FIELDS,
) {
    fun toObj(): Map<String, Any?> = LinkedHashMap(raw).apply {
        put("sort", linkedMapOf("col" to sort.col, "dir" to sort.dir.toLong()))
        put("theme", theme); put("tint", tint); put("tagbar", tagbar); put("tagMode", tagMode)
        put("interval", interval.toLong()); put("lastDir", lastDir); put("filesTree", filesTree)
        put("hideSkipped", hideSkipped); put("speedOf", speedOf); put("speedRange", speedRange.toLong())
        put("units", units); put("tab", tab); put("backupAsked", backupAsked)
        put("appFields", fields.toList())
        put("tableSort", LinkedHashMap<String, Any?>().apply {
            for ((k, o) in tableSort) put(k, linkedMapOf("col" to o.col, "dir" to o.dir.toLong()))
        })
    }

    companion object {
        val DEFAULT_FIELDS = listOf("tags", "progress", "status", "down_rate", "up_rate", "eta", "size")

        fun from(o: Map<String, Any?>): Prefs {
            val d = Prefs()
            val s = asMap(o["sort"])
            fun b(k: String, def: Boolean) = (o[k] as? Boolean) ?: def
            fun t(k: String, def: String) = (o[k] as? String) ?: def
            fun i(k: String, def: Int) = (o[k] as? Number)?.toInt() ?: def
            return Prefs(
                raw = o,
                sort = Orden((s["col"] as? String)?.takeIf { it in COLUMN_IDS } ?: "added", if (lng(s["dir"] ?: -1L) > 0) 1 else -1),
                theme = t("theme", d.theme), tint = b("tint", d.tint), tagbar = b("tagbar", d.tagbar),
                tagMode = t("tagMode", d.tagMode), interval = i("interval", d.interval), lastDir = t("lastDir", ""),
                filesTree = o["filesTree"] != false, hideSkipped = b("hideSkipped", false),
                speedOf = t("speedOf", d.speedOf), speedRange = i("speedRange", d.speedRange), units = t("units", d.units),
                tab = t("tab", d.tab), backupAsked = b("backupAsked", false),
                fields = (o["appFields"] as? List<*>)?.map { str(it) }?.filter { it in COLUMN_IDS } ?: DEFAULT_FIELDS,
                tableSort = asMap(o["tableSort"]).mapNotNull { (k, v) ->
                    val m = asMap(v); val col = m["col"] as? String ?: return@mapNotNull null
                    k to Orden(col, if (lng(m["dir"]) > 0) 1 else -1)
                }.toMap(),
            )
        }
    }
}

data class Filtro(
    val status: String = "all",
    val tags: Set<String> = emptySet(),
    val tracker: String? = null,
    val text: String = "",
)

/** Los filtros por estado de la barra lateral de la web. */
data class FiltroEstado(val id: String, val name: String, val icon: String, val test: (Torrent) -> Boolean)

val STATUS_FILTERS = listOf(
    FiltroEstado("all", "Todos", "layout-grid") { true },
    FiltroEstado("downloading", "Descargando", "arrow-down") { it.status == "downloading" },
    FiltroEstado("seeding", "Compartiendo", "arrow-up") { it.status == "seeding" },
    FiltroEstado("complete", "Completos", "check") { it.complete },
    FiltroEstado("incomplete", "Incompletos", "chart-pie") { !it.complete },
    FiltroEstado("stopped", "Detenidos y en pausa", "pause") { it.status in setOf("stopped", "finished", "paused") },
    FiltroEstado("active", "Con actividad", "arrow-down-up") { it.downRate > 0 || it.upRate > 0 },
    FiltroEstado("inactive", "Sin actividad", "circle") { it.downRate == 0L && it.upRate == 0L },
    FiltroEstado("checking", "Verificando", "refresh-cw") { it.status == "checking" || it.status == "queued" },
    FiltroEstado("error", "Con error", "triangle-alert") { it.status == "error" },
)

/** Las columnas de la web: título y valor para ordenar. Las filas muestran las elegidas en prefs.fields. */
data class Columna(val id: String, val title: String, val key: (Torrent, Estado) -> Comparable<*>)

val COLUMNS = listOf(
    Columna("pos", "#") { t, _ -> if (t.pos > 0) t.pos else 1_000_000_000L },
    Columna("name", "Nombre") { t, _ -> t.name.lowercase() },
    Columna("tags", "Etiquetas") { t, e -> if (t.tags.isNotEmpty()) e.tagRank(t.tags[0]).toLong() else 10_000_000L },
    Columna("size", "Tamaño") { t, _ -> t.size },
    Columna("progress", "Progreso") { t, _ -> t.progress },
    Columna("status", "Estado") { t, _ ->
        if (t.status == "queued" || t.status == "waiting") t.status + t.queuePos.toString().padStart(4, '0') else t.status
    },
    Columna("down_rate", "Bajada") { t, _ -> t.downRate },
    Columna("up_rate", "Subida") { t, _ -> t.upRate },
    Columna("eta", "Tiempo rest.") { t, _ -> if (t.eta < 0) 1_000_000_000_000L else t.eta },
    Columna("seeds", "Semillas") { t, _ -> t.seeds },
    Columna("peers", "Pares") { t, _ -> t.peers },
    Columna("ratio", "Proporción") { t, _ -> t.ratio },
    Columna("up_total", "Subido") { t, _ -> t.upTotal },
    Columna("done", "Descargado") { t, _ -> t.done },
    Columna("left", "Restante") { t, _ -> t.left },
    Columna("added", "Agregado") { t, _ -> t.added },
    Columna("finished", "Completado") { t, _ -> t.finished },
    Columna("tracker", "Rastreador") { t, _ -> t.tracker },
    Columna("directory", "Carpeta") { t, _ -> t.directory },
    Columna("priority", "Prioridad") { t, _ -> t.priority.toLong() },
    Columna("files", "Archivos") { t, _ -> t.files },
    Columna("private", "Privado") { t, _ -> if (t.private) 1L else 0L },
)
val COLUMN_IDS = COLUMNS.map { it.id }.toSet()
val COL = COLUMNS.associateBy { it.id }

data class Aviso(val texto: String, val error: Boolean = false)

/**
 * Todo lo que la interfaz muestra, como estado de Compose, y lo que hace: lo mismo que state, prefs,
 * refresh() y act() de app.js. Se actualiza cada prefs.interval segundos.
 */
class Estado(val api: Api, val plataforma: Plataforma, private val scope: CoroutineScope) {
    var torrents by mutableStateOf<List<Torrent>>(emptyList()); private set
    var byHash by mutableStateOf<Map<String, Torrent>>(emptyMap()); private set
    var stats by mutableStateOf(Stats()); private set
    var version by mutableStateOf(""); private set
    /** null mientras no llega la primera respuesta. */
    var conectado by mutableStateOf<Boolean?>(null); private set
    var errorConexion by mutableStateOf<String?>(null); private set
    var sinRtorrent by mutableStateOf(false); private set
    var tagConf by mutableStateOf(TagConf()); private set
    var prefs by mutableStateOf(Prefs()); private set
    var general by mutableStateOf(GeneralConf()); private set
    var filtro by mutableStateOf(Filtro())
    var sel by mutableStateOf<Set<String>>(emptySet())
    /** El diálogo abierto, el buscador, la barra lateral y los menús: lo que cierra el botón Atrás. */
    var dialogo by mutableStateOf<Dialogo?>(null)
    var buscando by mutableStateOf(false)
    var cajon by mutableStateOf(false)
    /** El gráfico de velocidad abierto en la mitad de abajo de la lista. */
    var grafico by mutableStateOf(false)
    /** La barra de abajo muestra Al principio/Subir/Bajar/Al final para los elegidos (botón Posición). */
    var moviendo by mutableStateOf(false)
    private val movLock = kotlinx.coroutines.sync.Mutex()

    /** Mover los elegidos en la lista (#), de a un pedido a la vez para que varios toques seguidos no se pisen. */
    fun mover(where: String) {
        val hs = objetivo().map { it.hash }
        if (hs.isEmpty()) return
        scope.launch {
            movLock.lock()
            try { api.post("/api/move", mapOf("hashes" to hs, "where" to where)); refreshOnce() }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; aviso(e.texto(), true) }
            finally { movLock.unlock() }
        }
    }
    var menu by mutableStateOf<String?>(null)
    /** El torrent cuyo detalle está abierto (sus pestañas), o null. */
    var detalle by mutableStateOf<String?>(null)
    var details by mutableStateOf<Detalles?>(null); private set
    var history by mutableStateOf<Historia?>(null); private set
    /** Carpetas cerradas en el árbol de Archivos ("hash:ruta"). */
    var collapsed by mutableStateOf<Set<String>>(emptySet())
    /** El texto de "Filtrar…" de cada tabla del detalle (files, peers, trackers); dura lo que la app abierta. */
    val tableFilter = mutableStateMapOf<String, String>()

    /** La última prueba del puerto desde internet (/api/port-test), y el puerto que se está probando. */
    var portTest by mutableStateOf<Map<String, Any?>?>(null); private set
    var portTesting by mutableStateOf(0L); private set
    private var portAuto = 0L

    /** Avisos breves (los toast de la web); la pantalla los muestra de a uno. */
    val avisos = Channel<Aviso>(Channel.BUFFERED)

    private val collator = Collator.getInstance(Locale("es")).apply { strength = Collator.SECONDARY }
    private var loop: Job? = null
    private var prefsSync: Job? = null
    private val jobErrors = HashSet<String>()

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            try { prefs = Prefs.from(asMap(api.get("/api/prefs")["prefs"])); Formato.decimal = prefs.units == "dec" } catch (e: Exception) { /* las de siempre */ }
            try { tagConf = TagConf.from(api.get("/api/tags")) } catch (e: Exception) { /* sin etiquetas configuradas */ }
            loadGeneral()
            if (plataforma.primerRespaldo) launch { delay(1500); primerRespaldo() }
            while (true) {
                refreshOnce()
                delay(maxOf(1, prefs.interval) * 1000L)
            }
        }
    }

    fun stop() { loop?.cancel(); loop = null }

    /** El botón Atrás: cierra lo de más arriba; false si no había nada (la app sale). */
    fun atras(): Boolean {
        when {
            menu != null -> menu = null
            moviendo -> moviendo = false
            dialogo != null -> dialogo = null
            cajon -> cajon = false
            detalle != null -> detalle = null
            sel.isNotEmpty() -> sel = emptySet()
            buscando -> { buscando = false; filtro = filtro.copy(text = "") }
            else -> return false
        }
        return true
    }

    /** Una vuelta de refresh() de la web, fuera del ritmo de siempre (después de una acción). */
    fun refresh() { scope.launch { refreshOnce() } }

    private suspend fun refreshOnce() {
        try {
            val data = api.get("/api/torrents")
            val list = listOfAny(data["torrents"]).map { Torrent.from(asMap(it)) }
            torrents = list
            byHash = list.associateBy { it.hash }
            stats = Stats.from(asMap(data["stats"]))
            version = str(data["tablero"])
            conectado = true
            errorConexion = null
            sinRtorrent = false
            sel = sel.filterTo(LinkedHashSet()) { it in byHash }
            if (detalle != null && detalle !in byHash) detalle = null
            reportJobs(list)
            autoPortTest()
            loadDetalle()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            conectado = false
            errorConexion = e.texto()
            sinRtorrent = e.sinRtorrent()
        }
    }

    /** Lo del detalle abierto: el gráfico en Velocidad, los detalles en las demás pestañas. */
    suspend fun loadDetalle() {
        val h = detalle ?: return
        try {
            if (prefs.tab == "speed") {
                val key = if (prefs.speedOf == "total") "total" else h
                history = Historia.from(key, api.get("/api/history", if (key == "total") emptyMap() else mapOf("hash" to h)))
            } else if (prefs.tab != "options") {
                val d = Detalles.from(h, api.get("/api/details", mapOf("hash" to h)))
                if (detalle == h) details = d
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // el torrent se quitó entretanto, o rTorrent no contesta: la próxima vuelta
        }
    }

    fun abrirDetalle(h: String) {
        if (details?.hash != h) details = null
        detalle = h
        scope.launch { loadDetalle() }
    }

    /** Ver ya la prioridad elegida en Archivos; la próxima vuelta la confirma. */
    fun marcarPrioridad(indexes: List<Int>, p: Int) {
        val d = details ?: return
        details = d.copy(files = d.files.mapIndexed { i, f -> if (i in indexes) f.copy(priority = p) else f })
    }

    /** Al iniciar, y si rTorrent vuelve en otro puerto, el puerto se prueba una vez solo (autoPortTest de la web). */
    private fun autoPortTest() {
        val s = stats
        if (!s.portOpen || s.port == 0L || portAuto == s.port) return
        portAuto = s.port; portTesting = s.port
        scope.launch { try { probarPuerto() } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e } finally { portTesting = 0 } }
    }

    suspend fun probarPuerto(): Map<String, Any?> = api.post("/api/port-test").also { portTest = it }

    /** portState de la web: (clase, texto) del puerto de entrada. */
    fun portState(): Pair<String, String> {
        val s = stats
        val t = portTest
        return when {
            !s.portOpen -> "bad" to "rTorrent no escucha conexiones entrantes"
            t != null && lng(t["port"]) == s.port -> if (bool(t["reachable"])) "good" to "abierto (probado)" else "bad" to "cerrado (probado)"
            portTesting == s.port -> "" to "probando…"
            s.incoming > 0 || (s.incomingSeen > 0 && System.currentTimeMillis() / 1000 - s.incomingSeen < 1800) -> "good" to "abierto"
            else -> "warn" to "sin conexiones entrantes"
        }
    }

    /** El puerto guardado para el próximo inicio de rTorrent, si es otro que el de ahora; 0 si no. */
    fun portNext(): Long = stats.portNext.takeIf { it != 0L && it != stats.port } ?: 0L

    /** Después de restaurar un respaldo: preferencias, etiquetas y conexiones otra vez desde el servidor. */
    fun recargarTodo() {
        scope.launch {
            try { prefs = Prefs.from(asMap(api.get("/api/prefs")["prefs"])); Formato.decimal = prefs.units == "dec" } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            try { tagConf = TagConf.from(api.get("/api/tags")) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            loadGeneral()
            refreshOnce()
        }
    }

    /** firstBackupFolder de la web: la primera vez, dónde guardar los respaldos. */
    private suspend fun primerRespaldo() {
        if (prefs.backupAsked || dialogo != null) return
        val info = try { api.get("/api/backups") } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; return }
        if (bool(info["chosen"])) { updatePrefs { it.copy(backupAsked = true) }; return }
        if (dialogo == null) dialogo = Dialogo.PrimerRespaldo(info)
    }

    private fun reportJobs(list: List<Torrent>) {
        for (t in list) if (t.jobState == "error" && jobErrors.add(t.hash + t.jobError))
            aviso("No se pudo reubicar ${t.name}: ${t.jobError}", true)
    }

    suspend fun loadGeneral(): GeneralConf? = try { GeneralConf.from(api.get("/api/general")).also { general = it } } catch (e: Exception) { null }

    fun aviso(texto: String, error: Boolean = false) { avisos.trySend(Aviso(texto, error)) }

    /** act() de la web: el pedido, el aviso si salió bien y otra vuelta de la lista. */
    fun act(path: String, body: Map<String, Any?>, ok: String? = null, then: (Map<String, Any?>) -> Unit = {}) {
        scope.launch {
            try {
                val r = api.post(path, body)
                if (ok != null) aviso(ok)
                then(r)
                refreshOnce()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                aviso(e.texto(), true)
            }
        }
    }

    /** Para los diálogos: corre [fn] y avisa el error; devuelve si salió bien. */
    suspend fun intenta(fn: suspend () -> Unit): Boolean = try { fn(); true } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        aviso(e.texto(), true); false
    }

    fun launch(fn: suspend CoroutineScope.() -> Unit) = scope.launch(block = fn)

    // ------------------------------------------------------------ prefs

    fun guardarPrefs(p: Prefs) {
        prefs = p
        Formato.decimal = p.units == "dec"
        prefsSync?.cancel()
        prefsSync = scope.launch {
            delay(1500)
            runCatching { api.post("/api/prefs", mapOf("prefs" to p.toObj())) }
        }
    }

    fun updatePrefs(fn: (Prefs) -> Prefs) = guardarPrefs(fn(prefs))

    // ------------------------------------------------------------ etiquetas

    /** Las configuradas en el orden del usuario, luego las demás que tenga algún torrent. */
    fun allTags(): List<String> {
        val out = LinkedHashSet<String>()
        tagConf.order.filter { it.isNotEmpty() }.forEach { out.add(it) }
        tagConf.tags.keys.sortedWith(collator).forEach { out.add(it) }
        val rest = torrents.flatMap { it.tags }.filter { it !in out }.toSortedSet(collator)
        out.addAll(rest)
        return out.toList()
    }

    fun tagRank(name: String): Int = allTags().indexOf(name).let { if (it < 0) 1_000_000 else it }

    /** saveTagConf de la web, esperando la respuesta (los diálogos). */
    suspend fun guardarEtiquetas(c: TagConf) { tagConf = TagConf.from(api.post("/api/tags", c.toObj())) }

    fun saveTagConf(c: TagConf, then: () -> Unit = {}) {
        scope.launch {
            try { tagConf = TagConf.from(api.post("/api/tags", c.toObj())); then() } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                aviso(e.texto(), true)
            }
        }
    }

    // ------------------------------------------------------------ filtro y orden

    private fun matchTags(t: Torrent, tags: Set<String>): Boolean {
        if (tags.isEmpty()) return true
        val has = { x: String -> if (x == NO_TAG) t.tags.isEmpty() else x in t.tags }
        return if (prefs.tagMode == "all") tags.all(has) else tags.any(has)
    }

    fun filtered(skipStatus: Boolean = false, skipTags: Boolean = false, skipTracker: Boolean = false): List<Torrent> {
        val f = filtro
        val st = STATUS_FILTERS.find { it.id == f.status } ?: STATUS_FILTERS[0]
        val words = f.text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return torrents.filter { t ->
            (skipStatus || st.test(t)) && (skipTags || matchTags(t, f.tags)) &&
                (skipTracker || f.tracker == null || t.tracker == f.tracker) &&
                words.all { t.name.lowercase().contains(it) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun sorted(list: List<Torrent>): List<Torrent> {
        val c = COL[prefs.sort.col] ?: COL.getValue("added")
        val d = prefs.sort.dir
        return list.sortedWith { a, b ->
            val x = c.key(a, this); val y = c.key(b, this)
            val r = if (x is String && y is String) collator.compare(x, y) else (x as Comparable<Any>).compareTo(y)
            if (r != 0) r * d else collator.compare(a.name, b.name)
        }
    }

    fun rows(): List<Torrent> = sorted(filtered())

    /** Un toque elige solo esa etiqueta (o la quita); con [multi] la suma o quita del filtro. */
    fun toggleTagFilter(name: String, multi: Boolean) {
        val f = filtro.tags
        filtro = filtro.copy(tags = when {
            multi -> if (name in f) f - name else f + name
            f.size == 1 && name in f -> emptySet()
            else -> setOf(name)
        })
    }

    fun selected(): List<Torrent> = sel.mapNotNull { byHash[it] }

    /** Sobre qué actúan el menú y la barra de acciones: el torrent del detalle abierto, o los elegidos. */
    fun objetivo(): List<Torrent> = detalle?.let { listOfNotNull(byHash[it]) } ?: selected()

    /** Cambia de pestaña en el detalle y trae lo que esa pestaña muestra. */
    fun pestana(tab: String) {
        updatePrefs { it.copy(tab = tab) }
        scope.launch { loadDetalle() }
    }

    /** "Abrir carpeta" de la web: del torrent, de uno de sus archivos ([index]) o de una carpeta suya ([dir]). */
    fun abrirCarpeta(hash: String, index: Int? = null, dir: String? = null) {
        scope.launch {
            intenta {
                val body = linkedMapOf<String, Any?>("hash" to hash)
                if (index != null) body["index"] = index else if (dir != null) body["dir"] = dir
                val r = api.post("/api/open-folder", body)
                if (bool(r["android"])) plataforma.abrirCarpeta(str(r["folder"])) else aviso("Carpeta: " + str(r["folder"]))
            }
        }
    }
}
