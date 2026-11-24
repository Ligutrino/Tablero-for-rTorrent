package cl.tablero.ui

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.widget.Toast
import io.github.ligutrino.rtorrent.engine.android.EngineProvider

/**
 * window.TableroAndroid dentro de la interfaz. Los métodos llegan en un hilo de la WebView.
 */
class TableroPuente(private val act: Activity) {

    @JavascriptInterface
    fun openFolder(path: String) = act.runOnUiThread {
        val raiz = "/storage/emulated/0/"
        // Android/data/<paquete> tampoco lo abre el explorador del sistema desde Android 11.
        if (!path.startsWith(raiz) || path.startsWith(raiz + "Android/data/")) {
            Toast.makeText(act, path, Toast.LENGTH_LONG).show()
            return@runOnUiThread
        }
        val doc = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:" + path.removePrefix(raiz).trimEnd('/'),
        )
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { act.startActivity(i) } catch (e: Exception) {
            Toast.makeText(act, path, Toast.LENGTH_LONG).show()
        }
    }

    /** "Encender rTorrent": el servicio arranca o adopta el daemon. */
    @JavascriptInterface
    fun startRtorrent() = EngineProvider.startService(act)

    /** "Reiniciar rTorrent" (p. ej. tras cambiar el puerto): parada ordenada y arranque. */
    @JavascriptInterface
    fun restartRtorrent() {
        Thread({
            runCatching { EngineProvider.get(act).stop() }
            EngineProvider.startService(act)
        }, "tablero-restart").start()
    }
}
