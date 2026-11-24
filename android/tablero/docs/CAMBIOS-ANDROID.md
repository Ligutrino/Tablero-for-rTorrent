# Cambios de la variante Android

## En este repositorio (sobre 0.9.2-android.1)

- `/api/shutdown` daba error 500 con rTorrent 0.16.17: rTorrent cierra el socket sin responder a
  `system.shutdown.normal`. Ahora `_send` lo informa como `ConnectionError` y `shutdown()` lo da por bueno.
  (No está en `android.patch`.)

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
