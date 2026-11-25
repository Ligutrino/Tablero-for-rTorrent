package cl.tablero.ui

import androidx.compose.ui.window.DialogProperties

/**
 * Los diálogos de Tablero en Android: más anchos que los de Material y dibujados bajo las barras del sistema
 * (decorFitsSystemWindows = false), para que Ventana deje el espacio de esas barras con WindowInsets.systemBars.
 * Sin esto, en tabletas la ventana tapa la barra de navegación y los botones de abajo quedan bajo ella.
 */
fun propiedadesDialogo() = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
