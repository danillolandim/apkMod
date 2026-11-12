package com.seupainel.player.search

import com.seupainel.player.playlist.LiveCatalog
import com.seupainel.player.playlist.M3uParser
import com.seupainel.player.playlist.SeriesGrouper
import com.seupainel.player.playlist.XtreamCatalog

/** Em qual seção (Filmes, Séries ou TV) cai cada item da lista M3U, com a mesma regra da tela de listas. */
object CatalogSections {

    enum class Kind { MOVIE, SERIES, LIVE }

    class Partition(val movies: List<M3uParser.Channel>, val series: List<M3uParser.Channel>, val live: List<M3uParser.Channel>)

    /**
     * O tipo da API manda; sem ele vale o grupo. Em grupo de filmes só um marcador forte de episódio (S01E02) move o
     * item para Séries. [isTv] é o tipo do TMDB já conhecido (série/filme), que vale mais que o grupo.
     */
    fun classify(ch: M3uParser.Channel, isTv: (String) -> Boolean? = { null }): Kind {
        when (ch.kind) {
            XtreamCatalog.KIND_MOVIE -> return Kind.MOVIE
            XtreamCatalog.KIND_SERIES -> return Kind.SERIES
            XtreamCatalog.KIND_LIVE -> return Kind.LIVE
        }
        val byGroup = LiveCatalog.groupKind(ch.group)
        if (byGroup == LiveCatalog.GroupKind.LIVE) return Kind.LIVE
        when (isTv(ch.name)) {
            true -> return Kind.SERIES
            false -> return Kind.MOVIE
            null -> {}
        }
        if (byGroup == LiveCatalog.GroupKind.SERIES) return Kind.SERIES
        if (SeriesGrouper.hasStrongEpisodeMarker(ch.name)) return Kind.SERIES
        return Kind.MOVIE
    }

    fun partition(channels: List<M3uParser.Channel>, isTv: (String) -> Boolean? = { null }): Partition {
        val movies = ArrayList<M3uParser.Channel>()
        val series = ArrayList<M3uParser.Channel>()
        val live = ArrayList<M3uParser.Channel>()
        for (ch in channels) when (classify(ch, isTv)) {
            Kind.MOVIE -> movies.add(ch)
            Kind.SERIES -> series.add(ch)
            Kind.LIVE -> live.add(ch)
        }
        return Partition(movies, series, live)
    }
}
