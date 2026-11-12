package com.seupainel.player.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeCodecTest {

    private fun ep(s: Int, e: Int, leg: Boolean = false, info: SeriesGrouper.EpisodeInfo? = null) =
        SeriesGrouper.Ep(s, e, M3uParser.Channel(name = "X S%02dE%02d".format(s, e), url = "http://x/$s/$e", group = "", duration = 0), leg, info)

    @Test
    fun `ida e volta mantem tudo, inclusive os dados extras`() {
        val info = SeriesGrouper.EpisodeInfo("Começo", "Sinopse \"curta\"\ncom quebra", 2700, "http://img/e.jpg")
        val back = EpisodeCodec.decode(EpisodeCodec.encode(listOf(ep(1, 1, leg = true, info = info), ep(2, 3))))
        assertEquals(2, back.size)
        assertEquals(1, back[0].season)
        assertTrue(back[0].leg)
        assertEquals("http://x/1/1", back[0].channel.url)
        assertEquals(info, back[0].info)
        assertNull(back[1].info)
    }

    @Test
    fun `texto salvo no formato antigo, sem dados extras, ainda le`() {
        val old = """[{"s":1,"e":2,"n":"Dark S01E02","u":"http://x/2","l":false}]"""
        val e = EpisodeCodec.decode(old).single()
        assertEquals(2, e.episode)
        assertEquals("Dark S01E02", e.channel.name)
        assertNull(e.info)
    }

    @Test
    fun `sinopse enorme e cortada para o intent nao estourar`() {
        val big = "a".repeat(5000)
        val json = EpisodeCodec.encode(listOf(ep(1, 1, info = SeriesGrouper.EpisodeInfo("", big, 0, ""))))
        val plot = EpisodeCodec.decode(json).single().info?.plot.orEmpty()
        assertTrue(plot.length in 200..400)
        assertTrue(plot.endsWith("…"))
    }

    @Test
    fun `json invalido ou vazio da lista vazia`() {
        assertTrue(EpisodeCodec.decode("nao e json").isEmpty())
        assertTrue(EpisodeCodec.decode(null).isEmpty())
        assertTrue(EpisodeCodec.decode("").isEmpty())
    }
}
