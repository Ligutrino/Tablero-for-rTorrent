# Cambios de la variante Android

## 0.11-android.17-kotlin (25-11-2026, rama `kotlin`)

- El botón Más abre un menú normal (como los demás), cargado a la derecha y anclado al botón, en vez de la hoja.
- "Posición en la lista (#)" solo aparece con la lista ordenada por #: rTorrent no tiene cola de descargas y el # solo
  ordena la lista (y qué se verifica primero cuando hay varios esperando).

## 0.11-android.16-kotlin (25-11-2026, rama `kotlin`)

- La hoja del botón Más pasa a cuadrícula de íconos (como la de Compartir): una fila por sección, y en pantallas anchas
  las secciones lado a lado, así cabe entera sin desplazar. "Copiar identificador" se llama ahora "Hash".

## 0.11-android.15-kotlin (25-11-2026, rama `kotlin`)

- Sin el ⋮ de arriba (lista con selección y detalle): la barra de acciones de abajo suma "Más", que abre una hoja desde
  abajo con Archivos, Posición en la cola, Copiar y Quitar todas las etiquetas. Sirve para uno o varios torrents.
- La versión de Tablero queda fija al pie de la barra lateral (sale de Opciones).

## 0.11-android.14-kotlin (25-11-2026, rama `kotlin`)

- Menú de los torrents: Descargar en orden pasa a Archivos (la sección Descarga desaparece).

## 0.11-android.13-kotlin (25-11-2026, rama `kotlin`)

- Menú de los torrents: Verificar datos pasa a Archivos; en Posición, Al principio va primero.

## 0.11-android.12-kotlin (25-11-2026, rama `kotlin`)

- Menú de los torrents ordenado por secciones: Descarga (en orden, verificar), Archivos (abrir carpeta, reubicar,
  renombrar carpeta), Posición en la cola, Copiar (nombre, identificador, magnet) y al final Quitar todas las etiquetas.
  Sin la sección Prioridad: se cambia en la pestaña Opciones de cada torrent.

## 0.11-android.11-kotlin (25-11-2026, rama `kotlin`)

- El botón + quedaba en parte bajo la barra de navegación del sistema (la barra de abajo vacía, de alto 0, le hacía
  ignorar ese espacio). Ahora la barra de abajo solo existe cuando muestra algo.

## 0.11-android.10-kotlin (25-11-2026, rama `kotlin`)

- Velocidades lado a lado cuando caben (y "Tablero" delante si también cabe); si no, una sobre otra.
- El panel del gráfico deja libre la zona de los botones del sistema.
- Menú de cada torrent y menú de etiqueta con un ícono en cada opción, como los demás menús; lo activo lleva ✓ a la derecha.

## 0.11-android.9-kotlin (25-11-2026, rama `kotlin`)

- "Tablero" vuelve a la barra superior, junto a las velocidades, cuando cabe entero (tabletas, horizontal).
- Los diálogos respetan las barras del sistema (en tabletas tapaban la barra de navegación y los botones de abajo
  quedaban bajo ella) y miden como máximo 560 dp de ancho.
- Estado del puerto: el resultado de la prueba va al lado del botón Probar puerto.

## 0.11-android.8-kotlin (25-11-2026, rama `kotlin`)

- El gráfico de velocidad sale de Límites y conexiones: tocar las velocidades de arriba abre (o cierra) un panel con el
  gráfico en la mitad de abajo, bajo la lista.
- Formularios parejos (`compose/Controles.kt`): campos, selectores y botones de 36 dp de alto; casillas y radios
  alineados al borde, con el toque en toda la fila; todos los campos numéricos de 140 dp, a la derecha de su nombre;
  un solo estilo de botón con ícono; 16 dp entre secciones. Los diálogos ocupan el ancho de la pantalla menos 16 dp.

## 0.11-android.7-kotlin (25-11-2026, rama `kotlin`)

- Sin menú del engranaje: la barra superior lleva, de derecha a izquierda, encendido de rTorrent, puerto (del color de
  su estado), Opciones, Límites y conexiones, Ordenar y Buscar. Las velocidades quedan una sobre otra a la izquierda
  (siguen abriendo Límites) y la versión de Tablero se ve al pie de Opciones.

## 0.11-android.6-kotlin (25-11-2026, rama `kotlin`)

- Se quita la sección Tablero de la barra lateral. Arriba a la derecha quedan el botón de encendido de rTorrent (verde
  encendido, rojo apagado; apagar pide confirmar, encender no) y el engranaje, que ahora es un menú con Límites y
  conexiones, el estado del puerto y Opciones. Respaldo y restauración sigue dentro de Opciones; los botones de
  rTorrent salen de Opciones.

## 0.11-android.5-kotlin (25-11-2026, rama `kotlin`)

Tercera ronda de Compose: ya tiene todo lo de la web y la app no lleva WebView (se quitaron el globo,
`TableroWebActivity` y el puente `TableroAndroid`; la web sigue para el acceso desde la red).
- Agregar torrents completo: elegir .torrent con el selector de Android, archivos a bajar en árbol o lista, carpeta con
  Examinar… o Elegir con Android…, espacio libre, y "Ya está en la biblioteca" con Sumar rastreadores.
