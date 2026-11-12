package com.seupainel.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGroupTest {

    private fun ch(name: String, group: String) = LiveChannel(
        name = name, url = "http://x/$name", logo = "", tvgId = "", apiId = 0L, group = group,
        userAgent = null, referer = null, variantUrls = emptyList(), variantLabels = emptyList(),
    )

    private fun names(groups: List<LiveGroup>) = groups.map { it.name }

    @Test
    fun `todos e sempre a primeira e traz todos os indices na ordem da lista`() {
        val g = LiveGroup.groupsOf(listOf(ch("a", "X"), ch("b", "Y"), ch("c", "X")))
        assertEquals(LiveGroup.ALL, g.first().name)
        assertEquals(listOf(0, 1, 2), g.first().indices)
    }

    @Test
    fun `categorias guardam o indice na lista todos e seguem a ordem da primeira aparicao`() {
        val g = LiveGroup.groupsOf(listOf(ch("a", "Esportes"), ch("b", "Filmes"), ch("c", "Esportes"), ch("d", "Filmes")))
        assertEquals(listOf("Todos", "Esportes", "Filmes"), names(g))
        assertEquals(listOf(0, 2), g[1].indices)
        assertEquals(listOf(1, 3), g[2].indices)
    }

    @Test
    fun `ordem preferida da tela vem primeiro e o resto segue a primeira aparicao`() {
        val list = listOf(ch("a", "Filmes"), ch("b", "Abertos"), ch("c", "Esportes"), ch("d", "Outros"))
        val g = LiveGroup.groupsOf(list, order = listOf("Esportes", "Inexistente", "Abertos"))
        assertEquals(listOf("Todos", "Esportes", "Abertos", "Filmes", "Outros"), names(g))
    }

    @Test
    fun `sem grupos so existe todos`() {
        val g = LiveGroup.groupsOf(listOf(ch("a", ""), ch("b", "   ")))
        assertEquals(listOf("Todos"), names(g))
        assertEquals(listOf(0, 1), g.single().indices)
    }

    @Test
    fun `canal sem grupo aparece so em todos e os demais continuam agrupados`() {
        val g = LiveGroup.groupsOf(listOf(ch("a", ""), ch("b", "Filmes")))
        assertEquals(listOf("Todos", "Filmes"), names(g))
        assertEquals(listOf(1), g[1].indices)
    }

    @Test
    fun `lista vazia devolve so todos vazio`() {
        val g = LiveGroup.groupsOf(emptyList())
        assertEquals(listOf("Todos"), names(g))
        assertTrue(g.single().indices.isEmpty())
    }

    @Test
    fun `nomes de grupo sao comparados sem espacos nas pontas`() {
        val g = LiveGroup.groupsOf(listOf(ch("a", "Filmes"), ch("b", " Filmes ")))
        assertEquals(listOf("Todos", "Filmes"), names(g))
        assertEquals(listOf(0, 1), g[1].indices)
    }

    @Test
    fun `categoria chamada todos nao duplica a primeira linha`() {
        val g = LiveGroup.groupsOf(listOf(ch("a", "todos"), ch("b", "Filmes")))
        assertEquals(listOf("Todos", "Filmes"), names(g))
    }

    @Test
    fun `positionOf acha a posicao do canal dentro do grupo ou menos um`() {
        val g = LiveGroup.groupsOf(listOf(ch("a", "X"), ch("b", "Y"), ch("c", "Y")))
        val y = g.first { it.name == "Y" }
        assertEquals(1, y.positionOf(2))
        assertEquals(-1, y.positionOf(0))
    }

    @Test
    fun `sessao calcula os grupos ao receber a lista`() {
        LiveSession.set(listOf(ch("a", "X"), ch("b", "Y")), index = 0, categoryOrder = listOf("Y", "X"))
        assertEquals(listOf("Todos", "Y", "X"), names(LiveSession.groups))
        LiveSession.clear()
        assertEquals(listOf("Todos"), names(LiveSession.groups))
    }
}
