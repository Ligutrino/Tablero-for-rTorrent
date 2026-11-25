package cl.tablero.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** La pantalla de Tablero: barra de arriba, filtros a un lado, la lista y las acciones sobre lo elegido. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableroPantalla(estado: Estado) {
    TableroTema(estado.prefs.theme) {
        val c = LocalColores.current
        val drawer = rememberDrawerState(DrawerValue.Closed)
        LaunchedEffect(estado.cajon) { if (estado.cajon) drawer.open() else drawer.close() }
        LaunchedEffect(drawer) { snapshotFlow { drawer.currentValue }.collect { estado.cajon = it == DrawerValue.Open } }
        val snack = remember { SnackbarHostState() }
        var avisoError by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            for (a in estado.avisos) { avisoError = a.error; snack.showSnackbar(a.texto, withDismissAction = a.error) }
        }
        val det = estado.detalle?.let { estado.byHash[it] }
        ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = det == null, drawerContent = {
            ModalDrawerSheet(drawerContainerColor = c.panel, modifier = Modifier.width(300.dp)) { Filtros(estado) }
        }) {
            Scaffold(
                containerColor = c.bg,
                topBar = { if (det != null) BarraDetalle(estado, det) { MenuTorrents(estado) } else BarraSuperior(estado) },
                bottomBar = {
                    // El gráfico de velocidad ocupa la mitad de abajo; el botón Agregar queda sobre él.
                    // Solo si hay algo: una barra vacía (alto 0) hace que el botón + ignore la barra de navegación del sistema.
                    if ((det == null && estado.grafico) || det != null || estado.sel.isNotEmpty()) Column {
                        // Sin la barra de acciones debajo, el panel deja libre la zona de los botones del sistema.
                        if (det == null && estado.grafico) PanelVelocidad(estado, Modifier.fillMaxWidth().fillMaxHeight(0.45f)
                            .then(if (det != null || estado.sel.isNotEmpty()) Modifier else Modifier.background(c.panel).navigationBarsPadding()))
                        if (det != null || estado.sel.isNotEmpty()) BarraAcciones(estado)
                    }
                },
                floatingActionButton = {
                    if (det == null && estado.sel.isEmpty()) FloatingActionButton(onClick = { estado.dialogo = Dialogo.Agregar() }, containerColor = c.accent, contentColor = Color.White) {
                        Icono("plus", size = 24.dp, desc = "Agregar")
                    }
                },
                snackbarHost = {
                    SnackbarHost(snack) { d ->
                        Snackbar(d, containerColor = if (avisoError) c.bad else c.panel2, contentColor = if (avisoError) Color.White else c.text,
                            dismissActionContentColor = Color.White)
                    }
                },
            ) { pad ->
                if (det != null) Column(Modifier.padding(pad).fillMaxSize()) {
                    Conexion(estado)
                    DetalleCuerpo(estado, det)
                } else Column(Modifier.padding(pad).fillMaxSize()) {
                    Conexion(estado)
                    if (estado.prefs.tagbar) BarraEtiquetas(estado)
                    ListaTorrents(estado)
                }
            }
        }
        DialogoActual(estado)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarraSuperior(estado: Estado) {
    val c = LocalColores.current
    val colors = TopAppBarDefaults.topAppBarColors(containerColor = c.panel, titleContentColor = c.text,
        navigationIconContentColor = c.text, actionIconContentColor = c.text)
    val n = estado.sel.size
    if (n > 0) {
        TopAppBar(colors = colors, navigationIcon = {
            IconButton(onClick = { estado.sel = emptySet() }) { Icono("x", size = 22.dp, desc = "Quitar la selección") }
        }, title = {
            val hidden = estado.sel.count { h -> estado.rows().none { it.hash == h } }
            Column {
                Text("$n seleccionado${if (n > 1) "s" else ""}", fontSize = 18.sp)
                if (hidden > 0) Text("$hidden oculto${if (hidden > 1) "s" else ""} por el filtro", fontSize = 12.sp, color = c.dim)
            }
        }, actions = {
            IconButton(onClick = { estado.sel = estado.rows().map { it.hash }.toSet() }) { Icono("list-checks", size = 22.dp, desc = "Elegir todos") }
            Box {
                IconButton(onClick = { estado.menu = "torrents" }) { Icono("ellipsis-vertical", size = 22.dp, desc = "Más") }
                MenuTorrents(estado)
            }
        })
        return
    }
    if (estado.buscando) {
        TopAppBar(colors = colors, navigationIcon = {
            IconButton(onClick = { estado.buscando = false; estado.filtro = estado.filtro.copy(text = "") }) { Icono("arrow-left", size = 22.dp, desc = "Cerrar la búsqueda") }
        }, title = {
            TextField(estado.filtro.text, { estado.filtro = estado.filtro.copy(text = it) }, singleLine = true,
                placeholder = { Text("Buscar por nombre") }, modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
        })
        return
    }
    TopAppBar(colors = colors, navigationIcon = {
        IconButton(onClick = { estado.cajon = true }) { Icono("menu", size = 22.dp, desc = "Filtros") }
    }, title = {
        // Tocar las velocidades abre o cierra el gráfico de velocidad abajo.
        TituloYVelocidades(estado, Modifier.clickable { estado.grafico = !estado.grafico })
    }, actions = {
        // De izquierda a derecha: buscar, ordenar, límites, opciones, puerto y encendido (este queda en la esquina).
        BotonBarra("search", "Buscar") { estado.buscando = true }
        Box {
            BotonBarra("arrow-up-down", "Ordenar") { estado.menu = "orden" }
            MenuOrden(estado)
        }
        BotonBarra("gauge", "Límites y conexiones") { estado.dialogo = Dialogo.Limites }
        BotonBarra("settings", "Opciones") { estado.dialogo = Dialogo.Opciones }
        BotonPuerto(estado)
        BotonEncendido(estado)
    })
}

/** Botón de la barra superior: 40 dp en vez de 48 para que entren seis junto a las velocidades. */
@Composable
private fun BotonBarra(icon: String, desc: String, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        if (tint == Color.Unspecified) Icono(icon, size = 22.dp, desc = desc) else Icono(icon, size = 22.dp, tint = tint, desc = desc)
    }
}

