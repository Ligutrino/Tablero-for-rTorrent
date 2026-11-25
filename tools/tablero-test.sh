#!/usr/bin/env bash
# Prueba el backend Kotlin de Tablero (android/tablero/src/main/kotlin/cl/tablero/backend, en la JVM)
# contra el rtorrent de host, con la misma prueba que tenía tablero.py (tools/tablero-smoke.py).
# Requiere native/out/host/librtorrent.so (tools/host-test.sh lo compila), JDK 17+ y python3 (solo para la prueba).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="${WORK:-/tmp/rtorrent-tablero-test}"
BIN="${RTORRENT_BIN:-$ROOT/native/out/host/librtorrent.so}"
TABLERO="$ROOT/tools/tablero-host/build/install/tablero/bin/tablero"
[[ -x "$TABLERO" ]] || "$ROOT/android/gradlew" --no-daemon -q -p "$ROOT/tools/tablero-host" installDist
[[ ! -e "$W" || -f "$W/.tablero-test" ]] || { echo "$W existe y no es de esta prueba" >&2; exit 1; }
rm -rf "$W"; mkdir -p "$W"/{base/session,base/log,dl,cfg}; touch "$W/.tablero-test"
cat > "$W/base/rtorrent.rc" <<RC
system.daemon.set = true
session.path.set = "$W/base/session"
directory.default.set = "$W/dl"
network.scgi.open_local = "$W/base/rpc.sock"
log.open_file = "main", "$W/base/log/rtorrent.log"
log.add_output = "info", "main"
# puerto-de-RtorrentConfig: 50000-50010
network.port_range.set = 50000-50010
network.port_random.set = no
$( [[ -e /proc/net/if_inet6 ]] || echo "network.block.ipv6.set = true" )
dht.mode.set = disable
RC
"$BIN" -n -o import="$W/base/rtorrent.rc" > "$W/base/log/stdout.log" 2>&1 &
RT=$!
for _ in $(seq 50); do [[ -S "$W/base/rpc.sock" ]] && break; sleep 0.2; done
"$TABLERO" --android --scgi "$W/base/rpc.sock" --web "$ROOT/android/tablero/src/main/assets/web" \
  --config "$W/cfg" --archivos "$W/dl" --port 18090 > "$W/tablero.log" 2>&1 &
TB=$!
trap 'kill $TB $RT 2>/dev/null || true' EXIT
python3 "$ROOT/tools/tablero-smoke.py" "http://127.0.0.1:18090" "$W"
