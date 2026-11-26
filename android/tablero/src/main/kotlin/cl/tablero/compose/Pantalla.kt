package cl.tablero.compose

import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
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
            // La hoja llega hasta abajo; la línea sobre la versión queda a la altura del borde de la barra de navegación
            // y la versión, dentro de esa franja.
            ModalDrawerSheet(drawerContainerColor = c.panel, windowInsets = WindowInsets.statusBars, modifier = Modifier.width(300.dp)) {
                // Los filtros se desplazan; la versión queda fija abajo, siempre a la vista.
                Box(Modifier.weight(1f)) { Filtros(estado) }
                HorizontalDivider(color = c.line)
                val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Box(Modifier.fillMaxWidth().height(maxOf(nav, 36.dp)).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    if (estado.version.isNotEmpty()) Text("Tablero ${estado.version}", fontSize = 11.sp, color = c.dim)
                }
            }
        }) {
            Scaffold(
                containerColor = c.bg,
                topBar = { if (det != null) BarraDetalle(estado, det) else BarraSuperior(estado) },
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
        TituloYVelocidades(estado) { estado.grafico = !estado.grafico }
    }, actions = {
        // De izquierda a derecha: buscar, ordenar, límites, opciones, puerto y encendido (este queda en la esquina).
        BotonBarra("search", "Buscar") { estado.buscando = true }
        BotonBarra("arrow-up-down", "Ordenar") { estado.dialogo = Dialogo.Orden }
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
private fun TituloYVelocidades(estado: Estado, onVelocidades: () -> Unit) {
    // Solo las velocidades abren el gráfico; el logo y el título no hacen nada.
    val toque = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onVelocidades).padding(horizontal = 4.dp, vertical = 2.dp)
    androidx.compose.ui.layout.Layout(content = {
        MarcaTablero(estado)
        LogoTablero(estado, 22.dp)
        Box(toque) { Velocidades(estado, lado = true) }
        Box(toque) { Velocidades(estado, lado = false) }
    }) { ms, cs ->
        val gap = 12.dp.roundToPx()
        val libre = androidx.compose.ui.unit.Constraints()
        // Sin espacio para el título, queda solo el logo.
        val t = ms[0].measure(libre).let { if (it.width + gap + ms[3].minIntrinsicWidth(0) <= cs.maxWidth) it else ms[1].measure(libre) }
        val fila = ms[2].measure(libre); val col = ms[3].measure(libre)
        val (titulo, v) = listOf(true to fila, true to col)
            .firstOrNull { (ti, v) -> (if (ti) t.width + gap else 0) + v.width <= cs.maxWidth } ?: (true to col)
        val x = if (titulo) t.width + gap else 0
        val h = maxOf(v.height, if (titulo) t.height else 0)
        layout(minOf(x + v.width, cs.maxWidth), h) {
            if (titulo) t.placeRelative(0, (h - t.height) / 2)
            v.placeRelative(x, (h - v.height) / 2)
        }
    }
}

/**
 * La marca de la web (.brand): el logo y "Tablero" con "para rTorrent" chico debajo, desde un tercio de "Tablero"
 * hasta su borde derecho (fitBrand de app.js); el logo va del alto de "Tablero" al pie de "para rTorrent".
 */
@Composable
private fun MarcaTablero(estado: Estado) {
    val c = LocalColores.current
    val medidor = androidx.compose.ui.text.rememberTextMeasurer()
    val dens = androidx.compose.ui.platform.LocalDensity.current
    // Como .brand-name de app.css: "Tablero" en 600 y "para rTorrent" en 400, con la fuente de la marca.
    val marca = estado.plataforma.fuenteMarca
    val grande = androidx.compose.ui.text.TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, fontFamily = marca)
    val anchoT = medidor.measure("Tablero", grande).size.width
    val anchoP = medidor.measure("para rTorrent", androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = marca)).size.width
    val chico = (10f * (anchoT * 2f / 3f) / anchoP.coerceAtLeast(1)).sp
    val anchoTdp = with(dens) { anchoT.toDp() }
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LogoTablero(estado, 0.dp, Modifier.fillMaxHeight().aspectRatio(1f))
        Column {
            Text("Tablero", style = grande, color = c.text, maxLines = 1, softWrap = false)
            Text("para rTorrent", fontSize = chico, lineHeight = chico, fontFamily = marca, color = c.dim, maxLines = 1, softWrap = false,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.padding(start = anchoTdp / 3).width(anchoTdp * 2 / 3))
        }
    }
}

