package cl.tablero.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * El detalle de un torrent (el panel de abajo de la web): sus siete pestañas a pantalla completa.
 * Se abre tocando una fila sin nada elegido; Atrás vuelve a la lista.
 */

val TABS = listOf(
    "general" to "General", "files" to "Archivos", "peers" to "Pares", "trackers" to "Rastreadores",
    "pieces" to "Piezas", "speed" to "Velocidad", "options" to "Opciones",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarraDetalle(estado: Estado, t: Torrent, menu: @Composable () -> Unit) {
    val c = LocalColores.current
    TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = c.panel, titleContentColor = c.text,
        navigationIconContentColor = c.text, actionIconContentColor = c.text),
        navigationIcon = { IconButton(onClick = { estado.detalle = null }) { Icono("arrow-left", size = 22.dp, desc = "Volver a la lista") } },
        title = {
            Column {
                Text(t.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(statusText(t), fontSize = 12.sp, color = statusColor(c, t.status))
                    Text(Formato.pct(t.progress), fontSize = 12.sp, color = c.dim)
                    if (t.downRate > 0) Text("↓ " + Formato.rate(t.downRate), fontSize = 12.sp, color = c.down)
                    if (t.upRate > 0) Text("↑ " + Formato.rate(t.upRate), fontSize = 12.sp, color = c.up)
                }
            }
        },
        actions = {
            Box {
                IconButton(onClick = { estado.menu = "torrents" }) { Icono("ellipsis-vertical", size = 22.dp, desc = "Más") }
                menu()
            }
        })
}

/** Las pestañas y lo de la elegida. */
@Composable
fun DetalleCuerpo(estado: Estado, t: Torrent) {
    val c = LocalColores.current
    val tab = estado.prefs.tab.takeIf { k -> TABS.any { it.first == k } } ?: "general"
    val idx = TABS.indexOfFirst { it.first == tab }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = idx, containerColor = c.panel, contentColor = c.text, edgePadding = 4.dp, divider = { HorizontalDivider(color = c.line) }) {
            TABS.forEachIndexed { i, (k, n) ->
                Tab(selected = i == idx, onClick = { estado.pestana(k) }, selectedContentColor = c.accent, unselectedContentColor = c.dim,
                    text = { Text(n, fontSize = 14.sp) })
            }
        }
        val d = estado.details?.takeIf { it.hash == t.hash }
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                "general" -> General(estado, t, d)
                "speed" -> Velocidad(estado, t)
                "options" -> Opciones(estado, t)
                else -> if (d == null) Placeholder("Cargando…") else when (tab) {
                    "files" -> Archivos(estado, t, d)
                    "peers" -> Pares(estado, d)
                    "trackers" -> Rastreadores(estado, t, d)
                    "pieces" -> Piezas(t, d)
                }
            }
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = LocalColores.current.dim, fontSize = 14.sp)
    }
}

/** Botones de a uno o varios, como el .seg de la web. */
@Composable
fun Seg(options: List<Pair<String, String>>, value: String, onPick: (String) -> Unit) {
    val c = LocalColores.current
    Row(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, c.line, RoundedCornerShape(6.dp))) {
        for ((v, n) in options) {
            val on = v == value
            Text(n, fontSize = 13.sp, color = if (on) Color.White else c.text,
                modifier = Modifier.background(if (on) c.accent else c.panel2).clickable { onPick(v) }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** Un botón chico con ícono y texto (toolBtn de la web). */
@Composable
fun BotonHerr(icon: String, label: String? = null, on: Boolean = false, desc: String? = null, onClick: () -> Unit) {
    val c = LocalColores.current
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) c.sel else c.panel2).clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icono(icon, size = 16.dp, tint = c.text, desc = desc ?: label)
        if (label != null) Text(label, fontSize = 13.sp, color = c.text)
    }
}

// ------------------------------------------------------------ General

private val AVAIL_TIP = "Copias completas del torrent entre los pares conectados: el número entero es cuántas copias hay de la pieza más escasa y la fracción, qué parte de las piezas tiene una copia más."
private const val AVAIL_EST = "Estimada a partir del avance de los pares conectados."

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun General(estado: Estado, t: Torrent, d: Detalles?) {
    val c = LocalColores.current
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Descargado", fontSize = 13.sp, color = c.dim, modifier = Modifier.width(110.dp))
            MapaPiezas(t, d, Modifier.weight(1f).height(16.dp))
            Text(Formato.pct(t.progress), fontSize = 13.sp, modifier = Modifier.width(60.dp))
        }
        val av = d?.availability
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Disponibilidad", fontSize = 13.sp, color = c.dim, modifier = Modifier.width(110.dp))
            Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(3.dp)).background(c.barBg)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((av ?: 0.0).coerceIn(0.0, 1.0).toFloat()).background(c.good.copy(alpha = 0.8f)))
            }
            Text(if (av == null) "…" else Formato.n2(av), fontSize = 13.sp, modifier = Modifier.width(60.dp))
        }
        Hint(if (d != null && !d.availabilityEstimated) AVAIL_TIP else AVAIL_EST)
        HorizontalDivider(color = c.line)
        @Composable fun dato(k: String, v: String, color: Color = c.text) = Dato(k) { Text(v, fontSize = 14.sp, color = color) }
        dato("Estado", statusText(t), statusColor(c, t.status))
        dato("Tamaño", "${Formato.size(t.size)} (${Formato.n0(t.files)} archivo${if (t.files == 1L) "" else "s"})")
        dato("Descargado", Formato.size(t.done))
        dato("Subido", "${Formato.size(t.upTotal)} · proporción ${Formato.n2(t.ratio)}")
        dato("Bajada", Formato.rate(t.downRate).ifEmpty { "0 B/s" }, c.down)
        dato("Subida", Formato.rate(t.upRate).ifEmpty { "0 B/s" }, c.up)
        dato("Tiempo rest.", Formato.eta(t.eta).ifEmpty { "—" })
        dato("Semillas / pares", "${t.seeds} / ${t.peers}")
        dato("Agregado", Formato.date(t.added).ifEmpty { "—" })
        dato("Completado", Formato.date(t.finished).ifEmpty { "—" })
        dato("Creado", Formato.date(t.created).ifEmpty { "—" })
        dato("Prioridad", PRIO[t.priority] ?: "")
        Dato("Etiquetas") {
            if (t.tags.isEmpty()) Text("—", fontSize = 14.sp)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (x in t.tags) Chip(estado, x)
            }
        }
        Dato("Carpeta") {
            Column {
                Text(d?.basePath ?: t.directory, fontSize = 14.sp)
                TextButton(onClick = { estado.abrirCarpeta(t.hash) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Icono("folder-open", size = 16.dp); Spacer(Modifier.width(6.dp)); Text("Abrir")
                }
            }
        }
        Dato("Identificador") {
            Column {
                SelectionContainer { Text(t.hash, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
                Hint("Huella única del torrent (info-hash), la misma en toda la red")
            }
        }
        if (t.message.isNotEmpty()) Dato("Mensaje") {
            Column {
                Text(messageText(t), fontSize = 14.sp)
                TextButton(onClick = { clip.setText(AnnotatedString(messageText(t))); estado.aviso("Copiado") }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Icono("copy", size = 16.dp); Spacer(Modifier.width(6.dp)); Text("Copiar")
                }
            }
        }
        if (d != null && d.chunks > 0) dato("Piezas", "${Formato.n0(d.chunks)} × ${Formato.size(d.chunkSize)}")
        dato("Privado", if (t.private) "Sí" else "No")
    }
}

@Composable
private fun Dato(k: String, v: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, fontSize = 13.sp, color = LocalColores.current.dim, modifier = Modifier.width(130.dp).padding(top = 1.dp))
        Box(Modifier.weight(1f)) { v() }
    }
}

