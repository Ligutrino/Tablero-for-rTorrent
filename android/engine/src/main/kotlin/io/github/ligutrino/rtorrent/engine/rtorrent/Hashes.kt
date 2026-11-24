package io.github.ligutrino.rtorrent.engine.rtorrent

import io.github.ligutrino.rtorrent.engine.api.EngineException
import java.net.URLDecoder
import java.security.MessageDigest

internal object Magnet {
    /** Infohash v1 en hex mayúsculas de un magnet (acepta hex o base32), o `null`. */
    fun infoHash(uri: String): String? {
        if (!uri.startsWith("magnet:?")) return null
        for (part in uri.substringAfter('?').split('&')) {
            val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
            if (k != "xt") continue
            val xt = URLDecoder.decode(v, "UTF-8")
            if (!xt.startsWith("urn:btih:", ignoreCase = true)) continue
            val h = xt.substring(9)
            return when (h.length) {
                40 -> h.uppercase()
                32 -> base32ToHex(h.uppercase())
                else -> null
            }
        }
        return null
    }

    private fun base32ToHex(s: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L; var bits = 0
        val out = StringBuilder()
        for (c in s) {
            val v = alphabet.indexOf(c); if (v < 0) return null
            buffer = (buffer shl 5) or v.toLong(); bits += 5
            while (bits >= 8) { bits -= 8; out.append("%02X".format(((buffer shr bits) and 0xFF).toInt())) }
        }
        return out.toString().takeIf { it.length == 40 }
    }
}

internal object Bencode {
    /** SHA-1 del diccionario "info" tal como aparece en el archivo (infohash v1). */
    fun infoHashV1(data: ByteArray): String {
        try {
            var i = 0
            if (data[i] != 'd'.code.toByte()) throw EngineException("El .torrent no es un diccionario bencode")
            i++
            while (data[i] != 'e'.code.toByte()) {
                val (key, afterKey) = readString(data, i)
                val end = skip(data, afterKey)
                if (key == "info") {
                    val md = MessageDigest.getInstance("SHA-1")
                    md.update(data, afterKey, end - afterKey)
                    return md.digest().joinToString("") { "%02X".format(it) }
                }
                i = end
            }
        } catch (e: IndexOutOfBoundsException) {
            throw EngineException("El .torrent está truncado", e)
        } catch (e: NumberFormatException) {
            throw EngineException("El .torrent está mal formado", e)
        }
        throw EngineException("El .torrent no tiene diccionario \"info\"")
    }

    private fun readString(d: ByteArray, start: Int): Pair<String, Int> {
        var i = start
        while (d[i] != ':'.code.toByte()) i++
        val len = String(d, start, i - start, Charsets.US_ASCII).toInt()
        return String(d, i + 1, len, Charsets.UTF_8) to (i + 1 + len)
    }

    /** Devuelve la posición justo después del valor que empieza en [start]. */
    private fun skip(d: ByteArray, start: Int): Int {
        var i = start
        return when (d[i].toInt().toChar()) {
            'i' -> { while (d[i] != 'e'.code.toByte()) i++; i + 1 }
            'l', 'd' -> {
                i++
                while (d[i] != 'e'.code.toByte()) i = skip(d, i)
                i + 1
            }
            in '0'..'9' -> readString(d, i).second.let { it }
            else -> throw EngineException("Bencode inválido en la posición $i")
        }
    }
}
