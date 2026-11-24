package io.github.ligutrino.rtorrent.engine.rpc

import io.github.ligutrino.rtorrent.engine.api.EngineException
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/** Error devuelto por el servidor XML-RPC (`<fault>`). */
class XmlRpcFault(val code: Long, val faultString: String) :
    EngineException("XML-RPC fault $code: $faultString")

/**
 * Codificador/decodificador XML-RPC mínimo (sin dependencias; funciona en Android y en la JVM).
 *
 * Tipos de parámetros: String, Int, Long, Boolean, Double, ByteArray (base64), List, Map<String, *>.
 * Tipos de resultados: String, Long (todos los enteros), Boolean, Double, ByteArray, List<Any?>, Map<String, Any?>.
 */
object XmlRpc {

    fun encodeCall(method: String, params: List<Any?>): ByteArray {
        val sb = StringBuilder(256)
        sb.append("<?xml version=\"1.0\"?><methodCall><methodName>")
        escape(sb, method)
        sb.append("</methodName><params>")
        for (p in params) {
            sb.append("<param>")
            encodeValue(sb, p)
            sb.append("</param>")
        }
        sb.append("</params></methodCall>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun encodeValue(sb: StringBuilder, v: Any?) {
        sb.append("<value>")
        when (v) {
            null -> sb.append("<string></string>")
            is String -> { sb.append("<string>"); escape(sb, v); sb.append("</string>") }
            is Int, is Short, is Byte -> sb.append("<i4>").append(v).append("</i4>")
            is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) sb.append("<i4>").append(v).append("</i4>")
                       else sb.append("<i8>").append(v).append("</i8>")
            is Boolean -> sb.append("<boolean>").append(if (v) 1 else 0).append("</boolean>")
            is Double, is Float -> sb.append("<double>").append(v).append("</double>")
            is ByteArray -> sb.append("<base64>").append(Base64.getEncoder().encodeToString(v)).append("</base64>")
            is List<*> -> {
                sb.append("<array><data>")
                v.forEach { encodeValue(sb, it) }
                sb.append("</data></array>")
            }
            is Map<*, *> -> {
                sb.append("<struct>")
                for ((k, value) in v) {
                    sb.append("<member><name>"); escape(sb, k.toString()); sb.append("</name>")
                    encodeValue(sb, value)
                    sb.append("</member>")
                }
                sb.append("</struct>")
            }
            else -> throw IllegalArgumentException("Tipo XML-RPC no soportado: ${v::class.java.name}")
        }
        sb.append("</value>")
    }

    private fun escape(sb: StringBuilder, s: String) {
        for (c in s) when (c) {
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&apos;")
            else -> if (c < ' ' && c != '\t' && c != '\n' && c != '\r') sb.append("&#").append(c.code).append(';') else sb.append(c)
        }
    }

    private val factory: DocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
    }

    /** Decodifica un `<methodResponse>`. Lanza [XmlRpcFault] si es un `<fault>`. */
    fun decodeResponse(xml: ByteArray): Any? {
        if (xml.isEmpty()) throw EngineException("Respuesta XML-RPC vacía")
        val doc = try {
            synchronized(factory) { factory.newDocumentBuilder() }.parse(ByteArrayInputStream(xml))
        } catch (e: Exception) {
            throw EngineException("Respuesta XML-RPC inválida: ${String(xml, Charsets.UTF_8).take(200)}", e)
        }
        val root = doc.documentElement
        if (root.tagName != "methodResponse") throw EngineException("Se esperaba <methodResponse>, llegó <${root.tagName}>")
        val first = root.childElements().firstOrNull() ?: return null
        return when (first.tagName) {
            "params" -> {
                val param = first.childElements().firstOrNull() ?: return null
                val value = param.childElements().firstOrNull() ?: return null
                decodeValue(value)
            }
            "fault" -> {
                @Suppress("UNCHECKED_CAST")
                val st = decodeValue(first.childElements().first()) as? Map<String, Any?> ?: emptyMap()
                throw XmlRpcFault((st["faultCode"] as? Long) ?: -1, st["faultString"]?.toString() ?: "")
            }
            else -> throw EngineException("Elemento inesperado <${first.tagName}>")
        }
    }

    private fun decodeValue(value: Element): Any? {
        val typed = value.childElements().firstOrNull() ?: return value.textContent // <value>texto</value> = string
        val text = typed.textContent
        return when (typed.tagName) {
            "string" -> text
            "i4", "i8", "int" -> text.trim().toLong()
            "boolean" -> text.trim() == "1" || text.trim().equals("true", ignoreCase = true)
            "double" -> text.trim().toDouble()
            "base64" -> Base64.getMimeDecoder().decode(text.trim())
            "nil" -> null
            "dateTime.iso8601" -> text
            "array" -> {
                val data = typed.childElements().firstOrNull { it.tagName == "data" } ?: return emptyList<Any?>()
                data.childElements().filter { it.tagName == "value" }.map { decodeValue(it) }
            }
            "struct" -> {
                val m = LinkedHashMap<String, Any?>()
                for (member in typed.childElements()) {
                    if (member.tagName != "member") continue
                    var name: String? = null
                    var v: Any? = null
                    for (c in member.childElements()) {
                        if (c.tagName == "name") name = c.textContent else if (c.tagName == "value") v = decodeValue(c)
                    }
                    if (name != null) m[name] = v
                }
                m
            }
            else -> text
        }
    }

    private fun Node.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val nl = childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element) out.add(n)
        }
        return out
    }
}
