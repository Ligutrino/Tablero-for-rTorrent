package cl.tablero.compose

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.tablero.backend.Json
import kotlinx.coroutines.delay

/*
 * Límites y conexiones, Estado del puerto, Opciones y Apagar rTorrent (los diálogos de la barra de estado
 * y del engranaje de la web).
 */

/** rtorrentCmdHTML de la web en Android: el botón que enciende o reinicia rTorrent. */
@Composable
fun BotonRtorrent(estado: Estado, kind: String) {
    if (kind == "start") BotonHerr("power", "Encender rTorrent") { estado.plataforma.encenderRtorrent(); estado.launch { delay(2500); estado.refresh() } }
    else BotonHerr("power", "Reiniciar rTorrent") { estado.plataforma.reiniciarRtorrent(); estado.aviso("Reiniciando rTorrent…"); estado.launch { delay(4000); estado.refresh() } }
}

private fun kib(b: Long) = (b / Formato.kb()).toString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LimitesDialogo(estado: Estado) {
    val c = LocalColores.current
    val s = estado.stats
    var g by remember { mutableStateOf<GeneralConf?>(null) }
    LaunchedEffect(Unit) { g = estado.loadGeneral() ?: GeneralConf() }
    val gg = g ?: run {
        Ventana(estado, "Límites y conexiones", null) { Text("Cargando…", color = c.dim) }
        return
    }
    val gen = gg.general
    var down by remember { mutableStateOf(kib(s.downLimit)) }
    var up by remember { mutableStateOf(kib(s.upLimit)) }
    var sockets by remember { mutableStateOf(gen["max_sockets"]?.let { lng(it).toString() } ?: "") }
    var pmax by remember { mutableStateOf(gen["peers_max"]?.let { lng(it).toString() } ?: "") }
    var umax by remember { mutableStateOf(gen["uploads_max"]?.let { lng(it).toString() } ?: "") }
    var rOn by remember { mutableStateOf(dbl(gen["stop_ratio"]) != 0.0) }
    var hOn by remember { mutableStateOf(dbl(gen["stop_hours"]) != 0.0) }
    var ratio by remember { mutableStateOf(dbl(gen["stop_ratio"]).takeIf { it != 0.0 }?.let { Formato.n1(it).removeSuffix(",0") } ?: "2") }
    var hours by remember { mutableStateOf(dbl(gen["stop_hours"]).takeIf { it != 0.0 }?.let { Formato.n1(it).removeSuffix(",0") } ?: "24") }
    val unit = Formato.units()[1] + "/s"
    Ventana(estado, "Límites y conexiones", "Aplicar", onOk = {
        fun peer(v: String) = if (v.trim().isEmpty()) null else maxOf(0L, numero(v).toLong())
        val general = linkedMapOf<String, Any?>(
            "max_sockets" to peer(sockets), "peers_max" to peer(pmax), "uploads_max" to peer(umax),
            "stop_ratio" to if (rOn) numero(ratio) else 0.0, "stop_hours" to if (hOn) numero(hours) else 0.0,
        )
        estado.intenta {
            estado.api.post("/api/limits", mapOf("down" to (numero(down) * Formato.kb()).toLong(), "up" to (numero(up) * Formato.kb()).toLong()))
            estado.api.post("/api/general", general)
            estado.loadGeneral()
            estado.aviso("Límites y conexiones aplicados")
            estado.refresh()
        }
    }) {
        Campo("Velocidad") {
            FilaNum("Bajada máxima") { NumCampo(down, { down = it }, unit) }
            FilaNum("Subida máxima") { NumCampo(up, { up = it }, unit) }
            Hint("0 = sin límite. Se aplica a todos los torrents juntos; cada torrent puede tener su propio límite en la pestaña Opciones.")
        }
        Campo("Conexiones") {
            FilaNum("En total") { NumCampo(sockets, { sockets = it }, "conexiones", gg.rtorrent["max_sockets"]?.let { lng(it).toString() }) }
            FilaNum("Pares por torrent") { NumCampo(pmax, { pmax = it }, "pares", gg.torrent["peers_max"]?.let { lng(it).toString() }) }
            FilaNum("Subidas por torrent") { NumCampo(umax, { umax = it }, "pares", gg.torrent["uploads_max"]?.let { lng(it).toString() }) }
            Hint("\"En total\" es para todos los torrents juntos y evita que muchas conexiones llenen el router y pongan lento el internet del resto de la casa. " +
                "Pares y subidas por torrent valen para cada torrent sin valores propios en su pestaña Opciones; el mínimo de pares es la mitad. " +
                "Vienen con 512 en total (lo mínimo que acepta rTorrent) y los valores de BiglyBT por torrent (50 y 4). Vacío = lo que diga rTorrent, en gris.")
        }
        Campo("Al sembrar") {
            FilaCasillaNum(rOn, { rOn = it }, "Detener al llegar a una proporción de subida de") { NumCampo(ratio, { ratio = it }, decimal = true) }
            FilaCasillaNum(hOn, { hOn = it }, "Detener después de") { NumCampo(hours, { hours = it }, "horas", decimal = true) }
            Hint("Tablero detiene cada torrent completo que cumpla la condición, salvo los que tienen su propia regla en la pestaña Opciones. Funciona mientras Tablero esté corriendo.")
        }
    }
}

