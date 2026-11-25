#!/usr/bin/env python3
"""Genera Iconos.kt (interfaz Compose) desde web/icons.js: cada ícono de Lucide como trazos SVG (path d).

Uso: tools/gen-iconos.py android/tablero/src/main/assets/web/icons.js > android/tablero/src/main/kotlin/cl/tablero/compose/Iconos.kt
"""
import json, re, sys

src = open(sys.argv[1], encoding="utf-8").read()
groups = json.loads(re.search(r"const ICON_GROUPS = (\[.*?\]\]\]\]);", src, re.S).group(1))
svg = {}
for m in re.finditer(r'^"([a-z0-9-]+)":\s*"(.*)",?\s*$', src, re.M):
    svg[m.group(1)] = json.loads('"' + m.group(2) + '"')

def num(v): return float(v)
def f(x): return ("%.3f" % x).rstrip("0").rstrip(".")

def to_d(tag, a):
    if tag == "path": return a["d"]
    if tag == "line": return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if tag in ("polyline", "polygon"):
        p = a["points"].replace(",", " ").split()
        pts = [p[i:i + 2] for i in range(0, len(p), 2)]
        return "M" + "L".join(f"{x} {y}" for x, y in pts) + ("Z" if tag == "polygon" else "")
    if tag == "circle":
        cx, cy, r = num(a["cx"]), num(a["cy"]), num(a["r"])
        return f"M{f(cx - r)} {f(cy)}a{f(r)} {f(r)} 0 1 0 {f(2 * r)} 0a{f(r)} {f(r)} 0 1 0 {f(-2 * r)} 0Z"
    if tag == "ellipse":
        cx, cy, rx, ry = num(a["cx"]), num(a["cy"]), num(a["rx"]), num(a["ry"])
        return f"M{f(cx - rx)} {f(cy)}a{f(rx)} {f(ry)} 0 1 0 {f(2 * rx)} 0a{f(rx)} {f(ry)} 0 1 0 {f(-2 * rx)} 0Z"
    if tag == "rect":
        x, y, w, h = num(a.get("x", 0)), num(a.get("y", 0)), num(a["width"]), num(a["height"])
        rx = num(a.get("rx", a.get("ry", 0))); ry = num(a.get("ry", a.get("rx", 0)))
        if not rx: return f"M{f(x)} {f(y)}h{f(w)}v{f(h)}h{f(-w)}Z"
        return (f"M{f(x + rx)} {f(y)}h{f(w - 2 * rx)}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(ry)}v{f(h - 2 * ry)}"
                f"a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(ry)}h{f(-(w - 2 * rx))}a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(-ry)}"
                f"v{f(-(h - 2 * ry))}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(-ry)}Z")
    raise SystemExit("forma desconocida: " + tag)

def kt(s): return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'

out = ["// Generado por tools/gen-iconos.py desde web/icons.js: no editar a mano.",
       "// Line icons from Lucide (https://lucide.dev), ISC License,",
       "// Copyright (c) Lucide Contributors 2022; portions (c) Cole Bemis 2013-2022 (Feather, MIT).",
       "package cl.tablero.compose", "",
       "/** Cada ícono: sus trazos como path d de SVG, en un cuadro de 24×24. */",
       "internal val LUCIDE: Map<String, List<String>> = mapOf("]
for name, body in svg.items():
    ds = [to_d(t, dict(re.findall(r'([a-z0-9-]+)="([^"]*)"', attrs))) for t, attrs in re.findall(r"<([a-z]+)\s([^>]*?)/?>", body)]
    out.append(f"    {kt(name)} to listOf({', '.join(kt(d) for d in ds)}),")
out.append(")")
out.append("")
out.append("/** Los íconos para etiquetas, por grupo: (grupo, [(nombre, descripción)]). */")
out.append("internal val ICON_GROUPS: List<Pair<String, List<Pair<String, String>>>> = listOf(")
for g, items in groups:
    out.append(f"    {kt(g)} to listOf({', '.join(f'{kt(n)} to {kt(l)}' for n, l in items)}),")
out.append(")")
print("\n".join(out))
