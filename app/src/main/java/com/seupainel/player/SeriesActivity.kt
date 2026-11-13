package com.seupainel.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.FragmentActivity
import coil.dispose
import com.seupainel.player.meta.Tmdb
import com.seupainel.player.playlist.EpisodeCodec
import com.seupainel.player.playlist.PlaylistHolder
import com.seupainel.player.playlist.PlaylistStore
import com.seupainel.player.playlist.SeriesGrouper
import com.seupainel.player.playlist.XtreamApi
import com.seupainel.player.playlist.XtreamCatalog
import com.seupainel.player.search.SearchCatalog
import kotlin.concurrent.thread

/**
 * Tela da série no tema Landimflix: dados e botão principal ("Continuar T2 · E4") à esquerda; temporadas em pílulas
 * (com a contagem de vistos) e a lista de episódios à direita, cada um com o estado em ícone + texto + cor.
 *
 * Os episódios chegam prontos pelo Intent (série da lista M3U) ou são buscados aqui (série da API), com estado de
 * carregando e de erro com "Tentar de novo". A tela abre com o foco no próximo episódio a assistir.
 */
class SeriesActivity : FragmentActivity() {

    private class Row(
        val ep: SeriesGrouper.Ep,
        val state: WatchLogic.State,
        val pct: Int,
        val remaining: String?,
    )

    /** O que foi visto, numa foto só (uma leitura do disco por atualização da tela). */
    private class Snap(val flags: Set<String>, val items: Map<String, ResumeStore.Item>) {
        fun pct(key: String): Int {
            val it = items[key] ?: return 0
            return if (it.dur > 0) ((it.pos * 100) / it.dur).toInt().coerceIn(1, 99) else 1
        }
        // Quem tem ponto salvo está em andamento, mesmo já tendo sido visto antes: conta como visto só ao terminar.
        fun watched(key: String) = key in flags && key !in items
        fun touched(key: String) = items[key]?.ts ?: 0L
    }

    private lateinit var episodesView: ListView
    private lateinit var seasonsRow: LinearLayout
    private lateinit var dubBtn: TextView
    private lateinit var legBtn: TextView
    private lateinit var playBtn: TextView
    private lateinit var stateBox: View

    private var allEps: List<SeriesGrouper.Ep> = emptyList()
    private var shownEps: List<SeriesGrouper.Ep> = emptyList()
    private var currentLeg = false
    private var currentSeason = 1
    private var pick: WatchLogic.Pick? = null
    private var title = ""
    private var logo = ""
    private var seriesId = 0L
    private var backdropIsArt = false
    private var started = false      // já abriu o player por aqui? (ao voltar, o foco vai para o próximo)
    private var focusedOnce = false