/** El puerto de escucha, del color de su estado (abierto, cerrado, probando); abre el diálogo del puerto. */
@Composable
private fun BotonPuerto(estado: Estado) {
    val c = LocalColores.current
    val s = estado.stats
    if (s.port <= 0) return
    val (cls, text) = estado.portState()
    val next = estado.portNext()
    BotonBarra("ethernet-port", "Puerto ${s.port}: $text" + if (next != 0L) " · $next al reiniciar rTorrent" else "",
        tint = when (cls) { "good" -> c.good; "bad" -> c.bad; "warn" -> c.warn; else -> c.dim }) { estado.dialogo = Dialogo.Puerto }
}

/**
 * rTorrent encendido (verde) o apagado (rojo). Encendido, el toque pide confirmar antes de apagar; apagado, lo enciende.
 * Gris mientras no se sabe (al abrir, o con otro error).
 */
@Composable
private fun BotonEncendido(estado: Estado) {
    val c = LocalColores.current
    val on = estado.conectado == true
    val off = estado.sinRtorrent
    BotonBarra("power", if (on) "rTorrent encendido: tocar para apagarlo" else "rTorrent apagado: tocar para encenderlo",
        tint = if (on) c.good else if (off) c.bad else c.dim) {
        when {
            on -> estado.dialogo = Dialogo.Apagar
            else -> { estado.plataforma.encenderRtorrent(); estado.aviso("Encendiendo rTorrent…"); estado.launch { kotlinx.coroutines.delay(2500); estado.refresh() } }
        }
    }
}

/**
 * "Tablero" y las velocidades, según el ancho que quede junto a los botones. En orden de preferencia:
 * Tablero + velocidades lado a lado, Tablero + una sobre otra, solo lado a lado, solo una sobre otra.
 */
