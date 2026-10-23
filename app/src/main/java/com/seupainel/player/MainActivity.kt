package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity

/**
 * Tela inicial para teste na TV.
 *
 * Dois modos:
 *  - URL direta (HLS/DASH/MP4): toca no ExoPlayer sem o motor P2P. Serve para
 *    validar qualidade, ABR, faixas de áudio/legenda, PiP, aspecto e HUD.
 *  - Endereço P2P: passa pelo motor TVBus (precisa da config em App.kt).
 *
 * Os presets são streams públicos de teste com múltiplas qualidades, áudios e
 * legendas (bons para exercitar o menu de faixas).
 */
class MainActivity : FragmentActivity() {

    private data class Preset(val title: String, val url: String, val live: Boolean = false)

    private val presets = listOf(
        Preset(
            "Apple HLS avançado — 4K, várias qualidades, áudios e legendas (fMP4)",
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        ),
        Preset(
            "Apple HLS BipBop 16x9 — multi-bitrate + legendas (TS)",
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8",
        ),
        Preset(
            "Mux HLS multi-bitrate (até 1080p)",
            "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
        ),
        Preset(
            "Big Buck Bunny — MP4 progressivo 1080p",
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val container = findViewById<LinearLayout>(R.id.presets)
        val urlField = findViewById<EditText>(R.id.url)
        val subField = findViewById<EditText>(R.id.sub_url)
        val liveToggle = findViewById<Button>(R.id.btn_live)
        var isLive = false

        liveToggle.setOnClickListener {
            isLive = !isLive
            liveToggle.text = if (isLive) "Modo: AO VIVO" else "Modo: VOD"
        }

        presets.forEach { p ->
            val b = Button(this).apply {
                text = p.title
                isAllCaps = false
                setOnClickListener { playDirect(p.url, subField.text.toString(), p.live) }
            }
            container.addView(b)
        }

        findViewById<Button>(R.id.btn_play_url).setOnClickListener {
            val u = urlField.text.toString().trim()
            if (u.isBlank()) { toast("Cole uma URL"); return@setOnClickListener }
            playDirect(u, subField.text.toString(), isLive)
        }

        // Carregar lista M3U (abre a tela de canais; detecta lista x stream).
        findViewById<Button>(R.id.btn_load_list).setOnClickListener {
            val u = urlField.text.toString().trim().ifBlank {
                com.seupainel.player.playlist.PlaylistStore(this).lastUrl.orEmpty()
            }
            if (u.isBlank()) { toast("Cole a URL da lista M3U"); return@setOnClickListener }
            startActivity(
                Intent(this, com.seupainel.player.playlist.ChannelListActivity::class.java)
                    .putExtra(com.seupainel.player.playlist.ChannelListActivity.EXTRA_URL, u)
            )
        }

        findViewById<Button>(R.id.btn_play_p2p).setOnClickListener {
            val addr = urlField.text.toString().trim()
            if (addr.isBlank()) { toast("Cole o endereço P2P"); return@setOnClickListener }
            if (BuildConfigValues.AUTH_URL.isBlank()) {
                toast("Configure o motor P2P em App.kt (BuildConfigValues) antes.")
                return@setOnClickListener
            }
            startActivity(
                PlayerActivity.intent(
                    this,
                    PanelSource(address = addr, isLive = isLive),
                )
            )
        }

        val openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btn_settings).setOnClickListener { openSettings() }
        findViewById<android.widget.ImageButton>(R.id.btn_gear).setOnClickListener { openSettings() }

        // Auto-load: se configurado, abre direto na lista ao iniciar.
        val store = com.seupainel.player.playlist.PlaylistStore(this)
        if (!autoLoaded && store.autoLoad &&
            (!store.playlistUrl.isNullOrBlank() || !store.configUrl.isNullOrBlank())
        ) {
            autoLoaded = true
            startActivity(
                Intent(this, com.seupainel.player.playlist.ChannelListActivity::class.java)
                    .putExtra(com.seupainel.player.playlist.ChannelListActivity.EXTRA_FROM_SETTINGS, true)
            )
        }

        // Foco inicial no primeiro preset (D-pad)
        container.post { container.getChildAt(0)?.requestFocus() }
    }

    private var autoLoaded = false

    private fun playDirect(url: String, subUrl: String, live: Boolean) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_DIRECT_URL, url)
            putExtra(PlayerActivity.EXTRA_IS_LIVE, live)
            if (subUrl.isNotBlank()) {
                putExtra(PlayerActivity.EXTRA_SUB_URL, subUrl.trim())
                putExtra(PlayerActivity.EXTRA_SUB_LABEL, "Externa")
                putExtra(PlayerActivity.EXTRA_SUB_LANG, "pt-BR")
            }
        })
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
