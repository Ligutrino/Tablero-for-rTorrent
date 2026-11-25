package cl.tablero.backend

import io.github.ligutrino.rtorrent.engine.api.EngineException
import io.github.ligutrino.rtorrent.engine.rpc.ScgiConnector
import io.github.ligutrino.rtorrent.engine.rpc.XmlRpc
import io.github.ligutrino.rtorrent.engine.rpc.XmlRpcFault
import java.io.ByteArrayOutputStream
import java.io.IOException

typealias Call = Pair<String, List<Any?>>

fun call(method: String, vararg params: Any?): Call = method to params.toList()

/**
 * La clase RTorrent de tablero.py: XML-RPC sobre SCGI, una conexión por llamada. Usa el
 * codificador de :engine. Los enteros llegan como Long.
 */
class Rt(private val connector: ScgiConnector) {
    @Volatile private var portRange: String? = null

    /** rTorrent 0.16 cambió network.port_range por network.listen.port.range. */
    fun portRangeMethod(): String {
        portRange?.let { return it }
        val m = try {
            call("network.listen.port.range"); "network.listen.port.range"
        } catch (e: XmlRpcFault) { "network.port_range" }
        portRange = m
        return m
    }

    fun call(method: String, vararg params: Any?): Any? = callList(method, params.toList())

    fun callList(method: String, params: List<Any?>): Any? {
        val body = XmlRpc.encodeCall(method, params)
        if (body.size > 500_000) fitSizeLimit(body.size)
        return send(body)
    }

    /** rTorrent rechaza pedidos sobre network.xmlrpc.size_limit (512 KB): se sube, nunca se baja. */
    private fun fitSizeLimit(n: Int) {
        val limit = try {
            (send(XmlRpc.encodeCall("network.xmlrpc.size_limit", emptyList())) as? Long) ?: 0L
        } catch (e: Exception) { 0L }
        if (n + 65536 > limit) {
            val want = ((n + 65536 + (1 shl 20) - 1).toLong() shr 20) shl 20
            send(XmlRpc.encodeCall("network.xmlrpc.size_limit.set", listOf("", want)))
        }
    }

    private fun send(body: ByteArray): Any? {
        val raw = try {
            connector.connect().use { conn ->
                val headers = "CONTENT_LENGTH\u0000${body.size}\u0000SCGI\u00001\u0000".toByteArray()
                val out = conn.output
                out.write("${headers.size}:".toByteArray()); out.write(headers); out.write(','.code); out.write(body)
                out.flush()
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(65536)
                while (true) {
                    val n = conn.input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                }
                buf.toByteArray()
            }
        } catch (e: IOException) {
            throw RtConnectionError(e.message ?: e.toString(), e)
        }
        // rTorrent 0.16 cierra sin responder a system.shutdown.normal.
        if (raw.isEmpty()) throw RtConnectionError("rTorrent cerró la conexión sin responder")
        var sep = -1
        for (i in 0 until raw.size - 3) {
            if (raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() && raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()) { sep = i; break }
        }
        val payload = if (sep >= 0) raw.copyOfRange(sep + 4, raw.size) else raw
        return try {
            XmlRpc.decodeResponse(payload)
        } catch (e: XmlRpcFault) {
            throw e
        } catch (e: EngineException) {
            throw RtConnectionError(e.message ?: "respuesta inválida de rTorrent", e)
        }
    }

    /** Una lista de llamadas en un system.multicall; una que falla deja un [CallFailed] en su lugar. */
    fun multicall(calls: List<Call>): List<Any?> {
        val res = call("system.multicall", calls.map { (m, p) -> linkedMapOf("methodName" to m, "params" to p) })
        return (res as? List<*> ?: emptyList<Any?>()).map { r ->
            when (r) {
                is Map<*, *> -> CallFailed(r["faultString"]?.toString() ?: "error")
                is List<*> -> r.firstOrNull()
                else -> r
            }
        }
    }
}

/** Valor de un multicall, o null si esa llamada falló ("None if isinstance(x, Exception)"). */
fun ok(v: Any?): Any? = if (v is Throwable) null else v

fun rows(v: Any?): List<List<Any?>> = (v as? List<*>)?.map { (it as? List<*>)?.toList() ?: emptyList() } ?: emptyList()
