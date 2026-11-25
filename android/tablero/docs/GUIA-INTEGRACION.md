# Tablero para rTorrent en Android: guía de integración

Este paquete es para el proyecto que porta rTorrent a Android y quiere usar **Tablero para rTorrent** como interfaz. Se puede leer sin conocer el proyecto de escritorio.

- Base: Tablero 0.11 (escritorio, Linux), con Límites y conexiones generales, con los arreglos para rTorrent 0.16 que salieron al probarlo en Termux y el gráfico de velocidad general en Límites.
- Esta variante: **0.11-android.1**, en `fuente/`. Es una rama aparte: la versión de escritorio no cambia.
- `android.patch`: todas las diferencias con la versión de escritorio (5 archivos: tablero.py y web/).
- `CAMBIOS-ANDROID.md`: qué trae cada versión de la variante. Sirve para llevar los cambios a cada versión nueva de escritorio (`patch -p1 < android.patch` dentro de la carpeta de la versión nueva).

## 0. Con el motor `:engine` (rtorrent 0.16.17 en modo daemon)

El proyecto Android ya tiene resuelto lo de rTorrent: binario estático `librtorrent.so`, daemon con XML-RPC por socket Unix, módulo `:engine` (`TorrentEngine`) y servicio en primer plano `specialUse`. Tablero se suma como **otro módulo de interfaz** que habla con el mismo daemon, sin tocar `:engine`:

```
 :app ──► :ui-tablero ──► WebView ──HTTP 127.0.0.1:8090──► tablero.py (Chaquopy)
              │                                                │
              └──────────► :engine (TorrentEngine) ──socket Unix──► rtorrent 0.16.17
                                                         ▲      │
                                tablero.py ──────────────┘ (el mismo socket, por SCGI)
```

Pasos concretos:

1. **Probarlo primero en Linux, con el mismo rtorrent.** La etapa `docker build --target hosttest .` del proyecto ya compila rtorrent 0.16.17 con tinyxml2 para Linux. Ahí, con el daemon corriendo: `python3 fuente/tablero.py --android --scgi /ruta/al/scgi.socket` y abrir `http://127.0.0.1:8090`. Tablero ya funciona con el rtorrent 0.16.28 de Termux, pero **no se ha probado con el XML-RPC de tinyxml2**: Tablero usa mucho `system.multicall`, `d.multicall2`, `f.multicall`, `p.multicall`, `t.multicall` y enteros de 64 bits (`i8`). Si algo falla, el error sale en la barra de estado de Tablero o en su salida.
2. **Agregar el módulo `:ui-tablero`** (ejemplo en `ejemplo-modulo-ui/`, ver 3.2): Chaquopy con `tablero.py` y `web/`, una actividad con WebView y el puente `TableroAndroid`.
3. **Arrancar Tablero desde el servicio de `:engine`**, después del daemon: `TableroServidor.iniciar(ctx, socketScgi, carpetaDescargas)`. Así sigue aplicando las reglas de siembra con la pantalla cerrada. Al detener el servicio: `TableroServidor.detener()`.
4. **Conectar el puente** con `TorrentEngine`: `startRtorrent()` → arrancar o adoptar el daemon; `restartRtorrent()` → apagarlo y arrancarlo.

Cosas a coordinar con `:engine`:

- **rtorrent.rc**: Tablero encuentra el .rc por el `-o import=` de la línea de comandos del daemon, o el que le diga la app con `--rtorrent-rc RUTA` (lo mejor si `:engine` corre rTorrent dentro de su propio proceso), y cambia ahí la línea del puerto (Estado del puerto → Cambiar puerto). Si `:engine` vuelve a escribir el .rc en cada arranque, ese cambio se pierde: conviene que `:engine` no pise la línea `network.listen.port.range.set` si ya existe, o que lea el puerto de su propia configuración.
- **Apagar rTorrent** desde Tablero (`system.shutdown.normal`): si `:engine` relanza el daemon solo cuando muere, Tablero no podrá apagarlo. Lo esperado: que `:engine` lo deje apagado hasta `startRtorrent()`.
- **Descargas en el almacenamiento propio de la app**: funciona tal cual. "Carpeta" vacía en Agregar usa la predeterminada de rTorrent; pasar esa misma carpeta a `--archivos` para que Examinar… y los respaldos empiecen ahí. Abrir carpeta sobre una carpeta privada solo puede mostrar la ruta (ningún explorador la abre). Cuando se decida copiar o descargar a Descargas, `Reubicar datos` de Tablero ya mueve los archivos y apunta el torrent a la nueva carpeta.
- **Dos clientes en el mismo socket** (`:engine` y Tablero): no hay problema; Tablero guarda lo suyo en `d.custom` con claves `tablero_tags`, `tablero_opts`, `tablero_pos`, que `:engine` no debe borrar.
- **Notificación del servicio**: `TableroAvisos` (en el módulo de ejemplo) toma la notificación de `:engine` por su ID y le pone velocidades, íconos de estado, Pausar todo, Reanudar todo y Salir, y avisa cada descarga completa (ver 6).

