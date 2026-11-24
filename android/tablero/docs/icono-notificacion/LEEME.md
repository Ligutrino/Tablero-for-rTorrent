# Íconos de la notificación (barra de estado)

Equivalen al ícono de la bandeja del escritorio: normal, con error y sin conexión. `res/drawable/` va en `app/src/main/res/drawable/`.

| Estado | Ícono chico (`setSmallIcon`) | Color (`setColor`) | Imagen grande (`setLargeIcon`, opcional) |
|---|---|---|---|
| Normal | `ic_stat_tablero` (la "r" con el punto) | `#1d4e9e` | ícono de la app |
| Algún torrent con `status == "error"` | `ic_stat_tablero_error` (el punto pasa a ser un círculo con "!") | `#d7262e` | ícono de la app con la insignia roja "!", como el de escritorio |
| Sin conexión con rTorrent o Tablero | `ic_stat_tablero_sinconexion` (solo contorno) | `#80868b` | ícono de la app en gris |

- Android dibuja el ícono chico con un solo color (blanco en la barra, el de `setColor` al bajar la cortina): solo importa la silueta. Por eso los tres se distinguen por forma, no por color.
- Son vectores de 24 dp; el "!" está calado (fillType evenOdd).
- Al aparecer un error nuevo, además del cambio de ícono conviene un aviso aparte (canal propio, como el de descarga completa); el punto sobre el ícono de la app sale solo.
- `vista-previa.png`: los tres en barra oscura y clara, ampliados y en la notificación.
