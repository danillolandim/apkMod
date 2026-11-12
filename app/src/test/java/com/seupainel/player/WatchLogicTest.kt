package com.seupainel.player

import com.seupainel.player.WatchLogic.Ep
import com.seupainel.player.WatchLogic.PickKind
import com.seupainel.player.WatchLogic.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLogicTest {

    private fun ep(s: Int, e: Int) = Ep(s, e, "s${s}e$e")

    /** T1 com 3 episódios e T2 com 2, fora de ordem de propósito. */
    private val eps = listOf(ep(2, 2), ep(1, 1), ep(2, 1), ep(1, 3), ep(1, 2))

    private fun pick(
        watched: Set<String> = emptySet(),
        progress: Map<String, Int> = emptyMap(),
        touched: Map<String, Long> = emptyMap(),
        list: List<Ep> = eps,
    ) = WatchLogic.pickNext(list, { it in watched }, { progress[it] ?: 0 }, { touched[it] ?: 0L })

    // ---------- contagem por temporada ----------

    @Test
    fun `conta vistos e total por temporada, em ordem`() {
        val c = WatchLogic.seasonCounts(eps) { it in setOf("s1e1", "s1e2", "s1e3", "s2e1") }
        assertEquals(listOf(WatchLogic.SeasonCount(1, 3, 3), WatchLogic.SeasonCount(2, 1, 2)), c)
        assertTrue(c[0].complete)
        assertFalse(c[1].complete)
    }

    @Test
    fun `temporada vazia nunca e concluida`() {
        assertFalse(WatchLogic.SeasonCount(1, 0, 0).complete)
    }

    @Test
    fun `rotulo da pilula e descricao falada`() {
        assertEquals("T1 · 10/10", WatchLogic.seasonLabel(WatchLogic.SeasonCount(1, 10, 10)))
        assertEquals("T2 · 3/8", WatchLogic.seasonLabel(WatchLogic.SeasonCount(2, 3, 8)))
        assertEquals("Temporada 1, concluída, 10 de 10 episódios", WatchLogic.seasonDescription(WatchLogic.SeasonCount(1, 10, 10)))
        assertEquals("Temporada 2, 3 de 8 episódios vistos", WatchLogic.seasonDescription(WatchLogic.SeasonCount(2, 3, 8)))
    }

    @Test
    fun `resumo do progresso da serie`() {
        assertEquals("Você viu 7 de 10 episódios", WatchLogic.summary(7, 10))
        assertEquals("Você ainda não começou · 10 episódios", WatchLogic.summary(0, 10))
        assertEquals("Você viu todos os 10 episódios", WatchLogic.summary(10, 10))
        assertEquals("Você ainda não começou · 1 episódio", WatchLogic.summary(0, 1))
        assertEquals("Você viu o único episódio", WatchLogic.summary(1, 1))
        assertEquals("", WatchLogic.summary(0, 0))
    }

    // ---------- próximo episódio ----------

    @Test
    fun `sem nada visto comeca no primeiro episodio`() {
        val p = pick() ?: error("sem episódio")
        assertEquals(ep(1, 1), p.ep)
        assertEquals(PickKind.START, p.kind)
        assertEquals("Assistir T1 · E1", WatchLogic.primaryLabel(p))
    }

    @Test
    fun `depois do ultimo visto vem o seguinte, atravessando a temporada`() {
        assertEquals(ep(1, 3), pick(watched = setOf("s1e1", "s1e2"))?.ep)
        val p = pick(watched = setOf("s1e1", "s1e2", "s1e3")) ?: error("sem episódio")
        assertEquals(ep(2, 1), p.ep)
        assertEquals(PickKind.NEXT, p.kind)
        assertEquals("Continuar T2 · E1", WatchLogic.primaryLabel(p))
    }

    @Test
    fun `episodio pulado antes do ultimo visto nao puxa o proximo para tras`() {
        // viu só o E2: o próximo é o E3, não o E1 que ficou para trás
        assertEquals(ep(1, 3), pick(watched = setOf("s1e2"))?.ep)
    }

    @Test
    fun `episodio em andamento vence e continua dele`() {
        val p = pick(watched = setOf("s1e1"), progress = mapOf("s1e2" to 32)) ?: error("sem episódio")
        assertEquals(ep(1, 2), p.ep)
        assertEquals(PickKind.RESUME, p.kind)
        assertEquals("Continuar T1 · E2", WatchLogic.primaryLabel(p))
    }

    @Test
    fun `com dois em andamento escolhe o mexido por ultimo`() {
        val p = pick(progress = mapOf("s1e2" to 10, "s2e1" to 50), touched = mapOf("s1e2" to 900L, "s2e1" to 100L))
        assertEquals(ep(1, 2), p?.ep)
    }

    @Test
    fun `tudo visto convida a rever desde o inicio`() {
        val p = pick(watched = eps.map { it.key }.toSet()) ?: error("sem episódio")
        assertEquals(ep(1, 1), p.ep)
        assertEquals(PickKind.REWATCH, p.kind)
        assertEquals("Rever T1 · E1", WatchLogic.primaryLabel(p))
    }

    @Test
    fun `ultimo episodio visto mas com buraco antes aponta o buraco`() {
        val p = pick(watched = setOf("s1e1", "s1e3", "s2e1", "s2e2")) ?: error("sem episódio")
        assertEquals(ep(1, 2), p.ep)
        assertEquals(PickKind.NEXT, p.kind)
    }

    @Test
    fun `lista vazia nao tem proximo`() {
        assertNull(pick(list = emptyList()))
    }

    // ---------- estado de cada episódio ----------

    @Test
    fun `estado do episodio sempre tem uma resposta so`() {
        assertEquals(State.IN_PROGRESS, WatchLogic.stateOf(watched = true, progressPct = 40, isNext = true))
        assertEquals(State.WATCHED, WatchLogic.stateOf(watched = true, progressPct = 0, isNext = false))
        assertEquals(State.IN_PROGRESS, WatchLogic.stateOf(watched = false, progressPct = 12, isNext = false))
        assertEquals(State.NEXT, WatchLogic.stateOf(watched = false, progressPct = 0, isNext = true))
        assertEquals(State.UNWATCHED, WatchLogic.stateOf(watched = false, progressPct = 0, isNext = false))
    }

    @Test
    fun `so o proximo de verdade ganha o destaque a seguir`() {
        val start = WatchLogic.Pick(ep(1, 1), PickKind.START)
        val resume = WatchLogic.Pick(ep(1, 2), PickKind.RESUME)
        val rewatch = WatchLogic.Pick(ep(1, 1), PickKind.REWATCH)
        assertTrue(WatchLogic.isHighlighted(start, ep(1, 1)))
        assertFalse(WatchLogic.isHighlighted(start, ep(1, 2)))
        assertFalse(WatchLogic.isHighlighted(resume, ep(1, 2)))   // já é "em andamento"
        assertFalse(WatchLogic.isHighlighted(rewatch, ep(1, 1)))
        assertFalse(WatchLogic.isHighlighted(null, ep(1, 1)))
    }

    @Test
    fun `descricao falada do episodio anuncia o estado`() {
        assertEquals("Episódio 4, assistido", WatchLogic.episodeDescription(4, "", State.WATCHED, null))
        assertEquals("Episódio 4, Piloto, em andamento, restam 12 min", WatchLogic.episodeDescription(4, "Piloto", State.IN_PROGRESS, "Restam 12 min"))
        assertEquals("Episódio 5, a seguir", WatchLogic.episodeDescription(5, "", State.NEXT, null))
        assertEquals("Episódio 6, não assistido", WatchLogic.episodeDescription(6, "", State.UNWATCHED, null))
    }

    // ---------- tempo ----------

    @Test
    fun `relogio do ponto em que parou`() {
        assertEquals("0:45", WatchLogic.clock(45_000))
        assertEquals("32:10", WatchLogic.clock(32 * 60_000L + 10_000))
        assertEquals("1:05:09", WatchLogic.clock(3_600_000L + 5 * 60_000L + 9_000))
    }

    @Test
    fun `quanto falta`() {
        assertEquals("Restam 12 min", WatchLogic.remainingText(48 * 60_000L, 60 * 60_000L))
        assertEquals("Restam 13 min", WatchLogic.remainingText(47 * 60_000L + 1, 60 * 60_000L))   // arredonda para cima
        assertEquals("Resta menos de 1 min", WatchLogic.remainingText(59 * 60_000L + 40_000, 60 * 60_000L))
        assertNull(WatchLogic.remainingText(1000, 0))
        assertNull(WatchLogic.remainingText(2000, 1000))
    }

    // ---------- filme ----------

    @Test
    fun `botao principal do filme`() {
        assertEquals("Assistir", WatchLogic.movieAction(0, 0, false).label)
        assertEquals("Continuar de 32:10", WatchLogic.movieAction(32 * 60_000L + 10_000, 7_200_000, false).label)
        assertEquals("Rever", WatchLogic.movieAction(0, 0, true).label)
        // reviu parte de um filme já visto: o ponto salvo manda no botão
        assertEquals("Continuar de 5:00", WatchLogic.movieAction(300_000, 7_200_000, true).label)
        assertEquals(WatchLogic.MovieKind.WATCH, WatchLogic.movieAction(0, 0, false).kind)
        assertEquals(WatchLogic.MovieKind.RESUME, WatchLogic.movieAction(300_000, 0, false).kind)
        assertEquals(WatchLogic.MovieKind.REWATCH, WatchLogic.movieAction(0, 0, true).kind)
    }

    // ---------- selos da grade ----------

    @Test
    fun `selo do filme na grade`() {
        assertEquals(WatchLogic.Mark(watched = true, progressPct = 0), WatchLogic.movieMark(watched = true, progressPct = 0))
        assertEquals(WatchLogic.Mark(watched = false, progressPct = 40), WatchLogic.movieMark(watched = true, progressPct = 40))
        assertEquals(WatchLogic.Mark(watched = false, progressPct = 0), WatchLogic.movieMark(watched = false, progressPct = 0))
    }

    @Test
    fun `selo da serie na grade pelos episodios vistos`() {
        assertEquals(WatchLogic.Mark(true, 0), WatchLogic.seriesMark(10, 10))
        assertEquals(WatchLogic.Mark(false, 70), WatchLogic.seriesMark(7, 10))
        assertEquals(WatchLogic.Mark(false, 0), WatchLogic.seriesMark(0, 10))
        assertEquals(WatchLogic.Mark(false, 0), WatchLogic.seriesMark(0, 0))
        assertEquals(WatchLogic.Mark(false, 1), WatchLogic.seriesMark(1, 500))   // nunca zera se algo foi visto
    }

    @Test
    fun `chave do resumo da serie`() {
        assertEquals("api:42", WatchLogic.seriesKey(42, "Qualquer"))
        assertEquals("t:dark", WatchLogic.seriesKey(0, "  DARK "))
    }
}