/** drawPieces de la web: cada columna de puntos muestra cuántas de sus piezas están; sin mapa, el avance. */
@Composable
private fun MapaPiezas(t: Torrent, d: Detalles?, modifier: Modifier) {
    val c = LocalColores.current
    val fill = if (t.complete) c.good else c.down
    Canvas(modifier.clip(RoundedCornerShape(3.dp))) {
        drawRect(c.barBg)
        val n = d?.chunks ?: 0
        if (d == null || n <= 0 || d.bitfield.isEmpty()) {
            drawRect(fill, size = Size(size.width * t.progress.toFloat(), size.height)); return@Canvas
        }
        val w = size.width.toInt()
        for (x in 0 until w) {
            val a = (x.toLong() * n / w).toInt()
            val b = max(a + 1, ((x + 1).toLong() * n / w).toInt())
            var done = 0
            for (i in a until b) if (d.have(i)) done++
            if (done == 0) continue
            drawRect(fill, Offset(x.toFloat(), 0f), Size(1f, size.height), alpha = done.toFloat() / (b - a))
        }
    }
}

// ------------------------------------------------------------ tablas: orden y filtro

/** Una columna de las tablas del detalle: [get] ordena, [text] (o get) filtra. */
class ColT<T>(val id: String, val title: String, val get: (T) -> Any?, val text: ((T) -> String)? = null)

private val tcol = Collator.getInstance(Locale("es")).apply { strength = Collator.SECONDARY }
private val chunks = Regex("\\d+|\\D+")

/** localeCompare(..., "es", {numeric: true}) de la web, y los números como números. */
fun tcmp(a: Any?, b: Any?): Int {
    if (a is Number && b is Number) return a.toDouble().compareTo(b.toDouble())
    val x = chunks.findAll(str(a)).map { it.value }.toList(); val y = chunks.findAll(str(b)).map { it.value }.toList()
    for (i in 0 until min(x.size, y.size)) {
        val p = x[i]; val q = y[i]
        val r = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else tcol.compare(p, q)
        if (r != 0) return r
    }
    return x.size - y.size
}

