package cl.tablero.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Un archivo dentro de un .torrent (de /api/torrent-info); [pad] son los de relleno, que no se muestran. */
class ArchivoInfo(val path: String, val size: Long, val pad: Boolean, val i: Int)

/** Un .torrent elegido en Agregar: lo que tiene adentro y qué archivos no bajar. */
class TorrentElegido(val f: ArchivoLocal) {
    var name by mutableStateOf("")
    var files by mutableStateOf<List<ArchivoInfo>?>(null)
    var error by mutableStateOf<String?>(null)
    var skip by mutableStateOf<Set<Int>>(emptySet())
    val real get() = files.orEmpty().filter { !it.pad }
}

/** addDialog de la web: archivos .torrent (y qué bajar de cada uno), enlaces, carpeta, etiquetas e iniciar. */
@Composable
fun AgregarDialogo(estado: Estado, d: Dialogo.Agregar) {
    val c = LocalColores.current
    val picked = remember { mutableStateListOf<TorrentElegido>() }
    // Los que llegan de otras apps mientras el diálogo está abierto se suman (el mismo, una vez).
    LaunchedEffect(d.files) {
        for (f in d.files) if (picked.none { it.f.path == f.path }) picked.add(TorrentElegido(f))
    }
    for (t in picked) LaunchedEffect(t) {
        if (t.files != null || t.error != null) return@LaunchedEffect
        try {
            val r = estado.api.post("/api/torrent-info", mapOf("path" to t.f.path))
            t.name = str(r["name"])
            t.files = listOfAny(r["files"]).mapIndexed { i, x -> val o = asMap(x); ArchivoInfo(str(o["path"]), lng(o["size"]), bool(o["pad"]), i) }
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; t.error = e.texto() }
    }
    var magnets by remember(d.magnets) { mutableStateOf(d.magnets) }
    var dir by remember { mutableStateOf(estado.prefs.lastDir) }
    var start by remember { mutableStateOf(true) }
    var tree by remember { mutableStateOf(estado.prefs.filesTree) }
    val closed = remember { mutableStateMapOf<String, Boolean>() }  // "k:carpeta" de las cerradas
    val marcas = remember {
        mutableStateMapOf<String, ToggleableState>().apply { estado.filtro.tags.filter { it != NO_TAG }.forEach { put(it, ToggleableState.On) } }
    }
    val nuevas = remember { mutableStateListOf<String>() }
    // El espacio libre del disco de la carpeta, contra lo que ocupan los archivos elegidos.
    var free by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(dir) {
        delay(300)
        free = try { lng(estado.api.get("/api/free-space", mapOf("path" to dir.trim()))["free"]) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; null
        }
    }

    Ventana(estado, "Agregar torrents", "Agregar", onOk = {
        val lines = magnets.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val tags = marcas.filter { it.value == ToggleableState.On }.keys.toList()
        val files = picked.filter { it.files != null }
        val none = files.find { t -> t.files!!.all { it.pad || it.i in t.skip } }
        when {
            picked.isEmpty() && lines.isEmpty() -> { estado.aviso("Agrega un archivo o un enlace", true); false }
            none != null -> { estado.aviso("Elige al menos un archivo de ${none.name}", true); false }
            else -> {
                estado.updatePrefs { it.copy(lastDir = dir.trim()) }
                var ok = 0; val fail = ArrayList<String>(); val dups = ArrayList<Map<String, Any?>>()
                suspend fun send(body: Map<String, Any?>, label: String?) {
                    try {
                        val r = estado.api.post("/api/add", body)
                        val dup = asMap(r["duplicate"])
                        if (dup.isNotEmpty()) dups.add(dup) else ok++
                    } catch (e: Exception) { fail.add((if (label != null) "$label: " else "") + e.texto()) }
                }
                for (t in picked) send(mapOf("path" to t.f.path, "directory" to dir.trim(), "tags" to tags, "start" to start, "skip" to t.skip.toList()), t.f.name)
                for (m in lines) send(mapOf("magnet" to m, "directory" to dir.trim(), "tags" to tags, "start" to start), null)
                when {
                    fail.isNotEmpty() -> estado.aviso(fail.joinToString(" · "), true)
                    ok > 0 -> estado.aviso("$ok torrent${if (ok == 1) "" else "s"} agregado${if (ok == 1) "" else "s"}")
                }
                estado.launch { delay(800); estado.refresh() }
                if (dups.isNotEmpty()) estado.dialogo = Dialogo.Duplicados(dups)
                true
            }
        }
    }) {
        Campo("Archivos") {
            BotonHerr("plus", "Elegir archivos .torrent") {
                estado.plataforma.elegirArchivos(listOf("application/x-bittorrent", "application/octet-stream", "*/*"), true) { fs ->
                    val ok = fs.filter { it.name.endsWith(".torrent", true) }
                    if (ok.size < fs.size) estado.aviso("Solo se pueden agregar archivos .torrent", true)
                    for (f in ok) if (picked.none { it.f.name == f.name }) picked.add(TorrentElegido(f))
                }
            }
        }
        if (picked.isNotEmpty()) Campo("Contenido") {
            val folders = picked.any { t -> t.real.any { '/' in it.path } }
            if (folders) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Seg(listOf("tree" to "Árbol", "list" to "Lista"), if (tree) "tree" else "list") { tree = it == "tree" }
                if (tree) {
                    BotonHerr("chevrons-up-down", desc = "Expandir todo") { closed.clear() }
                    BotonHerr("chevrons-down-up", desc = "Contraer todo") {
                        picked.forEachIndexed { k, t -> t.real.forEach { x -> val p = x.path.split("/"); for (n in 1 until p.size) closed["$k:" + p.subList(0, n).joinToString("/")] = true } }
                    }
                }
            }
            picked.forEachIndexed { k, t -> ContenidoTorrent(t, k, tree, closed) { picked.remove(t) } }
        }
        Campo("Enlaces magnet") {
            OutlinedTextField(magnets, { magnets = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp), placeholder = { Text("magnet:?xt=urn:btih:…") })
            Hint("Enlaces magnet o URL de archivos .torrent, uno por línea.")
        }
        Campo("Carpeta") {
            CarpetaCampo(estado, dir, { dir = it })
            Hint("Vacío = la carpeta predeterminada de rTorrent.")
            free?.let { fr ->
                val need = picked.sumOf { t -> t.real.filter { it.i !in t.skip }.sumOf { it.size } }
                if (need > fr) Marca("bad", "No cabe: hay ${Formato.size(need)} para descargar y quedan ${Formato.size(fr)} libres en ese disco. rTorrent lo dejaría en pausa.")
                else Hint("Libre en el disco: ${Formato.size(fr)}" + if (need > 0) " · para descargar: ${Formato.size(need)}" else "")
            }
        }
        Campo("Etiquetas") { SelectorEtiquetas(estado, marcas, nuevas) }
        CheckFila(start, { start = it }) { Text("Iniciar la descarga", fontSize = 14.sp) }
    }
}

