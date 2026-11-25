package cl.tablero.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

const val BACKUP_HAS = "Etiquetas y logos, tus preferencias de la interfaz, el acceso desde la red, la sesión de rTorrent (cada torrent con su avance, carpeta, etiquetas y estado) y rtorrent.rc. No incluye los archivos descargados."
private val EVERY = listOf("never" to "Nunca", "daily" to "Diario", "weekly" to "Semanal")

/** backupDialog de la web: la carpeta, el respaldo automático y los respaldos que hay (crear, restaurar, exportar, importar). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RespaldosDialogo(estado: Estado) {
    val c = LocalColores.current
    var info by remember { mutableStateOf<Map<String, Any?>?>(null) }
    LaunchedEffect(Unit) {
        try { info = estado.api.get("/api/backups") } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            estado.aviso(e.texto(), true); estado.dialogo = null
        }
    }
    val i0 = info ?: run { Ventana(estado, "Respaldo y restauración", null, cancel = "Cerrar") { Text("Cargando…", color = c.dim) }; return }
    var folder by remember { mutableStateOf(str(i0["folder"])) }
    var every by remember { mutableStateOf(str(i0["every"]).ifEmpty { "never" }) }
    var keep by remember { mutableStateOf(lng(i0["keep"]).takeIf { it > 0 }?.toString() ?: "5") }
    var files by remember { mutableStateOf(listOfAny(i0["files"]).map { asMap(it) }) }
    var chosen by remember { mutableStateOf(files.firstOrNull()?.let { str(it["path"]) } ?: "") }
    var busy by remember { mutableStateOf(false) }
    suspend fun reload() {
        files = try { listOfAny(estado.api.get("/api/backups", mapOf("folder" to folder.trim()))["files"]).map { asMap(it) } } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e; emptyList()
        }
        if (files.none { str(it["path"]) == chosen }) chosen = files.firstOrNull()?.let { str(it["path"]) } ?: ""
    }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(folder) { if (first) { first = false; return@LaunchedEffect }; delay(400); reload() }
    Ventana(estado, "Respaldo y restauración", "Guardar", cancel = "Cerrar", onOk = {
        estado.intenta {
            estado.api.post("/api/backup-settings", mapOf("folder" to folder.trim(), "every" to every, "keep" to (keep.toLongOrNull() ?: 5L)))
            estado.aviso(if (every == "never") "Guardado" else "Respaldo ${if (every == "daily") "diario" else "semanal"} activado")
        }
    }) {
        Campo("Carpeta") {
            CarpetaCampo(estado, folder, { folder = it })
            Hint("Donde se guardan los respaldos. Para que sobrevivan a un cambio de teléfono/tableta, guarda una copia fuera de él con Exportar (en el PC o en la nube).")
        }
        Campo("Automático") {
            Seg(EVERY, every) { every = it }
            FilaNum("Guardar los últimos") { NumCampo(keep, { keep = it.take(3) }, "respaldos") }
            Hint("Tablero hace el respaldo solo mientras está corriendo, y borra los automáticos más antiguos.")
        }
        Campo("Respaldos") {
            if (files.isEmpty()) Text("No hay respaldos en esta carpeta.", fontSize = 13.sp, color = c.dim)
            for (f in files) {
                val p = str(f["path"])
                Opcion(chosen == p, { chosen = p }, Formato.date(lng(f["time"]))) { Text(Formato.size(lng(f["size"])), fontSize = 13.sp, color = c.dim) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BotonHerr("archive", "Crear respaldo ahora") {
                    if (busy) return@BotonHerr
                    busy = true
                    estado.launch {
                        estado.intenta {
                            val r = estado.api.post("/api/backup", mapOf("folder" to folder.trim()))
                            estado.aviso("Respaldo creado: ${lng(r["torrents"])} torrents")
                            chosen = str(r["path"]); reload(); chosen = str(r["path"])
                        }
                        busy = false
                    }
                }
                if (files.isNotEmpty()) {
                    BotonHerr("rotate-ccw", "Restaurar el elegido…") { if (chosen.isNotEmpty()) estado.dialogo = Dialogo.Restaurar(chosen) }
                    BotonHerr("download", "Exportar el elegido…") { if (chosen.isNotEmpty()) estado.plataforma.exportar(chosen) }
                }
                BotonHerr("upload", "Importar…") {
                    estado.plataforma.elegirArchivos(listOf("application/zip", "application/octet-stream"), false) { fs ->
                        val f = fs.firstOrNull() ?: return@elegirArchivos
                        estado.aviso("Importando ${f.name}…")
                        estado.launch {
                            estado.intenta {
                                val r = estado.api.importarRespaldo(f.path, folder.trim(), f.name)
                                estado.dialogo = Dialogo.Restaurar(str(r["path"]))
                            }
                        }
                    }
                }
            }
            Hint("Exportar guarda una copia del respaldo donde elijas. Importar trae un respaldo de otro equipo a esta carpeta y lo abre para restaurarlo.")
            Hint("Incluye: $BACKUP_HAS")
        }
    }
}

/** firstBackupFolder de la web: la primera vez en Android, dónde guardar los respaldos. */
@Composable
fun PrimerRespaldoDialogo(estado: Estado, info: Map<String, Any?>) {
    var folder by remember { mutableStateOf(str(info["folder"])) }
    var every by remember { mutableStateOf("weekly") }
    Ventana(estado, "Respaldos de Tablero", "Guardar", cancel = "Ahora no", onCancel = { estado.updatePrefs { it.copy(backupAsked = true) } }, onOk = {
        estado.intenta {
            estado.api.post("/api/backup-settings", mapOf("folder" to folder.trim(), "every" to every, "keep" to (lng(info["keep"]).takeIf { it > 0 } ?: 5L)))
            estado.updatePrefs { it.copy(backupAsked = true) }
            estado.aviso("Carpeta de respaldos guardada")
        }
    }) {
        Campo("Carpeta") {
            CarpetaCampo(estado, folder, { folder = it })
            Hint("Dónde guardar los respaldos: tus etiquetas, opciones y los torrents con su avance. Elige una carpeta que no se borre al desinstalar la app; Download o Documents sirven.")
        }
        Campo("Automático") {
            Seg(EVERY, every) { every = it }
            Hint("Se cambia después en Opciones → Respaldo, donde también puedes exportar e importar respaldos.")
        }
    }
}

