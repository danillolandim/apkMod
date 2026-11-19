package com.seupainel.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodScrubTest {

    private val min = 60_000L
    private val hour = 60 * min

    /** Filme de 2 h, no minuto 10, passo base de 10 s. */
    private fun scrub(pos: Long = 10 * min, dur: Long = 2 * hour, base: Long = 10_000L) =
        VodScrub(base).apply { begin(pos, dur) }

    // ---------- começar, mover, limites ----------

    @Test
    fun `begin deixa o alvo na posicao atual e ativa o scrub`() {
        val s = scrub()
        assertTrue(s.isActive)
        assertEquals(10 * min, s.targetMs)
    }

    @Test
    fun `antes do begin nao ha scrub e step nao faz nada`() {
        val s = VodScrub(10_000L)
        assertFalse(s.isActive)
        assertEquals(0L, s.step(+1, 0))
        assertFalse(s.isActive)
    }

    @Test
    fun `direita soma o passo base e esquerda subtrai`() {
        val s = scrub()
        assertEquals(10 * min + 10_000, s.step(+1, 0))
        assertEquals(10 * min, s.step(-1, 0))
        assertEquals(10 * min - 10_000, s.step(-1, 0))
    }

    @Test
    fun `o passo base vem das configuracoes`() {
        val s = scrub(base = 30_000L)
        assertEquals(10 * min + 30_000, s.step(+1, 0))
    }

    @Test
    fun `nao passa de zero`() {
        val s = scrub(pos = 4_000)
        assertEquals(0L, s.step(-1, 0))
        assertEquals(0L, s.step(-1, 20))
    }

    @Test
    fun `nao passa da duracao`() {
        val s = scrub(pos = 2 * hour - 4_000)
        assertEquals(2 * hour, s.step(+1, 0))
        assertEquals(2 * hour, s.step(+1, 20))
    }

    @Test
    fun `posicao inicial fora do filme e ajustada`() {
        assertEquals(2 * hour, scrub(pos = 3 * hour).targetMs)
        assertEquals(0L, scrub(pos = -5).targetMs)
    }

    @Test
    fun `direcao zero nao move`() {
        val s = scrub()
        assertEquals(10 * min, s.step(0, 0))
    }

    @Test
    fun `direcao e so o sinal`() {
        val s = scrub()
        assertEquals(10 * min + 10_000, s.step(5, 0))
        assertEquals(10 * min, s.step(-9, 0))
    }

    // ---------- aceleração por tecla segurada ----------

    @Test
    fun `de 0 a 4 repeticoes usa o passo base`() {
        for (r in 0..4) assertEquals("repeat $r", 10_000L, VodScrub.stepFor(10_000L, r))
    }

    @Test
    fun `de 5 a 14 repeticoes anda 30 segundos`() {
        for (r in 5..14) assertEquals("repeat $r", 30_000L, VodScrub.stepFor(10_000L, r))
    }

    @Test
    fun `a partir de 15 repeticoes anda 60 segundos`() {
        for (r in listOf(15, 16, 40, 500)) assertEquals("repeat $r", 60_000L, VodScrub.stepFor(10_000L, r))
    }

    @Test
    fun `passo base maior que a faixa vale o base`() {
        assertEquals(60_000L, VodScrub.stepFor(60_000L, 0))
        assertEquals(60_000L, VodScrub.stepFor(60_000L, 7))
        assertEquals(60_000L, VodScrub.stepFor(60_000L, 30))
        assertEquals(90_000L, VodScrub.stepFor(90_000L, 30))
    }

    @Test
    fun `step acumula com a aceleracao`() {
        val s = scrub()
        assertEquals(10 * min + 10_000, s.step(+1, 0))             // +10 s
        assertEquals(10 * min + 40_000, s.step(+1, 5))             // +30 s
        assertEquals(10 * min + 100_000, s.step(+1, 15))           // +60 s
    }

    @Test
    fun `repeticao negativa conta como zero`() {
        assertEquals(10_000L, VodScrub.stepFor(10_000L, -3))
    }

    // ---------- pulo fixo (teclas FF/REW/NEXT/PREV) ----------

    @Test
    fun `jump soma o valor e respeita os limites`() {
        val s = scrub()
        assertEquals(10 * min + 60_000, s.jump(+60_000))
        assertEquals(10 * min, s.jump(-60_000))
        assertEquals(0L, s.jump(-hour))
        assertEquals(2 * hour, s.jump(+3 * hour))
    }

    @Test
    fun `jump sem begin nao faz nada`() {
        assertEquals(0L, VodScrub(10_000L).jump(+60_000))
    }

    // ---------- arrastar com o dedo (celular) ----------

    @Test
    fun `moveTo vai direto ao ponto e respeita os limites`() {
        val s = scrub()
        assertEquals(45 * min, s.moveTo(45 * min))
        assertEquals(0L, s.moveTo(-5_000))
        assertEquals(2 * hour, s.moveTo(5 * hour))
    }

    @Test
    fun `moveTo sem begin nao faz nada`() {
        assertEquals(0L, VodScrub(10_000L).moveTo(30 * min))
    }

    @Test
    fun `depois de arrastar o cancel ainda volta ao inicio`() {
        val s = scrub()
        s.moveTo(90 * min)
        assertEquals(10 * min, s.cancel())
    }

    @Test
    fun `moveTo e step se combinam`() {
        val s = scrub()
        s.moveTo(30 * min)
        assertEquals(30 * min + 10_000, s.step(+1, 0))
        assertEquals(30 * min + 10_000, s.commit())
    }

    @Test
    fun `moveTo com duracao desconhecida limita so em zero`() {
        val s = VodScrub(10_000L).apply { begin(5_000, -1) }
        assertEquals(10 * hour, s.moveTo(10 * hour))
        assertEquals(0L, s.moveTo(-1))
    }

    // ---------- duração desconhecida ----------

    @Test
    fun `duracao desconhecida limita so em zero`() {
        // C.TIME_UNSET = Long.MIN_VALUE + 1; 0 e negativos também valem como desconhecida
        for (dur in listOf(Long.MIN_VALUE + 1, -1L, 0L)) {
            val s = VodScrub(10_000L).apply { begin(5_000, dur) }
            assertEquals("dur $dur", 0L, s.step(-1, 0))
            assertEquals("dur $dur", 100 * hour, s.jump(100 * hour))
        }
    }

    @Test
    fun `duracao desconhecida mantem a posicao inicial`() {
        assertEquals(5_000L, VodScrub(10_000L).apply { begin(5_000, -1) }.targetMs)
    }

    // ---------- confirmar e cancelar ----------

    @Test
    fun `commit devolve o alvo e encerra`() {
        val s = scrub()
        s.step(+1, 0)
        assertEquals(10 * min + 10_000, s.commit())
        assertFalse(s.isActive)
    }

    @Test
    fun `cancel devolve a posicao original e encerra`() {
        val s = scrub()
        s.step(+1, 0); s.step(+1, 5); s.step(+1, 15)
        assertEquals(10 * min, s.cancel())
        assertFalse(s.isActive)
    }

    @Test
    fun `commit e cancel sem scrub devolvem null`() {
        val s = VodScrub(10_000L)
        assertEquals(null, s.commit())
        assertEquals(null, s.cancel())
    }

    @Test
    fun `depois de encerrar o step e ignorado ate novo begin`() {
        val s = scrub()
        s.commit()
        assertEquals(10 * min, s.step(+1, 0))
        s.begin(20 * min, 2 * hour)
        assertEquals(20 * min + 10_000, s.step(+1, 0))
    }

    @Test
    fun `begin de novo recomeca do zero`() {
        val s = scrub()
        s.step(+1, 15)
        s.begin(30 * min, 2 * hour)
        assertEquals(30 * min, s.targetMs)
        assertEquals(30 * min, s.cancel())
    }

    // ---------- formatação ----------

    @Test
    fun `fmt de minutos e segundos`() {
        assertEquals("0:00", VodScrub.fmt(0))
        assertEquals("0:09", VodScrub.fmt(9_000))
        assertEquals("5:03", VodScrub.fmt(5 * min + 3_000))
        assertEquals("32:10", VodScrub.fmt(32 * min + 10_000))
        assertEquals("59:59", VodScrub.fmt(hour - 1_000))
    }

    @Test
    fun `fmt com horas`() {
        assertEquals("1:00:00", VodScrub.fmt(hour))
        assertEquals("1:02:15", VodScrub.fmt(hour + 2 * min + 15_000))
        assertEquals("12:00:01", VodScrub.fmt(12 * hour + 1_000))
    }

    @Test
    fun `fmt descarta a fracao de segundo e nao mostra negativo`() {
        assertEquals("0:01", VodScrub.fmt(1_999))
        assertEquals("0:00", VodScrub.fmt(-5_000))
    }

    @Test
    fun `fmtRemaining mostra quanto falta com sinal de menos`() {
        assertEquals("-1:02:15", VodScrub.fmtRemaining(hour - 2 * min - 15_000, 2 * hour))
        assertEquals("-30:00", VodScrub.fmtRemaining(30 * min, hour))
        assertEquals("-0:00", VodScrub.fmtRemaining(hour, hour))
    }

    @Test
    fun `fmtRemaining arredonda para cima para fechar a conta com o decorrido`() {
        // 0:01 decorrido + 59:59 restante = 1:00:00
        assertEquals("0:01", VodScrub.fmt(1_500))
        assertEquals("-59:59", VodScrub.fmtRemaining(1_500, hour))
    }

    @Test
    fun `fmtRemaining passando da duracao fica em zero`() {
        assertEquals("-0:00", VodScrub.fmtRemaining(hour + 5_000, hour))
    }

    @Test
    fun `fmtRemaining sem duracao conhecida devolve vazio`() {
        assertEquals("", VodScrub.fmtRemaining(5_000, -1))
        assertEquals("", VodScrub.fmtRemaining(5_000, 0))
        assertEquals("", VodScrub.fmtRemaining(5_000, Long.MIN_VALUE + 1))
    }
}