/** Un .torrent elegido: su nombre, cuánto se bajará y, con varios archivos, cuáles (árbol o lista). */
@Composable
private fun ContenidoTorrent(t: TorrentElegido, k: Int, tree: Boolean, closed: MutableMap<String, Boolean>, quitar: () -> Unit) {
    val c = LocalColores.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.panel2).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    t.error != null -> Marca("bad", "${t.f.name}: ${t.error}")
                    t.files == null -> Text("${t.f.name}: leyendo…", fontSize = 13.sp, color = c.dim)
                    else -> {
                        val shown = t.real; val on = shown.filter { it.i !in t.skip }
                        Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("${on.size} de ${shown.size} archivo${if (shown.size == 1) "" else "s"} · ${Formato.size(on.sumOf { it.size })} de ${Formato.size(shown.sumOf { it.size })}",
                            fontSize = 12.sp, color = c.dim)
                    }
                }
            }
            IconButton(onClick = quitar, modifier = Modifier.size(32.dp)) { Icono("x", size = 16.dp, desc = "Quitar", tint = c.dim) }
        }
        val shown = t.real
        if (t.files != null && shown.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotonHerr("check", "Todos") { t.skip = emptySet() }
                BotonHerr("square", "Ninguno") { t.skip = shown.map { it.i }.toSet() }
            }
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                if (!tree) for (x in shown) FilaAgregar(x.path, x.size, 0, if (x.i in t.skip) ToggleableState.Off else ToggleableState.On) {
                    t.skip = if (x.i in t.skip) t.skip - x.i else t.skip + x.i
                }
                else ArbolAgregar(t, k, closed)
            }
        }
    }
}

private class NodoAdd(val name: String, val path: String) {
    val dirs = LinkedHashMap<String, NodoAdd>(); val files = ArrayList<ArchivoInfo>()
    val idx = ArrayList<Int>(); var size = 0L
    fun sum() {
        for (d in dirs.values) { d.sum(); idx.addAll(d.idx); size += d.size }
        for (f in files) { idx.add(f.i); size += f.size }
    }
}

