package cl.tablero.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import java.io.File

/**
 * Traduce la carpeta que devuelve el selector de Android (un URI content://, del Storage Access
 * Framework) a su ruta real. rtorrent solo entiende rutas de archivo, y con "Acceso a todos los
 * archivos" la app puede usar esa ruta directamente, sin copiar nada.
 */
object Carpetas {
    private const val ALMACENAMIENTO = "com.android.externalstorage.documents"
    private const val DESCARGAS = "com.android.providers.downloads.documents"

    /** null si la carpeta no está en un almacenamiento con ruta (Drive, otra app, etc.). */
    fun rutaReal(ctx: Context, arbol: Uri): String? {
        val id = try { DocumentsContract.getTreeDocumentId(arbol) } catch (e: Exception) { return null }
        return when (arbol.authority) {
            ALMACENAMIENTO -> deIdDeDocumento(ctx, id)
            // La carpeta Descargas misma ("downloads"), o una subcarpeta con su ruta ("raw:/storage/…").
            DESCARGAS -> when {
                id == "downloads" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                id.startsWith("raw:") -> id.removePrefix("raw:")
                else -> null
            }
            else -> null
        }
    }

    /** "primary:Movies/Series" → /storage/emulated/0/Movies/Series; "1A2B-3C4D:Fotos" → /storage/1A2B-3C4D/Fotos. */
    fun deIdDeDocumento(ctx: Context, id: String): String? {
        val dos = id.indexOf(':')
        if (dos < 0) return null
        val volumen = id.substring(0, dos)
        val dentro = id.substring(dos + 1).trim('/')
        val raiz = when (volumen) {
            "primary" -> Environment.getExternalStorageDirectory()
            "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            else -> raizDeVolumen(ctx, volumen) ?: File("/storage/$volumen")
        }
        return if (dentro.isEmpty()) raiz.absolutePath else File(raiz, dentro).absolutePath
    }

    private fun raizDeVolumen(ctx: Context, uuid: String): File? {
        if (Build.VERSION.SDK_INT < 30) return null
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.storageVolumes.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }?.directory
    }
}
