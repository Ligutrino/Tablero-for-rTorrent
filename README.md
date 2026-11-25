# rtorrent-android

Motor de torrents para Android basado en **rTorrent + libtorrent (rakshasa) 0.16.17**.
rtorrent corre como daemon (proceso hijo de la app) y se controla por **XML-RPC sobre SCGI** en un
socket Unix privado. La capa del motor no depende de ninguna interfaz: una UI nueva o un fork de
LibreTorrent solo tienen que hablar con `TorrentEngine`.

```
┌────────────── app (cualquier UI) ──────────────┐
│  TorrentEngine  ← API neutral, bloqueante      │
│  EngineProvider ← instancia única + servicio   │
├──────────────── módulo :engine ─────────────────┤
│  RtorrentEngine → XmlRpcClient → SCGI (socket) │
│  RtorrentDaemon → lanza librtorrent.so         │
│  EngineService  → foreground service specialUse│
└──────────────────────┬──────────────────────────┘
                       │ files/rtorrent/rpc.sock
              rtorrent (daemon, sin ncurses)
```

## Interfaz: Tablero (rama `kotlin`: backend en Kotlin, sin Python)

> Esta es la rama **`kotlin`**. La rama **`main`** sigue con el build tradicional (tablero.py con Chaquopy).
> Los cambios de Tablero entran primero en `main` y después se portan aquí: ver `android/tablero/PORTAR-A-KOTLIN.md`.

`android/tablero` es la app principal: **Tablero 0.11-android.2**. En el teléfono la interfaz es **Compose**
(`cl.tablero.compose`, ver `android/tablero/COMPOSE.md`), que llama a la API en el mismo proceso. La web de siempre
(`web/`, sin cambios, en `src/main/assets/web`) queda para el acceso desde la red y, mientras Compose no tenga todo,
a un toque desde la app (el globo). La API que antes servía tablero.py la sirve ahora
`cl.tablero.backend`, en Kotlin, con las mismas rutas, el mismo JSON y los mismos archivos de configuración
(`tags.json`, `general.json`, `access.json`, `backup.json`, `prefs.json`), así que una instalación de `main`
se actualiza a esta sin perder nada. El APK ya no lleva Python.

```
 TableroActivity (Compose) ──Tablero.get/post──► cl.tablero.backend ──SCGI──┐
 otros equipos / TableroWebActivity ──HTTP :8090──┘                          ├─► rtorrent (socket Unix)
 TableroActivity ──► EngineProvider / EngineService ──► :engine ──SCGI─────────┘
```

- `cl.tablero.backend` no usa nada de Android: corre igual en la JVM. `tools/tablero-host` lo compila para Linux, y
  `tools/tablero-test.sh`, `tools/sequential-test.py` y `tools/tablero-compare.py` lo prueban contra un rtorrent real.
  `tablero-compare.py` hace el mismo recorrido de la API con tablero.py y con el backend Kotlin y muestra lo que difiere.
- `android/tablero/referencia/tablero.py` es el tablero.py que el backend reproduce (no va en el APK). Al traer un
  Tablero nuevo desde `main`, su diff es lo que hay que portar.
- Abrir la app arranca el servicio en primer plano. El servicio arranca rtorrent y, mediante un
  `EngineServiceHook`, también el backend de Tablero. Así las reglas de siembra siguen funcionando con la pantalla cerrada.
- La notificación del servicio la pone Tablero (`TableroAvisos`, como el ícono de la bandeja en escritorio):
  velocidades, torrents con error, **Pausar todo / Reanudar todo / Salir** y un aviso por cada descarga completa.
- **Apagar rTorrent** en Tablero deja el motor detenido, no en error. La notificación ofrece **Encender**, y
  `startRtorrent()` / `restartRtorrent()` del puente usan el servicio. `exportFile()` abre "Guardar como" para respaldos.
- **Cambiar puerto** en Tablero edita `rtorrent.rc`. `:engine` reescribe el rc en cada arranque, pero conserva
  la línea de puerto si otro cliente la cambió (se compara con la marca `# puerto-de-RtorrentConfig:`).
