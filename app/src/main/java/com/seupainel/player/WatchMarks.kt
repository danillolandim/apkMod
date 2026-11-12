package com.seupainel.player

import android.content.Context
import android.view.View
import android.widget.ProgressBar

/**
 * Resumo guardado por série ("viu 7 de 10"), à parte do [ResumeStore] (arquivo próprio): a grade de séries não tem os
 * episódios na mão (na API eles só vêm ao abrir a série), então a tela da série deixa aqui a última contagem.
 */
class SeriesSummaryStore(context: Context) {

    data class Summary(val watched: Int, val total: Int)

    private val prefs = context.getSharedPreferences("series_summary", Context.MODE_PRIVATE)

    fun all(): Map<String, Summary> = prefs.all.mapNotNull { (k, v) ->
        val parts = (v as? String)?.split('/') ?: return@mapNotNull null
        val w = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val t = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        k to Summary(w, t)
    }.toMap()

    /** Só grava quando mudou (a tela da série chama a cada volta do player). */
    fun put(key: String, watched: Int, total: Int) {
        val v = "$watched/$total"
        if (prefs.getString(key, null) != v) prefs.edit().putString(key, v).apply()
    }
}

/**
 * Selos de assistido da grade de cartazes (Filmes, Séries e busca): uma foto do que foi visto, lida uma vez por
 * [refresh] e consultada por cartão sem tocar no disco.
 */
class WatchMarks(context: Context) {

    private val resume = ResumeStore(context)
    private val summaries = SeriesSummaryStore(context)

    // Lidos por cartões na UI e pela busca em segundo plano: a troca é de uma referência só.
    @Volatile private var watched: Set<String> = emptySet()
    @Volatile private var progress: Map<String, Int> = emptyMap()
    @Volatile private var series: Map<String, SeriesSummaryStore.Summary> = emptyMap()

    fun refresh() {
        watched = resume.watchedSet()
        progress = resume.inProgress().mapValues { (_, it) ->
            if (it.dur > 0) ((it.pos * 100) / it.dur).toInt().coerceIn(1, 99) else 0
        }
        series = summaries.all()
    }

    /** [urls] = as versões do filme (dublado e legendado contam como um só). */
    fun forMovie(urls: List<String>): WatchLogic.Mark =
        WatchLogic.movieMark(urls.any { it in watched }, urls.maxOfOrNull { progress[it] ?: 0 } ?: 0)

    fun forSeries(apiId: Long, title: String): WatchLogic.Mark {
        val s = series[WatchLogic.seriesKey(apiId, title)] ?: return WatchLogic.Mark(false, 0)
        return WatchLogic.seriesMark(s.watched, s.total)
    }

    companion object {
        /** Liga ou desliga o selo "Visto" e a barra fina do `item_poster`. */
        fun apply(card: View, mark: WatchLogic.Mark) {
            card.findViewById<View>(R.id.poster_watched)?.visibility = if (mark.watched) View.VISIBLE else View.GONE
            card.findViewById<ProgressBar>(R.id.poster_progress)?.let {
                it.progress = mark.progressPct
                it.visibility = if (mark.progressPct > 0) View.VISIBLE else View.GONE
            }
        }

        /** Para o leitor de tela: o estado junto do título, não só o selo. */
        fun spoken(mark: WatchLogic.Mark): String? = when {
            mark.watched -> "assistido"
            mark.progressPct > 0 -> "em andamento, ${mark.progressPct}%"
            else -> null
        }
    }
}
