import cl.tablero.backend.DirWebRoot
import cl.tablero.backend.Options
import cl.tablero.backend.Tablero
import io.github.ligutrino.rtorrent.engine.rpc.ScgiConnection
import io.github.ligutrino.rtorrent.engine.rpc.ScgiConnector
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import kotlin.system.exitProcess

/**
 * Tablero (backend Kotlin) en la JVM, con los argumentos de tablero.py:
 *   tablero --android --scgi RUTA|host:puerto --config DIR --archivos DIR --port N [--bind IP] [--rtorrent-rc RC] [--web DIR]
 */
fun connector(address: String): ScgiConnector =
    if (':' in address && !address.startsWith("/")) ScgiConnector {
        val s = Socket()
        s.connect(InetSocketAddress(address.substringBeforeLast(':'), address.substringAfterLast(':').toInt()), 15000)
        s.soTimeout = 15000
        object : ScgiConnection {
            override val input = s.getInputStream()
            override val output = s.getOutputStream()
            override fun close() = s.close()
        }
    } else ScgiConnector {
        val ch = SocketChannel.open(UnixDomainSocketAddress.of(File(address.replaceFirst(Regex("^~"), System.getProperty("user.home"))).toPath()))
        object : ScgiConnection {
            override val input = Channels.newInputStream(ch)
            override val output = Channels.newOutputStream(ch)
            override fun close() = ch.close()
        }
    }

fun main(args: Array<String>) {
    val a = HashMap<String, String>()
    var android = false
    var i = 0
    while (i < args.size) {
        when (val k = args[i]) {
            "--android" -> android = true
            "--version" -> { println("Tablero ${cl.tablero.backend.Tab.VERSION} (Kotlin)"); return }
            "--scgi", "--config", "--archivos", "--port", "--bind", "--rtorrent-rc", "--web" -> {
                if (i + 1 >= args.size) { System.err.println("falta el valor de $k"); exitProcess(2) }
                a[k] = args[++i]
            }
            else -> { System.err.println("argumento desconocido: $k"); exitProcess(2) }
        }
        i++
    }
    val scgi = a["--scgi"] ?: "127.0.0.1:5000"
    val config = a["--config"] ?: (System.getProperty("user.home") + "/.config/tablero")
    val web = File(a["--web"] ?: System.getenv("TABLERO_WEB") ?: error("falta --web (la carpeta web/ de Tablero)"))
    Tablero(Options(
        scgi = connector(scgi), scgiName = scgi, web = DirWebRoot(web), configDir = config,
        port = (a["--port"] ?: "8090").toInt(), bind = a["--bind"], android = android,
        userFiles = a["--archivos"], rtorrentRc = a["--rtorrent-rc"],
    )).run()
}
