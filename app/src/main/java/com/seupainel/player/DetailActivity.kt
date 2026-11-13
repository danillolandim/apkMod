package com.seupainel.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.meta.Tmdb
import com.seupainel.player.playlist.PlaylistStore
import com.seupainel.player.search.SearchCatalog
import kotlin.concurrent.thread

/**
 * Detalhe do filme no tema Landimflix: capa, ano, nota, sinopse e as ações (assistir/continuar, recomeçar, favoritar,
 * marcar como visto, outra versão). Mostra na hora o que a lista tem (capa, nome, nota do painel) e, se houver chave
 * TMDB, enriquece com capa de alta qualidade, nota e sinopse em 2º plano.
 */
class DetailActivity : FragmentActivity() {

    private val resume by lazy { ResumeStore(this) }
    private val store by lazy { PlaylistStore(this) }

    private var mainUrl = ""
    private var versions: List<String> = emptyList()   // dublado primeiro; uma só quando não há as duas
    private var dubUrl: String? = null
    private var legUrl: String? = null
    private var playUrl = ""
    private var title = ""
    private var logo = ""
    private var isLive = false
    private var isSeries = false
    private var backdropIsArt = false

    private lateinit var backdrop: ImageView
    private lateinit var watch: TextView
    private lateinit var restart: TextView
    private lateinit var alt: TextView
    private lateinit var fav: TextView
    private lateinit var seen: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        mainUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        logo = intent.getStringExtra(EXTRA_LOGO).orEmpty()
        isLive = intent.getBooleanExtra(EXTRA_IS_LIVE, false)
        isSeries = intent.getBooleanExtra(EXTRA_IS_SERIES, false)
        val dub = intent.getStringExtra(EXTRA_URL_DUB)?.ifBlank { null }
        val leg = intent.getStringExtra(EXTRA_URL_LEG)?.ifBlank { null }
        dubUrl = dub
        legUrl = leg
        versions = if (dub != null && leg != null) listOf(dub, leg) else listOf(dub ?: leg ?: mainUrl)

        // Abre a conexão com o servidor enquanto o usuário lê a sinopse: o Assistir começa mais rápido.
        if (!isLive && mainUrl.startsWith("http", ignoreCase = true))
            com.seupainel.player.net.Net.prewarm(mainUrl, store.userAgent)

        backdrop = findViewById(R.id.d_backdrop)
        watch = findViewById(R.id.d_watch)
        restart = findViewById(R.id.d_restart)
        alt = findViewById(R.id.d_alt)
        fav = findViewById(R.id.d_fav)
        seen = findViewById(R.id.d_seen)

        val poster = findViewById<ImageView>(R.id.d_poster)
        val titleView = findViewById<TextView>(R.id.d_title)
        val overview = findViewById<TextView>(R.id.d_overview)

        titleView.text = title
        overview.text = "Carregando informações…"

        // Ano do próprio nome e nota do painel, até o TMDB responder.
        showYear(SearchCatalog.yearOf(title))
        showRating(intent.getDoubleExtra(EXTRA_RATING, 0.0))

        // Capa imediata da lista (se houver); o fundo é o cartaz desfocado até haver arte horizontal.
        if (logo.isNotBlank()) {
            loadImage(poster, logo)
            softBackdrop(logo)
        }

        watch.setOnClickListener { go(playUrl) }
        restart.setOnClickListener {
            // O player retoma pelo ponto salvo: apaga-o para recomeçar do zero.
            resume.clear(playUrl)
            resume.removeRecent(playUrl)
            go(playUrl)
        }
        alt.setOnClickListener { go(otherVersion()) }
        fav.setOnClickListener {
            val added = store.toggleFavorite(mainUrl)
            toast(if (added) "Favoritado: $title" else "Removido dos favoritos")
            refreshState()
        }
        seen.setOnClickListener {
            if (versions.any { resume.isWatched(it) }) {
                versions.forEach { resume.unmarkWatched(it) }
                toast("Marcado como não visto")
            } else {
                resume.setWatched(versions, true)
                toast("Marcado como visto")
            }
            refreshState()
        }

        refreshState()
        watch.requestFocus()

