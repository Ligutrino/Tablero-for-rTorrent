package cl.tablero.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.widget.Toast
import io.github.ligutrino.rtorrent.engine.android.EngineProvider

/**
 * window.TableroAndroid dentro de la interfaz. Los métodos llegan en un hilo de la WebView.
 */
class TableroPuente(
    private val act: Activity,
    /** "Exportar el elegido…" de los respaldos: el "Guardar como" de Android con ese .zip. */
    private val exportar: (String) -> Unit,
    /** "Elegir con Android…": selector de carpetas; la respuesta va a window.tableroFolderPicked(id, …). */
    private val elegirCarpeta: (String) -> Unit,
) {

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

    /** ⚙ Opciones → Respaldo → "Exportar el elegido…": path es el .zip del respaldo. */
    @JavascriptInterface
    fun exportFile(path: String) = act.runOnUiThread { exportar(path) }

    /** "Elegir con Android…" junto a Examinar…: abre el selector de carpetas del sistema. */
    @JavascriptInterface
    fun pickFolder(id: String) = act.runOnUiThread { elegirCarpeta(id) }

    /**
     * "Reproducir mientras se descarga": url es http://127.0.0.1:8090/stream/… (Tablero entrega solo
     * las piezas completas). Se abre en el reproductor que elija el usuario (VLC, MX Player…).
     */
    @JavascriptInterface
    fun openStream(url: String, name: String) = act.runOnUiThread {
        val ext = name.substringAfterLast('.', "").lowercase()
        val tipo = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/*"
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), tipo).putExtra(Intent.EXTRA_TITLE, name)
        try {
            act.startActivity(Intent.createChooser(i, "Reproducir con"))
        } catch (e: Exception) {
            Toast.makeText(act, "No hay un reproductor instalado", Toast.LENGTH_LONG).show()
        }
    }
}