- Opciones de etiqueta (toque largo en una etiqueta de la barra lateral, o + para una nueva): nombre, color, ícono, logo
  con tono y apariencia por lugar con vista previa; subir, bajar y eliminar etiquetas.
- Límites y conexiones (tocando las velocidades de arriba), Estado del puerto, Opciones (engranaje), Apagar rTorrent,
  Respaldo y restauración (con el primer respaldo), Reubicar datos y Renombrar carpeta.
- La barra lateral suma la sección Tablero: Límites y conexiones, el puerto, Respaldo y Opciones.

## 0.11-android.4-kotlin (25-11-2026, rama `kotlin`)

Segunda ronda de Compose: tocar un torrent (sin nada elegido) abre su detalle a pantalla completa, con las
pestañas de la web: General (mapa de piezas, disponibilidad, datos, abrir carpeta), Archivos (árbol o lista,
ocultar los omitidos, prioridad por archivo o carpeta, reproducir mientras baja, mostrar en la carpeta),
Pares, Rastreadores (agregar rastreadores), Piezas (tocar un cuadro muestra sus piezas), Velocidad (del
torrent o total, 5 min / 30 min / 1 h; tocar o arrastrar muestra un instante) y Opciones. Las tablas se
filtran y ordenan como en la web. El menú del torrent suma "Abrir carpeta". La API no cambia.

## 0.11-android.3-kotlin (25-11-2026, rama `kotlin`)

Primera ronda de la interfaz en Compose (`cl.tablero.compose`), que reemplaza a la WebView en el teléfono:
lista, barra lateral con filtros, barra de etiquetas, búsqueda, orden, datos de cada fila, elegir torrents,
iniciar, pausar, detener, eliminar, asignar etiquetas, menú del torrent y agregar enlaces o .torrent recibidos
de otras apps. Lo que falta (detalle, opciones, respaldos…) se abre en la web de siempre desde el globo de
la barra de arriba. La API y los archivos de configuración no cambian. Ver `../COMPOSE.md`.

## 0.11-android.2-kotlin (25-11-2026, rama `kotlin`)

La misma 0.11-android.2, con la API servida por un backend en Kotlin (`cl.tablero.backend`) en vez de tablero.py con Chaquopy. La interfaz web no cambia. Mismas rutas, mismo JSON y mismos archivos de configuración. Ver `../PORTAR-A-KOTLIN.md`.

## 0.11-android.2 (25-11-2026)

- Deslizar dos dedos hacia abajo ya no recarga la página (que dejaba la pantalla en blanco un instante): vuelve a pedir torrents, etiquetas, conexiones generales, detalles y gráfico en el mismo lugar, con el aviso "Actualizando…".

## 0.11-android.1 (24-11-2026)

La variante 0.10.1-android.4 sobre Tablero 0.11 de escritorio, que es exactamente su base. Solo cambia el número de versión.

## 0.10.1-android.4 (24-11-2026)

Sobre escritorio en desarrollo con el ítem 49 rehecho: Conexiones generales = conexiones en total (`network.max_open_sockets`), pares por torrent y subidas por torrent; los pares mínimos son la mitad del máximo. Valores por defecto como BiglyBT: 250 en total, 50 pares y 4 subidas por torrent. `android.patch` aplicó sin cambios.

## 0.10.1-android.3 (24-11-2026)

Sobre Tablero de escritorio en desarrollo después de 0.10.1, que trae el ítem 49:

- "Límites y conexiones" (antes "Límites de velocidad"): además de la velocidad, Conexiones generales (pares máximos y mínimos, subidas simultáneas) y Al sembrar para todos los torrents.
- Pestaña Opciones de cada torrent: un campo vacío usa el valor general; "Usar general" y General/Propia en Al sembrar. Lo propio de un torrent manda sobre lo general.
- Los "?" de ayuda nuevos se abren tocándolos, como los demás.

A ancho de teléfono, en la pestaña Opciones el nombre va arriba de sus controles, para que Prioridad (Alta · Normal · Baja · Desactivada) no se corte a la derecha. Salió de esta variante y ya está también en escritorio.

Corrección del paquete: el `android.patch` de 0.10.1-android.2 se había generado contra una copia de escritorio a medio cambiar; el de esta versión está comprobado contra la versión de escritorio actual.

## 0.10.1-android.2 (24-11-2026)

- La carpeta de descarga por defecto es `/storage/emulated/0/Download/Torrents`, con T mayúscula, en los ejemplos de Tablero y en la guía.
- Módulo de ejemplo: Salir cierra Tablero. `TableroServidor.salir()` cierra la pantalla, la quita de las apps recientes y detiene tablero.py; `TableroAvisos` lo llama en su botón Salir y ya no vuelve a poner la notificación después. La pantalla de espera deja de reintentar cuando se cerró.

## 0.10.1-android.1 (24-11-2026)

