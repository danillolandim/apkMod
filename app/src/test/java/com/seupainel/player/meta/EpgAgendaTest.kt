package com.seupainel.player.meta

import com.seupainel.player.meta.Epg.Prog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgAgendaTest {

    private val min = 60_000L
    private fun p(startMin: Long, stopMin: Long, title: String) = Prog(startMin * min, stopMin * min, title)

    private val a = p(1_000, 1_030, "A")
    private val b = p(1_030, 1_060, "B")
    private val c = p(1_060, 1_090, "C")
    private val d = p(1_090, 1_120, "D")
    private val e = p(1_120, 1_150, "E")
    private val all = listOf(a, b, c, d, e)

    @Test
    fun `agora mais os proximos tres`() {
        val ag = Epg.agendaIn(all, now = 1_010 * min, upcoming = 3)
        assertSame(a, ag.current)
        assertEquals(listOf(b, c, d), ag.upcoming)
    }

    @Test
    fun `no meio da lista o atual e o seguinte nao se misturam`() {
        val ag = Epg.agendaIn(all, now = 1_065 * min, upcoming = 3)
        assertSame(c, ag.current)
        assertEquals(listOf(d, e), ag.upcoming)
    }

    @Test
    fun `no ultimo programa nao ha proximos`() {
        val ag = Epg.agendaIn(all, now = 1_130 * min, upcoming = 3)
        assertSame(e, ag.current)
        assertTrue(ag.upcoming.isEmpty())
    }

    @Test
    fun `intervalo sem programa atual lista os que ainda vao comecar`() {
        val depois = p(1_200, 1_230, "Depois")
        val ag = Epg.agendaIn(listOf(a, depois, p(1_230, 1_260, "Mais tarde")), now = 1_100 * min, upcoming = 3)
        assertNull(ag.current)
        assertEquals(listOf("Depois", "Mais tarde"), ag.upcoming.map { it.title })
    }

    @Test
    fun `antes do primeiro programa nao ha atual e vem os primeiros`() {
        val ag = Epg.agendaIn(all, now = 900 * min, upcoming = 2)
        assertNull(ag.current)
        assertEquals(listOf(a, b), ag.upcoming)
    }

    @Test
    fun `depois do ultimo programa a agenda e vazia`() {
        val ag = Epg.agendaIn(all, now = 5_000 * min, upcoming = 3)
        assertNull(ag.current)
        assertTrue(ag.upcoming.isEmpty())
    }

    @Test
    fun `pedir zero proximos devolve so o atual`() {
        val ag = Epg.agendaIn(all, now = 1_010 * min, upcoming = 0)
        assertSame(a, ag.current)
        assertTrue(ag.upcoming.isEmpty())
    }

    @Test
    fun `lista vazia e canal sem guia carregado dao agenda vazia`() {
        assertNull(Epg.agendaIn(emptyList(), now = 1, upcoming = 3).current)
        val ag = Epg.agenda("qualquer.id", upcoming = 3, now = 1_010 * min)
        assertNull(ag.current)
        assertTrue(ag.upcoming.isEmpty())
        assertNull(Epg.agenda(null, upcoming = 3, now = 1_010 * min).current)
    }
}
