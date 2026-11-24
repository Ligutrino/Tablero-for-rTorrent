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

## Interfaz: Tablero

`android/tablero` es la app principal: **Tablero 0.9.2-android.1** (tablero.py + web/) corriendo con
Python embebido (Chaquopy 17.2, Python 3.12) y mostrado en una WebView, sobre el mismo `:engine`.

```
 TableroActivity (WebView) ──HTTP 127.0.0.1:8090──► tablero.py ──SCGI──┐
          │  window.TableroAndroid (TableroPuente)                      ├─► rtorrent (socket Unix)
          └──► EngineProvider / EngineService ──► :engine ──SCGI───────┘
```

- Abrir la app arranca el servicio en primer plano. El servicio arranca rtorrent y, mediante un
  `EngineServiceHook`, también tablero.py. Así las reglas de siembra de Tablero siguen funcionando con la pantalla cerrada.
- **Apagar rTorrent** en Tablero deja el motor detenido, no en error. La notificación ofrece **Encender**, y
  `startRtorrent()` / `restartRtorrent()` del puente usan el servicio.
- **Cambiar puerto** en Tablero edita `rtorrent.rc`. `:engine` reescribe el rc en cada arranque, pero conserva
  la línea de puerto si otro cliente la cambió (se compara con la marca `# puerto-de-RtorrentConfig:`).
- El paquete original, la guía y `android.patch` están en `android/tablero/docs/`. Este repo trae un
  arreglo encima de la versión que entregaste: `/api/shutdown` con rtorrent 0.16.17 (ver `docs/CAMBIOS-ANDROID.md`).
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
adb install -r out/tablero-debug.apk          # la app con Tablero
adb install -r out/rtorrent-demo-debug.apk    # opcional: la app de prueba mínima del motor
```

`out/` queda con `tablero-debug.apk`, `rtorrent-demo-debug.apk` y `native/<abi>/librtorrent.so`. Las dos apps son independientes: cada una tiene sus propios torrents y sus propias descargas.
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

Compila rtorrent para Linux con las mismas recetas y ejecuta `tools/host-test`, más `tools/tablero-test.sh` (recorre la API de Tablero contra ese rtorrent),, que usa el código
Kotlin real del motor (sin la parte Android). La prueba arranca el daemon, añade un magnet y un .torrent,
verifica datos, pausa, reanuda, aplica límites, quita y borra archivos, adopta un daemon ya vivo, lo
para y comprueba que la sesión se restaura. Fuera de Docker: `tools/host-test.sh`.

## Estructura

| Ruta | Qué es |
|---|---|
| `native/sources.lock` | Versiones, sha256 y URLs de openssl 3.5.5, curl 8.23.0, libtorrent y rtorrent 0.16.17 |
| `native/fetch.sh` | Descarga y verifica las fuentes (usa `native/downloads/` si ya están ahí) |
| `native/build.sh <abi>…` | Compilación cruzada con el NDK. Deja `native/out/<abi>/librtorrent.so` |
| `native/patches/<paquete>/*.patch` | Parches que se aplican al desempaquetar (ahora no hay ninguno) |
| `android/engine` | Módulo library Kotlin: API neutral, cliente XML-RPC, daemon y servicio |
| `android/tablero` | App con Tablero (WebView + Chaquopy + puente) |
| `android/app` | App mínima de prueba |
| `tools/host-test` | Prueba de integración en la JVM |
| `Dockerfile` | SDK 35 + NDK r27c, luego el binario, luego el APK (`out`); o `hosttest` |

### Detalles del binario

- `rtorrent --without-ncurses --with-xmlrpc-tinyxml2` (daemon puro, XML-RPC integrado, sin xmlrpc-c).
- openssl, curl, libtorrent y libc++ enlazados **estáticamente**: solo depende de libc/libm/libdl/libz
  del sistema. API mínima **28** (Android 9) porque rtorrent usa `posix_spawn`.
- Alineado a páginas de 16 KB (`-z max-page-size=16384`), que Google Play exige.
- Se empaqueta como `lib/<abi>/librtorrent.so` porque desde targetSdk 29 una app solo puede ejecutar
  binarios desde `nativeLibraryDir`. Por eso la app **debe** usar
  `packaging { jniLibs { useLegacyPackaging = true } }`.
- Certificados HTTPS de trackers: `/system/etc/security/cacerts` (`network.http.capath`).

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
  Por defecto descarga en el directorio externo propio de la app. Llevar archivos a Descargas o a una
  carpeta elegida por el usuario requiere copiarlos (MediaStore/SAF) al terminar; eso no está hecho.
- **Si Android mata la app, también mata a rtorrent** (mismo grupo de procesos). La sesión se guarda
  cada 5 minutos y al parar. Al volver, `start()` adopta un daemon que siga vivo o lanza uno nuevo.
- rtorrent no tiene descarga secuencial, prioridad por pieza para streaming, BitTorrent v2 ni uTP.
- Licencias: rtorrent y libtorrent son GPL-2.0-or-later, curl es MIT/curl y OpenSSL 3 es Apache-2.0.
  El APK resultante debe distribuirse bajo GPL con sus fuentes.

## Actualizar versiones

Edita `native/sources.lock` (versión, nombre de archivo, sha256, URLs) y vuelve a compilar. El
primer URL es el archivo de Ubuntu (estable y con sha256 conocido) y el segundo es el upstream.
