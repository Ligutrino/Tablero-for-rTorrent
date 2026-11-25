# Tablero para rTorrent: paquete para Android

- `GUIA-INTEGRACION.md`: cómo usar Tablero como interfaz de la app Android (empieza aquí).
- `fuente/`: Tablero 0.11-android.2 (`tablero.py` + `web/` + `iconos/`). Rama aparte de la versión de escritorio.
- `ejemplo-modulo-ui/`: módulo Kotlin de ejemplo (`:ui-tablero`) que se conecta con `:engine`, con la notificación (`TableroAvisos`). Sin compilar.
- `icono-adaptable/`: ícono de la app para Android (capas fondo, frente y monocromo) con vista previa.
- `icono-notificacion/`: íconos de la notificación (normal, con error, sin conexión) con vista previa.
- `android.patch`: diferencias con la versión de escritorio, para aplicarlas a versiones nuevas.
- `CAMBIOS-ANDROID.md`: qué cambia respecto de escritorio.
- `PENDIENTE-PORT.md`: lo que falta conectar en la app (servicio de `:engine`, manifiesto, rtorrent.rc, Encender/Reiniciar), con el código y cómo comprobarlo.
- `anteriores/`: paquetes de versiones anteriores.
