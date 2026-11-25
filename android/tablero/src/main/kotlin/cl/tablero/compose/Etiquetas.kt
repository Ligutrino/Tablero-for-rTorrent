package cl.tablero.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** Dónde aparece una etiqueta: cada lugar tiene su forma (color, logo o ambos), como PLACES en la web. */
val PLACES = listOf(
    "sidebar" to "Barra lateral", "column" to "Columna Etiquetas",
    "bar" to "Barra de etiquetas", "other" to "Detalles, menús y diálogos",
)
private val DEFAULT_SHOW = mapOf("sidebar" to "both", "column" to "logo", "bar" to "both", "other" to "both")
private val DEFAULT_SHOW_ICON = mapOf("sidebar" to "both", "column" to "both", "bar" to "both", "other" to "both")
val LOGO_TONES = listOf("" to "Original", "invert" to "Invertido", "white" to "Blanco", "black" to "Negro", "auto" to "Automático")

/** Logos ya leídos (null: no está el archivo, se muestra como si no tuviera). */
private val logos = mutableStateMapOf<String, ImageBitmap?>()

@Composable
fun logoBitmap(estado: Estado, url: String): ImageBitmap? {
    if (url.isEmpty()) return null
    val has = logos.containsKey(url)
    LaunchedEffect(url) {
        if (!logos.containsKey(url)) logos[url] = estado.api.logo(url)?.let { estado.plataforma.imagen(it) }
    }
    return if (has) logos[url] else null
}

/** Un logo aún sin subir (el elegido en Opciones de etiqueta), para la vista previa: se ve con la clave [key]. */
fun ponerLogo(key: String, bmp: ImageBitmap?) { logos[key] = bmp }

/** Si el logo de la etiqueta existe (falta el archivo: se muestra sin logo). */
fun liveLogo(c: TagEntry?): String = c?.logo?.takeIf { it.isNotEmpty() && !(logos.containsKey(it) && logos[it] == null) } ?: ""

fun tagMode(conf: TagConf, name: String, place: String): String {
    val c = conf.tags[name]
    if (liveLogo(c).isEmpty() && !hasIcon(c?.icon)) return "color"
    return c?.show?.get(place) ?: (if (c?.logo.isNullOrEmpty()) DEFAULT_SHOW_ICON else DEFAULT_SHOW).getValue(place)
}

/** El filtro CSS de tone-*: el logo en blanco, negro o invertido para leerse sobre su fondo. */
fun toneFilter(tone: String, bg: Color?, oscuro: Boolean): ColorFilter? {
    val t = if (tone == "auto") (if (bg != null) (if (textOn(bg) == Color.White) "white" else "black") else if (oscuro) "white" else "black") else tone
    return when (t) {
        "white" -> ColorFilter.tint(Color.White, BlendMode.SrcIn)
        "black" -> ColorFilter.tint(Color.Black, BlendMode.SrcIn)
        "invert" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        else -> null
    }
}

@Composable
fun LogoImg(estado: Estado, entry: TagEntry, h: Dp, bg: Color?, desc: String? = null) {
    val bmp = logoBitmap(estado, entry.logo)
    val ratio = min(4.5, if (entry.logoRatio > 0) entry.logoRatio else 2.0)
    val mod = Modifier.height(h).width(h * ratio.toFloat())
    if (bmp == null) { Box(mod); return }
    Image(bmp, desc, mod, contentScale = ContentScale.Fit, colorFilter = toneFilter(entry.logoTone, bg, LocalColores.current.oscuro))
}

/**
 * Una etiqueta como en la web (chipHTML): píldora de color con el nombre, solo el logo o ícono,
 * o ambos, según cómo se eligió verla en [place]. [extra] va después del nombre (p. ej. la cuenta).
 */
@Composable
fun Chip(estado: Estado, name: String, place: String = "other", extra: String? = null, on: Boolean? = null, modifier: Modifier = Modifier,
         conf: TagConf = estado.tagConf) {
    val entry = conf.tags[name] ?: TagEntry()
    val color = tagColor(conf, name)
    val mode = tagMode(conf, name, place)
    val logo = liveLogo(entry).isNotEmpty()
    val icon = if (!logo && hasIcon(entry.icon)) entry.icon else ""
    val c = LocalColores.current
    val alpha = if (on == false) 0.55f else 1f
    val shape = RoundedCornerShape(4.dp)
    val ring = if (on == true) Modifier.border(2.dp, if (mode == "logo") c.accent else c.text, shape) else Modifier
    if (mode == "logo" && (logo || icon.isNotEmpty())) {
        Row(modifier.clip(shape).background(if (place == "bar") c.panel2.copy(alpha = alpha) else Color.Transparent)
            .then(ring).padding(horizontal = if (place == "bar") 6.dp else 2.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            if (logo) LogoImg(estado, entry.copy(logo = liveLogo(entry)), 16.dp, null, name) else Icono(icon, size = 16.dp, tint = color.copy(alpha = alpha), desc = name)
            if (extra != null) Text(" $extra", fontSize = 11.sp, color = c.dim)
        }
        return
    }
    val fg = textOn(color)
    Row(modifier.clip(shape).background(color.copy(alpha = alpha)).then(ring).padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (mode == "both") {
            if (logo) LogoImg(estado, entry.copy(logo = liveLogo(entry)), 14.dp, color)
            else if (icon.isNotEmpty()) Icono(icon, size = 12.dp, tint = fg)
        }
        Text(name, fontSize = 12.sp, color = fg, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (extra != null) Text(extra, fontSize = 11.sp, color = fg.copy(alpha = 0.75f))
    }
}

/** El inicio de una fila de la barra lateral: cuadro de color, logo o ícono y nombre (sideHTML). */
@Composable
fun SideTag(estado: Estado, name: String, modifier: Modifier = Modifier, conf: TagConf = estado.tagConf) {
    val entry = conf.tags[name] ?: TagEntry()
    val color = tagColor(conf, name)
    val mode = tagMode(conf, name, "sidebar")
    val logo = liveLogo(entry).isNotEmpty()
    val icon = if (!logo && hasIcon(entry.icon)) entry.icon else ""
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            mode == "logo" && logo -> LogoImg(estado, entry.copy(logo = liveLogo(entry)), 18.dp, null, name)
            mode == "logo" && icon.isNotEmpty() -> Icono(icon, size = 18.dp, tint = color, desc = name)
            mode == "both" && logo -> {
                Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(color))
                LogoImg(estado, entry.copy(logo = liveLogo(entry)), 18.dp, null)
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else -> {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(color), contentAlignment = Alignment.Center) {
                    if (mode == "both" && icon.isNotEmpty()) Icono(icon, size = 12.dp, tint = textOn(color))
                }
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

