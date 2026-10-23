package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.playlist.ChannelListActivity
import com.seupainel.player.playlist.PlaylistStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Configurações da lista. Você define a lista uma vez; o app atualiza sozinho
 * conforme o intervalo e pode abrir direto na lista ao iniciar.
 */
class SettingsActivity : FragmentActivity() {

    private lateinit var store: PlaylistStore
    private lateinit var app: AppSettings

    private lateinit var audioSpin: Spinner
    private lateinit var subSpin: Spinner
    private lateinit var sizeSpin: Spinner
    private lateinit var aspectSpin: Spinner
    private lateinit var connSpin: Spinner
    private lateinit var forceMax: CheckBox
    private lateinit var resumeChk: CheckBox
    private lateinit var retryChk: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = PlaylistStore(this)
        app = AppSettings(this)

        val url = findViewById<EditText>(R.id.set_url)
        val config = findViewById<EditText>(R.id.set_config)
        val ua = findViewById<EditText>(R.id.set_ua)
        val epg = findViewById<EditText>(R.id.set_epg)
        val refresh = findViewById<EditText>(R.id.set_refresh)
        val auto = findViewById<CheckBox>(R.id.set_auto)
        val info = findViewById<TextView>(R.id.set_info)

        url.setText(store.playlistUrl.orEmpty())
        config.setText(store.configUrl.orEmpty())
        ua.setText(store.userAgent.orEmpty())
        epg.setText(store.epgUrl.orEmpty())
        refresh.setText(store.refreshHours.toString())
        auto.isChecked = store.autoLoad
        info.text = statusText()

        // --- preferências de player ---
        audioSpin = spinner(R.id.set_audio_lang, AppSettings.LANG_OPTIONS.map { it.first })
        subSpin = spinner(R.id.set_sub_lang, AppSettings.LANG_OPTIONS.map { it.first })
        sizeSpin = spinner(R.id.set_sub_size, AppSettings.SUBTITLE_SIZES.map { it.first })
        aspectSpin = spinner(R.id.set_aspect, PlayerController.ASPECTS.map { it.second })
        connSpin = spinner(R.id.set_conn, AppSettings.CONNECTION_OPTIONS.map { it.first })
        forceMax = findViewById(R.id.set_force_max)
        resumeChk = findViewById(R.id.set_resume)
        retryChk = findViewById(R.id.set_retry)

        audioSpin.setSelection(AppSettings.LANG_OPTIONS.indexOfFirst { it.second == app.audioLanguage }.coerceAtLeast(0))
        subSpin.setSelection(AppSettings.LANG_OPTIONS.indexOfFirst { it.second == app.subtitleLanguage }.coerceAtLeast(0))
        sizeSpin.setSelection(AppSettings.SUBTITLE_SIZES.indexOfFirst { it.second == app.subtitleSizeSp }.coerceAtLeast(0))
        aspectSpin.setSelection(app.aspectIndex.coerceIn(PlayerController.ASPECTS.indices))
        connSpin.setSelection(AppSettings.CONNECTION_OPTIONS.indexOfFirst { it.second == app.connectionMbps }.coerceAtLeast(0))
        forceMax.isChecked = app.forceMaxQuality
        resumeChk.isChecked = app.resumeEnabled
        retryChk.isChecked = app.autoRetry

        fun save() {
            store.playlistUrl = url.text.toString().trim().ifBlank { null }
            store.configUrl = config.text.toString().trim().ifBlank { null }
            store.userAgent = ua.text.toString().trim().ifBlank { null }
            store.epgUrl = epg.text.toString().trim().ifBlank { null }
            store.refreshHours = refresh.text.toString().trim().toIntOrNull() ?: 12
            store.autoLoad = auto.isChecked
            app.audioLanguage = AppSettings.LANG_OPTIONS[audioSpin.selectedItemPosition].second
            app.subtitleLanguage = AppSettings.LANG_OPTIONS[subSpin.selectedItemPosition].second
            app.subtitleSizeSp = AppSettings.SUBTITLE_SIZES[sizeSpin.selectedItemPosition].second
            app.aspectIndex = aspectSpin.selectedItemPosition
            app.connectionMbps = AppSettings.CONNECTION_OPTIONS[connSpin.selectedItemPosition].second
            app.forceMaxQuality = forceMax.isChecked
            app.resumeEnabled = resumeChk.isChecked
            app.autoRetry = retryChk.isChecked
        }

        findViewById<Button>(R.id.set_save).setOnClickListener {
            save(); Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
            info.text = statusText()
        }

        findViewById<Button>(R.id.set_load_now).setOnClickListener {
            save()
            if (store.configUrl.isNullOrBlank() && store.playlistUrl.isNullOrBlank()) {
                Toast.makeText(this, "Preencha a URL da lista ou a URL de configuração", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, ChannelListActivity::class.java)
                    .putExtra(ChannelListActivity.EXTRA_FROM_SETTINGS, true)
                    .putExtra(ChannelListActivity.EXTRA_FORCE_REFRESH, true)
            )
        }

        findViewById<Button>(R.id.set_clear_cache).setOnClickListener {
            store.clearCache(); info.text = statusText()
            Toast.makeText(this, "Cache limpo", Toast.LENGTH_SHORT).show()
        }

        url.requestFocus()
    }

    private fun spinner(id: Int, items: List<String>): Spinner {
        val s = findViewById<Spinner>(id)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        return s
    }

    private fun statusText(): String {
        val cache = store.readCache()
        val when_ = if (store.lastRefreshMs > 0)
            SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(store.lastRefreshMs))
        else "nunca"
        val size = cache?.lineSequence()?.count { it.startsWith("#EXTINF", true) } ?: 0
        return "Última atualização: $when_" + if (size > 0) " • ~$size canais em cache" else " • sem cache"
    }

    override fun onPause() {
        super.onPause()
        // auto-save ao sair, para não perder o que foi editado
        store.playlistUrl = findViewById<EditText>(R.id.set_url).text.toString().trim().ifBlank { null }
        store.configUrl = findViewById<EditText>(R.id.set_config).text.toString().trim().ifBlank { null }
        store.userAgent = findViewById<EditText>(R.id.set_ua).text.toString().trim().ifBlank { null }
        store.epgUrl = findViewById<EditText>(R.id.set_epg).text.toString().trim().ifBlank { null }
        store.refreshHours = findViewById<EditText>(R.id.set_refresh).text.toString().trim().toIntOrNull() ?: 12
        store.autoLoad = findViewById<CheckBox>(R.id.set_auto).isChecked
        app.audioLanguage = AppSettings.LANG_OPTIONS[audioSpin.selectedItemPosition].second
        app.subtitleLanguage = AppSettings.LANG_OPTIONS[subSpin.selectedItemPosition].second
        app.subtitleSizeSp = AppSettings.SUBTITLE_SIZES[sizeSpin.selectedItemPosition].second
        app.aspectIndex = aspectSpin.selectedItemPosition
        app.connectionMbps = AppSettings.CONNECTION_OPTIONS[connSpin.selectedItemPosition].second
        app.forceMaxQuality = forceMax.isChecked
        app.resumeEnabled = resumeChk.isChecked
        app.autoRetry = retryChk.isChecked
    }
}