fun tnorm(s: String): String = Normalizer.normalize(s.lowercase(Locale("es")), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

fun <T> tableRows(estado: Estado, key: String, cols: List<ColT<T>>, rows: List<T>): List<T> {
    val q = tnorm(estado.tableFilter[key] ?: "")
    var out = if (q.isEmpty()) rows else rows.filter { r -> cols.any { c -> tnorm(c.text?.invoke(r) ?: str(c.get(r))).contains(q) } }
    val s = estado.prefs.tableSort[key]
    val c = s?.let { o -> cols.find { it.id == o.col } }
    if (s != null && c != null) out = out.sortedWith { a, b -> tcmp(c.get(a), c.get(b)) * s.dir }
    return out
}

/** Un toque ordena ascendente, otro descendente y el tercero vuelve al orden de siempre. */
fun nextSort(estado: Estado, key: String, col: String) {
    estado.updatePrefs { p ->
        val s = p.tableSort[key]
        val n = if (s == null || s.col != col) Orden(col, 1) else if (s.dir > 0) Orden(col, -1) else null
        p.copy(tableSort = if (n == null) p.tableSort - key else p.tableSort + (key to n))
    }
}

/** Encima de cada tabla: lo propio de la tabla, "Filtrar…", el orden y "Vista por defecto". */
@Composable
private fun <T> Herramientas(estado: Estado, key: String, cols: List<ColT<T>>, extra: @Composable RowScope.() -> Unit = {}) {
    val c = LocalColores.current
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = extra)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(c.bg).border(1.dp, c.line, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icono("search", size = 16.dp, tint = c.dim)
                val v = estado.tableFilter[key] ?: ""
                Box(Modifier.weight(1f)) {
                    if (v.isEmpty()) Text("Filtrar…", fontSize = 14.sp, color = c.dim)
                    BasicTextField(v, { estado.tableFilter[key] = it }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = c.text),
                        cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                }
                if (v.isNotEmpty()) Box(Modifier.clickable { estado.tableFilter.remove(key) }) { Icono("x", size = 16.dp, tint = c.dim, desc = "Borrar el filtro") }
            }
            Box {
                val s = estado.prefs.tableSort[key]
                BotonHerr("arrow-up-down", cols.find { it.id == s?.col }?.title?.let { it + if (s!!.dir > 0) " ▲" else " ▼" }, on = s != null, desc = "Ordenar") { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = c.panel2) {
                    Text("Ordenar por", fontSize = 12.sp, color = c.dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    for (col in cols) DropdownMenuItem(text = { Text(col.title) }, leadingIcon = {
                        if (s?.col == col.id) Icono(if (s.dir > 0) "arrow-up" else "arrow-down", size = 16.dp) else Spacer(Modifier.size(16.dp))
                    }, onClick = { nextSort(estado, key, col.id); menu = false })
                    if (s != null || estado.tableFilter[key].orEmpty().isNotEmpty()) {
                        HorizontalDivider(color = c.line)
                        DropdownMenuItem(text = { Text("Vista por defecto") }, leadingIcon = { Icono("rotate-ccw", size = 16.dp) }, onClick = {
                            menu = false; estado.tableFilter.remove(key)
                            estado.updatePrefs { it.copy(tableSort = it.tableSort - key) }
                        })
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ Archivos

val FILE_PRIO = listOf(2 to "Alta", 1 to "Normal", 0 to "No descargar")
val MEDIA_EXT = Regex("\\.(mp4|m4v|mkv|webm|avi|mov|wmv|flv|ts|m2ts|mpg|mpeg|3gp|mp3|m4a|aac|flac|ogg|oga|opus|wav|wma)$", RegexOption.IGNORE_CASE)

/** Un archivo o una carpeta del árbol: la carpeta suma tamaños y bytes listos; su prioridad es la común, o null si es mixta. */
class FilaArchivo(
    val name: String, val path: String, val size: Long, val progress: Double, val priority: Int?,
    val index: Int = -1, val dir: Boolean = false, val idx: List<Int> = listOf(index), val depth: Int = 0,
)

private class Nodo(val name: String, val path: String) {
    val dirs = LinkedHashMap<String, Nodo>()
    val files = ArrayList<Pair<Archivo, Int>>()
    var size = 0L; var doneB = 0.0; val idx = ArrayList<Int>()
    var prio: Int? = null; var prioSet = false
    fun mix(p: Int?) { if (!prioSet) { prio = p; prioSet = true } else if (prio != p) prio = null }
    fun sum() {
        for (d in dirs.values) { d.sum(); size += d.size; doneB += d.doneB; idx.addAll(d.idx); mix(d.prio) }
        for ((f, i) in files) { size += f.size; doneB += f.size * f.progress; idx.add(i); mix(f.priority) }
    }
    fun fila(depth: Int) = FilaArchivo(name, path, size, if (size > 0) doneB / size else 1.0, prio, dir = true, idx = idx.toList(), depth = depth)
}

private fun fileTree(files: List<Archivo>, hideSkipped: Boolean): Nodo {
    val root = Nodo("", "")
    files.forEachIndexed { i, f ->
        if (hideSkipped && f.priority == 0) return@forEachIndexed
        val parts = f.path.split("/")
        var node = root
        for (k in 0 until parts.size - 1) node = node.dirs.getOrPut(parts[k]) { Nodo(parts[k], parts.subList(0, k + 1).joinToString("/")) }
        node.files.add(f to i)
    }
    root.sum()
    return root
}

private fun allDirs(files: List<Archivo>): Set<String> {
    val out = HashSet<String>()
    for (f in files) { val p = f.path.split("/"); for (k in 1 until p.size) out.add(p.subList(0, k).joinToString("/")) }
    return out
}

private val FILE_COLS = listOf<ColT<FilaArchivo>>(
    ColT("name", "Archivo", { it.name }, { it.path }),
    ColT("size", "Tamaño", { it.size }),
    ColT("progress", "Progreso", { it.progress }),
    ColT("priority", "Prioridad", { it.priority ?: -1 }),
)

@Composable
private fun Archivos(estado: Estado, t: Torrent, d: Detalles) {
    val c = LocalColores.current
    val p = estado.prefs
    val files = d.files
    val skipped = files.count { it.priority == 0 }
    val q = tnorm(estado.tableFilter["files"] ?: "")
    fun match(f: Archivo) = q.isEmpty() || tnorm(f.path).contains(q)
    val fs = p.tableSort["files"]
    val fcol = fs?.let { o -> FILE_COLS.find { it.id == o.col } }
    fun order(list: List<FilaArchivo>): List<FilaArchivo> =
        if (fcol != null) list.sortedWith { a, b -> tcmp(fcol.get(a), fcol.get(b)) * fs.dir } else list.sortedWith { a, b -> tcmp(a.name, b.name) }
    val rows = ArrayList<FilaArchivo>()
    val pre = t.hash + ":"
    if (p.filesTree) {
        val shown = files.indices.filter { match(files[it]) }.toSet()
        fun walk(n: Nodo, depth: Int) {
            val dirs = n.dirs.values.filter { dd -> dd.idx.any { it in shown } }
            for (dd in order(dirs.map { it.fila(depth) })) {
                rows.add(dd)
                if (q.isNotEmpty() || (pre + dd.path) !in estado.collapsed) walk(n.dirs.getValue(dd.name), depth + 1)
            }
            val fl = n.files.filter { it.second in shown }.map { (f, i) -> FilaArchivo(f.name, f.path, f.size, f.progress, f.priority, i, depth = depth) }
            rows.addAll(order(fl))
        }
        walk(fileTree(files, p.hideSkipped), 0)
    } else {
        val list = files.mapIndexedNotNull { i, f ->
            if ((p.hideSkipped && f.priority == 0) || !match(f)) null else FilaArchivo(f.path, f.path, f.size, f.progress, f.priority, i)
        }
        rows.addAll(if (fcol != null) list.sortedWith { a, b -> tcmp(fcol.get(a), fcol.get(b)) * fs.dir } else list)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Herramientas(estado, "files", FILE_COLS) {
                Seg(listOf("tree" to "Árbol", "list" to "Lista"), if (p.filesTree) "tree" else "list") { v -> estado.updatePrefs { it.copy(filesTree = v == "tree") } }
                BotonHerr(if (p.hideSkipped) "eye" else "eye-off", (if (p.hideSkipped) "Mostrar" else "Ocultar") + " los que no se descargarán" + (if (skipped > 0) " ($skipped)" else ""),
                    on = p.hideSkipped) { estado.updatePrefs { it.copy(hideSkipped = !it.hideSkipped) } }
                if (p.filesTree) {
                    BotonHerr("chevrons-up-down", desc = "Expandir todo") { estado.collapsed = estado.collapsed.filterTo(HashSet()) { !it.startsWith(pre) } }
                    BotonHerr("chevrons-down-up", desc = "Contraer todo") { estado.collapsed = estado.collapsed + allDirs(files).map { pre + it } }
                }
            }
        }
        if (rows.isEmpty()) item { Placeholder(if (q.isNotEmpty()) "Ningún archivo coincide con el filtro" else "Todos los archivos están marcados como \"No descargar\"") }
        items(rows, key = { (if (it.dir) "d:" else "f:") + it.path + ":" + it.index }) { f -> FilaDeArchivo(estado, t, f, p.filesTree, pre) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilaDeArchivo(estado: Estado, t: Torrent, f: FilaArchivo, tree: Boolean, pre: String) {
    val c = LocalColores.current
    var menu by remember { mutableStateOf(false) }
    val playable = !f.dir && t.sequential != null && MEDIA_EXT.containsMatchIn(f.path)
    val open = (pre + f.path) !in estado.collapsed
    Box {
        Row(Modifier.fillMaxWidth().alpha(if (!f.dir && f.priority == 0) 0.55f else 1f)
            .combinedClickable(onClick = {
                if (f.dir) estado.collapsed = if (open) estado.collapsed + (pre + f.path) else estado.collapsed - (pre + f.path)
                else menu = true
            }, onLongClick = { menu = true })
            .padding(start = (12 + (if (tree) f.depth * 16 else 0)).dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (f.dir) Row(verticalAlignment = Alignment.CenterVertically) {
                Icono(if (open) "chevron-down" else "chevron-right", size = 16.dp, tint = c.dim); Icono("folder", size = 18.dp, tint = c.dim)
            } else if (playable) IconButton(onClick = { reproducir(estado, t, f) }, modifier = Modifier.size(32.dp)) {
                Icono("play", size = 18.dp, tint = c.accent, desc = "Reproducir mientras se descarga")
            } else if (tree) Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(f.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Formato.size(f.size), fontSize = 12.sp, color = c.dim, modifier = Modifier.width(72.dp))
                    BarraArchivo(f.progress, Modifier.weight(1f))
                    PrioArchivo(f.priority) { v -> prioridad(estado, t, f.idx, v) }
                }
            }
        }
        DropdownMenu(menu, { menu = false }, containerColor = c.panel2) {
            DropdownMenuItem(text = { Text(if (f.dir) "Abrir carpeta" else "Mostrar en la carpeta") }, leadingIcon = { Icono("folder-open", size = 16.dp) }, onClick = {
                menu = false
                if (f.dir) estado.abrirCarpeta(t.hash, dir = f.path) else estado.abrirCarpeta(t.hash, index = f.index)
            })
            if (playable) DropdownMenuItem(text = { Text("Reproducir mientras se descarga") }, leadingIcon = { Icono("play", size = 16.dp) }, onClick = {
                menu = false; reproducir(estado, t, f)
            })
            HorizontalDivider(color = c.line)
            Text("Prioridad", fontSize = 12.sp, color = c.dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            for ((v, n) in FILE_PRIO) DropdownMenuItem(text = { Text(n) }, leadingIcon = {
                if (f.priority == v) Icono("check", size = 16.dp) else Spacer(Modifier.size(16.dp))
            }, onClick = { menu = false; prioridad(estado, t, f.idx, v) })
        }
    }
    HorizontalDivider(color = c.line.copy(alpha = 0.5f))
}

private fun prioridad(estado: Estado, t: Torrent, idx: List<Int>, v: Int) {
    estado.marcarPrioridad(idx, v)
    estado.act("/api/file-priority", mapOf("hash" to t.hash, "indexes" to idx, "priority" to v))
}

private fun reproducir(estado: Estado, t: Torrent, f: FilaArchivo) {
    val name = f.path.substringAfterLast('/')
    try { estado.plataforma.reproducir(estado.api.streamUrl(t.hash, f.index, name), name) } catch (e: Exception) { estado.aviso(e.texto(), true); return }
    estado.aviso("Descarga en orden activada: el reproductor espera las piezas que falten")
    estado.launch { delay(1500); estado.refresh() }
}

@Composable
private fun BarraArchivo(p: Double, modifier: Modifier) {
    val c = LocalColores.current
    Box(modifier.height(15.dp).clip(RoundedCornerShape(3.dp)).background(c.barBg), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).align(Alignment.CenterStart).background((if (p >= 1) c.good else c.down).copy(alpha = 0.8f)))
        Text(Formato.pct(p), fontSize = 10.sp, color = c.text, fontWeight = FontWeight.Medium)
    }
}

/** La lista de prioridad de cada archivo o carpeta; "Mixta" cuando sus archivos no coinciden. */
@Composable
private fun PrioArchivo(prio: Int?, onPick: (Int) -> Unit) {
    val c = LocalColores.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.width(118.dp).clip(RoundedCornerShape(5.dp)).background(c.panel2).clickable { open = true }.padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(FILE_PRIO.find { it.first == prio }?.second ?: "Mixta", fontSize = 12.sp, color = if (prio == 0) c.dim else c.text,
                maxLines = 1, modifier = Modifier.weight(1f))
            Icono("chevron-down", size = 14.dp, tint = c.dim)
        }
        DropdownMenu(open, { open = false }, containerColor = c.panel2) {
            for ((v, n) in FILE_PRIO) DropdownMenuItem(text = { Text(n) }, leadingIcon = {
                if (prio == v) Icono("check", size = 16.dp) else Spacer(Modifier.size(16.dp))
            }, onClick = { open = false; if (v != prio) onPick(v) })
        }
    }
}

// ------------------------------------------------------------ Pares y Rastreadores

private fun ipKey(a: String): String = if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(a)) a.split(".").joinToString(".") { it.padStart(3, '0') } else a

private val PEER_COLS = listOf<ColT<Par>>(
    ColT("address", "Dirección", { ipKey(it.address) }, { "${it.address}:${it.port}" }),
    ColT("client", "Cliente", { it.client }),
    ColT("progress", "Progreso", { it.progress }),
    ColT("down", "Bajada", { it.downRate }),
    ColT("up", "Subida", { it.upRate }),
    ColT("flags", "Indicadores", { (if (it.incoming) "entrante" else "saliente") + if (it.encrypted) " cifrado" else "" }),
)

private val TRACKER_COLS = listOf<ColT<Rastreador>>(
    ColT("url", "URL", { it.url }),
    ColT("enabled", "Activo", { if (it.enabled) "Sí" else "No" }),
    ColT("seeds", "Semillas", { it.seeds }),
    ColT("leechers", "Pares", { it.leechers }),
    ColT("downloaded", "Descargas", { it.downloaded }),
)

@Composable
private fun Pares(estado: Estado, d: Detalles) {
    val c = LocalColores.current
    if (d.peers.isEmpty()) { Placeholder("Sin pares conectados"); return }
    val rows = tableRows(estado, "peers", PEER_COLS, d.peers)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Herramientas(estado, "peers", PEER_COLS) {
                Text(if (rows.size == d.peers.size) "${d.peers.size} pares" else "${rows.size} de ${d.peers.size} pares", fontSize = 13.sp, color = c.dim)
            }
        }
        if (rows.isEmpty()) item { Placeholder("Ningún par coincide con el filtro") }
        items(rows, key = { it.address + ":" + it.port }) { p ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.address}:${p.port}", fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Formato.pct(p.progress), fontSize = 13.sp, color = c.dim)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(p.client, fontSize = 12.sp, color = c.dim, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.downRate != 0L) Text("↓ " + Formato.rate(p.downRate), fontSize = 12.sp, color = c.down)
                    if (p.upRate != 0L) Text("↑ " + Formato.rate(p.upRate), fontSize = 12.sp, color = c.up)
                    if (p.encrypted) Icono("lock", size = 13.dp, tint = c.dim, desc = "cifrado")
                    Text(if (p.incoming) "entrante" else "saliente", fontSize = 12.sp, color = c.dim)
                }
            }
            HorizontalDivider(color = c.line.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun Rastreadores(estado: Estado, t: Torrent, d: Detalles) {
    val c = LocalColores.current
    val rows = tableRows(estado, "trackers", TRACKER_COLS, d.trackers)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Herramientas(estado, "trackers", TRACKER_COLS) {
                BotonHerr("plus", "Agregar rastreadores…") { estado.dialogo = Dialogo.Rastreadores(t.hash) }
            }
        }
        if (rows.isEmpty()) item { Placeholder(if (d.trackers.isNotEmpty()) "Ningún rastreador coincide con el filtro" else "Sin rastreadores") }
        items(rows, key = { it.url }) { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SelectionContainer { Text(r.url, fontSize = 14.sp, color = if (r.enabled) c.text else c.dim) }
                Text("Activo: ${if (r.enabled) "Sí" else "No"} · Semillas ${r.seeds} · Pares ${r.leechers} · Descargas ${r.downloaded}", fontSize = 12.sp, color = c.dim)
            }
            HorizontalDivider(color = c.line.copy(alpha = 0.5f))
        }
    }
}

