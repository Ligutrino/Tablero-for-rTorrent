"use strict";
// Tablero para rTorrent: BiglyBT-style interface for rTorrent. Plain JS, no build step.

const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const NO_TAG = "\u0000none";

const PALETTE = [
	"#e53935", "#d81b60", "#8e24aa", "#5e35b1", "#3949ab", "#1e88e5",
	"#039be5", "#00acc1", "#00897b", "#43a047", "#7cb342", "#c0ca33",
	"#fdd835", "#ffb300", "#fb8c00", "#f4511e", "#6d4c41", "#757575",
];
// A tag icon is "lucide:<name>": a line icon from icons.js, drawn in the
// text color. Anything else (emoji from older versions) is not shown.
function iconHTML(icon, cls = "ico") {
	const svg = icon && icon.startsWith("lucide:") && ICON_SVG[icon.slice(7)];
	if (svg) return `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${svg}</svg>`;
	return "";
}

// Buttons for tools inside panels, with the same look as the top bar: icon and text.
const toolBtn = (icon, label, attrs = "", on = false) =>
	`<button type="button" class="tbtn${on ? " on" : ""}" ${attrs}${on ? ' aria-pressed="true"' : ""}>${iconHTML("lucide:" + icon)}<span>${label}</span></button>`;
// A command for the terminal, with its own Copiar button (any [data-copy-cmd] copies).
const cmdHTML = (text) => `<span class="cmd"><code>${esc(text)}</code>${toolBtn("copy", "Copiar", `data-copy-cmd="${esc(text)}" title="Copiar el comando"`)}</span>`;

// ------------------------------------------------------------ android
// tablero.py --android: no systemctl, apt, ufw or file manager to start. The Android app
// can give window.TableroAndroid (addJavascriptInterface) with any of: openFolder(path),
// startRtorrent(), restartRtorrent(), openStream(url, name), pickFolder(id); what it lacks is hidden or
// explained in words.
const isAndroid = () => state.platform === "android";
const androidCan = (fn) => typeof window.TableroAndroid?.[fn] === "function";
const canOpenFolder = () => !isAndroid() || androidCan("openFolder");
// A touch screen (phone or tablet): no mouse hover, no right click, no keyboard shortcuts.
const touchUI = () => matchMedia("(pointer: coarse)").matches;
// How the texts name the device Tablero runs on.
const thisDevice = () => (isAndroid() ? "este teléfono/tableta" : "este equipo");
// How to filter by several tags at once.
// Touch: a small "i" beside a text that a mouse shows on hover. Tapping it shows the text.
const info = (title, text) => touchUI() ? `<span class="info" role="button" data-tip-title="${esc(title)}" data-tip="${esc(text)}">i</span>` : "";
const multiTagHow = () => (touchUI() ? "toque largo en una etiqueta y Agregar al filtro" : "Ctrl+clic");
// How to start or restart rTorrent: a command for the terminal, or on Android the app's button.
function rtorrentCmdHTML(kind) {
	if (!isAndroid()) return cmdHTML(kind === "start" ? START_CMD : RESTART_CMD);
	const fn = kind === "start" ? "startRtorrent" : "restartRtorrent";
	if (androidCan(fn)) return toolBtn("power", kind === "start" ? "Encender rTorrent" : "Reiniciar rTorrent", `data-android-call="${fn}"`);
	return "<span>desde la app de Android que ejecuta rTorrent.</span>";
}
const ufwHTML = (ports) => (isAndroid() ? "" : `<div class="cmd-list">Si usas el cortafuegos ufw:${ports.map((p) => cmdHTML(`sudo ufw allow ${p}`)).join("")}</div>`);
// For the Android app: opens Agregar torrents with magnet links and/or .torrent files that
// tablero.py can read, e.g. tableroAdd({ magnets: ["magnet:?…"], files: [{ name: "a.torrent", path: "/data/…/a.torrent" }] }).
// For the Android app's Back button: closes the open menu or dialog; false if there was none.
window.tableroBack = () => {
	if (menuOpen()) { closeMenu(); return true; }
	if (modalOpen()) { closeModal(); return true; }
	return false;
};
window.tableroAdd = (o = {}) => {
	const files = (o.files || []).map((f) => (typeof f === "string" ? { name: f.split("/").pop(), path: f } : f));
	const zone = modalOpen() && $("#add-drop");
	if (zone) zone.addFiles(files); else if (!modalOpen()) addDialog(files); else return false;
	const ta = $("#add-magnet");
	if (ta && o.magnets?.length) ta.value = [ta.value.trim(), ...o.magnets].filter(Boolean).join("\n");
	return true;
};
// A choice between a few options: [value, label, icon?].
const seg = (name, items, cur) => `<span class="seg">${items.map(([v, n, icon]) =>
	`<button type="button" data-${name}="${v}" class="${String(cur) === String(v) ? "on" : ""}">${icon ? iconHTML("lucide:" + icon) : ""}<span>${n}</span></button>`).join("")}</span>`;

// ------------------------------------------------------------ format

const nf1 = new Intl.NumberFormat("es-CL", { maximumFractionDigits: 1, minimumFractionDigits: 1 });
const nf2 = new Intl.NumberFormat("es-CL", { maximumFractionDigits: 2, minimumFractionDigits: 2 });
const nf0 = new Intl.NumberFormat("es-CL");
const df = new Intl.DateTimeFormat("es-CL", { dateStyle: "short", timeStyle: "short" });

// Binary units (KiB = 1024 B, as rTorrent counts) or decimal ones (KB = 1000 B), chosen in Opciones.
const KB = () => (prefs.units === "dec" ? 1000 : 1024);
const UNITS = () => (prefs.units === "dec" ? ["B", "KB", "MB", "GB", "TB"] : ["B", "KiB", "MiB", "GiB", "TiB"]);
function size(b) {
	if (!b) return "0 B";
	const u = UNITS(), k = KB();
	const i = Math.min(u.length - 1, Math.floor(Math.log(b) / Math.log(k)));
	return (i ? nf1.format(b / k ** i) : b) + " " + u[i];
}
const rate = (b) => (b ? size(b) + "/s" : "");
const date = (s) => (s ? df.format(new Date(s * 1000)) : "");
function eta(s) {
	if (s < 0) return "";
	if (s < 60) return s + " s";
	const d = Math.floor(s / 86400), h = Math.floor(s / 3600) % 24, m = Math.floor(s / 60) % 60;
	return d ? `${d} d ${h} h` : h ? `${h} h ${m} min` : `${m} min`;
}

// ------------------------------------------------------------ state

function load(key) {
	try { return JSON.parse(localStorage.getItem(key)) || {}; } catch { return {}; }
}
// Until 0.8 they were kept under the program's old name, moved here once. In the window,
// the new name (0.9) also means new storage: then they come from the server's copy (init).
function savedPrefs() {
	let p = load("tablero.prefs");
	if (!Object.keys(p).length) {
		p = load("mosaico.prefs");
		try {
			if (Object.keys(p).length) localStorage.setItem("tablero.prefs", JSON.stringify(p));
			localStorage.removeItem("mosaico.prefs");
		} catch { /* private mode */ }
	}
	return p;
}
const storedPrefs = savedPrefs(), prefsStored = Object.keys(storedPrefs).length > 0;

const prefs = Object.assign({
	columns: null, sort: { col: "added", dir: -1 }, theme: "dark", tint: true,
	tagbar: true, tagMode: "any", sidebarW: 220, detailsH: 230, tab: "general",
	interval: 2, lastDir: "", filesTree: true, hideSkipped: false,
	speedOf: "torrent", speedRange: 300, units: "bin", tableSort: {}, tableW: {},
}, storedPrefs);

let prefsSync = 0;
function savePrefs() {
	try { localStorage.setItem("tablero.prefs", JSON.stringify(prefs)); } catch { /* private mode */ }
	// Also kept by the server, so that backups have them.
	clearTimeout(prefsSync);
	prefsSync = setTimeout(() => api("/api/prefs", { prefs }).catch(() => {}), 1500);
}

const state = {
	torrents: [], byHash: new Map(), stats: {},
	tagConf: { tags: {}, order: [] },
	filter: { status: "all", tags: new Set(), tracker: null, text: "" },
	sel: new Set(), anchor: null, focus: null,
	details: null, detailsFor: null, ok: false, collapsed: new Set(),
	history: null, historyKey: null, hover: null,
};

// ------------------------------------------------------------ api

async function api(path, body) {
	const opt = body === undefined ? {} : {
		method: "POST", body: JSON.stringify(body),
		headers: { "Content-Type": "application/json", "X-Tablero": "1" },
	};
	const r = await fetch(path, opt);
	const data = await r.json().catch(() => ({ error: "Respuesta inválida (" + r.status + ")" }));
	if (!r.ok || data.error) throw new Error(data.error || r.statusText);
	return data;
}

async function act(path, body, okMsg) {
	try {
		await api(path, body);
		if (okMsg) toast(okMsg);
		refresh();
	} catch (e) {
		toast(e.message, true);
	}
}

// ------------------------------------------------------------ tags

