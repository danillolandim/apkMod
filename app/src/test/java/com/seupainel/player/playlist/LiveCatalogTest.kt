package com.seupainel.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCatalogTest {

    private fun ch(
        name: String,
        group: String = "",
        url: String = "http://x/${name.replace(' ', '_')}",
        kind: String? = null,
        apiId: Long = 0L,
        logo: String? = null,
        tvgId: String? = null,
    ) = M3uParser.Channel(
        name = name, url = url, group = group, logo = logo, tvgId = tvgId, duration = -1, kind = kind, apiId = apiId,
    )

    private fun names(c: LiveCatalog.Section) = c.channels.map { it.name }
    private fun cats(c: LiveCatalog.Section) = c.categories.map { it.name }

    // ---------- o que pertence à seção ao vivo ----------

    @Test
    fun `item da api manda na secao e o grupo so decide quando nao ha kind`() {
        assertTrue(LiveCatalog.isLive(ch("A", "Filmes | X", kind = XtreamCatalog.KIND_LIVE)))
        assertFalse(LiveCatalog.isLive(ch("A", "Canais", kind = XtreamCatalog.KIND_MOVIE)))
        assertFalse(LiveCatalog.isLive(ch("A", "Canais", kind = XtreamCatalog.KIND_SERIES)))
        assertTrue(LiveCatalog.isLive(ch("A", "Esportes")))
        assertTrue(LiveCatalog.isLive(ch("A", "")))
    }

    @Test
    fun `grupo com nome de filme ou serie nao e ao vivo`() {
        assertEquals(LiveCatalog.GroupKind.MOVIE, LiveCatalog.groupKind("Filmes | Lançamentos"))
        assertEquals(LiveCatalog.GroupKind.MOVIE, LiveCatalog.groupKind("Cinema"))
        assertEquals(LiveCatalog.GroupKind.MOVIE, LiveCatalog.groupKind("VOD 4K"))
        assertEquals(LiveCatalog.GroupKind.SERIES, LiveCatalog.groupKind("Séries | Netflix"))
        assertEquals(LiveCatalog.GroupKind.SERIES, LiveCatalog.groupKind("series"))
        assertEquals(LiveCatalog.GroupKind.LIVE, LiveCatalog.groupKind("Esportes"))
    }

    // ---------- colapso por qualidade ----------

    @Test
    fun `qualidades do mesmo canal viram um item com a melhor primeiro`() {
        val s = LiveCatalog.build(listOf(
            ch("Globo SD", "A", url = "http://x/sd"),
            ch("Globo HD", "A", url = "http://x/hd"),
            ch("Globo FHD", "A", url = "http://x/fhd"),
        ))
        val c = s.channels.single()
        assertEquals("Globo", c.name)
        assertEquals("http://x/fhd", c.url)
        assertEquals(listOf("http://x/fhd", "http://x/hd", "http://x/sd"), c.variantUrls)
        assertEquals(listOf("FHD", "HD", "SD"), c.variantLabels)
    }

    @Test
    fun `canal sem irmaos perde o sufixo de qualidade e nao tem variantes`() {
        val c = LiveCatalog.build(listOf(ch("ESPN HD", "A", url = "http://x/espn"))).channels.single()
        assertEquals("ESPN", c.name)
        assertEquals("http://x/espn", c.url)
        assertTrue(c.variantUrls.isEmpty())
        assertTrue(c.variantLabels.isEmpty())
    }

    @Test
    fun `todos segue a ordem da primeira aparicao de cada canal`() {
        val s = LiveCatalog.build(listOf(ch("B HD", "A"), ch("A", "A"), ch("B SD", "A"), ch("C", "A")))
        assertEquals(listOf("B", "A", "C"), names(s))
    }

    @Test
    fun `logo e tvg id vem do primeiro irmao que tiver`() {
        val c = LiveCatalog.build(listOf(
            ch("Rede FHD", "A", logo = null, tvgId = null),
            ch("Rede HD", "A", logo = "http://x/logo.png", tvgId = "rede.br"),
        )).channels.single()
        assertEquals("http://x/logo.png", c.logo)
        assertEquals("rede.br", c.tvgId)
    }

    @Test
    fun `o id da api e o do melhor irmao`() {
        val c = LiveCatalog.build(listOf(
            ch("Rede HD", "A", apiId = 11), ch("Rede FHD", "A", apiId = 22),
        )).channels.single()
        assertEquals(22L, c.apiId)
    }

    // ---------- categorias ----------

    @Test
    fun `categorias da api seguem a ordem do painel sem o prefixo e sem as vazias`() {
        val live = listOf(
            ch("A", "TV | Notícias", kind = "live"),
            ch("B", "TV | Esportes", kind = "live"),
            ch("C", "TV | Outros", kind = "live"),
        )
        val s = LiveCatalog.build(live, apiOrder = listOf("TV | Esportes", "TV | Infantil", "TV | Notícias", "TV | Outros"))
        assertEquals(listOf("Esportes", "Notícias", "Outros"), cats(s))
        assertEquals("Esportes", s.channels[1].group)
    }

    @Test
    fun `categoria que a ordem do painel nao cita vem no fim`() {
        val live = listOf(ch("A", "Z", kind = "live"), ch("B", "Y", kind = "live"))
        assertEquals(listOf("Y", "Z"), cats(LiveCatalog.build(live, apiOrder = listOf("Y"))))
    }

    @Test
    fun `lista com categorias reais usa ordem alfabetica sem diferenciar maiusculas`() {
        val live = listOf(ch("A", "esportes"), ch("B", "Abertos"), ch("C", "Notícias"))
        assertEquals(listOf("Abertos", "esportes", "Notícias"), cats(LiveCatalog.build(live)))
    }

    @Test
    fun `sem categorias reais deduz pelo nome na ordem fixa`() {
        val live = listOf(ch("CNN Brasil", "CANAIS"), ch("Globo", "CANAIS"), ch("ESPN HD", "CANAIS"))
        val s = LiveCatalog.build(live)
        assertEquals(listOf("Esportes", "Notícias", "Abertos"), cats(s))
        assertEquals("Esportes", s.channels.first { it.name == "ESPN" }.group)
    }

    @Test
    fun `canal em duas categorias aparece nas duas e a principal e a do melhor irmao`() {
        val live = listOf(ch("Globo SD", "Abertos SD", kind = "live"), ch("Globo FHD", "Abertos HD", kind = "live"))
        val s = LiveCatalog.build(live, apiOrder = listOf("Abertos HD", "Abertos SD"))
        assertEquals(1, s.channels.size)
        assertEquals("Abertos HD", s.channels.single().group)
        assertEquals(listOf("Abertos HD", "Abertos SD"), cats(s))
        assertEquals(listOf(0), s.categories[0].indices)
        assertEquals(listOf(0), s.categories[1].indices)
    }

    @Test
    fun `categorias guardam indices na lista todos em ordem crescente`() {
        val live = listOf(ch("A", "X"), ch("B", "Y"), ch("C", "X"), ch("D", "Y"))
        val s = LiveCatalog.build(live)
        assertEquals(listOf(0, 2), s.categories.first { it.name == "X" }.indices)
        assertEquals(listOf(1, 3), s.categories.first { it.name == "Y" }.indices)
    }

    @Test
    fun `canal sem grupo entra so em todos quando ha categorias reais`() {
        val s = LiveCatalog.build(listOf(ch("A", ""), ch("B", "X"), ch("C", "Y")))
        assertEquals(listOf("X", "Y"), cats(s))
        assertEquals(3, s.channels.size)
    }

    @Test
    fun `categoria chamada todos ou favoritos nao vira linha extra`() {
        val s = LiveCatalog.build(listOf(ch("A", "todos"), ch("B", "Favoritos"), ch("C", "X"), ch("D", "Y")))
        assertEquals(listOf("X", "Y"), cats(s))
    }

    @Test
    fun `entrada vazia gera secao vazia`() {
        val s = LiveCatalog.build(emptyList())
        assertTrue(s.channels.isEmpty())
        assertTrue(s.categories.isEmpty())
    }

    // ---------- grupos do painel ----------

    @Test
    fun `todos vem primeiro com todos os indices e as categorias em seguida`() {
        val s = LiveCatalog.build(listOf(ch("A", "X"), ch("B", "Y")))
        val g = LiveCatalog.groupsOf(s, favorites = emptySet())
        assertEquals(listOf("Todos", "X", "Y"), g.map { it.name })
        assertEquals(listOf(0, 1), g.first().indices)
    }

    @Test
    fun `favoritos vem logo apos todos e junta canais por qualquer qualidade`() {
        val s = LiveCatalog.build(listOf(
            ch("A", "X", url = "http://x/a"),
            ch("B HD", "X", url = "http://x/b_hd"), ch("B SD", "X", url = "http://x/b_sd"),
            ch("C", "Y", url = "http://x/c"),
        ))
        val g = LiveCatalog.groupsOf(s, favorites = setOf("http://x/b_sd", "http://x/c", "http://x/fora"))
        assertEquals(listOf("Todos", "Favoritos", "X", "Y"), g.map { it.name })
        assertEquals(listOf(1, 2), g[1].indices)
    }

    @Test
    fun `sem nenhum favorito ao vivo a categoria nao aparece`() {
        val s = LiveCatalog.build(listOf(ch("A", "X", url = "http://x/a"), ch("B", "Y")))
        val g = LiveCatalog.groupsOf(s, favorites = setOf("http://x/filme.mp4"))
        assertEquals(listOf("Todos", "X", "Y"), g.map { it.name })
    }

    @Test
    fun `secao vazia ainda devolve todos vazio`() {
        val g = LiveCatalog.groupsOf(LiveCatalog.build(emptyList()), favorites = setOf("x"))
        assertEquals(listOf("Todos"), g.map { it.name })
        assertTrue(g.single().indices.isEmpty())
    }

    @Test
    fun `painel abre na categoria do canal e cai em todos quando nao acha`() {
        val s = LiveCatalog.build(listOf(ch("A", "X"), ch("B", "Y"), ch("C", "")))
        val g = LiveCatalog.groupsOf(s, favorites = emptySet())
        assertEquals(2, LiveCatalog.groupIndexOf(g, s.channels[1]))   // Todos, X, Y
        assertEquals(0, LiveCatalog.groupIndexOf(g, s.channels[2]))   // sem grupo
    }

    // ---------- achar o canal ----------

    @Test
    fun `acha o canal pela url dele ou de uma das qualidades`() {
        val s = LiveCatalog.build(listOf(
            ch("A", "X", url = "http://x/a"),
            ch("B HD", "X", url = "http://x/b_hd"), ch("B SD", "X", url = "http://x/b_sd"),
        ))
        assertEquals(0, LiveCatalog.indexOfUrl(s.channels, "http://x/a"))
        assertEquals(1, LiveCatalog.indexOfUrl(s.channels, "http://x/b_hd"))
        assertEquals(1, LiveCatalog.indexOfUrl(s.channels, "http://x/b_sd"))
        assertEquals(-1, LiveCatalog.indexOfUrl(s.channels, "http://x/nada"))
    }

    @Test
    fun `chave estavel usa o id da api e, sem ele, o nome sem diferenciar maiusculas`() {
        val s = LiveCatalog.build(listOf(ch("Globo HD", "X", apiId = 77), ch("SBT", "X"), ch("Band", "X")))
        assertEquals("api:77", LiveCatalog.keyOf(s.channels[0]))
        assertEquals("name:sbt", LiveCatalog.keyOf(s.channels[1]))
        assertEquals(0, LiveCatalog.indexOfKey(s.channels, "api:77"))
        assertEquals(1, LiveCatalog.indexOfKey(s.channels, "name:sbt"))
        assertEquals(2, LiveCatalog.indexOfKey(s.channels, "name:band"))
    }

    @Test
    fun `chave que nao existe mais ou vazia nao acha canal`() {
        val s = LiveCatalog.build(listOf(ch("SBT", "X")))
        assertEquals(-1, LiveCatalog.indexOfKey(s.channels, "api:999"))
        assertEquals(-1, LiveCatalog.indexOfKey(s.channels, ""))
    }
}
