package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.fragment.app.FragmentActivity
import com.seupainel.player.playlist.M3uParser
import com.seupainel.player.playlist.PlaylistHolder
import com.seupainel.player.playlist.PlaylistStore
import kotlin.concurrent.thread

/**
 * Tela de abertura: mostra a animação "carregando" enquanto interpreta a lista
 * (do cache) e a guarda em memória (PlaylistHolder). Depois abre a Home. Assim,
 * abrir qualquer seção depois é instantâneo — sem reparsear a lista toda vez.
 */
class SplashActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        pulse()

        if (AppSettings(this).autoUpdate) Updater.check(this, silent = true)

        val startAt = System.currentTimeMillis()
        thread(name = "splash-preload") {
            runCatching {
                if (PlaylistHolder.channels == null) {
                    val cache = PlaylistStore(this).readCache()
                    if (cache != null) {
                        val res = M3uParser.parse(cache)
                        if (res is M3uParser.Result.Playlist) PlaylistHolder.channels = res.channels
                    }
                }
            }
            // mantém a animação visível por um tempo mínimo agradável
            val elapsed = System.currentTimeMillis() - startAt
            if (elapsed < 900) runCatching { Thread.sleep(900 - elapsed) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    private fun pulse() {
        val t = findViewById<View?>(R.id.loading_text) ?: return
        t.startAnimation(AlphaAnimation(0.45f, 1f).apply {
            duration = 700
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        })
    }
}
