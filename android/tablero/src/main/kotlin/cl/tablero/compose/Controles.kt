package cl.tablero.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Controles de formulario con una medida pareja: todos de ALTO de alto, alineados al borde izquierdo del diálogo
 * (sin el margen de 48 dp que Material deja alrededor de casillas y radios) y con el toque en toda la fila.
 */

/** La altura de campos, selectores, botones y filas con casilla o radio. */
val ALTO = 36.dp

/** Espacio entre el control y su texto. */
private val GAP = 10.dp

/** Una casilla con su texto; tocar cualquier parte de la fila la cambia. */
@Composable
fun Casilla(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(),
            label: @Composable RowScope.() -> Unit) {
    val c = LocalColores.current
    Row(modifier.heightIn(min = ALTO).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP)) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Checkbox(checked, null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.dim))
        }
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.text) { label() }
    }
}

/** Un radio con su texto (y una explicación opcional debajo); tocar la fila lo elige. */
@Composable
fun Opcion(selected: Boolean, onClick: () -> Unit, label: String, detail: String? = null, enabled: Boolean = true,
           trailing: (@Composable () -> Unit)? = null) {
    val c = LocalColores.current
    Row(Modifier.fillMaxWidth().heightIn(min = ALTO).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = if (detail == null) Alignment.CenterVertically else Alignment.Top, horizontalArrangement = Arrangement.spacedBy(GAP)) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            RadioButton(selected, null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.dim),
                modifier = if (detail == null) Modifier else Modifier.padding(top = 1.dp))
        }
        Column(Modifier.weight(1f).then(if (detail == null) Modifier else Modifier.padding(bottom = 6.dp))) {
            Text(label, fontSize = 14.sp, color = if (enabled) c.text else c.dim)
            if (detail != null) Text(detail, fontSize = 12.sp, color = c.dim)
        }
        trailing?.invoke()
    }
}

/** Campo de texto de ALTO de alto (o más, si es de varias líneas), con borde como los selectores. */
@Composable
fun CampoTexto(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), placeholder: String? = null,
    suffix: String? = null, enabled: Boolean = true, singleLine: Boolean = true, minHeight: Dp = ALTO,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None, trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalColores.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(value, onChange, modifier.onFocusChanged { focused = it.isFocused }, enabled = enabled, singleLine = singleLine,
        textStyle = TextStyle(fontSize = 14.sp, color = if (enabled) c.text else c.dim), cursorBrush = SolidColor(c.accent),
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation) { inner ->
        Row(Modifier.heightIn(min = minHeight).border(1.dp, if (focused) c.accent else c.line, shape).padding(start = 10.dp, end = if (trailing != null) 2.dp else 10.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (value.isEmpty() && placeholder != null)
                    Text(placeholder, fontSize = 14.sp, color = c.dim, maxLines = if (singleLine) 1 else 3, overflow = TextOverflow.Ellipsis)
                inner()
            }
            if (suffix != null) Text(suffix, fontSize = 12.sp, color = c.dim, modifier = Modifier.padding(start = 6.dp))
            trailing?.invoke()
        }
    }
}

/** El ancho de todos los campos numéricos, con su unidad adentro. */
val ANCHO_NUM = 140.dp

/** Una fila con el nombre a la izquierda y el campo numérico (u otro control) a la derecha. */
@Composable
fun FilaNum(label: String, field: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = ALTO), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 14.sp, color = LocalColores.current.text, modifier = Modifier.weight(1f))
        field()
    }
}

/** Como FilaNum, con una casilla delante del nombre (las reglas de siembra). */
@Composable
fun FilaCasillaNum(checked: Boolean, onChange: (Boolean) -> Unit, label: String, field: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Casilla(checked, onChange, modifier = Modifier.weight(1f)) { Text(label, fontSize = 14.sp, color = LocalColores.current.text) }
        field()
    }
}

/** Casilla de tres estados (etiquetas de varios torrents a la vez), con el toque en toda la fila. */
@Composable
fun CasillaTri(state: androidx.compose.ui.state.ToggleableState, onClick: () -> Unit, label: @Composable RowScope.() -> Unit) {
    val c = LocalColores.current
    Row(Modifier.fillMaxWidth().heightIn(min = ALTO).selectable(state == androidx.compose.ui.state.ToggleableState.On, role = Role.Checkbox, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GAP)) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            androidx.compose.material3.TriStateCheckbox(state, null, colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.dim))
        }
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.text) { label() }
    }
}
