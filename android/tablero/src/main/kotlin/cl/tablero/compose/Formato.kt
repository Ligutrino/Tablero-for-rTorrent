package cl.tablero.compose

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/** Los formatos de la web (es-CL): 1,5 MiB, 2,00, 25-11-2026, 14:03. */
object Formato {
    private val es = DecimalFormatSymbols(Locale("es", "CL")).apply { decimalSeparator = ','; groupingSeparator = '.' }
    private fun fmt(p: String) = ThreadLocal.withInitial { DecimalFormat(p, es) }
    private val f1 = fmt("#,##0.0")
    private val f2 = fmt("#,##0.00")
    private val f0 = fmt("#,##0")
    private val df = DateTimeFormatter.ofPattern("dd-MM-yyyy, HH:mm")

    /** Unidades binarias (KiB, como cuenta rTorrent) o decimales (KB), según Opciones. */
    @Volatile var decimal = false

    fun kb(): Long = if (decimal) 1000 else 1024
    fun units(): List<String> = if (decimal) listOf("B", "KB", "MB", "GB", "TB") else listOf("B", "KiB", "MiB", "GiB", "TiB")

    fun n1(x: Double): String = f1.get().format(x)
    fun n2(x: Double): String = f2.get().format(x)
    fun n0(x: Long): String = f0.get().format(x)

    fun size(b: Long): String {
        if (b <= 0) return "0 B"
        val u = units(); val k = kb().toDouble()
        val i = min(u.size - 1, floor(ln(b.toDouble()) / ln(k)).toInt())
        return (if (i > 0) n1(b / k.pow(i)) else b.toString()) + " " + u[i]
    }

    fun rate(b: Long): String = if (b != 0L) size(b) + "/s" else ""

    fun date(s: Long): String = if (s == 0L) "" else df.format(Instant.ofEpochSecond(s).atZone(ZoneId.systemDefault()))

    fun eta(s: Long): String {
        if (s < 0) return ""
        if (s < 60) return "$s s"
        val d = s / 86400; val h = (s / 3600) % 24; val m = (s / 60) % 60
        return if (d > 0) "$d d $h h" else if (h > 0) "$h h $m min" else "$m min"
    }

    fun pct(p: Double): String = n1(p * 100) + " %"
}

val STATUS = mapOf(
    "downloading" to "Descargando", "seeding" to "Compartiendo", "paused" to "En pausa",
    "stopped" to "Detenido", "finished" to "Completo", "checking" to "Verificando",
    "queued" to "En espera de verificación", "error" to "Error",
    "moving" to "Trasladando datos", "waiting" to "En espera de reubicación",
)

val STATUS_ICON = mapOf(
    "downloading" to "arrow-down", "seeding" to "arrow-up", "paused" to "pause", "stopped" to "square",
    "finished" to "check", "checking" to "refresh-cw", "queued" to "clock", "error" to "triangle-alert",
    "moving" to "arrow-right", "waiting" to "arrow-right",
)

val PRIO = mapOf(0 to "No descargar", 1 to "Baja", 2 to "Normal", 3 to "Alta")

fun statusText(t: Torrent): String {
    if ((t.status == "queued" || t.status == "waiting") && t.queuePos > 0) return "${STATUS[t.status]} ${t.queuePos}"
    return STATUS[t.status] ?: ""
}

/** rTorrent no avisa cuando un torrent no cabe en su disco: Tablero dice cuánto falta. */
fun messageText(t: Torrent): String =
    if (t.noSpaceNeed != null) "Sin espacio en el disco: le faltan ${Formato.size(t.noSpaceNeed)} y quedan ${Formato.size(t.noSpaceFree ?: 0)} libres"
    else t.message

fun statusTip(t: Torrent): String = when (t.status) {
    "queued" -> "Espera su turno: rTorrent verifica un torrent a la vez"
    "waiting" -> "Espera su turno: Tablero reubica un torrent a la vez"
    else -> messageText(t)
}

fun magnetOf(t: Torrent): String = "magnet:?xt=urn:btih:${t.hash}&dn=" +
    encodeUriComponent(t.name)

/** encodeURIComponent de JavaScript. */
fun encodeUriComponent(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")
