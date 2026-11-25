# Interfaz Compose (rama `kotlin`)

En el teléfono, Tablero se ve con Compose (`src/main/kotlin/cl/tablero/compose/`) en lugar de la web en una WebView.
Compose llama a la misma API que la web, en el mismo proceso y sin HTTP (`Tablero.get` / `Tablero.post` en `Server.kt`,
la misma tabla de rutas que `/api/...`). El servidor HTTP sigue corriendo: sirve la web para el acceso desde la red
y la reproducción mientras baja (`/stream`).

Mientras la interfaz Compose no tenga todo, el globo de la barra de arriba abre la web de siempre
(`TableroWebActivity`). Se quita cuando la tabla de abajo esté completa.

| Archivo | Qué tiene |
|---|---|
| `Api.kt` | `Api` (la API en el mismo proceso) y `Plataforma` (lo que pide al sistema: carpetas, reproductor, selectores) |
| `Modelo.kt` | `Torrent`, `Stats`, `TagConf`, `GeneralConf`: el JSON de la API con tipos |
| `Estado.kt` | `Estado`: `state`, `prefs`, `refresh()`, `act()`, filtros y orden de app.js |
| `Formato.kt` | tamaños, velocidades, fechas y textos de estado (`size`, `rate`, `eta`, `STATUS`…) |
| `Tema.kt` | colores de app.css (oscuro y claro), íconos de Lucide, colores de etiquetas |
| `Etiquetas.kt` | etiquetas como en la web: color, ícono o logo según el lugar |
| `Lista.kt`, `Pantalla.kt`, `Dialogos.kt` | la pantalla, la lista, la barra lateral y los diálogos |
| `Iconos.kt` | generado por `tools/gen-iconos.py` desde `web/icons.js` (no editar a mano) |

## Lo que tiene la web, y lo que ya está en Compose

Ronda 1 (✓) y lo que falta (○):

- ✓ Lista con estado, progreso, velocidades y etiquetas; tinte con el color de la primera etiqueta.
- ✓ Datos de cada fila: cualquiera de las 22 columnas de la web (orden, nombre, etiquetas, tamaño, progreso…).
- ✓ Orden por cualquier columna, otra vez la misma invierte; "Vista por defecto".
- ✓ Buscar por nombre.
- ✓ Barra lateral: filtros por estado, por etiqueta (toque largo: varias) y por rastreador, con sus cuentas.
- ✓ Barra de etiquetas con "Coincide: Cualquiera / Todas".
- ✓ Elegir torrents (toque largo, o tocar con algo ya elegido; elegir todos).
- ✓ Iniciar, Pausar, Detener, Etiquetas…, Eliminar… (con o sin los datos).
- ✓ Menú del torrent: prioridad, posición (subir, bajar, al principio, al final), descargar en orden,
  verificar datos, quitar etiquetas, copiar nombre, identificador o magnet.
- ✓ Asignar etiquetas (casillas a medias cuando solo algunos la tienen, etiqueta nueva).
- ✓ Agregar con enlaces magnet o URL, y los magnet y .torrent que llegan de otras apps.
- ✓ Sin conexión con rTorrent: aviso con "Encender rTorrent".
- ✓ Velocidad total arriba, con ⁽ˡ⁾ cuando hay límite.
- ✓ Tema oscuro y claro, unidades binarias o decimales (desde prefs; el diálogo Opciones llega en la ronda 2).
- ✓ Atrás cierra menú, diálogo, barra lateral, selección y búsqueda, en ese orden.
- ○ Detalle del torrent: General (mapa de piezas, disponibilidad, datos, abrir carpeta), Archivos (árbol o lista,
  prioridad por archivo, reproducir mientras baja), Pares, Rastreadores (agregar rastreadores), Piezas,
  Velocidad (gráfico 5 min / 30 min / 1 h, del torrent o total), Opciones (prioridad, límites, pares, al sembrar).
- ○ Agregar completo: elegir .torrent, archivos a bajar (árbol), carpeta (Examinar… y Elegir con Android…),
  espacio libre, duplicados (Sumar rastreadores).
- ○ Opciones de etiqueta: nombre, color, ícono, logo (con tono), apariencia por lugar; subir, bajar y eliminar etiquetas.
- ○ Límites y conexiones (con gráfico), puerto (estado, probar, cambiar).
- ○ Opciones: tema, tinte, barra de etiquetas, coincide, unidades, intervalo, apagar rTorrent, acceso desde la red,
  importar colores de ruTorrent.
- ○ Respaldo y restauración (crear, programar, exportar, importar, restaurar con cambio de carpetas), primer respaldo.
- ○ Reubicar datos, renombrar carpeta, abrir carpeta, errores de reubicación.

## Probar en el escritorio

`tools/tablero-compose` compila la interfaz con Compose Multiplatform (los mismos paquetes `androidx.compose` que
en Android) junto al backend, y la muestra sin ventana contra un rtorrent de Linux con datos de ejemplo:

```bash
native/build.sh host                                   # rtorrent para Linux, una vez
android/gradlew -p tools/tablero-host installDist
android/gradlew -p tools/tablero-compose installDist
tools/compose-shots.sh /tmp/capturas                   # una captura PNG por escena (412×892 dp)
tools/compose-shots.sh /tmp/capturas probar            # las acciones de verdad contra rTorrent: "TODO OK"
```

Las escenas están en `tools/tablero-compose/src/main/kotlin/TableroCompose.kt`. Lo que es solo de Android
(`TableroActivity`, selectores, Intents) se prueba en el teléfono.

## Al traer cambios de la web desde `main`

Un cambio en `web/app.js` o `web/app.css` se ve igual en el acceso desde la red, pero en el teléfono hay que
hacerlo también en Compose: el mismo texto, el mismo pedido a la API, en el archivo de la tabla de arriba.
Si cambia `web/icons.js`, vuelve a generar `Iconos.kt`:

```bash
tools/gen-iconos.py android/tablero/src/main/assets/web/icons.js > android/tablero/src/main/kotlin/cl/tablero/compose/Iconos.kt
```