@Composable
private fun TituloYVelocidades(estado: Estado, modifier: Modifier = Modifier) {
    androidx.compose.ui.layout.Layout(content = {
        Text("Tablero", fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
        Velocidades(estado, lado = true)
        Velocidades(estado, lado = false)
    }, modifier = modifier) { ms, cs ->
        val gap = 12.dp.roundToPx()
        val libre = androidx.compose.ui.unit.Constraints()
        val t = ms[0].measure(libre); val fila = ms[1].measure(libre); val col = ms[2].measure(libre)
        val (titulo, v) = listOf(true to fila, true to col, false to fila, false to col)
            .firstOrNull { (ti, v) -> (if (ti) t.width + gap else 0) + v.width <= cs.maxWidth } ?: (false to col)
        val x = if (titulo) t.width + gap else 0
        val h = maxOf(v.height, if (titulo) t.height else 0)
        layout(minOf(x + v.width, cs.maxWidth), h) {
            if (titulo) t.placeRelative(0, (h - t.height) / 2)
            v.placeRelative(x, (h - v.height) / 2)
        }
    }
}

/** Bajada y subida de rTorrent entero (la barra de estado de la web); ⁽ˡ⁾ cuando hay límite. */
@Composable
private fun Velocidades(estado: Estado, lado: Boolean) {
    val c = LocalColores.current
    val s = estado.stats
    val down = "↓ " + (if (s.downRate > 0) Formato.size(s.downRate) else "0 B") + "/s" + (if (s.downLimit > 0) " ⁽ˡ⁾" else "")
    val up = "↑ " + (if (s.upRate > 0) Formato.size(s.upRate) else "0 B") + "/s" + (if (s.upLimit > 0) " ⁽ˡ⁾" else "")
    @Composable fun t(x: String, col: Color) = Text(x, fontSize = 13.sp, color = col, maxLines = 1, softWrap = false)
    if (lado) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { t(down, c.down); t(up, c.up) }
    else Column { t(down, c.down); t(up, c.up) }
}

@Composable
private fun MenuOrden(estado: Estado) {
    val c = LocalColores.current
    DropdownMenu(expanded = estado.menu == "orden", onDismissRequest = { estado.menu = null }, containerColor = c.panel2) {
        val o = estado.prefs.sort
        Text("Ordenar por", fontSize = 12.sp, color = c.dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        for (col in COLUMNS) {
            DropdownMenuItem(text = { Text(col.title) }, leadingIcon = {
                if (o.col == col.id) Icono(if (o.dir > 0) "arrow-up" else "arrow-down", size = 16.dp) else Spacer(Modifier.size(16.dp))
            }, onClick = {
                // Otra vez la misma columna invierte el orden, como un clic en el título de la columna.
                estado.updatePrefs { p -> p.copy(sort = if (o.col == col.id) o.copy(dir = -o.dir) else Orden(col.id, if (col.id == "name") 1 else -1)) }
                estado.menu = null
            })
        }
        HorizontalDivider(color = c.line)
        DropdownMenuItem(text = { Text("Datos de cada fila…") }, leadingIcon = { Icono("columns-3", size = 16.dp) }, onClick = {
            estado.menu = null; estado.dialogo = Dialogo.Campos
        })
        DropdownMenuItem(text = { Text("Vista por defecto (orden y filtro)") }, leadingIcon = { Icono("rotate-ccw", size = 16.dp) }, onClick = {
            estado.menu = null
            estado.updatePrefs { it.copy(sort = Orden("added", -1)) }
            estado.filtro = estado.filtro.copy(text = "")
        })
    }
}

/** El menú de los torrents elegidos o del detalle abierto (rowMenu de la web). */
@Composable
fun MenuTorrents(estado: Estado) {
    val c = LocalColores.current
    val clip = LocalClipboardManager.current
    val ts = estado.objetivo()
    val hs = ts.map { it.hash }
    DropdownMenu(expanded = estado.menu == "torrents" && ts.isNotEmpty(), onDismissRequest = { estado.menu = null }, containerColor = c.panel2) {
        // Como los demás menús: un ícono a la izquierda de cada opción; ✓ a la derecha en lo que está activo.
        fun item(label: String, icon: String? = null, enabled: Boolean = true, check: Boolean? = null, run: () -> Unit) = @Composable {
            DropdownMenuItem(text = { Text(label) }, enabled = enabled, leadingIcon = {
                if (icon != null) Icono(icon, size = 16.dp) else Spacer(Modifier.size(16.dp))
            }, trailingIcon = if (check == true) ({ Icono("check", size = 16.dp, tint = c.accent) }) else null,
                onClick = { estado.menu = null; run() })
        }
        @Composable fun head(t: String) = Text(t, fontSize = 12.sp, color = c.dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        fun copy(text: String) { clip.setText(AnnotatedString(text)); estado.aviso("Copiado") }

        // La prioridad no va aquí: se cambia por torrent en la pestaña Opciones.
        head("Descarga")
        if (ts.all { it.sequential != null }) {
            val on = ts.all { it.sequential == true }
            item("Descargar en orden", "list-ordered", check = on) {
                estado.act("/api/action", mapOf("action" to "sequential", "hashes" to hs, "value" to if (on) 0 else 1),
                    if (on) "Descarga normal (por rareza)" else "Descarga en orden: desde el principio, para ver o escuchar mientras baja")
            }()
        }
        item("Verificar datos", "refresh-cw") {
            estado.act("/api/action", mapOf("action" to "recheck", "hashes" to hs), "Verificación iniciada: se revisa cada pieza contra el .torrent")
        }()
        HorizontalDivider(color = c.line)
        head("Archivos")
        if (ts.size == 1) item("Abrir carpeta", "folder-open") { estado.abrirCarpeta(ts[0].hash) }()
        item("Reubicar datos…", "folder") { estado.dialogo = Dialogo.Reubicar(hs) }()
        item("Renombrar carpeta…", "pencil", enabled = ts.size == 1 && ts[0].files >= 2) { estado.dialogo = Dialogo.RenombrarCarpeta(hs[0]) }()
        HorizontalDivider(color = c.line)
        head("Posición en la cola (#)")
        for ((w, n, i) in listOf(Triple("up", "Subir", "arrow-up"), Triple("down", "Bajar", "arrow-down"), Triple("top", "Al principio", "arrow-up-to-line"), Triple("bottom", "Al final", "arrow-down-to-line"))) item(n, i) {
            estado.act("/api/move", mapOf("hashes" to hs, "where" to w))
        }()
        HorizontalDivider(color = c.line)
        head("Copiar")
        item(if (ts.size > 1) "Nombres" else "Nombre", "copy") { copy(ts.joinToString("\n") { it.name }) }()
        item("Identificador", "copy") { copy(ts.joinToString("\n") { it.hash }) }()
        item("Enlace magnet", "magnet") { copy(ts.joinToString("\n") { magnetOf(it) }) }()
        HorizontalDivider(color = c.line)
        item("Quitar todas las etiquetas", "tag", enabled = ts.any { it.tags.isNotEmpty() }) {
            estado.act("/api/set-tags", mapOf("hashes" to hs, "tags" to emptyList<String>()))
        }()
    }
}

/** Iniciar, Pausar, Detener, Etiquetas y Eliminar, como la barra de arriba de la web. */
@Composable
private fun BarraAcciones(estado: Estado) {
    val c = LocalColores.current
    BottomAppBar(containerColor = c.panel, contentPadding = PaddingValues(horizontal = 4.dp)) {
        val hs = { estado.objetivo().map { it.hash } }
        @Composable fun accion(icon: String, label: String, tint: Color = c.text, run: () -> Unit) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = run).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icono(icon, size = 22.dp, tint = tint)
                Text(label, fontSize = 11.sp, color = tint)
            }
        }
        accion("play", "Iniciar") { estado.act("/api/action", mapOf("action" to "start", "hashes" to hs())) }
        accion("pause", "Pausar") { estado.act("/api/action", mapOf("action" to "pause", "hashes" to hs())) }
        accion("square", "Detener") { estado.act("/api/action", mapOf("action" to "stop", "hashes" to hs())) }
        accion("tag", "Etiquetas") { estado.dialogo = Dialogo.Etiquetas(hs()) }
        accion("trash-2", "Eliminar", c.bad) { estado.dialogo = Dialogo.Eliminar(hs()) }
    }
}

