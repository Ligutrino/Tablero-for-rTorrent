package io.github.ligutrino.rtorrent.engine.rpc

import io.github.ligutrino.rtorrent.engine.api.EngineNotRunningException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Conexión de un solo uso a un servidor SCGI (una petición, una respuesta). */
interface ScgiConnection : AutoCloseable {
    val input: InputStream
    val output: OutputStream
}

/** Abre conexiones al socket de rtorrent. En Android: [io.github.ligutrino.rtorrent.engine.android.LocalSocketConnector]. */
fun interface ScgiConnector {
    @Throws(IOException::class)
    fun connect(): ScgiConnection
}

/** Cliente XML-RPC sobre SCGI, el protocolo de `network.scgi.open_local`. */
class XmlRpcClient(private val connector: ScgiConnector) {

    /** Llama a [method]. Lanza [XmlRpcFault] si rtorrent devuelve un error, [EngineNotRunningException] si no hay conexión. */
    fun call(method: String, vararg params: Any?): Any? = callList(method, params.toList())

    fun callList(method: String, params: List<Any?>): Any? {
        val body = XmlRpc.encodeCall(method, params)
        val response = try {
            post(body)
        } catch (e: IOException) {
            throw EngineNotRunningException("No se pudo hablar con rtorrent ($method): ${e.message}", e)
        }
        return XmlRpc.decodeResponse(response)
    }

    /** Como [call], pero tolera que el servidor cierre sin responder (p. ej. `system.shutdown.normal`). */
    fun callIgnoringEmpty(method: String, vararg params: Any?) {
        val body = XmlRpc.encodeCall(method, params.toList())
        val response = try { post(body) } catch (e: IOException) { return }
        if (response.isNotEmpty()) XmlRpc.decodeResponse(response)
    }

    private fun post(body: ByteArray): ByteArray {
        connector.connect().use { conn ->
            val headers = ByteArrayOutputStream()
            fun h(k: String, v: String) {
                headers.write(k.toByteArray()); headers.write(0)
                headers.write(v.toByteArray()); headers.write(0)
            }
            h("CONTENT_LENGTH", body.size.toString()) // debe ir primero según la especificación SCGI
            h("SCGI", "1")
            h("REQUEST_METHOD", "POST")
            h("CONTENT_TYPE", "text/xml")
            val hb = headers.toByteArray()
            val out = conn.output
            out.write("${hb.size}:".toByteArray())
            out.write(hb)
            out.write(','.code)
            out.write(body)
            out.flush()

            val raw = conn.input.readBytes()
            return stripHttpHeaders(raw)
        }
    }

    /** rtorrent responde "Status: 200 OK\r\nContent-Type: text/xml\r\n...\r\n\r\n<xml>". */
    private fun stripHttpHeaders(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) return raw
        for (i in 0 until raw.size - 3) {
            if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()
            ) return raw.copyOfRange(i + 4, raw.size)
        }
        // Sin cabeceras: asumir que es todo cuerpo.
        return raw
    }
}