- El paquete original, la guía y `android.patch` están en `android/tablero/docs/` (hablan de tablero.py: valen para `main`).
- `android/tablero/cambios-locales.patch` tiene lo que este repo cambia sobre el paquete de Tablero (contra `tablero.py` y `web/`).
  En esta rama los cambios de `web/` están en `src/main/assets/web` y los de tablero.py ya están portados al backend:
  - El arreglo de `/api/shutdown` con rtorrent 0.16.17.
  - "Conexiones en total" con un mínimo y valor por defecto de 512, porque rtorrent 0.16 rechaza
    `network.max_open_sockets` por debajo de eso. Si había un 250 guardado, se sube a 512.
  - Descarga en orden y reproducción mientras baja (ver abajo).
  - El botón "Elegir con Android…" junto a Examinar… (ver abajo).
- `versionCode` 10 y `versionName` `0.11-android.3-kotlin`: se instala encima del APK de `main` (8) con la misma firma.
  Para volver al de `main` hay que desinstalar antes, porque Android no deja bajar de versión.

## Descarga en orden y reproducción mientras baja

rakshasa libtorrent no la trae. Este repo la agrega con `native/patches/libtorrent/0001-sequential.patch` y
`native/patches/rtorrent/0001-sequential.patch`:

| Comando | Qué hace |
|---|---|
| `d.sequential` / `d.sequential.set = 1` | Elige las piezas en orden desde el principio, no al azar por rareza. Queda guardado en la sesión. |
| `d.sequential_from` / `d.sequential_from.set = N` | Desde qué pieza sigue la descarga en orden (cuando el reproductor salta). Sigue hasta el final y después completa lo de antes. No se guarda. |

En Tablero:
- Menú del torrent → **Descargar en orden**.
- Pestaña Archivos: el botón ▶ (o mantener presionado → **Reproducir mientras se descarga**) en videos y audio.
  Abre `http://127.0.0.1:8090/stream/<hash>/<n>/<nombre>` en el reproductor que elijas (VLC, MX Player…).
  Tablero activa la descarga en orden, entrega solo los bytes de piezas ya completas (con `Range`) y espera
  la siguiente. Si adelantas el video, la descarga sigue desde ese punto.
- Con un rtorrent sin el parche (escritorio), Tablero oculta estas opciones.

## Carpetas elegidas con el selector de Android

Junto a **Examinar…**, el botón **Elegir con Android…** abre el selector de carpetas del sistema. La app traduce
la carpeta a su ruta real (`primary:Movies` → `/storage/emulated/0/Movies`, una tarjeta SD → `/storage/<id>/…`)
y la pone en el campo. Para eso hace falta "Acceso a todos los archivos". Si la carpeta no se puede escribir, Tablero
lo dice. Eso pasa en una tarjeta SD, donde Android solo deja escribir en `Android/data/cl.tablero.android`. No se copia nada:
rtorrent escribe directamente ahí.
- `applicationId` = `cl.tablero.android`; se cambia en `android/tablero/build.gradle.kts`.

## Identidad ante trackers y pares

Es la de rtorrent/libtorrent sin modificar, igual que en escritorio con la **misma versión**:

| Dónde | Valor con 0.16.17 |
|---|---|
| Peer ID | `-lt1011-` + 12 bytes al azar (`1011` = 0.16.17 en hex) |
| User-Agent HTTP | `rtorrent/0.16.17` |
| Handshake de extensiones `v` | `libTorrent 0.16.17` |
| DHT `v` | `lt\x10\x11` |

Para igualar otra versión de escritorio, cambia las versiones en `native/sources.lock`.

## Compilar (Docker) e instalar (adb)

Requisitos: Docker 23 o más reciente (BuildKit, que viene por defecto), unos 15 GB libres y conexión a
dl.google.com, archive.ubuntu.com, services.gradle.org, Maven Central y Google Maven.

```bash
git clone rtorrent-android.bundle rtorrent-android
cd rtorrent-android

# APK para arm64 (la gran mayoría de teléfonos). Tarda unos 15–30 min la primera vez.
docker build -o out .

# Opcional: más ABIs (emulador x86_64, teléfonos antiguos de 32 bits)
docker build --build-arg ABIS="arm64-v8a armeabi-v7a x86_64" -o out .

# Instalar en el teléfono (depuración USB activada)
adb install -r out/tablero.apk                # la app con Tablero
adb install -r out/rtorrent-demo-debug.apk    # opcional: la app de prueba mínima del motor
```

