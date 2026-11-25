package cl.tablero.compose

import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Los colores de app.css (:root y [data-theme="light"]). */
@Immutable
data class Colores(
    val oscuro: Boolean,
    val bg: Color, val panel: Color, val panel2: Color, val line: Color, val text: Color, val dim: Color,
    val accent: Color, val sel: Color, val selLine: Color, val hover: Color, val rowAlt: Color,
    val good: Color, val warn: Color, val bad: Color, val down: Color, val up: Color, val barBg: Color,
    val pcDone: Color, val pcPartial: Color, val pcAvail: Color, val pcNosource: Color, val pcMissing: Color, val pcSkipped: Color,
    val spDown: Color, val spUp: Color,
)

fun hex(s: String): Color {
    val v = s.removePrefix("#").toLong(16)
    return Color(0xFF000000 or v)
}

val OSCURO = Colores(
    true, hex("#1b1d21"), hex("#23262b"), hex("#2a2d33"), hex("#363a41"), hex("#d8dbe0"), hex("#8b919b"),
    hex("#3d8bfd"), hex("#2f4d7a"), hex("#4a78bd"), hex("#2c3036"), hex("#1f2125"),
    hex("#4caf50"), hex("#f0b429"), hex("#ef5350"), hex("#4fc3f7"), hex("#81c784"), hex("#33373e"),
    hex("#199e70"), hex("#c98500"), hex("#3987e5"), hex("#e66767"), hex("#8b919b"), hex("#3a3e45"),
    hex("#3987e5"), hex("#199e70"),
)

val CLARO = Colores(
    false, hex("#ffffff"), hex("#f3f4f6"), hex("#e9ebee"), hex("#d4d7dc"), hex("#1f2328"), hex("#646b75"),
    hex("#1c6fe0"), hex("#cfe0fb"), hex("#7fa9ea"), hex("#eef2f8"), hex("#f8f9fa"),
    hex("#4caf50"), hex("#f0b429"), hex("#ef5350"), hex("#0277bd"), hex("#2e7d32"), hex("#dfe2e6"),
    hex("#1baf7a"), hex("#eda100"), hex("#2a78d6"), hex("#e34948"), hex("#8b919b"), hex("#dfe2e6"),
    hex("#2a78d6"), hex("#1baf7a"),
)

val LocalColores = staticCompositionLocalOf { OSCURO }

/**
 * El espacio de las barras del sistema, medido en la ventana principal. Dentro de un Dialog, Android entrega
 * WindowInsets en cero aunque la ventana llegue bajo la barra de navegación, así que Ventana usa esta medida.
 */
val LocalBarras = compositionLocalOf { androidx.compose.foundation.layout.PaddingValues(0.dp) }

/**
 * La de Material con el alto de línea relativo a la letra (1,35 como en app.css): con un fontSize
 * propio, el alto fijo de Material (24 sp en bodyLarge) cortaba o descentraba textos chicos.
 */
private val TIPOGRAFIA = Typography().run {
    fun TextStyle.rel() = copy(lineHeight = 1.3.em)
    Typography(
        displayLarge.rel(), displayMedium.rel(), displaySmall.rel(), headlineLarge.rel(), headlineMedium.rel(), headlineSmall.rel(),
        titleLarge.rel(), titleMedium.rel(), titleSmall.rel(), bodyLarge.rel(), bodyMedium.rel(), bodySmall.rel(),
        labelLarge.rel(), labelMedium.rel(), labelSmall.rel(),
    )
}

@Composable
fun TableroTema(theme: String, content: @Composable () -> Unit) {
    val c = if (theme == "light") CLARO else OSCURO
    val scheme = if (c.oscuro) darkColorScheme(
        primary = c.accent, onPrimary = Color.White, background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = c.panel2, onSurfaceVariant = c.dim,
        surfaceContainer = c.panel, surfaceContainerHigh = c.panel2, surfaceContainerHighest = c.panel2,
        surfaceContainerLow = c.panel, surfaceContainerLowest = c.bg,
        outline = c.line, outlineVariant = c.line, error = c.bad, secondaryContainer = c.sel, onSecondaryContainer = c.text,
        primaryContainer = c.sel, onPrimaryContainer = c.text,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = Color.White, background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = c.panel2, onSurfaceVariant = c.dim,
        surfaceContainer = c.panel, surfaceContainerHigh = c.panel2, surfaceContainerHighest = c.panel2,
        surfaceContainerLow = c.panel, surfaceContainerLowest = c.bg,
        outline = c.line, outlineVariant = c.line, error = c.bad, secondaryContainer = c.sel, onSecondaryContainer = c.text,
        primaryContainer = c.sel, onPrimaryContainer = c.text,
    )
    val barras = androidx.compose.foundation.layout.WindowInsets.systemBars.asPaddingValues()
    CompositionLocalProvider(LocalColores provides c, LocalBarras provides barras) {
        MaterialTheme(colorScheme = scheme, typography = TIPOGRAFIA, content = content)
    }
}

// ------------------------------------------------------------ íconos de Lucide

private val vectores = HashMap<String, ImageVector?>()

/** Un ícono de Lucide (web/icons.js) como ImageVector de líneas, o null si no existe. */
fun lucide(name: String): ImageVector? = synchronized(vectores) {
    vectores.getOrPut(name) {
        val paths = LUCIDE[name] ?: LUCIDE_EXTRA[name] ?: return@getOrPut null
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) b.addPath(
            pathData = addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        b.build()
    }
}

/** Un ícono de tag ("lucide:<nombre>") o de la interfaz ("<nombre>"). */
fun hasIcon(icon: String?): Boolean = icon != null && icon.startsWith("lucide:") && LUCIDE.containsKey(icon.substring(7))

@Composable
fun Icono(name: String, modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color = LocalContentColor.current, desc: String? = null) {
    val v = lucide(name.removePrefix("lucide:")) ?: return
    Icon(v, contentDescription = desc, modifier = modifier.size(size), tint = tint)
}

// ------------------------------------------------------------ colores de etiquetas

/** hashHue y hslHex de la web: el color de una etiqueta sin color elegido. */
fun hashHue(s: String): Int {
    var h = 0L
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        h = (h * 31 + cp) and 0xFFFFFFFFL
        i += Character.charCount(cp)
    }
    return (h % 360).toInt()
}

fun hslHex(h: Int, s0: Int, l0: Int): String {
    val s = s0 / 100.0; val l = l0 / 100.0
    fun f(n: Int): String {
        val k = (n + h / 30.0) % 12
        val c = l - s * min(l, 1 - l) * max(-1.0, min(min(k - 3, 9 - k), 1.0))
        return Math.round(c * 255).toInt().toString(16).padStart(2, '0')
    }
    return "#" + f(0) + f(8) + f(4)
}

private val hexRe = Regex("^#[0-9a-fA-F]{6}$")

fun tagColorHex(conf: TagConf, name: String): String {
    val c = conf.tags[name]?.color ?: ""
    return if (hexRe.matches(c)) c else hslHex(hashHue(name), 45, 42)
}

fun tagColor(conf: TagConf, name: String): Color = hex(tagColorHex(conf, name))

/** textOn de la web: negro o blanco según lo claro del fondo. */
fun textOn(c: Color): Color {
    fun lin(x: Float): Double = if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    val lum = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    return if (lum > 0.4) Color.Black else Color.White
}