/**
 * El logo de Tablero según el estado, con los íconos de android/tablero/docs/iconos: normal (o claro con el tema
 * claro), error (un "!" rojo en el círculo) y sin conexión con rTorrent (en grises).
 */
@Composable
fun LogoTablero(estado: Estado, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier.size(size)) {
    val sinConexion = estado.errorConexion != null || estado.conectado == false
    val conError = !sinConexion && estado.torrents.any { it.status == "error" }
    val claro = estado.prefs.theme == "light"
    // El logo de la web recortado al dibujo (viewBox 4 4 56 56, sin el marco): cuadrado, la "r" negra entre los colores.
    androidx.compose.foundation.Canvas(modifier) {
        val k = this.size.width / 56f
        fun p(d: String) = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath()
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
        fun sz(w: Float, h: Float) = androidx.compose.ui.geometry.Size(w, h)
        val amarillo = p("M16 4h44v16H32a16 16 0 0 0-16 16z")
        val rojo = p("M46 20h14v40H26V36a6 6 0 0 1 6-6h14z")
        val centro = o(50f, 50f)
        scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            translate(-4f, -4f) {
                // Los colores de android/tablero/docs/iconos (tablero, -claro, -error, -sinconexion).
                val fondo = if (claro) Color(0xFFF2EFE6) else Color(0xFF1A1A1A)
                drawRect(fondo, topLeft = o(4f, 4f), size = sz(56f, 56f))
                drawRect(if (sinConexion) Color(0xFF5F6368) else Color(0xFF1D4E9E), topLeft = o(4f, 4f), size = sz(12f, 56f))
                drawPath(amarillo, if (sinConexion) Color(0xFFA3A7AD) else Color(0xFFF2C200))
                drawPath(rojo, if (sinConexion) Color(0xFF7D8186) else Color(0xFFD7262E))
                when {
                    sinConexion -> drawCircle(Color(0xFFD3D5D8), radius = 6f, center = centro)
                    conError -> {
                        drawCircle(Color(0xFFF2EFE6), radius = 10f, center = o(49.5f, 49.5f))
                        drawRoundRect(Color(0xFFD7262E), topLeft = o(47.3f, 42f), size = sz(4.4f, 9.2f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.6f))
                        drawCircle(Color(0xFFD7262E), radius = 2.3f, center = o(49.5f, 55.2f))
                    }
                    else -> drawCircle(if (claro) Color(0xFF1A1A1A) else Color(0xFFF2EFE6), radius = 6f, center = centro)
                }
            }
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

/**
 * Las acciones sobre los torrents elegidos o el abierto, todas a la vista: Iniciar, Pausar, Detener, Etiquetas, las de
 * los datos (Abrir, Reubicar, Renombrar, Verificar), Posición si la lista va por # y Eliminar al final. Si no caben
 * (teléfono), la barra se desplaza de lado.
 */
@Composable
private fun BarraAcciones(estado: Estado) {
    val c = LocalColores.current
    val ts = estado.objetivo()
    val hs = { estado.objetivo().map { it.hash } }
    val uno = ts.size == 1
    val posicion = estado.detalle == null && estado.prefs.sort.col == "pos"
    val n = 9 + (if (posicion) 1 else 0)
    if (estado.moviendo && posicion) { BarraMover(estado); return }
    BottomAppBar(containerColor = c.panel, contentPadding = PaddingValues(horizontal = 4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val min = 68.dp
            val cabe = maxWidth >= min * n
            val ancho = if (cabe) maxWidth / n else min
            Row(if (cabe) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())) {
                @Composable fun accion(icon: String, label: String, tint: Color = c.text, enabled: Boolean = true, run: () -> Unit) {
                    val t = if (enabled) tint else c.dim.copy(alpha = 0.5f)
                    Column(Modifier.width(ancho).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, onClick = run).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icono(icon, size = 22.dp, tint = t)
                        Text(label, fontSize = 11.sp, color = t, maxLines = 1)
                    }
                }
                accion("play", "Iniciar") { estado.act("/api/action", mapOf("action" to "start", "hashes" to hs())) }
                accion("pause", "Pausar") { estado.act("/api/action", mapOf("action" to "pause", "hashes" to hs())) }
                accion("square", "Detener") { estado.act("/api/action", mapOf("action" to "stop", "hashes" to hs())) }
                accion("tag", "Etiquetas") { estado.dialogo = Dialogo.Etiquetas(hs()) }
                accion("folder-open", "Abrir", enabled = uno) { estado.abrirCarpeta(ts[0].hash) }
                accion("folder", "Reubicar") { estado.dialogo = Dialogo.Reubicar(hs()) }
                accion("pencil", "Renombrar", enabled = uno && ts[0].files >= 2) { estado.dialogo = Dialogo.RenombrarCarpeta(ts[0].hash) }
                accion("refresh-cw", "Verificar") {
                    estado.act("/api/action", mapOf("action" to "recheck", "hashes" to hs()), "Verificación iniciada: se revisa cada pieza contra el .torrent")
                }
                if (posicion) accion("list-ordered", "Posición") { estado.moviendo = true }
                accion("trash-2", "Eliminar", c.bad) { estado.dialogo = Dialogo.Eliminar(hs()) }
            }
        }
    }
}

