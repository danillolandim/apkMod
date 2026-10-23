package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        store = PlaylistStore(this)

        val url = findViewById<EditText>(R.id.set_url)
        val config = findViewById<EditText>(R.id.set_config)
        val ua = findViewById<EditText>(R.id.set_ua)
        val refresh = findViewById<EditText>(R.id.set_refresh)
        val auto = findViewById<CheckBox>(R.id.set_auto)
        val info = findViewById<TextView>(R.id.set_info)

        url.setText(store.playlistUrl.orEmpty())
        config.setText(store.configUrl.orEmpty())
        ua.setText(store.userAgent.orEmpty())
        refresh.setText(store.refreshHours.toString())
        auto.isChecked = store.autoLoad
        info.text = statusText()

        fun save() {
            store.playlistUrl = url.text.toString().trim().ifBlank { null }
            store.configUrl = config.text.toString().trim().ifBlank { null }
            store.userAgent = ua.text.toString().trim().ifBlank { null }
            store.refreshHours = refresh.text.toString().trim().toIntOrNull() ?: 12
            store.autoLoad = auto.isChecked
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
        // auto-save ao sair, para não perder o que foi digitado
        PlaylistStore(this).apply {
            playlistUrl = findViewById<EditText>(R.id.set_url).text.toString().trim().ifBlank { null }
            configUrl = findViewById<EditText>(R.id.set_config).text.toString().trim().ifBlank { null }
            userAgent = findViewById<EditText>(R.id.set_ua).text.toString().trim().ifBlank { null }
            refreshHours = findViewById<EditText>(R.id.set_refresh).text.toString().trim().toIntOrNull() ?: 12
            autoLoad = findViewById<CheckBox>(R.id.set_auto).isChecked
        }
    }
}