/** addTrackerDialog de la web. */
@Composable
fun RastreadoresDialogo(estado: Estado, hash: String) {
    val t = estado.byHash[hash] ?: run { estado.dialogo = null; return }
    var urls by remember { mutableStateOf("") }
    Ventana(estado, "Agregar rastreadores", "Agregar", onOk = {
        val list = urls.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (list.isEmpty()) { estado.aviso("Escribe al menos una dirección", true); false }
        else estado.intenta {
            val r = estado.api.post("/api/add-trackers", mapOf("hash" to hash, "urls" to list))
            val n = lng(r["added"])
            estado.aviso(if (n > 0) "$n rastreador${if (n == 1L) "" else "es"} agregado${if (n == 1L) "" else "s"}" else "El torrent ya tenía esos rastreadores")
            estado.refresh()
        }
    }) {
        NombresTorrents(listOf(t))
        OutlinedTextField(urls, { urls = it }, minLines = 4, label = { Text("Rastreadores") },
            placeholder = { Text("udp://tracker.ejemplo.org:1337/announce") }, modifier = Modifier.fillMaxWidth())
        Hint("Una dirección por línea (http, https o udp). Las que el torrent ya tiene se omiten. Quedan guardadas en la sesión de rTorrent.")
    }
}

// ------------------------------------------------------------ Piezas

