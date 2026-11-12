package com.seupainel.player.search

import com.seupainel.player.playlist.M3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DubLegGroupTest {

    private fun ch(name: String, url: String = "http://x/${name.hashCode()}") =
        M3uParser.Channel(name = name, url = url, group = "Filmes", duration = 0)

    @Test
    fun `dublado e legendado do mesmo filme viram um item com as duas versoes`() {
        val dub = ch("Duna")
        val leg = ch("Duna (LEG)")
        val g = DubLegGroup.group(listOf(leg, dub)).single()
        assertEquals(dub, g.dub)
        assertEquals(leg, g.leg)
        assertEquals(dub, g.rep)
        assertEquals("Duna", g.title)
    }

    @Test
    fun `so legendado fica como representante`() {
        val leg = ch("Duna LEGENDADO")
        val g = DubLegGroup.group(listOf(leg)).single()
        assertNull(g.dub)
        assertEquals(leg, g.rep)
        assertEquals("Duna", g.title)
    }

    @Test
    fun `filmes diferentes continuam separados e a ordem de chegada se mantem`() {
        val list = listOf(ch("B filme"), ch("A filme"), ch("B filme (LEG)"))
        val g = DubLegGroup.group(list)
        assertEquals(listOf("B filme", "A filme"), g.map { it.title })
    }

    @Test
    fun `maiuscula e acento nao separam versoes`() {
        val g = DubLegGroup.group(listOf(ch("Ação Total"), ch("AÇÃO TOTAL (LEG)")))
        assertEquals(1, g.size)
    }
}