## 1. Cómo funciona

```
 ┌───────────── App Android ─────────────┐
 │  WebView  ──HTTP──►  tablero.py       │      rTorrent
 │  (web/)             (Python, stdlib)  │──SCGI──► (proceso nativo)
 │     ▲  window.TableroAndroid          │   XML-RPC   │
 │     └── puente JS ◄── Kotlin          │             ▼
 └───────────────────────────────────────┘     archivos en /storage/...
```

- **tablero.py** es un servidor HTTP hecho solo con la biblioteca estándar de Python 3 (sin pip, sin dependencias). Sirve la interfaz (`web/`) y una API JSON. Habla con rTorrent por XML-RPC sobre SCGI (TCP o socket Unix).
- **web/** es HTML, CSS y JavaScript sin compilar (sin frameworks, sin internet: íconos y tipografía van incluidos). Ya se adapta a pantallas angostas en vertical (se probó en Brave sobre tableta Android).
- Todo lo que Tablero guarda está en rTorrent (etiquetas, opciones y posición de cada torrent, en `d.custom`: claves `tablero_tags`, `tablero_opts`, `tablero_pos`) o en su carpeta de configuración (`tags.json`, `logos/`, `access.json`, `prefs.json`, respaldos).
- Tablero también aplica las reglas de siembra por torrent (detener al llegar a una proporción o tiempo): **solo mientras tablero.py está corriendo**. Por eso conviene que corra junto con rTorrent, no solo con la interfaz abierta.

## 2. Qué cambia la variante Android

Con `--android` (o la variable de entorno `TABLERO_PLATAFORMA=android`):

| Función de escritorio | Problema en Android | Qué hace ahora |
|---|---|---|
| **Abrir carpeta** (menú del torrent, botón Abrir en General, "Mostrar en la carpeta" en Archivos) | Usa `xdg-open` y busca la sesión de escritorio (`DISPLAY`/`WAYLAND_DISPLAY` leyendo `/proc`). En Termux falla con "No encontré la sesión de escritorio para abrir el explorador de archivos". | El servidor no abre nada: devuelve la ruta (`{"folder", "path", "android": true}`). La interfaz llama a `window.TableroAndroid.openFolder(ruta)` si la app lo ofrece; si no, **oculta** esas opciones. |
| Reiniciar Tablero al cambiar **⚙ Opciones → Red** | Usa `os.execv`, que en una app con Python embebido reemplazaría el proceso entero de la app. | Vuelve a abrir el servidor en la nueva dirección dentro del mismo proceso. |
| Comandos `sudo systemctl start/restart rtorrent` (sin conexión, Apagar rTorrent, cambio de puerto, restaurar respaldo) | No hay systemd ni sudo. | Botón **Encender rTorrent** / **Reiniciar rTorrent** si la app ofrece `startRtorrent()` / `restartRtorrent()`; si no, el texto "desde la app de Android que ejecuta rTorrent". |
| Comandos `sudo ufw allow …` (Estado del puerto, Red) | No hay ufw. | Ocultos. |
| Carpeta inicial de **Examinar…** y de los **respaldos** (`~`, `~/Respaldos de Tablero`) | El `~` de Termux o de una app es privado: ningún explorador de archivos lo ve. | La de `--archivos`; si no se da, `/storage/emulated/0` cuando se puede leer y escribir ahí. |
| Ventana GTK, ícono en la bandeja, avisos de escritorio, `.deb`, servicio systemd, `apt install gir1.2-xapp-1.0` | Son de Linux de escritorio. | No se incluyen (la carpeta `packaging/` no está en este paquete). La sección ⚙ Opciones → Ventana solo aparece dentro de la ventana GTK, así que en Android no sale. Ver 6 para el equivalente. |

| Textos que dicen "este equipo", "otro PC", "disco externo" | En Android es un teléfono o una tableta. | Dicen "este teléfono/tableta"; los respaldos hablan de cambiar de teléfono/tableta y de guardar una copia fuera con Exportar. Ejemplos de ruta con `/storage/emulated/0/Download/Torrents`. |
| ⚙ Opciones → Red | El aviso de `--bind` no aplica (los argumentos los pone la app). | Oculto. Se agrega que funciona con wifi y mientras la app está activa. |
| Estado del puerto → Si está cerrado | Con datos móviles la compañía comparte la IP pública. | Lo explica. |
| Restaurar respaldo | Un respaldo del PC trae rutas y un rtorrent.rc de PC. | La ayuda da un ejemplo de cambio de rutas de PC a Android; rtorrent.rc viene desmarcado y dice que no se marque si el respaldo viene de un PC. |
| Carpeta de respaldos | No hay un segundo disco que sugerir, y la carpeta de la app se borra al desinstalar. | La primera vez que se abre Tablero pregunta dónde guardar los respaldos (y propone Semanal). "Ahora no" no vuelve a preguntar. |

Respaldos, en ambos modos: **Exportar el elegido…** guarda una copia del respaldo donde el usuario quiera (en Android, con `TableroAndroid.exportFile`; en un navegador, como descarga) e **Importar…** trae un .zip de otro equipo a la carpeta de respaldos y abre Restaurar.

### 2.1 Pantalla táctil

Se activa sola cuando el puntero principal es un dedo (`(pointer: coarse)`), en la app o en el navegador del teléfono:

- Botón de menú (tres puntos) en la barra de arriba: abre el menú del torrent elegido. El toque largo también lo abre.
- En el menú de una etiqueta (toque largo): **Filtrar por esta etiqueta / Agregar al filtro / Quitar del filtro**, en vez de Ctrl+clic.
- En el menú de los títulos de la lista: **Ajustar el ancho al texto** (en escritorio es el doble clic en el borde).
- Los bordes de las columnas y los separadores de paneles se arrastran con el dedo, con una zona de toque más ancha.
- No se nombran atajos de teclado (Ctrl+O, Supr, Ctrl+clic…), ni en los botones ni en los menús.
- Agregar torrents dice "Elige archivos .torrent" en vez de "Arrastra…".
- A ancho de teléfono, las dos velocidades van una sobre otra y Agregar queda como solo "+", para que todo quepa sin ensanchar la página.
- Lo que con mouse sale al pasar por encima lleva al lado un botón "i" que lo muestra al tocarlo. El mapa de piezas y el gráfico de velocidad muestran el detalle del punto tocado.
- Dos dedos deslizados hacia abajo recargan la página (con aviso "Suelta para recargar"). No interfiere con el desplazamiento de un dedo ni con el pellizco, que se hace con zoom de la WebView.

Además (sirve en ambos modos, pero se agregó para Android):

- `tablero.run(argv)` y `tablero.stop()`: para arrancar y detener el servidor desde otro programa (Chaquopy) sin pasar por la línea de comandos.
- `--config CARPETA`: dónde guardar la configuración (por defecto `$XDG_CONFIG_HOME/tablero` o `~/.config/tablero`).
- `--rtorrent-rc RUTA`: el rtorrent.rc de rTorrent, si Tablero no lo encuentra solo.
- `--archivos CARPETA`: dónde empiezan Examinar… y los respaldos. Sin ella, en Android es `/storage/emulated/0` si la app tiene "Acceso a todos los archivos"; si no, la carpeta personal.
- `window.tableroBack()`: para el botón Atrás de Android; cierra el menú o diálogo abierto y devuelve `true`, o `false` si no había ninguno.
- `window.tableroAdd({...})`: abre "Agregar torrents" ya cargado (para cuando la app recibe un magnet o un .torrent desde otra app). Ver 5.
- `/api/torrents` y `/api/access` informan `"platform": "android"` o `"desktop"`.

Revisado y sin conflicto en Android: espacio libre (`os.statvfs`), prueba del puerto (pide a `https://ifconfig.co/port/N`, necesita internet), IP local, ubicar `rtorrent.rc` (lee `/proc/<pid de rTorrent>/cmdline`: funciona porque rTorrent corre con el mismo usuario que Tablero; si no lo encuentra, usa `$HOME/.rtorrent.rc` de rTorrent), Reubicar datos, Renombrar carpeta, Verificar datos, respaldo y restauración (rutas POSIX).

## 3. Cómo arrancarlo

### 3.1 Termux (lo que ya funciona hoy)

```bash
pkg install python rtorrent
# copiar fuente/ a ~/tablero
python ~/tablero/tablero.py --android --scgi 127.0.0.1:5000
# en el navegador del teléfono: http://127.0.0.1:8090
```

Opciones: `--scgi host:puerto` o `--scgi /ruta/al/socket`, `--port 8090`, `--bind 0.0.0.0` (abre a la red; mejor usar ⚙ Opciones → Red, que pide contraseña), `--config CARPETA`, `--demo` (28 torrents de prueba, sin rTorrent).

Para que Termux pueda escribir en `/storage/emulated/0`: `termux-setup-storage`. Para que Android no lo duerma: `termux-wake-lock` y quitar a Termux de la optimización de batería.

### 3.2 App propia (recomendado): Kotlin + WebView + Chaquopy

`ejemplo-modulo-ui/` trae este módulo armado, para copiar como `:ui-tablero`: `build.gradle.kts`, `TableroServidor.kt` (arranca y detiene tablero.py), `TableroActivity.kt` (WebView, pantalla de espera, selector de archivos, enlaces al navegador, recarga al volver, zoom, "Guardar como" de Exportar, magnet y .torrent de otras apps, botón Atrás), `TableroPuente.kt` (`window.TableroAndroid`), `TableroAvisos.kt` (notificación con velocidades, botones y "Descarga completa"), `res/drawable/` (íconos de la notificación) y un fragmento del manifiesto. **No se ha compilado** (no hay SDK de Android aquí): los `TODO` marcan dónde llamar a `TorrentEngine` y las versiones de dependencias hay que alinearlas con el proyecto. Lo de abajo explica las partes.

Recomendación: **Chaquopy** (plugin de Gradle que mete un intérprete de Python en el APK). Tablero no usa nada fuera de la biblioteca estándar, así que no hay paquetes que compilar. Python 3.12 (Tablero se prueba con 3.12 y 3.13).

1. `build.gradle` (módulo app): `id("com.chaquo.python")`, `chaquopy { defaultConfig { version = "3.12" } }`.
2. Copiar `fuente/tablero.py` y `fuente/web/` a `app/src/main/python/` (tablero.py busca `web/` junto a sí mismo).
3. Arrancarlo en un hilo del servicio en primer plano (ver 7):

```kotlin
if (!Python.isStarted()) Python.start(AndroidPlatform(context))
val tablero = Python.getInstance().getModule("tablero")
val conf = File(filesDir, "tablero").absolutePath
val scgi = File(filesDir, "rtorrent/scgi.socket").absolutePath
thread(name = "tablero", isDaemon = true) {
    tablero.callAttr("run", arrayOf("--android", "--scgi", scgi, "--config", conf, "--port", "8090"))
}
// al detener el servicio:
tablero.callAttr("stop")
```

4. WebView apuntando a `http://127.0.0.1:8090/`:

```kotlin
webView.settings.javaScriptEnabled = true
webView.settings.domStorageEnabled = true          // la interfaz guarda preferencias en localStorage
webView.addJavascriptInterface(TableroPuente(this), "TableroAndroid")
webView.webChromeClient = object : WebChromeClient() {
    // "elige archivos" en Agregar torrents usa <input type=file>: sin esto no hace nada.
    override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
        filePathCallback = cb; abrirSelector.launch(p.createIntent()); return true
    }
}
webView.loadUrl("http://127.0.0.1:8090/")
```

   Para HTTP sin cifrar a 127.0.0.1 hace falta `android:usesCleartextTraffic="true"` o un `network_security_config` que permita `127.0.0.1`.
   Si el servidor aún no responde al abrir la app, reintentar `loadUrl` cada segundo hasta que conteste.

Alternativa: dejar todo en Termux y que la app solo muestre la WebView. Es más simple de armar, pero depende de que Termux esté instalado y vivo; no sirve para una app que se instala sola.

## 4. rTorrent en Android

Con `:engine` esto ya está resuelto; queda como referencia y para el modo Termux.

### 4.1 Binario

Dentro de una app, Android 10+ no deja ejecutar archivos escritos en `filesDir` (W^X). El binario de rTorrent (compilado con el NDK, con libtorrent) va en `jniLibs/<abi>/librtorrent.so` (el nombre debe empezar por `lib` y terminar en `.so`) con `android:extractNativeLibs="true"` en el manifiesto, y se lanza desde `applicationInfo.nativeLibraryDir`:

```kotlin
val rt = ProcessBuilder(File(applicationInfo.nativeLibraryDir, "librtorrent.so").path,
                        "-n", "-o", "import=" + File(filesDir, "rtorrent/rtorrent.rc").path)
    .directory(File(filesDir, "rtorrent"))
    .apply { environment()["HOME"] = filesDir.path; environment()["TERM"] = "dumb" }
    .redirectErrorStream(true).start()
```

rTorrent 0.16 tiene modo sin interfaz de texto: `system.daemon.set = true` en el .rc. Con 0.9.x hace falta una terminal (en el escritorio corre dentro de `screen`/`tmux` o con un pseudo-terminal).

### 4.2 rtorrent.rc mínimo

```
# rutas: la app las escribe al instalar
directory.default.set = /storage/emulated/0/Download/Torrents
session.path.set      = /data/data/<paquete>/files/rtorrent/session
# SCGI por socket Unix: ninguna otra app del teléfono puede usarlo (un puerto TCP en 127.0.0.1 sí)
network.scgi.open_local = /data/data/<paquete>/files/rtorrent/scgi.socket
network.xmlrpc.size_limit.set = 16M
# puerto de entrada (0.16: network.listen.port.range; 0.9.x: network.port_range)
network.listen.port.range.set = 51420-51420
network.port_random.set = no
system.daemon.set = true
```

Tablero lee y cambia la línea del puerto en este archivo (Estado del puerto → Cambiar puerto); el cambio se aplica al reiniciar rTorrent.

### 4.3 rTorrent 0.16 (el de Termux)

- 0.16 quitó nombres viejos de XML-RPC salvo que se arranque con `-D`. `network.port_range` pasó a `network.listen.port.range` y `network.port_open` ya no existe. Esta versión de Tablero usa el nombre que tenga cada versión; si rTorrent no conoce una consulta, solo queda vacío ese dato.
- Sin configurar, 0.16 usa el rango 6881-6999 con puerto al azar. Tablero solo pide reiniciar cuando el puerto en uso queda fuera del rango configurado.
- Para revisar otros nombres: comparar los métodos que usa tablero.py con el bloque `method.use_deprecated` de `src/main.cc` de rTorrent.

## 5. Puente JavaScript `window.TableroAndroid`

Todos los métodos son opcionales: la interfaz pregunta si existen y oculta o explica lo que falta.

```kotlin
class TableroPuente(private val ctx: Context) {
    @JavascriptInterface fun openFolder(path: String) { /* abrir el explorador en esa carpeta */ }
    @JavascriptInterface fun startRtorrent() { /* arrancar el proceso de rTorrent */ }
    @JavascriptInterface fun restartRtorrent() { /* detener (system.shutdown.normal ya lo pidió Tablero, o matar) y arrancar */ }
    @JavascriptInterface fun exportFile(path: String) { /* "Guardar como" (ACTION_CREATE_DOCUMENT) y copiar ese .zip */ }
}
```

- `openFolder(path)` recibe una ruta POSIX (`/storage/emulated/0/Download/Torrents/Serie`). Android no tiene una forma estándar de "abrir esta carpeta" en cualquier explorador. Opciones: `Intent.ACTION_VIEW` con `DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/Torrents/Serie")` y tipo `vnd.android.document/directory` (abre el gestor de archivos del sistema en la mayoría de los teléfonos), o abrir un explorador propio de la app.
- Los métodos se llaman en un hilo que no es el de la interfaz: usar `runOnUiThread`/`Handler` para tocar vistas.

