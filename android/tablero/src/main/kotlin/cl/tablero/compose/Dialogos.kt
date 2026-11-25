package cl.tablero.compose

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Los diálogos (modal() de la web). */
sealed interface Dialogo {
    data class Eliminar(val hashes: List<String>) : Dialogo
    data class Etiquetas(val hashes: List<String>) : Dialogo
    data class Agregar(val magnets: String = "", val files: List<ArchivoTorrent> = emptyList()) : Dialogo
    data object Campos : Dialogo
    data class Rastreadores(val hash: String) : Dialogo
}

@Composable
fun DialogoActual(estado: Estado) {
    when (val d = estado.dialogo) {
        null -> {}
        is Dialogo.Eliminar -> EliminarDialogo(estado, d.hashes)
        is Dialogo.Etiquetas -> EtiquetasDialogo(estado, d.hashes)
        is Dialogo.Agregar -> AgregarDialogo(estado, d)
        Dialogo.Campos -> CamposDialogo(estado)
        is Dialogo.Rastreadores -> RastreadoresDialogo(estado, d.hash)
    }
}

/** Un diálogo con título, cuerpo desplazable y botones (Cancelar y el principal). */
@Composable
fun Ventana(
    estado: Estado, title: String, ok: String, onOk: suspend () -> Boolean, danger: Boolean = false,
    extra: (@Composable () -> Unit)? = null, body: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val c = LocalColores.current
    AlertDialog(
        onDismissRequest = { estado.dialogo = null },
        title = { Text(title, fontSize = 18.sp) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { body() } },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch { if (onOk()) estado.dialogo = null; busy = false }
            }, colors = if (danger) ButtonDefaults.textButtonColors(contentColor = c.bad) else ButtonDefaults.textButtonColors()) { Text(ok) }
        },
        dismissButton = {
            Row { extra?.invoke(); TextButton(onClick = { estado.dialogo = null }) { Text("Cancelar") } }
        },
    )
}

@Composable
fun Hint(text: String) = Text(text, fontSize = 12.sp, color = LocalColores.current.dim)

/** La primera fila de los diálogos sobre torrents: el nombre o la lista (torrentRow de la web). */
@Composable
fun NombresTorrents(ts: List<Torrent>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (t in ts.take(6)) Text((if (ts.size > 1) "• " else "") + t.name, fontSize = 13.sp, maxLines = 2)
        if (ts.size > 6) Text("y ${ts.size - 6} más…", fontSize = 13.sp, color = LocalColores.current.dim)
    }
}

@Composable
fun CheckFila(checked: Boolean, onChange: (Boolean) -> Unit, label: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Checkbox(checked, onChange)
        label()
    }
}

@Composable
fun EliminarDialogo(estado: Estado, hashes: List<String>) {
    val ts = hashes.mapNotNull { estado.byHash[it] }
    var data by remember { mutableStateOf(false) }
    Ventana(estado, if (ts.size > 1) "Eliminar ${ts.size} torrents" else "Eliminar torrent", "Eliminar", danger = true, onOk = {
        estado.sel = emptySet()
        estado.act("/api/action", mapOf("action" to "remove", "hashes" to hashes, "data" to data),
            if (data) "Torrents y datos eliminados" else "Torrents eliminados")
        true
    }) {
        NombresTorrents(ts)
        CheckFila(data, { data = it }) { Text("Eliminar también los archivos descargados del disco", fontSize = 14.sp) }
        Hint("Sin marcar, los archivos quedan en su carpeta y solo se quita el torrent de la lista.")
    }
}

/**
 * Las etiquetas para marcar (tagPicker de la web), con una casilla a medias para las que tienen solo
 * algunos de los torrents, y un campo para agregar una nueva.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SelectorEtiquetas(estado: Estado, marcas: MutableMap<String, ToggleableState>, nuevas: MutableList<String>) {
    val names = (estado.allTags() + nuevas).distinct()
    if (names.isEmpty()) Text("Aún no hay etiquetas", color = LocalColores.current.dim, fontSize = 13.sp)
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        for (x in names) {
            val st = marcas[x] ?: ToggleableState.Off
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TriStateCheckbox(st, onClick = { marcas[x] = if (st == ToggleableState.On) ToggleableState.Off else ToggleableState.On })
                Chip(estado, x)
            }
        }
    }
    var nueva by remember { mutableStateOf("") }
    fun agregar() {
        val n = nueva.trim()
        if (n.isEmpty()) return
        if (n !in names) nuevas.add(n)
        marcas[n] = ToggleableState.On
        nueva = ""
    }
    OutlinedTextField(nueva, { nueva = it }, Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text("Nueva etiqueta") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { agregar() }),
        trailingIcon = { TextButton(onClick = { agregar() }, enabled = nueva.isNotBlank()) { Text("Agregar") } })
}

@Composable
fun EtiquetasDialogo(estado: Estado, hashes: List<String>) {
    val ts = hashes.mapNotNull { estado.byHash[it] }
    val all = remember { estado.allTags().filter { x -> ts.isNotEmpty() && ts.all { x in it.tags } }.toSet() }
    val some = remember { estado.allTags().filter { x -> x !in all && ts.any { x in it.tags } }.toSet() }
    val marcas = remember {
        mutableStateMapOf<String, ToggleableState>().apply {
            all.forEach { put(it, ToggleableState.On) }; some.forEach { put(it, ToggleableState.Indeterminate) }
        }
    }
    val nuevas = remember { mutableStateListOf<String>() }
    Ventana(estado, if (ts.size == 1) "Asignar etiquetas" else "Asignar etiquetas a ${ts.size} torrents", "Guardar", onOk = {
        val add = ArrayList<String>(); val remove = ArrayList<String>()
        for ((x, st) in marcas) {
            if (st == ToggleableState.Indeterminate) continue
            if (st == ToggleableState.On && x !in all) add.add(x)
            if (st == ToggleableState.Off && (x in all || x in some)) remove.add(x)
        }
        if (add.isNotEmpty() || remove.isNotEmpty()) estado.act("/api/edit-tags", mapOf("hashes" to hashes, "add" to add, "remove" to remove))
        true
    }) {
        NombresTorrents(ts)
        SelectorEtiquetas(estado, marcas, nuevas)
        Hint("Un torrent puede tener varias etiquetas. La primera también se guarda como etiqueta de ruTorrent.")
    }
}

/** Un .torrent para agregar, que Tablero lee por su ruta (los que llegan de otras apps se copian al caché). */
data class ArchivoTorrent(val name: String, val path: String)

