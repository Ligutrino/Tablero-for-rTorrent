package cl.tablero.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** relocateDialog de la web: mover los datos a otra carpeta, o decirle al torrent dónde ya están. */
@Composable
fun ReubicarDialogo(estado: Estado, hashes: List<String>) {
    val c = LocalColores.current
    val ts = hashes.mapNotNull { estado.byHash[it] }
    // d.directory es la carpeta propia del torrent con varios archivos, y la que lo contiene si es uno solo.
    fun parent(t: Torrent) = if (t.files > 1) t.directory.substringBeforeLast('/', "").ifEmpty { "/" } else t.directory
    val dirs = ts.map { parent(it) }.toSet()
    var target by remember { mutableStateOf(estado.prefs.lastDir.ifEmpty { if (dirs.size == 1) dirs.first() else "" }) }
    var move by remember { mutableStateOf(true) }
    var wantCheck by remember { mutableStateOf(false) }
    Ventana(estado, if (ts.size == 1) "Reubicar datos" else "Reubicar datos de ${ts.size} torrents", "Reubicar", onOk = {
        val tg = target.trim()
        if (tg.isEmpty()) { estado.aviso("Elige la nueva carpeta", true); false }
        else estado.intenta {
            estado.api.post("/api/relocate", mapOf("hashes" to hashes, "target" to tg, "move" to move, "check" to (move && wantCheck)))
            estado.updatePrefs { it.copy(lastDir = tg) }
            estado.aviso(if (move) "Moviendo los datos…" else "Verificando los datos en la nueva carpeta…")
            estado.refresh()
        }
    }) {
        NombresTorrents(ts)
        Campo("Ubicación actual") { if (dirs.size == 1) Ruta(dirs.first()) else Text("${dirs.size} carpetas distintas", fontSize = 14.sp) }
        Campo("Nueva carpeta") { CarpetaCampo(estado, target, { target = it }) }
        Campo("Qué hacer") {
            for ((v, t, d) in listOf(
                Triple(true, "Mover los archivos a la nueva carpeta", "Se copian a la nueva ubicación y se borran de la anterior. Entre discos distintos puede tardar."),
                Triple(false, "Los archivos ya están en la nueva carpeta", "Solo cambia dónde los busca el torrent y los verifica. Útil si los moviste tú o cambió el disco."),
            )) Row(Modifier.fillMaxWidth().clickable { move = v }, verticalAlignment = Alignment.Top) {
                RadioButton(move == v, { move = v })
                Column(Modifier.padding(top = 10.dp)) {
                    Text(t, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(d, fontSize = 12.sp, color = c.dim)
                }
            }
        }
        Campo("Al terminar") {
            // Al ubicar siempre se verifica: la casilla lo muestra y no se puede quitar.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(if (move) wantCheck else true, { wantCheck = it }, enabled = move)
                Text("Verificar los datos", fontSize = 14.sp, color = if (move) c.text else c.dim)
            }
            Hint("El torrent queda detenido mientras tanto y se reanuda solo al terminar, si estaba activo.")
        }
    }
}

/** renameFolderDialog de la web: renombra en el disco la carpeta que trae un torrent con varios archivos. */
@Composable
fun RenombrarCarpetaDialogo(estado: Estado, hash: String) {
    val t = estado.byHash[hash] ?: run { estado.dialogo = null; return }
    val d = estado.details?.takeIf { it.hash == hash }
    val cur = (d?.basePath ?: t.directory).trimEnd('/')
    val name0 = cur.substringAfterLast('/')
    val parent = cur.substringBeforeLast('/', "").ifEmpty { "/" }
    var name by remember { mutableStateOf(name0) }
    Ventana(estado, "Renombrar carpeta", "Renombrar", onOk = {
        val v = name.trim()
        when {
            v.isEmpty() -> { estado.aviso("Escribe el nombre nuevo", true); false }
            v == name0 -> true
            else -> estado.intenta {
                val r = estado.api.post("/api/rename-folder", mapOf("hash" to hash, "name" to v))
                estado.aviso("Carpeta renombrada: ${str(r["folder"])}")
                estado.refresh()
            }
        }
    }) {
        NombresTorrents(listOf(t))
        Campo("Carpeta actual") { Ruta(cur) }
        Campo("Nombre nuevo") {
            OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Hint("Se renombra en el disco la carpeta que trae el torrent, dentro de $parent. Los archivos de adentro no cambian y no hace falta verificarlos. " +
                "El nombre del torrent en la lista sigue igual. Si está activo, se detiene un momento y se reanuda solo.")
        }
    }
}
