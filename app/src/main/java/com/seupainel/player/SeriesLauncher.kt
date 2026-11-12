package com.seupainel.player

import android.content.Context
import com.seupainel.player.playlist.SeriesGrouper

/** Abre a tela de uma série: a da API só leva o id (a tela busca os episódios); a da lista M3U leva os episódios. */
object SeriesLauncher {
    fun open(context: Context, s: SeriesGrouper.Series) {
        context.startActivity(
            if (s.apiId > 0) SeriesActivity.intentForApi(context, s.title, s.logo, s.apiId, s.rating)
            else SeriesActivity.intentForEpisodes(context, s.title, s.logo, s.episodes),
        )
    }
}
