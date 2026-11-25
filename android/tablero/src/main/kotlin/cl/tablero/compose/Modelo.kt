package cl.tablero.compose

/*
 * Lo que la API de Tablero entrega (GET /api/torrents, /api/tags...), con tipos. Los mapas vienen
 * de Json.parse o del backend mismo: números Long o Double, textos, listas y mapas.
 */

internal fun lng(v: Any?): Long = when (v) {
    is Long -> v
    is Number -> v.toLong()
    is Boolean -> if (v) 1 else 0
    is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong() ?: 0
    else -> 0
}
internal fun dbl(v: Any?): Double = when (v) {
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull() ?: 0.0
    else -> 0.0
}
internal fun str(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    else -> v.toString()
}
internal fun bool(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is String -> v.isNotEmpty()
    is Collection<*> -> v.isNotEmpty()
    is Map<*, *> -> v.isNotEmpty()
    else -> true
}
@Suppress("UNCHECKED_CAST")
internal fun asMap(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()
internal fun listOfAny(v: Any?): List<Any?> = v as? List<Any?> ?: emptyList()

/** Un torrent de GET /api/torrents. */
data class Torrent(
    val hash: String,
    val name: String,
    val size: Long,
    val done: Long,
    val downRate: Long,
    val upRate: Long,
    val upTotal: Long,
    val downTotal: Long,
    val ratio: Double,
    val state: Boolean,
    val active: Boolean,
    val complete: Boolean,
    val hashing: Boolean,
    val chunksHashed: Long,
    val chunks: Long,
    val message: String,
    val seeds: Long,
    val peers: Long,
    val tags: List<String>,
    val finished: Long,
    val added: Long,
    val directory: String,
    val priority: Int,
    val private: Boolean,
    val files: Long,
    val opts: Map<String, Any?>,
    val peersMax: Long,
    val peersMin: Long,
    val uploadsMax: Long,
    val pos: Long,
    /** null: este rTorrent no baja en orden. */
    val sequential: Boolean?,
    val status: String,
    val eta: Long,
    val tracker: String,
    val queuePos: Long,
    val jobState: String?,
    val jobError: String?,
    val noSpaceNeed: Long?,
    val noSpaceFree: Long?,
) {
    val left: Long get() = size - done
    val progress: Double get() = if (size > 0) done.toDouble() / size else 1.0

    companion object {
        fun from(o: Map<String, Any?>): Torrent {
            val job = o["job"] as? Map<*, *>
            val ns = o["nospace"] as? Map<*, *>
            return Torrent(
                hash = str(o["hash"]), name = str(o["name"]), size = lng(o["size"]), done = lng(o["done"]),
                downRate = lng(o["down_rate"]), upRate = lng(o["up_rate"]), upTotal = lng(o["up_total"]),
                downTotal = lng(o["down_total"]), ratio = dbl(o["ratio"]), state = bool(o["state"]),
                active = bool(o["active"]), complete = bool(o["complete"]), hashing = bool(o["hashing"]),
                chunksHashed = lng(o["chunks_hashed"]), chunks = lng(o["chunks"]), message = str(o["message"]),
                seeds = lng(o["seeds"]), peers = lng(o["peers"]), tags = listOfAny(o["tags"]).map { str(it) },
                finished = lng(o["finished"]), added = lng(o["added"]), directory = str(o["directory"]),
                priority = lng(o["priority"]).toInt(), private = bool(o["private"]), files = lng(o["files"]),
                opts = asMap(o["opts"]), peersMax = lng(o["peers_max"]), peersMin = lng(o["peers_min"]),
                uploadsMax = lng(o["uploads_max"]), pos = lng(o["pos"]),
                sequential = o["sequential"]?.let { bool(it) }, status = str(o["status"]), eta = lng(o["eta"] ?: -1L),
                tracker = str(o["tracker"]), queuePos = lng(o["queue_pos"]),
                jobState = job?.get("state") as? String, jobError = job?.get("error") as? String,
                noSpaceNeed = ns?.let { lng(it["need"]) }, noSpaceFree = ns?.let { lng(it["free"]) },
            )
        }
    }
}

/** stats de GET /api/torrents: lo de rTorrent entero. */
data class Stats(
    val downRate: Long = 0, val upRate: Long = 0, val downLimit: Long = 0, val upLimit: Long = 0,
    val version: String = "", val port: Long = 0, val portOpen: Boolean = false, val portNext: Long = 0,
    val incoming: Long = 0, val incomingSeen: Long = 0,
) {
    companion object {
        fun from(o: Map<String, Any?>) = Stats(
            downRate = lng(o["down_rate"]), upRate = lng(o["up_rate"]), downLimit = lng(o["down_limit"]),
            upLimit = lng(o["up_limit"]), version = str(o["version"]), port = lng(o["port"]),
            portOpen = bool(o["port_open"]), portNext = lng(o["port_next"]), incoming = lng(o["incoming"]),
            incomingSeen = lng(o["incoming_seen"]),
        )
    }
}

/** Una etiqueta configurada en tags.json: color, ícono o logo, y cómo se ve en cada lugar. */
data class TagEntry(
    val color: String = "",
    val icon: String = "",
    val logo: String = "",
    val logoRatio: Double = 0.0,
    val logoTone: String = "",
    val show: Map<String, String> = emptyMap(),
) {
    fun toObj(): MutableMap<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("color", color); put("icon", icon)
        if (logo.isNotEmpty()) { put("logo", logo); put("logoRatio", logoRatio); if (logoTone.isNotEmpty()) put("logoTone", logoTone) }
        if (show.isNotEmpty()) put("show", LinkedHashMap<String, Any?>(show))
    }

    companion object {
        fun from(o: Map<String, Any?>) = TagEntry(
            color = str(o["color"]), icon = str(o["icon"]), logo = str(o["logo"]), logoRatio = dbl(o["logoRatio"]),
            logoTone = str(o["logoTone"]), show = asMap(o["show"]).mapValues { str(it.value) },
        )
    }
}

data class TagConf(val tags: Map<String, TagEntry> = emptyMap(), val order: List<String> = emptyList()) {
    fun toObj(): Map<String, Any?> = linkedMapOf(
        "tags" to LinkedHashMap<String, Any?>().apply { for ((k, v) in tags) put(k, v.toObj()) },
        "order" to order.toList(),
    )

    companion object {
        fun from(o: Map<String, Any?>) = TagConf(
            tags = asMap(o["tags"]).mapValues { TagEntry.from(asMap(it.value)) },
            order = listOfAny(o["order"]).map { str(it) },
        )
    }
}

/** GET /api/general: lo elegido en Límites y conexiones y, para los campos vacíos, lo de rTorrent. */
data class GeneralConf(
    val general: Map<String, Any?> = emptyMap(),
    val rtorrent: Map<String, Any?> = emptyMap(),
    val torrent: Map<String, Any?> = emptyMap(),
) {
    companion object {
        fun from(o: Map<String, Any?>) = GeneralConf(asMap(o["general"]), asMap(o["rtorrent"]), asMap(o["torrent"]))
    }
}