/** restoreDialog de la web: qué restaurar y, si las rutas cambiaron, por cuáles cambiarlas. */
@Composable
fun RestaurarDialogo(estado: Estado, path: String) {
    val c = LocalColores.current
    var info by remember { mutableStateOf<Map<String, Any?>?>(null) }
    LaunchedEffect(path) {
        try { info = estado.api.post("/api/restore-inspect", mapOf("path" to path)) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            estado.aviso(e.texto(), true); estado.dialogo = null
        }
    }
    val i0 = info ?: run { Ventana(estado, "Restaurar respaldo", null) { Text("Leyendo el respaldo…", color = c.dim) }; return }
    val has = asMap(i0["has"])
    val torrents = lng(i0["torrents"]); val present = lng(i0["present"])
    // (clave, texto, marcado al abrir, se puede)
    val parts = listOf(
        listOf("torrents", "Torrents de rTorrent (${torrents - present} nuevos" + (if (present > 0) "; $present ya están y no se tocan" else "") + ")", torrents > present, true),
        listOf("tags", "Etiquetas y logos", bool(has["tags"]), bool(has["tags"])),
        listOf("prefs", "Preferencias de la interfaz (columnas, tema, filtros…)", bool(has["prefs"]), bool(has["prefs"])),
        listOf("access", "Acceso desde la red y su contraseña", false, bool(has["access"])),
        listOf("rc", "rtorrent.rc (reemplaza el de $THIS_DEVICE; se guarda una copia. No lo marques si el respaldo viene de un PC: sus rutas y su conexión con rTorrent no sirven aquí)", false, bool(has["rc"])),
    )
    val want = remember { mutableStateMapOf<String, Boolean>().apply { for (p in parts) put(p[0] as String, (p[2] as Boolean) && (p[3] as Boolean)) } }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf(listOfAny(i0["folders"]).map { asMap(it) }) }
    LaunchedEffect(from, to) {
        if (from.isEmpty() && to.isEmpty()) return@LaunchedEffect
        delay(400)
        try { folders = listOfAny(estado.api.post("/api/restore-inspect", mapOf("path" to path, "from" to from.trim(), "to" to to.trim()))["folders"]).map { asMap(it) } }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e }
    }
    Ventana(estado, "Restaurar respaldo", "Restaurar", onOk = {
        try {
            val r = estado.api.post("/api/restore", mapOf("path" to path, "parts" to want.toMap(), "from" to from.trim(), "to" to to.trim()))
            estado.dialogo = Dialogo.Restaurado(r)
            false
        } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; estado.aviso(e.texto(), true); false }
    }) {
        Campo("Respaldo") {
            Text(path.substringAfterLast('/'), fontSize = 14.sp)
            Hint("Creado el ${Formato.date(lng(i0["created"]))} en el equipo ${str(i0["host"]).ifEmpty { "?" }} con Tablero ${str(i0["version"])}: $torrents torrents.")
        }
        Campo("Qué restaurar") {
            for (p in parts) {
                val k = p[0] as String; val avail = p[3] as Boolean
                Casilla(want[k] == true, { want[k] = it }, enabled = avail) { Text(p[1] as String, fontSize = 14.sp, color = if (avail) c.text else c.dim) }
            }
        }
        Campo("Carpetas") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Cambiar", fontSize = 13.sp)
                CampoTexto(from, { from = it }, Modifier.weight(1f), placeholder = "/ruta/en/el/respaldo")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("por", fontSize = 13.sp)
                CampoTexto(to, { to = it }, Modifier.weight(1f), placeholder = ANDROID_DIR)
            }
            for (f in folders) {
                val found = lng(f["found"]); val count = lng(f["count"])
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(c.panel2).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("En el respaldo", fontSize = 11.sp, color = c.dim); Ruta(str(f["from"]))
                    Text("Aquí", fontSize = 11.sp, color = c.dim); Ruta(str(f["to"]))
                    Marca(if (found == count) "good" else if (found > 0) "" else "bad", "$found de $count torrents")
                }
            }
            Hint("Si el respaldo viene de un PC, cambia el comienzo de las rutas, por ejemplo /media/datos/Torrents por $ANDROID_DIR. ✓ = los datos están ahí.")
        }
    }
}

