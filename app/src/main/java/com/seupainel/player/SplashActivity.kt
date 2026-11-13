package com.seupainel.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.fragment.app.FragmentActivity
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

        // A verificação de atualização fica no Updater (a cada volta do app para a frente), não nesta tela.

        val startAt = System.currentTimeMillis()
        val expectCache = PlaylistStore(this).hasCache() || com.seupainel.player.playlist.XtreamCatalog.cachesReady(this)
        thread(name = "splash-wait") {
            // O App já inicia o parse ao subir o processo; aqui só esperamos ele
            // terminar (com teto), para que as seções abram instantâneas depois.
            while (expectCache && !PlaylistHolder.preloadDone &&
                System.currentTimeMillis() - startAt < 6000
            ) runCatching { Thread.sleep(60) }
            // mantém a animação visível por um tempo mínimo agradável (curto)
            val elapsed = System.currentTimeMillis() - startAt
            if (elapsed < 300) runCatching { Thread.sleep(300 - elapsed) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                // Sem conta configurada (primeiro uso): vai direto para a tela de entrada.
                val store = PlaylistStore(this)
                val configured = !store.playlistUrl.isNullOrBlank() || !store.configUrl.isNullOrBlank()
                startActivity(Intent(this, if (configured) MainActivity::class.java else LoginActivity::class.java))
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
