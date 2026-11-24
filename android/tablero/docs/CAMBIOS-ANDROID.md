# Cambios de la variante Android

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
