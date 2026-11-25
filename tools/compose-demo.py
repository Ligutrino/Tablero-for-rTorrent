#!/usr/bin/env python3
"""Llena un Tablero de prueba con torrents y etiquetas de ejemplo, para las capturas de la interfaz Compose.

Uso: compose-demo.py http://127.0.0.1:PUERTO DIR_DESCARGAS
"""
import base64, hashlib, json, os, sys, urllib.request

URL, DL = sys.argv[1], sys.argv[2]
PIECE = 16384

def api(path, body=None):
    req = urllib.request.Request(URL + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "X-Tablero": "1"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())

def benc(v):
    if isinstance(v, int): return b"i%de" % v
    if isinstance(v, str): v = v.encode()
    if isinstance(v, bytes): return b"%d:%s" % (len(v), v)
    if isinstance(v, list): return b"l" + b"".join(benc(x) for x in v) + b"e"
    if isinstance(v, dict): return b"d" + b"".join(benc(k) + benc(v[k]) for k in sorted(v)) + b"e"
    raise TypeError(v)

def make(name, files, keep=True):
    """files: [(ruta, tamaño)]. keep=False borra los datos después (queda incompleto)."""
    base = os.path.join(DL, name)
    data = b""
    for i, (p, n) in enumerate(files):
        full = os.path.join(base, p) if len(files) > 1 else os.path.join(DL, name)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        chunk = hashlib.sha256((name + p).encode()).digest() * (n // 32 + 1)
        chunk = chunk[:n]
        with open(full, "wb") as f: f.write(chunk)
        data += chunk
    pieces = b"".join(hashlib.sha1(data[i:i + PIECE]).digest() for i in range(0, len(data), PIECE))
    info = {"name": name, "piece length": PIECE, "pieces": pieces}
    if len(files) > 1: info["files"] = [{"length": n, "path": p.split("/")} for p, n in files]
    else: info["length"] = files[0][1]
    t = benc({"announce": "http://tracker.ejemplo.cl:6969/announce", "info": info})
    if not keep:
        for p, _ in files:
            full = os.path.join(base, p) if len(files) > 1 else os.path.join(DL, name)
            os.remove(full)
    return base64.b64encode(t).decode()

api("/api/tags", {"tags": {
    "Películas": {"color": "#e53935", "icon": "lucide:film"},
    "Series": {"color": "#1e88e5", "icon": "lucide:tv"},
    "Música": {"color": "#43a047", "icon": "lucide:music"},
    "4K": {"color": "#fdd835", "icon": ""},
}, "order": ["Películas", "Series", "Música", "4K"]})

demo = [
    ("Big Buck Bunny (2008) 2160p", [("bbb.mkv", 300000)], ["Películas", "4K"], True),
    ("Sintel (2010) 1080p", [("sintel.mkv", 200000), ("sintel.es.srt", 4000)], ["Películas"], False),
    ("Debian 13.2 netinst amd64", [("debian-13.2-amd64-netinst.iso", 250000)], ["Linux"], True),
    ("Tears of Steel temporada 1", [("e01.mkv", 120000), ("e02.mkv", 120000), ("e03.mkv", 120000)], ["Series"], False),
    ("Archivo de radio: jazz chileno", [("01.flac", 90000), ("02.flac", 90000)], ["Música"], True),
    ("Fotos del viaje al sur", [("img001.jpg", 50000)], [], False),
]
for name, files, tags, keep in demo:
    r = api("/api/add", {"file": make(name, files, keep), "directory": DL, "tags": tags, "start": True})
    print(name, r)
api("/api/add", {"magnet": "magnet:?xt=urn:btih:" + "c" * 40 + "&dn=Ubuntu%2026.04%20desktop", "directory": DL, "tags": ["Linux"], "start": True})
# Un .torrent con carpetas que no se agrega: para "Agregar torrents" con archivos (escena agregar-archivos).
nuevo = make("Patagonia (documental)", [("video/01 Torres del Paine.mkv", 80000), ("video/02 Glaciares.mkv", 80000),
                                       ("subtitulos/es.srt", 3000), ("subtitulos/en.srt", 3000), ("LEEME.txt", 500)], keep=False)
with open(os.path.join(os.path.dirname(DL.rstrip("/")), "patagonia.torrent"), "wb") as f: f.write(base64.b64decode(nuevo))
print("listo")
