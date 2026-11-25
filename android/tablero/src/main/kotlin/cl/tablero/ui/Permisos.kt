package cl.tablero.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context.MODE_PRIVATE
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings

/** Los permisos que Tablero pide al abrirse: notificaciones, todos los archivos y sin ahorro de batería. */
object Permisos {
    private const val REQ_NOTIFICACIONES = 2

    /** Un aviso a la vez: primero archivos, al cerrarlo el de batería. */
    fun pedir(act: Activity) {
        pedirNotificaciones(act)
        ofrecerAccesoArchivos(act) { ofrecerSinRestriccionBateria(act) }
    }

    /**
     * Sin "Acceso a todos los archivos", rtorrent solo puede leer y escribir en la carpeta propia de la app:
     * cualquier otra ruta (p. ej. /storage/emulated/0/Download) da "Permission denied".
     */
    fun ofrecerAccesoArchivos(act: Activity, despues: () -> Unit) { with(act) {
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
    }

    /**
     * Sin esto, el ahorro de batería (y las capas propias de cada fabricante) pueden matar el servicio
     * con la pantalla apagada. Android no deja hacerlo en silencio: se abre el diálogo del sistema.
     */
    fun ofrecerSinRestriccionBateria(act: Activity) { with(act) {
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
    }

    fun pedirNotificaciones(act: Activity) { with(act) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICACIONES)
    }
    }
}
