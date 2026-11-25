"""Recorre la API de Tablero contra un rtorrent real. Uso: tablero-smoke.py URL DIR_TRABAJO"""
import base64, hashlib, json, os, sys, time, urllib.error, urllib.request

B, W = sys.argv[1], sys.argv[2]
fails = 0

def req(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    r = urllib.request.Request(B + path, data=data, headers={"Content-Type": "application/json", "X-Tablero": "1"})
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            return resp.status, json.loads(resp.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:300]
    except OSError as e:
        return 0, str(e)

def check(name, res, ok=lambda b: True):
    global fails
    st, body = res
    good = st == 200 and ok(body)
    fails += 0 if good else 1
    print(("ok  " if good else "FALLO ") + f"{name} [{st}] {json.dumps(body, ensure_ascii=False)[:160]}")
    return body

def rpc(method, *params):
    """XML-RPC directo a rtorrent por el socket SCGI (para ver lo que Tablero aplicó)."""
    import socket, xmlrpc.client
    body = xmlrpc.client.dumps(params, method).encode()
    hdr = b"CONTENT_LENGTH\0%d\0SCGI\x001\0" % len(body)
    s = socket.socket(socket.AF_UNIX); s.connect(os.path.join(W, "base", "rpc.sock"))
    s.sendall(b"%d:" % len(hdr) + hdr + b"," + body)
    raw = b""
    while chunk := s.recv(65536): raw += chunk
    s.close()
    return xmlrpc.client.loads(raw.split(b"\r\n\r\n", 1)[1])[0][0]

for _ in range(60):
    if req("/api/access")[0] == 200: break
    time.sleep(0.5)

# torrent de un archivo con los datos ya en disco
seed = os.path.join(W, "dl", "seed"); os.makedirs(seed, exist_ok=True)
data = os.urandom(3 * 1024 * 1024 + 12345); open(os.path.join(seed, "payload.bin"), "wb").write(data)
pl = 262144
pieces = b"".join(hashlib.sha1(data[i:i + pl]).digest() for i in range(0, len(data), pl))
def be(x):
    if isinstance(x, int): return b"i%de" % x
    if isinstance(x, str): x = x.encode()
    if isinstance(x, bytes): return b"%d:" % len(x) + x
    if isinstance(x, dict): return b"d" + b"".join(be(k) + be(x[k]) for k in sorted(x)) + b"e"
info = {"name": "payload.bin", "length": len(data), "piece length": pl, "pieces": pieces}
tor = base64.b64encode(be({"announce": "udp://tracker.opentrackr.org:1337", "info": info})).decode()
TH = hashlib.sha1(be(info)).hexdigest().upper()
MAG = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny"

check("torrents (vacío)", req("/api/torrents"), lambda b: b["platform"] == "android" and b["stats"]["version"])
check("torrent-info", req("/api/torrent-info", {"file": tor}), lambda b: b["hash"] == TH)
check("add magnet", req("/api/add", {"magnet": MAG, "tags": ["video"], "start": True}))
check("add .torrent", req("/api/add", {"file": tor, "directory": seed, "tags": ["prueba"], "start": True}))
for _ in range(40):
    ts = {t["hash"]: t for t in req("/api/torrents")[1]["torrents"]}
    if TH in ts and ts[TH]["done"] == ts[TH]["size"]: break
    time.sleep(0.25)
check("verificado al 100 %", req("/api/torrents"),
      lambda b: any(t["hash"] == TH and t["done"] == t["size"] and t["status"] == "seeding" for t in b["torrents"]))
check("details (f/p/t.multicall)", req(f"/api/details?hash={TH}"), lambda b: b["files"] and b["trackers"])
check("history", req(f"/api/history?hash={TH}"))
for a in ("pause", "start", "stop", "start", "recheck"):
    check("action " + a, req("/api/action", {"action": a, "hashes": [TH]})); time.sleep(0.3)
check("priority", req("/api/action", {"action": "priority", "hashes": [TH], "value": 3}))
check("set-tags", req("/api/set-tags", {"hashes": [TH], "tags": ["a", "b"]}))
check("file-priority", req("/api/file-priority", {"hash": TH, "indexes": [0], "priority": 2}))
check("limits", req("/api/limits", {"down": 1048576, "up": 262144}))
check("move", req("/api/move", {"hashes": [TH], "where": "top"}))
check("open-folder (android)", req("/api/open-folder", {"hash": TH}), lambda b: b.get("android") is True)
check("add-trackers", req("/api/add-trackers", {"hash": TH, "urls": ["udp://open.stealth.si:80/announce"]}))
check("port (edita el rc)", req("/api/port", {"port": 50005}), lambda b: b.get("saved"))
check("relocate", req("/api/relocate", {"hashes": [TH], "target": os.path.join(W, "dl", "moved"), "move": True, "check": True}))
time.sleep(2)
check("tags quedaron en d.custom", req("/api/torrents"),
      lambda b: any(t["hash"] == TH and set(t["tags"]) == {"a", "b"} for t in b["torrents"]))
check("general (BiglyBT por defecto)", req("/api/general"),
      lambda b: b["general"]["max_sockets"] == 250 and b["general"]["peers_max"] == 50)
time.sleep(1)
# rtorrent 0.16 rechaza network.max_open_sockets < 512 ("minimum is 512"): el 250 de Tablero no se aplica.
print("AVISO network.max_open_sockets =", rpc("network.max_open_sockets"), "(Tablero pidió 250; rtorrent 0.16 exige 512 o más)")
check("general POST", req("/api/general", {"max_sockets": 300, "peers_max": 40, "uploads_max": 3}))
time.sleep(1)
check("general aplicado tras POST (por torrent)", (200, [rpc("d.peers_max", TH), rpc("d.uploads_max", TH)]),
      lambda v: v == [40, 3])
hb = urllib.request.Request(B + "/api/torrents", headers={"Host": "evil.example:18090"})
try:
    st = urllib.request.urlopen(hb, timeout=10).status
except urllib.error.HTTPError as e:
    st = e.code
check("Host ajeno rechazado (DNS rebinding)", (200, st), lambda v: v == 403)
check("remove con datos", req("/api/action", {"action": "remove", "hashes": [TH], "data": True}))
time.sleep(1)
if os.path.exists(os.path.join(W, "dl", "moved", "payload.bin")):
    fails += 1; print("FALLO los datos siguen en disco")
check("shutdown", req("/api/shutdown", {}))
print("TODO OK" if fails == 0 else f"{fails} FALLOS"); sys.exit(1 if fails else 0)
