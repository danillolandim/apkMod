package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.meta.Tmdb
import kotlin.concurrent.thread

/**
 * Tela de detalhes estilo IMDb: pôster, nota, sinopse e botão Assistir.
 * Mostra na hora o que a lista tem (logo/nome) e, se houver chave TMDB,
 * enriquece com capa de alta qualidade, nota e sinopse em 2º plano.
 */
class DetailActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val logo = intent.getStringExtra(EXTRA_LOGO).orEmpty()
        val isLive = intent.getBooleanExtra(EXTRA_IS_LIVE, false)
        val isSeries = intent.getBooleanExtra(EXTRA_IS_SERIES, false)

        val poster = findViewById<ImageView>(R.id.d_poster)
        val titleView = findViewById<TextView>(R.id.d_title)
        val meta = findViewById<TextView>(R.id.d_meta)
        val overview = findViewById<TextView>(R.id.d_overview)
        val watch = findViewById<TextView>(R.id.d_watch)
        val watchLeg = findViewById<TextView>(R.id.d_watch_leg)

        titleView.text = title
        meta.text = ""
        overview.text = "Carregando informações…"

        // Capa imediata da lista (se houver)
        if (logo.isNotBlank()) loadImage(poster, logo)

        fun go(u: String) {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_DIRECT_URL, u)
                putExtra(PlayerActivity.EXTRA_IS_LIVE, isLive)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_LOGO, logo)
                if (!isLive) putExtra(PlayerActivity.EXTRA_KIND, if (isSeries) "series" else "movie")
            })
        }

        val dubUrl = intent.getStringExtra(EXTRA_URL_DUB)?.ifBlank { null }
        val legUrl = intent.getStringExtra(EXTRA_URL_LEG)?.ifBlank { null }
        if (dubUrl != null && legUrl != null) {
            // Tem as duas versões -> dois botões.
            watch.text = "▶  Dublado"
            watch.setOnClickListener { go(dubUrl) }
            watchLeg.visibility = View.VISIBLE
            watchLeg.setOnClickListener { go(legUrl) }
        } else {
            watch.text = "▶  Assistir"
            watch.setOnClickListener { go(dubUrl ?: legUrl ?: url) }
            watchLeg.visibility = View.GONE
        }
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
                        }
                        val stars = if (m.rating > 0) "★ ${"%.1f".format(m.rating)}/10" else ""
                        meta.text = listOfNotNull(stars.ifBlank { null }, m.year).joinToString("   •   ")
                        overview.text = m.overview ?: "Sinopse indisponível."
                        if (m.title.isNotBlank()) titleView.text = m.title
                    }
                }
            }
        }
    }

    private fun loadImage(view: ImageView, url: String) {
        coil.Coil.imageLoader(this).enqueue(
            coil.request.ImageRequest.Builder(this).data(url).target(view).crossfade(true).build()
        )
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOGO = "logo"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_IS_SERIES = "is_series"
        const val EXTRA_URL_DUB = "url_dub"
        const val EXTRA_URL_LEG = "url_leg"
    }
}
