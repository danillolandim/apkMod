package com.seupainel.player.playlist

import org.junit.After
import org.junit.Assert.assertEquals
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
}
