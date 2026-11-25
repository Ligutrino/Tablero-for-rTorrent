# Interfaz Compose (rama `kotlin`)

En el teléfono, Tablero se ve con Compose (`src/main/kotlin/cl/tablero/compose/`) en lugar de la web en una WebView.
Compose llama a la misma API que la web, en el mismo proceso y sin HTTP (`Tablero.get` / `Tablero.post` en `Server.kt`,
la misma tabla de rutas que `/api/...`). El servidor HTTP sigue corriendo: sirve la web para el acceso desde la red
y la reproducción mientras baja (`/stream`).

Desde la ronda 3 Compose tiene todo lo de la web y la app ya no lleva WebView (`TableroWebActivity` y el puente
`TableroAndroid` se quitaron). La web sigue en `src/main/assets/web` para abrir Tablero desde otros equipos.

| Archivo | Qué tiene |
|---|---|
| `Api.kt` | `Api` (la API en el mismo proceso) y `Plataforma` (lo que pide al sistema: carpetas, reproductor, selectores) |
| `Modelo.kt` | `Torrent`, `Stats`, `TagConf`, `GeneralConf`, `Detalles`, `Historia`: el JSON de la API con tipos |
| `Estado.kt` | `Estado`: `state`, `prefs`, `refresh()`, `act()`, filtros y orden de app.js |
| `Formato.kt` | tamaños, velocidades, fechas y textos de estado (`size`, `rate`, `eta`, `STATUS`…) |
| `Tema.kt` | colores de app.css (oscuro y claro), íconos de Lucide, colores de etiquetas |
| `Etiquetas.kt` | etiquetas como en la web: color, ícono o logo según el lugar |
| `Lista.kt`, `Pantalla.kt`, `Dialogos.kt` | la pantalla, la lista, la barra lateral y los diálogos |
| `Detalle.kt` | el detalle de un torrent y sus siete pestañas |
| `Agregar.kt` | Agregar torrents (archivos, árbol, carpeta, espacio libre) y Ya está en la biblioteca |
| `EtiquetaEditor.kt` | Opciones de etiqueta, eliminarla y su menú (toque largo en la barra lateral) |
| `Ajustes.kt` | Límites y conexiones, Estado del puerto, Opciones y Apagar rTorrent |
| `Respaldos.kt` | Respaldo y restauración, Restaurar, el resultado y el primer respaldo |
| `Reubicar.kt` | Reubicar datos y Renombrar carpeta |
| `Comunes.kt` | piezas de los formularios: campos, carpeta con Examinar… y Elegir con Android… |
| `Iconos.kt` | generado por `tools/gen-iconos.py` desde `web/icons.js` (no editar a mano) |

## Lo que tiene la web, y lo que ya está en Compose

Todo está (✓), en tres rondas:

- ✓ Lista con estado, progreso, velocidades y etiquetas; tinte con el color de la primera etiqueta.
- ✓ Datos de cada fila: cualquiera de las 22 columnas de la web (orden, nombre, etiquetas, tamaño, progreso…).
- ✓ Orden por cualquier columna, otra vez la misma invierte; "Vista por defecto".
- ✓ Buscar por nombre.
- ✓ Barra lateral: filtros por estado, por etiqueta (toque largo: menú con Agregar al filtro) y por rastreador, con sus cuentas.
- ✓ Barra de etiquetas con "Coincide: Cualquiera / Todas".
- ✓ Elegir torrents (toque largo, o tocar con algo ya elegido; elegir todos).
- ✓ Iniciar, Pausar, Detener, Etiquetas…, Eliminar… (con o sin los datos).
- ✓ Menú del torrent: prioridad, posición (subir, bajar, al principio, al final), descargar en orden,
  verificar datos, quitar etiquetas, copiar nombre, identificador o magnet.
- ✓ Asignar etiquetas (casillas a medias cuando solo algunos la tienen, etiqueta nueva).
- ✓ Agregar con enlaces magnet o URL, y los magnet y .torrent que llegan de otras apps.
- ✓ Sin conexión con rTorrent: aviso con "Encender rTorrent".
- ✓ Velocidad total arriba, con ⁽ˡ⁾ cuando hay límite.
- ✓ Tema oscuro y claro, unidades binarias o decimales .
- ✓ Atrás cierra menú, diálogo, barra lateral, selección y búsqueda, en ese orden.
- ✓ Detalle del torrent (tocar una fila sin nada elegido): General (mapa de piezas, disponibilidad, datos, abrir carpeta),
  Archivos (árbol o lista, ocultar omitidos, prioridad por archivo o carpeta, reproducir mientras baja, mostrar en
  la carpeta), Pares, Rastreadores (agregar rastreadores), Piezas, Velocidad (gráfico 5 min / 30 min / 1 h, del
  torrent o total), Opciones (prioridad, límites, pares, al sembrar). Filtrar y ordenar las tablas.
  En el teléfono: el orden se elige con un botón (no hay títulos de columna) y lo que en la web es un tooltip
  se muestra al tocar (leyendas, cuadros de Piezas, instantes del gráfico). No hay anchos de columna.
- ✓ Agregar completo: elegir .torrent, archivos a bajar (árbol o lista), carpeta (Examinar… y Elegir con Android…),
  espacio libre, duplicados (Sumar rastreadores).
- ✓ Opciones de etiqueta: nombre (unir si ya existe), color (paleta u otro en hexadecimal), ícono, logo (con tono,
  revisado y reducido si pesa más de 900 KB), apariencia por lugar con vista previa; subir, bajar y eliminar
  etiquetas; agregar o quitar de los elegidos. Se abre con toque largo en la barra lateral, o con + para una nueva.
- ✓ Límites y conexiones (con gráfico), puerto (estado, probar, cambiar). Se abren tocando las velocidades de arriba
  y desde el menú del engranaje.
- ✓ Opciones (menú del engranaje): tema, tinte, barra de etiquetas, coincide, unidades, intervalo, respaldo,
  acceso desde la red, importar colores de ruTorrent. Apagar y encender rTorrent: el botón de encendido de arriba
  (verde encendido, rojo apagado; apagar pide confirmar).
- ✓ Respaldo y restauración (crear, programar, exportar, importar, restaurar con cambio de carpetas), primer respaldo.
- ✓ Reubicar datos, renombrar carpeta, abrir carpeta, errores de reubicación (aviso).

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
