package cl.tablero.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * La fuente de la marca de la web ("Tablero Marca" de app.css): Inter 4.0 (SIL Open Font License 1.1, rsms.me/inter),
 * recortada a las letras de "Tablero para rTorrent"; res/font tiene los mismos woff2 de app.css pasados a OTF.
 */
fun fuenteMarca() = FontFamily(Font(R.font.tablero_marca_400, FontWeight.Normal), Font(R.font.tablero_marca_600, FontWeight.SemiBold))