class CatPieza(val name: String, val tip: String)

val PIECE_CATS = linkedMapOf(
    "done" to CatPieza("Completa", "Pieza descargada y verificada. Ya está en tu disco y puedes compartirla con otros."),
    "partial" to CatPieza("Parcial", "El cuadro agrupa varias piezas y solo algunas están completas. Toca el mapa para ver el detalle."),
    "avail" to CatPieza("Disponible en pares", "Te falta esta pieza, pero al menos un par conectado la tiene, así que se puede descargar ahora."),
    "nosource" to CatPieza("Sin fuentes", "Te falta esta pieza y ningún par conectado la tiene. El torrent no se puede completar hasta que aparezca alguien con ella."),
    "missing" to CatPieza("Falta", "Te falta esta pieza. Con el torrent detenido o en pausa no hay pares conectados, así que no se sabe quién la tiene."),
    "skipped" to CatPieza("Omitida", "Pertenece solo a archivos marcados como “No descargar” en la pestaña Archivos, así que no se descargará."),
)
private const val PIECE_CELL = 8f
private const val PIECE_GAP = 2f
private const val PIECE_ROWS = 48

/** pieceData de la web: la categoría de cada pieza. */
private fun categorias(d: Detalles): Array<String> {
    val n = d.chunks.toInt()
    val skip = BooleanArray(n)
    for (f in d.files) if (f.priority == 0) for (i in f.first until min(f.last, n.toLong())) skip[i.toInt()] = true
    for (f in d.files) if (f.priority != 0) for (i in f.first until min(f.last, n.toLong())) skip[i.toInt()] = false
    return Array(n) { i ->
        if (d.have(i)) "done" else if (skip[i]) "skipped" else if (d.seen.isEmpty()) "missing" else if ((d.copies(i) ?: 0) > 0) "avail" else "nosource"
    }
}

