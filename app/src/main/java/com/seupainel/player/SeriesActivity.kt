package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.meta.Tmdb
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Tela da série: pôster + sinopse (TMDB), seletor de temporada e lista de
 * episódios. Selecionar um episódio abre o player.
 *
 * Recebe os episódios já agrupados via EXTRA_EPISODES (JSON).
 */
class SeriesActivity : FragmentActivity() {

    private data class Ep(val season: Int, val episode: Int, val name: String, val url: String)

    private lateinit var episodesView: ListView
    private var allEps: List<Ep> = emptyList()
    private var seasons: List<Int> = emptyList()
    private var shownEps: List<Ep> = emptyList()
    private var title: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series)

        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val logo = intent.getStringExtra(EXTRA_LOGO).orEmpty()
        allEps = parseEpisodes(intent.getStringExtra(EXTRA_EPISODES))

        val poster = findViewById<ImageView>(R.id.s_poster)
        val titleView = findViewById<TextView>(R.id.s_title)
        val meta = findViewById<TextView>(R.id.s_meta)
        val overview = findViewById<TextView>(R.id.s_overview)
        val seasonSpin = findViewById<Spinner>(R.id.s_season)
        episodesView = findViewById(R.id.s_episodes)

        titleView.text = title
        overview.text = "Carregando informações…"
        if (logo.isNotBlank()) loadImage(poster, logo)

        seasons = allEps.map { it.season }.distinct().sorted()
        seasonSpin.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            seasons.map { "Temporada $it" },
        )
        seasonSpin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                showSeason(seasons.getOrElse(pos) { seasons.firstOrNull() ?: 1 })
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        if (seasons.isNotEmpty()) showSeason(seasons.first())

        episodesView.setOnItemClickListener { _, _, pos, _ -> play(shownEps[pos]) }

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
                        }
                        val stars = if (m.rating > 0) "★ ${"%.1f".format(m.rating)}/10" else ""
                        meta.text = listOfNotNull(stars.ifBlank { null }, m.year).joinToString("   •   ")
                        overview.text = m.overview ?: "Sinopse indisponível."
                    }
                    is Tmdb.Res.InvalidKey -> overview.text = "Chave do TMDB inválida (confira nas Configurações)."
                    else -> overview.text = "Sem sinopse para \"$title\"."
                }
            }
        }
    }

    private fun showSeason(season: Int) {
        shownEps = allEps.filter { it.season == season }.sortedBy { it.episode }
        episodesView.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            shownEps.map { "Episódio ${it.episode}" },
        )
    }

    private fun play(ep: Ep) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, ep.url)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, false)
            putExtra(PlayerActivity.EXTRA_TITLE, "$title — T${ep.season}E${ep.episode}")
        })
    }

    private fun loadImage(view: ImageView, url: String) {
        coil.Coil.imageLoader(this).enqueue(
            coil.request.ImageRequest.Builder(this).data(url).target(view).crossfade(true).build()
        )
    }

    private fun parseEpisodes(json: String?): List<Ep> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Ep(o.optInt("s", 1), o.optInt("e", 1), o.optString("n"), o.optString("u"))
            }
        } catch (_: Exception) { emptyList() }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOGO = "logo"
        const val EXTRA_EPISODES = "episodes"

        fun episodesToJson(eps: List<com.seupainel.player.playlist.SeriesGrouper.Ep>): String {
            val arr = JSONArray()
            eps.forEach { e ->
                arr.put(JSONObject()
                    .put("s", e.season).put("e", e.episode)
                    .put("n", e.channel.name).put("u", e.channel.url))
            }
            return arr.toString()
        }
    }
}
