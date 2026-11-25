package cl.tablero.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import cl.tablero.compose.Api
import cl.tablero.compose.ArchivoLocal
import cl.tablero.compose.Dialogo
import cl.tablero.compose.Estado
import cl.tablero.compose.LocalColores
import cl.tablero.compose.Plataforma
import cl.tablero.compose.TableroPantalla
import cl.tablero.compose.TableroTema
import io.github.ligutrino.rtorrent.engine.android.EngineProvider
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

/**
 * La pantalla de Tablero, en Compose (cl.tablero.compose): llama a la API de Tablero en el mismo
 * proceso, sin HTTP. Al abrirse arranca el servicio del motor (rtorrent + backend de Tablero), pide
 * los permisos y recibe magnet: y .torrent de otras apps.
 */
class TableroActivity : ComponentActivity(), Plataforma {
    companion object {
        @Volatile private var actual: java.lang.ref.WeakReference<TableroActivity>? = null

        /** Salir: cierra la pantalla de Tablero si está abierta y la quita de las apps recientes. */
        fun cerrar() {
            actual?.get()?.let { a -> a.runOnUiThread { a.finishAndRemoveTask() } }
        }
    }

    private lateinit var estado: Estado
    /** El backend todavía no corre: pantalla de espera (pantallaEspera de la WebView). */
    private var esperando by mutableStateOf(true)
    private var esperaLarga by mutableStateOf(false)

    private var aExportar: File? = null
    private var carpetaListo: ((String?) -> Unit)? = null
    private var archivosListo: ((List<ArchivoLocal>) -> Unit)? = null

    private val elegirVarios = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { copiarElegidos(it) }
    private val elegirUno = registerForActivityResult(ActivityResultContracts.OpenDocument()) { copiarElegidos(listOfNotNull(it)) }

