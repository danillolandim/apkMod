package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.seupainel.player.playlist.ChannelListActivity
import com.seupainel.player.playlist.LiveCatalog
import com.seupainel.player.playlist.PlaylistHolder
import com.seupainel.player.playlist.PlaylistStore
import com.seupainel.player.playlist.XtreamCatalog
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Home simples: marca, relógio e três botões grandes (TV ao vivo, Filmes, Séries).
 *
 * O foco dos botões é um anel desenhado DENTRO do botão e eles não mudam de tamanho: assim
 * nada é recortado pelas bordas do container (o problema clássico do Android com a borda do foco).
 */
class MainActivity : FragmentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))
    private val numFmt = NumberFormat.getIntegerInstance(Locale("pt", "BR"))
    private var openingLive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clockTime = findViewById(R.id.clock_time)
        clockDate = findViewById(R.id.clock_date)

        // Altura dos botões proporcional à tela (serve a qualquer densidade de TV).
        val tileH = (resources.displayMetrics.heightPixels * 0.34f).toInt()
        listOf(R.id.card_live, R.id.card_filmes, R.id.card_series).forEach { id ->
            findViewById<View>(id).let { v ->
                v.layoutParams = (v.layoutParams as ViewGroup.LayoutParams).apply { height = tileH }
            }
        }

        applyWallpaper()

        findViewById<ImageButton>(R.id.btn_search).setOnClickListener {
            if (!isConfigured()) askLogin() else startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_gear).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.card_live).setOnClickListener { openLive() }
        findViewById<View>(R.id.card_filmes).setOnClickListener { openSection("filmes") }
        findViewById<View>(R.id.card_series).setOnClickListener { openSection("series") }

        findViewById<View>(R.id.card_filmes).requestFocus()
    }

    /**
     * TV ao vivo: sintoniza o último canal direto no player, com a lista de canais aberta. Montar a seção pode
     * esperar a abertura do app e pedir disco (e rede, na primeira vez), então roda em segundo plano com o aviso no
     * próprio botão. Se não houver como montar, diz o motivo e abre a tela de canais, que sabe carregar a lista.
     */
    private fun openLive() {
        if (!isConfigured()) { askLogin(); return }
        if (openingLive) return
        openingLive = true
        val sub = findViewById<TextView>(R.id.card_live_sub)
        val before = sub.text
        sub.text = "Carregando canais…"
        thread(name = "home-live") {
            val opening = LiveCatalog.openSession(applicationContext)
            runOnUiThread {
                openingLive = false
                sub.text = before
                // Usuário saiu da Home enquanto montava: abrir uma tela agora o puxaria de volta sem ele pedir.
                if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@runOnUiThread
                when (opening) {
                    is LiveCatalog.Opening.Ready -> {
                        opening.publish()
                        startActivity(PlayerActivity.liveHomeIntent(this, opening.first))
                    }
                    is LiveCatalog.Opening.Failed -> explainThenOpenList(opening.reason)
                }
            }
        }
    }

    /** O motivo fica numa caixa (aviso rápido some antes de dar para ler na TV); OK segue para a lista completa. */
    private fun explainThenOpenList(reason: String) {
        var opened = false
        fun go() { if (!opened) { opened = true; openSection("canais") } }
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Player_Dialog)
            .setTitle("TV ao vivo")
            .setMessage("Não consegui abrir direto no canal: $reason.\n\nVou abrir a lista completa de canais.")
            .setPositiveButton("OK") { _, _ -> go() }
            .setOnCancelListener { go() }
            .show()
    }

    private fun isConfigured(): Boolean {
        val store = PlaylistStore(this)
        return !(store.playlistUrl.isNullOrBlank() && store.configUrl.isNullOrBlank())
    }

    private fun askLogin() {
        Toast.makeText(this, "Entre com seu usuário e senha primeiro.", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, LoginActivity::class.java))
    }

    /** Abre a lista já na seção escolhida (Filmes / Séries, ou TV ao vivo quando o atalho direto não é possível). */
    private fun openSection(section: String) {
        if (!isConfigured()) { askLogin(); return }
        startActivity(
            Intent(this, ChannelListActivity::class.java)
                .putExtra(ChannelListActivity.EXTRA_FROM_SETTINGS, true)
                .putExtra(ChannelListActivity.EXTRA_SECTION, section)
        )
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Bom dia"
        in 12..17 -> "Boa tarde"
        else -> "Boa noite"
    }

    /**
     * Mostra quantos itens cada seção tem, sem travar a tela. Só mexe em Filmes e Séries (têm
     * cache em disco e não dependem da lista M3U); a TV ao vivo mostra o número quando já estiver carregada.
     */
    private fun refreshCounts() {
        fun show(tileSubId: Int, count: Int, unit: String) {
            if (count > 0) findViewById<TextView>(tileSubId).text = "${numFmt.format(count)} $unit"
        }
        val store = PlaylistStore(this)
        thread(name = "home-counts") {
            if (AppSettings(this).useXtream) {
                listOf(XtreamCatalog.Kind.MOVIE, XtreamCatalog.Kind.SERIES)
                    .filter { XtreamCatalog.hasCache(this, it) && XtreamCatalog.cachedCount(it) == 0 }
                    .forEach { runCatching { XtreamCatalog.load(it, applicationContext, store, PlaylistHolder.channels.orEmpty()) } }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                show(R.id.card_live_sub, XtreamCatalog.cachedCount(XtreamCatalog.Kind.LIVE), "canais")
                show(R.id.card_filmes_sub, XtreamCatalog.cachedCount(XtreamCatalog.Kind.MOVIE), "filmes")
                show(R.id.card_series_sub, XtreamCatalog.cachedCount(XtreamCatalog.Kind.SERIES), "séries")
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clockTime.text = timeFmt.format(now)
            clockDate.text = dateFmt.format(now).replaceFirstChar { it.uppercase() }
            findViewById<TextView>(R.id.greeting).text = greeting()
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
        refreshCounts()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }
}
