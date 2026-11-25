"""Prueba d.sequential con dos rtorrent locales: uno siembra, el otro baja limitado y en orden.
Con TABLERO=ruta/a/tablero.py prueba además /stream (Reproducir mientras se descarga).
Uso: sequential-test.py RTORRENT_BIN DIR_TRABAJO   (sale con 1 si algo falla)"""
import hashlib, os, shutil, socket, subprocess, sys, time, xmlrpc.client

BIN, W = sys.argv[1], sys.argv[2]
shutil.rmtree(W, ignore_errors=True)
PL, NP = 32 * 1024, 160                      # 160 piezas de 32 KiB = 5 MiB
data = os.urandom(PL * NP)

def be(x):
    if isinstance(x, int): return b"i%de" % x
    if isinstance(x, str): x = x.encode()
    if isinstance(x, bytes): return b"%d:" % len(x) + x
    if isinstance(x, dict): return b"d" + b"".join(be(k) + be(x[k]) for k in sorted(x)) + b"e"

info = {"name": "video.bin", "length": len(data), "piece length": PL,
        "pieces": b"".join(hashlib.sha1(data[i:i + PL]).digest() for i in range(0, len(data), PL))}
H = hashlib.sha1(be(info)).hexdigest().upper()
tor = be({"info": info})

def start(name, port, extra=""):
    d = os.path.join(W, name); os.makedirs(d + "/session", exist_ok=True); os.makedirs(d + "/dl", exist_ok=True)
    rc = f"""system.daemon.set = true
session.path.set = "{d}/session"
directory.default.set = "{d}/dl"
network.scgi.open_local = "{d}/rpc.sock"
network.port_range.set = {port}-{port}
network.port_random.set = no
network.block.ipv6.set = true
dht.mode.set = disable
protocol.pex.set = no
{extra}
"""
    open(d + "/rc", "w").write(rc)
    p = subprocess.Popen([BIN, "-n", "-o", "import=" + d + "/rc"], stdout=open(d + "/out.log", "w"), stderr=subprocess.STDOUT)
    for _ in range(100):
        if os.path.exists(d + "/rpc.sock"): break
        time.sleep(0.1)
    return p, d

def rpc(d, m, *a):
    body = xmlrpc.client.dumps(a, m).encode()
    hdr = b"CONTENT_LENGTH\0%d\0SCGI\x001\0" % len(body)
    s = socket.socket(socket.AF_UNIX); s.connect(d + "/rpc.sock"); s.sendall(b"%d:" % len(hdr) + hdr + b"," + body)
    raw = b""
    while c := s.recv(65536): raw += c
    s.close()
    return xmlrpc.client.loads(raw.split(b"\r\n\r\n", 1)[1])[0][0] if raw else None

fails = 0
def check(name, ok, info=""):
    global fails
    fails += 0 if ok else 1
    print(("ok  " if ok else "FALLO ") + name + (f"  ({info})" if info else ""))

