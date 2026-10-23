package com.seupainel.player.playlist

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.PlayerActivity
import com.seupainel.player.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Carrega uma URL e decide o que ela é:
 *  - arquivo de vídeo direto / stream HLS  -> abre o player;
 *  - lista M3U de canais                   -> mostra categorias + canais.
 *
 * Layout para TV: categorias à esquerda, canais à direita, busca em cima.
 * OK toca; botão vermelho (ou tecla longa) marca/desmarca favorito.
 */
class ChannelListActivity : FragmentActivity() {

    private lateinit var groupsView: ListView
    private lateinit var channelsView: ListView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var store: PlaylistStore

    private var all: List<M3uParser.Channel> = emptyList()
    private var groups: List<String> = emptyList()
    private var currentGroup: String = ALL
    private var shown: List<M3uParser.Channel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channels)
        groupsView = findViewById(R.id.groups)
        channelsView = findViewById(R.id.channels)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        store = PlaylistStore(this)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        if (url.isBlank()) { finish(); return }

        groupsView.setOnItemClickListener { _, _, pos, _ -> selectGroup(groups[pos]) }
        channelsView.setOnItemClickListener { _, _, pos, _ -> play(shown[pos]) }
        channelsView.setOnItemLongClickListener { _, _, pos, _ -> toggleFav(shown[pos]); true }
        search.setOnEditorActionListener { _, _, _ -> applyFilter(); channelsView.requestFocus(); true }

        status.text = "Carregando…"
        load(url)
    }

    private fun load(url: String) {
        thread(name = "playlist-load") {
            try {
                val lower = url.substringBefore('?').lowercase()
                if (DIRECT_EXT.any { lower.endsWith(it) }) {
                    runOnUiThread { openPlayer(url, live = false, title = null); finish() }
                    return@thread
                }
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept-Encoding", "gzip")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    conn.disconnect()
                    runOnUiThread { status.text = "Servidor respondeu HTTP $code" }
                    return@thread
                }
                val type = conn.contentType.orEmpty().lowercase()
                val looksList = lower.contains("m3u") || type.contains("mpegurl") || type.contains("text/plain")
                if ((type.startsWith("video/") || type.startsWith("audio/")) && !looksList) {
                    conn.disconnect()
                    runOnUiThread { openPlayer(url, live = false, title = null); finish() }
                    return@thread
                }
                val raw = if (conn.contentEncoding?.contains("gzip", true) == true)
                    GZIPInputStream(conn.inputStream) else conn.inputStream
                val text = BufferedReader(InputStreamReader(raw, Charsets.UTF_8)).use { r ->
                    val sb = StringBuilder(); val buf = CharArray(8192); var n: Int; var total = 0
                    while (r.read(buf).also { n = it } > 0) {
                        sb.appendRange(buf, 0, n); total += n
                        if (total > MAX_CHARS) break
                    }
                    sb.toString()
                }
                conn.disconnect()

                when (val res = M3uParser.parse(text)) {
                    is M3uParser.Result.HlsStream ->
                        runOnUiThread { openPlayer(url, live = true, title = null); finish() }
                    is M3uParser.Result.Empty -> runOnUiThread {
                        status.text = "Nada reconhecido nessa URL (não é lista M3U nem stream)."
                    }
                    is M3uParser.Result.Playlist -> runOnUiThread {
                        store.lastUrl = url
                        show(res.channels)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Erro ao carregar: ${e.message}" }
            }
        }
    }

    private fun show(channels: List<M3uParser.Channel>) {
        all = channels
        val realGroups = channels.map { it.group }.distinct().sorted()
        groups = buildList {
            add(ALL)
            if (store.favorites().isNotEmpty()) add(FAV)
            addAll(realGroups)
        }
        groupsView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, groups)
        status.text = "${channels.size} canais • ${realGroups.size} categorias"
        selectGroup(ALL)
        groupsView.requestFocus()
    }

    private fun selectGroup(g: String) { currentGroup = g; applyFilter() }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val favs = store.favorites()
        shown = all.filter { ch ->
            val inGroup = when (currentGroup) {
                ALL -> true
                FAV -> favs.contains(ch.url)
                else -> ch.group == currentGroup
            }
            inGroup && (q.isEmpty() || ch.name.lowercase().contains(q))
        }
        channelsView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            shown.map { (if (store.isFavorite(it.url)) "★ " else "") + it.name },
        )
        findViewById<View>(R.id.empty).visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toggleFav(ch: M3uParser.Channel) {
        val added = store.toggleFavorite(ch.url)
        Toast.makeText(this, if (added) "★ Favoritado: ${ch.name}" else "Removido dos favoritos", Toast.LENGTH_SHORT).show()
        applyFilter()
    }

    private fun play(ch: M3uParser.Channel) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, ch.url)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, ch.isLive)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            ch.userAgent?.let { putExtra(PlayerActivity.EXTRA_USER_AGENT, it) }
            ch.referer?.let { putExtra(PlayerActivity.EXTRA_REFERER, it) }
        })
    }

    private fun openPlayer(url: String, live: Boolean, title: String?) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, url)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, live)
            title?.let { putExtra(PlayerActivity.EXTRA_TITLE, it) }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_PROG_RED) {
            val pos = channelsView.selectedItemPosition
            if (pos in shown.indices) { toggleFav(shown[pos]); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val ALL = "Todos"
        private const val FAV = "★ Favoritos"
        private const val MAX_CHARS = 12_000_000
        private const val UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36"
        private val DIRECT_EXT = listOf(".mp4", ".mkv", ".ts", ".avi", ".mov", ".webm", ".mp3", ".aac", ".flac")
    }
}
