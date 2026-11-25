"""Compara el backend Kotlin con tablero.py: el mismo recorrido de la API contra un rtorrent nuevo
para cada uno, y las respuestas una al lado de la otra. Sirve al portar un cambio de Tablero de
escritorio a la rama kotlin: lo que difiere aparece aquí.

Uso: tablero-compare.py RTORRENT_BIN TABLERO_PY TABLERO_KOTLIN WEB_DIR DIR_TRABAJO
(sale con 1 si alguna respuesta difiere)"""
import base64, hashlib, json, os, random, re, shutil, socket, subprocess, sys, time, urllib.error, urllib.request, zipfile

BIN, PY, KT, WEB, W = sys.argv[1:6]
PORT = 18095

def be(x):
    if isinstance(x, int): return b"i%de" % x
    if isinstance(x, str): x = x.encode()
    if isinstance(x, bytes): return b"%d:" % len(x) + x
    if isinstance(x, list): return b"l" + b"".join(be(i) for i in x) + b"e"
    if isinstance(x, dict): return b"d" + b"".join(be(k) + be(x[k]) for k in sorted(x)) + b"e"

def make_torrents(dl):
    rnd = random.Random(5)
    out = []
    # un archivo
    d1 = rnd.randbytes(700000)
    os.makedirs(dl, exist_ok=True)
    open(os.path.join(dl, "uno.bin"), "wb").write(d1)
    pl = 65536
    i1 = {"name": "uno.bin", "length": len(d1), "piece length": pl,
          "pieces": b"".join(hashlib.sha1(d1[i:i + pl]).digest() for i in range(0, len(d1), pl))}
    out.append(be({"announce": "http://tracker.example.org:6969/announce", "info": i1}))
    # varios archivos, en su carpeta
    files = [("a.txt", rnd.randbytes(100000)), ("sub/b.mkv", rnd.randbytes(300000)), ("sub/c.srt", rnd.randbytes(5000))]
    blob = b"".join(d for _, d in files)
    for p, d in files:
        f = os.path.join(dl, "Carpeta", p); os.makedirs(os.path.dirname(f), exist_ok=True); open(f, "wb").write(d)
    i2 = {"name": "Carpeta", "piece length": pl,
          "files": [{"path": p.split("/"), "length": len(d)} for p, d in files],
          "pieces": b"".join(hashlib.sha1(blob[i:i + pl]).digest() for i in range(0, len(blob), pl))}
    out.append(be({"announce": "udp://open.tracker.example:1337", "announce-list": [["udp://open.tracker.example:1337"], ["https://otro.example.net/a"]], "info": i2}))
    return out

