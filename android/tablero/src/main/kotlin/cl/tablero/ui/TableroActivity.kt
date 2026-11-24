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
import android.provider.Settings
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import io.github.ligutrino.rtorrent.engine.android.EngineProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Pantalla con Tablero. Al abrirse arranca el servicio del motor (rtorrent + tablero.py) y carga
 * la interfaz cuando el servidor responde. Recibe también magnet: y .torrent de otras apps.
 */
class TableroActivity : Activity() {
    private companion object {
        const val REQ_ARCHIVOS = 1
        const val REQ_NOTIFICACIONES = 2
    }

    private lateinit var web: WebView
    private var elegido: ValueCallback<Array<Uri>>? = null
    private var listo = false
    @Volatile private var vivo = true
    private val pendientes = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        web = WebView(this)
        // targetSdk 35 dibuja de borde a borde: el marco recibe el espacio de las barras del sistema.
        setContentView(FrameLayout(this).apply { fitsSystemWindows = true; addView(web) })
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true   // Tablero guarda columnas, tema, etc. en localStorage
        web.addJavascriptInterface(TableroPuente(this), "TableroAndroid")
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
        }
        web.loadData("<p style='font-family:sans-serif;padding:2em;color:#888'>Iniciando rTorrent y Tablero…</p>", "text/html", "utf-8")

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
        web.destroy()
        super.onDestroy()
    }

    private fun cargarCuandoResponda() = thread(name = "tablero-espera", isDaemon = true) {
        while (vivo && !TableroServidor.responde()) Thread.sleep(500)
        if (vivo) runOnUiThread { web.loadUrl(TableroServidor.url) }
    }

    /** magnet:… o un .torrent (content://): se abre "Agregar torrents" con él. */
    private fun recibir(i: Intent?) {
        val uri = i?.data ?: return
        val js = if (uri.scheme == "magnet") {
            "tableroAdd(" + JSONObject().put("magnets", JSONArray().put(uri.toString())) + ")"
        } else {
            // tablero.py lee el .torrent por ruta: se copia al caché de la app.
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
