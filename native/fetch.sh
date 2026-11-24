#!/usr/bin/env bash
# Descarga y verifica (sha256) las fuentes listadas en sources.lock.
# Si el archivo ya está en native/downloads/ (p. ej. copiado a mano), no se descarga.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
DL="${DOWNLOAD_DIR:-$HERE/downloads}"
mkdir -p "$DL"

grep -vE '^\s*(#|$)' "$HERE/sources.lock" | while read -r name version file sha urls; do
  dest="$DL/$file"
  if [[ -f "$dest" ]] && echo "$sha  $dest" | sha256sum -c --status; then
    echo "[fetch] $file ya está verificado"
    continue
  fi
  ok=0
  for url in $urls; do
    echo "[fetch] $name $version <- $url"
    if curl -fsSL --retry 3 --connect-timeout 20 -o "$dest.part" "$url" \
       && echo "$sha  $dest.part" | sha256sum -c --status; then
      mv "$dest.part" "$dest"; ok=1; break
    fi
    echo "[fetch] falló o sha256 no coincide: $url" >&2
    rm -f "$dest.part"
  done
  [[ $ok == 1 ]] || { echo "[fetch] ERROR: no se pudo obtener $file" >&2; exit 1; }
done