def run(kind):
    w = os.path.join(W, kind)
    shutil.rmtree(w, ignore_errors=True)
    for d in ("base/session", "dl", "cfg", "resp"): os.makedirs(os.path.join(w, d))
    rc = os.path.join(w, "base", "rtorrent.rc")
    open(rc, "w").write(f"""system.daemon.set = true
session.path.set = "{w}/base/session"
directory.default.set = "{w}/dl"
network.scgi.open_local = "{w}/base/rpc.sock"
network.port_range.set = 50100-50100
network.port_random.set = no
network.block.ipv6.set = true
dht.mode.set = disable
""")
    rt = subprocess.Popen([BIN, "-n", "-o", "import=" + rc], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    for _ in range(50):
        if os.path.exists(f"{w}/base/rpc.sock"): break
        time.sleep(0.1)
    args = ["--android", "--scgi", f"{w}/base/rpc.sock", "--config", f"{w}/cfg", "--archivos", f"{w}/dl",
            "--port", str(PORT), "--rtorrent-rc", rc]
    cmd = [sys.executable, PY] + args if kind == "py" else [KT, "--web", WEB] + args
    env = dict(os.environ, HOME=w)
    tb = subprocess.Popen(cmd, stdout=open(f"{w}/tablero.log", "w"), stderr=subprocess.STDOUT, env=env)
    B = f"http://127.0.0.1:{PORT}"
    out = []

    def req(path, body=None, raw=False, headers=None, method=None, data=None):
        h = {"Content-Type": "application/json", "X-Tablero": "1"}
        h.update(headers or {})
        if body is not None: data = json.dumps(body).encode()
        r = urllib.request.Request(B + path, data=data, headers=h, method=method)
        try:
            with urllib.request.urlopen(r, timeout=30) as resp:
                b = resp.read()
                return resp.status, (b if raw else json.loads(b or b"null")), resp.headers
        except urllib.error.HTTPError as e:
            b = e.read()
            try: b = json.loads(b)
            except ValueError: b = b.decode("utf-8", "replace")[:200]
            return e.code, b, e.headers

    def rec(name, r, keep_headers=()):
        st, b, hd = r
        out.append((name, st, b, {k: hd.get(k) for k in keep_headers}))
        return b

    try:
        for _ in range(100):
            try:
                if req("/api/access")[0] == 200: break
            except OSError: pass
            time.sleep(0.2)
        tors = make_torrents(f"{w}/dl")
        enc = [base64.b64encode(t).decode() for t in tors]
        rec("access", req("/api/access"))
        rec("torrent-info 1", req("/api/torrent-info", {"file": enc[0]}))
        rec("torrent-info 2", req("/api/torrent-info", {"file": enc[1]}))
        rec("torrent-info malo", req("/api/torrent-info", {"file": base64.b64encode(b"hola").decode()}))
        rec("torrent-info sin nada", req("/api/torrent-info", {}))
        rec("add 1", req("/api/add", {"file": enc[0], "directory": f"{w}/dl", "tags": ["Linux", "Películas"]}))
        rec("add 2", req("/api/add", {"file": enc[1], "directory": f"{w}/dl", "tags": ["Música"], "skip": [2]}))
        rec("add duplicado", req("/api/add", {"file": enc[1]}))
        rec("add magnet", req("/api/add", {"magnet": "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny&tr=udp%3A%2F%2Ft.example%3A80", "start": False}))
        rec("add magnet base32 duplicado", req("/api/add", {"magnet": "magnet:?xt=urn:btih:3WBFL3G4PSSV7MF37AJSHWDQMLNR63I4"}))
        time.sleep(4)
        ts = req("/api/torrents")[1]
        hs = {t["name"]: t["hash"] for t in ts["torrents"]}
        H1, H2 = hs["uno.bin"], hs["Carpeta"]
        rec("torrents", req("/api/torrents"))
        rec("details 1", req(f"/api/details?hash={H1}"))
        rec("details 2", req(f"/api/details?hash={H2}"))
        rec("tags", req("/api/tags"))
        rec("tags POST", req("/api/tags", {"tags": {"Linux": {"color": "#fff", "icon": "lucide:terminal"}}, "order": ["Linux"]}))
        rec("tags otra vez", req("/api/tags"))
        rec("logo malo", req("/api/logo", {"data": base64.b64encode(b"no es imagen").decode()}))
        png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
        lg = rec("logo", req("/api/logo", {"data": base64.b64encode(png).decode()}))
        if isinstance(lg, dict) and "url" in lg:
            rec("logo GET", req(lg["url"], raw=True), ("Content-Type", "Cache-Control"))
        rec("edit-tags", req("/api/edit-tags", {"hashes": [H1, H2], "add": ["Nueva"], "remove": ["Linux"]}))
        rec("rename-tag", req("/api/rename-tag", {"old": "Nueva", "new": "Otra, con coma"}))
        rec("set-tags", req("/api/set-tags", {"hashes": [H2], "tags": ["  b ", "a", "b", ""]}))
        rec("options", req("/api/options", {"hash": H1, "options": {"down_limit": 204800, "up_limit": "0", "stop_ratio": 1.255,
                                                                     "stop_hours": "2.25", "peers_max": 30, "priority": 3}}))
        rec("options malas", req("/api/options", {"hash": H1, "options": {"peers_max": 3, "peers_min": 9}}))
        rec("general", req("/api/general"))
        rec("general POST", req("/api/general", {"max_sockets": "", "peers_max": 60, "uploads_max": None, "stop_ratio": "0.5"}))
        rec("general POST malo", req("/api/general", {"peers_max": 0}))
        rec("limits", req("/api/limits", {"down": 2048000}))
        rec("move bottom", req("/api/move", {"hashes": [H1], "where": "bottom"}))
        rec("move raro", req("/api/move", {"hashes": [H1], "where": "x"}))
        rec("action rara", req("/api/action", {"action": "volar", "hashes": [H1]}))
        rec("action sin hashes", req("/api/action", {"action": "stop"}))
        rec("file-priority", req("/api/file-priority", {"hash": H2, "indexes": [0, 1], "priority": 0}))
        rec("add-trackers malo", req("/api/add-trackers", {"hash": H1, "urls": ["ftp://x"]}))
        rec("add-trackers", req("/api/add-trackers", {"hash": H1, "urls": ["udp://t1.example:80", " udp://t1.example:80 ", ""]}))
        rec("open-folder sub", req("/api/open-folder", {"hash": H2, "dir": "sub"}))
        rec("open-folder index", req("/api/open-folder", {"hash": H2, "index": 1}))
        rec("open-folder ..", req("/api/open-folder", {"hash": H2, "dir": "../x"}))
        rec("rename-folder 1 archivo", req("/api/rename-folder", {"hash": H1, "name": "x"}))
        rec("rename-folder malo", req("/api/rename-folder", {"hash": H2, "name": "a/b"}))
        rec("rename-folder", req("/api/rename-folder", {"hash": H2, "name": "Carpeta nueva"}))
        rec("relocate sin ruta", req("/api/relocate", {"hashes": [H1], "target": "relativa"}))
        rec("relocate no existe", req("/api/relocate", {"hashes": [H1], "target": f"{w}/nada", "move": False}))
        rec("dirs", req("/api/dirs?path=" + urllib.parse.quote(f"{w}/dl")))
        rec("dirs inexistente", req("/api/dirs?path=" + urllib.parse.quote(f"{w}/dl/no/existe")))
        st, fs, hd = req("/api/free-space?path=" + urllib.parse.quote(f"{w}/dl/no"))
        rec("free-space", (st, {"path": fs.get("path"), "free": "n" if isinstance(fs.get("free"), int) else fs}, hd))
        rec("prefs", req("/api/prefs"))
        rec("prefs POST", req("/api/prefs", {"prefs": {"tema": "oscuro", "cols": [1, 2]}}))
        rec("prefs otra vez", req("/api/prefs"))
        rec("backups", req("/api/backups"))
        rec("backup-settings malo", req("/api/backup-settings", {"folder": "", "every": "daily"}))
        rec("backup-settings", req("/api/backup-settings", {"folder": f"{w}/resp", "every": "weekly", "keep": 200}))
        bk = rec("backup", req("/api/backup", {}))
        st, bl, hd = req("/api/backups")  # el .zip no pesa lo mismo: otro compresor
        for f in bl.get("files", []): f["size"] = f["size"] > 1000
        rec("backups lista", (st, bl, hd))
        if isinstance(bk, dict) and "path" in bk:
            with zipfile.ZipFile(bk["path"]) as z:
                names = sorted(n for n in z.namelist())
                man = json.loads(z.read("manifest.json"))
            out.append(("backup contenido", 200, {"names": names, "torrents": sorted(man["torrents"], key=lambda t: t["hash"]),
                                                  "keys": sorted(man)}, {}))
            rec("restore-inspect", req("/api/restore-inspect", {"path": bk["path"], "from": f"{w}/dl", "to": "/otra"}))
            st, b, hd = req("/api/backup-file?path=" + urllib.parse.quote(bk["path"]), raw=True)
            out.append(("backup-file", st, len(b) > 100, {"Content-Type": hd.get("Content-Type")}))
            rec("backup-import", req("/api/backup-import?folder=" + urllib.parse.quote(f"{w}/resp") + "&name=x.zip",
                                     data=open(bk["path"], "rb").read(), headers={"Content-Type": "application/zip"}))
            rec("backup-import malo", req("/api/backup-import?name=x.zip", data=b"PK no", headers={"Content-Type": "application/zip"}))
            rec("restore", req("/api/restore", {"path": bk["path"], "parts": {"tags": True, "prefs": True, "torrents": True}}))
        rec("backup-file malo", req("/api/backup-file?path=/etc/passwd"))
        rec("port malo", req("/api/port", {"port": 80}))
        rec("access POST corta", req("/api/access", {"lan": True, "password": "123"}))
        rec("access POST sin clave", req("/api/access", {"lan": True}))
        rec("sin X-Tablero", req("/api/limits", {}, headers={"X-Tablero": "0"}))
        rec("ruta desconocida", req("/api/nada", {}))
        rec("JSON malo", req("/api/limits", data=b"{x"))
        st, b, hd = req("/", raw=True)
        out.append(("index.html", st, hashlib.sha1(b).hexdigest(), {"Content-Type": hd.get("Content-Type"), "Cache-Control": hd.get("Cache-Control")}))
        st, b, hd = req("/app.js", raw=True)
        out.append(("app.js", st, hashlib.sha1(b).hexdigest(), {"Content-Type": hd.get("Content-Type")}))
        st, b, hd = req("/app.css?v=1", raw=True)
        out.append(("app.css", st, hashlib.sha1(b).hexdigest(), {"Content-Type": hd.get("Content-Type")}))
        out.append(("404", req("/nada.js", raw=True)[0], None, {}))
        out.append(("../", req("/../../etc/passwd", raw=True)[0], None, {}))
        time.sleep(5)
        rec("history", (200, {"n": len(req("/api/history")[1]["samples"]) > 0, "step": req("/api/history")[1]["step"]}, {}))
        rec("history torrent", (200, {"n": len(req(f"/api/history?hash={H1}")[1]["samples"]) > 0}, {}))
        rec("remove", req("/api/action", {"action": "remove", "hashes": [H2], "data": True}))
        out.append(("datos borrados", 200, os.path.exists(f"{w}/dl/Carpeta nueva"), {}))
        rec("torrents al final", req("/api/torrents"))
        rec("shutdown", req("/api/shutdown", {}))
    finally:
        for p in (tb, rt):
            p.terminate()
            try: p.wait(5)
            except subprocess.TimeoutExpired: p.kill()
    return out, w

VOLATILE = {"added", "started", "finished", "created", "last", "time", "ip", "local_ip", "host", "seen_complete",
            "down_rate", "up_rate", "incoming_seen", "version_" , "eta"}

def norm(x, w):
    if isinstance(x, dict):
        return {k: ("…" if k in VOLATILE else norm(v, w)) for k, v in x.items()}
    if isinstance(x, list):
        return [norm(v, w) for v in x]
    if isinstance(x, str):
        x = x.replace(w, "$W").replace("-kotlin", "")  # la rama kotlin marca su versión
        if x.startswith("Tracker: ["):
            return "Tracker: …"  # el error de red lo pone rTorrent y cambia según el momento
        x = re.sub(r"\d{4}-\d{2}-\d{2}-\d{4}", "FECHA", x)
        return x
    if isinstance(x, bytes):
        return "sha1:" + hashlib.sha1(x).hexdigest()
    if isinstance(x, float) and x == int(x):
        return int(x)  # 0.0 y 0 son lo mismo para la interfaz
    return x

def sort_torrents(b):
    if isinstance(b, dict) and isinstance(b.get("torrents"), list) and b["torrents"] and isinstance(b["torrents"][0], dict):
        b = dict(b, torrents=sorted(b["torrents"], key=lambda t: str(t.get("hash"))))
    return b

py, wp = run("py")
kt, wk = run("kt")
fails = 0
for (n1, s1, b1, h1), (n2, s2, b2, h2) in zip(py, kt):
    a = json.dumps([s1, norm(sort_torrents(b1), wp), h1], ensure_ascii=False, sort_keys=True)
    b = json.dumps([s2, norm(sort_torrents(b2), wk), h2], ensure_ascii=False, sort_keys=True)
    if a == b:
        print(f"igual    {n1}")
    else:
        fails += 1
        print(f"DIFIERE  {n1}\n  py: {a[:1500]}\n  kt: {b[:1500]}")
if len(py) != len(kt):
    fails += 1
    print(f"DIFIERE  cantidad de pasos: py {len(py)}, kt {len(kt)}")
print("TODO IGUAL" if not fails else f"{fails} DIFERENCIAS")
sys.exit(1 if fails else 0)
