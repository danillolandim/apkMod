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

    private enum class Sec { CANAIS, FILMES, SERIES, FAV }

    private var all: List<M3uParser.Channel> = emptyList()
    private var groups: List<String> = emptyList()          // subcategorias da seção atual
    private var sectionGroups: Map<Sec, List<String>> = emptyMap()
    private var currentSection: Sec = Sec.CANAIS
    private var currentSub: String? = null                  // null = tudo da seção
    private var shown: List<M3uParser.Channel> = emptyList()

    private fun secOf(group: String): Sec {
        val u = group.uppercase()
        return when {
            u.contains("SERIE") || u.contains("SÉRIE") -> Sec.SERIES
            u.contains("FILME") || u.contains("MOVIE") || u.contains("VOD") || u.contains("CINEMA") -> Sec.FILMES
            else -> Sec.CANAIS
        }
    }
    /**
     * Classifica um item considerando o nome, não só o group-title.
     * Muitas listas jogam episódios (S01E02, 1x02…) dentro de grupos de
     * "filmes"; aqui eles voltam para Séries. Canais ao vivo não são tocados.
     */
    private fun secOfChannel(ch: M3uParser.Channel): Sec {
        val byGroup = secOf(ch.group)
        if (byGroup != Sec.CANAIS && SeriesGrouper.parse(ch.name) != null) return Sec.SERIES
        return byGroup
    }

    private fun currentIsSeries() = currentSection == Sec.SERIES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channels)
        groupsView = findViewById(R.id.groups)
        channelsView = findViewById(R.id.channels)
        gridView = findViewById(R.id.channels_grid)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        store = PlaylistStore(this)

        groupsView.setOnItemClickListener { _, _, pos, _ -> selectSub(groups.getOrNull(pos)) }
        channelsView.setOnItemClickListener { _, _, pos, _ -> onChannelClick(shown[pos]) }
        channelsView.setOnItemLongClickListener { _, _, pos, _ -> toggleFav(shown[pos]); true }
        gridView.setOnItemClickListener { _, _, pos, _ ->
            if (seriesMode) shownSeries.getOrNull(pos)?.let { openSeries(it) }
            else shown.getOrNull(pos)?.let { openDetail(it) }
        }
        gridView.setOnItemLongClickListener { _, _, pos, _ ->
            if (!seriesMode) shown.getOrNull(pos)?.let { toggleFav(it) }; true
        }
        search.setOnEditorActionListener { _, _, _ -> applyFilter(); groupsView.requestFocus(); true }

        // Barra de navegação por seções.
        findViewById<View>(R.id.nav_canais).setOnClickListener { selectSection(Sec.CANAIS) }
        findViewById<View>(R.id.nav_filmes).setOnClickListener { selectSection(Sec.FILMES) }
        findViewById<View>(R.id.nav_series).setOnClickListener { selectSection(Sec.SERIES) }
        findViewById<View>(R.id.nav_fav).setOnClickListener { selectSection(Sec.FAV) }
        findViewById<View>(R.id.nav_search).setOnClickListener { openSearch() }

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
        val realGroups = channels.map { it.group }.distinct()
        sectionGroups = realGroups.groupBy { secOf(it) }
        status.text = "${channels.size} itens"
        selectSection(Sec.CANAIS)
        groupsView.requestFocus()
    }

    private enum class SubMode { GROUPS, ALPHA }
    private var subMode = SubMode.GROUPS

    private fun firstKey(name: String): String {
        val c = name.trimStart().firstOrNull { it.isLetterOrDigit() } ?: return "#"
        val u = c.uppercaseChar()
        return if (u in 'A'..'Z') u.toString() else "#"
    }

    private fun selectSection(sec: Sec) {
        currentSection = sec
        search.setText("")
        search.visibility = View.GONE
        val subs = (sectionGroups[sec] ?: emptyList()).sortedBy { it.lowercase() }

        if (sec == Sec.FAV) {
            subMode = SubMode.GROUPS
            groups = emptyList()
        } else if (sec == Sec.FILMES && subs.size <= 1) {
            // Lista sem subcategorias reais -> gênero/lançamento via TMDB.
            subMode = SubMode.ALPHA
            groups = filmesGroups()
            ensureFilmesIndexed()
        } else if (sec == Sec.SERIES && subs.size <= 1) {
            // Sem subcategorias reais na lista -> índice A-Z + Recentes.
            subMode = SubMode.ALPHA
            val present = all.asSequence()
                .filter { secOfChannel(it) == sec }
                .map { firstKey(it.name) }.distinct().toSortedSet()
            groups = buildList { add(ALL_SEC); add(RECENTES); addAll(present) }
        } else {
            subMode = SubMode.GROUPS
            groups = if (subs.size > 1) buildList { add(ALL_SEC); addAll(subs) } else subs
        }
        groupsView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, groups)
        currentSub = if (groups.isNotEmpty() && groups[0] != ALL_SEC) groups[0] else null
        if (groups.isNotEmpty()) groupsView.setSelection(0)
        applyFilter()
    }

    private fun selectSub(g: String?) {
        currentSub = if (g == null || g == ALL_SEC) null else g
        applyFilter()
    }

    /** Subcategorias de Filmes: fixos + gêneros presentes (ordem canônica). */
    private fun filmesGroups(): List<String> {
        val counts = vodIndex.genreCounts()
        val genreNames = com.seupainel.player.meta.VodIndex.GENRES.entries
            .filter { (counts[it.key] ?: 0) >= 3 }      // só gêneros com conteúdo
            .map { it.value }
        return buildList {
            add(ALL_SEC); add(RECENTES); add(LANCAMENTOS)
            addAll(genreNames)
            if (genreNames.isEmpty()) {
                // enquanto o índice ainda está vazio, oferece A-Z
                val present = all.asSequence()
                    .filter { secOfChannel(it) == Sec.FILMES }
                    .map { firstKey(it.name) }.distinct().toSortedSet()
                addAll(present)
            }
        }
    }

    /** Refaz a lista de subcategorias de Filmes se novos gêneros surgiram. */
    private fun rebuildFilmesGroups() {
        if (currentSection != Sec.FILMES) return
        val newGroups = filmesGroups()
        if (newGroups == groups) return
        groups = newGroups
        val selIdx = newGroups.indexOf(currentSub ?: ALL_SEC).coerceAtLeast(0)
        groupsView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, groups)
        groupsView.setSelection(selIdx)
    }

    /** Busca gênero+ano de cada filme no TMDB (uma vez), em segundo plano. */
    private fun ensureFilmesIndexed() {
        if (indexing || tmdbKey.isBlank()) return
        indexing = true
        thread(name = "index-dispatch") {
            val movies = all.filter { secOfChannel(it) == Sec.FILMES }
            // já indexados (disco) e os que falharam nesta sessão ficam de fora.
            val todo = movies.filter { !vodIndex.has(it.name) && !indexMiss.contains(it.name) }
            if (todo.isEmpty()) { indexing = false; runOnUiThread { rebuildFilmesGroups() }; return@thread }
            val total = todo.size
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            for (ch in todo) {
                indexExec.execute {
                    val cat = runCatching { com.seupainel.player.meta.Tmdb.categorize(tmdbKey, ch.name) }.getOrNull()
                    if (cat != null) {
                        vodIndex.put(ch.name, cat.genreIds, cat.year)
                        tmdbCache[ch.name] = com.seupainel.player.meta.Tmdb.Quick(cat.rating, cat.posterUrl)
                    } else {
                        // falha/não encontrado: não repete nesta sessão; tenta de novo na próxima.
                        indexMiss.add(ch.name)
                    }
                    val d = done.incrementAndGet()
                    if (d % 40 == 0 || d == total) {
                        vodIndex.saveIfDirty()
                        runOnUiThread { onIndexProgress(d, total, movies.size) }
                    }
                    if (d == total) indexing = false
                }
            }
        }
    }

    private fun onIndexProgress(done: Int, total: Int, movieCount: Int) {
        if (currentSection != Sec.FILMES) return
        rebuildFilmesGroups()
        status.text = if (done < total) "Indexando filmes… $done/$total" else "$movieCount filmes"
    }

    private fun openSearch() {
        search.visibility = View.VISIBLE
        search.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    @Volatile private var filterGen = 0

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val favs = store.favorites()
        val sec = currentSection
        val sub = currentSub
        val gen = ++filterGen
        // Resolve o tipo da subcategoria uma vez (fora do loop).
        val genreId = com.seupainel.player.meta.VodIndex.GENRE_BY_NAME[sub]
        thread(name = "filter") {
            var result = all.filter { ch ->
                val inSec = if (sec == Sec.FAV) favs.contains(ch.url) else secOfChannel(ch) == sec
                val inSub = when {
                    sub == null || sub == ALL_SEC || sub == RECENTES -> true
                    sub == LANCAMENTOS -> (vodIndex.year(ch.name) ?: 0) >= lancCutoff
                    genreId != null -> vodIndex.genres(ch.name).contains(genreId)
                    sub.length == 1 -> firstKey(ch.name) == sub          // índice A-Z
                    else -> ch.group == sub                              // subcategoria real
                }
                inSec && inSub && (q.isEmpty() || ch.name.lowercase().contains(q))
            }
            // Ordenação por subcategoria.
            when (sub) {
                RECENTES -> result = result.takeLast(200).reversed()      // últimos adicionados
                LANCAMENTOS -> result = result.sortedByDescending { vodIndex.year(it.name) ?: 0 }
            }
            if (gen == filterGen) runOnUiThread { if (gen == filterGen) publish(result, favs, sec) }
        }
    }

    @Volatile private var seriesMode = false

    private fun publish(list: List<M3uParser.Channel>, favs: Set<String>, sec: Sec) {
        shown = list
        findViewById<View>(R.id.empty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        if (sec == Sec.SERIES && list.isNotEmpty()) { publishSeries(list); return }
        seriesMode = false

        val useGrid = sec == Sec.FILMES
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

    private var shownSeries: List<SeriesGrouper.Series> = emptyList()
    private val seriesAdapter by lazy { SeriesAdapter() }

    private fun publishSeries(list: List<M3uParser.Channel>) {
        val gen = filterGen
        thread(name = "series-group") {
            val series = SeriesGrouper.group(list)
            if (gen != filterGen) return@thread
            runOnUiThread {
                if (gen != filterGen) return@runOnUiThread
                seriesMode = true
                shownSeries = series
                channelsView.visibility = View.GONE
                gridView.visibility = View.VISIBLE
                if (gridView.adapter !== seriesAdapter) gridView.adapter = seriesAdapter
                seriesAdapter.submit(series)
                gridView.setSelection(0)
                status.text = "${series.size} séries"
            }
        }
    }

    private fun openSeries(s: SeriesGrouper.Series) {
        startActivity(Intent(this, com.seupainel.player.SeriesActivity::class.java).apply {
            putExtra(com.seupainel.player.SeriesActivity.EXTRA_TITLE, s.title)
            putExtra(com.seupainel.player.SeriesActivity.EXTRA_LOGO, s.logo ?: "")
            putExtra(com.seupainel.player.SeriesActivity.EXTRA_EPISODES,
                com.seupainel.player.SeriesActivity.episodesToJson(s.episodes))
        })
    }

    private inner class SeriesAdapter : android.widget.BaseAdapter() {
        private var items: List<SeriesGrouper.Series> = emptyList()
        fun submit(list: List<SeriesGrouper.Series>) { items = list; notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_poster, parent, false)
            val s = items[position]
            v.tag = s.title
            v.findViewById<TextView>(R.id.poster_title).text = s.title
            val img = v.findViewById<android.widget.ImageView>(R.id.poster)
            val badge = v.findViewById<TextView>(R.id.poster_rating)
            if (s.logo.isNullOrBlank()) img.setImageDrawable(null) else loadImg(img, s.logo)
            // TMDB: pôster real da série + nota
            val cached = tmdbCache[s.title]
            if (cached != null) applySeries(badge, img, cached)
            else {
                badge.visibility = View.GONE
                if (tmdbKey.isNotBlank() && !tmdbMiss.contains(s.title)) {
                    tmdbMiss.add(s.title)
                    val key = s.title
                    tmdbExec.execute {
                        val q = runCatching { com.seupainel.player.meta.Tmdb.quick(tmdbKey, s.title) }.getOrNull()
                            ?: com.seupainel.player.meta.Tmdb.Quick(0.0, null)
                        tmdbCache[s.title] = q
                        runOnUiThread { if (v.tag == key) applySeries(badge, img, q) }
                    }
                }
            }
            return v
        }
        private fun applySeries(badge: TextView, img: android.widget.ImageView, q: com.seupainel.player.meta.Tmdb.Quick) {
            if (q.rating > 0) { badge.text = "★ ${"%.1f".format(q.rating)}"; badge.visibility = View.VISIBLE }
            else badge.visibility = View.GONE
            if (!q.posterUrl.isNullOrBlank()) loadImg(img, q.posterUrl)
        }
        private fun loadImg(img: android.widget.ImageView, url: String) {
            coil.Coil.imageLoader(this@ChannelListActivity).enqueue(
                coil.request.ImageRequest.Builder(this@ChannelListActivity).data(url).target(img).crossfade(true).build())
        }
    }

    private val posterAdapter by lazy { PosterAdapter() }

    // Cache TMDB (nota + pôster) e busca limitada (só itens visíveis, sem repetir).
    private val tmdbCache = java.util.concurrent.ConcurrentHashMap<String, com.seupainel.player.meta.Tmdb.Quick>()
    private val tmdbMiss = java.util.Collections.synchronizedSet(HashSet<String>())
    private val tmdbExec = java.util.concurrent.Executors.newFixedThreadPool(4)
    private val tmdbKey by lazy { com.seupainel.player.AppSettings(this).tmdbApiKey }

    // Índice de gênero/ano (TMDB) para os filtros de Filmes. Executor próprio
    // para não travar o enriquecimento dos cartazes (tmdbExec).
    private val vodIndex by lazy { com.seupainel.player.meta.VodIndex(this) }
    private val indexMiss = java.util.Collections.synchronizedSet(HashSet<String>())
    private val indexExec = java.util.concurrent.Executors.newFixedThreadPool(3)
    @Volatile private var indexing = false
    @Volatile private var indexGenre = 0     // muda quando as subcategorias de gênero mudam
    private val lancCutoff = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 2

    /** Canal clicado: filme/série abre detalhes; ao vivo toca direto. */
    private fun onChannelClick(ch: M3uParser.Channel) {
        when (secOfChannel(ch)) {
            Sec.FILMES, Sec.SERIES -> openDetail(ch)
            else -> play(ch)
        }
    }

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
        indexExec.shutdownNow()
        vodIndex.saveIfDirty()
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
            val v = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false)
            val ch = items[position]
            v.findViewById<TextView>(R.id.ch_item_name).text =
                if (favs.contains(ch.url)) "★ ${ch.name}" else ch.name
            val logo = v.findViewById<android.widget.ImageView>(R.id.ch_item_logo)
            if (ch.logo.isNullOrBlank()) logo.setImageDrawable(null)
            else coil.Coil.imageLoader(this@ChannelListActivity).enqueue(
                coil.request.ImageRequest.Builder(this@ChannelListActivity)
                    .data(ch.logo).target(logo).crossfade(true).build())
            return v
        }
    }

    private fun toggleFav(ch: M3uParser.Channel) {
        val added = store.toggleFavorite(ch.url)
        Toast.makeText(this, if (added) "★ Favoritado: ${ch.name}" else "Removido dos favoritos", Toast.LENGTH_SHORT).show()
        applyFilter()
    }

    /** Abre a tela de detalhes (filmes/séries) antes de assistir. */
    private fun openDetail(ch: M3uParser.Channel) {
        val isSeries = currentSection == Sec.SERIES
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

    /**
     * Diagnóstico: mostra a linha crua (#EXTINF) do item selecionado, mais os
     * campos já interpretados. Serve para descobrir que metadados a lista traz
     * (gênero, ano, etc.) e assim decidir como filtrar.
     */
    private fun showItemData() {
        val pos = if (gridView.visibility == View.VISIBLE) gridView.selectedItemPosition
                  else channelsView.selectedItemPosition

        val name: String
        val url: String?
        val parsed: String
        if (seriesMode) {
            val s = shownSeries.getOrNull(pos) ?: run {
                Toast.makeText(this, "Selecione um item", Toast.LENGTH_SHORT).show(); return
            }
            val first = s.episodes.firstOrNull()?.channel
            name = s.title
            url = first?.url
            parsed = buildString {
                append("SÉRIE: ").append(s.title).append('\n')
                append("Episódios: ").append(s.episodes.size).append('\n')
                append("Temporadas: ").append(s.episodes.map { it.season }.distinct().sorted().joinToString()).append('\n')
                first?.let {
                    append("group-title: ").append(it.group).append('\n')
                    append("tvg-logo: ").append(it.logo ?: "—").append('\n')
                    append("tvg-id: ").append(it.tvgId ?: "—").append('\n')
                }
            }
        } else {
            val ch = shown.getOrNull(pos) ?: run {
                Toast.makeText(this, "Selecione um item", Toast.LENGTH_SHORT).show(); return
            }
            name = ch.name
            url = ch.url
            parsed = buildString {
                append("nome: ").append(ch.name).append('\n')
                append("group-title: ").append(ch.group).append('\n')
                append("tvg-logo: ").append(ch.logo ?: "—").append('\n')
                append("tvg-id: ").append(ch.tvgId ?: "—").append('\n')
                append("ao vivo: ").append(if (ch.isLive) "sim" else "não (VOD)").append('\n')
                ch.userAgent?.let { append("user-agent: ").append(it).append('\n') }
                ch.referer?.let { append("referer: ").append(it).append('\n') }
            }
        }

        // Procura a linha crua #EXTINF no cache em segundo plano (arquivo grande
        // -> fora da UI), e só então mostra a janela.
        thread(name = "item-diag") {
            val raw = runCatching {
                if (url == null) return@runCatching null
                val u = url.trim()
                val txt = store.readCache() ?: return@runCatching null
                var prevMeta = StringBuilder()
                var found: String? = null
                for (line in txt.lineSequence()) {
                    val t = line.trimStart()
                    when {
                        t.startsWith("#") -> prevMeta.append(line).append('\n')
                        line.trim() == u -> { found = prevMeta.toString().trimEnd(); break }
                        t.isEmpty() -> {}
                        else -> prevMeta = StringBuilder()   // outra URL -> zera metadados
                    }
                }
                found
            }.getOrNull()

            val msg = buildString {
                append(parsed)
                append('\n')
                append("URL:\n").append(url ?: "—").append("\n\n")
                append("LINHA CRUA DA LISTA:\n")
                append(if (raw.isNullOrBlank()) "(não encontrada no cache)" else raw)
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                runCatching {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(name)
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_PROG_RED -> {
                val lv = if (gridView.visibility == View.VISIBLE) gridView.selectedItemPosition else channelsView.selectedItemPosition
                if (lv in shown.indices) { toggleFav(shown[lv]); return true }
            }
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_PROG_YELLOW -> { openSearch(); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_BLUE -> { showItemData(); return true }
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
        private const val ALL_SEC = "Tudo"
        private const val RECENTES = "Recentes"
        private const val LANCAMENTOS = "Lançamentos"
        private const val MAX_CHARS = 12_000_000
        private const val UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36"
        private val DIRECT_EXT = listOf(".mp4", ".mkv", ".ts", ".avi", ".mov", ".webm", ".mp3", ".aac", ".flac")
    }
}
