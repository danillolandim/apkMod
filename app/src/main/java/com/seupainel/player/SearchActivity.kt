package com.seupainel.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.HorizontalGridView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import com.seupainel.player.meta.Guide
import com.seupainel.player.meta.PosterCache
import com.seupainel.player.playlist.LiveCatalog
import com.seupainel.player.playlist.LiveSession
import com.seupainel.player.playlist.PlaylistStore
import com.seupainel.player.search.DubLegGroup
import com.seupainel.player.search.SearchCatalog
import com.seupainel.player.search.SearchHistory
import com.seupainel.player.search.SeriesHit
import com.seupainel.player.search.Snapshot
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Busca global (Filmes, Séries e Canais de uma vez) pensada para controle remoto: teclado na própria tela, à
 * esquerda, e os resultados à direita em fileiras por tipo. Não há campo de texto, então o teclado do sistema não abre.
 *
 * Foco: letra inicial "A"; → do teclado vai para os resultados; ← do primeiro cartão volta ao teclado;
 * Voltar sai dos resultados para o teclado e, do teclado, sai da busca.
 */
class SearchActivity : FragmentActivity() {

    private class Card(
        val title: String,
        val meta: String,
        val image: String?,
        val rating: Double,
        val description: String,
        val mark: () -> WatchLogic.Mark,
        val open: () -> Unit,
    )

    private class Row(val total: Int, val cards: List<Card>)

    private class Found(val movies: Row, val series: Row, val live: Row) {
        val isEmpty: Boolean get() = movies.total + series.total + live.total == 0
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search").apply { isDaemon = true } }
    private val generation = AtomicInteger()
    private val history by lazy { SearchHistory(this) }
    private val marks by lazy { WatchMarks(this).also { it.refresh() } }

    private var query = ""
    @Volatile private var snapshot: Snapshot? = null
    private var loadError: String? = null
    private var lastKey: View? = null
    private var keyboardStart: View? = null

    private lateinit var queryView: TextView
    private lateinit var statusView: TextView
    private lateinit var keys: GridLayout
    private lateinit var right: View
    private lateinit var scroll: ScrollView
    private lateinit var idle: View
    private lateinit var recents: LinearLayout
    private lateinit var recentTitle: View
    private lateinit var recentHint: View
    private lateinit var message: View
    private lateinit var msgIcon: ImageView
    private lateinit var msgTitle: TextView
    private lateinit var msgSub: TextView
    private lateinit var retry: View

    private class Section(val box: View, val title: TextView, val grid: HorizontalGridView, val adapter: CardAdapter)

    private lateinit var movies: Section
    private lateinit var series: Section
    private lateinit var live: Section

    private val searchNow = Runnable { runSearch() }

