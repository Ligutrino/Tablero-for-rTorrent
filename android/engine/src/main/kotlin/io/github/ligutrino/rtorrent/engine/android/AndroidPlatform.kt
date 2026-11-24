package io.github.ligutrino.rtorrent.engine.android

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import io.github.ligutrino.rtorrent.engine.rpc.ScgiConnection
import io.github.ligutrino.rtorrent.engine.rpc.ScgiConnector
import io.github.ligutrino.rtorrent.engine.rtorrent.DaemonPlatform
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Conecta al socket Unix de rtorrent (espacio de nombres FILESYSTEM). */
class LocalSocketConnector(private val socket: File, private val timeoutMs: Int = 30_000) : ScgiConnector {
    override fun connect(): ScgiConnection {
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(socket.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            s.soTimeout = timeoutMs
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        return object : ScgiConnection {
            override val input: InputStream = s.inputStream
            override val output: OutputStream = s.outputStream
            override fun close() = s.close()
        }
    }
}

object AndroidPlatform : DaemonPlatform {
    override fun connector(socket: File): ScgiConnector = LocalSocketConnector(socket)

    override fun kill(pid: Int, signal: Int) {
        runCatching { Os.kill(pid, signal) }
    }

    override fun ipv6Available(): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress(Inet6Address.getByName("::"), 0)) }
        true
    } catch (e: Exception) {
        false
    }
}
