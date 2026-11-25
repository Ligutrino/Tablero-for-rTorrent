package cl.tablero.backend

import java.io.ByteArrayOutputStream

/**
 * _bdecode / _bencode de tablero.py. Los textos quedan como ByteArray; las claves de un
 * diccionario como String en ISO-8859-1 (un carácter por byte, sin pérdida), así se ordenan
 * como bytes al volver a codificar. Bajo "_info_span" queda dónde empieza y termina "info"
 * (para el info hash).
 */
object Bencode {
    const val INFO_SPAN = "_info_span"

    fun decode(data: ByteArray): Any = decodeAt(data, 0).first

    fun decodeAt(data: ByteArray, start: Int): Pair<Any, Int> {
        var i = start
        if (i >= data.size) throw BadRequest("bencode: fin inesperado")
        when (data[i].toInt().toChar()) {
            'i' -> {
                val end = indexOf(data, 'e', i)
                return String(data, i + 1, end - i - 1, Charsets.ISO_8859_1).trim().toLongOrNull()
                    ?.let { it to end + 1 } ?: throw BadRequest("bencode: entero inválido")
            }
            'l' -> {
                val out = ArrayList<Any>()
                i++
                while (true) {
                    if (i >= data.size) throw BadRequest("bencode: lista sin cerrar")
                    if (data[i] == 'e'.code.toByte()) break
                    val (v, n) = decodeAt(data, i)
                    out.add(v); i = n
                }
                return out to i + 1
            }
            'd' -> {
                val out = LinkedHashMap<String, Any>()
                i++
                while (true) {
                    if (i >= data.size) throw BadRequest("bencode: diccionario sin cerrar")
                    if (data[i] == 'e'.code.toByte()) break
                    val (k, a) = decodeAt(data, i)
                    val key = String(k as? ByteArray ?: throw BadRequest("bencode: clave no es texto"), Charsets.ISO_8859_1)
                    val (v, b) = decodeAt(data, a)
                    out[key] = v
                    if (key == "info") out[INFO_SPAN] = intArrayOf(a, b)
                    i = b
                }
                return out to i + 1
            }
            else -> {
                val colon = indexOf(data, ':', i)
                val n = String(data, i, colon - i, Charsets.ISO_8859_1).toIntOrNull()
                    ?: throw BadRequest("bencode: largo inválido")
                if (n < 0 || colon + 1 + n > data.size) throw BadRequest("bencode: texto cortado")
                return data.copyOfRange(colon + 1, colon + 1 + n) to colon + 1 + n
            }
        }
    }

    private fun indexOf(data: ByteArray, c: Char, from: Int): Int {
        for (j in from until data.size) if (data[j] == c.code.toByte()) return j
        throw BadRequest("bencode: falta '$c'")
    }

    fun encode(x: Any?): ByteArray = ByteArrayOutputStream().also { enc(it, x) }.toByteArray()

    private fun enc(out: ByteArrayOutputStream, x: Any?) {
        when (x) {
            is Long, is Int -> out.write("i${x}e".toByteArray())
            is Boolean -> out.write("i${if (x) 1 else 0}e".toByteArray())
            is String -> bytes(out, x.toByteArray(Charsets.UTF_8))
            is ByteArray -> bytes(out, x)
            is List<*> -> { out.write('l'.code); x.forEach { enc(out, it) }; out.write('e'.code) }
            is Map<*, *> -> {
                out.write('d'.code)
                for (k in x.keys.map { it.toString() }.filter { it != INFO_SPAN }.sorted()) {
                    bytes(out, k.toByteArray(Charsets.ISO_8859_1))
                    enc(out, x[k])
                }
                out.write('e'.code)
            }
            else -> throw BadRequest("bencode: tipo no soportado")
        }
    }

    private fun bytes(out: ByteArrayOutputStream, b: ByteArray) {
        out.write("${b.size}:".toByteArray()); out.write(b)
    }

    /** Clave de diccionario desde un texto UTF-8 (b"custom" en Python). */
    fun key(s: String) = String(s.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    fun keyText(k: String) = String(k.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    fun text(v: Any?): String = if (v is ByteArray) String(v, Charsets.UTF_8) else ""

    @Suppress("UNCHECKED_CAST")
    fun dict(v: Any?): MutableMap<String, Any>? = v as? MutableMap<String, Any>
}
