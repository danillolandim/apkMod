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
    private lateinit var gridView: android.widget.GridView
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
        gridView = findViewById(R.id.channels_grid)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        store = PlaylistStore(this)

        groupsView.setOnItemClickListener { _, _, pos, _ -> selectGroup(groups[pos]) }
        channelsView.setOnItemClickListener { _, _, pos, _ -> play(shown[pos]) }
        channelsView.setOnItemLongClickListener { _, _, pos, _ -> toggleFav(shown[pos]); true }
        gridView.setOnItemClickListener { _, _, pos, _ -> openDetail(shown[pos]) }
        gridView.setOnItemLongClickListener { _, _, pos, _ -> toggleFav(shown[pos]); true }
        search.setOnEditorActionListener { _, _, _ -> applyFilter(); true }

        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        val force = intent.getBooleanExtra(EXTRA_FORCE_REFRESH, false)

        status.text = "Carregando…"

        if (fromSettings) {
            // 1) Tem cache? Mostra IMEDIATAMENTE (parse em 2º plano).
            val hasCache = store.readCache() != null
            if (hasCache && !force) {
                thread(name = "playlist-cache") {
                    val cached = store.readCache()
                    val res = cached?.let { M3uParser.parse(it) }
                    if (res is M3uParser.Result.Playlist) {
                        runOnUiThread { show(res.channels); status.text = "${res.channels.size} canais (cache)" }
                        // 2) Se o cache está velho, atualiza em silêncio por trás.
                        if (!store.cacheFresh()) resolveAndLoad(silent = true)
                    } else {
                        runOnUiThread { resolveAndLoad() }
                    }
                }
            } else {
                resolveAndLoad()
            }
        } else {
            val url = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
            if (url.isBlank()) { finish(); return }
            load(url, cacheIt = false)
        }
    }

    /** Resolve a URL da lista a partir das configurações (config URL -> lista). */
    private fun resolveAndLoad(silent: Boolean = false) {
        thread(name = "playlist-resolve") {
            val configUrl = store.configUrl
            val resolved = if (!configUrl.isNullOrBlank()) {
                runCatching { fetchText(configUrl).lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("http", true) } }
                    .getOrNull() ?: store.playlistUrl
            } else store.playlistUrl
            if (resolved.isNullOrBlank()) {
                if (!silent) runOnUiThread { status.text = "Configure a URL da lista nas Configurações." }
                return@thread
            }
            load(resolved, cacheIt = true, silent = silent)
        }
    }

    /** Abre a conexão seguindo redirecionamentos manualmente (inclusive http<->https). */
    private fun connect(urlStr: String, maxRedirects: Int = 6): HttpURLConnection {
        var current = urlStr
        var hops = 0
        while (true) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 30_000
                instanceFollowRedirects = false   // seguimos à mão (atravessa http/https)
                setRequestProperty("User-Agent", store.userAgent?.ifBlank { null } ?: UA)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Accept", "*/*")
            }
            val code = c.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank()) throw java.io.IOException("HTTP $code sem destino")
                current = URL(URL(current), loc).toString()   // resolve relativo/absoluto
                if (++hops > maxRedirects) throw java.io.IOException("Muitos redirecionamentos")
                continue
            }
            if (code !in 200..299) { c.disconnect(); throw java.io.IOException("HTTP $code") }
            return c
        }
    }

    private fun readBody(c: HttpURLConnection): String {
        val raw = if (c.contentEncoding?.contains("gzip", true) == true)
            GZIPInputStream(c.inputStream) else c.inputStream
        return BufferedReader(InputStreamReader(raw, Charsets.UTF_8)).use { r ->
            val sb = StringBuilder(); val buf = CharArray(8192); var n: Int; var total = 0
            while (r.read(buf).also { n = it } > 0) {
                sb.appendRange(buf, 0, n); total += n
                if (total > MAX_CHARS) break
            }
            c.disconnect(); sb.toString()
        }
    }

    private fun fetchText(url: String): String = readBody(connect(url))

    private fun load(url: String, cacheIt: Boolean, silent: Boolean = false) {
        thread(name = "playlist-load") {
            try {
                val lower = url.substringBefore('?').lowercase()
                if (DIRECT_EXT.any { lower.endsWith(it) }) {
                    runOnUiThread { openPlayer(url, live = false, title = null); finish() }
                    return@thread
                }
                val conn = connect(url)
                val finalUrl = conn.url.toString()
                val type = conn.contentType.orEmpty().lowercase()
                val looksList = lower.contains("m3u") || type.contains("mpegurl") || type.contains("text/plain")
                if ((type.startsWith("video/") || type.startsWith("audio/")) && !looksList) {
                    conn.disconnect()
                    runOnUiThread { openPlayer(finalUrl, live = false, title = null); finish() }
                    return@thread
                }
                val text = readBody(conn)

                when (val res = M3uParser.parse(text)) {
                    is M3uParser.Result.HlsStream ->
                        if (!silent) runOnUiThread { openPlayer(finalUrl, live = true, title = null); finish() }
                    is M3uParser.Result.Empty ->
                        if (!silent) runOnUiThread { status.text = "Nada reconhecido nessa URL (não é lista M3U nem stream)." }
                    is M3uParser.Result.Playlist -> {
                        store.lastUrl = url
                        if (cacheIt) { store.writeCache(text); store.channelCount = res.channels.size }
                        runOnUiThread {
                            if (silent) {
                                // Atualização por trás: só troca os dados se mudou, mantém a categoria.
                                if (res.channels.size != all.size) {
                                    show(res.channels)
                                    status.text = "${res.channels.size} canais (atualizado)"
                                }
                            } else show(res.channels)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!silent) runOnUiThread { status.text = "Erro ao carregar: ${e.message}" }
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

    private fun selectGroup(g: String) { currentGroup = g; gridForced = null; applyFilter() }

    @Volatile private var filterGen = 0

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val favs = store.favorites()
        val group = currentGroup
        val gen = ++filterGen

        // Sem filtro ("Todos" + busca vazia): aplica na hora, sem thread.
        if (group == ALL && q.isEmpty()) {
            publish(all, favs); return
        }
        // Filtro de 47k itens fora da UI para não travar.
        thread(name = "filter") {
            val result = all.filter { ch ->
                val inGroup = when (group) {
                    ALL -> true
                    FAV -> favs.contains(ch.url)
                    else -> ch.group == group
                }
                inGroup && (q.isEmpty() || ch.name.lowercase().contains(q))
            }
            if (gen == filterGen) runOnUiThread { if (gen == filterGen) publish(result, favs) }
        }
    }

    private fun publish(list: List<M3uParser.Channel>, favs: Set<String>) {
        shown = list
        findViewById<View>(R.id.empty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        // Grade de pôsteres para categorias de filmes/séries (ou quando forçado);
        // lista para canais ao vivo.
        val useGrid = gridForced ?: isVodCategory(currentGroup, list)
        if (useGrid) {
            channelsView.visibility = View.GONE
            gridView.visibility = View.VISIBLE
            if (gridView.adapter !== posterAdapter) gridView.adapter = posterAdapter
            posterAdapter.submit(list)
            gridView.setSelection(0)
        } else {
            gridView.visibility = View.GONE
            channelsView.visibility = View.VISIBLE
            if (channelsView.adapter !== channelAdapter) channelsView.adapter = channelAdapter
            channelAdapter.submit(list, favs)
            channelsView.setSelectionAfterHeaderView()
        }
    }

    /** null = automático (detecta por categoria); true/false = forçado pelo usuário. */
    private var gridForced: Boolean? = null

    private fun isVodCategory(group: String, sample: List<M3uParser.Channel>): Boolean {
        val g = group.uppercase()
        if (listOf("FILME", "MOVIE", "SERIE", "SÉRIE", "VOD", "CINEMA", "NETFLIX", "PRIME", "DISNEY", "HBO", "MAX ")
                .any { g.contains(it) }) return true
        if (group == ALL || group == FAV) return false
        // Se a maioria dos itens tem logo/pôster, trata como grade.
        val probe = sample.take(40)
        val withLogo = probe.count { !it.logo.isNullOrBlank() }
        return probe.isNotEmpty() && withLogo >= probe.size * 0.6
    }

    private val posterAdapter by lazy { PosterAdapter() }

    // Cache TMDB (nota + pôster) e busca limitada (só itens visíveis, sem repetir).
    private val tmdbCache = java.util.concurrent.ConcurrentHashMap<String, com.seupainel.player.meta.Tmdb.Quick>()
    private val tmdbMiss = java.util.Collections.synchronizedSet(HashSet<String>())
    private val tmdbExec = java.util.concurrent.Executors.newFixedThreadPool(4)
    private val tmdbKey by lazy { com.seupainel.player.AppSettings(this).tmdbApiKey }
    private fun currentIsSeries() = currentGroup.uppercase().let { it.contains("SERIE") || it.contains("SÉRIE") }

    private inner class PosterAdapter : android.widget.BaseAdapter() {
        private var items: List<M3uParser.Channel> = emptyList()
        fun submit(list: List<M3uParser.Channel>) { items = list; notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_poster, parent, false)
            val ch = items[position]
            v.tag = ch.url
            v.findViewById<TextView>(R.id.poster_title).text = ch.name
            val img = v.findViewById<android.widget.ImageView>(R.id.poster)
            val badge = v.findViewById<TextView>(R.id.poster_rating)
            val series = currentIsSeries()

            // Capa da lista como base (fallback). Em séries ela costuma ser um
            // frame do episódio, então será trocada pelo pôster do TMDB abaixo.
            val logo = ch.logo
            if (logo.isNullOrBlank()) img.setImageDrawable(null)
            else loadPoster(img, logo)

            bindTmdb(badge, img, v, ch, series)
            return v
        }

        private fun bindTmdb(badge: TextView, img: android.widget.ImageView, itemView: View, ch: M3uParser.Channel, series: Boolean) {
            val cached = tmdbCache[ch.name]
            if (cached != null) {
                applyTmdb(badge, img, ch, cached, series)
                return
            }
            badge.visibility = View.GONE
            if (tmdbKey.isBlank() || tmdbMiss.contains(ch.name)) return
            tmdbMiss.add(ch.name)
            val key = ch.url
            tmdbExec.execute {
                val q = runCatching { com.seupainel.player.meta.Tmdb.quick(tmdbKey, ch.name) }.getOrNull()
                    ?: com.seupainel.player.meta.Tmdb.Quick(0.0, null)
                tmdbCache[ch.name] = q
                runOnUiThread { if (itemView.tag == key) applyTmdb(badge, img, ch, q, series) }
            }
        }

        private fun applyTmdb(badge: TextView, img: android.widget.ImageView, ch: M3uParser.Channel, q: com.seupainel.player.meta.Tmdb.Quick, series: Boolean) {
            if (q.rating > 0) { badge.text = "★ ${"%.1f".format(q.rating)}"; badge.visibility = View.VISIBLE }
            else badge.visibility = View.GONE
            // Pôster do TMDB: sempre em séries (frame ruim), ou quando a lista não tem capa.
            if (!q.posterUrl.isNullOrBlank() && (series || ch.logo.isNullOrBlank())) {
                loadPoster(img, q.posterUrl)
            }
        }

        private fun loadPoster(img: android.widget.ImageView, url: String) {
            coil.Coil.imageLoader(this@ChannelListActivity).enqueue(
                coil.request.ImageRequest.Builder(this@ChannelListActivity)
                    .data(url).target(img).crossfade(true).build()
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tmdbExec.shutdownNow()
    }

    /** Adapter leve: não recria lista a cada filtro, só troca os dados e recicla as views. */
    private val channelAdapter by lazy { ChannelAdapter() }

    private inner class ChannelAdapter : android.widget.BaseAdapter() {
        private var items: List<M3uParser.Channel> = emptyList()
        private var favs: Set<String> = emptySet()
        fun submit(list: List<M3uParser.Channel>, favorites: Set<String>) {
            items = list; favs = favorites; notifyDataSetChanged()
        }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val tv = (convertView as? TextView) ?: layoutInflater
                .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            val ch = items[position]
            tv.text = if (favs.contains(ch.url)) "★ ${ch.name}" else ch.name
            return tv
        }
    }

    private fun toggleFav(ch: M3uParser.Channel) {
        val added = store.toggleFavorite(ch.url)
        Toast.makeText(this, if (added) "★ Favoritado: ${ch.name}" else "Removido dos favoritos", Toast.LENGTH_SHORT).show()
        applyFilter()
    }

    /** Abre a tela de detalhes (filmes/séries) antes de assistir. */
    private fun openDetail(ch: M3uParser.Channel) {
        val isSeries = currentGroup.uppercase().contains("SERIE") || currentGroup.uppercase().contains("SÉRIE")
        startActivity(Intent(this, com.seupainel.player.DetailActivity::class.java).apply {
            putExtra(com.seupainel.player.DetailActivity.EXTRA_URL, ch.url)
            putExtra(com.seupainel.player.DetailActivity.EXTRA_TITLE, ch.name)
            putExtra(com.seupainel.player.DetailActivity.EXTRA_LOGO, ch.logo ?: "")
            putExtra(com.seupainel.player.DetailActivity.EXTRA_IS_LIVE, ch.isLive)
            putExtra(com.seupainel.player.DetailActivity.EXTRA_IS_SERIES, isSeries)
        })
    }

    private fun play(ch: M3uParser.Channel) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, ch.url)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, ch.isLive)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            putExtra(PlayerActivity.EXTRA_LOGO, ch.logo ?: "")
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
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> {
                val lv = if (gridView.visibility == View.VISIBLE) gridView.selectedItemPosition else channelsView.selectedItemPosition
                if (lv in shown.indices) { toggleFav(shown[lv]); return true }
            }
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                // Alternar lista <-> grade (pôsteres)
                gridForced = !(if (gridForced != null) gridForced!! else gridView.visibility == View.VISIBLE)
                publish(shown, store.favorites())
                Toast.makeText(this, if (gridForced == true) "Grade de pôsteres" else "Lista", Toast.LENGTH_SHORT).show()
                return true
            }
            KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_MENU -> {
                status.text = "Atualizando…"
                if (intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)) resolveAndLoad()
                else load(intent.getStringExtra(EXTRA_URL).orEmpty().trim(), cacheIt = false)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_FROM_SETTINGS = "from_settings"
        const val EXTRA_FORCE_REFRESH = "force_refresh"
        private const val ALL = "Todos"
        private const val FAV = "★ Favoritos"
        private const val MAX_CHARS = 12_000_000
        private const val UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36"
        private val DIRECT_EXT = listOf(".mp4", ".mkv", ".ts", ".avi", ".mov", ".webm", ".mp3", ".aac", ".flac")
    }
}
