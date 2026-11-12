package com.seupainel.player.search

import com.seupainel.player.playlist.M3uParser
import com.seupainel.player.playlist.XtreamCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSectionsTest {

    private fun ch(name: String, group: String, kind: String? = null) =
        M3uParser.Channel(name = name, url = "http://x/${name.hashCode()}", group = group, duration = 0, kind = kind)

    @Test
    fun `tipo da api manda`() {
        assertEquals(CatalogSections.Kind.MOVIE, CatalogSections.classify(ch("A", "Canais", XtreamCatalog.KIND_MOVIE)))
        assertEquals(CatalogSections.Kind.SERIES, CatalogSections.classify(ch("A", "Filmes", XtreamCatalog.KIND_SERIES)))
        assertEquals(CatalogSections.Kind.LIVE, CatalogSections.classify(ch("A", "Filmes", XtreamCatalog.KIND_LIVE)))
    }

    @Test
    fun `grupo decide quando nao ha tipo`() {
        assertEquals(CatalogSections.Kind.LIVE, CatalogSections.classify(ch("Globo", "Esportes")))
        assertEquals(CatalogSections.Kind.MOVIE, CatalogSections.classify(ch("Duna", "Filmes | Ficção")))
        assertEquals(CatalogSections.Kind.SERIES, CatalogSections.classify(ch("Dark", "Séries | Netflix")))
    }

    @Test
    fun `episodio dentro de grupo de filmes volta para series so com marcador forte`() {
        assertEquals(CatalogSections.Kind.SERIES, CatalogSections.classify(ch("Dark S01E02", "Filmes")))
        assertEquals(CatalogSections.Kind.SERIES, CatalogSections.classify(ch("Dark T01E02", "Filmes")))
        assertEquals(CatalogSections.Kind.MOVIE, CatalogSections.classify(ch("Rocky 2x02", "Filmes")))
    }

    @Test
    fun `tipo do tmdb vale mais que o grupo, nos dois sentidos`() {
        assertEquals(CatalogSections.Kind.SERIES, CatalogSections.classify(ch("Dark", "Filmes")) { true })
        assertEquals(CatalogSections.Kind.MOVIE, CatalogSections.classify(ch("Dark", "Séries")) { false })
    }

    @Test
    fun `canal ao vivo nunca e reclassificado pelo tmdb`() {
        assertEquals(CatalogSections.Kind.LIVE, CatalogSections.classify(ch("Globo S01E02", "Esportes")) { false })
    }

    @Test
    fun `divide a lista nas tres secoes`() {
        val p = CatalogSections.partition(listOf(
            ch("Globo", "Esportes"), ch("Duna", "Filmes"), ch("Dark S01E01", "Séries"),
        ))
        assertEquals(listOf("Duna"), p.movies.map { it.name })
        assertEquals(listOf("Dark S01E01"), p.series.map { it.name })
        assertEquals(listOf("Globo"), p.live.map { it.name })
    }
}
