# Rama `kotlin`: cómo traer los cambios de Tablero

`main` es la rama del build tradicional: tablero.py con Chaquopy. Todo cambio de Tablero (versión nueva de
escritorio, `android.patch`, `cambios-locales.patch`) entra primero ahí. Esta rama tiene la misma interfaz web, pero la API
la sirve `cl.tablero.backend` (Kotlin) en lugar de tablero.py.

Dónde quedó cada cosa en esta rama:

| En `main` | En `kotlin` |
|---|---|
| `src/main/python/web/` | `src/main/assets/web/` (sin cambios) |
| `src/main/python/tablero.py` | `referencia/tablero.py` (no va en el APK) + `src/main/kotlin/cl/tablero/backend/` |
| Chaquopy en `build.gradle.kts` | nada |

## Pasos

```bash
git checkout kotlin
git merge main
```

Git sigue los archivos movidos: los cambios de `web/` caen en `src/main/assets/web/` y los de tablero.py en
`referencia/tablero.py`. Lo de `web/` ya queda listo para el acceso desde la red; en el teléfono la interfaz es
Compose, y el cambio hay que hacerlo también ahí (ver `COMPOSE.md`, al final):

```bash
git diff ORIG_HEAD -- android/tablero/src/main/assets/web/
```

Lo de tablero.py hay que portarlo a mano. El diff dice qué:

```bash
git diff ORIG_HEAD -- android/tablero/referencia/tablero.py
```

Cada clase o función de tablero.py tiene su par en Kotlin, con el mismo nombre en camelCase y los mismos mensajes:

| tablero.py | Kotlin (`cl/tablero/backend/`) |
|---|---|
| `RTorrent` | `Rt.kt` |
| `RTorrentBackend`, `with_availability`, `TORRENT_FIELDS` | `Backend.kt` |
| `General` | `General.kt` |
| `History` | `History.kt` |
| `TagStore`, `Backups`, `Access` | `Stores.kt` |
| `Handler` (rutas, `_stream`, `_guard`), `run`, `VERSION` | `Server.kt` |
| funciones sueltas (`status_of`, `torrent_files`, `find_data`…), `Jobs`, `CheckQueue` | `Util.kt` |
| `_bdecode`, `_bencode` | `Bencode.kt` |
| `json`, `os.path`, `urllib.parse`, `int()`, `round()` | `Json.kt`, `Py.kt` |

`DemoBackend` no se portó: la app nunca usa `--demo`.

## Comprobar

```bash
native/build.sh host                               # rtorrent para Linux, una vez
tools/tablero-test.sh                              # la prueba de la API (tools/tablero-smoke.py)
mkdir -p /tmp/ref && cp android/tablero/referencia/tablero.py /tmp/ref/ && ln -sfn "$PWD/android/tablero/src/main/assets/web" /tmp/ref/web
android/gradlew -p tools/tablero-host installDist
python3 tools/tablero-compare.py native/out/host/librtorrent.so /tmp/ref/tablero.py \
  tools/tablero-host/build/install/tablero/bin/tablero android/tablero/src/main/assets/web /tmp/tablero-compare
```

`tablero-compare.py` hace el mismo recorrido de la API (unas 80 llamadas: agregar, etiquetas, opciones, generales,
respaldos, restaurar, logos, acceso, archivos de la interfaz…) con tablero.py y con el backend Kotlin, cada uno con su
rtorrent, y compara las respuestas. Al portar algo nuevo, agrega ahí la llamada que lo usa: si las dos columnas dan
lo mismo, el port está bien. `docker build --target hosttest .` corre las pruebas de la API y de streaming contra el backend Kotlin.

Para la interfaz Compose: `tools/compose-shots.sh /tmp/capturas probar` (ver `COMPOSE.md`).

Al terminar, sube `VERSION` en `Server.kt` y `versionName`/`versionCode` en `build.gradle.kts` (siempre por encima del de `main`).