def run(sequential, desde=0):
    """Baja unos segundos limitado y devuelve el bitfield (lista de bool) del que baja."""
    seed, sd = start("seed", 51000)
    leech, ld = start("leech", 51001, "throttle.global_down.max_rate.set_kb = 96")
    try:
        open(sd + "/dl/video.bin", "wb").write(data)
        rpc(sd, "load.raw_start", "", xmlrpc.client.Binary(tor), "d.directory.set=" + sd + "/dl")
        for _ in range(100):
            if rpc(sd, "d.complete", H) == 1: break
            time.sleep(0.1)
        rpc(ld, "load.raw", "", xmlrpc.client.Binary(tor))
        time.sleep(0.3)
        if sequential:
            rpc(ld, "d.sequential.set", H, 1)
        if desde:
            rpc(ld, "d.sequential_from.set", H, desde)
            check(f"d.sequential_from = {desde}", rpc(ld, "d.sequential_from", H) == desde)
        check(f"d.sequential = {int(sequential)}", rpc(ld, "d.sequential", H) == int(sequential))
        rpc(ld, "d.start", H)
        rpc(ld, "add_peer", H, "127.0.0.1:51000")
        for _ in range(150):
            if rpc(ld, "d.completed_chunks", H) >= NP // 3: break
            time.sleep(0.1)
        bf = bytes.fromhex(rpc(ld, "d.bitfield", H))
        bits = [bool(bf[i // 8] & (0x80 >> (i % 8))) for i in range(NP)]
        if sequential and not desde:
            # Guardado en la sesión: al reiniciar sigue en orden.
            rpc(ld, "d.save_full_session", H)
            rpc(ld, "system.shutdown.normal")
            leech.wait(10)
            leech, ld = start("leech", 51001)
            for _ in range(50):
                try:
                    if rpc(ld, "d.hash", H): break
                except Exception: pass
                time.sleep(0.1)
            check("d.sequential se guarda en la sesión", rpc(ld, "d.sequential", H) == 1)
        return bits
    finally:
        for p in (seed, leech):
            p.terminate()
            try: p.wait(5)
            except subprocess.TimeoutExpired: p.kill()
        shutil.rmtree(W, ignore_errors=True)

def prefix_score(bits):
    """Qué parte de lo bajado está en el tramo inicial contiguo (1.0 = todo en orden)."""
    n = sum(bits)
    first_hole = bits.index(False) if False in bits else len(bits)
    return n, first_hole

bits = run(True)
n, hole = prefix_score(bits)
check("secuencial: piezas bajadas desde el principio", n >= 10 and hole >= n - 8, f"{n} piezas, las primeras {hole} seguidas")
bits = run(True, desde=100)
n3 = sum(bits); antes = sum(bits[:100]); hole3 = bits.index(False, 100) if False in bits[100:] else NP
check("secuencial desde la pieza 100 (un reproductor que salta)", n3 >= 10 and antes <= 8 and hole3 - 100 >= n3 - antes - 8,
      f"{n3} piezas, {antes} antes de la 100, de la 100 a la {hole3 - 1} seguidas")
TB = os.environ.get("TABLERO")

def stream_test():
    """Tablero sirve el archivo mientras baja: Range desde el medio, y solo bytes de piezas completas."""
    import urllib.request
    seed, sd = start("seed", 51000)
    leech, ld = start("leech", 51001, "throttle.global_down.max_rate.set_kb = 256")
    tb = subprocess.Popen([sys.executable, TB, "--android", "--scgi", ld + "/rpc.sock", "--config", W + "/cfg",
                           "--archivos", ld + "/dl", "--port", "18091"], stdout=open(W + "/tablero.log", "w"), stderr=subprocess.STDOUT)
    try:
        open(sd + "/dl/video.bin", "wb").write(data)
        rpc(sd, "load.raw_start", "", xmlrpc.client.Binary(tor), "d.directory.set=" + sd + "/dl")
        for _ in range(100):
            if rpc(sd, "d.complete", H) == 1: break
            time.sleep(0.1)
        rpc(ld, "load.raw_start", "", xmlrpc.client.Binary(tor))
        rpc(ld, "add_peer", H, "127.0.0.1:51000")
        url = f"http://127.0.0.1:18091/stream/{H}/0/video.bin"
        for _ in range(60):
            try:
                urllib.request.urlopen("http://127.0.0.1:18091/api/access", timeout=2); break
            except OSError: time.sleep(0.25)
        mid = PL * 120 + 1000
        r = urllib.request.Request(url, headers={"Range": f"bytes={mid}-{mid + 200 * 1024 - 1}"})
        t0 = time.time()
        with urllib.request.urlopen(r, timeout=90) as resp:
            got = resp.read()
            check("stream: 206 con Content-Range", resp.status == 206 and
                  resp.headers["Content-Range"] == f"bytes {mid}-{mid + 200 * 1024 - 1}/{len(data)}", resp.headers["Content-Range"])
        check("stream: bytes del medio correctos", got == data[mid:mid + 200 * 1024], f"{len(got)} bytes en {time.time() - t0:.1f} s")
        bf = bytes.fromhex(rpc(ld, "d.bitfield", H))
        bits = [bool(bf[i // 8] & (0x80 >> (i % 8))) for i in range(NP)]
        check("stream: bajó desde donde pidió el reproductor", sum(bits[120:127]) == 7 and sum(bits[:100]) <= 8,
              f"{sum(bits[:100])} piezas antes de la 100")
        check("stream: d.sequential quedó activado", rpc(ld, "d.sequential", H) == 1)
        with urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=10) as resp:
            check("stream: HEAD sin Range", resp.status == 200 and int(resp.headers["Content-Length"]) == len(data)
                  and resp.headers["Accept-Ranges"] == "bytes")
        try:
            urllib.request.urlopen(urllib.request.Request(url, headers={"Range": f"bytes={len(data)}-"}), timeout=10)
            check("stream: Range fuera del archivo da 416", False)
        except urllib.error.HTTPError as e:
            check("stream: Range fuera del archivo da 416", e.code == 416)
    finally:
        for p in (tb, seed, leech):
            p.terminate()
            try: p.wait(5)
            except subprocess.TimeoutExpired: p.kill()
        shutil.rmtree(W, ignore_errors=True)

if TB:
    import urllib.error
    stream_test()
bits = run(False)
n2, hole2 = prefix_score(bits)
print(f"    normal (referencia): {n2} piezas, las primeras {hole2} seguidas")
sys.exit(1 if fails else 0)
