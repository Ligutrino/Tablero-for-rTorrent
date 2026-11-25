package cl.tablero.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import io.github.ligutrino.rtorrent.engine.android.EngineProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Pantalla con Tablero. Al abrirse arranca el servicio del motor (rtorrent + backend de Tablero) y carga
 * la interfaz cuando el servidor responde. Recibe también magnet: y .torrent de otras apps.
 *
 * Como la ventana GTK en escritorio: pantalla de espera mientras arranca Tablero, enlaces externos en
 * el navegador, recarga si la página dejó de responder tras rato en segundo plano, zoom con dos dedos
 * y letra del tamaño del sistema, y "Guardar como" para exportar respaldos.
 */
class TableroActivity : Activity() {
    companion object {
        private const val REQ_ARCHIVOS = 1
        private const val REQ_NOTIFICACIONES = 2
        private const val REQ_EXPORTAR = 3
        private const val REQ_CARPETA = 4
        @Volatile private var actual: java.lang.ref.WeakReference<TableroActivity>? = null

        /** Salir: cierra la pantalla de Tablero si está abierta y la quita de las apps recientes. */
        fun cerrar() {
            actual?.get()?.let { a -> a.runOnUiThread { a.finishAndRemoveTask() } }
        }
    }

    private lateinit var web: WebView
    private var elegido: ValueCallback<Array<Uri>>? = null
    private var listo = false
    @Volatile private var vivo = true
    private val pendientes = mutableListOf<String>()
    private var aExportar: File? = null
    private var carpetaPedida: String? = null   // id de JS que espera window.tableroFolderPicked
    private var enSegundoPlanoDesde = 0L
    @Volatile private var espera = 0   // turno de cargarCuandoResponda vigente

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        actual = java.lang.ref.WeakReference(this)
        web = WebView(this)
        // targetSdk 35 dibuja de borde a borde: el marco recibe el espacio de las barras del sistema.
        setContentView(FrameLayout(this).apply { fitsSystemWindows = true; addView(web) })
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true   // Tablero guarda columnas, tema, etc. en localStorage
        // Zoom con dos dedos (sin los botones + y -), y la letra del tamaño elegido en Ajustes.
        web.settings.setSupportZoom(true)
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.settings.textZoom = (resources.configuration.fontScale * 100).toInt()
        web.addJavascriptInterface(TableroPuente(this, exportar = ::exportar, elegirCarpeta = ::elegirCarpeta), "TableroAndroid")
        web.webChromeClient = object : WebChromeClient() {
            // "elige archivos" en Agregar torrents
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                elegido?.onReceiveValue(null)
                elegido = cb
                return try {
                    startActivityForResult(p.createIntent(), REQ_ARCHIVOS); true
                } catch (e: Exception) {
                    elegido = null; false
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                if (!url.startsWith(TableroServidor.url)) return
                listo = true
                pendientes.forEach { web.evaluateJavascript(it, null) }
                pendientes.clear()
            }

            // Un enlace que sale de Tablero (p. ej. la página de un rastreador): al navegador.
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url.toString()
                if (u.startsWith(TableroServidor.url) || u.startsWith("about:") || u.startsWith("data:")) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, req.url)) }
                return true
            }
        }

        pedirNotificaciones()
        // Un aviso a la vez: primero archivos, al cerrarlo el de batería.
        ofrecerAccesoArchivos(despues = ::ofrecerSinRestriccionBateria)
        EngineProvider.startService(this)
        cargarCuandoResponda()
        recibir(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); recibir(i) }

    override fun onDestroy() {
        vivo = false
        if (actual?.get() === this) actual = null
        web.destroy()
        super.onDestroy()
    }

    override fun onStop() { super.onStop(); enSegundoPlanoDesde = SystemClock.elapsedRealtime() }

    /** Tras más de 2 minutos fuera, Android puede haber cerrado el proceso que dibuja la página. */
    override fun onStart() {
        super.onStart()
        if (!listo || enSegundoPlanoDesde == 0L) return
        if (SystemClock.elapsedRealtime() - enSegundoPlanoDesde < 120_000) return
        var contesto = false
        web.evaluateJavascript("1") { contesto = it == "1" }
        web.postDelayed({ if (vivo && !contesto) { listo = false; cargarCuandoResponda() } }, 3000)
    }

    private fun pantallaEspera(titulo: String, detalle: String) = web.loadDataWithBaseURL(null,
        "<html><body style='background:#1a1a1a;color:#ddd;font-family:sans-serif;display:grid;place-items:center;height:90vh;margin:0'>" +
            "<div style='text-align:center'><h3 style='font-weight:500'>$titulo</h3><p style='color:#999'>$detalle</p></div></body></html>",
        "text/html", "utf-8", null)

    private fun cargarCuandoResponda() {
        pantallaEspera("Iniciando rTorrent y Tablero…", "")
        val turno = ++espera
        thread(name = "tablero-espera", isDaemon = true) {
            val desde = SystemClock.elapsedRealtime()
            var avisado = false
            while (vivo && turno == espera && !TableroServidor.responde()) {
                if (!avisado && SystemClock.elapsedRealtime() - desde > 20_000) {
                    avisado = true
                    runOnUiThread { if (vivo) pantallaEspera("No se pudo iniciar Tablero", "Se sigue reintentando…") }
                }
                Thread.sleep(500)
            }
            if (vivo && turno == espera) runOnUiThread { web.loadUrl(TableroServidor.url) }
        }
    }

    /** "Elegir con Android…": el selector de carpetas del sistema (Storage Access Framework). */
    private fun elegirCarpeta(id: String) {
        carpetaPedida = id
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_CARPETA)
        } catch (e: Exception) {
            carpetaPedida = null
            Toast.makeText(this, "Este Android no tiene selector de carpetas", Toast.LENGTH_LONG).show()
        }
    }

    /** "Exportar el elegido…": Guardar como (Descargas, Drive, otra carpeta…) y se copia el .zip. */
    private fun exportar(ruta: String) {
        val f = File(ruta)
        if (!f.isFile) { Toast.makeText(this, "No encontré $ruta", Toast.LENGTH_LONG).show(); return }
        aExportar = f
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, f.name)
        try { startActivityForResult(i, REQ_EXPORTAR) } catch (e: Exception) {
            aExportar = null
            Toast.makeText(this, "No hay dónde guardar el respaldo", Toast.LENGTH_LONG).show()
        }
    }

    private fun copiarRespaldo(uri: Uri) {
        val origen = aExportar ?: return
        aExportar = null
        thread(name = "tablero-exportar") {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { sal -> origen.inputStream().use { it.copyTo(sal) } } != null
            } catch (e: Exception) { false }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Respaldo exportado" else "No pude exportar el respaldo", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** magnet:… o un .torrent (content://): se abre "Agregar torrents" con él. */
    private fun recibir(i: Intent?) {
        val uri = i?.data ?: return
        val js = if (uri.scheme == "magnet") {
            "tableroAdd(" + JSONObject().put("magnets", JSONArray().put(uri.toString())) + ")"
        } else {
            // Tablero lee el .torrent por ruta: se copia al caché de la app.
            val f = File(cacheDir, "recibido-${System.currentTimeMillis()}.torrent")
            try {
                contentResolver.openInputStream(uri)?.use { ent -> f.outputStream().use { ent.copyTo(it) } } ?: return
            } catch (e: Exception) { return }
            "tableroAdd(" + JSONObject().put("files", JSONArray().put(f.absolutePath)) + ")"
        }
        if (listo) web.evaluateJavascript(js, null) else pendientes += js
    }

    @Deprecated("API de Activity sin AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ARCHIVOS) {
            elegido?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            elegido = null
        } else if (requestCode == REQ_CARPETA) {
            val id = carpetaPedida ?: return
            carpetaPedida = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null) return
            val ruta = Carpetas.rutaReal(this, uri)
            val error = when {
                ruta == null -> "Android no dio una carpeta del almacenamiento del teléfono. Elige una en \"Almacenamiento interno\" o en la tarjeta SD."
                !File(ruta).isDirectory -> "No encuentro la carpeta $ruta"
                !File(ruta).canWrite() -> if (!Environment.isExternalStorageManager())
                    "rTorrent no puede escribir en $ruta: activa \"Acceso a todos los archivos\" para Tablero en los ajustes de Android."
                else "Android no deja escribir en $ruta (en una tarjeta SD solo se puede en Android/data/$packageName)."
                else -> null
            }
            web.evaluateJavascript("window.tableroFolderPicked && tableroFolderPicked(" +
                JSONObject.quote(id) + "," + JSONObject.quote(if (error == null) ruta else "") + "," +
                JSONObject.quote(error ?: "") + ")", null)
        } else if (requestCode == REQ_EXPORTAR) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) copiarRespaldo(uri) else aExportar = null
        }
    }

    @Deprecated("Atrás: primero cierra el menú o diálogo abierto en Tablero")
    override fun onBackPressed() {
        if (!listo) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        web.evaluateJavascript("window.tableroBack ? tableroBack() : false") {
            @Suppress("DEPRECATION")
            if (it != "true") super.onBackPressed()
        }
    }

    /**
     * Sin "Acceso a todos los archivos", rtorrent solo puede leer y escribir en la carpeta propia de la app:
     * cualquier otra ruta (p. ej. /storage/emulated/0/Download) da "Permission denied".
     */
    private fun ofrecerAccesoArchivos(despues: () -> Unit) {
        val prefs = getSharedPreferences("tablero-app", MODE_PRIVATE)
        if (Environment.isExternalStorageManager() || prefs.getBoolean("no-pedir-archivos", false)) {
            despues(); return
        }
        AlertDialog.Builder(this)
            .setOnDismissListener { despues() }
            .setTitle("Acceso a los archivos")
            .setMessage(
                "Para descargar en Descargas o sembrar archivos que ya tienes en el teléfono, rTorrent necesita " +
                    "\"Acceso a todos los archivos\". Sin ese permiso solo puede usar la carpeta de la app " +
                    "(Android/data/cl.tablero.android) y otras rutas fallan con \"Permission denied\".\n\n" +
                    "Después de activarlo, cierra y vuelve a abrir Tablero.",
            )
            .setPositiveButton("Permitir") { _, _ ->
                val app = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                try { startActivity(app) } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
            .setNegativeButton("Ahora no", null)
            .setNeutralButton("No volver a preguntar") { _, _ -> prefs.edit().putBoolean("no-pedir-archivos", true).apply() }
            .show()
    }

    /**
     * Sin esto, el ahorro de batería (y las capas propias de cada fabricante) pueden matar el servicio
     * con la pantalla apagada. Android no deja hacerlo en silencio: se abre el diálogo del sistema.
     */
    private fun ofrecerSinRestriccionBateria() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val prefs = getSharedPreferences("tablero-app", MODE_PRIVATE)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("no-pedir-bateria", false)) return
        val fabricante = Build.MANUFACTURER.lowercase().replace(' ', '-')
        AlertDialog.Builder(this)
            .setTitle("Seguir funcionando con la pantalla apagada")
            .setMessage(
                "Para que rTorrent siga descargando y sembrando con la pantalla apagada, Android debe dejar a " +
                    "Tablero fuera del ahorro de batería (\"Sin restricciones\").\n\n" +
                    "Algunos fabricantes (${Build.MANUFACTURER}) tienen además su propio ahorro de energía; " +
                    "la guía explica dónde desactivarlo.",
            )
            .setPositiveButton("Permitir") { _, _ ->
                try {
                    @SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    startActivity(i)
                } catch (e: Exception) {
                    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }
            .setNeutralButton("Guía del fabricante") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$fabricante"))) }
            }
            .setNegativeButton("No volver a preguntar") { _, _ -> prefs.edit().putBoolean("no-pedir-bateria", true).apply() }
            .show()
    }

    private fun pedirNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICACIONES)
    }
}
