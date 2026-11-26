package cl.tablero.compose

import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.PaddingValues
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
    data class Agregar(val magnets: String = "", val files: List<ArchivoLocal> = emptyList()) : Dialogo
    data object Campos : Dialogo
    data object Orden : Dialogo
    data class Rastreadores(val hash: String) : Dialogo
    data class Duplicados(val dups: List<Map<String, Any?>>) : Dialogo
    /** Opciones de etiqueta; null: Nueva etiqueta. */
    data class Etiqueta(val name: String?) : Dialogo
    data class EliminarEtiqueta(val name: String) : Dialogo
    data class Reubicar(val hashes: List<String>) : Dialogo
    data class RenombrarCarpeta(val hash: String) : Dialogo
    data object Limites : Dialogo
    data object Puerto : Dialogo
    data object Opciones : Dialogo
    data object Apagar : Dialogo
    data object Respaldos : Dialogo
    data class Restaurar(val path: String) : Dialogo
    data class Restaurado(val r: Map<String, Any?>) : Dialogo
    data class PrimerRespaldo(val info: Map<String, Any?>) : Dialogo
}

@Composable
fun DialogoActual(estado: Estado) {
    when (val d = estado.dialogo) {
        null -> {}
        is Dialogo.Eliminar -> EliminarDialogo(estado, d.hashes)
        is Dialogo.Etiquetas -> EtiquetasDialogo(estado, d.hashes)
        is Dialogo.Agregar -> AgregarDialogo(estado, d)
        Dialogo.Campos -> CamposDialogo(estado)
        Dialogo.Orden -> OrdenDialogo(estado)
        is Dialogo.Rastreadores -> RastreadoresDialogo(estado, d.hash)
        is Dialogo.Duplicados -> DuplicadosDialogo(estado, d.dups)
        is Dialogo.Etiqueta -> EtiquetaDialogo(estado, d.name)
        is Dialogo.EliminarEtiqueta -> EliminarEtiquetaDialogo(estado, d.name)
        is Dialogo.Reubicar -> ReubicarDialogo(estado, d.hashes)
        is Dialogo.RenombrarCarpeta -> RenombrarCarpetaDialogo(estado, d.hash)
        Dialogo.Limites -> LimitesDialogo(estado)
        Dialogo.Puerto -> PuertoDialogo(estado)
        Dialogo.Opciones -> OpcionesDialogo(estado)
        Dialogo.Apagar -> ApagarDialogo(estado)
        Dialogo.Respaldos -> RespaldosDialogo(estado)
        is Dialogo.Restaurar -> RestaurarDialogo(estado, d.path)
        is Dialogo.Restaurado -> RestauradoDialogo(estado, d.r)
        is Dialogo.PrimerRespaldo -> PrimerRespaldoDialogo(estado, d.info)
    }
}

/**
 * Un diálogo con título, cuerpo desplazable y botones: [cancel] cierra y [ok] (si hay) corre [onOk], que
 * devuelve si cerrar. [extra] va a la izquierda de Cancelar.
 */
@Composable
fun Ventana(
    estado: Estado, title: String, ok: String?, onOk: suspend () -> Boolean = { true }, danger: Boolean = false,
    cancel: String = "Cancelar", onCancel: () -> Unit = {}, extra: (@Composable () -> Unit)? = null, body: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val c = LocalColores.current
    val yo = estado.dialogo
    val cerrar = { onCancel(); estado.dialogo = null }
    // Se dibuja en la misma ventana de la app, encima de todo (no en una ventana de diálogo aparte): dentro de un Dialog,
    // Android entrega las barras del sistema en cero y la tarjeta se metía bajo la barra de navegación. Atrás lo cierra
    // por Estado.atras(); tocar fuera de la tarjeta también, como en AlertDialog.
    run {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(remember { MutableInteractionSource() }, null, onClick = cerrar)
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemBars.union(androidx.compose.foundation.layout.WindowInsets.ime))
            .padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {},
                shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
                Column(Modifier.padding(24.dp)) {
                    Text(title, fontSize = 18.sp, color = AlertDialogDefaults.titleContentColor, modifier = Modifier.padding(bottom = 16.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides AlertDialogDefaults.textContentColor) { body() }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        extra?.invoke()
                        TextButton(onClick = cerrar) { Text(cancel) }
                        if (ok != null) TextButton(enabled = !busy, onClick = {
                            busy = true
                            // Si onOk abrió otro diálogo (Restaurar, Sumar rastreadores…), ese queda.
                            scope.launch { if (onOk() && estado.dialogo === yo) estado.dialogo = null; busy = false }
                        }, colors = if (danger) ButtonDefaults.textButtonColors(contentColor = c.bad) else ButtonDefaults.textButtonColors()) { Text(ok) }
                    }
                }
            }
        }
    }
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
    Casilla(checked, onChange) { label() }
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
            CasillaTri(st, { marcas[x] = if (st == ToggleableState.On) ToggleableState.Off else ToggleableState.On }) { Chip(estado, x) }
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
    CampoTexto(nueva, { nueva = it }, placeholder = "Nueva etiqueta",
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { agregar() }),
        trailing = {
            Text("Agregar", fontSize = 14.sp, color = if (nueva.isNotBlank()) LocalColores.current.accent else LocalColores.current.dim,
                modifier = Modifier.clickable(enabled = nueva.isNotBlank()) { agregar() }.padding(horizontal = 10.dp, vertical = 8.dp))
        })
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
        if (marcas.values.any { it != ToggleableState.Off })
            BotonHerr("x", "Quitar todas") { for (x in (estado.allTags() + nuevas).distinct()) marcas[x] = ToggleableState.Off }
        Hint("Un torrent puede tener varias etiquetas. La primera también se guarda como etiqueta de ruTorrent.")
    }
}

/** Un archivo elegido o recibido de otra app, ya copiado al caché: Tablero lo lee por su ruta. */
data class ArchivoLocal(val name: String, val path: String)

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

/** Cómo se ordena la lista: la columna y el sentido (como un clic en el título de una columna de la web). */
@Composable
fun OrdenDialogo(estado: Estado) {
    val o = estado.prefs.sort
    var col by remember { mutableStateOf(o.col) }
    var dir by remember { mutableStateOf(o.dir) }
    var limpiar by remember { mutableStateOf(false) }
    Ventana(estado, "Ordenar la lista", "Guardar", onOk = {
        estado.updatePrefs { it.copy(sort = Orden(col, dir)) }
        if (limpiar) estado.filtro = estado.filtro.copy(text = "")
        true
    }) {
        Campo("Ordenar por") {
            // Dos columnas de radios: son 20 datos y en una sola la lista no cabe sin desplazar.
            Column {
                for (par in COLUMNS.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in par) Box(Modifier.weight(1f)) {
                        Opcion(col == k.id, {
                            if (col != k.id) dir = if (k.id == "name" || k.id == "pos") 1 else -1
                            col = k.id
                        }, if (k.id == "pos") "# (posición)" else k.title)
                    }
                    if (par.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Campo("Sentido") {
            Seg(listOf("1" to "Ascendente ▲", "-1" to "Descendente ▼"), dir.toString()) { dir = it.toInt() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonHerr("columns-3", "Datos de cada fila…") { estado.dialogo = Dialogo.Campos }
            BotonHerr("rotate-ccw", "Por defecto", on = limpiar) { col = "added"; dir = -1; limpiar = true }
        }
        Hint("Por defecto: por fecha de agregado, lo más nuevo arriba, y sin el texto de búsqueda.")
    }
}

