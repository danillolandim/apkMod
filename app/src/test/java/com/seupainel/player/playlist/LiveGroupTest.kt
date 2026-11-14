package com.seupainel.player.playlist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGroupTest {

    private fun ch(name: String) = LiveChannel(
        name = name, url = "http://x/$name", logo = "", tvgId = "", apiId = 0L, group = "",
        userAgent = null, referer = null, variantUrls = emptyList(), variantLabels = emptyList(),
    )

    @After
    fun limpa() = LiveSession.clear()

    @Test
    fun `positionOf acha a posicao do canal dentro do grupo ou menos um`() {
        val y = LiveGroup("Y", listOf(1, 2))
        assertEquals(1, y.positionOf(2))
        assertEquals(-1, y.positionOf(0))
    }

    @Test
    fun `canal com uma qualidade tem so a propria url e com varias tem todas`() {
        assertEquals(listOf("http://x/a"), ch("a").allUrls)
        val many = ch("b").copy(url = "http://x/hd", variantUrls = listOf("http://x/hd", "http://x/sd"))
        assertEquals(listOf("http://x/hd", "http://x/sd"), many.allUrls)
    }

    @Test
    fun `sessao sem grupos informados tem so todos com a lista inteira`() {
        LiveSession.set(listOf(ch("a"), ch("b")), index = 0)
        assertEquals(listOf("Todos"), LiveSession.groups.map { it.name })
        assertEquals(listOf(0, 1), LiveSession.groups.single().indices)
    }

    @Test
    fun `sessao guarda os grupos recebidos e clear volta a so todos vazio`() {
        val groups = listOf(LiveGroup("Todos", listOf(0, 1)), LiveGroup("Favoritos", listOf(1)))
        LiveSession.set(listOf(ch("a"), ch("b")), index = 1, groups = groups)
        assertEquals(groups, LiveSession.groups)
        LiveSession.clear()
        assertEquals(listOf("Todos"), LiveSession.groups.map { it.name })
        assertTrue(LiveSession.groups.single().indices.isEmpty())
    }

    @Test
    fun `step anda dentro da categoria e da a volta nas pontas`() {
        val g = LiveGroup("4K", listOf(7, 2, 40))
        assertEquals(2, g.step(7, +1))
        assertEquals(40, g.step(2, +1))
        assertEquals(7, g.step(40, +1))
        assertEquals(40, g.step(7, -1))
        assertNull(g.step(99, +1))
        assertNull(LiveGroup("Vazia", emptyList()).step(0, +1))
    }

    @Test
    fun `zapping fica na categoria por onde a pessoa chegou e nao na da melhor qualidade`() {
        fun ch(name: String, group: String) = LiveChannel(name, "http://x/$name", "", "", 0L, group, null, null, emptyList(), emptyList())
        // O canal 1 tem a melhor qualidade em "4K", mas também está em "Esportes".
        val channels = listOf(ch("a", "Esportes"), ch("b", "4K"), ch("c", "Esportes"))
        val groups = listOf(
            LiveGroup(LiveGroup.ALL, listOf(0, 1, 2)), LiveGroup("4K", listOf(1)), LiveGroup("Esportes", listOf(0, 1, 2)),
        )
        assertEquals(2, LiveCatalog.zapGroupOf(groups, channels, 1, listOf("Esportes")))
        assertEquals(2, LiveCatalog.zapGroupOf(groups, channels, 1, listOf("", "esportes")))
        // Sem pista, ou com uma categoria que não tem o canal: a do próprio canal.
        assertEquals(1, LiveCatalog.zapGroupOf(groups, channels, 1, emptyList()))
        assertEquals(2, LiveCatalog.zapGroupOf(groups, channels, 0, listOf("4K")))
    }
}
