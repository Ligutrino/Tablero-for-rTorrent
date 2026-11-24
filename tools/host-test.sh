#!/usr/bin/env bash
# Compila rtorrent para Linux y ejecuta la prueba de integración del motor Kotlin contra él.
# Requiere: compilador C/C++, autotools, libtool, pkg-config, zlib, JDK 17+.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/native/build.sh" host
"$ROOT/android/gradlew" --no-daemon -q -p "$ROOT/tools/host-test" installDist
"$ROOT/tools/host-test/build/install/host-test/bin/host-test" "${WORK:-/tmp/rtorrent-host-test}" "$ROOT/native/out/host/librtorrent.so"
"$ROOT/tools/tablero-test.sh"
