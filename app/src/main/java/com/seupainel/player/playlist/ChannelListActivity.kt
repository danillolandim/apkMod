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
    private val resumeStore by lazy { com.seupainel.player.ResumeStore(this) }
    @Volatile private var lastRepartition = 0L
    private var lastPrefetchEnd = -1
    private var startSection: Sec = Sec.CANAIS               // seção escolhida na home
    private var currentSub: String? = null                  // null = tudo da seção
    private var canaisDerived = false                       // canais agrupados por tema (do nome)
    @Volatile private var secLists: Map<Sec, List<M3uParser.Channel>>? = null  // partição por seção
    @Volatile private var liveVariants: Map<String, List<M3uParser.Channel>> = emptyMap()  // canal -> qualidades
    @Volatile private var vodVariants: Map<String, DubLeg> = emptyMap()  // filme base -> dub/leg

    private data class DubLeg(val dub: M3uParser.Channel?, val leg: M3uParser.Channel?)
    // Pré-cálculos de Filmes/Séries para seleção instantânea de gênero/lançamentos.
    @Volatile private var filmesByGenre: Map<Int, List<M3uParser.Channel>>? = null
    @Volatile private var filmesLancamentos: List<M3uParser.Channel>? = null
    @Volatile private var filmesGenreCounts: Map<Int, Int> = emptyMap()
    @Volatile private var filmesAclamadas: List<M3uParser.Channel>? = null
    @Volatile private var filmesPopulares: List<M3uParser.Channel>? = null
    @Volatile private var seriesByGenre: Map<Int, List<M3uParser.Channel>>? = null
    @Volatile private var seriesLancamentos: List<M3uParser.Channel>? = null
    @Volatile private var seriesAclamadas: List<M3uParser.Channel>? = null
    @Volatile private var seriesPopulares: List<M3uParser.Channel>? = null
    @Volatile private var seriesGenreCounts: Map<Int, Int> = emptyMap()
    @Volatile private var buildingFilmes = false
    private var shown: List<M3uParser.Channel> = emptyList()

    // group-title -> Sec é determinístico e há poucos grupos distintos: memoiza
    // (evita re-uppercase/contains por item em listas de dezenas de milhares).
    private val groupSecCache = java.util.concurrent.ConcurrentHashMap<String, Sec>()
    private fun secOf(group: String): Sec = groupSecCache.getOrPut(group) {
        val u = group.uppercase()
        when {
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
        if (byGroup == Sec.CANAIS) return byGroup
        // 1) Tipo do TMDB manda (resolve grupo mal nomeado, nos dois sentidos).
        when (vodIndex.isTv(ch.name)) {
            true -> return Sec.SERIES
            false -> return Sec.FILMES
            null -> {}   // ainda não indexado: usa a heurística abaixo
        }
        // 2) Grupo já é de séries: mantém.
        if (byGroup == Sec.SERIES) return Sec.SERIES
        // 3) Grupo é de filmes: só move p/ Séries com marcador FORTE (S01E02/T01E02).
        // O formato frouxo "1x02" casa com nomes de filme e vazava filme p/ série.
        if (SeriesGrouper.hasStrongEpisodeMarker(ch.name)) return Sec.SERIES
        return byGroup
    }

    private fun currentIsSeries() = currentSection == Sec.SERIES

    /** Host de uma URL (para pré-resolver DNS). */
    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channels)
        groupsView = findViewById(R.id.groups)
        channelsView = findViewById(R.id.channels)
        gridView = findViewById(R.id.channels_grid)
        status = findViewById(R.id.status)
        search = findViewById(R.id.search)
        store = PlaylistStore(this)

        // Atualiza a lista da direita assim que o foco muda de categoria (instantâneo),
        // não só ao clicar. O clique apenas pula o foco para o conteúdo.
        groupsView.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val g = groups.getOrNull(pos)
                if (g != null && g != (currentSub ?: ALL_SEC)) selectSub(g)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        groupsView.setOnItemClickListener { _, _, pos, _ ->
            selectSub(groups.getOrNull(pos))
            (if (gridView.visibility == View.VISIBLE) gridView else channelsView).requestFocus()
        }
        channelsView.setOnItemClickListener { _, _, pos, _ -> onChannelClick(shown[pos]) }
        channelsView.setOnItemLongClickListener { _, _, pos, _ -> toggleFav(shown[pos]); true }
        gridView.setOnItemClickListener { _, _, pos, _ ->
            when {
                currentSub == CONTINUAR -> shown.getOrNull(pos)?.let { resumeItem(it) }
                seriesMode -> shownSeries.getOrNull(pos)?.let { openSeries(it) }
                else -> shown.getOrNull(pos)?.let { openDetail(it) }
            }
        }
        gridView.setOnItemLongClickListener { _, _, pos, _ ->
            if (!seriesMode) shown.getOrNull(pos)?.let { toggleFav(it) }; true
        }
        // Pré-carrega os cartazes das próximas linhas conforme rola (sem "pop-in").
        gridView.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScroll(v: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                if (visible <= 0) return
                val target = first + visible
                if (target != lastPrefetchEnd) { lastPrefetchEnd = target; prefetchPosters(target, visible) }
            }
            override fun onScrollStateChanged(v: android.widget.AbsListView?, state: Int) {}
        })
        search.setOnEditorActionListener { _, _, _ -> applyFilter(); groupsView.requestFocus(); true }

        // Barra de navegação por seções.
        findViewById<View>(R.id.nav_canais).setOnClickListener { selectSection(Sec.CANAIS) }
        findViewById<View>(R.id.nav_filmes).setOnClickListener { selectSection(Sec.FILMES) }
        findViewById<View>(R.id.nav_series).setOnClickListener { selectSection(Sec.SERIES) }
        findViewById<View>(R.id.nav_fav).setOnClickListener { selectSection(Sec.FAV) }
        findViewById<View>(R.id.nav_search).setOnClickListener { openSearch() }

        startSection = when (intent.getStringExtra(EXTRA_SECTION)) {
            "filmes" -> Sec.FILMES
            "series" -> Sec.SERIES
            else -> Sec.CANAIS
        }

        val fromSettings = intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
        val force = intent.getBooleanExtra(EXTRA_FORCE_REFRESH, false)

        startLoadingAnim()
        status.text = "Carregando…"

        val preloaded = PlaylistHolder.channels
        if (fromSettings && preloaded != null && !force) {
            // Já veio pronto do Splash -> abre instantâneo, sem tela de carregando.
            findViewById<View>(R.id.loading).visibility = View.GONE
            show(preloaded)
            if (!store.cacheFresh()) resolveAndLoad(silent = true)
        } else if (fromSettings) {
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
                if (!silent) runOnUiThread { hideLoading(); status.text = "Configure a URL da lista nas Configurações." }
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
                        if (!silent) runOnUiThread { hideLoading(); status.text = "Nada reconhecido nessa URL (não é lista M3U nem stream)." }
                    is M3uParser.Result.Playlist -> {
                        store.lastUrl = url
                        if (cacheIt) {
                            store.writeCache(text); store.channelCount = res.channels.size
                            // compara com a versão anterior e marca as novidades
                            newItems.update(res.channels.map { it.url })
                        }
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
                if (!silent) runOnUiThread { hideLoading(); status.text = "Erro ao carregar: ${e.message}" }
            }
        }
    }

    /** Animação "aguarde, carregando o conteúdo" até a lista ficar pronta. */
    private fun startLoadingAnim() {
        findViewById<View?>(R.id.loading)?.requestFocus()   // segura o foco até carregar
        val text = findViewById<TextView?>(R.id.loading_text) ?: return
        text.startAnimation(android.view.animation.AlphaAnimation(0.45f, 1f).apply {
            duration = 700
            repeatMode = android.view.animation.Animation.REVERSE
            repeatCount = android.view.animation.Animation.INFINITE
        })
    }

    private fun hideLoading() {
        val v = findViewById<View>(R.id.loading) ?: return
        if (v.visibility == View.GONE) return
        findViewById<View?>(R.id.loading_text)?.clearAnimation()
        v.animate().alpha(0f).setDuration(250)
            .withEndAction { v.visibility = View.GONE }.start()
    }

    private fun show(channels: List<M3uParser.Channel>) {
        all = channels
        PlaylistHolder.channels = channels          // mantém a memória compartilhada em dia
        // Particiona por seção em 2º plano -> filtros/categorias ficam instantâneos.
        thread(name = "partition") {
            val m = HashMap<Sec, MutableList<M3uParser.Channel>>()
            val hostCount = HashMap<String, Int>()
            for (ch in channels) {
                m.getOrPut(secOfChannel(ch)) { ArrayList() }.add(ch)
                hostOf(ch.url)?.let { hostCount[it] = (hostCount[it] ?: 0) + 1 }
            }
            secLists = m
            // Pré-resolve o DNS dos hosts mais usados (zapping mais rápido).
            val topHosts = hostCount.entries.sortedByDescending { it.value }.map { it.key }.take(12)
            com.seupainel.player.net.Net.warmDns(topHosts)
        }
        val realGroups = channels.map { it.group }.distinct()
        sectionGroups = realGroups.groupBy { secOf(it) }
        status.text = "${channels.size} itens"
        selectSection(startSection)
        groupsView.requestFocus()
        hideLoading()
        // Lista padrão: indexa tudo em 2º plano, mesmo fora da seção Filmes,
        // e já pré-calcula os atalhos de gênero/lançamentos/aclamadas.
        ensureFilmesIndexed()
        buildFilmesIndex()
        // EPG (guia "agora/a seguir"): carrega o XMLTV em 2º plano; quando pronto,
        // atualiza a lista ao vivo pra mostrar o programa atual sob cada canal.
        com.seupainel.player.meta.Epg.ensureLoaded(store.epgUrl, store.userAgent)
        com.seupainel.player.meta.Epg.onReady {
            runOnUiThread { if (currentSection == Sec.CANAIS) channelAdapter.notifyDataSetChanged() }
        }
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

        canaisDerived = false
        when {
            sec == Sec.FAV -> {
                subMode = SubMode.GROUPS
                groups = emptyList()
            }
            subs.size > 1 -> {
                // A lista já traz categorias reais para esta seção. Usa-as direto.
                subMode = SubMode.GROUPS
                groups = buildList { add(ALL_SEC); addAll(subs) }
            }
            sec == Sec.FILMES -> {
                // Sem categorias reais -> gênero/lançamento via TMDB.
                subMode = SubMode.ALPHA
                groups = filmesGroups()
                ensureFilmesIndexed()
                buildFilmesIndex()
            }
            sec == Sec.CANAIS -> {
                // Sem categorias reais -> deduz do nome (Esportes, Notícias, …).
                canaisDerived = true
                subMode = SubMode.GROUPS
                val present = all.asSequence()
                    .filter { secOfChannel(it) == Sec.CANAIS }
                    .map { LiveGroups.categoryOf(it.name) }.toHashSet()
                groups = buildList {
                    add(ALL_SEC)
                    if (newItems.anyNew()) add(NOVIDADES)
                    addAll(LiveGroups.ORDER.filter { it in present })
                }
            }
            else -> {   // Sec.SERIES sem categorias reais -> gênero/lançamento via TMDB.
                subMode = SubMode.ALPHA
                groups = vodGroups(Sec.SERIES)
                ensureFilmesIndexed()
                buildFilmesIndex()
            }
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

    /** Pré-computa gênero->conteúdo e lançamentos de Filmes E Séries (em 2º plano). */
    private fun buildFilmesIndex() {
        if (buildingFilmes) return
        buildingFilmes = true
        thread(name = "vod-fast") {
            // Reparticiona considerando o tipo do TMDB (melhora conforme indexa):
            // um filme que a lista pôs em "Séries" (ou vice-versa) volta pro lugar.
            // Throttle: no máximo a cada 8s (a indexação chama isto dezenas de vezes).
            val nowMs = System.currentTimeMillis()
            if (all.isNotEmpty() && (secLists == null || nowMs - lastRepartition > 8_000)) {
                val m = HashMap<Sec, MutableList<M3uParser.Channel>>()
                for (ch in all) m.getOrPut(secOfChannel(ch)) { ArrayList() }.add(ch)
                secLists = m
                lastRepartition = nowMs
            }
            // Filmes
            val movies = secLists?.get(Sec.FILMES) ?: all.filter { secOfChannel(it) == Sec.FILMES }
            val mByGenre = HashMap<Int, ArrayList<M3uParser.Channel>>()
            val mYear = ArrayList<Pair<M3uParser.Channel, Int>>()
            val mAcl = ArrayList<Pair<M3uParser.Channel, Double>>()
            val mPop = ArrayList<Pair<M3uParser.Channel, Int>>()
            for (ch in movies) {
                val e = vodIndex.get(ch.name) ?: continue
                e.genreIds.forEach { mByGenre.getOrPut(it) { ArrayList() }.add(ch) }
                val y = e.year
                if (y != null && y >= lancCutoff) mYear.add(ch to y)
                if (!e.rating.isNaN() && e.rating >= ACLAMADA_MIN_RATING && e.votes >= ACLAMADA_MIN_VOTES)
                    mAcl.add(ch to e.rating)
                if (e.votes >= POPULAR_MIN_VOTES) mPop.add(ch to e.votes)
            }
            filmesByGenre = mByGenre
            filmesGenreCounts = mByGenre.mapValues { it.value.size }
            filmesLancamentos = mYear.sortedByDescending { it.second }.map { it.first }
            filmesAclamadas = mAcl.sortedByDescending { it.second }.map { it.first }
            filmesPopulares = mPop.sortedByDescending { it.second }.map { it.first }

            // Séries (episódios herdam o gênero/ano da série pelo título)
            val series = secLists?.get(Sec.SERIES) ?: all.filter { secOfChannel(it) == Sec.SERIES }
            val sByGenre = HashMap<Int, ArrayList<M3uParser.Channel>>()
            val sTitles = HashMap<Int, HashSet<String>>()
            val sYear = ArrayList<Pair<M3uParser.Channel, Int>>()
            val sAcl = ArrayList<Pair<M3uParser.Channel, Double>>()
            val sPop = ArrayList<Pair<M3uParser.Channel, Int>>()
            for (ch in series) {
                val e = vodIndex.get(ch.name) ?: continue
                val tkey = vodIndex.keyFor(ch.name)
                e.genreIds.forEach {
                    sByGenre.getOrPut(it) { ArrayList() }.add(ch)
                    sTitles.getOrPut(it) { HashSet() }.add(tkey)
                }
                val y = e.year
                if (y != null && y >= lancCutoff) sYear.add(ch to y)
                if (!e.rating.isNaN() && e.rating >= ACLAMADA_MIN_RATING && e.votes >= ACLAMADA_MIN_VOTES)
                    sAcl.add(ch to e.rating)
                if (e.votes >= POPULAR_MIN_VOTES) sPop.add(ch to e.votes)
            }
            seriesByGenre = sByGenre
            seriesGenreCounts = sTitles.mapValues { it.value.size }   // séries distintas por gênero
            seriesLancamentos = sYear.sortedByDescending { it.second }.map { it.first }
            seriesAclamadas = sAcl.sortedByDescending { it.second }.map { it.first }
            seriesPopulares = sPop.sortedByDescending { it.second }.map { it.first }

            buildingFilmes = false
        }
    }

    /** Subcategorias de Filmes/Séries: fixos + gêneros presentes (ordem canônica). */
    private fun vodGroups(sec: Sec): List<String> {
        val counts = if (sec == Sec.SERIES) seriesGenreCounts else filmesGenreCounts
        val genreNames = com.seupainel.player.meta.VodIndex.GENRES.entries
            .filter { (counts[it.key] ?: 0) >= 3 }      // só gêneros com conteúdo
            .map { it.value }
        val aclamadas = if (sec == Sec.SERIES) seriesAclamadas else filmesAclamadas
        val populares = if (sec == Sec.SERIES) seriesPopulares else filmesPopulares
        val resumeKind = if (sec == Sec.SERIES) "series" else "movie"
        return buildList {
            if (resumeStore.recents(resumeKind).isNotEmpty()) add(CONTINUAR)
            add(ALL_SEC); add(LANCAMENTOS)
            if (!populares.isNullOrEmpty()) add(POPULARES)
            if (!aclamadas.isNullOrEmpty()) add(ACLAMADAS)
            if (newItems.anyNew()) add(NOVIDADES)
            addAll(genreNames)
            if (genreNames.isEmpty()) {
                // enquanto o índice ainda está vazio, oferece A-Z
                val present = all.asSequence()
                    .filter { secOfChannel(it) == sec }
                    .map { firstKey(it.name) }.distinct().toSortedSet()
                addAll(present)
            }
        }
    }
    private fun filmesGroups(): List<String> = vodGroups(Sec.FILMES)

    /** Refaz a lista de subcategorias de Filmes/Séries se novos gêneros surgiram. */
    private fun rebuildFilmesGroups() {
        if (currentSection != Sec.FILMES && currentSection != Sec.SERIES) return
        // Em Filmes/Séries sem subcategorias reais (modo gênero/A-Z).
        if (subMode != SubMode.ALPHA) return
        val newGroups = vodGroups(currentSection)
        if (newGroups == groups) return
        // Se o usuário está navegando a lista de categorias, preserva a posição.
        val keepPos = if (groupsView.hasFocus()) groupsView.selectedItemPosition else -1
        groups = newGroups
        groupsView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, groups)
        val sel = if (keepPos in newGroups.indices) keepPos
                  else newGroups.indexOf(currentSub ?: ALL_SEC).coerceAtLeast(0)
        groupsView.setSelection(sel)
    }

    /** Busca gênero+ano no TMDB (uma vez por título) de filmes E séries. */
    private fun ensureFilmesIndexed() {
        if (indexing || tmdbKey.isBlank()) return
        indexing = true
        indexStarted = true
        thread(name = "index-dispatch") {
            val vod = all.filter { val s = secOfChannel(it); s == Sec.FILMES || s == Sec.SERIES }
            // Um item por TÍTULO (episódios de uma série colapsam na mesma chave),
            // pulando os já indexados; novidades primeiro.
            val seenKeys = HashSet<String>()
            val todo = ArrayList<M3uParser.Channel>()
            // Prioriza a seção aberta (o que o usuário vê agora), depois novidades.
            val openSec = currentSection
            val ordered = vod.sortedWith(
                compareByDescending<M3uParser.Channel> { if (secOfChannel(it) == openSec) 1 else 0 }
                    .thenByDescending { if (newItems.isNew(it.url)) 1 else 0 }
            )
            for (ch in ordered) {
                val key = vodIndex.keyFor(ch.name)
                if (key.isBlank() || indexMiss.contains(ch.name)) continue
                val e = vodIndex.getByKey(key)
                // Casou no TMDB? (itens "não existe" têm tudo vazio/zero.)
                val matched = e != null && (e.genreIds.isNotEmpty() || e.year != null ||
                    e.votes > 0 || (!e.rating.isNaN() && e.rating > 0.0))
                // novo, OU sem nota (formato antigo), OU casou mas ainda sem o tipo filme/série
                val needs = e == null || e.rating.isNaN() || (matched && e.tv == null)
                if (needs && seenKeys.add(key)) todo.add(ch)
            }
            if (todo.isEmpty()) { indexing = false; runOnUiThread { rebuildFilmesGroups() }; return@thread }
            val movies = vod   // só para o texto de progresso
            val total = todo.size
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            for (ch in todo) {
                indexExec.execute {
                    if (Thread.currentThread().isInterrupted) return@execute
                    awaitIndexResume()                 // espera se o app estiver em 2º plano
                    if (Thread.currentThread().isInterrupted) return@execute
                    when (val r = runCatching { com.seupainel.player.meta.Tmdb.categorize(tmdbKey, ch.name) }
                        .getOrDefault(com.seupainel.player.meta.Tmdb.CatRes.Error)) {
                        is com.seupainel.player.meta.Tmdb.CatRes.Found -> {
                            vodIndex.put(ch.name, r.cat.genreIds, r.cat.year, r.cat.rating, r.cat.votes, r.cat.isTv)
                            tmdbCache[ch.name] = com.seupainel.player.meta.Tmdb.Quick(r.cat.rating, r.cat.posterUrl)
                            com.seupainel.player.meta.PosterCache.put(ch.name, r.cat.posterUrl)
                        }
                        com.seupainel.player.meta.Tmdb.CatRes.NotFound ->
                            vodIndex.put(ch.name, emptyList(), null, 0.0, 0)   // não existe: guarda de vez
                        com.seupainel.player.meta.Tmdb.CatRes.Error ->
                            indexMiss.add(ch.name)                     // rede: tenta na próxima sessão
                    }
                    val d = done.incrementAndGet()
                    if (d % 200 == 0 || d == total) {
                        vodIndex.saveIfDirty()
                        runOnUiThread { onIndexProgress(d, total, movies.size) }
                    }
                    if (d == total) indexing = false
                }
            }
        }
    }

    private fun onIndexProgress(done: Int, total: Int, movieCount: Int) {
        if (currentSection != Sec.FILMES && currentSection != Sec.SERIES) return
        rebuildFilmesGroups()
        buildFilmesIndex()   // atualiza os atalhos de gênero/lançamentos
        status.text = if (done < total) "Organizando catálogo… $done/$total" else ""
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
        val derivedLive = canaisDerived
        val gen = ++filterGen
        // Fileira "Continuar assistindo": itens em andamento (grade plana).
        if (sub == CONTINUAR && (sec == Sec.FILMES || sec == Sec.SERIES)) {
            val kind = if (sec == Sec.SERIES) "series" else "movie"
            val items = resumeStore.recents(kind).map {
                M3uParser.Channel(name = it.title, url = it.key, group = "",
                    logo = it.logo.ifBlank { null }, duration = 0)
            }
            publish(items, favs, Sec.FILMES)   // grade plana, sem reagrupar séries
            return
        }
        // Resolve o tipo da subcategoria uma vez (fora do loop).
        val genreId = com.seupainel.player.meta.VodIndex.GENRE_BY_NAME[sub]
        val lists = secLists
        val fastGenre = when {
            sec == Sec.FILMES && genreId != null -> filmesByGenre?.get(genreId)
            sec == Sec.SERIES && genreId != null -> seriesByGenre?.get(genreId)
            else -> null
        }
        val fastLanc = when {
            sec == Sec.FILMES && sub == LANCAMENTOS -> filmesLancamentos
            sec == Sec.SERIES && sub == LANCAMENTOS -> seriesLancamentos
            sec == Sec.FILMES && sub == ACLAMADAS -> filmesAclamadas
            sec == Sec.SERIES && sub == ACLAMADAS -> seriesAclamadas
            sec == Sec.FILMES && sub == POPULARES -> filmesPopulares
            sec == Sec.SERIES && sub == POPULARES -> seriesPopulares
            else -> null
        }
        thread(name = "filter") {
            // Atalho pré-computado p/ Filmes (gênero/lançamentos) -> instantâneo.
            val fast = fastGenre ?: fastLanc
            if (fast != null) {
                if (gen != filterGen) return@thread     // filtro antigo: não mexe no estado
                var r = if (q.isEmpty()) fast else fast.filter { it.name.lowercase().contains(q) }
                if (sec == Sec.FILMES) r = collapseDubLeg(r)   // junta dublado+legendado
                val out = r
                if (gen == filterGen) runOnUiThread { if (gen == filterGen) publish(out, favs, sec) }
                return@thread
            }
            // Base da seção já particionada (rápido); se ainda não pronta, filtra tudo.
            val base: List<M3uParser.Channel> = when {
                sec == Sec.FAV -> all.filter { favs.contains(it.url) }
                lists != null -> lists[sec] ?: emptyList()
                else -> all.filter { secOfChannel(it) == sec }
            }
            var result = base.filter { ch ->
                val inSub = when {
                    sub == null || sub == ALL_SEC || sub == RECENTES -> true
                    sub == NOVIDADES -> newItems.isNew(ch.url)            // entrou de novo
                    derivedLive -> LiveGroups.categoryOf(ch.name) == sub  // canais por tema
                    sub == ACLAMADAS -> {
                        val r = vodIndex.rating(ch.name)
                        !r.isNaN() && r >= ACLAMADA_MIN_RATING && vodIndex.votes(ch.name) >= ACLAMADA_MIN_VOTES
                    }
                    sub == POPULARES -> vodIndex.votes(ch.name) >= POPULAR_MIN_VOTES
                    sub == LANCAMENTOS -> (vodIndex.year(ch.name) ?: 0) >= lancCutoff
                    genreId != null -> vodIndex.genres(ch.name).contains(genreId)
                    sub.length == 1 -> firstKey(ch.name) == sub          // índice A-Z
                    else -> ch.group == sub                              // subcategoria real
                }
                inSub && (q.isEmpty() || ch.name.lowercase().contains(q))
            }
            // Ordenação por subcategoria.
            when (sub) {
                RECENTES -> result = result.takeLast(200).reversed()      // últimos adicionados
                NOVIDADES -> result = result.sortedByDescending { newItems.firstSeen(it.url) }
                LANCAMENTOS -> result = result.sortedByDescending { vodIndex.year(it.name) ?: 0 }
                ACLAMADAS -> result = result.sortedByDescending { vodIndex.rating(it.name).let { r -> if (r.isNaN()) 0.0 else r } }
                POPULARES -> result = result.sortedByDescending { vodIndex.votes(it.name) }
            }
            // Junta variações num item só: canais por qualidade; filmes por dub/leg.
            // Só o filtro atual mexe nos mapas de variações (evita estado velho).
            if (gen != filterGen) return@thread
            if (sec == Sec.CANAIS) result = collapseLive(result)
            else if (sec == Sec.FILMES) result = collapseDubLeg(result)
            if (gen == filterGen) runOnUiThread { if (gen == filterGen) publish(result, favs, sec) }
        }
    }

    @Volatile private var seriesMode = false

    private fun publish(list: List<M3uParser.Channel>, favs: Set<String>, sec: Sec) {
        shown = list
        lastPrefetchEnd = -1
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
        val sub = currentSub
        thread(name = "series-group") {
            var series = SeriesGrouper.group(list)
            when (sub) {
                LANCAMENTOS -> series = series.sortedByDescending { vodIndex.year(it.title) ?: 0 }
                ACLAMADAS -> series = series.sortedByDescending { vodIndex.rating(it.title).let { r -> if (r.isNaN()) 0.0 else r } }
                POPULARES -> series = series.sortedByDescending { vodIndex.votes(it.title) }
            }
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
            val v = convertView ?: layoutInflater.inflate(R.layout.item_poster, parent, false).also { it.tag = PH(it) }
            val h = v.tag as PH
            val s = items[position]
            h.key = s.title
            h.title.text = s.title
            // Cartaz: usa o do TMDB (mesmo do detalhe) quando já conhecido.
            val known = com.seupainel.player.meta.PosterCache.get(s.title)
            when {
                !known.isNullOrBlank() -> loadImg(h.poster, known)
                !s.logo.isNullOrBlank() -> loadImg(h.poster, s.logo)
                else -> h.poster.setImageDrawable(null)
            }
            // TMDB: pôster real da série + nota
            val cached = tmdbCache[s.title]
            if (cached != null) applySeries(h, cached, s.title)
            else {
                h.badge.visibility = View.GONE
                if (tmdbKey.isNotBlank() && !tmdbMiss.contains(s.title)) {
                    tmdbMiss.add(s.title)
                    val key = s.title
                    tmdbExec.execute {
                        val q = runCatching { com.seupainel.player.meta.Tmdb.quick(tmdbKey, s.title) }.getOrNull()
                            ?: com.seupainel.player.meta.Tmdb.Quick(0.0, null)
                        tmdbCache[s.title] = q
                        com.seupainel.player.meta.PosterCache.put(s.title, q.posterUrl)
                        runOnUiThread { if (h.key == key) applySeries(h, q, s.title) }
                    }
                }
            }
            return v
        }
        private fun applySeries(h: PH, q: com.seupainel.player.meta.Tmdb.Quick, title: String) {
            if (q.rating > 0) { h.badge.text = "★ ${"%.1f".format(q.rating)}"; h.badge.visibility = View.VISIBLE }
            else h.badge.visibility = View.GONE
            val poster = com.seupainel.player.meta.PosterCache.get(title) ?: q.posterUrl
            if (!poster.isNullOrBlank()) loadImg(h.poster, poster)
        }
        private fun loadImg(img: android.widget.ImageView, url: String) {
            coil.Coil.imageLoader(this@ChannelListActivity).enqueue(
                coil.request.ImageRequest.Builder(this@ChannelListActivity).data(gridPosterUrl(url)).target(img).crossfade(true).build())
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
    private val indexExec = java.util.concurrent.Executors.newFixedThreadPool(8)
    @Volatile private var indexing = false
    @Volatile private var indexStarted = false      // Filmes já foi aberto nesta sessão?
    @Volatile private var indexPaused = false       // pausa a indexação durante a reprodução
    private val indexGate = Object()
    private val lancCutoff = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - 3

    // Detector de novidades (o que entrou de novo entre uma atualização e outra).
    private val newItems by lazy { NewItems(this) }

    /** Segura os workers de indexação enquanto o app está em 2º plano. */
    private fun awaitIndexResume() {
        synchronized(indexGate) {
            while (indexPaused) {
                try { indexGate.wait() } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt(); return
                }
            }
        }
    }

    /** Canal clicado: filme/série abre detalhes; ao vivo toca direto. */
    private fun onChannelClick(ch: M3uParser.Channel) {
        if (currentSub == CONTINUAR) { resumeItem(ch); return }
        when (secOfChannel(ch)) {
            Sec.FILMES, Sec.SERIES -> openDetail(ch)
            else -> playLive(ch)
        }
    }

    /** Retoma direto um item da fileira "Continuar" (a posição vem pela URL). */
    private fun resumeItem(ch: M3uParser.Channel) {
        val kind = if (currentSection == Sec.SERIES) "series" else "movie"
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, ch.url)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, false)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            putExtra(PlayerActivity.EXTRA_LOGO, ch.logo ?: "")
            putExtra(PlayerActivity.EXTRA_KIND, kind)
        })
    }

    private inner class PH(val root: View) {
        val title: TextView = root.findViewById(R.id.poster_title)
        val poster: android.widget.ImageView = root.findViewById(R.id.poster)
        val badge: TextView = root.findViewById(R.id.poster_rating)
        var key: String = ""   // identidade para casar o callback assíncrono
    }

    private inner class PosterAdapter : android.widget.BaseAdapter() {
        private var items: List<M3uParser.Channel> = emptyList()
        fun submit(list: List<M3uParser.Channel>) { items = list; notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_poster, parent, false).also { it.tag = PH(it) }
            val h = v.tag as PH
            val ch = items[position]
            h.key = ch.url
            h.title.text = ch.name
            val series = currentIsSeries()

            // Capa da lista como base (fallback). Em séries ela costuma ser um
            // frame do episódio, então será trocada pelo pôster do TMDB abaixo.
            val logo = ch.logo
            if (logo.isNullOrBlank()) h.poster.setImageDrawable(null)
            else loadPoster(h.poster, logo)

            bindTmdb(h, ch, series)
            return v
        }

        private fun bindTmdb(h: PH, ch: M3uParser.Channel, series: Boolean) {
            val cached = tmdbCache[ch.name]
            if (cached != null) { applyTmdb(h, ch, cached, series); return }
            h.badge.visibility = View.GONE
            if (tmdbKey.isBlank() || tmdbMiss.contains(ch.name)) return
            tmdbMiss.add(ch.name)
            val key = ch.url
            tmdbExec.execute {
                val q = runCatching { com.seupainel.player.meta.Tmdb.quick(tmdbKey, ch.name) }.getOrNull()
                    ?: com.seupainel.player.meta.Tmdb.Quick(0.0, null)
                tmdbCache[ch.name] = q
                com.seupainel.player.meta.PosterCache.put(ch.name, q.posterUrl)
                runOnUiThread { if (h.key == key) applyTmdb(h, ch, q, series) }
            }
        }

        private fun applyTmdb(h: PH, ch: M3uParser.Channel, q: com.seupainel.player.meta.Tmdb.Quick, series: Boolean) {
            if (q.rating > 0) { h.badge.text = "★ ${"%.1f".format(q.rating)}"; h.badge.visibility = View.VISIBLE }
            else h.badge.visibility = View.GONE
            // Sempre preferir o pôster do TMDB (mesmo que a tela de detalhes),
            // porque a capa da lista costuma estar trocada/antiga.
            val poster = com.seupainel.player.meta.PosterCache.get(ch.name) ?: q.posterUrl
            if (!poster.isNullOrBlank()) loadPoster(h.poster, poster)
        }

        private fun loadPoster(img: android.widget.ImageView, url: String) {
            coil.Coil.imageLoader(this@ChannelListActivity).enqueue(
                coil.request.ImageRequest.Builder(this@ChannelListActivity)
                    .data(gridPosterUrl(url)).target(img).crossfade(true).build()
            )
        }
    }

    /**
     * Pôster leve (w342) para a grade de cards menores (6 por linha): a imagem
     * bate com o tamanho exibido, então fica nítida e pesa menos (rolagem mais
     * suave). O cartaz cheio (w500) aparece na tela de detalhes.
     */
    private fun gridPosterUrl(url: String): String = url.replace("/t/p/w500", "/t/p/w342")

    /**
     * Aquece o cache de imagem dos próximos [count] itens da grade, para o
     * cartaz já estar pronto quando o foco chegar neles. Só pré-carrega URLs
     * JÁ conhecidas (pôster do TMDB em cache ou capa da lista); a busca no TMDB
     * dos itens ainda sem pôster fica para a célula visível (que atualiza a
     * própria view) — assim o prefetch nunca "segura" um item na tela.
     */
    private fun prefetchPosters(start: Int, count: Int) {
        if (seriesMode) return
        val list = shown
        val end = minOf(start + count, list.size)
        val loader = coil.Coil.imageLoader(this)
        for (i in start until end) {
            if (i < 0) continue
            val ch = list[i]
            val url = com.seupainel.player.meta.PosterCache.get(ch.name)
                ?: tmdbCache[ch.name]?.posterUrl ?: ch.logo
            if (!url.isNullOrBlank()) {
                // Só aquece o cache de DISCO (mata a latência de rede adiantada);
                // não escreve na memória pra não despejar os cartazes visíveis.
                loader.enqueue(
                    coil.request.ImageRequest.Builder(this)
                        .data(gridPosterUrl(url))
                        .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                        .build())
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Em 2º plano (assistindo/detalhes): pausa a indexação e salva o progresso.
        if (indexStarted) { indexPaused = true; vodIndex.saveIfDirty() }
    }

    override fun onResume() {
        super.onResume()
        if (indexStarted) synchronized(indexGate) { indexPaused = false; indexGate.notifyAll() }
        // Volta do player: atualiza a fileira "Continuar" (entrou/concluiu algo).
        if (currentSection == Sec.FILMES || currentSection == Sec.SERIES) rebuildFilmesGroups()
    }

    override fun onDestroy() {
        super.onDestroy()
        tmdbExec.shutdownNow()
        indexExec.shutdownNow()
        synchronized(indexGate) { indexPaused = false; indexGate.notifyAll() }
        if (indexStarted) vodIndex.saveIfDirty()
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
        private inner class CH(root: View) {
            val name: TextView = root.findViewById(R.id.ch_item_name)
            val epg: TextView = root.findViewById(R.id.ch_item_epg)
            val logo: android.widget.ImageView = root.findViewById(R.id.ch_item_logo)
        }
        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup?): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_channel, parent, false).also { it.tag = CH(it) }
            val h = v.tag as CH
            val ch = items[position]
            h.name.text = if (favs.contains(ch.url)) "★ ${ch.name}" else ch.name
            val now = com.seupainel.player.meta.Epg.nowText(ch.tvgId)
            if (now.isBlank()) h.epg.visibility = View.GONE
            else { h.epg.text = now; h.epg.visibility = View.VISIBLE }
            if (ch.logo.isNullOrBlank()) h.logo.setImageDrawable(null)
            else coil.Coil.imageLoader(this@ChannelListActivity).enqueue(
                coil.request.ImageRequest.Builder(this@ChannelListActivity)
                    .data(ch.logo).target(h.logo).crossfade(true).build())
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
        val variants = if (isSeries) null else vodVariants[ch.name.lowercase()]
        startActivity(Intent(this, com.seupainel.player.DetailActivity::class.java).apply {
            putExtra(com.seupainel.player.DetailActivity.EXTRA_URL, ch.url)
            putExtra(com.seupainel.player.DetailActivity.EXTRA_TITLE, ch.name)
            putExtra(com.seupainel.player.DetailActivity.EXTRA_LOGO, ch.logo ?: "")
            putExtra(com.seupainel.player.DetailActivity.EXTRA_IS_LIVE, ch.isLive)
            putExtra(com.seupainel.player.DetailActivity.EXTRA_IS_SERIES, isSeries)
            putExtra(com.seupainel.player.DetailActivity.EXTRA_URL_DUB, variants?.dub?.url)
            putExtra(com.seupainel.player.DetailActivity.EXTRA_URL_LEG, variants?.leg?.url)
        })
    }

    /** Junta dublado+legendado do mesmo filme num item; guarda as duas versões. */
    private fun collapseDubLeg(list: List<M3uParser.Channel>): List<M3uParser.Channel> {
        val groups = LinkedHashMap<String, MutableList<M3uParser.Channel>>()
        for (ch in list) groups.getOrPut(Dub.base(ch.name).lowercase()) { ArrayList() }.add(ch)
        val reps = ArrayList<M3uParser.Channel>(groups.size)
        val vmap = HashMap<String, DubLeg>(groups.size)
        for (variants in groups.values) {
            val leg = variants.firstOrNull { Dub.isLeg(it.name) }
            val dub = variants.firstOrNull { !Dub.isLeg(it.name) }
            val rep = dub ?: leg ?: variants.first()
            val repBase = Dub.base(rep.name)
            reps.add(rep.copy(name = repBase))
            vmap[repBase.lowercase()] = DubLeg(dub, leg)
        }
        vodVariants = vmap
        return reps
    }

    /** Junta variações de qualidade do mesmo canal; guarda as variações p/ o player. */
    private fun collapseLive(list: List<M3uParser.Channel>): List<M3uParser.Channel> {
        val groups = LinkedHashMap<String, MutableList<M3uParser.Channel>>()
        for (ch in list) groups.getOrPut(ChannelQuality.baseName(ch.name).lowercase()) { ArrayList() }.add(ch)
        val reps = ArrayList<M3uParser.Channel>(groups.size)
        val vmap = HashMap<String, List<M3uParser.Channel>>(groups.size)
        for (variants in groups.values) {
            val sorted = variants.sortedByDescending { ChannelQuality.rank(it.name) }
            val best = sorted.first()
            val baseName = ChannelQuality.baseName(best.name)
            reps.add(best.copy(name = baseName))
            vmap[baseName] = sorted
        }
        liveVariants = vmap
        return reps
    }

    /** Toca um canal ao vivo; se tiver várias qualidades, leva todas ao player. */
    private fun playLive(ch: M3uParser.Channel) {
        val variants = liveVariants[ch.name]
        if (variants == null || variants.size <= 1) { play(ch); return }
        val best = variants.first()
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, best.url)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, best.isLive)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            putExtra(PlayerActivity.EXTRA_LOGO, ch.logo ?: "")
            putExtra(PlayerActivity.EXTRA_TVG_ID, ch.tvgId ?: "")
            best.userAgent?.let { putExtra(PlayerActivity.EXTRA_USER_AGENT, it) }
            best.referer?.let { putExtra(PlayerActivity.EXTRA_REFERER, it) }
            putStringArrayListExtra(PlayerActivity.EXTRA_VARIANT_URLS, ArrayList(variants.map { it.url }))
            putStringArrayListExtra(PlayerActivity.EXTRA_VARIANT_LABELS,
                ArrayList(variants.map { ChannelQuality.labelOf(it.name) }))
        })
    }

    private fun play(ch: M3uParser.Channel) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, ch.url)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, ch.isLive)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            putExtra(PlayerActivity.EXTRA_LOGO, ch.logo ?: "")
            putExtra(PlayerActivity.EXTRA_TVG_ID, ch.tvgId ?: "")
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
                    androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Player_Dialog)
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
        const val EXTRA_SECTION = "section"   // "canais" | "filmes" | "series"
        private const val ALL_SEC = "Tudo"
        private const val RECENTES = "Recentes"
        private const val NOVIDADES = "Novidades"
        private const val CONTINUAR = "▶ Continuar"
        private const val LANCAMENTOS = "Lançamentos"
        private const val ACLAMADAS = "Aclamadas"
        private const val POPULARES = "Populares"
        private const val ACLAMADA_MIN_RATING = 7.5
        private const val ACLAMADA_MIN_VOTES = 20
        private const val POPULAR_MIN_VOTES = 30     // mais assistidas (por votos)
        private const val MAX_CHARS = 12_000_000
        private const val UA = "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/120 Safari/537.36"
        private val DIRECT_EXT = listOf(".mp4", ".mkv", ".ts", ".avi", ".mov", ".webm", ".mp3", ".aac", ".flac")
    }
}