function hashHue(s) {
	let h = 0;
	for (const c of s) h = (h * 31 + c.codePointAt(0)) >>> 0;
	return h % 360;
}
function hslHex(h, s, l) {
	s /= 100; l /= 100;
	const f = (n) => {
		const k = (n + h / 30) % 12;
		const c = l - s * Math.min(l, 1 - l) * Math.max(-1, Math.min(k - 3, 9 - k, 1));
		return Math.round(c * 255).toString(16).padStart(2, "0");
	};
	return "#" + f(0) + f(8) + f(4);
}
function tagInfo(name) {
	const c = state.tagConf.tags[name] || {};
	return { color: /^#[0-9a-f]{6}$/i.test(c.color || "") ? c.color : hslHex(hashHue(name), 45, 42), icon: c.icon || "" };
}
function textOn(hex) {
	const lin = (c) => { c /= 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
	const n = parseInt(hex.slice(1), 16);
	const lum = 0.2126 * lin(n >> 16) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
	return lum > 0.4 ? "#000" : "#fff";
}
function rgba(hex, a) {
	const n = parseInt(hex.slice(1), 16);
	return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`;
}
// A tag's picture is a logo or an icon, never both. Where a tag shows up,
// it looks like "color" (colored pill with the name), "logo" (only the
// picture) or "both" (pill with the picture and the name).
const PLACES = [
	["sidebar", "Barra lateral"], ["column", "Columna Etiquetas"],
	["bar", "Barra de etiquetas"], ["other", "Detalles, menús y diálogos"],
];
const DEFAULT_SHOW = { sidebar: "both", column: "logo", bar: "both", other: "both" };
const DEFAULT_SHOW_ICON = { sidebar: "both", column: "both", bar: "both", other: "both" };
const hasIcon = (icon) => !!(icon && icon.startsWith("lucide:") && ICON_SVG[icon.slice(7)]);

// Logos whose file is missing on the server: those tags show as if they had no logo.
const brokenLogos = new Set();
document.addEventListener("error", (e) => {
	const img = e.target;
	if (img.tagName !== "IMG") return;
	const src = new URL(img.src, location.href).pathname;
	if (!src.startsWith("/logos/") || brokenLogos.has(src)) return;
	brokenLogos.add(src);
	if (Object.values(state.tagConf.tags).some((t) => t.logo === src)) setTimeout(render, 0);
}, true);
const liveLogo = (c) => (c.logo && !brokenLogos.has(c.logo) ? c.logo : "");

function defaultShow(logo) { return logo ? DEFAULT_SHOW : DEFAULT_SHOW_ICON; }
function tagMode(name, place) {
	const c = state.tagConf.tags[name] || {};
	if (!liveLogo(c) && !hasIcon(c.icon)) return "color";
	return (c.show && c.show[place]) || defaultShow(c.logo)[place];
}
// Version 0.2 let browsers keep a "not found" answer for a logo for a year, so a logo that
// was missing once stayed invisible after it came back. A new address skips those answers.
const logoSrc = (src) => (src.startsWith("/logos/") ? src + "?v=2" : src);
// A logo at a given height, with its width set from the stored aspect
// ratio so the layout is right before the image loads.
// bg: the color behind the logo (a chip's color), or none when it sits on the page.
function logoImg(logo, h, cls = "", alt = "", bg = "") {
	const w = Math.round(h * Math.min(4.5, logo.ratio || 2));
	const tone = toneClass(logo.tone, bg);
	return `<img class="${[cls, tone].filter(Boolean).join(" ")}" src="${esc(logoSrc(logo.src))}" width="${w}" height="${h}" alt="${esc(alt)}">`;
}
// Recoloring a logo so it reads on what is behind it: done with CSS filters when drawn,
// the stored image never changes. "auto" picks white or black for the background.
const LOGO_TONES = [["", "Original"], ["invert", "Invertido"], ["white", "Blanco"], ["black", "Negro"], ["auto", "Automático"]];
function toneClass(tone, bg) {
	if (tone === "auto") return bg ? (textOn(bg) === "#fff" ? "tone-white" : "tone-black") : "tone-page";
	return tone && LOGO_TONES.some(([v]) => v === tone) ? "tone-" + tone : "";
}
function logoOf(name) {
	const c = state.tagConf.tags[name] || {};
	return liveLogo(c) ? { src: c.logo, ratio: c.logoRatio, tone: c.logoTone || "" } : null;
}
function chipHTML(name, info, logo, mode, extra = "", cls = "") {
	const icon = !logo && hasIcon(info.icon) ? info.icon : "";
	if (mode === "logo" && (logo || icon))
		return `<span class="chip logo-only ${cls}" data-tag="${esc(name)}" title="${esc(name)}"${icon ? ` style="color:${info.color}"` : ""}>` +
			`${logo ? logoImg(logo, 16, "", name) : iconHTML(icon, "ico big")}${extra}</span>`;
	const lead = mode !== "both" ? "" : logo ? logoImg(logo, 14, "", "", info.color) : iconHTML(icon);
	return `<span class="chip ${cls}" data-tag="${esc(name)}" style="background:${info.color};color:${textOn(info.color)}">${lead}${esc(name)}${extra}</span>`;
}
// The start of a side panel row: color square, picture and name as the mode says.
function sideHTML(name, info, logo, mode) {
	const icon = !logo && hasIcon(info.icon) ? info.icon : "";
	if (mode === "logo" && logo) return `<span class="name">${logoImg(logo, 18, "slogo", name)}</span>`;
	if (mode === "logo" && icon) return `<span class="sicon-only" style="color:${info.color}">${iconHTML(icon, "ico big")}</span>`;
	if (mode === "both" && logo) return `<span class="swatch strip" style="background:${info.color}"></span>${logoImg(logo, 18, "slogo")}<span class="name">${esc(name)}</span>`;
	return `<span class="swatch" style="background:${info.color};color:${textOn(info.color)}">${mode === "both" ? iconHTML(icon) : ""}</span><span class="name">${esc(name)}</span>`;
}
function chip(name, extra = "", place = "other", cls = "") {
	return chipHTML(name, tagInfo(name), logoOf(name), tagMode(name, place), extra, cls);
}
// Configured tags in the user's order, then any tag seen on a torrent.
function allTags() {
	const out = [], seen = new Set();
	const push = (t) => { if (t && !seen.has(t)) { seen.add(t); out.push(t); } };
	state.tagConf.order.forEach(push);
	Object.keys(state.tagConf.tags).sort((a, b) => a.localeCompare(b, "es")).forEach(push);
	const rest = new Set();
	state.torrents.forEach((t) => t.tags.forEach((x) => seen.has(x) || rest.add(x)));
	[...rest].sort((a, b) => a.localeCompare(b, "es")).forEach(push);
	return out;
}
function tagRank(name) {
	const i = allTags().indexOf(name);
	return i < 0 ? 1e6 : i;
}
async function saveTagConf() {
	try {
		state.tagConf = await api("/api/tags", state.tagConf);
	} catch (e) {
		toast(e.message, true);
	}
	render();
}

// ------------------------------------------------------------ columns

const STATUS = {
	downloading: "Descargando", seeding: "Compartiendo", paused: "En pausa",
	stopped: "Detenido", finished: "Completo", checking: "Verificando", queued: "En espera de verificación", error: "Error",
	moving: "Trasladando datos", waiting: "En espera de reubicación",
};
// "En espera de verificación 2", "En espera de reubicación 1". The checking one shows no percentage: rTorrent's d.chunks_hashed
// counts pieces read ahead into its hash queue, not checked ones; the progress bar shows those.
// rTorrent says nothing when a torrent does not fit on its disk: Tablero says how much is missing.
const messageText = (t) => (t.nospace ? `Sin espacio en el disco: le faltan ${size(t.nospace.need)} y quedan ${size(t.nospace.free)} libres` : t.message);
function statusText(t) {
	if ((t.status === "queued" || t.status === "waiting") && t.queue_pos) return `${STATUS[t.status]} ${t.queue_pos}`;
	return STATUS[t.status] || "";
}
const PRIO = { 0: "No descargar", 1: "Baja", 2: "Normal", 3: "Alta" };

function progressBar(t) {
	const p = t.size ? t.done / t.size : 1;
	const cls = t.status === "error" ? "error" : p >= 1 ? "done" : t.state ? "" : "stopped";
	return `<div class="bar ${cls}"><span style="width:${(p * 100).toFixed(1)}%"></span><em>${nf1.format(p * 100)} %</em></div>`;
}
// Status signs are Lucide line icons, like the buttons: characters such as ⏸ or ⚠
// turned into color emoji on Android and did not match each other.
const sico = (name) => iconHTML("lucide:" + name, "sico");
const STATUS_ICON = { downloading: "arrow-down", seeding: "arrow-up", paused: "pause", stopped: "square", finished: "check",
	checking: "refresh-cw", queued: "clock", error: "triangle-alert", moving: "arrow-right", waiting: "arrow-right" };
// Notes in dialogs: ok, warning, error.
const mark = (k) => sico({ good: "check", warn: "triangle-alert", bad: "x" }[k]);
function statusIcon(t) {
	return STATUS_ICON[t.status] ? sico(STATUS_ICON[t.status]) : "";
}

const COLUMNS = [
	{ id: "pos", title: "#", w: 50, num: true, get: (t) => t.pos || 1e9, html: (t) => t.pos || "", headTip: true, tip: () => "Posición del torrent en la lista. " + (touchUI() ? "Cámbiala desde el menú del torrent" : "Cámbiala con Ctrl+↑ / Ctrl+↓ o desde el menú del torrent") },
	{ id: "name", title: "Nombre", w: 380, get: (t) => t.name.toLowerCase(),
		html: (t) => `<span class="ico">${statusIcon(t)}</span>${esc(t.name)}`, cls: "name", tip: (t) => t.name },
	{ id: "tags", title: "Etiquetas", w: 220, get: (t) => (t.tags.length ? tagRank(t.tags[0]) : 1e7),
		html: (t) => t.tags.map((x) => chip(x, "", "column")).join(""), cls: "tags", tip: (t) => t.tags.join(", ") },
	{ id: "size", title: "Tamaño", w: 90, num: true, get: (t) => t.size, html: (t) => size(t.size) },
	{ id: "progress", title: "Progreso", w: 120, get: (t) => (t.size ? t.done / t.size : 1), html: progressBar },
	{ id: "status", title: "Estado", w: 120, get: (t) => ["queued", "waiting"].includes(t.status) ? t.status + String(t.queue_pos).padStart(4, "0") : t.status,
		html: (t) => `<span class="st st-${t.status}">${statusText(t)}</span>${statusTip(t) ? info(statusText(t), statusTip(t)) : ""}`, tip: statusTip },
	{ id: "down_rate", title: "Bajada", w: 90, num: true, get: (t) => t.down_rate, html: (t) => `<span class="rate-down">${rate(t.down_rate)}</span>` },
	{ id: "up_rate", title: "Subida", w: 90, num: true, get: (t) => t.up_rate, html: (t) => `<span class="rate-up">${rate(t.up_rate)}</span>` },
	{ id: "eta", title: "Tiempo rest.", w: 95, num: true, get: (t) => (t.eta < 0 ? 1e12 : t.eta), html: (t) => eta(t.eta) },
	{ id: "seeds", title: "Semillas", w: 70, num: true, get: (t) => t.seeds, html: (t) => t.seeds || "" },
	{ id: "peers", title: "Pares", w: 60, num: true, get: (t) => t.peers, html: (t) => t.peers || "" },
	{ id: "ratio", title: "Proporción", headTip: true, tip: () => "Subido ÷ descargado", w: 85, num: true, get: (t) => t.ratio, html: (t) => nf2.format(t.ratio) },
	{ id: "up_total", title: "Subido", w: 90, num: true, get: (t) => t.up_total, html: (t) => size(t.up_total) },
	{ id: "done", title: "Descargado", w: 95, num: true, get: (t) => t.done, html: (t) => size(t.done) },
	{ id: "left", title: "Restante", w: 90, num: true, get: (t) => t.size - t.done, html: (t) => (t.size - t.done ? size(t.size - t.done) : "") },
	{ id: "added", title: "Agregado", w: 125, get: (t) => t.added, html: (t) => date(t.added) },
	{ id: "finished", title: "Completado", w: 125, get: (t) => t.finished, html: (t) => date(t.finished) },
	{ id: "tracker", title: "Rastreador", w: 140, get: (t) => t.tracker, html: (t) => esc(t.tracker) },
	{ id: "directory", title: "Carpeta", w: 200, get: (t) => t.directory, html: (t) => esc(t.directory), tip: (t) => t.directory },
	{ id: "priority", title: "Prioridad", w: 80, get: (t) => t.priority, html: (t) => PRIO[t.priority] || "" },
	{ id: "files", title: "Archivos", w: 70, num: true, get: (t) => t.files, html: (t) => t.files },
	{ id: "private", title: "Privado", w: 60, get: (t) => t.private, html: (t) => (t.private ? "Sí" : "") },
];
const COL = Object.fromEntries(COLUMNS.map((c) => [c.id, c]));
const DEFAULT_COLS = ["pos", "name", "tags", "size", "progress", "status", "down_rate", "up_rate", "eta", "seeds", "peers", "ratio", "added"];

function columns() {
	if (!Array.isArray(prefs.columns)) {
		prefs.columns = COLUMNS.map((c) => ({ id: c.id, w: c.w, on: DEFAULT_COLS.includes(c.id) }));
		// Default order: visible ones first, in DEFAULT_COLS order.
		prefs.columns.sort((a, b) => (DEFAULT_COLS.indexOf(a.id) + 1 || 99) - (DEFAULT_COLS.indexOf(b.id) + 1 || 99));
	}
	const known = new Set(prefs.columns.map((c) => c.id));
	// Columns added in later versions: "#" goes first and visible, the rest hidden at the end.
	COLUMNS.forEach((c) => known.has(c.id) || (c.id === "pos" ? prefs.columns.unshift({ id: c.id, w: c.w, on: true })
		: prefs.columns.push({ id: c.id, w: c.w, on: false })));
	prefs.columns = prefs.columns.filter((c) => COL[c.id]);
	return prefs.columns;
}
const visibleCols = () => columns().filter((c) => c.on);

// ------------------------------------------------------------ filtering

const STATUS_FILTERS = [
	{ id: "all", name: "Todos", icon: sico("layout-grid"), test: () => true },
	{ id: "downloading", name: "Descargando", icon: sico("arrow-down"), test: (t) => t.status === "downloading" },
	{ id: "seeding", name: "Compartiendo", icon: sico("arrow-up"), test: (t) => t.status === "seeding" },
	{ id: "complete", name: "Completos", icon: sico("check"), test: (t) => !!t.complete },
	{ id: "incomplete", name: "Incompletos", icon: sico("chart-pie"), test: (t) => !t.complete },
	{ id: "stopped", name: "Detenidos y en pausa", icon: sico("pause"), test: (t) => ["stopped", "finished", "paused"].includes(t.status) },
	{ id: "active", name: "Con actividad", icon: sico("arrow-down-up"), test: (t) => t.down_rate > 0 || t.up_rate > 0 },
	{ id: "inactive", name: "Sin actividad", icon: sico("circle"), test: (t) => !t.down_rate && !t.up_rate },
	{ id: "checking", name: "Verificando", icon: sico("refresh-cw"), test: (t) => ["checking", "queued"].includes(t.status) },
	{ id: "error", name: "Con error", icon: sico("triangle-alert"), test: (t) => t.status === "error" },
];

function matchTags(t, tags) {
	if (!tags.size) return true;
	const has = (x) => (x === NO_TAG ? t.tags.length === 0 : t.tags.includes(x));
	return prefs.tagMode === "all" ? [...tags].every(has) : [...tags].some(has);
}
function filtered({ skipStatus = false, skipTags = false, skipTracker = false } = {}) {
	const f = state.filter;
	const st = STATUS_FILTERS.find((s) => s.id === f.status) || STATUS_FILTERS[0];
	const words = f.text.toLowerCase().split(/\s+/).filter(Boolean);
	return state.torrents.filter((t) =>
		(skipStatus || st.test(t)) &&
		(skipTags || matchTags(t, f.tags)) &&
		(skipTracker || !f.tracker || t.tracker === f.tracker) &&
		words.every((w) => t.name.toLowerCase().includes(w)));
}
function sorted(list) {
	const c = COL[prefs.sort.col] || COL.added, d = prefs.sort.dir;
	return list.slice().sort((a, b) => {
		const x = c.get(a), y = c.get(b);
		const r = typeof x === "string" ? x.localeCompare(y, "es") : x - y;
		return r * d || a.name.localeCompare(b.name, "es");
	});
}

// ------------------------------------------------------------ render

let rows = []; // torrents currently shown, in order

// The refresh every 2 s rebuilds the HTML, but only what changed is written to
// the page: rewriting everything recreated the tag logos each time and made
// them blink (very visible in the desktop window).
function setHTML(el, html) {
	if (el._html === html && el.firstChild === el._first) return false;
	const old = keepable(el);
	el.innerHTML = html;
	reuse(el, old);
	el._html = html;
	el._first = el.firstChild;
	return true;
}
// When a block does change, its pictures and charts are moved into the new HTML
// instead of being created again, so they don't go blank for a moment.
function keepable(root) {
	const imgs = new Map();
	root.querySelectorAll("img").forEach((i) => {
		const k = i.outerHTML;
		imgs.has(k) ? imgs.get(k).push(i) : imgs.set(k, [i]);
	});
	return { imgs, canvases: [...root.querySelectorAll("canvas[id]")] };
}
function reuse(root, old) {
	root.querySelectorAll("img").forEach((i) => {
		const same = old.imgs.get(i.outerHTML);
		if (same && same.length) i.replaceWith(same.shift());
	});
	old.canvases.forEach((c) => {
		const n = root.querySelector("#" + CSS.escape(c.id));
		if (n && n.tagName === "CANVAS") { c.className = n.className; n.replaceWith(c); }
	});
}
const tplRow = document.createElement("template");
function patchRows(tbody, keys, htmls) {
	const cur = tbody.rows;
	if (cur.length !== keys.length || keys.some((k, i) => cur[i].dataset.hash !== k)) {
		const old = keepable(tbody);
		tbody.innerHTML = htmls.join("");
		reuse(tbody, old);
		htmls.forEach((h, i) => (tbody.rows[i]._html = h));
		return;
	}
	htmls.forEach((h, i) => {
		const r = cur[i];
		if (r._html === h) return;
		tplRow.innerHTML = h;
		const n = tplRow.content.firstElementChild;
		for (const a of ["class", "style"]) {
			const v = n.getAttribute(a);
			if (v !== r.getAttribute(a)) v == null ? r.removeAttribute(a) : r.setAttribute(a, v);
		}
		const oldCells = [...r.cells], newCells = [...n.cells];
		if (oldCells.length !== newCells.length) r.replaceWith(n);
		else newCells.forEach((c, j) => {
			if (c.outerHTML === oldCells[j].outerHTML) return;
			reuse(c, keepable(oldCells[j]));
			r.replaceChild(c, oldCells[j]);
		});
		(oldCells.length !== newCells.length ? n : r)._html = h;
	});
}

function render() {
	document.documentElement.dataset.theme = prefs.theme;
	renderSidebar();
	renderTagbar();
	renderTable();
	renderStatus();
	renderDetails();
}

function li(attrs, inner, on) {
	const a = Object.entries(attrs).map(([k, v]) => `${k}="${esc(v)}"`).join(" ");
	return `<li ${a} class="${on ? "on" : ""}">${inner}</li>`;
}
const countPill = (n) => `<span class="count">${n}</span>`;

function renderSidebar() {
	const f = state.filter;
	const base = filtered({ skipStatus: true });
	setHTML($("#status-list"), STATUS_FILTERS.map((s) =>
		li({ "data-status": s.id }, `<span class="sicon">${s.icon}</span><span class="name">${s.name}</span>${countPill(base.filter(s.test).length)}`, f.status === s.id)).join(""));

	const forTags = filtered({ skipTags: true });
	const counts = new Map();
	let none = 0;
	forTags.forEach((t) => { if (!t.tags.length) none++; t.tags.forEach((x) => counts.set(x, (counts.get(x) || 0) + 1)); });
	setHTML($("#tag-list"), allTags().map((name) => {
		const { color, icon } = tagInfo(name);
		const n = counts.get(name) || 0;
		const lead = sideHTML(name, { color, icon }, logoOf(name), tagMode(name, "sidebar"));
		return li({ "data-tag": name, title: name }, lead +
			`<span class="count" style="${n ? `background:${rgba(color, .28)};color:var(--text)` : ""}">${n}</span>`, f.tags.has(name));
	}).join("") + li({ "data-tag": NO_TAG }, `<span class="sicon">${sico("ban")}</span><span class="name">Sin etiqueta</span>${countPill(none)}`, f.tags.has(NO_TAG)));

	const forTr = filtered({ skipTracker: true });
	const tr = new Map();
	forTr.forEach((t) => t.tracker && tr.set(t.tracker, (tr.get(t.tracker) || 0) + 1));
	setHTML($("#tracker-list"), [...tr.keys()].sort().map((h) =>
		li({ "data-tracker": h, title: h }, `<span class="sicon">🌐</span><span class="name">${esc(h)}</span>${countPill(tr.get(h))}`, f.tracker === h)).join(""));
}

function renderTagbar() {
	const bar = $("#tagbar");
	// With the bar hidden, "Coincide" goes to the sidebar's Etiquetas heading.
	setHTML($("#side-mode"), prefs.tagbar ? "" : `<span title="Coincide: al elegir varias etiquetas (${multiTagHow()}), mostrar los torrents que tengan cualquiera de ellas o todas">` +
		seg("tagmode", [["any", "Cualquiera"], ["all", "Todas"]], prefs.tagMode) + info("Coincide", `Al elegir varias etiquetas (${multiTagHow()}), mostrar los torrents que tengan cualquiera de ellas o todas`) + "</span>");
	if (!prefs.tagbar) { setHTML(bar, ""); return; }
	const counts = new Map();
	filtered({ skipTags: true }).forEach((t) => t.tags.forEach((x) => counts.set(x, (counts.get(x) || 0) + 1)));
	const tags = allTags().filter((x) => counts.has(x) || state.filter.tags.has(x));
	if (!tags.length) { setHTML(bar, ""); return; }
	setHTML(bar, tags.map((x) => chip(x, ` <span class="n">${counts.get(x) || 0}</span>`, "bar", state.filter.tags.has(x) ? "on" : "")).join("") +
		`<span class="mode" title="Al elegir varias etiquetas (${multiTagHow()}), mostrar los torrents que tengan cualquiera de ellas o todas"><span class="dimtxt">Coincide</span>${info("Coincide", `Al elegir varias etiquetas (${multiTagHow()}), mostrar los torrents que tengan cualquiera de ellas o todas`)}` +
		seg("tagmode", [["any", "Cualquiera"], ["all", "Todas"]], prefs.tagMode) + "</span>");
}

function renderHeader() {
	const cols = visibleCols();
	setHTML($("#torrents colgroup"), cols.map((c) => `<col style="width:${c.w}px">`).join(""));
	setHTML($("#torrents thead tr"), cols.map((c) => {
		const d = COL[c.id];
		const arrow = prefs.sort.col === c.id ? `<span class="arrow">${prefs.sort.dir > 0 ? "▲" : "▼"}</span>` : "";
		return `<th data-col="${c.id}" draggable="true" class="${d.num ? "num" : ""}" title="${esc(d.title)}">${esc(d.title)}${d.headTip ? info(d.title, d.tip()) : ""}${arrow}<span class="grip"></span></th>`;
	}).join(""));
}

function renderTable() {
	renderHeader();
	const cols = visibleCols().map((c) => COL[c.id]);
	rows = sorted(filtered());
	for (const h of state.sel) if (!state.byHash.has(h)) state.sel.delete(h);
	patchRows($("#torrents tbody"), rows.map((t) => t.hash), rows.map((t) => {
		let cls = state.sel.has(t.hash) ? "sel" : "";
		let style = "";
		if (prefs.tint && t.tags.length) {
			cls += " tinted";
			style = ` style="--tint:${rgba(tagInfo(t.tags[0]).color, .16)}"`;
		}
		return `<tr data-hash="${t.hash}" class="${cls}" draggable="true"${style}>` + cols.map((c) =>
			`<td class="${c.num ? "num " : ""}${c.cls || ""}"${c.tip ? ` title="${esc(c.tip(t))}"` : ""}>${c.html(t)}</td>`).join("") + "</tr>";
	}));
	const empty = $("#empty");
	empty.hidden = rows.length > 0 || !state.ok;
	empty.textContent = state.torrents.length ? "Ningún torrent coincide con el filtro." : "No hay torrents. Usa Agregar o arrastra archivos .torrent aquí.";
}

function renderStatus() {
	const s = state.stats;
	$("#sp-down").textContent = (s.down_rate ? size(s.down_rate) : "0 B") + "/s" + (s.down_limit ? " ⁽ˡ⁾" : "");
	$("#sp-up").textContent = (s.up_rate ? size(s.up_rate) : "0 B") + "/s" + (s.up_limit ? " ⁽ˡ⁾" : "");
	$("#st-count").textContent = `${rows.length} de ${state.torrents.length} torrents`;
	const n = state.sel.size;
	const shown = new Set(rows.map((t) => t.hash));
	state.hiddenSel = [...state.sel].filter((h) => !shown.has(h)).length;
	$("#st-sel").textContent = n ? `${n} seleccionado${n > 1 ? "s" : ""}` + (state.hiddenSel ? ` (${state.hiddenSel} oculto${state.hiddenSel > 1 ? "s" : ""} por el filtro)` : "") : "";
}

// ------------------------------------------------------------ details

function detailHash() {
	if (state.focus && state.sel.has(state.focus)) return state.focus;
	return state.sel.size === 1 ? [...state.sel][0] : null;
}

async function loadDetails() {
	const h = detailHash();
	if (!h) return;
	try {
		const d = await api("/api/details?hash=" + encodeURIComponent(h));
		if (detailHash() === h) { state.details = d; state.detailsFor = h; renderDetails(); }
	} catch (e) { /* torrent removed meanwhile */ }
}

function dl(pairs) {
	return `<dl class="grid">${pairs.map(([k, v, wide]) => `<div class="${wide ? "wide" : ""}"><dt>${k}</dt><dd title="${esc(String(v).replace(/<[^>]+>/g, ""))}">${v}</dd></div>`).join("")}</dl>`;
}

function renderDetails() {
	$$(".tabs button").forEach((b) => b.classList.toggle("on", b.dataset.tab === prefs.tab));
	const h = detailHash(), t = h && state.byHash.get(h), body = $("#details-body");
	$("#details-title").textContent = t ? t.name : "";
	if (prefs.tab === "speed") return renderSpeed(body, t);
	if (!t) {
		setHTML(body, `<div class="placeholder">${state.sel.size > 1 ? `${state.sel.size} torrents seleccionados` : "Selecciona un torrent para ver sus detalles"}</div>`);
		return;
	}
	const d = state.detailsFor === h ? state.details : null;
	// Nor while a column is being widened.
	if (state.subResize) return;
	// Don't rebuild under an open priority list.
	if (body.contains(document.activeElement) && document.activeElement.tagName === "SELECT") return;
	// The options form is built once per torrent, so a refresh doesn't undo what is being typed.
	if (prefs.tab === "options") {
		if (state.optsHash !== h || !$("#opt-form", body)) { body.innerHTML = optionsHTML(t); state.optsHash = h; }
		return;
	}
	state.optsHash = null;
	const keep = body.scrollTop;
	const ae = document.activeElement, typing = ae && ae.dataset && ae.dataset.tfilter ? [ae.dataset.tfilter, ae.selectionStart] : null;
	if (prefs.tab === "general") {
		const pct = t.size ? t.done / t.size : 1;
		const av = d ? d.availability : null;
		// The bars are built once and then only updated, like the grid below.
		if (!$("#piece-map", body)) {
			body.innerHTML = `<div class="meters">
				<span class="lbl">Descargado</span><canvas id="piece-map" class="meter"></canvas><span class="val" id="gen-pct"></span>
				<span class="lbl">Disponibilidad${info("Disponibilidad", "")}</span><div class="meter avail" id="gen-avail" data-tip-title="Disponibilidad"><span></span></div><span class="val" id="gen-av"></span>
			</div><div id="gen-grid"></div>`;
		}
		$("#gen-pct", body).textContent = `${nf1.format(pct * 100)} %`;
		$("#gen-av", body).textContent = av == null ? "…" : nf2.format(av);
		const avail = $("#gen-avail", body);
		const availTip = d && !d.availability_estimated ? "Copias completas del torrent entre los pares conectados: el número entero es cuántas copias hay de la pieza más escasa y la fracción, qué parte de las piezas tiene una copia más." : "Estimada a partir del avance de los pares conectados.";
		avail.dataset.tip = availTip;
		const availInfo = $(".lbl .info", body);
		if (availInfo) availInfo.dataset.tip = availTip;
		avail.firstChild.style.width = `${av == null ? 0 : Math.min(1, av) * 100}%`;
		setHTML($("#gen-grid", body), dl([
			["Estado", `<span class="st st-${t.status}">${statusText(t)}</span>`],
			["Tamaño", `${size(t.size)} (${nf0.format(t.files)} archivo${t.files === 1 ? "" : "s"})`],
			["Descargado", size(t.done)],
			["Subido", `${size(t.up_total)} · proporción ${nf2.format(t.ratio)}`],
			["Bajada", rate(t.down_rate) || "0 B/s"],
			["Subida", rate(t.up_rate) || "0 B/s"],
			["Tiempo rest.", eta(t.eta) || "—"],
			["Semillas / pares", `${t.seeds} / ${t.peers}`],
			["Agregado", date(t.added) || "—"],
			["Completado", date(t.finished) || "—"],
			["Creado", date(t.created) || "—"],
			["Prioridad", PRIO[t.priority] || ""],
			["Etiquetas", t.tags.map((x) => chip(x)).join(" ") || "—", true],
			["Carpeta", `<span class="folder-line"><span title="${esc(d && d.base_path || t.directory)}">${esc(d && d.base_path || t.directory)}</span>${canOpenFolder() ? toolBtn("folder-open", "Abrir", 'data-open-folder title="Abrir en el explorador de archivos"') : ""}</span>`, true],
			["Identificador", `<code title="Huella única del torrent (info-hash), la misma en toda la red">${t.hash}</code>${info("Identificador", "Huella única del torrent (info-hash), la misma en toda la red")}`, true],
			...(t.message ? [["Mensaje", `<span class="folder-line"><span title="${esc(messageText(t))}">${esc(messageText(t))}</span>${toolBtn("copy", "Copiar", 'data-copy-message title="Copiar el mensaje"')}</span>`, true]] : []),
			...(d && d.chunks ? [["Piezas", `${nf0.format(d.chunks)} × ${size(d.chunk_size)}`]] : []),
			["Privado", t.private ? "Sí" : "No"],
		]));
	} else if (!d) {
		setHTML(body, `<div class="placeholder">Cargando…</div>`);
	} else if (prefs.tab === "files") {
		setHTML(body, filesHTML(d.files));
	} else if (prefs.tab === "peers") {
		setHTML(body, peersHTML(d.peers));
	} else if (prefs.tab === "pieces") {
		setHTML(body, piecesHTML(t, d));
	} else if (prefs.tab === "trackers") {
		setHTML(body, trackersHTML(d.trackers));
	}
	body.scrollTop = keep;
	if (typing) {
		const el = $(`[data-tfilter="${typing[0]}"]`, body);
		if (el) { el.focus(); el.setSelectionRange(typing[1], typing[1]); }
	}
	fitSubtools(body);
	if (prefs.tab === "general") drawPieces($("#piece-map"), t, d);
	if (prefs.tab === "pieces" && d) drawPieceGrid($("#piece-grid"), t, d);
	if (state.tipEl && !state.tipEl.isConnected) { state.tipEl = null; hideTip(); }
}

// The tools above a detail table wrap to two lines in a narrow window: the sticky
// titles and the speed chart start right below them, at their real height.
function fitSubtools(body) {
	const st = $(".subtools", body);
	if (st) body.style.setProperty("--subtools-h", st.offsetHeight + "px");
}

// ------------------------------------------------------------ detail tables

// Archivos, Pares and Trackers sort by any column (a click sorts up, a second one
// down, a third goes back to the default order) and filter by text. The order is
// remembered; the filter lasts until the page is reloaded.
const tableFilter = {};
const tsortOf = (key) => prefs.tableSort[key] || null;
const tnorm = (x) => String(x ?? "").toLocaleLowerCase("es").normalize("NFD").replace(/[\u0300-\u036f]/g, "");
const tcmp = (a, b) => typeof a === "number" && typeof b === "number" ? a - b : String(a ?? "").localeCompare(String(b ?? ""), "es", { numeric: true });

function tableTools(key, extra = "") {
	const changed = tsortOf(key) || tableFilter[key] || prefs.tableW[key];
	return `<div class="subtools">${extra}<span class="spacer"></span>
		<label class="tfilter">${iconHTML("lucide:search")}<input type="search" data-tfilter="${key}" placeholder="Filtrar…" value="${esc(tableFilter[key] || "")}"></label>
		${changed ? toolBtn("rotate-ccw", "Vista por defecto", `data-treset="${key}" title="Orden y anchos originales, sin filtro"`) : ""}</div>`;
}
// The width a column needs to show its widest cell (title included) without cutting it:
// what a double click on a column edge sets.
function fitWidth(table, idx) {
	let need = 40;
	for (const row of table.rows) {
		const cell = row.cells[idx];
		if (!cell || cell.colSpan > 1) continue;
		let l = Infinity, r = -Infinity;
		for (const n of cell.childNodes) {
			if (n.nodeType === 1 && n.classList.contains("grip")) continue;
			let box;
			if (n.nodeType === 3) { const rg = document.createRange(); rg.selectNodeContents(n); box = rg.getBoundingClientRect(); }
			else if (n.nodeType === 1) box = n.getBoundingClientRect();
			if (box && box.width) { l = Math.min(l, box.left); r = Math.max(r, box.right); }
		}
		if (r < l) continue;
		const cs = getComputedStyle(cell);
		need = Math.max(need, r - l + parseFloat(cs.paddingLeft) + parseFloat(cs.paddingRight) + 2);
	}
	return Math.ceil(need);
}

// The opening of a detail table. Once a column has been widened by hand, every column
// keeps a fixed width (saved per table) and the table may scroll sideways, as the main list.
function tableOpen(key, cols, cls = "") {
	const w = prefs.tableW[key];
	if (!w) return `<table class="sub ${cls}">`;
	const px = (c) => w[c.id] || 100;
	return `<table class="sub fixed ${cls}" style="width:${cols.reduce((n, c) => n + px(c), 0)}px">` +
		`<colgroup>${cols.map((c) => `<col data-col="${c.id}" style="width:${px(c)}px">`).join("")}</colgroup>`;
}
function tableHead(key, cols) {
	const s = tsortOf(key);
	return `<thead><tr>${cols.map((c) => `<th class="sortable${c.num ? " num" : ""}" data-tsort="${key}" data-col="${c.id}" title="Ordenar por ${esc(c.title)}">${c.title}` +
		(s && s.col === c.id ? `<span class="arrow">${s.dir > 0 ? "▲" : "▼"}</span>` : "") +
		`<span class="grip" data-tgrip="${key}" title="Arrastra para cambiar el ancho"></span></th>`).join("")}</tr></thead>`;
}
// The rows that pass the filter, in the chosen order.
function tableRows(key, cols, rows) {
	const q = tnorm(tableFilter[key]), s = tsortOf(key);
	let out = q ? rows.filter((r) => cols.some((c) => tnorm(c.text ? c.text(r) : c.get(r)).includes(q))) : rows;
	const c = s && cols.find((x) => x.id === s.col);
	if (c) out = out.slice().sort((a, b) => tcmp(c.get(a), c.get(b)) * s.dir);
	return out;
}
function nextSort(key, col) {
	const s = tsortOf(key);
	prefs.tableSort[key] = !s || s.col !== col ? { col, dir: 1 } : s.dir > 0 ? { col, dir: -1 } : undefined;
	if (!prefs.tableSort[key]) delete prefs.tableSort[key];
	savePrefs();
}
const ipKey = (a) => /^\d+\.\d+\.\d+\.\d+$/.test(a) ? a.split(".").map((n) => n.padStart(3, "0")).join(".") : a;

const PEER_COLS = [
	{ id: "address", title: "Dirección", get: (p) => ipKey(p.address), text: (p) => `${p.address}:${p.port}` },
	{ id: "client", title: "Cliente", get: (p) => p.client },
	{ id: "progress", title: "Progreso", get: (p) => p.progress },
	{ id: "down", title: "Bajada", num: true, get: (p) => p.down_rate },
	{ id: "up", title: "Subida", num: true, get: (p) => p.up_rate },
	{ id: "flags", title: "Indicadores", get: (p) => (p.incoming ? "entrante" : "saliente") + (p.encrypted ? " cifrado" : "") },
];
const TRACKER_COLS = [
	{ id: "url", title: "URL", get: (r) => r.url },
	{ id: "enabled", title: "Activo", get: (r) => (r.enabled ? "Sí" : "No") },
	{ id: "seeds", title: "Semillas", num: true, get: (r) => r.seeds },
	{ id: "leechers", title: "Pares", num: true, get: (r) => r.leechers },
	{ id: "downloaded", title: "Descargas", num: true, get: (r) => r.downloaded },
];
// Files and folders of the tree share these: a folder sorts by its totals.
const FILE_COLS = [
	{ id: "name", title: "Archivo", get: (f) => f.name ?? f.path, text: (f) => f.path },
	{ id: "size", title: "Tamaño", num: true, get: (f) => f.size },
	{ id: "progress", title: "Progreso", get: (f) => f.progress ?? (f.size ? f.doneB / f.size : 1) },
	{ id: "priority", title: "Prioridad", get: (f) => f.priority ?? (f.prio ?? -1) },
];

function peersHTML(peers) {
	if (!peers.length) return `<div class="placeholder">Sin pares conectados</div>`;
	const rows = tableRows("peers", PEER_COLS, peers);
	return tableTools("peers", `<span class="dimtxt">${rows.length === peers.length ? peers.length : `${rows.length} de ${peers.length}`} pares</span>`) +
		`${tableOpen("peers", PEER_COLS)}${tableHead("peers", PEER_COLS)}<tbody>` +
		(rows.map((p) => `<tr><td>${esc(p.address)}:${p.port}</td><td>${esc(p.client)}</td><td>${nf1.format(p.progress * 100)} %</td>` +
			`<td class="num rate-down">${rate(p.down_rate)}</td><td class="num rate-up">${rate(p.up_rate)}</td><td>${p.encrypted ? iconHTML("lucide:lock", "ico dir-ico") : ""}${p.incoming ? "entrante" : "saliente"}</td></tr>`).join("") ||
			`<tr><td colspan="6" class="placeholder">Ningún par coincide con el filtro</td></tr>`) + "</tbody></table>";
}

function trackersHTML(trackers) {
	const rows = tableRows("trackers", TRACKER_COLS, trackers);
	return tableTools("trackers", toolBtn("plus", "Agregar rastreadores…", "data-add-tracker")) +
		`${tableOpen("trackers", TRACKER_COLS)}${tableHead("trackers", TRACKER_COLS)}<tbody>` +
		(rows.map((r) => `<tr><td>${esc(r.url)}</td><td>${r.enabled ? "Sí" : "No"}</td><td class="num">${r.seeds}</td><td class="num">${r.leechers}</td><td class="num">${r.downloaded}</td></tr>`).join("") ||
			`<tr><td colspan="5" class="placeholder">${trackers.length ? "Ningún rastreador coincide con el filtro" : "Sin rastreadores"}</td></tr>`) + "</tbody></table>";
}

// ------------------------------------------------------------ files tab

const FILE_PRIO = [[2, "Alta"], [1, "Normal"], [0, "No descargar"]];

function prioSelect(attr, prio) {
	const mixed = prio === null;
	return `<select ${attr}>${mixed ? '<option value="" selected disabled>Mixta</option>' : ""}` +
		FILE_PRIO.map(([v, n]) => `<option value="${v}"${prio === v ? " selected" : ""}>${n}</option>`).join("") + "</select>";
}
function fileBar(p) {
	return `<div class="bar ${p >= 1 ? "done" : ""}"><span style="width:${(p * 100).toFixed(1)}%"></span><em>${nf1.format(p * 100)} %</em></div>`;
}

// Folder tree from the file paths. Each node sums its files' sizes and
// done bytes; its priority is the shared one, or null when mixed.
function fileTree(files) {
	const root = { dirs: new Map(), files: [] };
	files.forEach((f, i) => {
		if (prefs.hideSkipped && f.priority === 0) return;
		const parts = f.path.split("/");
		let node = root;
		parts.slice(0, -1).forEach((p, k) => {
			if (!node.dirs.has(p)) node.dirs.set(p, { name: p, path: parts.slice(0, k + 1).join("/"), dirs: new Map(), files: [] });
			node = node.dirs.get(p);
		});
		node.files.push({ ...f, i, name: parts[parts.length - 1] });
	});
	const sum = (n) => {
		n.size = 0; n.doneB = 0; n.idx = []; n.prio = undefined;
		const mix = (p) => { n.prio = n.prio === undefined ? p : n.prio === p ? p : null; };
		for (const d of n.dirs.values()) { sum(d); n.size += d.size; n.doneB += d.doneB; n.idx.push(...d.idx); mix(d.prio); }
		for (const f of n.files) { n.size += f.size; n.doneB += f.size * f.progress; n.idx.push(f.i); mix(f.priority); }
	};
	sum(root);
	return root;
}

// ------------------------------------------------------------ play while downloading
// Only rtorrent-android's rTorrent has d.sequential (t.sequential is null elsewhere).
const MEDIA_EXT = /\.(mp4|m4v|mkv|webm|avi|mov|wmv|flv|ts|m2ts|mpg|mpeg|3gp|mp3|m4a|aac|flac|ogg|oga|opus|wav|wma)$/i;
const canStream = (h) => state.byHash.get(h)?.sequential != null;
const playBtn = (i, path) => canStream(detailHash()) && MEDIA_EXT.test(path)
	? `<button type="button" class="play-btn" data-play="${i}" title="Reproducir mientras se descarga">${iconHTML("lucide:play")}</button>` : "";

function playFile(h, i) {
	const f = state.details?.files?.[i];
	if (!f) return;
	const name = f.path.split("/").pop();
	const url = `${location.origin}/stream/${h}/${i}/${encodeURIComponent(name)}`;
	if (androidCan("openStream")) window.TableroAndroid.openStream(url, name);
	else window.open(url, "_blank", "noopener");
	toast("Descarga en orden activada: el reproductor espera las piezas que falten");
	setTimeout(refresh, 1500);
}

function filesHTML(files) {
	const skipped = files.filter((f) => f.priority === 0).length;
	const tools = tableTools("files", `
		${seg("view", [["tree", "Árbol", "list-tree"], ["list", "Lista", "list"]], prefs.filesTree ? "tree" : "list")}
		${toolBtn(prefs.hideSkipped ? "eye" : "eye-off", (prefs.hideSkipped ? "Mostrar" : "Ocultar") + ` los que no se descargarán${skipped ? ` (${skipped})` : ""}`,
			'data-toggle="hideSkipped"', prefs.hideSkipped)}
		${prefs.filesTree ? `${toolBtn("chevrons-up-down", "Expandir todo", 'data-tree="expand"')}${toolBtn("chevrons-down-up", "Contraer todo", 'data-tree="collapse"')}` : ""}`);
	const head = `${tableOpen("files", FILE_COLS, "files")}${tableHead("files", FILE_COLS)}<tbody>`;
	// Filtering keeps the files whose path matches, and in the tree the folders that hold them (opened).
	const q = tnorm(tableFilter.files), match = (f) => !q || tnorm(f.path).includes(q);
	const fs = tsortOf("files"), fcol = fs && FILE_COLS.find((c) => c.id === fs.col);
	const order = (list) => fcol ? list.slice().sort((a, b) => tcmp(fcol.get(a), fcol.get(b)) * fs.dir)
		: list.slice().sort((a, b) => a.name.localeCompare(b.name, "es", { numeric: true }));
	let rowsHTML = "";
	if (prefs.filesTree) {
		const shown = new Set(files.map((f, i) => match(f) ? i : -1).filter((i) => i >= 0));
		const walk = (node, depth) => {
			const dirs = order([...node.dirs.values()].filter((d) => d.idx.some((i) => shown.has(i))));
			for (const d of dirs) {
				const open = q || !state.collapsed.has(d.path);
				rowsHTML += `<tr class="dir" data-dirpath="${esc(d.path)}"><td class="path" style="padding-left:${8 + depth * 18}px"><span class="twisty" data-dir="${esc(d.path)}">${open ? "▾" : "▸"}</span>${iconHTML("lucide:folder", "ico dir-ico")}${esc(d.name)}</td>` +
					`<td class="num">${size(d.size)}</td><td>${fileBar(d.size ? d.doneB / d.size : 1)}</td><td>${prioSelect(`data-files="${d.idx.join(",")}"`, d.prio)}</td></tr>`;
				if (open) walk(d, depth + 1);
			}
			for (const f of order(node.files.filter((f) => shown.has(f.i))))
				rowsHTML += `<tr class="${f.priority === 0 ? "skipped" : ""}" data-file="${f.i}"><td class="path" style="padding-left:${26 + depth * 18}px">${playBtn(f.i, f.path)}${esc(f.name)}</td><td class="num">${size(f.size)}</td>` +
					`<td>${fileBar(f.progress)}</td><td>${prioSelect(`data-files="${f.i}"`, f.priority)}</td></tr>`;
		};
		walk(fileTree(files), 0);
	} else {
		const list = files.map((f, i) => ({ ...f, i })).filter((f) => !(prefs.hideSkipped && f.priority === 0) && match(f));
		(fcol ? list.sort((a, b) => tcmp(fcol.get(a), fcol.get(b)) * fs.dir) : list).forEach((f) => {
			const i = f.i;
			rowsHTML += `<tr class="${f.priority === 0 ? "skipped" : ""}" data-file="${i}"><td class="path">${playBtn(i, f.path)}${esc(f.path)}</td><td class="num">${size(f.size)}</td>` +
				`<td>${fileBar(f.progress)}</td><td>${prioSelect(`data-files="${i}"`, f.priority)}</td></tr>`;
		});
	}
	if (!rowsHTML) rowsHTML = `<tr><td colspan="4" class="placeholder">${q ? "Ningún archivo coincide con el filtro" : 'Todos los archivos están marcados como "No descargar"'}</td></tr>`;
	return tools + head + rowsHTML + "</tbody></table>";
}

function allDirs(files) {
	const out = new Set();
	files.forEach((f) => { const p = f.path.split("/"); for (let k = 1; k < p.length; k++) out.add(p.slice(0, k).join("/")); });
	return out;
}

// ------------------------------------------------------------ options tab

const OPT_HELP = {
	priority: "Cuánto ancho de banda recibe este torrent frente a los demás. \u201cDesactivada\u201d lo deja sin transferir.",
	speed: "Tope solo para este torrent. 0 = sin tope propio; el límite general sigue aplicando igual.",
	peers: "Cuántos pares puede tener conectados a la vez este torrent, y cuántos intenta mantener como mínimo.",
	uploads: "A cuántos pares les envía datos al mismo tiempo. Menos subidas concentran la velocidad en menos pares.",
	seed: "Tablero detiene el torrent cuando cumple la condición. Funciona mientras Tablero esté corriendo. Si vuelves a iniciarlo a mano, no lo detiene de nuevo hasta que cambies esta regla.",
	general: "Vacío = el valor general de Límites y conexiones (clic en las velocidades de arriba), que se ve en gris. Un número aquí vale solo para este torrent.",
};

// Límites y conexiones: totals for all torrents together and the general Al sembrar rule.
// "rtorrent" holds rTorrent's own totals and "torrent" each torrent's Conexiones from
// rtorrent.rc, shown where a field is left empty.
async function loadGeneral() {
	try { state.general = await api("/api/general"); } catch { /* keep the last one */ }
	return state.general;
}
// A torrent's Conexiones when it has none of its own: the general ones (the minimum is half
// the maximum), else rTorrent's.
function generalPeer(k) {
	const g = state.general;
	if (!g) return null;
	const v = k === "peers_min" ? (g.general.peers_max != null ? Math.floor(g.general.peers_max / 2) : null) : g.general[k];
	return v ?? g.torrent?.[k] ?? null;
}
const seedOwn = (o) => ("seed_own" in o ? !!o.seed_own : !!(o.stop_ratio || o.stop_hours));
function seedRuleText(r) {
	const parts = [];
	if (r.stop_ratio) parts.push("al llegar a una proporción de " + r.stop_ratio.toLocaleString("es-CL"));
	if (r.stop_hours) parts.push(`después de ${r.stop_hours.toLocaleString("es-CL")} horas sembrando`);
	return parts.length ? "Detener " + parts.join(" o ") : "Sin regla: sigue sembrando";
}

function optionsHTML(t) {
	const o = t.opts || {};
	const kib = (b) => Math.round((b || 0) / KB()), unit = UNITS()[1] + "/s";
	const help = (k) => `<span class="help" data-tip-title="Ayuda" data-tip="${esc(OPT_HELP[k])}">?</span>`;
	const num = (id, v, unit, step = 1) => `<label class="num-in"><input type="number" id="${id}" min="0" step="${step}" value="${v}"><span>${unit}</span></label>`;
	// Empty = the general value, shown as the placeholder.
	const gnum = (id, v, g) => `<label class="num-in"><input type="number" id="${id}" min="0" step="1" value="${v}" placeholder="${g ?? ""}"><span>pares</span></label>`;
	const own = seedOwn(o);
	return `<form id="opt-form" class="form-grid opt-grid" autocomplete="off">
		<div class="frow"><span class="fl">Prioridad ${help("priority")}</span><div>
			<span class="seg">${[[3, "Alta"], [2, "Normal"], [1, "Baja"], [0, "Desactivada"]].map(([v, n]) =>
				`<label><input type="radio" name="opt-prio" value="${v}"${t.priority === v ? " checked" : ""}><span>${n}</span></label>`).join("")}</span>
		</div></div>
		<div class="frow"><span class="fl">Velocidad ${help("speed")}</span><div class="opt-cols">
			<div><span class="sub-l">Bajada máxima</span>${num("opt-down", kib(o.down_limit), unit)}</div>
			<div><span class="sub-l">Subida máxima</span>${num("opt-up", kib(o.up_limit), unit)}</div>
		</div></div>
		<div class="frow"><span class="fl">Conexiones ${help("peers")}</span><div>
			<div class="opt-cols">
				${[["opt-pmax", "peers_max", "Pares máximos"], ["opt-pmin", "peers_min", "Pares mínimos"], ["opt-umax", "uploads_max", `Subidas simultáneas ${help("uploads")}`]].map(([id, k, label]) =>
					`<div><span class="sub-l">${label}</span>${gnum(id, k in o ? o[k] : "", generalPeer(k))}</div>`).join("")}
			</div>
			<div class="opt-general">${toolBtn("rotate-ccw", "Usar general", 'id="opt-peers-general"')}<span class="hint">${esc(OPT_HELP.general)}</span></div>
		</div></div>
		<div class="frow"><span class="fl">Al sembrar ${help("seed")}</span><div class="opt-rules">
			<span class="seg">${[[0, "General"], [1, "Propia de este torrent"]].map(([v, n]) =>
				`<label><input type="radio" name="opt-seed" value="${v}"${+own === v ? " checked" : ""}><span>${n}</span></label>`).join("")}</span>
			<div id="opt-seed-general" class="hint"${own ? " hidden" : ""}>${esc(seedRuleText(state.general?.general || {}))}. Se cambia en Límites y conexiones.</div>
			<div id="opt-seed-own"${own ? "" : " hidden"}>
			<label class="check"><input type="checkbox" id="opt-r-on"${own && o.stop_ratio ? " checked" : ""}> Detener al llegar a una proporción de subida de ${num("opt-ratio", (own && o.stop_ratio) || 2, "", 0.1)}</label>
			<label class="check"><input type="checkbox" id="opt-h-on"${own && o.stop_hours ? " checked" : ""}> Detener después de ${num("opt-hours", (own && o.stop_hours) || 24, "horas sembrando", 0.5)}</label>
			</div>
			${o.rule_done ? '<div class="hint">Este torrent ya se detuvo una vez por su regla de siembra.</div>' : ""}
		</div></div>
		<div class="opt-foot"><span id="opt-status" class="dimtxt"></span><span class="spacer"></span>
			<button type="button" class="btn" id="opt-reset">Deshacer cambios</button>
			<button type="submit" class="btn primary">Aplicar</button></div>
	</form>`;
}

async function saveOptions(t) {
	const v = (id) => +$("#" + id).value || 0;
	const own = (id) => ($("#" + id).value.trim() === "" ? null : Math.max(0, Math.round(+$("#" + id).value || 0)));
	const seed = $('input[name="opt-seed"]:checked')?.value === "1";
	const options = {
		priority: +($('input[name="opt-prio"]:checked')?.value ?? t.priority),
		down_limit: v("opt-down") * KB(), up_limit: v("opt-up") * KB(),
		peers_max: own("opt-pmax"), peers_min: own("opt-pmin"), uploads_max: own("opt-umax"),
		seed_own: seed,
		stop_ratio: seed && $("#opt-r-on").checked ? v("opt-ratio") : 0,
		stop_hours: seed && $("#opt-h-on").checked ? v("opt-hours") : 0,
	};
	// Checked with what the torrent will have, its own value or the general one.
	const eff = (k) => options[k] ?? generalPeer(k);
	if (eff("peers_min") != null && eff("peers_max") != null && eff("peers_min") > eff("peers_max") &&
		(options.peers_min != null || options.peers_max != null))
		return toast("Los pares mínimos no pueden superar a los máximos", true);
	$("#opt-status").textContent = "Aplicando…";
	try {
		await api("/api/options", { hash: t.hash, options });
		$("#opt-status").textContent = "";
		toast("Opciones aplicadas a " + t.name);
		state.optsHash = null;
		refresh();
	} catch (e) {
		$("#opt-status").textContent = "";
		toast(e.message, true);
	}
}

// ------------------------------------------------------------ tooltip

function showTip(x, y, html) {
	const tip = $("#tip");
	tip.innerHTML = html;
	tip.hidden = false;
	const r = tip.getBoundingClientRect();
	tip.style.left = Math.max(6, Math.min(x + 14, innerWidth - r.width - 6)) + "px";
	tip.style.top = (y + 18 + r.height > innerHeight ? y - r.height - 10 : y + 18) + "px";
}
function hideTip() { $("#tip").hidden = true; }
// The legend item is what shows the text; the "i" only marks it on a touch screen.
const legendInfo = () => (touchUI() ? '<span class="info" aria-hidden="true">i</span>' : "");
function statusTip(t) {
	return t.status === "queued" ? "Espera su turno: rTorrent verifica un torrent a la vez" : t.status === "waiting" ? "Espera su turno: Tablero reubica un torrent a la vez" : messageText(t);
}

async function softReload(hint) {
	if (hint.dataset.busy) return;
	hint.dataset.busy = "1";
	hint.textContent = "Actualizando…";
	hint.classList.add("ready");
	hint.style.transform = "translate(-50%, 55px)";
	hint.hidden = false;
	const t0 = Date.now();
	try {
		try { state.tagConf = await api("/api/tags"); } catch { /* keep the last ones */ }
		await loadGeneral();
		state.detailsFor = null;
		state.historyKey = null;
		await refresh();
	} finally {
		// Long enough to be seen, so it is clear that it did refresh.
		setTimeout(() => { delete hint.dataset.busy; hint.hidden = true; hint.classList.remove("ready"); }, Math.max(0, 600 - (Date.now() - t0)));
	}
}

// Touch: tapping an "i" (or a legend, or a "?") shows its text; a tap anywhere else hides it.
function wireTouchTips() {
	document.addEventListener("click", (e) => {
		const el = e.target.closest?.("[data-tip]");
		if (!el || el.matches("button")) return;
		e.preventDefault();
		e.stopPropagation();
		if (state.tipEl === el && !$("#tip").hidden) return hideTip();
		const r = el.getBoundingClientRect();
		showTip(r.left, r.bottom - 12, `<b>${esc(el.dataset.tipTitle || "")}</b><div>${esc(el.dataset.tip)}</div>`);
		state.tipEl = el;
	}, true);
	document.addEventListener("pointerdown", (e) => {
		if ($("#tip").hidden || e.target.closest?.("[data-tip]") || e.target === state.tipCanvas) return;
		state.tipCanvas?.onmouseleave?.();
		state.tipCanvas = null;
		hideTip();
	}, true);
	// The table and panels scroll under it.
	document.addEventListener("scroll", () => { if (!$("#tip").hidden && !state.tipCanvas) hideTip(); }, true);
}

// Touch: two fingers dragged down together refresh everything. One finger scrolls the list and a
// pinch (fingers moving apart or together) zooms, so neither gets in the way. It fetches again
// in place instead of reloading the page, which would blank the screen for a moment.
function wireTwoFingerReload() {
	const hint = document.createElement("div");
	hint.id = "reload-hint";
	hint.hidden = true;
	document.body.append(hint);
	let start = null, ready = false;
	const pts = (e) => [...e.touches].slice(0, 2).map((t) => [t.clientX, t.clientY]);
	const gap = (p) => Math.hypot(p[0][0] - p[1][0], p[0][1] - p[1][1]);
	const reset = () => { start = null; ready = false; if (!hint.dataset.busy) hint.hidden = true; };
	document.addEventListener("touchstart", (e) => {
		const zoomed = window.visualViewport && visualViewport.scale > 1.05;   // two fingers then pan the zoomed page
		if (e.touches.length === 2 && !zoomed && !modalOpen()) start = pts(e); else reset();
	}, { passive: true });
	document.addEventListener("touchmove", (e) => {
		if (!start || e.touches.length !== 2) return;
		const p = pts(e);
		if (Math.abs(gap(p) - gap(start)) > 50) return reset();   // a pinch
		const dy = Math.min(p[0][1] - start[0][1], p[1][1] - start[1][1]);
		ready = dy > 110;
		hint.hidden = dy < 25;
		hint.classList.toggle("ready", ready);
		hint.textContent = ready ? "Suelta para recargar" : "Sigue bajando para recargar";
		hint.style.transform = `translate(-50%, ${Math.min(dy, 110) * 0.5}px)`;
	}, { passive: true });
	document.addEventListener("touchend", () => { const go = ready; reset(); if (go) softReload(hint); });
	document.addEventListener("touchcancel", reset);
}

// ------------------------------------------------------------ pieces tab

const PIECE_CATS = {
	done: { name: "Completa", tip: "Pieza descargada y verificada. Ya está en tu disco y puedes compartirla con otros." },
	partial: { name: "Parcial", tip: `El cuadro agrupa varias piezas y solo algunas están completas. ${touchUI() ? "Toca" : "Pasa el mouse sobre"} el mapa para ver el detalle.` },
	avail: { name: "Disponible en pares", tip: "Te falta esta pieza, pero al menos un par conectado la tiene, así que se puede descargar ahora." },
	nosource: { name: "Sin fuentes", tip: "Te falta esta pieza y ningún par conectado la tiene. El torrent no se puede completar hasta que aparezca alguien con ella." },
	missing: { name: "Falta", tip: "Te falta esta pieza. Con el torrent detenido o en pausa no hay pares conectados, así que no se sabe quién la tiene." },
	skipped: { name: "Omitida", tip: "Pertenece solo a archivos marcados como \u201cNo descargar\u201d en la pestaña Archivos, así que no se descargará." },
};
const PIECE_CELL = 8, PIECE_GAP = 2, PIECE_ROWS = 48;

// Per-piece facts from the details: have, copies seen, skipped.
function pieceData(d) {
	const n = d.chunks, hex = d.bitfield || "", seen = d.seen || "";
	const have = (i) => (parseInt(hex[i >> 2], 16) >> (3 - (i & 3))) & 1;
	const copies = seen ? (i) => parseInt(seen.substr(i * 2, 2), 16) : () => null;
	const skip = new Uint8Array(n);
	const ranged = d.files.filter((f) => f.range);
	ranged.forEach((f) => { if (f.priority === 0) for (let i = f.range[0]; i < f.range[1] && i < n; i++) skip[i] = 1; });
	ranged.forEach((f) => { if (f.priority !== 0) for (let i = f.range[0]; i < f.range[1] && i < n; i++) skip[i] = 0; });
	const cat = (i) => (have(i) ? "done" : skip[i] ? "skipped" : !seen ? "missing" : copies(i) > 0 ? "avail" : "nosource");
	return { n, have, copies, cat };
}

function piecesHTML(t, d) {
	if (!d) return `<div class="placeholder">Cargando…</div>`;
	if (!d.chunks || !d.bitfield) return `<div class="placeholder">rTorrent aún no tiene el mapa de piezas de este torrent</div>`;
	const p = pieceData(d), counts = {};
	for (let i = 0; i < p.n; i++) { const c = p.cat(i); counts[c] = (counts[c] || 0) + 1; }
	const cols = Math.max(1, Math.floor(($("#details-body").clientWidth - 32 + PIECE_GAP) / (PIECE_CELL + PIECE_GAP)));
	const k = Math.ceil(p.n / (cols * PIECE_ROWS));
	const shown = ["done", ...(k > 1 ? ["partial"] : []), ...(d.seen ? ["avail", "nosource"] : ["missing"]), "skipped"];
	const legend = shown.map((c) => `<span class="legend-item" data-tip-title="${PIECE_CATS[c].name}" data-tip="${esc(PIECE_CATS[c].tip)}">${legendInfo()}` +
		`<i class="sw pc-${c}"></i>${PIECE_CATS[c].name}${c === "partial" ? "" : ` <b>${nf0.format(counts[c] || 0)}</b>`}</span>`).join("");
	return `<div class="subtools">${legend}<span class="spacer"></span>
		<span class="dimtxt">${nf0.format(p.n)} piezas de ${size(d.chunk_size)}${k > 1 ? ` · cada cuadro = ${k} piezas` : ""} · disponibilidad ${nf2.format(d.availability)}</span></div>
		<div class="piece-wrap"><canvas id="piece-grid"></canvas></div>`;
}

// Resizing a canvas reallocates it and leaves it blank until the next paint, which
// showed as a blink on every refresh in a wide (maximized) window. Only resize
// when the size really changed; otherwise just clear it and draw again.
function canvasCtx(canvas, w, h) {
	const dpr = devicePixelRatio || 1, W = Math.round(w * dpr), H = Math.round(h * dpr);
	if (canvas.width !== W) canvas.width = W;
	if (canvas.height !== H) canvas.height = H;
	const g = canvas.getContext("2d");
	g.setTransform(1, 0, 0, 1, 0, 0);
	g.clearRect(0, 0, W, H);
	g.setTransform(dpr, 0, 0, dpr, 0, 0);
	return g;
}

function drawPieceGrid(canvas, t, d) {
	if (!canvas || !d.chunks || !d.bitfield) return;
	const p = pieceData(d), pitch = PIECE_CELL + PIECE_GAP;
	// The room inside the padding: clientWidth counts the padding, and the grid was
	// 32 px too wide, its last column cut and the panel scrolling sideways.
	const wrap = canvas.parentElement, ws = getComputedStyle(wrap);
	const w = wrap.clientWidth - parseFloat(ws.paddingLeft) - parseFloat(ws.paddingRight);
	const cols = Math.max(1, Math.floor((w + PIECE_GAP) / pitch));
	const k = Math.ceil(p.n / (cols * PIECE_ROWS)), cells = Math.ceil(p.n / k), rows = Math.ceil(cells / cols);
	const h = rows * pitch, dpr = devicePixelRatio || 1;
	canvas.style.width = w + "px";
	canvas.style.height = h + "px";
	const g = canvasCtx(canvas, w, h);
	const css = getComputedStyle(document.documentElement);
	const color = Object.fromEntries(Object.keys(PIECE_CATS).map((c) => [c, css.getPropertyValue("--pc-" + c).trim()]));
	const cellCat = [];
	for (let c = 0; c < cells; c++) {
		const a = c * k, b = Math.min(p.n, a + k);
		let done = 0;
		const tally = {};
		for (let i = a; i < b; i++) { const x = p.cat(i); if (x === "done") done++; else tally[x] = (tally[x] || 0) + 1; }
		const cat = done === b - a ? "done" : done ? "partial" : Object.entries(tally).sort((x, y) => y[1] - x[1])[0][0];
		cellCat.push(cat);
		g.fillStyle = color[cat];
		const x = (c % cols) * pitch, y = Math.floor(c / cols) * pitch;
		g.beginPath();
		g.roundRect ? g.roundRect(x, y, PIECE_CELL, PIECE_CELL, 1.5) : g.rect(x, y, PIECE_CELL, PIECE_CELL);
		g.fill();
	}
	canvas.onmousemove = (e) => {
		const r = canvas.getBoundingClientRect();
		const col = Math.floor((e.clientX - r.left) / pitch), row = Math.floor((e.clientY - r.top) / pitch);
		const c = row * cols + col;
		if (col >= cols || c >= cells || c < 0) return hideTip();
		const a = c * k, b = Math.min(p.n, a + k);
		let done = 0, lo = Infinity, hi = -Infinity;
		for (let i = a; i < b; i++) { done += p.have(i); const s = p.copies(i); if (s !== null) { lo = Math.min(lo, s); hi = Math.max(hi, s); } }
		const files = d.files.filter((f) => f.range && f.range[0] < b && f.range[1] > a).map((f) => f.path.split("/").pop());
		const cat = PIECE_CATS[cellCat[c]];
		showTip(e.clientX, e.clientY,
			`<b>${b - a > 1 ? `Piezas ${nf0.format(a + 1)}–${nf0.format(b)}` : `Pieza ${nf0.format(a + 1)}`}</b>` +
			`<div><i class="sw pc-${cellCat[c]}"></i>${cat.name}${b - a > 1 ? ` · ${done} de ${b - a} completas` : ""}</div>` +
			(lo !== Infinity ? `<div>Copias en pares conectados: ${lo === hi ? lo : `${lo}–${hi}`}</div>` : "") +
			(files.length ? `<div class="dimtxt">${esc(files.slice(0, 3).join(", "))}${files.length > 3 ? ` y ${files.length - 3} más` : ""}</div>` : ""));
	};
	canvas.onmouseleave = hideTip;
	canvas.onpointerdown = (e) => { if (e.pointerType !== "mouse") { state.tipCanvas = canvas; canvas.onmousemove(e); } };
}

// ------------------------------------------------------------ speed tab

const SPEED_RANGES = [[300, "5 min"], [1800, "30 min"], [3600, "1 h"]];
const SPEED_SERIES = {
	down: { name: "Bajada", tip: "Velocidad a la que recibes datos de otros pares (descarga)." },
	up: { name: "Subida", tip: "Velocidad a la que envías datos a otros pares (lo que compartes)." },
	dlim: { name: "Límite de bajada", tip: "Tope de descarga configurado (clic en las velocidades de arriba para cambiarlo). La bajada no pasa de esta línea." },
	ulim: { name: "Límite de subida", tip: "Tope de subida configurado (clic en las velocidades de arriba para cambiarlo). La subida no pasa de esta línea." },
};

// Velocidad tab: the selected torrent by default, or all torrents together. (speedOf replaces the old
// speedScope pref, which started on "total" and was stored that way.)
function historyKey(t) { return prefs.speedOf === "total" ? "total" : t && t.hash; }

async function loadHistory() {
	if (prefs.tab !== "speed") return;
	const t = state.byHash.get(detailHash()), key = historyKey(t);
	if (!key) return;
	try {
		const data = await api("/api/history" + (key === "total" ? "" : "?hash=" + encodeURIComponent(key)));
		state.history = data;
		state.historyKey = key;
		if (prefs.tab === "speed") renderDetails();
	} catch { /* next refresh */ }
}

// Lines to draw and their legend; the limits only exist for the total of all torrents.
function speedLegend(samples, total) {
	const last = samples[samples.length - 1];
	const series = ["down", "up", ...(total && last && last[3] ? ["dlim"] : []), ...(total && last && last[4] ? ["ulim"] : [])];
	const value = (s) => !last ? "" : s === "down" ? rate(last[1]) || "0 B/s" : s === "up" ? rate(last[2]) || "0 B/s" : s === "dlim" ? rate(last[3]) : rate(last[4]);
	const legend = series.map((s) => `<span class="legend-item" data-tip-title="${SPEED_SERIES[s].name}" data-tip="${esc(SPEED_SERIES[s].tip)}">${legendInfo()}` +
		`<i class="ln ln-${s}"></i>${SPEED_SERIES[s].name} <b>${value(s)}</b></span>`).join("");
	return { series, legend };
}

function renderSpeed(body, t) {
	const key = historyKey(t);
	const total = prefs.speedOf === "total";
	const samples = state.historyKey === key && state.history ? state.history.samples : [];
	const { series, legend } = speedLegend(samples, total);
	setHTML(body, `<div class="subtools">
		${seg("scope", [["torrent", "Torrent seleccionado"], ["total", "Total"]], prefs.speedOf)}
		${seg("range", SPEED_RANGES, prefs.speedRange)}
		<span class="spacer"></span>${legend}</div>
		<div class="chart-wrap">${!key ? `<div class="placeholder">Selecciona un torrent para ver su velocidad</div>` :
			samples.length < 2 ? `<div class="placeholder">Juntando datos…</div>` : `<canvas id="speed-chart"></canvas>`}</div>`);
	fitSubtools(body);
	const canvas = $("#speed-chart");
	if (canvas) drawSpeed(canvas, samples, series);
}

function niceStep(max, ticks) {
	const k = KB(), raw = max / ticks, unit = k ** Math.max(0, Math.floor(Math.log(raw) / Math.log(k)));
	const m = raw / unit;
	return unit * ([1, 2, 5, 10, 20, 50, 100, 200, 500, 1000].find((s) => s >= m) || 1000);
}

function drawSpeed(canvas, samples, series) {
	const wrap = canvas.parentElement, w = wrap.clientWidth, h = Math.max(120, wrap.clientHeight);
	const dpr = devicePixelRatio || 1;
	canvas.style.width = w + "px";
	canvas.style.height = h + "px";
	const g = canvasCtx(canvas, w, h);
	const css = getComputedStyle(document.documentElement), v = (n) => css.getPropertyValue(n).trim();
	const now = samples[samples.length - 1][0], t0 = now - prefs.speedRange;
	const pts = samples.filter((s) => s[0] >= t0);
	const col = { 1: "--sp-down", 2: "--sp-up", 3: "--sp-down", 4: "--sp-up" };
	const idx = { down: 1, up: 2, dlim: 3, ulim: 4 };
	// The scale follows the real speeds. A limit far above them would squash the lines
	// against the bottom, so it is left out of the scale and named at the top instead.
	let max = 1024;
	pts.forEach((s) => ["down", "up"].forEach((k) => (max = Math.max(max, s[idx[k]]))));
	const lim = (k) => Math.max(...pts.map((s) => s[idx[k]]));
	const off = series.filter((k) => k.endsWith("lim") && lim(k) > max * 2);
	series = series.filter((k) => !off.includes(k));
	series.forEach((k) => pts.forEach((s) => (max = Math.max(max, s[idx[k]]))));
	const step = niceStep(max * 1.1, 4);
	max = Math.ceil(max * 1.1 / step) * step;
	g.font = "11px system-ui, sans-serif";
	const labelW = Math.max(...[0, 1, 2, 3, 4].map((i) => g.measureText(rate(i * step) || "0").width)) + 10;
	const L = 16 + labelW, R = w - 16, T = 12, B = h - 24;
	const X = (ts) => L + (ts - t0) / prefs.speedRange * (R - L), Y = (val) => B - val / max * (B - T);
	const draw = (hover) => {
		g.setTransform(dpr, 0, 0, dpr, 0, 0);
		g.clearRect(0, 0, w, h);
		// grid + y labels
		g.strokeStyle = v("--line");
		g.fillStyle = v("--dim");
		g.lineWidth = 1;
		g.textAlign = "right";
		g.textBaseline = "middle";
		for (let val = 0; val <= max + 1; val += step) {
			const y = Math.round(Y(val)) + 0.5;
			g.globalAlpha = val ? 0.5 : 1;
			g.beginPath(); g.moveTo(L, y); g.lineTo(R, y); g.stroke();
			g.globalAlpha = 1;
			g.fillText(val ? rate(val) : "0", L - 8, y);
		}
		// x labels: clock times on round minutes, spaced so they don't overlap in a narrow chart
		const clock = (ts) => new Date(ts * 1000).toLocaleTimeString("es-CL", { hour: "2-digit", minute: "2-digit" });
		const gap = g.measureText(clock(now)).width + 16;
		const every = [60, 120, 300, 600, 900, 1200, 1800, 3600].find((e) =>
			e >= (prefs.speedRange <= 300 ? 60 : prefs.speedRange <= 1800 ? 300 : 600) && e / prefs.speedRange * (R - L) >= gap) || 3600;
		g.textAlign = "center";
		g.textBaseline = "top";
		for (let ts = Math.ceil(t0 / every) * every; ts <= now; ts += every) {
			g.fillText(clock(ts), X(ts), B + 6);
		}
		// series: areas first, then lines; limits dashed
		const line = (k, area) => {
			const i = idx[k];
			g.beginPath();
			pts.forEach((s, n) => (n ? g.lineTo(X(s[0]), Y(s[i])) : g.moveTo(X(s[0]), Y(s[i]))));
			if (area) {
				g.lineTo(X(pts[pts.length - 1][0]), B); g.lineTo(X(pts[0][0]), B); g.closePath();
				g.fillStyle = v(col[i]); g.globalAlpha = 0.12; g.fill(); g.globalAlpha = 1;
				return;
			}
			g.strokeStyle = v(col[i]);
			g.lineWidth = k.endsWith("lim") ? 1.5 : 2;
			g.setLineDash(k.endsWith("lim") ? [6, 4] : []);
			g.lineJoin = "round";
			g.stroke();
			g.setLineDash([]);
		};
		["down", "up"].forEach((k) => line(k, true));
		series.forEach((k) => line(k, false));
		g.textAlign = "right";
		g.textBaseline = "top";
		off.forEach((k, n) => {
			g.fillStyle = v(col[idx[k]]);
			g.fillText(`${SPEED_SERIES[k].name}: ${rate(lim(k))}, fuera de escala ↑`, R - 4, T + 2 + n * 15);
		});
		if (hover) {
			const x = X(hover[0]);
			g.strokeStyle = v("--dim");
			g.lineWidth = 1;
			g.beginPath(); g.moveTo(Math.round(x) + 0.5, T); g.lineTo(Math.round(x) + 0.5, B); g.stroke();
			["down", "up"].forEach((k) => {
				g.beginPath();
				g.arc(x, Y(hover[idx[k]]), 4, 0, Math.PI * 2);
				g.fillStyle = v(col[idx[k]]); g.fill();
				g.lineWidth = 2; g.strokeStyle = v("--bg"); g.stroke();
			});
		}
	};
	draw(null);
	canvas.onmousemove = (e) => {
		const r = canvas.getBoundingClientRect(), mx = e.clientX - r.left;
		if (mx < L || mx > R) { draw(null); return hideTip(); }
		const ts = t0 + (mx - L) / (R - L) * prefs.speedRange;
		let best = pts[0];
		for (const s of pts) if (Math.abs(s[0] - ts) < Math.abs(best[0] - ts)) best = s;
		draw(best);
		showTip(e.clientX, e.clientY, `<b>${new Date(best[0] * 1000).toLocaleTimeString("es-CL")}</b>` +
			`<div><i class="ln ln-down"></i>Bajada ${rate(best[1]) || "0 B/s"}</div><div><i class="ln ln-up"></i>Subida ${rate(best[2]) || "0 B/s"}</div>`);
	};
	canvas.onmouseleave = () => { draw(null); hideTip(); };
	canvas.onpointerdown = (e) => { if (e.pointerType !== "mouse") { state.tipCanvas = canvas; canvas.onmousemove(e); } };
}

// Downloaded pieces, BiglyBT style: each pixel column shows how many of
// its pieces are done. Without the bitfield yet, a plain progress fill.
function drawPieces(canvas, t, d) {
	const w = canvas.clientWidth, h = canvas.clientHeight, dpr = devicePixelRatio || 1;
	const g = canvasCtx(canvas, w, h);
	const css = getComputedStyle(document.documentElement);
	g.fillStyle = css.getPropertyValue("--bar-bg");
	g.fillRect(0, 0, w, h);
	g.fillStyle = css.getPropertyValue(t.complete ? "--good" : "--down");
	const hex = d && d.bitfield, n = d && d.chunks;
	if (!hex || !n) {
		g.fillRect(0, 0, w * (t.size ? t.done / t.size : 1), h);
		return;
	}
	const bit = (i) => (parseInt(hex[i >> 2], 16) >> (3 - (i & 3))) & 1;
	for (let x = 0; x < w; x++) {
		const a = Math.floor(x * n / w), b = Math.max(a + 1, Math.floor((x + 1) * n / w));
		let done = 0;
		for (let i = a; i < b; i++) done += bit(i);
		if (!done) continue;
		g.globalAlpha = done / (b - a);
		g.fillRect(x, 0, 1, h);
	}
	g.globalAlpha = 1;
}

// ------------------------------------------------------------ polling

let timer = null;
async function refresh() {
	clearTimeout(timer);
	try {
		const data = await api("/api/torrents");
		state.torrents = data.torrents;
		state.byHash = new Map(data.torrents.map((t) => [t.hash, t]));
		state.stats = data.stats;
		state.platform = data.platform || "desktop";
		state.ok = true;
		reportJobs();
		const c = $("#st-conn");
		c.className = "";
		c.textContent = `Tablero ${data.tablero || ""} · conectado a rTorrent ${data.stats.version || ""}`;
		c._html = null;
		renderPort(data.stats);
		if (!menuOpen() && !state.dragging) render();
		if (prefs.tab !== "general") state.detailsFor = null;
		if (prefs.tab === "speed") loadHistory(); else loadDetails();
		loadLimitsHistory();
	} catch (e) {
		state.ok = false;
		const c = $("#st-conn");
		$("#st-port").hidden = true;
		c.className = "err";
		const html = /conectar con rTorrent/.test(e.message) ? `Sin conexión con rTorrent: está apagado o no responde. Para encenderlo: ${rtorrentCmdHTML("start")}` : esc(e.message);
		if (c._html !== html) { c.innerHTML = html; c._html = html; }
	}
	timer = setTimeout(refresh, Math.max(1, prefs.interval) * 1000);
}

// ------------------------------------------------------------ selection

function selected() { return [...state.sel].filter((h) => state.byHash.has(h)); }

function clickRow(e, tr) {
	const h = tr.dataset.hash;
	if (e.shiftKey && state.anchor) {
		const a = rows.findIndex((t) => t.hash === state.anchor), b = rows.findIndex((t) => t.hash === h);
		if (!e.ctrlKey && !e.metaKey) state.sel.clear();
		rows.slice(Math.min(a, b), Math.max(a, b) + 1).forEach((t) => state.sel.add(t.hash));
	} else if (e.ctrlKey || e.metaKey) {
		state.sel.has(h) ? state.sel.delete(h) : state.sel.add(h);
		state.anchor = h;
	} else {
		state.sel.clear();
		state.sel.add(h);
		state.anchor = h;
	}
	state.focus = h;
	afterSelect();
}
function afterSelect() {
	$$("#torrents tbody tr").forEach((r) => r.classList.toggle("sel", state.sel.has(r.dataset.hash)));
	renderStatus();
	renderDetails();
	loadDetails();
	loadHistory();
}

// ------------------------------------------------------------ menus

function menuOpen() { return !$("#menu").hidden; }
function closeMenu() { $("#menu").hidden = true; }

// items: [{label, k, chk, disabled, run, html}] | "-" | {head}
function openMenu(x, y, items) {
	const m = $("#menu");
	m.innerHTML = "";
	for (const it of items) {
		if (it === "-") { m.insertAdjacentHTML("beforeend", '<div class="hr"></div>'); continue; }
		if (it.head) { m.insertAdjacentHTML("beforeend", `<div class="head">${esc(it.head)}</div>`); continue; }
		const d = document.createElement("div");
		d.className = "item" + (it.disabled ? " disabled" : "");
		d.innerHTML = `<span class="chk">${it.chk === true ? sico("check") : it.chk === "mixed" ? "–" : ""}</span>${it.html || esc(it.label)}${it.k && !touchUI() ? `<span class="k">${esc(it.k)}</span>` : ""}`;
		d.onclick = () => { closeMenu(); it.run(); };
		m.append(d);
	}
	m.hidden = false;
	const r = m.getBoundingClientRect();
	m.style.left = Math.min(x, innerWidth - r.width - 6) + "px";
	m.style.top = Math.max(6, Math.min(y, innerHeight - r.height - 6)) + "px";
}

function rowMenu(e) {
	const hs = selected();
	if (!hs.length) return;
	const ts = hs.map((h) => state.byHash.get(h));
	const tagItems = allTags().slice(0, 14).map((x) => {
		const n = ts.filter((t) => t.tags.includes(x)).length;
		return {
			html: chip(x), chk: n === ts.length ? true : n ? "mixed" : false,
			run: () => act("/api/edit-tags", n === ts.length ? { hashes: hs, remove: [x] } : { hashes: hs, add: [x] }),
		};
	});
	openMenu(e.clientX, e.clientY, [
		{ label: "Iniciar", run: () => command("start") },
		{ label: "Pausar", run: () => command("pause") },
		{ label: "Detener", run: () => command("stop") },
		"-",
		{ head: "Etiquetas" },
		...tagItems,
		{ label: "Editar etiquetas…", k: "T", run: assignTagsDialog },
		{ label: "Quitar todas las etiquetas", disabled: !ts.some((t) => t.tags.length), run: () => act("/api/set-tags", { hashes: hs, tags: [] }) },
		"-",
		{ head: "Prioridad" },
		...[[3, "Alta"], [2, "Normal"], [1, "Baja"]].map(([v, n]) => ({
			label: n, chk: ts.every((t) => t.priority === v), run: () => act("/api/action", { action: "priority", hashes: hs, value: v }),
		})),
		"-",
		{ head: "Posición (#)" },
		{ label: "Subir", k: "Ctrl+↑", run: () => movePos("up") },
		{ label: "Bajar", k: "Ctrl+↓", run: () => movePos("down") },
		{ label: "Al principio", k: "Ctrl+Inicio", run: () => movePos("top") },
		{ label: "Al final", k: "Ctrl+Fin", run: () => movePos("bottom") },
		"-",
		...(canOpenFolder() ? [{ label: hs.length > 1 ? "Abrir carpetas" : "Abrir carpeta", run: () => hs.slice(0, 5).forEach((h) => openFolder(h)) }] : []),
		...(ts.every((t) => t.sequential != null) ? [{
			label: "Descargar en orden", chk: ts.every((t) => t.sequential),
			run: () => act("/api/action", { action: "sequential", hashes: hs, value: ts.every((t) => t.sequential) ? 0 : 1 },
				ts.every((t) => t.sequential) ? "Descarga normal (por rareza)" : "Descarga en orden: desde el principio, para ver o escuchar mientras baja"),
		}] : []),
		{ label: "Reubicar datos…", run: relocateDialog },
		{ label: "Renombrar carpeta…", disabled: ts.length !== 1 || ts[0].files < 2, run: renameFolderDialog },
		{ label: "Verificar datos", run: () => act("/api/action", { action: "recheck", hashes: hs }, "Verificación iniciada: se revisa cada pieza contra el .torrent") },
		{ label: hs.length > 1 ? "Copiar nombres" : "Copiar nombre", run: () => copy(ts.map((t) => t.name).join("\n")) },
		{ label: "Copiar identificador", run: () => copy(ts.map((t) => t.hash).join("\n")) },
		{ label: "Copiar enlace magnet", run: () => copy(ts.map((t) => `magnet:?xt=urn:btih:${t.hash}&dn=${encodeURIComponent(t.name)}`).join("\n")) },
		"-",
		{ label: "Eliminar…", k: "Supr", run: removeDialog },
	]);
}

function headerMenu(e) {
	openMenu(e.clientX, e.clientY, [
		{ head: "Columnas" },
		...columns().map((c) => ({
			label: COL[c.id].title, chk: c.on,
			run: () => { if (c.on && visibleCols().length === 1) return; c.on = !c.on; savePrefs(); renderTable(); },
		})),
		"-",
		...(touchUI() ? [{ label: "Ajustar el ancho al texto", run: () => {
			visibleCols().forEach((c, i) => (c.w = fitWidth($("#torrents"), i)));
			savePrefs();
			renderTable();
		} }] : []),
		{ label: "Restablecer columnas", run: () => { prefs.columns = null; savePrefs(); renderTable(); } },
		{ label: "Vista por defecto (orden y filtro)", run: () => {
			prefs.sort = { col: "added", dir: -1 };
			state.filter.text = "";
			$("#search").value = "";
			savePrefs();
			render();
		} },
	]);
}

function tagMenu(e, name) {
	if (name === NO_TAG) return;
	const order = allTags(), i = order.indexOf(name);
	const move = (d) => {
		const o = order.slice();
		o.splice(i, 1);
		o.splice(Math.max(0, Math.min(o.length, i + d)), 0, name);
		state.tagConf.order = o;
		saveTagConf();
	};
	const hs = selected(), f = state.filter.tags;
	openMenu(e.clientX, e.clientY, [
		{ html: chip(name), disabled: true },
		"-",
		// On a touch screen there is no Ctrl+clic to filter by several tags.
		...(touchUI() ? [{ label: f.has(name) ? "Quitar del filtro" : f.size ? "Agregar al filtro" : "Filtrar por esta etiqueta",
			run: () => toggleTagFilter(name, true) }, "-"] : []),
		{ label: "Opciones de etiqueta…", run: () => tagDialog(name) },
		{ label: `Agregar a ${hs.length} seleccionado${hs.length === 1 ? "" : "s"}`, disabled: !hs.length, run: () => act("/api/edit-tags", { hashes: hs, add: [name] }) },
		{ label: "Quitar de los seleccionados", disabled: !hs.length, run: () => act("/api/edit-tags", { hashes: hs, remove: [name] }) },
		"-",
		{ label: "Subir", disabled: i <= 0, run: () => move(-1) },
		{ label: "Bajar", disabled: i >= order.length - 1, run: () => move(1) },
		"-",
		{ label: "Eliminar etiqueta…", run: () => deleteTag(name) },
	]);
}

async function copy(text) {
	try { await navigator.clipboard.writeText(text); return toast("Copiado"); } catch { /* not allowed here: the older way */ }
	// Without the clipboard API (an address other than localhost, some embedded views).
	const ta = document.createElement("textarea");
	ta.value = text;
	ta.style.cssText = "position:fixed;opacity:0";
	document.body.append(ta);
	ta.select();
	let ok = false;
	try { ok = document.execCommand("copy"); } catch { /* unsupported */ }
	ta.remove();
	ok ? toast("Copiado") : toast("No se pudo copiar", true);
}

// ------------------------------------------------------------ modal

function modal(title, bodyHTML, buttons, cls = "") {
	// A fresh element each time: listeners a dialog adds to it must not stay for the next
	// one (they piled up, and e.g. "Elegir imagen…" asked twice for a file in the window).
	const m = $("#modal").cloneNode(false);
	$("#modal").replaceWith(m);
	m.className = cls;
	m.innerHTML = `<h2>${esc(title)}</h2><div class="body">${bodyHTML}</div><div class="foot"></div>`;
	const foot = $(".foot", m);
	for (const b of buttons) {
		const el = document.createElement("button");
		el.className = "btn " + (b.cls || "");
		el.textContent = b.label;
		el.onclick = async () => { if ((await b.run?.()) !== false) closeModal(); };
		foot.append(el);
	}
	$("#modal-bg").hidden = false;
	setTimeout(() => $("input[type=text], textarea, input", m)?.focus(), 0);
	return m;
}
function closeModal() {
	$("#modal-bg").hidden = true;
	// Focus left in the hidden dialog would swallow the next keyboard shortcut.
	if ($("#modal-bg").contains(document.activeElement)) document.activeElement.blur();
}
// Dialog layout shared by every window: a label column on the left and one section per row.
const form = (...rows) => `<div class="form-grid">${rows.join("")}</div>`;
// First row of dialogs that act on torrents: the name, or the list when there are several.
const torrentRow = (ts) => ts.length === 1 ? frow("Torrent", `<div class="cur-loc">${esc(ts[0].name)}</div>`) :
	frow("Torrents", `<ul class="name-list">${ts.slice(0, 6).map((t) => `<li>${esc(t.name)}</li>`).join("")}${ts.length > 6 ? `<li>y ${ts.length - 6} más…</li>` : ""}</ul>`);
const frow = (label, body, forId) =>
	`<div class="frow">${forId ? `<label class="fl" for="${forId}">${label}</label>` : `<span class="fl">${label}</span>`}<div>${body}</div></div>`;
const modalOpen = () => !$("#modal-bg").hidden;

let toastTimer;
function toast(msg, err) {
	const t = $("#toast");
	t.textContent = msg;
	t.className = err ? "err" : "";
	t.hidden = false;
	clearTimeout(toastTimer);
	toastTimer = setTimeout(() => (t.hidden = true), err ? 6000 : 2200);
}

// ------------------------------------------------------------ commands

function command(cmd) {
	const hs = selected();
	switch (cmd) {
		case "add": return addDialog();
		case "settings": return settingsDialog();
		case "limits": return limitsDialog();
		case "new-tag": return tagDialog(null);
	}
	if (!hs.length) return toast("Selecciona uno o más torrents");
	if (cmd === "menu") { // touch screens: the torrent menu without a long press
		const r = $('#toolbar [data-cmd="menu"]').getBoundingClientRect();
		return rowMenu({ clientX: r.left, clientY: r.bottom + 2 });
	}
	if (cmd === "remove") return removeDialog();
	if (cmd === "tags") return assignTagsDialog();
	act("/api/action", { action: cmd, hashes: hs });
}

function removeDialog() {
	const hs = selected();
	if (!hs.length) return;
	const m = modal(hs.length > 1 ? `Eliminar ${hs.length} torrents` : "Eliminar torrent", form(
		torrentRow(hs.map((h) => state.byHash.get(h))),
		frow("Archivos", `<label class="check"><input type="checkbox" id="rm-data"> Eliminar también los archivos descargados del disco</label>
			<div class="hint">Sin marcar, los archivos quedan en su carpeta y solo se quita el torrent de la lista.</div>`)),
		[{ label: "Cancelar" }, {
			label: "Eliminar", cls: "danger",
			run: () => {
				const data = $("#rm-data", m).checked;
				state.sel.clear();
				act("/api/action", { action: "remove", hashes: hs, data }, data ? "Torrents y datos eliminados" : "Torrents eliminados");
			},
		}]);
}

function tagPicker(selectedTags, mixed = new Set()) {
	return `<div class="taglist">${allTags().map((x) =>
		`<label><input type="checkbox" value="${esc(x)}"${selectedTags.has(x) ? " checked" : ""}${mixed.has(x) ? " data-mixed" : ""}> ${chip(x)}</label>`).join("") ||
		'<span style="color:var(--dim)">Aún no hay etiquetas</span>'}</div>
		<div class="row"><input type="text" class="newtag" placeholder="Nueva etiqueta (Enter para agregar)" style="flex:1"></div>`;
}
function wirePicker(m) {
	$$("input[data-mixed]", m).forEach((i) => (i.indeterminate = true));
	const input = $(".newtag", m);
	input.addEventListener("keydown", (e) => {
		if (e.key !== "Enter" || !input.value.trim()) return;
		e.preventDefault();
		const name = input.value.trim();
		input.value = "";
		let box = $$(".taglist input", m).find((i) => i.value === name);
		if (!box) {
			const list = $(".taglist", m);
			if (!list.querySelector("label")) list.innerHTML = "";
			list.insertAdjacentHTML("beforeend", `<label><input type="checkbox" value="${esc(name)}"> ${chip(name)}</label>`);
			box = list.lastElementChild.querySelector("input");
		}
		box.indeterminate = false;
		box.checked = true;
	});
}

function assignTagsDialog() {
	const hs = selected();
	if (!hs.length) return;
	const ts = hs.map((h) => state.byHash.get(h));
	const all = new Set(), some = new Set();
	allTags().forEach((x) => {
		const n = ts.filter((t) => t.tags.includes(x)).length;
		if (n === ts.length) all.add(x); else if (n) some.add(x);
	});
	const m = modal(hs.length === 1 ? "Asignar etiquetas" : `Asignar etiquetas a ${hs.length} torrents`,
		form(torrentRow(ts), frow("Etiquetas", tagPicker(all, some) +
			`<div class="hint">Un torrent puede tener varias etiquetas. La primera también se guarda como etiqueta de ruTorrent.</div>`)),
		[{ label: "Cancelar" }, {
			label: "Guardar", cls: "primary",
			run: () => {
				const add = [], remove = [];
				$$(".taglist input", m).forEach((i) => {
					if (i.indeterminate) return;
					if (i.checked && !all.has(i.value)) add.push(i.value);
					if (!i.checked && (all.has(i.value) || some.has(i.value))) remove.push(i.value);
				});
				if (add.length || remove.length) act("/api/edit-tags", { hashes: hs, add, remove });
			},
		}]);
	wirePicker(m);
}

const LOGO_HELP = "Recomendado: PNG o WebP con fondo transparente, de 64 px de alto y entre 64 y 256 px de ancho " +
	"(proporción entre 1:1 y 4:1, por ejemplo 128×64 o 192×64). Se muestra a 14–20 px de alto; el doble de resolución " +
	"la mantiene nítida en pantallas de alta densidad. Peso ideal: hasta 50 KB; hasta 900 KB se guarda tal cual y, si pesa más, Tablero la reduce sola.";
const LOGO_H = 64, LOGO_MAXW = 256, LOGO_KEEP = 900 * 1024; // the server takes up to 1 MB

function tagDialog(name) {
	const conf = (name && state.tagConf.tags[name]) || {};
	const info = name ? tagInfo(name) : { color: PALETTE[Math.floor(Math.random() * PALETTE.length)], icon: "" };
	const show = { ...defaultShow(conf.logo), ...(conf.show || {}) };
	const logo = { url: conf.logo || "", ratio: conf.logoRatio, data: null }; // data: new image waiting to be uploaded
	const m = modal(name ? "Opciones de etiqueta" : "Nueva etiqueta", `
		<div class="form-grid">
			<div class="frow">
			<label class="fl" for="tg-name">Nombre</label>
			<div><input type="text" id="tg-name" value="${esc(name || "")}" placeholder="Por ejemplo: Blu-ray UHD"></div>
			</div>
			<div class="frow">
			<span class="fl">Color</span>
			<div class="color-pick">
				<div class="swatches">${PALETTE.map((c) => `<button type="button" data-c="${c}" style="background:${c}" title="${c}"></button>`).join("")}</div>
				<label class="custom-color" title="Otro color"><input type="color" id="tg-color" value="${info.color}"><span>Otro…</span></label>
			</div>
			</div>
			<div class="frow">
			<span class="fl">Ícono</span>
			<div>
				<input type="hidden" id="tg-icon" value="${esc(info.icon && info.icon.startsWith("lucide:") ? info.icon : "")}">
				<div class="icon-row">
					<div class="icon-current" id="tg-icon-cur"></div>
					<button type="button" class="btn" id="tg-icon-clear" data-i="">Quitar</button>
					<span class="hint">Una etiqueta lleva un ícono o un logo: elegir uno quita el otro.</span>
				</div>
				<div class="icon-groups">${ICON_GROUPS.map(([g, items], k) => `<div class="icon-group"><span class="ig-name">${g}</span><div class="icon-grid">` +
					items.map(([n, label]) => `<button type="button" data-i="lucide:${n}" title="${esc(label)}">${iconHTML("lucide:" + n)}</button>`).join("") + "</div></div>").join("")}</div>
			</div>
			</div>
			<div class="frow">
			<span class="fl">Logo <span class="help" data-tip-title="Tamaño recomendado" data-tip="${esc(LOGO_HELP)}">?</span></span>
			<div>
				<div class="logo-row">
					<div class="logo-box" id="tg-logo-box"></div>
					<div class="logo-actions">
						<button type="button" class="btn" id="tg-logo-pick" data-tip-title="Tamaño recomendado" data-tip="${esc(LOGO_HELP)}">Elegir imagen…</button>
						<button type="button" class="btn" id="tg-logo-clear">Quitar</button>
					</div>
					<input type="file" id="tg-logo-file" accept="image/png,image/jpeg,image/webp,image/gif" hidden>
				</div>
				<div id="tg-logo-info" class="logo-info"></div>
				<div class="tone-row" id="tg-tone-row">
					<span class="sub-l">Color del logo</span>
					<span class="seg">${LOGO_TONES.map(([v, n]) => `<label><input type="radio" name="tg-tone" value="${v}"${(conf.logoTone || "") === v ? " checked" : ""}><span>${n}</span></label>`).join("")}</span>
					<div class="hint">Para que el logo se vea bien sobre el color de la etiqueta. Automático usa blanco o negro según lo claro u oscuro del fondo. Mira el resultado en la vista previa.</div>
				</div>
			</div>
			</div>
			<div class="frow">
			<span class="fl">Apariencia</span>
			<div id="tg-show">
				<table class="showgrid"><thead><tr><th>Lugar</th><th>Color</th><th>Logo o ícono</th><th>Ambos</th><th>Vista previa</th></tr></thead><tbody>
				${PLACES.map(([p, label]) => `<tr><td>${label}</td>${["color", "logo", "both"].map((v) =>
					`<td class="r"><input type="radio" name="show-${p}" value="${v}"${show[p] === v ? " checked" : ""}></td>`).join("")}<td class="pv" data-pv="${p}"></td></tr>`).join("")}
				</tbody></table>
				<div class="hint" id="tg-show-hint">Elige un ícono o un logo para decidir cómo se ve en cada lugar.</div>
			</div>
			</div>
		</div>`,
		[...(name ? [{ label: "Eliminar", cls: "danger", run: () => { deleteTag(name); return false; } }] : []),
			{ label: "Cancelar" }, { label: "Guardar", cls: "primary", run: () => saveTag(name, m, logo) }], "wide");
	const prev = () => {
		const c = $("#tg-color", m).value, i = $("#tg-icon", m).value.trim(), n = $("#tg-name", m).value.trim() || "Etiqueta";
		const tone = $('input[name="tg-tone"]:checked', m)?.value || "";
		const src = logo.data || logo.url, inf = { color: c, icon: i }, lg = src && { src, ratio: logo.ratio, tone };
		$("#tg-tone-row", m).hidden = !src;
		const pic = !!src || hasIcon(i);
		$$("[data-c]", m).forEach((b) => b.classList.toggle("on", b.dataset.c === c));
		$$(".icon-grid [data-i]", m).forEach((b) => b.classList.toggle("on", b.dataset.i === i));
		const label = i && ICON_GROUPS.flatMap(([, items]) => items).find(([k]) => "lucide:" + k === i);
		$("#tg-icon-cur", m).innerHTML = label ? `${iconHTML(i)}<span>${esc(label[1])}</span>` : '<span class="dimtxt">Sin ícono</span>';
		$("#tg-icon-clear", m).disabled = !label;
		$("#tg-logo-box", m).innerHTML = src ? `<img src="${esc(logoSrc(src))}" alt="">` : '<span class="dimtxt">Sin logo</span>';
		$("#tg-logo-clear", m).disabled = !src;
		$("#tg-show", m).classList.toggle("disabled", !pic);
		$("#tg-show-hint", m).hidden = pic;
		$$("#tg-show input", m).forEach((r) => (r.disabled = !pic));
		for (const [p] of PLACES) {
			const mode = pic ? $(`input[name="show-${p}"]:checked`, m).value : "color";
			const cell = $(`[data-pv="${p}"]`, m);
			cell.innerHTML = p === "sidebar" ? `<span class="pv-side">${sideHTML(n, inf, lg, mode)}</span>` : chipHTML(n, inf, lg, mode);
		}
	};
	m.addEventListener("input", prev);
	m.addEventListener("change", prev);
	const missing = () => {
		if (logo.url && !logo.data && brokenLogos.has(logo.url))
			$("#tg-logo-info", m).innerHTML = '<div class="bad">' + mark("bad") + ' No se encontró el archivo de este logo en el servidor. Vuelve a elegir la imagen.</div>';
	};
	$("#tg-logo-box", m).addEventListener("error", () => setTimeout(missing, 0), true);
	missing();
	m.addEventListener("click", (e) => {
		if (e.target.closest("#tg-logo-pick")) return $("#tg-logo-file", m).click();
		if (e.target.closest("#tg-logo-clear")) { logo.url = ""; logo.data = null; $("#tg-logo-info", m).innerHTML = ""; return prev(); }
		const b = e.target.closest("button[data-c], button[data-i]");
		if (!b) return;
		if (b.dataset.c) $("#tg-color", m).value = b.dataset.c;
		else {
			$("#tg-icon", m).value = b.dataset.i;
			if (b.dataset.i && (logo.url || logo.data)) { logo.url = ""; logo.data = null; $("#tg-logo-info", m).innerHTML = ""; }
		}
		prev();
	});
	$("#tg-logo-file", m).addEventListener("change", async (e) => {
		const f = e.target.files[0];
		if (!f) return;
		try {
			const r = await prepareLogo(f);
			logo.data = r.data;
			logo.ratio = r.ratio;
			$("#tg-icon", m).value = "";
			$("#tg-logo-info", m).innerHTML = r.report;
		} catch (err) {
			$("#tg-logo-info", m).innerHTML = `<div class="bad">${mark("bad")} ${esc(err.message)}</div>`;
		}
		e.target.value = "";
		prev();
	});
	prev();
}

// Reads the chosen image, checks it against the recommendations and, if
// it is bigger than needed, scales it down to LOGO_H px high (as PNG).
async function prepareLogo(file) {
	if (!/^image\/(png|jpeg|webp|gif)$/.test(file.type)) throw new Error("Formato no admitido. Usa PNG, JPEG, WebP o GIF.");
	const url = await new Promise((res, rej) => { const r = new FileReader(); r.onload = () => res(r.result); r.onerror = rej; r.readAsDataURL(file); });
	const img = await new Promise((res, rej) => { const i = new Image(); i.onload = () => res(i); i.onerror = () => rej(new Error("No se pudo leer la imagen")); i.src = url; });
	const w = img.naturalWidth, h = img.naturalHeight, ratio = w / h;
	const kb = (b) => size(b);
	const ok = (good, text) => `<div class="${good ? "good" : "warn"}">${mark(good ? "good" : "warn")} ${text}</div>`;
	const ratioTxt = ratio >= 1 ? `${nf1.format(ratio)}:1` : `1:${nf1.format(1 / ratio)}`;
	let lines = `<div><b>${esc(file.name)}</b>: ${w}×${h} px · proporción ${ratioTxt} · ${kb(file.size)}</div>` +
		ok(file.type !== "image/jpeg", file.type === "image/jpeg" ? "JPEG no tiene transparencia: el fondo se verá como un rectángulo." : "Formato con transparencia.") +
		ok(h >= 32, h >= 32 ? "Alto suficiente para verse nítida." : `Muy baja (${h} px): se verá borrosa. Lo ideal es ${LOGO_H} px de alto.`) +
		ok(ratio >= 0.8 && ratio <= 4.2, ratio > 4.2 ? "Muy ancha: el logo se verá muy chico. Recorta los márgenes o usa una versión más compacta (máx. 4:1)." :
			ratio < 0.8 ? "Más alta que ancha: se verá angosta. Funciona mejor cuadrada o apaisada." : "Proporción adecuada.");
	// The original is stored as is whenever it fits: shrinking it in the browser (canvas) can
	// come out garbled, e.g. with the anti-fingerprinting protection of some browsers.
	if (file.size <= LOGO_KEEP) {
		lines += ok(file.size <= 50 * 1024, file.size <= 50 * 1024 ? "Peso adecuado." :
			"Pesa más de lo necesario, pero se guardará tal cual y se verá bien.");
		return { data: url, report: lines, ratio };
	}
	// Too heavy: shrink it in halves, which keeps thin lines that a single big step loses.
	const nh = Math.min(h, LOGO_H), nw = Math.min(LOGO_MAXW, Math.round(nh * ratio));
	let src = img, sw = w, sh = h;
	while (sw / 2 >= nw) {
		const step = document.createElement("canvas");
		step.width = sw = Math.round(sw / 2);
		step.height = sh = Math.round(sh / 2);
		const g = step.getContext("2d");
		g.imageSmoothingQuality = "high";
		g.drawImage(src, 0, 0, sw, sh);
		src = step;
	}
	const cv = document.createElement("canvas");
	cv.width = nw;
	cv.height = Math.max(1, Math.round(nw / ratio));
	const g = cv.getContext("2d");
	g.imageSmoothingQuality = "high";
	g.drawImage(src, 0, 0, cv.width, cv.height);
	const data = cv.toDataURL("image/png");
	const bytes = Math.round((data.length - 22) * 3 / 4);
	lines += `<div class="good">${mark("good")} Pesa más de ${kb(LOGO_KEEP)}: se guardará reducida a ${cv.width}×${cv.height} px (${kb(bytes)}). Revisa la vista previa.</div>`;
	return { data, report: lines, ratio: cv.width / cv.height };
}

async function saveTag(old, m, logo) {
	const name = $("#tg-name", m).value.trim();
	if (!name) { toast("Escribe un nombre", true); return false; }
	const conf = state.tagConf;
	if (logo.data) {
		try { logo.url = (await api("/api/logo", { data: logo.data.split(",")[1] })).url; } catch (e) { toast(e.message, true); return false; }
		logo.data = null;
	}
	if (old && old !== name) {
		if (allTags().includes(name) && !confirm(`"${name}" ya existe. ¿Unir ambas etiquetas?`)) return false;
		try { await api("/api/rename-tag", { old, new: name }); } catch (e) { toast(e.message, true); return false; }
		delete conf.tags[old];
		conf.order = conf.order.map((x) => (x === old ? name : x));
		if (state.filter.tags.delete(old)) state.filter.tags.add(name);
	}
	const entry = { color: $("#tg-color", m).value, icon: logo.url ? "" : $("#tg-icon", m).value.trim() };
	if (logo.url) {
		entry.logo = logo.url;
		entry.logoRatio = Math.round((logo.ratio || 2) * 1000) / 1000;
		const tone = $('input[name="tg-tone"]:checked', m)?.value;
		if (tone) entry.logoTone = tone;
	}
	if (logo.url || hasIcon(entry.icon))
		entry.show = Object.fromEntries(PLACES.map(([p]) => [p, $(`input[name="show-${p}"]:checked`, m).value]));
	conf.tags[name] = entry;
	if (!conf.order.includes(name)) conf.order = [...allTags().filter((x) => x !== name), name];
	await saveTagConf();
	refresh();
}

async function deleteTag(name) {
	const n = state.torrents.filter((t) => t.tags.includes(name)).length;
	if (!confirm(`¿Eliminar la etiqueta "${name}"?` + (n ? ` Se quitará de ${n} torrent${n > 1 ? "s" : ""} (los torrents no se borran).` : ""))) return;
	try { if (n) await api("/api/rename-tag", { old: name, new: "" }); } catch (e) { return toast(e.message, true); }
	delete state.tagConf.tags[name];
	state.tagConf.order = state.tagConf.order.filter((x) => x !== name);
	state.filter.tags.delete(name);
	closeModal();
	await saveTagConf();
	refresh();
}

// What a drop brought: real files plus file:// addresses, which the server reads from disk.
// Some file managers and sandboxed browsers on Linux give only addresses, and Firefox on
// Linux gives just the first of several dragged files but every address.
function droppedFiles(dt) {
	const files = [...(dt.files || [])], names = new Set(files.map((f) => f.name));
	const uris = (dt.getData("text/uri-list") || dt.getData("text/x-moz-url") || dt.getData("text/plain") || "").split(/\r?\n/);
	// file:// addresses, or plain paths (some sandboxed browsers give only the path as text).
	for (const u of uris.map((x) => x.trim()).filter((x) => /^file:\/\//i.test(x) || /^\/.+\.torrent$/i.test(x))) {
		const path = u.startsWith("/") ? u : decodeURIComponent(u.replace(/^file:\/\/[^/]*/i, "")), name = path.split("/").pop();
		if (!names.has(name)) { names.add(name); files.push({ path, name }); }
	}
	return files;
}
// Anything dragged in from outside Tablero may be a file: each browser and file manager
// names it differently (Files, text/uri-list, text/x-moz-url, or only text/plain from a
// sandboxed browser), and a drop the page does not take makes the browser open the file
// itself. Tablero's own drags (rows, column titles) carry an application/x-tablero type.
const isFileDrag = (dt) => !!dt && dt.types.length > 0 && ![...dt.types].some((t) => t.startsWith("application/x-tablero"));
// Use an effect the source allows; asking for one it doesn't offer makes the drop do nothing.
function setDropEffect(dt) {
	const a = dt.effectAllowed || "all";
	dt.dropEffect = ["all", "uninitialized", "copy", "copyMove", "copyLink"].includes(a) ? "copy" : /link/i.test(a) ? "link" : "move";
}

// Dropped files: into the open Agregar window, or a new one.
function addDropped(all) {
	const zone = modalOpen() && $("#add-drop");
	if (zone) return zone.addFiles(all);
	if (modalOpen()) return;
	const files = all.filter((f) => /\.torrent$/i.test(f.name));
	if (files.length) addDialog(files);
	else toast("Solo se pueden agregar archivos .torrent", true);
}

function addDialog(files = []) {
	let picked = [...files];
	const m = modal("Agregar torrents", form(
		frow("Archivos", `<div class="dropzone" id="add-drop">${touchUI() ? "<u>Elige archivos .torrent</u>" : "Arrastra archivos .torrent aquí o <u>elige archivos</u>"}<input type="file" id="add-file" accept=".torrent,application/x-bittorrent" multiple hidden><div id="add-files"></div></div>`),
		frow("Contenido", '<div id="add-content"></div>').replace('class="frow"', 'class="frow" id="add-content-row" hidden'),
		frow("Enlaces magnet", `<textarea id="add-magnet" placeholder="magnet:?xt=urn:btih:…"></textarea><div class="hint">Enlaces magnet o URL de archivos .torrent, uno por línea.</div>`, "add-magnet"),
		frow("Carpeta", folderPicker("add-dir", prefs.lastDir) + `<div class="hint">Vacío = la carpeta predeterminada de rTorrent.</div><div class="hint" id="add-space"></div>`),
		frow("Etiquetas", tagPicker(new Set([...state.filter.tags].filter((x) => x !== NO_TAG)))),
		frow("Al agregar", `<label class="check"><input type="checkbox" id="add-start" checked> Iniciar la descarga</label>`)),
		[{ label: "Cancelar" }, { label: "Agregar", cls: "primary", run: () => doAdd(m, picked) }]);
	wirePicker(m);
	wireFolderPickers(m);
	// Each .torrent's files, to choose which ones to download (unchecked = "No bajar"),
	// as a folder tree or a flat list like the Archivos tab.
	const content = $("#add-content", m);
	let tree = prefs.filesTree !== false;
	const closed = new Set();  // "k:folder" of folded folders
	const real = (f) => f.info.files.map((x, i) => [x, i]).filter(([x]) => !x.pad);
	const fileRow = (f, x, i, label, depth) =>
		`<div class="add-row" style="padding-left:${8 + depth * 18}px"><span class="twisty"></span><label class="check"><input type="checkbox" data-i="${i}"${f.skip.has(i) ? "" : " checked"}>` +
		`<span class="add-path" title="${esc(x.path)}">${esc(label)}</span><span class="add-size">${size(x.size)}</span></label></div>`;
	const treeRows = (f, k) => {
		const root = { dirs: new Map(), files: [] };
		for (const [x, i] of real(f)) {
			const parts = x.path.split("/");
			let node = root;
			parts.slice(0, -1).forEach((p, d) => {
				if (!node.dirs.has(p)) node.dirs.set(p, { name: p, path: parts.slice(0, d + 1).join("/"), dirs: new Map(), files: [], idx: [], size: 0 });
				node = node.dirs.get(p);
			});
			node.files.push([x, i, parts[parts.length - 1]]);
		}
		const sum = (n) => { for (const d of n.dirs.values()) { sum(d); n.idx.push(...d.idx); n.size += d.size; } for (const [x, i] of n.files) { n.idx.push(i); n.size += x.size; } };
		root.idx = []; root.size = 0;
		sum(root);
		const byName = (a, b) => a.localeCompare(b, "es", { numeric: true });
		let out = "";
		const walk = (n, depth) => {
			for (const d of [...n.dirs.values()].sort((a, b) => byName(a.name, b.name))) {
				const open = !closed.has(k + ":" + d.path), on = d.idx.filter((i) => !f.skip.has(i)).length;
				out += `<div class="add-row dir" style="padding-left:${8 + depth * 18}px"><span class="twisty" data-tw="${esc(d.path)}">${open ? "▾" : "▸"}</span>` +
					`<label class="check"><input type="checkbox" data-dir="${esc(d.path)}"${on ? " checked" : ""}${on && on < d.idx.length ? " data-mixed" : ""}>` +
					`${iconHTML("lucide:folder", "ico dir-ico")}<span class="add-path">${esc(d.name)}</span><span class="add-size">${size(d.size)}</span></label></div>`;
				if (open) walk(d, depth + 1);
			}
			for (const [x, i, name] of n.files.sort((a, b) => byName(a[2], b[2]))) out += fileRow(f, x, i, name, depth);
		};
		walk(root, 0);
		return out;
	};
	const showContent = () => {
		$("#add-content-row", m).hidden = !picked.length;
		const folders = picked.some((f) => f.info && real(f).some(([x]) => x.path.includes("/")));
		content.innerHTML = (folders ? `<div class="add-tools">${seg("addview", [["tree", "Árbol", "list-tree"], ["list", "Lista", "list"]], tree ? "tree" : "list")}` +
			(tree ? `${toolBtn("chevrons-up-down", "Expandir todo", 'data-fold="open"')}${toolBtn("chevrons-down-up", "Contraer todo", 'data-fold="close"')}` : "") + "</div>" : "") +
		picked.map((f, k) => {
			if (f.infoError) return `<div class="add-tor"><div class="bad">${mark("bad")} ${esc(f.name)}: ${esc(f.infoError)}</div></div>`;
			if (!f.info) return `<div class="add-tor"><div class="dimtxt">${esc(f.name)}: leyendo…</div></div>`;
			const shown = real(f), on = shown.filter(([, i]) => !f.skip.has(i));
			const total = shown.reduce((a, [x]) => a + x.size, 0), sel = on.reduce((a, [x]) => a + x.size, 0);
			return `<div class="add-tor" data-k="${k}">
				<div class="add-tor-head"><b>${esc(f.info.name)}</b>
					<span class="dimtxt">${on.length} de ${shown.length} archivo${shown.length === 1 ? "" : "s"} · ${size(sel)} de ${size(total)}</span>
					${shown.length > 1 ? `${toolBtn("check", "Todos", 'data-all="1"')}${toolBtn("square", "Ninguno", 'data-all="0"')}` : ""}</div>
				${shown.length > 1 ? `<div class="add-tor-files">${tree ? treeRows(f, k) : shown.map(([x, i]) => fileRow(f, x, i, x.path, 0)).join("")}</div>` : ""}
			</div>`;
		}).join("");
		$$("input[data-mixed]", content).forEach((b) => (b.indeterminate = true));
		showSpace();
	};
	content.addEventListener("change", (e) => {
		const f = picked[+e.target.closest("[data-k]")?.dataset.k], box = e.target;
		if (!f) return;
		if (box.dataset.i !== undefined) box.checked ? f.skip.delete(+box.dataset.i) : f.skip.add(+box.dataset.i);
		else if (box.dataset.dir !== undefined) {
			const pre = box.dataset.dir + "/";
			for (const [x, i] of real(f)) if (x.path.startsWith(pre)) box.checked ? f.skip.delete(i) : f.skip.add(i);
		}
		showContent();
	});
	content.addEventListener("click", (e) => {
		const t = e.target.closest("[data-tw], [data-all], [data-addview], [data-fold]");
		if (!t) return;
		const kEl = t.closest("[data-k]"), f = kEl && picked[+kEl.dataset.k];
		if (t.dataset.tw !== undefined) { const key = kEl.dataset.k + ":" + t.dataset.tw; closed.has(key) ? closed.delete(key) : closed.add(key); }
		else if (t.dataset.all !== undefined) f.skip = t.dataset.all === "1" ? new Set() : new Set(real(f).map(([, i]) => i));
		else if (t.dataset.addview) tree = t.dataset.addview === "tree";
		else if (t.dataset.fold === "open") closed.clear();
		else picked.forEach((p, k) => p.info && real(p).forEach(([x]) => {
			const parts = x.path.split("/");
			for (let d = 1; d < parts.length; d++) closed.add(k + ":" + parts.slice(0, d).join("/"));
		}));
		showContent();
	});
	// The free space on the folder's disk, against what the chosen files take.
	let free = null, freeFor = null, freeTimer = 0;
	const showSpace = () => {
		const el = $("#add-space", m);
		if (!free) { el.textContent = ""; return; }
		const need = picked.reduce((a, f) => a + (f.info ? real(f).filter(([, i]) => !f.skip.has(i)).reduce((b, [x]) => b + x.size, 0) : 0), 0);
		el.classList.toggle("bad", need > free.free);
		el.textContent = need > free.free
			? `No cabe: hay ${size(need)} para descargar y quedan ${size(free.free)} libres en ese disco. rTorrent lo dejaría en pausa.`
			: `Libre en el disco: ${size(free.free)}` + (need ? ` · para descargar: ${size(need)}` : "");
	};
	const readSpace = () => {
		const dir = $("#add-dir", m).value.trim();
		if (dir === freeFor) return showSpace();
		freeFor = dir;
		api("/api/free-space?path=" + encodeURIComponent(dir)).then(
			(r) => { if (freeFor === dir) { free = r; showSpace(); } },
			() => { if (freeFor === dir) { free = null; showSpace(); } });
	};
	$("#add-dir", m).addEventListener("input", () => { clearTimeout(freeTimer); freeTimer = setTimeout(readSpace, 300); });
	const readInfo = async (f) => {
		if (f.info || f.infoError) return;
		try {
			f.info = await api("/api/torrent-info", f.path ? { path: f.path } : { file: await fileB64(f) });
			f.skip = new Set();
		} catch (e) { f.infoError = e.message; }
		showContent();
	};
	const list = () => {
		$("#add-files", m).textContent = picked.map((f) => f.name).join(", ");
		showContent();
		picked.forEach(readInfo);
	};
	const zone = $("#add-drop", m), input = $("#add-file", m);
	// Files dropped on the zone, or anywhere while this dialog is open (see wire()).
	zone.addFiles = (files) => {
		const all = [...files], ok = all.filter((f) => /\.torrent$/i.test(f.name));
		if (ok.length < all.length) toast("Solo se pueden agregar archivos .torrent", true);
		// The same file dropped twice is added once.
		const key = (f) => f.path || f.name + ":" + (f.size || "");
		const have = new Set(picked.map(key));
		picked.push(...ok.filter((f) => !have.has(key(f)) && have.add(key(f))));
		list();
	};
	zone.addDrop = (dt) => {
		const files = droppedFiles(dt);
		if (files.length) zone.addFiles(files);
		else if (!window.tableroDesktop) toast("No llegó ningún archivo al soltar (" + ([...dt.types].join(", ") || "sin datos") + "). Usa “elige archivos”.", true);
	};
	// The input's own click bubbles up here: leave it alone, or the file chooser gets cancelled.
	zone.addEventListener("click", (e) => { if (e.target !== input) input.click(); });
	input.onchange = () => { zone.addFiles(input.files); input.value = ""; };
	let idle = 0; // as on the page: the highlight goes when dragover stops coming
	const over = (e) => {
		e.preventDefault(); e.stopPropagation(); setDropEffect(e.dataTransfer); zone.classList.add("over");
		clearTimeout(idle);
		if (!window.tableroDesktop) idle = setTimeout(() => zone.classList.remove("over"), 1000);
	};
	zone.addEventListener("dragenter", over);
	zone.addEventListener("dragover", over);
	zone.addEventListener("dragleave", (e) => { if (!zone.contains(e.relatedTarget)) zone.classList.remove("over"); });
	zone.addEventListener("drop", (e) => { e.preventDefault(); e.stopPropagation(); zone.classList.remove("over"); zone.addDrop(e.dataTransfer); });
	list();
	readSpace();
}

// A dropped or chosen file as base64; a path (from the desktop window) is read by the server.
const fileB64 = (f) => new Promise((res, rej) => {
	const r = new FileReader();
	r.onload = () => res(String(r.result).split(",")[1]);
	r.onerror = rej;
	r.readAsDataURL(f);
});

async function doAdd(m, files) {
	const magnets = $("#add-magnet", m).value.split(/\s+/).filter(Boolean);
	if (!files.length && !magnets.length) { toast("Agrega un archivo o un enlace", true); return false; }
	const directory = $("#add-dir", m).value.trim();
	const tags = $$(".taglist input", m).filter((i) => i.checked).map((i) => i.value);
	const start = $("#add-start", m).checked;
	prefs.lastDir = directory;
	savePrefs();
	let ok = 0;
	const fail = [], dups = [];
	const sent = (r) => (r && r.duplicate ? dups.push(r.duplicate) : ok++);
	const none = files.find((f) => f.info && f.info.files.every((x, i) => x.pad || f.skip.has(i)));
	if (none) { toast(`Elige al menos un archivo de ${none.info.name}`, true); return false; }
	for (const f of files) {
		const skip = f.skip ? [...f.skip] : [];
		try {
			if (f.path) { sent(await api("/api/add", { path: f.path, directory, tags, start, skip })); continue; }
			sent(await api("/api/add", { file: await fileB64(f), directory, tags, start, skip }));
		} catch (e) { fail.push(f.name + ": " + e.message); }
	}
	for (const magnet of magnets) {
		try { sent(await api("/api/add", { magnet, directory, tags, start })); } catch (e) { fail.push(e.message); }
	}
	if (fail.length) toast(fail.join(" · "), true);
	else if (ok) toast(`${ok} torrent${ok === 1 ? "" : "s"} agregado${ok === 1 ? "" : "s"}`);
	setTimeout(refresh, 800);
	if (dups.length) { setTimeout(() => duplicateDialog(dups), 0); }
}

// rTorrent keeps one torrent per hash: a file or magnet that is already there can bring its trackers.
function duplicateDialog(dups) {
	const any = dups.some((d) => d.trackers.length);
	const m = modal(dups.length > 1 ? "Ya están en la biblioteca" : "Ya está en la biblioteca", form(...dups.flatMap((d, k) => [
		frow("Torrent", `<div class="cur-loc">${esc(d.name)}</div><div class="hint">rTorrent no admite dos veces el mismo torrent, así que no se agregó de nuevo.</div>`),
		frow("Trackers nuevos", d.trackers.length ?
			'<div class="opt-rules">' + d.trackers.map((u) => `<label class="check"><input type="checkbox" data-dup="${k}" value="${esc(u)}" checked> ${esc(u)}</label>`).join("") +
				`</div><div class="hint">Rastreadores que trae el archivo y este torrent no tiene.</div>` :
			'<div class="dimtxt">Ya tiene todos los rastreadores del archivo: no hay nada que sumar.</div>')])),
		any ? [{ label: "Cancelar" }, {
			label: "Sumar rastreadores", cls: "primary",
			run: async () => {
				let n = 0;
				for (const [k, d] of dups.entries()) {
					const urls = $$(`input[data-dup="${k}"]`, m).filter((i) => i.checked).map((i) => i.value);
					if (!urls.length) continue;
					try { n += (await api("/api/add-trackers", { hash: d.hash, urls })).added; } catch (e) { toast(e.message, true); return false; }
				}
				toast(`${n} rastreador${n === 1 ? "" : "es"} sumado${n === 1 ? "" : "s"}`);
				state.detailsFor = null;
				refresh();
			},
		}] : [{ label: "Cerrar", cls: "primary" }]);
}

function addTrackerDialog(t) {
	if (!t) return;
	const m = modal("Agregar rastreadores", form(torrentRow([t]),
		frow("Rastreadores", '<textarea id="at-urls" rows="4" placeholder="udp://tracker.ejemplo.org:1337/announce"></textarea>' +
			'<div class="hint">Una dirección por línea (http, https o udp). Las que el torrent ya tiene se omiten. Quedan guardadas en la sesión de rTorrent.</div>', "at-urls")),
		[{ label: "Cancelar" }, {
			label: "Agregar", cls: "primary",
			run: async () => {
				const urls = $("#at-urls", m).value.split(/\s+/).filter(Boolean);
				if (!urls.length) { toast("Escribe al menos una dirección", true); return false; }
				try {
					const r = await api("/api/add-trackers", { hash: t.hash, urls });
					toast(r.added ? `${r.added} rastreador${r.added === 1 ? "" : "es"} agregado${r.added === 1 ? "" : "s"}` : "El torrent ya tenía esos rastreadores");
				} catch (e) { toast(e.message, true); return false; }
				state.detailsFor = null;
				refresh();
			},
		}]);
	setTimeout(() => $("#at-urls", m).focus(), 0);
}

// A folder field with a browser of the folders on the computer running Tablero.
function folderPicker(id, value) {
	return `<div class="fp"><div class="row"><input type="text" id="${id}" value="${esc(value || "")}" placeholder="${isAndroid() ? "/storage/emulated/0/Download/Torrents" : "/ruta/a/la/carpeta"}" style="flex:1">` +
		`<button type="button" class="btn" data-fp-browse="${id}">Examinar…</button>` +
		(androidCan("pickFolder") ? `<button type="button" class="btn" data-fp-saf="${id}" title="El selector de carpetas de Android">Elegir con Android…</button>` : "") +
		`</div><div class="fp-panel" data-fp-for="${id}" hidden></div></div>`;
}
// Android's folder picker (TableroAndroid.pickFolder): the app turns the folder into its real path
// and answers with window.tableroFolderPicked(id, path, error).
const safWaiting = new Map();
let safSeq = 0;
window.tableroFolderPicked = (id, path, err) => {
	const input = safWaiting.get(id);
	safWaiting.delete(id);
	if (err) return toast(err, true);
	if (input && path) setPath(input, path);
};
// Puts a path in the field scrolled to its end, where the folder's own name is.
function setPath(input, path) {
	input.value = path;
	input.scrollLeft = input.scrollWidth;
	input.dispatchEvent(new Event("input"));
}
// Click picks a folder (it goes into the field), double click or the arrow opens it.
async function browseFolder(panel, path) {
	try {
		const d = await api("/api/dirs?path=" + encodeURIComponent(path || ""));
		const input = $("#" + panel.dataset.fpFor, panel.closest("#modal"));
		const join = (n) => d.path.replace(/\/$/, "") + "/" + n;
		panel.dataset.path = d.path;
		setPath(input, d.path);
		panel.innerHTML = `<div class="fp-head"><button type="button" class="btn" data-fp-open="${esc(d.parent || "")}" title="Subir a la carpeta anterior"${d.parent ? "" : " disabled"}>↑</button>` +
			`<code title="${esc(d.path)}"><bdi>${esc(d.path)}</bdi></code></div>` +
			`<div class="fp-list">${d.dirs.map((n) => `<div class="fp-item" data-fp-pick="${esc(join(n))}">` +
				`${iconHTML("lucide:folder")}<span class="fp-name">${esc(n)}</span>` +
				`<button type="button" class="fp-open" data-fp-open="${esc(join(n))}" title="Abrir">›</button></div>`).join("") ||
				'<span class="dimtxt">Sin subcarpetas</span>'}</div>` +
			'<div class="fp-foot">Clic para elegir una carpeta · doble clic o › para abrirla</div>';
		panel.hidden = false;
	} catch (e) { toast(e.message, true); }
}
function wireFolderPickers(m) {
	m.addEventListener("click", (e) => {
		const saf = e.target.closest("[data-fp-saf]");
		if (saf) {
			const id = String(++safSeq);
			safWaiting.set(id, $("#" + saf.dataset.fpSaf, m));
			return window.TableroAndroid.pickFolder(id);
		}
		const b = e.target.closest("[data-fp-browse]");
		if (b) {
			const panel = $(`[data-fp-for="${b.dataset.fpBrowse}"]`, m);
			return panel.hidden ? browseFolder(panel, $("#" + b.dataset.fpBrowse, m).value) : (panel.hidden = true);
		}
		const panel = e.target.closest(".fp-panel");
		if (!panel) return;
		const open = e.target.closest("[data-fp-open]"), pick = e.target.closest("[data-fp-pick]");
		if (open) return open.dataset.fpOpen && browseFolder(panel, open.dataset.fpOpen);
		if (pick) {
			$$(".fp-item", panel).forEach((x) => x.classList.toggle("on", x === pick));
			setPath($("#" + panel.dataset.fpFor, m), pick.dataset.fpPick);
		}
	});
	m.addEventListener("dblclick", (e) => {
		const pick = e.target.closest("[data-fp-pick]");
		if (pick && !e.target.closest("[data-fp-open]")) browseFolder(pick.closest(".fp-panel"), pick.dataset.fpPick);
	});
}

// Changes the selected torrents' number (#), keeping them selected.
async function movePos(where) {
	const hs = selected();
	if (!hs.length) return;
	// Sorted by # from high to low, the list is upside down: keep "up" meaning up on screen.
	if (prefs.sort.col === "pos" && prefs.sort.dir < 0) where = { up: "down", down: "up", top: "bottom", bottom: "top" }[where];
	try { await api("/api/move", { hashes: hs, where }); } catch (e) { return toast(e.message, true); }
	if (prefs.sort.col !== "pos") toast("Posición cambiada. Ordena por # para ver el orden.");
	refresh();
}

// Opens the data's folder (or a file's folder) in the file manager of the computer running Tablero.
async function openFolder(hash, index, dir) {
	try {
		const r = await api("/api/open-folder", index !== undefined ? { hash, index } : dir !== undefined ? { hash, dir } : { hash });
		if (r.android) {
			if (androidCan("openFolder")) window.TableroAndroid.openFolder(r.folder);
			else toast(`Carpeta: ${r.folder}`);
			return;
		}
		toast(r.demo ? `En el modo demo no hay carpetas reales: se abriría ${r.folder}` : `Abriendo ${r.folder}`);
	} catch (e) { toast(e.message, true); }
}

function relocateDialog() {
	const hs = selected();
	if (!hs.length) return;
	const ts = hs.map((h) => state.byHash.get(h));
	// d.directory is the torrent's own folder for multi-file torrents and the containing folder otherwise.
	const parent = (t) => (t.files > 1 ? t.directory.replace(/\/[^/]*$/, "") || "/" : t.directory);
	const dirs = new Set(ts.map(parent));
	const where = dirs.size === 1 ? `<code>${esc([...dirs][0])}</code>` : `${dirs.size} carpetas distintas`;
	const m = modal(ts.length === 1 ? "Reubicar datos" : `Reubicar datos de ${ts.length} torrents`, form(
		torrentRow(ts),
		frow("Ubicación actual", `<div class="cur-loc">${where}</div>`),
		frow("Nueva carpeta", folderPicker("rl-dir", prefs.lastDir || (dirs.size === 1 ? [...dirs][0] : ""))),
		frow("Qué hacer", `<div class="choices">
			<label class="check"><input type="radio" name="rl-mode" value="move" checked> <span><b>Mover los archivos</b> a la nueva carpeta<br><span class="dimtxt">Se copian a la nueva ubicación y se borran de la anterior. Entre discos distintos puede tardar.</span></span></label>
			<label class="check"><input type="radio" name="rl-mode" value="locate"> <span><b>Los archivos ya están</b> en la nueva carpeta<br><span class="dimtxt">Solo cambia dónde los busca el torrent y los verifica. Útil si los moviste tú o cambió el disco.</span></span></label>
		</div>`),
		frow("Al terminar", `<label class="check" id="rl-check-wrap"><input type="checkbox" id="rl-check"> Verificar los datos</label>
			<div class="hint">El torrent queda detenido mientras tanto y se reanuda solo al terminar, si estaba activo.</div>`)),
		[{ label: "Cancelar" }, {
			label: "Reubicar", cls: "primary",
			run: async () => {
				const target = $("#rl-dir", m).value.trim(), move = $('input[name="rl-mode"]:checked', m).value === "move";
				if (!target) { toast("Elige la nueva carpeta", true); return false; }
				try {
					await api("/api/relocate", { hashes: hs, target, move, check: move && $("#rl-check", m).checked });
				} catch (e) { toast(e.message, true); return false; }
				prefs.lastDir = target;
				savePrefs();
				toast(move ? "Moviendo los datos…" : "Verificando los datos en la nueva carpeta…");
				refresh();
			},
		}]);
	wireFolderPickers(m);
	// Locating always verifies, so the box shows that and can't be unchecked.
	let wantCheck = false;
	m.addEventListener("change", (e) => {
		const box = $("#rl-check", m), move = $('input[name="rl-mode"]:checked', m).value === "move";
		if (e.target === box) return void (wantCheck = box.checked);
		box.disabled = !move;
		box.checked = move ? wantCheck : true;
	});
}

// Renames the folder a multi-file torrent brings (d.directory is that folder), not the
// folder it is saved in. The torrent is stopped for a moment and resumes if it was active.
function renameFolderDialog() {
	const t = state.byHash.get(selected()[0]);
	if (!t || t.files < 2) return;
	// The data location from the details (taken from d.directory), else d.directory.
	const d = state.detailsFor === t.hash && state.details;
	const cur = ((d && d.base_path) || t.directory).replace(/\/+$/, ""), name = cur.slice(cur.lastIndexOf("/") + 1), parent = cur.slice(0, cur.lastIndexOf("/")) || "/";
	const m = modal("Renombrar carpeta", form(
		torrentRow([t]),
		frow("Carpeta actual", `<div class="cur-loc"><code>${esc(cur)}</code></div>`),
		frow("Nombre nuevo", `<input type="text" id="rf-name" value="${esc(name)}" spellcheck="false">` +
			`<div class="hint">Se renombra en el disco la carpeta que trae el torrent, dentro de <code>${esc(parent)}</code>. Los archivos de adentro no cambian y no hace falta verificarlos. El nombre del torrent en la lista sigue igual. Si está activo, se detiene un momento y se reanuda solo.</div>`, "rf-name")),
		[{ label: "Cancelar" }, {
			label: "Renombrar", cls: "primary",
			run: async () => {
				const v = $("#rf-name", m).value.trim();
				if (!v) { toast("Escribe el nombre nuevo", true); return false; }
				if (v === name) return;
				try {
					const r = await api("/api/rename-folder", { hash: t.hash, name: v });
					toast(`Carpeta renombrada: ${r.folder}`);
				} catch (e) { toast(e.message, true); return false; }
				state.detailsFor = null;
				refresh();
			},
		}]);
	const input = $("#rf-name", m);
	input.focus();
	input.select();
}

// Shows each failed data move once.
const jobErrors = new Set();
function reportJobs() {
	for (const t of state.torrents) {
		const j = t.job;
		if (j && j.state === "error" && !jobErrors.has(t.hash + j.error)) {
			jobErrors.add(t.hash + j.error);
			toast(`No se pudo reubicar ${t.name}: ${j.error}`, true);
		}
	}
}

async function limitsDialog() {
	const s = state.stats, g = (await loadGeneral()) || { general: {}, rtorrent: {}, torrent: {} }, gen = g.general;
	const num = (id, v) => `<span class="num-in"><input type="number" id="${id}" min="0" value="${Math.round((v || 0) / KB())}"> ${UNITS()[1]}/s</span>`;
	// Empty = rTorrent's own value (rtorrent.rc), shown as the placeholder.
	const pnum = (id, k, unit, from) => `<span class="num-in"><input type="number" id="${id}" min="0" step="1" value="${gen[k] ?? ""}" placeholder="${from[k] ?? ""}"> ${unit}</span>`;
	const snum = (id, v, step) => `<span class="num-in"><input type="number" id="${id}" min="0" step="${step}" value="${v}"></span>`;
	const m = modal("Límites y conexiones", form(
		frow("Velocidad", `<div class="lim-tools">${seg("range", SPEED_RANGES, prefs.speedRange)}<span class="lim-legend"></span></div>` +
			'<div class="lim-chart"><div class="placeholder">Juntando datos…</div></div>' +
			'<div class="hint">Bajada y subida de todos los torrents juntos; las líneas cortadas son los límites actuales. Cada torrent tiene su propio gráfico en la pestaña Velocidad.</div>'),
		frow("Bajada máxima", num("lim-down", s.down_limit), "lim-down"),
		frow("Subida máxima", num("lim-up", s.up_limit) + '<div class="hint">0 = sin límite. Se aplica a todos los torrents juntos; cada torrent puede tener su propio límite en la pestaña Opciones.</div>', "lim-up"),
		frow("Conexiones", `<div class="opt-cols">
			<div><span class="sub-l">En total</span>${pnum("lim-sockets", "max_sockets", "conexiones", g.rtorrent).replace('min="0"', 'min="512"')}</div>
			<div><span class="sub-l">Pares por torrent</span>${pnum("lim-pmax", "peers_max", "pares", g.torrent)}</div>
			<div><span class="sub-l">Subidas por torrent</span>${pnum("lim-umax", "uploads_max", "pares", g.torrent)}</div></div>` +
			'<div class="hint">"En total" es para todos los torrents juntos y evita que muchas conexiones llenen el router y pongan lento el internet del resto de la casa. Pares y subidas por torrent valen para cada torrent sin valores propios en su pestaña Opciones; el mínimo de pares es la mitad. Vienen con 512 en total (lo mínimo que acepta rTorrent) y los valores de BiglyBT por torrent (50 y 4). Vacío = lo que diga rTorrent, en gris.</div>'),
		frow("Al sembrar", `<div class="opt-rules">
			<label class="check"><input type="checkbox" id="lim-r-on"${gen.stop_ratio ? " checked" : ""}> Detener al llegar a una proporción de subida de ${snum("lim-ratio", gen.stop_ratio || 2, 0.1)}</label>
			<label class="check"><input type="checkbox" id="lim-h-on"${gen.stop_hours ? " checked" : ""}> Detener después de ${snum("lim-hours", gen.stop_hours || 24, 0.5)} horas sembrando</label></div>` +
			'<div class="hint">Tablero detiene cada torrent completo que cumpla la condición, salvo los que tienen su propia regla en la pestaña Opciones. Funciona mientras Tablero esté corriendo.</div>')),
		[{ label: "Cancelar" }, {
			label: "Aplicar", cls: "primary",
			run: async () => {
				const peer = (id) => ($("#" + id, m).value.trim() === "" ? null : Math.max(0, Math.round(+$("#" + id, m).value || 0)));
				const general = {
					max_sockets: peer("lim-sockets"), peers_max: peer("lim-pmax"), uploads_max: peer("lim-umax"),
					stop_ratio: $("#lim-r-on", m).checked ? +$("#lim-ratio", m).value || 0 : 0,
					stop_hours: $("#lim-h-on", m).checked ? +$("#lim-hours", m).value || 0 : 0,
				};
				try {
					await api("/api/limits", { down: +$("#lim-down", m).value * KB(), up: +$("#lim-up", m).value * KB() });
					state.general = await api("/api/general", general);
					state.optsHash = null;
					toast("Límites y conexiones aplicados");
					refresh();
				} catch (e) { toast(e.message, true); return false; }
			},
		}], "wide limits");
	$(".lim-chart", m).closest(".frow").classList.add("lim-row");
	m.addEventListener("click", (e) => {
		const b = e.target.closest("[data-range]");
		if (!b) return;
		prefs.speedRange = +b.dataset.range;
		savePrefs();
		m.querySelectorAll("[data-range]").forEach((x) => x.classList.toggle("on", x === b));
		drawLimitsChart();
	});
	state.limitsHistory = null;
	loadLimitsHistory();
}

// The overall chart in Límites de velocidad: the same total history as the Velocidad tab,
// fetched again on every refresh while the dialog is open.
const limitsOpen = () => modalOpen() && $("#modal").classList.contains("limits");
async function loadLimitsHistory() {
	if (!limitsOpen()) return;
	try {
		state.limitsHistory = (await api("/api/history")).samples;
		drawLimitsChart();
	} catch { /* next refresh */ }
}
function drawLimitsChart() {
	const m = $("#modal"), wrap = $(".lim-chart", m), samples = state.limitsHistory;
	if (!limitsOpen() || !wrap || !samples) return;
	$(".lim-legend", m).innerHTML = speedLegend(samples, true).legend;
	if (samples.length < 2) { wrap.innerHTML = '<div class="placeholder">Juntando datos…</div>'; return; }
	let canvas = $("canvas", wrap);
	if (!canvas) { wrap.innerHTML = ""; canvas = document.createElement("canvas"); wrap.append(canvas); }
	drawSpeed(canvas, samples, speedLegend(samples, true).series);
}

// Only in the desktop window, which owns these options (ventana.json, ~/.config/autostart).
function windowOptionsHTML() {
	const w = window.tableroWindow;
	if (!w) return "";
	if (!w.tray) return frow("Ventana", '<div class="hint">Sin ícono en la bandeja: falta el paquete <code>gir1.2-xapp-1.0</code>. Al cerrar la ventana se cierra Tablero (el servidor sigue aplicando las reglas de siembra).</div>' +
		'<div class="cmd-list">Para instalarlo:' + cmdHTML("sudo apt install gir1.2-xapp-1.0") + '</div>');
	return frow("Ventana", `<div class="opt-rules"><div class="seg-row"><span class="seg-label">Al cerrar la ventana</span><span class="seg">${[["tray", "Dejar en la bandeja"], ["quit", "Salir de Tablero"]].map(([v, n]) =>
		`<label><input type="radio" name="op-close" value="${v}"${w.close === v ? " checked" : ""}><span>${n}</span></label>`).join("")}</span></div>
		<label class="check"><input type="checkbox" id="op-autostart"${w.autostart ? " checked" : ""}> Abrir Tablero en la bandeja al iniciar la sesión</label></div>` +
		'<div class="hint">Salir cierra la ventana y el ícono; el servidor sigue corriendo y aplicando las reglas de siembra. Al iniciar la sesión, Tablero queda solo como ícono en la bandeja, con sus avisos de descarga completa; un clic en el ícono abre la ventana.</div>');
}

function saveWindowOptions(m) {
	const w = window.tableroWindow, h = window.webkit?.messageHandlers?.tablero;
	if (!w || !w.tray || !h) return;
	const close = $('input[name="op-close"]:checked', m)?.value || w.close, autostart = $("#op-autostart", m).checked;
	if (close !== w.close || autostart !== w.autostart) h.postMessage(JSON.stringify({ type: "window-options", close, autostart }));
}

function settingsDialog() {
	const m = modal("Opciones", form(
		frow("Tema", '<select id="op-theme"><option value="dark">Oscuro</option><option value="light">Claro</option></select>', "op-theme"),
		frow("Lista", `<div class="opt-rules">
			<label class="check"><input type="checkbox" id="op-tint"> Teñir las filas con el color de su primera etiqueta</label>
			<label class="check"><input type="checkbox" id="op-tagbar"> Mostrar la barra de etiquetas sobre la lista</label></div>`),
		frow("Filtro", '<select id="op-mode"><option value="any">Mostrar torrents con cualquiera de las etiquetas elegidas</option><option value="all">Mostrar solo torrents con todas las etiquetas elegidas</option></select>' +
			`<div class="hint">Cuando filtras por varias etiquetas a la vez (${multiTagHow()}).</div>`, "op-mode"),
		frow("Unidades", `<span class="seg">${[["bin", "Binarias: KiB, MiB, GiB"], ["dec", "Decimales: KB, MB, GB"]].map(([v, n]) =>
			`<label><input type="radio" name="op-units" value="${v}"${prefs.units === v ? " checked" : ""}><span>${n}</span></label>`).join("")}</span>` +
			'<div class="hint">Para tamaños, velocidades y límites. Binarias: 1 KiB = 1024 bytes, como cuenta rTorrent. Decimales: 1 KB = 1000 bytes, como los discos y la mayoría de las páginas.</div>'),
		frow("Actualizar", '<span class="num-in">cada <input type="number" id="op-int" min="1" max="60"> segundos</span>', "op-int"),
		windowOptionsHTML(),
		frow("rTorrent", toolBtn("power", "Apagar rTorrent…", 'id="op-shutdown"') +
			'<div class="hint">Lo cierra de forma normal: guarda la sesión y avisa a los rastreadores.</div>' +
			'<div class="cmd-list">Para volver a encenderlo: ' + rtorrentCmdHTML("start") + '</div>'),
		frow("Respaldo", toolBtn("archive", "Respaldo y restauración…", 'id="op-backup"') +
			`<div class="hint">${isAndroid() ? "Para cambiar de teléfono/tableta o pasar todo a un PC" : "Para reinstalar el sistema o llevar todo a otro PC"}: etiquetas, opciones y los torrents de rTorrent con su avance.</div>`),
		frow("Red", `<label class="check"><input type="checkbox" id="op-lan"> Permitir abrir Tablero desde otros equipos de la red</label>
			<div class="pw-row"><input type="password" id="op-pw" autocomplete="new-password" spellcheck="false"></div>
			<div id="op-net"></div>`, "op-lan"),
		frow("Importar", '<textarea id="op-import" placeholder=\'{"Películas":{"color":"#e53935","icon":"🎬"}}\'></textarea>' +
			'<div class="hint">Colores del plugin labelcolors de ruTorrent: pega el valor de <code>webui.labelcolors.map</code>.</div>', "op-import")),
		[{ label: "Cancelar" }, {
			label: "Guardar", cls: "primary",
			run: async () => {
				prefs.theme = $("#op-theme", m).value;
				prefs.tint = $("#op-tint", m).checked;
				prefs.tagbar = $("#op-tagbar", m).checked;
				prefs.tagMode = $("#op-mode", m).value;
				prefs.interval = Math.max(1, +$("#op-int", m).value || 2);
				prefs.units = $('input[name="op-units"]:checked', m)?.value || "bin";
				saveWindowOptions(m);
				const lan = $("#op-lan", m).checked, pw = $("#op-pw", m).value;
				if (net && net.local && (lan !== net.lan || pw)) {
					try {
						const r = await api("/api/access", { lan, password: pw });
						toast(r.restart ? (lan ? "Tablero se reinicia para abrirse a la red…" : `Tablero se reinicia para escuchar solo en ${thisDevice()}…`) :
							pw ? "Contraseña guardada" : "Guardado");
					} catch (e) { toast(e.message, true); return false; }
				}
				savePrefs();
				const raw = $("#op-import", m).value.trim();
				if (raw) {
					try {
						const map = JSON.parse(raw);
						for (const [k, v] of Object.entries(map)) if (v && v.color) state.tagConf.tags[k] = { color: v.color, icon: "" };
						saveTagConf();
						toast(`${Object.keys(map).length} etiquetas importadas`);
					} catch { toast("El texto pegado no es JSON válido", true); return false; }
				}
				render();
				refresh();
			},
		}]);
	$("#op-theme", m).value = prefs.theme;
	$("#op-tint", m).checked = prefs.tint;
	$("#op-tagbar", m).checked = prefs.tagbar;
	$("#op-mode", m).value = prefs.tagMode;
	$("#op-int", m).value = prefs.interval;
	$("#op-shutdown", m).onclick = shutdownDialog;
	$("#op-backup", m).onclick = backupDialog;
	// Access from the network: the server says how it listens and whether this is its computer.
	let net = null;
	const lanBox = $("#op-lan", m), pwIn = $("#op-pw", m), netInfo = $("#op-net", m);
	const showNet = () => {
		if (!net) return;
		const url = `http://${net.ip || "IP-de-este-equipo"}:${net.port}`;
		pwIn.placeholder = net.password ? "Contraseña (vacío = no cambiarla)" : "Contraseña (al menos 6 caracteres)";
		pwIn.disabled = lanBox.disabled = !net.local;
		pwIn.hidden = !lanBox.checked && !net.password;
		netInfo.innerHTML = !net.local ? `<div class="hint">Solo se cambia en ${isAndroid() ? "el teléfono/tableta" : "el equipo"} donde corre Tablero.</div>` :
			`<div>${net.listening ? `Ahora se abre desde la red en <b>${esc(url)}</b>${net.password ? "" : ", sin contraseña"}.` : `Ahora solo se abre en ${thisDevice()}.`}</div>` +
			(net.fixed && !isAndroid() ? `<div class="hint">Tablero se inició con <code>--bind ${esc(net.fixed)}</code>, que manda sobre esta opción.</div>` : "") +
			`<div class="hint">Desde otro equipo, el navegador pide la contraseña (el usuario puede ser cualquiera). En ${thisDevice()} no se pide. Viaja sin cifrar: úsalo solo en tu red de casa.</div>` +
			(isAndroid() ? '<div class="hint">Funciona con wifi y mientras la app de Tablero está activa.</div>' : "") +
			ufwHTML([`${net.port}/tcp`]);
	};
	lanBox.onchange = showNet;
	fetch("/api/access").then((r) => r.json()).then((j) => { net = j; lanBox.checked = j.lan; showNet(); }).catch(() => { netInfo.textContent = ""; });
}

// ------------------------------------------------------------ backups

const BACKUP_HAS = "Etiquetas y logos, tus preferencias de la interfaz, el acceso desde la red, la sesión de rTorrent (cada torrent con su avance, carpeta, etiquetas y estado) y rtorrent.rc. No incluye los archivos descargados.";

async function backupDialog() {
	let info;
	try { info = await api("/api/backups"); } catch (e) { return toast(e.message, true); }
	const m = modal("Respaldo y restauración", form(
		frow("Carpeta", folderPicker("bk-dir", info.folder || "") +
			`<div class="hint">${isAndroid() ? "Donde se guardan los respaldos. Para que sobrevivan a un cambio de teléfono/tableta, guarda una copia fuera de él con Exportar (en el PC o en la nube)." :
				"Donde se guardan los respaldos. Mejor en otro disco o en uno externo, para que sobrevivan a una reinstalación."}</div>`, "bk-dir"),
		frow("Automático", seg("bkevery", [["never", "Nunca"], ["daily", "Diario"], ["weekly", "Semanal"]], info.every) +
			` <span class="num-in">guardar los últimos <input type="number" id="bk-keep" min="1" max="100" value="${info.keep}"></span>` +
			'<div class="hint">Tablero hace el respaldo solo mientras está corriendo, y borra los automáticos más antiguos.</div>'),
		frow("Respaldos", '<div id="bk-list"></div>' +
			`<div class="add-tools">${toolBtn("archive", "Crear respaldo ahora", 'id="bk-now"')}${toolBtn("rotate-ccw", "Restaurar el elegido…", 'id="bk-restore"')}</div>` +
			`<div class="add-tools">${toolBtn("download", "Exportar el elegido…", 'id="bk-export"')}${toolBtn("upload", "Importar…", 'id="bk-import"')}` +
			'<input type="file" id="bk-file" accept=".zip,application/zip" hidden></div>' +
			'<div class="hint">Exportar guarda una copia del respaldo donde elijas. Importar trae un respaldo de otro equipo a esta carpeta y lo abre para restaurarlo.</div>' +
			`<div class="hint">Incluye: ${BACKUP_HAS}</div>`)),
		[{ label: "Cerrar" }, {
			label: "Guardar", cls: "primary",
			run: async () => {
				try {
					await api("/api/backup-settings", { folder: $("#bk-dir", m).value.trim(), every, keep: +$("#bk-keep", m).value || 5 });
					toast(every === "never" ? "Guardado" : `Respaldo ${every === "daily" ? "diario" : "semanal"} activado`);
				} catch (e) { toast(e.message, true); return false; }
			},
		}]);
	wireFolderPickers(m);
	let every = info.every, chosen = info.files[0]?.path || "";
	const list = (files) => {
		$("#bk-list", m).innerHTML = files.length ? `<table class="showgrid bk-table"><thead><tr><th></th><th>Respaldo</th><th>Tamaño</th></tr></thead><tbody>` +
			files.map((f) => `<tr><td><input type="radio" name="bk-pick" value="${esc(f.path)}"${f.path === chosen ? " checked" : ""}></td>` +
				`<td>${esc(date(f.time))}</td><td>${size(f.size)}</td></tr>`).join("") + "</tbody></table>" :
			'<div class="dimtxt">No hay respaldos en esta carpeta.</div>';
		$("#bk-restore", m).disabled = $("#bk-export", m).disabled = !files.length;
	};
	list(info.files);
	const reload = async () => {
		try { const r = await api("/api/backups?folder=" + encodeURIComponent($("#bk-dir", m).value.trim())); chosen = r.files[0]?.path || ""; list(r.files); } catch { list([]); }
	};
	$("#bk-dir", m).addEventListener("change", reload);
	m.addEventListener("click", async (e) => {
		const b = e.target.closest("[data-bkevery]");
		if (b) {
			every = b.dataset.bkevery;
			$$("[data-bkevery]", m).forEach((x) => x.classList.toggle("on", x === b));
			return;
		}
		if (e.target.closest(".fp-item")) setTimeout(reload, 0);
		const pick = e.target.closest('input[name="bk-pick"]');
		if (pick) chosen = pick.value;
		if (e.target.closest("#bk-now")) {
			const btn = $("#bk-now", m);
			btn.disabled = true;
			try {
				const r = await api("/api/backup", { folder: $("#bk-dir", m).value.trim() });
				toast(`Respaldo creado: ${r.torrents} torrents`);
				chosen = r.path;
				await reload();
				chosen = r.path;
				$$('input[name="bk-pick"]', m).forEach((x) => (x.checked = x.value === r.path));
			} catch (err) { toast(err.message, true); }
			btn.disabled = false;
		}
		if (e.target.closest("#bk-restore") && chosen) restoreDialog(chosen);
		if (e.target.closest("#bk-export") && chosen) exportBackup(chosen);
		if (e.target.closest("#bk-import")) $("#bk-file", m).click();
	});
	$("#bk-file", m).addEventListener("change", async (e) => {
		const file = e.target.files[0];
		e.target.value = "";
		if (!file) return;
		const btn = $("#bk-import", m);
		btn.disabled = true;
		toast("Importando " + file.name + "…");
		try {
			const r = await importBackup(file, $("#bk-dir", m).value.trim());
			chosen = r.path;
			await reload();
			chosen = r.path;
			$$('input[name="bk-pick"]', m).forEach((x) => (x.checked = x.value === r.path));
			restoreDialog(r.path);
		} catch (err) { toast(err.message, true); }
		btn.disabled = false;
	});
}

// A copy of a backup wherever the person wants it: the Android app's "Guardar como"
// (TableroAndroid.exportFile), or the browser's download.
function exportBackup(path) {
	if (androidCan("exportFile")) return window.TableroAndroid.exportFile(path);
	const a = document.createElement("a");
	a.href = "/api/backup-file?path=" + encodeURIComponent(path);
	a.download = path.split("/").pop();
	document.body.append(a);
	a.click();
	a.remove();
}

// A backup .zip from elsewhere, sent as it is (it may be larger than the JSON limit).
async function importBackup(file, folder) {
	const r = await fetch("/api/backup-import?" + new URLSearchParams({ folder, name: file.name }), {
		method: "POST", body: file, headers: { "Content-Type": "application/zip", "X-Tablero": "1" },
	});
	const data = await r.json().catch(() => ({ error: "Respuesta inválida (" + r.status + ")" }));
	if (!r.ok || data.error) throw new Error(data.error || r.statusText);
	return data;
}

// Android, the first time: where the backups go (a phone or tablet has no second disk to
// suggest, and its own folder is lost if the app is uninstalled).
async function firstBackupFolder() {
	if (!isAndroid() || prefs.backupAsked || modalOpen()) return;
	let info;
	try { info = await api("/api/backups"); } catch { return; }
	if (info.chosen) { prefs.backupAsked = true; savePrefs(); return; }
	if (modalOpen()) return;
	let every = "weekly";
	const m = modal("Respaldos de Tablero", form(
		frow("Carpeta", folderPicker("fb-dir", info.folder || "") +
			'<div class="hint">Dónde guardar los respaldos: tus etiquetas, opciones y los torrents con su avance. Elige una carpeta que no se borre al desinstalar la app; Download o Documents sirven.</div>', "fb-dir"),
		frow("Automático", seg("fbevery", [["never", "Nunca"], ["daily", "Diario"], ["weekly", "Semanal"]], every) +
			'<div class="hint">Se cambia después en ⚙ Opciones → Respaldo, donde también puedes exportar e importar respaldos.</div>')),
		[{ label: "Ahora no", run: () => { prefs.backupAsked = true; savePrefs(); } }, {
			label: "Guardar", cls: "primary",
			run: async () => {
				try {
					await api("/api/backup-settings", { folder: $("#fb-dir", m).value.trim(), every, keep: info.keep || 5 });
				} catch (e) { toast(e.message, true); return false; }
				prefs.backupAsked = true;
				savePrefs();
				toast("Carpeta de respaldos guardada");
			},
		}]);
	wireFolderPickers(m);
	m.addEventListener("click", (e) => {
		const b = e.target.closest("[data-fbevery]");
		if (!b) return;
		every = b.dataset.fbevery;
		$$("[data-fbevery]", m).forEach((x) => x.classList.toggle("on", x === b));
	});
}

async function restoreDialog(path) {
	let info;
	try { info = await api("/api/restore-inspect", { path }); } catch (e) { return toast(e.message, true); }
	const created = date(info.created);
	const parts = [
		["torrents", `Torrents de rTorrent <span class="dimtxt">(${info.torrents - info.present} nuevos${info.present ? `; ${info.present} ya están y no se tocan` : ""})</span>`, info.torrents > info.present, true],
		["tags", "Etiquetas y logos", info.has.tags, info.has.tags],
		["prefs", "Preferencias de la interfaz (columnas, tema, filtros…)", info.has.prefs, info.has.prefs],
		["access", "Acceso desde la red y su contraseña", false, info.has.access],
		["rc", `rtorrent.rc <span class="dimtxt">(reemplaza el de ${thisDevice()}; se guarda una copia${isAndroid() ? ". No lo marques si el respaldo viene de un PC: sus rutas y su conexión con rTorrent no sirven aquí" : ""})</span>`, false, info.has.rc],
	];
	const m = modal("Restaurar respaldo", form(
		frow("Respaldo", `<div class="cur-loc">${esc(path.split("/").pop())}</div><div class="hint">Creado el ${esc(created)} en el equipo <b>${esc(info.host || "?")}</b> con Tablero ${esc(info.version || "")}: ${info.torrents} torrents.</div>`),
		frow("Qué restaurar", `<div class="opt-rules">${parts.map(([k, label, on, avail]) =>
			`<label class="check"><input type="checkbox" data-part="${k}"${on && avail ? " checked" : ""}${avail ? "" : " disabled"}> ${label}</label>`).join("")}</div>`),
		frow("Carpetas", `<div class="rs-map"><span>Cambiar</span><input type="text" id="rs-from" placeholder="/ruta/en/el/respaldo" spellcheck="false"><span>por</span><input type="text" id="rs-to" placeholder="${isAndroid() ? "/storage/emulated/0/Download/Torrents" : "/ruta/en/este/equipo"}" spellcheck="false"></div>` +
			'<div id="rs-folders"></div><div class="hint">' + (isAndroid() ? "Si el respaldo viene de un PC, cambia el comienzo de las rutas, por ejemplo /media/datos/Torrents por /storage/emulated/0/Download/Torrents. " :
				"Si en este equipo los discos están montados en otro lugar, cambia el comienzo de las rutas. ") + mark("good") + ' = los datos están ahí.</div>')),
		[{ label: "Cancelar" }, {
			label: "Restaurar", cls: "primary",
			run: async () => {
				const want = Object.fromEntries($$("[data-part]", m).map((x) => [x.dataset.part, x.checked]));
				let r;
				try { r = await api("/api/restore", { path, parts: want, from: $("#rs-from", m).value.trim(), to: $("#rs-to", m).value.trim() }); }
				catch (e) { toast(e.message, true); return false; }
				if (r.prefs) {
					Object.assign(prefs, r.prefs);
					try { localStorage.setItem("tablero.prefs", JSON.stringify(prefs)); } catch { /* private mode */ }
				}
				restoredDialog(r);
				return false; // the result dialog replaced this one
			},
		}]);
	const folders = (fs) => {
		$("#rs-folders", m).innerHTML = fs.length ? `<table class="showgrid rs-table"><thead><tr><th>En el respaldo</th><th>${isAndroid() ? "Aquí" : "En este equipo"}</th><th>Torrents</th></tr></thead><tbody>` +
			fs.map((f) => `<tr><td><code>${pathBreaks(f.from)}</code></td><td><code>${pathBreaks(f.to)}</code></td>` +
				`<td><span class="${f.found === f.count ? "ok" : f.found ? "" : "bad"}">${f.found === f.count ? mark("good") : f.found ? "" : mark("bad")} ${f.found} de ${f.count}</span></td></tr>`).join("") + "</tbody></table>" : "";
	};
	folders(info.folders);
	let t = 0;
	const update = () => {
		clearTimeout(t);
		t = setTimeout(async () => {
			try { folders((await api("/api/restore-inspect", { path, from: $("#rs-from", m).value.trim(), to: $("#rs-to", m).value.trim() })).folders); } catch { /* keep the last */ }
		}, 400);
	};
	$("#rs-from", m).addEventListener("input", update);
	$("#rs-to", m).addEventListener("input", update);
}

// A path that may wrap only after its slashes, never inside a folder name.
const pathBreaks = (p) => esc(p).replace(/\//g, "/<wbr>");

function restoredDialog(r) {
	const t = r.torrents, lines = [];
	if (t) {
		if (t.written) lines.push(`${t.written} torrent${t.written === 1 ? "" : "s"} listo${t.written === 1 ? "" : "s"} en la sesión de rTorrent: aparecen al reiniciarlo, con su avance (sin verificar de nuevo si los archivos no cambiaron).`);
		if (t.loaded) lines.push(`${t.loaded} torrent${t.loaded === 1 ? "" : "s"} agregado${t.loaded === 1 ? "" : "s"} y en verificación (la carpeta de sesión de rTorrent no se podía escribir).`);
		if (t.skipped) lines.push(`${t.skipped} ya estaba${t.skipped === 1 ? "" : "n"} en rTorrent y no se tocaron.`);
	}
	if (r.tags !== undefined) lines.push("Etiquetas y logos restaurados.");
	if (r.prefs) lines.push("Preferencias de la interfaz restauradas.");
	if (r.access) lines.push("Acceso desde la red restaurado: se aplica al reiniciar Tablero.");
	if (r.rc) lines.push(`rtorrent.rc restaurado en <code>${esc(r.rc)}</code> (la copia anterior quedó con la terminación .antes-de-tablero). Se aplica al reiniciar rTorrent.`);
	const restart = (t && t.restart) || r.rc;
	const m = modal("Respaldo restaurado", form(
		frow("Resultado", `<ul class="rs-done">${lines.map((x) => `<li>${x}</li>`).join("") || "<li>No había nada que restaurar.</li>"}</ul>`),
		restart ? frow("rTorrent", toolBtn("power", "Apagar rTorrent…", 'id="rs-shutdown"') +
			'<div class="cmd-list">Y luego vuelve a encenderlo: ' + rtorrentCmdHTML("start") + "</div>") : ""),
		[{ label: "Cerrar", cls: "primary", run: () => { render(); refresh(); } }]);
	$("#rs-shutdown", m)?.addEventListener("click", shutdownDialog);
}

// ------------------------------------------------------------ port

// Open if peers connected to us in the last half hour; otherwise unknown until tested.
function portState(s) {
	if (!s.port_open) return ["bad", "rTorrent no escucha conexiones entrantes"];
	if (state.portTest && state.portTest.port === s.port) return state.portTest.reachable ? ["ok", "abierto (probado)"] : ["bad", "cerrado (probado)"];
	if (state.portTesting === s.port) return ["", "probando…"];
	if (s.incoming || (s.incoming_seen && Date.now() / 1000 - s.incoming_seen < 1800)) return ["ok", "abierto"];
	return ["warn", "sin conexiones entrantes"];
}
// The port saved for the next start of rTorrent, when it differs from the one in use.
const portNext = (s) => (s.port_next && s.port_next !== s.port ? s.port_next : 0);
const RESTART_CMD = "sudo systemctl restart rtorrent";
// Tests the port from internet (ifconfig.co) and keeps the result for the status bar.
async function portTest() {
	const r = await api("/api/port-test", {});
	state.portTest = r;
	renderPort(state.stats);
	return r;
}
// When Tablero starts, and again if rTorrent comes back on another port, the port is
// tested once on its own; if the test fails, the bar keeps the estimate from incoming peers.
function autoPortTest(s) {
	if (!s.port_open || state.portAuto === s.port) return;
	state.portAuto = state.portTesting = s.port;
	portTest().catch(() => {}).finally(() => { state.portTesting = 0; renderPort(state.stats); });
}
function renderPort(s) {
	const el = $("#st-port");
	if (!s || !s.port) return void (el.hidden = true);
	autoPortTest(s);
	const [cls, text] = portState(s);
	el.hidden = false;
	el.className = cls;
	const next = portNext(s);
	el.innerHTML = `<i class="dot"></i>Puerto ${s.port}: ${text}${next ? ` · ${next} al reiniciar rTorrent` : ""}`;
	el.title = "Estado del puerto de entrada de rTorrent. Clic para ver detalles y probarlo desde internet.";
}
function portDialog() {
	const s = state.stats || {};
	const [cls, text] = portState(s);
	const fwd = portNext(s) || s.port || "";
	const seen = s.incoming ? `${s.incoming} par${s.incoming > 1 ? "es" : ""} conectado${s.incoming > 1 ? "s" : ""} desde afuera ahora` :
		s.incoming_seen ? `Ninguna ahora; la última hace ${eta(Math.max(60, Math.round(Date.now() / 1000 - s.incoming_seen)))}` : "Ninguna desde que se inició Tablero";
	const m = modal("Estado del puerto", form(
		frow("Puerto", `<div>${s.port || "?"} (TCP y UDP)${s.port_open ? "" : " · rTorrent no lo tiene abierto"}</div>` +
			(portNext(s) ? `<div>Cambia a ${portNext(s)} al reiniciar rTorrent: ${rtorrentCmdHTML("restart")}</div>` : "") +
			'<div class="hint">Por este puerto llegan las conexiones de otros pares. Si está cerrado igual puedes bajar, pero conectas con menos pares.</div>'),
		frow("Cambiar puerto", `<div class="port-change"><input type="number" id="port-new" min="1024" max="65535" value="${portNext(s) || s.port || ""}">` +
			`${toolBtn("check", "Cambiar", 'id="port-set"')}</div><div id="port-set-result"></div>` +
			'<div class="hint">Entre 1024 y 65535. rTorrent abre el puerto solo al iniciar: Tablero lo guarda en su archivo de configuración y el cambio se aplica al reiniciarlo. Después, reenvía el puerto nuevo en el router.</div>', "port-new"),
		frow("Conexiones entrantes", `<div>${seen}</div><div class="hint">Si llegan pares desde internet, el puerto está abierto. Si no llega ninguno, puede estar cerrado o simplemente no hay pares buscando.</div>`),
		frow("Prueba", `${toolBtn("search", "Probar puerto", 'id="port-test"')}<div id="port-result" class="port-result ${cls}">Estado: ${text}</div>` +
			'<div class="hint">Le pide a ifconfig.co que intente conectarse a tu IP pública en este puerto. Solo se envía el número de puerto. Tablero hace esta prueba solo al iniciar.</div>'),
		frow("Si está cerrado", `<div id="port-help">En el router, reenvía el puerto ${fwd} (TCP y UDP) a la IP local de ${thisDevice()}<span id="port-ip"></span>. Con VPN, el puerto debe abrirse en la VPN.${isAndroid() ? " Con datos móviles casi nunca se puede abrir: la compañía comparte tu IP pública con otros clientes." : ""}</div>` +
			ufwHTML([`${fwd}/tcp`, `${fwd}/udp`]))),
		[{ label: "Cerrar", cls: "primary" }]);
	fetch("/api/local-ip").then((r) => r.json()).then((j) => { if (j.ip) $("#port-ip", m).textContent = ` (${j.ip})`; }).catch(() => {});
	const setPort = async () => {
		const out = $("#port-set-result", m);
		out.className = "port-result";
		out.textContent = "Guardando…";
		try {
			const r = await api("/api/port", { port: $("#port-new", m).value });
			const line = cmdHTML(r.line);
			out.className = "port-result " + (r.saved ? "ok" : "warn");
			out.innerHTML = r.demo ? `Modo demo: aquí se guardaría el puerto ${r.port} en la configuración de rTorrent.` :
				r.saved ? `Guardado en <code>${esc(r.rc)}</code>. Para aplicarlo, reinicia rTorrent${isAndroid() ? "" : " en una terminal"}: ${rtorrentCmdHTML("restart")}` :
				r.rc ? `No tengo permiso para escribir en <code>${esc(r.rc)}</code>. Cambia ahí la línea del puerto (o agrégala) por: ${line} y luego reinicia rTorrent.` :
				`No encontré el archivo de configuración de rTorrent. En él, cambia la línea del puerto (o agrégala) por: ${line} y luego reinicia rTorrent.`;
			refresh();
		} catch (e) { out.className = "port-result bad"; out.textContent = e.message; }
	};
	$("#port-set", m).onclick = setPort;
	$("#port-new", m).onkeydown = (e) => { if (e.key === "Enter") { e.preventDefault(); setPort(); } };
	$("#port-test", m).onclick = async () => {
		const out = $("#port-result", m);
		out.className = "port-result";
		out.textContent = "Probando…";
		try {
			const r = await portTest();
			out.className = "port-result " + (r.reachable ? "ok" : "bad");
			out.textContent = r.demo ? "Modo demo: aquí diría si el puerto está abierto." :
				r.reachable ? `Abierto: ifconfig.co pudo conectarse a ${r.ip}:${r.port}.` : `Cerrado: ifconfig.co no pudo conectarse a ${r.ip}:${r.port}.`;
		} catch (e) { out.className = "port-result bad"; out.textContent = e.message; }
	};
}

const START_CMD = "sudo systemctl start rtorrent";

function shutdownDialog() {
	const n = state.torrents.filter((t) => t.state).length;
	const busy = state.torrents.filter((t) => t.job && t.job.state !== "error");
	modal("Apagar rTorrent", form(
		frow("Qué pasa", `<div>rTorrent se cierra y deja de bajar y subir${n ? ` (${n} torrent${n > 1 ? "s" : ""} iniciado${n > 1 ? "s" : ""})` : ""}. Antes guarda la sesión, así que al encenderlo cada torrent vuelve como estaba.</div>` +
			'<div class="hint">Tablero sigue abierto, pero sin conexión hasta que rTorrent vuelva.</div>'),
		...(busy.length ? [frow("En curso", `<div class="warn">${mark("warn")} Reubicar datos sigue trabajando en ${busy.length === 1 ? esc(busy[0].name) : busy.length + " torrents"}. ` +
			"Si apagas ahora, los archivos terminan de moverse, pero no se verifican ni se reanudan: al encender rTorrent, usa Verificar datos e Inicia el torrent.</div>")] : []),
		frow("Encenderlo", `<div>${isAndroid() ? "Después:" : "En una terminal:"} ${rtorrentCmdHTML("start")}</div>`)),
		[{ label: "Cancelar" }, {
			label: "Apagar", cls: "danger",
			run: async () => {
				try {
					const r = await api("/api/shutdown", {});
					toast(r.demo ? "En el modo demo no hay rTorrent que apagar" : "Apagando rTorrent…");
				} catch (e) { toast(e.message, true); }
				setTimeout(refresh, 1500);
			},
		}]);
}

// ------------------------------------------------------------ events

function toggleTagFilter(name, multi) {
	const f = state.filter.tags;
	if (multi) f.has(name) ? f.delete(name) : f.add(name);
	else if (f.size === 1 && f.has(name)) f.clear();
	else { f.clear(); f.add(name); }
	render();
}

// Dragging a column edge or a splitter: the mouse keeps its mouse events; a finger uses
// pointer events (touch-action: none on those handles keeps the page from scrolling instead).
function onDragStart(el, fn) {
	el.addEventListener("mousedown", fn);
	el.addEventListener("pointerdown", (e) => { if (e.pointerType !== "mouse") fn(e); });
}
function track(e, move, done) {
	const [mv, end] = e.type === "pointerdown" ? ["pointermove", "pointerup"] : ["mousemove", "mouseup"];
	const up = () => {
		removeEventListener(mv, move);
		removeEventListener(end, up);
		removeEventListener("pointercancel", up);
		done();
	};
	addEventListener(mv, move);
	addEventListener(end, up);
	if (mv === "pointermove") addEventListener("pointercancel", up);
}
// After a drag the click that follows must not sort the column; a finger's comes later.
const clickAfter = (e) => (e.type === "pointerdown" ? 400 : 0);

function wire() {
	$("#st-port").addEventListener("click", portDialog);
	$("#toolbar").addEventListener("click", (e) => {
		const b = e.target.closest("[data-cmd]");
		if (b) command(b.dataset.cmd);
	});
	$("#sidebar").addEventListener("click", (e) => {
		const b = e.target.closest("[data-cmd]");
		if (b) return command(b.dataset.cmd);
		const l = e.target.closest("li");
		if (!l) return;
		const f = state.filter;
		if (l.dataset.status) f.status = l.dataset.status;
		else if (l.dataset.tracker) f.tracker = f.tracker === l.dataset.tracker ? null : l.dataset.tracker;
		else if (l.dataset.tag) return toggleTagFilter(l.dataset.tag, e.ctrlKey || e.metaKey);
		render();
	});
	$("#sidebar").addEventListener("contextmenu", (e) => {
		const l = e.target.closest("li[data-tag]");
		if (!l) return;
		e.preventDefault();
		tagMenu(e, l.dataset.tag);
	});
	$("#sidebar").addEventListener("dblclick", (e) => {
		const l = e.target.closest("li[data-tag]");
		if (l && l.dataset.tag !== NO_TAG) tagDialog(l.dataset.tag);
	});

	// Drag torrents onto a tag in the side panel to tag them.
	const tagLi = (e) => e.target.closest?.("#tag-list li[data-tag]");
	$("#sidebar").addEventListener("dragover", (e) => {
		const l = tagLi(e);
		if (!l || !e.dataTransfer.types.includes("application/x-tablero")) return;
		e.preventDefault();
		$$("#tag-list li.drop").forEach((x) => x !== l && x.classList.remove("drop"));
		l.classList.add("drop");
	});
	$("#sidebar").addEventListener("dragleave", (e) => tagLi(e)?.classList.remove("drop"));
	$("#sidebar").addEventListener("drop", (e) => {
		const l = tagLi(e);
		if (!l) return;
		e.preventDefault();
		l.classList.remove("drop");
		const hs = JSON.parse(e.dataTransfer.getData("application/x-tablero") || "[]");
		const tag = l.dataset.tag;
		if (!hs.length) return;
		if (tag === NO_TAG) act("/api/set-tags", { hashes: hs, tags: [] }, "Etiquetas quitadas");
		else act("/api/edit-tags", { hashes: hs, add: [tag] }, `Etiqueta "${tag}" agregada a ${hs.length} torrent${hs.length > 1 ? "s" : ""}`);
	});

	$("#side-mode").addEventListener("click", (e) => {
		const mb = e.target.closest("[data-tagmode]");
		if (mb) { prefs.tagMode = mb.dataset.tagmode; savePrefs(); render(); }
	});
	$("#tagbar").addEventListener("click", (e) => {
		const mb = e.target.closest("[data-tagmode]");
		if (mb) { prefs.tagMode = mb.dataset.tagmode; savePrefs(); return render(); }
		if (e.target.closest(".mode")) return;
		const c = e.target.closest(".chip");
		if (c) toggleTagFilter(c.dataset.tag, e.ctrlKey || e.metaKey);
	});
	$("#tagbar").addEventListener("contextmenu", (e) => {
		const c = e.target.closest(".chip");
		if (c) { e.preventDefault(); tagMenu(e, c.dataset.tag); }
	});

	const tbody = $("#torrents tbody");
	tbody.addEventListener("mousedown", (e) => {
		const tr = e.target.closest("tr");
		if (!tr) return;
		if (e.button === 2 && state.sel.has(tr.dataset.hash)) return;
		if (e.button === 0 && state.sel.has(tr.dataset.hash) && !e.ctrlKey && !e.shiftKey && state.sel.size > 1) {
			state.pendingSingle = tr.dataset.hash; // may become a drag of the whole selection
			return;
		}
		if (e.button <= 2) clickRow(e, tr);
	});
	tbody.addEventListener("click", (e) => {
		const tr = e.target.closest("tr");
		if (tr && state.pendingSingle === tr.dataset.hash) clickRow(e, tr);
		state.pendingSingle = null;
	});
	tbody.addEventListener("contextmenu", (e) => { if (e.target.closest("tr")) { e.preventDefault(); rowMenu(e); } });
	tbody.addEventListener("dblclick", (e) => {
		const t = state.byHash.get(e.target.closest("tr")?.dataset.hash);
		if (t) command(t.state && t.active ? "pause" : "start");
	});
	tbody.addEventListener("dragstart", (e) => {
		state.pendingSingle = null;
		const tr = e.target.closest("tr");
		if (!state.sel.has(tr.dataset.hash)) { state.sel.clear(); state.sel.add(tr.dataset.hash); afterSelect(); }
		e.dataTransfer.setData("application/x-tablero", JSON.stringify(selected()));
		e.dataTransfer.effectAllowed = "copy";
		state.dragging = true;
	});
	document.addEventListener("dragend", () => (state.dragging = false));
	$("#table-wrap").addEventListener("mousedown", (e) => {
		if (e.target.id === "table-wrap" || e.target.id === "empty") { state.sel.clear(); afterSelect(); }
	});

	// Header: sort, reorder by drag, resize, column menu.
	const thead = $("#torrents thead");
	thead.addEventListener("click", (e) => {
		const th = e.target.closest("th");
		if (!th || e.target.classList.contains("grip") || state.resized) return;
		const col = th.dataset.col;
		prefs.sort = { col, dir: prefs.sort.col === col ? -prefs.sort.dir : (COL[col].num || col === "added" ? -1 : 1) };
		savePrefs();
		renderTable();
	});
	thead.addEventListener("contextmenu", (e) => { e.preventDefault(); headerMenu(e); });
	let dragCol = null;
	thead.addEventListener("dragstart", (e) => {
		const th = e.target.closest("th");
		dragCol = th.dataset.col;
		th.classList.add("dragging");
		e.dataTransfer.setData("text/plain", dragCol);
		e.dataTransfer.setData("application/x-tablero-col", dragCol);
	});
	thead.addEventListener("dragover", (e) => {
		const th = e.target.closest("th");
		if (!th || !dragCol) return;
		e.preventDefault();
		$$("th.drop-before").forEach((x) => x.classList.remove("drop-before"));
		th.classList.add("drop-before");
	});
	thead.addEventListener("drop", (e) => {
		const th = e.target.closest("th");
		if (!th || !dragCol) return;
		e.preventDefault();
		const cols = columns(), from = cols.findIndex((c) => c.id === dragCol);
		const [c] = cols.splice(from, 1);
		cols.splice(cols.findIndex((x) => x.id === th.dataset.col), 0, c);
		savePrefs();
		renderTable();
	});
	thead.addEventListener("dragend", () => { dragCol = null; renderHeader(); });
	thead.addEventListener("dblclick", (e) => {
		if (!e.target.classList.contains("grip")) return;
		const th = e.target.parentElement, c = columns().find((x) => x.id === th.dataset.col);
		c.w = fitWidth($("#torrents"), th.cellIndex);
		savePrefs();
		renderTable();
	});
	onDragStart(thead, (e) => {
		if (!e.target.classList.contains("grip")) return;
		e.preventDefault();
		e.stopPropagation();
		const th = e.target.parentElement, c = columns().find((x) => x.id === th.dataset.col);
		const x0 = e.clientX, w0 = c.w, colEl = $$("#torrents col")[visibleCols().indexOf(c)];
		th.draggable = false;
		const move = (ev) => { c.w = Math.max(40, w0 + ev.clientX - x0); colEl.style.width = c.w + "px"; };
		track(e, move, () => {
			th.draggable = true;
			state.resized = true;
			setTimeout(() => (state.resized = false), clickAfter(e));
			savePrefs();
		});
	});

	// Splitters.
	$$("[data-split]").forEach((s) => onDragStart(s, (e) => {
		e.preventDefault();
		const kind = s.dataset.split, x0 = e.clientX, y0 = e.clientY, w0 = prefs.sidebarW, h0 = prefs.detailsH;
		const move = (ev) => {
			if (kind === "sidebar") prefs.sidebarW = Math.max(140, Math.min(480, w0 + ev.clientX - x0));
			else prefs.detailsH = Math.max(32, Math.min(innerHeight - 200, h0 - (ev.clientY - y0)));
			applyLayout();
		};
		track(e, move, savePrefs);
	}));

	$(".tabs").addEventListener("click", (e) => {
		const b = e.target.closest("[data-tab]");
		if (!b) return;
		prefs.tab = b.dataset.tab;
		savePrefs();
		state.detailsFor = null;
		hideTip();
		renderDetails();
		loadDetails();
		loadHistory();
	});
	$("#details-body").addEventListener("input", (e) => {
		const f = e.target.closest("[data-tfilter]");
		if (f) { tableFilter[f.dataset.tfilter] = f.value; renderDetails(); }
	});
	$("#details-body").addEventListener("change", (e) => {
		if (e.target.name === "opt-seed") {
			const own = e.target.value === "1";
			$("#opt-seed-own").hidden = !own;
			$("#opt-seed-general").hidden = own;
			return;
		}
		const h = detailHash();
		const opt = e.target.closest("input[data-opt]");
		if (opt) { prefs[opt.dataset.opt] = opt.checked; savePrefs(); return renderDetails(); }
		const s = e.target.closest("select[data-files]");
		if (!s || !h) return;
		const indexes = s.dataset.files.split(",").map(Number), priority = +s.value;
		// Show it right away; the next refresh confirms it.
		if (state.details) indexes.forEach((i) => (state.details.files[i].priority = priority));
		s.blur();
		renderDetails();
		act("/api/file-priority", { hash: h, indexes, priority });
	});
	$("#details-body").addEventListener("contextmenu", (e) => {
		const tr = e.target.closest("tr[data-file], tr[data-dirpath]"), h = detailHash();
		if (!tr || !h) return;
		e.preventDefault();
		const run = tr.dataset.dirpath !== undefined ? () => openFolder(h, undefined, tr.dataset.dirpath) : () => openFolder(h, +tr.dataset.file);
		const items = canOpenFolder() ? [{ label: "Mostrar en la carpeta", run }] : [];
		if (tr.dataset.file !== undefined && canStream(h))
			items.push({ label: "Reproducir mientras se descarga", run: () => playFile(h, +tr.dataset.file) });
		if (items.length) openMenu(e.clientX, e.clientY, items);
	});
	$("#details-body").addEventListener("submit", (e) => {
		e.preventDefault();
		const t = state.byHash.get(detailHash());
		if (t && e.target.id === "opt-form") saveOptions(t);
	});
	document.addEventListener("click", (e) => {
		const pb = e.target.closest("[data-play]");
		if (pb && detailHash()) { e.preventDefault(); e.stopPropagation(); return playFile(detailHash(), +pb.dataset.play); }
		const ac = e.target.closest("[data-android-call]");
		if (ac && androidCan(ac.dataset.androidCall)) {
			window.TableroAndroid[ac.dataset.androidCall]();
			toast(ac.dataset.androidCall === "startRtorrent" ? "Encendiendo rTorrent…" : "Reiniciando rTorrent…");
			setTimeout(refresh, 2500);
			return;
		}
		const b = e.target.closest("[data-copy-cmd]");
		if (b) copy(b.dataset.copyCmd);
	});
	// Double click on a column edge: as wide as its widest text.
	$("#details-body").addEventListener("dblclick", (e) => {
		const g = e.target.closest("[data-tgrip]");
		if (!g) return;
		const key = g.dataset.tgrip, th = g.parentElement, table = g.closest("table");
		const w = prefs.tableW[key] || Object.fromEntries([...th.parentElement.cells].map((x) => [x.dataset.col, Math.round(x.getBoundingClientRect().width)]));
		w[th.dataset.col] = fitWidth(table, th.cellIndex);
		prefs.tableW[key] = w;
		savePrefs();
		renderDetails();
	});
	// Column widths in Archivos, Pares and Trackers: drag the edge of a title.
	onDragStart($("#details-body"), (e) => {
		const g = e.target.closest("[data-tgrip]");
		if (!g) return;
		e.preventDefault();
		const key = g.dataset.tgrip, col = g.parentElement.dataset.col, body = $("#details-body");
		if (!prefs.tableW[key]) {
			// The first time, every column keeps the width it has now.
			prefs.tableW[key] = Object.fromEntries([...g.closest("tr").cells].map((th) => [th.dataset.col, Math.round(th.getBoundingClientRect().width)]));
			renderDetails();
		}
		const w = prefs.tableW[key], x0 = e.clientX, w0 = w[col];
		state.subResize = true;
		const move = (ev) => {
			w[col] = Math.max(40, Math.round(w0 + ev.clientX - x0));
			const table = $("table.sub.fixed", body);
			if (!table) return;
			$(`col[data-col="${col}"]`, table).style.width = w[col] + "px";
			table.style.width = Object.values(w).reduce((n, x) => n + x, 0) + "px";
		};
		track(e, move, () => {
			state.subResize = false;
			state.resized = true;
			setTimeout(() => (state.resized = false), clickAfter(e));
			savePrefs();
			renderDetails();
		});
	});
	$("#details-body").addEventListener("click", (e) => {
		if (e.target.closest("#opt-reset")) { state.optsHash = null; return renderDetails(); }
		if (e.target.closest("#opt-peers-general")) { ["opt-pmax", "opt-pmin", "opt-umax"].forEach((id) => ($("#" + id).value = "")); return; }
		const th = e.target.closest("[data-tsort]");
		if (th) { if (!e.target.closest(".grip") && !state.resized) nextSort(th.dataset.tsort, th.dataset.col); return renderDetails(); }
		const tr0 = e.target.closest("[data-treset]");
		if (tr0) {
			const k = tr0.dataset.treset;
			delete prefs.tableSort[k]; delete prefs.tableW[k]; delete tableFilter[k];
			savePrefs();
			return renderDetails();
		}
		if (e.target.closest("[data-add-tracker]")) return addTrackerDialog(state.byHash.get(detailHash()));
		if (e.target.closest("[data-open-folder]")) return detailHash() && openFolder(detailHash());
		if (e.target.closest("[data-copy-message]")) { const t = state.byHash.get(detailHash()); return t && copy(t.message); }
		const vb = e.target.closest("[data-view]"), tg = e.target.closest("[data-toggle]");
		if (vb || tg) {
			if (vb) prefs.filesTree = vb.dataset.view === "tree";
			else prefs[tg.dataset.toggle] = !prefs[tg.dataset.toggle];
			savePrefs();
			return renderDetails();
		}
		const sb = e.target.closest("[data-scope], [data-range]");
		if (sb) {
			if (sb.dataset.scope) prefs.speedOf = sb.dataset.scope;
			else prefs.speedRange = +sb.dataset.range;
			savePrefs();
			renderDetails();
			return loadHistory();
		}
		const tw = e.target.closest("[data-dir]"), tb = e.target.closest("[data-tree]");
		if (tw) {
			const p = tw.dataset.dir;
			state.collapsed.has(p) ? state.collapsed.delete(p) : state.collapsed.add(p);
		} else if (tb && state.details) {
			state.collapsed = tb.dataset.tree === "collapse" ? allDirs(state.details.files) : new Set();
		} else return;
		renderDetails();
	});

	document.addEventListener("mouseover", (e) => {
		if (touchUI()) return;   // a tap also sends mouseover; on touch the tap shows it (wireTouchTips)
		const el = e.target.closest?.("[data-tip]");
		if (!el) return;
		const r = el.getBoundingClientRect();
		showTip(r.left, r.bottom - 12, `<b>${esc(el.dataset.tipTitle || "")}</b><div>${esc(el.dataset.tip)}</div>`);
		state.tipEl = el;
		el.addEventListener("mouseleave", hideTip, { once: true });
	});

	$("#search").addEventListener("input", (e) => { state.filter.text = e.target.value; render(); });

	document.addEventListener("mousedown", (e) => { if (menuOpen() && !e.target.closest("#menu")) closeMenu(); });
	$("#modal-bg").addEventListener("mousedown", (e) => { if (e.target.id === "modal-bg") closeModal(); });

	document.addEventListener("keydown", (e) => {
		if (e.key === "Escape") { closeMenu(); closeModal(); return; }
		if (modalOpen()) {
			if (e.key === "Enter" && !e.defaultPrevented && e.target.tagName === "INPUT" && !e.target.classList.contains("newtag")) $("#modal .btn.primary")?.click();
			return;
		}
		if (e.target.matches("input, textarea, select")) {
			if (e.key === "Escape") e.target.blur();
			return;
		}
		if (e.key === "/") { e.preventDefault(); $("#search").focus(); }
		else if (e.key === "Delete") command("remove");
		else if (e.key.toLowerCase() === "t" && !e.ctrlKey) command("tags");
		else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "a") { e.preventDefault(); rows.forEach((t) => state.sel.add(t.hash)); afterSelect(); }
		else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "o") { e.preventDefault(); addDialog(); }
		else if ((e.ctrlKey || e.metaKey) && ["ArrowUp", "ArrowDown", "Home", "End"].includes(e.key)) {
			e.preventDefault();
			movePos({ ArrowUp: "up", ArrowDown: "down", Home: "top", End: "bottom" }[e.key]);
		}
		else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
			e.preventDefault();
			let i = rows.findIndex((t) => t.hash === state.focus) + (e.key === "ArrowDown" ? 1 : -1);
			i = Math.max(0, Math.min(rows.length - 1, i));
			if (!rows[i]) return;
			// Shift: the selection is the range from the anchor to here, so going back the
			// other way shrinks it towards the anchor and then grows past it.
			const a = e.shiftKey ? rows.findIndex((t) => t.hash === state.anchor) : -1;
			state.sel.clear();
			if (a < 0) state.anchor = e.shiftKey && state.focus ? state.focus : rows[i].hash;
			const from = Math.max(0, rows.findIndex((t) => t.hash === state.anchor));
			if (e.shiftKey) rows.slice(Math.min(from, i), Math.max(from, i) + 1).forEach((t) => state.sel.add(t.hash));
			else state.sel.add(rows[i].hash);
			state.focus = rows[i].hash;
			afterSelect();
			$(`#torrents tr[data-hash="${rows[i].hash}"]`)?.scrollIntoView({ block: "nearest" });
		}
	});

	// Drop .torrent files anywhere to add them.
	// The highlight goes when the drag leaves the window, and also when dragover stops coming
	// (browsers fire it several times a second while the pointer is inside): when the drag is called
	// off, LibreWolf may send its last dragleave without the file types, or none at all.
	let depth = 0, idle = 0;
	const isFiles = (e) => isFileDrag(e.dataTransfer);
	const unhighlight = () => { depth = 0; clearTimeout(idle); document.body.classList.remove("dropping"); };
	// Browsers only: the desktop window may send dragover only while the pointer moves.
	const keepAlive = () => { clearTimeout(idle); if (!window.tableroDesktop) idle = setTimeout(unhighlight, 1000); };
	document.addEventListener("dragenter", (e) => { if (isFiles(e) && !modalOpen()) { depth++; document.body.classList.add("dropping"); keepAlive(); } });
	// Leaving the window has no relatedTarget in browsers; the desktop window never gives one.
	const leftWindow = (e) => !e.relatedTarget && !window.tableroDesktop;
	document.addEventListener("dragleave", (e) => { if (leftWindow(e) || (isFiles(e) && --depth <= 0)) unhighlight(); });
	const accept = (e) => { if (isFiles(e)) { e.preventDefault(); setDropEffect(e.dataTransfer); if (document.body.classList.contains("dropping")) keepAlive(); } };
	document.addEventListener("dragenter", accept);
	document.addEventListener("dragover", accept);
	document.addEventListener("drop", (e) => {
		if (!isFiles(e)) return;
		e.preventDefault();
		unhighlight();
		const zone = modalOpen() && $("#add-drop");
		if (zone) return zone.addDrop(e.dataTransfer);
		const all = droppedFiles(e.dataTransfer);
		if (all.length) return addDropped(all);
		if (window.tableroDesktop) return; // the window passes the files on by itself
		toast("No llegó ningún archivo al soltar (" + ([...e.dataTransfer.types].join(", ") || "sin datos") + "). Usa Agregar → “elige archivos”.", true);
	});
	// The desktop window: WebKit does not show the page the paths of files dropped from the
	// file manager, so the window takes the drop itself and hands over the paths here.
	window.tableroDropPaths = (paths) => {
		unhighlight();
		addDropped(paths.map((path) => ({ path, name: path.split("/").pop() })));
	};
}