fun pieceColor(c: Colores, cat: String): Color = when (cat) {
    "done" -> c.pcDone; "partial" -> c.pcPartial; "avail" -> c.pcAvail; "nosource" -> c.pcNosource; "missing" -> c.pcMissing; else -> c.pcSkipped
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Piezas(t: Torrent, d: Detalles) {
    val c = LocalColores.current
    if (d.chunks <= 0 || d.bitfield.isEmpty()) { Placeholder("rTorrent aún no tiene el mapa de piezas de este torrent"); return }
    val cats = remember(d) { categorias(d) }
    val n = cats.size
    var tocada by remember(t.hash) { mutableStateOf<Int?>(null) }
    var ayuda by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value - 32f
        val pitch = PIECE_CELL + PIECE_GAP
        val cols = max(1, floor((w + PIECE_GAP) / pitch).toInt())
        val k = ceil(n.toDouble() / (cols * PIECE_ROWS)).toInt().coerceAtLeast(1)
        val cells = ceil(n.toDouble() / k).toInt()
        val rowsN = ceil(cells.toDouble() / cols).toInt()
        val cellCat = remember(cats, k) {
            Array(cells) { cc ->
                val a = cc * k; val b = min(n, a + k)
                var done = 0; val tally = HashMap<String, Int>()
                for (i in a until b) { val x = cats[i]; if (x == "done") done++ else tally[x] = (tally[x] ?: 0) + 1 }
                if (done == b - a) "done" else if (done > 0) "partial" else tally.maxByOrNull { it.value }!!.key
            }
        }
        val counts = remember(cats) { cats.groupingBy { it }.eachCount() }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val shown = listOf("done") + (if (k > 1) listOf("partial") else emptyList()) + (if (d.seen.isNotEmpty()) listOf("avail", "nosource") else listOf("missing")) + "skipped"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (cat in shown) Row(Modifier.clickable { ayuda = if (ayuda == cat) null else cat }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(pieceColor(c, cat)).border(if (cat == "skipped") 1.dp else 0.dp, c.line, RoundedCornerShape(2.dp)))
                    Text(PIECE_CATS.getValue(cat).name, fontSize = 13.sp)
                    if (cat != "partial") Text(Formato.n0((counts[cat] ?: 0).toLong()), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            ayuda?.let { Hint(PIECE_CATS.getValue(it).tip) }
            Text("${Formato.n0(n.toLong())} piezas de ${Formato.size(d.chunkSize)}" + (if (k > 1) " · cada cuadro = $k piezas" else "") +
                " · disponibilidad ${Formato.n2(d.availability ?: 0.0)}", fontSize = 12.sp, color = c.dim)
            Canvas(Modifier.width(w.dp).height((rowsN * pitch).dp).pointerInput(cols, k, cells) {
                detectTapGestures { o ->
                    val col = (o.x / pitch.dp.toPx()).toInt(); val row = (o.y / pitch.dp.toPx()).toInt()
                    val cc = row * cols + col
                    tocada = if (col < cols && cc in 0 until cells && cc != tocada) cc else null
                }
            }) {
                val px = pitch.dp.toPx(); val cell = PIECE_CELL.dp.toPx(); val r = CornerRadius(1.5.dp.toPx())
                for (cc in 0 until cells) {
                    val x = (cc % cols) * px; val y = (cc / cols) * px
                    drawRoundRect(pieceColor(c, cellCat[cc]), Offset(x, y), Size(cell, cell), r)
                    if (cellCat[cc] == "skipped") drawRoundRect(c.line, Offset(x, y), Size(cell, cell), r, style = Stroke(1f))
                    if (cc == tocada) drawRoundRect(c.text, Offset(x - 1, y - 1), Size(cell + 2, cell + 2), r, style = Stroke(2f))
                }
            }
            tocada?.takeIf { it < cells }?.let { cc ->
                val a = cc * k; val b = min(n, a + k)
                var done = 0; var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
                for (i in a until b) { if (d.have(i)) done++; d.copies(i)?.let { s -> lo = min(lo, s); hi = max(hi, s) } }
                val files = d.files.filter { it.first < b && it.last > a }.map { it.name }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.panel2).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (b - a > 1) "Piezas ${Formato.n0(a + 1L)}–${Formato.n0(b.toLong())}" else "Pieza ${Formato.n0(a + 1L)}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(pieceColor(c, cellCat[cc])))
                        Text(PIECE_CATS.getValue(cellCat[cc]).name + if (b - a > 1) " · $done de ${b - a} completas" else "", fontSize = 13.sp)
                    }
                    if (lo != Int.MAX_VALUE) Text("Copias en pares conectados: ${if (lo == hi) "$lo" else "$lo–$hi"}", fontSize = 13.sp)
                    if (files.isNotEmpty()) Text(files.take(3).joinToString(", ") + if (files.size > 3) " y ${files.size - 3} más" else "", fontSize = 12.sp, color = c.dim)
                }
            }
        }
    }
}

// ------------------------------------------------------------ Velocidad

val SPEED_RANGES = listOf(300 to "5 min", 1800 to "30 min", 3600 to "1 h")
private val SPEED_NAMES = mapOf("down" to "Bajada", "up" to "Subida", "dlim" to "Límite de bajada", "ulim" to "Límite de subida")
private val SPEED_TIPS = mapOf(
    "down" to "Velocidad a la que recibes datos de otros pares (descarga).",
    "up" to "Velocidad a la que envías datos a otros pares (lo que compartes).",
    "dlim" to "Tope de descarga configurado en Límites y conexiones. La bajada no pasa de esta línea.",
    "ulim" to "Tope de subida configurado en Límites y conexiones. La subida no pasa de esta línea.",
)
private val SERIE_IDX = mapOf("down" to 1, "up" to 2, "dlim" to 3, "ulim" to 4)
private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
private val hhmmss = DateTimeFormatter.ofPattern("HH:mm:ss")
private fun clock(ts: Long, f: DateTimeFormatter) = f.format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))

fun niceStep(max: Double, ticks: Int): Double {
    val k = Formato.kb().toDouble(); val raw = max / ticks
    val unit = k.pow(max(0, floor(ln(raw) / ln(k)).toInt()))
    val m = raw / unit
    return unit * (listOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000).firstOrNull { it >= m } ?: 1000)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Velocidad(estado: Estado, t: Torrent) {
    val c = LocalColores.current
    val p = estado.prefs
    val total = p.speedOf == "total"
    val key = if (total) "total" else t.hash
    val samples = estado.history?.takeIf { it.key == key }?.samples ?: emptyList()
    val last = samples.lastOrNull()
    val series = listOf("down", "up") + (if (total && last != null && last.size > 3 && last[3] > 0) listOf("dlim") else emptyList()) +
        (if (total && last != null && last.size > 4 && last[4] > 0) listOf("ulim") else emptyList())
    var ayuda by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Seg(listOf("torrent" to "Este torrent", "total" to "Total"), p.speedOf) { v -> estado.updatePrefs { it.copy(speedOf = v) }; estado.pestana("speed") }
            Seg(SPEED_RANGES.map { it.first.toString() to it.second }, p.speedRange.toString()) { v -> estado.updatePrefs { it.copy(speedRange = v.toInt()) } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in series) Row(Modifier.clickable { ayuda = if (ayuda == s) null else s }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(14.dp).height(3.dp).background(if (s == "down" || s == "dlim") c.spDown else c.spUp))
                Text(SPEED_NAMES.getValue(s), fontSize = 13.sp)
                val v = if (last == null) "" else Formato.rate(last[SERIE_IDX.getValue(s)]).ifEmpty { "0 B/s" }
                Text(v, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        ayuda?.let { Hint(SPEED_TIPS.getValue(it)) }
        if (samples.size < 2) Placeholder("Juntando datos…")
        else Grafico(samples, series, p.speedRange, Modifier.fillMaxWidth().height(280.dp))
    }
}

