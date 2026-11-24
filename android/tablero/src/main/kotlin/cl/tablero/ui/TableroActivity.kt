package cl.tablero.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    private fun pedirNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICACIONES)
    }
}
