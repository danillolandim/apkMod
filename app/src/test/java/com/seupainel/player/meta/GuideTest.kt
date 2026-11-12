package com.seupainel.player.meta

import com.seupainel.player.meta.Epg.Prog
import com.seupainel.player.meta.Guide.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTest {

    private val min = 60_000L
    private val now = 1_000 * min
    private val jornal = Prog(start = now - 10 * min, stop = now + 20 * min, title = "Jornal")
    private val novela = Prog(start = now + 20 * min, stop = now + 80 * min, title = "Novela")
    private val filme = Prog(start = now + 80 * min, stop = now + 200 * min, title = "Filme")

    private fun entry(vararg p: Prog) = ProgramCache.Entry(p.toList(), at = 0L, ok = true)

    // ---------- de onde vem a programação ----------

    @Test
    fun `xmltv com dado para o canal vence a api`() {
        val l = Guide.resolve(xml = listOf(jornal, novela), cached = entry(filme), canFetch = true, fresh = true, upcoming = 3, now = now)
        assertEquals(Phase.READY, l.phase)
        assertEquals("Jornal", l.agenda.current?.title)
        assertEquals(listOf("Novela"), l.agenda.upcoming.map { it.title })
    }

    @Test
    fun `xmltv sem programa util cai para a api`() {
        val velho = Prog(start = now - 300 * min, stop = now - 200 * min, title = "Velho")
        val l = Guide.resolve(xml = listOf(velho), cached = entry(jornal, novela), canFetch = true, fresh = true, upcoming = 3, now = now)
        assertEquals("Jornal", l.agenda.current?.title)
    }

    @Test
    fun `sem xmltv usa o que a api trouxe e entrega atual mais os proximos pedidos`() {
        val l = Guide.resolve(xml = null, cached = entry(jornal, novela, filme), canFetch = true, fresh = true, upcoming = 3, now = now)
        assertEquals(Phase.READY, l.phase)
        assertEquals("Jornal", l.agenda.current?.title)
        assertEquals(listOf("Novela", "Filme"), l.agenda.upcoming.map { it.title })
    }

    @Test
    fun `dado velho que ainda cobre o horario continua valendo enquanto atualiza`() {
        val l = Guide.resolve(xml = null, cached = entry(jornal, novela), canFetch = true, fresh = false, upcoming = 1, now = now)
        assertEquals(Phase.READY, l.phase)
    }

    // ---------- carregando x sem informacao ----------

    @Test
    fun `sem dado e com consulta possivel mostra carregando`() {
        val l = Guide.resolve(xml = null, cached = null, canFetch = true, fresh = false, upcoming = 3, now = now)
        assertEquals(Phase.LOADING, l.phase)
        assertNull(l.agenda.current)
    }

    @Test
    fun `consulta feita e vazia vira sem informacao e nao fica carregando para sempre`() {
        val l = Guide.resolve(xml = null, cached = entry(), canFetch = true, fresh = true, upcoming = 3, now = now)
        assertEquals(Phase.NONE, l.phase)
    }

    @Test
    fun `sem conta e sem xmltv e sem informacao`() {
        val l = Guide.resolve(xml = null, cached = null, canFetch = false, fresh = false, upcoming = 3, now = now)
        assertEquals(Phase.NONE, l.phase)
    }

    @Test
    fun `dado todo no passado e sem consulta fresca volta a carregar`() {
        val passado = Prog(start = now - 100 * min, stop = now - 50 * min, title = "Passado")
        val l = Guide.resolve(xml = null, cached = entry(passado), canFetch = true, fresh = false, upcoming = 3, now = now)
        assertEquals(Phase.LOADING, l.phase)
    }

    // ---------- textos ----------

    @Test
    fun `linha do agora mostra o horario e o titulo`() {
        val l = Guide.resolve(xml = listOf(jornal), cached = null, canFetch = false, fresh = false, upcoming = 0, now = now)
        assertEquals("${Epg.hhmm(jornal.start)}–${Epg.hhmm(jornal.stop)}  Jornal", Guide.nowLine(l))
    }

    @Test
    fun `linha do agora distingue so o proximo, carregando e sem informacao`() {
        val soProximo = Guide.resolve(xml = listOf(novela), cached = null, canFetch = false, fresh = false, upcoming = 1, now = now)
        assertEquals("Sem informação do programa atual", Guide.nowLine(soProximo))
        val carregando = Guide.resolve(xml = null, cached = null, canFetch = true, fresh = false, upcoming = 1, now = now)
        assertEquals("Carregando programação…", Guide.nowLine(carregando))
        val nada = Guide.resolve(xml = null, cached = null, canFetch = false, fresh = false, upcoming = 1, now = now)
        assertEquals("Sem informação de programação", Guide.nowLine(nada))
    }

    // ---------- cache ----------

    private class Clock(var t: Long = 0L) { fun read() = t }

    private fun cache(clock: Clock) = ProgramCache(ttlMs = 10 * min, failTtlMs = 1 * min, clock = clock::read)

    @Test
    fun `cache guarda ate o ttl e depois pede de novo`() {
        val clock = Clock()
        val c = cache(clock)
        assertTrue(c.tryBegin(7))
        c.finish(7, listOf(jornal), ok = true)
        assertFalse(c.tryBegin(7))            // fresco
        assertTrue(c.isFresh(7))
        clock.t = 10 * min - 1
        assertFalse(c.tryBegin(7))
        clock.t = 10 * min
        assertFalse(c.isFresh(7))
        assertTrue(c.tryBegin(7))             // venceu
        assertEquals(listOf(jornal), c.entry(7)?.programs)   // o antigo continua legível enquanto busca
    }

    @Test
    fun `so uma consulta por canal ao mesmo tempo`() {
        val c = cache(Clock())
        assertTrue(c.tryBegin(1))
        assertFalse(c.tryBegin(1))            // ainda buscando
        assertTrue(c.isFetching(1))
        assertTrue(c.tryBegin(2))             // outro canal não espera
        c.finish(1, emptyList(), ok = true)
        assertFalse(c.isFetching(1))
        assertTrue(c.isFetching(2))
    }

    @Test
    fun `falha vale por pouco tempo e nao apaga o dado bom anterior`() {
        val clock = Clock()
        val c = cache(clock)
        c.tryBegin(1); c.finish(1, listOf(jornal), ok = true)
        clock.t = 10 * min
        assertTrue(c.tryBegin(1))
        c.finish(1, emptyList(), ok = false)
        assertEquals(listOf(jornal), c.entry(1)?.programs)
        assertTrue(c.isFresh(1))
        clock.t = 10 * min + 59_999
        assertFalse(c.tryBegin(1))
        clock.t = 11 * min
        assertTrue(c.tryBegin(1))
    }

    @Test
    fun `falha na primeira consulta guarda vazio por pouco tempo`() {
        val clock = Clock()
        val c = cache(clock)
        c.tryBegin(3); c.finish(3, emptyList(), ok = false)
        assertTrue(c.entry(3)?.programs?.isEmpty() == true)
        assertFalse(c.tryBegin(3))
        clock.t = 1 * min
        assertTrue(c.tryBegin(3))
    }

    @Test
    fun `clear esquece tudo`() {
        val c = cache(Clock())
        c.tryBegin(1); c.finish(1, listOf(jornal), ok = true)
        c.clear()
        assertNull(c.entry(1))
        assertTrue(c.tryBegin(1))
    }
}
