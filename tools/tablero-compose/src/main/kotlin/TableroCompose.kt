import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import cl.tablero.backend.DirWebRoot
import cl.tablero.backend.Options
import cl.tablero.backend.Tablero
import cl.tablero.compose.Api
import cl.tablero.compose.ArchivoLocal
import cl.tablero.compose.Dialogo
import cl.tablero.compose.Estado
import cl.tablero.compose.Plataforma
import cl.tablero.compose.TableroPantalla
import cl.tablero.compose.lng
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Lo del sistema en el escritorio: solo se anota (las capturas no abren nada). */
class Escritorio : Plataforma {
    val pedidos = ArrayList<String>()
    override fun abrirCarpeta(path: String) { pedidos.add("abrirCarpeta $path") }
    override fun encenderRtorrent() { pedidos.add("encenderRtorrent") }
    override fun reiniciarRtorrent() { pedidos.add("reiniciarRtorrent") }
    override fun reproducir(url: String, nombre: String) { pedidos.add("reproducir $url") }
    override fun exportar(path: String) { pedidos.add("exportar $path") }
    override fun elegirCarpeta(listo: (String?) -> Unit) { listo(null) }
    override val tieneSelectorCarpetas: Boolean get() = true
    override val primerRespaldo: Boolean get() = false
    /** Lo que "elige" el selector de archivos en las pruebas. */
    var elegibles: List<ArchivoLocal> = emptyList()
    override fun elegirArchivos(tipos: List<String>, varios: Boolean, listo: (List<ArchivoLocal>) -> Unit) { listo(elegibles) }
    override fun reducirImagen(bytes: ByteArray, w: Int, h: Int): ByteArray? = try {
        val src = Image.makeFromEncoded(bytes)
        val surf = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        surf.canvas.drawImageRect(src, org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
        surf.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    } catch (e: Exception) { null }
    override fun imagen(bytes: ByteArray): ImageBitmap? =
        try { Image.makeFromEncoded(bytes).toComposeImageBitmap() } catch (e: Exception) { null }
}

/** Las acciones de la ronda 1 de verdad contra rTorrent, comprobando el estado que ve la interfaz. */
fun probar(e: Estado) {
    fun espera(que: String, cond: () -> Boolean) {
        var n = 0
        while (!cond() && n < 60) { Thread.sleep(100); n++ }
        println((if (cond()) "ok   " else "FALLA") + " " + que)
        if (!cond()) fallas++
    }
    val t = e.rows().first { it.complete }
    e.act("/api/action", mapOf("action" to "pause", "hashes" to listOf(t.hash)))
    espera("pausar") { e.byHash[t.hash]?.status == "paused" }
    e.act("/api/action", mapOf("action" to "start", "hashes" to listOf(t.hash)))
    espera("iniciar") { e.byHash[t.hash]?.status == "seeding" }
    e.act("/api/action", mapOf("action" to "stop", "hashes" to listOf(t.hash)))
    espera("detener") { e.byHash[t.hash]?.status == "finished" }
    e.act("/api/action", mapOf("action" to "start", "hashes" to listOf(t.hash)))
    e.act("/api/edit-tags", mapOf("hashes" to listOf(t.hash), "add" to listOf("Nueva"), "remove" to emptyList<String>()))
    espera("agregar etiqueta") { e.byHash[t.hash]?.tags?.contains("Nueva") == true && "Nueva" in e.allTags() }
    e.act("/api/action", mapOf("action" to "priority", "hashes" to listOf(t.hash), "value" to 3))
    espera("prioridad alta") { e.byHash[t.hash]?.priority == 3 }
    val antes = e.rows().sortedBy { it.pos }.map { it.hash }
    e.act("/api/move", mapOf("hashes" to listOf(antes.last()), "where" to "top"))
    espera("mover al principio") { e.byHash[antes.last()]?.pos == 1L }
    e.updatePrefs { it.copy(sort = cl.tablero.compose.Orden("name", 1)) }
    espera("ordenar por nombre") { e.rows().map { it.name.lowercase() } == e.rows().map { it.name.lowercase() }.sorted() }
    e.filtro = cl.tablero.compose.Filtro(status = "seeding")
    espera("filtro Compartiendo") { e.rows().isNotEmpty() && e.rows().all { it.status == "seeding" } }
    e.filtro = cl.tablero.compose.Filtro(tags = setOf(cl.tablero.compose.NO_TAG))
    espera("filtro Sin etiqueta") { e.rows().all { it.tags.isEmpty() } && e.rows().isNotEmpty() }
    e.filtro = cl.tablero.compose.Filtro()
    // Ronda 2: el detalle.
    val f = e.rows().first { it.files > 1 }
    e.updatePrefs { it.copy(tab = "files") }
    e.abrirDetalle(f.hash)
    espera("detalle: archivos") { e.details?.hash == f.hash && e.details!!.files.size > 1 }
    e.act("/api/file-priority", mapOf("hash" to f.hash, "indexes" to listOf(0), "priority" to 0))
    espera("archivo: No descargar") { e.loadDetalleSync(); e.details?.files?.get(0)?.priority == 0 }
    e.act("/api/file-priority", mapOf("hash" to f.hash, "indexes" to listOf(0), "priority" to 2))
    espera("archivo: Alta") { e.loadDetalleSync(); e.details?.files?.get(0)?.priority == 2 }
    val nt = e.details!!.trackers.size
    e.act("/api/add-trackers", mapOf("hash" to f.hash, "urls" to listOf("udp://tracker.prueba.example:1337/announce")))
    espera("agregar rastreador") { e.loadDetalleSync(); e.details?.trackers?.size == nt + 1 }
    e.act("/api/options", mapOf("hash" to f.hash, "options" to mapOf("priority" to 2, "down_limit" to 100 * 1024L, "up_limit" to 0L,
        "peers_max" to 40L, "peers_min" to null, "uploads_max" to null, "seed_own" to true, "stop_ratio" to 1.5, "stop_hours" to 0.0)))
    espera("opciones del torrent") { e.byHash[f.hash]?.let { lng(it.opts["down_limit"]) == 100 * 1024L && lng(it.opts["peers_max"]) == 40L } == true }
    e.updatePrefs { it.copy(tab = "speed") }
    e.loadDetalleSync()
    espera("historia de velocidad") { e.history?.key == f.hash }
    e.detalle = null
    e.updatePrefs { it.copy(tab = "general") }
    // Ronda 3.
    runBlocking {
        val nuevo = File(File(archivos).parentFile, "patagonia.torrent").path
        val info = e.api.post("/api/torrent-info", mapOf("path" to nuevo))
        check("leer un .torrent", cl.tablero.compose.listOfAny(info["files"]).size == 5)
        check("espacio libre", lng(e.api.get("/api/free-space", mapOf("path" to archivos))["free"]) > 0)
        check("carpetas (Examinar)", cl.tablero.compose.listOfAny(e.api.get("/api/dirs", mapOf("path" to archivos))["dirs"]).isNotEmpty())
        e.api.post("/api/limits", mapOf("down" to 500 * 1024L, "up" to 0L))
        e.api.post("/api/general", mapOf("max_sockets" to 600L, "peers_max" to 60L, "uploads_max" to null, "stop_ratio" to 0.0, "stop_hours" to 0.0))
        check("conexiones generales", e.loadGeneral()?.general?.get("peers_max")?.let { lng(it) } == 60L)
        e.guardarEtiquetas(e.tagConf.copy(tags = e.tagConf.tags + ("Prueba" to cl.tablero.compose.TagEntry(color = "#123456", icon = "lucide:star"))))
        check("etiqueta nueva", e.tagConf.tags["Prueba"]?.color == "#123456")
        e.guardarEtiquetas(e.tagConf.copy(tags = e.tagConf.tags - "Prueba"))
        check("eliminar etiqueta", "Prueba" !in e.tagConf.tags)
        val bk = File(File(archivos).parentFile, "respaldos").apply { mkdirs() }.path
        val r = e.api.post("/api/backup", mapOf("folder" to bk))
        check("crear respaldo", File(cl.tablero.compose.str(r["path"])).isFile)
        val ins = e.api.post("/api/restore-inspect", mapOf("path" to cl.tablero.compose.str(r["path"])))
        check("revisar respaldo", lng(ins["torrents"]) == e.torrents.size.toLong())
        val imp = e.api.importarRespaldo(cl.tablero.compose.str(r["path"]), bk, "copia.zip")
        check("importar respaldo", File(cl.tablero.compose.str(imp["path"])).isFile)
    }
    espera("límite de bajada") { e.stats.downLimit == 500 * 1024L }
    val tos = e.torrents.first { it.name.startsWith("Tears of Steel") }
    e.act("/api/rename-folder", mapOf("hash" to tos.hash, "name" to "Tears of Steel T1"))
    espera("renombrar carpeta") { e.byHash[tos.hash]?.directory?.endsWith("Tears of Steel T1") == true }
    val n = e.torrents.size
    e.act("/api/add", mapOf("magnet" to "magnet:?xt=urn:btih:" + "d".repeat(40), "directory" to "", "tags" to listOf("Linux"), "start" to false))
    espera("agregar magnet") { e.torrents.size == n + 1 }
    val m = e.torrents.first { it.hash == "D".repeat(40) }
    e.act("/api/action", mapOf("action" to "remove", "hashes" to listOf(m.hash), "data" to false))
    espera("eliminar") { e.byHash[m.hash] == null }
    println(if (fallas == 0) "TODO OK" else "$fallas FALLAS")
}
var fallas = 0

fun check(que: String, ok: Boolean) { println((if (ok) "ok   " else "FALLA") + " " + que); if (!ok) fallas++ }
lateinit var archivos: String

fun Estado.loadDetalleSync() = runBlocking { loadDetalle() }

/**
 * tablero-compose --shots DIR [--escena a,b,...] --scgi RUTA --config DIR --archivos DIR --web DIR [--port N]
 * Arranca Tablero, muestra la interfaz Compose sin ventana (412×892 dp, densidad 2) y guarda una
 * captura PNG por escena.
 */
fun main(args: Array<String>) {
    val a = HashMap<String, String>()
    var i = 0
    while (i < args.size) { a[args[i]] = args.getOrElse(i + 1) { "" }; i += 2 }
    val shots = File(a["--shots"] ?: run { System.err.println("falta --shots DIR"); exitProcess(2) })
    shots.mkdirs()
    val scgi = a["--scgi"] ?: "127.0.0.1:5000"
    val tablero = Tablero(Options(
        scgi = connector(scgi), scgiName = scgi, web = DirWebRoot(File(a["--web"] ?: error("falta --web"))),
        configDir = a["--config"] ?: error("falta --config"), port = (a["--port"] ?: "18091").toInt(), android = true,
        userFiles = a["--archivos"],
    ))
    thread(isDaemon = true) { tablero.run() }
    archivos = a["--archivos"] ?: error("falta --archivos")
    val plataforma = Escritorio()
    val estado = Estado(Api(tablero), plataforma, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    estado.start()
    val w = (a["--w"] ?: "412").toInt(); val h = (a["--h"] ?: "892").toInt()
    val scene = ImageComposeScene(w * 2, h * 2, Density(2f)) { TableroPantalla(estado) }
    var t = 0L
    fun frames(ms: Long) {
        val end = t + ms * 1_000_000
        while (t < end) { scene.render(t); t += 16_000_000; Thread.sleep(2) }
    }
    fun shot(name: String) {
        frames(700)
        val img = scene.render(t)
        File(shots, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("captura $name.png")
    }
    // Espera la primera lista.
    var waited = 0
    while (estado.conectado == null && waited < 100) { Thread.sleep(100); waited++ }
    Thread.sleep(500)
    val rows = estado.rows()
    val escenas = (a["--escena"] ?: "lista,grafico,cajon,seleccion,menu,etiquetas,eliminar,orden,buscar,claro,agregar,campos,general,archivos,lista-archivos,pares,rastreadores,piezas,velocidad,total,opciones,menu-detalle,general-claro,agregar-archivos,etiqueta,etiqueta-nueva,limites,puerto,ajustes,apagar,respaldos,reubicar,renombrar,duplicados").split(',')
    for (e in escenas) {
        estado.sel = emptySet(); estado.dialogo = null; estado.menu = null; estado.cajon = false; estado.buscando = false; estado.grafico = false
        estado.filtro = cl.tablero.compose.Filtro(); estado.detalle = null
        if (estado.prefs.theme != "dark") estado.updatePrefs { it.copy(theme = "dark") }
        frames(400)
        when (e) {
            "lista" -> {}
            "grafico" -> { estado.grafico = true; frames(2500) }
            "cajon" -> estado.cajon = true
            "seleccion" -> estado.sel = rows.take(2).map { it.hash }.toSet()
            "menu" -> { estado.sel = rows.take(2).map { it.hash }.toSet(); frames(200); estado.menu = "torrents" }
            "menu-pos" -> {
                estado.updatePrefs { it.copy(sort = cl.tablero.compose.Orden("pos", 1)) }; frames(300)
                estado.sel = rows.take(2).map { it.hash }.toSet(); frames(200); estado.menu = "torrents"
            }
            "etiquetas" -> { estado.sel = rows.take(2).map { it.hash }.toSet(); estado.dialogo = Dialogo.Etiquetas(rows.take(2).map { it.hash }) }
            "eliminar" -> { estado.sel = rows.take(1).map { it.hash }.toSet(); estado.dialogo = Dialogo.Eliminar(rows.take(1).map { it.hash }) }
            "orden" -> estado.dialogo = Dialogo.Orden
            "buscar" -> { estado.buscando = true; estado.filtro = estado.filtro.copy(text = rows.firstOrNull()?.name?.take(4) ?: "") }
            "claro" -> estado.updatePrefs { it.copy(theme = "light") }
            "agregar" -> estado.dialogo = Dialogo.Agregar()
            "campos" -> estado.dialogo = Dialogo.Campos
            "probar" -> { probar(estado); continue }
            "general", "archivos", "lista-archivos", "pares", "rastreadores", "piezas", "velocidad", "total", "opciones", "menu-detalle", "general-claro" -> {
                val tab = mapOf("archivos" to "files", "lista-archivos" to "files", "pares" to "peers", "rastreadores" to "trackers", "piezas" to "pieces",
                    "velocidad" to "speed", "total" to "speed", "opciones" to "options")[e] ?: "general"
                val d = rows.firstOrNull { !it.complete && it.files > 1 } ?: rows.first()
                estado.updatePrefs { it.copy(tab = tab, filesTree = e != "lista-archivos", speedOf = if (e == "total") "total" else "torrent",
                    theme = if (e == "general-claro") "light" else "dark") }
                estado.abrirDetalle(d.hash)
                frames(1200)
                if (e == "menu-detalle") estado.menu = "torrents"
            }
            "agregar-archivos" -> {
                plataforma.elegibles = listOf(ArchivoLocal("patagonia.torrent", File(File(archivos).parentFile, "patagonia.torrent").path))
                estado.dialogo = Dialogo.Agregar(files = plataforma.elegibles); frames(1500)
            }
            "etiqueta" -> estado.dialogo = Dialogo.Etiqueta("Películas")
            "etiqueta-nueva" -> estado.dialogo = Dialogo.Etiqueta(null)
            "limites" -> { estado.dialogo = Dialogo.Limites; frames(1500) }
            "puerto" -> estado.dialogo = Dialogo.Puerto
            "ajustes" -> { estado.dialogo = Dialogo.Opciones; frames(800) }
            "apagar" -> estado.dialogo = Dialogo.Apagar
            "respaldos" -> { estado.dialogo = Dialogo.Respaldos; frames(800) }
            "reubicar" -> estado.dialogo = Dialogo.Reubicar(rows.take(2).map { it.hash })
            "renombrar" -> estado.dialogo = Dialogo.RenombrarCarpeta(rows.first { it.files > 1 }.hash)
            "duplicados" -> estado.dialogo = Dialogo.Duplicados(listOf(mapOf("hash" to rows[0].hash, "name" to rows[0].name,
                "trackers" to listOf("udp://tracker.otro.example:1337/announce", "https://tracker.ejemplo.org/announce"))))
            "filtro" -> { estado.toggleTagFilter("Películas", true); estado.toggleTagFilter("Linux", true) }
        }
        shot(e)
    }
    if (estado.prefs.theme != "dark") estado.updatePrefs { it.copy(theme = "dark") }
    println("pedidos: ${plataforma.pedidos}")
    Thread.sleep(1800)  // que alcance a guardar las preferencias
    tablero.stop()
    exitProcess(0)
}
