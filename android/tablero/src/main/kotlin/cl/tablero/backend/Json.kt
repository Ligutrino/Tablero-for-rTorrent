package cl.tablero.backend

/**
 * JSON sin dependencias (org.json no existe en la JVM de las pruebas). Lee objetos como
 * LinkedHashMap, listas, String, Long (enteros), Double (con punto o exponente), Boolean y null,
 * igual que json.loads de Python distingue int de float.
 */
object Json {
    class Error(msg: String) : BadRequest(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw Error("Extra data: line 1 column ${p.i + 1} (char ${p.i})")
        return v
    }

    fun parse(bytes: ByteArray): Any? = parse(String(bytes, Charsets.UTF_8).removePrefix("﻿"))

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }
        fun err(what: String): Nothing = throw Error("$what: line 1 column ${i + 1} (char $i)")

        fun value(): Any? {
            if (i >= s.length) err("Expecting value")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                'N' -> lit("NaN", Double.NaN)
                'I' -> lit("Infinity", Double.POSITIVE_INFINITY)
                else -> if (c == '-' || c in '0'..'9') num() else err("Expecting value")
            }
        }

        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) err("Expecting value")
            i += w.length
            return v
        }

        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            if (s.startsWith("Infinity", i)) { i += 8; return Double.NEGATIVE_INFINITY }
            while (i < s.length && s[i] in '0'..'9') i++
            var float = false
            if (i < s.length && s[i] == '.') { float = true; i++; while (i < s.length && s[i] in '0'..'9') i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                float = true; i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(start, i)
            if (t == "-" || t.isEmpty()) err("Expecting value")
            return if (float) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }

        fun str(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) err("Unterminated string starting at")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) err("Unterminated string starting at")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) err("Invalid \\uXXXX escape")
                                val h = s.substring(i, i + 4).toIntOrNull(16) ?: err("Invalid \\uXXXX escape")
                                sb.append(h.toChar()); i += 4
                            }
                            else -> err("Invalid \\escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun obj(): Obj {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') err("Expecting property name enclosed in double quotes")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') err("Expecting ':' delimiter")
                i++
                ws()
                m[k] = value()
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return m }
                err("Expecting ',' delimiter")
            }
        }

        fun arr(): MutableList<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws()
                l.add(value())
                ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return l }
                err("Expecting ',' delimiter")
            }
        }
    }

    /** json.dumps(ensure_ascii=False). Una llamada fallida de un multicall va como su mensaje. */
    fun dumps(v: Any?, indent: Int? = null): String = StringBuilder().also { write(it, v, indent, 0) }.toString()

    private fun nl(sb: StringBuilder, indent: Int?, level: Int) {
        if (indent != null) { sb.append('\n'); repeat(indent * level) { sb.append(' ') } }
    }

    private fun write(sb: StringBuilder, v: Any?, indent: Int?, level: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Long, is Int, is Short, is Byte -> sb.append(v.toString())
            is Double -> sb.append(num(v))
            is Float -> sb.append(num(v.toDouble()))
            is String -> str(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(if (indent == null) ", " else ",")
                    first = false
                    nl(sb, indent, level + 1)
                    str(sb, k.toString()); sb.append(": ")
                    write(sb, x, indent, level + 1)
                }
                nl(sb, indent, level)
                sb.append('}')
            }
            is Iterable<*> -> {
                val l = v.toList()
                if (l.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                l.forEachIndexed { n, x ->
                    if (n > 0) sb.append(if (indent == null) ", " else ",")
                    nl(sb, indent, level + 1)
                    write(sb, x, indent, level + 1)
                }
                nl(sb, indent, level)
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList(), indent, level)
            is LongArray -> write(sb, v.toList(), indent, level)
            is Throwable -> str(sb, v.message ?: v.toString())
            else -> str(sb, v.toString())
        }
    }

    private fun num(d: Double): String = when {
        d.isNaN() -> "NaN"
        d == Double.POSITIVE_INFINITY -> "Infinity"
        d == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> {
            val r = d.toString()  // 0.5, 1.0, 1.0E21: todos JSON válido
            r.replace("E", "e")
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000c' -> sb.append("\\f")
            else -> if (c < ' ' || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }
}