function applyLayout() {
	document.documentElement.style.setProperty("--sidebar-w", prefs.sidebarW + "px");
	document.documentElement.style.setProperty("--details-h", prefs.detailsH + "px");
}

// ------------------------------------------------------------ start

// "para rTorrent" spans from a third of "Tablero" to its right edge, and the logo runs
// from the top of "Tablero" to the bottom of the "p" below, whatever font the browser or
// the window ends up using. measureText only measures: it reads no pixels back, which
// LibreWolf's anti-fingerprinting would spoil.
function fitBrand() {
	const b = $(".brand-name b"), sm = $(".brand-name small"), logo = $(".brand .logo");
	if (!b || !sm || !logo) return;
	sm.style.fontSize = "10px";
	const w = sm.scrollWidth;
	if (w) sm.style.fontSize = (10 * (b.offsetWidth * 2 / 3) / w).toFixed(2) + "px";
	let top = 1, bottom = 26;
	try {
		const ctx = document.createElement("canvas").getContext("2d");
		const ink = (el) => {
			const cs = getComputedStyle(el), size = parseFloat(cs.fontSize);
			ctx.font = `${cs.fontWeight} ${cs.fontSize} ${cs.fontFamily}`;
			const m = ctx.measureText(el.textContent);
			if (!m.fontBoundingBoxAscent) throw new Error("no font metrics");
			// With line-height 1 the font's content area is centred in a line as tall as the font.
			const base = el.offsetTop + (size - m.fontBoundingBoxAscent - m.fontBoundingBoxDescent) / 2 + m.fontBoundingBoxAscent;
			return [base - m.actualBoundingBoxAscent, base + m.actualBoundingBoxDescent];
		};
		top = ink(b)[0];
		bottom = ink(sm)[1];
	} catch { /* keep the defaults */ }
	const h = Math.max(16, bottom - top);
	logo.style.width = logo.style.height = h.toFixed(1) + "px";
	logo.style.marginTop = top.toFixed(1) + "px";
}
fitBrand();
document.fonts?.ready.then(fitBrand);

