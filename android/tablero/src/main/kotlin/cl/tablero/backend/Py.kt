package cl.tablero.backend

import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode

/*
 * Lo que tablero.py da por hecho en Python y aquí hay que escribir: verdad de un valor, int() y
 * float() con sus errores, round(), os.path y urllib.parse.quote/unquote. Nada de esto depende de
 * Android: el backend entero corre igual en la JVM (las pruebas de tools/ lo usan así).
 */

/** ValueError, KeyError, TypeError o IndexError de tablero.py: la API responde 400 con el mensaje. */
open class BadRequest(message: String) : RuntimeException(message)

/** LookupError (Reproducir: el torrent o el archivo no sirve): 404. */
class NotFound(message: String) : RuntimeException(message)

/** ConnectionError: rTorrent no contesta. Como en Python, la API responde 502. */
class RtConnectionError(message: String, cause: Throwable? = null) : IOException(message, cause)

/** El resultado de una llamada fallida dentro de un system.multicall (Exception en Python). */
class CallFailed(message: String) : RuntimeException(message) {
    override fun toString() = message ?: "error"
}

typealias Obj = MutableMap<String, Any?>

fun obj(vararg pairs: Pair<String, Any?>): Obj = linkedMapOf(*pairs)

/** bool(v) de Python. */
fun truthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Long -> v != 0L
    is Int -> v != 0
    is Double -> v != 0.0
    is Number -> v.toDouble() != 0.0
    is String -> v.isNotEmpty()
    is Collection<*> -> v.isNotEmpty()
    is Map<*, *> -> v.isNotEmpty()
    is ByteArray -> v.isNotEmpty()
    else -> true
}

/** `a or b` de Python. */
fun or(a: Any?, b: Any?): Any? = if (truthy(a)) a else b

private fun pyType(v: Any?) = when (v) {
    null -> "NoneType"; is String -> "str"; is Boolean -> "bool"; is Long, is Int -> "int"
    is Double -> "float"; is List<*> -> "list"; is Map<*, *> -> "dict"; else -> v::class.java.simpleName
}

/** int(v) de Python: trunca un float, lee un texto con espacios; lo demás es un error 400. */
fun pyInt(v: Any?): Long = when (v) {
    is Long -> v
    is Int -> v.toLong()
    is Boolean -> if (v) 1 else 0
    is Double -> if (v.isNaN() || v.isInfinite()) throw BadRequest("cannot convert float $v to integer") else v.toLong()
    is Number -> v.toLong()
    is String -> v.trim().replace("_", "").toLongOrNull()
        ?: throw BadRequest("invalid literal for int() with base 10: '${v}'")
    else -> throw BadRequest("int() argument must be a string, a bytes-like object or a real number, not '${pyType(v)}'")
}

/** float(v) de Python. */
fun pyFloat(v: Any?): Double = when (v) {
    is Double -> v
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> v.trim().toDoubleOrNull() ?: throw BadRequest("could not convert string to float: '${v}'")
    else -> throw BadRequest("float() argument must be a string or a real number, not '${pyType(v)}'")
}

/** round(x, n) de Python (al par más cercano sobre el valor exacto del double). */
fun pyRound(x: Double, n: Int): Double =
    if (x.isNaN() || x.isInfinite()) x else BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toDouble()

/** body["k"] de Python: sin la clave es un KeyError ('k'), que la API devuelve como 400. */
fun Map<String, Any?>.need(k: String): Any? = if (containsKey(k)) get(k) else throw BadRequest("'$k'")