/**
 * Posición (#) en vivo: cada toque mueve los elegidos y la lista se reordena detrás, siguiéndolos. Listo (o Atrás)
 * vuelve a la barra de acciones.
 */
@Composable
private fun BarraMover(estado: Estado) {
    val c = LocalColores.current
    BottomAppBar(containerColor = c.panel, contentPadding = PaddingValues(horizontal = 4.dp)) {
        @Composable fun boton(icon: String, label: String, tint: Color = c.text, run: () -> Unit) {
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = run).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icono(icon, size = 22.dp, tint = tint)
                Text(label, fontSize = 11.sp, color = tint, maxLines = 1)
            }
        }
        boton("arrow-up-to-line", "Al principio") { estado.mover("top") }
        boton("arrow-up", "Subir") { estado.mover("up") }
        boton("arrow-down", "Bajar") { estado.mover("down") }
        boton("arrow-down-to-line", "Al final") { estado.mover("bottom") }
        boton("check", "Listo", c.accent) { estado.moviendo = false }
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
    val lista = rememberLazyListState()
    // Al mover la posición, la lista sigue al primero de los elegidos para que se vea dónde quedó.
    val primero = if (estado.moviendo) rows.indexOfFirst { it.hash in estado.sel } else -1
    LaunchedEffect(primero) {
        if (primero < 0) return@LaunchedEffect
        val vis = lista.layoutInfo.visibleItemsInfo
        if (vis.isEmpty() || primero <= vis.first().index || primero >= vis.last().index) lista.animateScrollToItem(maxOf(0, primero - 1))
    }
    LazyColumn(Modifier.fillMaxSize(), state = lista, contentPadding = PaddingValues(bottom = 88.dp)) {
        items(rows, key = { it.hash }) { t ->
            val selected = t.hash in estado.sel
            FilaTorrent(estado, t, selected, Modifier.animateItem(),
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