// WebKit (the desktop window) clips a scrolled list at the pixel above its top when that top falls
// between pixels (the bar and the tag bar above can have fractional heights), so a line of the rows
// scrolled under the titles showed above them. Start the list on a whole pixel.
function snapList() {
	const w = $("#table-wrap");
	if (!w) return;
	w.style.marginTop = "";
	const f = w.getBoundingClientRect().top % 1;
	if (f > 0.01) w.style.marginTop = 1 - f + "px";
}
if (window.ResizeObserver) {
	const ro = new ResizeObserver(snapList);
	["#toolbar", "#tagbar", "#main"].forEach((s) => $(s) && ro.observe($(s)));
}

(async function init() {
	$$("[data-icon]").forEach((el) => (el.innerHTML = iconHTML("lucide:" + el.dataset.icon)));
	if (!prefsStored) {
		try {
			const r = await api("/api/prefs");
			if (r.prefs && Object.keys(r.prefs).length) {
				Object.assign(prefs, r.prefs);
				try { localStorage.setItem("tablero.prefs", JSON.stringify(prefs)); } catch { /* private mode */ }
				document.documentElement.dataset.theme = prefs.theme;
			}
		} catch { /* keep the defaults */ }
	}
	// Known before rTorrent answers, for the "Sin conexión con rTorrent" line.
	try { state.platform = (await api("/api/access")).platform || "desktop"; } catch { /* desktop */ }
	applyLayout();
	if (touchUI()) {
		// No keyboard on a touch screen: the shortcuts are left out of the button names.
		$$("#toolbar [title]").forEach((b) => (b.title = b.title.replace(/\s*\([^)]*\)$/, "")));
		$("#search").placeholder = "Filtrar por nombre…";
		wireTouchTips();
		wireTwoFingerReload();
	}
	wire();
	try { state.tagConf = await api("/api/tags"); } catch { /* keep defaults */ }
	loadGeneral();
	render();
	refresh();
	setTimeout(firstBackupFolder, 1500);
})();