En el otro sentido, la app puede llamar a la interfaz:

```kotlin
// la app recibió un magnet (intent-filter scheme "magnet") o un .torrent (ACTION_VIEW application/x-bittorrent)
webView.evaluateJavascript("tableroAdd({magnets: ['magnet:?xt=urn:btih:…']})", null)
// un .torrent: copiarlo antes a una ruta que tablero.py pueda leer (p. ej. cacheDir)
webView.evaluateJavascript("tableroAdd({files: ['/data/data/<paquete>/cache/x.torrent']})", null)
```

`tableroAdd` abre "Agregar torrents" con todo cargado, para que el usuario elija carpeta, archivos y etiquetas. Devuelve `false` si había otro diálogo abierto. Sin mostrar nada, también se puede agregar directo con la API (`POST /api/add`, abajo).

## 6. Ventana, bandeja y avisos: el equivalente en Android

En escritorio, la ventana GTK pone un ícono en la bandeja (velocidades, Pausar/Reanudar todo, rojo si hay errores) y avisa cuando termina una descarga. En Android eso es la **notificación del servicio en primer plano**:

- Texto: velocidades `stats.down_rate` / `stats.up_rate` (bytes/s) de `GET /api/torrents`, cada 2-5 s mientras la pantalla está encendida; más espaciado con la pantalla apagada.
- Acciones: Pausar todo / Reanudar todo con `POST /api/action`: `{"action": "pause", "hashes": [...]}` y `{"action": "start", "hashes": [...]}` (start también reanuda). Todas las acciones: `start`, `stop`, `pause`, `recheck`, `remove` (con `"data": true` borra también los archivos), `priority` (con `"value"`: 0 a 3). Ver `RTorrentBackend.action` en tablero.py.
- Aviso de descarga completa: guardar qué hashes tenían `complete: 0` y avisar cuando pasan a `1`.
- Errores: torrents con `status == "error"` (el detalle está en `message`).
- Íconos: `icono-notificacion/` trae los de la barra de estado para los tres estados (normal, con error, sin conexión), con el color de notificación de cada uno.