/**
 * Ronda 1: Agregar con enlaces magnet o URL de .torrent, y los .torrent que llegan de otras apps (sin
 * elegir archivos ni carpeta todavía).
 */
@Composable
fun AgregarDialogo(estado: Estado, d: Dialogo.Agregar) {
    var magnets by remember(d.magnets) { mutableStateOf(d.magnets) }
    var start by remember { mutableStateOf(true) }
    val marcas = remember {
        mutableStateMapOf<String, ToggleableState>().apply { estado.filtro.tags.filter { it != NO_TAG }.forEach { put(it, ToggleableState.On) } }
    }
    val nuevas = remember { mutableStateListOf<String>() }
    Ventana(estado, "Agregar torrents", "Agregar", onOk = {
        val tags = marcas.filter { it.value == ToggleableState.On }.keys.toList()
        val lines = magnets.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (lines.isEmpty() && d.files.isEmpty()) { estado.aviso("Agrega un archivo o un enlace", true); return@Ventana false }
        var ok = 0; val fail = ArrayList<String>(); val dups = ArrayList<String>()
        suspend fun send(body: Map<String, Any?>, label: String?) {
            try {
                val r = estado.api.post("/api/add", body)
                val dup = asMap(r["duplicate"])
                if (dup.isNotEmpty()) dups.add(str(dup["name"])) else ok++
            } catch (e: Exception) { fail.add((if (label != null) "$label: " else "") + e.texto()) }
        }
        for (f in d.files) send(mapOf("path" to f.path, "directory" to "", "tags" to tags, "start" to start, "skip" to emptyList<Int>()), f.name)
        for (m in lines) send(mapOf("magnet" to m, "directory" to "", "tags" to tags, "start" to start), null)
        when {
            fail.isNotEmpty() -> estado.aviso(fail.joinToString(" · "), true)
            ok > 0 -> estado.aviso("$ok torrent${if (ok == 1) "" else "s"} agregado${if (ok == 1) "" else "s"}")
        }
        // Ronda 1: sin el diálogo de duplicados (Sumar rastreadores), solo el aviso.
        if (dups.isNotEmpty()) estado.aviso("Ya está en la biblioteca: " + dups.joinToString(" · "), true)
        estado.refresh()
        true
    }) {
        if (d.files.isNotEmpty()) {
            Text("Archivos", fontSize = 13.sp)
            for (f in d.files) Text("• " + f.name, fontSize = 13.sp)
        }
        OutlinedTextField(magnets, { magnets = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp),
            label = { Text("Enlaces magnet") }, placeholder = { Text("magnet:?xt=urn:btih:…") })
        Hint("Enlaces magnet o URL de archivos .torrent, uno por línea.")
        Text("Etiquetas", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        SelectorEtiquetas(estado, marcas, nuevas)
        CheckFila(start, { start = it }) { Text("Iniciar la descarga", fontSize = 14.sp) }
    }
}

/** Qué datos muestra cada fila (las columnas visibles de la web). */
@Composable
fun CamposDialogo(estado: Estado) {
    val sel = remember { mutableStateListOf<String>().apply { addAll(estado.prefs.fields) } }
    Ventana(estado, "Datos de cada fila", "Guardar", onOk = {
        estado.updatePrefs { p -> p.copy(fields = COLUMNS.map { it.id }.filter { it in sel }) }
        true
    }, extra = {
        TextButton(onClick = { sel.clear(); sel.addAll(Prefs.DEFAULT_FIELDS) }) { Text("Restablecer") }
    }) {
        Hint("El nombre siempre se ve. Lo demás, en el orden de esta lista.")
        for (col in COLUMNS) {
            if (col.id == "name") continue
            CheckFila(col.id in sel, { if (it) sel.add(col.id) else sel.remove(col.id) }) { Text(col.title, fontSize = 14.sp) }
        }
    }
}