@Composable
fun PuertoDialogo(estado: Estado) {
    val s = estado.stats
    val (cls, text) = estado.portState()
    val next = estado.portNext()
    val fwd = if (next != 0L) next else s.port
    var nuevo by remember { mutableStateOf((if (next != 0L) next else s.port).takeIf { it > 0 }?.toString() ?: "") }
    var setRes by remember { mutableStateOf<Pair<String, String>?>(null) }
    var setRestart by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<Pair<String, String>?>(null) }
    var ip by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { ip = try { str(estado.api.get("/api/local-ip")["ip"]) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; "" } }
    val seen = when {
        s.incoming > 0 -> "${s.incoming} par${if (s.incoming > 1) "es" else ""} conectado${if (s.incoming > 1) "s" else ""} desde afuera ahora"
        s.incomingSeen > 0 -> "Ninguna ahora; la última hace ${Formato.eta(maxOf(60, System.currentTimeMillis() / 1000 - s.incomingSeen))}"
        else -> "Ninguna desde que se inició Tablero"
    }
    Ventana(estado, "Estado del puerto", null, cancel = "Cerrar") {
        Campo("Puerto") {
            Text("${if (s.port > 0) s.port else "?"} (TCP y UDP)" + if (s.portOpen) "" else " · rTorrent no lo tiene abierto", fontSize = 14.sp)
            if (next != 0L) { Text("Cambia a $next al reiniciar rTorrent:", fontSize = 14.sp); BotonRtorrent(estado, "restart") }
            Hint("Por este puerto llegan las conexiones de otros pares. Si está cerrado igual puedes bajar, pero conectas con menos pares.")
        }
        Campo("Cambiar puerto") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumCampo(nuevo, { nuevo = it })
                BotonHerr("check", "Cambiar") {
                    setRes = "" to "Guardando…"; setRestart = false
                    estado.launch {
                        try {
                            val r = estado.api.post("/api/port", mapOf("port" to nuevo))
                            setRes = when {
                                bool(r["demo"]) -> "" to "Modo demo: aquí se guardaría el puerto ${lng(r["port"])} en la configuración de rTorrent."
                                bool(r["saved"]) -> { setRestart = true; "good" to "Guardado en ${str(r["rc"])}. Para aplicarlo, reinicia rTorrent:" }
                                str(r["rc"]).isNotEmpty() -> "warn" to "No tengo permiso para escribir en ${str(r["rc"])}. Cambia ahí la línea del puerto (o agrégala) por: ${str(r["line"])} y luego reinicia rTorrent."
                                else -> "warn" to "No encontré el archivo de configuración de rTorrent. En él, cambia la línea del puerto (o agrégala) por: ${str(r["line"])} y luego reinicia rTorrent."
                            }
                            estado.refresh()
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; setRes = "bad" to e.texto() }
                    }
                }
            }
            setRes?.let { (k, t) -> Marca(k, t) }
            if (setRestart) BotonRtorrent(estado, "restart")
            Hint("Entre 1024 y 65535. rTorrent abre el puerto solo al iniciar: Tablero lo guarda en su archivo de configuración y el cambio se aplica al reiniciarlo. Después, reenvía el puerto nuevo en el router.")
        }
        Campo("Conexiones entrantes") {
            Text(seen, fontSize = 14.sp)
            Hint("Si llegan pares desde internet, el puerto está abierto. Si no llega ninguno, puede estar cerrado o simplemente no hay pares buscando.")
        }
        Campo("Prueba") {
            // El botón y el resultado, uno al lado del otro.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BotonHerr("search", "Probar puerto") {
                    test = "" to "Probando…"
                    estado.launch {
                        test = try {
                            val r = estado.probarPuerto()
                            if (bool(r["demo"])) "" to "Modo demo: aquí diría si el puerto está abierto."
                            else if (bool(r["reachable"])) "good" to "Abierto: ifconfig.co pudo conectarse a ${str(r["ip"])}:${lng(r["port"])}."
                            else "bad" to "Cerrado: ifconfig.co no pudo conectarse a ${str(r["ip"])}:${lng(r["port"])}."
                        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; "bad" to e.texto() }
                    }
                }
                val (k, t) = test ?: (cls to "Estado: $text")
                androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { Marca(k, t) }
            }
            Hint("Le pide a ifconfig.co que intente conectarse a tu IP pública en este puerto. Solo se envía el número de puerto. Tablero hace esta prueba solo al iniciar.")
        }
        Campo("Si está cerrado") {
            Text("En el router, reenvía el puerto $fwd (TCP y UDP) a la IP local de $THIS_DEVICE${if (ip.isNotEmpty()) " ($ip)" else ""}. Con VPN, el puerto debe abrirse en la VPN. " +
                "Con datos móviles casi nunca se puede abrir: la compañía comparte tu IP pública con otros clientes.", fontSize = 14.sp)
        }
    }
}