`out/` queda con `tablero.apk`, `rtorrent-demo-debug.apk` y `native/<abi>/librtorrent.so`. Las dos apps son independientes: cada una tiene sus propios torrents y sus propias descargas.
El APK se firma con `android/debug.keystore` (incluido a propósito), así que cada compilación nueva
se instala encima de la anterior con `adb install -r`.

### Probar que el motor funciona

1. Abre **rTorrent (prueba)** y pulsa **Iniciar**. Acepta el permiso de notificaciones.
   Arriba debe aparecer `Motor: rtorrent 0.16.17 (pid …)` y una notificación persistente.
2. Pulsa **Torrent de prueba** (Big Buck Bunny, licencia CC-BY) o pega un magnet y pulsa **Añadir**.
   Primero aparece como `METADATA` y luego `DOWNLOADING` con velocidad y pares.
   También puedes abrir un `.torrent` con **Abrir .torrent**, o tocar un enlace magnet en el navegador.
3. Toca un torrent para pausar, reanudar, verificar o quitar (con o sin borrar archivos).
4. **Log** muestra la salida de rtorrent. Por adb:

```bash
adb logcat -s EngineService
adb shell run-as io.github.ligutrino.rtorrent.demo cat files/rtorrent/log/rtorrent.log
adb shell run-as io.github.ligutrino.rtorrent.demo cat files/rtorrent/rtorrent.rc
```

Las descargas van a `/sdcard/Android/data/io.github.ligutrino.rtorrent.demo/files/Download/`
(`adb pull` sirve para sacarlas).

### Comprobar el motor sin teléfono

```bash
docker build --target hosttest .
```

Compila rtorrent para Linux con las mismas recetas y ejecuta `tools/host-test`, `tools/tablero-test.sh` (la API de Tablero, servida por el backend Kotlin) y `tools/sequential-test.py` (descarga en orden y /stream con dos rtorrent). `tools/host-test` usa el código
Kotlin real del motor (sin la parte Android). La prueba arranca el daemon, añade un magnet y un .torrent,
verifica datos, pausa, reanuda, aplica límites, quita y borra archivos, adopta un daemon ya vivo, lo
para y comprueba que la sesión se restaura. Fuera de Docker: `tools/host-test.sh`.

## Estructura

| Ruta | Qué es |
|---|---|
| `native/sources.lock` | Versiones, sha256 y URLs de openssl 3.5.5, curl 8.23.0, libtorrent y rtorrent 0.16.17 |
| `native/fetch.sh` | Descarga y verifica las fuentes (usa `native/downloads/` si ya están ahí) |
| `native/build.sh <abi>…` | Compilación cruzada con el NDK. Deja `native/out/<abi>/librtorrent.so` |
| `native/patches/<paquete>/*.patch` | Parches que se aplican al desempaquetar: descarga en orden (`d.sequential`) |
| `android/engine` | Módulo library Kotlin: API neutral, cliente XML-RPC, daemon y servicio |
| `android/tablero` | App con Tablero (WebView + backend Kotlin `cl.tablero.backend` + puente) |
| `android/tablero/referencia/tablero.py` | El tablero.py que reproduce el backend (no va en el APK) |
| `android/app` | App mínima de prueba |
| `tools/host-test` | Prueba de integración en la JVM |
| `tools/tablero-host` | El backend de Tablero en la JVM, para las pruebas |
| `tools/tablero-compare.py` | Mismo recorrido de la API con tablero.py y con el backend Kotlin; muestra las diferencias |
| `Dockerfile` | SDK 35 + NDK r27c, luego el binario, luego el APK (`out`); o `hosttest` |

### Detalles del binario

- `rtorrent --without-ncurses --with-xmlrpc-tinyxml2` (daemon puro, XML-RPC integrado, sin xmlrpc-c).
- openssl, curl, libtorrent y libc++ enlazados **estáticamente**: solo depende de libc/libm/libdl/libz
  del sistema. API mínima **28** (Android 9) porque rtorrent usa `posix_spawn`.
- Alineado a páginas de 16 KB (`-z max-page-size=16384`), que Google Play exige.
- Se empaqueta como `lib/<abi>/librtorrent.so` porque desde targetSdk 29 una app solo puede ejecutar
  binarios desde `nativeLibraryDir`. Por eso la app **debe** usar
  `packaging { jniLibs { useLegacyPackaging = true } }`.