    // Voz: o app escuta por conta própria e escreve no campo da busca. A tela de voz do sistema (com o campo
    // "Search" dela) só entra quando o aparelho não deixa o app escutar.
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var micReady = false
    private var voiceKey: TextView? = null

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else startSystemVoice()
    }

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (r.resultCode == RESULT_OK && !said.isNullOrBlank()) {
            setQuery(said.take(MAX_QUERY))
            ui.removeCallbacks(searchNow)
            runSearch()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        queryView = findViewById(R.id.s_query)
        statusView = findViewById(R.id.s_status)
        keys = findViewById(R.id.s_keys)
        right = findViewById(R.id.s_right)
        scroll = findViewById(R.id.s_scroll)
        idle = findViewById(R.id.s_idle)
        recents = findViewById(R.id.s_recents)
        recentTitle = findViewById(R.id.s_recent_title)
        recentHint = findViewById(R.id.s_recent_hint)
        message = findViewById(R.id.s_message)
        msgIcon = findViewById(R.id.s_msg_icon)
        msgTitle = findViewById(R.id.s_msg_title)
        msgSub = findViewById(R.id.s_msg_sub)
        retry = findViewById(R.id.s_retry)
        retry.setOnClickListener { loadCatalog() }

        movies = section(R.id.sec_movies, R.id.title_movies, R.id.row_movies, live = false)
        series = section(R.id.sec_series, R.id.title_series, R.id.row_series, live = false)
        live = section(R.id.sec_live, R.id.title_live, R.id.row_live, live = true)

        buildKeyboard()
        showIdle()
        keyboardStart?.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (listening) stopListening()
                else if (focusInRight()) focusKeyboard() else finish()
            }
        })

        loadCatalog()
    }

    override fun onResume() {
        super.onResume()
        if (query.isBlank()) renderRecents()
        // Voltou do player ou do detalhe: os selos de assistido dos cartões podem ter mudado.
        marks.refresh()
        listOf(movies, series).forEach { it.adapter.notifyDataSetChanged() }
    }

    override fun onPause() {
        super.onPause()
        if (listening) stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        recognizer = null
        ui.removeCallbacksAndMessages(null)
        generation.incrementAndGet()
        worker.shutdownNow()
    }

    // ---------- catálogo ----------

    private fun loadCatalog() {
        loadError = null
        statusView.text = "Preparando a busca…"
        if (query.isNotBlank()) showMessage("Carregando o catálogo…", "A busca começa sozinha assim que ele terminar.", retry = false)
        thread(name = "search-catalog") {
            val out = SearchCatalog.load(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (out) {
                    is SearchCatalog.Outcome.Ready -> {
                        snapshot = out.snapshot
                        statusView.text = ""
                        if (query.isNotBlank()) runSearch()
                    }
                    is SearchCatalog.Outcome.Failed -> {
                        loadError = out.reason
                        statusView.text = ""
                        if (query.isNotBlank()) showCatalogError()
                    }
                }
            }
        }
    }

    private fun showCatalogError() =
        showMessage("Não consegui carregar o catálogo", loadError.orEmpty().replaceFirstChar { it.uppercase() }, retry = true)

    // ---------- teclado ----------

    private val keyRows = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ1234", "567890")

    private fun buildKeyboard() {
        keys.removeAllViews()
        keyRows.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                val key = addKey(r, c, 1, ch.toString(), null, ch.toString()) { type(ch.lowercaseChar()) }
                if (r == 0 && c == 0) keyboardStart = key
            }
        }
        val last = keyRows.size
        addKey(last, 0, 2, "Espaço", null, "Espaço") { type(' ') }
        addKey(last, 2, 2, "Apagar", R.drawable.ic_backspace, "Apagar") { backspace() }
        addKey(last, 4, 2, "Limpar", null, "Limpar tudo") { setQuery("") }
        if (voiceAvailable()) voiceKey = addKey(last + 1, 0, 6, "Falar", R.drawable.ic_mic, "Falar o nome") { startVoice() } as TextView
    }

    private fun addKey(row: Int, col: Int, span: Int, label: String, icon: Int?, description: String, action: () -> Unit): View {
        val key = TextView(this, null, 0, R.style.SearchKey).apply {
            text = label
            contentDescription = description
            if (icon != null) {
                val d: Drawable? = ContextCompat.getDrawable(context, icon)?.mutate()
                d?.setTint(ContextCompat.getColor(context, R.color.lf_text))
                setCompoundDrawablesWithIntrinsicBounds(d, null, null, null)
                compoundDrawablePadding = dp(6)
            }
            if (label.length > 1) textSize = 15f
            setOnClickListener { lastKey = it; action() }
            setOnFocusChangeListener { v, has -> if (has) lastKey = v }
            // → da borda direita do teclado entra nos resultados (ou fica onde está, se não há nada para focar).
            if (col + span == KEY_COLUMNS) setOnKeyListener { _, code, ev ->
                code == KeyEvent.KEYCODE_DPAD_RIGHT && ev.action == KeyEvent.ACTION_DOWN && run { focusResults(); true }
            }
        }
        val lp = GridLayout.LayoutParams(GridLayout.spec(row), GridLayout.spec(col, span, GridLayout.FILL, 1f)).apply {
            width = 0
            height = dp(40)
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
        keys.addView(key, lp)
        return key
    }

    private fun type(c: Char) {
        if (query.length >= MAX_QUERY) return
        if (c == ' ' && (query.isEmpty() || query.endsWith(' '))) return
        setQuery(query + c)
    }

    private fun backspace() {
        if (query.isNotEmpty()) setQuery(query.dropLast(1))
    }

    private fun setQuery(q: String) {
        if (listening) stopListening()
        query = q
        queryView.text = q
        queryView.contentDescription = if (q.isEmpty()) "Campo de busca vazio" else "Busca: $q"
        ui.removeCallbacks(searchNow)
        if (q.isBlank()) {
            generation.incrementAndGet()
            statusView.text = if (snapshot == null && loadError == null) "Preparando a busca…" else ""
            showIdle()
        } else {
            ui.postDelayed(searchNow, DEBOUNCE_MS)
        }
    }

    private fun speechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga o que quer assistir")

    private fun canListenInApp() = SpeechRecognizer.isRecognitionAvailable(this)

    private fun voiceAvailable() = canListenInApp() || speechIntent().resolveActivity(packageManager) != null

    /** "Falar" de novo enquanto escuta cancela. */
    private fun startVoice() {
        when {
            listening -> stopListening()
            !canListenInApp() -> startSystemVoice()
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** A tela de voz do próprio aparelho: reserva para quando o app não consegue escutar sozinho. */
    private fun startSystemVoice() {
        try {
            speech.launch(speechIntent())
        } catch (_: Exception) {
            Toast.makeText(this, "Este aparelho não tem reconhecimento de voz.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun listen() {
        try {
            val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(voiceListener)
                recognizer = it
            }
            listening = true
            micReady = false
            showListening(true)
            r.startListening(
                speechIntent()
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName),
            )
        } catch (_: Exception) {
            endListening()
            startSystemVoice()
        }
    }

    private fun stopListening() {
        recognizer?.cancel()
        endListening()
    }

    private fun endListening() {
        listening = false
        showListening(false)
    }

    /** Enquanto escuta, o campo da busca mostra o que está sendo dito; ao parar, volta ao texto de antes. */
    private fun showListening(on: Boolean) {
        queryView.text = if (on) "" else query
        queryView.hint = if (on) "Preparando o microfone…" else "Digite o nome…"
        voiceKey?.let { key ->
            key.text = if (on) "Ouvindo…" else "Falar"
            key.contentDescription = if (on) "Ouvindo. Aperte para cancelar." else "Falar o nome"
            key.compoundDrawables[0]?.setTint(ContextCompat.getColor(this, if (on) R.color.lf_accent else R.color.lf_text))
        }
    }

    private fun firstHeard(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private val voiceListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!listening) return
            micReady = true
            queryView.hint = "Fale agora…"
            window.decorView.announceForAccessibility("Fale agora")
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (listening) firstHeard(partialResults)?.let { queryView.text = it.take(MAX_QUERY) }
        }

        override fun onResults(results: Bundle?) {
            if (!listening) return
            endListening()
            val said = firstHeard(results)
            if (said == null) {
                Toast.makeText(this@SearchActivity, "Não entendi. Tente de novo.", Toast.LENGTH_SHORT).show()
                return
            }
            setQuery(said.take(MAX_QUERY))
            ui.removeCallbacks(searchNow)
            runSearch()
        }

        override fun onError(error: Int) {
            if (!listening) return
            val ready = micReady
            endListening()
            when {
                error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    Toast.makeText(this@SearchActivity, "Não ouvi nada. Tente de novo.", Toast.LENGTH_SHORT).show()
                // O microfone nem chegou a abrir: este aparelho não deixa o app escutar, usa a tela de voz dele.
                !ready || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> startSystemVoice()
                else -> Toast.makeText(this@SearchActivity, "A voz falhou. Tente de novo.", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Teclado físico: letras, números e espaço digitam; Backspace apaga; SEARCH/microfone abre a voz. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when {
            keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> { backspace(); return true }
            keyCode == KeyEvent.KEYCODE_SEARCH && voiceAvailable() -> { startVoice(); return true }
            event.isPrintingKey -> {
                val c = event.unicodeChar.takeIf { it > 0 }?.toChar()
                if (c != null && (c.isLetterOrDigit() || c == ' ')) { type(c.lowercaseChar()); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- foco ----------

    private fun focusKeyboard() {
        (lastKey ?: keyboardStart)?.requestFocus()
    }

    private fun focusInRight(): Boolean {
        var v: View? = currentFocus
        while (v != null) {
            if (v === right) return true
            v = v.parent as? View
        }
        return false
    }

    private fun focusResults(): Boolean {
        if (scroll.visibility == View.VISIBLE) {
            val first = listOf(movies, series, live).firstOrNull { it.box.visibility == View.VISIBLE }
            return first?.grid?.requestFocus() ?: false
        }
        if (idle.visibility == View.VISIBLE && recents.childCount > 0) return recents.getChildAt(0).requestFocus()
        if (message.visibility == View.VISIBLE && retry.visibility == View.VISIBLE) return retry.requestFocus()
        return false
    }

    // ---------- busca ----------

    private fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) { showIdle(); return }
        val snap = snapshot
        if (snap == null) {
            if (loadError != null) showCatalogError()
            else showMessage("Carregando o catálogo…", "A busca começa sozinha assim que ele terminar.", retry = false)
            return
        }
        val gen = generation.incrementAndGet()
        statusView.text = "Buscando…"
        worker.execute {
            // Exceção solta nesta thread mataria o app inteiro: vira mensagem na tela, com o motivo.
            val found = try {
                find(snap, q) { generation.get() != gen } ?: return@execute
            } catch (e: Exception) {
                runOnUiThread {
                    if (gen == generation.get() && !isFinishing && !isDestroyed) {
                        statusView.text = ""
                        showMessage("A busca falhou", "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(160), retry = false)
                    }
                }
                return@execute
            }
            runOnUiThread { if (gen == generation.get() && !isFinishing && !isDestroyed) show(q, found) }
        }
    }

    private fun find(snap: Snapshot, q: String, cancelled: () -> Boolean): Found? {
        val m = snap.movieIndex.search(q, ROW_LIMIT, cancelled) ?: return null
        val s = snap.seriesIndex.search(q, ROW_LIMIT, cancelled) ?: return null
        val l = snap.liveIndex.search(q, ROW_LIMIT, cancelled) ?: return null
        return Found(
            Row(m.total, m.top.map { movieCard(snap.movies[it], q) }),
            Row(s.total, s.top.map { seriesCard(snap.series[it], q) }),
            Row(l.total, l.top.map { liveCard(snap, it, q) }),
        )
    }

    private fun movieCard(g: DubLegGroup, q: String): Card {
        val ch = g.rep
        val fromApi = ch.kind != null
        val image = (if (fromApi) ch.logo else PosterCache.get(g.title) ?: ch.logo)?.takeIf { it.isNotBlank() }
        val meta = metaOf(g.title, if (fromApi) ch.group else "")
        val urls = listOfNotNull(ch.url, g.dub?.url, g.leg?.url).distinct()
        return Card(g.title, meta, image, ch.rating, describe("Filme", g.title, meta, ch.rating), { marks.forMovie(urls) }) { openMovie(g, q) }
    }

    private fun seriesCard(h: SeriesHit, q: String): Card {
        val s = h.series
        val fromApi = s.apiId > 0
        val image = (if (fromApi) s.logo else PosterCache.get(s.title) ?: s.logo)?.takeIf { it.isNotBlank() }
        val meta = metaOf(s.title, if (fromApi) h.group else "")
        return Card(
            s.title, meta, image, s.rating, describe("Série", s.title, meta, s.rating),
            { marks.forSeries(s.apiId, s.title) },
        ) { openSeries(h, q) }
    }

    private fun liveCard(snap: Snapshot, index: Int, q: String): Card {
        val c = snap.live.channels[index]
        // Só o que já está em cache: a busca nunca pede programação à rede.
        val now = Guide.nowText(c.tvgId, c.apiId).ifBlank { c.group }
        return Card(
            c.name, now, c.logo.ifBlank { null }, 0.0, describe("Canal", c.name, now, 0.0),
            { WatchLogic.Mark(false, 0) },
        ) { openLive(snap, index, q) }
    }

    private fun metaOf(title: String, group: String): String =
        listOfNotNull(SearchCatalog.yearOf(title), SearchCatalog.categoryOf(group).ifBlank { null }).joinToString(" • ")

    private fun describe(kind: String, title: String, meta: String, rating: Double): String =
        listOfNotNull(
            "$kind $title", meta.ifBlank { null },
            rating.takeIf { it > 0 }?.let { "nota ${"%.1f".format(it)}" },
        ).joinToString(", ")

    // ---------- resultados na tela ----------

    private fun section(boxId: Int, titleId: Int, gridId: Int, live: Boolean): Section {
        val grid = findViewById<HorizontalGridView>(gridId)
        val adapter = CardAdapter(live)
        grid.adapter = adapter
        grid.setItemSpacing(dp(10))
        val box = findViewById<View>(boxId)
        return Section(box, findViewById(titleId), grid, adapter)
    }

    private fun show(q: String, f: Found) {
        statusView.text = ""
        val hadFocus = focusInRight()
        if (f.isEmpty) {
            showMessage("Nada encontrado para \"$q\"", "Confira a grafia ou tente com menos palavras.", retry = false)
            window.decorView.announceForAccessibility("Nada encontrado para $q")
            if (hadFocus) focusKeyboard()
            return
        }
        bind(movies, "Filmes", f.movies)
        bind(series, "Séries", f.series)
        bind(live, "Canais", f.live)
        scroll.visibility = View.VISIBLE
        idle.visibility = View.GONE
        message.visibility = View.GONE
        scroll.scrollTo(0, 0)
        window.decorView.announceForAccessibility(
            "Filmes ${f.movies.total}, séries ${f.series.total}, canais ${f.live.total}",
        )
        // Os resultados trocaram debaixo do foco (digitou no teclado físico): recoloca o foco nos novos.
        if (hadFocus) scroll.post { if (!focusResults()) focusKeyboard() }
    }

    private fun bind(s: Section, name: String, row: Row) {
        s.box.visibility = if (row.total == 0) View.GONE else View.VISIBLE
        s.title.text = if (row.total > row.cards.size) "$name · ${row.total} (primeiros ${row.cards.size})" else "$name · ${row.total}"
        s.adapter.submit(row.cards)
        s.grid.scrollToPosition(0)
    }

    private fun showIdle() {
        scroll.visibility = View.GONE
        message.visibility = View.GONE
        idle.visibility = View.VISIBLE
        renderRecents()
    }

    private fun showMessage(title: String, sub: String, retry: Boolean) {
        scroll.visibility = View.GONE
        idle.visibility = View.GONE
        message.visibility = View.VISIBLE
        msgTitle.text = title
        msgSub.text = sub
        msgSub.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        this.retry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    // ---------- buscas recentes ----------

    private fun renderRecents() {
        val terms = history.all()
        recents.removeAllViews()
        val has = terms.isNotEmpty()
        recentTitle.visibility = if (has) View.VISIBLE else View.GONE
        recentHint.visibility = if (has) View.VISIBLE else View.GONE
        terms.forEach { term -> recents.addView(recentRow(term, history = true)) }
        if (has) recents.addView(recentRow("Limpar buscas", history = false))
    }

    private fun recentRow(term: String, history: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(300), dp(40)).apply { bottomMargin = dp(6) }
            contentDescription = if (history) "Busca recente: $term. Segure OK para apagar." else term
        }
        if (history) row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_history)
            setColorFilter(ContextCompat.getColor(context, R.color.lf_text_3))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) }
        })
        row.addView(TextView(this).apply {
            text = term
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(ContextCompat.getColor(context, if (history) R.color.lf_text else R.color.lf_text_2))
            textSize = 16f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.rajdhani_semibold)
        })
        row.setOnClickListener {
            if (history) {
                setQuery(term)
                ui.removeCallbacks(searchNow)
                runSearch()
                focusKeyboard()
            } else {
                this.history.clear()
                renderRecents()
                focusKeyboard()
            }
        }
        if (history) row.setOnLongClickListener {
            this.history.remove(term)
            renderRecents()
            Toast.makeText(this, "Busca apagada", Toast.LENGTH_SHORT).show()
            if (!focusResults()) focusKeyboard()
            true
        }
        row.setOnKeyListener { _, code, ev ->
            code == KeyEvent.KEYCODE_DPAD_LEFT && ev.action == KeyEvent.ACTION_DOWN && run { focusKeyboard(); true }
        }
        return row
    }

    // ---------- abrir um resultado ----------

    private fun openMovie(g: DubLegGroup, q: String) {
        history.add(q)
        startActivity(
            DetailActivity.intent(
                this, url = g.rep.url, title = g.title, logo = g.rep.logo, isSeries = false,
                urlDub = if (g.dub != null && g.leg != null) g.dub.url else null,
                urlLeg = if (g.dub != null && g.leg != null) g.leg.url else null,
                rating = g.rep.rating,
            ),
        )
    }

    private fun openSeries(h: SeriesHit, q: String) {
        history.add(q)
        SeriesLauncher.open(this, h.series)
    }

    /** Toca o canal com a seção inteira na sessão, para o zapping e o painel do player funcionarem como sempre. */
    private fun openLive(snap: Snapshot, index: Int, q: String) {
        val ch = snap.live.channels.getOrNull(index) ?: return
        history.add(q)
        LiveSession.set(snap.live.channels, index, LiveCatalog.groupsOf(snap.live, PlaylistStore(this).favorites()))
        // Mesmo intent da Home, mas sem abrir a lista de canais por cima: quem buscou quer ver o canal.
        startActivity(PlayerActivity.liveHomeIntent(this, ch).apply { removeExtra(PlayerActivity.EXTRA_LIVE_HOME) })
    }

    // ---------- cartões ----------

    private inner class CardAdapter(private val live: Boolean) : RecyclerView.Adapter<CardAdapter.VH>() {

        private var cards: List<Card> = emptyList()

        fun submit(list: List<Card>) {
            cards = list
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView? = v.findViewById(if (live) R.id.ch_logo else R.id.poster)
            val title: TextView = v.findViewById(if (live) R.id.ch_name else R.id.poster_title)
            val meta: TextView = v.findViewById(if (live) R.id.ch_now else R.id.poster_meta)
            val badge: TextView? = if (live) null else v.findViewById(R.id.poster_rating)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val inflater = LayoutInflater.from(parent.context)
            val v = inflater.inflate(if (live) R.layout.item_search_channel else R.layout.item_poster, parent, false)
            // Só ajusta o tamanho: o leanback faz cast dos LayoutParams do filho para o tipo dele
            // (GridLayoutManager.LayoutParams, criado pelo inflate); trocar por RecyclerView.LayoutParams derruba o app.
            v.layoutParams.width = dp(if (live) 156 else 112)
            v.layoutParams.height = dp(if (live) 92 else 168)
            if (!live) {
                v.isFocusable = true
                v.isClickable = true
                (v as FrameLayout).foreground = ContextCompat.getDrawable(parent.context, R.drawable.search_card_focus)
            }
            val holder = VH(v)
            v.setOnClickListener { cards.getOrNull(holder.adapterPosition)?.open?.invoke() }
            v.setOnKeyListener { _, code, ev ->
                code == KeyEvent.KEYCODE_DPAD_LEFT && ev.action == KeyEvent.ACTION_DOWN &&
                    holder.adapterPosition == 0 && run { focusKeyboard(); true }
            }
            return holder
        }

        override fun getItemCount() = cards.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = cards[position]
            h.title.text = c.title
            h.meta.text = c.meta
            h.meta.visibility = if (c.meta.isBlank()) View.GONE else View.VISIBLE
            val mark = c.mark()
            h.itemView.contentDescription = listOfNotNull(c.description, WatchMarks.spoken(mark)).joinToString(", ")
            if (!live) WatchMarks.apply(h.itemView, mark)
            h.badge?.let {
                if (c.rating > 0) { it.text = "★ ${"%.1f".format(c.rating)}"; it.visibility = View.VISIBLE }
                else it.visibility = View.GONE
            }
            val img = h.image ?: return
            img.dispose()
            if (c.image == null) {
                img.setImageDrawable(null)
            } else {
                coil.Coil.imageLoader(this@SearchActivity).enqueue(
                    coil.request.ImageRequest.Builder(this@SearchActivity)
                        .data(SearchCatalog.thumbOf(c.image)).target(img).crossfade(true).build(),
                )
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ROW_LIMIT = 40
        private const val DEBOUNCE_MS = 250L
        private const val MAX_QUERY = 40
        private const val KEY_COLUMNS = 6
    }
}
