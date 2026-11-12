package com.seupainel.player.search

import com.seupainel.player.playlist.M3uParser
import com.seupainel.player.playlist.XtreamCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCatalogTest {

    private fun ch(
        name: String, group: String = "", kind: String? = null, apiId: Long = 0L, rating: Double = 0.0,
        added: Long = 0L, tmdbId: Int = 0, url: String = "http://x/${name.hashCode()}",
    ) = M3uParser.Channel(
        name = name, url = url, group = group, duration = -1, kind = kind, apiId = apiId, rating = rating,
        added = added, tmdbId = tmdbId,
    )

    private fun search(index: SearchIndex, q: String) = index.search(q, 40) ?: error("cancelada")

    @Test
    fun `filmes dublado e legendado contam como um so`() {
        val s = SearchCatalog.assemble(
            movies = listOf(ch("Duna", "Filmes | Ficção"), ch("Duna (LEG)", "Filmes | Ficção"), ch("Outro", "Filmes | Drama")),
            series = emptyList(), live = emptyList(), apiOrderLive = emptyList(),
        )
        val r = search(s.movieIndex, "duna")
        assertEquals(1, r.total)
        assertEquals("Duna", s.movies[r.top.single()].title)
    }

    @Test
    fun `serie da api e um item por serie e guarda tmdb e categoria`() {
        val s = SearchCatalog.assemble(
            movies = emptyList(),
            series = listOf(
                ch("Dark", "Séries | Netflix", XtreamCatalog.KIND_SERIES, apiId = 7, rating = 8.5, added = 100, tmdbId = 70523),
                ch("Dark (LEG)", "Séries | Netflix", XtreamCatalog.KIND_SERIES, apiId = 8),
            ),
            live = emptyList(), apiOrderLive = emptyList(),
        )
        // Cada series_id abre uma tela de episódios própria: não dá para fundir as duas.
        assertEquals(2, s.series.size)
        val dark = s.series.first { it.series.apiId == 7L }
        assertEquals(70523, dark.tmdbId)
        assertEquals("Séries | Netflix", dark.group)
        assertEquals(100L, dark.added)
        assertEquals(8.5, dark.series.rating, 0.0)
    }

    @Test
    fun `episodios soltos da lista m3u viram uma serie so, com as duas versoes`() {
        val s = SearchCatalog.assemble(
            movies = emptyList(),
            series = listOf(
                ch("Dark S01E01", "Séries"), ch("Dark S01E02", "Séries"), ch("Dark LEGENDADO S01E01", "Séries"),
            ),
            live = emptyList(), apiOrderLive = emptyList(),
        )
        assertEquals(1, s.series.size)
        assertEquals(3, s.series.single().series.episodes.size)
        assertEquals("Dark", s.series.single().series.title)
    }

    @Test
    fun `canais de qualidades diferentes viram um canal e a busca acha pelo nome base`() {
        val s = SearchCatalog.assemble(
            movies = emptyList(), series = emptyList(),
            live = listOf(ch("Globo SD", "Abertos"), ch("Globo HD", "Abertos"), ch("Globo News HD", "Notícias")),
            apiOrderLive = emptyList(),
        )
        assertEquals(2, s.live.channels.size)
        val r = search(s.liveIndex, "globo")
        assertEquals(2, r.total)
        // "Globo" (título começa) antes de "Globo News"
        assertEquals(listOf("Globo", "Globo News"), r.top.map { s.live.channels[it].name })
    }

    @Test
    fun `busca nos tres indices e independente`() {
        val s = SearchCatalog.assemble(
            movies = listOf(ch("Matrix", "Filmes")), series = listOf(ch("Matrix Resurrections S01E01", "Séries")),
            live = listOf(ch("Canal Matrix", "Esportes")), apiOrderLive = emptyList(),
        )
        assertEquals(1, search(s.movieIndex, "matrix").total)
        assertEquals(1, search(s.seriesIndex, "matrix").total)
        assertEquals(1, search(s.liveIndex, "matrix").total)
        assertTrue(search(s.movieIndex, "canal").top.isEmpty())
    }

    @Test
    fun `categoria mostrada tira o prefixo do painel`() {
        assertEquals("Netflix", SearchCatalog.categoryOf("Séries | Netflix"))
        assertEquals("Ação", SearchCatalog.categoryOf("Ação"))
        assertEquals("", SearchCatalog.categoryOf(""))
    }

    @Test
    fun `ano vem do titulo quando existe`() {
        assertEquals("2021", SearchCatalog.yearOf("Duna (2021)"))
        assertEquals("1999", SearchCatalog.yearOf("Matrix 1999 HD"))
        assertEquals(null, SearchCatalog.yearOf("Duna"))
        assertEquals(null, SearchCatalog.yearOf("Apollo 11000"))
    }
}
