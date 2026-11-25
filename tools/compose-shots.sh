#!/usr/bin/env bash
# Capturas de la interfaz Compose (tools/tablero-compose) contra un rtorrent de host con datos de ejemplo.
# Uso: tools/compose-shots.sh DIR_CAPTURAS [escena,escena...]   (requiere native/out/host/librtorrent.so)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mkdir -p "$1" && cd "$1" && pwd)"; ESC="${2:-}"
W="${WORK:-/tmp/rtorrent-compose-shots}"
BIN="${RTORRENT_BIN:-$ROOT/native/out/host/librtorrent.so}"
HOST="$ROOT/tools/tablero-host/build/install/tablero/bin/tablero"
UI="$ROOT/tools/tablero-compose/build/install/tablero-compose/bin/tablero-compose"
[[ ! -e "$W" || -f "$W/.compose-shots" ]] || { echo "$W existe y no es de esta prueba" >&2; exit 1; }
rm -rf "$W"; mkdir -p "$W"/{base/session,base/log,dl,cfg}; touch "$W/.compose-shots"
cat > "$W/base/rtorrent.rc" <<RC
system.daemon.set = true
session.path.set = "$W/base/session"
directory.default.set = "$W/dl"
network.scgi.open_local = "$W/base/rpc.sock"
network.port_range.set = 50100-50110
network.port_random.set = no
$( [[ -e /proc/net/if_inet6 ]] || echo "network.block.ipv6.set = true" )
dht.mode.set = disable
RC
"$BIN" -n -o import="$W/base/rtorrent.rc" > "$W/base/log/stdout.log" 2>&1 &
RT=$!
trap 'kill $RT 2>/dev/null || true' EXIT
for _ in $(seq 50); do [[ -S "$W/base/rpc.sock" ]] && break; sleep 0.2; done
WEB="$ROOT/android/tablero/src/main/assets/web"
"$HOST" --android --scgi "$W/base/rpc.sock" --web "$WEB" --config "$W/cfg" --archivos "$W/dl" --port 18092 > "$W/host.log" 2>&1 &
TB=$!
for _ in $(seq 50); do curl -s -o /dev/null http://127.0.0.1:18092/api/access && break; sleep 0.2; done
python3 "$ROOT/tools/compose-demo.py" http://127.0.0.1:18092 "$W/dl"
sleep 3
kill $TB; wait $TB 2>/dev/null || true
"$UI" --shots "$OUT" ${ESC:+--escena "$ESC"} --scgi "$W/base/rpc.sock" --web "$WEB" --config "$W/cfg" --archivos "$W/dl" --port 18093
