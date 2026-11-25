package cl.tablero.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Base64
import kotlin.math.roundToInt

const val LOGO_HELP = "Recomendado: PNG o WebP con fondo transparente, de 64 px de alto y entre 64 y 256 px de ancho " +
    "(proporción entre 1:1 y 4:1, por ejemplo 128×64 o 192×64). Se muestra a 14–20 px de alto; el doble de resolución " +
    "la mantiene nítida en pantallas de alta densidad. Peso ideal: hasta 50 KB; hasta 900 KB se guarda tal cual y, si pesa más, Tablero la reduce sola."
private const val LOGO_H = 64
private const val LOGO_MAXW = 256
private const val LOGO_KEEP = 900 * 1024

/** Un logo elegido y aún sin subir: los bytes, su proporción y lo que se revisó (líneas "good"/"warn" y texto). */
class LogoNuevo(val bytes: ByteArray, val ratio: Double, val report: List<Pair<String, String>>)

private fun tipoImagen(b: ByteArray): String? = when {
    b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "png"
    b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpeg"
    b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
    b.size > 6 && String(b, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "gif"
    else -> null
}

/** prepareLogo de la web: revisa la imagen contra lo recomendado y, si pesa demasiado, la reduce a 64 px de alto. */
fun prepararLogo(p: Plataforma, name: String, bytes: ByteArray): LogoNuevo {
    val tipo = tipoImagen(bytes) ?: throw IllegalArgumentException("Formato no admitido. Usa PNG, JPEG, WebP o GIF.")
    val img = p.imagen(bytes) ?: throw IllegalArgumentException("No se pudo leer la imagen")
    val w = img.width; val h = img.height; val ratio = w.toDouble() / h
    val ratioTxt = if (ratio >= 1) "${Formato.n1(ratio)}:1" else "1:${Formato.n1(1 / ratio)}"
    val lines = ArrayList<Pair<String, String>>()
    lines.add("" to "$name: $w×$h px · proporción $ratioTxt · ${Formato.size(bytes.size.toLong())}")
    lines.add(if (tipo == "jpeg") "warn" to "JPEG no tiene transparencia: el fondo se verá como un rectángulo." else "good" to "Formato con transparencia.")
    lines.add(if (h >= 32) "good" to "Alto suficiente para verse nítida." else "warn" to "Muy baja ($h px): se verá borrosa. Lo ideal es $LOGO_H px de alto.")
    lines.add(when {
        ratio > 4.2 -> "warn" to "Muy ancha: el logo se verá muy chico. Recorta los márgenes o usa una versión más compacta (máx. 4:1)."
        ratio < 0.8 -> "warn" to "Más alta que ancha: se verá angosta. Funciona mejor cuadrada o apaisada."
        else -> "good" to "Proporción adecuada."
    })
    if (bytes.size <= LOGO_KEEP) {
        lines.add(if (bytes.size <= 50 * 1024) "good" to "Peso adecuado." else "warn" to "Pesa más de lo necesario, pero se guardará tal cual y se verá bien.")
        return LogoNuevo(bytes, ratio, lines)
    }
    val nh = minOf(h, LOGO_H); val nw = minOf(LOGO_MAXW, (nh * ratio).roundToInt())
    val nh2 = maxOf(1, (nw / ratio).roundToInt())
    val out = p.reducirImagen(bytes, nw, nh2) ?: throw IllegalArgumentException("No se pudo reducir la imagen")
    lines.add("good" to "Pesa más de ${Formato.size(LOGO_KEEP.toLong())}: se guardará reducida a $nw×$nh2 px (${Formato.size(out.size.toLong())}). Revisa la vista previa.")
    return LogoNuevo(out, nw.toDouble() / nh2, lines)
}

private val hexColor = Regex("^#[0-9a-fA-F]{6}$")

/** tagDialog de la web: nombre, color, ícono o logo (con su tono) y cómo se ve en cada lugar, con vista previa. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EtiquetaDialogo(estado: Estado, old: String?) {
    val c = LocalColores.current
    val conf0 = old?.let { estado.tagConf.tags[it] } ?: TagEntry()
    var name by remember { mutableStateOf(old ?: "") }
    var color by remember { mutableStateOf(if (old != null) tagColorHex(estado.tagConf, old) else PALETTE.random()) }
    var icon by remember { mutableStateOf(conf0.icon.takeIf { it.startsWith("lucide:") } ?: "") }
    var logoUrl by remember { mutableStateOf(conf0.logo) }
    var logoRatio by remember { mutableStateOf(conf0.logoRatio) }
    var nuevo by remember { mutableStateOf<LogoNuevo?>(null) }
    var logoError by remember { mutableStateOf<String?>(null) }
    var tone by remember { mutableStateOf(conf0.logoTone) }
    val show = remember {
        mutableStateMapOf<String, String>().apply {
            val def = if (conf0.logo.isNotEmpty()) mapOf("sidebar" to "both", "column" to "logo", "bar" to "both", "other" to "both")
                else PLACES.associate { it.first to "both" }
            putAll(def); putAll(conf0.show)
        }
    }
    var unir by remember { mutableStateOf(false) }  // el nombre nuevo ya existe: unir ambas
    val nuevoKey = remember { "nuevo:" + System.nanoTime() }
    val src = if (nuevo != null) nuevoKey else logoUrl
    val pic = src.isNotEmpty() || hasIcon(icon)
    val entry = TagEntry(color = color, icon = if (src.isNotEmpty()) "" else icon, logo = src, logoRatio = if (nuevo != null) nuevo!!.ratio else logoRatio,
        logoTone = tone, show = if (pic) show.toMap() else emptyMap())
    val previewName = name.trim().ifEmpty { "Etiqueta" }
    val preview = TagConf(estado.tagConf.tags + (previewName to entry), estado.tagConf.order)

    Ventana(estado, if (old != null) "Opciones de etiqueta" else "Nueva etiqueta", if (unir) "Unir y guardar" else "Guardar", onOk = guardar@{
        val n = name.trim()
        if (n.isEmpty()) { estado.aviso("Escribe un nombre", true); return@guardar false }
        if (old != null && old != n && n in estado.allTags() && !unir) { unir = true; return@guardar false }
        estado.intenta {
            var url = logoUrl
            nuevo?.let { url = str(estado.api.post("/api/logo", mapOf("data" to Base64.getEncoder().encodeToString(it.bytes)))["url"]) }
            var tags = estado.tagConf.tags; var order = estado.tagConf.order
            if (old != null && old != n) {
                estado.api.post("/api/rename-tag", mapOf("old" to old, "new" to n))
                tags = tags - old; order = order.map { if (it == old) n else it }
                if (old in estado.filtro.tags) estado.filtro = estado.filtro.copy(tags = estado.filtro.tags - old + n)
            }
            val e = TagEntry(color = color, icon = if (url.isNotEmpty()) "" else icon, logo = url,
                logoRatio = if (url.isNotEmpty()) ((nuevo?.ratio ?: logoRatio).takeIf { it > 0 } ?: 2.0).let { Math.round(it * 1000) / 1000.0 } else 0.0,
                logoTone = if (url.isNotEmpty()) tone else "", show = if (url.isNotEmpty() || hasIcon(icon)) show.toMap() else emptyMap())
            tags = tags + (n to e)
            if (n !in order) order = estado.allTags().filter { it != n && it != old } + n
            estado.guardarEtiquetas(TagConf(tags, order))
            estado.refresh()
        }
    }, extra = {
        if (old != null) TextButton(onClick = { estado.dialogo = Dialogo.EliminarEtiqueta(old) }) { Text("Eliminar", color = c.bad) }
    }) {
        Campo("Nombre") {
            OutlinedTextField(name, { name = it; unir = false }, singleLine = true, placeholder = { Text("Por ejemplo: Blu-ray UHD", color = c.dim) }, modifier = Modifier.fillMaxWidth())
            if (unir) Marca("warn", "\"${name.trim()}\" ya existe. Toca Unir y guardar para juntar ambas etiquetas.")
        }
        Campo("Color") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in PALETTE) Box(Modifier.size(28.dp).clip(CircleShape).background(hex(p))
                    .border(if (p.equals(color, true)) 3.dp else 0.dp, c.text, CircleShape).clickable { color = p })
            }
            var otro by remember { mutableStateOf(color) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Otro…", fontSize = 13.sp, color = c.dim)
                OutlinedTextField(otro, { v -> otro = v; if (hexColor.matches(v.trim())) color = v.trim().lowercase() }, singleLine = true,
                    placeholder = { Text("#rrggbb", color = c.dim) }, modifier = Modifier.width(140.dp))
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(hex(color)))
            }
        }
        Campo("Ícono") {
            val label = ICON_GROUPS.flatMap { it.second }.find { "lucide:" + it.first == icon }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (label != null) { Icono(icon, size = 20.dp); Text(label.second, fontSize = 14.sp) } else Text("Sin ícono", fontSize = 13.sp, color = c.dim)
                if (label != null) BotonHerr("x", "Quitar") { icon = "" }
            }
            Hint("Una etiqueta lleva un ícono o un logo: elegir uno quita el otro.")
            for ((g, items) in ICON_GROUPS) {
                Text(g, fontSize = 12.sp, color = c.dim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((n, l) in items) {
                        val on = icon == "lucide:$n"
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(if (on) c.sel else c.panel2)
                            .clickable { icon = "lucide:$n"; logoUrl = ""; nuevo = null; logoError = null }, contentAlignment = Alignment.Center) {
                            Icono("lucide:$n", size = 18.dp, desc = l)
                        }
                    }
                }
            }
        }
        Campo("Logo") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.height(48.dp).widthIn(min = 96.dp).clip(RoundedCornerShape(6.dp)).background(c.panel2).padding(6.dp), contentAlignment = Alignment.Center) {
                    val bmp = if (src.isNotEmpty()) logoBitmap(estado, src) else null
                    if (bmp != null) Image(bmp, null, Modifier.height(36.dp), contentScale = ContentScale.Fit)
                    else Text("Sin logo", fontSize = 12.sp, color = c.dim)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotonHerr("image", "Elegir imagen…") {
                        estado.plataforma.elegirArchivos(listOf("image/png", "image/jpeg", "image/webp", "image/gif"), false) { fs ->
                            val f = fs.firstOrNull() ?: return@elegirArchivos
                            try {
                                val r = prepararLogo(estado.plataforma, f.name, java.io.File(f.path).readBytes())
                                ponerLogo(nuevoKey, estado.plataforma.imagen(r.bytes))
                                nuevo = r; icon = ""; logoError = null
                            } catch (e: Exception) { logoError = e.message ?: e.toString() }
                        }
                    }
                    if (src.isNotEmpty()) BotonHerr("x", "Quitar") { logoUrl = ""; nuevo = null; logoError = null }
                }
            }
            if (logoUrl.isNotEmpty() && nuevo == null && liveLogo(TagEntry(logo = logoUrl)).isEmpty())
                Marca("bad", "No se encontró el archivo de este logo en el servidor. Vuelve a elegir la imagen.")
            logoError?.let { Marca("bad", it) }
            nuevo?.report?.forEach { (k, t) -> if (k.isEmpty()) Text(t, fontSize = 13.sp, fontWeight = FontWeight.Medium) else Marca(k, t) }
            Hint(LOGO_HELP)
            if (src.isNotEmpty()) {
                Text("Color del logo", fontSize = 12.sp, color = c.dim)
                Row(Modifier.horizontalScroll(rememberScrollState())) { Seg(LOGO_TONES, tone) { tone = it } }
                Hint("Para que el logo se vea bien sobre el color de la etiqueta. Automático usa blanco o negro según lo claro u oscuro del fondo. Mira el resultado en la vista previa.")
            }
        }
        Campo("Apariencia") {
            if (!pic) Hint("Elige un ícono o un logo para decidir cómo se ve en cada lugar.")
            for ((p, label) in PLACES) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, fontSize = 13.sp)
                    FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (pic) Seg(listOf("color" to "Color", "logo" to "Logo o ícono", "both" to "Ambos"), show[p] ?: "both") { show[p] = it }
                        Box(Modifier.background(if (p == "sidebar") c.panel else Color.Transparent).padding(4.dp)) {
                            if (p == "sidebar") SideTag(estado, previewName, conf = preview) else Chip(estado, previewName, p, conf = preview)
                        }
                    }
                }
            }
        }
    }
}

/** deleteTag de la web: se quita de los torrents que la tengan (los torrents no se borran). */
@Composable
fun EliminarEtiquetaDialogo(estado: Estado, name: String) {
    val n = estado.torrents.count { name in it.tags }
    Ventana(estado, "Eliminar etiqueta", "Eliminar", danger = true, onOk = {
        estado.intenta {
            if (n > 0) estado.api.post("/api/rename-tag", mapOf("old" to name, "new" to ""))
            estado.filtro = estado.filtro.copy(tags = estado.filtro.tags - name)
            estado.guardarEtiquetas(TagConf(estado.tagConf.tags - name, estado.tagConf.order.filter { it != name }))
            estado.refresh()
        }
    }) {
        Chip(estado, name)
        Text("¿Eliminar la etiqueta \"$name\"?" + if (n > 0) " Se quitará de $n torrent${if (n > 1) "s" else ""} (los torrents no se borran)." else "", fontSize = 14.sp)
    }
}

/** tagMenu de la web (toque largo en una etiqueta de la barra lateral). */
@Composable
fun MenuEtiqueta(estado: Estado, name: String, expanded: Boolean, onDismiss: () -> Unit) {
    val c = LocalColores.current
    val order = estado.allTags(); val i = order.indexOf(name)
    val hs = estado.selected().map { it.hash }
    val f = estado.filtro.tags
    fun move(d: Int) {
        val o = order.toMutableList(); o.removeAt(i); o.add((i + d).coerceIn(0, o.size), name)
        estado.saveTagConf(estado.tagConf.copy(order = o))
    }
    DropdownMenu(expanded, onDismiss, containerColor = c.panel2) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Chip(estado, name) }
        HorizontalDivider(color = c.line)
        @Composable fun item(label: String, enabled: Boolean = true, run: () -> Unit) =
            DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { onDismiss(); run() })
        if (name != NO_TAG) {
            item(if (name in f) "Quitar del filtro" else if (f.isNotEmpty()) "Agregar al filtro" else "Filtrar por esta etiqueta") { estado.toggleTagFilter(name, true) }
            HorizontalDivider(color = c.line)
            item("Opciones de etiqueta…") { estado.dialogo = Dialogo.Etiqueta(name) }
            item("Agregar a ${hs.size} seleccionado${if (hs.size == 1) "" else "s"}", hs.isNotEmpty()) {
                estado.act("/api/edit-tags", mapOf("hashes" to hs, "add" to listOf(name), "remove" to emptyList<String>()))
            }
            item("Quitar de los seleccionados", hs.isNotEmpty()) {
                estado.act("/api/edit-tags", mapOf("hashes" to hs, "add" to emptyList<String>(), "remove" to listOf(name)))
            }
            HorizontalDivider(color = c.line)
            item("Subir", i > 0) { move(-1) }
            item("Bajar", i in 0 until order.size - 1) { move(1) }
            HorizontalDivider(color = c.line)
            item("Eliminar etiqueta…") { estado.dialogo = Dialogo.EliminarEtiqueta(name) }
        }
    }
}