Todo eso ya está en `ejemplo-modulo-ui/.../TableroAvisos.kt`: en el servicio, después de `startForeground`, `TableroAvisos(this, ID_NOTIFICACION) { detenerServicio() }.iniciar()`, y `detener()` en `onDestroy`. Mira cada 3 s con la pantalla encendida y cada 30 s apagada. "Descarga completa" sale solo para torrents que estaban bajando (no al agregar uno ya completo ni al verificar datos viejos), igual que en escritorio. Los botones llegan a `TableroAcciones` (receptor en el manifiesto). Salir cierra también Tablero con `TableroServidor.salir()` (pantalla, apps recientes y servidor); si la app usa su propia notificación, su Salir debe llamarlo. En Android 13+ `TableroActivity` pide el permiso de notificaciones.

No hay opción de arrancar al encender el teléfono: el teléfono o la tableta casi siempre está encendido.

## 7. Mantenerlo vivo

- **Servicio en primer plano** que arranca rTorrent y Tablero y muestra la notificación de 6. Android 14+ exige tipo: `dataSync` está limitado a 6 horas cada 24 en Android 15, así que para un cliente BitTorrent que siembra todo el día conviene `specialUse` (con `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Cliente BitTorrent"/>`). Si la app no va a Google Play, `specialUse` no tiene revisión.
- `WakeLock` parcial mientras haya torrents activos y `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`), si no Android corta la red con la pantalla apagada.
- Pedir quitar la app de la optimización de batería (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
- Al detener: `POST /api/shutdown` (rTorrent guarda la sesión y avisa a los rastreadores), esperar a que el proceso termine, luego `tablero.stop()`.
- Si el sistema mata el proceso de la app, rTorrent (proceso hijo) muere también. Al volver, la sesión se recupera desde `session/`.

## 8. Permisos y almacenamiento

- rTorrent necesita **rutas POSIX con acceso directo** a los archivos. El Storage Access Framework (URIs `content://`) no le sirve.
  - Para descargar en `/storage/emulated/0/...` (visible para el usuario y otras apps): `MANAGE_EXTERNAL_STORAGE` ("Acceso a todos los archivos", el usuario lo activa en Ajustes). Es lo que usan las apps de este tipo instaladas fuera de Play.
  - Sin ese permiso, solo la carpeta propia de la app (`getExternalFilesDir()`, `/storage/emulated/0/Android/data/<paquete>/files`), que el usuario ve mal y se borra al desinstalar.
- Tarjeta SD: el acceso de escritura directo está muy limitado; mejor solo almacenamiento interno al comienzo.
- Otros: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` (Android 13+), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- La configuración de Tablero (`--config`) va en `filesDir/tablero`. Los respaldos: la primera vez Tablero pregunta la carpeta (propone `/storage/emulated/0/Respaldos de Tablero`, o la de `--archivos`); con Exportar se copian fuera.

## 9. Seguridad

- Tablero escucha en `127.0.0.1:8090`. Las peticiones desde el mismo equipo no piden contraseña. En Android, **cualquier app del teléfono** puede conectarse a 127.0.0.1. Las peticiones que cambian algo (POST) exigen la cabecera `X-Tablero: 1`, que frena a páginas web abiertas en un navegador, pero no a otra app instalada. Si eso importa, hay dos caminos: dejar rTorrent en un socket Unix privado (lo más delicado) y aceptar el riesgo en Tablero, o pedir que se agregue a Tablero un token por petición que la app inyecte en la WebView.
- Desde 0.10.1, Tablero responde 403 a un pedido local cuyo `Host` no sea `localhost`, `127.x`, `::1`, el nombre del equipo o su IP en la red (protección contra DNS rebinding). La WebView debe cargar `http://127.0.0.1:PUERTO/` o `http://localhost:PUERTO/`, como ya hace `TableroServidor.url`.
- ⚙ Opciones → Red abre Tablero a la red local con contraseña (HTTP sin cifrar: solo para la red de casa).

## 10. API HTTP

Todo JSON. Los POST llevan `Content-Type: application/json` y `X-Tablero: 1`. Los errores vuelven como `{"error": "texto"}` (502 si rTorrent no responde, 400 si los datos no sirven).

**GET**

| Ruta | Devuelve |
|---|---|
| `/api/torrents` | `{torrents: [...], stats: {down_rate, up_rate, down_limit, up_limit, version, port, port_open, port_next, incoming, incoming_seen}, tablero, platform}`. Cada torrent: `hash, name, status, state, active, complete, size, done, down_rate, up_rate, eta, ratio, peers, seeds, tags, directory, message, pos, priority, opts, files, added, finished…` |
| `/api/details?hash=H` | archivos, pares, rastreadores y piezas de un torrent |
| `/api/history?hash=H` | velocidades de la última hora (sin `hash`: total) |
| `/api/tags` | etiquetas: color, ícono, logo, apariencia |
| `/api/prefs` | preferencias de la interfaz guardadas en el servidor |
| `/api/access` | acceso desde la red, `ip`, `port`, `local`, `platform` |
| `/api/dirs?path=P` | subcarpetas (para Examinar…) |
| `/api/free-space?path=P` | `{path, free}` |
| `/api/general` (GET y POST) | `{general, rtorrent: {max_sockets}, torrent: {peers_max, peers_min, uploads_max}}`: Conexiones y Al sembrar generales (`general.json` en la carpeta de `--config`) |
| `/api/backups?folder=F` | respaldos existentes y su configuración; `chosen: false` si nadie eligió aún la carpeta |
| `/api/backup-file?path=P` | el .zip de un respaldo, como descarga (Exportar en un navegador) |
| `/api/local-ip` | `{ip}` |
| `/logos/<id>.<ext>` | imagen de logo de una etiqueta |

**POST**

| Ruta | Cuerpo |
|---|---|
| `/api/add` | `{magnet}` o `{file: base64}` o `{path}`, más `directory`, `tags`, `start`, `skip` (índices de archivos a no bajar) |
| `/api/torrent-info` | `{file: base64}` o `{path}` → nombre y archivos del .torrent |
| `/api/action` | `{action, hashes, value?, data?}` |
| `/api/set-tags`, `/api/edit-tags` | `{hashes, tags}` / `{hashes, add, remove}` |
| `/api/rename-tag` | `{old, new}` |
| `/api/file-priority` | `{hash, indexes, priority}` (0 no bajar, 1 normal, 2 alta) |
| `/api/limits` | `{down, up}` en bytes/s (0 = sin límite) |
| `/api/options` | `{hash, options}` (prioridad, límites, pares, reglas de siembra) |
| `/api/move` | `{hashes, where: up|down|top|bottom}` (columna #) |
| `/api/relocate` | `{hashes, target, move, check}` |
| `/api/rename-folder` | `{hash, name}` |
| `/api/open-folder` | `{hash, index?, dir?}` → en Android `{folder, path, android: true}` |
| `/api/add-trackers` | `{hash, urls}` |
| `/api/port`, `/api/port-test` | `{port}` / `{}` |
| `/api/shutdown` | `{}`: apaga rTorrent guardando la sesión |
| `/api/tags`, `/api/logo`, `/api/prefs`, `/api/access` | guardar etiquetas, subir logo (`{data: base64}`), preferencias, acceso desde la red |
| `/api/backup`, `/api/backup-settings`, `/api/restore-inspect`, `/api/restore` | respaldo y restauración |
| `/api/backup-import?folder=F&name=N` | el .zip tal cual (`Content-Type: application/zip`, hasta 1 GB) → `{path, torrents}`; queda como `tablero-respaldo-<fecha>-importado.zip` y no se borra con los automáticos |

## 11. Pruebas hechas aquí

Con rTorrent 0.9.8 real en Linux (no hay Android en este entorno):

- `tablero.run([...,"--android",...])` en un hilo: agregar un torrent con etiqueta, listarlo (`platform: "android"`), Abrir carpeta devuelve la ruta sin buscar el escritorio, `tablero.stop()` termina el hilo.
- Activar ⚙ Opciones → Red: el servidor vuelve a abrir en 0.0.0.0 dentro del mismo proceso.
- Interfaz en un Pixel 7 emulado (Chromium), sin puente y con un `TableroAndroid` de prueba: sin puente no aparecen Abrir carpeta ni Mostrar en la carpeta; con puente aparecen y llaman a `openFolder` con la ruta; Apagar rTorrent muestra el botón Encender rTorrent y llama a `startRtorrent`; `tableroAdd` abre Agregar con el magnet. Sin errores de JavaScript.
- Modo escritorio (sin `--android`): igual que antes.
- `android.patch` aplicado sobre la versión de escritorio da exactamente `fuente/`.

En 0.9.2-android.2, con rTorrent 0.9.8 real y la interfaz en un Pixel 7 emulado (Chromium, pantalla táctil):

- Primer inicio: pregunta la carpeta de respaldos; con "Guardar" o "Ahora no" no vuelve a preguntar al recargar.
- Botón de menú del torrent, menú sin atajos, Filtrar por esta etiqueta desde el toque largo, Ajustar el ancho al texto, separador de paneles arrastrado con el dedo (230 → 310 px).
- ⚙ Opciones: textos de teléfono/tableta, wifi, sin ufw, sin `--bind`, sin Ctrl.
- Respaldos: Crear, Exportar (descarga) e Importar el mismo .zip, que abre Restaurar con la ayuda de rutas de PC a Android. Importar un archivo que no es respaldo da error; `/api/backup-file` no entrega otros archivos.
- Estado del puerto con el aviso de datos móviles; Agregar con "Elige archivos .torrent".
- El gráfico de Límites se ve a ancho de teléfono.
- La misma interfaz con mouse en 1280 px: sin botón de menú, atajos visibles, bordes de columna arrastrables con el mouse.
- `tablero.run(...)`/`stop()` en un hilo, con `--rtorrent-rc`.

En 0.10.1-android.3 y android.4 se repitieron de nuevo, y se revisaron Límites y conexiones y la pestaña Opciones a ancho de teléfono. En 0.10.1-android.1 se repitieron todas las pruebas de android.2 y android.3 sobre la base 0.10.1, sin errores.

En 0.9.2-android.3, igual: cada "i" muestra y cierra su texto sin ordenar la columna; tocar otra parte lo cierra; el gráfico de velocidad muestra el punto tocado; dos dedos hacia abajo recargan, un tramo corto no, y un pellizco no. Con mouse en 1280 px no hay ningún "i" y el texto sigue saliendo al pasar el mouse.

Los archivos Kotlin de `ejemplo-modulo-ui/` no se han compilado.

Falta probar en un teléfono: WebView real, Chaquopy, rTorrent 0.16 compilado para Android, permisos de almacenamiento y el servicio en primer plano.

## 12. Lista de pasos para el proyecto Android

Los pasos 3, 4 y 8 están detallados, con código, en `PENDIENTE-PORT.md`.

1. Probar `fuente/tablero.py --android --scgi <socket>` contra el rtorrent 0.16.17 de la etapa `hosttest` (ver 0). Corregir aquí lo que falle del XML-RPC de tinyxml2.
2. Copiar `ejemplo-modulo-ui/` como `:ui-tablero`, con `fuente/tablero.py` y `fuente/web/` en `src/main/python/`.
3. Completar los `TODO` del puente con `TorrentEngine` y llamar a `TableroServidor.iniciar` desde el servicio de `:engine`.
4. Coordinar con `:engine` el .rc (puerto) y no relanzar el daemon tras Apagar rTorrent (ver 0).
5. Hacer `TableroActivity` la actividad de inicio (o un botón desde la app de prueba).
6. Más adelante: `MANAGE_EXTERNAL_STORAGE` para descargar en `/storage/emulated/0/Download`.
7. Ícono de la app: `icono-adaptable/res/` (ícono adaptable con capa monocroma; ver su LEEME). Los de la notificación ya van en `ejemplo-modulo-ui/src/main/res/drawable/`.
8. En el servicio de `:engine`: `TableroAvisos(this, ID_NOTIFICACION) { … }.iniciar()` (ver 6), y pasar `--rtorrent-rc` en `TableroServidor.iniciar` si el .rc no sale en la línea de comandos del daemon.