/** drawSpeed de la web: áreas y líneas de bajada y subida, los límites punteados. Tocar o arrastrar muestra un instante. */
@Composable
fun Grafico(samples: List<LongArray>, series0: List<String>, range: Int, modifier: Modifier) {
    val c = LocalColores.current
    val measurer = rememberTextMeasurer()
    var hoverX by remember { mutableStateOf<Float?>(null) }
    val now = samples.last()[0]; val t0 = now - range
    val pts = samples.filter { it[0] >= t0 }.ifEmpty { samples.takeLast(2) }
    fun v(s: LongArray, i: Int) = if (i < s.size) s[i].toDouble() else 0.0
    var maxV = 1024.0
    for (s in pts) { maxV = max(maxV, v(s, 1)); maxV = max(maxV, v(s, 2)) }
    fun lim(k: String) = pts.maxOf { v(it, SERIE_IDX.getValue(k)) }
    val off = series0.filter { it.endsWith("lim") && lim(it) > maxV * 2 }
    val series = series0 - off.toSet()
    for (k in series) for (s in pts) maxV = max(maxV, v(s, SERIE_IDX.getValue(k)))
    val step = niceStep(maxV * 1.1, 4)
    maxV = ceil(maxV * 1.1 / step) * step
    val style = TextStyle(fontSize = 11.sp, color = c.dim)
    val col = { i: Int -> if (i == 1 || i == 3) c.spDown else c.spUp }
    var hover: LongArray? = null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(modifier.pointerInput(Unit) {
            detectTapGestures { hoverX = if (hoverX == null) it.x else null }
        }.pointerInput(Unit) {
            detectHorizontalDragGestures(onDragEnd = {}) { ch, _ -> hoverX = ch.position.x }
        }) {
            val labels = (0..4).map { measurer.measure(if (it == 0) "0" else Formato.rate((it * step).toLong()), style) }
            val labelW = labels.maxOf { it.size.width } + 10.dp.toPx()
            val L = 4.dp.toPx() + labelW; val R = size.width - 8.dp.toPx(); val T = 12.dp.toPx(); val B = size.height - 24.dp.toPx()
            fun X(ts: Long) = L + (ts - t0).toFloat() / range * (R - L)
            fun Y(x: Double) = (B - x / maxV * (B - T)).toFloat()
            var vv = 0.0; var n = 0
            while (vv <= maxV + 1) {
                val y = Y(vv)
                drawLine(c.line, Offset(L, y), Offset(R, y), 1f, alpha = if (vv == 0.0) 1f else 0.5f)
                val lab = measurer.measure(if (vv == 0.0) "0" else Formato.rate(vv.toLong()), style)
                drawText(lab, topLeft = Offset(L - 8.dp.toPx() - lab.size.width, y - lab.size.height / 2))
                vv += step; n++
            }
            val gap = measurer.measure(clock(now, hhmm), style).size.width + 16.dp.toPx()
            val minEvery = if (range <= 300) 60 else if (range <= 1800) 300 else 600
            val every = listOf(60, 120, 300, 600, 900, 1200, 1800, 3600).firstOrNull { it >= minEvery && it.toFloat() / range * (R - L) >= gap } ?: 3600
            var ts = ceil(t0.toDouble() / every).toLong() * every
            while (ts <= now) {
                val lab = measurer.measure(clock(ts, hhmm), style)
                drawText(lab, topLeft = Offset(X(ts) - lab.size.width / 2, B + 6.dp.toPx()))
                ts += every
            }
            fun path(i: Int, area: Boolean): Path = Path().apply {
                pts.forEachIndexed { k, s -> if (k == 0) moveTo(X(s[0]), Y(v(s, i))) else lineTo(X(s[0]), Y(v(s, i))) }
                if (area) { lineTo(X(pts.last()[0]), B); lineTo(X(pts.first()[0]), B); close() }
            }
            for (k in listOf(1, 2)) drawPath(path(k, true), col(k), alpha = 0.12f)
            for (k in series) {
                val i = SERIE_IDX.getValue(k); val isLim = k.endsWith("lim")
                drawPath(path(i, false), col(i), style = Stroke(width = (if (isLim) 1.5f else 2f).dp.toPx(), join = StrokeJoin.Round,
                    pathEffect = if (isLim) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null))
            }
            off.forEachIndexed { k, s ->
                val lab = measurer.measure("${SPEED_NAMES[s]}: ${Formato.rate(lim(s).toLong())}, fuera de escala ↑", style.copy(color = col(SERIE_IDX.getValue(s))))
                drawText(lab, topLeft = Offset(R - 4.dp.toPx() - lab.size.width, T + 2.dp.toPx() + k * 15.dp.toPx()))
            }
            hoverX?.let { hx ->
                if (hx < L || hx > R) return@let
                val tsH = t0 + (hx - L) / (R - L) * range
                val best = pts.minByOrNull { kotlin.math.abs(it[0] - tsH) } ?: return@let
                hover = best
                val x = X(best[0])
                drawLine(c.dim, Offset(x, T), Offset(x, B), 1f)
                for (i in listOf(1, 2)) {
                    drawCircle(col(i), 4.dp.toPx(), Offset(x, Y(v(best, i))))
                    drawCircle(c.bg, 4.dp.toPx(), Offset(x, Y(v(best, i))), style = Stroke(2.dp.toPx()))
                }
            }
        }
        // El instante tocado, debajo del gráfico (el tooltip de la web).
        val h = hoverX?.let { hover }
        if (h != null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(clock(h[0], hhmmss), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("Bajada " + Formato.rate(h[1]).ifEmpty { "0 B/s" }, fontSize = 13.sp, color = c.spDown)
            Text("Subida " + Formato.rate(h[2]).ifEmpty { "0 B/s" }, fontSize = 13.sp, color = c.spUp)
        } else Hint("Toca o arrastra sobre el gráfico para ver un instante.")
    }
}

// ------------------------------------------------------------ Opciones

val OPT_HELP = mapOf(
    "priority" to "Cuánto ancho de banda recibe este torrent frente a los demás. “Desactivada” lo deja sin transferir.",
    "speed" to "Tope solo para este torrent. 0 = sin tope propio; el límite general sigue aplicando igual.",
    "peers" to "Cuántos pares puede tener conectados a la vez este torrent, y cuántos intenta mantener como mínimo.",
    "uploads" to "A cuántos pares les envía datos al mismo tiempo. Menos subidas concentran la velocidad en menos pares.",
    "seed" to "Tablero detiene el torrent cuando cumple la condición. Funciona mientras Tablero esté corriendo. Si vuelves a iniciarlo a mano, no lo detiene de nuevo hasta que cambies esta regla.",
    "general" to "Vacío = el valor general de Límites y conexiones, que se ve en gris. Un número aquí vale solo para este torrent.",
)

/** generalPeer de la web: las Conexiones de un torrent sin las suyas (el mínimo es la mitad del máximo), o las de rTorrent. */
fun generalPeer(g: GeneralConf, k: String): Long? {
    val v = if (k == "peers_min") g.general["peers_max"]?.let { lng(it) / 2 } else g.general[k]?.let { lng(it) }
    return v ?: g.torrent[k]?.let { lng(it) }
}

fun seedOwn(o: Map<String, Any?>): Boolean = if ("seed_own" in o) bool(o["seed_own"]) else (dbl(o["stop_ratio"]) != 0.0 || dbl(o["stop_hours"]) != 0.0)

private fun numEs(x: Double): String = if (x == floor(x)) x.toLong().toString() else x.toString().replace('.', ',')

fun seedRuleText(r: Map<String, Any?>): String {
    val parts = ArrayList<String>()
    if (dbl(r["stop_ratio"]) != 0.0) parts.add("al llegar a una proporción de " + numEs(dbl(r["stop_ratio"])))
    if (dbl(r["stop_hours"]) != 0.0) parts.add("después de ${numEs(dbl(r["stop_hours"]))} horas sembrando")
    return if (parts.isNotEmpty()) "Detener " + parts.joinToString(" o ") else "Sin regla: sigue sembrando"
}

