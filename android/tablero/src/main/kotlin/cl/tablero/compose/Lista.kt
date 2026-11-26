package cl.tablero.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun statusColor(c: Colores, s: String): Color = when (s) {
    "downloading" -> c.down
    "seeding" -> c.good
    "paused" -> c.warn
    "stopped", "finished" -> c.dim
    "checking", "moving" -> hex("#ba68c8")
    "queued" -> hex("#9575cd")
    "waiting" -> hex("#9e86a6")
    "error" -> c.bad
    else -> c.dim
}

/** La barra de progreso de la columna Progreso, con el porcentaje encima. */
@Composable
fun BarraProgreso(t: Torrent, modifier: Modifier = Modifier) {
    val c = LocalColores.current
    val p = t.progress
    val fill = when {
        t.status == "error" -> c.bad
        p >= 1 -> c.good
        !t.state -> c.dim
        else -> c.down
    }
    Box(modifier.height(16.dp).clip(RoundedCornerShape(3.dp)).background(c.barBg), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.toFloat().coerceIn(0f, 1f)).align(Alignment.CenterStart).background(fill.copy(alpha = 0.8f)))
        Text(Formato.pct(p), fontSize = 11.sp, color = c.text, fontWeight = FontWeight.Medium)
    }
}

/** El valor de una columna como texto corto para la fila (con su nombre cuando el número solo no se entiende). */
fun campo(t: Torrent, id: String): String = when (id) {
    "pos" -> if (t.pos > 0) "#${t.pos}" else ""
    "size" -> Formato.size(t.size)
    "eta" -> Formato.eta(t.eta).let { if (it.isEmpty()) "" else "Quedan $it" }
    "seeds" -> if (t.seeds > 0) "${t.seeds} semillas" else ""
    "peers" -> if (t.peers > 0) "${t.peers} pares" else ""
    "ratio" -> "Proporción ${Formato.n2(t.ratio)}"
    "up_total" -> "Subido ${Formato.size(t.upTotal)}"
    "done" -> "Descargado ${Formato.size(t.done)}"
    "left" -> if (t.left > 0) "Restan ${Formato.size(t.left)}" else ""
    "added" -> if (t.added > 0) "Agregado ${Formato.date(t.added)}" else ""
    "finished" -> if (t.finished > 0) "Completado ${Formato.date(t.finished)}" else ""
    "tracker" -> t.tracker
    "directory" -> t.directory
    "priority" -> PRIO[t.priority]?.let { "Prioridad $it" } ?: ""
    "files" -> if (t.files == 1L) "1 archivo" else "${t.files} archivos"
    "private" -> if (t.private) "Privado" else ""
    else -> ""
}

/**
 * Una fila de la lista: el ícono del estado y el nombre, la barra de progreso y los datos elegidos
 * (prefs.fields), teñida con el color de su primera etiqueta como en la web.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun FilaTorrent(estado: Estado, t: Torrent, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = LocalColores.current
    val fields = estado.prefs.fields
    val bg = when {
        selected -> c.sel
        estado.prefs.tint && t.tags.isNotEmpty() -> tagColor(estado.tagConf, t.tags[0]).copy(alpha = 0.16f).compositeOverBg(c.bg)
        else -> c.bg
    }
    Row(modifier.fillMaxWidth().background(bg).drawBehind {
        if (selected) drawRect(c.selLine, size = Size(3.dp.toPx(), size.height))
    }.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp).weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                STATUS_ICON[t.status]?.let { Icono(it, size = 15.dp, tint = statusColor(c, t.status)) }
                Spacer(Modifier.width(6.dp))
                Text(t.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = c.text, modifier = Modifier.weight(1f))
            }
            if ("progress" in fields) BarraProgreso(t, Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (id in fields) {
                    when (id) {
                        "status" -> Text(statusText(t), fontSize = 12.sp, color = statusColor(c, t.status))
                        "down_rate" -> if (t.downRate > 0) Text("↓ " + Formato.rate(t.downRate), fontSize = 12.sp, color = c.down)
                        "up_rate" -> if (t.upRate > 0) Text("↑ " + Formato.rate(t.upRate), fontSize = 12.sp, color = c.up)
                        "name", "progress", "tags" -> {}
                        else -> campo(t, id).takeIf { it.isNotEmpty() }?.let {
                            Text(it, fontSize = 12.sp, color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (t.status == "error" || t.noSpaceNeed != null) {
                Text(messageText(t), fontSize = 12.sp, color = c.bad, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if ("tags" in fields && t.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (x in t.tags) Chip(estado, x, "column")
                }
            }
        }
    }
    HorizontalDivider(color = c.line.copy(alpha = 0.5f), thickness = 0.5.dp)
}

/** Un color con transparencia ya mezclado con el fondo (para que la fila no deje ver lo de abajo). */
fun Color.compositeOverBg(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}
