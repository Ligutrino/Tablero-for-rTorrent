package io.github.ligutrino.rtorrent.demo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.ligutrino.rtorrent.engine.android.EngineProvider
import io.github.ligutrino.rtorrent.engine.api.EngineListener
import io.github.ligutrino.rtorrent.engine.api.EngineState
import io.github.ligutrino.rtorrent.engine.api.TorrentEngine
import io.github.ligutrino.rtorrent.engine.api.TorrentStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App mínima para comprobar el motor. Solo usa la API neutral [TorrentEngine] y [EngineProvider]:
 * la UI definitiva (fork de LibreTorrent o app nueva) puede reemplazar esto por completo.
 */
class MainActivity : Activity(), CoroutineScope by MainScope() {

    private companion object {
        const val REQ_OPEN_TORRENT = 1
        const val REQ_NOTIFICATIONS = 2
        /** Big Buck Bunny (Blender Foundation, CC-BY): contenido legal y con semillas. */
        const val TEST_MAGNET = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny" +
            "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337&tr=udp%3A%2F%2Fexplodie.org%3A6969" +
            "&tr=udp%3A%2F%2Ftracker.leechers-paradise.org%3A6969&tr=udp%3A%2F%2Ftracker.coppersurfer.tk%3A6969"
    }

    private lateinit var engine: TorrentEngine
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var magnet: EditText
    private lateinit var adapter: ArrayAdapter<String>
    private var torrents: List<TorrentStatus> = emptyList()
    private var poller: Job? = null

    private val engineListener = EngineListener { st -> runOnUiThread { showState(st) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        engine = EngineProvider.get(this)

        status = requireViewById(R.id.status)
        stats = requireViewById(R.id.stats)
        magnet = requireViewById(R.id.magnet)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        val list = requireViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> torrents.getOrNull(pos)?.let(::showActions) }

        requireViewById<Button>(R.id.btnStart).setOnClickListener {
            askNotificationPermission()
            EngineProvider.startService(this)
        }
        requireViewById<Button>(R.id.btnStop).setOnClickListener { EngineProvider.stopService(this) }
        requireViewById<Button>(R.id.btnLog).setOnClickListener { showLog() }
        requireViewById<Button>(R.id.btnAdd).setOnClickListener { addUri(magnet.text.toString()) }
        requireViewById<Button>(R.id.btnTest).setOnClickListener { addUri(TEST_MAGNET) }
        requireViewById<Button>(R.id.btnOpen).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQ_OPEN_TORRENT,
            )
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        engine.addListener(engineListener)
        showState(engine.state)
        poller = launch {
            while (isActive) {
                refresh()
                delay(2000)
            }
        }
    }

    override fun onStop() {
        poller?.cancel()
        engine.removeListener(engineListener)
        super.onStop()
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        when (data.scheme) {
            "magnet" -> magnet.setText(data.toString())
            "content", "file" -> addTorrentUri(data)
        }
    }

    private fun showState(st: EngineState) {
        status.text = when (st) {
            is EngineState.Running -> "Motor: ${st.version} (pid ${st.pid})"
            is EngineState.Failed -> "Motor: ERROR — ${st.message}"
            else -> "Motor: $st"
        }
    }

    private suspend fun refresh() {
        if (!engine.state.isRunning) {
            torrents = emptyList()
            adapter.clear()
            stats.text = ""
            return
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { engine.sessionStats() to engine.torrents() }
        }
        result.onSuccess { (s, list) ->
            stats.text = "↓ ${fmtRate(s.downloadRate)}  ↑ ${fmtRate(s.uploadRate)}  ·  puerto ${s.listenPort}  ·  " +
                "límites ${if (s.downloadLimit == 0L) "∞" else fmtRate(s.downloadLimit)}/${if (s.uploadLimit == 0L) "∞" else fmtRate(s.uploadLimit)}"
            torrents = list.sortedByDescending { it.addedEpochSeconds }
            adapter.clear()
            adapter.addAll(torrents.map(::describe))
        }.onFailure { stats.text = "Error al consultar: ${it.message}" }
    }

    private fun describe(t: TorrentStatus): String =
        "${t.name}\n${t.state} · ${"%.1f".format(t.progress * 100)} % de ${fmtSize(t.totalBytes)} · " +
            "↓ ${fmtRate(t.downloadRate)} ↑ ${fmtRate(t.uploadRate)} · ${t.peersConnected} pares" +
            (if (t.message.isNotBlank()) "\n${t.message}" else "")

    private fun addUri(text: String) {
        val uri = text.trim()
        if (uri.isEmpty()) return toast("Pega un enlace magnet o una URL .torrent")
        background("Añadido") {
            engine.addUri(uri)
            withContext(Dispatchers.Main) { magnet.setText("") }
        }
    }

    private fun addTorrentUri(uri: Uri) = background("Torrent añadido") {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo leer $uri")
        engine.addTorrentFile(bytes)
    }

    private fun showActions(t: TorrentStatus) {
        val actions = arrayOf("Pausar", "Reanudar", "Verificar datos", "Quitar (conservar archivos)", "Quitar y borrar archivos")
        AlertDialog.Builder(this)
            .setTitle(t.name)
            .setMessage(null)
            .setItems(actions) { _, which ->
                background(actions[which]) {
                    when (which) {
                        0 -> engine.pause(t.hash)
                        1 -> engine.resume(t.hash)
                        2 -> engine.recheck(t.hash)
                        3 -> engine.remove(t.hash, deleteFiles = false)
                        4 -> engine.remove(t.hash, deleteFiles = true)
                    }
                }
            }
            .show()
    }

    private fun showLog() = launch {
        val text = withContext(Dispatchers.IO) { runCatching { engine.logTail(32 * 1024) }.getOrElse { it.toString() } }
        val tv = TextView(this@MainActivity).apply {
            setText(text.ifBlank { "(log vacío)" })
            setTextIsSelectable(true)
            textSize = 11f
            setPadding(24, 24, 24, 24)
            movementMethod = ScrollingMovementMethod()
        }
        AlertDialog.Builder(this@MainActivity).setTitle("Log de rtorrent").setView(tv).setPositiveButton("Cerrar", null).show()
    }

    private fun background(okMessage: String, block: suspend () -> Unit) = launch {
        val r = withContext(Dispatchers.IO) { runCatching { block() } }
        r.onSuccess { toast(okMessage); refresh() }.onFailure { toast("Error: ${it.message}") }
    }

    @Deprecated("API de Activity sin AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN_TORRENT && resultCode == RESULT_OK) data?.data?.let(::addTorrentUri)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun fmtRate(bps: Long) = fmtSize(bps) + "/s"

    private fun fmtSize(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GiB".format(b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MiB".format(b / 1048576.0)
        b >= 1L shl 10 -> "%.0f KiB".format(b / 1024.0)
        else -> "$b B"
    }
}