La variante android.3 sobre Tablero 0.10.1 de escritorio (`android.patch` aplicado sin cambios, salvo el número de versión). Trae lo de 0.10.1:

- La pestaña Velocidad empieza en "Torrent seleccionado".
- Eliminar con datos un torrent de varios archivos borra solo sus archivos y las carpetas que queden vacías; Mover y Renombrar no aceptan una carpeta compartida con otros torrents.
- Las barras de progreso ya no se dibujan sobre los títulos de las tablas de abajo, ni queda una línea de 1 px sobre los títulos de la lista.
- Protección contra DNS rebinding: un pedido local con un `Host` desconocido recibe 403. La WebView debe usar `http://127.0.0.1` o `http://localhost` (ver la guía, sección 9).

## 0.9.2-android.3 (24-11-2026)

**Nuevo**
- Pantalla táctil: un botón "i" junto a cada texto que con mouse sale al pasar por encima (títulos # y Proporción, estado de un torrent con mensaje o en espera, Coincide, Disponibilidad, Identificador, leyendas de Piezas y Velocidad). Al tocarlo muestra el texto; al tocar otra parte se cierra.
- Tocar el mapa de piezas o el gráfico de velocidad muestra el detalle de ese punto, como al pasar el mouse.
- Recargar la página deslizando dos dedos hacia abajo. Un dedo sigue moviendo la lista y el pellizco sigue haciendo zoom; con la página ampliada, dos dedos la mueven y no recargan.

**Correcciones**
- El botón Agregar a menos de 760 px también quedó corregido en la versión de escritorio, con la misma regla.

## 0.9.2-android.2 (24-11-2026)

Basada en Tablero 0.9.2 de escritorio en desarrollo, que ya trae el gráfico de velocidad general en Límites. Revisión de todo lo que era de escritorio (ver la tabla "Opciones de escritorio y propuesta para Android").

**Nuevo**
- Primer inicio: Tablero pregunta dónde guardar los respaldos (y propone Semanal).
- Respaldos: Exportar el elegido (con `TableroAndroid.exportFile`, o como descarga en un navegador) e Importar un respaldo de otro equipo, que se abre para restaurarlo. Los importados no se borran con los automáticos.
- Pantalla táctil: botón de menú del torrent en la barra de arriba; Filtrar por esta etiqueta / Agregar al filtro en el menú de la etiqueta; Ajustar el ancho al texto en el menú de los títulos; bordes de columnas y separadores de paneles que se arrastran con el dedo.
- `--rtorrent-rc RUTA` para cuando Tablero no encuentra el rtorrent.rc solo.
- Módulo de ejemplo: `TableroAvisos` (notificación con velocidades, íconos de estado, Pausar todo, Reanudar todo, Salir y "Descarga completa"), pantalla de espera, enlaces al navegador, recarga al volver tras más de 2 minutos, zoom con pellizco y letra del sistema, "Guardar como" de Exportar, íconos de notificación en `res/drawable/`.

**Cambios**
- Los textos dicen "este teléfono/tableta" en vez de "este equipo"; los de respaldo hablan de cambiar de teléfono/tableta y de guardar una copia fuera con Exportar.
- Red: sin el aviso de `--bind`; dice que funciona con wifi y mientras la app está activa.
- Estado del puerto: explica que con datos móviles casi nunca se puede abrir.
- Restaurar: ayuda con un ejemplo de rutas de PC a Android; rtorrent.rc dice que no se marque si el respaldo viene de un PC.
- Ejemplos de ruta con /storage/emulated/0/Download/torrents.
- Con pantalla táctil no se nombran atajos de teclado, y Agregar dice "Elige archivos .torrent".
- A ancho de teléfono las dos velocidades van una sobre otra y Agregar queda como "+".

**Correcciones**
- A menos de 760 px de ancho el botón Agregar no se veía (una regla CSS más específica lo ocultaba). En esta variante ya sale.

## 0.9.2-android.1 (24-11-2026)

Basada en Tablero 0.9.2 de escritorio con los arreglos para rTorrent 0.16 (en desarrollo). Solo cambian `tablero.py` y `web/app.js`; la versión de escritorio no se toca.

- Nueva opción `--android` (o `TABLERO_PLATAFORMA=android`).
- Abrir carpeta ya no busca la sesión de escritorio ni usa xdg-open: devuelve la ruta y la interfaz se la pasa a la app (`TableroAndroid.openFolder`). Sin la app, esas opciones no aparecen.
- Comandos `sudo systemctl …` reemplazados por los botones Encender/Reiniciar rTorrent de la app (o un texto); comandos `ufw` ocultos.
- Examinar… y los respaldos empiezan en `/storage/emulated/0`.
- Cambiar el acceso desde la red reabre el servidor en el mismo proceso (sin `os.execv`).
- `--archivos CARPETA` (dónde empiezan Examinar… y los respaldos); `/storage/emulated/0` solo si se puede leer y escribir.
- `window.tableroBack()` para el botón Atrás.
- `tablero.run(argv)` / `tablero.stop()`, opción `--config`, `window.tableroAdd(...)`, campo `platform` en `/api/torrents` y `/api/access`.