@Composable
private fun ArbolAgregar(t: TorrentElegido, k: Int, closed: MutableMap<String, Boolean>) {
    val root = NodoAdd("", "")
    for (x in t.real) {
        val parts = x.path.split("/")
        var n = root
        for (d in 0 until parts.size - 1) n = n.dirs.getOrPut(parts[d]) { NodoAdd(parts[d], parts.subList(0, d + 1).joinToString("/")) }
        n.files.add(x)
    }
    root.sum()
    @Composable
    fun walk(n: NodoAdd, depth: Int) {
        for (d in n.dirs.values.sortedWith { a, b -> tcmp(a.name, b.name) }) {
            val key = "$k:${d.path}"
            val open = closed[key] != true
            val on = d.idx.count { it !in t.skip }
            FilaAgregar(d.name, d.size, depth, if (on == 0) ToggleableState.Off else if (on < d.idx.size) ToggleableState.Indeterminate else ToggleableState.On,
                dir = true, open = open, onTwisty = { if (open) closed[key] = true else closed.remove(key) }) {
                // Marcar una carpeta a medias la marca entera, como el navegador con una casilla indeterminada.
                t.skip = if (on == 0 || on < d.idx.size) t.skip - d.idx.toSet() else t.skip + d.idx
            }
            if (open) walk(d, depth + 1)
        }
        for (x in n.files.sortedWith { a, b -> tcmp(a.path.substringAfterLast('/'), b.path.substringAfterLast('/')) })
            FilaAgregar(x.path.substringAfterLast('/'), x.size, depth, if (x.i in t.skip) ToggleableState.Off else ToggleableState.On) {
                t.skip = if (x.i in t.skip) t.skip - x.i else t.skip + x.i
            }
    }
    walk(root, 0)
}

@Composable
private fun FilaAgregar(label: String, size: Long, depth: Int, st: ToggleableState, dir: Boolean = false, open: Boolean = true,
                        onTwisty: () -> Unit = {}, onToggle: () -> Unit) {
    val c = LocalColores.current
    Row(Modifier.fillMaxWidth().padding(start = (depth * 14).dp), verticalAlignment = Alignment.CenterVertically) {
        if (dir) Row(Modifier.clickable(onClick = onTwisty).padding(4.dp)) { Icono(if (open) "chevron-down" else "chevron-right", size = 16.dp, tint = c.dim) }
        else Spacer(Modifier.width(24.dp))
        TriStateCheckbox(st, onClick = onToggle, modifier = Modifier.size(36.dp))
        if (dir) { Icono("folder", size = 16.dp, tint = c.dim); Spacer(Modifier.width(4.dp)) }
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(Formato.size(size), fontSize = 12.sp, color = c.dim, modifier = Modifier.padding(start = 6.dp))
    }
}

/** duplicateDialog de la web: rTorrent no admite dos veces el mismo torrent; puede sumar sus rastreadores. */
@Composable
fun DuplicadosDialogo(estado: Estado, dups: List<Map<String, Any?>>) {
    val c = LocalColores.current
    val elegidos = remember { mutableStateMapOf<String, Boolean>() }  // "k|url"
    val any = dups.any { listOfAny(it["trackers"]).isNotEmpty() }
    Ventana(estado, if (dups.size > 1) "Ya están en la biblioteca" else "Ya está en la biblioteca", if (any) "Sumar rastreadores" else null,
        cancel = if (any) "Cancelar" else "Cerrar", onOk = {
            var n = 0L
            var ok = true
            for ((k, d) in dups.withIndex()) {
                val urls = listOfAny(d["trackers"]).map { str(it) }.filter { elegidos["$k|$it"] != false }
                if (urls.isEmpty()) continue
                ok = ok && estado.intenta { n += lng(estado.api.post("/api/add-trackers", mapOf("hash" to str(d["hash"]), "urls" to urls))["added"]) }
                if (!ok) break
            }
            if (ok) { estado.aviso("$n rastreador${if (n == 1L) "" else "es"} sumado${if (n == 1L) "" else "s"}"); estado.refresh() }
            ok
        }) {
        for ((k, d) in dups.withIndex()) {
            Campo("Torrent") {
                Text(str(d["name"]), fontSize = 14.sp)
                Hint("rTorrent no admite dos veces el mismo torrent, así que no se agregó de nuevo.")
            }
            val trs = listOfAny(d["trackers"]).map { str(it) }
            Campo("Trackers nuevos") {
                if (trs.isEmpty()) Text("Ya tiene todos los rastreadores del archivo: no hay nada que sumar.", fontSize = 13.sp, color = c.dim)
                else {
                    for (u in trs) CheckFila(elegidos["$k|$u"] != false, { elegidos["$k|$u"] = it }) { Text(u, fontSize = 13.sp) }
                    Hint("Rastreadores que trae el archivo y este torrent no tiene.")
                }
            }
        }
    }
}
