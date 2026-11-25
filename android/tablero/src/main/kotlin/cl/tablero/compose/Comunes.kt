package cl.tablero.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Piezas que comparten los diálogos de la ronda 3 (los form() y frow() de la web).
 */

const val THIS_DEVICE = "este teléfono/tableta"
const val ANDROID_DIR = "/storage/emulated/0/Download/Torrents"

/** Una fila de formulario: el nombre arriba y el contenido debajo (frow de la web, en angosto). */
@Composable
fun Campo(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LocalColores.current.dim)
        content()
    }
}

/** mark() de la web: ✓, ⚠ o ✗ con su color, y el texto. */
@Composable
fun Marca(kind: String, text: String) {
    val c = LocalColores.current
    val color = when (kind) { "good" -> c.good; "warn" -> c.warn; "bad" -> c.bad; else -> c.text }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (kind in setOf("good", "warn", "bad"))
            Icono(mapOf("good" to "check", "warn" to "triangle-alert", "bad" to "x").getValue(kind), size = 15.dp, tint = color, modifier = Modifier.padding(top = 2.dp))
        Text(text, fontSize = 13.sp, color = if (kind == "bad" || kind == "warn") color else c.text)
    }
}

/** Ruta en letra de ancho fijo (el <code> de la web). */
@Composable
fun Ruta(p: String, color: Color = LocalColores.current.text) = Text(p, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = color)

/** Un número con su unidad al lado; vacío vale lo que diga [placeholder]. */
@Composable
fun NumCampo(v: String, onV: (String) -> Unit, suffix: String = "", placeholder: String? = null, decimal: Boolean = false, modifier: Modifier = Modifier.width(ANCHO_NUM)) {
    val c = LocalColores.current
    CampoTexto(v, { s -> onV(s.filter { it.isDigit() || (decimal && (it == ',' || it == '.')) }) }, modifier,
        placeholder = placeholder, suffix = suffix.ifEmpty { null },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))
}

fun numero(s: String): Double = s.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

/**
 * folderPicker de la web: el campo de la carpeta, "Examinar…" (las carpetas del teléfono, vía /api/dirs)
 * y "Elegir con Android…" (el selector del sistema).
 */
@Composable
fun CarpetaCampo(estado: Estado, value: String, onChange: (String) -> Unit, placeholder: String = ANDROID_DIR) {
    val c = LocalColores.current
    var panel by remember { mutableStateOf<Map<String, Any?>?>(null) }
    fun browse(path: String) = estado.launch {
        estado.intenta {
            val d = estado.api.get("/api/dirs", mapOf("path" to path))
            panel = d
            onChange(str(d["path"]))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CampoTexto(value, onChange, placeholder = placeholder)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonHerr("folder", "Examinar…", on = panel != null) { if (panel != null) panel = null else browse(value) }
            if (estado.plataforma.tieneSelectorCarpetas) BotonHerr("folder-open", "Elegir con Android…") {
                estado.plataforma.elegirCarpeta { p -> if (p != null) { onChange(p); panel = null } }
            }
        }
        panel?.let { d ->
            val path = str(d["path"]); val parent = str(d["parent"])
            val join = { n: String -> path.trimEnd('/') + "/" + n }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, c.line, RoundedCornerShape(6.dp)).background(c.panel)) {
                Row(Modifier.fillMaxWidth().background(c.panel2).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (parent.isNotEmpty()) browse(parent) }, enabled = parent.isNotEmpty()) {
                        Icono("arrow-up", size = 18.dp, desc = "Subir a la carpeta anterior", tint = if (parent.isNotEmpty()) c.text else c.dim)
                    }
                    Ruta(path)
                }
                val dirs = listOfAny(d["dirs"]).map { str(it) }
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    if (dirs.isEmpty()) Text("Sin subcarpetas", fontSize = 13.sp, color = c.dim, modifier = Modifier.padding(12.dp))
                    for (n in dirs) Row(Modifier.fillMaxWidth().background(if (value == join(n)) c.sel else Color.Transparent)
                        .clickable { onChange(join(n)) }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icono("folder", size = 16.dp, tint = c.dim)
                        Text(n, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { browse(join(n)) }) { Icono("chevron-right", size = 18.dp, desc = "Abrir") }
                    }
                }
                Text("Toca para elegir una carpeta · › para abrirla", fontSize = 11.sp, color = c.dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