    private val resume by lazy { ResumeStore(this) }
    private val summaries by lazy { SeriesSummaryStore(this) }
    private val adapter = EpisodeAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)

        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        logo = intent.getStringExtra(EXTRA_LOGO).orEmpty()
        seriesId = intent.getLongExtra(EXTRA_SERIES_ID, 0L)

        val poster = findViewById<ImageView>(R.id.s_poster)
        val titleView = findViewById<TextView>(R.id.s_title)
        val overview = findViewById<TextView>(R.id.s_overview)
        seasonsRow = findViewById(R.id.s_seasons)
        episodesView = findViewById(R.id.s_episodes)
        dubBtn = findViewById(R.id.s_dub)
        legBtn = findViewById(R.id.s_leg)
        playBtn = findViewById(R.id.s_play)
        stateBox = findViewById(R.id.s_state)

        titleView.text = title
        overview.text = "Carregando informações…"
        showYear(SearchCatalog.yearOf(title))
        showRating(intent.getDoubleExtra(EXTRA_RATING, 0.0))
        if (logo.isNotBlank()) {
            loadImage(poster, logo)
            softBackdrop(logo)
        }

        episodesView.adapter = adapter
        episodesView.setOnItemClickListener { _, _, pos, _ -> shownEps.getOrNull(pos)?.let { play(it) } }
        // Segurar um episódio -> marca/desmarca como assistido.
        episodesView.setOnItemLongClickListener { _, _, pos, _ ->
            shownEps.getOrNull(pos)?.let { toggleEpisode(it) }
            true
        }
        playBtn.setOnClickListener { pick?.let { p -> allEps.firstOrNull { it.channel.url == p.ep.key }?.let(::play) } }
        findViewById<View>(R.id.s_retry).setOnClickListener { loadEpisodes() }
        dubBtn.setOnClickListener { selectVariant(false) }
        legBtn.setOnClickListener { selectVariant(true) }

        val json = intent.getStringExtra(EXTRA_EPISODES)
        when {
            !json.isNullOrBlank() -> onEpisodes(EpisodeCodec.decode(json))
            seriesId > 0 -> loadEpisodes()
            else -> showState("Esta série não tem episódios", "", retry = false)
        }

        // TMDB (sinopse/nota/pôster)
        val key = AppSettings(this).tmdbApiKey
        if (key.isBlank()) { overview.text = "Dica: configure a chave do TMDB para nota e sinopse."; return }
        thread(name = "tmdb-series") {
            val r = Tmdb.fetch(key, title, isSeries = true)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (r) {
                    is Tmdb.Res.Ok -> {
                        val m = r.meta
                        if (!m.posterUrl.isNullOrBlank()) {
                            loadImage(poster, m.posterUrl)
                            com.seupainel.player.meta.PosterCache.put(title, m.posterUrl)
                            softBackdrop(m.posterUrl)
                        }
                        if (!m.backdropUrl.isNullOrBlank()) {
                            backdropIsArt = true
                            loadImage(findViewById(R.id.s_backdrop), m.backdropUrl)
                        }
                        showYear(m.year)
                        showRating(m.rating)
                        overview.text = m.overview ?: "Sinopse indisponível."
                    }
                    is Tmdb.Res.InvalidKey -> overview.text = "Chave do TMDB inválida (confira nas Configurações)."
                    else -> overview.text = "Sem sinopse para \"$title\"."
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Volta do player: atualiza os estados (o que acabou de ser assistido) e leva o foco ao próximo episódio.
        if (allEps.isNotEmpty()) {
            refresh()
            if (started) {
                started = false
                pick?.let { showSeason(it.ep.season) }
                focusPick()
            }
        }
    }

    // ---------- episódios ----------

    private fun loadEpisodes() {
        showState("Carregando episódios…", "", retry = false)
        thread(name = "series-info") {
            val result = runCatching {
                val acc = XtreamCatalog.accountFor(PlaylistStore(this), PlaylistHolder.channels.orEmpty()) ?: error("sem conta")
                XtreamApi.seriesEpisodes(acc, PlaylistStore(this).userAgent, seriesId, title, logo)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val eps = result.getOrNull()
                when {
                    eps == null -> showState(
                        "Não consegui carregar os episódios",
                        result.exceptionOrNull()?.let { XtreamApi.describe(it) }.orEmpty().replaceFirstChar { it.uppercase() },
                        retry = true,
                    )
                    eps.isEmpty() -> showState("Esta série ainda não tem episódios", "Tente de novo mais tarde.", retry = true)
                    else -> onEpisodes(eps)
                }
            }
        }
    }

    private fun onEpisodes(eps: List<SeriesGrouper.Ep>) {
        allEps = eps
        if (eps.isEmpty()) { showState("Esta série não tem episódios", "", retry = false); return }
        stateBox.visibility = View.GONE
        episodesView.visibility = View.VISIBLE
        findViewById<View>(R.id.s_hint).visibility = View.VISIBLE

        val hasDub = eps.any { !it.leg }
        val hasLeg = eps.any { it.leg }
        currentLeg = !hasDub && hasLeg      // se só tiver legendado, começa nele
        findViewById<View>(R.id.s_dubrow).visibility = if (hasDub && hasLeg) View.VISIBLE else View.GONE
        markVariant()
        buildSeasons()
        refresh()
        pick?.let { currentSeason = it.ep.season }
        showSeason(currentSeason)
        if (!focusedOnce) {
            focusedOnce = true
            focusPick()
        }
    }

    private fun showState(titleText: String, sub: String, retry: Boolean) {
        episodesView.visibility = View.GONE
        stateBox.visibility = View.VISIBLE
        findViewById<TextView>(R.id.s_state_title).text = titleText
        findViewById<TextView>(R.id.s_state_sub).apply {
            text = sub
            visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        }
        val retryBtn = findViewById<View>(R.id.s_retry)
        retryBtn.visibility = if (retry) View.VISIBLE else View.GONE
        if (retry) retryBtn.requestFocus()
        playBtn.visibility = View.GONE
        findViewById<View>(R.id.s_hint).visibility = View.GONE
    }

    private fun variantEps() = allEps.filter { it.leg == currentLeg }

    private fun refOf(e: SeriesGrouper.Ep) = WatchLogic.Ep(e.season, e.episode, e.channel.url)

    private fun snap() = Snap(resume.watchedSet(), resume.inProgress())

    /** Recalcula resumo, botão principal, pílulas e linhas a partir do que está salvo. */
    private fun refresh() {
        val snap = snap()
        val refs = variantEps().map(::refOf)
        val counts = WatchLogic.seasonCounts(refs, snap::watched)
        val seen = counts.sumOf { it.watched }
        val total = counts.sumOf { it.total }
        pick = WatchLogic.pickNext(refs, snap::watched, snap::pct, snap::touched)

        val summary = findViewById<TextView>(R.id.s_summary)
        summary.text = WatchLogic.summary(seen, total)
        summary.visibility = if (total > 0) View.VISIBLE else View.GONE
        findViewById<ProgressBar>(R.id.s_progress).apply {
            progress = if (total > 0) seen * 100 / total else 0
            visibility = if (total > 0) View.VISIBLE else View.GONE
        }
        summaries.put(WatchLogic.seriesKey(seriesId, title), seen, total)

        val p = pick
        if (p == null) {
            playBtn.visibility = View.GONE
        } else {
            playBtn.visibility = View.VISIBLE
            playBtn.text = WatchLogic.primaryLabel(p)
            val hint = findViewById<TextView>(R.id.s_play_hint)
            val item = snap.items[p.ep.key]
            val text = when (p.kind) {
                WatchLogic.PickKind.RESUME -> item?.let { WatchLogic.remainingText(it.pos, it.dur) } ?: "Em andamento"
                WatchLogic.PickKind.NEXT -> "Próximo episódio"
                WatchLogic.PickKind.REWATCH -> "Você já viu todos"
                WatchLogic.PickKind.START -> null
            }
            hint.text = text.orEmpty()
            hint.visibility = if (text == null) View.GONE else View.VISIBLE
        }
        updatePills(counts)
        adapter.update(rowsFor(shownEps, snap))
    }

    private fun rowsFor(eps: List<SeriesGrouper.Ep>, snap: Snap): List<Row> = eps.map { e ->
        val key = e.channel.url
        val pct = snap.pct(key)
        val state = WatchLogic.stateOf(snap.watched(key), pct, WatchLogic.isHighlighted(pick, refOf(e)))
        val item = snap.items[key]
        val remaining = if (state == WatchLogic.State.IN_PROGRESS && item != null) WatchLogic.remainingText(item.pos, item.dur) else null
        Row(e, state, if (item != null && item.dur > 0) pct else 0, remaining)
    }

    private fun focusPick() {
        val idx = shownEps.indexOfFirst { it.channel.url == pick?.ep?.key }
        episodesView.post {
            episodesView.requestFocus()
            if (idx >= 0) episodesView.setSelection(idx)
        }
    }

    // ---------- versão (dublado / legendado) e temporadas ----------

    private fun selectVariant(leg: Boolean) {
        if (currentLeg == leg) return
        currentLeg = leg
        markVariant()
        buildSeasons()
        refresh()
        pick?.let { currentSeason = it.ep.season }
        showSeason(currentSeason)
    }

    private fun markVariant() {
        dubBtn.text = if (!currentLeg) "● Dublado" else "Dublado"
        legBtn.text = if (currentLeg) "● Legendado" else "Legendado"
        dubBtn.isSelected = !currentLeg
        legBtn.isSelected = currentLeg
    }

    /** Cria as pílulas de temporada da versão atual; o texto (contagem) é preenchido em [updatePills]. */
    private fun buildSeasons() {
        val seasons = variantEps().map { it.season }.distinct().sorted()
        seasonsRow.removeAllViews()
        if (currentSeason !in seasons) currentSeason = seasons.firstOrNull() ?: 1
        val d = resources.displayMetrics.density
        seasons.forEach { s ->
            val chip = TextView(this, null, 0, R.style.PillChip).apply {
                tag = s
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = (8 * d).toInt() }
                setOnFocusChangeListener { _, has -> if (has) showSeason(s) }
                // OK vai para a lista de episódios; OK segurado marca a temporada inteira.
                setOnClickListener { showSeason(s); episodesView.requestFocus() }
                setOnLongClickListener { toggleSeason(s); true }
            }
            seasonsRow.addView(chip)
        }
    }

    private fun updatePills(counts: List<WatchLogic.SeasonCount>) {
        val accent = ContextCompat.getColorStateList(this, R.color.lf_accent)
        for (i in 0 until seasonsRow.childCount) {
            val chip = seasonsRow.getChildAt(i) as? TextView ?: continue
            val c = counts.firstOrNull { it.season == chip.tag } ?: continue
            chip.text = WatchLogic.seasonLabel(c)
            chip.contentDescription = WatchLogic.seasonDescription(c)
            // Concluída: o ícone de visto, além do número (nunca só cor).
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (c.complete) R.drawable.ic_check else 0, 0)
            TextViewCompat.setCompoundDrawableTintList(chip, accent)
        }
    }

    private fun showSeason(season: Int) {
        currentSeason = season
        for (i in 0 until seasonsRow.childCount) seasonsRow.getChildAt(i).isSelected = seasonsRow.getChildAt(i).tag == season
        shownEps = variantEps().filter { it.season == season }.sortedBy { it.episode }
        adapter.update(rowsFor(shownEps, snap()))
        // Posiciona no episódio do botão principal (se for desta temporada) ou no primeiro ainda não visto.
        val snap = snap()
        val idx = shownEps.indexOfFirst { it.channel.url == pick?.ep?.key }.takeIf { it >= 0 }
            ?: shownEps.indexOfFirst { !snap.watched(it.channel.url) }.coerceAtLeast(0)
        episodesView.setSelection(idx)
    }

    // ---------- marcar como visto ----------

    private fun toggleEpisode(ep: SeriesGrouper.Ep) {
        val key = ep.channel.url
        val wasWatched = snap().watched(key)
        if (wasWatched) {
            resume.unmarkWatched(key)
        } else {
            resume.setWatched(listOf(key), true)
        }
        toast("Episódio ${ep.episode} marcado como ${if (wasWatched) "não visto" else "visto"}")
        refresh()
    }

    private fun toggleSeason(season: Int) {
        val keys = variantEps().filter { it.season == season }.map { it.channel.url }
        if (keys.isEmpty()) return
        val snap = snap()
        val allWatched = keys.all { snap.watched(it) }
        if (allWatched) keys.forEach { resume.unmarkWatched(it) } else resume.setWatched(keys, true)
        toast("Temporada $season marcada como ${if (allWatched) "não vista" else "vista"}")
        refresh()
    }

    /** MENU faz o mesmo que segurar OK: no episódio marca o episódio; na pílula marca a temporada. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = currentFocus
            val season = focused?.tag as? Int
            when {
                season != null && focused.parent === seasonsRow -> { toggleSeason(season); return true }
                episodesView.hasFocus() -> {
                    shownEps.getOrNull(episodesView.selectedItemPosition)?.let { toggleEpisode(it) }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- tocar ----------

    private fun play(ep: SeriesGrouper.Ep) {
        started = true
        // Fila de autoplay: do episódio clicado em diante, na mesma versão (dub/leg).
        val ordered = variantEps().sortedWith(compareBy({ it.season }, { it.episode }))
        val startIdx = ordered.indexOfFirst { it.channel.url == ep.channel.url }.coerceAtLeast(0)
        val queue = ordered.drop(startIdx)
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, ep.channel.url)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, false)
            putExtra(PlayerActivity.EXTRA_TITLE, "$title — T${ep.season}E${ep.episode}")
            putExtra(PlayerActivity.EXTRA_LOGO, logo)
            putExtra(PlayerActivity.EXTRA_KIND, "series")
            putExtra(PlayerActivity.EXTRA_SUBTITLED, ep.leg)
            putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE_URLS, ArrayList(queue.map { it.channel.url }))
            putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE_TITLES,
                ArrayList(queue.map { "$title — T${it.season}E${it.episode}" }))
        })
    }

    // ---------- cabeçalho ----------

    private fun showYear(year: String?) {
        val v = findViewById<TextView>(R.id.s_year)
        v.text = year.orEmpty()
        v.visibility = if (year.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun showRating(rating: Double) {
        val box = findViewById<View>(R.id.s_rating)
        if (rating <= 0) { box.visibility = View.GONE; return }
        findViewById<TextView>(R.id.s_rating_text).text = "%.1f".format(rating)
        box.contentDescription = "Nota ${"%.1f".format(rating)} de 10"
        box.visibility = View.VISIBLE
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun loadImage(view: ImageView, url: String) {
        coil.Coil.imageLoader(this).enqueue(
            coil.request.ImageRequest.Builder(this).data(url).target(view).crossfade(true).build()
        )
    }

    /** Sem arte horizontal, o fundo é o cartaz bem desfocado (a tela escurece por cima). */
    private fun softBackdrop(posterUrl: String) {
        if (backdropIsArt) return
        coil.Coil.imageLoader(this).enqueue(
            coil.request.ImageRequest.Builder(this).data(posterUrl)
                .size(SoftBackdrop.WIDTH * 2, SoftBackdrop.HEIGHT * 2)
                .transformations(SoftBackdrop).target(findViewById<ImageView>(R.id.s_backdrop)).crossfade(true).build()
        )
    }

    // ---------- lista de episódios ----------

    private inner class EpisodeAdapter : BaseAdapter() {

        private var rows: List<Row> = emptyList()

        fun update(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_episode, parent, false)
            val row = rows[position]
            val info = row.ep.info
            val title = info?.title?.takeIf { it.isNotBlank() && !GENERIC_TITLE.matches(it) }
            val number = v.findViewById<TextView>(R.id.ep_number)
            val thumb = v.findViewById<ImageView>(R.id.ep_thumb)
            val titleView = v.findViewById<TextView>(R.id.ep_title)
            val plot = v.findViewById<TextView>(R.id.ep_plot)
            val bar = v.findViewById<ProgressBar>(R.id.ep_progress)
            val icon = v.findViewById<ImageView>(R.id.ep_state_icon)
            val stateText = v.findViewById<TextView>(R.id.ep_state_text)

            number.text = row.ep.episode.toString()
            titleView.text = title ?: "Episódio ${row.ep.episode}"

            val minutes = (info?.durationSec ?: 0) / 60
            val detail = listOfNotNull(
                minutes.takeIf { it > 0 }?.let { "$it min" },
                info?.plot?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            plot.text = detail
            plot.visibility = if (detail.isBlank()) View.GONE else View.VISIBLE

            thumb.dispose()
            if (info?.image.isNullOrBlank()) {
                thumb.visibility = View.GONE
            } else {
                thumb.visibility = View.VISIBLE
                coil.Coil.imageLoader(this@SeriesActivity).enqueue(
                    coil.request.ImageRequest.Builder(this@SeriesActivity).data(info?.image).target(thumb).crossfade(true).build(),
                )
            }

            bar.visibility = if (row.state == WatchLogic.State.IN_PROGRESS && row.pct > 0) View.VISIBLE else View.GONE
            bar.progress = row.pct

            // Assistido: a linha fica mais apagada, mas o estado continua legível (ícone + texto).
            val dim = if (row.state == WatchLogic.State.WATCHED) 0.55f else 1f
            listOf(number, thumb, titleView, plot).forEach { it.alpha = dim }

            val accent = ContextCompat.getColor(v.context, R.color.lf_accent)
            val text = ContextCompat.getColor(v.context, R.color.lf_text)
            when (row.state) {
                WatchLogic.State.WATCHED -> showState(icon, stateText, R.drawable.ic_check, "Assistido", accent)
                WatchLogic.State.IN_PROGRESS ->
                    showState(icon, stateText, R.drawable.ic_clock, row.remaining ?: "Em andamento", text)
                WatchLogic.State.NEXT -> showState(icon, stateText, R.drawable.ic_play, "A seguir", accent)
                WatchLogic.State.UNWATCHED -> { icon.visibility = View.GONE; stateText.text = "" }
            }
            v.setBackgroundResource(if (row.state == WatchLogic.State.NEXT) R.drawable.episode_next_bg else 0)
            v.contentDescription = WatchLogic.episodeDescription(row.ep.episode, title.orEmpty(), row.state, row.remaining)
            return v
        }

        private fun showState(icon: ImageView, text: TextView, res: Int, label: String, color: Int) {
            icon.setImageResource(res)
            icon.setColorFilter(color)
            icon.visibility = View.VISIBLE
            text.text = label
            text.setTextColor(color)
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOGO = "logo"
        const val EXTRA_EPISODES = "episodes"
        const val EXTRA_SERIES_ID = "series_id"   // série da API: os episódios são buscados aqui
        const val EXTRA_RATING = "rating"         // nota do painel (0..10), enquanto o TMDB não responde

        // "Episódio 4" como título é só ruído: o número já aparece na linha.
        private val GENERIC_TITLE = Regex("""(?i)epis[óo]dio\s*\d+""")

        /** Série da API: só o id; a tela busca os episódios e mostra carregando/erro. */
        fun intentForApi(context: Context, title: String, logo: String?, seriesId: Long, rating: Double = 0.0) =
            Intent(context, SeriesActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_LOGO, logo ?: "")
                putExtra(EXTRA_SERIES_ID, seriesId)
                putExtra(EXTRA_RATING, rating)
            }

        /** Série da lista M3U: os episódios já estão agrupados. */
        fun intentForEpisodes(context: Context, title: String, logo: String?, eps: List<SeriesGrouper.Ep>) =
            Intent(context, SeriesActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_LOGO, logo ?: "")
                putExtra(EXTRA_EPISODES, EpisodeCodec.encode(eps))
            }
    }
}
