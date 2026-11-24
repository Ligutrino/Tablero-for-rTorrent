# Pendiente para el proyecto del port (rTorrent en Android)

Lo que Tablero ya trae listo y falta conectar en la app, porque depende del código de `:engine`. Nada de esto se puede hacer desde Tablero. Más detalle en `GUIA-INTEGRACION.md`, secciones 5, 6 y 12.

## 1. Notificación del servicio (`TableroAvisos`)

En el servicio en primer plano de `:engine`:

```kotlin
private var avisos: TableroAvisos? = null

// después de startForeground(ID_NOTIFICACION, notificacion)
avisos = TableroAvisos(this, ID_NOTIFICACION) {
    // "Salir" en la notificación: lo mismo que hace hoy el servicio para detenerse
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
}.also { it.iniciar() }

override fun onDestroy() {
    avisos?.detener()
    super.onDestroy()
}
```

- `ID_NOTIFICACION` es el ID que el servicio ya usa en `startForeground`. `TableroAvisos` reemplaza esa notificación con velocidades, íconos de estado y los botones Pausar todo, Reanudar todo y Salir, y avisa cada "Descarga completa".
- Si `:engine` y `:ui-tablero` son módulos separados, `:engine` necesita depender de `:ui-tablero`, o el servicio puede recibir un callback para crear `TableroAvisos` desde la app.

## 2. Manifiesto

Copiar del `ejemplo-modulo-ui/AndroidManifest-fragmento.xml`:

- el receptor `cl.tablero.ui.TableroAcciones` (`exported="false"`), que reciben los botones de la notificación;
- el permiso `POST_NOTIFICATIONS`, que `TableroActivity` pide en Android 13+.

## 3. rtorrent.rc

Si `:engine` arranca rTorrent sin `-o import=RUTA` en la línea de comandos, o lo corre dentro de su propio proceso, Tablero no encuentra el .rc solo. Pasárselo en `TableroServidor.iniciar`:

```kotlin
"--rtorrent-rc", File(filesDir, "rtorrent.rc").absolutePath   // la ruta real que use :engine
```

Sin eso, Estado del puerto → Cambiar puerto no puede escribir el puerto nuevo.

## 4. Botones Encender y Reiniciar rTorrent

En `TableroActivity.kt` (línea ~79) quedan dos TODO. Hay que cambiarlos por los métodos reales de `TorrentEngine`:

```kotlin
encender = { /* p. ej. engine.start(), o adoptar el daemon si ya corre */ },
reiniciar = { /* p. ej. engine.stop(); engine.start() */ },
```

Tablero ya pidió `system.shutdown.normal` antes de llamar a `reiniciar`, así que basta con esperar a que el daemon termine (o matarlo tras unos segundos) y arrancarlo de nuevo.

## 5. Comprobar

1. Al abrir la app, la notificación fija muestra "↓ … ↑ …" y cambia cada 3 s.
2. Con un torrent con error, la notificación muestra el ícono de error y "N con error".
3. Al detener rTorrent: la notificación dice "Sin conexión con rTorrent" y en Tablero el botón Encender rTorrent lo vuelve a levantar.
4. Pausar todo, Reanudar todo y Salir desde la notificación.
5. Un torrent que termina de bajar da "Descarga completa"; uno que se agrega ya completo no.
6. Cambiar puerto escribe en el rtorrent.rc y Reiniciar rTorrent lo aplica.
7. Compilar `ejemplo-modulo-ui/`, que todavía no se ha compilado.
