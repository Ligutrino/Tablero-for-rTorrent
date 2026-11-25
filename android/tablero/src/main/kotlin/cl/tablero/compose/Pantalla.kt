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
        ModalNavigationDrawer(drawerState = drawer, drawerContent = {
            ModalDrawerSheet(drawerContainerColor = c.panel, modifier = Modifier.width(300.dp)) { Filtros(estado) }
        }) {
            Scaffold(
                containerColor = c.bg,
                topBar = { BarraSuperior(estado) },
                bottomBar = { if (estado.sel.isNotEmpty()) BarraAcciones(estado) },
                floatingActionButton = {
                    if (estado.sel.isEmpty()) FloatingActionButton(onClick = { estado.dialogo = Dialogo.Agregar() }, containerColor = c.accent, contentColor = Color.White) {
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
                Column(Modifier.padding(pad).fillMaxSize()) {
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
        Column {
            Text("Tablero", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Velocidades(estado)
        }
    }, actions = {
        IconButton(onClick = { estado.buscando = true }) { Icono("search", size = 22.dp, desc = "Buscar") }
        Box {
            IconButton(onClick = { estado.menu = "orden" }) { Icono("arrow-up-down", size = 22.dp, desc = "Ordenar") }
            MenuOrden(estado)
        }
        // Mientras la interfaz Compose no tenga todo, la web de siempre sigue a un toque.
        if (estado.plataforma.tieneWeb) IconButton(onClick = { estado.plataforma.abrirWeb() }) { Icono("globe", size = 22.dp, desc = "Interfaz web") }
    })
}

/** Bajada y subida de rTorrent entero (la barra de estado de la web); ⁽ˡ⁾ cuando hay límite. */
@Composable
private fun Velocidades(estado: Estado) {
    val c = LocalColores.current
    val s = estado.stats
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("↓ " + (if (s.downRate > 0) Formato.size(s.downRate) else "0 B") + "/s" + (if (s.downLimit > 0) " ⁽ˡ⁾" else ""), fontSize = 12.sp, color = c.down)
        Text("↑ " + (if (s.upRate > 0) Formato.size(s.upRate) else "0 B") + "/s" + (if (s.upLimit > 0) " ⁽ˡ⁾" else ""), fontSize = 12.sp, color = c.up)
    }
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

/** El menú de los torrents elegidos (rowMenu de la web), menos lo que llega en la ronda 2. */
@Composable
private fun MenuTorrents(estado: Estado) {
    val c = LocalColores.current
    val clip = LocalClipboardManager.current
    val ts = estado.selected()
    val hs = ts.map { it.hash }
    DropdownMenu(expanded = estado.menu == "torrents" && ts.isNotEmpty(), onDismissRequest = { estado.menu = null }, containerColor = c.panel2) {
        fun item(label: String, enabled: Boolean = true, check: Boolean? = null, run: () -> Unit) = @Composable {
            DropdownMenuItem(text = { Text(label) }, enabled = enabled, leadingIcon = {
                if (check == true) Icono("check", size = 16.dp) else Spacer(Modifier.size(16.dp))
            }, onClick = { estado.menu = null; run() })
        }
        @Composable fun head(t: String) = Text(t, fontSize = 12.sp, color = c.dim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        fun copy(text: String) { clip.setText(AnnotatedString(text)); estado.aviso("Copiado") }

        head("Prioridad")
        for ((v, n) in listOf(3 to "Alta", 2 to "Normal", 1 to "Baja")) item(n, check = ts.all { it.priority == v }) {
            estado.act("/api/action", mapOf("action" to "priority", "hashes" to hs, "value" to v))
        }()
        HorizontalDivider(color = c.line)
        head("Posición (#)")
        for ((w, n) in listOf("up" to "Subir", "down" to "Bajar", "top" to "Al principio", "bottom" to "Al final")) item(n) {
            estado.act("/api/move", mapOf("hashes" to hs, "where" to w))
        }()
        HorizontalDivider(color = c.line)
        if (ts.all { it.sequential != null }) {
            val on = ts.all { it.sequential == true }
            item("Descargar en orden", check = on) {
                estado.act("/api/action", mapOf("action" to "sequential", "hashes" to hs, "value" to if (on) 0 else 1),
                    if (on) "Descarga normal (por rareza)" else "Descarga en orden: desde el principio, para ver o escuchar mientras baja")
            }()
        }
        item("Verificar datos") {
            estado.act("/api/action", mapOf("action" to "recheck", "hashes" to hs), "Verificación iniciada: se revisa cada pieza contra el .torrent")
        }()
        item("Quitar todas las etiquetas", enabled = ts.any { it.tags.isNotEmpty() }) {
            estado.act("/api/set-tags", mapOf("hashes" to hs, "tags" to emptyList<String>()))
        }()
        item(if (ts.size > 1) "Copiar nombres" else "Copiar nombre") { copy(ts.joinToString("\n") { it.name }) }()
        item("Copiar identificador") { copy(ts.joinToString("\n") { it.hash }) }()
        item("Copiar enlace magnet") { copy(ts.joinToString("\n") { magnetOf(it) }) }()
    }
}

/** Iniciar, Pausar, Detener, Etiquetas y Eliminar, como la barra de arriba de la web. */
@Composable
private fun BarraAcciones(estado: Estado) {
    val c = LocalColores.current
    BottomAppBar(containerColor = c.panel, contentPadding = PaddingValues(horizontal = 4.dp)) {
        val hs = { estado.selected().map { it.hash } }
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
                    // Con algo elegido, tocar suma o quita; si no, elige solo ese (el detalle llega en la ronda 2).
                    estado.sel = if (estado.sel.isNotEmpty()) (if (selected) estado.sel - t.hash else estado.sel + t.hash) else setOf(t.hash)
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
        head("Etiquetas") { if (!estado.prefs.tagbar && f.tags.size > 1) Coincide(estado) }
        val forTags = estado.filtered(skipTags = true)
        val counts = HashMap<String, Int>()
        var none = 0
        forTags.forEach { t -> if (t.tags.isEmpty()) none++; t.tags.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        for (name in estado.allTags()) {
            val n = counts[name] ?: 0
            fila(name in f.tags, n, countBg = if (n > 0) tagColor(estado.tagConf, name).copy(alpha = 0.28f) else c.panel2,
                onClick = { estado.toggleTagFilter(name, multi = false) }, onLong = { estado.toggleTagFilter(name, multi = true) }) {
                SideTag(estado, name)
            }
        }
        fila(NO_TAG in f.tags, none, onClick = { estado.toggleTagFilter(NO_TAG, multi = false) }, onLong = { estado.toggleTagFilter(NO_TAG, multi = true) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icono("ban", size = 16.dp, tint = c.dim); Text("Sin etiqueta", fontSize = 14.sp)
            }
        }
        Text("Toque largo: elegir varias", fontSize = 11.sp, color = c.dim, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
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