/** restoredDialog de la web: lo que quedó restaurado y, si hace falta, reiniciar rTorrent. */
@Composable
fun RestauradoDialogo(estado: Estado, r: Map<String, Any?>) {
    val t = r["torrents"]?.let { asMap(it) }
    val lines = ArrayList<String>()
    fun pl(n: Long, a: String, b: String) = if (n == 1L) a else b
    if (t != null) {
        val w = lng(t["written"]); val l = lng(t["loaded"]); val s = lng(t["skipped"])
        if (w > 0) lines.add("$w torrent${pl(w, "", "s")} listo${pl(w, "", "s")} en la sesión de rTorrent: aparecen al reiniciarlo, con su avance (sin verificar de nuevo si los archivos no cambiaron).")
        if (l > 0) lines.add("$l torrent${pl(l, "", "s")} agregado${pl(l, "", "s")} y en verificación (la carpeta de sesión de rTorrent no se podía escribir).")
        if (s > 0) lines.add("$s ya estaba${pl(s, "", "n")} en rTorrent y no se tocaron.")
    }
    if (r.containsKey("tags")) lines.add("Etiquetas y logos restaurados.")
    if (bool(r["prefs"])) lines.add("Preferencias de la interfaz restauradas.")
    if (bool(r["access"])) lines.add("Acceso desde la red restaurado: se aplica al reiniciar Tablero.")
    if (bool(r["rc"])) lines.add("rtorrent.rc restaurado en ${str(r["rc"])} (la copia anterior quedó con la terminación .antes-de-tablero). Se aplica al reiniciar rTorrent.")
    val restart = (t != null && bool(t["restart"])) || bool(r["rc"])
    LaunchedEffect(Unit) { estado.recargarTodo() }
    Ventana(estado, "Respaldo restaurado", null, cancel = "Cerrar") {
        Campo("Resultado") {
            if (lines.isEmpty()) Text("• No había nada que restaurar.", fontSize = 14.sp)
            for (x in lines) Text("• $x", fontSize = 14.sp)
        }
        if (restart) Campo("rTorrent") {
            BotonRtorrent(estado, "restart")
            Hint("Reiniciar apaga rTorrent (guardando la sesión) y lo vuelve a encender con lo restaurado.")
        }
    }
}
