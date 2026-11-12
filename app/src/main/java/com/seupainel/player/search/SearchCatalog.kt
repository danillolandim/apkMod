package com.seupainel.player.search

import android.content.Context
import com.seupainel.player.AppSettings
import com.seupainel.player.meta.VodIndex
import com.seupainel.player.playlist.LiveCatalog
import com.seupainel.player.playlist.LiveChannel
import com.seupainel.player.playlist.M3uParser
import com.seupainel.player.playlist.PlaylistHolder
import com.seupainel.player.playlist.PlaylistStore
import com.seupainel.player.playlist.SeriesGrouper
import com.seupainel.player.playlist.XtreamApi
import com.seupainel.player.playlist.XtreamCatalog
import kotlin.concurrent.thread

class SeriesHit(val series: SeriesGrouper.Series, val tmdbId: Int, val group: String, val added: Long)

/** O catálogo inteiro (Filmes, Séries e TV) já com um índice de títulos por seção. */
class Snapshot(val movies: List<DubLegGroup>, val series: List<SeriesHit>, val live: LiveCatalog.Section) {
    val movieIndex = SearchIndex(movies.map { SearchDoc(it.title, it.rep.rating, it.rep.added) })
    val seriesIndex = SearchIndex(series.map { SearchDoc(it.series.title, it.series.rating, it.added) })
    val liveIndex = SearchIndex(live.channels.map { SearchDoc(it.name) })
}

/**
 * Reúne as listas completas de Filmes, Séries e Canais para a busca global, sem depender da tela de listas: usa os
 * catálogos da API da conta (em memória ou cache) e, no que a API não cobre, a lista M3U carregada na abertura.
 *
 * [assemble] é a parte pura (testada em JVM); [load] é a cola com o app e guarda o último resultado: abrir a busca de
 * novo não refaz o índice enquanto os catálogos forem os mesmos.
 */
object SearchCatalog {

    sealed class Outcome {
        class Ready(val snapshot: Snapshot) : Outcome()
        class Failed(reason: String) : Outcome() {
            val reason: String = LiveCatalog.scrub(reason)
        }
    }

    fun assemble(
        movies: List<M3uParser.Channel>,
        series: List<M3uParser.Channel>,
        live: List<M3uParser.Channel>,
        apiOrderLive: List<String>,
    ): Snapshot {
        // Série da API é uma por item (os episódios vêm ao abrir); a da lista M3U é montada juntando os episódios.
        val (fromApi, fromM3u) = series.partition { it.kind == XtreamCatalog.KIND_SERIES && it.apiId > 0 }
        val hits = ArrayList<SeriesHit>(fromApi.size)
        for (ch in fromApi) {
            hits.add(SeriesHit(
                SeriesGrouper.Series(ch.name, ch.logo, emptyList(), ch.rating, ch.apiId), ch.tmdbId, ch.group, ch.added,
            ))
        }
        if (fromM3u.isNotEmpty()) for (s in SeriesGrouper.group(fromM3u)) {
            hits.add(SeriesHit(s, 0, s.episodes.firstOrNull()?.channel?.group.orEmpty(), 0L))
        }
        return Snapshot(DubLegGroup.group(movies), hits, LiveCatalog.build(live, apiOrderLive))
    }

    /** O painel prefixa as categorias ("Filmes | Netflix"); na tela entra só o que vem depois. */
    fun categoryOf(group: String): String = group.substringAfter(" | ", group).trim()

    private val YEAR = Regex("""(?<!\d)(19|20)\d{2}(?!\d)""")

    fun yearOf(title: String): String? = YEAR.find(title)?.value

    /** Cartaz leve (w342) para os cartões pequenos, o mesmo que a grade das listas usa. */
    fun thumbOf(url: String): String =
        url.replace("/t/p/w500", "/t/p/w342").replace("/t/p/w600_and_h900_bestv2", "/t/p/w342")

    // ---------- cola com o app ----------

    private class Cached(val sources: List<Any?>, val snapshot: Snapshot)
    @Volatile private var cached: Cached? = null

    private const val PRELOAD_WAIT_MS = 30_000L

    /** Bloqueia (espera a abertura do app, disco e, na primeira vez, rede): chamar fora da UI. Nunca lança. */
    fun load(ctx: Context): Outcome = try {
        doLoad(ctx)
    } catch (e: Throwable) {
        Outcome.Failed("erro ao montar o catálogo (${XtreamApi.describe(e)})")
    }

    private fun doLoad(ctx: Context): Outcome {
        try {
            LiveCatalog.waitUntil(PRELOAD_WAIT_MS, 100, done = { PlaylistHolder.preloadDone })
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val app = ctx.applicationContext
        val store = PlaylistStore(app)
        val settings = AppSettings(app)
        val raw = PlaylistHolder.channels

        val api = arrayOfNulls<XtreamCatalog.Section>(XtreamCatalog.Kind.values().size)
        if (settings.useXtream) {
            XtreamCatalog.Kind.values().map { k ->
                thread(name = "search-${k.tag}") {
                    api[k.ordinal] = runCatching { XtreamCatalog.load(k, app, store, raw.orEmpty()) }.getOrNull()
                }
            }.forEach { it.join() }
        }
        val apiMovies = api[XtreamCatalog.Kind.MOVIE.ordinal]
        val apiSeries = api[XtreamCatalog.Kind.SERIES.ordinal]
        val apiLive = api[XtreamCatalog.Kind.LIVE.ordinal]

        val sources = listOf(apiMovies, apiSeries, apiLive, raw)
        cached?.takeIf { c -> c.sources.indices.all { c.sources[it] === sources[it] } }
            ?.let { return Outcome.Ready(it.snapshot) }

        if (raw == null && (apiMovies == null || apiSeries == null || apiLive == null)) {
            val why = XtreamCatalog.statusOf(XtreamCatalog.Kind.MOVIE) ?: XtreamCatalog.statusOf(XtreamCatalog.Kind.SERIES)
                ?: XtreamCatalog.statusOf(XtreamCatalog.Kind.LIVE)
            return Outcome.Failed(why ?: "o catálogo ainda não foi baixado")
        }
        // A lista M3U só entra no que a API não cobriu.
        val part = if (raw != null && (apiMovies == null || apiSeries == null || apiLive == null)) {
            val index = VodIndex(app)
            CatalogSections.partition(raw) { index.isTv(it) }
        } else null
        val snapshot = assemble(
            movies = apiMovies?.channels ?: part?.movies.orEmpty(),
            series = apiSeries?.channels ?: part?.series.orEmpty(),
            live = apiLive?.channels ?: part?.live.orEmpty(),
            apiOrderLive = apiLive?.categoryOrder.orEmpty(),
        )
        if (snapshot.movieIndex.size + snapshot.seriesIndex.size + snapshot.liveIndex.size == 0)
            return Outcome.Failed("o catálogo veio vazio")
        cached = Cached(sources, snapshot)
        return Outcome.Ready(snapshot)
    }
}

/** Buscas recentes, guardadas à parte (arquivo próprio) para não tocar em favoritos nem em "Continuar". */
class SearchHistory(context: Context) {

    private val prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)

    fun all(): List<String> = RecentSearches.decode(prefs.getString(KEY, null))

    fun add(term: String) = save(RecentSearches.add(all(), term))

    fun remove(term: String) = save(RecentSearches.remove(all(), term))

    fun clear() = save(emptyList())

    private fun save(list: List<String>) = prefs.edit().putString(KEY, RecentSearches.encode(list)).apply()

    private companion object {
        const val KEY = "recent"
    }
}