/** Sin conexión con rTorrent o con otro error: el aviso de la barra de estado de la web. */
@Composable
private fun Conexion(estado: Estado) {
    val c = LocalColores.current
    val err = estado.errorConexion ?: return
    Row(Modifier.fillMaxWidth().background(c.bad.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (estado.sinRtorrent) "Sin conexión con rTorrent: está apagado o no responde." else err,
            fontSize = 13.sp, color = c.text, modifier = Modifier.weight(1f))
        if (estado.sinRtorrent) TextButton(onClick = { estado.plataforma.encenderRtorrent() }) {
            Icono("power", size = 16.dp); Spacer(Modifier.width(6.dp)); Text("Encender rTorrent")
        }
    }
}

/** La barra de etiquetas sobre la lista: las que tienen los torrents a la vista, con su cuenta. */
@Composable
private fun BarraEtiquetas(estado: Estado) {
    val c = LocalColores.current
    val counts = HashMap<String, Int>()
    estado.filtered(skipTags = true).forEach { t -> t.tags.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    val f = estado.filtro.tags
    val tags = estado.allTags().filter { it in counts || it in f }
    if (tags.isEmpty()) return
    Row(Modifier.fillMaxWidth().background(c.panel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (f.size > 1) Coincide(estado)
        for (x in tags) {
            Chip(estado, x, "bar", extra = (counts[x] ?: 0).toString(), on = if (f.isEmpty()) null else x in f,
                modifier = Modifier.clickable { estado.toggleTagFilter(x, multi = true) })
        }
    }
}

/** Coincide: con varias etiquetas elegidas, mostrar los que tengan cualquiera o todas. */
@Composable
fun Coincide(estado: Estado) {
    val c = LocalColores.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Coincide", fontSize = 12.sp, color = c.dim)
        for ((v, n) in listOf("any" to "Cualquiera", "all" to "Todas")) {
            val on = estado.prefs.tagMode == v
            Text(n, fontSize = 12.sp, color = if (on) Color.White else c.text,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (on) c.accent else c.panel2)
                    .clickable { estado.updatePrefs { it.copy(tagMode = v) } }.padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}

@Composable
private fun ListaTorrents(estado: Estado) {
    val c = LocalColores.current
    val rows = estado.rows()
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (estado.conectado == true) Text(
                if (estado.torrents.isNotEmpty()) "Ningún torrent coincide con el filtro." else "No hay torrents. Usa Agregar.",
                color = c.dim, fontSize = 14.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(rows, key = { it.hash }) { t ->
            val selected = t.hash in estado.sel
            FilaTorrent(estado, t, selected,
                onClick = {
                    // Con algo elegido, tocar suma o quita; si no, abre su detalle.
                    if (estado.sel.isNotEmpty()) estado.sel = if (selected) estado.sel - t.hash else estado.sel + t.hash
                    else estado.abrirDetalle(t.hash)
                },
                onLongClick = { estado.sel = if (selected) estado.sel - t.hash else estado.sel + t.hash })
        }
    }
}

// ------------------------------------------------------------ barra lateral

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Filtros(estado: Estado) {
    val c = LocalColores.current
    val f = estado.filtro
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        @Composable fun head(t: String, extra: @Composable () -> Unit = {}) = Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(t.uppercase(), fontSize = 11.sp, color = c.dim, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)); extra()
        }
        @Composable fun fila(on: Boolean, count: Int, countBg: Color = c.panel2, onClick: () -> Unit, onLong: (() -> Unit)? = null, lead: @Composable () -> Unit) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(6.dp)).background(if (on) c.sel else Color.Transparent)
                .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { lead() }
                Text(count.toString(), fontSize = 12.sp, color = c.text, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(countBg).padding(horizontal = 7.dp, vertical = 1.dp))
            }
        }
        head("Estado")
        val base = estado.filtered(skipStatus = true)
        for (s in STATUS_FILTERS) fila(f.status == s.id, base.count(s.test), onClick = { estado.filtro = f.copy(status = s.id); estado.cajon = false }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icono(s.icon, size = 16.dp, tint = c.dim); Text(s.name, fontSize = 14.sp)
            }
        }
        head("Etiquetas") {
            if (!estado.prefs.tagbar && f.tags.size > 1) Coincide(estado)
            Box(Modifier.clip(RoundedCornerShape(6.dp)).clickable { estado.cajon = false; estado.dialogo = Dialogo.Etiqueta(null) }.padding(4.dp)) {
                Icono("plus", size = 18.dp, tint = c.dim, desc = "Nueva etiqueta")
            }
        }
        val forTags = estado.filtered(skipTags = true)
        val counts = HashMap<String, Int>()
        var none = 0
        forTags.forEach { t -> if (t.tags.isEmpty()) none++; t.tags.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        for (name in estado.allTags()) {
            val n = counts[name] ?: 0
            var menu by remember { mutableStateOf(false) }
            Box {
                fila(name in f.tags, n, countBg = if (n > 0) tagColor(estado.tagConf, name).copy(alpha = 0.28f) else c.panel2,
                    onClick = { estado.toggleTagFilter(name, multi = false) }, onLong = { menu = true }) {
                    SideTag(estado, name)
                }
                MenuEtiqueta(estado, name, menu) { menu = false }
            }
        }
        fila(NO_TAG in f.tags, none, onClick = { estado.toggleTagFilter(NO_TAG, multi = false) }, onLong = { estado.toggleTagFilter(NO_TAG, multi = true) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icono("ban", size = 16.dp, tint = c.dim); Text("Sin etiqueta", fontSize = 14.sp)
            }
        }
        Text("Toque largo: filtrar por varias, editar u ordenar", fontSize = 11.sp, color = c.dim, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        val forTr = estado.filtered(skipTracker = true)
        val tr = forTr.filter { it.tracker.isNotEmpty() }.groupingBy { it.tracker }.eachCount()
        if (tr.isNotEmpty()) {
            head("Rastreadores")
            for (h in tr.keys.sorted()) fila(f.tracker == h, tr.getValue(h), onClick = {
                estado.filtro = f.copy(tracker = if (f.tracker == h) null else h); estado.cajon = false
            }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icono("globe", size = 16.dp, tint = c.dim)
                    Text(h, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
