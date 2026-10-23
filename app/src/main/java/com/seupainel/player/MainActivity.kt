package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.playlist.ChannelListActivity
import com.seupainel.player.playlist.PlaylistStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela inicial estilo Apple TV: wallpaper, relógio e cartões de ação.
 * - Meus canais  -> abre a lista configurada (ou Configurações se não houver).
 * - Configurações-> painel de preferências e lista.
 * - Testar/demos -> streams públicos e URL avulsa.
 */
class MainActivity : FragmentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var clockTime: TextView
    private lateinit var clockDate: TextView

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        clockTime = findViewById(R.id.clock_time)
        clockDate = findViewById(R.id.clock_date)

        applyWallpaper()

        // Verifica atualização do app ao abrir (se ativado nas Configurações).
        if (AppSettings(this).autoUpdate) Updater.check(this, silent = true)

        val openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<ImageButton>(R.id.btn_gear).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.card_settings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.card_test).setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
        }
        findViewById<Button>(R.id.card_channels).setOnClickListener { openChannels() }

        maybeAutoLoad()
        findViewById<Button>(R.id.card_channels).requestFocus()
    }

    private fun openChannels() {
        val store = PlaylistStore(this)
        if (store.playlistUrl.isNullOrBlank() && store.configUrl.isNullOrBlank()) {
            Toast.makeText(this, "Configure sua lista primeiro (engrenagem).", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        startActivity(
            Intent(this, ChannelListActivity::class.java)
                .putExtra(ChannelListActivity.EXTRA_FROM_SETTINGS, true)
        )
    }

    private fun maybeAutoLoad() {
        val store = PlaylistStore(this)
        // Só uma vez por execução do app (flag estática), senão a lista volta
        // por cima toda vez que a MainActivity é recriada pela TV.
        if (!autoLoadedOnce && store.autoLoad &&
            (!store.playlistUrl.isNullOrBlank() || !store.configUrl.isNullOrBlank())
        ) {
            autoLoadedOnce = true
            startActivity(
                Intent(this, ChannelListActivity::class.java)
                    .putExtra(ChannelListActivity.EXTRA_FROM_SETTINGS, true)
            )
        }
    }

    private companion object { @JvmStatic var autoLoadedOnce = false }

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
        val bg = findViewById<android.widget.ImageView>(R.id.bg_image)
        if (s.wallpaper == "custom" && s.wallpaperUrl.isNotBlank()) {
            coil.Coil.imageLoader(this).enqueue(
                coil.request.ImageRequest.Builder(this)
                    .data(s.wallpaperUrl).target(bg).crossfade(true)
                    .error(resIdOf(AppSettings.wallpaperDrawable("dusk")))
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
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }
}
