# Ícono adaptable de Tablero para Android

- `res/`: copiar a `app/src/main/res/` y en el manifiesto `android:icon="@mipmap/ic_launcher"` y `android:roundIcon="@mipmap/ic_launcher_round"`. Son vectores: no hacen falta PNG por densidad (la app pide Android 9+, y los íconos adaptables existen desde Android 8).
- Capas de 108 dp:
  - **Fondo**: negro con los bloques azul, amarillo y rojo llevados hasta el borde, para que ninguna máscara deje un marco oscuro.
  - **Frente**: la "r" negra y el punto. Quedan dentro de la zona segura de 66 dp (el punto llega a 32,7 dp del centro, el límite es 33), así ninguna máscara los corta.
  - **Monocromo**: la "r" y el punto, para los íconos temáticos de Android 13+.
- Mismos colores que el logo de escritorio (#1d4e9e, #f2c200, #d7262e, #f2efe6, #1a1a1a). Respecto del logo de 64 px, el diseño queda a 1,04 dp por unidad, centrado.
- `vista-previa.png`: cómo se ve con máscara de círculo, squircle y cuadrado redondeado, y como ícono temático.
- `fondo.svg`, `frente.svg`, `monocromo.svg`, `completo.svg`: las mismas capas en SVG.
