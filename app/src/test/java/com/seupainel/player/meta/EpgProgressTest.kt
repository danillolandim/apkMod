package com.seupainel.player.meta

import com.seupainel.player.meta.Epg.Prog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EpgProgressTest {

    private val min = 60_000L
    private val jornal = Prog(start = 1_000 * min, stop = 1_030 * min, title = "Jornal")
    private val novela = Prog(start = 1_030 * min, stop = 1_090 * min, title = "Novela")

    @Test
    fun `progresso no meio do programa e a fracao ja transcorrida`() {
        assertEquals(0.5f, Epg.progressOf(jornal, now = 1_015 * min), 0.0001f)
        assertEquals(0.25f, Epg.progressOf(novela, now = 1_045 * min), 0.0001f)
    }

    @Test
    fun `progresso no inicio e zero e no fim e um`() {
        assertEquals(0f, Epg.progressOf(jornal, now = jornal.start), 0f)
        assertEquals(1f, Epg.progressOf(jornal, now = jornal.stop), 0f)
    }

    @Test
    fun `progresso nunca sai de zero a um mesmo com relogio fora do programa`() {
        assertEquals(0f, Epg.progressOf(jornal, now = jornal.start - 10 * min), 0f)
        assertEquals(1f, Epg.progressOf(jornal, now = jornal.stop + 10 * min), 0f)
    }

    @Test
    fun `programa com duracao invalida nao divide por zero`() {
        assertEquals(0f, Epg.progressOf(Prog(start = 500, stop = 500, title = "x"), now = 500), 0f)
        assertEquals(0f, Epg.progressOf(Prog(start = 500, stop = 100, title = "x"), now = 500), 0f)
    }

    @Test
    fun `agora e a seguir saem da lista pelo instante recebido`() {
        val list = listOf(jornal, novela)
        val (cur, nxt) = Epg.nowNextIn(list, now = 1_010 * min)
        assertSame(jornal, cur)
        assertSame(novela, nxt)
    }

    @Test
    fun `no ultimo programa nao ha a seguir`() {
        val (cur, nxt) = Epg.nowNextIn(listOf(jornal, novela), now = 1_050 * min)
        assertSame(novela, cur)
        assertNull(nxt)
    }

    @Test
    fun `intervalo sem programa devolve so o proximo`() {
        val depois = Prog(start = 1_100 * min, stop = 1_130 * min, title = "Filme")
        val (cur, nxt) = Epg.nowNextIn(listOf(jornal, depois), now = 1_050 * min)
        assertNull(cur)
        assertSame(depois, nxt)
    }

    @Test
    fun `antes de tudo ou depois de tudo nao ha agora nem a seguir`() {
        val list = listOf(jornal, novela)
        assertEquals(null to jornal, Epg.nowNextIn(list, now = 900 * min))
        assertEquals(null to null, Epg.nowNextIn(list, now = 2_000 * min))
    }

    @Test
    fun `canal sem guia carregado nao tem progresso`() {
        assertNull(Epg.progress("qualquer.id", now = 1_015 * min))
        assertNull(Epg.progress(null, now = 1_015 * min))
        assertNull(Epg.progress("   ", now = 1_015 * min))
    }
}