/** Lo que se está escribiendo en Opciones; no lo pisa la próxima vuelta de la lista. */
private class FormOpciones(t: Torrent) {
    val o = t.opts
    var prio by mutableStateOf(t.priority)
    var down by mutableStateOf((lng(o["down_limit"]) / Formato.kb()).toString())
    var up by mutableStateOf((lng(o["up_limit"]) / Formato.kb()).toString())
    var pmax by mutableStateOf(o["peers_max"]?.let { lng(it).toString() } ?: "")
    var pmin by mutableStateOf(o["peers_min"]?.let { lng(it).toString() } ?: "")
    var umax by mutableStateOf(o["uploads_max"]?.let { lng(it).toString() } ?: "")
    val own0 = seedOwn(o)
    var own by mutableStateOf(own0)
    var rOn by mutableStateOf(own0 && dbl(o["stop_ratio"]) != 0.0)
    var hOn by mutableStateOf(own0 && dbl(o["stop_hours"]) != 0.0)
    var ratio by mutableStateOf(numEs(if (own0 && dbl(o["stop_ratio"]) != 0.0) dbl(o["stop_ratio"]) else 2.0))
    var hours by mutableStateOf(numEs(if (own0 && dbl(o["stop_hours"]) != 0.0) dbl(o["stop_hours"]) else 24.0))
}

private fun num(s: String): Double = s.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Opciones(estado: Estado, t: Torrent) {
    val c = LocalColores.current
    var vuelta by remember { mutableStateOf(0) }
    val f = remember(t.hash, vuelta) { FormOpciones(t) }
    var aplicando by remember { mutableStateOf(false) }
    LaunchedEffect(t.hash) { estado.loadGeneral() }
    val g = estado.general
    val unit = Formato.units()[1] + "/s"
    @Composable fun seccion(titulo: String, help: String, body: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titulo, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            body()
            Hint(help)
        }
    }
    @Composable fun campo(label: String, v: String, onV: (String) -> Unit, suffix: String, placeholder: String? = null, modifier: Modifier = Modifier) {
        // El nombre va arriba y no como label: así el valor general (placeholder) se ve aunque el campo esté vacío.
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (label.isNotEmpty()) Text(label, fontSize = 12.sp, color = c.dim)
            OutlinedTextField(v, { s -> onV(s.filter { it.isDigit() || it == ',' || it == '.' }) }, singleLine = true,
                suffix = { Text(suffix, fontSize = 12.sp, color = c.dim) }, placeholder = placeholder?.let { { Text(it, color = c.dim) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        seccion("Prioridad", OPT_HELP.getValue("priority")) {
            Seg(listOf("3" to "Alta", "2" to "Normal", "1" to "Baja", "0" to "Desactivada"), f.prio.toString()) { f.prio = it.toInt() }
        }
        seccion("Velocidad", OPT_HELP.getValue("speed")) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                campo("Bajada máxima", f.down, { f.down = it }, unit, modifier = Modifier.weight(1f))
                campo("Subida máxima", f.up, { f.up = it }, unit, modifier = Modifier.weight(1f))
            }
        }
        seccion("Conexiones", OPT_HELP.getValue("peers") + " " + OPT_HELP.getValue("uploads")) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                campo("Pares máximos", f.pmax, { f.pmax = it.filter(Char::isDigit) }, "pares", generalPeer(g, "peers_max")?.toString(), Modifier.weight(1f))
                campo("Pares mínimos", f.pmin, { f.pmin = it.filter(Char::isDigit) }, "pares", generalPeer(g, "peers_min")?.toString(), Modifier.weight(1f))
            }
            campo("Subidas simultáneas", f.umax, { f.umax = it.filter(Char::isDigit) }, "pares", generalPeer(g, "uploads_max")?.toString(), Modifier.fillMaxWidth(0.5f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotonHerr("rotate-ccw", "Usar general") { f.pmax = ""; f.pmin = ""; f.umax = "" }
            }
            Hint(OPT_HELP.getValue("general"))
        }
        seccion("Al sembrar", OPT_HELP.getValue("seed")) {
            Seg(listOf("0" to "General", "1" to "Propia de este torrent"), if (f.own) "1" else "0") { f.own = it == "1" }
            if (!f.own) Hint(seedRuleText(g.general) + ". Se cambia en Límites y conexiones.")
            else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.rOn, { f.rOn = it })
                    Text("Detener al llegar a una proporción de subida de", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    campo("", f.ratio, { f.ratio = it }, "", modifier = Modifier.width(90.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.hOn, { f.hOn = it })
                    Text("Detener después de", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    campo("", f.hours, { f.hours = it }, "horas", modifier = Modifier.width(120.dp))
                }
            }
            if (bool(t.opts["rule_done"])) Hint("Este torrent ya se detuvo una vez por su regla de siembra.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (aplicando) Text("Aplicando…", fontSize = 13.sp, color = c.dim)
            OutlinedButton(onClick = { vuelta++ }, border = BorderStroke(1.dp, c.line)) { Text("Deshacer cambios") }
            Button(enabled = !aplicando, onClick = {
                val own = { s: String -> if (s.trim().isEmpty()) null else max(0L, num(s).roundToInt().toLong()) }
                val options = linkedMapOf<String, Any?>(
                    "priority" to f.prio, "down_limit" to (num(f.down) * Formato.kb()).toLong(), "up_limit" to (num(f.up) * Formato.kb()).toLong(),
                    "peers_max" to own(f.pmax), "peers_min" to own(f.pmin), "uploads_max" to own(f.umax),
                    "seed_own" to f.own,
                    "stop_ratio" to if (f.own && f.rOn) num(f.ratio) else 0.0,
                    "stop_hours" to if (f.own && f.hOn) num(f.hours) else 0.0,
                )
                fun eff(k: String) = (options[k] as Long?) ?: generalPeer(g, k)
                val mn = eff("peers_min"); val mx = eff("peers_max")
                if (mn != null && mx != null && mn > mx && (options["peers_min"] != null || options["peers_max"] != null)) {
                    estado.aviso("Los pares mínimos no pueden superar a los máximos", true); return@Button
                }
                aplicando = true
                estado.launch {
                    if (estado.intenta { estado.api.post("/api/options", mapOf("hash" to t.hash, "options" to options)) }) {
                        estado.aviso("Opciones aplicadas a " + t.name)
                        estado.refresh()
                    }
                    aplicando = false
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)) { Text("Aplicar") }
        }
    }
}
