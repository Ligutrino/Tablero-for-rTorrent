package cl.tablero.compose

import androidx.compose.ui.graphics.ImageBitmap
import cl.tablero.backend.ApiError
import cl.tablero.backend.Tablero
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lo que la interfaz necesita del sistema: en el teléfono, Intents y selectores de Android; en el
 * escritorio (tools/tablero-compose), lo que haya.
 */
interface Plataforma {
    /** "Abrir carpeta": el explorador del sistema en esa carpeta. */
    fun abrirCarpeta(path: String)
    fun encenderRtorrent()
    fun reiniciarRtorrent()
    /** "Reproducir mientras se descarga": url sirve solo las piezas completas. */
    fun reproducir(url: String, nombre: String)
    /** "Exportar el elegido…" de los respaldos: el "Guardar como" del sistema con ese .zip. */
    fun exportar(path: String)
    /** "Elegir con Android…": el selector de carpetas; responde la ruta o null. */
    fun elegirCarpeta(listo: (String?) -> Unit)
    /** Hay selector de carpetas del sistema (el botón "Elegir con Android…"). */
    val tieneSelectorCarpetas: Boolean get() = false
    /**
     * El selector de archivos del sistema ([tipos]: tipos MIME). Cada archivo elegido se copia al caché
     * de la app y llega con su ruta, que Tablero lee; nada elegido: lista vacía.
     */
    fun elegirArchivos(tipos: List<String>, varios: Boolean, listo: (List<ArchivoLocal>) -> Unit)
    /** La imagen reducida a [w]×[h] px, como PNG (para un logo demasiado pesado), o null. */
    fun reducirImagen(bytes: ByteArray, w: Int, h: Int): ByteArray?
    /** Los bytes de un PNG, JPEG, WebP o GIF como imagen, o null. */
    fun imagen(bytes: ByteArray): ImageBitmap?
    /** Preguntar la primera vez dónde guardar los respaldos (no en las capturas del escritorio). */
    val primerRespaldo: Boolean get() = true
    /** Las propiedades de los diálogos (en Android, además, bajo las barras del sistema). */
    val propiedadesDialogo: androidx.compose.ui.window.DialogProperties
        get() = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
}

/**
 * La API de Tablero en el mismo proceso: las mismas rutas que /api/... (ver Server.kt), sin HTTP.
 * Cada llamada corre en un hilo de E/S; los errores llegan como [ApiError] con el texto de la web.
 */
class Api(private val fuente: () -> Tablero?) {
    constructor(t: Tablero) : this({ t })

    /** El Tablero de ahora: en Android se crea otra vez cuando el servicio de rTorrent vuelve a arrancar. */
    private fun t(): Tablero = fuente() ?: throw ApiError(502, "No se pudo conectar con rTorrent: el servicio no está corriendo")

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): Map<String, Any?> =
        withContext(Dispatchers.IO) { asMap(t().get(path, query)) }

    suspend fun post(path: String, body: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        withContext(Dispatchers.IO) { asMap(t().post(path, body)) }

    suspend fun logo(url: String): ByteArray? = withContext(Dispatchers.IO) { fuente()?.logoBytes(url) }

    /** POST /api/backup-import con un .zip ya copiado a [path]. */
    suspend fun importarRespaldo(path: String, folder: String, name: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val f = java.io.File(path)
        f.inputStream().use { asMap(t().importBackup(it, f.length(), folder, name)) }
    }

    fun streamUrl(hash: String, index: Int, name: String) = t().streamUrl(hash, index, name)
}

/** El texto de un error para mostrarlo, como el mensaje de la web. */
fun Throwable.texto(): String = when (this) {
    is ApiError -> message
    else -> message ?: toString()
}

/** Sin conexión con rTorrent: la web lo dice así y ofrece encenderlo. */
fun Throwable.sinRtorrent(): Boolean = this is ApiError && code == 502 && message.startsWith("No se pudo conectar con rTorrent")