    private val guardarComo = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val origen = aExportar
        aExportar = null
        if (uri != null && origen != null) copiarRespaldo(origen, uri)
    }

    private val elegirArbol = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val listo = carpetaListo ?: return@registerForActivityResult
        carpetaListo = null
        if (uri == null) { listo(null); return@registerForActivityResult }
        val ruta = Carpetas.rutaReal(this, uri)
        val error = when {
            ruta == null -> "Android no dio una carpeta del almacenamiento del teléfono. Elige una en \"Almacenamiento interno\" o en la tarjeta SD."
            !File(ruta).isDirectory -> "No encuentro la carpeta $ruta"
            !File(ruta).canWrite() -> if (!Environment.isExternalStorageManager())
                "rTorrent no puede escribir en $ruta: activa \"Acceso a todos los archivos\" para Tablero en los ajustes de Android."
            else "Android no deja escribir en $ruta (en una tarjeta SD solo se puede en Android/data/$packageName)."
            else -> null
        }
        if (error != null) estado.aviso(error, true)
        listo(if (error == null) ruta else null)
    }

    override fun onCreate(estadoGuardado: Bundle?) {
        super.onCreate(estadoGuardado)
        actual = java.lang.ref.WeakReference(this)
        estado = Estado(Api { TableroServidor.actual() }, this, lifecycleScope)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (esperando || !estado.atras()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        setContent {
            if (esperando) Espera(estado.prefs.theme, esperaLarga)
            else TableroPantalla(estado)  // de borde a borde (targetSdk 35): Scaffold deja libres las barras del sistema
            // Los íconos de las barras del sistema, claros u oscuros según el tema de Tablero (no el de Android).
            val oscuro = estado.prefs.theme != "light"
            LaunchedEffect(oscuro) {
                val barra = if (oscuro) SystemBarStyle.dark(Color.TRANSPARENT)
                            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = barra, navigationBarStyle = barra)
            }
            LaunchedEffect(Unit) {
                val desde = System.currentTimeMillis()
                while (TableroServidor.actual() == null) {
                    if (System.currentTimeMillis() - desde > 20_000) esperaLarga = true
                    delay(300)
                }
                esperando = false
                estado.start()
            }
        }
        Permisos.pedir(this)
        EngineProvider.startService(this)
        recibir(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); recibir(i) }

    override fun onDestroy() {
        if (actual?.get() === this) actual = null
        super.onDestroy()
    }

    /** magnet:… o un .torrent (content://): se abre "Agregar torrents" con él. */
    private fun recibir(i: Intent?) {
        val uri = i?.data ?: return
        val actualDialogo = estado.dialogo as? Dialogo.Agregar
        if (uri.scheme == "magnet") {
            estado.dialogo = (actualDialogo ?: Dialogo.Agregar()).let { d ->
                d.copy(magnets = listOf(d.magnets.trim(), uri.toString()).filter { it.isNotEmpty() }.joinToString("\n"))
            }
            return
        }
        // Tablero lee el .torrent por ruta: se copia al caché de la app.
        val f = File(cacheDir, "recibido-${System.currentTimeMillis()}.torrent")
        try {
            contentResolver.openInputStream(uri)?.use { ent -> f.outputStream().use { ent.copyTo(it) } } ?: return
        } catch (e: Exception) { return }
        val nombre = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.endsWith(".torrent", true) } ?: f.name
        estado.dialogo = (actualDialogo ?: Dialogo.Agregar()).let { d -> d.copy(files = d.files + ArchivoLocal(nombre, f.absolutePath)) }
    }

    private fun copiarRespaldo(origen: File, uri: Uri) {
        thread(name = "tablero-exportar") {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { sal -> origen.inputStream().use { it.copyTo(sal) } } != null
            } catch (e: Exception) { false }
            estado.aviso(if (ok) "Respaldo exportado" else "No pude exportar el respaldo", !ok)
        }
    }

    // ------------------------------------------------------------ Plataforma

    override fun abrirCarpeta(path: String) {
        val raiz = "/storage/emulated/0/"
        // Android/data/<paquete> tampoco lo abre el explorador del sistema desde Android 11.
        if (!path.startsWith(raiz) || path.startsWith(raiz + "Android/data/")) {
            Toast.makeText(this, path, Toast.LENGTH_LONG).show(); return
        }
        val doc = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + path.removePrefix(raiz).trimEnd('/'))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, path, Toast.LENGTH_LONG).show() }
    }

    override fun encenderRtorrent() = EngineProvider.startService(this)

    override fun reiniciarRtorrent() {
        thread(name = "tablero-restart") {
            runCatching { EngineProvider.get(this).stop() }
            EngineProvider.startService(this)
        }
    }

    override fun reproducir(url: String, nombre: String) {
        val ext = nombre.substringAfterLast('.', "").lowercase()
        val tipo = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), tipo).putExtra(Intent.EXTRA_TITLE, nombre)
        try { startActivity(Intent.createChooser(i, "Reproducir con")) } catch (e: Exception) {
            Toast.makeText(this, "No hay un reproductor instalado", Toast.LENGTH_LONG).show()
        }
    }

    override fun exportar(path: String) {
        val f = File(path)
        if (!f.isFile) { estado.aviso("No encontré $path", true); return }
        aExportar = f
        try { guardarComo.launch(f.name) } catch (e: Exception) {
            aExportar = null
            estado.aviso("No hay dónde guardar el respaldo", true)
        }
    }

    override fun elegirCarpeta(listo: (String?) -> Unit) {
        carpetaListo = listo
        try { elegirArbol.launch(null) } catch (e: Exception) {
            carpetaListo = null
            estado.aviso("Este Android no tiene selector de carpetas", true)
            listo(null)
        }
    }

    override fun imagen(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    override val tieneSelectorCarpetas: Boolean get() = true
    override val propiedadesDialogo get() = propiedadesDialogo()

    override fun elegirArchivos(tipos: List<String>, varios: Boolean, listo: (List<ArchivoLocal>) -> Unit) {
        archivosListo = listo
        try { if (varios) elegirVarios.launch(tipos.toTypedArray()) else elegirUno.launch(tipos.toTypedArray()) } catch (e: Exception) {
            archivosListo = null
            estado.aviso("Este Android no tiene selector de archivos", true)
            listo(emptyList())
        }
    }

    /** Los archivos elegidos, copiados al caché de la app con su nombre (Tablero los lee por ruta). */
    private fun copiarElegidos(uris: List<Uri>) {
        val listo = archivosListo ?: return
        archivosListo = null
        if (uris.isEmpty()) { listo(emptyList()); return }
        thread(name = "tablero-elegidos") {
            val out = uris.mapNotNull { uri ->
                val nombre = nombreDe(uri)
                val dir = File(cacheDir, "elegidos/${System.nanoTime()}").apply { mkdirs() }
                val f = File(dir, nombre.replace('/', '_'))
                try {
                    contentResolver.openInputStream(uri)?.use { ent -> f.outputStream().use { ent.copyTo(it) } } ?: return@mapNotNull null
                    ArchivoLocal(nombre, f.absolutePath)
                } catch (e: Exception) { null }
            }
            runOnUiThread { listo(out) }
        }
    }

    private fun nombreDe(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        } catch (e: Exception) { /* sin nombre */ }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "archivo"
    }

    override fun reducirImagen(bytes: ByteArray, w: Int, h: Int): ByteArray? {
        var b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        // En mitades, que conserva las líneas finas que un solo paso grande pierde (como prepareLogo de la web).
        while (b.width / 2 >= w) b = Bitmap.createScaledBitmap(b, b.width / 2, maxOf(1, b.height / 2), true)
        b = Bitmap.createScaledBitmap(b, w, h, true)
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }
}

@Composable
private fun Espera(theme: String, larga: Boolean) {
    TableroTema(theme) {
        val c = LocalColores.current
        Column(Modifier.fillMaxSize().background(c.bg).padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (larga) "No se pudo iniciar Tablero" else "Iniciando rTorrent y Tablero…", fontSize = 18.sp, color = c.text)
            if (larga) Text("Se sigue reintentando…", fontSize = 14.sp, color = c.dim, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