        // Enriquecimento via TMDB (se configurado)
        val key = AppSettings(this).tmdbApiKey
        if (key.isBlank()) {
            overview.text = "Dica: configure a chave do TMDB nas Configurações para ver nota e sinopse."
            return
        }
        thread(name = "tmdb") {
            val r = Tmdb.fetch(key, title, isSeries)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (r) {
                    is Tmdb.Res.InvalidKey ->
                        overview.text = "Chave do TMDB inválida. Confira em Configurações (use a 'API Key (v3)' ou o 'Read Access Token v4')."
                    is Tmdb.Res.Err ->
                        overview.text = "Não foi possível consultar o TMDB (${r.msg})."
                    is Tmdb.Res.NotFound ->
                        overview.text = "Sem informações para \"$title\" no TMDB (título pode estar diferente do original)."
                    is Tmdb.Res.Ok -> {
                        val m = r.meta
                        if (!m.posterUrl.isNullOrBlank()) {
                            loadImage(poster, m.posterUrl)
                            // Guarda para o grid usar o MESMO cartaz.
                            com.seupainel.player.meta.PosterCache.put(title, m.posterUrl)
                            softBackdrop(m.posterUrl)
                        }
                        if (!m.backdropUrl.isNullOrBlank()) {
                            backdropIsArt = true
                            loadImage(backdrop, m.backdropUrl)
                        }
                        showYear(m.year)
                        showRating(m.rating)
                        overview.text = m.overview ?: "Sinopse indisponível."
                        if (m.title.isNotBlank()) titleView.text = m.title
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Volta do player: o ponto de parada e o "visto" mudaram.
        refreshState()
    }

    private fun showYear(year: String?) {
        val v = findViewById<TextView>(R.id.d_year)
        v.text = year.orEmpty()
        v.visibility = if (year.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun showRating(rating: Double) {
        val box = findViewById<View>(R.id.d_rating)
        if (rating <= 0) { box.visibility = View.GONE; return }
        findViewById<TextView>(R.id.d_rating_text).text = "%.1f".format(rating)
        box.contentDescription = "Nota ${"%.1f".format(rating)} de 10"
        box.visibility = View.VISIBLE
    }

    private fun otherVersion(): String = versions.firstOrNull { it != playUrl } ?: playUrl

    /** Recalcula o botão principal, a barra de progresso e os botões de apoio a partir do que está salvo. */
    private fun refreshState() {
        val progress = resume.inProgress()
        // Entre dublado e legendado vale o que já tem ponto salvo; senão o dublado.
        playUrl = versions.firstOrNull { resume.get(it) > 0L } ?: versions.first()
        val pos = resume.get(playUrl)
        val dur = progress[playUrl]?.dur ?: 0L
        val watched = versions.any { resume.isWatched(it) }
        val action = WatchLogic.movieAction(pos, dur, watched)

        watch.text = action.label
        watch.contentDescription = action.label
        restart.visibility = if (pos > 0) View.VISIBLE else View.GONE

        if (versions.size > 1) {
            alt.visibility = View.VISIBLE
            alt.text = if (playUrl == dubUrl) "Assistir legendado" else "Assistir dublado"
        } else alt.visibility = View.GONE

        val box = findViewById<View>(R.id.d_progress_box)
        if (pos > 0 && dur > 0) {
            val pct = ((pos * 100) / dur).toInt().coerceIn(1, 99)
            findViewById<ProgressBar>(R.id.d_progress).progress = pct
            findViewById<TextView>(R.id.d_progress_text).text =
                listOfNotNull("Parou em ${WatchLogic.clock(pos)}", WatchLogic.remainingText(pos, dur)).joinToString(" · ")
            box.visibility = View.VISIBLE
        } else box.visibility = View.GONE

        val status = findViewById<View>(R.id.d_status)
        when {
            pos > 0 -> showStatus(status, R.drawable.ic_clock, "Em andamento")
            watched -> showStatus(status, R.drawable.ic_check, "Assistido")
            else -> status.visibility = View.GONE
        }

        val isFav = store.isFavorite(mainUrl)
        fav.text = if (isFav) "Favoritado" else "Favoritar"
        fav.setCompoundDrawablesRelativeWithIntrinsicBounds(if (isFav) R.drawable.ic_star else R.drawable.ic_star_outline, 0, 0, 0)
        seen.text = if (watched) "Marcar como não visto" else "Marcar como visto"
    }

    private fun showStatus(box: View, icon: Int, text: String) {
        findViewById<ImageView>(R.id.d_status_icon).setImageResource(icon)
        findViewById<TextView>(R.id.d_status_text).text = text
        box.contentDescription = text
        box.visibility = View.VISIBLE
    }

    private fun go(u: String) {
        // Ao vivo aberto por aqui não vem de uma lista: não pode reaproveitar a lista de um zapping anterior.
        if (isLive) com.seupainel.player.playlist.LiveSession.clear()
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, u)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, isLive)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_LOGO, logo)
            putExtra(PlayerActivity.EXTRA_SUBTITLED, !isLive && u == legUrl)
            if (!isLive) putExtra(PlayerActivity.EXTRA_KIND, if (isSeries) "series" else "movie")
        })
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
                .transformations(SoftBackdrop).target(backdrop).crossfade(true).build()
        )
    }

    companion object {
        /** [urlDub] e [urlLeg] só quando o filme tem as duas versões na lista (juntas contam como um item). */
        fun intent(
            context: Context, url: String, title: String, logo: String?, isSeries: Boolean,
            urlDub: String? = null, urlLeg: String? = null, rating: Double = 0.0,
        ) = Intent(context, DetailActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_LOGO, logo ?: "")
            putExtra(EXTRA_IS_LIVE, false)
            putExtra(EXTRA_IS_SERIES, isSeries)
            putExtra(EXTRA_URL_DUB, urlDub)
            putExtra(EXTRA_URL_LEG, urlLeg)
            putExtra(EXTRA_RATING, rating)
        }

        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOGO = "logo"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_IS_SERIES = "is_series"
        const val EXTRA_URL_DUB = "url_dub"
        const val EXTRA_URL_LEG = "url_leg"
        const val EXTRA_RATING = "rating"   // nota do painel (0..10), enquanto o TMDB não responde
    }
}