@Suppress("UNCHECKED_CAST")
fun asObj(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: throw BadRequest("se esperaba un objeto, no '${pyType(v)}'")

fun asList(v: Any?): List<Any?> = when (v) {
    is List<*> -> v
    is String -> v.map { it.toString() }   // Python recorre un texto letra por letra
    null -> throw BadRequest("'NoneType' object is not iterable")
    else -> throw BadRequest("'${pyType(v)}' object is not iterable")
}

fun asStr(v: Any?): String = v as? String ?: throw BadRequest("se esperaba un texto, no '${pyType(v)}'")

fun strList(v: Any?): List<String> = asList(v).map { asStr(it) }

/** str(v) de Python, para los pocos lugares donde se formatea un valor cualquiera. */
fun pyStr(v: Any?): String = when (v) {
    null -> "None"
    is Boolean -> if (v) "True" else "False"
    else -> v.toString()
}

// ---------------------------------------------------------------- os.path

object PyPath {
    @Volatile var home: String = System.getProperty("user.home")?.takeIf { it.isNotEmpty() }
        ?: System.getenv("HOME") ?: "/"

    fun join(first: String, vararg rest: String): String {
        val sb = StringBuilder(first)
        for (p in rest) {
            if (p.startsWith("/")) { sb.setLength(0); sb.append(p) }
            else if (sb.isEmpty() || sb.endsWith("/")) sb.append(p)
            else sb.append('/').append(p)
        }
        return sb.toString()
    }

    fun dirname(p: String): String {
        val i = p.lastIndexOf('/') + 1
        var head = p.substring(0, i)
        if (head.isNotEmpty() && head != "/".repeat(head.length)) head = head.trimEnd('/')
        return head
    }

    fun basename(p: String): String = p.substring(p.lastIndexOf('/') + 1)

    fun isabs(p: String) = p.startsWith("/")

    fun expanduser(p: String): String = when {
        p == "~" -> home
        p.startsWith("~/") -> home.trimEnd('/') + p.substring(1)
        else -> p
    }

    /** os.path.realpath: absoluta, sin . ni .., con los enlaces resueltos (también si no existe). */
    fun realpath(p: String): String {
        val f = File(if (isabs(p)) p else join(File("").absolutePath, p))
        return try { f.canonicalPath } catch (e: IOException) { f.absoluteFile.normalize().path }
    }

    fun abspath(p: String): String = File(if (isabs(p)) p else join(File("").absolutePath, p)).normalize().path

    fun isdir(p: String) = p.isNotEmpty() && File(p).isDirectory
    fun isfile(p: String) = p.isNotEmpty() && File(p).isFile
    fun exists(p: String) = p.isNotEmpty() && File(p).exists()
    fun lexists(p: String) = p.isNotEmpty() && (File(p).exists() ||
        runCatching { java.nio.file.Files.isSymbolicLink(File(p).toPath()) }.getOrDefault(false))
    fun islink(p: String) = runCatching { java.nio.file.Files.isSymbolicLink(File(p).toPath()) }.getOrDefault(false)
    fun canWrite(p: String) = p.isNotEmpty() && File(p).canWrite()
}

// ---------------------------------------------------------------- urllib.parse

private const val SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.-~"
private val HEX = "0123456789ABCDEF".toCharArray()

/** urllib.parse.quote(s, safe=""). */
fun quote(s: String): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xff)
        if (c < 128 && SAFE.indexOf(c.toChar()) >= 0) sb.append(c.toChar())
        else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
    }
    return sb.toString()
}

/** urllib.parse.unquote(s): %XX en UTF-8 (lo inválido queda como U+FFFD); el + no cambia. */
fun unquote(s: String): String {
    if ('%' !in s) return s
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length) {
            val pair = s.substring(i + 1, i + 3)
            if (pair.all { it in "0123456789abcdefABCDEF" }) { out.write(pair.toInt(16)); i += 3; continue }
        }
        val n = if (c.isHighSurrogate() && i + 1 < s.length) 2 else 1
        out.write(s.substring(i, i + n).toByteArray(Charsets.UTF_8))
        i += n
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

/** urllib.parse.parse_qs (sin valores vacíos), con + como espacio. */
fun parseQs(query: String?): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    if (query.isNullOrEmpty()) return out
    for (part in query.split('&', ';')) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        if (eq < 0) continue
        val k = unquote(part.substring(0, eq).replace('+', ' '))
        val v = unquote(part.substring(eq + 1).replace('+', ' '))
        if (v.isEmpty()) continue
        out.getOrPut(k) { mutableListOf() }.add(v)
    }
    return out
}

fun hexOf(b: ByteArray, upper: Boolean = false): String {
    val sb = StringBuilder(b.size * 2)
    for (x in b) {
        val c = x.toInt() and 0xff
        sb.append(HEX[c shr 4]).append(HEX[c and 15])
    }
    return if (upper) sb.toString() else sb.toString().lowercase()
}

/** bytes.fromhex: ValueError si no es hexadecimal. */
fun fromHex(s: String): ByteArray {
    val t = s.filterNot { it.isWhitespace() }
    if (t.length % 2 != 0) throw BadRequest("non-hexadecimal number found in fromhex() arg")
    return ByteArray(t.length / 2) { i ->
        val v = t.substring(2 * i, 2 * i + 2).toIntOrNull(16) ?: throw BadRequest("non-hexadecimal number found in fromhex() arg")
        v.toByte()
    }
}

fun sha1(b: ByteArray, off: Int = 0, len: Int = b.size): ByteArray =
    java.security.MessageDigest.getInstance("SHA-1").apply { update(b, off, len) }.digest()

/** base64.b32decode (alfabeto RFC 4648, mayúsculas). */
fun base32Decode(s: String): ByteArray {
    val alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    val t = s.trimEnd('=')
    val out = java.io.ByteArrayOutputStream()
    var buf = 0L; var bits = 0
    for (c in t) {
        val v = alpha.indexOf(c)
        if (v < 0) throw BadRequest("Non-base32 digit found")
        buf = (buf shl 5) or v.toLong(); bits += 5
        if (bits >= 8) { bits -= 8; out.write(((buf shr bits) and 0xff).toInt()) }
    }
    return out.toByteArray()
}

fun nowSec(): Double = System.currentTimeMillis() / 1000.0

fun log(msg: String) = System.err.println(msg)
