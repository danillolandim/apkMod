package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import com.seupainel.player.meta.TmdbLists
import com.seupainel.player.playlist.ChannelListActivity
import com.seupainel.player.playlist.M3uParser
import com.seupainel.player.playlist.PlaylistHolder
import com.seupainel.player.playlist.PlaylistStore
import com.seupainel.player.playlist.XtreamCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Home fluida: barra com as seções, um destaque que acompanha o cartaz em foco e fileiras de
 * cartazes (Continuar assistindo, Adicionados recentemente, Aclamados, Populares e as
 * categorias do provedor). As seções completas abrem pelas abas.
 */
class MainActivity : FragmentActivity() {

    private class HomeRow(val title: String, val items: List<M3uParser.Channel>)

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView
    private lateinit var rowsContainer: LinearLayout
    private lateinit var hero: View
    private lateinit var heroPoster: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroOpen: Button
    private lateinit var homeEmpty: TextView

    private var heroItem: M3uParser.Channel? = null
    private var loadGen = 0
    private var listsRequested = false
    private var rendered = false
    private var focusRow = 0
    private var focusPos = 0

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))

    // Tamanho dos cartazes: proporcional à altura da tela (serve a qualquer densidade de TV).
    private val cardH by lazy { (resources.displayMetrics.heightPixels * 0.25f).toInt() }
    private val cardW by lazy { cardH * 2 / 3 }
    // Foco: leve aumento. Respeita "sem animações" do Android (acessibilidade).
    private val animate by lazy {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clockTime = findViewById(R.id.clock_time)
        clockDate = findViewById(R.id.clock_date)
        rowsContainer = findViewById(R.id.home_rows)
        hero = findViewById(R.id.hero)
        heroPoster = findViewById(R.id.hero_poster)
        heroTitle = findViewById(R.id.hero_title)
        heroMeta = findViewById(R.id.hero_meta)
        heroOpen = findViewById(R.id.hero_open)
        homeEmpty = findViewById(R.id.home_empty)

        heroPoster.layoutParams = heroPoster.layoutParams.apply {
            height = (cardH * 0.72f).toInt(); width = height * 2 / 3
        }

        applyWallpaper()

        findViewById<ImageButton>(R.id.btn_gear).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.home_tab_live).setOnClickListener { openSection("canais") }
        findViewById<View>(R.id.home_tab_movies).setOnClickListener { openSection("filmes") }
        findViewById<View>(R.id.home_tab_series).setOnClickListener { openSection("series") }
        findViewById<View>(R.id.home_tab_fav).setOnClickListener { openSection("fav") }
        heroOpen.setOnClickListener { heroItem?.let { open(it) } }

        findViewById<View>(R.id.home_tab_movies).requestFocus()
    }

    /** Abre a lista completa já na seção escolhida. */
    private fun openSection(section: String) {
        val store = PlaylistStore(this)
        if (store.playlistUrl.isNullOrBlank() && store.configUrl.isNullOrBlank()) {
            Toast.makeText(this, "Configure sua lista primeiro (engrenagem).", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        startActivity(
            Intent(this, ChannelListActivity::class.java)
                .putExtra(ChannelListActivity.EXTRA_FROM_SETTINGS, true)
                .putExtra(ChannelListActivity.EXTRA_SECTION, section)
        )
    }

    // ---------------- dados da Home ----------------

    private fun loadHome() {
        val gen = ++loadGen
        thread(name = "home-load") {
            val rows = runCatching { buildRows() }.getOrDefault(emptyList())
            runOnUiThread { if (gen == loadGen && !isFinishing) render(rows) }
        }
    }

    private fun buildRows(): List<HomeRow> {
        val store = PlaylistStore(this)
        var raw = PlaylistHolder.channels
        if (raw == null) {
            raw = store.readCache()?.let { (M3uParser.parse(it) as? M3uParser.Result.Playlist)?.channels }.orEmpty()
            if (raw.isNotEmpty()) PlaylistHolder.channels = raw
        }
        val rawList = raw.orEmpty()
        val rows = ArrayList<HomeRow>()

        // Continuar assistindo (filmes e séries já começados)
        val resume = ResumeStore(this).recents().map {
            M3uParser.Channel(name = it.title, url = it.key, group = it.kind, logo = it.logo.ifBlank { null },
                duration = 0, kind = KIND_RESUME)
        }
        if (resume.isNotEmpty()) rows.add(HomeRow("Continuar assistindo", resume.take(MAX_PER_ROW)))

        if (!AppSettings(this).useXtream) {
            XtreamCatalog.lastStatus = "API desligada nas Configurações: a Home só mostra o que você já começou."
            return rows
        }
        XtreamCatalog.lastStatus = ""
        val kinds = listOf(XtreamCatalog.Kind.MOVIE, XtreamCatalog.Kind.SERIES)
        val results = arrayOfNulls<XtreamCatalog.Section>(kinds.size)
        kinds.mapIndexed { i, k ->
            thread(name = "home-${k.tag}") { results[i] = XtreamCatalog.load(k, applicationContext, store, rawList) }
        }.forEach { it.join() }
        val movies = results[0]
        val series = results[1]

        // Notas/votos do TMDB (cache em disco; baixa em segundo plano na primeira vez)
        TmdbLists.loadCached(applicationContext)
        val tmdbKey = AppSettings(this).tmdbApiKey
        if (tmdbKey.isNotBlank() && !listsRequested) {
            listsRequested = true
            thread(name = "home-tmdb") {
                val changed = runCatching { TmdbLists.ensure(applicationContext, tmdbKey) }.getOrDefault(false)
                if (changed) runOnUiThread { if (!isFinishing) loadHome() }
            }
        }

        val movieList = movies?.channels.orEmpty()
        val seriesList = series?.channels.orEmpty()
        val recentMovies = movieList.sortedByDescending { it.added }
        val recentSeries = seriesList.sortedByDescending { it.added }

        fun entry(c: M3uParser.Channel) =
            if (c.tmdbId > 0) TmdbLists.entry(tv = c.kind == XtreamCatalog.KIND_SERIES, id = c.tmdbId) else null

        fun acclaimed(list: List<M3uParser.Channel>) = list.mapNotNull { c ->
            entry(c)?.takeIf { !it.rating.isNaN() && it.rating >= ACCLAIMED_MIN_RATING && it.votes >= ACCLAIMED_MIN_VOTES }
                ?.let { c to it.rating }
        }.sortedByDescending { it.second }.map { it.first }

        fun popular(list: List<M3uParser.Channel>) = list.mapNotNull { c ->
            entry(c)?.takeIf { it.votes >= POPULAR_MIN_VOTES }?.let { c to it.votes }
        }.sortedByDescending { it.second }.map { it.first }

        fun add(title: String, items: List<M3uParser.Channel>) {
            if (items.isNotEmpty()) rows.add(HomeRow(title, items.take(MAX_PER_ROW)))
        }
        add("Filmes adicionados recentemente", recentMovies)
        add("Séries adicionadas recentemente", recentSeries)
        add("Filmes aclamados", acclaimed(movieList))
        add("Séries aclamadas", acclaimed(seriesList))
        add("Filmes populares", popular(movieList))
        add("Séries populares", popular(seriesList))
        // Primeiras categorias do provedor (Cinema, Lançamentos, plataformas…), na ordem do painel
        movies?.categoryOrder.orEmpty().take(CATEGORY_ROWS).forEach { cat ->
            add(cat.substringAfter(" | ", cat), recentMovies.filter { it.group == cat })
        }
        return rows
    }

    // ---------------- desenho ----------------

    private fun render(rows: List<HomeRow>) {
        // 1ª carga: leva o foco ao primeiro cartaz se o usuário ainda está na aba padrão.
        // Depois: só restaura o foco se ele já estava nas fileiras (não rouba o foco das abas).
        val hadFocus = if (!rendered) currentFocus == null || currentFocus?.id == R.id.home_tab_movies
                       else currentFocus == null || rowsContainer.hasFocus()
        rowsContainer.removeAllViews()
        if (rows.isEmpty()) {
            hero.visibility = View.GONE
            homeEmpty.visibility = View.VISIBLE
            homeEmpty.text = XtreamCatalog.lastStatus.ifBlank { "Nada para mostrar ainda. Abra uma seção acima." }
            return
        }
        homeEmpty.visibility = View.GONE
        hero.visibility = View.VISIBLE
        val titleFont = ResourcesCompat.getFont(this, R.font.rajdhani_bold)
        val views = ArrayList<HorizontalGridView>()

        rows.forEachIndexed { ri, row ->
            rowsContainer.addView(TextView(this).apply {
                text = row.title
                typeface = titleFont
                textSize = 17f
                setTextColor(ResourcesCompat.getColor(resources, R.color.lf_text, theme))
                setPadding(dp(4), dp(if (ri == 0) 0 else 10), 0, dp(2))
            })
            val grid = HorizontalGridView(this).apply {
                setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
                setItemSpacing(dp(10))
                clipChildren = false
                clipToPadding = false
                setPadding(dp(4), dp(6), dp(4), dp(8))   // espaço para o aumento e o anel de foco
                adapter = CardAdapter(row.items, ri)
            }
            views.add(grid)
            rowsContainer.addView(grid, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        rendered = true
        val r = focusRow.coerceIn(0, rows.size - 1)
        val p = focusPos.coerceIn(0, rows[r].items.size - 1)
        showHero(rows[r].items[p])
        if (hadFocus) views[r].post { views[r].selectedPosition = p; views[r].requestFocus() }
    }

    private fun metaOf(ch: M3uParser.Channel): String = when (ch.kind) {
        KIND_RESUME -> if (ch.group == "series") "Continuar assistindo • Série" else "Continuar assistindo • Filme"
        XtreamCatalog.KIND_SERIES -> listOf("Série", ch.group.substringAfter(" | ", ch.group).takeIf { it.isNotBlank() })
            .filterNotNull().joinToString(" • ") + ratingText(ch)
        else -> ch.group.substringAfter(" | ", ch.group) + ratingText(ch)
    }

    private fun ratingText(ch: M3uParser.Channel) = if (ch.rating > 0) "  •  ★ ${"%.1f".format(ch.rating)}" else ""

    private fun posterUrl(url: String) =
        url.replace("/t/p/w500", "/t/p/w342").replace("/t/p/w600_and_h900_bestv2", "/t/p/w342")

    private fun showHero(ch: M3uParser.Channel) {
        heroItem = ch
        heroTitle.text = ch.name
        heroMeta.text = metaOf(ch)
        heroOpen.text = when (ch.kind) {
            KIND_RESUME -> "Continuar"
            XtreamCatalog.KIND_SERIES -> "Ver episódios"
            else -> "Ver detalhes"
        }
        val logo = ch.logo
        if (logo.isNullOrBlank()) heroPoster.setImageDrawable(null)
        else coil.Coil.imageLoader(this).enqueue(
            coil.request.ImageRequest.Builder(this).data(posterUrl(logo)).target(heroPoster).build())
    }

    /** Clique no cartaz (ou no botão do destaque). */
    private fun open(ch: M3uParser.Channel) {
        when (ch.kind) {
            KIND_RESUME -> startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_DIRECT_URL, ch.url)
                putExtra(PlayerActivity.EXTRA_IS_LIVE, false)
                putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
                putExtra(PlayerActivity.EXTRA_LOGO, ch.logo ?: "")
                putExtra(PlayerActivity.EXTRA_KIND, ch.group)
            })
            XtreamCatalog.KIND_SERIES ->
                SeriesLauncher.open(this, PlaylistStore(this), ch.name, ch.logo, ch.apiId)
            else -> startActivity(Intent(this, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_URL, ch.url)
                putExtra(DetailActivity.EXTRA_TITLE, ch.name)
                putExtra(DetailActivity.EXTRA_LOGO, ch.logo ?: "")
                putExtra(DetailActivity.EXTRA_IS_LIVE, false)
                putExtra(DetailActivity.EXTRA_IS_SERIES, false)
            })
        }
    }

    private class CardVH(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.card_poster)
        val title: TextView = v.findViewById(R.id.card_title)
        val rating: TextView = v.findViewById(R.id.card_rating)
    }

    private inner class CardAdapter(val items: List<M3uParser.Channel>, val rowIdx: Int) :
        RecyclerView.Adapter<CardVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH {
            val v = layoutInflater.inflate(R.layout.item_home_card, parent, false)
            v.layoutParams = RecyclerView.LayoutParams(cardW, cardH)
            return CardVH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: CardVH, position: Int) {
            val ch = items[position]
            h.title.text = ch.name
            if (ch.rating > 0) { h.rating.text = "★ ${"%.1f".format(ch.rating)}"; h.rating.visibility = View.VISIBLE }
            else h.rating.visibility = View.GONE
            val logo = ch.logo
            if (logo.isNullOrBlank()) h.poster.setImageDrawable(null)
            else coil.Coil.imageLoader(this@MainActivity).enqueue(
                coil.request.ImageRequest.Builder(this@MainActivity)
                    .data(posterUrl(logo)).target(h.poster).crossfade(animate).build())
            h.itemView.setOnClickListener { open(ch) }
            h.itemView.setOnFocusChangeListener { v, focused ->
                val scale = if (focused && animate) 1.06f else 1f
                v.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
                if (focused) {
                    focusRow = rowIdx
                    focusPos = h.adapterPosition.coerceAtLeast(0)
                    showHero(ch)
                }
            }
        }
    }

    // ---------------- relógio e fundo ----------------

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clockTime.text = timeFmt.format(now)
            clockDate.text = dateFmt.format(now).replaceFirstChar { it.uppercase() }
            ui.postDelayed(this, 10_000)
        }
    }

    private fun applyWallpaper() {
        val s = AppSettings(this)
        val bg = findViewById<ImageView>(R.id.bg_image)
        // Se há uma URL preenchida, usa a imagem; enquanto carrega / se falhar, mostra o tema vetorial.
        if (s.wallpaperUrl.isNotBlank()) {
            bg.setImageResource(resIdOf(AppSettings.wallpaperDrawable(s.wallpaper)))
            coil.Coil.imageLoader(this).enqueue(
                coil.request.ImageRequest.Builder(this)
                    .data(s.wallpaperUrl).target(bg).crossfade(true)
                    .error(resIdOf(AppSettings.wallpaperDrawable(s.wallpaper)))
                    .build()
            )
        } else {
            bg.setImageResource(resIdOf(AppSettings.wallpaperDrawable(s.wallpaper)))
        }
    }

    private fun resIdOf(name: String): Int =
        resources.getIdentifier(name, "drawable", packageName).let { if (it != 0) it else R.drawable.wallpaper }

    override fun onResume() {
        super.onResume()
        applyWallpaper()   // reflete mudança feita nas Configurações
        ui.post(tick)
        loadHome()         // atualiza "Continuar assistindo" ao voltar de um vídeo
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    companion object {
        private const val KIND_RESUME = "resume"
        private const val MAX_PER_ROW = 30
        private const val CATEGORY_ROWS = 6
        private const val ACCLAIMED_MIN_RATING = 7.5
        private const val ACCLAIMED_MIN_VOTES = 20
        private const val POPULAR_MIN_VOTES = 30
    }
}