@Composable
fun ApagarDialogo(estado: Estado) {
    val c = LocalColores.current
    val n = estado.torrents.count { it.state }
    val busy = estado.torrents.filter { it.jobState != null && it.jobState != "error" }
    Ventana(estado, "Apagar rTorrent", "Apagar", danger = true, onOk = {
        try {
            val r = estado.api.post("/api/shutdown")
            estado.aviso(if (bool(r["demo"])) "En el modo demo no hay rTorrent que apagar" else "Apagando rTorrent…")
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; estado.aviso(e.texto(), true) }
        estado.launch { delay(1500); estado.refresh() }
        true
    }) {
        Campo("Qué pasa") {
            Text("rTorrent se cierra y deja de bajar y subir" + (if (n > 0) " ($n torrent${if (n > 1) "s" else ""} iniciado${if (n > 1) "s" else ""})" else "") +
                ". Antes guarda la sesión, así que al encenderlo cada torrent vuelve como estaba.", fontSize = 14.sp)
            Hint("Tablero sigue abierto, pero sin conexión hasta que rTorrent vuelva.")
        }
        if (busy.isNotEmpty()) Campo("En curso") {
            Marca("warn", "Reubicar datos sigue trabajando en ${if (busy.size == 1) busy[0].name else "${busy.size} torrents"}. " +
                "Si apagas ahora, los archivos terminan de moverse, pero no se verifican ni se reanudan: al encender rTorrent, usa Verificar datos e Inicia el torrent.")
        }
        Campo("Encenderlo") { Text("Después, con el botón de encendido de arriba, que queda en rojo mientras rTorrent está apagado.", fontSize = 14.sp, color = c.text) }
    }
}

