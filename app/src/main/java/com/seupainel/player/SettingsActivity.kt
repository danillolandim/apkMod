package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.view.View
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
    private lateinit var seekSpin: Spinner
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
        val tmdb = findViewById<EditText>(R.id.set_tmdb)
        val refresh = findViewById<EditText>(R.id.set_refresh)
        val auto = findViewById<CheckBox>(R.id.set_auto)
        val info = findViewById<TextView>(R.id.set_info)

        url.setText(store.playlistUrl.orEmpty())
        config.setText(store.configUrl.orEmpty())
        ua.setText(store.userAgent.orEmpty())
        epg.setText(store.epgUrl.orEmpty())
        tmdb.setText(app.tmdbApiKey)

        // Lista de User-Agents prontos: escolher preenche o campo.
        val uaPreset = spinner(R.id.set_ua_preset, AppSettings.UA_PRESETS.map { it.first })
        val curUaIdx = AppSettings.UA_PRESETS.indexOfFirst { it.second == store.userAgent }
        uaPreset.setSelection(if (curUaIdx >= 0) curUaIdx else 0)
        uaPreset.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val value = AppSettings.UA_PRESETS[pos].second
                if (pos != 0 || value.isNotEmpty()) ua.setText(value)
                else if (pos == 0 && ua.text.toString() in AppSettings.UA_PRESETS.map { it.second }) ua.setText("")
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        refresh.setText(store.refreshHours.toString())
        auto.isChecked = store.autoLoad
        info.text = statusText()

        // --- preferências de player ---
        audioSpin = spinner(R.id.set_audio_lang, AppSettings.LANG_OPTIONS.map { it.first })
        subSpin = spinner(R.id.set_sub_lang, AppSettings.LANG_OPTIONS.map { it.first })
        sizeSpin = spinner(R.id.set_sub_size, AppSettings.SUBTITLE_SIZES.map { it.first })
        aspectSpin = spinner(R.id.set_aspect, PlayerController.ASPECTS.map { it.second })
        connSpin = spinner(R.id.set_conn, AppSettings.CONNECTION_OPTIONS.map { it.first })
        seekSpin = spinner(R.id.set_seek_step, AppSettings.SEEK_STEPS.map { it.first })
        forceMax = findViewById(R.id.set_force_max)
        resumeChk = findViewById(R.id.set_resume)
        retryChk = findViewById(R.id.set_retry)

        audioSpin.setSelection(AppSettings.LANG_OPTIONS.indexOfFirst { it.second == app.audioLanguage }.coerceAtLeast(0))
        subSpin.setSelection(AppSettings.LANG_OPTIONS.indexOfFirst { it.second == app.subtitleLanguage }.coerceAtLeast(0))
        sizeSpin.setSelection(AppSettings.SUBTITLE_SIZES.indexOfFirst { it.second == app.subtitleSizeSp }.coerceAtLeast(0))
        aspectSpin.setSelection(app.aspectIndex.coerceIn(PlayerController.ASPECTS.indices))
        connSpin.setSelection(AppSettings.CONNECTION_OPTIONS.indexOfFirst { it.second == app.connectionMbps }.coerceAtLeast(0))
        seekSpin.setSelection(AppSettings.SEEK_STEPS.indexOfFirst { it.second == app.seekStepSec }.coerceAtLeast(0))
        forceMax.isChecked = app.forceMaxQuality
        resumeChk.isChecked = app.resumeEnabled
        retryChk.isChecked = app.autoRetry

        // Aparência e atualização
        val wallpaperSpin = spinner(R.id.set_wallpaper, AppSettings.WALLPAPERS.map { it.first })
        val wallpaperUrl = findViewById<EditText>(R.id.set_wallpaper_url)
        val autoUpdate = findViewById<CheckBox>(R.id.set_auto_update)
        wallpaperSpin.setSelection(AppSettings.WALLPAPERS.indexOfFirst { it.second == app.wallpaper }.coerceAtLeast(0))
        wallpaperUrl.setText(app.wallpaperUrl)
        autoUpdate.isChecked = app.autoUpdate
        findViewById<Button>(R.id.set_check_update).setOnClickListener {
            Toast.makeText(this, "Verificando…", Toast.LENGTH_SHORT).show()
            Updater.check(this, silent = false)
        }

        fun save() {
            val newUrl = url.text.toString().trim().ifBlank { null }
            val newConfig = config.text.toString().trim().ifBlank { null }
            if (newUrl != store.playlistUrl || newConfig != store.configUrl) {
                com.seupainel.player.playlist.PlaylistHolder.clear()   // lista mudou
            }
            store.playlistUrl = newUrl
            store.configUrl = newConfig
            store.userAgent = ua.text.toString().trim().ifBlank { null }
            store.epgUrl = epg.text.toString().trim().ifBlank { null }
            app.tmdbApiKey = tmdb.text.toString()
            store.refreshHours = refresh.text.toString().trim().toIntOrNull() ?: 12
            store.autoLoad = auto.isChecked
            app.audioLanguage = AppSettings.LANG_OPTIONS[audioSpin.selectedItemPosition].second
            app.subtitleLanguage = AppSettings.LANG_OPTIONS[subSpin.selectedItemPosition].second
            app.subtitleSizeSp = AppSettings.SUBTITLE_SIZES[sizeSpin.selectedItemPosition].second
            app.aspectIndex = aspectSpin.selectedItemPosition
            app.connectionMbps = AppSettings.CONNECTION_OPTIONS[connSpin.selectedItemPosition].second
            app.seekStepSec = AppSettings.SEEK_STEPS[seekSpin.selectedItemPosition].second
            app.forceMaxQuality = forceMax.isChecked
            app.resumeEnabled = resumeChk.isChecked
            app.autoRetry = retryChk.isChecked
            app.wallpaper = AppSettings.WALLPAPERS[wallpaperSpin.selectedItemPosition].second
            app.wallpaperUrl = wallpaperUrl.text.toString()
            app.autoUpdate = autoUpdate.isChecked
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
            store.clearCache(); com.seupainel.player.playlist.PlaylistHolder.clear()
            info.text = statusText()
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
        // Não lê o arquivo de cache (pode ter dezenas de MB) — usa a contagem salva.
        val when_ = if (store.lastRefreshMs > 0)
            SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(store.lastRefreshMs))
        else "nunca"
        val size = store.channelCount
        return "Última atualização: $when_" + if (size > 0) " • $size canais em cache" else " • sem cache"
    }

    override fun onPause() {
        super.onPause()
        // auto-save ao sair, para não perder o que foi editado
        val newUrl = findViewById<EditText>(R.id.set_url).text.toString().trim().ifBlank { null }
        val newConfig = findViewById<EditText>(R.id.set_config).text.toString().trim().ifBlank { null }
        if (newUrl != store.playlistUrl || newConfig != store.configUrl) {
            com.seupainel.player.playlist.PlaylistHolder.clear()
        }
        store.playlistUrl = newUrl
        store.configUrl = newConfig
        store.userAgent = findViewById<EditText>(R.id.set_ua).text.toString().trim().ifBlank { null }
        store.epgUrl = findViewById<EditText>(R.id.set_epg).text.toString().trim().ifBlank { null }
        app.tmdbApiKey = findViewById<EditText>(R.id.set_tmdb).text.toString()
        store.refreshHours = findViewById<EditText>(R.id.set_refresh).text.toString().trim().toIntOrNull() ?: 12
        store.autoLoad = findViewById<CheckBox>(R.id.set_auto).isChecked
        app.audioLanguage = AppSettings.LANG_OPTIONS[audioSpin.selectedItemPosition].second
        app.subtitleLanguage = AppSettings.LANG_OPTIONS[subSpin.selectedItemPosition].second
        app.subtitleSizeSp = AppSettings.SUBTITLE_SIZES[sizeSpin.selectedItemPosition].second
        app.aspectIndex = aspectSpin.selectedItemPosition
        app.connectionMbps = AppSettings.CONNECTION_OPTIONS[connSpin.selectedItemPosition].second
        app.seekStepSec = AppSettings.SEEK_STEPS[seekSpin.selectedItemPosition].second
        app.forceMaxQuality = forceMax.isChecked
        app.resumeEnabled = resumeChk.isChecked
        app.autoRetry = retryChk.isChecked
        app.wallpaper = AppSettings.WALLPAPERS[findViewById<Spinner>(R.id.set_wallpaper).selectedItemPosition].second
        app.wallpaperUrl = findViewById<EditText>(R.id.set_wallpaper_url).text.toString()
        app.autoUpdate = findViewById<CheckBox>(R.id.set_auto_update).isChecked
    }
}