- Certificados HTTPS de trackers: se juntan en `files/rtorrent/ca-bundle.pem` desde `/apex/com.android.conscrypt/cacerts` (Android 14+) o `/system/etc/security/cacerts` en cada arranque (`network.http.cacert`). No sirve `capath` directo: Android nombra esos archivos con el hash antiguo de OpenSSL.

## API del motor

Todo es bloqueante: llámalo desde un hilo de fondo. Es seguro llamarlo desde varios hilos.

```kotlin
EngineProvider.startService(context)          // desde la UI: arranca el servicio y rtorrent
val engine: TorrentEngine = EngineProvider.get(context)

withContext(Dispatchers.IO) {
    val hash = engine.addUri("magnet:?xt=urn:btih:…")       // o addTorrentFile(bytes)
    engine.torrents()                                       // List<TorrentStatus>
    engine.pause(hash); engine.resume(hash); engine.recheck(hash)
    engine.remove(hash, deleteFiles = true)                 // solo borra dentro de downloadDir
    engine.setRateLimits(0, 512 * 1024)                     // bajada, subida en bytes/s (0 = sin límite)
    engine.sessionStats()
}
engine.addListener { state -> /* Stopped, Starting, Running, Stopping, Failed */ }
EngineProvider.stopService(context)           // parada ordenada (guarda la sesión)
```

Configuración: `EngineProvider.configure(RtorrentConfig(...))` antes del primer `get()`: puertos, DHT,
PEX, cifrado, pares, memoria, límites, IPv6, espacio mínimo libre y `extraRcLines` para cualquier
comando de rtorrent. Para lo que la API aún no cubre: `EngineProvider.rtorrent(ctx).rawCall("d.…", …)`.

### Cómo encaja con LibreTorrent

LibreTorrent tiene el motor detrás de las interfaces `TorrentSession` y `TorrentDownload`. Con un fork,
se incluye `:engine` como módulo y se escribe un `RtorrentSession` y un `RtorrentDownload` que
traduzcan a `TorrentEngine`. Su servicio en primer plano ya es `specialUse`, así que se puede usar el
suyo en vez de `EngineService` (basta llamar `engine.start()` y `engine.stop()`). Como `TorrentEngine`
es bloqueante y no usa corrutinas, se usa bien desde Java y RxJava (`Single.fromCallable`).

## Limitaciones y riesgos conocidos

- **Aún no se ha compilado con el NDK.** El entorno donde se escribió no tenía acceso a dl.google.com
  ni a GitHub. Las recetas se probaron compilando para Linux (`hosttest`, todo pasa) y el código
  Android se verificó contra la API 35. Si el primer `docker build` falla en una dependencia por algo
  propio de bionic, el arreglo va en `native/patches/` o en `native/build.sh`.
- **Servicio en primer plano**: se usa `specialUse` porque desde Android 15 `dataSync` solo puede
  correr 6 h de cada 24. Google Play revisa la justificación de `specialUse`; para F-Droid o instalación
  directa no hay problema. El servicio debe arrancarse con la app visible (restricción de Android 12+).
- **Almacenamiento**: rtorrent necesita rutas de archivo reales; no entiende URIs SAF (`content://`).
  Con "Acceso a todos los archivos", el selector de Android se traduce a la ruta real. Sin ese permiso, o en una
  tarjeta SD, solo sirve la carpeta propia de la app (copiar al terminar no está hecho).
- **Si Android mata la app, también mata a rtorrent** (mismo grupo de procesos). La sesión se guarda
  cada 5 minutos y al parar. Al volver, `start()` adopta un daemon que siga vivo o lanza uno nuevo.
- rtorrent no tiene BitTorrent v2 (los híbridos se abren como v1) ni uTP. La descarga en orden la agrega el parche de arriba.
- Licencias: rtorrent y libtorrent son GPL-2.0-or-later, curl es MIT/curl y OpenSSL 3 es Apache-2.0.
  El APK resultante debe distribuirse bajo GPL con sus fuentes.

## Actualizar versiones

Edita `native/sources.lock` (versión, nombre de archivo, sha256, URLs) y vuelve a compilar. El
primer URL es el archivo de Ubuntu (estable y con sha256 conocido) y el segundo es el upstream.
