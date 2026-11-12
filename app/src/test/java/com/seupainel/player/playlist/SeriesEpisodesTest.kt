package com.seupainel.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesEpisodesTest {

    private val acc = XtreamApi.Account("http://h:80", "usr", "pwd")

    private fun parse(json: String) = XtreamApi.parseSeriesEpisodes(json, acc, "Minha Série", "http://img/capa.jpg")

    @Test
    fun `episodios por temporada com titulo, sinopse, duracao e miniatura`() {
        val eps = parse(
            """{"episodes":{"1":[{"id":"501","episode_num":1,"title":"Minha Série - S01E01 - Começo","container_extension":"mkv",
            "info":{"movie_image":"http://img/e1.jpg","plot":"O início de tudo.","duration_secs":2700},"season":1}]}}""",
        )
        val e = eps.single()
        assertEquals(1, e.season)
        assertEquals(1, e.episode)
        assertEquals("http://h:80/series/usr/pwd/501.mkv", e.channel.url)
        assertEquals("Minha Série S01E01", e.channel.name)
        val info = e.info ?: error("sem info")
        assertEquals("Começo", info.title)
        assertEquals("O início de tudo.", info.plot)
        assertEquals(2700, info.durationSec)
        assertEquals("http://img/e1.jpg", info.image)
    }

    @Test
    fun `formato lista de listas e ordem por temporada e episodio`() {
        val eps = parse(
            """{"episodes":[[{"id":"2","episode_num":2,"season":1},{"id":"1","episode_num":1,"season":1}],[{"id":"3","episode_num":1}]]}""",
        )
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), eps.map { it.season to it.episode })
        assertEquals("http://h:80/series/usr/pwd/3.mp4", eps.last().channel.url)
    }

    @Test
    fun `sem dados extras o episodio continua valendo, sem info`() {
        val e = parse("""{"episodes":{"1":[{"id":"9","episode_num":3,"info":{"plot":"","movie_image":""}}]}}""").single()
        assertNull(e.info)
        assertEquals(3, e.episode)
    }

    @Test
    fun `duracao em texto quando nao vem em segundos`() {
        val e = parse("""{"episodes":{"1":[{"id":"9","episode_num":1,"info":{"duration":"00:42:30"}}]}}""").single()
        assertEquals(2550, e.info?.durationSec)
    }

    @Test
    fun `titulo que e so o nome da serie ou so o marcador some`() {
        val e1 = parse("""{"episodes":{"1":[{"id":"9","episode_num":1,"title":"Minha Série - S01E01"}]}}""").single()
        assertNull(e1.info)
        val e2 = parse("""{"episodes":{"1":[{"id":"9","episode_num":1,"title":"Episódio 1","info":{"plot":"x"}}]}}""").single()
        assertEquals("Episódio 1", e2.info?.title)
    }

    @Test
    fun `resposta sem episodios ou invalida da lista vazia`() {
        assertTrue(parse("""{"info":{}}""").isEmpty())
        assertTrue(parse("""{"episodes":[]}""").isEmpty())
    }
}