/** settingsDialog de la web. */
@Composable
fun OpcionesDialogo(estado: Estado) {
    val c = LocalColores.current
    val p = estado.prefs
    var theme by remember { mutableStateOf(p.theme) }
    var tint by remember { mutableStateOf(p.tint) }
    var tagbar by remember { mutableStateOf(p.tagbar) }
    var mode by remember { mutableStateOf(p.tagMode) }
    var units by remember { mutableStateOf(p.units) }
    var interval by remember { mutableStateOf(p.interval.toString()) }
    var net by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var lan by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var import by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { net = estado.api.get("/api/access").also { lan = bool(it["lan"]) } } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
    }
    Ventana(estado, "Opciones", "Guardar", onOk = guardar@{
        val n = net
        if (n != null && bool(n["local"]) && (lan != bool(n["lan"]) || pw.isNotEmpty())) {
            val ok = estado.intenta {
                val r = estado.api.post("/api/access", mapOf("lan" to lan, "password" to pw))
                estado.aviso(if (bool(r["restart"])) (if (lan) "Tablero se reinicia para abrirse a la red…" else "Tablero se reinicia para escuchar solo en $THIS_DEVICE…")
                    else if (pw.isNotEmpty()) "Contraseña guardada" else "Guardado")
            }
            if (!ok) return@guardar false
        }
        var map: Map<String, Any?>? = null
        if (import.isNotBlank()) {
            map = try { asMap(Json.parse(import.trim())).takeIf { Json.parse(import.trim()) is Map<*, *> } } catch (e: Exception) { null }
            if (map == null) { estado.aviso("El texto pegado no es JSON válido", true); return@guardar false }
        }
        estado.updatePrefs {
            it.copy(theme = theme, tint = tint, tagbar = tagbar, tagMode = mode, units = units, interval = maxOf(1, interval.toIntOrNull() ?: 2))
        }
        if (map != null) {
            val tags = estado.tagConf.tags.toMutableMap()
            for ((k, v) in map) { val o = asMap(v); if (str(o["color"]).isNotEmpty()) tags[k] = TagEntry(color = str(o["color"])) }
            estado.saveTagConf(estado.tagConf.copy(tags = tags))
            estado.aviso("${map.size} etiquetas importadas")
        }
        estado.refresh()
        true
    }) {
        Campo("Tema") { Seg(listOf("dark" to "Oscuro", "light" to "Claro"), theme) { theme = it } }
        Campo("Lista") {
            CheckFila(tint, { tint = it }) { Text("Teñir las filas con el color de su primera etiqueta", fontSize = 14.sp) }
            CheckFila(tagbar, { tagbar = it }) { Text("Mostrar la barra de etiquetas sobre la lista", fontSize = 14.sp) }
        }
        Campo("Filtro") {
            for ((v, n) in listOf("any" to "Mostrar torrents con cualquiera de las etiquetas elegidas", "all" to "Mostrar solo torrents con todas las etiquetas elegidas"))
                Opcion(mode == v, { mode = v }, n)
            Hint("Cuando filtras por varias etiquetas a la vez (toque largo en una etiqueta y Agregar al filtro).")
        }
        Campo("Unidades") {
            for ((v, n) in listOf("bin" to "Binarias: KiB, MiB, GiB", "dec" to "Decimales: KB, MB, GB"))
                Opcion(units == v, { units = v }, n)
            Hint("Para tamaños, velocidades y límites. Binarias: 1 KiB = 1024 bytes, como cuenta rTorrent. Decimales: 1 KB = 1000 bytes, como los discos y la mayoría de las páginas.")
        }
        Campo("Actualizar") {
            FilaNum("Cada") { NumCampo(interval, { interval = it.take(2) }, "segundos") }
        }
        Campo("Respaldo") {
            BotonHerr("archive", "Respaldo y restauración…") { estado.dialogo = Dialogo.Respaldos }
            Hint("Para cambiar de teléfono/tableta o pasar todo a un PC: etiquetas, opciones y los torrents de rTorrent con su avance.")
        }
        Campo("Red") {
            val n = net
            val local = n != null && bool(n["local"])
            CheckFila(lan, { if (local) lan = it }) { Text("Permitir abrir Tablero desde otros equipos de la red", fontSize = 14.sp) }
            if (n != null && (lan || bool(n["password"]))) CampoTexto(pw, { pw = it }, enabled = local,
                placeholder = if (bool(n["password"])) "Contraseña (vacío = no cambiarla)" else "Contraseña (al menos 6 caracteres)",
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (n != null) {
                val url = "http://${str(n["ip"]).ifEmpty { "IP-de-este-equipo" }}:${lng(n["port"])}"
                if (!local) Hint("Solo se cambia en el teléfono/tableta donde corre Tablero.")
                else {
                    Text(if (bool(n["listening"])) "Ahora se abre desde la red en $url" + (if (bool(n["password"])) "." else ", sin contraseña.") else "Ahora solo se abre en $THIS_DEVICE.", fontSize = 14.sp)
                    Hint("Desde otro equipo, el navegador pide la contraseña (el usuario puede ser cualquiera). En $THIS_DEVICE no se pide. Viaja sin cifrar: úsalo solo en tu red de casa.")
                    Hint("Funciona con wifi y mientras la app de Tablero está activa.")
                }
            }
        }
        Campo("Importar") {
            CampoTexto(import, { import = it }, singleLine = false, minHeight = 80.dp,
                placeholder = "{\"Películas\":{\"color\":\"#e53935\",\"icon\":\"🎬\"}}")
            Hint("Colores del plugin labelcolors de ruTorrent: pega el valor de webui.labelcolors.map.")
        }
        if (estado.version.isNotEmpty()) Text("Tablero ${estado.version}", fontSize = 11.sp, color = c.dim)
    }
}

/**
 * El gráfico de bajada y subida de todos los torrents juntos (el de la pestaña Velocidad en Total), en la mitad de
 * abajo de la pantalla. Se abre y se cierra tocando las velocidades de la barra superior.
 */
@Composable
fun PanelVelocidad(estado: Estado, modifier: Modifier = Modifier) {
    val c = LocalColores.current
    var samples by remember { mutableStateOf<List<LongArray>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            try { samples = Historia.from("total", estado.api.get("/api/history")).samples } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
            delay(maxOf(1, estado.prefs.interval) * 1000L)
        }
    }
    Column(modifier.background(c.panel).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = c.line)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Seg(SPEED_RANGES.map { it.first.toString() to it.second }, estado.prefs.speedRange.toString()) { v -> estado.updatePrefs { it.copy(speedRange = v.toInt()) } }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = { estado.grafico = false }, modifier = Modifier.size(36.dp)) { Icono("x", size = 20.dp, desc = "Cerrar el gráfico") }
        }
        val last = samples.lastOrNull()
        val series = listOf("down", "up") + (if (last != null && last.size > 3 && last[3] > 0) listOf("dlim") else emptyList()) +
            (if (last != null && last.size > 4 && last[4] > 0) listOf("ulim") else emptyList())
        if (samples.size < 2) Hint("Juntando datos…")
        else Grafico(samples, series, estado.prefs.speedRange, Modifier.fillMaxWidth().weight(1f))
    }
}
