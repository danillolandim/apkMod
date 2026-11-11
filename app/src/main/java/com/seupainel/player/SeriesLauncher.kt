package com.seupainel.player

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import com.seupainel.player.playlist.PlaylistHolder
import com.seupainel.player.playlist.PlaylistStore
import com.seupainel.player.playlist.XtreamApi
import com.seupainel.player.playlist.XtreamCatalog
import kotlin.concurrent.thread

/** Abre a tela de uma série da API: o catálogo só traz as séries, então os episódios vêm agora. */
object SeriesLauncher {
    fun open(activity: Activity, store: PlaylistStore, title: String, logo: String?, seriesId: Long) {
        Toast.makeText(activity, "Carregando episódios…", Toast.LENGTH_SHORT).show()
        thread(name = "series-info") {
            val result = runCatching {
                val acc = XtreamCatalog.accountFor(store, PlaylistHolder.channels.orEmpty()) ?: error("sem conta")
                XtreamApi.seriesEpisodes(acc, store.userAgent, seriesId, title, logo)
            }
            activity.runOnUiThread {
                val eps = result.getOrNull()
                if (activity.isFinishing) return@runOnUiThread
                if (eps.isNullOrEmpty()) {
                    val why = result.exceptionOrNull()?.let { XtreamApi.describe(it) } ?: "sem episódios"
                    Toast.makeText(activity, "Não consegui carregar os episódios ($why)", Toast.LENGTH_LONG).show()
                } else activity.startActivity(Intent(activity, SeriesActivity::class.java).apply {
                    putExtra(SeriesActivity.EXTRA_TITLE, title)
                    putExtra(SeriesActivity.EXTRA_LOGO, logo ?: "")
                    putExtra(SeriesActivity.EXTRA_EPISODES, SeriesActivity.episodesToJson(eps))
                })
            }
        }
    }
}
