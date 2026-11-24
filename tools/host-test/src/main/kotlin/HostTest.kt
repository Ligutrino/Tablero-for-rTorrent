import io.github.ligutrino.rtorrent.engine.api.*
import io.github.ligutrino.rtorrent.engine.rpc.*
import io.github.ligutrino.rtorrent.engine.rtorrent.*
import java.io.File
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

object JvmPlatform : DaemonPlatform {
    override fun connector(socket: File) = ScgiConnector {
        val ch = SocketChannel.open(UnixDomainSocketAddress.of(socket.toPath()))
        object : ScgiConnection {
            override val input = Channels.newInputStream(ch)
            override val output = Channels.newOutputStream(ch)
            override fun close() = ch.close()
        }
    }
    override fun kill(pid: Int, signal: Int) { ProcessHandle.of(pid.toLong()).ifPresent { it.destroyForcibly() } }
    override fun ipv6Available() = File("/proc/net/if_inet6").exists()
}

fun check(c: Boolean, msg: String) { if (!c) error("FALLO: $msg") else println("ok  $msg") }

/** Crea un archivo aleatorio y su .torrent (bencode a mano) para probar la verificación. */
fun makeTorrent(dir: File): ByteArray {
    dir.mkdirs()
    val data = ByteArray(3 * 1024 * 1024 + 12345).also { java.util.Random(42).nextBytes(it) }
    File(dir, "payload.bin").writeBytes(data)
    val pl = 262144
    val pieces = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < data.size) {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update(data, i, minOf(pl, data.size - i)); pieces.write(md.digest()); i += pl
    }
    val out = java.io.ByteArrayOutputStream()
    fun s(b: ByteArray) { out.write("${b.size}:".toByteArray()); out.write(b) }
    fun s(t: String) = s(t.toByteArray())
    out.write('d'.code); s("announce"); s("udp://tracker.opentrackr.org:1337")
    s("info"); out.write('d'.code)
    s("length"); out.write("i${data.size}e".toByteArray())
    s("name"); s("payload.bin")
    s("piece length"); out.write("i${pl}e".toByteArray())
    s("pieces"); s(pieces.toByteArray())
    out.write('e'.code); out.write('e'.code)
    return out.toByteArray()
}

fun main(args: Array<String>) {
    require(args.size == 2) { "uso: host-test <dir-de-trabajo-vacío> <ruta a librtorrent.so de host>" }
    val root = File(args[0])
    require(!root.exists() || root.list().isNullOrEmpty() || File(root, ".host-test").exists()) {
        "$root existe y no es un directorio de host-test: usa uno vacío"
    }
    root.deleteRecursively()
    root.mkdirs()
    File(root, ".host-test").createNewFile()
    val cfg = RtorrentConfig(baseDir = File(root, "base"), downloadDir = File(root, "dl"), caPath = "/etc/ssl/certs")
    val exe = File(args[1])
    val states = mutableListOf<EngineState>()
    val engine = RtorrentEngine(RtorrentDaemon(cfg, exe, JvmPlatform))
    engine.addListener { states += it }
    engine.start()
    check(engine.state is EngineState.Running, "arranca: ${engine.state}")
    println(cfg.rcFile.readText())
    check(engine.version() == "rtorrent 0.16.17", "version ${engine.version()}")

    val mh = engine.addUri("magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337")
    check(mh == "DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C", "hash magnet $mh")
    check(engine.addUri("magnet:?xt=urn:btih:3WBFL3G4PSSV7MF37AJSHWDQMLNR63I4") == "DD8255ECDC7CA55FB0BBF81323D87062DB1F6D1C", "base32 magnet")
    Thread.sleep(500)
    var list = engine.torrents()
    println(list)
    check(list.any { it.hash == mh && it.state == TorrentState.METADATA }, "magnet en estado METADATA")

    // .torrent real con datos ya presentes en disco -> debe verificar al 100 %
    val torrentBytes = makeTorrent(File(root, "seed"))
    val th = engine.addTorrentFile(torrentBytes, AddOptions(directory = File(root, "seed").absolutePath))
    println("hash torrent: $th")
    var t: TorrentStatus? = null
    for (i in 0 until 50) { Thread.sleep(200); t = engine.torrent(th); if (t != null && t.state != TorrentState.CHECKING && t.completedBytes == t.totalBytes) break }
    println(t)
    check(t != null, "torrent cargado con el hash calculado en Kotlin")
    check(t!!.completedBytes == t.totalBytes && t.totalBytes > 0, "datos verificados al 100 %")
    check(t.state == TorrentState.SEEDING, "estado SEEDING: ${t.state}")
    check(t.path.endsWith("seed/payload.bin"), "ruta ${t.path}")

    engine.pause(th); Thread.sleep(300)
    check(engine.torrent(th)!!.state == TorrentState.FINISHED, "pausa -> ${engine.torrent(th)!!.state}")
    engine.resume(th); Thread.sleep(300)
    check(engine.torrent(th)!!.state == TorrentState.SEEDING, "reanudar -> ${engine.torrent(th)!!.state}")

    engine.setRateLimits(1_000_000, 200_000)
    val s = engine.sessionStats()
    println(s)
    check(s.downloadLimit == 1_000_000L / 1024 * 1024 && s.uploadLimit == 200_000L / 1024 * 1024 && s.torrentCount == 2 && s.listenPort in 50000..50010, "stats y límites")

    // borrar con datos fuera del dir gestionado debe negarse
    val refused = runCatching { engine.remove(th, deleteFiles = true) }
    check(refused.isFailure && File(root, "seed/payload.bin").exists() && engine.torrent(th) != null, "no borra ni quita si está fuera de downloadDir: ${refused.exceptionOrNull()?.message}")

    // ahora uno dentro del dir de descargas
    engine.remove(th, deleteFiles = false)
    check(engine.torrent(th) == null && File(root, "seed/payload.bin").exists(), "quita conservando archivos")
    File(root, "seed/payload.bin").copyTo(File(cfg.downloadDir, "payload.bin"), overwrite = true)
    val th2 = engine.addTorrentFile(torrentBytes, AddOptions(start = false))
    check(th2 == th, "mismo hash")
    Thread.sleep(500)
    engine.remove(th, deleteFiles = true)
    check(!File(cfg.downloadDir, "payload.bin").exists() && engine.torrent(th) == null, "quita y borra dentro de downloadDir")

    // adopción: un segundo engine ve el daemon vivo
    val engine2 = RtorrentEngine(RtorrentDaemon(cfg, exe, JvmPlatform))
    engine2.start()
    check(engine2.state is EngineState.Running, "adopta daemon existente: ${engine2.state}")

    val rpcFault = runCatching { engine.rawCall("no.existe", "") }
    check(rpcFault.exceptionOrNull() is XmlRpcFault, "fault XML-RPC -> ${rpcFault.exceptionOrNull()}")

    engine.stop()
    check(engine.state == EngineState.Stopped && !cfg.socketFile.exists(), "para ordenadamente")
    check(RtorrentDaemon(cfg, exe, JvmPlatform).ping() == null, "el daemon ya no responde")

    // reinicio: la sesión conserva el magnet
    engine.start()
    Thread.sleep(500)
    list = engine.torrents()
    check(list.any { it.hash == mh }, "sesión restaurada tras reinicio (${list.size} torrents)")
    engine.stop()
    println("estados: $states")
    println(engine.logTail(2000))
    println("TODO OK")
}

