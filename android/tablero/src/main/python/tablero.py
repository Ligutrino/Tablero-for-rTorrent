#!/usr/bin/env python3
"""Tablero para rTorrent: a BiglyBT-style web interface for rTorrent.

Serves the web UI from ./web and a small JSON API that talks to rTorrent
over SCGI (XML-RPC). Uses only the Python standard library.

Tags: rTorrent has a single label per torrent (d.custom1, used by ruTorrent).
Tablero keeps any number of tags per torrent in d.custom "tablero_tags"
(URL-encoded names joined by commas) and mirrors the first one into
d.custom1, so ruTorrent still shows a label. Torrents without Tablero tags
show their ruTorrent label as their only tag.

Tag colors and icons are stored in ~/.config/tablero/tags.json.
"""

import argparse
import base64
import collections
import hashlib
import hmac
import re
import json
import os
import random
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import xmlrpc.client
import zipfile
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

VERSION = "0.10.1-android.1"
TAGS_KEY = "tablero_tags"
OPTS_KEY = "tablero_opts"
POS_KEY = "tablero_pos"
# Until 0.8 (as Mosaico) the same values had these names: renamed in each torrent once.
OLD_KEYS = {"mosaico_tags": TAGS_KEY, "mosaico_opts": OPTS_KEY, "mosaico_pos": POS_KEY}
OLD_THROTTLE = "mosaico_"


def parse_opts(value):
    try:
        opts = json.loads(value) if value else {}
        return opts if isinstance(opts, dict) else {}
    except ValueError:
        return {}


def clean_opts(o):
    """Per-torrent options, as the UI sends them. Speeds in bytes/s,
    0 = no limit of its own; ratio and hours 0 = off."""
    out = {}
    for k in ("down_limit", "up_limit"):
        out[k] = max(0, int(o.get(k) or 0))
    out["stop_ratio"] = max(0.0, round(float(o.get("stop_ratio") or 0), 2))
    out["stop_hours"] = max(0.0, round(float(o.get("stop_hours") or 0), 1))
    return out


def rule_reached(t, opts, now):
    """Whether a seeding torrent met its stop rule."""
    if not (t["complete"] and t["state"]) or opts.get("rule_done"):
        return False
    if opts.get("stop_ratio") and t["ratio"] >= opts["stop_ratio"]:
        return True
    return bool(opts.get("stop_hours") and t["finished"]
                and now - t["finished"] >= opts["stop_hours"] * 3600)
WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
CONFIG_BASE = os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config")
CONFIG_DIR = os.path.join(CONFIG_BASE, "tablero")
# "desktop", or "android" (--android): the interface then hides what only works on a
# PC (xdg-open, systemctl, apt, ufw) and uses window.TableroAndroid when the app gives it.
PLATFORM = "desktop"
ANDROID_STORAGE = "/storage/emulated/0"
USER_FILES = None  # --archivos: where Examinar… and the backups start
RTORRENT_RC = None  # --rtorrent-rc: the .rc the app gives rTorrent, when Tablero cannot find it




def user_files():
    """Where the user's own files are: ~ on a PC; on Android the shared storage
    (Termux's ~ and an app's home are private, no file manager shows them)."""
    if USER_FILES:
        return USER_FILES
    # Without "Acceso a todos los archivos" the shared storage exists but can't be read.
    if PLATFORM == "android" and os.access(ANDROID_STORAGE, os.R_OK | os.W_OK | os.X_OK):
        return ANDROID_STORAGE
    return os.path.expanduser("~")


def move_old_config(base=CONFIG_BASE):
    """Until 0.8 (as Mosaico) the settings lived in ~/.config/mosaico: moved once to
    ~/.config/tablero. The window does the same at start, and also drops the storage
    of its old name."""
    old, new = os.path.join(base, "mosaico"), os.path.join(base, "tablero")
    if os.path.islink(old):
        os.remove(old)  # a link left by a test version
        return
    if not os.path.isdir(old) or os.path.lexists(new):
        return
    try:
        os.rename(old, new)
    except OSError as e:
        print("could not move %s to %s: %s" % (old, new, e), file=sys.stderr)


def encode_tags(tags):
    return ",".join(urllib.parse.quote(t, safe="") for t in tags)


def decode_tags(value):
    return [urllib.parse.unquote(t) for t in value.split(",") if t]


def clean_tags(tags):
    seen, out = set(), []
    for t in tags:
        t = str(t).strip()
        if t and t not in seen:
            seen.add(t)
            out.append(t)
    return out


# ---------------------------------------------------------------- SCGI

