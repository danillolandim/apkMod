package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.meta.Tmdb
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Tela da série (estilo Apple TV): pôster + sinopse (TMDB), alternância
 * Dublado/Legendado, abas de temporada e a lista de episódios abaixo.
 * Recebe os episódios já agrupados via EXTRA_EPISODES (JSON).
 */
class SeriesActivity : FragmentActivity() {

    private data class Ep(val season: Int, val episode: Int, val name: String, val url: String, val leg: Boolean)

    private lateinit var episodesView: ListView
    private lateinit var seasonsRow: LinearLayout
    private lateinit var dubBtn: TextView
    private lateinit var legBtn: TextView

    private var allEps: List<Ep> = emptyList()
    private var shownEps: List<Ep> = emptyList()
    private var currentLeg = false
    private var currentSeason = 1
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
        seasonsRow = findViewById(R.id.s_seasons)
        episodesView = findViewById(R.id.s_episodes)
        dubBtn = findViewById(R.id.s_dub)
        legBtn = findViewById(R.id.s_leg)

        titleView.text = title
        overview.text = "Carregando informações…"
        if (logo.isNotBlank()) loadImage(poster, logo)

        episodesView.setOnItemClickListener { _, _, pos, _ -> play(shownEps[pos]) }

        val hasDub = allEps.any { !it.leg }
        val hasLeg = allEps.any { it.leg }
        currentLeg = !hasDub && hasLeg      // se só tiver legendado, começa nele
        if (hasDub && hasLeg) {
            findViewById<View>(R.id.s_dubrow).visibility = View.VISIBLE
            dubBtn.setOnClickListener { selectVariant(false) }
            legBtn.setOnClickListener { selectVariant(true) }
        }
        markVariant()
        buildSeasons()

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

    private fun selectVariant(leg: Boolean) {
        if (currentLeg == leg) return
        currentLeg = leg
        markVariant()
        buildSeasons()
    }

    private fun markVariant() {
        dubBtn.text = if (!currentLeg) "● Dublado" else "Dublado"
        legBtn.text = if (currentLeg) "● Legendado" else "Legendado"
    }

    /** Monta as abas de temporada da versão atual (dublado/legendado). */
    private fun buildSeasons() {
        val seasons = allEps.filter { it.leg == currentLeg }.map { it.season }.distinct().sorted()
        seasonsRow.removeAllViews()
        if (currentSeason !in seasons) currentSeason = seasons.firstOrNull() ?: 1
        val d = resources.displayMetrics.density
        seasons.forEach { s ->
            val chip = TextView(this, null, 0, R.style.SeasonChip).apply {
                text = "${s}ª temporada"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (12 * d).toInt() }
                setOnFocusChangeListener { _, has -> if (has) showSeason(s) }
                setOnClickListener { showSeason(s); episodesView.requestFocus() }
            }
            seasonsRow.addView(chip)
        }
        showSeason(currentSeason)
    }

    private fun showSeason(season: Int) {
        currentSeason = season
        shownEps = allEps.filter { it.leg == currentLeg && it.season == season }.sortedBy { it.episode }
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
            putExtra(PlayerActivity.EXTRA_LOGO, intent.getStringExtra(EXTRA_LOGO).orEmpty())
            putExtra(PlayerActivity.EXTRA_KIND, "series")
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
                Ep(o.optInt("s", 1), o.optInt("e", 1), o.optString("n"), o.optString("u"), o.optBoolean("l", false))
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
                    .put("n", e.channel.name).put("u", e.channel.url).put("l", e.leg))
            }
            return arr.toString()
        }
    }
}
