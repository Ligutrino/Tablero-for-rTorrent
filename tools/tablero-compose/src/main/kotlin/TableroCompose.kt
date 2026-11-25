import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import cl.tablero.backend.DirWebRoot
import cl.tablero.backend.Options
import cl.tablero.backend.Tablero
import cl.tablero.compose.Api
import cl.tablero.compose.Dialogo
import cl.tablero.compose.Estado
import cl.tablero.compose.Plataforma
import cl.tablero.compose.TableroPantalla
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
    val n = e.torrents.size
    e.act("/api/add", mapOf("magnet" to "magnet:?xt=urn:btih:" + "d".repeat(40), "directory" to "", "tags" to listOf("Linux"), "start" to false))
    espera("agregar magnet") { e.torrents.size == n + 1 }
    val m = e.torrents.first { it.hash == "D".repeat(40) }
    e.act("/api/action", mapOf("action" to "remove", "hashes" to listOf(m.hash), "data" to false))
    espera("eliminar") { e.byHash[m.hash] == null }
    println(if (fallas == 0) "TODO OK" else "$fallas FALLAS")
}
var fallas = 0

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
    val escenas = (a["--escena"] ?: "lista,cajon,seleccion,menu,etiquetas,eliminar,orden,buscar,claro,agregar,campos").split(',')
    for (e in escenas) {
        estado.sel = emptySet(); estado.dialogo = null; estado.menu = null; estado.cajon = false; estado.buscando = false
        estado.filtro = cl.tablero.compose.Filtro()
        if (estado.prefs.theme != "dark") estado.updatePrefs { it.copy(theme = "dark") }
        frames(400)
        when (e) {
            "lista" -> {}
            "cajon" -> estado.cajon = true
            "seleccion" -> estado.sel = rows.take(2).map { it.hash }.toSet()
            "menu" -> { estado.sel = rows.take(2).map { it.hash }.toSet(); frames(200); estado.menu = "torrents" }
            "etiquetas" -> { estado.sel = rows.take(2).map { it.hash }.toSet(); estado.dialogo = Dialogo.Etiquetas(rows.take(2).map { it.hash }) }
            "eliminar" -> { estado.sel = rows.take(1).map { it.hash }.toSet(); estado.dialogo = Dialogo.Eliminar(rows.take(1).map { it.hash }) }
            "orden" -> estado.menu = "orden"
            "buscar" -> { estado.buscando = true; estado.filtro = estado.filtro.copy(text = rows.firstOrNull()?.name?.take(4) ?: "") }
            "claro" -> estado.updatePrefs { it.copy(theme = "light") }
            "agregar" -> estado.dialogo = Dialogo.Agregar()
            "campos" -> estado.dialogo = Dialogo.Campos
            "probar" -> { probar(estado); continue }
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