class RTorrent:
    """Minimal XML-RPC client over SCGI (TCP host:port or a Unix socket)."""

    def __init__(self, address):
        self.address = address
        self._port_range = None

    def port_range_method(self):
        """rTorrent 0.16 (Termux) renamed network.port_range to network.listen.port.range
        and dropped the old name unless started with -D; 0.9.8 only has the old one."""
        if self._port_range is None:
            try:
                self.call("network.listen.port.range")
                self._port_range = "network.listen.port.range"
            except xmlrpc.client.Fault:
                self._port_range = "network.port_range"
        return self._port_range

    def _connect(self):
        if ":" in self.address and not self.address.startswith("/"):
            host, port = self.address.rsplit(":", 1)
            return socket.create_connection((host, int(port)), timeout=15)
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(15)
        s.connect(os.path.expanduser(self.address))
        return s

    def call(self, method, *params):
        body = xmlrpc.client.dumps(params, method, allow_none=False).encode()
        if len(body) > 500000:
            self._fit_size_limit(len(body))
        return self._send(body)

    def _fit_size_limit(self, n):
        """rTorrent rejects XML-RPC requests over network.xmlrpc.size_limit
        (512 KB by default), which a large .torrent passes once base64-encoded.
        Raise the limit for this rTorrent session, never lower it."""
        try:
            limit = int(self._send(xmlrpc.client.dumps((), "network.xmlrpc.size_limit").encode()))
        except (OSError, ValueError, xmlrpc.client.Fault):
            limit = 0
        if n + 65536 > limit:
            want = -(-(n + 65536) // (1 << 20)) << 20  # rounded up to whole MB
            self._send(xmlrpc.client.dumps(("", want), "network.xmlrpc.size_limit.set").encode())

    def _send(self, body):
        headers = b"CONTENT_LENGTH\x00%d\x00SCGI\x001\x00" % len(body)
        packet = b"%d:%s," % (len(headers), headers) + body
        with self._connect() as s:
            s.sendall(packet)
            chunks = []
            while True:
                data = s.recv(65536)
                if not data:
                    break
                chunks.append(data)
        raw = b"".join(chunks)
        sep = raw.find(b"\r\n\r\n")
        if not raw:
            # rTorrent 0.16 cierra sin responder a system.shutdown.normal.
            raise ConnectionError("rTorrent cerró la conexión sin responder")
        payload = raw[sep + 4:] if sep >= 0 else raw
        result, _ = xmlrpc.client.loads(payload.decode("utf-8", "replace"))
        return result[0]

    def multicall(self, calls):
        """calls: list of (method, params). Returns a list of results;
        a failed call yields an Exception instance in its slot."""
        res = self.call("system.multicall",
                        [{"methodName": m, "params": list(p)} for m, p in calls])
        out = []
        for r in res:
            if isinstance(r, dict):
                out.append(Exception(r.get("faultString", "error")))
            else:
                out.append(r[0])
        return out


TORRENT_FIELDS = [
    ("hash", "d.hash="),
    ("name", "d.name="),
    ("size", "d.size_bytes="),
    ("done", "d.completed_bytes="),
    ("down_rate", "d.down.rate="),
    ("up_rate", "d.up.rate="),
    ("up_total", "d.up.total="),
    ("down_total", "d.down.total="),
    ("ratio", "d.ratio="),
    ("state", "d.state="),
    ("active", "d.is_active="),
    ("complete", "d.complete="),
    ("hashing", "d.hashing="),
    ("hash_checking", "d.is_hash_checking="),
    ("chunks_hashed", "d.chunks_hashed="),
    ("chunks", "d.size_chunks="),
    ("message", "d.message="),
    ("seeds", "d.peers_complete="),
    ("peers", "d.peers_accounted="),
    ("label", "d.custom1="),
    ("tags", "d.custom=" + TAGS_KEY),
    ("addtime", "d.custom=addtime"),
    ("started", "d.timestamp.started="),
    ("finished", "d.timestamp.finished="),
    ("created", "d.creation_date="),
    ("directory", "d.directory="),
    ("priority", "d.priority="),
    ("private", "d.is_private="),
    ("files", "d.size_files="),
    ("opts", "d.custom=" + OPTS_KEY),
    ("peers_max", "d.peers_max="),
    ("peers_min", "d.peers_min="),
    ("uploads_max", "d.uploads_max="),
    ("pos", "d.custom=" + POS_KEY),
]


def status_of(t):
    # rTorrent checks one torrent at a time: the others keep d.hashing set while they wait.
    if t["hashing"]:
        return "checking" if t.get("hash_checking", 1) else "queued"
    # A tracker warning ("Tracker: [Timed out]") stays in d.message after a stop and is
    # not a fault of the torrent; other messages on a torrent that is not running are.
    if t["message"] and not t["active"] and not t["message"].startswith("Tracker:"):
        return "error"
    if not t["state"]:
        return "finished" if t["complete"] else "stopped"
    if not t["active"]:
        return "paused"
    return "seeding" if t["complete"] else "downloading"


class CheckQueue:
    """The order of the torrents waiting to be checked, which rTorrent does not report:
    the order Tablero asked for them, or the order it first saw them waiting."""

    def __init__(self):
        self.seq = {}
        self.next = 0
        self.lock = threading.Lock()

    def asked(self, hashes):
        with self.lock:
            for h in hashes:
                if h not in self.seq:
                    self.seq[h] = self.next
                    self.next += 1

    def annotate(self, torrents):
        """Adds queue_pos (1 = next) to waiting torrents."""
        with self.lock:
            for t in torrents:
                if not t["hashing"]:
                    self.seq.pop(t["hash"], None)
            waiting = sorted((t for t in torrents if t["status"] == "queued"), key=lambda t: t.get("pos", 0))
            for t in waiting:
                if t["hash"] not in self.seq:
                    self.seq[t["hash"]] = self.next
                    self.next += 1
            waiting.sort(key=lambda t: self.seq[t["hash"]])
        for n, t in enumerate(waiting, 1):
            t["queue_pos"] = n


def move_order(order, chosen, where):
    """order with the chosen items moved one step up or down, or to the top or bottom,
    keeping the order among them."""
    chosen = set(chosen)
    if where == "top":
        return [h for h in order if h in chosen] + [h for h in order if h not in chosen]
    if where == "bottom":
        return [h for h in order if h not in chosen] + [h for h in order if h in chosen]
    out = list(order)
    if where == "up":
        for i in range(1, len(out)):
            if out[i] in chosen and out[i - 1] not in chosen:
                out[i - 1], out[i] = out[i], out[i - 1]
    elif where == "down":
        for i in range(len(out) - 2, -1, -1):
            if out[i] in chosen and out[i + 1] not in chosen:
                out[i], out[i + 1] = out[i + 1], out[i]
    else:
        raise ValueError("unknown move " + where)
    return out


def tracker_host(url):
    host = urllib.parse.urlsplit(url).hostname or ""
    parts = host.split(".")
    return ".".join(parts[-2:]) if len(parts) > 2 else host


class RTorrentBackend:
    def __init__(self, address):
        self.rt = RTorrent(address)
        self.trackers = {}  # hash -> tracker host, cached: rarely changes
        self.lock = threading.Lock()
        self.jobs = Jobs()
        self.checks = CheckQueue()
        self.incoming = {"count": 0, "seen": 0, "checked": 0}
        self.last_pos = 0
        self.migrated = False

    def migrate_old_keys(self):
        """Mosaico (until 0.8) kept tags, options and position under other names, and named
        the speed limits of each torrent after itself: renamed once, the first time
        rTorrent answers. rTorrent 0.9.8 can't delete a key, so the old one is emptied."""
        old = list(OLD_KEYS)
        rows = self.rt.call("d.multicall2", "", "main", "d.hash=", "d.throttle_name=", "d.state=",
                            *["d.custom=" + k for k in old], *["d.custom=" + OLD_KEYS[k] for k in old])
        calls, throttled = [], []
        for row in rows:
            h, throttle, state = row[:3]
            olds, news = row[3:3 + len(old)], row[3 + len(old):]
            for k, ov, nv in zip(old, olds, news):
                if ov:
                    if not nv:
                        calls.append(("d.custom.set", [h, OLD_KEYS[k], ov]))
                    calls.append(("d.custom.set", [h, k, ""]))
            if throttle.startswith(OLD_THROTTLE):
                i = old.index("mosaico_opts")
                throttled.append((h, state, olds[i] or news[i]))
        if calls:
            self.rt.multicall(calls)
        for h, started, opts in throttled:
            # rTorrent only changes the throttle of a stopped torrent.
            o = parse_opts(opts)
            name = self._define_throttle(h, o) if o["down_limit"] or o["up_limit"] else ""
            if started:
                self.rt.call("d.stop", h)
            try:
                self.rt.call("d.throttle_name.set", h, name)
            finally:
                if started:
                    self.rt.call("d.start", h)
        if calls or throttled:
            print("renamed the old keys in %d torrents" % len({c[1][0] for c in calls} | {t[0] for t in throttled}),
                  file=sys.stderr)

    def _tags_of(self, label, tags):
        label = urllib.parse.unquote(label or "")
        tags = decode_tags(tags or "")
        # The label was changed elsewhere (ruTorrent): it replaces the
        # first tag, which is the one Tablero mirrors into d.custom1.
        if not tags or label != tags[0]:
            tags = ([label] if label else []) + [t for t in tags[1:] if t != label]
        return tags

    def list(self):
        if not self.migrated:
            with self.lock:
                if not self.migrated:
                    self.migrate_old_keys()
                    self.migrated = True
        fields = [f for _, f in TORRENT_FIELDS]
        rows = self.rt.call("d.multicall2", "", "main", *fields)
        out = []
        for row in rows:
            t = dict(zip([k for k, _ in TORRENT_FIELDS], row))
            t["tags"] = self._tags_of(t.pop("label"), t["tags"])
            t["opts"] = parse_opts(t["opts"])
            t["status"] = status_of(t)
            t["ratio"] = t["ratio"] / 1000.0
            t["eta"] = int((t["size"] - t["done"]) / t["down_rate"]) if t["down_rate"] else -1
            try:
                t["added"] = int(t.pop("addtime") or 0) or t["started"] or t["created"]
            except ValueError:
                t["added"] = t["started"] or t["created"]
            out.append(t)
        mark_no_space(out)
        self._fill_positions(out)
        self._fill_trackers(out)
        self.checks.annotate(out)
        self.jobs.annotate(out)
        self._count_incoming(out)
        g = self.rt.multicall([
            ("throttle.global_down.rate", []), ("throttle.global_up.rate", []),
            ("throttle.global_down.max_rate", []), ("throttle.global_up.max_rate", []),
            ("system.client_version", []), ("network.listen.port", []),
            (self.rt.port_range_method(), []),
        ])
        # A call this rTorrent does not know leaves its value empty, not the whole list.
        g = [None if isinstance(x, Exception) else x for x in g]
        stats = dict(zip(["down_rate", "up_rate", "down_limit", "up_limit", "version", "port"], g))
        # rTorrent listens only when its port opened (network.port_open is gone in 0.16).
        stats["port_open"] = 1 if g[5] else 0
        # After a port change the new port is in network.port_range, but rTorrent keeps
        # listening on the old one until it is restarted. A port inside the range is not a
        # change: with a range (6881-6999 and random ports by default in 0.16) rTorrent
        # listens on any port of it.
        first, last = port_range(g[6])
        stats["port_next"] = g[5] if first <= (g[5] or 0) <= last else first
        stats.update(incoming=self.incoming["count"], incoming_seen=self.incoming["seen"])
        return {"torrents": out, "stats": stats}

    def _count_incoming(self, torrents):
        """Peers that connected to us prove the port is reachable from outside.
        Counted every 30 seconds over the active torrents."""
        now = time.time()
        if now - self.incoming["checked"] < 30:
            return
        self.incoming["checked"] = now
        active = [t["hash"] for t in torrents if t["active"]]
        res = self.rt.multicall([("p.multicall", [h, "", "p.is_incoming="]) for h in active]) if active else []
        n = sum(sum(p[0] for p in r) for r in res if not isinstance(r, Exception))
        self.incoming["count"] = n
        if n:
            self.incoming["seen"] = int(now)

    def port_test(self):
        return test_port(self.rt.call("network.listen.port"))

    def set_port(self, port):
        """rTorrent opens its port only when it starts, and does not save it in the
        session: the new port is written to its configuration file and applies at the
        next start. Without write access, the result says which line to change."""
        port = check_port(port)
        pid = self.rt.call("system.pid")
        home = self.rt.call("system.env", "", "HOME")
        rc = rtorrent_rc(pid, home)
        cmd = self.rt.port_range_method() + ".set"
        res = {"port": port, "rc": rc, "line": "%s = %d-%d" % (cmd, port, port)}
        if rc and os.access(rc, os.W_OK):
            res["saved"] = set_port_in_rc(rc, port, cmd)
            self.rt.call(cmd, "", "%d-%d" % (port, port))
        return res

    def hashes(self):
        return self.rt.call("download_list")

    def _rc(self):
        if RTORRENT_RC:
            return RTORRENT_RC
        return rtorrent_rc(self.rt.call("system.pid"), self.rt.call("system.env", "", "HOME"))

    def session_files(self):
        """(session folder, rtorrent.rc, {hash: {".torrent": bytes, ...}}) after rTorrent
        has saved its session: each torrent's .torrent with its progress (.libtorrent_resume)
        and its fields (.rtorrent: folder, tags, state...)."""
        self.rt.call("session.save")
        sp = self.rt.call("session.path")
        if not sp:
            raise ValueError("rTorrent no guarda sesión (session.path vacío): no hay torrents que respaldar")
        out = {}
        try:
            for h in self.rt.call("download_list"):
                for ext in (".torrent", ".torrent.libtorrent_resume", ".torrent.rtorrent"):
                    try:
                        with open(os.path.join(sp, h + ext), "rb") as f:
                            out.setdefault(h, {})[ext] = f.read()
                    except FileNotFoundError:
                        pass
        except PermissionError:
            raise ValueError("No tengo permiso para leer la sesión de rTorrent en %s" % sp)
        return sp, self._rc(), out

    def restore_torrents(self, entries, old, new):
        """Adds the torrents of a backup that rTorrent doesn't have. Written into its session
        folder, they load at its next start with their progress (no check, if the files are
        unchanged), folder, tags and state. If that folder can't be written, they are loaded
        now instead, and checked."""
        have = set(self.rt.call("download_list"))
        todo = {h: e for h, e in entries.items() if h not in have}
        res = {"skipped": len(entries) - len(todo), "written": 0, "loaded": 0}
        sp = self.rt.call("session.path")
        for h, e in todo.items():
            meta = {}
            if ".torrent.rtorrent" in e:
                try:
                    meta, _ = _bdecode(e[".torrent.rtorrent"])
                except (ValueError, IndexError):
                    meta = {}
            if meta.get(b"directory"):
                d = meta[b"directory"].decode("utf-8", "surrogateescape")
                meta[b"directory"] = remap_path(d, old, new).encode("utf-8", "surrogateescape")
            rename_old_meta(meta)
            # Not tied to a .torrent file of the old computer (rTorrent may drop a torrent whose file is gone).
            for k in (b"tied_to_file", b"loaded_file"):
                if k in meta:
                    meta[k] = b""
            if sp and os.access(sp, os.W_OK):
                with open(os.path.join(sp, h + ".torrent"), "wb") as f:
                    f.write(e[".torrent"])
                if ".torrent.libtorrent_resume" in e:
                    with open(os.path.join(sp, h + ".torrent.libtorrent_resume"), "wb") as f:
                        f.write(e[".torrent.libtorrent_resume"])
                if meta:
                    with open(os.path.join(sp, h + ".torrent.rtorrent"), "wb") as f:
                        f.write(_bencode(meta))
                res["written"] += 1
            else:
                self._load_restored(h, e[".torrent"], meta)
                res["loaded"] += 1
        res["restart"] = res["written"] > 0
        return res

    def _load_restored(self, h, data, meta):
        self.rt.call("load.raw", "", xmlrpc.client.Binary(data))
        for _ in range(50):
            if h in self.rt.call("download_list"):
                break
            time.sleep(0.1)
        calls = []
        d = meta.get(b"directory", b"").decode("utf-8", "surrogateescape")
        if d:
            calls.append(("d.directory_base.set", [h, d]))
        for k, v in (meta.get(b"custom") or {}).items():
            calls.append(("d.custom.set", [h, k.decode("utf-8", "replace"), v.decode("utf-8", "replace")]))
        for i in range(1, 6):
            v = meta.get(b"custom%d" % i)
            if v:
                calls.append(("d.custom%d.set" % i, [h, v.decode("utf-8", "replace")]))
        if isinstance(meta.get(b"priority"), int):
            calls.append(("d.priority.set", [h, meta[b"priority"]]))
        calls.append(("d.check_hash", [h]))
        self.rt.multicall(calls)
        self._start_after_check(h, meta.get(b"state") == 1)

    def restore_rc(self, data):
        rc = self._rc() or os.path.join(self.rt.call("system.env", "", "HOME") or os.path.expanduser("~"), ".rtorrent.rc")
        try:
            if os.path.isfile(rc):
                shutil.copy(rc, rc + ".antes-de-tablero")
            with open(rc, "wb") as f:
                f.write(data)
        except OSError as e:
            raise ValueError("No pude escribir %s: %s" % (rc, e.strerror or e))
        return rc

    def _fill_positions(self, torrents):
        """Each torrent has a position (d.custom "tablero_pos"): the order in
        which it was added. Torrents from before Tablero get the next
        numbers, oldest first. When torrents are removed (here or in
        ruTorrent), the rest move up so the numbers stay 1..N."""
        for t in torrents:
            try:
                t["pos"] = int(t["pos"])
            except (TypeError, ValueError):
                t["pos"] = 0
        with self.lock:
            missing = sorted((t for t in torrents if not t["pos"]), key=lambda t: (t["added"], t["name"]))
            nxt = max([t["pos"] for t in torrents] + [self.last_pos]) + 1
            for t in missing:
                t["pos"] = nxt
                nxt += 1
            calls = [("d.custom.set", [t["hash"], POS_KEY, str(t["pos"])]) for t in missing]
            for n, t in enumerate(sorted(torrents, key=lambda t: t["pos"]), 1):
                if t["pos"] != n:
                    t["pos"] = n
                    calls.append(("d.custom.set", [t["hash"], POS_KEY, str(n)]))
            self.last_pos = len(torrents)
            if calls:
                self.rt.multicall(calls)

    def move_pos(self, hashes, where):
        torrents = self.list()["torrents"]  # also fills in and renumbers positions
        order = [t["hash"] for t in sorted(torrents, key=lambda t: t["pos"])]
        pos = {t["hash"]: t["pos"] for t in torrents}
        calls = [("d.custom.set", [h, POS_KEY, str(n)])
                 for n, h in enumerate(move_order(order, hashes, where), 1) if pos[h] != n]
        if calls:
            with self.lock:
                self.rt.multicall(calls)
        return {"moved": len(calls)}

    def _next_pos(self):
        with self.lock:
            used = self.rt.call("d.multicall2", "", "main", "d.custom=" + POS_KEY)
            nums = [int(r[0]) for r in used if r[0].isdigit()]
            self.last_pos = max(nums + [len(used) - 1, 0]) + 1
            return self.last_pos

    def _fill_trackers(self, torrents):
        missing = [t["hash"] for t in torrents if t["hash"] not in self.trackers]
        if missing:
            res = self.rt.multicall([("t.multicall", [h, "", "t.url="]) for h in missing])
            for h, r in zip(missing, res):
                urls = [] if isinstance(r, Exception) else [x[0] for x in r]
                urls = [u for u in urls if not u.startswith("dht://")]
                self.trackers[h] = tracker_host(urls[0]) if urls else ""
        for t in torrents:
            t["tracker"] = self.trackers.get(t["hash"], "")

    def details(self, h):
        res = self.rt.multicall([
            ("f.multicall", [h, "", "f.path=", "f.size_bytes=", "f.completed_chunks=",
                             "f.size_chunks=", "f.priority=", "f.range_first=",
                             "f.range_second="]),
            ("p.multicall", [h, "", "p.address=", "p.port=", "p.client_version=",
                             "p.completed_percent=", "p.down_rate=", "p.up_rate=",
                             "p.is_encrypted=", "p.is_incoming="]),
            ("t.multicall", [h, "", "t.url=", "t.is_enabled=", "t.scrape_complete=",
                             "t.scrape_incomplete=", "t.scrape_downloaded=", "t.type="]),
            ("d.directory", [h]), ("d.chunk_size", [h]), ("d.size_chunks", [h]),
            ("d.connection_current", [h]), ("d.bitfield", [h]), ("d.chunks_seen", [h]),
            ("d.name", [h]), ("d.is_multi_file", [h]), ("d.peers_complete", [h]),
            ("d.is_active", [h]),
        ])
        files, peers, trackers = res[:3]
        for r in (files, peers, trackers):
            if isinstance(r, Exception):
                raise r
        info = [None if isinstance(x, Exception) else x for x in res[3:]]
        return with_availability({
            "files": [{"path": f[0], "size": f[1],
                       "progress": (f[2] / f[3]) if f[3] else 1.0, "priority": f[4],
                       "range": [f[5], f[6]]}
                      for f in files],
            "peers": [{"address": p[0], "port": p[1], "client": p[2], "progress": p[3] / 100.0,
                       "down_rate": p[4], "up_rate": p[5], "encrypted": bool(p[6]),
                       "incoming": bool(p[7])} for p in peers],
            "trackers": [{"url": t[0], "enabled": bool(t[1]), "seeds": t[2], "leechers": t[3],
                          "downloaded": t[4]} for t in trackers],
            "base_path": data_location(info[0], info[6], info[7]) if info[0] and info[6] else None,
            "chunk_size": info[1], "chunks": info[2],
            "connection": info[3], "bitfield": info[4] or "",
            # Stopped or paused, rTorrent keeps d.chunks_seen as all zeros: no peers are
            # connected, so it is not known who has each piece ("Falta", not "Sin fuentes").
            "seen": info[5] if info[9] else "",
            "seen_complete": info[8] or 0,
        })

    def action(self, action, hashes, **kw):
        calls = []
        if action == "start":
            for h in hashes:
                self.jobs.resume(h)
            # A stopped torrent is stopped and closed again first: after a hash check that
            # found bad pieces, rTorrent ignores d.start until both have been done.
            states = self.rt.multicall([("d.state", [h]) for h in hashes])
            for h, st in zip(hashes, states):
                if st == 0:
                    calls += [("d.stop", [h]), ("d.close", [h])]
                calls += [("d.open", [h]), ("d.start", [h]), ("d.resume", [h])]
        elif action == "stop":
            # Stopped and closed, a torrent also leaves rTorrent's check queue (or its check).
            for h in hashes:
                self.jobs.stop(h)
                calls += [("d.stop", [h]), ("d.close", [h])]
        elif action == "pause":
            calls = [("d.pause", [h]) for h in hashes]
        elif action == "recheck":
            self.checks.asked(hashes)
            # rTorrent keeps the result of an old check in d.message even after a good one;
            # cleared first, a message after this check is about this check.
            calls = [c for h in hashes for c in (("d.message.set", [h, ""]), ("d.check_hash", [h]))]
        elif action == "priority":
            calls = [("d.priority.set", [h, int(kw["value"])]) for h in hashes]
        elif action == "remove":
            paths, folders = [], []
            if kw.get("data"):
                res = self.rt.multicall([c for h in hashes for c in (
                    ("d.directory", [h]), ("d.name", [h]), ("d.is_multi_file", [h]),
                    ("f.multicall", [h, "", "f.path="]))])
                for i in range(0, len(res), 4):
                    d, n, m, fl = res[i:i + 4]
                    if not (isinstance(d, str) and d and isinstance(n, str) and n) or isinstance(m, Exception):
                        continue
                    if not m:
                        paths.append(data_location(d, n, m))
                    elif isinstance(fl, list):
                        # Only the torrent's own files: its d.directory can be a shared folder
                        # (added with d.directory_base, e.g. ruTorrent's "do not add the name").
                        folders.append((d, [f[0] for f in fl if f and isinstance(f[0], str)]))
                for h in hashes:
                    calls += [("d.stop", [h]), ("d.close", [h])]
            calls += [("d.erase", [h]) for h in hashes]
            res = self.rt.multicall(calls)
            for p in paths:
                remove_path(p)
            for d, files in folders:
                remove_torrent_files(d, files)
            return res
        else:
            raise ValueError("unknown action " + action)
        return self.rt.multicall(calls)

    def set_tags(self, hashes, tags):
        tags = clean_tags(tags)
        first = urllib.parse.quote(tags[0], safe="") if tags else ""
        calls = []
        for h in hashes:
            calls += [("d.custom.set", [h, TAGS_KEY, encode_tags(tags)]),
                      ("d.custom1.set", [h, first])]
        return self.rt.multicall(calls)

    def edit_tags(self, hashes, add=(), remove=()):
        cur = self.rt.multicall(
            [m for h in hashes for m in (("d.custom", [h, TAGS_KEY]), ("d.custom1", [h]))])
        calls = []
        for i, h in enumerate(hashes):
            tags = self._tags_of(cur[2 * i + 1], cur[2 * i])
            tags = clean_tags([t for t in tags if t not in remove] + list(add))
            first = urllib.parse.quote(tags[0], safe="") if tags else ""
            calls += [("d.custom.set", [h, TAGS_KEY, encode_tags(tags)]),
                      ("d.custom1.set", [h, first])]
        return self.rt.multicall(calls)

    def rename_tag(self, old, new):
        data = self.list()["torrents"]
        hashes = [t["hash"] for t in data if old in t["tags"]]
        for t in data:
            if old in t["tags"]:
                tags = [new if x == old else x for x in t["tags"]] if new else \
                    [x for x in t["tags"] if x != old]
                self.set_tags([t["hash"]], tags)
        return len(hashes)

    def file_priority(self, h, indexes, prio):
        calls = [("f.priority.set", ["%s:f%d" % (h, i), int(prio)]) for i in indexes]
        calls.append(("d.update_priorities", [h]))
        return self.rt.multicall(calls)

    def add(self, magnet=None, data=None, directory="", tags=(), start=True, skip=()):
        dup = self.duplicate(magnet, data)
        if dup:
            return dup
        skip = sorted({int(i) for i in skip or ()})
        cmds = []
        if directory:
            cmds.append('d.directory.set="%s"' % directory.replace('"', '\\"'))
        tags = clean_tags(tags)
        if tags:
            # Quoted: the encoded list has commas, which separate arguments.
            cmds.append('d.custom.set=%s,"%s"' % (TAGS_KEY, encode_tags(tags)))
            cmds.append("d.custom1.set=%s" % urllib.parse.quote(tags[0], safe=""))
        cmds.append("d.custom.set=addtime,%d" % int(time.time()))
        if hasattr(self, "_next_pos"):
            cmds.append("d.custom.set=%s,%d" % (POS_KEY, self._next_pos()))
        if data is not None and skip:
            # Files left out: loaded stopped, those files set to "No bajar", then started.
            h = torrent_files(data)["hash"]
            self.rt.call("load.raw", "", xmlrpc.client.Binary(data), *cmds)
            for _ in range(50):
                if h in set(self.rt.call("download_list", "")):
                    break
                time.sleep(0.1)
            else:
                raise ValueError("rTorrent no cargó el torrent")
            self.rt.multicall([("f.priority.set", ["%s:f%d" % (h, i), 0]) for i in skip] +
                              [("d.update_priorities", [h])] + ([("d.start", [h])] if start else []))
            return {"hash": h, "skipped": len(skip)}
        if data is not None:
            method = "load.raw_start" if start else "load.raw"
            return self.rt.call(method, "", xmlrpc.client.Binary(data), *cmds)
        method = "load.start" if start else "load.normal"
        return self.rt.call(method, "", magnet, *cmds)

    def default_dir(self):
        return self.rt.call("directory.default")

    def duplicate(self, magnet=None, data=None):
        """rTorrent silently ignores a torrent it already has: say so, with the trackers
        the new file or magnet would bring."""
        ident = torrent_identity(data, magnet)
        if not ident:
            return None
        h = ident[0]
        if h not in set(self.rt.call("download_list", "")):
            return None
        name, have = self.rt.multicall([("d.name", [h]), ("t.multicall", [h, "", "t.url="])])
        have = {u[0] for u in have}
        return {"duplicate": {"hash": h, "name": name,
                              "trackers": [u for u in ident[2] if u not in have and tracker_url_ok(u)]}}

    def add_trackers(self, h, urls):
        urls = [u.strip() for u in urls if u.strip()]
        bad = [u for u in urls if not tracker_url_ok(u)]
        if bad:
            raise ValueError("Dirección de rastreador no válida: %s" % bad[0])
        rows = self.rt.call("t.multicall", h, "", "t.url=", "t.group=")
        have = {u for u, _ in rows}
        group = max([g for _, g in rows] + [-1]) + 1
        new = [u for u in dict.fromkeys(urls) if u not in have]
        # Each in a group of its own after the existing ones, so they are all announced to.
        self.rt.multicall([("d.tracker.insert", [h, str(group + n), u]) for n, u in enumerate(new)])
        return {"added": len(new)}

    def data_path(self, h, index=None, sub=None):
        """Where a torrent's data is (or one of its files), also while it is closed."""
        directory, name, multi = self.rt.multicall([
            ("d.directory", [h]), ("d.name", [h]), ("d.is_multi_file", [h])])
        path = data_location(directory, name, multi)
        if index is not None and multi:
            path = os.path.join(path, self.rt.call("f.path", "%s:f%d" % (h, int(index))))
        elif sub and multi:
            # A folder inside the torrent, as the Archivos tree names it; never above the torrent's folder.
            parts = [p for p in str(sub).split("/") if p not in ("", ".")]
            if ".." in parts:
                raise ValueError("Carpeta no válida")
            path = os.path.join(path, *parts)
        return path

    def open_folder(self, h, index=None, sub=None):
        path = self.data_path(h, index, sub)
        if PLATFORM == "android":
            # No file manager to start from here: the Android app opens it (window.TableroAndroid).
            folder = path if os.path.isdir(path) else os.path.dirname(path)
            return {"folder": folder, "path": path, "android": True}
        return open_in_file_manager(path)

    @staticmethod
    def throttle_of(h):
        return "tablero_" + h[:12].lower()

    def _define_throttle(self, h, opts):
        name = self.throttle_of(h)
        # Named throttles take KiB/s; 0 would mean no limit.
        self.rt.multicall([("throttle.down", ["", name, str(opts["down_limit"] // 1024)]),
                           ("throttle.up", ["", name, str(opts["up_limit"] // 1024)])])
        return name

    def set_options(self, h, o):
        opts = clean_opts(o)
        calls = [("d.custom.set", [h, OPTS_KEY, json.dumps(opts)])]
        if "priority" in o:
            calls.append(("d.priority.set", [h, max(0, min(3, int(o["priority"])))]))
        for k in ("peers_max", "peers_min", "uploads_max"):
            if o.get(k) is not None:
                calls.append(("d.%s.set" % k, [h, max(0, int(o[k]))]))
        self.rt.multicall(calls)
        limited = opts["down_limit"] or opts["up_limit"]
        want = self._define_throttle(h, opts) if limited else ""
        if self.rt.call("d.throttle_name", h) != want:
            # rTorrent only changes the throttle of a stopped torrent.
            started = self.rt.call("d.state", h)
            if started:
                self.rt.call("d.stop", h)
            try:
                self.rt.call("d.throttle_name.set", h, want)
            finally:
                if started:
                    self.rt.call("d.start", h)

    def instance(self):
        return self.rt.call("system.pid")

    def restore_options(self, torrents):
        """Named throttles live only in memory: define them again after
        rTorrent or Tablero restarts."""
        for t in torrents:
            o = t["opts"]
            if o.get("down_limit") or o.get("up_limit"):
                self._define_throttle(t["hash"], o)

    def mark_rule_done(self, h, opts):
        opts = dict(opts, rule_done=True)
        self.rt.call("d.custom.set", h, OPTS_KEY, json.dumps(opts))

    def relocate(self, hashes, target, move, check):
        """Moves a torrent's data to another folder, or points the torrent to
        where its data already is. Runs in the background: moving between
        disks can take long. The torrent is stopped meanwhile."""
        target = check_target(target)
        if not move and not os.path.isdir(target):
            raise ValueError("La carpeta %s no existe" % target)
        # One torrent at a time: the others wait their turn.
        for h in hashes:
            self.jobs.set(h, "waiting")
        threading.Thread(target=self._relocate_all, args=(list(hashes), target, move, check),
                         daemon=True).start()

    def _relocate_all(self, hashes, target, move, check):
        for h in hashes:
            if not self.jobs.take(h):
                continue  # stopped while waiting its turn
            try:
                self._relocate(h, target, move, check)
                self.jobs.done(h)
            except Exception as e:
                self.jobs.set(h, "error", str(e))

    def _relocate(self, h, target, move, check):
        name, directory, multi, started, first, first_size = self.rt.multicall([
            ("d.name", [h]), ("d.directory", [h]), ("d.is_multi_file", [h]), ("d.state", [h]),
            ("f.path", [h + ":f0"]), ("f.size_bytes", [h + ":f0"])])
        base = data_location(directory, name, multi)
        if not move:
            # Found before stopping: a torrent whose data isn't there keeps running as it was.
            found = find_data(target, name, multi, first, first_size, base)
            if not found:
                raise ValueError("No encontré los archivos de %s en %s ni en sus subcarpetas" % (name, target))
        self.jobs.set(h, "moving" if move else "checking")
        self.rt.multicall([("d.stop", [h]), ("d.close", [h])])
        try:
            if move:
                if multi:
                    self._check_own_folder(h, base, name)
                dest = os.path.join(target, name)
                if os.path.realpath(base) == os.path.realpath(dest):
                    raise ValueError("Los datos ya están en esa carpeta")
                if os.path.exists(dest):
                    raise ValueError("Ya existe %s; no se sobrescribe" % dest)
                os.makedirs(target, exist_ok=True)
                # The new folder is set and saved before moving, which can take long: if
                # rTorrent is closed meanwhile, it still looks for the data where it ends up.
                self.rt.call("d.directory.set", h, target)
                self.rt.call("d.save_full_session", h)
                try:
                    if os.path.exists(base):
                        shutil.move(base, dest)
                except Exception:
                    try:
                        self.rt.call("d.directory.set", h, os.path.dirname(base))
                        self.rt.call("d.save_full_session", h)
                    except (OSError, xmlrpc.client.Fault):
                        pass
                    raise
            elif multi:
                # The torrent's own folder, as found (it may be named otherwise than the torrent).
                self.rt.call("d.directory_base.set", h, found)
            else:
                self.rt.call("d.directory.set", h, os.path.dirname(found))
            self.rt.call("d.save_full_session", h)
            if check or not move:
                # rTorrent checks one torrent at a time and queues the rest itself: the next
                # torrent is relocated meanwhile, and this one resumes after its check.
                self.rt.call("d.check_hash", h)
                self._start_after_check(h, started)
                started = False
        finally:
            if started and self.jobs.may_start(h):
                self.rt.multicall([("d.open", [h]), ("d.start", [h])])

    def _start_after_check(self, h, started):
        if not started:
            return

        def run():
            try:
                time.sleep(2)
                while self.rt.call("d.hashing", h):
                    time.sleep(2)
                if self.jobs.may_start(h):  # not if stopped meanwhile (which ends the check)
                    self.rt.multicall([("d.open", [h]), ("d.start", [h])])
            except (OSError, xmlrpc.client.Fault):
                pass
        threading.Thread(target=run, daemon=True).start()

    def _check_own_folder(self, h, folder, name):
        """A multi-file torrent added with d.directory_base (ruTorrent's "do not add the
        name") can have a shared folder as its own: moving or renaming that folder would
        take other torrents' data along. Refused when the folder is not named as the torrent
        and holds more than the torrent's files."""
        if os.path.basename(folder.rstrip("/")) == name or not os.path.isdir(folder):
            return
        own = {f[0].split("/", 1)[0] for f in self.rt.call("f.multicall", h, "", "f.path=") if f and f[0]}
        others = sorted(set(os.listdir(folder)) - own)
        if others:
            raise ValueError("La carpeta %s no es solo de este torrent (también tiene %s%s); "
                             "no la muevo ni le cambio el nombre" % (folder, others[0], " y otros" if len(others) > 1 else ""))

    def rename_folder(self, h, new):
        """Renames the torrent's own folder (multi-file torrents) on disk and points the
        torrent to it. The files inside stay the same, so no check is needed. The torrent
        is stopped and closed meanwhile; the name shown in the list stays the .torrent's."""
        new = check_folder_name(new)
        directory, name, multi, started = self.rt.multicall([
            ("d.directory", [h]), ("d.name", [h]), ("d.is_multi_file", [h]), ("d.state", [h])])
        if not multi:
            raise ValueError("Este torrent es un solo archivo, sin carpeta propia")
        old = data_location(directory, name, multi)
        dest = os.path.join(os.path.dirname(old), new)
        if dest == old:
            raise ValueError("La carpeta ya tiene ese nombre")
        if os.path.lexists(dest):
            raise ValueError("Ya existe %s; no se sobrescribe" % dest)
        self._check_own_folder(h, old, name)
        self.rt.multicall([("d.stop", [h]), ("d.close", [h])])
        try:
            if os.path.exists(old):
                try:
                    os.rename(old, dest)
                except OSError as e:
                    raise ValueError("No pude renombrar %s: %s" % (old, e.strerror or e))
            self.rt.call("d.directory_base.set", h, dest)
            self.rt.call("d.save_full_session", h)
        finally:
            if started:
                self.rt.multicall([("d.open", [h]), ("d.start", [h])])
        return {"folder": dest}

    def shutdown(self):
        # A normal shutdown: rTorrent saves its session and tells the trackers before quitting.
        try:
            session = self.rt.call("session.path")
        except Exception:  # noqa: BLE001 - only for the cleanup below
            session = ""
        try:
            self.rt.call("system.shutdown.normal")
        except ConnectionError:
            pass  # rTorrent ya se está apagando: no contesta a esta llamada
        if session:
            threading.Thread(target=self._clean_session, args=(session,), daemon=True).start()
        return {}

    def _clean_session(self, session):
        """rTorrent can't delete a key of a torrent, so the emptied keys of Mosaico (until
        0.8) stay in its session. Once it has quit, they are taken out of the files."""
        for _ in range(120):
            time.sleep(0.5)
            try:
                self.rt.call("system.pid")
            except Exception:  # noqa: BLE001 - not answering: it quit
                break
        else:
            return
        for _ in range(20):  # the lock file goes last
            if not os.path.exists(os.path.join(session, "rtorrent.lock")):
                break
            time.sleep(0.5)
        else:
            return
        if not os.access(session, os.W_OK):
            return
        cleaned = 0
        for name in os.listdir(session):
            if not name.endswith(".torrent.rtorrent"):
                continue
            path = os.path.join(session, name)
            try:
                with open(path, "rb") as f:
                    meta, _ = _bdecode(f.read())
                custom = meta.get(b"custom")
                if not isinstance(custom, dict):
                    continue
                old = [k for k in custom if k.decode("utf-8", "replace") in OLD_KEYS and not custom[k]]
                if not old:
                    continue
                for k in old:
                    del custom[k]
                with open(path + ".tmp", "wb") as f:
                    f.write(_bencode(meta))
                os.replace(path + ".tmp", path)
                cleaned += 1
            except (OSError, ValueError, IndexError):
                continue
        if cleaned:
            print("took the old keys out of %d session files" % cleaned, file=sys.stderr)

    def limits(self, down=None, up=None):
        calls = []
        if down is not None:
            calls.append(("throttle.global_down.max_rate.set", ["", int(down)]))
        if up is not None:
            calls.append(("throttle.global_up.max_rate.set", ["", int(up)]))
        return self.rt.multicall(calls)


def with_availability(d):
    """Availability (distributed copies), as BiglyBT shows it: the fewest
    copies of any piece among the connected peers, plus the fraction of
    pieces with more copies than that. Comes from d.chunks_seen (two hex
    digits per piece: how many connected peers have it). Without it, an
    estimate from the peers' completion."""
    seen = d.get("seen")
    extra = d.pop("seen_complete", 0) or 0
    if isinstance(seen, str) and seen:
        # rTorrent counts connected seeds apart (d.peers_complete): they are not in
        # d.chunks_seen, which only tallies partial peers. Without adding them, a piece
        # only seeds have showed as "Sin fuentes" next to peers at 100 %.
        counts = [min(255, int(seen[i:i + 2], 16) + extra) for i in range(0, len(seen), 2)]
        if extra:
            d["seen"] = "".join("%02X" % c for c in counts)
        low = min(counts)
        d["availability"] = low + sum(1 for c in counts if c > low) / len(counts)
        d["availability_estimated"] = False
        return d
    d["seen"] = ""
    seeds = sum(1 for p in d["peers"] if p["progress"] >= 1)
    d["availability"] = seeds + sum(p["progress"] for p in d["peers"] if p["progress"] < 1)
    d["availability_estimated"] = True
    return d


class History:
    """Speed samples for the last hour, global and per torrent, taken in
    the background so the chart has data as soon as it opens."""

    STEP = 2
    KEEP = 3600 // STEP

    def __init__(self, backend):
        self.backend = backend
        self.lock = threading.Lock()
        self.total = collections.deque(maxlen=self.KEEP)
        self.torrents = {}
        self.restored = False
        if hasattr(backend, "backfill"):
            backend.backfill(self)
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while True:
            try:
                self._sample()
            except Exception as e:  # rTorrent down: retry next round
                print("historial: %s" % e, file=sys.stderr)
            time.sleep(self.STEP)

    def _sample(self):
        data = self.backend.list()
        now = int(time.time())
        # Named throttles live in rTorrent's memory: defined again whenever rTorrent is a new
        # process (it was restarted, e.g. with Apagar rTorrent), not only when Tablero starts.
        instance = self.backend.instance()
        if self.restored != instance:
            self.backend.restore_options(data["torrents"])
            self.restored = instance
        for t in data["torrents"]:
            if rule_reached(t, t["opts"], now):
                self.backend.action("stop", [t["hash"]])
                self.backend.mark_rule_done(t["hash"], t["opts"])
                print("%s: detenido por su regla de siembra" % t["name"], file=sys.stderr)
        st = data["stats"]
        with self.lock:
            self.total.append((now, st["down_rate"], st["up_rate"],
                               st["down_limit"], st["up_limit"]))
            alive = set()
            for t in data["torrents"]:
                alive.add(t["hash"])
                q = self.torrents.setdefault(t["hash"], collections.deque(maxlen=self.KEEP))
                q.append((now, t["down_rate"], t["up_rate"]))
            for h in list(self.torrents):
                if h not in alive:
                    del self.torrents[h]

    def get(self, h=None):
        with self.lock:
            if h:
                return {"step": self.STEP, "samples": list(self.torrents.get(h, ()))}
            return {"step": self.STEP, "samples": list(self.total)}


class Jobs:
    """Data moves running in the background, by torrent hash. A finished
    job is dropped; a failed one is kept a minute so the UI can show why."""

    def __init__(self):
        self.lock = threading.Lock()
        self.items = {}
        self.seq = 0
        self.stopped = set()  # stopped (Detener) during a relocation or while waiting for its check

    def set(self, h, state, error=""):
        with self.lock:
            self.seq += 1
            self.items[h] = {"state": state, "error": error, "time": time.time(), "seq": self.seq}

    def done(self, h):
        with self.lock:
            self.items.pop(h, None)

    def stop(self, h):
        """Detener: a relocation still waiting its turn is called off, and a torrent being
        relocated or waiting for the check after it is not started again at the end."""
        with self.lock:
            j = self.items.get(h)
            if j and j["state"] == "waiting":
                del self.items[h]
            self.stopped.add(h)

    def resume(self, h):
        """Iniciar: started again by hand, it may be started after its check too."""
        with self.lock:
            self.stopped.discard(h)

    def take(self, h):
        """At a torrent's turn to be relocated: False if it was called off meanwhile."""
        with self.lock:
            j = self.items.get(h)
            if not j or j["state"] != "waiting":
                return False
            self.stopped.discard(h)
            return True

    def may_start(self, h):
        with self.lock:
            return h not in self.stopped

    def annotate(self, torrents):
        with self.lock:
            now = time.time()
            for h in [h for h, j in self.items.items() if j["state"] == "error" and now - j["time"] > 60]:
                del self.items[h]
            waiting = []
            for t in torrents:
                j = self.items.get(t["hash"])
                if j:
                    t["job"] = {"state": j["state"], "error": j["error"]}
                    if j["state"] in ("moving", "waiting"):
                        t["status"] = j["state"]
                    if j["state"] == "waiting":
                        waiting.append((j["seq"], t))
        # "En espera de reubicación 1, 2…", in the order they will be moved.
        for n, (_, t) in enumerate(sorted(waiting, key=lambda x: x[0]), 1):
            t["queue_pos"] = n


def free_space(path):
    """Free bytes on the disk of a folder, or of its nearest existing parent folder."""
    path = os.path.realpath(os.path.expanduser(path or "~"))
    while not os.path.isdir(path):
        path = os.path.dirname(path)
    try:
        st = os.statvfs(path)
    except OSError:
        return None
    return {"path": path, "free": st.f_bavail * st.f_frsize}


def mark_no_space(torrents):
    """rTorrent leaves a torrent it could not open for lack of space started and inactive,
    with no message (on exFAT, which cannot leave room for a file without writing it):
    a paused, unfinished torrent whose disk has less free space than it still needs is
    shown as an error saying so."""
    free = {}
    for t in torrents:
        if t["status"] != "paused" or t["complete"]:
            continue
        d = t["directory"]
        if d not in free:
            free[d] = free_space(d)
        need = t["size"] - t["done"]
        if free[d] is not None and free[d]["free"] < need:
            t["status"] = "error"
            t["message"] = "Sin espacio en el disco"
            t["nospace"] = {"need": need, "free": free[d]["free"], "path": free[d]["path"]}


def list_dirs(path):
    """Subfolders of a folder on this computer, for the folder picker."""
    path = os.path.realpath(os.path.expanduser(path or user_files()))
    while not os.path.isdir(path):
        path = os.path.dirname(path)
    try:
        names = sorted((e.name for e in os.scandir(path)
                        if e.is_dir() and not e.name.startswith(".")), key=str.lower)
    except OSError:
        names = []
    parent = os.path.dirname(path)
    return {"path": path, "parent": parent if parent != path else None, "dirs": names}


DESKTOP_VARS = ("DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_CURRENT_DESKTOP", "XDG_SESSION_TYPE",
                "DESKTOP_SESSION", "DBUS_SESSION_BUS_ADDRESS", "XDG_RUNTIME_DIR", "XDG_DATA_DIRS")


def desktop_env():
    """The environment for opening a desktop program. Started as a service at login,
    Tablero may lack the desktop's variables (DISPLAY...), and then xdg-open opens
    nothing: they are taken from another process of this user that has them."""
    env = dict(os.environ)
    if env.get("DISPLAY") or env.get("WAYLAND_DISPLAY") or not os.path.isdir("/proc"):
        return env
    uid = os.getuid()
    for pid in sorted((p for p in os.listdir("/proc") if p.isdigit()), key=int, reverse=True):
        try:
            if os.stat("/proc/" + pid).st_uid != uid:
                continue
            with open("/proc/%s/environ" % pid, "rb") as f:
                pairs = dict(x.split(b"=", 1) for x in f.read().split(b"\0") if b"=" in x)
        except (OSError, ValueError):
            continue
        if pairs.get(b"DISPLAY") or pairs.get(b"WAYLAND_DISPLAY"):
            for k in DESKTOP_VARS:
                v = pairs.get(k.encode())
                if v:
                    env[k] = v.decode("utf-8", "replace")
            break
    return env


def open_in_file_manager(path):
    """Shows a folder in the desktop's file manager, on the computer running Tablero."""
    folder = path if os.path.isdir(path) else os.path.dirname(path)
    if not os.path.isdir(folder):
        raise ValueError("La carpeta %s no existe" % folder)
    if sys.platform == "win32":
        os.startfile(folder)  # noqa: only on Windows
        return {"folder": folder}
    opener = shutil.which("open" if sys.platform == "darwin" else "xdg-open")
    if not opener:
        raise ValueError("No encontré xdg-open para abrir el explorador de archivos")
    env = desktop_env()
    if sys.platform != "darwin" and not (env.get("DISPLAY") or env.get("WAYLAND_DISPLAY")):
        raise ValueError("No encontré la sesión de escritorio para abrir el explorador de archivos")
    p = subprocess.Popen([opener, folder], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                         stderr=subprocess.PIPE, start_new_session=True, env=env)
    # xdg-open fails right away when it can't open anything; when the file manager
    # takes longer to start, it is working.
    try:
        _, err = p.communicate(timeout=3)
    except subprocess.TimeoutExpired:
        threading.Thread(target=p.communicate, daemon=True).start()
        return {"folder": folder}
    if p.returncode:
        msg = err.decode("utf-8", "replace").strip().splitlines()
        raise ValueError("xdg-open no pudo abrir la carpeta%s" % (": " + msg[-1] if msg else ""))
    return {"folder": folder}


def read_torrent_file(path):
    """A .torrent on this computer, for files dragged in as file:// addresses."""
    path = os.path.realpath(os.path.expanduser(path))
    if not path.lower().endswith(".torrent") or not os.path.isfile(path):
        raise ValueError("No encontré el archivo %s" % path)
    if os.path.getsize(path) > 20 * 1024 * 1024:
        raise ValueError("El archivo %s es demasiado grande para ser un .torrent" % path)
    with open(path, "rb") as f:
        return f.read()


def _bdecode(data, i=0):
    """One bencoded value at data[i:]: (value, end). Byte strings stay bytes."""
    c = data[i:i + 1]
    if c == b"i":
        end = data.index(b"e", i)
        return int(data[i + 1:end]), end + 1
    if c == b"l":
        out, i = [], i + 1
        while data[i:i + 1] != b"e":
            v, i = _bdecode(data, i)
            out.append(v)
        return out, i + 1
    if c == b"d":
        out, i = {}, i + 1
        while data[i:i + 1] != b"e":
            k, i = _bdecode(data, i)
            start = i
            v, i = _bdecode(data, i)
            out[k] = v
            if k == b"info":
                out[b"_info_span"] = (start, i)
        return out, i + 1
    colon = data.index(b":", i)
    n = int(data[i:colon])
    return data[colon + 1:colon + 1 + n], colon + 1 + n


def tracker_url_ok(url):
    return re.match(r"^(https?|udp)://[^\s/]+", url or "") is not None


def torrent_identity(data=None, magnet=None):
    """(info hash in upper hex, name, tracker URLs) of a .torrent or a magnet; None if unreadable."""
    try:
        if data is not None:
            meta, _ = _bdecode(data)
            a, b = meta[b"_info_span"]
            urls = [meta.get(b"announce", b"")] + [u for tier in meta.get(b"announce-list", []) for u in tier]
            name = meta[b"info"].get(b"name", b"").decode("utf-8", "replace")
            return (hashlib.sha1(data[a:b]).hexdigest().upper(), name,
                    list(dict.fromkeys(u.decode("utf-8", "replace") for u in urls if u)))
        q = urllib.parse.parse_qs(urllib.parse.urlsplit(magnet).query)
        xt = next(x for x in q.get("xt", []) if x.lower().startswith("urn:btih:"))[9:]
        h = base64.b32decode(xt.upper()).hex() if len(xt) == 32 else xt
        return h.upper(), q.get("dn", [""])[0], list(dict.fromkeys(q.get("tr", [])))
    except (ValueError, KeyError, IndexError, TypeError, StopIteration, AttributeError):
        return None


def find_data(target, name, multi, first, first_size, current):
    """Where a torrent's data is in or under target, for "Los archivos ya están": the file
    (single-file torrents) or the torrent's own folder, named like the torrent or not (one
    renamed, or the target itself), told by its first file and that file's size. None if
    it isn't there."""
    if not multi:
        path = os.path.join(target, name)
        return path if os.path.isfile(path) else None

    def holds(folder, sized):
        f = os.path.join(folder, first)
        try:
            return os.path.isfile(f) and (not sized or os.path.getsize(f) == first_size)
        except OSError:
            return False
    named = os.path.join(target, name)
    candidates = [named, target, os.path.join(target, os.path.basename(current))]
    try:
        # Folders whose name contains the torrent's first: "Disco (2024) [FLAC]" for "Disco".
        key = name.lower()
        candidates += sorted((e.path for e in os.scandir(target) if e.is_dir()),
                             key=lambda p: (key not in os.path.basename(p).lower(), p))
    except OSError:
        pass
    # The size tells apart folders holding a file of the same name; a partly downloaded
    # file may be smaller, so then the name alone.
    for sized in (True, False):
        for c in dict.fromkeys(candidates):
            if holds(c, sized):
                return c
    return named if os.path.isdir(named) else None


def data_location(directory, name, multi):
    """Where a torrent's data is: its own folder (multi-file) or its file. Taken from
    d.directory, not d.base_path: d.base_path is empty while the torrent is closed, and
    keeps the old place after d.directory.set until rTorrent opens the torrent again
    (a torrent closed during a check, or waiting in the check queue, can stay like that)."""
    directory = directory.rstrip("/") or "/"
    return directory if multi else os.path.join(directory, name)


def torrent_files(data):
    """Name, info hash and files of a .torrent, in rTorrent's order (f0, f1...)."""
    ident = torrent_identity(data)
    if not ident:
        raise ValueError("No es un archivo .torrent válido")
    info = _bdecode(data)[0][b"info"]
    text = lambda b: b.decode("utf-8", "replace")
    if b"files" in info:
        files = [{"path": "/".join(text(x) for x in f.get(b"path.utf-8", f.get(b"path", []))),
                  "size": int(f.get(b"length", 0)), "pad": b"p" in f.get(b"attr", b"")}
                 for f in info[b"files"]]
    else:
        files = [{"path": ident[1], "size": int(info.get(b"length", 0)), "pad": False}]
    return {"hash": ident[0], "name": ident[1], "files": files}


def test_port(port):
    """Asks an outside service (ifconfig.co) whether it can connect to this computer's
    public address on the port. Only the port number is sent."""
    req = urllib.request.Request("https://ifconfig.co/port/%d" % int(port),
                                 headers={"Accept": "application/json", "User-Agent": "Tablero/" + VERSION})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except (OSError, ValueError) as e:
        raise ValueError("No pude hacer la prueba con ifconfig.co: %s" % getattr(e, "reason", e))
    return {"port": int(port), "reachable": bool(data.get("reachable")), "ip": data.get("ip", "")}


def port_range(r):
    try:
        first, _, last = str(r).partition("-")
        first = int(first)
        return first, int(last or first)
    except ValueError:
        return 0, 0


def range_start(r):
    try:
        return int(str(r).split("-")[0])
    except ValueError:
        return 0


def check_port(port):
    try:
        port = int(port)
    except (TypeError, ValueError):
        raise ValueError("Escribe un número de puerto")
    if not 1024 <= port <= 65535:
        raise ValueError("El puerto debe estar entre 1024 y 65535")
    return port


PORT_LINE = re.compile(r"^(\s*)(network\.listen\.port\.range\.set|network\.port_range\.set|port_range)(\s*=\s*)(\S+)(.*)$", re.M)


def rtorrent_rc(pid, home):
    """The configuration file rTorrent read when it started: the one given with
    -o import=, or the usual ~/.rtorrent.rc (or ~/.config/rtorrent/rtorrent.rc)."""
    try:
        with open("/proc/%d/cmdline" % int(pid), "rb") as f:
            args = [a.decode("utf-8", "replace") for a in f.read().split(b"\0") if a]
    except (OSError, ValueError):
        args = []
    for a in args:
        for part in a.split(","):
            m = re.match(r"\s*(try_)?import\s*=\s*(.+)", part)
            if m:
                path = os.path.expanduser(m.group(2).strip().strip("\"'"))
                return path if os.path.isabs(path) else os.path.join(home or "/", path)
    if "-n" in args or not home:
        return None
    for path in (os.path.join(home, ".rtorrent.rc"), os.path.join(home, ".config", "rtorrent", "rtorrent.rc")):
        if os.path.isfile(path):
            return path
    return None


def set_port_in_rc(rc, port, cmd="network.port_range.set"):
    """Changes the port line (or adds one) and keeps a copy of the file as it was."""
    with open(rc, encoding="utf-8", errors="surrogateescape") as f:
        text = f.read()
    with open(rc + ".antes-de-tablero", "w", encoding="utf-8", errors="surrogateescape") as f:
        f.write(text)
    new, n = PORT_LINE.subn(lambda m: "%s%s%s%d-%d%s" % (m.group(1), cmd, m.group(3), port, port, m.group(5)), text)
    if not n:
        new = text + ("" if text.endswith("\n") or not text else "\n") + \
            "# Puerto de entrada (lo cambió Tablero)\n%s = %d-%d\n" % (cmd, port, port)
    with open(rc, "w", encoding="utf-8", errors="surrogateescape") as f:
        f.write(new)
    return True


def local_ip():
    """This computer's address on the local network (no packet is sent)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))
            return s.getsockname()[0]
    except OSError:
        return ""


def check_folder_name(name):
    name = (name or "").strip()
    if not name or name in (".", "..") or "/" in name or "\0" in name:
        raise ValueError("Escribe un nombre de carpeta válido, sin /")
    if len(name.encode("utf-8")) > 255:
        raise ValueError("El nombre es demasiado largo")
    return name


def check_target(target):
    target = os.path.expanduser((target or "").strip())
    if not os.path.isabs(target):
        raise ValueError("Escribe la ruta completa de la carpeta (por ejemplo %s)" % (
            "/storage/emulated/0/Download/torrents" if PLATFORM == "android" else "/home/usuario/Torrents"))
    return os.path.realpath(target)


def remove_path(path):
    path = os.path.realpath(path)
    if path in ("/", os.path.expanduser("~")) or path.count("/") < 2:
        print("refusing to delete %s" % path, file=sys.stderr)
        return
    try:
        if os.path.isdir(path):
            shutil.rmtree(path)
        elif os.path.exists(path):
            os.remove(path)
    except OSError as e:
        print("could not delete %s: %s" % (path, e), file=sys.stderr)


def remove_torrent_files(folder, files):
    """Deletes a multi-file torrent's files (paths relative to its folder), then the
    folders left empty, the torrent's folder last. Anything else in them stays."""
    folder = os.path.realpath(folder)
    if folder in ("/", os.path.expanduser("~")) or folder.count("/") < 2:
        print("refusing to delete in %s" % folder, file=sys.stderr)
        return
    dirs = set()
    for rel in files:
        parts = [p for p in rel.split("/") if p not in ("", ".")]
        if not parts or ".." in parts:
            continue
        full = os.path.join(folder, *parts)
        if not os.path.realpath(full).startswith(folder + os.sep):
            continue  # a link that leads out of the torrent's folder
        d = os.path.dirname(full)
        while d != folder and d.startswith(folder + os.sep):
            dirs.add(d)
            d = os.path.dirname(d)
        try:
            if os.path.isfile(full) or os.path.islink(full):
                os.remove(full)
        except OSError as e:
            print("could not delete %s: %s" % (full, e), file=sys.stderr)
    for d in sorted(dirs, key=len, reverse=True) + [folder]:
        try:
            os.rmdir(d)
        except OSError:
            pass  # not empty: holds files that are not the torrent's


# ---------------------------------------------------------------- demo

class DemoBackend:
    """In-memory fake engine with the same interface, for trying the UI."""

    NAMES = [
        ("Big Buck Bunny 2160p HDR10 Blu-ray Remux", ["Blu-ray UHD", "Películas"], 61e9),
        ("Sintel 2010 UHD Blu-ray HEVC", ["Blu-ray UHD", "Películas"], 48e9),
        ("Tears of Steel 4K Remux", ["Blu-ray UHD", "Películas", "Ciencia ficción"], 39e9),
        ("Night of the Living Dead 1968 1080p", ["Películas", "Clásicos"], 12e9),
        ("Metropolis 1927 Restored 1080p", ["Películas", "Clásicos"], 9.8e9),
        ("Nosferatu 1922 Blu-ray", ["Películas", "Clásicos"], 21e9),
        ("Beethoven - Symphonies 1-9 (24-96) FLAC", ["FLAC", "Música", "Clásica"], 4.1e9),
        ("Bach - Goldberg Variations FLAC", ["FLAC", "Música", "Clásica"], 0.9e9),
        ("Musopen - Chopin Nocturnes FLAC", ["FLAC", "Música"], 0.7e9),
        ("Free Music Archive - Jazz Sampler 2024 FLAC", ["FLAC", "Música", "Jazz"], 1.2e9),
        ("Kevin MacLeod - Complete Collection MP3", ["Música"], 6.5e9),
        ("Cosmos Laundromat 2015 2160p", ["Películas", "Animación"], 14e9),
        ("Spring 2019 Open Movie 4K", ["Animación", "Blu-ray UHD"], 8e9),
        ("Agent 327 Operation Barbershop", ["Animación"], 2.2e9),
        ("linuxmint-22.2-cinnamon-64bit.iso", ["Linux"], 2.9e9),
        ("debian-13.1.0-amd64-DVD-1.iso", ["Linux"], 3.9e9),
        ("ubuntu-24.04.3-desktop-amd64.iso", ["Linux"], 6.1e9),
        ("Fedora-Workstation-Live-43.iso", ["Linux"], 2.4e9),
        ("archlinux-2026.10.01-x86_64.iso", ["Linux"], 1.3e9),
        ("NASA - Apollo 11 Archive Footage", ["Documentales"], 31e9),
        ("Internet Archive - Prelinger Collection Vol 3", ["Documentales", "Clásicos"], 17e9),
        ("Planet Earth Sample Reel 2160p", ["Documentales", "Blu-ray UHD"], 22e9),
        ("Caminandes Trilogy 1080p", ["Animación", "Series"], 1.1e9),
        ("Pioneer One S01 1080p", ["Series"], 8.4e9),
        ("Wikipedia dump es 2026-10", [], 24e9),
        ("LibriVox - Don Quijote (audiolibro)", ["Audiolibros"], 1.6e9),
        ("OpenStreetMap Chile extract", ["Mapas"], 0.4e9),
        ("Blender Studio - Charge 4K", ["Animación", "Blu-ray UHD"], 11e9),
    ]
    TRACKERS = ["tracker.opentrackr.org", "academictorrents.com", "archive.org",
                "linuxtracker.org", "torrent.ubuntu.com", "bt.etree.org"]

    def __init__(self):
        rnd = random.Random(7)
        now = int(time.time())
        self.torrents = []
        for i, (name, tags, size) in enumerate(self.NAMES):
            size = int(size)
            kind = rnd.choice(["seed", "seed", "seed", "down", "down", "stopped", "paused"])
            if i == 24:
                kind = "error"
            done = size if kind == "seed" else int(size * rnd.uniform(0.05, 0.97))
            if kind == "stopped" and rnd.random() < 0.5:
                done = size
            h = "%040X" % rnd.getrandbits(160)
            self.torrents.append({
                "hash": h, "name": name, "size": size, "done": done,
                "down_total": done, "up_total": int(done * rnd.uniform(0, 4)),
                "state": 0 if kind in ("stopped", "error") else 1,
                "active": 0 if kind in ("stopped", "paused", "error") else 1,
                "hashing": 0, "message": "Storage error: [File chunk read error: No such file or directory]"
                if kind == "error" else "",
                "tags": list(tags), "directory": "/srv/torrents/" +
                (tags[0] if tags else "Otros"), "priority": 2,
                "private": 1 if i % 5 == 0 else 0, "files": rnd.randint(1, 40),
                "added": now - rnd.randint(3600, 86400 * 400),
                "created": now - rnd.randint(86400 * 400, 86400 * 3000),
                "tracker": self.TRACKERS[i % len(self.TRACKERS)],
                "_seeds": rnd.randint(0, 120), "_peers": rnd.randint(0, 40),
                "opts": {}, "peers_max": 200, "peers_min": 100, "uploads_max": 50,
            })
            t = self.torrents[-1]
            t["started"] = t["added"]
            t["finished"] = t["added"] + 3600 if done == size else 0
        for n, t in enumerate(sorted(self.torrents, key=lambda t: t["added"]), 1):
            t["pos"] = n
        self.lock = threading.Lock()
        self.jobs = Jobs()
        self.checks = CheckQueue()
        self.incoming = {"count": 0, "seen": 0, "checked": 0}
        self.last = time.time()
        self.down_limit, self.up_limit = 0, 0
        self.port_next = 51420
        self.file_prio = {}

    def _tick(self):
        now = time.time()
        dt, self.last = now - self.last, now
        rnd = random.Random()
        self._tick_checks(dt)
        for t in self.torrents:
            t["complete"] = 1 if t["done"] >= t["size"] else 0
            running = t["state"] and t["active"]
            # Random walks, so the speed chart looks like real traffic.
            if running and not t["complete"]:
                prev = t.get("down_rate") or rnd.uniform(0.5, 4) * 2**20
                t["down_rate"] = int(min(9 * 2**20, max(0.2 * 2**20, prev + rnd.gauss(0, 0.25 * 2**20))))
                t["done"] = min(t["size"], t["done"] + int(t["down_rate"] * dt))
            else:
                t["down_rate"] = 0
            if running:
                prev = t.get("up_rate") or rnd.uniform(0, 0.8) * 2**20
                t["up_rate"] = int(min(1.5 * 2**20, max(0, prev + rnd.gauss(0, 0.08 * 2**20))))
            else:
                t["up_rate"] = 0
            t["up_total"] += int(t["up_rate"] * dt)
            t["down_total"] = max(t["down_total"], t["done"])
            t["seeds"] = rnd.randint(0, min(12, t["_seeds"])) if running else 0
            t["peers"] = rnd.randint(0, min(10, t["_peers"])) if running else 0

    def _tick_checks(self, dt):
        """Checks the waiting torrents one at a time, like rTorrent, at 3 GB/s."""
        waiting = [t for t in self.torrents if t["hashing"]]
        if not waiting:
            return
        cur = next((t for t in waiting if t.get("hash_checking")), None)
        if not cur:
            cur = min(waiting, key=lambda t: self.checks.seq.get(t["hash"], 1e9))
            cur["hash_checking"], cur["_checked"] = 1, 0
        cur["_checked"] += 3e9 * dt
        cur["chunks"] = 1000
        cur["chunks_hashed"] = min(1000, int(1000 * cur["_checked"] / max(1, cur["size"])))
        if cur["chunks_hashed"] >= 1000:
            cur["hashing"] = cur["hash_checking"] = 0

    def list(self):
        with self.lock:
            self._tick()
            out = []
            for t in self.torrents:
                c = {k: v for k, v in t.items() if not k.startswith("_")}
                c["tags"] = list(t["tags"])
                c["opts"] = dict(t["opts"])
                c["ratio"] = t["up_total"] / t["down_total"] if t["down_total"] else 0
                c["eta"] = int((t["size"] - t["done"]) / t["down_rate"]) if t["down_rate"] else -1
                c["status"] = status_of(c)
                out.append(c)
            self.checks.annotate(out)
            self.jobs.annotate(out)
            stats = {"down_rate": sum(t["down_rate"] for t in out),
                     "up_rate": sum(t["up_rate"] for t in out),
                     "down_limit": self.down_limit, "up_limit": self.up_limit,
                     "version": "demo", "port": 51420, "port_open": 1, "port_next": self.port_next,
                     "incoming": sum(1 for t in out if t["up_rate"]) // 3, "incoming_seen": int(time.time())}
            return {"torrents": out, "stats": stats}

    def backfill(self, history):
        """An invented past hour, so the demo chart is not empty."""
        rnd = random.Random(3)
        now = int(time.time())
        data = self.list()["torrents"]
        # Walk backwards from the current speeds, so the past joins the present.
        level = {t["hash"]: [t["down_rate"], t["up_rate"]] for t in data}
        per = {t["hash"]: [] for t in data}
        totals = []
        for k in range(1, history.KEEP + 1):
            down = up = 0
            for t in data:
                lv = level[t["hash"]]
                for j, cap in ((0, 9 * 2**20), (1, 1.5 * 2**20)):
                    if lv[j]:
                        lv[j] = min(cap, max(0.05 * 2**20, lv[j] + rnd.gauss(0, cap / 40)))
                per[t["hash"]].append((now - k * history.STEP, int(lv[0]), int(lv[1])))
                down += lv[0]
                up += lv[1]
            totals.append((now - k * history.STEP, int(down), int(up), self.down_limit, self.up_limit))
        for h, rows in per.items():
            history.torrents[h] = collections.deque(reversed(rows), maxlen=history.KEEP)
        history.total.extend(reversed(totals))

    def _find(self, hashes):
        return [t for t in self.torrents if t["hash"] in hashes]

    def details(self, h):
        t = self._find([h])[0]
        rnd = random.Random(h)
        n = t["files"]
        files = []
        for i in range(n):
            ext = ".flac" if "FLAC" in t["tags"] else ".mkv" if n < 4 else rnd.choice(
                [".mkv", ".m2ts", ".srt", ".nfo", ".jpg"])
            folder = "BDMV/STREAM" if ext == ".m2ts" else "Extras" if ext in (".jpg", ".nfo") \
                else "Subtítulos" if ext == ".srt" else "Disco %d" % (1 + i * 2 // n)
            files.append({"path": "%s/%02d - parte %d%s" % (folder, i + 1, i + 1, ext),
                          "size": t["size"] // n,
                          "progress": min(1.0, t["done"] / t["size"] * rnd.uniform(0.8, 1.2)),
                          "priority": self.file_prio.get((h, i), 0 if rnd.random() < 0.08 else 1)})
        clients = ["qBittorrent 5.0.1", "Transmission 4.0.6", "BiglyBT 3.7.0.0",
                   "libtorrent (Rasterbar) 2.0.10", "rTorrent 0.9.8", "Deluge 2.1.1"]
        peers = [{"address": "%d.%d.%d.%d" % tuple(rnd.randint(11, 220) for _ in range(4)),
                  "port": rnd.randint(1025, 65000), "client": rnd.choice(clients),
                  "progress": rnd.choice([1.0, rnd.random()]),
                  "down_rate": rnd.randint(0, 900000) if t["down_rate"] else 0,
                  "up_rate": rnd.randint(0, 300000) if t["up_rate"] else 0,
                  "encrypted": rnd.random() < 0.7, "incoming": rnd.random() < 0.4}
                 for _ in range(t["seeds"] + t["peers"])]
        trackers = self._trackers(t, rnd)
        chunks = max(1, t["size"] // (4 * 2**20))
        p = t["done"] / t["size"] if t["size"] else 1
        bits = [p >= 1 or (i / chunks) * 0.6 + rnd.random() * 0.4 < p for i in range(chunks)]
        seen = "".join("%02X" % (0 if rnd.random() < 0.04 * (1 - p) else rnd.randint(1, 9))
                       for _ in range(chunks))
        per = max(1, chunks // max(1, n))
        for k, f in enumerate(files):
            f["range"] = [min(chunks, k * per), chunks if k == n - 1 else min(chunks, (k + 1) * per)]
        bits += [False] * (-len(bits) % 4)
        bitfield = "".join("%X" % int("".join("1" if b else "0" for b in bits[i:i + 4]), 2)
                           for i in range(0, len(bits), 4))
        return with_availability({
            "files": files, "peers": peers, "trackers": trackers,
            "base_path": t.get("_folder") or t["directory"] + "/" + t["name"], "chunk_size": 4 * 2**20,
            "chunks": chunks, "connection": "seed" if t["complete"] else "leech",
            "bitfield": bitfield, "seen": seen if t["state"] else ""})

    def action(self, action, hashes, **kw):
        with self.lock:
            if action == "remove":
                self.torrents = [t for t in self.torrents if t["hash"] not in hashes]
                for n, t in enumerate(sorted(self.torrents, key=lambda t: t["pos"]), 1):
                    t["pos"] = n
                return []
            for t in self._find(hashes):
                if action == "start":
                    t["state"], t["active"], t["message"] = 1, 1, ""
                elif action == "stop":
                    t["state"], t["active"] = 0, 0
                    t["hashing"] = 0
                    self.jobs.stop(t["hash"])
                elif action == "pause":
                    t["active"] = 0
                elif action == "priority":
                    t["priority"] = int(kw["value"])
                elif action == "recheck" and not t["hashing"]:
                    t["hashing"], t["hash_checking"] = 3, 0
            if action == "recheck":
                self.checks.asked(hashes)
        return []

    def set_tags(self, hashes, tags):
        with self.lock:
            for t in self._find(hashes):
                t["tags"] = clean_tags(tags)

    def edit_tags(self, hashes, add=(), remove=()):
        with self.lock:
            for t in self._find(hashes):
                t["tags"] = clean_tags([x for x in t["tags"] if x not in remove] + list(add))

    def rename_tag(self, old, new):
        with self.lock:
            n = 0
            for t in self.torrents:
                if old in t["tags"]:
                    n += 1
                    t["tags"] = clean_tags([new if x == old else x for x in t["tags"]]
                                           if new else [x for x in t["tags"] if x != old])
            return n

    def file_priority(self, h, indexes, prio):
        for i in indexes:
            self.file_prio[(h, int(i))] = int(prio)

    def default_dir(self):
        return "~"

    def duplicate(self, magnet=None, data=None):
        ident = torrent_identity(data, magnet)
        t = ident and next((x for x in self.torrents if x["hash"] == ident[0]), None)
        if not t:
            return None
        have = {r["url"] for r in self._trackers(t)}
        return {"duplicate": {"hash": t["hash"], "name": t["name"],
                              "trackers": [u for u in ident[2] if u not in have and tracker_url_ok(u)]}}

    def add_trackers(self, h, urls):
        urls = [u.strip() for u in urls if u.strip()]
        bad = [u for u in urls if not tracker_url_ok(u)]
        if bad:
            raise ValueError("Dirección de rastreador no válida: %s" % bad[0])
        with self.lock:
            t = self._find([h])[0]
            have = {r["url"] for r in self._trackers(t)}
            new = [u for u in dict.fromkeys(urls) if u not in have]
            t.setdefault("_extra", []).extend(new)
        return {"added": len(new)}

    def _trackers(self, t, rnd=random):
        return ([{"url": "https://%s/announce" % t["tracker"], "enabled": True,
                  "seeds": t["_seeds"], "leechers": t["_peers"], "downloaded": rnd.randint(10, 9000)},
                 {"url": "udp://tracker.opentrackr.org:1337/announce", "enabled": True,
                  "seeds": t["_seeds"] // 2, "leechers": t["_peers"] // 2, "downloaded": rnd.randint(10, 3000)}] +
                [{"url": u, "enabled": True, "seeds": 0, "leechers": 0, "downloaded": 0} for u in t.get("_extra", [])])

    def add(self, magnet=None, data=None, directory="", tags=(), start=True, skip=()):
        dup = self.duplicate(magnet, data)
        if dup:
            return dup
        with self.lock:
            name = torrent_files(data)["name"] if data else "Nuevo torrent"
            if magnet:
                q = urllib.parse.parse_qs(urllib.parse.urlsplit(magnet).query)
                name = q.get("dn", [name])[0]
            self.torrents.append({
                "hash": "%040X" % random.getrandbits(160), "name": name,
                "size": int(3e9), "done": 0, "down_total": 0, "up_total": 0,
                "state": 1 if start else 0, "active": 1 if start else 0, "hashing": 0,
                "message": "", "tags": clean_tags(tags),
                "directory": directory or "/srv/torrents", "priority": 2, "private": 0,
                "files": 3, "added": int(time.time()), "started": int(time.time()),
                "finished": 0, "created": int(time.time()), "tracker": "",
                "_seeds": 20, "_peers": 10,
                "pos": max([t["pos"] for t in self.torrents] + [0]) + 1,
                "opts": {}, "peers_max": 200, "peers_min": 100, "uploads_max": 50})

    def set_options(self, h, o):
        with self.lock:
            t = self._find([h])[0]
            t["opts"] = clean_opts(o)
            if "priority" in o:
                t["priority"] = int(o["priority"])
            for k in ("peers_max", "peers_min", "uploads_max"):
                if o.get(k) is not None:
                    t[k] = int(o[k])

    def instance(self):
        return 1

    def restore_options(self, torrents):
        pass

    def mark_rule_done(self, h, opts):
        with self.lock:
            self._find([h])[0]["opts"] = dict(opts, rule_done=True)

    def move_pos(self, hashes, where):
        with self.lock:
            order = [t["hash"] for t in sorted(self.torrents, key=lambda t: t["pos"])]
            by = {t["hash"]: t for t in self.torrents}
            for n, h in enumerate(move_order(order, hashes, where), 1):
                by[h]["pos"] = n
        return {"moved": len(hashes)}

    def open_folder(self, h, index=None, sub=None):
        # The demo's folders don't exist: say where it would open instead.
        t = next(x for x in self.torrents if x["hash"] == h)
        return {"folder": t["directory"], "demo": True}

    def relocate(self, hashes, target, move, check):
        target = check_target(target)

        def run():
            found = sorted(self._find(hashes), key=lambda t: hashes.index(t["hash"]))
            for t in found:
                self.jobs.set(t["hash"], "waiting")
            for t in found:
                if not self.jobs.take(t["hash"]):
                    continue
                self.jobs.set(t["hash"], "moving")
                time.sleep(4)
                with self.lock:
                    t["directory"] = target
                    t.pop("_folder", None)
                self.jobs.done(t["hash"])
        threading.Thread(target=run, daemon=True).start()

    def hashes(self):
        return [t["hash"] for t in self.torrents]

    def session_files(self):
        # Stand-ins for the session files, enough for the backup's list of torrents.
        out = {}
        for t in self.torrents:
            multi = t["files"] > 1
            info = {b"name": t["name"].encode(), b"piece length": 1}
            if multi:
                info[b"files"] = []
            folder = os.path.join(t["directory"], t["name"]) if multi else t["directory"]
            out[t["hash"]] = {".torrent": _bencode({b"info": info}),
                              ".torrent.rtorrent": _bencode({b"directory": folder.encode()})}
        return "", "", out

    def restore_torrents(self, entries, old, new):
        have = set(self.hashes())
        return {"skipped": sum(h in have for h in entries), "written": 0,
                "loaded": sum(h not in have for h in entries), "restart": False}

    def restore_rc(self, data):
        return "~/.rtorrent.rc"

    def rename_folder(self, h, new):
        new = check_folder_name(new)
        with self.lock:
            t = next(x for x in self.torrents if x["hash"] == h)
            if t["files"] < 2:
                raise ValueError("Este torrent es un solo archivo, sin carpeta propia")
            # The demo keeps the containing folder in "directory"; the renamed one apart.
            t["_folder"] = os.path.join(t["directory"], new)
        return {"folder": t["_folder"], "demo": True}

    def shutdown(self):
        return {"demo": True}

    def port_test(self):
        return {"demo": True, "port": 51420, "reachable": True, "ip": "203.0.113.7"}

    def set_port(self, port):
        self.port_next = check_port(port)
        return {"demo": True, "port": self.port_next, "rc": "~/.rtorrent.rc", "saved": True,
                "line": "network.port_range.set = %d-%d" % (self.port_next, self.port_next)}

    def limits(self, down=None, up=None):
        if down is not None:
            self.down_limit = int(down)
        if up is not None:
            self.up_limit = int(up)


# ---------------------------------------------------------------- tag store

class TagStore:
    """Tag colors, icons and order, in a JSON file."""

    # Tags of a new installation (no tags.json yet), in this order. Existing tags are never touched.
    DEFAULTS = {
        # Contenido
        "Películas": {"color": "#e53935", "icon": "lucide:clapperboard"},
        "Series": {"color": "#3949ab", "icon": "lucide:tv"},
        "Documentales": {"color": "#fb8c00", "icon": "lucide:globe"},
        "Música": {"color": "#00897b", "icon": "lucide:music"},
        "Audiolibros": {"color": "#6d4c41", "icon": "lucide:book-headphones"},
        "Libros": {"color": "#ffb300", "icon": "lucide:book-open"},
        "Juegos": {"color": "#43a047", "icon": "lucide:gamepad-2"},
        "Aplicaciones": {"color": "#757575", "icon": "lucide:app-window"},
        "Linux": {"color": "#fdd835", "icon": "lucide:terminal"},
        "Windows": {"color": "#00acc1", "icon": "lucide:monitor"},
        # Formatos físicos y digitales de video
        "Blu-ray": {"color": "#039be5", "icon": "lucide:disc"},
        "Blu-ray UHD": {"color": "#1e88e5", "icon": "lucide:disc-3"},
        "Blu-ray Remux": {"color": "#039be5", "icon": "lucide:hard-drive"},
        "Blu-ray UHD Remux": {"color": "#1e88e5", "icon": "lucide:hard-drive"},
        "Web-DL": {"color": "#8e24aa", "icon": "lucide:cloud"},
        "DVD": {"color": "#757575", "icon": "lucide:disc"},
        "DVD Rip": {"color": "#757575", "icon": "lucide:film"},
        # Formatos físicos y digitales de audio
        "CD": {"color": "#757575", "icon": "lucide:disc"},
        "SACD": {"color": "#ffb300", "icon": "lucide:disc-3"},
        "MP3": {"color": "#7cb342", "icon": "lucide:headphones"},
        "FLAC": {"color": "#43a047", "icon": "lucide:audio-lines"},
        "DSD": {"color": "#00897b", "icon": "lucide:audio-lines"},
        "WAV": {"color": "#00acc1", "icon": "lucide:music-2"},
        # Uso
        "Favoritos": {"color": "#fdd835", "icon": "lucide:star"},
        "Colección": {"color": "#ffb300", "icon": "lucide:crown"},
        "Ver después": {"color": "#5e35b1", "icon": "lucide:bookmark"},
        "Visto / Escuchado": {"color": "#7cb342", "icon": "lucide:check"},
        "Conservar": {"color": "#3949ab", "icon": "lucide:shield"},
        "Sembrar siempre": {"color": "#f4511e", "icon": "lucide:flame"},
        "Temporal": {"color": "#757575", "icon": "lucide:clock"},
        "Por revisar": {"color": "#fb8c00", "icon": "lucide:search"},
        "Privado": {"color": "#d81b60", "icon": "lucide:lock"},
        # Idioma
        "Latino": {"color": "#43a047", "icon": "lucide:languages"},
        "Castellano": {"color": "#e53935", "icon": "lucide:languages"},
        "Inglés": {"color": "#1e88e5", "icon": "lucide:languages"},
        "Subtitulado": {"color": "#757575", "icon": "lucide:captions"},
        "Dual": {"color": "#8e24aa", "icon": "lucide:languages"},
        # Imagen y sonido
        "HDR": {"color": "#ffb300", "icon": "lucide:sun"},
        "Dolby Vision": {"color": "#5e35b1", "icon": "lucide:eye"},
        "Atmos": {"color": "#00acc1", "icon": "lucide:speaker"},
    }

    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        try:
            with open(path, encoding="utf-8") as f:
                self.data = json.load(f)
        except (OSError, ValueError):
            self.data = {"tags": {k: dict(v) for k, v in self.DEFAULTS.items()}, "order": list(self.DEFAULTS)}

    def get(self):
        return self.data

    def save(self, data):
        with self.lock:
            self.data = {"tags": data.get("tags", {}), "order": data.get("order", [])}
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.data, f, ensure_ascii=False, indent=1)
            os.replace(tmp, self.path)
            self._clean_logos()
        return self.data

    # Logos: small images in logos/ next to tags.json, named by content.
    LOGO_TYPES = {b"\x89PNG": "png", b"\xff\xd8\xff": "jpg", b"GIF8": "gif", b"RIFF": "webp"}
    LOGO_MAX = 1024 * 1024

    def logo_dir(self):
        return os.path.join(os.path.dirname(self.path), "logos")

    def add_logo(self, data):
        if len(data) > self.LOGO_MAX:
            raise ValueError("La imagen pesa más de 1 MB")
        ext = next((e for magic, e in self.LOGO_TYPES.items() if data.startswith(magic)), None)
        if ext == "webp" and data[8:12] != b"WEBP":
            ext = None
        if not ext:
            raise ValueError("Formato no admitido: usa PNG, JPEG, WebP o GIF")
        name = "%s.%s" % (hashlib.sha1(data).hexdigest()[:16], ext)
        os.makedirs(self.logo_dir(), exist_ok=True)
        with open(os.path.join(self.logo_dir(), name), "wb") as f:
            f.write(data)
        return {"url": "/logos/" + name}

    def _clean_logos(self):
        # The demo and the real tags (tags-demo.json, tags.json) share logos/, so a logo
        # stays while any tags file uses it. Fresh files may be an upload about to be saved.
        d = os.path.dirname(self.path)
        used = set()
        for name in os.listdir(d):
            if name.startswith("tags") and name.endswith(".json"):
                try:
                    with open(os.path.join(d, name), encoding="utf-8") as f:
                        tags = json.load(f).get("tags", {})
                except (OSError, ValueError, AttributeError):
                    return  # unreadable tags file: keep every logo
                used |= {t.get("logo", "").rsplit("/", 1)[-1] for t in tags.values() if isinstance(t, dict)}
        try:
            for f in os.listdir(self.logo_dir()):
                full = os.path.join(self.logo_dir(), f)
                if f not in used and time.time() - os.path.getmtime(full) > 3600:
                    os.remove(full)
        except OSError:
            pass


def _bencode(x):
    if isinstance(x, int):
        return b"i%de" % x
    if isinstance(x, str):
        x = x.encode()
    if isinstance(x, bytes):
        return b"%d:%s" % (len(x), x)
    if isinstance(x, list):
        return b"l" + b"".join(map(_bencode, x)) + b"e"
    return b"d" + b"".join(_bencode(k) + _bencode(v) for k, v in sorted(x.items())) + b"e"


def rename_old_meta(meta):
    """A torrent from a backup made by Mosaico (until 0.8): its values under Tablero's names."""
    custom = meta.get(b"custom")
    if isinstance(custom, dict):
        for k, new in OLD_KEYS.items():
            v = custom.pop(k.encode(), None)
            if v and not custom.get(new.encode()):
                custom[new.encode()] = v
    t = meta.get(b"throttle_name")
    if isinstance(t, bytes) and t.startswith(OLD_THROTTLE.encode()):
        meta[b"throttle_name"] = b"tablero_" + t[len(OLD_THROTTLE):]


def remap_path(path, old, new):
    """path with its beginning old changed to new (whole folder names only)."""
    old = (old or "").rstrip("/")
    if not old or not new:
        return path
    if path == old or path.startswith(old + "/"):
        return new.rstrip("/") + path[len(old):] if new != "/" else "/" + path[len(old):].lstrip("/")
    return path


# Imported backups end in -importado (-importado-2…), and are never removed as old ones.
BACKUP_NAME = re.compile(r"^tablero-respaldo-\d{4}-\d{2}-\d{2}-\d{4}(-importado(-\d+)?)?\.zip$")
BACKUP_IMPORT_MAX = 1024 ** 3
BACKUP_EVERY = {"daily": 86400, "weekly": 7 * 86400}


class Backups:
    """Backups for a reinstall or another computer: a .zip with Tablero's settings
    (tags, logos, network access, window, interface preferences), the rTorrent session
    (every torrent's .torrent, its progress and its fields: folder, tags, state) and
    rtorrent.rc. Not the downloaded files. Made by hand or on a schedule, into a folder;
    the settings are in backup.json."""

    def __init__(self, config_dir, backend, tagstore, access):
        self.dir, self.backend, self.tagstore, self.access = config_dir, backend, tagstore, access
        self.path = os.path.join(config_dir, "backup.json")
        self.lock = threading.Lock()

    def settings(self):
        try:
            with open(self.path, encoding="utf-8") as f:
                conf = json.load(f)
        except (OSError, ValueError):
            conf = {}
        return {"folder": conf.get("folder") or os.path.join(user_files(), "Respaldos de Tablero"), "chosen": bool(conf.get("folder")),
                "every": conf.get("every", "never"),
                "keep": int(conf.get("keep", 5)), "last": conf.get("last", 0), "last_file": conf.get("last_file", "")}

    def _save_settings(self, conf):
        os.makedirs(self.dir, exist_ok=True)
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(conf, f, ensure_ascii=False)
        os.replace(tmp, self.path)

    def set_settings(self, folder, every, keep):
        folder = check_target(folder) if folder else ""
        if every not in ("never", "daily", "weekly"):
            raise ValueError("Frecuencia no válida")
        if every != "never" and not folder:
            raise ValueError("Elige la carpeta de los respaldos automáticos")
        conf = self.settings()
        conf.update(folder=folder, every=every, keep=max(1, min(100, int(keep or 5))))
        self._save_settings(conf)
        return conf

    def list(self, folder=None):
        conf = self.settings()
        folder = folder or conf["folder"]
        files = []
        if folder and os.path.isdir(folder):
            for e in os.scandir(folder):
                if BACKUP_NAME.match(e.name) and e.is_file():
                    files.append({"name": e.name, "path": e.path, "size": e.stat().st_size,
                                  "time": int(e.stat().st_mtime)})
        files.sort(key=lambda f: f["name"], reverse=True)
        return dict(conf, folder=folder, files=files)

    def load_prefs(self):
        try:
            with open(os.path.join(self.dir, "prefs.json"), encoding="utf-8") as f:
                return {"prefs": json.load(f)}
        except (OSError, ValueError):
            return {"prefs": {}}

    def save_prefs(self, prefs):
        """The interface preferences, which the page keeps in the browser: kept here
        too, so that a backup has them."""
        os.makedirs(self.dir, exist_ok=True)
        with open(os.path.join(self.dir, "prefs.json"), "w", encoding="utf-8") as f:
            json.dump(prefs, f, ensure_ascii=False)
        return {}

    def create(self, folder=None, auto=False):
        conf = self.settings()
        folder = check_target(folder or conf["folder"] or "")
        try:
            os.makedirs(folder, exist_ok=True)
        except OSError as e:
            raise ValueError("No pude crear la carpeta %s: %s" % (folder, e.strerror or e))
        with self.lock:
            name = time.strftime("tablero-respaldo-%Y-%m-%d-%H%M.zip")
            path = os.path.join(folder, name)
            tmp = path + ".tmp"
            torrents = []
            try:
                with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
                    # Tablero: everything in its folder but temporary and demo files.
                    for root, dirs, names in os.walk(self.dir):
                        if root == self.dir:
                            dirs[:] = [d for d in dirs if d != "demo"]
                        for n in names:
                            if n.endswith(".tmp") or "-demo." in n or n == "backup.json":
                                continue
                            full = os.path.join(root, n)
                            z.write(full, "tablero/" + os.path.relpath(full, self.dir))
                    session, rc, entries = self.backend.session_files()
                    for h, files in entries.items():
                        for ext, data in files.items():
                            z.writestr("rtorrent/session/%s%s" % (h, ext), data)
                        meta = files.get(".torrent.rtorrent")
                        info = {"hash": h}
                        try:
                            m, _ = _bdecode(meta) if meta else ({}, 0)
                            t, _ = _bdecode(files[".torrent"])
                            info.update(name=t[b"info"][b"name"].decode("utf-8", "replace"),
                                        directory=m.get(b"directory", b"").decode("utf-8", "surrogateescape"),
                                        multi=b"files" in t[b"info"])
                        except (ValueError, KeyError, IndexError, AttributeError):
                            pass
                        torrents.append(info)
                    if rc and os.path.isfile(rc):
                        z.write(rc, "rtorrent/rtorrent.rc")
                    z.writestr("manifest.json", json.dumps({
                        "app": "Tablero", "version": VERSION, "created": int(time.time()),
                        "host": socket.gethostname(), "session": session, "rc": rc or "",
                        "torrents": torrents}, ensure_ascii=False, indent=1))
                os.replace(tmp, path)
            except OSError as e:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
                raise ValueError("No pude crear el respaldo en %s: %s" % (folder, e.strerror or e))
            conf.update(last=int(time.time()), last_file=path)
            if not conf["folder"]:
                conf["folder"] = folder
            self._save_settings(conf)
            if auto:
                old = sorted(f for f in os.listdir(folder) if BACKUP_NAME.match(f) and "-importado" not in f)[:-conf["keep"]]
                for f in old:
                    try:
                        os.remove(os.path.join(folder, f))
                    except OSError:
                        pass
        return {"path": path, "torrents": len(torrents)}

    def run_schedule(self):
        """Every half hour: a backup when the last one is older than the chosen period."""
        while True:
            conf = self.settings()
            period = BACKUP_EVERY.get(conf["every"])
            if period and conf["folder"] and time.time() - conf["last"] >= period:
                try:
                    self.create(auto=True)
                except (ValueError, OSError, ConnectionError, xmlrpc.client.Fault):
                    pass
            time.sleep(1800)

    @staticmethod
    def backup_path(path):
        """A backup Tablero may hand out: named like one, and really a backup."""
        path = os.path.realpath(path or "")
        if not BACKUP_NAME.match(os.path.basename(path)):
            raise ValueError("No es un respaldo de Tablero")
        Backups._open(path)[0].close()
        return path

    def import_file(self, read, size, folder="", name=""):
        """A backup .zip sent from elsewhere (Importar): into the backups folder, named by
        when it was made, and checked before it is kept."""
        if size <= 0:
            raise ValueError("El archivo está vacío")
        if size > BACKUP_IMPORT_MAX:
            raise ValueError("El archivo es demasiado grande para ser un respaldo de Tablero")
        folder = check_target(folder or self.settings()["folder"])
        try:
            os.makedirs(folder, exist_ok=True)
        except OSError as e:
            raise ValueError("No pude crear la carpeta %s: %s" % (folder, e.strerror or e))
        tmp = os.path.join(folder, ".importando-%d.tmp" % os.getpid())
        try:
            with open(tmp, "wb") as f:
                left = size
                while left > 0:
                    chunk = read(min(left, 1 << 20))
                    if not chunk:
                        raise ValueError("El archivo no llegó completo")
                    f.write(chunk)
                    left -= len(chunk)
            try:
                z, man = self._open(tmp)
            except ValueError:
                raise ValueError("%s no es un respaldo de Tablero" % (name or "El archivo"))
            z.close()
            stamp = time.strftime("%Y-%m-%d-%H%M", time.localtime(man.get("created") or time.time()))
            n = 1
            while True:
                path = os.path.join(folder, "tablero-respaldo-%s-importado%s.zip" % (stamp, "-%d" % n if n > 1 else ""))
                if not os.path.exists(path):
                    break
                if os.path.getsize(path) == size:  # the same backup, imported again
                    os.remove(tmp)
                    return {"path": path, "torrents": len(man.get("torrents", []))}
                n += 1
            os.replace(tmp, path)
        except OSError as e:
            raise ValueError("No pude guardar el respaldo en %s: %s" % (folder, e.strerror or e))
        finally:
            if os.path.exists(tmp):
                os.remove(tmp)
        return {"path": path, "torrents": len(man.get("torrents", []))}

    @staticmethod
    def _open(path):
        if not path or not os.path.isfile(path):
            raise ValueError("No encontré el respaldo %s" % path)
        try:
            z = zipfile.ZipFile(path)
            manifest = json.loads(z.read("manifest.json"))
        except (OSError, KeyError, ValueError, zipfile.BadZipFile):
            raise ValueError("%s no es un respaldo de Tablero" % os.path.basename(path))
        return z, manifest

    def inspect(self, path, old="", new=""):
        """What a backup holds, and where its torrents' data would be looked for here."""
        z, man = self._open(path)
        with z:
            names = set(z.namelist())
        have = set(self.backend.hashes())
        folders = collections.OrderedDict()
        for t in man.get("torrents", []):
            d = t.get("directory") or ""
            if not d:
                continue
            parent = os.path.dirname(d) if t.get("multi") else d
            data = d if t.get("multi") else os.path.join(d, t.get("name", ""))
            f = folders.setdefault(parent, {"from": parent, "to": remap_path(parent, old, new), "count": 0, "found": 0})
            f["count"] += 1
            f["found"] += os.path.exists(remap_path(data, old, new))
        return {"created": man.get("created"), "host": man.get("host"), "version": man.get("version"),
                "torrents": len(man.get("torrents", [])),
                "present": sum(t["hash"] in have for t in man.get("torrents", [])),
                "folders": sorted(folders.values(), key=lambda f: f["from"]),
                "has": {"tags": "tablero/tags.json" in names, "prefs": "tablero/prefs.json" in names,
                        "access": "tablero/access.json" in names, "rc": "rtorrent/rtorrent.rc" in names}}

    def restore(self, path, parts, old="", new=""):
        z, man = self._open(path)
        res = {}
        with z:
            names = z.namelist()
            if parts.get("tags") and "tablero/tags.json" in names:
                tags = json.loads(z.read("tablero/tags.json"))
                logos = self.tagstore.logo_dir()
                os.makedirs(logos, exist_ok=True)
                for n in names:
                    base = os.path.basename(n)
                    if n.startswith("tablero/logos/") and base and not base.startswith("."):
                        with open(os.path.join(logos, base), "wb") as f:
                            f.write(z.read(n))
                self.tagstore.save(tags)
                res["tags"] = len(tags.get("tags", tags) if isinstance(tags, dict) else [])
            if parts.get("prefs") and "tablero/prefs.json" in names:
                res["prefs"] = json.loads(z.read("tablero/prefs.json"))
            if parts.get("access") and "tablero/access.json" in names:
                with open(self.access.path, "wb") as f:
                    f.write(z.read("tablero/access.json"))
                res["access"] = True
            if parts.get("rc") and "rtorrent/rtorrent.rc" in names:
                res["rc"] = self.backend.restore_rc(z.read("rtorrent/rtorrent.rc"))
            if parts.get("torrents"):
                entries = {}
                for n in names:
                    m = re.match(r"^rtorrent/session/([0-9A-F]{40})(\.torrent(?:\.libtorrent_resume|\.rtorrent)?)$", n)
                    if m:
                        entries.setdefault(m.group(1), {})[m.group(2)] = z.read(n)
                entries = {h: e for h, e in entries.items() if ".torrent" in e}
                res["torrents"] = self.backend.restore_torrents(entries, old, new)
        return res


class Access:
    """Opening Tablero from other computers of the network (⚙ Opciones), kept in
    access.json. With it on, Tablero listens on every address, and anyone not on this
    computer must give the password (HTTP Basic: the browser asks for it)."""
    ROUNDS = 200000

    def __init__(self, path, bind_arg, port):
        self.path, self.bind_arg, self.port = path, bind_arg, port
        self.lock = threading.Lock()
        self.ok = set()  # Authorization headers already checked
        try:
            with open(path, encoding="utf-8") as f:
                self.data = json.load(f)
        except (OSError, ValueError):
            self.data = {}
        self.listening = self.wanted_bind()

    def wanted_bind(self):
        if self.bind_arg:
            return self.bind_arg
        return "0.0.0.0" if self.data.get("lan") and self.data.get("hash") else "127.0.0.1"

    def info(self):
        lan = self.listening not in ("127.0.0.1", "localhost", "::1")
        return {"lan": bool(self.data.get("lan")), "password": bool(self.data.get("hash")),
                "fixed": self.bind_arg or "", "listening": lan, "ip": local_ip(), "port": self.port}

    def _hash(self, password, salt):
        return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), bytes.fromhex(salt), self.ROUNDS).hex()

    def save(self, lan, password):
        with self.lock:
            data = dict(self.data, lan=bool(lan))
            if password:
                if len(password) < 6:
                    raise ValueError("La contraseña debe tener al menos 6 caracteres")
                data["salt"] = secrets.token_hex(16)
                data["hash"] = self._hash(password, data["salt"])
            if data["lan"] and not data.get("hash"):
                raise ValueError("Elige una contraseña para abrir Tablero desde la red")
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(data, f)
            os.chmod(tmp, 0o600)
            os.replace(tmp, self.path)
            self.data = data
            self.ok.clear()
        return dict(self.info(), restart=self.wanted_bind() != self.listening)

    def allowed(self, client, header):
        """This computer always enters; others need the password, when there is one."""
        if client in ("127.0.0.1", "::1", "::ffff:127.0.0.1") or not self.data.get("hash"):
            return True
        if not header or not header.startswith("Basic "):
            return False
        if header in self.ok:
            return True
        try:
            password = base64.b64decode(header[6:]).decode("utf-8").split(":", 1)[1]
        except (ValueError, IndexError):
            return False
        if hmac.compare_digest(self._hash(password, self.data["salt"]), self.data["hash"]):
            self.ok.add(header)
            return True
        time.sleep(1)  # slows down guessing
        return False


_server = None          # the running ThreadingHTTPServer
_restart = threading.Event()


def restart_self():
    """Starts Tablero again in the same process, to listen on another address."""
    if PLATFORM == "android" or __name__ != "__main__":
        # Embedded (Chaquopy) or on Android: exec would replace the whole app,
        # so run() only opens the server again on the new address.
        _restart.set()
        if _server:
            threading.Thread(target=_server.shutdown, daemon=True).start()
        return
    sys.stdout.flush()
    sys.stderr.flush()
    os.execv(sys.executable, [sys.executable, os.path.abspath(sys.argv[0])] + sys.argv[1:])


# ---------------------------------------------------------------- HTTP

class Handler(SimpleHTTPRequestHandler):
    backend = None
    tagstore = None
    history = None
    access = None

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=WEB_DIR, **kw)

    _status = None

    def send_response(self, code, message=None):
        self._status = code
        super().send_response(code, message)

    def log_message(self, fmt, *args):
        if not self.path.startswith("/api/torrents"):
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def end_headers(self):
        # Logos are named by their content, so they never change.
        self.send_header("Cache-Control", "max-age=31536000, immutable"
                         if self.path.startswith("/logos/") and self._status == 200 else "no-store")
        super().end_headers()

    def _json(self, obj, status=200):
        # A failed call inside a multicall is an Exception: sent as its message.
        body = json.dumps(obj, ensure_ascii=False,
                          default=lambda o: str(o) if isinstance(o, Exception) else repr(o)).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _guard(self, fn):
        try:
            self._json(fn())
        except (ConnectionError, OSError) as e:
            self._json({"error": "No se pudo conectar con rTorrent: %s" % e}, 502)
        except xmlrpc.client.Fault as e:
            self._json({"error": "rTorrent: %s" % e.faultString}, 502)
        except (ValueError, KeyError, TypeError, IndexError) as e:
            self._json({"error": str(e)}, 400)

    def _local(self):
        return self.client_address[0] in ("127.0.0.1", "::1", "::ffff:127.0.0.1")

    def _host_ok(self):
        """This computer enters without a password, so a page from another site must not
        reach Tablero through it by pointing its own name at 127.0.0.1 (DNS rebinding):
        from this computer, the address must name this computer."""
        if not self._local():
            return True
        host = (self.headers.get("Host") or "").strip().lower()
        name = host[1:host.find("]")] if host.startswith("[") else host.rsplit(":", 1)[0]
        return (name in ("", "localhost", "::1", socket.gethostname().lower(), local_ip())
                or name.endswith(".localhost") or re.fullmatch(r"127(\.\d{1,3}){3}", name) is not None)

    def _authorized(self):
        if not self._host_ok():
            self._json({"error": "Dirección no permitida: abre Tablero en http://localhost:%d" % self.server.server_address[1]}, 403)
            return False
        if self.access.allowed(self.client_address[0], self.headers.get("Authorization")):
            return True
        body = "Tablero pide la contraseña de Acceso desde la red.".encode()
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="Tablero", charset="UTF-8"')
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        return False

    def do_HEAD(self):
        if self._authorized():
            super().do_HEAD()

    def do_GET(self):
        if not self._authorized():
            return
        url = urllib.parse.urlsplit(self.path)
        if url.path == "/api/access":
            return self._json(dict(self.access.info(), local=self._local(), platform=PLATFORM))
        if url.path == "/api/torrents":
            return self._guard(lambda: dict(self.backend.list(), tablero=VERSION, platform=PLATFORM))
        if url.path == "/api/backups":
            q = urllib.parse.parse_qs(url.query)
            return self._guard(lambda: self.backups.list((q.get("folder") or [""])[0]))
        if url.path == "/api/prefs":
            return self._guard(lambda: self.backups.load_prefs())
        if url.path == "/api/backup-file":
            return self._backup_file((urllib.parse.parse_qs(url.query).get("path") or [""])[0])
        if url.path == "/api/details":
            h = urllib.parse.parse_qs(url.query)["hash"][0]
            return self._guard(lambda: self.backend.details(h))
        if url.path == "/api/dirs":
            p = urllib.parse.parse_qs(url.query).get("path", [""])[0]
            return self._json(list_dirs(p))
        if url.path == "/api/free-space":
            p = urllib.parse.parse_qs(url.query).get("path", [""])[0]
            return self._guard(lambda: free_space(p or self.backend.default_dir()))
        if url.path == "/api/history":
            h = urllib.parse.parse_qs(url.query).get("hash", [None])[0]
            return self._json(self.history.get(h))
        if url.path == "/api/tags":
            return self._json(self.tagstore.get())
        if url.path == "/api/local-ip":
            return self._json({"ip": local_ip()})
        m = re.fullmatch(r"/logos/([0-9a-f]{16}\.(png|jpg|gif|webp))", url.path)
        if m:
            return self._logo(m.group(1), m.group(2))
        return super().do_GET()

    def _backup_file(self, path):
        """Exportar in a browser: the backup as a download."""
        try:
            path = self.backups.backup_path(path)
            size = os.path.getsize(path)
            f = open(path, "rb")
        except (ValueError, OSError) as e:
            return self._json({"error": str(e)}, 404)
        with f:
            self.send_response(200)
            self.send_header("Content-Type", "application/zip")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", 'attachment; filename="%s"' % os.path.basename(path))
            self.end_headers()
            shutil.copyfileobj(f, self.wfile)

    def _logo(self, name, ext):
        try:
            with open(os.path.join(self.tagstore.logo_dir(), name), "rb") as f:
                data = f.read()
        except OSError:
            return self._json({"error": "not found"}, 404)
        self.send_response(200)
        self.send_header("Content-Type", "image/" + ("jpeg" if ext == "jpg" else ext))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        if not self._authorized():
            return
        # A custom header forces a CORS preflight, so other web pages
        # cannot drive the API through a plain form post.
        if self.headers.get("X-Tablero") != "1":
            return self._json({"error": "forbidden"}, 403)
        n = int(self.headers.get("Content-Length") or 0)
        url = urllib.parse.urlsplit(self.path)
        if url.path == "/api/backup-import":  # the .zip itself, not JSON
            q = urllib.parse.parse_qs(url.query)
            return self._guard(lambda: self.backups.import_file(
                self.rfile.read, n, (q.get("folder") or [""])[0], (q.get("name") or [""])[0]))
        if n > 8 * 1024 * 1024:
            return self._json({"error": "Demasiado grande"}, 413)
        try:
            body = json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return self._json({"error": "JSON inválido"}, 400)
        b = self.backend
        routes = {
            "/api/action": lambda: b.action(body["action"], body["hashes"],
                                            data=body.get("data"), value=body.get("value")),
            "/api/set-tags": lambda: b.set_tags(body["hashes"], body["tags"]),
            "/api/edit-tags": lambda: b.edit_tags(body["hashes"], body.get("add", []),
                                                  body.get("remove", [])),
            "/api/rename-tag": lambda: {"count": b.rename_tag(body["old"], body.get("new", ""))},
            "/api/file-priority": lambda: b.file_priority(body["hash"], body["indexes"],
                                                          body["priority"]),
            "/api/add": lambda: b.add(
                magnet=body.get("magnet"),
                data=base64.b64decode(body["file"]) if body.get("file") else
                read_torrent_file(body["path"]) if body.get("path") else None,
                directory=body.get("directory", ""), tags=body.get("tags", []),
                start=body.get("start", True), skip=body.get("skip", [])),
            "/api/torrent-info": lambda: torrent_files(
                base64.b64decode(body["file"]) if body.get("file") else read_torrent_file(body["path"])),
            "/api/limits": lambda: b.limits(body.get("down"), body.get("up")),
            "/api/shutdown": lambda: b.shutdown(),
            "/api/port-test": lambda: dict(b.port_test(), local_ip=local_ip()),
            "/api/port": lambda: b.set_port(body.get("port")),
            "/api/add-trackers": lambda: b.add_trackers(body["hash"], body.get("urls", [])),
            "/api/open-folder": lambda: b.open_folder(body["hash"], body.get("index"), body.get("dir")),
            "/api/move": lambda: b.move_pos(body["hashes"], body["where"]),
            "/api/options": lambda: b.set_options(body["hash"], body["options"]),
            "/api/rename-folder": lambda: b.rename_folder(body["hash"], body.get("name")),
            "/api/relocate": lambda: b.relocate(body["hashes"], body["target"], bool(body.get("move")),
                                                bool(body.get("check"))),
            "/api/tags": lambda: self.tagstore.save(body),
            "/api/logo": lambda: self.tagstore.add_logo(base64.b64decode(body["data"])),
            "/api/access": lambda: self._set_access(body),
            "/api/prefs": lambda: self.backups.save_prefs(body.get("prefs") or {}),
            "/api/backup-settings": lambda: self.backups.set_settings(body.get("folder", ""), body.get("every", "never"),
                                                                       body.get("keep", 5)),
            "/api/backup": lambda: self.backups.create(body.get("folder")),
            "/api/restore-inspect": lambda: self.backups.inspect(body["path"], body.get("from", ""), body.get("to", "")),
            "/api/restore": lambda: self.backups.restore(body["path"], body.get("parts") or {},
                                                         body.get("from", ""), body.get("to", "")),
        }
        fn = routes.get(urllib.parse.urlsplit(self.path).path)
        if not fn:
            return self._json({"error": "not found"}, 404)

        def run():
            r = fn()
            return r if isinstance(r, dict) else {"ok": True}
        self._guard(run)


    def _set_access(self, body):
        if not self._local():
            raise ValueError("El acceso desde la red solo se cambia en %s donde corre Tablero" % (
                "el teléfono/tableta" if PLATFORM == "android" else "el equipo"))
        r = self.access.save(body.get("lan"), body.get("password") or "")
        if r["restart"]:
            threading.Timer(0.5, restart_self).start()
        return r


def run(argv=None):
    """Runs Tablero until stop() (or Ctrl+C). argv as on the command line, for example
    run(["--android", "--scgi", "127.0.0.1:5000", "--config", "/data/.../files/tablero"])
    from an Android app (Chaquopy), in a thread of its own."""
    global PLATFORM, CONFIG_DIR, USER_FILES, RTORRENT_RC, _server
    p = argparse.ArgumentParser(prog="tablero", description="Interfaz web estilo BiglyBT para rTorrent")
    p.add_argument("--version", action="version", version="Tablero " + VERSION)
    p.add_argument("--scgi", default="127.0.0.1:5000",
                   help="host:puerto o ruta del socket SCGI de rTorrent (por defecto 127.0.0.1:5000)")
    p.add_argument("--bind", default=None,
                   help="dirección donde escuchar (por defecto la de ⚙ Opciones: este equipo o toda la red)")
    p.add_argument("--port", type=int, default=8090, help="puerto web (por defecto 8090)")
    p.add_argument("--demo", action="store_true", help="usar torrents de prueba, sin rTorrent")
    p.add_argument("--android", action="store_true",
                   help="interfaz para Android: oculta lo que solo funciona en un PC (xdg-open, systemctl, apt, ufw)")
    p.add_argument("--config", default=None,
                   help="carpeta de configuración (por defecto ~/.config/tablero)")
    p.add_argument("--archivos", default=None,
                   help="carpeta donde empiezan Examinar… y los respaldos (en Android, p. ej. la de descargas de la app)")
    p.add_argument("--rtorrent-rc", default=None,
                   help="el rtorrent.rc que usa rTorrent, si Tablero no lo encuentra solo (Cambiar puerto y los respaldos lo usan)")
    a = p.parse_args(argv)
    if a.rtorrent_rc:
        RTORRENT_RC = os.path.abspath(os.path.expanduser(a.rtorrent_rc))
    if a.archivos:
        USER_FILES = os.path.abspath(os.path.expanduser(a.archivos))
    if a.android or os.environ.get("TABLERO_PLATAFORMA") == "android":
        PLATFORM = "android"
    if a.config:
        CONFIG_DIR = os.path.abspath(os.path.expanduser(a.config))
    else:
        move_old_config()
    os.makedirs(CONFIG_DIR, exist_ok=True)
    Handler.backend = DemoBackend() if a.demo else RTorrentBackend(a.scgi)
    Handler.history = History(Handler.backend)
    Handler.tagstore = TagStore(os.path.join(
        CONFIG_DIR, "tags-demo.json" if a.demo else "tags.json"))
    Handler.access = Access(os.path.join(
        CONFIG_DIR, "access-demo.json" if a.demo else "access.json"), a.bind, a.port)
    Handler.backups = Backups(os.path.join(CONFIG_DIR, "demo") if a.demo else CONFIG_DIR,
                              Handler.backend, Handler.tagstore, Handler.access)
    if not a.demo:
        threading.Thread(target=Handler.backups.run_schedule, daemon=True).start()
    while True:
        _restart.clear()
        Handler.access.listening = bind = Handler.access.wanted_bind()
        _server = ThreadingHTTPServer((bind, a.port), Handler)
        _server.daemon_threads = True
        print("Tablero %s en http://%s:%d  (%s%s)" % (VERSION, bind, a.port,
                                                      "demo" if a.demo else "rTorrent " + a.scgi,
                                                      ", Android" if PLATFORM == "android" else ""), flush=True)
        try:
            _server.serve_forever()
        except KeyboardInterrupt:
            _restart.clear()
        finally:
            _server.server_close()
        if not _restart.is_set():
            break


def stop():
    """Stops run() from another thread (the Android app, when its service ends)."""
    _restart.clear()
    if _server:
        _server.shutdown()


def main():
    run()


if __name__ == "__main__":
    main()
